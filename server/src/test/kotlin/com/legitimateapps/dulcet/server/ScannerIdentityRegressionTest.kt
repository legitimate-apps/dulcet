package com.legitimateapps.dulcet.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ScannerIdentityRegressionTest {
    @Test fun splitCreatesSeparateSuccessorsAndRetainsAmbiguousSavedState() {
        val root = createTempDirectory("dulcet-album-split")
        val ctx = Fixtures.context(root)
        try {
            val first = ctx.config.musicFolder.resolve("first.flac")
            val second = ctx.config.musicFolder.resolve("second.flac")
            Fixtures.track(first, seconds = 0.5, tags = mapOf("album" to "Whole"))
            Fixtures.track(second, seconds = 0.7, tags = mapOf("album" to "Whole"))
            ctx.scanner.scan()
            val before = ctx.db.read { query("SELECT path,id FROM track") { it.string("path") to it.string("id") }.toMap() }
            val old = ctx.db.read { string("SELECT id FROM album")!! }
            val user = ctx.users.create("split", "pw", false)
            ctx.db.write {
                execute("INSERT INTO annotation VALUES(?,'album',?,5,4,7,11)", user.id, old)
                execute("INSERT INTO library_item VALUES(?,'album',?,6)", user.id, old)
            }
            Fixtures.track(first, seconds = 0.5, tags = mapOf("album" to "Left"))
            Fixtures.track(second, seconds = 0.7, tags = mapOf("album" to "Right"))
            for (file in listOf(first, second)) Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000))
            ctx.scanner.scan(force = false)
            val successors = ctx.db.read {
                assertEquals(before, query("SELECT path,id FROM track") { it.string("path") to it.string("id") }.toMap())
                val ids = query("SELECT id FROM album") { it.string("id") }.toSet()
                assertEquals(2, ids.size)
                assertFalse(old in ids)
                assertEquals("split", string("SELECT reason FROM api_identity WHERE type='album' AND id=?", old))
                assertEquals(ids, Json.parseToJsonElement(string("SELECT replaced_by FROM api_identity WHERE type='album' AND id=?", old)!!).jsonArray.map { it.jsonPrimitive.content }.toSet())
                assertEquals(4L, long("SELECT rating FROM annotation WHERE item_type='album' AND item_id=?", old))
                assertEquals(6L, long("SELECT added FROM library_item WHERE item_type='album' AND item_id=?", old))
                assertEquals(1L, long("SELECT COUNT(*) FROM annotation WHERE item_type='album'"), "an ambiguous rating is not assigned to either successor")
                ids
            }
            ctx.scanner.scan()
            assertEquals(successors, ctx.db.read { query("SELECT id FROM album") { it.string("id") }.toSet() })
            assertEquals(1L, ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE type='album' AND id=? AND reason='split'", old) })
        } finally {
            ctx.db.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun removedMemberPreventsAnOldAlbumFromClaimingACompleteReleaseRetag() {
        // Check either position: a missing first member must not hide surviving release tags,
        // and a missing later member must not be discarded when deciding completeness.
        for (removedName in listOf("first.flac", "second.flac")) {
            val root = createTempDirectory("dulcet-incomplete-release")
            val ctx = Fixtures.context(root)
            try {
                val first = ctx.config.musicFolder.resolve("first.flac")
                val second = ctx.config.musicFolder.resolve("second.flac")
                val newerFile = ctx.config.musicFolder.resolve("newer.flac")
                val release = "268975b8-15dd-4a2c-aa14-c357095fab17"
                Fixtures.track(first, seconds = 0.5, tags = mapOf("album" to "Older"))
                Fixtures.track(second, seconds = 0.7, tags = mapOf("album" to "Older"))
                ctx.scanner.scan()
                val older = ctx.db.read { string("SELECT id FROM album")!! }
                Fixtures.track(newerFile, seconds = 0.9, tags = mapOf("album" to "Newer", "musicbrainzalbumid" to release))
                ctx.scanner.scan()
                val newer = ctx.db.read { string("SELECT id FROM album WHERE name='Newer'")!! }
                assertNotEquals(older, newer)
                val removed = ctx.config.musicFolder.resolve(removedName)
                val retained = if (removed == first) second else first
                val seconds = if (retained == first) 0.5 else 0.7
                val retainedId = ctx.db.read { string("SELECT id FROM track WHERE path=?", retained.fileName.toString())!! }
                Files.delete(removed)
                Fixtures.track(retained, seconds = seconds, tags = mapOf("album" to "Retagged", "musicbrainzalbumid" to release))
                Files.setLastModifiedTime(retained, FileTime.fromMillis(System.currentTimeMillis() + 5000))
                val result = ctx.scanner.scan(force = false)!!
                assertEquals(1, result.removed)
                assertEquals(1, result.updated)
                ctx.db.read {
                    assertEquals(1L, long("SELECT COUNT(*) FROM album"))
                    assertEquals(newer, string("SELECT id FROM album"), "the incomplete older group cannot displace the existing release identity")
                    assertEquals(retainedId, string("SELECT id FROM track WHERE path=?", retained.fileName.toString()))
                    assertEquals("merged", string("SELECT reason FROM api_identity WHERE type='album' AND id=?", older))
                    assertEquals("[\"$newer\"]", string("SELECT replaced_by FROM api_identity WHERE type='album' AND id=?", older))
                }
            } finally {
                ctx.db.close()
                root.toFile().deleteRecursively()
            }
        }
    }
}
