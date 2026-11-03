package com.legitimateapps.dulcet.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Historical test identifiers remain stable for parity evidence; assertions use automatic reconnect. */
/**
 * The connected TV app as launched: the production activity, its top bar, library, account screen
 * and sign-out, with a saved account in the production credential store (host cipher only). The
 * library's server does not answer, so the home has no cards — the case where the remote's resting
 * place is the bar's. Every move is a remote key; nothing is clicked or focused semantically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = AndroidTvSearchTestApplication::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w960dp-h540dp-land-television")
class TvHomeLandingTest {
    private val saved = object : ExternalResource() {
        override fun before() {
            AndroidAccountCredentialStore(RuntimeEnvironment.getApplication())
                .save("Fixture", "https://music.example.invalid", "listener", "tv-password", false)
        }
    }
    private val compose = createAndroidComposeRule<TvSearchActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(saved).around(compose)

    /** A connected account opens on Listen Now with the remote on its tab. */
    @Test fun aConnectedAccountOpensOnTheLibraryWithTheRemoteOnTheLibraryTab() {
        await("Listen Now at launch") { exists("listenNow.surface") }
        assertFalse(exists("search.query"), "The app must not open on search")
        assertTrue(selected("listenNow.open"), "The bar's Listen Now tab is lit")
        await("the remote on Listen Now") { focused("listenNow.open") }
        assertFalse(focused("tv.account.signout"), "Sign out is never where focus starts")
        assertTrue(!exists("tv.account.signout"), "Sign out is not on the library or the bar")
    }

    /** Automatic offline recovery keeps manual Reconnect reachable with the remote. */
    @Test fun aSavedAccountSaysItWaitsForReconnectWhichIsOneDownFromTheBar() {
        landOnLibrary()
        upFromTheLibrarysInitialFocusToItsTab()
        await("the automatic connection notice") { exists("library.saved") }
        assertTrue(exists("library.reconnect"), "Manual reconnect remains available while the server is unreachable")
        key(Key.DirectionDown)
        assertFocused("library.reconnect")
        // From the far end of the library's sections, UP out of the library is Reconnect, not the bar
        // place above Refresh; and from the far end of the bar, DOWN is Reconnect, not Refresh. On the
        // emulator's layout neither lines up with Reconnect (core-ci run 37657259993).
        key(Key.DirectionDown)
        // The sections are the root's left pane: land in it, then walk it down to its last row (the
        // restyle drops the pane's Refresh row, restyle spec "Shell": TV refresh is overflow or on entry).
        key(Key.DirectionLeft)
        val last = "library.view.mostPlayed"
        var downs = 0
        while (!focused(last) && downs < 12) { key(Key.DirectionDown); downs += 1 }
        assertFocused(last)
        // UP out of the pane is from its top row.
        var ups = 0
        while (!focused("library.view.playlists") && ups < 12) { key(Key.DirectionUp); ups += 1 }
        assertFocused("library.view.playlists")
        key(Key.DirectionUp)
        assertFocused("library.reconnect")
        key(Key.DirectionUp)
        assertFocused("library.open")
        key(Key.DirectionRight)
        assertFocused("browse.open")
        key(Key.DirectionRight)
        assertFocused("search.open")
        key(Key.DirectionRight)
        assertFocused("tv.account.open")
        key(Key.DirectionDown)
        assertFocused("library.reconnect")
        key(Key.DirectionCenter)
        await("the library remains usable during manual reconnect") {
            exists("library.saved") && exists("library.reconnect")
        }
        // The pane's last row may be past its viewport: scroll it in, then find it.
        compose.onNodeWithTag("library.nav").performScrollToNode(hasTestTag(last))
        assertTrue(exists(last))
    }

    /**
     * Should-fixes 4 and 5: along the bar to Account, which opens on its first server row and not on
     * Sign out; DOWN traverses saved servers, Add Server and the settings rows before Sign out, whose
     * dialog opens on Stay signed in; and Back from Account returns to the library with the remote
     * where it was.
     */
    @Test fun theBarReachesAccountWhichOpensOnBackThenSignOutAndBackReturnsToTheLibrary() {
        landOnLibrary()
        upFromTheLibrarysInitialFocusToItsTab()
        key(Key.DirectionRight)
        assertFocused("browse.open")
        key(Key.DirectionRight)
        assertFocused("search.open")
        key(Key.DirectionRight)
        assertFocused("tv.account.open")
        key(Key.DirectionCenter)
        await("the account screen") { exists("account.surface") }
        await("the account screen's focus on its saved server") {
            focusedTags().singleOrNull()?.startsWith("tv.account.server.") == true
        }
        assertFalse(focused("tv.account.signout"), "The account screen must not open on Sign out")

        key(Key.DirectionDown)
        assertFocused("tv.account.add-server")
        var downs = 0
        while (!focused("tv.account.signout") && downs < 24) { key(Key.DirectionDown); downs += 1 }
        assertFocused("tv.account.signout")
        key(Key.DirectionCenter)
        await("the sign-out dialog, on Stay signed in") {
            (exists("signout.confirm") || exists("signout.unknown")) && focused("signout.stay")
        }
        key(Key.DirectionCenter)
        await("Stay closes the dialog") { !exists("signout.confirm") && !exists("signout.unknown") }
        assertEquals(true, AndroidAccountCredentialStore(RuntimeEnvironment.getApplication()).activeAccountId() != null,
            "Stay keeps the account")

        back()
        await("Back from Account returns to the library") { exists("library.surface") && !exists("account.surface") }
        // The route returns to what was last focused on it: the first section, where Library opened.
        await("the remote back where it was on Library: its first section") { focused("library.view.playlists") }
    }

    /**
     * Back from Account opened over Search returns to Search; Back from Search goes to Listen Now,
     * the screen the app opens on.
     */
    @Test fun backFromAccountReturnsToSearchAndBackFromSearchToTheLibrary() {
        landOnLibrary()
        upFromTheLibrarysInitialFocusToItsTab()
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        assertFocused("search.open")
        key(Key.DirectionCenter)
        await("search") { exists("search.query") }
        await("the remote on the bar's Search tab") { focused("search.open") }
        key(Key.DirectionRight)
        assertFocused("tv.account.open")
        key(Key.DirectionCenter)
        await("the account screen on its saved server") {
            exists("account.surface") && focusedTags().singleOrNull()?.startsWith("tv.account.server.") == true
        }

        back()
        await("Back from Account returns to search") { exists("search.query") && !exists("account.surface") }
        back()
        await("Back from search goes to Listen Now") { exists("listenNow.surface") && !exists("search.query") }
        assertFalse(compose.activity.isFinishing, "Back from search does not leave the app")
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; focused=${focusedTags()}", timeout)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun landOnLibrary() {
        await("the Listen Now tab at launch") { focused("listenNow.open") }
        key(Key.DirectionRight)
        assertFocused("library.open")
        key(Key.DirectionCenter)
        await("Library") { exists("library.surface") }
    }

    /**
     * Library's one initial focus is its root's first section (the left pane's top row, restyle
     * spec: the Library root is a two-pane page). The saved account's Reconnect sits under the bar,
     * so UP from that row is Reconnect, and UP again is the Library tab.
     */
    private fun upFromTheLibrarysInitialFocusToItsTab() {
        await("the Library's initial focus on its first section") { focused("library.view.playlists") }
        await("the saved account's Reconnect") { exists("library.reconnect") }
        key(Key.DirectionUp)
        assertFocused("library.reconnect")
        key(Key.DirectionUp)
        assertFocused("library.open")
    }

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun selected(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Selected) { false } }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun assertFocused(tag: String) = assertTrue(focused(tag), "$tag holds focus; focused=${focusedTags()}")

    /** To the window in front: the dialog's while one is open, as a remote's key goes. */
    private fun key(key: Key) {
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The remote's Back, through the activity's dispatcher as the platform delivers it. */
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
