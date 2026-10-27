package com.legitimateapps.dulcet.tv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.DiscoverBrowseMemory
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h720dp-land-television")
class TvDiscoverTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = FakeDiscoverWishlistSource()
    private val session = DiscoverWishlistSession(source)
    private val account = SearchAccount("provider:test", "http://127.0.0.1:1", "u", "p", true)
    private val opened = mutableListOf<String>()
    private val navigator = TvNavigator(open = { opened += it }, back = {})
    private var surface = ""
    private val age = DiscoverAge(null, 0, 0, false, false)
    @After fun close() { session.close() }
    private fun host(route: String) {
        surface = when {
            route == ROUTE_DISCOVER -> "discover.home"
            route == ROUTE_WISHLIST -> "wishlist.surface"
            route.startsWith("discover.playlist:") -> "discover.playlist.surface"
            route.startsWith("discover.album:") -> "discover.album.surface"
            else -> "discover.shelf.surface"
        }
        compose.setContent { MaterialTheme { TvDiscoverRoute(session, DiscoverBrowseMemory(), route, account, null, navigator) } }
    }
    private fun click(tag: String) {
        compose.onNodeWithTag(surface).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    @Test fun shelfLoadsAnotherPageAndPreservesSourceForWish() {
        source.shelfAnswer = { id, cursor -> DiscoverResult.Value(DiscoverShelfPage(id,
            listOf(source.item("album").copy(id = if (cursor == null) "first" else "second")), emptyList(), if (cursor == null) "next" else null, age)) }
        host("discover.shelf:trendingAlbums")
        click("discover.shelf.more")
        assertEquals(listOf("trendingAlbums" to null, "trendingAlbums" to "next"), source.shelfReads)
        click("discover.wish.second")
        assertEquals(listOf(WishSource("shelf", "trendingAlbums")), source.wishSources)
        click("discover.item.1.open")
        assertEquals("discover.album:album", opened.last())
    }

    @Test fun playlistKeepsDuplicatePositionsAndOmitsWishWithoutTarget() {
        source.playlistAnswer = { id, _ -> DiscoverResult.Value(DiscoverPlaylist(DiscoverPlaylistSummary(id, "List", null, 3),
            listOf(DiscoverPlaylistEntry(1, source.item("song")), DiscoverPlaylistEntry(2, source.item("song")),
                DiscoverPlaylistEntry(3, source.item("song").copy(id = "unknown", target = null, wishable = false))), null, age)) }
        host("discover.playlist:list")
        compose.onNodeWithTag("discover.playlist.position.1").assertExists()
        compose.onNodeWithTag("discover.playlist.position.2").performScrollTo().assertExists()
        compose.onNodeWithTag("discover.playlist.item.3").performScrollTo().assertExists()
        compose.onNodeWithTag("discover.wish.unknown").assertDoesNotExist()
        compose.onNodeWithTag(surface).performScrollToNode(hasTestTag("discover.wish.song"))
        compose.onAllNodesWithTag("discover.wish.song").onFirst().performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onAllNodesWithTag("discover.wish.song").onFirst().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(WishSource("playlist", "list"), source.wishSources.single())
    }

    @Test fun heldArtistRoutesToScopedLibraryButPartialAlbumKeepsMetadata() {
        val ref = DiscoverLibraryRef(account.providerInstanceId, "artist", "opaque:artist")
        assertEquals(ref, tvDiscoverLibraryRoute(source.item("artist").copy(availability =
            DiscoverAvailability("held", "unknown", null, null, null, null, listOf(ref))), account.providerInstanceId))
        val album = source.item("album").copy(availability = DiscoverAvailability("partlyHeld", "known", null, 1, 12, null,
            listOf(ref.copy(type = "album"))))
        assertNull(tvDiscoverLibraryRoute(album, account.providerInstanceId))
        assertNull(tvDiscoverLibraryRoute(source.item("artist").copy(availability =
            DiscoverAvailability("held", "known", null, null, null, null, listOf(ref))), "other-account"))
    }

    @Test fun albumEditionReadIsExplicitAndWishIsAlbumSourced() {
        source.albums[null] = DiscoverAlbum(source.item("album"), listOf(DiscoverEdition("release2", "2025", 12, emptyList())), "release1", source.page(emptyList()))
        source.albums["release2"] = source.albums.getValue(null).copy(selectedReleaseId = "release2", tracks = source.page(listOf(source.item("song"))))
        host("discover.album:album")
        click("discover.edition.release2")
        assertEquals(listOf(null, "release2"), source.selected)
        click("discover.wish.song")
        assertEquals(WishSource("album", "album"), source.wishSources.single())
    }

    @Test fun refreshFailureRetainsShelfRowsAndShowsSourceAge() {
        source.shelfAnswer = { id, _ -> DiscoverResult.Value(DiscoverShelfPage(id, listOf(source.item("song")), emptyList(), null,
            age.copy(stale = true, sourceAgeSeconds = 7200), DiscoverFailure("upstream-unavailable", null, true))) }
        host("discover.shelf:trendingSongs")
        compose.onNodeWithTag("discover.stale").assertTextContains("7200", substring = true)
        compose.onNodeWithTag("discover.item.0").performScrollTo().assertExists()
        source.shelfAnswer = { _, _ -> DiscoverResult.Failed(DiscoverFailure("offline", null, true)) }
        click("discover.retry")
        compose.onNodeWithTag("discover.item.0").performScrollTo().assertExists()
        compose.onNodeWithTag("discover.error").assertTextContains("offline", substring = true)
    }

    @Test fun wishlistHasExplicitRemoveAndFiltersUseAccountSession() {
        val target = checkNotNull(source.item("song").target)
        source.publish(source.state.copy(wishes = listOf(WishRow("stable-key", null,
            WishMutation.Add("operation", 0, target, WishSnapshot("Desired song", "Artist", null, null), WishSource("search", null)), true, null))))
        host(ROUTE_WISHLIST)
        click("wishlist.kind.song")
        assertEquals(WishFilter(kind = "song"), source.filter)
        click("wishlist.history")
        assertTrue(source.filter.includeFulfilledHistory)
        click("wishlist.remove.0")
        assertEquals("stable-key", source.removed)
    }

    @Test fun everywhereSubmitsTheEditedQueryByRemoteWithoutChangingServerSearch() {
        surface = "discover.search"
        source.filter = WishFilter(kind = "artist")
        compose.setContent { MaterialTheme { TvDiscoverSearch(session, account, null, navigator) } }
        click("discover.query")
        compose.onNodeWithTag("discover.query").performTextReplacement("arrival")
        compose.waitForIdle()
        click("discover.submit")
        assertEquals(listOf("arrival"), source.queries)
        assertEquals(WishFilter(), source.filter)
    }

    @Test fun switchingToEverywhereDoesNotRestoreTheScopeOverQueryFocus() {
        val presenter = com.legitimateapps.dulcet.search.SearchPresenter(account,
            com.legitimateapps.dulcet.search.conformance.LibraryDepthSearchFixture())
        try {
            val route = TvRouteFocus()
            compose.setContent { MaterialTheme {
                CompositionLocalProvider(LocalTvRouteFocus provides route, LocalTvDiscover provides session,
                    LocalTvDiscoverNavigator provides navigator) {
                    TvSearchScreen(presenter, account, onActivate = {})
                }
            } }
            compose.onNodeWithTag("discover.scope.everywhere").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("discover.scope.everywhere").performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitForIdle()
            compose.onNodeWithTag("discover.query").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.waitForIdle()
            compose.onNodeWithTag("discover.query").assertIsFocused()
        } finally { presenter.close() }
    }

    @Test fun unsupportedServerShowsNoDiscoverOrWishlist() {
        source.publish(source.state.copy(access = source.state.access.copy(discoverAvailable = false, wishlistAvailable = false)))
        host(ROUTE_DISCOVER)
        compose.onNodeWithTag("discover.home").assertDoesNotExist()
        assertEquals(0, source.homeRefreshes)
        assertTrue(source.shelfReads.isEmpty())
    }

    @Test fun homeRefreshesOnlyOnEntryAndExposesAllSixShelves() {
        source.publish(source.state.copy(home = DiscoverHome(emptyList(), emptyList(), false, true, null)))
        host(ROUTE_DISCOVER)
        click("discover.shelf.publicPlaylists.all")
        assertEquals("discover.playlists", opened.last())
        assertEquals(1, source.homeRefreshes)
    }
}
