package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import java.nio.file.Files
import kotlin.test.*

/** Synthetic historical events protect the seq-watermark fast path and real visibility bookkeeping. */
class JournalScaleTest {
    private class SqlTrace : Db.WorkObserver {
        val tailStatements=mutableListOf<String>()
        override fun transaction(write:Boolean) {}
        override fun statement(sql:String) {
            val normalized=sql.lowercase().replace(Regex("\\s+")," ")
            val visibility=normalized.startsWith("select count(*) from api_journal") ||
                normalized.startsWith("insert or ignore into api_journal_visibility")
            if(visibility && "from api_journal" in normalized && Regex("(?:\\b|\\.)seq\\s*>\\s*\\?").containsMatchIn(normalized)) tailStatements+=sql
        }
    }
    private inline fun fixture(block:(ServerContext,String,Long)->Unit) {
        val ctx=Fixtures.context(Files.createTempDirectory("generated-journal-scale"))
        try {
            val user=ctx.users.create("generated-journal-user","generated-password",false)
            ctx.db.write {
                execute("INSERT INTO music_folder VALUES(2,'Generated other source','generated-other')")
                execute("""INSERT INTO track(id,path,folder_id,parent_dir,mtime,size,title,album,album_id,artist,artist_id,
                    album_artist,album_artist_id,duration,bitrate,suffix,content_type,created,updated,search)
                    VALUES('generated-track','generated.flac',1,'',1,1,'Generated','Generated','generated-album',
                    'Generated','generated-artist','Generated','generated-artist',1,128,'flac','audio/flac',1,1,'generated')""")
                for(index in 1..10_000)execute("INSERT INTO api_journal(type,id,user_id,op,at) VALUES('itemState',?,?,'upsert',1)","generated-history-$index",user.id)
                execute("UPDATE api_journal SET at=1")
                // A catalog event ensures the persisted visibility watermark includes the frozen history.
                execute("INSERT INTO api_journal(type,id,op,at) VALUES('source','1','upsert',?)",System.currentTimeMillis())
            }
            val historical=ctx.db.read {long("SELECT MAX(seq) FROM api_journal WHERE id LIKE 'generated-history-%'")}
            block(ctx,user.id,historical)
        } finally {ctx.close();ctx.db.close()}
    }
    private fun trace(ctx:ServerContext,action:()->Unit):SqlTrace {
        val trace=SqlTrace();ctx.db.workObserver=trace
        try {action()} finally {ctx.db.workObserver=null}
        return trace
    }
    private fun tailUsesSequenceSeek(ctx:ServerContext,trace:SqlTrace,watermark:Long) {
        assertTrue(trace.tailStatements.isNotEmpty(),"Positive control: a real journal tail must exercise visibility accounting")
        for(sql in trace.tailStatements.distinct()) {
            val plans=ctx.db.read {query("EXPLAIN QUERY PLAN $sql",watermark) {it.string("detail")}}
            val journal=plans.filter { Regex("(?:api_journal|\\bj\\b)",RegexOption.IGNORE_CASE).containsMatchIn(it) }
            assertTrue(journal.any {"INTEGER PRIMARY KEY" in it && "rowid>?" in it},
                "Recent-tail visibility must seek by seq instead of revisiting historical type-index entries: $plans")
            assertFalse(journal.any {"api_journal_entity" in it},"Historical entity-index traversal remains: $plans")
            println("GENERATED_JOURNAL_SCALE tailPlan="+journal.joinToString(";"))
        }
    }
    @Test fun metadataOnlyCommitWithUnchangedJournalHeadDoesNotQueryHistoricalTail() = fixture {ctx,_,_->
        val head=ctx.journal.head()
        val observed=trace(ctx) {ctx.db.write {execute("INSERT INTO meta(key,value) VALUES('generated-probe','one')")}}
        assertEquals(head,ctx.journal.head())
        assertTrue(observed.tailStatements.isEmpty(),"A commit with no journal tail queried historical visibility: ${observed.tailStatements.size} tail statements")
    }
    @Test fun userAndCatalogTailsSeekBySequenceAndKeepMoveAndDeleteVisibility() = fixture {ctx,user,_->
        val before=ctx.journal.head()
        val userTail=trace(ctx) {ctx.db.write {
            execute("INSERT INTO annotation(user_id,item_type,item_id,play_count) VALUES(?,'song','generated-track',1)",user)
        }}
        val userSeq=ctx.journal.head()
        assertTrue(userSeq>before)
        assertEquals(0,ctx.db.read {long("SELECT COUNT(*) FROM api_journal_visibility WHERE seq=?",userSeq)})
        val epoch=ctx.journal.visibilityEpoch()
        val move=trace(ctx) {ctx.db.write {execute("UPDATE track SET folder_id=2 WHERE id='generated-track'")}}
        val moved=ctx.db.read {query("""SELECT v.source_ids FROM api_journal j JOIN api_journal_visibility v ON v.seq=j.seq
            WHERE j.seq>? AND j.type='track' AND j.id='generated-track'""",userSeq) {it.string("source_ids")}}
        assertTrue(moved.isNotEmpty());assertTrue(moved.all {it=="[\"2\"]"},"Moved track must reflect source2: $moved")
        assertTrue(ctx.journal.visibilityEpoch()>epoch,"Changing source membership must invalidate old visibility cursors")
        val beforeDelete=ctx.journal.head()
        val deletion=trace(ctx) {ctx.db.write {execute("DELETE FROM track WHERE id='generated-track'")}}
        assertEquals("[\"2\"]",ctx.db.read {string("""SELECT v.source_ids FROM api_journal j JOIN api_journal_visibility v ON v.seq=j.seq
            WHERE j.seq>? AND j.type='track' AND j.id='generated-track' AND j.op='delete'""",beforeDelete)})
        tailUsesSequenceSeek(ctx,userTail,before)
        tailUsesSequenceSeek(ctx,move,userSeq)
        tailUsesSequenceSeek(ctx,deletion,beforeDelete)
    }
    @Test fun pruningFrozenHistoryKeepsCurrentVisibilityAndJournalHead() = fixture {ctx,_,historical->
        val head=ctx.journal.head()
        ctx.journal.prune()
        assertTrue(ctx.journal.floor()>=historical,"Expired generated history must be pruned")
        assertEquals(0,ctx.db.read {long("SELECT COUNT(*) FROM api_journal WHERE id LIKE 'generated-history-%'")})
        assertEquals(head,ctx.journal.head(),"Retention keeps the sequence clock even when rows are deleted")
        assertEquals(0,ctx.db.read {long("SELECT COUNT(*) FROM api_journal_visibility v LEFT JOIN api_journal j ON j.seq=v.seq WHERE j.seq IS NULL")})
        assertNotNull(ctx.db.read {string("""SELECT v.source_ids FROM api_journal j JOIN api_journal_visibility v ON v.seq=j.seq
            WHERE j.type='source' AND j.id='1' ORDER BY j.seq DESC LIMIT 1""")})
    }
}
