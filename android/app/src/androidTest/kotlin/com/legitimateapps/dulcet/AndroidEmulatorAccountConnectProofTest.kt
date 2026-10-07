package com.legitimateapps.dulcet

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * CONF-09b on a phone emulator, through the production app from an install with no saved account
 * (spec §10.2, §13.6): a person's own path to a connected library, with every declared connect state
 * reached by what the app itself does — never an injected result.
 *
 * Every touch is a real touch: a motion event injected at the control's place on the screen, so a
 * keyboard or a dialog lying over a control takes the touch instead of it. Text is typed as key
 * events into the focused field. The server is the disposable one behind a [ServerRelay], which can
 * hold the app's request in flight, make the server unreachable, and read what the server answered.
 *
 * - **idle**: the empty form, Connect, no status.
 * - **domain error, from the server**: a wrong password, refused by the server itself (the relay
 *   reads its Subsonic error 40), shown as an account the server did not accept — not as a server
 *   that could not be reached — and nothing saved.
 * - **domain error, from the network**: the same form with the server unreachable, shown as such.
 * - **in progress**: with the request held, the connecting card and Cancel; Cancel returns the form
 *   to idle, and the request answered after that saves nothing.
 * - **credential-persistence error**: the device refuses the save (its preferences directory made
 *   read-only), so the server accepted the account and the app says it could not be saved, stays on
 *   the form, and nothing is on disk.
 * - **connected**: the library, the server's albums in it, the account on disk.
 * - **saved, disconnected**: a relaunch with the server unreachable opens the library for the saved
 *   account and says it is offline.
 * - **credential-persistence error at launch**: the saved account's Keystore key lost, as a Keystore
 *   reset loses it; the relaunch says the account could not be read and offers to sign it out, and
 *   signing it out leaves an empty install.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorAccountConnectProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun aPersonReachesEveryConnectStateOnTheWayFromAnEmptyInstallToTheLibraryAndBackAfterARelaunch() {
        val probe = DisposableServerProbe.fromInstrumentation()
        awaitQueuedBroadcastsDelivered()
        val store = AndroidAccountCredentialStore(context)
        check(store.activeAccountId() == null) { "The proof starts from an install with no saved account" }
        val ui = context.getSharedPreferences("dulcet.ui", 0)
        val tabBefore = ui.getString("tab", null)
        val observed = mutableListOf<String>()
        var connectOverKeyboard = false
        ServerRelay(probe.baseUrl).use { relay ->
            try {
                launch().use { scenario ->
                    awaitNode("the connect form") { exists("account.submit") }
                    check(label("account.submit") == text(R.string.connect)) { "Idle shows Connect, not ${label("account.submit")}" }
                    check(statusCards().isEmpty()) { "Idle shows no status: ${statusCards()}" }
                    observed += "idle"

                    touch("account.server", scenario)
                    awaitNode("the keyboard, for the server address") { keyboardShown(scenario) }
                    replaceText("account.server", relay.url)
                    touch("account.username", scenario)
                    awaitNode("the username field focused") { focused("account.username") }
                    type(DisposableServerProbe.USER)
                    touch("account.password", scenario)
                    awaitNode("the password field focused") { focused("account.password") }
                    type(WRONG_PASSWORD)
                    check(fieldText("account.server") == relay.url) { "The server field holds ${fieldText("account.server")}" }
                    check(fieldText("account.username") == DisposableServerProbe.USER) { "The username field holds what was typed" }
                    connectOverKeyboard = visibleAboveKeyboard("account.submit", scenario)
                    closeKeyboard(scenario)
                    touch("account.allow-local-http", scenario)
                    awaitNode("local HTTP allowed") { toggle("account.allow-local-http") == ToggleableState.On }

                    // The server's own refusal of the password.
                    touch("account.submit", scenario)
                    awaitNode("the server's refusal of the password") { cardSays("account.status.failed", R.string.error_auth_title) }
                    check(relay.serverAnsweredErrorCode(40)) { "The server must itself have refused the password (Subsonic error 40)" }
                    check(!cardSays("account.status.failed", R.string.error_unreachable_title)) {
                        "A wrong password must not read as a server that could not be reached"
                    }
                    requireNothingSaved(store, "after a refused password")
                    observed += "domain-error(auth, server code 40)"

                    // The same form with the server unreachable.
                    relay.makeUnreachable()
                    val refusedBefore = relay.refusedConnections.get()
                    touch("account.submit", scenario)
                    awaitNode("the unreachable server said") { cardSays("account.status.failed", R.string.error_unreachable_title) }
                    check(relay.refusedConnections.get() > refusedBefore) { "The app must have tried the server" }
                    relay.makeReachable()
                    requireNothingSaved(store, "after an unreachable server")
                    observed += "domain-error(unreachable)"

                    touch("account.password", scenario)
                    awaitNode("the keyboard, for the password") { keyboardShown(scenario) && focused("account.password") }
                    clearField("account.password", WRONG_PASSWORD.length)
                    type(DisposableServerProbe.PASSWORD)
                    closeKeyboard(scenario)

                    // In progress, held at the relay, then cancelled by the person.
                    relay.hold()
                    touch("account.submit", scenario)
                    awaitNode("the connecting card and Cancel") {
                        exists("account.status.connecting") && label("account.submit") == text(R.string.cancel)
                    }
                    await("the app's request held in flight") { relay.heldConnections.get() > 0 }
                    check(!enabled("account.password")) { "The form is not editable while connecting" }
                    observed += "in-progress"
                    touch("account.submit", scenario)
                    awaitNode("Cancel returns the form to idle") {
                        statusCards().isEmpty() && label("account.submit") == text(R.string.connect)
                    }
                    relay.release()
                    SystemClock.sleep(SETTLE_MILLIS)
                    check(exists("account.submit") && !exists("library.open")) { "A cancelled connect must stay on the form" }
                    requireNothingSaved(store, "after Cancel, once the held request was answered")
                    observed += "cancelled->idle"

                    // The server accepts the account and the device refuses to keep it.
                    val forwardedBefore = relay.forwardedConnections.get()
                    withPreferencesWritesRefused(context) {
                        touch("account.submit", scenario)
                        awaitNode("the account that could not be saved") { exists("account.status.persistence-failed") }
                    }
                    check(relay.forwardedConnections.get() > forwardedBefore) { "The server must have been asked" }
                    check(exists("account.submit") && !exists("library.open")) { "An account that was not saved is not connected" }
                    requireNothingSaved(store, "after the device refused the save")
                    observed += "credential-persistence-error(save)"

                    // Connected: the library, with the server's albums.
                    touch("account.submit", scenario)
                    awaitNode("the library, once connected", 60_000) { exists("library.open") && !exists("account.submit") }
                    val saved = checkNotNull(store.load()) { "A connected account must be saved" }
                    check(saved.username == DisposableServerProbe.USER && saved.serverUrl.startsWith(relay.url)) { "The saved account is the one typed" }
                    check(savedAccountFileNamesAnAccount(context)) { "The saved account must be on disk" }
                    touch("library.open", scenario)
                    awaitNode("the server's albums in the library", 60_000) { exists("library.home.0.item.0") }
                    observed += "connected"
                }

                // Relaunch, with the server unreachable: the saved account's library, offline.
                relay.makeUnreachable()
                val triedBefore = relay.refusedConnections.get()
                launch().use { scenario ->
                    awaitNode("the library for the saved account at relaunch") { exists("library.open") }
                    check(!exists("account.submit")) { "A saved account must not be asked for again" }
                    awaitNode("the library saying it is offline", 60_000) { saysOffline() }
                    touch("account.open", scenario)
                    awaitNode("the account named") {
                        exists("account.dialog") && compose.onAllNodes(hasText(DisposableServerProbe.USER, substring = true))
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    observed += "saved-disconnected(relaunch, tried=${relay.refusedConnections.get() - triedBefore})"
                }

                // Relaunch after the Keystore lost the saved account's key.
                loseSavedAccountKey(context)
                relay.makeReachable()
                launch().use { scenario ->
                    awaitNode("the unreadable account said at launch") {
                        exists("account.status.persistence-failed") && exists("account.signout-unreadable")
                    }
                    check(!exists("library.open")) { "An unreadable account must not open the library" }
                    observed += "credential-persistence-error(load)"
                    touch("account.signout-unreadable", scenario)
                    awaitNode("the sign-out question") { exists("signout.anyway") }
                    touch("signout.anyway", scenario)
                    awaitNode("the empty form after signing the unreadable account out") {
                        exists("account.submit") && !exists("account.signout-unreadable") && statusCards().isEmpty()
                    }
                    check(store.activeAccountId() == null && !savedAccountFileNamesAnAccount(context)) { "Sign out must remove the account" }
                    observed += "signed-out->idle"
                }
                println("ANDROID EMULATOR ACCOUNT CONNECT OBSERVED surface=phone states=${observed.joinToString(",")} " +
                    "connect-visible-over-keyboard=$connectOverKeyboard")
            } finally {
                forgetSavedAccount(context)
                ui.edit().apply { if (tabBefore == null) remove("tab") else putString("tab", tabBefore) }.commit()
            }
        }
    }

    /**
     * The app opened from the launcher, and in front: its window holds the focus, as a person waits for
     * the app before touching it. A touch sent before then went to the launcher (OBSERVED in core-ci
     * run 37638393461).
     */
    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch<MainActivity>(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(context, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ).also { scenario ->
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        awaitWindowInFront(instrumentation, { activity })
    }

    private fun requireNothingSaved(store: AndroidAccountCredentialStore, moment: String) {
        check(store.activeAccountId() == null) { "No account may be saved $moment" }
        check(!savedAccountFileNamesAnAccount(context)) { "No account may be on disk $moment" }
    }

    /**
     * A finger on the control: a down and an up at its centre on the screen, delivered by the system
     * to whatever window lies there. Refuses a control off the screen or under the keyboard.
     */
    private fun touch(tag: String, scenario: ActivityScenario<MainActivity>) {
        compose.waitForIdle()
        val node = compose.onNode(hasTestTag(tag)).fetchSemanticsNode()
        val x = node.positionOnScreen.x + node.size.width / 2f
        val y = node.positionOnScreen.y + node.size.height / 2f
        val (width, height) = screen(scenario)
        check(x in 0f..width.toFloat() && y in 0f..height.toFloat()) { "$tag is off the screen at ($x, $y)" }
        keyboardTop(scenario)?.let { top -> check(y < top) { "$tag lies under the keyboard (y=$y, keyboard from $top)" } }
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 60).forEach { (action, time) ->
            val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "The touch on $tag was not delivered" }
            event.recycle()
        }
        compose.waitForIdle()
    }

    private fun type(text: String) {
        instrumentation.sendStringSync(text)
        compose.waitForIdle()
    }

    private fun clearField(tag: String, length: Int) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MOVE_END)
        repeat(length + 2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL) }
        compose.waitForIdle()
        check(fieldText(tag).isEmpty()) { "$tag was not emptied by backspace" }
    }

    /** The field holds [text], typed over whatever the channel put there. */
    private fun replaceText(tag: String, text: String) {
        clearField(tag, fieldText(tag).length)
        type(text)
    }

    /** Back closes the keyboard, as a person does before reaching what it covers. */
    private fun closeKeyboard(scenario: ActivityScenario<MainActivity>) {
        if (!keyboardShown(scenario)) return
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitNode("Back closes the keyboard") { !keyboardShown(scenario) }
    }

    private fun visibleAboveKeyboard(tag: String, scenario: ActivityScenario<MainActivity>): Boolean {
        val node = compose.onNode(hasTestTag(tag)).fetchSemanticsNode()
        val bottom = node.positionOnScreen.y + node.size.height
        return bottom <= (keyboardTop(scenario) ?: screen(scenario).second)
    }

    private fun screen(scenario: ActivityScenario<MainActivity>): Pair<Int, Int> {
        var size = 0 to 0
        scenario.onActivity { size = it.windowManager.currentWindowMetrics.bounds.let { b -> b.width() to b.height() } }
        return size
    }

    private fun keyboardShown(scenario: ActivityScenario<MainActivity>) = keyboardTop(scenario) != null

    /** Where the keyboard begins on the screen, or null while none is shown. */
    private fun keyboardTop(scenario: ActivityScenario<MainActivity>): Int? {
        var top: Int? = null
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(decor) ?: return@onActivity
            if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return@onActivity
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            top = origin[1] + decor.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        return top
    }

    private fun text(id: Int) = context.getString(id)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun statusCards(): List<String> = compose.onAllNodes(SemanticsMatcher("a status card") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("account.status.")
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    private fun cardSays(tag: String, title: Int) =
        compose.onAllNodes(hasText(text(title)) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    private fun saysOffline() = compose.onAllNodes(hasText(text(SharedR.string.library_reason_offline), substring = true))
        .fetchSemanticsNodes().isNotEmpty()

    private fun label(tag: String): String = compose.onNode(hasTestTag(tag)).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }

    private fun fieldText(tag: String): String = compose.onNode(hasTestTag(tag)).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun enabled(tag: String) = SemanticsProperties.Disabled !in compose.onNode(hasTestTag(tag)).fetchSemanticsNode().config

    private fun toggle(tag: String) = compose.onNode(hasTestTag(tag)).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ToggleableState)

    private fun focused(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun awaitNode(what: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) =
        compose.waitUntil(what, timeoutMillis) { runCatching(condition).getOrDefault(false) }

    private companion object {
        const val WRONG_PASSWORD = "not-the-password"

        /** Long enough for a held request, released, to be answered by the local server. */
        const val SETTLE_MILLIS = 3_000L
    }
}
