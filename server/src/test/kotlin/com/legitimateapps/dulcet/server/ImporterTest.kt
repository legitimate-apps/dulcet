package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.importer.ImportOptions
import com.legitimateapps.dulcet.server.importer.NavidromeImporter
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The importer against a real OpenSubsonic server in-process: a second Dulcet Server stands in for
 * Navidrome (both speak the same API). The two libraries hold the same music under different
 * layouts, so each matching route — path suffix, MusicBrainz id, tags — is exercised.
 */
class ImporterTest {
    private fun library(music: Path, layout: Map<String, Map<String, String>>) =
        layout.forEach { (path, tags) -> Fixtures.track(music.resolve(path), seconds = 3.0, tags = tags) }

    private val pathSong = mapOf("title" to "By Path", "artist" to "Ada", "album" to "First", "track" to "1")
    private val mbzSong = mapOf("title" to "By MBZ", "artist" to "Ada", "album" to "First", "track" to "2", "MUSICBRAINZ_TRACKID" to "11111111-2222-3333-4444-555555555555")
    private val tagSong = mapOf("title" to "By Tags", "artist" to "Ada", "album" to "First", "track" to "3")
    private val missingSong = mapOf("title" to "Only There", "artist" to "Bo", "album" to "Elsewhere", "track" to "1")

    @Test
    fun importsStarsRatingsPlaysAndPlaylistsThroughEachMatchingRoute() {
        val root = createTempDirectory("dulcet-import")
        val source = Fixtures.context(root.resolve("source"))
        library(source.config.musicFolder, mapOf(
            "Ada/First/01 - By Path.mp3" to pathSong,
            "Ada/First/02 - By MBZ.mp3" to mbzSong,
            "Ada/First/03 - By Tags.mp3" to tagSong,
            "Bo/Elsewhere/01 - Only There.mp3" to missingSong,
        ))
        source.scanner.scan()
        val remoteUser = source.users.create("someone", "remote-password", isAdmin = false)

        val target = Fixtures.context(root.resolve("target"))
        library(target.config.musicFolder, mapOf(
            "Collection/Ada/First/01 - By Path.mp3" to pathSong, // same tail, deeper root
            "Loose/renamed-mbz.mp3" to mbzSong, // a different path, same recording id
            "Loose/renamed-tags.mp3" to tagSong, // a different path, tags only
        ))
        target.scanner.scan()
        val localUser = target.users.create("me", "local-password", isAdmin = false)

        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") { dulcetModule(source) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val ids = source.db.read { query("SELECT id, title FROM track") { it.string("title") to it.string("id") }.toMap() }
            val albumId = source.db.read { string("SELECT id FROM album WHERE name = 'First'") }!!
            val http = java.net.http.HttpClient.newHttpClient()
            fun remote(method: String, vararg params: Pair<String, String>) {
                val query = (listOf("u" to "someone", "p" to "remote-password", "v" to "1.16.1", "c" to "test", "f" to "json") + params)
                    .joinToString("&") { "${it.first}=${java.net.URLEncoder.encode(it.second, Charsets.UTF_8)}" }
                val body = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:$port/rest/$method?$query")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body()
                check("\"status\":\"ok\"" in body) { "$method failed: $body" }
            }
            remote("star", "id" to ids.getValue("By Path"), "id" to ids.getValue("Only There"), "albumId" to albumId)
            remote("setRating", "id" to ids.getValue("By MBZ"), "rating" to "4")
            repeat(3) { remote("scrobble", "id" to ids.getValue("By Tags")) }
            remote("createPlaylist", "name" to "Mix", "songId" to ids.getValue("By Tags"), "songId" to ids.getValue("Only There"), "songId" to ids.getValue("By Path"))

            val options = ImportOptions("http://127.0.0.1:$port", "someone", "remote-password", localUser.id)
            val dry = NavidromeImporter(target).run(options.copy(dryRun = true))
            assertEquals(0, target.db.read { long("SELECT COUNT(*) FROM annotation") }, "a dry run writes nothing")
            assertEquals(1, dry.matchedByPath)

            val report = NavidromeImporter(target).run(options)
            assertEquals(4, report.songsSeen, report.summary())
            assertEquals(1, report.matchedByPath, report.summary())
            assertEquals(1, report.matchedByMbz, report.summary())
            assertEquals(1, report.matchedByTags, report.summary())
            assertEquals(1, report.unmatched, report.summary())

            val local = target.db.read { query("SELECT id, title FROM track") { it.string("title") to it.string("id") }.toMap() }
            fun annotation(id: String, column: String) = target.db.read {
                single("SELECT $column AS v FROM annotation WHERE user_id = ? AND item_id = ?", localUser.id, id) { it.stringOrNull("v") }
            }
            assertTrue(annotation(local.getValue("By Path"), "starred") != null, "the path-matched song is starred")
            assertEquals("4", annotation(local.getValue("By MBZ"), "rating"))
            assertEquals("3", annotation(local.getValue("By Tags"), "play_count"))
            val localAlbum = target.db.read { string("SELECT id FROM album WHERE name = 'First'") }!!
            assertTrue(annotation(localAlbum, "starred") != null, "the album star follows the album")
            val entries = target.db.read {
                query("SELECT pt.track_id FROM playlist p JOIN playlist_track pt ON pt.playlist_id = p.id WHERE p.owner_id = ? AND p.name = 'Mix' ORDER BY pt.position", localUser.id) { it.string("track_id") }
            }
            assertEquals(listOf(local.getValue("By Tags"), local.getValue("By Path")), entries, "order kept, the missing song dropped")

            // Local edits survive another import.
            target.db.write {
                execute("UPDATE annotation SET rating = 5 WHERE item_id = ?", local.getValue("By MBZ"))
                execute("INSERT INTO playlist_track SELECT id,2,? FROM playlist WHERE name = 'Mix'", local.getValue("By MBZ"))
                execute("UPDATE playlist SET changed = changed + 1 WHERE name = 'Mix'")
            }
            NavidromeImporter(target).run(options)
            assertEquals("3", annotation(local.getValue("By Tags"), "play_count"))
            assertEquals(1, target.db.read { long("SELECT COUNT(*) FROM playlist WHERE owner_id = ?", localUser.id) })
            assertEquals("5", annotation(local.getValue("By MBZ"), "rating"))
            assertEquals(3L, target.db.read { long("SELECT COUNT(*) FROM playlist_track") })
            assertTrue(remoteUser.id.isNotEmpty())
        } finally {
            server.stop(0, 0)
            root.toFile().deleteRecursively()
        }
    }

    init {
        check(Files.isExecutable(Path.of(Fixtures.ffmpeg)) || Fixtures.ffmpeg == "ffmpeg")
    }
}
