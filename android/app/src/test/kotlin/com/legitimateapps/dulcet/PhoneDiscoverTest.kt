package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import androidx.compose.ui.semantics.SemanticsProperties
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
            PhoneSearchScopes(session, server = {
                androidx.compose.foundation.layout.Column {
                    // The real server Search chrome renders the shared scope slot.
                    LocalPhoneSearchScopes.current?.invoke()
                    androidx.compose.material3.Text("Existing query", androidx.compose.ui.Modifier.testTag("baseline"))
                }
            }, openLibrary = {}, openArtist = {}, openAlbum = {})
        } }
        compose.onNodeWithTag("baseline").assertIsDisplayed()
        compose.onNodeWithTag("discover.scope.everywhere").performScrollTo().performClick()
        compose.onNodeWithTag("discover.search.field").performTextInput("new")
        assertEquals("new",source.queries.last())
        compose.onNodeWithTag("discover.scope.server").performClick()
        compose.onNodeWithTag("baseline").assertIsDisplayed()
        compose.onNodeWithTag("discover.scope.everywhere").performScrollTo().performClick()
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
    @Test fun aPendingSongRowAnnouncesWaitingToSyncAtTheLargestFont() {
        val song=source.item("song")
        val pending=WishMutation.Add("pending",1,song.target!!,WishSnapshot(song.title,null,null,null),WishSource("search",null))
        source.state=source.state.copy(query="new",groups=listOf(DiscoverGroup("song",source.page(listOf(song)),false,null)),
            wishes=listOf(WishRow("pending",null,pending,true,null)))
        source.publish(source.state)
        compose.setContent { MaterialTheme {
            val density=androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)) {
                PhoneDiscoverSearch(session,{}, {}, {})
            }
        } }
        compose.onNodeWithTag("discover.list").performScrollToNode(hasTestTag("discover.item.song.0"))
        compose.onNodeWithTag("discover.sync.song.0",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("discover.item.song.0").assert(hasContentDescription("Waiting to sync",substring=true))
        // The reason the emulator proof reaches the row: its icon's tag is not in the merged tree.
        assertEquals(0,compose.onAllNodesWithTag("discover.sync.song.0").fetchSemanticsNodes().size)
    }
    @Test fun resultGroupsWithoutRowsOrInitialLoadingAreHidden() {
        source.state=source.state.copy(query="new",groups=listOf(
            DiscoverGroup("artist",source.page(emptyList()),false,null),
            DiscoverGroup("album",null,false,null),
            DiscoverGroup("song",null,true,null)))
        search()
        compose.onNodeWithTag("discover.group.artist").assertDoesNotExist()
        compose.onNodeWithTag("discover.group.album").assertDoesNotExist()
        compose.onNodeWithTag("discover.group.song").assertExists()
        compose.onNodeWithTag("discover.group.song.loading").assertExists()
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
        // The card's own line says when; a button that cannot be pressed would say it a second time.
        compose.onNodeWithTag("discover.retry").assertDoesNotExist()
        compose.onNodeWithTag("discover.notice.stale").assertTextContains("ago", substring = true)
    }
    private fun failedSearch(failure: DiscoverFailure) {
        source.state=source.state.copy(connection="online",query="music",groups=listOf(DiscoverGroup("album",source.page(listOf(source.item("album"))),false,failure)))
        search()
    }
    @Test fun aWaitThatHasEndedOffersTheRetryAndNoLongerCountsMinutes() {
        failedSearch(DiscoverFailure("rate-limited",System.currentTimeMillis()-1_000,true))
        compose.onNodeWithTag("discover.retry").assertIsEnabled()
        compose.onNodeWithTag("discover.notice.busy").assertTextEquals("Server is busy.")
    }
    /** The card's Try again ends where its sentence starts, mirrored: the button's own padding no longer pushes the ink inward. */
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // Real text widths: legacy ones centre a narrow label in the button.
    @Test fun aRetryLinesItsInkUpWithTheCardsText() {
        failedSearch(DiscoverFailure("rate-limited",System.currentTimeMillis()-1_000,true))
        val root=compose.onRoot().getBoundsInRoot()
        val sentence=compose.onNodeWithTag("discover.notice.busy",useUnmergedTree=true).getBoundsInRoot()
        val ink=compose.onNodeWithText("Try again",useUnmergedTree=true).getBoundsInRoot()
        val start=sentence.left-root.left
        val end=root.right-ink.right
        assertTrue((start-end).value.let { kotlin.math.abs(it) } <= 1f,"Try again ends ${end} from the edge, the sentence starts ${start} from it (ink $ink)")
    }
    /**
     * Everywhere's field and chips sit where Your server's do, so switching scope moves nothing: 8dp above the field, as
     * Search's column pads it, and a 12dp gap to the chips, as Search's column spaces it.
     */
    @Test fun everywhereKeepsSearchsFieldAndChipSpacing() {
        source.publish(source.state)
        compose.setContent { MaterialTheme {
            PhoneSearchScopes(session, server = {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(vertical = 8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    PhoneSearchField("", {}, {}, "search.query")
                    LocalPhoneSearchScopes.current?.invoke()
                }
            }, openLibrary = {}, openArtist = {}, openAlbum = {})
        } }
        val serverField=compose.onNodeWithTag("search.query").getBoundsInRoot()
        val serverChip=compose.onNodeWithTag("discover.scope.server").getBoundsInRoot()
        compose.onNodeWithTag("discover.scope.everywhere").performClick()
        compose.waitForIdle()
        val field=compose.onNodeWithTag("discover.search.field").getBoundsInRoot()
        val chip=compose.onNodeWithTag("discover.scope.server").getBoundsInRoot()
        assertEquals(serverField.top,field.top,"the field moved when the scope changed")
        assertEquals(serverChip.top,chip.top,"the chips moved when the scope changed")
    }
    @Test fun aFailureARetryCanAnswerOffersItEnabledAndOneItCannotOffersNothing() {
        failedSearch(DiscoverFailure("server-error",null,true))
        compose.onNodeWithTag("discover.retry").assertIsEnabled()
        source.publish(source.state.copy(groups=listOf(DiscoverGroup("album",source.page(listOf(source.item("album"))),false,DiscoverFailure("server-error",null,false)))))
        compose.waitForIdle()
        compose.onNodeWithTag("discover.retry").assertDoesNotExist()
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
    @Test fun emptyEditionExplainsThatNoTracksAreListed() {
        source.albums[null] = DiscoverAlbum(source.item("album"), emptyList(), "empty-edition", source.page(emptyList()))
        compose.setContent { MaterialTheme { PhoneDiscoverAlbum(session, "album", {}, {}) } }
        compose.onNodeWithTag("discover.album.list").performScrollToNode(hasTestTag("discover.album.tracks.empty"))
        compose.onNodeWithTag("discover.album.tracks.empty").assertTextContains("No tracks are listed", substring = true)
    }
    @Test fun partialUnknownAndHeldActionsAreDistinct() {
        var opened:DiscoverLibraryRef?=null
        val local=DiscoverLibraryRef("provider:test","album","local-album")
        source.state=source.state.copy(groups=listOf(DiscoverGroup("album",source.page(listOf(
            source.item("album").copy(availability=DiscoverAvailability("partlyHeld","known",null,8,12,null,listOf(local))),
            source.item("album",title="Unknown").copy(availability=DiscoverAvailability("notHeld","unknown",null,null,null,null,emptyList())))),false,null)))
        source.publish(source.state)
        compose.setContent { MaterialTheme { PhoneDiscoverSearch(session,{opened=it},{},{}) } }
        compose.onNodeWithTag("discover.availability.album.0", useUnmergedTree = true).assertTextContains("8 of 12 tracks in your library")
        revealAction("discover.openLibrary.album.0")
        compose.onNodeWithTag("discover.openLibrary.album.0").performClick()
        assertEquals("provider:test",opened?.providerInstanceId)
        assertEquals("local-album",opened?.id)
        compose.onNodeWithText("Not in your library").assertDoesNotExist()
    }
    @Test fun existingWishWithoutRemovalPermissionOffersNeitherDuplicateAddNorRemove() {
        val item = source.item("song")
        val wish = DiscoverWish("existing", "u", item.target, "song", WishSnapshot(item.title, item.artist, null, null),
            WishSource("search", null), 1000, 2000, "wanted", null, null, null, null, item.availability, "1")
        source.state = source.state.copy(access = source.state.access.copy(canAddWish = true, canRemoveWish = false),
            groups = listOf(DiscoverGroup("song", source.page(listOf(item)), false, null)),
            wishes = listOf(WishRow("existing", wish, null, false, null)))
        search()
        compose.onNodeWithTag("discover.item.song.0").assertIsDisplayed()
        compose.onNodeWithTag("discover.item.menu.song.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.wish.song.0").assertDoesNotExist()
        assertEquals(0, source.added)
        assertNull(source.removed)
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
        compose.onNodeWithTag("discover.openLibrary.album.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.reject.album.0.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.item.menu.album.0").performClick()
        compose.onNodeWithTag("discover.openLibrary.album.0").assertIsDisplayed()
        compose.onNodeWithTag("discover.reject.album.0.0").assertIsDisplayed()
    }
    @Test fun heldCanonicalResultStillOffersWishAfterPriorRemoval() {
        val local=DiscoverLibraryRef("provider:test","song","local-song")
        val target=source.item("song").copy(availability=DiscoverAvailability("held","known",null,null,null,null,listOf(local)))
        source.state=source.state.copy(groups=listOf(DiscoverGroup("song",source.page(listOf(target)),false,null)))
        search()
        revealAction("discover.openLibrary.song.0")
        compose.onNodeWithTag("discover.openLibrary.song.0").assertIsDisplayed()
        // A scan may fulfil A after it was removed. Its held metadata still starts a new B.
        revealAction("discover.wish.song.0")
        compose.onNodeWithTag("discover.wish.song.0").assertIsDisplayed().performClick()
        assertEquals(1,source.added)
    }
    @Test fun artistDetailNamesItsLibraryHeaderAndPrimaryWish() {
        compose.setContent { MaterialTheme { PhoneDiscoverArtist(session,"artist",{},{},{}) } }
        compose.onNodeWithTag("discover.artist.header").assertIsDisplayed()
        revealAction("discover.wish.artist.0")
        compose.onNodeWithTag("discover.wish.artist.0").assertTextContains("Add to wishlist")
    }
    @Test fun albumDetailNamesItsLibraryHeaderAndPrimaryWish() {
        compose.setContent { MaterialTheme { PhoneDiscoverAlbum(session,"album",{},{}) } }
        compose.onNodeWithTag("discover.album.header").assertIsDisplayed()
        revealAction("discover.wish.album.0")
        compose.onNodeWithTag("discover.wish.album.0").assertTextContains("Add to wishlist")
    }
    @Test fun availabilityHasAStableDiscoverTreatment() {
        source.state=source.state.copy(groups=listOf(DiscoverGroup("song",source.page(listOf(source.item("song"))),false,null)))
        search()
        compose.onNodeWithTag("discover.availability.song.0", useUnmergedTree = true).assertTextContains("Not in your library")
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
    fun largestTextResultKeepsFullAccessibleTitleWithinTwoLinesAndWorkingMenuActions() {
        val title="A long recording title that must remain readable"
        val local=DiscoverLibraryRef("provider:test","song","local-song")
        var opened: DiscoverLibraryRef? = null
        val held=source.item("song").copy(title=title,
            availability=DiscoverAvailability("held","known",null,null,null,null,listOf(local)))
        compose.setContent { MaterialTheme {
            DiscoverItemRow(session,held,0,WishSource("search",null),{ opened = it },{},{})
        } }
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(title,useUnmergedTree=true).assertTextEquals(title)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f,layouts.single().layoutInput.density.fontScale)
        assertTrue(layouts.single().size.width>20,"Positive native text measurement")
        // The visible contract is two lines with ellipsis; accessibility and layout retain the complete title.
        assertEquals(title, layouts.single().layoutInput.text.text)
        assertEquals(2, layouts.single().layoutInput.maxLines)
        assertEquals(2, layouts.single().lineCount)
        revealAction("discover.openLibrary.song.0")
        compose.onNodeWithTag("discover.openLibrary.song.0").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(local, opened)
        revealAction("discover.wish.song.0")
        compose.onNodeWithTag("discover.wish.song.0").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, source.added)
    }
    @Test
    @Config(sdk=[35],qualifiers="w411dp-h891dp-port",fontScale=2f)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun largestTextAlbumTrackKeepsFullAccessibleTitleWithinTwoLinesAndWorkingMenuActions() {
        val title="A long recording title that must remain readable"
        val local=DiscoverLibraryRef("provider:test","song","local-song")
        var opened: DiscoverLibraryRef? = null
        val held=source.item("song").copy(title=title,
            availability=DiscoverAvailability("held","known",null,null,null,null,listOf(local)))
        compose.setContent { MaterialTheme {
            DiscoverTrackRow(session,held,0,WishSource("album","album"),{ opened = it })
        } }
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(title,useUnmergedTree=true).assertTextEquals(title)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f,layouts.single().layoutInput.density.fontScale)
        assertTrue(layouts.single().size.width>20,"Positive native text measurement")
        // The visible contract is two lines with ellipsis; accessibility and layout retain the complete title.
        assertEquals(title, layouts.single().layoutInput.text.text)
        assertEquals(2, layouts.single().layoutInput.maxLines)
        assertEquals(2, layouts.single().lineCount)
        revealAction("discover.openLibrary.song.0")
        compose.onNodeWithTag("discover.openLibrary.song.0").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(local, opened)
        revealAction("discover.wish.song.0")
        compose.onNodeWithTag("discover.wish.song.0").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, source.added)
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
        revealAction("discover.openLibrary.album.0")
        compose.onNodeWithTag("discover.openLibrary.album.0").assertIsDisplayed()
        compose.onNodeWithTag("discover.reject.album.0.0").assertIsDisplayed()
        revealAction("discover.openLibrary.album.0")
        compose.onNodeWithTag("discover.openLibrary.album.0").performClick()
        assertEquals("local-album",openedLibrary?.id)
        compose.onNodeWithTag("discover.item.album.0").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("album",opened)
    }
    @Test fun anArtistsOwnDiscoverAlbumCardShowsTheYearAloneAndNotAFullDate() {
        val dated=source.item("album").copy(artist="Fixture Artist 00",releaseDate="2026-03-04")
        compose.setContent { MaterialTheme { DiscoverAlbumCard(session,dated,0,WishSource("artist","artist"),{},{},omitArtist=true) } }
        val shown=compose.onNodeWithTag("discover.item.album.0").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf(dated.title,"2026"),shown)
    }
    @Test fun aHeldAlbumLeadsWithOpeningItAndWishingDropsBelow() {
        val local=DiscoverLibraryRef("provider:test","album","local-album")
        val held=source.item("album").copy(availability=DiscoverAvailability("held","known",null,12,12,null,listOf(local)))
        compose.setContent { MaterialTheme { DiscoverDetailHeader(session,held,WishSource("album","album"),"header",null,{},{}) } }
        val top={ tag:String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("discover.openLibrary.album.0") < top("discover.wish.album.0"))
    }
    /** A25: three stacked actions pushed Tracks under the bar; Play and the wish share a line when they fit. */
    @Test fun aHeldAlbumsPlayAndWishShareALineAtEveryTextSize() {
        val local=DiscoverLibraryRef("provider:test","album","local-album")
        val held=source.item("album").copy(availability=DiscoverAvailability("held","known",null,12,12,null,listOf(local)))
        val scale=androidx.compose.runtime.mutableStateOf(1f)
        compose.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,scale.value)) {
                MaterialTheme { DiscoverDetailHeader(session,held,WishSource("album","album"),"header",null,{},{},playLibrary={}) }
            }
        }
        for (each in listOf(1f,2f)) {
            scale.value=each;compose.waitForIdle()
            val bounds={ tag:String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot }
            assertEquals(bounds("discover.play.album.0").top,bounds("discover.wish.album.0").top,"at text scale $each")
            assertTrue(bounds("discover.openLibrary.album.0").bottom<=bounds("discover.play.album.0").top,"Opening it still leads")
            assertTrue(bounds("discover.play.album.0").right<=bounds("discover.wish.album.0").left,"Play comes first")
            // Host text is one pixel a character, so a cut label cannot be measured here. What cuts one can:
            // wish keeps the width its words ask for, and Play takes what is left instead of half.
            val wish=bounds("discover.wish.album.0");val play=bounds("discover.play.album.0")
            assertTrue(wish.width<play.width,"wish hugs its words at text scale $each: $wish beside $play")
            if (each>=2f) {
                compose.onNodeWithTag("discover.play.album.0.icon",useUnmergedTree=true).assertDoesNotExist()
                compose.onNodeWithTag("discover.wish.album.0.icon",useUnmergedTree=true).assertDoesNotExist()
            }
        }
    }
    @Test fun editionDatesReadAsDatesAtEveryPrecision() {
        assertEquals("Mar 4, 2026",discoverEditionDate("2026-03-04",java.util.Locale.US))
        assertEquals("Mar 2026",discoverEditionDate("2026-03",java.util.Locale.US))
        assertEquals("2026",discoverEditionDate("2026",java.util.Locale.US))
        assertEquals("unknown",discoverEditionDate("unknown",java.util.Locale.US))
        assertEquals("2026-13-04",discoverEditionDate("2026-13-04",java.util.Locale.US))
    }
    @Test fun albumCardMenuOffersWishForNotHeldMusic() {
        compose.setContent { MaterialTheme { DiscoverAlbumCard(session,source.item("album"),0,WishSource("artist","artist"),{},{}) } }
        compose.onNodeWithTag("discover.wish.album.0").assertDoesNotExist()
        compose.onNodeWithTag("discover.item.album.0").performSemanticsAction(SemanticsActions.OnLongClick)
        revealAction("discover.wish.album.0")
        compose.onNodeWithTag("discover.wish.album.0").assertIsDisplayed()
        revealAction("discover.wish.album.0")
        compose.onNodeWithTag("discover.wish.album.0").performClick()
        assertEquals(1,source.added)
    }
    /** The page's answer, released only after the loading page has been laid out, as a network answer is. */
    private class HeldDetails(private val fake: FakeDiscoverWishlistSource) : com.legitimateapps.dulcet.library.DiscoverWishlistSource by fake {
        val held = mutableListOf<() -> Unit>()
        var artist: DiscoverArtist? = null
        override fun album(mbid: String, releaseId: String?, completion: (DiscoverResult<DiscoverAlbum>) -> Unit) =
            fake.album(mbid, releaseId) { result -> held += { completion(result) } }
        override fun artist(mbid: String, completion: (DiscoverResult<DiscoverArtist>) -> Unit) =
            fake.artist(mbid) { result -> held += { completion(artist?.let { DiscoverResult.Value(it) } ?: result) } }
        fun release() { held.toList().also { held.clear() }.forEach { it() } }
    }
    @Test fun anAlbumThatArrivesAfterItsLoadingPageOpensAtItsHeaderAndAvailability() {
        val local=DiscoverLibraryRef("provider:test","album","local-album")
        source.albums[null]=DiscoverAlbum(source.item("album").copy(availability=DiscoverAvailability("partlyHeld","known",null,8,12,null,listOf(local))),
            listOf(DiscoverEdition("one",null,30,emptyList()),DiscoverEdition("two",null,30,emptyList())),"one",
            source.page((0 until 30).map { source.item("song",title="Track $it") }))
        val details=HeldDetails(source)
        val heldSession=DiscoverWishlistSession(details)
        try {
            compose.setContent { MaterialTheme { PhoneDiscoverAlbum(heldSession,"album",{},{}) } }
            compose.onNodeWithTag("discover.album.loading").assertExists()
            compose.runOnIdle { assertTrue(details.held.isNotEmpty(), "The album answer must still be held") ; details.release() }
            compose.onNodeWithTag("discover.album.header").assertIsDisplayed()
            compose.onNodeWithTag("discover.availability.album.0", useUnmergedTree = true)
                .assertIsDisplayed().assertTextContains("8 of 12", substring = true)
        } finally { heldSession.close() }
    }
    @Test fun anArtistThatArrivesAfterItsLoadingPageOpensAtItsHeader() {
        val details=HeldDetails(source)
        details.artist=DiscoverArtist(source.item("artist"),source.page((0 until 40).map { source.item("album",title="Album $it") }))
        val heldSession=DiscoverWishlistSession(details)
        try {
            compose.setContent { MaterialTheme { PhoneDiscoverArtist(heldSession,"artist",{},{},{}) } }
            compose.onNodeWithTag("discover.artist.loading").assertExists()
            compose.runOnIdle { assertTrue(details.held.isNotEmpty(), "The artist answer must still be held") ; details.release() }
            compose.onNodeWithTag("discover.artist.header").assertIsDisplayed()
        } finally { heldSession.close() }
    }
    private fun revealAction(tag: String) {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
        val suffix = tag.substringAfter("discover.").substringAfter('.')
        val trigger = "discover.item.menu.$suffix"
        if (compose.onAllNodesWithTag(trigger).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag(trigger).performClick()
        else compose.onNodeWithTag("discover.item.$suffix").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

}
