package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import kotlin.test.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-port")
class PhoneDiscoverShelvesTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val source=FakeDiscoverWishlistSource()
    private val session=DiscoverWishlistSession(source)
    private val age=DiscoverAge(null,7200,0,true,false)
    private val artist=source.item("artist").copy(id="opaque:Artist/A")
    @After fun close()=session.close()
    private fun page(id:String="trendingArtists",items:List<DiscoverItem> = listOf(artist),next:String?=null)=DiscoverShelfPage(id,items,emptyList(),next,age)
    private fun home(shelves:List<DiscoverShelfState>) {source.publish(source.state.copy(home=DiscoverHome(shelves,emptyList(),false,true,null)))}
    private fun showHome(openArtist:(String)->Unit={},openAlbum:(String)->Unit={},openLibrary:(DiscoverLibraryRef)->Unit={},openShelf:(String)->Unit={}) {
        compose.setContent { MaterialTheme { PhoneDiscoverHome(session,openShelf,{}, {}, {},openLibrary,openArtist,openAlbum) } }
    }
    private fun scroll(tag:String) { compose.onNodeWithTag("discover.home.list").performScrollToNode(hasTestTag(tag)) }
    @Test fun independentMixedStatesKeepRowsAndExposeHonestFailureEmptyAndAge() {
        home(listOf(DiscoverShelfState("trendingArtists",page(),false,null),
            DiscoverShelfState("trendingAlbums",null,true,null),DiscoverShelfState("trendingSongs",null,false,DiscoverFailure("upstream-unavailable",null,true)),
            DiscoverShelfState("similarArtists",page("similarArtists",emptyList()),false,null)))
        showHome()
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${artist.id}").assertExists()
        compose.onNodeWithTag("discover.shelf.trendingArtists.age").assertExists()
        scroll("discover.shelf.trendingAlbums.loading");compose.onNodeWithTag("discover.shelf.trendingAlbums.loading").assertExists()
        scroll("discover.shelf.trendingSongs.error");compose.onNodeWithTag("discover.shelf.trendingSongs.error").assertExists()
        compose.onNodeWithTag("discover.shelf.trendingSongs.empty").assertDoesNotExist()
        scroll("discover.shelf.similarArtists.empty");compose.onNodeWithText("Play or favourite music on your server to find artists like yours.").assertExists()
        assertEquals(1,source.homeRefreshes)
    }
    @Test fun homeSeeAllOnlyWithCursorAndNamedWishUsesShelfSource() {
        source.filter=WishFilter("album")
        home(listOf(DiscoverShelfState("trendingArtists",page(next="opaque:cursor"),false,null)))
        var selected:String?=null;showHome(openShelf={selected=it})
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${artist.id}.wish").assertContentDescriptionEquals("Wish for ${artist.title}").performClick()
        assertEquals(WishSource("shelf","trendingArtists"),source.wishSources.single());assertEquals(WishFilter(),source.filter)
        compose.onNodeWithTag("discover.shelf.trendingArtists.all").performClick();assertEquals("trendingArtists",selected)
        scroll("discover.shelf.newReleases");compose.onNodeWithTag("discover.shelf.newReleases.all").assertDoesNotExist()
    }
    @Test fun heldScopedItemOpensLibraryAndPartialAlbumOpensDiscover() {
        val ref=DiscoverLibraryRef("provider:test","artist","local:artist")
        val held=artist.copy(availability=artist.availability.copy(status="held",libraryItems=listOf(ref)))
        val album=source.item("album").copy(availability=artist.availability.copy(status="partlyHeld",libraryItems=listOf(ref.copy(type="album"))))
        home(listOf(DiscoverShelfState("trendingArtists",page(items=listOf(held)),false,null),DiscoverShelfState("trendingAlbums",page("trendingAlbums",listOf(album)),false,null)))
        var local:DiscoverLibraryRef?=null;var external:String?=null
        showHome(openLibrary={local=it},openAlbum={external=it})
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${held.id}").performClick();assertEquals(ref,local)
        scroll("discover.shelf.trendingAlbums.item.${album.id}");compose.onNodeWithTag("discover.shelf.trendingAlbums.item.${album.id}").performClick();assertEquals(album.target!!.mbid,external)
    }
    @Test fun unknownKindRemainsVisibleWithoutCanonicalActions() {
        val unknown=artist.copy(kind="futureKind",id="future:opaque")
        home(listOf(DiscoverShelfState("trendingArtists",page(items=listOf(unknown)),false,null)));showHome()
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${unknown.id}").assertExists().assertHasNoClickAction()
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${unknown.id}.wish").assertDoesNotExist()
    }
    @Test fun absentCapabilityHidesHomeAndMakesNoRead() {
        source.publish(source.state.copy(access=source.state.access.copy(discoverAvailable=false)))
        showHome();compose.onNodeWithTag("discover.home").assertDoesNotExist();assertEquals(0,source.homeRefreshes)
    }
    @Test fun shelfPaginationUsesCursorAndErrorRetainsFirstPage() {
        source.shelfAnswer={id,cursor -> if(cursor==null)DiscoverResult.Value(page(id,next="Cursor:/Case")) else DiscoverResult.Failed(DiscoverFailure("upstream-unavailable",null,true)) }
        compose.setContent { MaterialTheme { PhoneDiscoverShelf(session,"trendingArtists",{}, {}, {}, {}) } }
        compose.onNodeWithTag("discover.shelfList.trendingArtists").performScrollToNode(hasTestTag("discover.shelfList.trendingArtists.more"))
        compose.onNodeWithTag("discover.shelfList.trendingArtists.more").performClick()
        assertEquals(listOf("trendingArtists" to null,"trendingArtists" to "Cursor:/Case"),source.shelfReads)
        compose.onNodeWithTag("discover.shelfList.trendingArtists").performScrollToNode(hasTestTag("discover.shelf.trendingArtists.error"))
        compose.onNodeWithTag("discover.shelf.trendingArtists.error").assertExists()
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${artist.id}").assertExists()
    }
    @Test fun playlistDuplicatesMissingTargetAndSourceAwareWish() {
        val song=source.item("song")
        source.playlistAnswer={id,_->DiscoverResult.Value(DiscoverPlaylist(DiscoverPlaylistSummary(id,"Public mix",null,null),listOf(DiscoverPlaylistEntry(1,song),DiscoverPlaylistEntry(2,song),DiscoverPlaylistEntry(3,song.copy(target=null,wishable=false))),null,age))}
        compose.setContent { MaterialTheme { PhoneDiscoverPlaylist(session,"opaque:playlist",{}, {}, {}, {}) } }
        val list=compose.onNodeWithTag("discover.playlist.opaque:playlist")
        list.performScrollToNode(hasTestTag("discover.playlist.opaque:playlist.entry.1.wish"))
        compose.onNodeWithTag("discover.playlist.opaque:playlist.entry.1.wish").performClick();assertEquals(WishSource("playlist","opaque:playlist"),source.wishSources.single())
        list.performScrollToNode(hasTestTag("discover.playlist.opaque:playlist.entry.2"));compose.onNodeWithTag("discover.playlist.opaque:playlist.entry.2").assertExists()
        list.performScrollToNode(hasTestTag("discover.playlist.opaque:playlist.entry.3"));compose.onNodeWithTag("discover.playlist.opaque:playlist.entry.3.wish").assertDoesNotExist()
    }
    @Test fun playlistsSubmitExplicitlyAndRestoreQueryAfterRecreation() {
        source.playlistsAnswer={_,_->DiscoverResult.Value(DiscoverPlaylistList(emptyList(),null,age))}
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme { PhoneDiscoverPlaylists(session,{}, {}) } }
        compose.onNodeWithTag("discover.playlists.query").performTextInput("jazz")
        assertEquals(listOf<Pair<String,String?>>("" to null),source.playlistQueries)
        compose.onNodeWithTag("discover.playlists.submit").performClick();compose.waitForIdle();assertEquals<Pair<String,String?>>("jazz" to null,source.playlistQueries.last())
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discover.playlists.query").assertTextContains("jazz");assertEquals<Pair<String,String?>>("jazz" to null,source.playlistQueries.last())
    }
    @Test fun wishlistAbsenceHidesRecentWishesAndWishActionsIndependently() {
        home(listOf(DiscoverShelfState("trendingArtists",page(),false,null)))
        source.publish(source.state.copy(access=source.state.access.copy(wishlistAvailable=false,canAddWish=false,canRemoveWish=false)))
        showHome();compose.onNodeWithTag("discover.shelf.trendingArtists.item.${artist.id}.wish").assertDoesNotExist()
        scroll("discover.shelf.publicPlaylists");compose.onNodeWithTag("discover.recentWishes").assertDoesNotExist()
    }
    @Test @Config(qualifiers="w360dp-h800dp-port") fun largestTextKeepsNamedActionsOnANarrowPhone() {
        val long=artist.copy(title="A long artist name that must remain readable")
        home(listOf(DiscoverShelfState("trendingArtists",page(items=listOf(long)),false,null)))
        compose.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)) {
                MaterialTheme { PhoneDiscoverHome(session,{}, {}, {}, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${long.id}.wish").assertContentDescriptionEquals("Wish for ${long.title}").assertIsDisplayed()
        compose.onNodeWithText(long.title).assertIsDisplayed()
    }
    @Test fun recentWishPreservesStableKeyAndPendingRemovalUsesExistingOutbox() {
        val row=WishRow("stable:before/ack",null,WishMutation.Add("op",123,artist.target!!,
            WishSnapshot(artist.title,artist.artist,null,null),WishSource("shelf","trendingArtists")),true,null)
        source.publish(source.state.copy(home=DiscoverHome(listOf(DiscoverShelfState("trendingArtists",page(),false,null)),listOf(row),false,true,null)))
        var key:String?=null
        compose.setContent {MaterialTheme { PhoneDiscoverHome(session,{}, {}, {}, {}, {}, {}, {},openRecentWish={key=it}) } }
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${artist.id}.wish").assertContentDescriptionEquals("Remove ${artist.title} from Wishlist").performClick()
        assertEquals(row.key,source.removed)
        scroll("discover.recentWishes.item.${row.key}");compose.onNodeWithTag("discover.recentWishes.item.${row.key}").performClick();assertEquals(row.key,key)
        compose.onNodeWithTag("discover.recentWishes.item.${row.key}").assertTextContains("Waiting to sync")
    }
    @Test fun cachedFailureKeepsAgeRowsAndRetryDistinctFromEmpty() {
        val failure=DiscoverFailure("offline",null,true)
        home(listOf(DiscoverShelfState("trendingArtists",page().copy(age=age.copy(fromDeviceCache=true),refreshError=failure),false,failure)))
        source.publish(source.state.copy(connection="offline"));showHome()
        compose.onNodeWithText("Cached answer").assertExists()
        compose.onNodeWithTag("discover.shelf.trendingArtists.item.${artist.id}").assertExists()
        compose.onNodeWithTag("discover.shelf.trendingArtists.retry").performClick();assertEquals(2,source.homeRefreshes)
        compose.onNodeWithTag("discover.shelf.trendingArtists.empty").assertDoesNotExist()
    }
}
