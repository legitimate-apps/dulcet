package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
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
        compose.onNodeWithText("First edition").assertExists()
        compose.onNodeWithTag("discover.album.edition.1").performClick()
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
}
