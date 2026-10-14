package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.scan.Probe
import com.legitimateapps.dulcet.server.media.Transcoder
import com.legitimateapps.dulcet.server.media.OutputFormat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ProtocolSafetyTest {
    @Test fun transcodesHaveABoundedNumberOfLiveProcesses() {
        val root = createTempDirectory("transcode-capacity")
        val executable = root.resolve("encoder")
        Files.writeString(executable, "#!/bin/sh\nexec sleep 30\n")
        executable.toFile().setExecutable(true)
        val transcoder = Transcoder(executable.toString())
        val live = ArrayList<Process>()
        try {
            repeat(4) { live += transcoder.start(root.resolve("unused"), OutputFormat.MP3, 128, null, 0) }
            assertFails { transcoder.start(root.resolve("unused"), OutputFormat.MP3, 128, null, 0).also { live += it } }
        } finally { live.forEach { it.destroyForcibly(); it.waitFor() } }
    }

    @Test fun capacityReturns429AndCleansTemporaryFiles() = testApplication {
        val root = createTempDirectory("transcode-http-capacity")
        val executable = root.resolve("encoder")
        Files.writeString(executable, """#!/bin/sh
for arg in "${'$'}@"; do
  case "${'$'}arg" in */hold) exec sleep 30;; esac
done
printf audio
""")
        executable.toFile().setExecutable(true)
        val music = Files.createDirectory(root.resolve("music"))
        val data = Files.createDirectory(root.resolve("data"))
        val config = Config(music, data, 0, "127.0.0.1", executable.toString(), Fixtures.ffprobe, null, scanOnStart = false)
        val ctx = ServerContext(config, com.legitimateapps.dulcet.server.db.Db.inMemory())
        Fixtures.track(music.resolve("song.flac"))
        ctx.scanner.scan()
        ctx.users.create("reader", "pw", false)
        val id = ctx.db.read { string("SELECT id FROM track")!! }
        val live = ArrayList<Process>()
        application { dulcetModule(ctx) }
        try {
            repeat(4) { live += ctx.transcoder.start(root.resolve("hold"), OutputFormat.MP3, 128, null, 0) }
            for (ranged in listOf(true, false)) {
                val response = client.get("/rest/stream?u=reader&p=pw&v=1&c=t&id=$id&format=mp3") {
                    if (ranged) header("Range", "bytes=0-9")
                }
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                assertEquals("1", response.headers["Retry-After"])
            }
            Files.walk(data.resolve("cache")).use { files ->
                assertEquals(0L, files.filter { it.fileName.toString().endsWith(".part") }.count())
            }
        } finally { live.forEach { it.destroyForcibly(); it.waitFor() } }
    }

    private fun disguisedPlaylist(): java.nio.file.Path {
        val root = createTempDirectory("protocol-safety")
        val music = Files.createDirectory(root.resolve("music"))
        Fixtures.track(root.resolve("outside.mp3"))
        Files.createSymbolicLink(music.resolve("segment.mp3"), root.resolve("outside.mp3"))
        return music.resolve("playlist.mp3").also { Files.writeString(it, "ffconcat version 1.0\nfile 'segment.mp3'\n") }
    }

    @Test fun disguisedConcatCannotProbeAnEscapingSegment() {
        assertFails { Probe(Fixtures.ffprobe).probe(disguisedPlaylist()) }
    }

    @Test fun disguisedConcatCannotTranscodeAnEscapingSegment() {
        val process = Transcoder(Fixtures.ffmpeg).start(disguisedPlaylist(), OutputFormat.MP3, 128, null, 0)
        try {
            val bytes = process.inputStream.readBytes()
            assertNotEquals(0, process.waitFor())
            assertTrue(bytes.isEmpty())
        } finally { process.destroyForcibly() }
    }
}
