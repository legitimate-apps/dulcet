package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.discover.*
import java.nio.file.Files
import kotlin.test.*

class DiscoverMatcherTest {
    private val group="11111111-1111-4111-8111-111111111111"
    private fun recording(n:Int)="${n.toString().padStart(8,'0')}-2222-4222-8222-222222222222"
    private fun song(n:Int,title:String="Song $n")=DiscoverItem(kind=DiscoverKind.Song,title=title,artist="Björk",externalIds=mapOf(DiscoverKind.Song.registryKey to recording(n)))
    private fun album()=DiscoverItem(kind=DiscoverKind.Album,title="Album",artist="Björk",externalIds=mapOf(DiscoverKind.Album.registryKey to group))
    private fun editions(count:Int)=Edition(recording(99),group,(1..count).map { EditionTrack(1,it,recording(it),"Song $it","Björk",1000) })
    private inline fun fixture(block:(ServerContext,String,DiscoverMatcher)->Unit) {
        val ctx=Fixtures.context(Files.createTempDirectory("discover-match"))
        try { val user=ctx.users.create("user","password",false);block(ctx,user.id,DiscoverMatcher(ctx.db,ctx.access)) } finally { ctx.db.close() }
    }
    private fun add(ctx:ServerContext,n:Int,recording:String?=recording(n),title:String="Song $n",album:String="al",folder:Int=1,groupId:String?=group) {
        ctx.db.write {
            execute("INSERT OR IGNORE INTO music_folder(id,name,path) VALUES(1,'Music','music')")
            execute("""INSERT OR IGNORE INTO album(id,name,sort_name,album_artist,artist_id,compilation,song_count,duration,size,created,search) VALUES(?,'Album','album','Björk','ar',0,12,12,12,1,'album')""",album)
            execute("INSERT OR IGNORE INTO artist(id,name,sort_name,album_count,song_count,search) VALUES('ar','Björk','björk',1,12,'björk')")
            execute("""INSERT INTO track(id,path,folder_id,parent_dir,mtime,size,title,album,album_id,artist,artist_id,album_artist,album_artist_id,duration,bitrate,suffix,content_type,created,updated,search,mbz_recording_id,mbz_release_group_id,disc_no,track_no)
                VALUES(?,?,?,'',1,1,?,'Album',?,'Björk','ar','Björk','ar',1,128,'flac','audio/flac',1,1,'',?,?,1,?)""","t$n","$n.flac",folder,title,album,recording,groupId,n)
            execute("INSERT INTO track_artist VALUES(?,'artist','ar','Björk',0)","t$n")
            execute("INSERT OR IGNORE INTO album_artist VALUES(?,'ar','Björk',0)",album)
        }
    }
    @Test fun recordingMbidWinsWithoutVersionConflictButConflictingMbidCannotFallBack() = fixture { ctx,user,m ->
        add(ctx,1,title="Song 1 (live)"); assertEquals("notHeld",m.match(user,song(1)).status)
        ctx.db.write { execute("UPDATE track SET title='Different' WHERE id='t1'") }
        assertEquals("held",m.match(user,song(1)).status)
        assertEquals("notHeld",m.match(user,song(2,"Different")).status)
        ctx.db.write { execute("UPDATE track SET mbz_recording_id=NULL") }
        assertEquals("held",m.match(user,song(2,"Different")).status)
    }
    @Test fun recordingMbidNeverOverridesAnExplicitVersionMismatch() = fixture { ctx,user,m ->
        add(ctx,1)
        for(version in listOf("live","remix","demo","acoustic","instrumental")) {
            ctx.db.write { execute("UPDATE track SET title=? WHERE id='t1'","Song 1 ($version)") }
            assertEquals("notHeld",m.match(user,song(1)).status,"Local $version with a stale studio recording tag")
            assertEquals("held",m.match(user,song(1,"Song 1 [${version.uppercase()}]")).status,"The same normalized version remains an MBID match")
            ctx.db.write { execute("UPDATE track SET title='Song 1' WHERE id='t1'") }
            assertEquals("notHeld",m.match(user,song(1,"Song 1 ($version)")).status,"Requested $version must not match studio")
        }
    }
    @Test fun staleRecordingMbidCannotFillAnAlbumEditionSlotWithAnotherVersion() = fixture { ctx,user,m ->
        add(ctx,1,title="Song 1 (live)")
        val mismatch=m.match(user,album(),listOf(editions(1)))
        assertEquals("partlyHeld",mismatch.status);assertEquals(0,mismatch.matchedTrackCount)
        val live=editions(1).let { it.copy(tracks=it.tracks.map { slot -> slot.copy(title="Song 1 [LIVE]") }) }
        assertEquals("held",m.match(user,album(),listOf(live)).status)
        ctx.db.write { execute("UPDATE track SET title='Song 1 (live remix)' WHERE id='t1'") }
        assertEquals("partlyHeld",m.match(user,album(),listOf(live)).status)
    }
    @Test fun namesFoldUnicodeWhitespaceAndPunctuationPreservingVersionWords() = fixture { ctx,user,m ->
        add(ctx,1,recording=null,title="  Bjo\u0308rk / One  ")
        assertEquals("held",m.match(user,song(1,"Björk - One")).status)
        for(version in listOf("live","remix","demo","acoustic","instrumental")) assertEquals("notHeld",m.match(user,song(1,"Björk One ($version)")).status)
        assertEquals(m.normalizeName("AC/DC"),m.normalizeName("AC - DC"))
    }
    @Test fun eightOfTwelveIsPartialAndAnyEditionCanBeComplete() = fixture { ctx,user,m ->
        (1..8).forEach { add(ctx,it) }
        val partial=m.match(user,album(),listOf(editions(12)))
        assertEquals("partlyHeld",partial.status);assertEquals(8,partial.matchedTrackCount);assertEquals(12,partial.totalTrackCount)
        val complete=m.match(user,album(),listOf(editions(12),editions(8).copy(releaseMbid=recording(98))))
        assertEquals("held",complete.status);assertEquals(8,complete.totalTrackCount)
    }
    @Test fun equalCountsWrongSongsAndDuplicateSlotsAreNotComplete() = fixture { ctx,user,m ->
        (1..12).forEach { add(ctx,it,recording=recording(1)) }
        val wrong=m.match(user,album(),listOf(editions(12)))
        assertEquals("partlyHeld",wrong.status); assertEquals(1,wrong.matchedTrackCount)
        val duplicate=editions(2).copy(tracks=listOf(EditionTrack(1,1,recording(1),"Song 1","Björk",1000),EditionTrack(2,1,recording(1),"Song 1","Björk",1000)))
        assertEquals("partlyHeld",m.match(user,album(),listOf(duplicate)).status)
    }
    @Test fun hiddenTracksAndDifferentAlbumsNeverContribute() = fixture { ctx,user,m ->
        (1..6).forEach { add(ctx,it) };(7..12).forEach { add(ctx,it,album="other") }
        assertEquals("partlyHeld",m.match(user,album(),listOf(editions(12))).status)
        ctx.db.write { execute("UPDATE track SET folder_id=2 WHERE id='t1'") }
        assertEquals("notHeld",m.match(user,song(1)).status)
        assertTrue(m.match(user,song(1)).libraryItems.isEmpty())
        val unknown=m.match(user,album())
        assertEquals("unknown",unknown.completeness);assertNull(unknown.totalTrackCount)
    }
}
