package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.api.parseRange
import com.legitimateapps.dulcet.server.auth.Users
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestTest {
    private val ctx = Fixtures.context(createTempDirectory("dulcet-rest")).also { ctx ->
        val album = ctx.config.musicFolder.resolve("A/Record")
        Fixtures.track(album.resolve("01 - First.flac"), tags = mapOf("title" to "First", "artist" to "Artist A", "album" to "Record", "track" to "1"))
        Fixtures.track(album.resolve("02 - Second.flac"), tags = mapOf("title" to "Second", "artist" to "Artist A", "album" to "Record", "track" to "2"))
        ctx.scanner.scan()
        ctx.users.create("alice", "alice-pw", isAdmin = true)
        ctx.users.create("bob", "bob-pw", isAdmin = false)
    }
    private val songIds = ctx.db.read { query("SELECT id FROM track ORDER BY track_no") { it.string("id") } }
    private val albumId = ctx.db.read { string("SELECT id FROM album")!! }

    private fun JsonObject.code(): Int? = (this["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.int
    private fun JsonObject.status(): String = this["status"]!!.jsonPrimitive.content

    @Test
    fun authenticatesByTokenAndPasswordAndRejectsTheRest() = testApplication {
        application { dulcetModule(ctx) }
        val salt = "c0ffee"
        val token = Users.md5Hex("alice-pw$salt")
        val byToken = client.get("/rest/ping.view?u=alice&t=$token&s=$salt&v=1.16.1&c=t&f=json").bodyAsText()
        assertTrue("\"status\":\"ok\"" in byToken, byToken)
        assertEquals("ok", client.rest("ping", "alice", "alice-pw").status())
        val hex = "alice-pw".toByteArray().joinToString("") { "%02x".format(it) }
        assertEquals("ok", client.rest("ping", "alice", "enc:$hex").status())
        assertEquals(40, client.rest("ping", "alice", "wrong").code())
        assertEquals(40, client.rest("ping", "nobody", "x").code())
        assertEquals(40, client.get("/rest/ping?u=alice&t=$token&s=other&v=1&c=t&f=json").bodyAsText().let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["subsonic-response"]!!.jsonObject.code() })
        val missing = kotlinx.serialization.json.Json.parseToJsonElement(client.get("/rest/ping?v=1&c=t&f=json").bodyAsText()).jsonObject["subsonic-response"]!!.jsonObject
        assertEquals(10, missing.code())
        assertEquals(42, client.rest("ping", "alice", "alice-pw", "apiKey" to "k").code())
        val extensions = client.get("/rest/getOpenSubsonicExtensions?f=json").bodyAsText()
        assertTrue("\"dulcetLibrary\"" in extensions && "\"status\":\"ok\"" in extensions, "extensions are open: $extensions")
        val xml = client.get("/rest/ping?u=alice&p=wrong&v=1&c=t").bodyAsText()
        assertTrue(xml.startsWith("<?xml") && "code=\"40\"" in xml, xml)
        val unknown = client.get("/rest/noSuchMethod?u=alice&p=alice-pw&v=1&c=t")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
    }

    @Test
    fun myLibraryIsPerUserAndHasAChangeClock() = testApplication {
        application { dulcetModule(ctx) }
        assertEquals(70, client.rest("addToDulcetLibrary", "alice", "alice-pw", "albumId" to "missing").code())
        assertEquals(10, client.rest("addToDulcetLibrary", "alice", "alice-pw").code())
        assertEquals("ok", client.rest("addToDulcetLibrary", "alice", "alice-pw", "albumId" to albumId, "id" to songIds[0]).status())
        assertEquals("ok", client.rest("addToDulcetLibrary", "alice", "alice-pw", "albumId" to albumId).status(), "adding twice is harmless")
        val alice = client.rest("getDulcetLibrary", "alice", "alice-pw")["dulcetLibrary"]!!.jsonObject
        assertEquals(1, alice["albumCount"]!!.jsonPrimitive.int)
        assertEquals(1, alice["songCount"]!!.jsonPrimitive.int)
        assertEquals(albumId, alice["album"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        val firstClock = alice["changed"]!!.jsonPrimitive.content
        val bob = client.rest("getDulcetLibrary", "bob", "bob-pw")["dulcetLibrary"]!!.jsonObject
        assertEquals(0, bob["albumCount"]!!.jsonPrimitive.int)
        assertNull(bob["album"], "bob's library is his own")
        val albumForAlice = client.rest("getAlbum", "alice", "alice-pw", "id" to albumId)["album"]!!.jsonObject
        assertEquals(JsonPrimitive(true), albumForAlice["dulcetInLibrary"])
        assertNull(client.rest("getAlbum", "bob", "bob-pw", "id" to albumId)["album"]!!.jsonObject["dulcetInLibrary"])
        assertEquals("ok", client.rest("removeFromDulcetLibrary", "alice", "alice-pw", "albumId" to albumId).status())
        val after = client.rest("getDulcetLibrary", "alice", "alice-pw", "type" to "album")["dulcetLibrary"]!!.jsonObject
        assertEquals(0, after["albumCount"]!!.jsonPrimitive.int)
        assertTrue(after["changed"]!!.jsonPrimitive.content > firstClock, "a removal moves the change clock")
        assertEquals("ok", client.rest("removeFromDulcetLibrary", "alice", "alice-pw", "id" to "gone-from-disk").status(), "removing an unknown id is not an error")
    }

    @Test
    fun starsRatingsPlaysAndPlaylistsArePerUser() = testApplication {
        application { dulcetModule(ctx) }
        client.rest("star", "alice", "alice-pw", "id" to songIds[0])
        client.rest("setRating", "alice", "alice-pw", "id" to albumId, "rating" to "4")
        client.rest("scrobble", "alice", "alice-pw", "id" to songIds[1])
        client.rest("scrobble", "alice", "alice-pw", "id" to songIds[1])
        assertEquals(1, client.rest("getStarred2", "alice", "alice-pw")["starred2"]!!.jsonObject["song"]!!.jsonArray.size)
        assertNull(client.rest("getStarred2", "bob", "bob-pw")["starred2"]!!.jsonObject["song"])
        val song = client.rest("getSong", "alice", "alice-pw", "id" to songIds[1])["song"]!!.jsonObject
        assertEquals(2, song["playCount"]!!.jsonPrimitive.int)
        assertEquals(0, client.rest("getSong", "bob", "bob-pw", "id" to songIds[1])["song"]!!.jsonObject["playCount"]!!.jsonPrimitive.int)
        assertEquals(4, client.rest("getAlbum", "alice", "alice-pw", "id" to albumId)["album"]!!.jsonObject["userRating"]!!.jsonPrimitive.int)
        assertEquals(1, client.rest("getAlbumList2", "alice", "alice-pw", "type" to "frequent")["albumList2"]!!.jsonObject["album"]!!.jsonArray.size)

        val created = client.rest("createPlaylist", "alice", "alice-pw", "name" to "Mine", "songId" to songIds[1])["playlist"]!!.jsonObject
        val playlistId = created["id"]!!.jsonPrimitive.content
        client.rest("updatePlaylist", "alice", "alice-pw", "playlistId" to playlistId, "songIdToAdd" to songIds[0])
        val entries = client.rest("getPlaylist", "alice", "alice-pw", "id" to playlistId)["playlist"]!!.jsonObject["entry"]!!.jsonArray
        assertEquals(listOf(songIds[1], songIds[0]), entries.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(70, client.rest("getPlaylist", "bob", "bob-pw", "id" to playlistId).code(), "a private playlist is invisible to others")
        assertEquals(50, client.rest("deletePlaylist", "bob", "bob-pw", "id" to playlistId).code())
    }

    @Test
    fun streamServesRangesAndEnvelopesErrors() = testApplication {
        application { dulcetModule(ctx) }
        val auth = "u=alice&p=alice-pw&v=1.16.1&c=t"
        val size = Files.size(ctx.config.musicFolder.resolve("A/Record/01 - First.flac"))
        val whole = client.get("/rest/stream?$auth&id=${songIds[0]}")
        assertEquals(HttpStatusCode.OK, whole.status)
        assertEquals(size.toInt(), whole.bodyAsBytes().size)
        assertEquals("bytes", whole.headers["Accept-Ranges"])
        val part = client.get("/rest/stream?$auth&id=${songIds[0]}") { header("Range", "bytes=4-9") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("bytes 4-9/$size", part.headers["Content-Range"])
        assertEquals(6, part.bodyAsBytes().size)
        val beyond = client.get("/rest/stream?$auth&id=${songIds[0]}") { header("Range", "bytes=${size + 10}-") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, beyond.status)
        val missing = client.get("/rest/stream?$auth&id=nope&f=json").bodyAsText()
        assertTrue("\"code\":70" in missing, missing)
        val transcoded = client.get("/rest/stream?$auth&id=${songIds[0]}&format=mp3&maxBitRate=96")
        assertEquals("audio/mpeg", transcoded.headers["Content-Type"])
        val bytes = transcoded.bodyAsBytes()
        assertTrue(bytes.size > 100 && (bytes[0] == 'I'.code.toByte() || bytes[0] == 0xFF.toByte()), "an MP3 stream")
        val art = client.get("/rest/getCoverArt?$auth&id=al-$albumId&size=64")
        assertEquals(HttpStatusCode.OK, art.status)
        assertTrue(art.headers["Content-Type"]!!.startsWith("image/"), "an album without art gets a placeholder image")
    }

    @Test
    fun parsesRanges() {
        assertEquals(0L..99L, parseRange("bytes=0-99", 1000))
        assertEquals(900L..999L, parseRange("bytes=900-", 1000))
        assertEquals(990L..999L, parseRange("bytes=-10", 1000))
        assertEquals(500L..999L, parseRange("bytes=500-5000", 1000))
        assertEquals(-1L..-1L, parseRange("bytes=1000-", 1000))
        assertNull(parseRange("bytes=0-1,5-6", 1000))
        assertNull(parseRange("items=0-1", 1000))
        assertFalse(parseRange("bytes=5-1", 1000) != null)
    }
}
