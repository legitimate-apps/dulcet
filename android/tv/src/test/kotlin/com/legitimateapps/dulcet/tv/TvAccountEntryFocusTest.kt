package com.legitimateapps.dulcet.tv

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.AccountCredentialStore
import com.legitimateapps.dulcet.AccountDataGateway
import com.legitimateapps.dulcet.AccountRemovalJournal
import com.legitimateapps.dulcet.AccountSignOut
import com.legitimateapps.dulcet.PendingChanges
import com.legitimateapps.dulcet.PlaybackRelease
import com.legitimateapps.dulcet.SignOutState
import com.legitimateapps.dulcet.StoredAccount
import com.legitimateapps.dulcet.core.AndroidLibrarySearchPublication
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRow
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.SearchSource
import com.legitimateapps.dulcet.search.SearchSourceHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A remote reaches the TV's Sign out (spec §14.7) with nothing but D-pad keys, on the real search
 * screen inside the real host: from the launch state, where focus waits above the empty query field, and
 * from a list of results. No semantic click or focus request is used, since a remote has neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvAccountEntryFocusTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val application = RuntimeEnvironment.getApplication()
    private val store = MemoryStore()
    private val journal = AccountRemovalJournal(application.getSharedPreferences("tv-entry-focus-test", 0))
    private val signOut = AccountSignOut(scope, store, NothingOwed(), PlaybackRelease { true }, journal) {}
    private val account = SearchAccount("provider::opaque", "https://music.example.invalid", "listener", "tv-password", false)

    @After fun clean() {
        scope.cancel()
        application.getSharedPreferences("tv-entry-focus-test", 0).edit().clear().commit()
    }

    @Test fun fromTheLaunchStateUpReachesSignOutAndCenterAsksFirst() {
        host()
        // At launch focus waits on the row above the screen, here Sign out, and never in the field,
        // whose focus would bring up the on-screen keyboard.
        assertTrue(focused("tv.account.signout"), "At launch focus is on the row above the search screen")
        assertTrue(!focused("search.query"), "At launch the query field must not hold focus")

        // Down into the screen, then up again: the entry is not a dead end.
        key("tv.account.signout", Key.DirectionDown)
        assertTrue(focused("search.query"), "DOWN from Sign out must return to the query field")
        key("search.query", Key.DirectionUp)
        assertTrue(focused("tv.account.signout"))

        key("tv.account.signout", Key.DirectionCenter)
        compose.waitUntil(5_000) { signOut.state.value !is SignOutState.Checking }
        assertIs<SignOutState.Confirm>(signOut.state.value, "CENTER on Sign out asks first")
    }

    /**
     * More results than the screen has room for, walked to the end and back: every result is reached
     * by DOWN, including those composed only once focus moves toward them, and UP from the first
     * result and then the query field reaches Sign out, which covers no result it passed.
     */
    @Test fun fromAListOfResultsUpReachesSignOutWhichCoversNoResult() {
        host()
        key("tv.account.signout", Key.DirectionDown)
        key("search.query", Key.DirectionCenter)
        compose.onNodeWithTag("search.query").performTextInput("echo")
        compose.waitForIdle()
        key("search.query", Key.DirectionDown)
        val cards = mutableListOf<Rect>()
        for (index in 0 until RESULTS) {
            assertTrue(focused("search.result.$index"), "DOWN must reach result $index of $RESULTS")
            cards += bounds("search.result.$index")
            if (index < RESULTS - 1) key("search.result.$index", Key.DirectionDown)
        }
        for (index in RESULTS - 1 downTo 0) {
            assertTrue(focused("search.result.$index"), "UP must reach result $index")
            cards += bounds("search.result.$index")
            key("search.result.$index", Key.DirectionUp)
        }
        assertTrue(focused("search.query"), "UP from the first result returns to the query field")
        key("search.query", Key.DirectionUp)
        assertTrue(focused("tv.account.signout"), "UP from the results must reach Sign out")

        val entry = bounds("tv.account.signout")
        for (card in cards) assertTrue(entry.bottom <= card.top, "Sign out $entry overlaps a result at $card")
        assertTrue(entry.bottom <= bounds("search.query").top, "Sign out overlaps the query field")
    }

    private fun host() {
        store.save("Fixture", "https://music.example.invalid", "listener", "tv-password", false)
        compose.setContent {
            MaterialTheme {
                TvAccountHost(signOut, "id") {
                    TvSearchScreen(SearchPresenter(account, Results())) {}
                }
            }
        }
        compose.waitForIdle()
    }

    private fun key(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focused(tag: String): Boolean = runCatching {
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
    }.getOrDefault(false)

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private class MemoryStore : AccountCredentialStore {
        var saved: StoredAccount? = null
        override fun load(): StoredAccount? = saved
        override fun save(serverName: String, serverUrl: String, username: String, password: String, allowLocalHttp: Boolean) =
            StoredAccount("tv-account", serverName, serverUrl, username, password, allowLocalHttp).also { saved = it }
        override fun delete() { saved = null }
    }

    private class NothingOwed : AccountDataGateway {
        override suspend fun pendingChanges(account: StoredAccount) = PendingChanges(emptySet(), 0)
        override suspend fun send(account: StoredAccount, changes: Boolean) = PendingChanges(emptySet(), 0)
        override suspend fun removeAccountData(serverId: String) {}
        override suspend fun sweep(activeAccountId: () -> String?): Set<String> = emptySet()
    }

    /** [RESULTS] results for any non-blank query, none for a blank one. */
    private class Results : SearchSource {
        private val rows = (0 until RESULTS).map { index ->
            SearchResultItem(
                id = ProviderItemId("provider::opaque", "item-$index"), type = SearchResultType.Album, title = "Echo $index",
                credits = emptyList(), albumTitle = null, year = null, duration = null, discNumber = null,
                trackNumber = null, sourceContainer = null, mediaSourceId = null, artworkKey = null,
            )
        }

        override fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle = object : SearchSourceHandle {
            private var sequence = 0
            override fun updateQuery(text: String) {
                listener(AndroidLibrarySearchPublication(text, ++sequence, AndroidLibrarySearchScope.ServerAndDevice,
                    if (text.isBlank()) emptyList()
                    else rows.map { AndroidLibrarySearchRow(it, AndroidLibrarySearchRowSource.Server, null, null, null) }))
            }
            override fun refresh() = Unit
            override fun close() = Unit
        }
    }

    private companion object {
        /** More than a 960 x 540 dp screen shows at once, so the walk must compose results as it goes. */
        const val RESULTS = 8
    }
}
