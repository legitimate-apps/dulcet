package com.legitimateapps.dulcet.tv

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import com.legitimateapps.dulcet.playback.PlaybackService
import com.legitimateapps.dulcet.search.conformance.DisposableServer
import com.legitimateapps.dulcet.search.conformance.albumId
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import com.legitimateapps.dulcet.search.conformance.LibraryReaderScenarios
import com.legitimateapps.dulcet.search.conformance.ProductionLibraryEnvironment
import com.legitimateapps.dulcet.search.conformance.ReaderAppUi
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Android TV app's library on the reader, end to end: the production activity, `LibraryEntry`'s
 * `LibrarySession` and `AndroidLibraryReader` against the disposable server. The same scenarios as
 * the phone's, driven through the TV's own screens; one test per CONF id, then the session's own
 * reachability handling and album play, one test each.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w960dp-h540dp")
class AndroidTvProductionLibraryReaderAppConformanceTest {
    private val environment = ProductionLibraryEnvironment()
    private val compose = createAndroidComposeRule<TvSearchActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)

    /** The TV's navigation row: Search and Library, each a root, reached as a remote reaches it. */
    private fun show(tab: String) = press(tab)

    private val scenarios by lazy {
        LibraryReaderScenarios(compose, environment, object : ReaderAppUi {
            override fun openLibrary() = show("library.open")

            override fun openSearch() = show("search.open")

            override fun backFromAlbum() = press("album.back")

            /** A TV has no touch: focus on the node, then the remote's centre key. */
            override fun activate(node: SemanticsNodeInteraction) {
                node.performSemanticsAction(SemanticsActions.RequestFocus)
                compose.waitForIdle()
                check(node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }) {
                    "The remote could not focus ${node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.TestTag) { "?" }}"
                }
                key(Key.DirectionCenter)
            }

            /**
             * As a remote does it: focus on [from], one step down with the D-pad, then the centre key.
             * Where the step lands is the app's focus order, so a row that takes two steps to reach
             * or cannot be selected from where it lands fails here. A track row is also left for
             * the next one, which must exist, and re-entered, one step each way (focus order
             * between rows), and must still hold focus after the centre key: the row handles only
             * the key's release, so its press reaches the platform, which moves focus into any
             * focus target inside the row. A row that is two focus targets, one inside the other,
             * therefore loses focus there.
             */
            override fun select(tag: String, from: String) {
                compose.onNodeWithTag(from).performSemanticsAction(SemanticsActions.RequestFocus)
                compose.waitForIdle()
                compose.onNodeWithTag(from).assertIsFocused()
                compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                compose.waitForIdle()
                compose.onNodeWithTag(tag).assertIsFocused()
                val track = TRACK_ROW.matchEntire(tag)
                val next = track?.let { "album.track.${it.groupValues[1].toInt() + 1}" }
                if (next != null) {
                    check(compose.onAllNodesWithTag(next).fetchSemanticsNodes().isNotEmpty()) {
                        "No $next to leave $tag for: the walk between rows needs an album with a track after it"
                    }
                    compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                    compose.waitForIdle()
                    compose.onNodeWithTag(next).assertIsFocused()
                    compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
                    compose.waitForIdle()
                    compose.onNodeWithTag(tag).assertIsFocused()
                }
                compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
                compose.waitForIdle()
                if (track != null) compose.onNodeWithTag(tag).assertIsFocused()
            }
        }, platform = "androidtv")
    }

    private companion object {
        val TRACK_ROW = Regex("album\\.track\\.(\\d+)")
    }

    @Test fun conf76RelaunchPaintsTheCacheBeforeAnyAnswerAndANeverOpenedAlbumSaysUnavailableOffline() =
        scenarios.conf76CachedPaintBeforeAnyRequestAndOfflineUnavailable()

    @Test fun conf77ReconnectFlushesReadsTheEpochRevalidatesTheVisibleScreenAndNothingElse() =
        scenarios.conf77ReconnectFlushesReadsTheEpochRevalidatesAndNothingElse()

    @Test fun conf77ForegroundReturnReadsTheEpochAndTheVisibleScreenOnly() =
        scenarios.conf77ForegroundReturnReadsTheEpochAndTheVisibleScreenOnly()

    @Test fun conf77EpochCadenceRunsInTheForegroundOnly() =
        scenarios.conf77EpochCadenceRunsInTheForegroundOnly()

    @Test fun conf79SearchPublishesEachScopeWithSeenCountsOffline() =
        scenarios.conf79SearchPublishesEachScopeWithSeenCountsOffline()

    @Test fun conf84StarShowsWithTheTapSurvivesARevalidationCompactsAndAdoptsTheEcho() =
        scenarios.conf84StarShowsWithTheTapSurvivesARevalidationAndAdoptsTheEcho()

    @Test fun conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive() =
        scenarios.conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive()

    @Test fun aReconnectAnsweredAfterTheNetworkWentAwayLeavesTheLibraryOffline() =
        scenarios.aReconnectAnsweredAfterTheNetworkWentAwayLeavesTheLibraryOffline()

    @Test fun anUnreachableServerTakesTheReaderOfflineAndTryingAgainTellsItTheNetworkIsBack() =
        scenarios.anUnreachableServerTakesTheReaderOfflineAndTryingAgainTellsItTheNetworkIsBack()

    @Test fun aTransientReconnectFailureIsRetriedInTheForegroundOnly() =
        scenarios.aTransientReconnectFailureIsRetriedInTheForegroundOnly()

    @Test fun anUnreachableAnswerArrivingInTheBackgroundStartsNoRead() =
        scenarios.anUnreachableAnswerArrivingInTheBackgroundStartsNoRead()

    /** Selected with the remote, a track plays its album from there and opens the TV's Now Playing. */
    @Test fun aTrackSelectedWithTheRemotePlaysTheAlbumFromThatTrackAndOpensNowPlaying() =
        scenarios.aTrackSelectedPlaysTheAlbumFromThatTrack {
            val started = assertNotNull(shadowOf(compose.activity).nextStartedActivity, "Now Playing was opened")
            assertEquals(PlaybackIntents.ACTION_SHOW_NOW_PLAYING, started.action)
            val resolved = assertNotNull(compose.activity.packageManager.resolveActivity(started, 0)).activityInfo
            assertEquals(TvPlaybackActivity::class.java.name, resolved.name, "the TV's own Now Playing")
        }

    // ---- The TV's own navigation, focus and playing (not shared with the phone) -------------------

    /**
     * With the remote only — focus moves and the centre key, no semantic click — the albums and
     * artists screens open, a card opens its album or artist, each detail screen puts focus on Play,
     * and every Back returns focus to the element that opened the screen it leaves.
     */
    @Test fun albumsAndArtistsOpenFromTheRemoteAndBackReturnsFocusToWhatOpenedThem() {
        show("library.open")
        await("the first home row's first card to take focus") { focused("library.home.0.item.0") }

        press("library.view.albums")
        await("the albums screen, focus on its first album") { focused("library.albums.item.0") }
        key(Key.DirectionRight)
        assertFocused("library.albums.item.1")
        val album = texts("library.albums.item.1").first()
        key(Key.DirectionCenter)
        await("album $album open, focus on Play") { titleIs("album.title", album) && focused("album.play") }
        back()
        await("Back to the albums, on the album that was opened") { focused("library.albums.item.1") }
        back()
        await("Back to the library, on Albums") { focused("library.view.albums") }

        press("library.view.artists")
        await("the artists screen") { exists("library.artists.item.0") }
        val artist = cardWithText("library.artists.item.", ARTIST)
        focus(artist)
        key(Key.DirectionCenter)
        await("artist $ARTIST open, focus on Play") { titleIs("artist.title", ARTIST) && focused("artist.play") }
        await("the artist's albums") { exists("artist.album.1") }
        val second = texts("artist.album.1").first()
        focus("artist.album.1")
        key(Key.DirectionCenter)
        await("album $second open from the artist") { titleIs("album.title", second) && focused("album.play") }
        back()
        await("Back to the artist, on that album") { focused("artist.album.1") }
        back()
        await("Back to the artists, on $ARTIST") { focused(artist) }
        back()
        await("Back to the library, on Artists") { focused("library.view.artists") }
        back()
        await("Back from the library to search") { exists("search.query") && !exists("library.surface") }
        println("TV NAVIGATION OBSERVED albums=$album artist=$ARTIST artist-album=$second")
    }

    /**
     * Play on an album queues all of its tracks and opens Now Playing; the playing row says so and,
     * selected, opens Now Playing without starting over; the navigation row reaches Now Playing from
     * the library and from search; and Play on an artist queues every album's tracks in the artist's
     * order.
     */
    @Test fun albumAndArtistPlayQueueEveryTrackAndNowPlayingIsReachableFromEveryScreen() {
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val binder = checkNotNull(service.get().onBind(Intent(PlaybackService.LOCAL_BIND))) { "setup: no local binder" }
            shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
                Intent(app, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND),
                ComponentName(app, PlaybackService::class.java),
                binder,
            )
            // The screen binds the service when it starts; it started before the binding existed.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            val playback = checkNotNull(service.get().playback) { "setup: the service has no controller" }
            assertTrue(playback.state.value.queue.isEmpty(), "setup: nothing is queued yet")
            assertFalse(exists("tv.nav.nowplaying"), "Now Playing is offered only while something plays")

            val server = environment.server
            val albumId = server.albumId(ALBUM)
            val songs = server.get("getAlbum", mapOf("id" to albumId)).getJSONObject("album").getJSONArray("song")
            val expected = (0 until songs.length()).map { songs.getJSONObject(it).getString("id") }
            assertTrue(expected.size >= 3, "setup: a multi-track album")

            show("library.open")
            press("library.view.albums")
            await("the albums screen") { exists("library.albums.item.0") }
            compose.onNodeWithTag("library.albums").performScrollToNode(hasText(ALBUM))
            focus(cardWithText("library.albums.item.", ALBUM))
            key(Key.DirectionCenter)
            await("album $ALBUM open, focus on Play") { titleIs("album.title", ALBUM) && focused("album.play") }
            key(Key.DirectionCenter)
            await("the album to be queued") {
                shadowOf(Looper.getMainLooper()).idle()
                playback.state.value.queue.size == expected.size
            }
            val queued = playback.state.value
            assertEquals(expected, queued.queue.map { it.track.rawId }, "the queue is the album, in the server's order")
            assertEquals(0, queued.currentIndex, "Play starts at the first track")
            assertNowPlayingOpened("Play on the album")

            await("the playing row marked, and Now Playing in the navigation row") {
                playingRow("album.track.0") && exists("tv.nav.nowplaying")
            }
            assertFalse(playingRow("album.track.1"), "only the playing row is marked")
            focus("album.track.0")
            key(Key.DirectionCenter)
            assertNowPlayingOpened("the playing row")
            assertEquals(queued.queue.map { it.queueEntryId }, playback.state.value.queue.map { it.queueEntryId },
                "selecting the playing row does not start the album over")

            back()
            await("the albums screen offers Now Playing") { exists("library.albums.item.0") && exists("tv.nav.nowplaying") }
            show("search.open")
            await("search offers Now Playing") { exists("search.query") && exists("tv.nav.nowplaying") }
            press("tv.nav.nowplaying")
            assertNowPlayingOpened("the navigation row")

            val artistAlbums = server.get("getArtist", mapOf("id" to server.artistId(ARTIST)))
                .getJSONObject("artist").getJSONArray("album")
            val artistTracks = (0 until artistAlbums.length()).sumOf { artistAlbums.getJSONObject(it).getInt("songCount") }
            show("library.open")
            press("library.view.artists")
            await("the artists screen") { exists("library.artists.item.0") }
            focus(cardWithText("library.artists.item.", ARTIST))
            key(Key.DirectionCenter)
            await("artist $ARTIST, Play ready") { titleIs("artist.title", ARTIST) && focused("artist.play") }
            key(Key.DirectionCenter)
            await("the artist to be queued") {
                shadowOf(Looper.getMainLooper()).idle()
                playback.state.value.queue.size == artistTracks
            }
            assertNowPlayingOpened("Play on the artist")
            assertNoCredentialLeak()
            println("TV PLAYBACK OBSERVED album=${expected.size} artist=$artistTracks now-playing-entries=4")
        } finally {
            service.destroy()
        }
    }

    private fun assertNowPlayingOpened(from: String) {
        val started = assertNotNull(shadowOf(compose.activity).nextStartedActivity, "$from opened Now Playing")
        assertEquals(PlaybackIntents.ACTION_SHOW_NOW_PLAYING, started.action, from)
        val resolved = assertNotNull(compose.activity.packageManager.resolveActivity(started, 0)).activityInfo
        assertEquals(TvPlaybackActivity::class.java.name, resolved.name, "$from: the TV's own Now Playing")
    }

    private fun assertNoCredentialLeak() {
        val shown = compose.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { text -> text.text } }
        assertTrue(shown.none { ProductionLibraryEnvironment.PASSWORD in it }, "no screen shows the password")
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(60_000) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: Throwable) {
            val tags = compose.onAllNodes(SemanticsMatcher("tagged") { SemanticsProperties.TestTag in it.config }, useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
            throw AssertionError("Timed out waiting for $what; focused=${focusedTags()}; tags=$tags", timeout)
        }
    }

    /** A track row marked as the one playing: selected, with the playing mark inside it. */
    private fun playingRow(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false } &&
            compose.onAllNodesWithTag("$tag.playing", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun assertFocused(tag: String) = assertTrue(focused(tag), "$tag holds focus; focused=${focusedTags()}")

    private fun texts(tag: String): List<String> = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }

    private fun titleIs(tag: String, title: String) = exists(tag) && texts(tag).firstOrNull() == title

    /** The tag of the card among [prefix]N whose title is [title]. */
    private fun cardWithText(prefix: String, title: String): String {
        val node = compose.onAllNodes(hasText(title) and SemanticsMatcher("a card") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
        }).fetchSemanticsNodes().firstOrNull() ?: error("No card titled $title among $prefix*")
        return node.config[SemanticsProperties.TestTag]
    }

    /** As a remote reaches it: focus on [tag], then the centre key. */
    private fun press(tag: String) {
        focus(tag)
        key(Key.DirectionCenter)
    }

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertFocused(tag)
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The remote's Back, through the activity's dispatcher as the platform delivers it. */
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}

private const val ALBUM = "Threshold Boundary"
private const val ARTIST = "Dulcet Fixtures"

private fun DisposableServer.artistId(name: String): String {
    val indexes = get("getArtists").getJSONObject("artists").getJSONArray("index")
    for (i in 0 until indexes.length()) {
        val artists = indexes.getJSONObject(i).getJSONArray("artist")
        for (j in 0 until artists.length()) {
            val artist = artists.getJSONObject(j)
            if (artist.getString("name") == name) return artist.getString("id")
        }
    }
    error("The fixture corpus has no artist named $name")
}
