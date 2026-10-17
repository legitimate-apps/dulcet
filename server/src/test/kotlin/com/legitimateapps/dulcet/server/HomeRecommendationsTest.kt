package com.legitimateapps.dulcet.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class HomeRecommendationsTest {
    @Test fun `imported annotations feed home per user and both contracts advertise and enforce scope`() = testApplication {
        val ctx = Fixtures.context(Files.createTempDirectory("home"))
        repeat(3) { i -> Fixtures.track(ctx.config.musicFolder.resolve("$i.flac"), tags=mapOf("title" to "Track $i", "album" to "Album $i", "artist" to "Artist $i", "genre" to "Jazz")) }
        ctx.scanner.scan()
        val alice = ctx.users.create("alice", "password", false)
        val bob = ctx.users.create("bob", "password", false)
        val token = ctx.apiAuth.bootstrapPersonal(alice, setOf("catalog:read", "user:read"))
        val limited = ctx.apiAuth.bootstrapPersonal(bob, setOf("catalog:read"))
        val ids = ctx.db.read { query("SELECT id FROM track ORDER BY title") { it.string("id") } }
        val old = System.currentTimeMillis()-60L*86400000
        // Same persisted annotations the importer writes; no synthetic API plays are needed.
        ctx.db.write {
            execute("INSERT INTO annotation(user_id,item_type,item_id,starred,rating,play_count,played) VALUES(?,'song',?,?,5,12,?)", alice.id, ids[0], old, old)
            execute("INSERT INTO annotation(user_id,item_type,item_id,rating,play_count,played) VALUES(?,'song',?,0,1,?)", alice.id, ids[1], System.currentTimeMillis())
            // Explicit dislike overrides inherited album affection, even with a large play count.
            execute("INSERT INTO annotation(user_id,item_type,item_id,rating,play_count,played) VALUES(?,'song',?,1,200,?)", alice.id, ids[2], System.currentTimeMillis())
            execute("INSERT INTO annotation(user_id,item_type,item_id,starred,rating) SELECT ?,'album',album_id,?,5 FROM track WHERE id=?", alice.id, old, ids[2])
        }
        ctx.db.write {
            execute("INSERT INTO playlist(id,owner_id,name,public,created,changed) VALUES ('home-playlist',?,'Recent mix',0,?,?)",alice.id,old,old)
            execute("INSERT INTO playlist_track(playlist_id,position,track_id) VALUES ('home-playlist',0,?)",ids[0])
            execute("INSERT INTO api_play(user_id,id,track_id,started_at,payload,recorded_at) VALUES (?,?,?,?,?,?)",alice.id,"home-history",ids[0],old,
                """{"context":{"type":"playlist","id":"home-playlist"}}""",old)
        }
        application { dulcetModule(ctx) }
        suspend fun response(path:String) = client.get(path) { bearerAuth(token) }
        try {
            val shelves = ctx.home.shelves(alice.id).associateBy { it.id }
            assertEquals(listOf("home-playlist"), shelves.getValue("recentPlaylists").ids)
            assertTrue(ctx.home.shelves(bob.id).first { it.id=="recentPlaylists" }.ids.isEmpty())
            assertEquals(listOf(ids[0]), shelves.getValue("favourites").ids)
            assertTrue(ids[0] in shelves.getValue("dailyMix").ids)
            for (shelf in listOf("dailyMix", "favourites", "artistStation", "genreStation")) {
                assertFalse(ids[2] in shelves.getValue(shelf).ids, "$shelf must omit explicitly low-rated songs")
            }
            assertEquals(1, shelves.getValue("rediscover").ids.size)
            assertEquals(3, shelves.getValue("recent").ids.size)
            assertTrue(ctx.home.shelves(bob.id).first { it.id=="dailyMix" }.ids.isEmpty())
            assertTrue(ctx.home.shelves(bob.id).first { it.id=="recent" }.ids.isEmpty())
            ctx.db.write { execute("INSERT OR REPLACE INTO api_user_access(user_id,source_ids) VALUES (?,'[]')",bob.id) }
            assertTrue(ctx.home.shelves(bob.id).all { it.ids.isEmpty() }, "Every shelf must filter forbidden source music")
            val caps = Json.parseToJsonElement(response("/dulcet/v1/capabilities").bodyAsText()).jsonObject
            assertNotNull(caps["capabilities"]!!.jsonObject["dulcet.home"])
            val api = response("/dulcet/v1/recommendations/home")
            assertEquals(HttpStatusCode.OK, api.status)
            val home = Json.parseToJsonElement(api.bodyAsText()).jsonObject["shelves"]!!.jsonArray
            assertEquals(10, home.size)
            assertEquals("playlist",home.first { it.jsonObject["id"]!!.jsonPrimitive.content=="recentPlaylists" }.jsonObject["items"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.NotModified, client.get("/dulcet/v1/recommendations/home") { bearerAuth(token); header("If-None-Match", api.headers["ETag"]!!) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/dulcet/v1/recommendations/home") { bearerAuth(limited) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/dulcet/v1/recommendations/home").status)
            assertEquals(HttpStatusCode.BadRequest, response("/dulcet/v1/recommendations/home?limit=51").status)
            val rest = client.rest("getDulcetHome", "alice", "password", "shelf" to "dailyMix")["dulcetHome"]!!.jsonObject
            assertEquals(shelves.getValue("dailyMix").ids, rest["song"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val extensions = client.rest("getOpenSubsonicExtensions", "alice", "password")["openSubsonicExtensions"]!!.jsonArray
            assertTrue(extensions.any { it.jsonObject["name"]!!.jsonPrimitive.content=="dulcetHome" })
        } finally { ctx.db.close() }
    }
    @Test fun `REST playlist context records only submitted member plays and merges with API history`() = testApplication {
        val ctx = Fixtures.context(Files.createTempDirectory("home-context"))
        repeat(2) { i -> Fixtures.track(ctx.config.musicFolder.resolve("$i.flac"), tags=mapOf("title" to "Track $i", "album" to "Album")) }
        ctx.scanner.scan()
        val alice = ctx.users.create("alice", "password", false)
        val bob = ctx.users.create("bob", "password", false)
        val ids = ctx.db.read { query("SELECT id FROM track ORDER BY title") { it.string("id") } }
        val at = System.currentTimeMillis()
        ctx.db.write {
            for ((id, owner, track) in listOf(Triple("played", alice.id, ids[0]), Triple("unrelated", alice.id, ids[1]), Triple("private", bob.id, ids[0]))) {
                execute("INSERT INTO playlist(id,owner_id,name,public,created,changed) VALUES (?,?,?,0,?,?)",id,owner,id,at,at)
                execute("INSERT INTO playlist_track(playlist_id,position,track_id) VALUES (?,0,?)",id,track)
            }
        }
        application { dulcetModule(ctx) }
        fun recent(user: String) = ctx.home.shelves(user, only="recentPlaylists").single().ids
        suspend fun submit(playlist: String, time: Long = at, submission: Boolean = true) =
            client.rest("scrobble", "alice", "password", "id" to ids[0], "time" to time.toString(),
                "submission" to submission.toString(), "dulcetContextType" to "playlist", "dulcetContextId" to playlist)
        try {
            client.rest("getPlaylist", "alice", "password", "id" to "played")
            assertTrue(recent(alice.id).isEmpty(), "Reading a playlist is not playing it")
            submit("played", submission=false)
            assertTrue(recent(alice.id).isEmpty(), "Now playing is not an eligible submitted play")
            submit("private")
            submit("unrelated")
            assertTrue(recent(alice.id).isEmpty(), "Private or unrelated context must be ignored")
            assertTrue(recent(bob.id).isEmpty(), "No other user's playlist annotation is written")
            submit("played")
            submit("played")
            submit("played", at-1000)
            assertEquals(listOf("played"), recent(alice.id))
            ctx.db.read {
                assertEquals(at, long("SELECT played FROM annotation WHERE user_id=? AND item_type='playlist' AND item_id='played'",alice.id))
                assertEquals(0L, long("SELECT play_count FROM annotation WHERE user_id=? AND item_type='playlist' AND item_id='played'",alice.id))
            }
            ctx.db.write {
                execute("INSERT INTO api_play(user_id,id,track_id,started_at,payload,recorded_at) VALUES (?,?,?,?,?,?)",alice.id,"same-playlist",ids[0],at,
                    """{"context":{"type":"playlist","id":"played"}}""",at)
            }
            assertEquals(listOf("played"), recent(alice.id), "API and REST history share one shelf item")
            val home = client.rest("getDulcetHome", "alice", "password", "shelf" to "recentPlaylists")["dulcetHome"]!!.jsonObject
            assertEquals("played", home["playlist"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            ctx.db.write { execute("INSERT OR REPLACE INTO api_user_access(user_id,source_ids) VALUES (?,'[]')",alice.id) }
            assertTrue(recent(alice.id).isEmpty(), "Catalog permission still applies to recorded playlist history")
        } finally { ctx.db.close() }
    }

}
