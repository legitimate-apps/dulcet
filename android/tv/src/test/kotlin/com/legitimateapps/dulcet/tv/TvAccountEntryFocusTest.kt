package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
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
 * A remote reaches the TV's Sign out (spec §14.7) with nothing but D-pad keys, in the shell's real
 * pieces: the top bar over the search screen, and the account screen it opens. Sign out is never on
 * the bar itself. No semantic click or focus request is used, since a remote has neither.
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

    @Test fun fromTheLaunchStateTheRemoteReachesTheAccountEntryOnTheBar() {
        var opened = false
        host(onAccount = { opened = true })
        // At launch focus waits on the navigation bar, here its Search tab, and never in the field,
        // whose focus would bring up the on-screen keyboard.
        assertTrue(focused("search.open"), "At launch focus is on the navigation bar")
        assertTrue(!focused("search.query"), "At launch the query field must not hold focus")

        // Down into the screen, then up again: the bar is not a dead end.
        key("search.open", Key.DirectionDown)
        assertTrue(focused("search.query"), "DOWN from the bar must return to the query field")
        key("search.query", Key.DirectionUp)
        assertTrue(focused("search.open"))

        // Along the bar to the account entry; Sign out is not on the bar, the account screen holds it.
        key("search.open", Key.DirectionRight)
        assertTrue(focused("library.open"), "RIGHT from Search reaches Library")
        key("library.open", Key.DirectionRight)
        assertTrue(focused("tv.account.open"), "RIGHT from Library reaches the account entry")
        assertTrue(missing("tv.account.signout"), "Sign out is not a control of the bar")
        key("tv.account.open", Key.DirectionCenter)
        assertTrue(opened, "The account entry opens the account screen")
    }

    /** The account screen opens on Back, never on Sign out; DOWN reaches Sign out, which asks first. */
    @Test fun theAccountScreenOpensOnBackAndSignOutOneStepDownAsksFirst() {
        store.save("Fixture", "https://music.example.invalid", "listener", "tv-password", false)
        compose.setContent {
            MaterialTheme {
                TvAccountHost(signOut, "id") {
                    CompositionLocalProvider(LocalTvRouteFocus provides remember { TvRouteFocus() }) {
                        TvAccountScreen(account, TvNavigator({}, {}))
                    }
                }
            }
        }
        compose.waitForIdle()
        awaitFocused("account.back", "the account screen opens on Back")
        assertTrue(!focused("tv.account.signout"), "The account screen must not open on Sign out")
        key("account.back", Key.DirectionDown)
        assertTrue(focused("tv.account.signout"), "DOWN from Back reaches Sign out")
        key("tv.account.signout", Key.DirectionCenter)
        compose.waitUntil(5_000) { signOut.state.value !is SignOutState.Checking }
        assertIs<SignOutState.Confirm>(signOut.state.value, "CENTER on Sign out asks first")
    }

    /**
     * More results than the screen has room for, walked to the end and back: every result is reached
     * by DOWN, including those composed only once focus moves toward them, and UP from the first
     * result and then the query field reaches the bar, which covers no result it passed.
     */
    @Test fun fromAListOfResultsUpReachesTheBarWhichCoversNoResult() {
        host()
        key("search.open", Key.DirectionDown)
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
        assertTrue(focused("search.open"), "UP from the results must reach the navigation bar")

        val bar = bounds("search.open")
        for (card in cards) assertTrue(bar.bottom <= card.top, "The bar $bar overlaps a result at $card")
        assertTrue(bar.bottom <= bounds("search.query").top, "The bar overlaps the query field")
    }

    private fun host(onAccount: () -> Unit = {}) {
        store.save("Fixture", "https://music.example.invalid", "listener", "tv-password", false)
        compose.setContent {
            MaterialTheme {
                val navigation = remember { TvNavigationFocus() }
                TvAccountHost(signOut, "id") {
                    Column(Modifier.fillMaxSize()) {
                        TvTopBar(top = ROUTE_SEARCH, root = ROUTE_SEARCH, playing = false, focus = navigation,
                            onSearch = {}, onLibrary = {}, onNowPlaying = {}, onAccount = onAccount)
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            CompositionLocalProvider(LocalTvNavFocus provides navigation.current) {
                                TvSearchScreen(SearchPresenter(account, Results())) {}
                            }
                        }
                    }
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

    private fun missing(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun awaitFocused(tag: String, where: String) {
        val ok = runCatching {
            compose.waitUntil(5_000) { focused(tag) }
        }.isSuccess
        assertTrue(ok, "Focus must reach $tag in $where")
    }

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
