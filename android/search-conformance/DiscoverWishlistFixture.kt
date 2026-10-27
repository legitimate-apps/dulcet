package com.legitimateapps.dulcet.search.conformance

import com.legitimateapps.dulcet.search.SearchAccount
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Only the disposable real servers supplied by with-discover-wishlist-fixture, never a fallback. */
class DiscoverWishlistFixture {
    val server = required("DULCET_DISCOVER_SERVER_URL").trimEnd('/')
    private val control = required("DULCET_DISCOVER_CONTROL_URL").trimEnd('/')
    val username = required("DULCET_DISCOVER_USERNAME")
    private val password = required("DULCET_DISCOVER_PASSWORD")

    init {
        check(required("DULCET_DISCOVER_DISPOSABLE") == "true") { "Use the disposable Discover fixture wrapper" }
        for (address in listOf(server, control)) {
            val uri = URI(address)
            check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.rawQuery == null) {
                "Discover host conformance accepts only disposable loopback servers"
            }
        }
        check(server == control) { "App and control must be on the wrapper's observed proxy" }
        check(control("/control/state").getBoolean("healthy")) { "Disposable fixture is not healthy" }
    }

    fun reset(): SearchAccount {
        val reset = control("/control/reset", JSONObject())
        check(reset.getBoolean("reset") && reset.has("identity")) { "A real fresh checkpoint is required" }
        return SearchAccount("provider:discover-host-${UUID.randomUUID()}", server, username, password, true)
    }

    fun arm(name: String): JSONObject = control("/control/scenario", JSONObject().put("name", name)).also {
        check(it.getBoolean("armed") && it.getString("scenario") == name && it.getLong("revision") > 0)
    }
    fun cursor(): Long = control("/control/requests").getLong("next")
    fun requests(since: Long): List<JSONObject> = control("/control/requests?since=$since").getJSONArray("requests").objects()
    fun effect(ack: JSONObject, endpoint: String, delivered: Boolean = false): Boolean =
        control("/control/state").getJSONArray("effects").objects().any {
            it.getLong("revision") == ack.getLong("revision") && it.getString("endpoint") == endpoint &&
                (!delivered || it.optBoolean("delivered"))
        }

    fun wishes(): List<JSONObject> = rest("getDulcetWishlist", mapOf("size" to "100", "includeFulfilledHistory" to "true"))
        .getJSONObject("dulcetWishlist").optJSONArray("wish")?.objects().orEmpty()
    fun wish(mbid: String): JSONObject? = wishes().firstOrNull {
        it.getJSONObject("externalIds").optString("musicbrainzRecordingId") == mbid
    }
    fun libraryContains(id: String): Boolean = rest("getDulcetLibrary", mapOf("type" to "song", "size" to "100"))
        .getJSONObject("dulcetLibrary").optJSONArray("song")?.objects()?.any { it.getString("id") == id } == true

    /** Independent public REST observations of what the actual app's outbox changed. */
    fun rest(endpoint: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        val salt = UUID.randomUUID().toString()
        val token = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        val query = (mapOf("u" to username, "t" to token, "s" to salt, "v" to "1.16.1", "c" to "dulcet-discover-host-proof", "f" to "json") + parameters)
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return http("$server/rest/$endpoint.view?$query").getJSONObject("subsonic-response").also {
            check(it.getString("status") == "ok") { "Disposable public REST probe failed: $endpoint" }
        }
    }
    private fun control(path: String, body: JSONObject? = null): JSONObject = http(control + path, body)
    private fun http(url: String, body: JSONObject? = null): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 35_000
        return try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(connection.responseCode in 200..299) { "Disposable request failed: HTTP ${connection.responseCode}" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    companion object {
        fun mbid(n: Int): String = "%08x-1111-4111-8111-111111111111".format(n)
        private fun required(name: String): String = checkNotNull(System.getenv(name)) { "Missing $name; use with-discover-wishlist-fixture" }
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    }
}
