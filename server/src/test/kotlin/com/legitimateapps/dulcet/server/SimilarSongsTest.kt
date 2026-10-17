package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.j
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SimilarSongsTest {
    private val endpoints = mapOf("getSimilarSongs" to "similarSongs", "getSimilarSongs2" to "similarSongs2")

    private fun fixture(): ServerContext {
        val ctx = Fixtures.context(createTempDirectory("dulcet-similar"))
        fun track(title: String, album: String, artist: String, genre: String) = Fixtures.track(
            ctx.config.musicFolder.resolve("$title.flac"),
            tags = mapOf("title" to title, "album" to album, "artist" to artist, "genre" to genre),
        )
        track("Seed", "Seed Album", "A; B", "Rock; Jazz")
        track("Same Album", "Seed Album", "A; B", "Rock")
        track("Artist A", "Other A", "A", "Classical")
        track("Artist B", "Other B", "B", "Classical")
        track("Genre Rock", "Other C", "C", "Rock")
        track("Genre Jazz", "Other D", "D", "Jazz")
        track("Unrelated", "Other E", "E", "Electronic")
        ctx.scanner.scan()
        ctx.users.create("reader", "reader-password", false)
        return ctx
    }

    private fun ServerContext.id(title: String): String = db.read { string("SELECT id FROM track WHERE title=?", title)!! }
    private fun JsonObject.songs(container: String): List<JsonObject> {
        assertEquals("ok", this["status"]!!.jsonPrimitive.content, toString())
        return (this[container]!!.jsonObject["song"] as? JsonArray).orEmpty().map { it.jsonObject }
    }
    private fun List<JsonObject>.titles(): Set<String> = map { it["title"]!!.jsonPrimitive.content }.toSet()
    private val neighbours = setOf("Artist A", "Artist B", "Genre Rock", "Genre Jazz")

    @Test fun songSeedsMixAllArtistAndGenreCreditsAndExcludeTheSeedAndItsAlbum() = testApplication {
        val ctx = fixture()
        application { dulcetModule(ctx) }
        for ((method, container) in endpoints) {
            val songs = client.rest(method, "reader", "reader-password", "id" to ctx.id("Seed")).songs(container)
            assertEquals(neighbours, songs.titles(), method)
            assertEquals(4, songs.size, "No duplicates")
            // Recommendation songs retain the ordinary catalog shape, including per-user fields.
            val song = songs.first()
            val normal = client.rest("getSong", "reader", "reader-password", "id" to song["id"]!!.jsonPrimitive.content)["song"]
            assertEquals(normal, song)
            val pair = client.rest(method, "reader", "reader-password", "id" to ctx.id("Seed"), "count" to "2").songs(container).titles()
            assertEquals(1, pair.intersect(setOf("Artist A", "Artist B")).size)
            assertEquals(1, pair.intersect(setOf("Genre Rock", "Genre Jazz")).size)
        }
        ctx.db.close()
    }

    @Test fun albumSeedsUseTheVisibleAlbumsArtistsAndGenres() = testApplication {
        val ctx = fixture()
        val album = ctx.db.read { string("SELECT album_id FROM track WHERE title='Seed'")!! }
        application { dulcetModule(ctx) }
        for ((method, container) in endpoints) {
            assertEquals(neighbours, client.rest(method, "reader", "reader-password", "id" to album).songs(container).titles(), method)
        }
        ctx.db.close()
    }

    @Test fun artistSeedsIncludeTheirOwnSongsAndOtherArtistsWithSharedGenres() = testApplication {
        val ctx = fixture()
        val artist = ctx.db.read { string("SELECT id FROM artist WHERE name='A'")!! }
        application { dulcetModule(ctx) }
        for ((method, container) in endpoints) {
            val songs = client.rest(method, "reader", "reader-password", "id" to artist).songs(container)
            assertEquals(setOf("Seed", "Same Album", "Artist A", "Artist B", "Genre Rock", "Genre Jazz"), songs.titles(), method)
            assertEquals(6, songs.size)
        }
        ctx.db.close()
    }

    @Test fun countDefaultsToFiftyAndClampsToZeroAndFiveHundred() = testApplication {
        val ctx = fixture()
        // Grow a scanned artist neighbour without spawning hundreds of audio probes. The copied
        // catalog rows retain real scanner metadata and credits, with distinct opaque ids/paths.
        ctx.db.write {
            val columns = query("PRAGMA table_info(track)") { it.string("name") }
            val values = columns.joinToString(",") { when (it) { "id", "path" -> "?"; else -> it } }
            repeat(505) { index ->
                val id = "count-neighbour-$index"
                execute("INSERT INTO track (${columns.joinToString(",")}) SELECT $values FROM track WHERE title='Artist A' LIMIT 1", id, "$id.flac")
                execute("INSERT INTO track_artist SELECT ?,role,artist_id,name,position FROM track_artist WHERE track_id=?", id, ctx.id("Artist A"))
            }
        }
        application { dulcetModule(ctx) }
        for ((method, container) in endpoints) {
            suspend fun songs(count: String?) = client.rest(method, "reader", "reader-password", "id" to ctx.id("Seed"), *listOfNotNull(count?.let { "count" to it }).toTypedArray()).songs(container)
            assertEquals(50, songs(null).size)
            assertEquals(3, songs("3").size)
            assertEquals(0, songs("0").size)
            assertEquals(0, songs("-1").size)
            val capped = songs("99999")
            assertEquals(500, capped.size)
            assertEquals(500, capped.map { it["id"] }.toSet().size)
        }
        ctx.db.close()
    }

    @Test fun sourcePolicyFiltersCandidatesAndHiddenSeedMetadataAndChangesLive() = testApplication {
        val ctx = fixture()
        // A hidden member of the visible seed album must not contribute either its artists or
        // its genres. Its neighbours are visible, making metadata leakage observable.
        Fixtures.track(ctx.config.musicFolder.resolve("Hidden.flac"), tags = mapOf(
            "title" to "Hidden", "album" to "Seed Album", "album_artist" to "A; B", "artist" to "E", "genre" to "Electronic",
        ))
        Fixtures.track(ctx.config.musicFolder.resolve("HiddenGenreNeighbour.flac"), tags = mapOf(
            "title" to "Hidden Genre Neighbour", "album" to "Other F", "artist" to "F", "genre" to "Electronic",
        ))
        ctx.scanner.scan()
        assertEquals(
            ctx.db.read { string("SELECT album_id FROM track WHERE title='Seed'") },
            ctx.db.read { string("SELECT album_id FROM track WHERE title='Hidden'") },
            "The hidden metadata must belong to the visible seed album",
        )
        ctx.db.write {
            execute("INSERT INTO music_folder VALUES(2,'Hidden source','hidden')")
            execute("UPDATE track SET folder_id=2 WHERE title IN ('Hidden','Artist B','Genre Jazz')")
        }
        val user = ctx.users.find("reader")!!
        val album = ctx.db.read { string("SELECT album_id FROM track WHERE title='Seed'")!! }
        val artist = ctx.db.read { string("SELECT id FROM artist WHERE name='A'")!! }
        application { dulcetModule(ctx) }
        for ((method, container) in endpoints) {
            assertTrue("Artist B" in client.rest(method, user.name, "reader-password", "id" to ctx.id("Seed")).songs(container).titles())
            for (seed in listOf(album, artist)) {
                val titles = client.rest(method, user.name, "reader-password", "id" to seed).songs(container).titles()
                assertTrue("Unrelated" in titles, "Hidden artist metadata must have a visible neighbour before restriction")
                assertTrue("Hidden Genre Neighbour" in titles, "Hidden genre metadata must have a visible neighbour before restriction")
            }
        }
        ctx.apiUsers.update(user.id, j("sourceIds" to listOf("1")))
        for ((method, container) in endpoints) {
            for (seed in listOf(ctx.id("Seed"), album)) {
                val songs = client.rest(method, user.name, "reader-password", "id" to seed).songs(container)
                assertEquals(setOf("Artist A", "Genre Rock"), songs.titles(), "$method / $seed")
            }
            assertEquals(setOf("Seed", "Same Album", "Artist A", "Genre Rock"), client.rest(method, user.name, "reader-password", "id" to artist).songs(container).titles())
        }
        ctx.db.close()
    }

    @Test fun unknownAndInvisibleSeedsFailWhileAVisibleIsolatedSeedSucceedsEmpty() = testApplication {
        val ctx = fixture()
        val user = ctx.users.find("reader")!!
        val seedIds = listOf(ctx.id("Seed"), ctx.db.read { string("SELECT album_id FROM track WHERE title='Seed'")!! }, ctx.db.read { string("SELECT id FROM artist WHERE name='A'")!! })
        application { dulcetModule(ctx) }
        for ((method, container) in endpoints) {
            assertTrue(client.rest(method, user.name, "reader-password", "id" to ctx.id("Unrelated")).songs(container).isEmpty())
            assertEquals(10, client.rest(method, user.name, "reader-password")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            val missing = client.rest(method, user.name, "reader-password", "id" to "missing")
            assertEquals("failed", missing["status"]!!.jsonPrimitive.content)
            assertEquals(70, missing["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        }
        ctx.apiUsers.update(user.id, j("sourceIds" to emptyList<String>()))
        for (method in endpoints.keys) for (seed in seedIds) {
            val response = client.rest(method, user.name, "reader-password", "id" to seed, "count" to "0")
            assertEquals("failed", response["status"]!!.jsonPrimitive.content)
            assertEquals(70, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        }
        ctx.db.close()
    }

    @Test fun xmlUsesTheExistingContainerNamesAndNormalSongChildren() = testApplication {
        val ctx = fixture()
        application { dulcetModule(ctx) }
        for ((method, container) in endpoints) {
            val body = client.get("/rest/$method?u=reader&p=reader-password&v=1.16.1&c=test&id=${ctx.id("Seed")}").bodyAsText()
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(body))).documentElement
            assertEquals("subsonic-response", root.tagName)
            assertEquals("ok", root.getAttribute("status"))
            val songs = root.getElementsByTagName(container).item(0).childNodes
            val titles = (0 until songs.length).map { songs.item(it) as org.w3c.dom.Element }.map {
                assertEquals("song", it.tagName)
                assertEquals("false", it.getAttribute("isDir"))
                assertTrue(it.getAttribute("id").isNotEmpty())
                assertTrue(it.getAttribute("albumId").isNotEmpty())
                it.getAttribute("title")
            }
            assertEquals(neighbours, titles.toSet())
            assertEquals(4, titles.size)
        }
        ctx.db.close()
    }
}
