package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.v1.Journal
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DbCheckpointTest {
    @Test fun checkpointIncludesWalAndRestoreKeepsLiveReferencesAndRotatesTimeline() {
        val root = createTempDirectory("dulcet-checkpoint")
        val file = root.resolve("library.db")
        val backup = root.resolve("checkpoint.db")
        var restoredInstance = ""
        Db.open(file).use { db ->
            val journal = Journal(db, root)
            db.write { execute("INSERT INTO music_folder VALUES(1,'Before','music')") }
            assertTrue(Files.size(root.resolve("library.db-wal")) > 0)
            val instance = journal.instanceId
            val cursor = journal.cursor("u", "source")
            val revision = journal.revision("source", "1")
            val reference = db
            db.checkpoint(backup)
            db.write { execute("UPDATE music_folder SET name='After'") }
            db.exclusive { db.restore(backup); journal.rotateInstance() }
            assertEquals("Before", reference.read { string("SELECT name FROM music_folder") })
            assertNotEquals(instance, journal.instanceId)
            assertNotEquals(revision, journal.revision("source", "1"))
            assertTrue(assertFailsWith<Journal.CursorException> { journal.decodeCursor("u", "source", cursor) }.expired)
            db.write { execute("UPDATE music_folder SET name='Writable after restore'") }
            assertEquals("Writable after restore", reference.read { string("SELECT name FROM music_folder") })
            restoredInstance = journal.instanceId
        }
        Db.open(file).use { assertEquals(restoredInstance, Journal(it, root).instanceId) }
    }

    @Test fun inMemoryCheckpointCanBeReplacedAndRestored() {
        val backup = createTempDirectory("dulcet-checkpoint").resolve("checkpoint.db")
        Db.inMemory().use { db ->
            db.write { execute("INSERT INTO music_folder VALUES(1,'First','music')") }
            db.checkpoint(backup)
            db.write { execute("UPDATE music_folder SET name='Second'") }
            db.checkpoint(backup)
            db.write { execute("DELETE FROM music_folder") }
            db.restore(backup)
            assertEquals("Second", db.read { string("SELECT name FROM music_folder") })
        }
    }

    @Test fun checkpointAndRestoreRejectTransactionsAndCorruptInputDoesNotChangeData() {
        val root = createTempDirectory("dulcet-checkpoint")
        val backup = root.resolve("checkpoint.db")
        Db.open(root.resolve("library.db")).use { db ->
            db.write { execute("INSERT INTO music_folder VALUES(1,'Retained','music')") }
            db.checkpoint(backup)
            db.write {
                assertFailsWith<IllegalStateException> { db.checkpoint(backup) }
                assertFailsWith<IllegalStateException> { db.restore(backup) }
            }
            assertFailsWith<IllegalArgumentException> { db.checkpoint(db.file!!) }
            assertFailsWith<IllegalArgumentException> { db.restore(db.file!!) }
            Files.writeString(root.resolve("corrupt.db"), "not a SQLite database")
            assertFails { db.restore(root.resolve("corrupt.db")) }
            assertEquals("Retained", db.read { string("SELECT name FROM music_folder") })
        }
    }
}
