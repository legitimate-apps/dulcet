package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Builds small real audio files with ffmpeg, so the scanner reads genuine tags. */
object Fixtures {
    val ffmpeg: String = listOf("/opt/homebrew/bin/ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { Files.isExecutable(Path.of(it)) } ?: "ffmpeg"
    val ffprobe: String = ffmpeg.removeSuffix("ffmpeg") + "ffprobe"

    fun track(file: Path, seconds: Double = 1.0, tags: Map<String, String> = emptyMap(), picture: Path? = null) {
        Files.createDirectories(file.parent)
        val command = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds")
        if (picture != null) command += listOf("-i", picture.toString(), "-map", "0:a", "-map", "1:v", "-c:v", "copy", "-disposition:v", "attached_pic")
        tags.forEach { (key, value) -> command += listOf("-metadata", "$key=$value") }
        command += file.toString()
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg failed: $output" }
    }

    fun jpeg(file: Path, edge: Int = 800) {
        Files.createDirectories(file.parent)
        val process = ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=red:s=${edge}x$edge", "-frames:v", "1", file.toString())
            .redirectErrorStream(true).start()
        process.inputStream.readBytes()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0)
    }

    fun context(root: Path, provider: com.legitimateapps.dulcet.server.lyrics.LyricsProvider? = null): ServerContext {
        val config = Config(
            musicFolder = root.resolve("music").also(Files::createDirectories),
            dataFolder = root.resolve("data").also(Files::createDirectories),
            port = 0, bind = "127.0.0.1", ffmpeg = ffmpeg, ffprobe = ffprobe, lrclibUrl = null, scanOnStart = false,
        )
        return ServerContext(config, Db.inMemory(), provider)
    }
}

suspend fun HttpClient.rest(method: String, user: String, password: String, vararg params: Pair<String, String>): JsonObject {
    val query = (listOf("u" to user, "p" to password, "v" to "1.16.1", "c" to "test", "f" to "json") + params)
        .joinToString("&") { "${it.first}=${java.net.URLEncoder.encode(it.second, Charsets.UTF_8)}" }
    return Json.parseToJsonElement(get("/rest/$method?$query").bodyAsText()).jsonObject["subsonic-response"]!!.jsonObject
}
