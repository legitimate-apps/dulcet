package com.legitimateapps.dulcet.server

import org.sqlite.ProgressHandler
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerScaleTest {
    @Test fun oneAddedSongDoesNotCompareEveryReleaseWithEveryOldAlbumInSql() {
        val root = createTempDirectory("dulcet-scan-scale")
        val ctx = Fixtures.context(root)
        try {
            val music = ctx.config.musicFolder
            val template = music.resolve("template.flac")
            Fixtures.track(template, tags = mapOf("album" to "Template", "musicbrainzalbumid" to "release-template"))
            ctx.scanner.scan()
            val catalogSize = 400
            // Clone a genuine scanned row and matching files. Only the added song needs probing;
            // seeding a large unchanged catalog must not start hundreds of ffmpeg processes.
            ctx.db.write {
                val columns = query("PRAGMA table_info(track)") { it.string("name") }
                val albumColumns = query("PRAGMA table_info(album)") { it.string("name") }
                for (index in 1 until catalogSize) {
                    val path = "unchanged-$index.flac"
                    val file = Files.copy(template, music.resolve(path))
                    val id = "track-$index"
                    val album = "album-$index"
                    val release = "release-$index"
                    val substitutions = mapOf(
                        "id" to "?", "path" to "?", "mtime" to "?", "album" to "?",
                        "album_id" to "?", "mbz_album_id" to "?",
                    )
                    val selected = columns.joinToString(",") { substitutions[it] ?: it }
                    execute("INSERT INTO track SELECT $selected FROM track WHERE path='template.flac'",
                        id, path, Files.getLastModifiedTime(file).toMillis(), "Album $index", album, release)
                    val albumSelected = albumColumns.joinToString(",") {
                        when (it) { "id", "name", "mbz_album_id" -> "?"; else -> it }
                    }
                    execute("INSERT INTO album SELECT $albumSelected FROM album WHERE name='Template'", album, "Album $index", release)
                    execute("INSERT INTO track_identity SELECT ?,?,fingerprint FROM track_identity WHERE path='template.flac'", path, id)
                    execute("INSERT INTO track_artist SELECT ?,role,artist_id,name,position FROM track_artist WHERE track_id=(SELECT id FROM track WHERE path='template.flac')", id)
                    execute("INSERT INTO api_group_identity VALUES('album',?,?)", album, album)
                    execute("INSERT INTO api_album_issued(id) VALUES(?)", album)
                }
            }
            val before = ctx.db.read { query("SELECT id,album_id FROM track") { it.string("id") to it.string("album_id") }.toMap() }
            Fixtures.track(music.resolve("added.flac"), seconds = 0.7, tags = mapOf("album" to "Added", "musicbrainzalbumid" to "release-added"))
            val instructions = AtomicLong()
            val quantum = 1
            ctx.db.read {
                ProgressHandler.setHandler(connection, quantum, object : ProgressHandler() {
                    override fun progress(): Int { instructions.addAndGet(quantum.toLong()); return 0 }
                })
            }
            val result = try { ctx.scanner.scan(force = false)!! }
            finally { ctx.db.read { ProgressHandler.clearHandler(connection) } }
            assertEquals(1, result.added)
            assertEquals(catalogSize, result.unchanged)
            assertEquals(0, result.updated)
            assertEquals(0, result.removed)
            assertEquals(0, result.failed)
            ctx.db.read {
                assertEquals(catalogSize + 1L, long("SELECT COUNT(*) FROM track"))
                assertEquals(catalogSize + 1L, long("SELECT COUNT(*) FROM album"))
                assertEquals(before, query("SELECT id,album_id FROM track WHERE path<>'added.flac'") { it.string("id") to it.string("album_id") }.toMap())
                assertEquals(0L, long("SELECT COUNT(*) FROM api_identity WHERE type='album' AND deleted_at IS NOT NULL"))
            }
            // SQLite VM work is deterministic across host load. This allows ample regroup/journal
            // work for 401 albums, but rejects the former quadratic point-query reconciliation.
            println("Incremental scan SQLite VM instructions: ${instructions.get()}")
            assertTrue(instructions.get() < 2_000_000, "incremental scan used ${instructions.get()} SQLite VM instructions for $catalogSize unchanged albums")
        } finally {
            ctx.db.close()
            root.toFile().deleteRecursively()
        }
    }
}
