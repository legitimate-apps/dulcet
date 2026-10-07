package com.legitimateapps.dulcet.tv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.ServerRelay
import com.legitimateapps.dulcet.emulator.await
import com.legitimateapps.dulcet.emulator.awaitQueuedBroadcastsDelivered
import com.legitimateapps.dulcet.emulator.awaitWindowInFront
import com.legitimateapps.dulcet.emulator.forgetSavedAccount
import com.legitimateapps.dulcet.emulator.loseSavedAccountKey
import com.legitimateapps.dulcet.emulator.savedAccountFileNamesAnAccount
import com.legitimateapps.dulcet.emulator.serverAnsweredErrorCode
import com.legitimateapps.dulcet.emulator.withPreferencesWritesRefused
import com.legitimateapps.dulcet.shared.R as SharedR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CONF-09b on an Android TV emulator, through the production TV app from an install with no saved
 * account, driven only with the remote: every action is a key event delivered to the focused window,
 * text is typed while the TV's own on-screen keyboard is up, and Back closes that keyboard. The
 * screen's semantics only say where focus is and what is shown. The server is the disposable one
 * behind a [ServerRelay], which can hold the request in flight, make the server unreachable, and read
 * what the server answered.
 *
 * The states, each reached by what the app itself does: idle; a wrong password refused by the server
 * itself (its Subsonic error 40) and said as an account not accepted, never as an unreachable
 * server; an unreachable server said as such; in progress, with Cancel, and nothing saved by the
 * request answered after Cancel; the device refusing the save (its preferences directory read-only),
 * said and nothing on disk; connected, the library opening with the remote on it and no keyboard;
 * a relaunch into the library this device has seen, saying the account is saved and not connected
 * and sending the server nothing — the relay counts no connection in five seconds — until the remote
 * chooses Reconnect, one DOWN from the Library tab, which reconnects in place; and a relaunch
 * after the Keystore lost the account's key, said, with Sign out reachable by the remote.
 *
 * The keyboard comes up only when the person selects a field with the centre key, never because the
 * remote landed on or passed over one: a keyboard that opens on its own takes the D-pad until Back
 * closes it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorAccountConnectProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun aPersonWithTheRemoteReachesEveryConnectStateFromAnEmptyInstallToTheLibraryAndBackAfterARelaunch() {
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        val probe = DisposableServerProbe.fromInstrumentation()
        awaitQueuedBroadcastsDelivered()
        val store = AndroidAccountCredentialStore(context)
        check(store.activeAccountId() == null) { "The proof starts from an install with no saved account" }
        val observed = mutableListOf<String>()
        var keyboardAtLaunch = false
        var keyboardsInTheWay = 0
        ServerRelay(probe.baseUrl).use { relay ->
            try {
                launch().use { scenario ->
                    awaitNode("the connect form, the remote on the server address") { focused("tv.connect.server") }
                    keyboardAtLaunch = !keyboardStaysDown(scenario)
                    check(!keyboardAtLaunch) { "The keyboard came up at launch, before the server address was selected" }
                    check(label("tv.connect.submit") == text(R.string.tv_connect)) { "Idle shows Connect" }
                    check(!exists("tv.connect.status")) { "Idle shows no status" }
                    observed += "idle"

                    enter("tv.connect.server", relay.url, scenario)
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("DOWN reaches the username") { focused("tv.connect.username") }
                    enter("tv.connect.username", DisposableServerProbe.USER, scenario)
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("DOWN reaches the password") { focused("tv.connect.password") }
                    enter("tv.connect.password", WRONG_PASSWORD, scenario)
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("DOWN reaches the local-HTTP switch") { focused("tv.connect.allow-local-http") }
                    check(!keyboardShown(scenario)) { "No keyboard on the switch" }
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("the centre key allows local HTTP") { checked("tv.connect.allow-local-http") }
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("DOWN reaches Connect") { focused("tv.connect.submit") }
                    check(!keyboardShown(scenario)) { "Connect must not be under a keyboard" }
                    check(onScreen("tv.connect.submit", scenario)) { "Connect must be on the screen when the remote is on it" }
                    check(fieldText("tv.connect.server") == relay.url) { "The server field holds ${fieldText("tv.connect.server")}" }
                    check(fieldText("tv.connect.username") == DisposableServerProbe.USER) { "The username field holds what was typed" }

                    // The server's own refusal of the password.
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("the server's refusal of the password") { status()?.startsWith(text(SharedR.string.error_auth_title)) == true }
                    check(relay.serverAnsweredErrorCode(40)) { "The server must itself have refused the password (Subsonic error 40)" }
                    check(focused("tv.connect.submit")) { "The remote stays on Connect after a failure" }
                    requireNothingSaved(store, "after a refused password")
                    observed += "domain-error(auth, server code 40)"

                    // The same form with the server unreachable.
                    relay.makeUnreachable()
                    val refusedBefore = relay.refusedConnections.get()
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("the unreachable server said") { status()?.startsWith(text(SharedR.string.error_unreachable_title)) == true }
                    check(relay.refusedConnections.get() > refusedBefore) { "The app must have tried the server" }
                    relay.makeReachable()
                    requireNothingSaved(store, "after an unreachable server")
                    observed += "domain-error(unreachable)"

                    // Back up to the password, with the remote, to correct it.
                    remote(KeyEvent.KEYCODE_DPAD_UP)
                    awaitNode("UP reaches the switch") { focused("tv.connect.allow-local-http") }
                    remote(KeyEvent.KEYCODE_DPAD_UP)
                    awaitNode("UP reaches the password") { focused("tv.connect.password") }
                    enter("tv.connect.password", DisposableServerProbe.PASSWORD, scenario, replacing = WRONG_PASSWORD.length)
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("back on Connect") { focused("tv.connect.submit") }

                    // In progress, held at the relay, then cancelled with the centre key.
                    relay.hold()
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("connecting, and Cancel") {
                        status() == text(R.string.tv_connecting) && label("tv.connect.submit") == text(R.string.tv_cancel)
                    }
                    await("the app's request held in flight") { relay.heldConnections.get() > 0 }
                    check(focused("tv.connect.submit")) { "The remote stays on the button that is now Cancel" }
                    observed += "in-progress"
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("Cancel returns the form to idle") {
                        !exists("tv.connect.status") && label("tv.connect.submit") == text(R.string.tv_connect)
                    }
                    relay.release()
                    SystemClock.sleep(SETTLE_MILLIS)
                    check(exists("tv.connect.submit") && !exists("library.surface")) { "A cancelled connect must stay on the form" }
                    requireNothingSaved(store, "after Cancel, once the held request was answered")
                    observed += "cancelled->idle"

                    // The server accepts the account and the device refuses to keep it.
                    val forwardedBefore = relay.forwardedConnections.get()
                    withPreferencesWritesRefused(context) {
                        remote(KeyEvent.KEYCODE_DPAD_CENTER)
                        awaitNode("the account that could not be saved") { status() == text(R.string.tv_error_persistence) }
                    }
                    check(relay.forwardedConnections.get() > forwardedBefore) { "The server must have been asked" }
                    check(!exists("library.surface")) { "An account that was not saved is not connected" }
                    requireNothingSaved(store, "after the device refused the save")
                    observed += "credential-persistence-error(save)"

                    // Connected: the library, with the remote on it and no keyboard.
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("the library, the remote on it", 60_000) {
                        exists("library.surface") && (focused("library.open") || focused("library.home.0.item.0"))
                    }
                    check(!keyboardShown(scenario)) { "No keyboard over the library" }
                    val saved = checkNotNull(store.load()) { "A connected account must be saved" }
                    check(saved.username == DisposableServerProbe.USER && saved.serverUrl.startsWith(relay.url)) { "The saved account is the one typed" }
                    check(savedAccountFileNamesAnAccount(context)) { "The saved account must be on disk" }
                    awaitNode("the server's albums in the library", 60_000) { exists("library.home.0.item.0") }
                    observed += "connected"
                }

                // Relaunch into the saved account (spec §13.1, CONF-10b).
                relaunchIntoTheSavedAccountAndReconnect(relay, observed)

                // Relaunch after the Keystore lost the saved account's key.
                endProcessConnection()
                loseSavedAccountKey(context)
                launch().use { scenario ->
                    awaitNode("the connect form, with Sign out for the unreadable account") {
                        exists("tv.connect.submit") && exists("tv.account.signout")
                    }
                    check(!exists("library.surface")) { "An unreadable account must not open the library" }
                    awaitNode("the unreadable account said") { status() == text(R.string.tv_error_unreadable) }
                    observed += "credential-persistence-error(load)"
                    // Sign out is reached by the remote: down from the form, past Connect.
                    // A field that brings up the keyboard as the remote passes takes the D-pad until
                    // Back closes it; each one is counted.
                    var presses = 0
                    while (!focused("tv.account.signout") && presses++ < 8) {
                        if (keyboardShown(scenario)) { keyboardsInTheWay += 1; remote(KeyEvent.KEYCODE_BACK) }
                        remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    }
                    check(focused("tv.account.signout")) { "DOWN must reach Sign out" }
                    check(keyboardsInTheWay == 0) { "$keyboardsInTheWay keyboards came up on fields the remote only passed over" }
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("the sign-out question, the remote on Stay") { focused("signout.stay") }
                    remote(KeyEvent.KEYCODE_DPAD_RIGHT)
                    awaitNode("RIGHT reaches Sign out anyway") { focused("signout.anyway") }
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("the empty form after signing the unreadable account out") {
                        exists("tv.connect.submit") && !exists("tv.account.signout") && !exists("signout.unknown")
                    }
                    check(store.activeAccountId() == null && !savedAccountFileNamesAnAccount(context)) { "Sign out must remove the account" }
                    observed += "signed-out->idle"
                }
                println("ANDROID TV EMULATOR ACCOUNT CONNECT OBSERVED surface=tv leanback=true states=${observed.joinToString(",")} " +
                    "keyboard-at-launch=$keyboardAtLaunch keyboards-in-the-way-to-sign-out=$keyboardsInTheWay")
            } finally {
                forgetSavedAccount(context)
            }
        }
    }

    /**
     * CONF-10b on an Android TV emulator (spec §13.1), with the remote only: an app launched into a
     * saved account sends its server nothing — no library read, no cover art, no reconnect on
     * reachability or the foreground — until the remote chooses Reconnect, one DOWN from the Library
     * tab, which connects in place without the form. The account is connected and saved through the
     * form first, as a person's is, so the relaunch has the albums this device has seen to show.
     */
    @Test fun aRelaunchIntoTheSavedAccountSendsNothingUntilTheRemoteChoosesReconnect() {
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        val probe = DisposableServerProbe.fromInstrumentation()
        awaitQueuedBroadcastsDelivered()
        val store = AndroidAccountCredentialStore(context)
        check(store.activeAccountId() == null) { "The proof starts from an install with no saved account" }
        val observed = mutableListOf<String>()
        ServerRelay(probe.baseUrl).use { relay ->
            try {
                launch().use { scenario ->
                    awaitNode("the connect form, the remote on the server address") { focused("tv.connect.server") }
                    enter("tv.connect.server", relay.url, scenario)
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("DOWN reaches the username") { focused("tv.connect.username") }
                    enter("tv.connect.username", DisposableServerProbe.USER, scenario)
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("DOWN reaches the password") { focused("tv.connect.password") }
                    enter("tv.connect.password", DisposableServerProbe.PASSWORD, scenario)
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("DOWN reaches the local-HTTP switch") { focused("tv.connect.allow-local-http") }
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("the centre key allows local HTTP") { checked("tv.connect.allow-local-http") }
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitNode("DOWN reaches Connect") { focused("tv.connect.submit") }
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitNode("the library, the remote on it", 60_000) {
                        exists("library.surface") && (focused("library.open") || focused("library.home.0.item.0"))
                    }
                    awaitNode("the server's albums in the library", 60_000) { exists("library.home.0.item.0") }
                    observed += "connected"
                }
                relaunchIntoTheSavedAccountAndReconnect(relay, observed)
                println("ANDROID TV EMULATOR SAVED ACCOUNT RELAUNCH OBSERVED surface=tv leanback=true states=${observed.joinToString(",")}")
            } finally {
                forgetSavedAccount(context)
            }
        }
    }

    /**
     * A relaunch into the saved account (spec §13.1): the library this device has seen, said to be
     * saved and not connected, and NOTHING sent to the server until the person chooses Reconnect with
     * the remote — one DOWN from the Library tab — which reconnects in place. The process's reader is
     * closed first, as a new process has none; this instrumentation shares the app's process.
     */
    private fun relaunchIntoTheSavedAccountAndReconnect(relay: ServerRelay, observed: MutableList<String>) {
        endProcessConnection()
        val triedBefore = relay.forwardedConnections.get() + relay.refusedConnections.get()
        val tried = { relay.forwardedConnections.get() + relay.refusedConnections.get() - triedBefore }
        launch().use { scenario ->
            // The remote rests on the Library tab until the home's rows arrive, and the first card the
            // device has seen takes it when they do (TvLibrary's landing rule).
            awaitNode("the library for the saved account at relaunch, the remote on the Library tab or its first card") {
                exists("library.surface") && (focused("library.open") || focused("library.home.0.item.0"))
            }
            check(!exists("tv.connect.submit")) { "A saved account must not be asked for again" }
            check(!keyboardShown(scenario)) { "No keyboard at relaunch" }
            awaitNode("the library saying the account is saved and not connected") {
                exists("library.saved") && exists("library.reconnect")
            }
            check(label("library.saved").contains(relay.url.removePrefix("http://").substringBefore(':'))) {
                "The saved line names the saved account's server: ${label("library.saved")}"
            }
            awaitNode("the albums this device has seen, painted with nothing sent") { exists("library.home.0.item.0") }
            SystemClock.sleep(SAVED_SETTLE_MILLIS)
            val triedBeforeReconnect = tried()
            check(triedBeforeReconnect == 0) { "The app contacted the server $triedBeforeReconnect times before Reconnect" }
            observed += "saved-disconnected(relaunch, tried=$triedBeforeReconnect)"

            // Reconnect lies between the bar and the rows: DOWN from the Library tab, UP from the first card.
            val landing = if (focused("library.open")) "library-tab" else "first-card"
            remote(if (landing == "library-tab") KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            awaitNode("the remote reaching Reconnect from the $landing") { focused("library.reconnect") }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitNode("Reconnect reaching the server, in place", 60_000) {
                tried() > 0 && !exists("library.saved") && !exists("library.reconnect") && exists("library.surface")
            }
            check(!exists("tv.connect.submit")) { "Reconnect connects in place, without the form" }
            awaitNode("the library connected, not offline", 60_000) { !saysOffline() }
            observed += "reconnected(in-place, tried=${tried()}, from=$landing)"
        }
    }

    /**
     * The app opened and in front: its window holds the focus, so the remote's keys reach it and not
     * the launcher (where a phone touch went in core-ci run 37638393461).
     */
    private fun launch(): ActivityScenario<TvSearchActivity> = ActivityScenario.launch<TvSearchActivity>(
        Intent(Intent.ACTION_MAIN).setClassName(context, TvSearchActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ).also { scenario ->
        var activity: TvSearchActivity? = null
        scenario.onActivity { activity = it }
        awaitWindowInFront(instrumentation, { activity })
    }

    private fun requireNothingSaved(store: AndroidAccountCredentialStore, moment: String) {
        check(store.activeAccountId() == null) { "No account may be saved $moment" }
        check(!savedAccountFileNamesAnAccount(context)) { "No account may be on disk $moment" }
    }

    /**
     * Types [value] into the field the remote is on: the keyboard stays down until the centre key
     * selects the field and brings it up, [replacing] characters are taken back first, and Back closes
     * the keyboard, leaving the remote on the field.
     */
    private fun enter(tag: String, value: String, scenario: ActivityScenario<TvSearchActivity>, replacing: Int = 0) {
        check(focused(tag)) { "The remote must be on $tag" }
        check(keyboardStaysDown(scenario)) { "The keyboard came up on $tag before the centre key selected it" }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitNode("the TV's keyboard, for $tag") { keyboardShown(scenario) }
        if (replacing > 0) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MOVE_END)
            repeat(replacing + 2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL) }
            compose.waitForIdle()
            check(fieldText(tag).isEmpty()) { "$tag was not emptied by backspace" }
        }
        instrumentation.sendStringSync(value)
        compose.waitForIdle()
        remote(KeyEvent.KEYCODE_BACK)
        awaitNode("Back closes the keyboard and stays on $tag") { !keyboardShown(scenario) && focused(tag) }
    }

    private fun remote(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun text(id: Int) = context.getString(id)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    /** The tags of what holds focus now, for a failure to say where the remote is. */
    private fun focusedTags(): List<String> = runCatching {
        compose.onAllNodes(SemanticsMatcher("focused") { it.config.getOrElse(SemanticsProperties.Focused) { false } }, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "(untagged)" } }
    }.getOrDefault(emptyList())

    private fun status(): String? = runCatching { label("tv.connect.status") }.getOrNull()

    private fun label(tag: String): String = compose.onNode(hasTestTag(tag)).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }

    private fun fieldText(tag: String): String = compose.onNode(hasTestTag(tag)).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun checked(tag: String) = compose.onNode(hasTestTag(tag)).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On

    /** Closes the process's connection to the account and waits until it is closed: what a new process starts without. */
    private fun endProcessConnection() {
        val closed = java.util.concurrent.CountDownLatch(1)
        com.legitimateapps.dulcet.core.AndroidLibraryReader.closeCurrent { closed.countDown() }
        check(closed.await(60, java.util.concurrent.TimeUnit.SECONDS)) { "The library reader did not close" }
    }

    private fun saysOffline() = compose.onAllNodes(hasText(text(SharedR.string.library_reason_offline), substring = true))
        .fetchSemanticsNodes().isNotEmpty()

    private fun onScreen(tag: String, scenario: ActivityScenario<TvSearchActivity>): Boolean {
        val node = compose.onNode(hasTestTag(tag)).fetchSemanticsNode()
        var height = 0
        scenario.onActivity { height = it.windowManager.currentWindowMetrics.bounds.height() }
        return node.positionOnScreen.y >= 0 && node.positionOnScreen.y + node.size.height <= height
    }

    /**
     * The keyboard is down and stays down for [KEYBOARD_SETTLE_MILLIS]: the keyboard comes up a moment
     * after focus lands, so one look straight after a key press cannot tell that it will not.
     */
    private fun keyboardStaysDown(scenario: ActivityScenario<TvSearchActivity>): Boolean {
        val until = SystemClock.uptimeMillis() + KEYBOARD_SETTLE_MILLIS
        while (SystemClock.uptimeMillis() < until) {
            compose.waitForIdle()
            if (keyboardShown(scenario)) return false
            SystemClock.sleep(100)
        }
        return !keyboardShown(scenario)
    }

    private fun keyboardShown(scenario: ActivityScenario<TvSearchActivity>): Boolean {
        var shown = false
        scenario.onActivity { activity ->
            shown = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    /** Waits for [condition]; a timeout also says what held the focus and which tags were there. */
    private fun awaitNode(what: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(what, timeoutMillis) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$what: never, within $timeoutMillis ms; focus on ${focusedTags()}; " +
                "library.surface=${exists("library.surface")} library.saved=${exists("library.saved")} " +
                "library.reconnect=${exists("library.reconnect")} tv.connect.submit=${exists("tv.connect.submit")}", timeout)
        }
    }

    private companion object {
        /** How long a launch into the saved account is watched for any request before Reconnect. */
        const val SAVED_SETTLE_MILLIS = 5_000L

        const val WRONG_PASSWORD = "not-the-password"

        /** How long a field must keep the keyboard down after the remote lands on it. */
        const val KEYBOARD_SETTLE_MILLIS = 1_500L

        /** Long enough for a held request, released, to be answered by the local server. */
        const val SETTLE_MILLIS = 3_000L
    }
}
