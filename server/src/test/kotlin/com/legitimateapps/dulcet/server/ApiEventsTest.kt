package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ApiEventsTest {
    private fun context():ServerContext {
        val dir=Files.createTempDirectory("api-events")
        val config=Config(dir.resolve("music").also(Files::createDirectories),dir.resolve("data").also(Files::createDirectories),0,"127.0.0.1",Fixtures.ffmpeg,Fixtures.ffprobe,null,scanOnStart=false,testMode=true)
        return ServerContext(config,Db.open(config.dataFolder.resolve("dulcet.db")))
    }
    @Test fun `events dispatch only after successful outer commit`() {
        val ctx=context();val seen=mutableListOf<ApiEvents.Event>()
        val subscription=ctx.apiEvents.subscribe { seen.add(it) }
        ctx.db.write {
            ctx.apiEvents.publish("reader","queue",j("revision" to "r_1","activeDeviceId" to null,"changed" to listOf("entries")),setOf("user:read"))
            assertTrue(seen.isEmpty())
        }
        assertEquals(1,seen.size)
        assertEquals("reader",seen.single().userId)
        assertEquals(setOf("user:read"),seen.single().scopes)
        assertTrue(seen.single().id.startsWith("ev_${ctx.journal.instanceId}_"))
        assertFailsWith<IllegalStateException> {
            ctx.db.write {
                ctx.apiEvents.publish("reader","queue",j(),setOf("user:read"))
                error("roll back")
            }
        }
        ctx.db.write { execute("INSERT INTO meta VALUES('unrelated','1')") }
        assertEquals(1,seen.size)
        assertEquals(1L,ctx.db.read { long("SELECT COUNT(*) FROM api_event") })
        subscription.close();ctx.apiEvents.publish("reader","queue",j(),setOf("user:read"));assertEquals(1,seen.size)
        ctx.db.close()
    }
    @Test fun `journal translates real mutations and preserves owner and event schemas`()=testApplication {
        val ctx=context();val user=ctx.users.create("listener","password",false)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:read","user:write"))
        val seen=mutableListOf<ApiEvents.Event>();ctx.apiEvents.subscribe { seen.add(it) }
        application { dulcetModule(ctx) }
        val response=client.post("/dulcet/v1/playlists") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("name" to "Journal event").toString()) }
        assertEquals(HttpStatusCode.Created,response.status)
        val changes=seen.first { it.type=="changes" && it.data["types"]!!.jsonArray.any { value -> value==JsonPrimitive("playlist") } }
        assertEquals(user.id,changes.userId);assertEquals(setOf("user:read"),changes.scopes)
        assertTrue(changes.data["cursor"]!!.jsonPrimitive.content.startsWith("sc_"))
        assertEquals(listOf("playlist"),changes.data["types"]!!.jsonArray.map { it.jsonPrimitive.content })
        val playlist=seen.first { it.type=="playlist.changed" }
        assertEquals("Journal event",playlist.data["name"]!!.jsonPrimitive.content)
        assertEquals(user.id,playlist.data["owner"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val playlistId=playlist.data["id"]!!.jsonPrimitive.content
        val failed=java.util.concurrent.atomic.AtomicBoolean(true)
        ctx.db.beforeCommit { if(it.string("SELECT name FROM playlist WHERE id=?",playlistId)=="Rolled back" && failed.compareAndSet(true,false)) error("Fail after event staging") }
        val count=seen.size
        assertFailsWith<IllegalStateException> { ctx.db.write { execute("UPDATE playlist SET name='Rolled back' WHERE id=?",playlistId) } }
        assertEquals(count,seen.size)
        ctx.db.write { execute("UPDATE playlist SET name='Committed update' WHERE id=?",playlistId) }
        assertEquals("Committed update",seen.last { it.type=="playlist.changed" }.data["name"]!!.jsonPrimitive.content)
        ctx.db.close()
    }
    @Test fun `event types require readable scopes before stream opens`()=testApplication {
        val ctx=context();val user=ctx.users.create("listener","password",false)
        val catalog=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"));val write=ctx.apiAuth.bootstrapPersonal(user,setOf("user:write"))
        application { dulcetModule(ctx) }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/dulcet/v1/events").status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/dulcet/v1/events?types=queue") { bearerAuth(catalog) }.status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/dulcet/v1/events?types=heartbeat") { bearerAuth(write) }.status)
        assertEquals(HttpStatusCode.BadRequest,client.get("/dulcet/v1/events?types=unknown") { bearerAuth(catalog) }.status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/dulcet/v1/events?types=scan") { bearerAuth(catalog) }.status)
        ctx.db.close()
    }
    @Test fun `real SSE isolates users resumes and closes after revocation`() {
        val ctx=context();val a=ctx.users.create("device-a","password",false);val b=ctx.users.create("device-b","password",false)
        var token=ctx.apiAuth.bootstrapPersonal(a,setOf("user:read"))
        val principal=ctx.apiAuth.authenticate("Bearer $token")!!
        val server=io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO,host="127.0.0.1",port=0) { dulcetModule(ctx) }.start(wait=false)
        val port=kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }
        val http=java.net.http.HttpClient.newHttpClient()
        var body:java.io.InputStream?=null
        fun stream(last:String?=null):java.io.BufferedReader {
            val request=java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/dulcet/v1/events?types=queue"))
                .header("Authorization","Bearer $token").GET()
            if(last!=null) request.header("Last-Event-ID",last)
            val response=http.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"))
            body=response.body();return body!!.bufferedReader()
        }
        fun next(reader:java.io.BufferedReader,wanted:String):Pair<String?,JsonObject> = java.util.concurrent.CompletableFuture.supplyAsync {
            var id:String?=null;var kind="";var data=j()
            while(true) {
                val line=reader.readLine() ?: error("Stream closed before $wanted")
                when { line.startsWith("id: ")->id=line.removePrefix("id: ");line.startsWith("event: ")->kind=line.removePrefix("event: ");line.startsWith("data: ")->data=Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject;line.isEmpty() && kind==wanted->return@supplyAsync id to data }
            }
            @Suppress("UNREACHABLE_CODE") (null to j())
        }.get(5,java.util.concurrent.TimeUnit.SECONDS)
        try {
            val reader=stream()
            assertEquals(j(),next(reader,"heartbeat").second)
            ctx.apiEvents.publish(b.id,"queue",j("revision" to "r_hidden","activeDeviceId" to null,"changed" to listOf("entries")),setOf("user:read"))
            ctx.apiEvents.publish(a.id,"queue",j("revision" to "r_seen","activeDeviceId" to null,"changed" to listOf("entries")),setOf("user:read"))
            val first=next(reader,"queue");assertEquals("r_seen",first.second["revision"]!!.jsonPrimitive.content)
            body!!.close()
            ctx.apiEvents.publish(a.id,"queue",j("revision" to "r_resume","activeDeviceId" to null,"changed" to listOf("current")),setOf("user:read"))
            val resumed=stream(first.first)
            assertEquals("r_resume",next(resumed,"queue").second["revision"]!!.jsonPrimitive.content)
            ctx.db.write { execute("UPDATE api_grant SET revoked=1 WHERE id=?",principal.tokenId) }
            assertNotNull(next(resumed,"revoked").second["reason"])
            assertNull(java.util.concurrent.CompletableFuture.supplyAsync { resumed.readLine() }.get(5,java.util.concurrent.TimeUnit.SECONDS))
            body!!.close()
            token=ctx.apiAuth.bootstrapPersonal(a,setOf("user:read"))
            val live=ctx.apiAuth.authenticate("Bearer $token")!!
            val expiring=stream();assertEquals(j(),next(expiring,"heartbeat").second)
            ctx.db.write { execute("UPDATE api_credential SET expires=? WHERE id=?",System.currentTimeMillis()-1,live.credentialId) }
            assertTrue(ctx.apiAuth.isActive(live.tokenId),"Grant remains active when one credential expires")
            assertFalse(ctx.apiAuth.isActive(live))
            assertNotNull(next(expiring,"revoked").second["reason"])
            assertNull(java.util.concurrent.CompletableFuture.supplyAsync { expiring.readLine() }.get(5,java.util.concurrent.TimeUnit.SECONDS))
        } finally { body?.close();server.stop(100,1000);ctx.db.close() }
    }
    @Test fun `commands relay only to active stream and accepted UUID retries emit once`() {
        val ctx=context();val user=ctx.users.create("controller","password",false)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:read","user:write"))
        val p=ctx.apiAuth.authenticate("Bearer $token")!!
        ctx.apiQueue.register(p,"phone",j("name" to "Phone","canPlay" to true))
        ctx.apiQueue.register(p,"speaker",j("name" to "Speaker","canPlay" to true))
        ctx.apiQueue.transfer(p,j("deviceId" to "speaker","play" to false),"phone",null)
        val server=io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO,host="127.0.0.1",port=0) { dulcetModule(ctx) }.start(wait=false)
        val port=kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }
        val http=java.net.http.HttpClient.newHttpClient()
        val bodies=mutableListOf<java.net.Socket>()
        fun open(device:String):java.io.BufferedReader {
            val socket=java.net.Socket("127.0.0.1",port);socket.soTimeout=5000;bodies.add(socket)
            socket.getOutputStream().write(("GET /dulcet/v1/events?types=command,queue HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\nDulcet-Device-Id: $device\r\nConnection: close\r\n\r\n").toByteArray())
            socket.getOutputStream().flush()
            val input=java.io.BufferedInputStream(socket.getInputStream())
            fun line():String { val bytes=java.io.ByteArrayOutputStream();while(true) { val c=input.read();if(c<0)error("Unexpected HTTP EOF");if(c==10)break;bytes.write(c) };return bytes.toString(Charsets.US_ASCII).trimEnd('\r') }
            assertTrue(line().startsWith("HTTP/1.1 200"))
            val headers=generateSequence { line().takeIf(String::isNotEmpty) }.toList()
            assertTrue(headers.any { it.startsWith("Content-Type: text/event-stream",true) })
            assertTrue(headers.any { it.equals("Transfer-Encoding: chunked",true) })
            val chunks=object:java.io.InputStream() {
                var remaining=0;var finished=false;var first=true
                private fun chunk():Boolean {
                    if(finished)return false
                    if(remaining==0) {
                        if(!first)assertEquals("",line());first=false
                        remaining=line().substringBefore(';').toInt(16)
                        if(remaining==0) { finished=true;return false }
                    }
                    return true
                }
                override fun read():Int { if(!chunk())return -1;remaining--;return input.read() }
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    if(length==0)return 0
                    if(!chunk())return -1
                    val count=input.read(buffer,offset,minOf(length,remaining))
                    if(count>0)remaining-=count
                    return count
                }
            }
            return chunks.bufferedReader()
        }
        fun next(reader:java.io.BufferedReader):Pair<String,JsonObject> = java.util.concurrent.CompletableFuture.supplyAsync {
            var type="";var data=j()
            while(true) {
                val line=reader.readLine() ?: error("Unexpected stream close")
                when { line.startsWith("event: ")->type=line.removePrefix("event: ");line.startsWith("data: ")->data=Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject;line.isEmpty() && type.isNotEmpty()->return@supplyAsync type to data }
            }
            @Suppress("UNREACHABLE_CODE") ("" to j())
        }.get(5,java.util.concurrent.TimeUnit.SECONDS)
        fun command(id:String):java.net.http.HttpResponse<String> = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/dulcet/v1/me/queue/commands"))
            .header("Authorization","Bearer $token").header("Dulcet-Device-Id","phone").header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(j("id" to id,"command" to "pause").toString())).build(),java.net.http.HttpResponse.BodyHandlers.ofString())
        try {
            val phone=open("phone");val speaker=open("speaker")
            assertEquals("heartbeat",next(phone).first);assertEquals("heartbeat",next(speaker).first)
            val first=java.util.UUID.randomUUID().toString();assertEquals(202,command(first).statusCode())
            val received=next(speaker);assertEquals("command",received.first)
            assertEquals(first,received.second["id"]!!.jsonPrimitive.content);assertEquals("phone",received.second["from"]!!.jsonPrimitive.content)
            assertEquals(202,command(first).statusCode())
            val second=java.util.UUID.randomUUID().toString();assertEquals(202,command(second).statusCode())
            assertEquals(second,next(speaker).second["id"]!!.jsonPrimitive.content)
            ctx.apiEvents.publish(user.id,"queue",j("revision" to "r_barrier","activeDeviceId" to "speaker","changed" to listOf("mode")),setOf("user:read"),"phone")
            val onlyPhone=next(phone);assertEquals("queue",onlyPhone.first);assertEquals("r_barrier",onlyPhone.second["revision"]!!.jsonPrimitive.content)
            bodies[1].close()
            ctx.apiEvents.publish(user.id,"queue",j("revision" to "r_disconnect","activeDeviceId" to "speaker","changed" to listOf("mode")),setOf("user:read"),"speaker")
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(ctx.apiEvents.online(user.id,"speaker") && System.nanoTime()<until) {
                Thread.sleep(50)
                ctx.apiEvents.publish(user.id,"queue",j("revision" to "r_disconnect","activeDeviceId" to "speaker","changed" to listOf("mode")),setOf("user:read"),"speaker")
            }
            assertFalse(ctx.apiEvents.online(user.id,"speaker"),"Closed stream must stop receiving commands")
            assertEquals(202,command(first).statusCode(),"Accepted retry remains acknowledged after disconnect")
            val unavailable=command(java.util.UUID.randomUUID().toString());assertEquals(409,unavailable.statusCode())
            assertEquals("urn:dulcet:problem:device-unavailable",Json.parseToJsonElement(unavailable.body()).jsonObject["type"]!!.jsonPrimitive.content)
        } finally { bodies.forEach { runCatching { it.close() } };server.stop(100,1000);ctx.db.close() }
    }
    @Test fun `now playing hints recheck current sharing and administrator role`() {
        val ctx=context();val owner=ctx.users.create("owner","password",false);val viewer=ctx.users.create("viewer","password",false);val admin=ctx.users.create("admin","password",true)
        val ordinary=Principal(viewer,"",null,setOf("user:read"));val privileged=Principal(admin,"",null,setOf("user:read"))
        val hint=j("userId" to owner.id,"deviceId" to "speaker","state" to "playing","trackId" to "private-track")
        ctx.db.write { execute("INSERT INTO api_profile(user_id,settings) VALUES(?,?)",owner.id,j("shareNowPlaying" to true).toString()) }
        assertTrue(ctx.apiEvents.canReadNowPlaying(ordinary,hint))
        ctx.db.write { execute("UPDATE api_profile SET settings=? WHERE user_id=?",j("shareNowPlaying" to false).toString(),owner.id) }
        assertFalse(ctx.apiEvents.canReadNowPlaying(ordinary,hint),"A retained or queued hint loses visibility immediately when its owner unshares")
        assertTrue(ctx.apiEvents.canReadNowPlaying(privileged,hint))
        ctx.db.write { execute("UPDATE user SET is_admin=0 WHERE id=?",admin.id) }
        assertFalse(ctx.apiEvents.canReadNowPlaying(privileged,hint),"The cached principal's former administrator role grants no visibility")
        assertTrue(ctx.apiEvents.canReadNowPlaying(ordinary.copy(user=owner),hint))
        ctx.db.close()
    }
    @Test fun `bounded hint history does not retain an unlimited queue log`() {
        val ctx=context();var now=System.currentTimeMillis()
        val service=ApiEvents(ctx) { now }
        service.publish("reader","queue",j("revision" to "r_1","activeDeviceId" to null,"changed" to listOf("entries")),setOf("user:read"))
        now+=86400001
        service.publish("reader","queue",j("revision" to "r_2","activeDeviceId" to null,"changed" to listOf("entries")),setOf("user:read"))
        assertEquals(1L,ctx.db.read { long("SELECT COUNT(*) FROM api_event") })
        ctx.db.close()
    }
}
