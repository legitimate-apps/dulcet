package com.legitimateapps.dulcet.server

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO
import kotlin.test.*

/** Generated replacements preserve size and mtime, as timestamp-preserving library tools do. */
class ArtworkMetadataRegressionTest {
    private class Catalog:AutoCloseable {
        val root:Path=Files.createTempDirectory("generated-art-metadata")
        val ctx=Fixtures.context(root)
        val files=(1..2).map {ctx.config.musicFolder.resolve("artist-$it/album/song.flac")}
        fun tags(index:Int)=mapOf("title" to "Song $index","artist" to "Artist $index",
            "album_artist" to "Artist $index","album" to "Album $index")
        fun populate() {files.forEachIndexed {index,file->Fixtures.track(file,0.2,tags(index+1))}}
        fun ids(index:Int)=ctx.db.read {single("SELECT id,album_id,artist_id FROM track WHERE path=?","artist-$index/album/song.flac") {
            listOf("track" to it.string("id"),"album" to it.string("album_id"),"artist" to it.string("artist_id"))
        }!!}
        fun events(after:Long)=ctx.db.read {query("SELECT type,id,op FROM api_journal WHERE seq>?",after) {
            Triple(it.string("type"),it.string("id"),it.string("op"))
        }}
        fun image(id:Pair<String,String>,size:Int?=null)=ctx.artwork.image(
            when(id.first) {"track"->"mf-";"album"->"al-";else->"ar-"}+id.second,size)!!.bytes
        override fun close() {ctx.close();ctx.db.close();root.toFile().deleteRecursively()}
    }
    private fun picture(rgb:Int):ByteArray {
        val image=BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
        for(x in 0..63)for(y in 0..63)image.setRGB(x,y,rgb)
        val out=ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",out))
        // Trailing padding leaves a valid image and gives both colors exactly the same length.
        return out.toByteArray().also {assertTrue(it.size<4096)}.copyOf(4096)
    }
    private fun blue(bytes:ByteArray) {
        val rgb=ImageIO.read(ByteArrayInputStream(bytes)).getRGB(0,0)
        assertTrue((rgb and 0xff)>200 && ((rgb shr 16) and 0xff)<30,"Expected replacement blue image")
    }
    private fun replacePreservingMetadata(path:Path,bytes:ByteArray) {
        val size=Files.size(path);val mtime=Files.getLastModifiedTime(path)
        assertEquals(size,bytes.size.toLong(),"Replacement fixture must retain exact source size")
        Files.write(path,bytes);Files.setLastModifiedTime(path,mtime)
        assertEquals(size,Files.size(path));assertEquals(mtime,Files.getLastModifiedTime(path))
    }
    private fun assertPublished(c:Catalog,head:Long,expected:List<Pair<String,String>>) {
        val events=c.events(head)
        println("GENERATED_ART_METADATA expectedTypes=${expected.map {it.first}} counts="+events.groupingBy {it.first+":"+it.third}.eachCount())
        for((type,id) in expected)assertTrue(Triple(type,id,"upsert") in events,"Changed $type artwork must notify cursor clients")
    }
    @Test fun sameSizeAndMtimeFolderCoverReplacementPublishesInheritedArtwork() {
        Catalog().use { c->
            c.populate();val cover=c.files[0].resolveSibling("cover.png")
            Files.write(cover,picture(0xff0000));assertEquals(2,c.ctx.scanner.scan()!!.added)
            val ids=c.ids(1);ids.forEach {c.image(it);c.image(it,32)}
            val head=c.ctx.journal.head();replacePreservingMetadata(cover,picture(0x0000ff))
            val scan=c.ctx.scanner.scan(force=true)!!;assertEquals(0,scan.updated);assertEquals(2,scan.unchanged)
            ids.forEach {blue(c.image(it));blue(c.image(it,32))}
            assertPublished(c,head,ids)
        }
    }
    private fun selectedArtistReplacement(preserveMetadata:Boolean) {
        Catalog().use { c->
            c.populate();val art=c.files[0].parent.parent.resolve("artist.png")
            Files.write(art,picture(0xff0000));assertEquals(2,c.ctx.scanner.scan()!!.added)
            val selected=c.ids(1).single {it.first=="artist"};val unrelated=c.ids(2).single {it.first=="artist"}
            val unrelatedImage=c.image(unrelated);c.image(selected);c.image(selected,32)
            val head=c.ctx.journal.head()
            if(preserveMetadata)replacePreservingMetadata(art,picture(0x0000ff)) else {
                val old=Files.getLastModifiedTime(art);Files.write(art,picture(0x0000ff))
                Files.setLastModifiedTime(art,FileTime.fromMillis(old.toMillis()+5_000))
            }
            val scan=c.ctx.scanner.scan(force=true)!!;assertEquals(0,scan.updated);assertEquals(2,scan.unchanged)
            blue(c.image(selected));blue(c.image(selected,32));assertContentEquals(unrelatedImage,c.image(unrelated))
            assertPublished(c,head,listOf(selected))
            val events=c.events(head)
            assertEquals(listOf(Triple("artist",selected.second,"upsert")),events,"One selected standalone image must not revise unrelated artists, albums or tracks")
            val stableHead=c.ctx.journal.head();c.ctx.scanner.scan(force=true)
            assertTrue(c.events(stableHead).isEmpty(),"An unchanged follow-up scan must not repeat art notifications")
        }
    }
    @Test fun sameSizeAndMtimeStandaloneArtistReplacementNotifiesOnlySelectedArtist()=selectedArtistReplacement(true)
    @Test fun changedStandaloneArtistMtimeDoesNotFanOutToUnrelatedArtists()=selectedArtistReplacement(false)

