package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.j
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ApiSyncVisibilitySecurityTest {
    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun fixture(): ServerContext {
        val ctx = Fixtures.context(Files.createTempDirectory("api-sync-security"))
        listOf("Visible", "Hidden").forEach { name ->
            Fixtures.track(ctx.config.musicFolder.resolve("$name.flac"), tags = mapOf("title" to name,"album" to name,"artist" to name,"genre" to name))
        }
        ctx.scanner.scan()
        return ctx
    }

    @Test fun `catalog deletion tombstones retain source visibility after related rows vanish`() = testApplication {
        val ctx = fixture()
        val blocked = ctx.users.create("blocked", "password", false)
        val allowed = ctx.users.create("allowed", "password", false)
        ctx.db.write { execute("INSERT INTO api_user_access(user_id,source_ids) VALUES(?,'[]')", blocked.id) }
        val blockedToken = ctx.apiAuth.bootstrapPersonal(blocked,setOf("catalog:read"))
        val allowedToken = ctx.apiAuth.bootstrapPersonal(allowed,setOf("catalog:read"))
        application { dulcetModule(ctx) }
        suspend fun feed(token: String, query: String) = client.get("/dulcet/v1/sync/changes?types=track,album,artist,genre,source&$query") { bearerAuth(token) }
        val blockedCursor = feed(blockedToken,"start=now").obj()["nextCursor"]!!.jsonPrimitive.content
        val allowedCursor = feed(allowedToken,"start=now").obj()["nextCursor"]!!.jsonPrimitive.content
        val deletedIds = ctx.db.read { query("SELECT id FROM track UNION SELECT id FROM album UNION SELECT id FROM artist UNION SELECT id FROM api_group_identity WHERE type='genre'") { it.string("id") } }.toSet()
        ctx.db.write {
            execute("DELETE FROM track_genre")
            execute("DELETE FROM track_artist")
            execute("DELETE FROM album_artist")
            execute("DELETE FROM track")
            execute("DELETE FROM album")
            execute("DELETE FROM artist")
            execute("DELETE FROM music_folder")
        }
        // Preserve the allowed user's source grant after the source itself disappears.
        ctx.db.write { execute("INSERT INTO api_user_access(user_id,source_ids) VALUES(?,'[\"1\"]')",allowed.id) }
        val hidden = feed(blockedToken,"cursor=$blockedCursor")
        assertEquals(HttpStatusCode.OK,hidden.status)
        assertTrue(hidden.obj()["changes"]!!.jsonArray.isEmpty(),"Hidden deletion IDs and revisions must not be disclosed")
        val visible = feed(allowedToken,"cursor=$allowedCursor")
        assertEquals(HttpStatusCode.OK,visible.status)
        val deletions = visible.obj()["changes"]!!.jsonArray.map { it.jsonObject }.filter { it["op"]!!.jsonPrimitive.content == "delete" }
        assertTrue(deletions.map { it["id"]!!.jsonPrimitive.content }.toSet().containsAll(deletedIds))
        assertTrue(deletions.any { it["type"]!!.jsonPrimitive.content == "source" })
        ctx.db.close()
    }

    @Test fun `merged tombstone omits replacements from inaccessible sources`() = testApplication {
        val ctx = fixture()
        val user = ctx.users.create("reader", "password", false)
        val visible = ctx.db.read { string("SELECT id FROM album WHERE name='Visible'")!! }
        val hidden = ctx.db.read { string("SELECT id FROM album WHERE name='Hidden'")!! }
        ctx.db.write {
            execute("INSERT INTO music_folder VALUES(2,'Restricted','restricted')")
            execute("UPDATE track SET folder_id=2 WHERE album_id=?",hidden)
            execute("INSERT INTO api_user_access(user_id,source_ids) VALUES(?,'[\"1\"]')",user.id)
        }
        val token = ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"))
        application { dulcetModule(ctx) }
        suspend fun feed(query: String) = client.get("/dulcet/v1/sync/changes?types=album&$query") { bearerAuth(token) }
        val cursor = feed("start=now").obj()["nextCursor"]!!.jsonPrimitive.content
        ctx.db.write {
            execute("DELETE FROM track WHERE album_id=?",visible)
            execute("DELETE FROM album WHERE id=?",visible)
            execute("UPDATE api_identity SET reason='merged',replaced_by=? WHERE type='album' AND id=?",JsonArray(listOf(JsonPrimitive(hidden))).toString(),visible)
            execute("INSERT INTO api_journal(type,id,op,at,reason,replaced_by) VALUES('album',?,'delete',?,'merged',?)",visible,System.currentTimeMillis(),JsonArray(listOf(JsonPrimitive(hidden))).toString())
        }
        val response = feed("cursor=$cursor")
        assertEquals(HttpStatusCode.OK,response.status)
        val deletion = response.obj()["changes"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(visible),deletion["id"])
        assertEquals(JsonPrimitive("merged"),deletion["reason"])
        assertFalse(response.bodyAsText().contains(hidden),"A merge mapping must not reveal an inaccessible survivor")
        ctx.db.close()
    }

    @Test fun `unknown historical tombstone visibility expires the cursor instead of exposing IDs`() = testApplication {
        val ctx = fixture()
        val user = ctx.users.create("reader", "password", false)
        val token = ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"))
        application { dulcetModule(ctx) }
        val start = client.get("/dulcet/v1/sync/changes?types=track&start=now") { bearerAuth(token) }.obj()
        ctx.db.write { execute("INSERT INTO api_journal(type,id,op,at,reason) VALUES('track','legacy-unknown','delete',?,'deleted')",System.currentTimeMillis()) }
        val response = client.get("/dulcet/v1/sync/changes?types=track&cursor=${start["nextCursor"]!!.jsonPrimitive.content}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Gone,response.status)
        assertEquals(j("type" to "urn:dulcet:problem:cursor-expired")["type"],response.obj()["type"])
        assertFalse(response.bodyAsText().contains("legacy-unknown"))
        ctx.db.close()
    }
}
