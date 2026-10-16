package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.*

class ApiWebhooksTest {
    private class Receiver : WebhookTransport {
        data class Request(val body:ByteArray,val headers:Map<String,String>)
        val requests=ArrayList<Request>()
        var response=WebhookResult(204)
        var cancelled=false
        var crashAfterSend=false
        var blocked:CountDownLatch?=null
        val started=CountDownLatch(1)
        override fun validate(url:String) { if(!url.startsWith("https://receiver.example/")) throw ApiProblem(422,"webhook-target-refused") }
        override fun send(url:String,body:ByteArray,headers:Map<String,String>,permitted:()->Boolean):WebhookResult {
            started.countDown();blocked?.await(5,TimeUnit.SECONDS)
            if(!permitted() || cancelled) return WebhookResult(null,"grant-revoked")
            requests.add(Request(body,headers));if(crashAfterSend) { crashAfterSend=false;throw AssertionError("simulated process loss after send") };return response
        }
        override fun cancel(url:String) { cancelled=true;blocked?.countDown() }
    }
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("api-webhooks")
        val config=Config(root.resolve("music").also(Files::createDirectories),root.resolve("data").also(Files::createDirectories),0,"127.0.0.1",Fixtures.ffmpeg,Fixtures.ffprobe,null,scanOnStart=false,testMode=true)
        val ctx=ServerContext(config,Db.open(config.dataFolder.resolve("dulcet.db")))
        val receiver=Receiver()
        var time=System.currentTimeMillis()
        val hooks=ApiWebhooks(ctx,clock={time},transport=receiver)
        val user=ctx.users.create("owner","password",true)
        fun principal(scopes:Set<String> = setOf("account","user:read","catalog:read","admin")):Principal=ctx.apiAuth.authenticate("Bearer "+ctx.apiAuth.bootstrapPersonal(user,scopes))!!
        fun create(p:Principal,event:String="*")=hooks.create(p,j("url" to "https://receiver.example/events","events" to listOf(event),"description" to "test receiver"))
        override fun close() { ctx.db.close();root.toFile().deleteRecursively() }
    }
    private fun JsonObject.id()=this["id"]!!.jsonPrimitive.content
    @Test fun `targets are HTTPS pinned public addresses and admin exceptions never include metadata`() {
        fun transport(address:String,private:Boolean=false,ports:Boolean=false)=WebhookHttpTransport(private,ports) { arrayOf(InetAddress.getByName(address)) }
        transport("93.184.216.34").validate("https://receiver.example/events?topic=plays")
        for(ip in listOf("127.0.0.1","10.0.0.1","192.168.1.2","172.16.0.1","100.64.1.2","169.254.169.254","::1","fc00::1","fe80::1","::ffff:10.0.0.1")) assertFailsWith<ApiProblem>(ip) { transport(ip).validate("https://public-name.example/events") }
        for(url in listOf("http://receiver.example/","https://user:pass@receiver.example/","https://receiver.example/#fragment","https://receiver.example:8443/")) assertFailsWith<ApiProblem> { transport("93.184.216.34").validate(url) }
        transport("10.0.0.1",true,true).validate("https://receiver.example:8443/")
        assertEquals("webhook-target-refused",assertFailsWith<ApiProblem> { transport("10.0.0.1",true,true).validate("https://receiver.example:65536/") }.code)
        for(ip in listOf("169.254.169.254","100.100.100.200","fe80::1","fd00:ec2::254")) assertFailsWith<ApiProblem> { transport(ip,true,true).validate("https://receiver.example:8443/") }
        val changing=WebhookHttpTransport(resolver={arrayOf(InetAddress.getByName("127.0.0.1"))})
        assertEquals("webhook-target-refused",changing.send("https://public-name.example/",byteArrayOf(),emptyMap()) { true }.error)
        var elapsed=0L
        val slow=WebhookHttpTransport(nanoClock={elapsed},resolver={elapsed=11_000_000_000;arrayOf(InetAddress.getByName("93.184.216.34"))})
        assertEquals("delivery-timeout",slow.send("https://receiver.example/",byteArrayOf(),emptyMap()) { true }.error)
    }
    @Test fun `secret shown once ciphertext at rest and Standard Webhooks signs exact bytes`()=Fixture().use { f ->
        val p=f.principal();val hook=f.create(p);val id=hook.id();val secret=hook["secret"]!!.jsonPrimitive.content
        assertTrue(secret.startsWith("whsec_"));assertFalse(f.hooks.get(p,id).containsKey("secret"));assertFalse(f.hooks.list(p).toString().contains(secret))
        val stored=f.ctx.db.read { single("SELECT secret FROM api_webhook WHERE id=?",id) { it.bytes("secret")!! } }!!
        assertFalse(stored.decodeToString().contains(secret))
        val attempt=f.hooks.test(p,id);assertEquals(JsonPrimitive(204),attempt["responseStatus"])
        val request=f.receiver.requests.single();val body=Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertEquals(request.headers["webhook-id"],body.id());assertEquals(JsonPrimitive("ping"),body["type"]);assertEquals(j(),body["data"]);assertEquals(JsonPrimitive(p.user.id),body["userId"])
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(Base64.getDecoder().decode(secret.removePrefix("whsec_")),"HmacSHA256"))
        val signed="${request.headers["webhook-id"]}.${request.headers["webhook-timestamp"]}.".toByteArray()+request.body
        assertEquals("v1,"+Base64.getEncoder().encodeToString(mac.doFinal(signed)),request.headers["webhook-signature"])
        assertEquals(0,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_delivery") }.toInt())
    }
    @Test fun `retry backoff preserves delivery identity and disables persistent failures after a day`()=Fixture().use { f ->
        val p=f.principal();val id=f.create(p).id();f.receiver.response=WebhookResult(500,"receiver-rejected")
        val first=f.hooks.test(p,id);assertEquals(JsonPrimitive(500),first["responseStatus"])
        assertEquals(1,f.receiver.requests.size);f.hooks.maintenance();assertEquals(1,f.receiver.requests.size)
        f.time+=60_000;f.hooks.maintenance();assertEquals(2,f.receiver.requests.size)
        assertContentEquals(f.receiver.requests[0].body,f.receiver.requests[1].body)
        assertEquals(f.receiver.requests[0].headers["webhook-id"],f.receiver.requests[1].headers["webhook-id"])
        assertNotEquals(f.receiver.requests[0].headers["webhook-timestamp"],f.receiver.requests[1].headers["webhook-timestamp"])
        f.time+=86_400_000;f.hooks.maintenance();val disabled=f.hooks.get(p,id)
        assertEquals(JsonPrimitive(false),disabled["active"]);assertEquals(JsonPrimitive("delivery-failures"),disabled["disabledReason"])
        assertEquals(JsonPrimitive(3),disabled["consecutiveFailures"])
        assertEquals(0,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_delivery") }.toInt())
        assertEquals(JsonPrimitive(true),f.hooks.update(p,id,j("active" to true))["active"])
        assertEquals(JsonNull,f.hooks.get(p,id)["disabledReason"])
        f.receiver.response=WebhookResult(204);f.hooks.test(p,id);assertEquals(JsonPrimitive(0),f.hooks.get(p,id)["consecutiveFailures"])
    }
    @Test fun `creating scopes bound subscriptions and recipient state never reaches another user`()=Fixture().use { f ->
        val p=f.principal(setOf("account"));val id=f.create(p).id()
        val refused=assertFailsWith<ApiProblem> { f.create(p,"play.recorded") }
        assertEquals(403,refused.status);assertEquals(json(listOf("play.recorded")),refused.extra["events"]);assertEquals(json(listOf("user:read")),refused.extra["requiredScopes"])
        assertEquals(403,assertFailsWith<ApiProblem> { f.hooks.update(p,id,j("events" to listOf("catalog.changed"))) }.status)
        f.hooks.enqueue("play.recorded",p.user.id,j("id" to "private-play"));f.hooks.maintenance();assertTrue(f.receiver.requests.isEmpty())
        val q=f.principal();val full=f.create(q,"play.recorded").id()
        f.hooks.enqueue("play.recorded","other-user",j("id" to "other-play"));f.hooks.maintenance();assertTrue(f.receiver.requests.isEmpty())
        val event=j("id" to "own-play","trackId" to "trk_test","playedAt" to iso(f.time),"positionMs" to 10)
        f.hooks.enqueue("play.recorded",p.user.id,event,eventKey="dedupe");f.hooks.enqueue("play.recorded",p.user.id,event,eventKey="dedupe");f.hooks.maintenance()
        assertEquals(1,f.receiver.requests.size);assertEquals(event,Json.parseToJsonElement(f.receiver.requests.single().body.decodeToString()).jsonObject["data"])
        val other=f.ctx.users.create("other","password",false);val op=f.ctx.apiAuth.authenticate("Bearer "+f.ctx.apiAuth.bootstrapPersonal(other,setOf("account")))!!
        assertEquals(404,assertFailsWith<ApiProblem> { f.hooks.get(op,full) }.status)
        assertEquals(404,assertFailsWith<ApiProblem> { f.hooks.remove(op,full) }.status)
    }
    @Test fun `revocation deletes hook and durable pending deliveries and cancels in flight`()=Fixture().use { f ->
        val p=f.principal();val id=f.create(p).id();f.hooks.enqueue("play.recorded",p.user.id,j("id" to "play"))
        f.receiver.blocked=CountDownLatch(1)
        val worker=thread { f.hooks.maintenance() };assertTrue(f.receiver.started.await(5,TimeUnit.SECONDS))
        f.ctx.db.write { execute("UPDATE api_grant SET revoked=1 WHERE id=?",p.tokenId) }
        worker.join(5000);assertFalse(worker.isAlive);assertTrue(f.receiver.cancelled);assertTrue(f.receiver.requests.isEmpty())
        assertEquals(0,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook WHERE id=?",id) }.toInt())
        assertEquals(0,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_delivery") }.toInt())
    }
    @Test fun `committed event bus delivery matches type and queues rollback nothing`()=Fixture().use { f ->
        val p=f.principal();f.create(p,"queue.changed")
        val state=j("revision" to "revision1","activeDeviceId" to "device1","changed" to listOf("entries"))
        assertFailsWith<IllegalStateException> { f.ctx.db.write { f.ctx.apiEvents.publish(p.user.id,"queue",state,setOf("user:read"));error("rollback") } }
        f.hooks.maintenance();assertTrue(f.receiver.requests.isEmpty())
        f.ctx.apiEvents.publish(p.user.id,"queue",state,setOf("user:read"))
        assertEquals(1L,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_delivery") },"committed event must stage durable delivery")
        // The context's production outbox observer uses wall time; this injected worker's clock
        // must reach that committed attempt time before it can process the delivery.
        f.time=System.currentTimeMillis()
        f.hooks.maintenance()
        val envelope=Json.parseToJsonElement(f.receiver.requests.single().body.decodeToString()).jsonObject
        assertEquals(JsonPrimitive("queue.changed"),envelope["type"]);assertEquals(state,envelope["data"])
    }
    @Test fun `ping idempotency returns first attempt across success failure and request conflicts`()=Fixture().use { f ->
        val p=f.principal();val id=f.create(p).id();val key=java.util.UUID.randomUUID().toString()
        val result=f.hooks.test(p,id,key)
        assertEquals(result,f.hooks.test(p,id,key));assertEquals(1,f.receiver.requests.size)
        val other=f.create(p).id()
        assertEquals(422,assertFailsWith<ApiProblem> { f.hooks.test(p,other,key) }.status)
        assertEquals(422,assertFailsWith<ApiProblem> { f.hooks.test(f.principal(),id,key) }.status)
        assertEquals(400,assertFailsWith<ApiProblem> { f.hooks.test(p,id,"not-a-uuid") }.status)
        val usedElsewhere=java.util.UUID.randomUUID().toString()
        f.ctx.db.write { execute("INSERT INTO api_idempotency VALUES(?,?,?,201,'cached','{}',?)",p.user.id,usedElsewhere,"another-operation",f.time) }
        assertEquals(422,assertFailsWith<ApiProblem> { f.hooks.test(p,id,usedElsewhere) }.status)
        val failureKey=java.util.UUID.randomUUID().toString();f.receiver.response=WebhookResult(500,"receiver-rejected")
        val failure=f.hooks.test(p,id,failureKey)
        assertEquals(JsonPrimitive(500),failure["responseStatus"]);assertEquals(failure,f.hooks.test(p,id,failureKey));assertEquals(2,f.receiver.requests.size)
        f.receiver.response=WebhookResult(204);f.time+=60_000;f.hooks.maintenance();assertEquals(3,f.receiver.requests.size)
        assertEquals(failure,f.hooks.test(p,id,failureKey));assertEquals(3,f.receiver.requests.size)
    }
    @Test fun `pending ping request survives interruption with its original delivery identity`()=Fixture().use { f ->
        val p=f.principal();val id=f.create(p).id();val key=java.util.UUID.randomUUID().toString()
        f.receiver.crashAfterSend=true
        assertFailsWith<AssertionError> { f.hooks.test(p,id,key) }
        assertEquals(1L,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_request WHERE result IS NULL") })
        assertEquals(1L,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_delivery") })
        val restoredWorker=ApiWebhooks(f.ctx,clock={f.time},transport=f.receiver)
        val result=restoredWorker.test(p,id,key)
        assertEquals(2,f.receiver.requests.size)
        assertEquals(f.receiver.requests[0].headers["webhook-id"],f.receiver.requests[1].headers["webhook-id"])
        assertEquals(result.id(),f.receiver.requests[0].headers["webhook-id"])
        assertEquals(result,restoredWorker.test(p,id,key));assertEquals(2,f.receiver.requests.size)
        assertEquals(0L,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_delivery") })
    }
    @Test fun `webhook CRUD journals user state in the same transaction and rollback preserves neither`()=Fixture().use { f ->
        val p=f.principal();val before=f.ctx.journal.head();val id=f.create(p).id()
        f.hooks.update(p,id,j("description" to "changed"));f.hooks.remove(p,id)
        val changes=f.ctx.db.read { query("SELECT user_id,op FROM api_journal WHERE seq>? AND type='webhook' AND id=? ORDER BY seq",before,id) { it.string("user_id") to it.string("op") } }
        assertEquals(listOf(p.user.id to "upsert",p.user.id to "upsert",p.user.id to "delete"),changes)
        val rollbackHead=f.ctx.journal.head()
        assertFailsWith<IllegalStateException> { f.ctx.db.write { f.create(p);error("rollback") } }
        assertEquals(0L,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook") })
        assertEquals(0L,f.ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='webhook'",rollbackHead) })
    }
    @Test fun `Testing override permits a real signed HTTP receiver and resets target retry and disable policy`()=Fixture().use { f ->
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val bodies=java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        server.createContext("/events") { exchange ->
            val body=Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            assertEquals(body.id(),exchange.requestHeaders.getFirst("webhook-id"));assertTrue(exchange.requestHeaders.getFirst("webhook-signature").startsWith("v1,"))
            bodies.add(body);exchange.sendResponseHeaders(500,-1);exchange.close()
        }
        server.start()
        try {
            val p=f.principal();val api=f.ctx.apiWebhooks;val url="http://127.0.0.1:${server.address.port}/events"
            assertEquals("webhook-target-refused",assertFailsWith<ApiProblem> { api.create(p,j("url" to url,"events" to listOf("ping"))) }.code)
            f.ctx.testing.replace(j("webhooks" to j("allowPrivateTargets" to true,"retryScheduleSeconds" to listOf(0,0),"disableAfterFailures" to 3)))
            val hook=api.create(p,j("url" to url,"events" to listOf("ping")));val id=hook.id()
            val first=api.test(p,id);assertEquals(JsonPrimitive(500),first["responseStatus"])
            api.maintenance();api.maintenance()
            assertEquals(3,bodies.size);assertEquals(1,bodies.map { it.id() }.distinct().size)
            assertEquals(JsonPrimitive(false),api.get(p,id)["active"]);assertEquals(JsonPrimitive("delivery-failures"),api.get(p,id)["disabledReason"])
            api.update(p,id,j("active" to true));api.update(p,id,j("active" to false))
            assertEquals(JsonPrimitive("admin"),api.get(p,id)["disabledReason"])
            f.ctx.testing.replace(j())
            assertEquals("webhook-target-refused",assertFailsWith<ApiProblem> { api.update(p,id,j("active" to true)) }.code)
            assertEquals("webhook-target-refused",assertFailsWith<ApiProblem> { api.create(p,j("url" to url,"events" to listOf("ping"))) }.code)
            val defaultHook=f.create(p).id();f.receiver.response=WebhookResult(500,"receiver-rejected")
            f.hooks.test(p,defaultHook);f.hooks.maintenance()
            assertEquals(1,f.receiver.requests.size);assertEquals(JsonPrimitive(true),f.hooks.get(p,defaultHook)["active"])
            val neverMetadata=WebhookHttpTransport(testingOverride={true},resolver={arrayOf(InetAddress.getByName("169.254.169.254"))})
            assertEquals("webhook-target-refused",assertFailsWith<ApiProblem> { neverMetadata.validate("http://receiver.example:8080/") }.code)
        } finally { server.stop(0) }
    }
    @Test fun `Testing retry list exhausts without disabling unless the consecutive threshold is reached`()=Fixture().use { f ->
        f.ctx.testing.replace(j("webhooks" to j("retryScheduleSeconds" to listOf(1,2))))
        val p=f.principal();val id=f.create(p).id();f.receiver.response=WebhookResult(500,"receiver-rejected")
        f.hooks.test(p,id);f.time+=999;f.hooks.maintenance();assertEquals(1,f.receiver.requests.size)
        f.time+=1;f.hooks.maintenance();assertEquals(2,f.receiver.requests.size)
        f.time+=1999;f.hooks.maintenance();assertEquals(2,f.receiver.requests.size)
        f.time+=1;f.hooks.maintenance();assertEquals(3,f.receiver.requests.size)
        assertEquals(0L,f.ctx.db.read { long("SELECT COUNT(*) FROM api_webhook_delivery") });assertEquals(JsonPrimitive(true),f.hooks.get(p,id)["active"])
        f.ctx.testing.replace(j("webhooks" to j("retryScheduleSeconds" to emptyList<Int>(),"disableAfterFailures" to 1)))
        f.hooks.test(p,id);assertEquals(JsonPrimitive(false),f.hooks.get(p,id)["active"]);assertEquals(JsonPrimitive("delivery-failures"),f.hooks.get(p,id)["disabledReason"])
    }
    @Test fun `HTTP requires account scope refuses private targets and isolates webhook ids`()=testApplication {
        val f=Fixture();val p=f.principal();val token=f.ctx.apiAuth.bootstrapPersonal(p.user,setOf("account"));val reader=f.ctx.apiAuth.bootstrapPersonal(p.user,setOf("user:read"))
        application { dulcetModule(f.ctx) }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/dulcet/v1/webhooks").status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/dulcet/v1/webhooks") { bearerAuth(reader) }.status)
        val denied=client.post("/dulcet/v1/webhooks") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("url" to "https://127.0.0.1/","events" to listOf("ping")).toString()) }
        assertEquals(HttpStatusCode.UnprocessableEntity,denied.status)
        assertEquals(HttpStatusCode.NotFound,client.get("/dulcet/v1/webhooks/unknown") { bearerAuth(token) }.status)
        f.close()
    }
}
