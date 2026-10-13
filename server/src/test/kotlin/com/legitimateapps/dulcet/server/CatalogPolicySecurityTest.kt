package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.j
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CatalogPolicySecurityTest {
    private fun fixture(): ServerContext {
        val ctx=Fixtures.context(createTempDirectory("dulcet-policy-projections"))
        Fixtures.track(ctx.config.musicFolder.resolve("visible/song.flac"),tags=mapOf("title" to "Visible", "album" to "Shared", "artist" to "Artist", "genre" to "Public", "disc" to "1"))
        Fixtures.track(ctx.config.musicFolder.resolve("hidden/song.flac"),tags=mapOf("title" to "Secret", "album" to "Shared", "artist" to "Artist", "genre" to "SecretGenre", "disc" to "2"))
        Fixtures.track(ctx.config.musicFolder.resolve("hidden/other.flac"),tags=mapOf("title" to "Other Secret", "album" to "Hidden Album", "artist" to "Artist"))
        ctx.scanner.scan()
        ctx.db.write { execute("INSERT INTO music_folder VALUES(2,'Hidden','hidden')"); execute("UPDATE track SET folder_id=2 WHERE path LIKE 'hidden/%'") }
        val user=ctx.users.create("reader","reader-password",false)
        ctx.apiUsers.update(user.id,j("sourceIds" to listOf("1")))
        return ctx
    }

    @Test fun mixedSourceCatalogProjectsOnlyVisibleTracksInBothProtocols() = testApplication {
        val ctx=fixture(); val user=ctx.users.find("reader")!!
        val album=ctx.db.read { string("SELECT id FROM album WHERE name='Shared'")!! }
        val artist=ctx.db.read { string("SELECT id FROM artist WHERE name='Artist'")!! }
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"))
        application { dulcetModule(ctx) }
        val legacy=client.rest("getAlbum",user.name,"reader-password","id" to album)["album"]!!.jsonObject
        assertEquals(1,legacy["songCount"]!!.jsonPrimitive.int)
        assertFalse(legacy.toString().contains("SecretGenre"))
        assertEquals(1,client.rest("getArtist",user.name,"reader-password","id" to artist)["artist"]!!.jsonObject["albumCount"]!!.jsonPrimitive.int)
        val apiAlbum=Json.parseToJsonElement(client.get("/dulcet/v1/albums/$album") { bearerAuth(token) }.bodyAsText()).jsonObject
        assertEquals(1,apiAlbum["trackCount"]!!.jsonPrimitive.int)
        assertEquals(1,apiAlbum["discCount"]!!.jsonPrimitive.int)
        assertFalse(apiAlbum.toString().contains("SecretGenre"))
        val apiArtist=Json.parseToJsonElement(client.get("/dulcet/v1/artists/$artist") { bearerAuth(token) }.bodyAsText()).jsonObject
        assertEquals(1,apiArtist["albumCount"]!!.jsonPrimitive.int)
        assertEquals(1,apiArtist["trackCount"]!!.jsonPrimitive.int)
        ctx.db.close()
    }

    @Test fun albumArtworkCannotResolveThroughAHiddenSource() = testApplication {
        val ctx=fixture()
        Fixtures.jpeg(ctx.config.musicFolder.resolve("hidden/cover.jpg"))
        val album=ctx.db.read { string("SELECT id FROM album WHERE name='Shared'")!! }
        ctx.db.write { execute("UPDATE album SET folder_art='hidden/cover.jpg',art_track_id=(SELECT id FROM track WHERE title='Secret') WHERE id=?",album) }
        application { dulcetModule(ctx) }
        val response=client.get("/rest/getCoverArt?u=reader&p=reader-password&v=1.16.1&c=test&id=al-$album")
        assertEquals(ContentType.Image.PNG,response.contentType())
        ctx.db.close()
    }

    @Test fun legacyNowPlayingHonorsSharingAndOptInForOtherUsers() = testApplication {
        val ctx=fixture(); val owner=ctx.users.find("reader")!!
        val viewer=ctx.users.create("viewer","viewer-password",false)
        val track=ctx.db.read { string("SELECT id FROM track WHERE title='Visible'")!! }
        ctx.db.write { execute("INSERT INTO now_playing VALUES(?,?,?,?)",owner.id,"player",track,System.currentTimeMillis()) }
        application { dulcetModule(ctx) }
        suspend fun entries()=client.rest("getNowPlaying",viewer.name,"viewer-password")["nowPlaying"]!!.jsonObject["entry"] as? JsonArray ?: JsonArray(emptyList())
        assertTrue(entries().isEmpty(),"Private now-playing must not be exposed through /rest")
        ctx.db.write { execute("INSERT OR REPLACE INTO api_profile(user_id,settings) VALUES(?,?)",owner.id,j("shareNowPlaying" to true).toString()) }
        assertEquals(1,entries().size)
        ctx.apiUsers.update(owner.id,j("permissions" to j("share" to false)))
        assertTrue(entries().isEmpty())
        val own=client.rest("getNowPlaying",owner.name,"reader-password")["nowPlaying"]!!.jsonObject["entry"]!!.jsonArray
        assertEquals(1,own.size)
        ctx.db.close()
    }
}
