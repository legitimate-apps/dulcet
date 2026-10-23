package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
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
class PhoneWishlistTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val source=FakeDiscoverWishlistSource()
    private val session=DiscoverWishlistSession(source)
    @org.junit.After fun close()=session.close()
    private fun row(state:String="wanted",note:String?=null,key:String=state)=WishRow(key,
        DiscoverWish(key,"u",DiscoverTarget(DiscoverKind.Song,key),"song",WishSnapshot("Wish $key","Artist",null,null),WishSource("search",null),1000,2000,state,note,3000,null,null,source.item("song").availability,"1"),null,false,null)
    private fun host(open:(DiscoverLibraryRef)->Unit={}) { source.publish(source.state); compose.setContent {MaterialTheme {PhoneWishlist(session,open,{})}} }

    @Test fun libraryWishlistIsCapabilityGatedAndDistinctFromMyLibrary() {
        var opened=0
        source.state=source.state.copy(access=source.state.access.copy(wishlistAvailable=false,canReadWishlist=false))
        source.publish(source.state)
        compose.setContent {MaterialTheme {PhoneWishlistEntry(session){opened++}}}
        compose.onNodeWithTag("wishlist.open").assertDoesNotExist()
        compose.runOnIdle {source.publish(source.state.copy(access=source.state.access.copy(wishlistAvailable=true,canReadWishlist=true)))}
        compose.onNodeWithTag("wishlist.open").performClick()
        assertEquals(1,opened);assertEquals(0,source.added)
    }
    @Test fun waitingRowHasRemoveInOverflowAndSettledHistoryInFilterMenu() {
        val pending=WishMutation.Add("add",1,DiscoverTarget(DiscoverKind.Song,"mbid"),WishSnapshot("Pending",null,null,null),WishSource("search",null))
        source.state=source.state.copy(wishes=listOf(WishRow("stable",null,pending,true,null)))
        host()
        compose.onNodeWithText("Waiting to sync").assertExists()
        compose.onNodeWithTag("wishlist.remove.0").assertDoesNotExist()
        compose.onNodeWithTag("wishlist.history").assertDoesNotExist()
        compose.onNodeWithTag("wishlist.row.menu.0").performClick()
        compose.onNodeWithTag("wishlist.remove.0").performClick()
        assertEquals("stable",source.removed)
        compose.onNodeWithTag("wishlist.filters").performClick()
        compose.onNodeWithTag("wishlist.history").performClick()
        compose.waitForIdle()
        assertTrue(source.filter.includeFulfilledHistory)
    }
    @Test fun fulfilledArrivalRoutesLocalItemWithoutClientAdd() {
        val expected=DiscoverLibraryRef("provider:test","song","local-song")
        source.state=source.state.copy(wishes=listOf(row("fulfilled").let {it.copy(wish=it.wish!!.copy(libraryItem=expected,fulfilledAtMillis=4000))}))
        var opened:DiscoverLibraryRef?=null;host{opened=it}
        compose.onNodeWithTag("wishlist.row.state.0").assertTextContains("Arrived")
        compose.onNodeWithTag("wishlist.arrival.0").assertDoesNotExist()
        compose.onNodeWithTag("wishlist.row.menu.0").performClick()
        compose.onNodeWithTag("wishlist.arrival.0").performClick()
        assertEquals(expected,opened);assertEquals(0,source.added)
    }
    @Test fun discoverOffStillReadsAndRemovesFromOverflow() {
        source.state=source.state.copy(access=source.state.access.copy(discoverAvailable=false,canSearch=false,canAddWish=false),wishes=listOf(row()))
        host()
        compose.onNodeWithTag("wishlist.row.menu.0").performClick()
        compose.onNodeWithTag("wishlist.remove.0").performClick()
        assertEquals("wanted",source.removed)
        compose.onNodeWithTag("wishlist.refresh").performClick()
        assertFalse(source.filter.includeFulfilledHistory)
    }
    @Test fun futureStateAndPlainNoteRemainVisible() {
        source.state=source.state.copy(wishes=listOf(row("future-state","<b>plain note</b>")))
        host()
        compose.onNodeWithTag("wishlist.row.state.0").assertTextContains("future-state")
        compose.onNodeWithText("<b>plain note</b>",substring=true).assertExists()
        compose.onNodeWithTag("wishlist.row.menu.0").performClick()
        compose.onNodeWithTag("wishlist.remove.0").assertIsDisplayed()
    }
    @Test fun fiveStatesHaveTheApprovedLabels() {
        source.state=source.state.copy(wishes=listOf("wanted","requested","searching","unavailable","fulfilled").map { row(it) })
        host()
        listOf("Wanted","Requested","Searching","Not found","Arrived").forEachIndexed { index, label ->
            compose.onNodeWithTag("wishlist.list").performScrollToNode(hasTestTag("wishlist.row.state.$index"))
            compose.onNodeWithTag("wishlist.row.state.$index").assertTextContains(label)
        }
    }
    @Test fun permissionFailureRollsBackOnlyThatWish() {
        source.state=source.state.copy(wishes=listOf(row(key="one").copy(error=DiscoverFailure("forbidden",null,false)),row(key="two")))
        host()
        compose.onNodeWithText("Not saved",substring=true).assertExists()
        compose.onNodeWithText("Wish two",substring=true).assertExists()
        compose.onNodeWithTag("wishlist.row.menu.1").performClick()
        compose.onNodeWithTag("wishlist.remove.1").assertIsDisplayed()
    }
    @Test fun wishlistReadFailureKeepsSavedRowsAndNamesWishlist() {
        source.state=source.state.copy(wishes=listOf(row(key="saved")),wishlistError=DiscoverFailure("unreachable",null,true))
        host()
        compose.onNodeWithText("Could not refresh Wishlist. Try again.").assertExists()
        compose.onNodeWithText("Wish saved").assertExists()
        compose.onNodeWithText("Not saved").assertDoesNotExist()
    }

}
