package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-port")
class PhoneDiscoverTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val source=FakeDiscoverWishlistSource()
    private val session=DiscoverWishlistSession(source)
    @org.junit.After fun close()=session.close()
    private fun search() { source.publish(source.state); compose.setContent { MaterialTheme { PhoneDiscoverSearch(session,{}, {}, {}) } } }

    @Test fun scopeSwitchKeepsYourServerSearchIntact() {
        compose.setContent { MaterialTheme {
            PhoneSearchScopes(session,server={ androidx.compose.material3.Text("Existing query",androidx.compose.ui.Modifier.testTag("baseline")) },openLibrary={},openArtist={},openAlbum={})
        } }
        compose.onNodeWithTag("baseline").assertIsDisplayed()
        compose.onNodeWithText("Everywhere").performClick()
        compose.onNodeWithTag("discover.search.field").performTextInput("new")
        assertEquals("new",source.queries.last())
        compose.onNodeWithTag("discover.scope.server").performClick()
        compose.onNodeWithTag("baseline").assertIsDisplayed()
        compose.onNodeWithTag("discover.scope.everywhere").performClick()
        compose.onNodeWithTag("discover.search.field").assertTextContains("new")
    }
    @Test fun incrementalGroupsAndLateQueryUseCorePublication() {
        search()
        compose.onNodeWithTag("discover.search.field").performTextInput("new")
        compose.runOnIdle { source.publish(source.state.copy(query="new",groups=listOf(DiscoverGroup("artist",source.page(listOf(source.item("artist"))),false,null),DiscoverGroup("album",null,true,null)))) }
        compose.onNodeWithTag("discover.item.artist.0").assertIsDisplayed()
        compose.onNodeWithTag("discover.group.album").assertExists()
        assertEquals("new",source.queries.last())
    }
    @Test fun busyStaleAndOfflineKeepRowsVisible() {
        source.state=source.state.copy(connection="offline",query="music",groups=listOf(DiscoverGroup("album",source.page(listOf(source.item("album")),stale=true),false,DiscoverFailure("rate-limited",System.currentTimeMillis()+120_000,true))))
        search()
        compose.onNodeWithTag("discover.item.album.0").assertExists()
        compose.onNodeWithTag("discover.notice.busy").assertExists()
        compose.onNodeWithTag("discover.notice.stale").assertExists()
        compose.onNodeWithTag("discover.retry").assertIsNotEnabled()
        compose.onNodeWithTag("discover.age").assertTextContains("7200", substring = true)
    }
    @Test fun editionSelectionShowsOnlySelectedTracks() {
        source.albums[null]=DiscoverAlbum(source.item("album"),listOf(DiscoverEdition("one",null,12,emptyList()),DiscoverEdition("two",null,9,emptyList())),"one",source.page(listOf(source.item("song",title="First edition"))))
        source.albums["two"]=source.albums[null]!!.copy(selectedReleaseId="two",tracks=source.page(listOf(source.item("song",title="Second edition"))))
        compose.setContent { MaterialTheme { PhoneDiscoverAlbum(session,"album",{}, {},{}) } }
        compose.onNodeWithTag("discover.album.list").performScrollToNode(hasText("First edition"))
        compose.onNodeWithText("First edition").assertExists()
        compose.onNodeWithTag("discover.album.edition.1").performClick()
        compose.onNodeWithTag("discover.album.list").performScrollToNode(hasText("Second edition"))
        compose.onNodeWithText("Second edition").assertExists()
        compose.onNodeWithText("First edition").assertDoesNotExist()
        assertEquals("two",source.selected.last())
    }
    @Test fun partialUnknownAndHeldActionsAreDistinct() {
        var opened:DiscoverLibraryRef?=null
        val local=DiscoverLibraryRef("provider:test","album","local-album")
        source.state=source.state.copy(groups=listOf(DiscoverGroup("album",source.page(listOf(
            source.item("album").copy(availability=DiscoverAvailability("partlyHeld","known",null,8,12,null,listOf(local))),
            source.item("album",title="Unknown").copy(availability=DiscoverAvailability("notHeld","unknown",null,null,null,null,emptyList())))),false,null)))
        source.publish(source.state)
        compose.setContent { MaterialTheme { PhoneDiscoverSearch(session,{opened=it},{},{}) } }
        compose.onNodeWithText("8 of 12").assertExists()
        compose.onNodeWithTag("discover.openLibrary.album.0").performClick()
        assertEquals("provider:test",opened?.providerInstanceId)
        assertEquals("local-album",opened?.id)
        compose.onNodeWithText("Not in your library").assertDoesNotExist()
    }
    @Test fun missingCanonicalMbidHasNoWishAction() {
        source.state=source.state.copy(groups=listOf(DiscoverGroup("song",source.page(listOf(source.item("song").copy(target=null,wishable=false))),false,null)))
        search()
        compose.onNodeWithTag("discover.item.song.0").assertExists()
        compose.onNodeWithTag("discover.wish.song.0").assertDoesNotExist()
    }
    @Test fun matchCorrectionMovesIntoTheResultOverflow() {
        val local=DiscoverLibraryRef("provider:test","album","local-album")
        source.state=source.state.copy(groups=listOf(DiscoverGroup("album",source.page(listOf(
            source.item("album",title="Held").copy(availability=DiscoverAvailability("held","known",null,null,null,null,listOf(local))))),false,null)))
        search()
        compose.onNodeWithTag("discover.openLibrary.album.0").assertIsDisplayed()
        compose.onNodeWithTag("discover.reject.album.0.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.item.menu.album.0").performClick()
        compose.onNodeWithTag("discover.reject.album.0.0").assertIsDisplayed()
    }
    @Test fun artistDetailNamesItsLibraryHeaderAndPrimaryWish() {
        compose.setContent { MaterialTheme { PhoneDiscoverArtist(session,"artist",{},{},{}) } }
        compose.onNodeWithTag("discover.artist.header").assertIsDisplayed()
        compose.onNodeWithTag("discover.wish.artist.0").assertTextContains("Add to Wishlist")
    }
    @Test fun albumDetailNamesItsLibraryHeaderAndPrimaryWish() {
        compose.setContent { MaterialTheme { PhoneDiscoverAlbum(session,"album",{},{},{}) } }
        compose.onNodeWithTag("discover.album.header").assertIsDisplayed()
        compose.onNodeWithTag("discover.wish.album.0").assertTextContains("Add to Wishlist")
    }
    @Test fun availabilityHasAStableDiscoverTreatment() {
        source.state=source.state.copy(groups=listOf(DiscoverGroup("song",source.page(listOf(source.item("song"))),false,null)))
        search()
        compose.onNodeWithTag("discover.availability.song.0").assertTextContains("Not in your library")
    }
    @Test
    @Config(sdk=[35],qualifiers="w411dp-h891dp-port",fontScale=2f)
    fun largestTextCardArtworkOpensTheAlbum() {
        var opened:String?=null
        compose.setContent { MaterialTheme { PhoneDiscoverArtist(session,"artist",{}, { opened=it }, {}) } }
        compose.onNodeWithTag("discover.artist.list").performScrollToNode(hasTestTag("discover.item.album.0"))
        compose.onNodeWithTag("discover.item.album.0").performTouchInput { click(Offset(8f,8f)) }
        compose.waitForIdle()
        assertEquals("album",opened)
    }
}
