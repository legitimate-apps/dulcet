package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.auth.SlowHash
import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class GrantSecurityTest {
    private fun context() = Fixtures.context(Files.createTempDirectory("grant-security"))
    private fun form(vararg values:Pair<String,String>) = FormDataContent(Parameters.build { values.forEach { (k,v)->append(k,v) } })
    @Test fun `device consent cannot be approved without first receiving its cookie`() = testApplication {
        val ctx=context();ctx.users.create("owner","password-long",false)
        application { routing { ctx.apiAuth.installOAuth(this) } }
        val codes=Json.parseToJsonElement(client.post("/oauth/device_authorization") { setBody(form("client_id" to "https://invalid.example/client.json","scope" to "user:read")) }.bodyAsText()).jsonObject
        val code=codes["user_code"]!!.jsonPrimitive.content
        assertNull(ctx.db.read { string("SELECT browser_hash FROM api_authorization WHERE user_code=?",code) })
        val denied=client.post("/device") { setBody(form("user_code" to code,"approve" to "yes","username" to "owner","password" to "password-long")) }
        assertEquals(HttpStatusCode.BadRequest,denied.status)
        assertNull(ctx.db.read { string("SELECT user_id FROM api_authorization WHERE user_code=?",code) })
        val page=client.get("/device") { parameter("user_code",code) }
        val cookie=page.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val wrong=client.post("/device") { header(HttpHeaders.Cookie,"dulcet_consent=wrong");setBody(form("user_code" to code,"approve" to "yes","username" to "owner","password" to "password-long")) }
        assertEquals(HttpStatusCode.BadRequest,wrong.status)
        val approved=client.post("/device") { header(HttpHeaders.Cookie,cookie);setBody(form("user_code" to code,"approve" to "yes","username" to "owner","password" to "password-long")) }
        assertEquals(HttpStatusCode.OK,approved.status)
        ctx.db.close()
    }
    @Test fun `consumed browser consent cannot be approved again without a cookie`() = testApplication {
        val ctx=context();ctx.users.create("owner","password-long",false)
        application { routing { ctx.apiAuth.installOAuth(this) } }
        val browser=createClient { followRedirects=false }
        val start=browser.get("/oauth/authorize") { parameter("client_id","https://invalid.example/client.json");parameter("redirect_uri","com.example.player:/oauth/callback");parameter("scope","user:read");parameter("response_type","code");parameter("state","state");parameter("code_challenge","a".repeat(43));parameter("code_challenge_method","S256") }
        val request=Regex("name=\"request\" value=\"([^\"]+)\"").find(start.bodyAsText())!!.groupValues[1]
        val approved=browser.post("/oauth/authorize") { header(HttpHeaders.Cookie,start.headers[HttpHeaders.SetCookie]!!.substringBefore(';'));setBody(form("request" to request,"approve" to "yes","username" to "owner","password" to "password-long")) }
        assertEquals(HttpStatusCode.Found,approved.status)
        val code=java.net.URLDecoder.decode(approved.headers[HttpHeaders.Location]!!.substringAfter("code=").substringBefore('&'),Charsets.UTF_8)
        val again=browser.post("/oauth/authorize") { setBody(form("request" to code.substringBefore('.'),"approve" to "yes","username" to "owner","password" to "password-long")) }
        assertEquals(HttpStatusCode.BadRequest,again.status)
        assertEquals(1L,ctx.db.read { long("SELECT COUNT(*) FROM api_authorization WHERE id=?",code.substringBefore('.')) })
        ctx.db.close()
    }
    private class Receiver : WebhookTransport {
        var calls=0
        var response=WebhookResult(204)
        override fun validate(url:String) {}
        override fun cancel(url:String) {}
        override fun send(url:String,body:ByteArray,headers:Map<String,String>,permitted:()->Boolean):WebhookResult { if(permitted()) calls++;return response }
    }
    @Test fun `narrowed webhook grant cannot enqueue or retry former readable events`() {
        val ctx=context();val user=ctx.users.create("owner","password-long",false)
        val p=ctx.apiAuth.authenticate("Bearer "+ctx.apiAuth.bootstrapPersonal(user,setOf("account","user:read")))!!
        val receiver=Receiver();var time=System.currentTimeMillis()
        val hooks=ApiWebhooks(ctx,clock={time},transport=receiver)
        val id=hooks.create(p,j("url" to "https://receiver.example/events","events" to listOf("play.recorded")))["id"]!!.jsonPrimitive.content
        hooks.enqueue("play.recorded",user.id,j("id" to "private-play"));receiver.response=WebhookResult(500,"receiver-rejected");hooks.maintenance()
        assertEquals(1,receiver.calls)
        ctx.db.write { execute("UPDATE api_grant SET scopes='account' WHERE id=?",p.tokenId) }
        assertTrue(ctx.apiAuth.isActive(p.tokenId),"The creating grant survives with narrower scopes")
        time+=60001;hooks.maintenance()
        assertEquals(1,receiver.calls,"An already staged retry must lose the removed scope")
        hooks.enqueue("play.recorded",user.id,j("id" to "second-private-play"));hooks.maintenance()
        assertEquals(1,receiver.calls,"New deliveries must also use current grant scopes")
        assertEquals(0L,ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_delivery WHERE hook_id=?",id) })
        assertEquals(JsonPrimitive(true),hooks.get(p,id)["active"],"Scope narrowing does not revoke the subscription")
        ctx.db.close()
    }
    @Test fun `refresh narrowing filters an existing SSE stream before its next event`() {
        val ctx=context();val user=ctx.users.create("owner","password-long",false)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read","user:read","offline_access"))
        val p=ctx.apiAuth.authenticate("Bearer $token")!!
        val clientId="https://invalid.example/client.json";val selector=UUID.randomUUID().toString();val refresh="dulcet_rt_$selector."+"a".repeat(43)
        ctx.db.write { execute("UPDATE api_grant SET kind='oauth',client_id=? WHERE id=?",clientId,p.tokenId);execute("INSERT INTO api_credential(id,grant_id,kind,secret_hash,expires) VALUES(?,?,'refresh',?,?)",selector,p.tokenId,SlowHash.hash(refresh),System.currentTimeMillis()+86400000) }
        val server=io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO,host="127.0.0.1",port=0) { dulcetModule(ctx) }.start(wait=false)
        val port=kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port };val http=java.net.http.HttpClient.newHttpClient()
        val url="http://127.0.0.1:$port";var body:java.io.InputStream?=null
        fun next(reader:java.io.BufferedReader):String=java.util.concurrent.CompletableFuture.supplyAsync {
            var type="";while(true) { val line=reader.readLine() ?: error("Unexpected stream close");if(line.startsWith("event: ")) type=line.removePrefix("event: ");if(line.isEmpty() && type.isNotEmpty())return@supplyAsync type };@Suppress("UNREACHABLE_CODE") ""
        }.get(5,java.util.concurrent.TimeUnit.SECONDS)
        try {
            val response=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI("$url/dulcet/v1/events?types=queue,changes")).header("Authorization","Bearer $token").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            assertEquals(200,response.statusCode());body=response.body();val reader=body!!.bufferedReader();assertEquals("heartbeat",next(reader))
            val narrowed=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI("$url/oauth/token")).header("Content-Type","application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString("grant_type=refresh_token&client_id=$clientId&refresh_token=$refresh&scope=catalog%3Aread+offline_access")).build(),java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals(200,narrowed.statusCode());assertEquals(setOf("catalog:read","offline_access"),ctx.apiAuth.authenticate("Bearer $token")!!.scopes)
            ctx.apiEvents.publish(user.id,"queue",j("revision" to "r_hidden"),setOf("user:read"))
            ctx.apiEvents.publish(user.id,"changes",j("types" to listOf("track")),setOf("catalog:read"))
            assertEquals("changes",next(reader),"The stream keeps its catalog subscription but loses user data immediately")
        } finally { body?.close();server.stop(100,1000);ctx.db.close() }
    }
}
