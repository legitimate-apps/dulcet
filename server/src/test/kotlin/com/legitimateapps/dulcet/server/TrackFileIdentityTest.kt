package com.legitimateapps.dulcet.server

import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.*

/** Actual same-length FLAC metadata edits, not an mtime-only proxy for the missing condition. */
class TrackFileIdentityTest {
    private fun editPreservingSizeAndMtime(file: Path, edit: (ByteArray) -> ByteArray) {
        val old = Files.readAllBytes(file)
        val mtime = Files.getLastModifiedTime(file)
        val ctime = Files.getAttribute(file,"unix:ctime")
        // Settle even on coarse timestamp filesystems; the assertion below proves the condition.
        Thread.sleep(20)
        val next = edit(old)
        assertEquals(old.size,next.size)
        assertFalse(old.contentEquals(next))
        Files.write(file,next)
        Files.setLastModifiedTime(file,mtime)
        assertEquals(old.size.toLong(),Files.size(file))
        assertEquals(mtime,Files.getLastModifiedTime(file))
        assertNotEquals(ctime,Files.getAttribute(file,"unix:ctime"),"Fixture must actually change inode ctime")
    }
    @Test fun sameSizePreservedMtimeRetagIsReadAndKeepsAudioIdentity() {
        val root=Files.createTempDirectory("track-file-identity")
        val ctx=Fixtures.context(root)
        try {
            val file=ctx.config.musicFolder.resolve("generated.flac")
            Fixtures.track(file,tags=mapOf("title" to "AAA_Tag"))
            ctx.scanner.scan()
            val id=ctx.db.read { string("SELECT id FROM track")!! }
            editPreservingSizeAndMtime(file) { bytes ->
                val needle="AAA_Tag".toByteArray()
                val index=(0..bytes.size-needle.size).single { offset -> needle.indices.all { bytes[offset+it]==needle[it] } }
                bytes.copyOf().also { "BBB_Tag".toByteArray().copyInto(it,index) }
            }
            val result=ctx.scanner.scan()!!
            assertEquals(1,result.updated,"Same-size tag replacement with preserved mtime must be probed")
            assertEquals("BBB_Tag",ctx.db.read { string("SELECT title FROM track WHERE id=?",id) })
            assertEquals(1,ctx.scanner.scan()!!.unchanged)
        } finally { ctx.close();ctx.db.close();root.toFile().deleteRecursively() }
    }
    @Test fun newlyEmbeddedPictureInsideExistingPaddingIsDetected() {
        val root=Files.createTempDirectory("track-embedded-identity")
        val ctx=Fixtures.context(root)
        try {
            val file=ctx.config.musicFolder.resolve("generated.flac")
            Fixtures.track(file,tags=mapOf("title" to "Generated"))
            ctx.scanner.scan()
            assertEquals(0L,ctx.db.read { long("SELECT has_embedded_art FROM track") })
            val image=ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",it) }.toByteArray()
            val picture=ByteArrayOutputStream().also { raw -> DataOutputStream(raw).use { out ->
                val mime="image/png".toByteArray()
                out.writeInt(3);out.writeInt(mime.size);out.write(mime);out.writeInt(0)
                out.writeInt(8);out.writeInt(8);out.writeInt(24);out.writeInt(0)
                out.writeInt(image.size);out.write(image)
            } }.toByteArray()
            editPreservingSizeAndMtime(file) { original ->
                val bytes=original.copyOf();var offset=4;var inserted=false
                while(offset<bytes.size) {
                    val header=bytes[offset].toInt() and 255
                    val length=((bytes[offset+1].toInt() and 255) shl 16) or ((bytes[offset+2].toInt() and 255) shl 8) or (bytes[offset+3].toInt() and 255)
                    if(header and 127 == 1 && length>=picture.size+4) {
                        fun block(at:Int,kind:Int,size:Int) { bytes[at]=kind.toByte();bytes[at+1]=(size shr 16).toByte();bytes[at+2]=(size shr 8).toByte();bytes[at+3]=size.toByte() }
                        block(offset,6,picture.size);picture.copyInto(bytes,offset+4)
                        block(offset+4+picture.size,1 or (header and 128),length-picture.size-4)
                        inserted=true;break
                    }
                    if(header and 128 != 0) break
                    offset+=4+length
                }
                assertTrue(inserted,"Generated FLAC must have enough existing padding for an actual same-size picture insertion")
                bytes
            }
            val result=ctx.scanner.scan()!!
            assertEquals(1,result.updated,"New embedded art must not be hidden by unchanged size and mtime")
            assertEquals(1L,ctx.db.read { long("SELECT has_embedded_art FROM track") })
        } finally { ctx.close();ctx.db.close();root.toFile().deleteRecursively() }
    }
    @Test fun metadataOnlyChmodConservativelyRereadsOneTrackOnce() {
        val root=Files.createTempDirectory("track-chmod-identity")
        val ctx=Fixtures.context(root)
        try {
            val file=ctx.config.musicFolder.resolve("generated.flac")
            Fixtures.track(file)
            ctx.scanner.scan()
            val size=Files.size(file);val mtime=Files.getLastModifiedTime(file);val ctime=Files.getAttribute(file,"unix:ctime")
            Thread.sleep(20)
            val permissions=Files.getPosixFilePermissions(file).toMutableSet()
            val bit=java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
            if(!permissions.add(bit)) permissions.remove(bit)
            Files.setPosixFilePermissions(file,permissions)
            assertEquals(size,Files.size(file));assertEquals(mtime,Files.getLastModifiedTime(file))
            assertNotEquals(ctime,Files.getAttribute(file,"unix:ctime"),"chmod must actually change ctime")
            assertEquals(1,ctx.scanner.scan()!!.updated,"A metadata-only ctime change conservatively costs one reread")
            val head=ctx.journal.head()
            assertEquals(1,ctx.scanner.scan()!!.unchanged)
            assertEquals(head,ctx.journal.head(),"The adopted new ctime must not repeatedly reread or journal")
        } finally { ctx.close();ctx.db.close();root.toFile().deleteRecursively() }
    }
    @Test fun legacyBaselineAdoptionReadsNoTagsOrJournalEventsThenDetectsNextChange() {
        val root=Files.createTempDirectory("track-identity-adoption")
        val ctx=Fixtures.context(root)
        try {
            val file=ctx.config.musicFolder.resolve("generated.flac")
            Fixtures.track(file,tags=mapOf("title" to "AAA_Tag"))
            ctx.scanner.scan()
            // A missing versioned table models an upgrade from the previous scanner.
            ctx.db.write { execute("DROP TABLE IF EXISTS track_file_identity_v1") }
            val scanner=com.legitimateapps.dulcet.server.scan.Scanner(ctx.db,ctx.config.musicFolder,com.legitimateapps.dulcet.server.scan.Probe("must-not-run-generated-probe"))
            val before=ctx.journal.head()
            val adopted=scanner.scan(force=false)!!
            assertEquals(0,adopted.failed);assertEquals(0,adopted.updated);assertEquals(1,adopted.unchanged)
            assertEquals(before,ctx.journal.head(),"Metadata baseline adoption must emit no journal events")
            assertEquals(1L,ctx.db.read { long("SELECT COUNT(*) FROM track_file_identity_v1") })
            assertEquals(1,scanner.scan()!!.unchanged)
            editPreservingSizeAndMtime(file) { it.copyOf().also { bytes -> bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte() } }
            assertEquals(1,scanner.scan()!!.failed,"A subsequent changed identity must actually invoke the unavailable probe")
            assertEquals(1,scanner.scan()!!.failed,"Failed reads must not overwrite the previous identity and hide the pending edit")
        } finally { ctx.close();ctx.db.close();root.toFile().deleteRecursively() }
    }
}
