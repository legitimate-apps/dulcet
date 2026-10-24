package com.legitimateapps.dulcet.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.*

/** Generated audio and journal counts distinguish semantic changes from aggregate rebuild churn. */
class ScannerJournalAmplificationTest {
    private class Catalog(val ctx:ServerContext,val root:Path,val files:List<Path>) : AutoCloseable {
        override fun close() { ctx.close();ctx.db.close();root.toFile().deleteRecursively() }
    }
    private fun tags(index:Int,title:String="Song $index",artist:String="Artist $index")=mapOf(
        "title" to title,"artist" to artist,"album_artist" to "Artist $index","album" to "Album $index","disc" to "1")
    private fun catalog():Catalog {
        val root=Files.createTempDirectory("generated-scan-journal")
        val ctx=Fixtures.context(root)
        val files=(1..3).map { index -> ctx.config.musicFolder.resolve("album-$index/song.flac").also { Fixtures.track(it,0.2,tags(index)) } }
        assertEquals(3,ctx.scanner.scan()!!.added)
        return Catalog(ctx,root,files)
    }
    private fun revisions(c:Catalog)=c.ctx.db.read {
        query("SELECT type,id,seq FROM api_revision WHERE type IN ('album','artist') ORDER BY type,id") { (it.string("type") to it.string("id")) to it.long("seq") }.toMap()
    }
    private fun id(c:Catalog,type:String,name:String)=c.ctx.db.read {string("SELECT id FROM $type WHERE name=?",name)!!}
    private fun changes(c:Catalog,after:Long,label:String):Set<Pair<String,String>> = c.ctx.db.read {
        val rows=query("SELECT type,id,op FROM api_journal WHERE seq>?",after) { Triple(it.string("type"),it.string("id"),it.string("op")) }
        println("GENERATED_SCAN_JOURNAL path=$label counts="+rows.groupingBy { it.first+":"+it.third }.eachCount().toSortedMap()+" total="+rows.size)
        rows.filter { it.first in setOf("album","artist") }.map { it.first to it.second }.toSet()
    }
    private fun retag(file:Path,index:Int,title:String="Song $index",artist:String="Artist $index") {
        Fixtures.track(file,0.2,tags(index,title,artist))
        Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()+5_000))
    }
    @Test fun forcedUnchangedScanKeepsAggregateRevisionsAndWritesNoAggregateEvents() {
        catalog().use { c ->
            val before=revisions(c);val head=c.ctx.journal.head()
            val scan=c.ctx.scanner.scan(force=true)!!
            assertEquals(3,scan.unchanged);assertEquals(0,scan.updated)
            val changed=changes(c,head,"forced-unchanged-three-albums")
            assertTrue(changed.isEmpty(),"Unchanged forced scan rewrote ${changed.size} aggregate identities")
            assertEquals(before,revisions(c))
        }
    }
    @Test fun addingOneSongUpdatesOnlyItsAlbumAndArtist() {
        catalog().use { c ->
            val before=revisions(c);val head=c.ctx.journal.head()
            val expected=setOf("album" to id(c,"album","Album 1"),"artist" to id(c,"artist","Artist 1"))
            Fixtures.track(c.files[0].resolveSibling("added.flac"),0.3,tags(1,"Added"))
            assertEquals(1,c.ctx.scanner.scan(force=false)!!.added)
            val changed=changes(c,head,"one-added-existing-album")
            assertEquals(expected,changed,"Adding one song must preserve unrelated aggregate revisions")
            for((key,value) in before.filterKeys { it !in expected })assertEquals(value,revisions(c)[key])
            assertEquals(2,c.ctx.db.read {long("SELECT song_count FROM album WHERE id=?",expected.first {it.first=="album"}.second)})
        }
    }
    @Test fun titleOnlyRetagDoesNotReviseUnchangedAggregates() {
        catalog().use { c ->
            val before=revisions(c);val head=c.ctx.journal.head()
            val columns=c.ctx.db.read {query("PRAGMA table_info(album)") {it.string("name")}}
            fun albumRows()=c.ctx.db.read {query("SELECT * FROM album ORDER BY id") {row->columns.map(row::stringOrNull)}}
            val beforeRows=albumRows()
            retag(c.files[0],1,"Edit 1")
            assertEquals(1,c.ctx.scanner.scan(force=false)!!.updated)
            val changed=changes(c,head,"title-only-retag")
            assertEquals("Edit 1",c.ctx.db.read {string("SELECT title FROM track WHERE path='album-1/song.flac'")})
            assertEquals(beforeRows,albumRows(),"Equal-length title fixture must preserve all album scalar values")
            assertTrue(changed.isEmpty(),"Title-only retag rewrote unchanged aggregates: $changed")
            assertEquals(before,revisions(c))
        }
    }
    @Test fun actualAlbumAndArtistRemovalKeepsTombstonesWithoutRevisingSurvivors() {
        catalog().use { c ->
            val before=revisions(c);val head=c.ctx.journal.head()
            val removed=setOf("album" to id(c,"album","Album 3"),"artist" to id(c,"artist","Artist 3"))
            Files.delete(c.files[2]);assertEquals(1,c.ctx.scanner.scan(force=false)!!.removed)
            val changed=changes(c,head,"remove-one-album")
            for((type,id) in removed) {
                assertEquals(1,c.ctx.db.read {long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type=? AND id=? AND op='delete'",head,type,id)})
                assertNotNull(c.ctx.db.read {string("SELECT reason FROM api_identity WHERE type=? AND id=?",type,id)})
                assertNotNull(c.ctx.db.read {string("SELECT v.source_ids FROM api_journal j JOIN api_journal_visibility v ON v.seq=j.seq WHERE j.seq>? AND j.type=? AND j.id=? AND j.op='delete'",head,type,id)})
            }
            assertEquals(removed,changed,"Deleting one album must leave unrelated aggregate revisions stable")
            for((key,value) in before.filterKeys { it !in removed })assertEquals(value,revisions(c)[key])
        }
    }
    @Test fun addedCreditMembershipStillPublishesTheNewArtist() {
        catalog().use { c ->
            val head=c.ctx.journal.head()
            retag(c.files[0],1,artist="Artist 1;Guest")
            assertEquals(1,c.ctx.scanner.scan(force=false)!!.updated)
            val guest=id(c,"artist","Guest")
            val changed=changes(c,head,"added-artist-credit")
            assertTrue(("artist" to guest) in changed,"New credit must publish a new artist")
            assertEquals(1,c.ctx.db.read {long("SELECT COUNT(*) FROM track_artist WHERE artist_id=? AND role='artist'",guest)})
            assertEquals(1,c.ctx.db.read {long("SELECT song_count FROM artist WHERE id=?",guest)})
        }
    }
    @Test fun swappedCreditMembershipInvalidatesArtistsEvenWhenTheirScalarRowsAreUnchanged() {
        catalog().use { c ->
            fun artistRows()=c.ctx.db.read {query("SELECT * FROM artist ORDER BY id") { row ->
                listOf("id","name","sort_name","mbz_artist_id","album_count","song_count","search").map(row::stringOrNull)
            }}
            val beforeRows=artistRows();val before=revisions(c);val head=c.ctx.journal.head()
            val affected=setOf("artist" to id(c,"artist","Artist 1"),"artist" to id(c,"artist","Artist 2"))
            retag(c.files[0],1,artist="Artist 2")
            retag(c.files[1],2,artist="Artist 1")
            assertEquals(2,c.ctx.scanner.scan(force=false)!!.updated)
            val changed=changes(c,head,"equal-count-artist-membership-swap")
            assertEquals(beforeRows,artistRows(),"Positive control requires identical persisted artist scalars")
            assertTrue(changed.containsAll(affected),"Changed artist track membership must invalidate artist representations despite unchanged scalar rows")
            for(key in affected)assertTrue(revisions(c).getValue(key)>before.getValue(key))
        }
    }
    @Test fun swappedTrackMembershipInvalidatesAlbumsEvenWhenTheirScalarRowsAreUnchanged() {
        val root=Files.createTempDirectory("generated-album-membership")
        val ctx=Fixtures.context(root)
        try {
            fun metadata(album:Int,track:Int)=mapOf("title" to "Track $track","artist" to "Shared Artist",
                "album_artist" to "Shared Artist","album" to "Album $album",
                "musicbrainzalbumid" to "00000000-1111-4111-8111-${album.toString().padStart(12,'0')}")
            val files=(1..2).flatMap { album -> (1..2).map { track ->
                Triple(album,track,ctx.config.musicFolder.resolve("$album-$track.flac").also {Fixtures.track(it,0.2,metadata(album,track))})
            }}
            assertEquals(4,ctx.scanner.scan()!!.added)
            val columns=ctx.db.read {query("PRAGMA table_info(album)") {it.string("name")}}
            fun albumRows()=ctx.db.read {query("SELECT * FROM album ORDER BY id") {row->columns.map(row::stringOrNull)}}
            val beforeRows=albumRows()
            val before=ctx.db.read {query("SELECT id,seq FROM api_revision WHERE type='album'") {it.string("id") to it.long("seq")}.toMap()}
            val head=ctx.journal.head()
            for((album,track,file) in files.filter {it.second==1}) {
                Fixtures.track(file,0.2,metadata(3-album,track))
                Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()+5_000))
            }
            assertEquals(2,ctx.scanner.scan(force=false)!!.updated)
            assertEquals(beforeRows,albumRows(),"Positive control requires identical persisted album scalars")
            val changed=ctx.db.read {query("SELECT DISTINCT id FROM api_journal WHERE seq>? AND type='album'",head) {it.string("id")}.toSet()}
            println("GENERATED_SCAN_JOURNAL path=equal-count-album-membership-swap changedAlbums=${changed.size}")
            assertEquals(before.keys,changed,"Changed track membership must invalidate both album representations")
            for((id,revision) in before)assertTrue(ctx.db.read {long("SELECT seq FROM api_revision WHERE type='album' AND id=?",id)}>revision)
        } finally {ctx.close();ctx.db.close();root.toFile().deleteRecursively()}
    }
    @Test fun discCountChangeInvalidatesAlbumWithUnchangedScalarRowsAndTrackMembership() {
        catalog().use { c ->
            val second=c.files[0].resolveSibling("second.flac")
            Fixtures.track(second,0.2,tags(1,"Song 2"))
            c.ctx.scanner.scan(force=false)
            val album=id(c,"album","Album 1")
            val columns=c.ctx.db.read {query("PRAGMA table_info(album)") {it.string("name")}}
            fun albumRow()=c.ctx.db.read {single("SELECT * FROM album WHERE id=?",album) {row->columns.map(row::stringOrNull)}}
            val before=albumRow();val revision=revisions(c).getValue("album" to album);val head=c.ctx.journal.head()
            Fixtures.track(second,0.2,tags(1,"Song 2")+("disc" to "2"))
            Files.setLastModifiedTime(second,FileTime.fromMillis(System.currentTimeMillis()+5_000))
            assertEquals(1,c.ctx.scanner.scan(force=false)!!.updated)
            assertEquals(before,albumRow(),"Disc-only fixture must preserve persisted album scalars")
            assertEquals(2,c.ctx.db.read {long("SELECT COUNT(DISTINCT coalesce(disc_no,1)) FROM track WHERE album_id=?",album)})
            val changed=changes(c,head,"disc-count-change")
            assertTrue(("album" to album) in changed,"Changed public discCount must invalidate album")
            assertTrue(revisions(c).getValue("album" to album)>revision)
        }
    }
    @Test fun embeddedArtworkAdditionInvalidatesArtistInheritingAlbumArtwork() {
        catalog().use { c ->
            val artist=id(c,"artist","Artist 1");val album=id(c,"album","Album 1")
            val beforeImage=c.ctx.artwork.image("ar-$artist",null)!!.bytes
            val revision=revisions(c).getValue("artist" to artist);val head=c.ctx.journal.head()
            val picture=c.root.resolve("generated-art.jpg").also {Fixtures.jpeg(it,64)}
            Fixtures.track(c.files[0],0.2,tags(1),picture)
            Files.setLastModifiedTime(c.files[0],FileTime.fromMillis(System.currentTimeMillis()+5_000))
            assertEquals(1,c.ctx.scanner.scan(force=false)!!.updated)
            assertNotNull(c.ctx.db.read {string("SELECT art_track_id FROM album WHERE id=?",album)})
            assertFalse(beforeImage.contentEquals(c.ctx.artwork.image("ar-$artist",null)!!.bytes),"Artist now inherits embedded album artwork instead of placeholder")
            val changed=changes(c,head,"embedded-art-added")
            assertTrue(("artist" to artist) in changed,"Inherited artist artwork changed without folder art stamp change")
            assertTrue(revisions(c).getValue("artist" to artist)>revision)
        }
    }
    @Test fun samePathFolderArtworkReplacementInvalidatesInheritedRepresentations() {
        catalog().use { c ->
            val cover=c.files[0].resolveSibling("cover.jpg")
            fun picture(rgb:Int) {
                val image=java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_RGB)
                for(x in 0..15)for(y in 0..15)image.setRGB(x,y,rgb)
                assertTrue(javax.imageio.ImageIO.write(image,"jpg",cover.toFile()))
            }
            picture(0xff0000);c.ctx.scanner.scan(force=false)
            val album=id(c,"album","Album 1");val artist=id(c,"artist","Artist 1")
            val before=revisions(c);val beforeImage=c.ctx.artwork.image("ar-$artist",null)!!.bytes;val head=c.ctx.journal.head()
            picture(0x0000ff);Files.setLastModifiedTime(cover,FileTime.fromMillis(System.currentTimeMillis()+5_000))
            c.ctx.scanner.scan(force=false)
            assertFalse(beforeImage.contentEquals(c.ctx.artwork.image("ar-$artist",null)!!.bytes))
            val changed=changes(c,head,"same-path-folder-art-replaced")
            for(key in setOf("album" to album,"artist" to artist)) {
                assertTrue(key in changed);assertTrue(revisions(c).getValue(key)>before.getValue(key))
            }
            assertTrue(c.ctx.db.read {long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='track' AND id=(SELECT id FROM track WHERE path='album-1/song.flac')",head)}>0)
        }
    }
    @Test fun movingTrackBetweenExistingCoveredDirectoriesInvalidatesInheritedArtistArtwork() {
        catalog().use { c ->
            fun picture(file:Path,rgb:Int) {
                val image=java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_RGB)
                for(x in 0..15)for(y in 0..15)image.setRGB(x,y,rgb)
                assertTrue(javax.imageio.ImageIO.write(image,"jpg",file.toFile()))
            }
            picture(c.files[0].resolveSibling("cover.jpg"),0xff0000)
            picture(c.files[1].resolveSibling("cover.jpg"),0x0000ff)
            c.ctx.scanner.scan(force=false)
            val artist=id(c,"artist","Artist 1");val album=id(c,"album","Album 1")
            val beforeImage=c.ctx.artwork.image("ar-$artist",null)!!.bytes
            val beforeRevision=revisions(c).getValue("artist" to artist)
            val stamp=c.ctx.db.read {string("SELECT value FROM meta WHERE key='folder_art_stamp'")}
            val track=c.ctx.db.read {string("SELECT id FROM track WHERE path='album-1/song.flac'")!!}
            val head=c.ctx.journal.head()
            Files.move(c.files[0],c.files[1].resolveSibling("moved.flac"))
            c.ctx.scanner.scan(force=false)
            assertEquals(stamp,c.ctx.db.read {string("SELECT value FROM meta WHERE key='folder_art_stamp'")},"Both directories and their artwork files remain unchanged")
            assertEquals(track,c.ctx.db.read {string("SELECT id FROM track WHERE path='album-2/moved.flac'")},"Move must preserve track identity")
            assertEquals("album-2/cover.jpg",c.ctx.db.read {string("SELECT folder_art FROM album WHERE id=?",album)})
            assertFalse(beforeImage.contentEquals(c.ctx.artwork.image("ar-$artist",null)!!.bytes),"Artist inherits a different existing cover")
            val changed=changes(c,head,"move-between-existing-covers")
            assertTrue(("artist" to artist) in changed,"Inherited artist image source changed without global folder art stamp change")
            assertTrue(revisions(c).getValue("artist" to artist)>beforeRevision)
        }
    }
    @Test fun standaloneArtistArtworkReplacementInvalidatesOnlyArtistsOnForcedScan() {
        catalog().use { c ->
            val picture=c.ctx.config.musicFolder.resolve("artist.jpg")
            fun writePicture(rgb:Int) {
                val image=java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_RGB)
                for(x in 0..15)for(y in 0..15)image.setRGB(x,y,rgb)
                assertTrue(javax.imageio.ImageIO.write(image,"jpg",picture.toFile()))
            }
            writePicture(0xff0000)
            c.ctx.scanner.scan(force=true)
            val artist=id(c,"artist","Artist 1")
            val beforeImage=c.ctx.artwork.image("ar-$artist",null)!!.bytes
            val before=revisions(c);val head=c.ctx.journal.head()
            val albumStamp=c.ctx.db.read {string("SELECT value FROM meta WHERE key='folder_art_stamp'")}
            val columns=c.ctx.db.read {query("PRAGMA table_info(track)") {it.string("name")}}
            fun tracks()=c.ctx.db.read {query("SELECT * FROM track ORDER BY id") {row->columns.map(row::stringOrNull)}}
            val beforeTracks=tracks()
            writePicture(0x0000ff)
            Files.setLastModifiedTime(picture,FileTime.fromMillis(System.currentTimeMillis()+5_000))
            val scan=c.ctx.scanner.scan(force=true)!!
            assertEquals(3,scan.unchanged);assertEquals(0,scan.updated)
            assertEquals(albumStamp,c.ctx.db.read {string("SELECT value FROM meta WHERE key='folder_art_stamp'")})
            assertEquals(beforeTracks,tracks(),"Standalone image replacement must preserve all track metadata")
            assertFalse(beforeImage.contentEquals(c.ctx.artwork.image("ar-$artist",null)!!.bytes),"Standalone artist image bytes must change")
            val changed=changes(c,head,"standalone-artist-art-replaced")
            assertTrue(("artist" to artist) in changed,"Changed standalone image must invalidate artist")
            assertTrue(revisions(c).getValue("artist" to artist)>before.getValue("artist" to artist))
            assertTrue(changed.none {it.first=="album"},"Artist-only artwork change must not churn album revisions")
            assertEquals(0,c.ctx.db.read {long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='track'",head)},"Artist-only artwork change must not churn track revisions")
            for((key,revision) in before.filterKeys {it.first=="album"})assertEquals(revision,revisions(c)[key])
        }
    }
    @Test fun folderArtworkChangeStillInvalidatesTheAffectedAlbumAndTrack() {
        catalog().use { c ->
            val head=c.ctx.journal.head();val album=id(c,"album","Album 1")
            Fixtures.jpeg(c.files[0].resolveSibling("cover.jpg"),64)
            assertEquals(3,c.ctx.scanner.scan(force=false)!!.unchanged)
            val changed=changes(c,head,"folder-art-added")
            assertTrue(("album" to album) in changed,"Album artwork representation must change")
            assertEquals("album-1/cover.jpg",c.ctx.db.read {string("SELECT folder_art FROM album WHERE id=?",album)})
            assertTrue(c.ctx.db.read {long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='track' AND id=(SELECT id FROM track WHERE path='album-1/song.flac')",head)}>0,"Inherited track artwork must invalidate")
        }
    }
}
