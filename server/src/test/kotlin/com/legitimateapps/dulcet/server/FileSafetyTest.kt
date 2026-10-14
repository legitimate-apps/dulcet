package com.legitimateapps.dulcet.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class FileSafetyTest {
    @Test fun scannerSkipsEscapingLinksAndRootArtistDoesNotReadParent() {
        val root = createTempDirectory("file-safety")
        val ctx = Fixtures.context(root)
        val music = ctx.config.musicFolder
        Fixtures.track(root.resolve("outside.flac"))
        Files.createSymbolicLink(music.resolve("escape.flac"), root.resolve("outside.flac"))
        Fixtures.track(music.resolve("song.flac"), tags = mapOf("artist" to "Singer"))
        Files.writeString(root.resolve("secret.txt"), "private canary")
        Files.createSymbolicLink(music.resolve("song.txt"), root.resolve("secret.txt"))
        Files.createSymbolicLink(music.resolve("cover.jpg"), root.resolve("secret.txt"))
        Fixtures.jpeg(root.resolve("artist.jpg"), 32)
        ctx.scanner.scan()
        assertEquals(1L, ctx.db.read { long("SELECT COUNT(*) FROM track") })
        assertNull(ctx.db.read { string("SELECT lyrics_sidecar FROM track") })
        assertNull(ctx.db.read { string("SELECT folder_art FROM album") })
        val artist = ctx.db.read { string("SELECT id FROM artist")!! }
        assertEquals("image/png", ctx.artwork.image("ar-$artist", null)!!.contentType)
    }

    @Test fun rootArtistNeverReadsAboveMusicFolder() {
        val root = createTempDirectory("root-artist")
        val ctx = Fixtures.context(root)
        Fixtures.track(ctx.config.musicFolder.resolve("song.flac"), tags = mapOf("artist" to "Singer"))
        Fixtures.jpeg(root.resolve("artist.jpg"), 32)
        ctx.scanner.scan()
        val id = ctx.db.read { string("SELECT id FROM artist")!! }
        assertEquals("image/png", ctx.artwork.image("ar-$id", null)!!.contentType)
    }

    @Test fun servingRechecksSymlinksAndTraversalAfterScan() = testApplication {
        val root = createTempDirectory("file-serving")
        val ctx = Fixtures.context(root)
        val song = ctx.config.musicFolder.resolve("song.flac")
        Fixtures.track(song)
        val sidecar = ctx.config.musicFolder.resolve("song.txt")
        Files.writeString(sidecar, "safe lyrics")
        val cover = ctx.config.musicFolder.resolve("cover.jpg")
        Fixtures.jpeg(cover, 32)
        ctx.scanner.scan()
        ctx.users.create("reader", "pw", false)
        val id = ctx.db.read { string("SELECT id FROM track")!! }
        val album = ctx.db.read { string("SELECT id FROM album")!! }
        val secret = root.resolve("secret.txt")
        Files.writeString(secret, "private canary")
        for (file in listOf(song, sidecar, cover)) {
            Files.delete(file)
            Files.createSymbolicLink(file, secret)
        }
        application { dulcetModule(ctx) }
        for (endpoint in listOf("stream", "download")) {
            val body = client.get("/rest/$endpoint?u=reader&p=pw&v=1&c=t&f=json&id=$id").bodyAsText()
            assertTrue("\"code\":70" in body, body)
        }
        assertFalse(ctx.artwork.image("al-$album", null)!!.bytes.decodeToString().contains("private canary"))
        assertTrue(ctx.lyrics.forTrack(id)!!.isEmpty())
        ctx.db.write {
            execute("UPDATE track SET path = '../secret.txt', lyrics_sidecar = '../secret.txt'")
            execute("UPDATE album SET folder_art = '../secret.txt'")
        }
        assertTrue("\"code\":70" in client.get("/rest/download?u=reader&p=pw&v=1&c=t&f=json&id=$id").bodyAsText())
        assertTrue(ctx.lyrics.forTrack(id)!!.isEmpty())
        assertFalse(ctx.artwork.image("al-$album", null)!!.bytes.decodeToString().contains("private canary"))
    }
}
