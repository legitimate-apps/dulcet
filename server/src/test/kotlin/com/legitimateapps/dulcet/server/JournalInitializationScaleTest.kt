package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.db.Schema
import kotlin.test.*

/** Generated historical rows protect startup work and legacy revision continuity. */
class JournalInitializationScaleTest {
    private class Trace:Db.WorkObserver {
        val rebuilds=mutableListOf<String>()
        override fun transaction(write:Boolean) {}
        override fun statement(sql:String) {
            val text=sql.lowercase().replace(Regex("\\s+")," ")
            if("insert into api_revision" in text && "from api_journal" in text && "group by" in text)rebuilds+=text
        }
    }
    private fun seed(db:Db) = db.write {
        for(index in 1..10_000)execute("INSERT INTO api_journal(type,id,op,at) VALUES('album',?,'upsert',1)","generated-${index%10}")
        execute("INSERT INTO api_journal(type,id,op,at,reason) VALUES('album','generated-deleted','delete',1,'deleted')")
        execute("INSERT INTO api_journal(type,id,op,at) VALUES('artist','generated-expired','upsert',1)")
    }
    @Test fun repeatedMigrationDoesNotRebuildCurrentRevisionsFromWholeHistory() {
        Db.inMemory().use { db ->
            seed(db)
            val before=db.read {query("SELECT type,id,user_id,seq FROM api_revision ORDER BY type,id,user_id") {listOf(it.string("type"),it.string("id"),it.string("user_id"),it.long("seq").toString())}}
            db.write {execute("DELETE FROM api_journal WHERE id='generated-expired'")}
            val count=db.read {long("SELECT COUNT(*) FROM api_journal")}
            val head=db.read {long("SELECT MAX(seq) FROM sqlite_sequence WHERE name='api_journal'")}
            val trace=Trace();db.workObserver=trace
            try {repeat(2) {Schema.migrate(db)}} finally {db.workObserver=null}
            assertEquals(count,db.read {long("SELECT COUNT(*) FROM api_journal")},"Initialized startup must not reseed journal")
            assertEquals(head,db.read {long("SELECT MAX(seq) FROM sqlite_sequence WHERE name='api_journal'")})
            assertEquals(before,db.read {query("SELECT type,id,user_id,seq FROM api_revision ORDER BY type,id,user_id") {listOf(it.string("type"),it.string("id"),it.string("user_id"),it.long("seq").toString())}},"Latest revisions including removed journal history must survive startup")
            println("GENERATED_JOURNAL_INITIALIZATION retainedRows=$count repeatMigrations=2 historicalGroupByRebuilds=${trace.rebuilds.size}")
            assertTrue(trace.rebuilds.isEmpty(),"Initialized migration replayed ${trace.rebuilds.size} whole-history revision rebuilds")
        }
    }
    @Test fun genuinelyMissingRevisionTableBackfillsLatestRevisionsIncludingDeletion() {
        Db.inMemory().use { db ->
            seed(db)
            val head=db.read {long("SELECT MAX(seq) FROM api_journal")}
            db.write {execute("DROP TABLE api_revision")}
            Schema.migrate(db)
            db.read {
                assertEquals(12,long("SELECT COUNT(*) FROM api_revision"))
                assertEquals(head,long("SELECT MAX(seq) FROM api_journal"),"Backfill must not append journal events")
                assertEquals(1,long("SELECT COUNT(*) FROM api_revision r JOIN api_journal j ON j.seq=r.seq WHERE r.id='generated-deleted' AND j.op='delete'"))
                assertEquals(0,long("SELECT COUNT(*) FROM api_revision r WHERE seq<>(SELECT MAX(j.seq) FROM api_journal j WHERE j.type=r.type AND j.id=r.id AND coalesce(j.user_id,'')=r.user_id)"))
            }
            db.write {execute("INSERT INTO api_journal(type,id,op,at) VALUES('album','generated-1','upsert',2)")}
            assertEquals(db.read {long("SELECT MAX(seq) FROM api_journal")},db.read {long("SELECT seq FROM api_revision WHERE type='album' AND id='generated-1'")},"Future trigger updates must continue after legacy backfill")
        }
    }
    @Test fun missingTriggerBackfillsOlderWriterEventsAndPreservesPrunedRevisions() {
        Db.inMemory().use { db ->
            seed(db)
            val retained=db.read {long("SELECT seq FROM api_revision WHERE id='generated-expired'")}
            db.write {
                execute("DELETE FROM api_journal WHERE id='generated-expired'")
                execute("DROP TRIGGER api_journal_revision")
                // Characterize an older writer while current-revision bookkeeping is absent.
                execute("INSERT INTO api_journal(type,id,op,at) VALUES('album','generated-1','upsert',2)")
                execute("INSERT INTO api_journal(type,id,op,at,reason) VALUES('album','older-writer-deleted','delete',2,'deleted')")
            }
            val head=db.read {long("SELECT MAX(seq) FROM api_journal")}
            Schema.migrate(db)
            assertEquals(head,db.read {long("SELECT MAX(seq) FROM api_journal")},"Backfill must not append history")
            assertEquals(retained,db.read {long("SELECT seq FROM api_revision WHERE id='generated-expired'")},"Pruned history must not erase its current revision")
            assertEquals(0,db.read {long("SELECT COUNT(*) FROM api_revision r WHERE EXISTS(SELECT 1 FROM api_journal j WHERE j.type=r.type AND j.id=r.id AND coalesce(j.user_id,'')=r.user_id) AND r.seq<>(SELECT MAX(j.seq) FROM api_journal j WHERE j.type=r.type AND j.id=r.id AND coalesce(j.user_id,'')=r.user_id)")})
            assertEquals(head,db.read {long("SELECT seq FROM api_revision WHERE id='older-writer-deleted'")},"Deleted entity written without trigger must be backfilled")
            db.write {execute("INSERT INTO api_journal(type,id,op,at) VALUES('album','generated-1','upsert',3)")}
            assertEquals(db.read {long("SELECT MAX(seq) FROM api_journal")},db.read {long("SELECT seq FROM api_revision WHERE type='album' AND id='generated-1'")},"Reinstalled trigger must maintain future writes")
            val trace=Trace();db.workObserver=trace
            try {Schema.migrate(db)} finally {db.workObserver=null}
            assertTrue(trace.rebuilds.isEmpty(),"Repaired installed schema must not backfill on every startup")
        }
    }
}
