package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.v1.Journal
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class JournalTest {
    @Test fun foreignTimelineWithAnIndependentSigningKeyExpiresWhileLocalTamperingIsInvalid() {
        Db.inMemory().use { firstDb ->
            Db.inMemory().use { secondDb ->
                val first = Journal(firstDb, createTempDirectory("dulcet-first-key"))
                val second = Journal(secondDb, createTempDirectory("dulcet-second-key"))
                val cursor = first.cursor("u", "track")
                assertTrue(assertFailsWith<Journal.CursorException> { second.decodeCursor("u", "track", cursor) }.expired)
                assertFalse(assertFailsWith<Journal.CursorException> { first.decodeCursor("u", "track", cursor.dropLast(1)+"!") }.expired)
            }
        }
    }
    @Test fun mutationsInReadHelpersStillPersistContinuityEvidence() {
        val root = createTempDirectory("dulcet-read-write")
        val file = root.resolve("library.db")
        var instance = ""
        Db.open(file).use { db ->
            val journal = Journal(db, root)
            instance = journal.instanceId
            db.read { execute("INSERT INTO music_folder VALUES(1,'Direct read mutation','music')") }
            db.read { db.write { execute("UPDATE music_folder SET name='Nested write'") } }
        }
        Db.open(file).use { db ->
            assertEquals(instance, Journal(db, root).instanceId)
            assertEquals("Nested write", db.read { string("SELECT name FROM music_folder") })
        }
    }
    @Test fun shortenedRetentionCompactsLaterWritesButNotReads() {
        Db.inMemory().use { db ->
            val journal = Journal(db, createTempDirectory("dulcet-retention"))
            db.write { execute("INSERT INTO music_folder VALUES(1,'Before','music')") }
            val oldHead = journal.head()
            val cursor = journal.cursor("u", "source", oldHead - 1)
            db.write { execute("UPDATE api_journal SET at=?", System.currentTimeMillis() - 10_000) }
            journal.retentionMillis = 1_000
            assertEquals(0L, journal.floor())
            assertEquals(oldHead, journal.head())
            db.write { execute("UPDATE music_folder SET name='After'") }
            assertEquals(oldHead, journal.floor())
            assertTrue(journal.head() > oldHead)
            assertEquals(1L, db.read { long("SELECT COUNT(*) FROM api_journal") })
            assertTrue(assertFailsWith<Journal.CursorException> { journal.decodeCursor("u", "source", cursor) }.expired)
        }
    }
    @Test fun journalAndNestedWritesRollbackWithTheirState() {
        Db.inMemory().use { db ->
            db.write { execute("INSERT INTO user VALUES('u','User',X'01',0,1)") }
            val journal = Journal(db,createTempDirectory("dulcet-journal"))
            val head = journal.head()
            assertFailsWith<IllegalStateException> {
                db.write {
                    db.write { execute("INSERT INTO annotation VALUES('u','song','t',1,2,0,NULL)") }
                    error("rollback")
                }
            }
            assertEquals(head,journal.head())
            assertEquals(0L,db.read { long("SELECT COUNT(*) FROM annotation") })
            db.write {
                execute("INSERT INTO annotation VALUES('u','song','t',1,2,0,NULL)")
                assertFailsWith<IllegalStateException> { db.write { execute("UPDATE annotation SET rating=3"); error("savepoint") } }
                assertEquals(2L,long("SELECT rating FROM annotation"))
            }
            assertEquals(head+1,journal.head())
            assertEquals("itemState",db.read { string("SELECT type FROM api_journal ORDER BY seq DESC LIMIT 1") })
            assertNotEquals(journal.revision("itemState","t","u"),journal.revision("itemState","t","other"))
        }
    }

    @Test fun cursorBindsUserFilterTimelineAndHead() {
        Db.inMemory().use { db ->
            val journal = Journal(db,createTempDirectory("dulcet-journal"))
            val cursor = journal.cursor("u","track,album")
            assertEquals(journal.head(),journal.decodeCursor("u","album,track",cursor))
            assertFalse(assertFailsWith<Journal.CursorException> { journal.decodeCursor("v","track,album",cursor) }.expired)
            assertFalse(assertFailsWith<Journal.CursorException> { journal.decodeCursor("u","track",cursor) }.expired)
            assertTrue(assertFailsWith<Journal.CursorException> { journal.decodeCursor("u","track",journal.cursor("u","track",journal.head()+1)) }.expired)
            assertFailsWith<Journal.CursorException> { journal.decodeCursor("u","track,album",cursor.dropLast(1)+"!") }
        }
    }

    @Test fun restartKeepsTimelineButRestoreAndCopyRotateIt() {
        val folder=createTempDirectory("dulcet-timeline")
        val file=folder.resolve("library.db")
        val backup=folder.resolve("backup.db")
        var first=""
        Db.open(file).use { db ->
            val journal=Journal(db,folder)
            first=journal.instanceId
            db.write { execute("INSERT INTO music_folder VALUES(1,'Music','music')") }
            java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.prepareStatement("VACUUM INTO ?").use { it.setString(1,backup.toString()); it.executeUpdate() } }
            db.write { execute("UPDATE music_folder SET name='Renamed'") }
        }
        Db.open(file).use { assertEquals(first,Journal(it,folder).instanceId) }
        // Restore to the existing inode too, so detection does not rely only on file replacement.
        Files.newInputStream(backup).use { input -> Files.newOutputStream(file).use { input.copyTo(it) } }
        var restored=""
        Db.open(file).use { restored=Journal(it,folder).instanceId; assertNotEquals(first,restored) }
        val copy=folder.resolve("copied.db")
        Files.copy(file,copy)
        Db.open(copy).use { assertNotEquals(restored,Journal(it,folder).instanceId) }
    }

    @Test fun sharedPlaylistVisibilityInvalidatesCursorsWithoutLeakingPrivateDeletes() {
        Db.inMemory().use { db ->
            val journal=Journal(db,createTempDirectory("dulcet-journal"))
            db.write {
                execute("INSERT INTO user VALUES('u','User',X'01',0,1)")
                execute("INSERT INTO playlist VALUES('p','u','Shared',NULL,1,1,1)")
            }
            assertNotEquals("r_${journal.instanceId}_0",journal.revision("playlist","p","viewer"))
            val cursor=journal.cursor("viewer","playlist")
            db.write { execute("UPDATE playlist SET public=0 WHERE id='p'") }
            assertTrue(assertFailsWith<Journal.CursorException> { journal.decodeCursor("viewer","playlist",cursor) }.expired)
            db.write { execute("INSERT INTO playlist_track VALUES('p',0,'t')") }
            val head=journal.head()
            db.write { execute("DELETE FROM playlist WHERE id='p'") }
            assertEquals(0L,db.read { long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND user_id IS NULL",head) })
        }
    }

    @Test fun retentionPersistsFloorAndDoesNotReuseSequence() {
        Db.inMemory().use { db ->
            val journal=Journal(db,createTempDirectory("dulcet-journal"))
            db.write { execute("INSERT INTO music_folder VALUES(1,'Music','music')") }
            val head=journal.head()
            val revision=journal.revision("source","1")
            val cursor=journal.cursor("u","source",head-1)
            db.write { execute("UPDATE api_journal SET at=0") }
            journal.prune()
            assertEquals(revision,journal.revision("source","1"))
            assertEquals(head,journal.floor())
            assertEquals(head,journal.head())
            assertTrue(assertFailsWith<Journal.CursorException> { journal.decodeCursor("u","source",cursor) }.expired)
            db.write { execute("UPDATE music_folder SET name='Next'") }
            assertTrue(journal.head()>head)
            assertEquals(1L,db.read { long("SELECT COUNT(*) FROM api_identity") })
        }
    }
}
