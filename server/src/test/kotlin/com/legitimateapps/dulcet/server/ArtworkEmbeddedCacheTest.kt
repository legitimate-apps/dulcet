package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.auth.User
import com.legitimateapps.dulcet.server.media.Artwork
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO
import kotlin.test.*

/** No real decoding or remote data: fake ffmpeg counts extractions and emits a generated image. */
class ArtworkEmbeddedCacheTest {
    private class Fixture:AutoCloseable {
        val root:Path=Files.createTempDirectory("generated-embedded-cache")
        val ctx=Fixtures.context(root)
        val source:Path=ctx.config.musicFolder.resolve("generated.flac")
        val countFile:Path=root.resolve("extractions")
        val payload:Path=root.resolve("image.png")
        val failure:Path=root.resolve("fail")
        val executable:Path=root.resolve("fake-ffmpeg")
        val cache:Path=root.resolve("art-cache")
        val visible:User
        val hidden:User
        val artwork:Artwork
        init {
            Files.write(source,byteArrayOf(1,2,3,4))
            color(0xff0000)
            Files.writeString(executable,"""#!/bin/sh
                |base=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)
                |printf 'extract\n' >> "${'$'}base/extractions"
                |if [ -e "${'$'}base/fail" ]; then exit 1; fi
                |cat "${'$'}base/image.png"
                |""".trimMargin())
            check(executable.toFile().setExecutable(true))
            visible=ctx.users.create("generated-visible","generated-password",false)
            hidden=ctx.users.create("generated-hidden","generated-password",false)
            ctx.db.write {
                execute("INSERT INTO api_user_access(user_id,source_ids,permissions) VALUES(?,'[\"1\"]','{}')",visible.id)
                execute("INSERT INTO api_user_access(user_id,source_ids,permissions) VALUES(?,'[]','{}')",hidden.id)
                execute("INSERT INTO artist(id,name,sort_name,album_count,song_count,search) VALUES('generated-artist','Generated','generated',1,1,'generated')")
                execute("""INSERT INTO album(id,name,sort_name,album_artist,artist_id,compilation,song_count,duration,size,created,art_track_id,search)
                    VALUES('generated-album','Generated','generated','Generated','generated-artist',0,1,1,4,1,'generated-track','generated')""")
                execute("INSERT INTO album_artist VALUES('generated-album','generated-artist','Generated',0)")
                execute("""INSERT INTO track(id,path,folder_id,parent_dir,mtime,size,title,album,album_id,artist,artist_id,
                    album_artist,album_artist_id,duration,bitrate,suffix,content_type,has_embedded_art,created,updated,search)
                    VALUES('generated-track','generated.flac',1,'',1,4,'Generated','Generated','generated-album','Generated','generated-artist',
                    'Generated','generated-artist',1,128,'flac','audio/flac',1,1,1,'generated')""")
                execute("INSERT INTO track_artist VALUES('generated-track','artist','generated-artist','Generated',0)")
                execute("INSERT INTO track_artist VALUES('generated-track','albumartist','generated-artist','Generated',0)")
            }
            artwork=newArtwork()
        }
        fun newArtwork()=Artwork(ctx.db,ctx.config.musicFolder,cache,executable.toString(),ctx.access)
        fun color(rgb:Int) {
            val image=BufferedImage(256,256,BufferedImage.TYPE_INT_RGB)
            for(x in 0..255)for(y in 0..255)image.setRGB(x,y,rgb)
            check(ImageIO.write(image,"png",payload.toFile()))
        }
        fun count()=if(Files.exists(countFile))Files.readAllLines(countFile).size else 0
        fun image(size:Int?=null,user:User=visible)=artwork.image("mf-generated-track",size,user)!!
        override fun close() {ctx.close();ctx.db.close();root.toFile().deleteRecursively()}
    }
    private fun isBlue(bytes:ByteArray) {
        val rgb=ImageIO.read(ByteArrayInputStream(bytes)).getRGB(0,0)
        assertTrue((rgb and 0xff)>200 && ((rgb shr 16) and 0xff)<30,"Expected new blue artwork, rgb=${rgb.toUInt().toString(16)}")
    }
    @Test fun repeatedOriginalResizedAndInheritedRequestsExtractOnceAcrossArtworkInstances() {
        Fixture().use { f ->
            val original=f.image().bytes
            assertContentEquals(Files.readAllBytes(f.payload),original)
            repeat(2) {
                assertContentEquals(original,f.image().bytes)
                assertEquals(64,ImageIO.read(ByteArrayInputStream(f.image(64).bytes)).width)
                assertNotNull(f.artwork.image("al-generated-album",32,f.visible))
                assertNotNull(f.artwork.image("ar-generated-artist",null,f.visible))
            }
            assertContentEquals(original,f.newArtwork().image("mf-generated-track",null,f.visible)!!.bytes)
            println("GENERATED_EMBEDDED_CACHE path=repeated-original-resized-inherited extractions=${f.count()}")
            assertEquals(1,f.count(),"Original and resized views of one embedded source must reuse one disk extraction")
        }
    }
    @Test fun sourceMtimeChangeInvalidatesOriginalImage() {
        Fixture().use { f ->
            f.image();val before=Files.getLastModifiedTime(f.source)
            f.color(0x0000ff);Files.setLastModifiedTime(f.source,FileTime.fromMillis(before.toMillis()+5_000))
            isBlue(f.image().bytes)
            assertEquals(2,f.count())
        }
    }
    @Test fun sourceSizeChangeWithSameMtimeInvalidatesOriginalAndResizedImages() {
        Fixture().use { f ->
            f.image();f.image(64)
            val before=Files.getLastModifiedTime(f.source)
            f.color(0x0000ff);Files.write(f.source,byteArrayOf(1,2,3,4,5));Files.setLastModifiedTime(f.source,before)
            assertEquals(before,Files.getLastModifiedTime(f.source))
            isBlue(f.image().bytes);isBlue(f.image(64).bytes)
            println("GENERATED_EMBEDDED_CACHE path=size-only-invalidated extractions=${f.count()}")
            assertEquals(2,f.count(),"A changed source must extract once, including when its mtime is preserved")
        }
    }
    @Test fun deletedSourceDoesNotServePopulatedOriginalOrResizedCache() {
        Fixture().use { f ->
            val original=f.image().bytes;val resized=f.image(64).bytes;val count=f.count()
            Files.delete(f.source)
            assertFalse(original.contentEquals(f.image().bytes),"Deleted source must return placeholder rather than old original")
            assertFalse(resized.contentEquals(f.image(64).bytes),"Deleted source must not return old resized artwork")
            assertEquals(count,f.count(),"Missing source must not launch extraction")
        }
    }
    @Test fun movedSourceRequiresCurrentPathAndNewExtraction() {
        Fixture().use { f ->
            val original=f.image().bytes;val before=f.count()
            Files.move(f.source,f.source.resolveSibling("moved.flac"))
            assertFalse(original.contentEquals(f.image().bytes),"Stale catalog path must not use old cache")
            assertEquals(before,f.count())
            f.ctx.db.write {execute("UPDATE track SET path='moved.flac' WHERE id='generated-track'")}
            assertContentEquals(original,f.image().bytes)
            assertEquals(before+1,f.count(),"New real path must have its own extraction key")
        }
    }
    @Test fun replacementSymlinkOutsideMusicBoundaryDoesNotServePopulatedCache() {
        Fixture().use { f ->
            val original=f.image().bytes;val resized=f.image(64).bytes;val before=f.count()
            val outside=f.root.resolve("outside.flac");Files.move(f.source,outside);Files.createSymbolicLink(f.source,outside)
            assertFalse(original.contentEquals(f.image().bytes),"Out-of-boundary replacement must not reveal cached original")
            assertFalse(resized.contentEquals(f.image(64).bytes),"Out-of-boundary replacement must not reveal cached resized image")
            assertEquals(before,f.count())
        }
    }
    @Test fun hiddenUserAndRevokedVisibleUserCannotReadPopulatedCache() {
        Fixture().use { f ->
            f.image();f.image(64);val before=f.count()
            assertNull(f.artwork.image("mf-generated-track",null,f.hidden))
            assertNull(f.artwork.image("al-generated-album",64,f.hidden))
            assertNull(f.artwork.image("ar-generated-artist",null,f.hidden))
            f.ctx.db.write {execute("UPDATE api_user_access SET source_ids='[]' WHERE user_id=?",f.visible.id)}
            assertNull(f.artwork.image("mf-generated-track",null,f.visible))
            assertNull(f.artwork.image("al-generated-album",64,f.visible))
            assertEquals(before,f.count(),"Permission check must precede cache access and extraction")
        }
    }
    @Test fun failedExtractionIsRetriedAndOnlySuccessIsCached() {
        Fixture().use { f ->
            Files.writeString(f.failure,"fail")
            val failed=f.image().bytes;assertEquals(1,f.count())
            Files.delete(f.failure)
            val success=f.image().bytes
            assertContentEquals(Files.readAllBytes(f.payload),success)
            assertFalse(failed.contentEquals(success))
            assertEquals(2,f.count(),"Failed extraction must not poison the original cache")
            assertContentEquals(success,f.image().bytes)
            println("GENERATED_EMBEDDED_CACHE path=failure-retry extractions=${f.count()}")
            assertEquals(2,f.count(),"Successful retry must then be cached")
        }
    }
}
