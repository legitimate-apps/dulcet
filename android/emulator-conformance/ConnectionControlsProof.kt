package com.legitimateapps.dulcet.emulator

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.StoredAccount
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlin.math.roundToInt

/** Only native input/navigation adapters: no backend or presentation result can be supplied. */
interface ConnectionControlsUi {
    fun connect(fixture: ConnectionControlsFixture, legacy: Boolean)
    fun relaunch()
    fun openAccount()
    fun openLibrary(): String
    fun activate(tag: String)
    fun enterAddress(value: String)
    fun show(tag: String)
    fun close()
}

class ConnectionControlsFixture private constructor(val document: JSONObject) {
    val nonce: String = document.getString("nonce")
    val username: String = document.getString("username")
    val password: String = document.getString("password")
    val home: String = document.getString("routeA")
    val legacy: String = document.getString("legacyHttp")
    val longLabel: String = document.getJSONObject("routeLabels").getString("home")
    fun route(id: String): String = document.getJSONObject("routes").getString(id)
    fun control(action: String, parameters: Map<String, String> = emptyMap(), post: Boolean = false): JSONObject {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            java.net.URLEncoder.encode(key, "UTF-8") + "=" + java.net.URLEncoder.encode(value, "UTF-8")
        }
        val url = document.getString("controlBase") + "/" + action + if (query.isEmpty()) "" else "?$query"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000; connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = if (post) "POST" else "GET"
        connection.setRequestProperty("X-Dulcet-Fixture", nonce)
        return try {
            check(connection.responseCode == 200) { "The owned fixture control must acknowledge its request" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).also {
                check(it.getString("nonce") == nonce) { "The fixture receipt belongs to another run" }
            }
        } finally { connection.disconnect() }
    }
    fun receipts(): List<JSONObject> = control("state").getJSONArray("requests").let { array ->
        (0 until array.length()).map(array::getJSONObject)
    }
    fun authenticated(route: String): Boolean = receipts().any {
        it.getString("routeId") == route && it.getBoolean("completed") &&
            (it.getBoolean("queryAuth") || it.getBoolean("bodyAuth") || it.getBoolean("authorization"))
    }
    fun directories(route: String): Int = receipts().count {
        it.getString("routeId") == route && it.getString("endpoint") == "getDulcetConnections" &&
            it.getString("method") == "POST" && it.getBoolean("bodyAuth") && it.getBoolean("completed")
    }
    companion object {
        fun load(): ConnectionControlsFixture {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val emulator = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use {
                FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
            }
            check(emulator == "1") { "Connections controls proofs require a disposable emulator, never a physical device" }
            val raw = checkNotNull(InstrumentationRegistry.getArguments().getString("DULCET_MULTIPATH_MANIFEST_JSON")) {
                "The owned disposable multipath fixture manifest is required; no existing server is a fallback"
            }
            val document = JSONObject(raw)
            check(document.getString("standard") == "dulcet-multipath-fixture" && document.getBoolean("disposable") && document.getInt("phase") == 2)
            UUID.fromString(document.getString("nonce"))
            val routes = document.getJSONObject("routes")
            val destinations = routes.keys().asSequence().map(routes::getString).toList() +
                listOf(document.getString("routeA"), document.getString("legacyHttp"), document.getString("controlBase"))
            destinations.forEach { address ->
                val uri = URI(address)
                check(uri.host == "localhost" && uri.port in 1..65535 && uri.scheme in setOf("http", "https") &&
                    uri.userInfo == null && uri.query == null && uri.fragment == null) { "Only owned loopback fixture destinations are accepted" }
            }
            check(document.getString("routeA") == routes.getString("home"))
            check(document.getJSONArray("tracks").length() == 4 && document.getString("corpusSha256").matches(Regex("[a-f0-9]{64}")))
            return ConnectionControlsFixture(document).also { fixture ->
                check(fixture.longLabel.length > 80)
                fixture.control("state") // Positive nonce witness before the app can authenticate.
            }
        }
    }
}

