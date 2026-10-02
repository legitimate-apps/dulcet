package com.legitimateapps.dulcet.search.conformance

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Looper
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.search.SearchHostDependencyOwner
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.*

/** The disposable loopback server this run was given; anything else fails setup, never skips. */
internal fun disposableBaseUrl(): String {
    check(System.getenv("DULCET_CONFORMANCE_DISPOSABLE") == "true") {
        "Live library tests require the disposable conformance runner"
    }
    val url = checkNotNull(System.getenv("DULCET_CONFORMANCE_BASE_URL")) { "No disposable server address was given" }
    check(Regex("""http://127\.0\.0\.1:\d{2,5}""").matches(url)) {
        "Live library tests run only against a disposable server on this machine's loopback"
    }
    val port = url.substringAfterLast(':').toInt()
    // A refusal is a failure, never an assumption or a skip.
    try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
    } catch (_: Exception) {
        error("Disposable library server must be reachable (endpoint redacted)")
    }
    return url
}

/**
 * Closes the process's reader and waits until it has terminated: a cold start follows. It gives up
 * after 3,000 polls 5 ms apart, about 15 s plus the idling, which is normally inside the reader's
 * 30 s executor bound, so the termination it sees is a real thread stop.
 */
internal fun closeProcessReader() {
    var done = false
    val started = kotlin.time.TimeSource.Monotonic.markNow()
    AndroidLibraryReader.closeCurrent { done = true }
    repeat(3_000) {
        if (done) {
            println("READER_CLOSED waited-ms=${started.elapsedNow().inWholeMilliseconds}")
            return
        }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    // The last idle may have delivered it.
    if (done) return
    error("The reader did not terminate within 3,000 polls")
}

/**
 * The production app against the disposable server, through a [CountingProxy]: the saved account
 * points at the proxy, which forwards every request unchanged and counts it. Nothing in the app is
 * replaced — the reader, the session, the transport and the database are the production ones.
 */
class ProductionLibraryEnvironment : ExternalResource() {
    lateinit var proxy: CountingProxy
        private set
    lateinit var server: DisposableServer
        private set
    val network: PlatformNetwork by lazy { PlatformNetwork(RuntimeEnvironment.getApplication()) }

    override fun before() {
        val target = disposableBaseUrl()
        val app = RuntimeEnvironment.getApplication()
        assertFalse(app is SearchHostDependencyOwner, "Application must not replace any dependency")
        closeProcessReader()
        app.deleteDatabase("dulcet.db")
        server = DisposableServer(target)
        // Favourites are per-user server state; a run starts from none, whatever an earlier run left.
        server.unstarEverything()
        // So are playlists: a run starts with none of the ones these tests make.
        server.deletePlaylistsNamed(TEST_PLAYLIST_PREFIX)
        proxy = CountingProxy(target)
        AndroidAccountCredentialStore(app).save("Disposable", proxy.baseUrl, USERNAME, PASSWORD, true)
    }

    override fun after() {
        val app = RuntimeEnvironment.getApplication()
        runCatching { network.restoreIfLost() }
        runCatching { closeProcessReader() }
        proxy.close()
        runCatching { server.unstarEverything() }
        runCatching { server.deletePlaylistsNamed(TEST_PLAYLIST_PREFIX) }
        AndroidAccountCredentialStore(app).delete()
        app.deleteDatabase("dulcet.db")
    }

    companion object {
        const val USERNAME = "dulcet-admin"
        const val PASSWORD = "dulcet-ci-canary-password"

        /** Every playlist a test makes is named with this, and only those are ever deleted. */
        const val TEST_PLAYLIST_PREFIX = "Dulcet test "
    }
}

/**
 * The platform's reachability, driven the way Android reports it: the default network goes away
 * (no active network, `onLost`) and comes back (`onAvailable`) to every registered default-network
 * callback — the production session's among them.
 */
class PlatformNetwork(app: Application) {
    private val manager = app.getSystemService(ConnectivityManager::class.java)
    private val shadow = shadowOf(manager)
    private var saved: NetworkInfo? = null
    private var lost = false

    /** The default network the callbacks were last told of: the host's own, or one switched to. */
    private var reported: android.net.Network? = null

    fun lose() {
        check(!lost)
        val network = checkNotNull(reported ?: manager.activeNetwork) { "setup: the host must start with a network" }
        check(shadow.networkCallbacks.isNotEmpty()) { "setup: the session registered no network callback" }
        saved = manager.activeNetworkInfo
        shadow.setActiveNetworkInfo(null)
        check(manager.activeNetwork == null) { "setup: the platform still reports a network" }
        lost = true
        shadow.networkCallbacks.toList().forEach { it.onLost(network) }
    }

    fun restore() {
        check(lost)
        shadow.setActiveNetworkInfo(saved)
        val network = checkNotNull(manager.activeNetwork) { "setup: the platform reports no network" }
        lost = false
        reported = network
        shadow.networkCallbacks.toList().forEach { it.onAvailable(network) }
    }

    /**
     * The device moves to a different network while it has one — Wi-Fi to cellular, say: the platform
     * reports a new default network, with no loss in between.
     */
    fun switchNetwork() {
        check(!lost)
        check(shadow.networkCallbacks.isNotEmpty()) { "setup: the session registered no network callback" }
        val other = org.robolectric.shadows.ShadowNetwork.newInstance(++switches + 1_000)
        reported = other
        shadow.networkCallbacks.toList().forEach { it.onAvailable(other) }
    }

    private var switches = 0

    /**
     * The platform's report that the default network is [constrained] (metered) or not, as Android
     * gives it: the network's capabilities change, and every default-network callback hears it.
     */
    fun constrain(constrained: Boolean) {
        check(!lost) { "setup: a constraint is reported for a network the device has" }
        val network = checkNotNull(reported ?: manager.activeNetwork) { "setup: the host must start with a network" }
        check(shadow.networkCallbacks.isNotEmpty()) { "setup: the session registered no network callback" }
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        if (!constrained) shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadow.setNetworkCapabilities(network, capabilities)
        shadow.networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, capabilities) }
    }

    internal fun restoreIfLost() {
        if (lost) {
            shadow.setActiveNetworkInfo(saved)
            lost = false
        }
    }
}

