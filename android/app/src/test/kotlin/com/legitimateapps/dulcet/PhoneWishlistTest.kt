package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
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
    private var visible by mutableStateOf(true)
    private fun host(open:(DiscoverLibraryRef)->Unit={}) { source.publish(source.state); compose.setContent {MaterialTheme {if (visible) PhoneWishlist(session,open,{})}} }

    @Test fun tappedRemoveSurvivesWishlistRowDisposalBeforeEnqueue() {
        source.holdMutations = true
        source.state = source.state.copy(wishes = listOf(row()))
        host()
        compose.onNodeWithTag("wishlist.row.menu.0").performClick()
        compose.onNodeWithTag("wishlist.remove.0").performClick()
        assertEquals("wanted", source.removed, "positive tap control")
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { source.heldMutations.single()() }
        assertEquals(listOf("remove:wanted"), source.durableMutations)
        assertEquals(0, source.mutationCancellationCount)
    }

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
        compose.onNodeWithText("Not synced yet").assertExists()
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
        compose.onNodeWithTag("wishlist.row.state.0", useUnmergedTree = true).assertTextContains("Arrived", substring = true)
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
        compose.onNodeWithTag("wishlist.row.state.0", useUnmergedTree = true).assertTextContains("future-state", substring = true)
        compose.onNodeWithText("<b>plain note</b>",substring=true).assertExists()
        compose.onNodeWithTag("wishlist.row.menu.0").performClick()
        compose.onNodeWithTag("wishlist.remove.0").assertIsDisplayed()
    }
    @Test fun fiveStatesHaveTheApprovedLabels() {
        source.state=source.state.copy(wishes=listOf("wanted","requested","searching","unavailable","fulfilled").map { row(it) })
        host()
        listOf("Wanted","Requested","Searching","Not found","Arrived").forEachIndexed { index, label ->
            compose.onNodeWithTag("wishlist.list").performScrollToNode(hasTestTag("wishlist.row.$index"))
            compose.onNodeWithTag("wishlist.row.state.$index", useUnmergedTree = true).assertTextContains(label, substring = true)
        }
    }
    private fun shown(tag:String):String {
        val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.last().layoutInput.text.text
    }
    private fun shownColor(tag:String):androidx.compose.ui.graphics.Color {
        val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.last().layoutInput.style.color
    }
    /** The group's header already says it; the line says only what the header cannot. A reader of the row alone still hears it. */
    @Test fun aRowUnderItsStatesHeaderDoesNotShowTheWordAgain() {
        source.state=source.state.copy(wishes=listOf(row("requested")))
        host()
        val line=shown("wishlist.row.state.0")
        assertFalse(line.contains("Requested"),line)
        assertTrue(line.startsWith("Song"),line)
        compose.onNodeWithText("Requested").assertExists()
        compose.onNodeWithTag("wishlist.row.state.0",useUnmergedTree=true).assertTextContains("Requested",substring=true)
    }
    /**
     * A pending wish uses one status in its group, row and sheet. Reading the row alone still announces the status.
     */
    @Test fun aPendingWishShowsItsSyncStatusInTheSharedRowTemplate() {
        source.state=source.state.copy(wishes=listOf(row().copy(pending=true)))
        host()
        compose.onNodeWithTag("wishlist.section.pending",useUnmergedTree=true).assertTextEquals("Not synced yet")
        val line=shown("wishlist.row.state.0")
        assertTrue(line.startsWith("Song"),line)
        assertTrue(line.contains("Not synced yet"), line)
        assertFalse(line.contains("Wanted"), line)
        compose.onNodeWithTag("wishlist.row.state.0",useUnmergedTree=true).assertTextContains("Not synced yet",substring=true)
        compose.onNodeWithTag("wishlist.row.pending.0",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("wishlist.row.0").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithTag("wishlist.details.status",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        assertEquals("Not synced yet",shown("wishlist.details.status"))
    }
    /** The rows no longer repeat their state, so a scrolled group keeps its header at the top of the list, over the rows. */
    @Test fun aScrolledGroupKeepsItsHeaderPinned() {
        source.state=source.state.copy(wishes=listOf("unavailable","requested").flatMap { state -> (0 until 12).map { row(state,key="$state$it") } })
        host()
        // Unscrolled, a header sits in the list like any heading: no edge of its own.
        compose.onNodeWithTag("wishlist.section.unavailable.pinned",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("wishlist.list").performScrollToNode(hasTestTag("wishlist.row.8"))
        compose.waitForIdle()
        // Pinned over rows, it takes the scrolled bar's tint and a hairline, so the rows passing under it have an edge.
        compose.onNodeWithTag("wishlist.section.unavailable.pinned",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("wishlist.section.requested.pinned",useUnmergedTree=true).assertDoesNotExist()
        val list=compose.onNodeWithTag("wishlist.list").getBoundsInRoot()
        val header=compose.onNodeWithTag("wishlist.section.unavailable",useUnmergedTree=true).assertIsDisplayed().getBoundsInRoot()
        assertTrue(header.top==list.top+DulcetSpacing.sectionGap,"Not found header is not pinned: list $list, header $header")
        compose.onNodeWithTag("wishlist.list").performScrollToNode(hasTestTag("wishlist.row.20"))
        compose.waitForIdle()
        compose.onNodeWithTag("wishlist.section.requested",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("wishlist.section.unavailable",useUnmergedTree=true).assertDoesNotExist()
    }
    /** Not found is the one state that asks for attention: its header and its sheet say it in the same red. */
    @Test fun notFoundIsRedWhereverItIsSaid() {
        source.state=source.state.copy(wishes=listOf(row("unavailable")))
        var error=androidx.compose.ui.graphics.Color.Unspecified
        source.publish(source.state)
        compose.setContent {MaterialTheme {error=MaterialTheme.colorScheme.error;PhoneWishlist(session,{},{})}}
        assertEquals(error,shownColor("wishlist.section.unavailable"))
        compose.onNodeWithTag("wishlist.row.0").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithTag("wishlist.details.status",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        assertEquals(error,shownColor("wishlist.details.status"))
    }
    /** The sheet's artist is the line that stands out under the title; its status is plain metadata beside the dates. */
    @Test fun theSheetsStatusIsNotAsHeavyAsItsArtist() {
        source.state=source.state.copy(wishes=listOf(row("requested")))
        var neutral=androidx.compose.ui.graphics.Color.Unspecified
        var metadata=androidx.compose.ui.text.TextStyle.Default
        source.publish(source.state)
        compose.setContent {MaterialTheme {neutral=MaterialTheme.colorScheme.onSurfaceVariant;metadata=MaterialTheme.typography.bodyMedium;PhoneWishlist(session,{},{})}}
        compose.onNodeWithTag("wishlist.row.0").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithTag("wishlist.details.status",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        assertEquals(neutral,shownColor("wishlist.details.status"))
        val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("wishlist.details.status",useUnmergedTree=true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertEquals(metadata.fontWeight,results.last().layoutInput.style.fontWeight)
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
        compose.onNodeWithText("Couldn't refresh wishlist").assertExists()
        compose.onNodeWithText("Wish saved").assertExists()
        compose.onNodeWithText("Not saved").assertDoesNotExist()
    }
    @Test fun aFailedRefreshUsesTheInfoNoticeAndKeepsRetryBelowTheCopy() {
        source.state=source.state.copy(wishes=listOf(row(key="saved")),wishlistError=DiscoverFailure("unreachable",null,true))
        var calm=androidx.compose.ui.graphics.Color.Unspecified
        source.publish(source.state)
        compose.setContent {MaterialTheme {calm=MaterialTheme.colorScheme.onSecondaryContainer;PhoneWishlist(session,{},{})}}
        assertEquals(calm,shownColor("wishlist.refreshFailed"))
        val text=compose.onNodeWithTag("wishlist.refreshFailed",useUnmergedTree=true).getBoundsInRoot()
        val retry=compose.onNodeWithTag("wishlist.refreshFailed.retry",useUnmergedTree=true).getBoundsInRoot()
        assertTrue(text.bottom <= retry.top, "Try again should follow the notice copy: text $text, retry $retry")
        compose.onNodeWithTag("wishlist.refreshFailed.retry").assertHasClickAction()
    }

}