/** MP-09 orchestration shared by the existing phone and TV account-state instrumentation classes. */
class ConnectionControlsProof(private val compose: ComposeTestRule, private val ui: ConnectionControlsUi,
                              private val surface: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val credentials = AndroidAccountCredentialStore(context)
    private val fixture = ConnectionControlsFixture.load()
    init { check(credentials.activeAccountId() == null) { "A controls proof starts from an empty disposable app install" } }

    fun currentRemovalAndReadd() = run("current") {
        fixture.control("reset", post = true)
        ui.connect(fixture, legacy = false)
        val cachedAlbum = ui.openLibrary()
        check(cachedAlbum.contains("Fixture Album")) { "The actual fixture album is required before offline assertions" }
        ui.openAccount()
        val owner = saved()
        current("home")
        expect("connections.label.home", fixture.longLabel)
        capture("MP09-01-current-home-long-label")

        fixture.control("reset", post = true)
        fixture.control("outage", mapOf("routeId" to "home", "state" to "on"), post = true)
        ui.relaunch(); ui.openAccount()
        current("tunnel")
        expect("connections.status.home", "Unavailable")
        check(fixture.receipts().any { it.getString("routeId") == "home" && !it.getBoolean("completed") })
        unchanged(owner)
        capture("MP09-02-unavailable-home-current-tunnel")

        ui.activate("connections.remove.home")
        val directoryReads = fixture.directories("tunnel")
        ui.activate("connections.verify")
        await("Verify must authenticate through the surviving route") { fixture.directories("tunnel") > directoryReads }
        check(!exists("connections.remove.home")) { "Verify cannot clear the removed bootstrap tombstone" }
        for (route in listOf("different", "bad-name", "tunnel", "remote")) ui.activate("connections.remove.$route")
        expect("connections.offline", "Offline · Your downloads are available")
        unchanged(owner)
        capture("MP09-03-last-route-removed-offline")

        fixture.control("reset", post = true)
        ui.relaunch()
        check(ui.openLibrary() == cachedAlbum) { "The same cached album must remain browsable after removing every route" }
        ui.openAccount()
        expect("connections.offline", "Offline · Your downloads are available")
        check(fixture.receipts().isEmpty()) { "An offline account with no routes cannot fall back to the ownership anchor" }
        unchanged(owner)
        ui.enterAddress(fixture.home); ui.activate("connections.add")
        current("home")
        check(!exists("connections.remove.tunnel")) { "Explicit re-add clears only the chosen historical tombstone" }
        unchanged(owner)
        capture("MP09-04-explicit-bootstrap-readd")
        println("DULCET MP-09 ANDROID surface=$surface current-receipt=true silent-failure=true bootstrap-remove=true last-offline=true cached-album-retained=true readd=true")
    }

    fun upgradeCancelAndRefusal() = run("upgrade") {
        fixture.control("reset", post = true)
        ui.connect(fixture, legacy = true)
        val cachedAlbum = ui.openLibrary()
        check(cachedAlbum.contains("Fixture Album"))
        ui.openAccount()
        val owner = saved()
        check(owner.serverUrl == fixture.legacy)
        check(fixture.authenticated("legacy")) { "Zero legacy traffic later needs a real HTTP positive control first" }
        ui.enterAddress(fixture.home); ui.activate("connections.upgrade")
        current("home"); unchanged(owner)
        capture("MP09-05-preserving-https-upgrade")

        ui.enterAddress(fixture.route("different")); ui.activate("connections.add")
        await("The different-server Add must be refused in the actual UI") { exists("connections.error") }
        check(!exists("account.replacement")) { "Connections enrollment cannot offer destructive replacement" }
        unchanged(owner)
        expect("connections.status.home", "Current HTTPS connection")
        capture("MP09-06-different-server-refused")

        ui.activate(if (surface == "tv") "tv.account.signout" else "account.signout")
        await("Sign Out must offer Stay signed in") { exists("signout.stay") }
        ui.activate("signout.stay")
        unchanged(owner)
        fixture.control("reset", post = true)
        ui.relaunch()
        check(ui.openLibrary() == cachedAlbum) { "Upgrade and cancellation must retain the cached album" }
        ui.openAccount(); current("home"); unchanged(owner)
        check(fixture.receipts().none { it.getString("routeId") == "legacy" }) { "An adopted account never dials its historical HTTP anchor" }
        capture("MP09-07-upgraded-relaunch-cancel-preserved")
        println("DULCET MP-09 ANDROID surface=$surface preserving-upgrade=true cancel=true different-server-refused=true http-after-upgrade=0 cached-album-retained=true")
    }

    private fun run(label: String, proof: () -> Unit) {
        try { proof() }
        catch (failure: Throwable) {
            runCatching { capture("MP09-$label-failure") }.onFailure {
                println("CONNECTIONS MP-09 failure capture error=${it::class.simpleName}")
            }
            throw failure
        }
        finally {
            try { fixture.control("reset", post = true) }
            finally { ui.close(); forgetSavedAccount(context) }
        }
    }
    private fun saved(): StoredAccount = checkNotNull(credentials.load())
    private fun unchanged(owner: StoredAccount) { check(saved() == owner) { "Connections actions changed the saved ownership account" } }
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun text(tag: String): String = compose.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }
    private fun expect(tag: String, expected: String) {
        ui.show(tag)
        await("$tag must display the expected route state") { exists(tag) && text(tag) == expected }
    }
    private fun current(route: String) {
        expect("connections.status.$route", "Current HTTPS connection")
        expect("connections.address.$route", fixture.route(route))
        ui.show("connections.checked.$route")
        await("The actual check's wall time is accessible") { exists("connections.checked.$route") && text("connections.checked.$route").startsWith("Last checked:") }
        ui.show("connections.automatic")
        await("Automatic describes the server's route ordering") { exists("connections.automatic") && text("connections.automatic").contains("Automatic") }
        check(fixture.authenticated(route)) { "Current route text must match a completed authenticated receipt from that exact listener" }
    }
    private fun capture(name: String) {
        // Preserve the textual state even if the platform refuses the photograph.
        val directory = File(context.filesDir, "connection-controls/${fixture.nonce}").apply { check(isDirectory || mkdirs()) }
        val geometry = compose.onAllNodes(SemanticsMatcher("all control geometry") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes().joinToString("\n") { node ->
                "${node.config} position=${node.positionOnScreen} size=${node.size}"
            }
        File(directory, "$surface-$name.txt").writeText(geometry)
        instrumentation.waitForIdleSync()
        val original = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val ratio = minOf(1.0, 1000.0 / maxOf(original.width, original.height))
        val scaled = Bitmap.createScaledBitmap(original, (original.width * ratio).roundToInt(), (original.height * ratio).roundToInt(), true)
        File(directory, "$surface-$name.jpg").outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 55, it)) }
        if (scaled !== original) scaled.recycle()
        original.recycle()
    }
}
