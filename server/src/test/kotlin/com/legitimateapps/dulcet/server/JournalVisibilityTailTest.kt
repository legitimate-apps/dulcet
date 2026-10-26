package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.v1.Journal
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** User-state-only tails must not be reconsidered by every unrelated metadata commit. */
class JournalVisibilityTailTest {
    private class VisibilityTrace : Db.WorkObserver {
        var tailReads = 0
        var catalogReads = 0
        override fun transaction(write: Boolean) {}
        override fun statement(sql: String) {
            val normalized = sql.lowercase().replace(Regex("\\s+"), " ")
            if (normalized.startsWith("select count(*) from api_journal not indexed where seq>")) tailReads++
            if (normalized.startsWith("insert or ignore into api_live_visibility")) catalogReads++
        }
    }

    @Test fun entityFreeTailAdvancesPersistedHeadAndIsNotReadAgain() {
        Db.inMemory().use { db ->
            val journal = Journal(db, createTempDirectory("journal-user-tail"))
            db.write { execute("INSERT INTO music_folder VALUES(1,'Generated source','generated')") }
            val before = journal.head()
            val epoch = journal.visibilityEpoch()
            val trace = VisibilityTrace()
            db.workObserver = trace
            try {
                db.write {
                    repeat(1_000) { index ->
                        execute("INSERT INTO api_journal(type,id,user_id,op,at) VALUES('itemState',?,'generated-user','upsert',?)",
                            "generated-$index", System.currentTimeMillis())
                    }
                }
                val after = journal.head()
                // Positive controls establish a real, nonempty tail containing only user events.
                assertEquals(1_000L, after - before)
                assertEquals(1_000L, db.read { long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='itemState'", before) })
                assertEquals(0L, db.read { long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type IN ('track','album','artist','genre','source')", before) })
                assertEquals(1, trace.tailReads, "The real new tail must be examined once")
                assertEquals(0, trace.catalogReads, "User-only changes do not require catalog visibility rebuilding")
                assertEquals(after.toString(), db.read { string("SELECT value FROM meta WHERE key='api_visibility_head'") },
                    "A fully examined entity-free tail must advance the persistent visibility watermark")
                repeat(8) { index ->
                    db.write { execute("INSERT INTO meta(key,value) VALUES(?,?)", "generated-cache-$index", "generated") }
                }
                assertEquals(1, trace.tailReads, "Unrelated commits must not read an already examined user-only tail")
                assertEquals(0, trace.catalogReads)
                assertEquals(epoch, journal.visibilityEpoch(), "User-only changes must not expire catalog cursors")
                assertEquals(0L, db.read { long("SELECT COUNT(*) FROM api_journal_visibility WHERE seq>?", before) })

                // Advancing past user rows must still capture the next catalog tombstone.
                db.write { execute("DELETE FROM music_folder WHERE id=1") }
                assertEquals("[\"1\"]", db.read { string("SELECT source_ids FROM api_journal_visibility WHERE seq=?", journal.head()) })
                assertEquals(journal.head().toString(), db.read { string("SELECT value FROM meta WHERE key='api_visibility_head'") })
            } finally { db.workObserver = null }
        }
    }

    @Test fun entityFreeWatermarkRollsBackWhenALaterCommitHookFails() {
        Db.inMemory().use { db ->
            val journal = Journal(db, createTempDirectory("journal-user-tail-rollback"))
            db.write { execute("INSERT INTO music_folder VALUES(1,'Generated source','generated')") }
            val before = journal.head()
            val watermark = db.read { string("SELECT value FROM meta WHERE key='api_visibility_head'") }
            var fail = true
            var watermarkSeenByLaterHook: String? = null
            db.beforeCommit { session ->
                watermarkSeenByLaterHook = session.string("SELECT value FROM meta WHERE key='api_visibility_head'")
                if (fail) error("generated commit failure")
            }
            assertFailsWith<IllegalStateException> {
                db.write { execute("INSERT INTO api_journal(type,id,user_id,op,at) VALUES('itemState','generated','generated-user','upsert',?)", System.currentTimeMillis()) }
            }
            assertEquals((before + 1).toString(), watermarkSeenByLaterHook,
                "The failing hook must run after visibility accounting actually advanced the watermark")
            assertEquals(before, journal.head())
            assertEquals(watermark, db.read { string("SELECT value FROM meta WHERE key='api_visibility_head'") })
            fail = false
            db.write { execute("INSERT INTO api_journal(type,id,user_id,op,at) VALUES('itemState','generated','generated-user','upsert',?)", System.currentTimeMillis()) }
            assertEquals(journal.head().toString(), db.read { string("SELECT value FROM meta WHERE key='api_visibility_head'") })
        }
    }
}
