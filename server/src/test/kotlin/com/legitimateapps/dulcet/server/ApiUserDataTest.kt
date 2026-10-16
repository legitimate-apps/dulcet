package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.*
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import java.util.UUID
import io.ktor.server.testing.testApplication
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*

class ApiUserDataTest {
    private fun fixture():Pair<ServerContext,Principal> {
        val ctx=Fixtures.context(createTempDirectory("dulcet-user-data"))
        val user=ctx.users.create("listener","a-long-test-password",false)
        ctx.db.write {
            execute("INSERT INTO artist(id,name,sort_name,album_count,song_count,search) VALUES('artist','Artist','Artist',0,1,'artist')")
            execute("""INSERT INTO track(id,path,folder_id,parent_dir,mtime,size,title,album,album_id,artist,artist_id,album_artist,album_artist_id,duration,bitrate,suffix,content_type,created,updated,search)
                VALUES('track','song.flac',1,'',0,100,'Song','Album','album','Artist','artist','Artist','artist',300,100000,'flac','audio/flac',1,1,'song')""")
        }
        return ctx to Principal(user,"test",null,setOf("user:read","user:write","user:delete","plays:write","catalog:read"))
    }
    @Test fun stateUsesPerFieldActionClocksAndKeepsOriginalDates() {
        val (ctx,p)=fixture(); ctx.db.use {
            val data=ctx.apiUserData
            data.change(p,"track","favorite",null,"2026-10-12T10:00:00Z")
            val first=data.itemState("track",p)["favoritedAt"]
            data.change(p,"track","favorite",null,"2026-10-12T12:00:00Z")
            assertEquals(first,data.itemState("track",p)["favoritedAt"])
            val stale=data.change(p,"track","unfavorite",null,"2026-10-12T11:00:00Z")
            assertEquals(JsonPrimitive(false),stale["applied"])
            assertEquals(first,data.itemState("track",p)["favoritedAt"])
            data.change(p,"track","libraryAdd",null,"2026-10-12T09:00:00Z")
            data.change(p,"track","libraryAdd",null,"2026-10-12T13:00:00Z")
            assertEquals(JsonPrimitive("2026-10-12T09:00:00.000Z"),data.itemState("track",p)["libraryAddedAt"])
            data.change(p,"track","libraryRemove",null,"2026-10-12T14:00:00Z")
            assertEquals(JsonNull,data.itemState("track",p)["libraryAddedAt"])
            assertEquals(JsonPrimitive(true),data.change(p,"gone","libraryRemove",null,null)["applied"])
        }
    }
    @Test fun ratingsPreserveHalfStarsAndLegacyWritesInvalidateExactValues() {
        val (ctx,p)=fixture(); ctx.db.use {
            ctx.apiUserData.change(p,"track","rate",7,null)
            assertEquals(JsonPrimitive(7),ctx.apiUserData.itemState("track",p)["rating"])
            assertEquals(4L,ctx.db.read { long("SELECT rating FROM annotation WHERE user_id=? AND item_id='track'",p.user.id) })
            ctx.db.write { execute("UPDATE annotation SET rating=4 WHERE user_id=? AND item_id='track'",p.user.id) }
            assertEquals(JsonPrimitive(8),ctx.apiUserData.itemState("track",p)["rating"])
            assertEquals(400,assertFailsWith<ApiProblem> { ctx.apiUserData.change(p,"track","rate",0,null) }.status)
            assertEquals(JsonPrimitive(false),ctx.apiUserData.change(p,"track","rate",3,"2026-10-12T10:00:00Z")["applied"])
            assertEquals(JsonPrimitive(8),ctx.apiUserData.itemState("track",p)["rating"])
            assertEquals(0L,ctx.db.read { long("SELECT COUNT(*) FROM api_state_mutation") })
        }
    }
    @Test fun playsRecordExactlyOnceAndRemainDeletedAfterRetry() {
        val (ctx,p)=fixture(); ctx.db.use {
            val id=UUID.randomUUID().toString(); val play=j("id" to id,"trackId" to "track","startedAt" to "2026-10-12T10:00:00Z","listenedMs" to 180000)
            assertEquals(JsonPrimitive("recorded"),ctx.apiUserData.recordPlay(p,play)["status"])
            assertEquals(JsonPrimitive("duplicate"),ctx.apiUserData.recordPlay(p,play)["status"])
            assertEquals(JsonPrimitive(1L),ctx.apiUserData.itemState("track",p)["playCount"])
            ctx.apiUserData.deletePlay(p,id)
            assertEquals(JsonPrimitive(0L),ctx.apiUserData.itemState("track",p)["playCount"])
            assertEquals(JsonPrimitive("duplicate"),ctx.apiUserData.recordPlay(p,play)["status"])
            assertNull(ctx.apiUserData.resource("play",id,p))
            assertEquals(0L,ctx.db.read { long("SELECT COUNT(*) FROM api_play WHERE user_id=? AND id=?",p.user.id,id) })
            assertEquals(1L,ctx.db.read { long("SELECT COUNT(*) FROM api_play_id WHERE id=?",id) })
            val other=ctx.users.create("another-listener","another-test-password",false)
            assertEquals(JsonPrimitive("duplicate"),ctx.apiUserData.recordPlay(p.copy(user=other),play)["status"])
            assertEquals("delete",ctx.db.read { string("SELECT op FROM api_journal WHERE type='play' AND id=? ORDER BY seq DESC LIMIT 1",id) })
            ctx.db.write { execute("DELETE FROM user WHERE id=?",p.user.id) }
            assertEquals(JsonPrimitive("duplicate"),ctx.apiUserData.recordPlay(p.copy(user=other),play)["status"])
        }
    }
    @Test fun deletionScopeIsSeparateAndUserStateIsIsolated() {
        val (ctx,p)=fixture(); ctx.db.use {
            val limited=p.copy(scopes=setOf("user:write"))
            ctx.apiUserData.change(limited,"track","libraryAdd",null,null)
            assertEquals(403,assertFailsWith<ApiProblem> { ctx.apiUserData.change(limited,"track","libraryRemove",null,null) }.status)
            ctx.apiUserData.change(limited,"track","unfavorite",null,null)
            val other=ctx.users.create("other","another-test-password",false)
            assertEquals(JsonNull,ctx.apiUserData.itemState("track",p.copy(user=other))["libraryAddedAt"])
        }
    }
    @Test fun playlistEntryIdsConcurrencyAndDeletionScope() = testApplication {
        val (ctx,p)=fixture()
        val secret=ctx.apiAuth.bootstrapPersonal(p.user,p.scopes)
        val writeOnly=ctx.apiAuth.bootstrapPersonal(p.user,setOf("user:write","user:read"))
        application { dulcetModule(ctx) }
        fun HttpRequestBuilder.auth(token:String=secret) { header(HttpHeaders.Authorization,"Bearer $token"); contentType(ContentType.Application.Json) }
        val created=client.post("/dulcet/v1/playlists") { auth(); setBody(j("name" to "Duplicates","itemIds" to listOf("track","track")).toString()) }
        assertEquals(HttpStatusCode.Created,created.status)
        val playlist=Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val id=playlist["id"]!!.jsonPrimitive.content
        val tag=created.headers[HttpHeaders.ETag]!!
        val first=client.get("/dulcet/v1/playlists/$id/items?limit=1") { auth() }
        val page=Json.parseToJsonElement(first.bodyAsText()).jsonObject
        val entry=page["items"]!!.jsonArray.first().jsonObject["entryId"]!!.jsonPrimitive.content
        val cursor=page["nextCursor"]!!.jsonPrimitive.content
        val missingTag=client.put("/dulcet/v1/playlists/$id/items") { auth(); setBody(j("itemIds" to emptyList<String>()).toString()) }
        assertEquals(HttpStatusCode.fromValue(428),missingTag.status)
        val forbidden=client.put("/dulcet/v1/playlists/$id/items") { auth(writeOnly); header(HttpHeaders.IfMatch,tag); setBody(j("itemIds" to listOf("track")).toString()) }
        assertEquals(HttpStatusCode.Forbidden,forbidden.status)
        val moved=client.patch("/dulcet/v1/playlists/$id/items/$entry") { auth(); header(HttpHeaders.IfMatch,tag); setBody(j("beforeEntryId" to null).toString()) }
        assertEquals(HttpStatusCode.OK,moved.status)
        assertNotEquals(tag,moved.headers[HttpHeaders.ETag])
        val stale=client.get("/dulcet/v1/playlists/$id/items?limit=1&cursor=$cursor") { auth() }
        assertEquals(HttpStatusCode.Conflict,stale.status)
        assertEquals(JsonPrimitive("urn:dulcet:problem:conflict"),Json.parseToJsonElement(stale.bodyAsText()).jsonObject["type"])
        val current=client.get("/dulcet/v1/playlists/$id/items") { auth() }
        val entries=Json.parseToJsonElement(current.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(2,entries.size)
        assertEquals(entry,entries.last().jsonObject["entryId"]!!.jsonPrimitive.content)
        assertNotEquals(entries.first().jsonObject["entryId"],entries.last().jsonObject["entryId"])
        val overwritten=client.patch("/dulcet/v1/playlists/$id") { auth(); header(HttpHeaders.IfMatch,tag); setBody(j("name" to "Wrong").toString()) }
        assertEquals(HttpStatusCode.PreconditionFailed,overwritten.status)
        assertEquals(JsonPrimitive("urn:dulcet:problem:revision-mismatch"),Json.parseToJsonElement(overwritten.bodyAsText()).jsonObject["type"])
        assertNotNull(Json.parseToJsonElement(overwritten.bodyAsText()).jsonObject["current"])
        val heartbeat=client.put("/dulcet/v1/me/now-playing") { auth(); setBody(j("deviceId" to "speaker","trackId" to "track","state" to "playing","positionMs" to 1000).toString()) }
        assertEquals(HttpStatusCode.OK,heartbeat.status)
        assertEquals(JsonPrimitive(0L),ctx.apiUserData.itemState("track",p)["playCount"])
        assertEquals(1L,ctx.db.read { long("SELECT COUNT(*) FROM now_playing WHERE user_id=? AND client='speaker'",p.user.id) })
        assertEquals("upsert",ctx.db.read { string("SELECT op FROM api_journal WHERE type='nowPlaying' AND id='speaker' AND user_id=? ORDER BY seq DESC LIMIT 1",p.user.id) })
        val cleared=client.delete("/dulcet/v1/me/now-playing?deviceId=speaker") { auth() }
        assertEquals(HttpStatusCode.NoContent,cleared.status)
        assertEquals("delete",ctx.db.read { string("SELECT op FROM api_journal WHERE type='nowPlaying' AND id='speaker' AND user_id=? ORDER BY seq DESC LIMIT 1",p.user.id) })
        val export=client.get("/dulcet/v1/me/export") { auth() }
        assertEquals(HttpStatusCode.OK,export.status)
        assertTrue(export.headers[HttpHeaders.ContentType]!!.startsWith("application/x-ndjson"))
        val lines=export.bodyAsText().lineSequence().filter { it.isNotEmpty() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
        assertEquals(JsonPrimitive("header"),lines.first()["kind"])
        val exported=lines.first { it["kind"]==JsonPrimitive("playlist") }
        assertEquals(id,exported["id"]!!.jsonPrimitive.content)
        assertEquals(entry,exported["items"]!!.jsonArray.last().jsonObject["entryId"]!!.jsonPrimitive.content)
        java.nio.file.Files.list(ctx.config.dataFolder).use { files -> assertEquals(0L,files.filter { it.fileName.toString().startsWith("api-export-") }.count()) }
        ctx.db.close()
    }

}
