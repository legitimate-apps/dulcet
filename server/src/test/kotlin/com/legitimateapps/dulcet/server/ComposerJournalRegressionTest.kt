package com.legitimateapps.dulcet.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

/** Composer credits remain valid even when they have no artist catalog representation. */
class ComposerJournalRegressionTest {
    private fun tags(composer:String)=mapOf("title" to "Generated song", "artist" to "Performer",
        "album_artist" to "Performer", "album" to "Generated album", "composer" to composer)

    @Test fun repeatedUnchangedScansPreserveComposerOnlyCreditsWithoutSelfTombstones() {
        val root=Files.createTempDirectory("generated-composer-journal")
        val ctx=Fixtures.context(root)
        try {
            for(index in 1..2)Fixtures.track(ctx.config.musicFolder.resolve("song-$index.flac"),0.2,tags("Composer $index"))
            assertEquals(2,ctx.scanner.scan()!!.added)
            fun credits()=ctx.db.read {query("SELECT track_id,artist_id,name,position FROM track_artist WHERE role='composer' ORDER BY track_id,position") {
                listOf(it.string("track_id"),it.string("artist_id"),it.string("name"),it.long("position").toString())
            }}
            fun aggregates()=ctx.db.read {query("SELECT type,id,seq FROM api_revision WHERE type IN ('album','artist') ORDER BY type,id") {
                listOf(it.string("type"),it.string("id"),it.long("seq").toString())
            }}
            val beforeCredits=credits();assertEquals(2,beforeCredits.size)
            assertEquals(0,ctx.db.read {long("SELECT COUNT(*) FROM artist WHERE id IN (SELECT artist_id FROM track_artist WHERE role='composer')")})
            val beforeAggregates=aggregates();val head=ctx.journal.head()
            repeat(2) {
                val result=ctx.scanner.scan(force=true)!!
                assertEquals(2,result.unchanged);assertEquals(0,result.updated)
            }
            val counts=ctx.db.read {query("SELECT type,op,COUNT(*) AS n FROM api_journal WHERE seq>? GROUP BY type,op",head) {
                "${it.string("type")}:${it.string("op")}" to it.long("n")
            }.toMap()}
            println("GENERATED_COMPOSER_JOURNAL composers=2 unchangedScans=2 counts=$counts")
            assertEquals(beforeCredits,credits(),"All composer names, identities and memberships must survive unchanged scans")
            assertEquals(beforeAggregates,aggregates(),"Unchanged composer-only credits must not revise aggregates")
            assertEquals(0,ctx.db.read {long("SELECT COUNT(*) FROM api_identity WHERE type='artist' AND id IN (SELECT artist_id FROM track_artist WHERE role='composer')")},"An existing composer credit cannot be replaced by itself")
            assertTrue(counts.isEmpty(),"Unchanged catalog must append no events; observed $counts")
        } finally {ctx.close();ctx.db.close();root.toFile().deleteRecursively()}
    }

    @Test fun composerOnlyRenamePreservesIdentityAndPublishesRealTrackChanges() {
        val root=Files.createTempDirectory("generated-composer-rename")
        val ctx=Fixtures.context(root)
        try {
            val file=ctx.config.musicFolder.resolve("song.flac")
            Fixtures.track(file,0.2,tags("Composer One"));assertEquals(1,ctx.scanner.scan()!!.added)
            val old=ctx.db.read {string("SELECT artist_id FROM track_artist WHERE role='composer'")!!}
            val track=ctx.db.read {string("SELECT id FROM track")!!};val head=ctx.journal.head()
            Fixtures.track(file,0.2,tags("Composer Two"))
            Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()+5_000))
            assertEquals(1,ctx.scanner.scan(force=true)!!.updated)
            assertEquals(listOf(old,"Composer Two"),ctx.db.read {single("SELECT artist_id,name FROM track_artist WHERE role='composer'") {listOf(it.string("artist_id"),it.string("name"))}})
            assertEquals(0,ctx.db.read {long("SELECT COUNT(*) FROM artist WHERE id=?",old)},"Composer-only identity remains outside artist catalog")
            assertTrue(ctx.db.read {long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='track' AND id=? AND op='upsert'",head,track)}>0,"Retag must still publish its changed track")
            val deletes=ctx.db.read {long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='artist' AND id=? AND op='delete'",head,old)}
            println("GENERATED_COMPOSER_JOURNAL path=rename artistDeletes=$deletes")
            assertEquals(0,deletes,"Preserved composer identity must not publish a self-replacement")
            assertEquals(0,ctx.db.read {long("SELECT COUNT(*) FROM api_identity WHERE type='artist' AND id=?",old)})
        } finally {ctx.close();ctx.db.close();root.toFile().deleteRecursively()}
    }
}
