package com.legitimateapps.dulcet.emulator

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The instrument for emulator playback evidence: reads the disposable server's own play record.
 * It is not the app's client; it signs its own requests under a distinct client name. Writes go to
 * the disposable server only, and its credentials are the published fixture constants.
 */
class DisposableServerProbe private constructor(val baseUrl: String) {
    fun call(endpoint: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val token = MessageDigest.getInstance("MD5").digest((PASSWORD + salt).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val query = (mapOf("u" to USER, "t" to token, "s" to salt, "v" to "1.16.1", "c" to CLIENT, "f" to "json") + parameters)
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val connection = URL("$baseUrl/rest/$endpoint.view?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            val body = connection.inputStream.bufferedReader().readText()
            val response = JSONObject(body).getJSONObject("subsonic-response")
            check(response.getString("status") == "ok") { "Probe $endpoint was refused by the server" }
            return response
        } finally { connection.disconnect() }
    }

    fun playCount(rawId: String): Int = call("getSong", mapOf("id" to rawId)).getJSONObject("song").optInt("playCount", 0)

    /** The raw id of the one song titled [title], asserted unique so the probe cannot read the wrong song. */
    fun songId(title: String): String {
        val songs = call("search3", mapOf("query" to title, "songCount" to "20", "albumCount" to "0", "artistCount" to "0"))
            .getJSONObject("searchResult3").optJSONArray("song") ?: error("The fixture corpus has no song titled $title")
        val matches = (0 until songs.length()).map(songs::getJSONObject).filter { it.getString("title") == title }
        check(matches.size == 1) { "Expected exactly one fixture song titled $title, found ${matches.size}" }
        return matches.single().getString("id")
    }

    companion object {
        const val USER = "dulcet-admin"
        const val PASSWORD = "dulcet-ci-canary-password"
        const val CANARY_TITLE = "UI Playback Canary"
        private const val CLIENT = "dulcet-emulator-probe"

        /**
         * The server named by the runner. Absent, not disposable, or not answering is a FAILURE,
         * never a skip: a skipped playback proof reads as green while proving nothing.
         */
        fun fromInstrumentation(): DisposableServerProbe {
            val arguments = InstrumentationRegistry.getArguments()
            val url = arguments.getString("dulcetServerUrl")
                ?: error("dulcetServerUrl is required: emulator playback evidence needs the disposable server")
            check(arguments.getString("dulcetServerDisposable") == "true") {
                "Emulator playback tests write plays; they run only against a disposable server"
            }
            return DisposableServerProbe(url.trimEnd('/')).also { it.call("ping") }
        }
    }
}
