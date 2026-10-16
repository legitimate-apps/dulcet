package com.legitimateapps.dulcet.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class IdentityJournalTest {
    @Test fun musicBrainzMergeKeepsFirstIssuedAlbumEvenWhenReleaseKeyAlreadyNamesNewerAlbum() {
        val ctx = Fixtures.context(createTempDirectory("dulcet-mbz-merge"))
        try {
            val olderFile = ctx.config.musicFolder.resolve("older.flac")
            val newerFile = ctx.config.musicFolder.resolve("newer.flac")
            val release = "268975b8-15dd-4a2c-aa14-c357095fab17"
            Fixtures.track(olderFile, tags=mapOf("album" to "Older"))
            ctx.scanner.scan()
            val older = ctx.db.read { string("SELECT id FROM album")!! }
            Fixtures.track(newerFile, seconds=0.7, tags=mapOf("album" to "Newer", "musicbrainzalbumid" to release))
            ctx.scanner.scan()
            val newer = ctx.db.read { string("SELECT id FROM album WHERE name='Newer'")!! }
            val user = ctx.users.create("mbz", "pw", false)
            ctx.db.write {
                execute("INSERT INTO annotation VALUES(?,'album',?,9,3,2,10)", user.id, older)
                execute("INSERT INTO annotation VALUES(?,'album',?,5,4,7,11)", user.id, newer)
                execute("INSERT INTO library_item VALUES(?,'album',?,8)", user.id, older)
                execute("INSERT INTO library_item VALUES(?,'album',?,6)", user.id, newer)
            }
            Fixtures.track(olderFile, tags=mapOf("album" to "Different title", "musicbrainzalbumid" to release))
            Files.setLastModifiedTime(olderFile, FileTime.fromMillis(System.currentTimeMillis()+5000))
            ctx.scanner.scan()
            ctx.db.read {
                assertEquals(1L, long("SELECT COUNT(*) FROM album"))
                assertEquals(older, string("SELECT id FROM album"))
                assertEquals(2L, long("SELECT COUNT(*) FROM track WHERE album_id=?", older))
                assertEquals(listOf(5L,3L,9L,11L), single("SELECT * FROM annotation WHERE item_id=?", older) {
                    listOf(it.long("starred"),it.long("rating"),it.long("play_count"),it.long("played"))
                })
                assertEquals(6L, long("SELECT added FROM library_item WHERE item_id=?", older))
                assertEquals("merged", string("SELECT reason FROM api_identity WHERE id=?", newer))
                assertEquals("[\"$older\"]", string("SELECT replaced_by FROM api_identity WHERE id=?", newer))
                val tombstone = long("SELECT MAX(seq) FROM api_journal WHERE id=? AND reason='merged'", newer)
                assertTrue(long("SELECT MAX(seq) FROM api_journal WHERE id=? AND type='itemState'", older) < tombstone)
                assertEquals(0L, long("SELECT COUNT(*) FROM annotation WHERE item_id=?", newer))
            }
            ctx.scanner.scan()
            assertEquals(older, ctx.db.read { string("SELECT id FROM album") })
        } finally { ctx.db.close() }
    }

    @Test fun differentMusicBrainzReleasesKeepDistinctAlbumIdentities() {
        val ctx = Fixtures.context(createTempDirectory("dulcet-mbz-distinct"))
        try {
            Fixtures.track(ctx.config.musicFolder.resolve("one.flac"), tags=mapOf("album" to "Same title", "musicbrainzalbumid" to "268975b8-15dd-4a2c-aa14-c357095fab17"))
            Fixtures.track(ctx.config.musicFolder.resolve("two.flac"), seconds=0.7, tags=mapOf("album" to "Same title", "musicbrainzalbumid" to "6fa12f00-906a-4b6d-92f6-bef89fc2e876"))
            ctx.scanner.scan()
            assertEquals(2L, ctx.db.read { long("SELECT COUNT(*) FROM album") })
            assertEquals(0L, ctx.db.read { long("SELECT COUNT(*) FROM api_identity WHERE type='album' AND reason='merged'") })
        } finally { ctx.db.close() }
    }
    @Test fun retagAndMoveKeepAudioAndAlbumIdentity() {
        val ctx=Fixtures.context(createTempDirectory("dulcet-identity"))
        val first=ctx.config.musicFolder.resolve("one.flac")
        val second=ctx.config.musicFolder.resolve("two.flac")
        Fixtures.track(first,seconds=0.5,tags=mapOf("title" to "One","album" to "Old","artist" to "Artist"))
        Fixtures.track(second,seconds=0.7,tags=mapOf("title" to "Two","album" to "Old","artist" to "Artist"))
        ctx.scanner.scan()
        val ids=ctx.db.read { query("SELECT path,id FROM track") { it.string("path") to it.string("id") }.toMap() }
        val album=ctx.db.read { string("SELECT id FROM album")!! }
        val artist=ctx.db.read { string("SELECT id FROM artist")!! }
        assertTrue(ids.values.all { it.matches(Regex("[0-9a-f-]{36}")) })
        Fixtures.track(first,seconds=0.5,tags=mapOf("title" to "Corrected","album" to "Corrected album","artist" to "Corrected artist"))
        Fixtures.track(second,seconds=0.7,tags=mapOf("title" to "Two","album" to "Corrected album","artist" to "Corrected artist"))
        Files.setLastModifiedTime(first,FileTime.fromMillis(System.currentTimeMillis()+5000))
        Files.setLastModifiedTime(second,FileTime.fromMillis(System.currentTimeMillis()+5000))
        Files.move(first,ctx.config.musicFolder.resolve("moved.flac"))
        ctx.scanner.scan()
        assertEquals(ids["one.flac"],ctx.db.read { string("SELECT id FROM track WHERE title='Corrected'") })
        assertEquals(album,ctx.db.read { string("SELECT id FROM album") })
        assertEquals(artist,ctx.db.read { string("SELECT id FROM artist") })
        assertEquals(0L,ctx.db.read { long("SELECT COUNT(*) FROM api_identity WHERE type='album' AND deleted_at IS NOT NULL") })
    }

    @Test fun sourceCountsAndFolderArtworkChangesAreJournaled() {
        val ctx=Fixtures.context(createTempDirectory("dulcet-art-journal"))
        val music=ctx.config.musicFolder
        Fixtures.track(music.resolve("Album/one.flac"))
        Fixtures.jpeg(music.resolve("Album/cover.jpg"),64)
        ctx.scanner.scan()
        val id=ctx.db.read { string("SELECT id FROM track")!! }
        val sourceRevision=ctx.journal.revision("source","1")
        val revision=ctx.journal.revision("track",id)
        val before=ctx.journal.head()
        Fixtures.jpeg(music.resolve("Album/cover.jpg"),128)
        Files.setLastModifiedTime(music.resolve("Album/cover.jpg"),FileTime.fromMillis(System.currentTimeMillis()+5000))
        ctx.scanner.scan(false)
        assertNotEquals(revision,ctx.journal.revision("track",id))
        assertTrue(ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='track' AND id=?",before,id) }>0)
        Fixtures.track(music.resolve("Album/two.flac"),seconds=0.7)
        ctx.scanner.scan(false)
        assertNotEquals(sourceRevision,ctx.journal.revision("source","1"))
    }

    @Test fun albumMergeMovesStateBeforeTombstoneAndKeepsSurvivorRating() {
        val ctx=Fixtures.context(createTempDirectory("dulcet-merge"))
        val a=ctx.config.musicFolder.resolve("a.flac")
        val b=ctx.config.musicFolder.resolve("b.flac")
        Fixtures.track(a,tags=mapOf("album" to "A"))
        Fixtures.track(b,seconds=0.7,tags=mapOf("album" to "B"))
        ctx.scanner.scan()
        val old=ctx.db.read { string("SELECT id FROM album WHERE name='A'")!! }
        val survivor=ctx.db.read { string("SELECT id FROM album WHERE name='B'")!! }
        val user=ctx.users.create("merge","pw",false)
        ctx.db.write {
            execute("INSERT INTO annotation VALUES(?,'album',?,5,3,7,11)",user.id,old)
            execute("INSERT INTO annotation VALUES(?,'album',?,9,4,2,10)",user.id,survivor)
            execute("INSERT INTO library_item VALUES(?,'album',?,6)",user.id,old)
            execute("INSERT INTO library_item VALUES(?,'album',?,8)",user.id,survivor)
        }
        val clocks=ctx.db.read { query("SELECT field,MAX(at) AS at FROM api_state_clock WHERE item_id IN (?,?) GROUP BY field",old,survivor) { it.string("field") to it.long("at") }.toMap() }
        Fixtures.track(a,tags=mapOf("album" to "B"))
        Files.setLastModifiedTime(a,FileTime.fromMillis(System.currentTimeMillis()+5000))
        ctx.scanner.scan()
        ctx.db.read {
            assertEquals(1L,long("SELECT COUNT(*) FROM album"))
            assertEquals(survivor,string("SELECT id FROM album"))
            val state=single("SELECT * FROM annotation WHERE item_id=?",survivor) { listOf(it.long("starred"),it.long("rating"),it.long("play_count"),it.long("played")) }
            assertEquals(listOf(5L,4L,9L,11L),state)
            assertEquals(6L,long("SELECT added FROM library_item WHERE item_id=?",survivor))
            assertEquals("merged",string("SELECT reason FROM api_identity WHERE id=?",old))
            assertEquals("[\"$survivor\"]",string("SELECT replaced_by FROM api_identity WHERE id=?",old))
            val tombstone=long("SELECT MAX(seq) FROM api_journal WHERE id=? AND reason='merged'",old)
            assertTrue(long("SELECT MAX(seq) FROM api_journal WHERE id=? AND type='itemState'",survivor)<tombstone)
            assertEquals(0L,long("SELECT COUNT(*) FROM annotation WHERE item_id=?",old))
            assertEquals(clocks,query("SELECT field,at FROM api_state_clock WHERE item_id=?",survivor) { it.string("field") to it.long("at") }.toMap())
        }
    }
}
