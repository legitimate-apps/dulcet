package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
    @Test fun aRestoredQueryIsSubmittedToTheReplacementReaderSession() {
        val sources=mutableListOf<FakeDiscoverWishlistSource>()
        val restoration=StateRestorationTester(compose)
        restoration.setContent {
            val restoredSession=remember {
                DiscoverWishlistSession(FakeDiscoverWishlistSource().also { sources+=it })
            }
            DisposableEffect(restoredSession) { onDispose { restoredSession.close() } }
            MaterialTheme { PhoneDiscoverSearch(restoredSession,{}, {}, {}) }
        }
        compose.onNodeWithTag("discover.search.field").performTextInput("new")
        assertEquals("new",sources.single().queries.last())
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discover.search.field").assertTextContains("new")
        compose.runOnIdle {
            assertEquals(2,sources.size)
            assertEquals("new",sources.last().queries.lastOrNull(),
                "A restored field must drive the new reader, rather than showing a query with no results")
        }
    }
    @Test fun replacingAVisibleReaderSubmitsTheQueryToThatReader() {
        val active=mutableStateOf(session)
        val replacementSource=FakeDiscoverWishlistSource()
        val replacement=DiscoverWishlistSession(replacementSource)
        try {
            compose.setContent { MaterialTheme { PhoneDiscoverSearch(active.value,{}, {}, {}) } }
            compose.onNodeWithTag("discover.search.field").performTextInput("new")
            compose.runOnIdle { active.value=replacement }
            compose.onNodeWithTag("discover.search.field").assertTextContains("new")
            compose.runOnIdle {
                assertEquals("new",replacementSource.queries.lastOrNull(),
                    "The retained field must drive the current reader rather than its previous publication")
            }
        } finally { replacement.close() }
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
        compose.setContent { MaterialTheme { PhoneDiscoverAlbum(session,"album",{},{}) } }
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
    @Test fun heldCanonicalResultStillOffersWishAfterPriorRemoval() {
        val local=DiscoverLibraryRef("provider:test","song","local-song")
        val target=source.item("song").copy(availability=DiscoverAvailability("held","known",null,null,null,null,listOf(local)))
        source.state=source.state.copy(groups=listOf(DiscoverGroup("song",source.page(listOf(target)),false,null)))
        search()
        compose.onNodeWithTag("discover.openLibrary.song.0").assertIsDisplayed()
        // A scan may fulfil A after it was removed. Its held metadata still starts a new B.
        compose.onNodeWithTag("discover.wish.song.0").assertIsDisplayed().performClick()
        assertEquals(1,source.added)
    }
    @Test fun artistDetailNamesItsLibraryHeaderAndPrimaryWish() {
        compose.setContent { MaterialTheme { PhoneDiscoverArtist(session,"artist",{},{},{}) } }
        compose.onNodeWithTag("discover.artist.header").assertIsDisplayed()
        compose.onNodeWithTag("discover.wish.artist.0").assertTextContains("Add to Wishlist")
    }
    @Test fun albumDetailNamesItsLibraryHeaderAndPrimaryWish() {
        compose.setContent { MaterialTheme { PhoneDiscoverAlbum(session,"album",{},{}) } }
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
    @Test
    @Config(sdk=[35],qualifiers="w411dp-h891dp-port",fontScale=2f)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun largestTextTrackKeepsItsFullTitleBesideLibraryAndWishActions() {
        val title="A long recording title that must remain readable"
        val local=DiscoverLibraryRef("provider:test","song","local-song")
        val held=source.item("song").copy(title=title,
            availability=DiscoverAvailability("held","known",null,null,null,null,listOf(local)))
        compose.setContent { MaterialTheme {
            DiscoverItemRow(session,held,0,WishSource("search",null),{},{},{})
        } }
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(title,useUnmergedTree=true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f,layouts.single().layoutInput.density.fontScale)
        assertTrue(layouts.single().size.width>20,"Positive native text measurement")
        assertFalse(layouts.single().hasVisualOverflow,"Largest text must not ellipsize the recording title: ${layouts.single()}")
        compose.onNodeWithTag("discover.openLibrary.song.0").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("discover.wish.song.0").assertIsDisplayed().assertHasClickAction()
    }
    @Test fun albumCardKeepsArtCleanAndMovesActionsIntoItsMenu() {
        val local=DiscoverLibraryRef("provider:test","album","local-album")
        val held=source.item("album").copy(availability=DiscoverAvailability("held","known",null,12,12,null,listOf(local)))
        var opened:String?=null; var openedLibrary:DiscoverLibraryRef?=null
        compose.setContent { MaterialTheme { DiscoverAlbumCard(session,held,0,WishSource("artist","artist"),{openedLibrary=it},{opened=it}) } }
        compose.onNodeWithTag("discover.openLibrary.album.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.reject.album.0.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.item.menu.album.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.item.album.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("discover.openLibrary.album.0").assertIsDisplayed()
        compose.onNodeWithTag("discover.reject.album.0.0").assertIsDisplayed()
        compose.onNodeWithTag("discover.openLibrary.album.0").performClick()
        assertEquals("local-album",openedLibrary?.id)
        compose.onNodeWithTag("discover.item.album.0").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("album",opened)
    }
    @Test fun albumCardMenuOffersWishForNotHeldMusic() {
        compose.setContent { MaterialTheme { DiscoverAlbumCard(session,source.item("album"),0,WishSource("artist","artist"),{},{}) } }
        compose.onNodeWithTag("discover.wish.album.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.item.album.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("discover.wish.album.0").assertIsDisplayed()
        compose.onNodeWithTag("discover.wish.album.0").performClick()
        assertEquals(1,source.added)
    }
}