    @Test fun sameSizeAndMtimeEmbeddedReplacementRefreshesOriginalAndResizedCacheBeforeScan()=embeddedReplacement(true)
    @Test fun sameSizeAndMtimeEmbeddedReplacementNotifiesAndRechecksTrackTags()=embeddedReplacement(false)
    private fun embeddedReplacement(checkCacheBeforeScan:Boolean) {
        Catalog().use { c->
            c.populate()
            val red=c.root.resolve("red.png");val replacement=c.root.resolve("blue.flac")
            val bluePicture=c.root.resolve("blue.png")
            Files.write(red,picture(0xff0000));Files.write(bluePicture,picture(0x0000ff))
            Fixtures.track(c.files[0],0.2,c.tags(1),red)
            Fixtures.track(replacement,0.2,c.tags(1),bluePicture)
            val redSource=Files.readAllBytes(c.files[0]);val blueSource=Files.readAllBytes(replacement)
            val paddedSize=maxOf(redSource.size,blueSource.size)+4096
            Files.write(c.files[0],redSource.copyOf(paddedSize))
            assertEquals(2,c.ctx.scanner.scan()!!.added)
            val ids=c.ids(1);val before=ids.associateWith {c.image(it)}
            ids.forEach {c.image(it,32)}
            val trackBefore=c.ctx.db.read {single("SELECT title,mtime,size FROM track WHERE path='artist-1/album/song.flac'") {
                listOf(it.string("title"),it.long("mtime").toString(),it.long("size").toString())
            }}
            val head=c.ctx.journal.head();replacePreservingMetadata(c.files[0],blueSource.copyOf(paddedSize))
            // Access before scanning must also invalidate the populated original and resized cache.
            if(checkCacheBeforeScan)ids.forEach {assertFalse(before.getValue(it).contentEquals(c.image(it)));blue(c.image(it));blue(c.image(it,32))}
            val scan=c.ctx.scanner.scan(force=true)!!
            assertEquals(1,scan.updated,"A changed embedded source may contain a hidden tag edit and must now be re-read")
            assertEquals(1,scan.unchanged)
            assertEquals(trackBefore,c.ctx.db.read {single("SELECT title,mtime,size FROM track WHERE path='artist-1/album/song.flac'") {
                listOf(it.string("title"),it.long("mtime").toString(),it.long("size").toString())
            }})
            assertPublished(c,head,ids)
            ids.forEach {assertFalse(before.getValue(it).contentEquals(c.image(it)));blue(c.image(it));blue(c.image(it,32))}
            val stableHead=c.ctx.journal.head();c.ctx.scanner.scan(force=true)
            assertTrue(c.events(stableHead).isEmpty(),"Persisted embedded source stamps must suppress repeated notifications")
        }
    }
}
