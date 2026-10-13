package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PlaylistSecurityTest {
    private fun fixture(): Pair<ServerContext, Principal> {
        val ctx = Fixtures.context(createTempDirectory("dulcet-playlist-security"))
        val user = ctx.users.create("listener", "playlist-test-password", false)
        ctx.db.write {
            execute("""INSERT INTO track(id,path,folder_id,parent_dir,mtime,size,title,album,album_id,artist,artist_id,album_artist,album_artist_id,duration,bitrate,suffix,content_type,created,updated,search)
                VALUES('track','song.flac',1,'',0,100,'Song','Album','album','Artist','artist','Artist','artist',300,100000,'flac','audio/flac',1,1,'song')""")
        }
        return ctx to Principal(user, "test", null, setOf("user:read", "user:write", "user:delete", "catalog:read"))
    }

    @Test fun publicationRequiresCurrentSharePermissionAndRollsBackTheWholePatch() = testApplication {
        val (ctx, p) = fixture()
        val token = ctx.apiAuth.bootstrapPersonal(p.user, p.scopes)
        val other = ctx.users.create("other-listener", "another-test-password", false)
        val otherToken = ctx.apiAuth.bootstrapPersonal(other, setOf("user:read"))
        application { dulcetModule(ctx) }
        fun HttpRequestBuilder.auth(value: String = token) {
            header(HttpHeaders.Authorization, "Bearer $value")
            contentType(ContentType.Application.Json)
        }
        val created = client.post("/dulcet/v1/playlists") { auth(); setBody(j("name" to "Private playlist").toString()) }
        assertEquals(HttpStatusCode.Created, created.status)
        val original = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val id = original["id"]!!.jsonPrimitive.content
        // Restrict an existing credential and playlist: authorization must read live policy.
        ctx.apiUsers.update(p.user.id, j("permissions" to j("share" to false)))
        val head = ctx.journal.head()
        val refused = client.patch("/dulcet/v1/playlists/$id") {
            auth(); header(HttpHeaders.IfMatch, created.headers[HttpHeaders.ETag]!!)
            setBody(j("name" to "Published playlist", "visibility" to "public").toString())
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals(JsonPrimitive("urn:dulcet:problem:permission-denied"), Json.parseToJsonElement(refused.bodyAsText()).jsonObject["type"])
        assertEquals(head, ctx.journal.head(), "Rejected publication must not journal partial metadata edits")
        assertEquals(original, ctx.apiUserData.resource("playlist", id, p))
        assertEquals(HttpStatusCode.NotFound, client.get("/dulcet/v1/playlists/$id") { auth(otherToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/dulcet/v1/playlists") {
            auth(); setBody(j("name" to "Direct publication", "visibility" to "public").toString())
        }.status)
        ctx.apiUsers.update(p.user.id, j("permissions" to j("share" to true)))
        assertEquals(HttpStatusCode.OK, client.patch("/dulcet/v1/playlists/$id") {
            auth(); setBody(j("visibility" to "public").toString())
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/dulcet/v1/playlists/$id") { auth(otherToken) }.status)
        ctx.db.close()
    }

    @Test fun replacementRejectsTracksOutsideCurrentSources() = hiddenTrackMutation("replace")

    @Test fun insertionRejectsTracksOutsideCurrentSources() = hiddenTrackMutation("insert")

    private fun hiddenTrackMutation(operation: String) = testApplication {
        val (ctx, p) = fixture()
        val token = ctx.apiAuth.bootstrapPersonal(p.user, p.scopes)
        application { dulcetModule(ctx) }
        fun HttpRequestBuilder.auth() {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
        }
        val created = client.post("/dulcet/v1/playlists") { auth(); setBody(j("name" to "Empty playlist").toString()) }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val tag = created.headers[HttpHeaders.ETag]!!
        ctx.apiUsers.update(p.user.id, j("sourceIds" to emptyList<String>()))
        val head = ctx.journal.head()
        suspend fun mutate() = client.request("/dulcet/v1/playlists/$id/items") {
            method = if (operation == "replace") HttpMethod.Put else HttpMethod.Post
            auth(); header(HttpHeaders.IfMatch, tag); setBody(j("itemIds" to listOf("track")).toString())
        }
        val refused = mutate()
        assertEquals(HttpStatusCode.NotFound, refused.status)
        assertEquals(head, ctx.journal.head(), "Rejected hidden tracks must leave the playlist unchanged")
        assertEquals(0L, ctx.db.read { long("SELECT COUNT(*) FROM playlist_track WHERE playlist_id=?", id) })
        ctx.apiUsers.update(p.user.id, j("sourceIds" to listOf("1")))
        assertEquals(HttpStatusCode.OK, mutate().status)
        assertEquals(1L, ctx.db.read { long("SELECT COUNT(*) FROM playlist_track WHERE playlist_id=?", id) })
        ctx.db.close()
    }
}
