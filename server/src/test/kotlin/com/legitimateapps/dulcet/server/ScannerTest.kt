package com.legitimateapps.dulcet.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerTest {
    private val root: Path = createTempDirectory("dulcet-scan")
    private val ctx = Fixtures.context(root)
    private val music = ctx.config.musicFolder

    private fun seed() {
        val album = music.resolve("Ensemble/Shared Record")
        Fixtures.track(album.resolve("Disc 1/01 - One.flac"), tags = mapOf("title" to "One", "artist" to "Alpha Ensemble feat. Delta", "album_artist" to "Alpha Ensemble;Beta Ensemble", "album" to "Shared Record", "track" to "1", "disc" to "1/2", "date" to "2019-05-01", "MUSICBRAINZ_ALBUMID" to "6a9cdf2b-0000-4000-8000-000000000001"))
        Fixtures.track(album.resolve("Disc 2/01 - Two.flac"), tags = mapOf("title" to "Two", "artist" to "Beta Ensemble", "album_artist" to "Alpha Ensemble;Beta Ensemble", "album" to "Shared Record", "track" to "1", "disc" to "2/2", "MUSICBRAINZ_ALBUMID" to "6a9cdf2b-0000-4000-8000-000000000001"))
        Fixtures.jpeg(album.resolve("cover.jpg"))
        Files.writeString(album.resolve("Disc 2/01 - Two.lrc"), "[offset:+100]\n[00:01.50]hello\n")
        Fixtures.track(music.resolve("Mix/01 - A.mp3"), tags = mapOf("title" to "A", "artist" to "Solo / Duo", "album" to "Mix", "compilation" to "1"))
        Fixtures.track(music.resolve("Mix/02 - B.mp3"), tags = mapOf("title" to "B", "artist" to "Other", "album" to "Mix", "compilation" to "1"))
        Fixtures.track(music.resolve(".hidden/skip.mp3"), tags = mapOf("title" to "Hidden"))
        Fixtures.track(music.resolve("Loose/untagged.ogg"))
    }

    @Test
    fun indexesAlbumsArtistsCreditsDiscsArtAndSidecars() {
        seed()
        val result = ctx.scanner.scan()!!
        assertEquals(5, result.added, "hidden folders are skipped")
        ctx.db.read {
            val shared = single("SELECT id, album_artist, song_count, folder_art, year, mbz_album_id FROM album WHERE name = 'Shared Record'") {
                listOf(it.string("id"), it.string("album_artist"), it.int("song_count").toString(), it.stringOrNull("folder_art"), it.intOrNull("year")?.toString(), it.stringOrNull("mbz_album_id"))
            }!!
            assertEquals("Alpha Ensemble • Beta Ensemble", shared[1])
            assertEquals("2", shared[2], "both discs group into one album")
            assertEquals("Ensemble/Shared Record/cover.jpg", shared[3], "disc folders fall back to the album folder's art")
            assertEquals("2019", shared[4])
            assertEquals(2, long("SELECT COUNT(*) FROM album_artist WHERE album_id = ?", shared[0]).toInt())
            assertEquals(listOf(1, 2), query("SELECT disc_no FROM track WHERE album_id = ? ORDER BY disc_no", shared[0]) { it.int("disc_no") })
            assertEquals(listOf("Alpha Ensemble", "Delta"), query("SELECT name FROM track_artist WHERE role = 'artist' AND track_id = (SELECT id FROM track WHERE title = 'One') ORDER BY position") { it.string("name") })
            assertEquals("Ensemble/Shared Record/Disc 2/01 - Two.lrc", string("SELECT lyrics_sidecar FROM track WHERE title = 'Two'"))
            val mix = single("SELECT album_artist, compilation, song_count FROM album WHERE name = 'Mix'") { Triple(it.string("album_artist"), it.bool("compilation"), it.int("song_count")) }!!
            assertEquals(Triple(VARIOUS_ARTISTS, true, 2), mix)
            assertEquals("Solo / Duo", string("SELECT artist FROM track WHERE title = 'A'"), "a slash never splits an artist")
            assertEquals(UNKNOWN_ALBUM, string("SELECT album FROM track WHERE path = 'Loose/untagged.ogg'"))
            assertEquals("untagged", string("SELECT title FROM track WHERE path = 'Loose/untagged.ogg'"))
        }
    }

    @Test
    fun rescansAreIncrementalAndKeepIds() {
        seed()
        ctx.scanner.scan()
        val ids = ctx.db.read { query("SELECT path, id FROM track") { it.string("path") to it.string("id") }.toMap() }
        assertEquals(ScanResultCounts(0, 0, 0, 5), counts(ctx.scanner.scan()!!), "an unchanged folder probes nothing")

        val changed = music.resolve("Mix/02 - B.mp3")
        Fixtures.track(changed, tags = mapOf("title" to "B", "artist" to "Other", "album" to "Mix", "compilation" to "1", "genre" to "Jazz"))
        Files.setLastModifiedTime(changed, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        Files.delete(music.resolve("Loose/untagged.ogg"))
        Fixtures.track(music.resolve("Mix/03 - C.mp3"), tags = mapOf("title" to "C", "artist" to "Other", "album" to "Mix", "compilation" to "1"))
        assertEquals(ScanResultCounts(1, 1, 1, 3), counts(ctx.scanner.scan()!!))

        val after = ctx.db.read { query("SELECT path, id FROM track") { it.string("path") to it.string("id") }.toMap() }
        assertEquals(ids["Mix/02 - B.mp3"], after["Mix/02 - B.mp3"], "a retagged file keeps its id")
        assertNull(after["Loose/untagged.ogg"])
        ctx.db.read {
            assertEquals("Jazz", string("SELECT genre FROM track_genre WHERE track_id = ?", after["Mix/02 - B.mp3"]))
            assertEquals(0L, long("SELECT COUNT(*) FROM album WHERE name = ?", UNKNOWN_ALBUM), "an album whose last file vanished goes")
            assertNotNull(string("SELECT value FROM meta WHERE key = 'last_scan'"))
        }
    }

    @Test
    fun embeddedPictureAndLyricsAreRead() {
        val picture = root.resolve("pic.jpg").also { Fixtures.jpeg(it, 64) }
        Fixtures.track(music.resolve("Art/01 - Pic.mp3"), tags = mapOf("title" to "Pic", "artist" to "P", "album" to "Art", "lyrics-eng" to "[00:02.00]sung"), picture = picture)
        ctx.scanner.scan()
        ctx.db.read {
            assertTrue(single("SELECT has_embedded_art FROM track WHERE title = 'Pic'") { it.bool("has_embedded_art") }!!)
        }
        val id = ctx.db.read { string("SELECT id FROM track WHERE title = 'Pic'")!! }
        val art = ctx.artwork.image("mf-$id", null)!!
        assertEquals("image/jpeg", art.contentType)
        val lyrics = ctx.lyrics.forTrack(id)!!
        assertEquals(1, lyrics.size, "embedded lyrics found: $lyrics")
        assertEquals(2000L, lyrics.single().lines.single().startMillis)
    }

    @Test fun retagKeepsExistingIdsAndAllUserData() {
        val file = music.resolve("song.flac")
        Fixtures.track(file, tags = mapOf("title" to "Typo", "album" to "Old"))
        ctx.scanner.scan()
        val user = ctx.users.create("reader", "pw", false)
        val id = ctx.db.read { string("SELECT id FROM track")!! }
        val album = ctx.db.read { string("SELECT id FROM album")!! }
        ctx.db.write {
            for ((type, item) in listOf("song" to id, "album" to album)) {
                execute("INSERT INTO annotation VALUES (?,?,?,123,4,7,456)", user.id, type, item)
                execute("INSERT INTO library_item VALUES (?,?,?,123)", user.id, type, item)
            }
            execute("INSERT INTO playlist VALUES ('pl',?,'Mine','',0,1,1)", user.id)
            execute("INSERT INTO playlist_track VALUES ('pl',0,?)", id)
            execute("INSERT INTO lyrics_cache VALUES (?,'test','found',NULL,'cached',1)", id)
        }
        Fixtures.track(file, tags = mapOf("title" to "Corrected", "album" to "New"))
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000))
        ctx.scanner.scan()
        assertEquals(id, ctx.db.read { string("SELECT id FROM track") })
        val newAlbum = ctx.db.read { string("SELECT id FROM album")!! }
        ctx.db.read {
            for ((type, item) in listOf("song" to id, "album" to newAlbum)) {
                assertEquals(4L, long("SELECT rating FROM annotation WHERE item_type = ? AND item_id = ?", type, item))
                assertEquals(7L, long("SELECT play_count FROM annotation WHERE item_type = ? AND item_id = ?", type, item))
                assertEquals(123L, long("SELECT starred FROM annotation WHERE item_type = ? AND item_id = ?", type, item))
                assertEquals(1L, long("SELECT COUNT(*) FROM library_item WHERE item_type = ? AND item_id = ?", type, item))
            }
            assertEquals(id, string("SELECT track_id FROM playlist_track"))
            assertEquals("cached", string("SELECT plain FROM lyrics_cache WHERE track_id = ?", id))
        }
    }

    @Test fun missingAndSuddenlyEmptyRootsKeepCatalogAndScanClock() {
        Fixtures.track(music.resolve("song.flac"))
        ctx.scanner.scan()
        val stamp = ctx.lastScanMillis()
        Files.move(music, root.resolve("unmounted"))
        ctx.scanner.scan()
        assertEquals(1L, ctx.db.read { long("SELECT COUNT(*) FROM track") })
        assertEquals(stamp, ctx.lastScanMillis())
        Files.createDirectory(music)
        ctx.scanner.scan(force = false)
        assertEquals(1L, ctx.db.read { long("SELECT COUNT(*) FROM track") })
        assertEquals(stamp, ctx.lastScanMillis())
    }

    private data class ScanResultCounts(val added: Int, val updated: Int, val removed: Int, val unchanged: Int)
    private fun counts(result: com.legitimateapps.dulcet.server.scan.ScanResult) = ScanResultCounts(result.added, result.updated, result.removed, result.unchanged)
}
