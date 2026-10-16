package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ApiQueueTest {
    private fun fixture():Pair<ServerContext,Principal> {
        val ctx=Fixtures.context(Files.createTempDirectory("api-queue"))
        val u=ctx.users.create("listener","password",false)
        ctx.db.write {
            for(i in 1..3) execute("""INSERT INTO track(id,path,folder_id,parent_dir,mtime,size,title,album,album_id,artist,artist_id,album_artist,album_artist_id,duration,bitrate,suffix,content_type,created,updated,search)
                VALUES(?,?,1,'',0,10,?,'Album','album','Artist','artist','Artist','artist',60,100000,'flac','audio/flac',1,1,?)""","t$i","$i.flac","Track $i","track $i")
        }
        return ctx to Principal(u,"test",null,setOf("user:read","user:write","catalog:read","plays:write"))
    }
    private fun tag(q:JsonObject)="\"${q["revision"]!!.jsonPrimitive.content}\""
    private suspend fun HttpResponse.obj()=Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test fun `queue is shared and optimistic offline writes preserve server state`() {
        val(ctx,p)=fixture();ctx.db.use {
            val q=ctx.apiQueue
            val initial=q.getQueue(p);assertEquals(JsonPrimitive(0L),initial["entryCount"])
            q.register(p,"phone",j("name" to "Phone","canPlay" to true))
            q.register(p,"speaker",j("name" to "Speaker","canPlay" to true))
            val started=q.replace(p,j("itemIds" to listOf("t1","t2"),"play" to true),"phone",tag(initial))
            assertEquals(JsonPrimitive("phone"),started["activeDeviceId"])
            assertEquals(started,q.getQueue(p.copy(tokenId="another-device-grant")))
            val error=assertFailsWith<ApiProblem> { q.replace(p,j("itemIds" to listOf("t3")),"speaker",tag(initial)) }
            assertEquals(412,error.status);assertEquals(started,error.extra["current"])
            assertEquals(started,q.getQueue(p))
            assertEquals(428,assertFailsWith<ApiProblem> { q.update(p,j("repeat" to "all"),"phone",null) }.status)
            val moved=q.transfer(p,j("deviceId" to "speaker"),"phone",tag(started))
            assertEquals(JsonPrimitive("speaker"),moved["activeDeviceId"])
            assertEquals(412,assertFailsWith<ApiProblem> { q.transfer(p,j("deviceId" to "phone"),"phone",tag(started)) }.status)
            assertEquals(moved,q.getQueue(p))
            val other=ctx.users.create("other","password",false)
            assertEquals(JsonPrimitive(0L),q.getQueue(p.copy(user=other))["entryCount"])
        }
    }
    @Test fun `playback position never changes revision and write scope stands alone`() {
        val(ctx,p)=fixture();ctx.db.use {
            val q=ctx.apiQueue;val write=p.copy(scopes=setOf("user:write"))
            q.register(write,"phone",j("name" to "Phone","canPlay" to true))
            q.register(write,"speaker",j("name" to "Speaker","canPlay" to true))
            val state=q.replace(write,j("itemIds" to listOf("t1","t2"),"play" to true),"phone",tag(q.getQueue(p)))
            val body=j("deviceId" to "phone","entryId" to state["currentEntryId"],"state" to "playing","positionMs" to 1234,"positionAt" to iso(System.currentTimeMillis()),"volume" to .7)
            val before=ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE type='queue'") }
            val playbackRows=ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE type='playback'") }
            assertEquals(body,q.report(write,body,"phone"))
            assertEquals(playbackRows+1,ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE type='playback'") })
            assertEquals(state["revision"],q.getQueue(p)["revision"])
            assertEquals(before,ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE type='queue'") })
            val wrong=assertFailsWith<ApiProblem> { q.report(write,JsonObject(body+j("deviceId" to "speaker")),"speaker") }
            assertEquals("not-active-device",wrong.code);assertEquals(state,wrong.extra["current"])
            val handoff=q.transfer(write,j("deviceId" to "speaker","play" to false),"phone",null)
            assertNotEquals(state["revision"],handoff["revision"])
            assertTrue(q.playback(p)["positionMs"]!!.jsonPrimitive.long>=1234)
            assertEquals(JsonPrimitive("paused"),q.playback(p)["state"])
            q.update(write,j("repeat" to "all"),"speaker",tag(handoff))
        }
    }
    @Test fun `entry identity pagination scope and forgetting active device agree`() {
        val(ctx,p)=fixture();ctx.db.use {
            val q=ctx.apiQueue;q.register(p,"phone",j("name" to "Phone","canPlay" to true))
            val initial=q.replace(p,j("itemIds" to listOf("t1","t1","t2"),"play" to true),"phone",tag(q.getQueue(p)))
            val first=q.entries(p,1);val entry=first["items"]!!.jsonArray.first().jsonObject["entryId"]!!.jsonPrimitive.content
            val cursor=first["nextCursor"]!!.jsonPrimitive.content
            val all=q.entries(p)["items"]!!.jsonArray;assertNotEquals(all[0].jsonObject["entryId"],all[1].jsonObject["entryId"])
            assertTrue("track" in all[0].jsonObject)
            assertFalse("track" in q.entries(p.copy(scopes=setOf("user:read")))["items"]!!.jsonArray[0].jsonObject)
            val moved=q.move(p,entry,j("beforeEntryId" to null),"phone",tag(initial))
            assertEquals(entry,q.entries(p)["items"]!!.jsonArray.last().jsonObject["entryId"]!!.jsonPrimitive.content)
            assertEquals(409,assertFailsWith<ApiProblem> { q.entries(p,1,cursor) }.status)
            q.remove(p,entry,"phone",tag(moved));assertEquals(2,q.entries(p)["items"]!!.jsonArray.size)
            q.forget(p,"phone");assertEquals(JsonNull,q.getQueue(p)["activeDeviceId"])
            assertEquals(409,assertFailsWith<ApiProblem> { q.transfer(p,j("deviceId" to "phone"),null,null) }.status)
        }
    }
    @Test fun `queue writes and journal roll back together`() {
        val(ctx,p)=fixture();ctx.db.use {
            val q=ctx.apiQueue;val before=q.getQueue(p);val head=ctx.journal.head()
            assertFailsWith<IllegalStateException> { ctx.db.write { q.insert(p,j("itemIds" to listOf("t1")));error("rollback") } }
            assertEquals(before,q.getQueue(p));assertEquals(head,ctx.journal.head())
            val valid=q.insert(p,j("itemIds" to listOf("t1")))
            assertEquals(JsonPrimitive(1L),valid["queue"]!!.jsonObject["entryCount"])
            assertEquals("queue",ctx.db.read { string("SELECT type FROM api_journal ORDER BY seq DESC LIMIT 1") })
            assertEquals(409,assertFailsWith<ApiProblem> { q.command(p,j("id" to java.util.UUID.randomUUID().toString(),"command" to "play")) }.status)
        }
    }
    @Test fun `HTTP queue ETags sync and now playing header fallback follow contract`()=testApplication {
        val(ctx,p)=fixture();val token=ctx.apiAuth.bootstrapPersonal(p.user,p.scopes)
        application { dulcetModule(ctx) }
        val q=client.get("/dulcet/v1/me/queue") { bearerAuth(token) };assertEquals(HttpStatusCode.OK,q.status)
        val old=q.obj();assertEquals(tag(old),q.headers[HttpHeaders.ETag])
        val added=client.post("/dulcet/v1/me/queue/entries") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("itemIds" to listOf("t1")).toString()) }
        assertEquals(HttpStatusCode.OK,added.status)
        val stale=client.put("/dulcet/v1/me/queue") { bearerAuth(token);header(HttpHeaders.IfMatch,tag(old));contentType(ContentType.Application.Json);setBody(j("itemIds" to listOf("t2")).toString()) }
        assertEquals(HttpStatusCode.PreconditionFailed,stale.status);assertNotNull(stale.obj()["current"])
        val feed=client.get("/dulcet/v1/sync/changes?start=snapshot&types=queue") { bearerAuth(token) }.obj()
        assertEquals(JsonPrimitive("queue"),feed["changes"]!!.jsonArray.first().jsonObject["id"])
        val playback=client.get("/dulcet/v1/me/queue/playback") { bearerAuth(token) }
        assertEquals("no-store",playback.headers[HttpHeaders.CacheControl]);assertNull(playback.headers[HttpHeaders.ETag])
        client.put("/dulcet/v1/me/now-playing") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("deviceId" to "phone","trackId" to "t1","state" to "playing","positionMs" to 0).toString()) }
        assertEquals(HttpStatusCode.NoContent,client.delete("/dulcet/v1/me/now-playing") { bearerAuth(token);header("Dulcet-Device-Id","phone") }.status)
        assertEquals(HttpStatusCode.BadRequest,client.delete("/dulcet/v1/me/now-playing") { bearerAuth(token) }.status)
        ctx.db.close()
    }
}
