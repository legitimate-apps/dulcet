package com.legitimateapps.dulcet.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
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

    /** The Problem: a connected account opens on Library, not on an empty search field. */
    @Test fun aConnectedAccountOpensOnTheLibraryWithTheRemoteOnTheLibraryTab() {
        await("the library at launch") { exists("library.surface") }
        assertFalse(exists("search.query"), "The app must not open on search")
        assertTrue(selected("library.open"), "The bar's Library tab is lit")
        await("the remote on the bar's Library tab") { focused("library.open") }
        assertFalse(focused("tv.account.signout"), "Sign out is never where focus starts")
        assertTrue(!exists("tv.account.signout"), "Sign out is not on the library or the bar")
    }

    /**
     * Should-fixes 4 and 5: along the bar to Account, which opens on Back and not on Sign out; DOWN
     * reaches Sign out, whose dialog opens on Stay signed in; and Back from Account returns to the
     * library with the remote where it was.
     */
    @Test fun theBarReachesAccountWhichOpensOnBackThenSignOutAndBackReturnsToTheLibrary() {
        await("the remote on the bar's Library tab") { focused("library.open") }
        key(Key.DirectionRight)
        assertFocused("tv.account.open")
        key(Key.DirectionCenter)
        await("the account screen") { exists("account.surface") }
        await("the account screen's focus on Back") { focused("account.back") }
        assertFalse(focused("tv.account.signout"), "The account screen must not open on Sign out")

        key(Key.DirectionDown)
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
        await("the remote back on the Library tab") { focused("library.open") }
    }

    /**
     * Back from Account opened over Search returns to Search; Back from Search goes to the library,
     * the screen the app opens on.
     */
    @Test fun backFromAccountReturnsToSearchAndBackFromSearchToTheLibrary() {
        await("the remote on the bar's Library tab") { focused("library.open") }
        key(Key.DirectionLeft)
        assertFocused("search.open")
        key(Key.DirectionCenter)
        await("search") { exists("search.query") }
        await("the remote on the bar's Search tab") { focused("search.open") }
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        assertFocused("tv.account.open")
        key(Key.DirectionCenter)
        await("the account screen on Back") { exists("account.surface") && focused("account.back") }

        back()
        await("Back from Account returns to search") { exists("search.query") && !exists("account.surface") }
        back()
        await("Back from search goes to the library") { exists("library.surface") && !exists("search.query") }
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
