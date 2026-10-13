package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.j
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class RestAuthorizationSecurityTest {
    @Test fun restrictedSourcesHideEveryLegacyCatalogAndMediaRead() = testApplication {
        val ctx = Fixtures.context(createTempDirectory("dulcet-rest-access"))
        val user = ctx.users.create("listener", "listener-password", false)
        Fixtures.track(ctx.config.musicFolder.resolve("album/song.flac"), tags = mapOf("title" to "Restricted Song", "album" to "Restricted Album", "artist" to "Restricted Artist", "genre" to "Restricted Genre"))
        ctx.scanner.scan()
        val track = ctx.db.read { string("SELECT id FROM track LIMIT 1")!! }
        val album = ctx.db.read { string("SELECT id FROM album LIMIT 1")!! }
        val artist = ctx.db.read { string("SELECT id FROM artist LIMIT 1")!! }
        application { dulcetModule(ctx) }
        suspend fun rest(method: String, vararg params: Pair<String, String>) = client.rest(method, user.name, "listener-password", *params)
        assertEquals("ok", rest("getSong", "id" to track)["status"]!!.jsonPrimitive.content)
        ctx.apiUsers.update(user.id, j("sourceIds" to emptyList<String>()))
        for ((method, params) in listOf(
            "getRandomSongs" to arrayOf("size" to "1"), "search3" to arrayOf("query" to "Restricted"),
            "search2" to arrayOf("query" to "Restricted"), "getArtists" to emptyArray(), "getIndexes" to emptyArray(),
            "getMusicFolders" to emptyArray(), "getGenres" to emptyArray(),
            "getAlbumList2" to arrayOf("type" to "newest"), "getSongsByGenre" to arrayOf("genre" to "Restricted Genre"),
            "getTopSongs" to arrayOf("artist" to "Restricted Artist"), "getStarred2" to emptyArray()
        )) {
            val response = rest(method, *params).toString()
            assertFalse(response.contains(track) || response.contains(album) || response.contains(artist) || response.contains("Restricted"), "$method leaked catalog: $response")
            if(method == "getMusicFolders") assertFalse(response.contains("musicFolder\":"), response)
        }
        for ((method, id) in listOf("getSong" to track, "getAlbum" to album, "getArtist" to artist, "getMusicDirectory" to album,
            "stream" to track, "download" to track, "getCoverArt" to "mf-$track", "getCoverArt" to "al-$album", "getCoverArt" to "ar-$artist", "getLyricsBySongId" to track)) {
            val response = rest(method, "id" to id)
            assertEquals("failed", response["status"]!!.jsonPrimitive.content, method)
            assertEquals(70, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int, method)
        }
        assertEquals("failed", rest("getTranscodeDecision", "mediaId" to track, "mediaType" to "song")["status"]!!.jsonPrimitive.content)
        assertEquals("failed", rest("getTranscodeStream", "mediaId" to track, "mediaType" to "song", "transcodeParams" to "invalid")["status"]!!.jsonPrimitive.content)
        ctx.db.close()
    }

    @Test fun legacyDownloadAndPublicationUseLiveAccountPermissions() = testApplication {
        val ctx = Fixtures.context(createTempDirectory("dulcet-rest-permissions"))
        val user = ctx.users.create("listener", "listener-password", false)
        Fixtures.track(ctx.config.musicFolder.resolve("song.flac"))
        ctx.scanner.scan()
        val track = ctx.db.read { string("SELECT id FROM track LIMIT 1")!! }
        application { dulcetModule(ctx) }
        suspend fun rest(method: String, vararg params: Pair<String, String>) = client.rest(method, user.name, "listener-password", *params)
        val playlist = rest("createPlaylist", "name" to "Private", "songId" to track)["playlist"]!!.jsonObject["id"]!!.jsonPrimitive.content
        ctx.apiUsers.update(user.id, j("permissions" to j("download" to false, "share" to false)))
        val download = client.get("/rest/download?u=listener&p=listener-password&v=1.16.1&c=test&f=json&id=$track").bodyAsText()
        assertTrue(download.startsWith("{"), "A forbidden download must return an error envelope, not audio")
        assertEquals(50, Json.parseToJsonElement(download).jsonObject["subsonic-response"]!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        for ((method, params) in listOf("getShares" to emptyArray(), "updatePlaylist" to arrayOf("playlistId" to playlist, "public" to "true", "name" to "Should roll back"))) {
            val response = rest(method, *params)
            assertEquals("failed", response["status"]!!.jsonPrimitive.content, method)
            assertEquals(50, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int, method)
        }
        assertEquals("Private", ctx.db.read { string("SELECT name FROM playlist WHERE id=?", playlist) })
        assertEquals(0L, ctx.db.read { long("SELECT public FROM playlist WHERE id=?", playlist) })
        val profile = rest("getUser")["user"]!!.jsonObject
        assertEquals(JsonPrimitive(false), profile["downloadRole"])
        // Streaming is permitted independently of downloading.
        val stream = client.get("/rest/stream?u=listener&p=listener-password&v=1.16.1&c=test&id=$track&format=raw")
        assertTrue(stream.bodyAsText().startsWith("fLaC"))
        ctx.db.close()
    }
}