/**
 * Direct reads of the disposable server, bypassing the app entirely: the independent instrument
 * for per-user state the app claims to have changed.
 */
class DisposableServer(private val baseUrl: String) {
    fun get(endpoint: String, parameters: Map<String, String> = emptyMap()): JSONObject = get(endpoint, parameters.toList())

    /** [parameters] in order, a name repeated as often as it appears (`songId`, `songIdToAdd`). */
    fun get(endpoint: String, parameters: List<Pair<String, String>>): JSONObject {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val token = MessageDigest.getInstance("MD5").digest((ProductionLibraryEnvironment.PASSWORD + salt).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val query = mapOf("u" to ProductionLibraryEnvironment.USERNAME, "t" to token, "s" to salt, "v" to "1.16.1",
            "c" to "dulcet-conformance", "f" to "json")
            .toList().let { it + parameters }
            .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val connection = URI("$baseUrl/rest/$endpoint?$query").toURL().openConnection() as HttpURLConnection
        try {
            val body = connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val response = JSONObject(body).getJSONObject("subsonic-response")
            check(response.getString("status") == "ok") { "$endpoint failed on the disposable server" }
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun albumStarred(albumId: String): Boolean = get("getAlbum", mapOf("id" to albumId)).getJSONObject("album").has("starred")

    fun songStarred(songId: String): Boolean = get("getSong", mapOf("id" to songId)).getJSONObject("song").has("starred")

    /** The account's rating of a song as the server reports it: 0 when it has none. */
    fun songRating(songId: String): Int = get("getSong", mapOf("id" to songId)).getJSONObject("song").optInt("userRating", 0)

    /** The account's playlists: id to name. */
    fun playlists(): Map<String, String> {
        val list = get("getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist") ?: JSONArray()
        return (0 until list.length()).associate { list.getJSONObject(it).getString("id") to list.getJSONObject(it).getString("name") }
    }

    /** One playlist's entries, song ids in the server's order, duplicates kept. */
    fun playlistEntries(id: String): List<String> {
        val entries = get("getPlaylist", mapOf("id" to id)).getJSONObject("playlist").optJSONArray("entry") ?: JSONArray()
        return (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }
    }

    /** Makes a playlist directly on the server, as another client would. Returns its id. */
    fun createPlaylist(name: String, songIds: List<String>): String {
        require(name.startsWith(ProductionLibraryEnvironment.TEST_PLAYLIST_PREFIX)) { "a test playlist is named for cleanup" }
        return get("createPlaylist", listOf("name" to name) + songIds.map { "songId" to it }).getJSONObject("playlist").getString("id")
    }

    fun deletePlaylistsNamed(prefix: String) {
        for ((id, name) in playlists()) if (name.startsWith(prefix)) get("deletePlaylist", mapOf("id" to id))
    }

    fun unstarEverything() {
        val starred = get("getStarred2").optJSONObject("starred2") ?: return
        for (kind in listOf("artist", "album", "song")) {
            val items = starred.optJSONArray(kind) ?: JSONArray()
            for (index in 0 until items.length()) get("unstar", mapOf("id" to items.getJSONObject(index).getString("id")))
        }
    }
}
