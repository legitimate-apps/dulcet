package com.legitimateapps.dulcet.tv

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentDialog
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
import org.robolectric.shadows.ShadowDialog

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
    // The TV opens on the library, so it reads before a scenario installs its rules; its launch
    // requests wait at the proxy until the test first opens a screen (or, outside the shared
    // scenarios, unparks it itself), and meet the rules in force then.
    private val environment = ProductionLibraryEnvironment(parkLaunchRequests = true)
    private val compose = createAndroidComposeRule<TvSearchActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)

    /** The TV's navigation row: Search and Library, each a root, reached as a remote reaches it. */
    private fun show(tab: String) = press(tab)

    private val scenarios by lazy {
        LibraryReaderScenarios(compose, environment, object : ReaderAppUi {
            /**
             * The library is the screen the app opens on. Pressing its tab while it shows would race
             * the home's first card, which may take focus between the focus move and the centre key
             * and turn the press into opening an album.
             */
            override fun openLibrary() {
                environment.proxy.unpark()
                if (!exists("library.surface")) show("library.open")
            }

            override fun openSearch() {
                environment.proxy.unpark()
                show("search.open")
            }

            override fun backFromAlbum() = press("album.back")

            /** The remote's Back, from a browse grid to the library's home. */
            override fun leaveBrowseView() {
                back()
                check(exists("library.surface")) { "Back did not return to the library's home" }
            }

            /** As a remote reaches the list's status lines: Up until focus leaves the cards for the grid's header. */
            override fun showListStatus() {
                repeat(64) {
                    if (focusedAlbum() == null) return
                    key(Key.DirectionUp)
                }
                error("The remote did not leave the album cards; focus is on ${focusedAlbum()}")
            }

            /**
             * As a remote moves through a grid: from the card that has focus (or the first card drawn),
             * Down a row a press and then Right along the row, until the card at [index] has focus;
             * the grid scrolls to keep focus in view. A step that overshoots goes back Up.
             */
            override fun showAlbum(index: Int) {
                if (focusedAlbum() == null) {
                    val first = compose.onAllNodes(albumCard()).fetchSemanticsNodes()
                        .minOf { it.config[SemanticsProperties.TestTag].removePrefix(ALBUM_CARD).toInt() }
                    compose.onNodeWithTag("$ALBUM_CARD$first").performSemanticsAction(SemanticsActions.RequestFocus)
                    compose.waitForIdle()
                }
                var downward = true
                repeat(64) {
                    val focused = checkNotNull(focusedAlbum()) { "no album card has focus" }
                    if (focused == index) return
                    val step = when {
                        focused > index -> Key.DirectionUp.also { downward = false }
                        downward -> Key.DirectionDown
                        else -> Key.DirectionRight
                    }
                    key(step)
                    if (step == Key.DirectionDown && focusedAlbum() == focused) downward = false
                }
                error("The remote did not reach album card $index; focus is on ${focusedAlbum()}")
            }

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

    private fun albumCard() = SemanticsMatcher("an album card") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(ALBUM_CARD)
    }

    /** The album card that has focus, by its index, if one has. */
    private fun focusedAlbum(): Int? = compose.onAllNodes(albumCard()).fetchSemanticsNodes()
        .firstOrNull { it.config.getOrElse(SemanticsProperties.Focused) { false } }
        ?.config?.get(SemanticsProperties.TestTag)?.removePrefix(ALBUM_CARD)?.toInt()

    private companion object {
        val TRACK_ROW = Regex("album\\.track\\.(\\d+)")
        const val ALBUM_CARD = "library.albums.item."
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

    @Test fun aSongsHeartReachesTheServerAndTheFavouritesScreenReadsItBack() =
        scenarios.aSongsHeartReachesTheServerAndTheFavouritesScreenReadsItBack()

    @Test fun conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive() =
        scenarios.conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive()

    @Test fun conf87LookAheadIsBoundedSkipsAConstrainedNetworkAndOpensALookedAheadAlbumWithNoRequest() =
        scenarios.conf87LookAheadIsBoundedSkipsAConstrainedNetworkAndOpensALookedAheadAlbumWithNoRequest()

    @Test fun conf82AScanDuringAPageReadTearsTheWindowAndOnlyTheViewportPagesAreReRead() =
        scenarios.conf82AScanDuringAPageReadTearsTheWindowAndOnlyTheViewportPagesAreReRead()
    @Test fun conf82AWindowSeenUnderAnotherEpochIsRebasedAtItsFirstLiveReadAndNeverExtended() =
        scenarios.conf82AWindowSeenUnderAnotherEpochIsRebasedAtItsFirstLiveReadAndNeverExtended()
    @Test fun conf82WhileTheServerScansPagesAppendUnguardedAndTheScanEndRebasesUnderAnUnchangedStamp() =
        scenarios.conf82WhileTheServerScansPagesAppendUnguardedAndTheScanEndRebasesUnderAnUnchangedStamp()
    @Test fun conf82TheSentinelAndAnAbsentStampAreNoEpochWhateverScanningSaysAndNeverUnchanged() =
        scenarios.conf82TheSentinelAndAnAbsentStampAreNoEpochWhateverScanningSaysAndNeverUnchanged()
    @Test fun conf82AFailedStatusReadIsUnreadAndTheWindowKeepsItsPagesAndLabel() =
        scenarios.conf82AFailedStatusReadIsUnreadAndTheWindowKeepsItsPagesAndLabel()
    @Test fun conf82AWindowWithoutXTotalCountHasAnUnknownTotalAndConfirmsItsEnd() =
        scenarios.conf82AWindowWithoutXTotalCountHasAnUnknownTotalAndConfirmsItsEnd()

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

    /** A playlist selected with the remote plays in its own order from that entry and opens Now Playing. */
    @Test fun aPlaylistSelectedWithTheRemotePlaysInItsOwnOrderAndOpensNowPlaying() =
        scenarios.aPlaylistOpensAndPlaysInItsOwnOrderFromTheEntrySelected {
            val started = assertNotNull(shadowOf(compose.activity).nextStartedActivity, "Now Playing was opened")
            assertEquals(PlaybackIntents.ACTION_SHOW_NOW_PLAYING, started.action)
        }

    // ---- The TV's own navigation, focus and playing (not shared with the phone) -------------------

    /**
     * With the remote only — focus moves and the centre key, no semantic click — the albums and
     * artists screens open, a card opens its album or artist, each detail screen puts focus on Play,
     * and every Back returns focus to the element that opened the screen it leaves.
     */
    @Test fun albumsAndArtistsOpenFromTheRemoteAndBackReturnsFocusToWhatOpenedThem() {
        environment.proxy.unpark()
        // The app opens on the library: the first card takes focus when the rows arrive.
        await("the library at launch") { exists("library.surface") }
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
        // The library is the screen the app opens on: Back from search returns to it.
        show("search.open")
        await("search") { exists("search.query") && !exists("library.surface") }
        back()
        await("Back from search to the library") { exists("library.surface") && !exists("search.query") }
        println("TV NAVIGATION OBSERVED albums=$album artist=$ARTIST artist-album=$second")
    }

    /**
     * Play on an album queues all of its tracks and opens Now Playing; the playing row says so and,
     * selected, opens Now Playing without starting over; the navigation row reaches Now Playing from
     * the library and from search; and Play on an artist queues every album's tracks in the artist's
     * order.
     */
    @Test fun albumAndArtistPlayQueueEveryTrackAndNowPlayingIsReachableFromEveryScreen() {
        environment.proxy.unpark()
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

            if (!exists("library.surface")) show("library.open")  // the launch screen; see openLibrary
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

    /**
     * With the remote only: a track's queue button offers Add to Playlist…, New Playlist… takes a
     * name and Create makes the playlist on the server holding that track; then the album's own
     * queue button adds the whole album to that playlist from the chooser. Each step is read back
     * from the server directly, and both land in the one playlist.
     */
    @Test fun aTrackItsAlbumAndAnotherTrackGoIntoANewPlaylistWithTheRemoteAndTheServerHoldsEach() {
        environment.proxy.unpark()
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val binder = checkNotNull(service.get().onBind(Intent(PlaybackService.LOCAL_BIND))) { "setup: no local binder" }
            shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
                Intent(app, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND),
                ComponentName(app, PlaybackService::class.java),
                binder,
            )
            // The queue button, which carries Add to Playlist, is offered while a playback service is bound.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            checkNotNull(service.get().playback) { "setup: the service has no controller" }

            val server = environment.server
            val songs = server.get("getAlbum", mapOf("id" to server.albumId(ALBUM))).getJSONObject("album").getJSONArray("song")
                .let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
            assertTrue(songs.size >= 2, "setup: a multi-track album")
            val name = ProductionLibraryEnvironment.TEST_PLAYLIST_PREFIX + "tv remote"
            assertTrue(name !in server.playlists().values, "setup: no playlist of that name yet")

            if (!exists("library.surface")) show("library.open")  // the launch screen; see openLibrary
            press("library.view.albums")
            await("the albums screen") { exists("library.albums.item.0") }
            compose.onNodeWithTag("library.albums").performScrollToNode(hasText(ALBUM))
            focus(cardWithText("library.albums.item.", ALBUM))
            key(Key.DirectionCenter)
            await("album $ALBUM open, focus on Play") { titleIs("album.title", ALBUM) && focused("album.play") }

            // The first track: RIGHT along its row to the queue button, then Add to Playlist….
            focus("album.track.0")
            repeat(4) { if (!focused("album.track.0.queue")) key(Key.DirectionRight) }
            assertFocused("album.track.0.queue")
            key(Key.DirectionCenter)
            await("the queue dialog, Play Next focused") { focused("queue.add.playNext") }
            stepTo("queue.add.playlist", Key.DirectionDown, from = "queue.add.playNext")
            keyAt("queue.add.playlist", Key.DirectionCenter)
            await("the chooser, New Playlist… focused") { focused("playlists.add.new") }
            keyAt("playlists.add.new", Key.DirectionCenter)
            await("the name field focused") { focused("playlists.add.name") }
            // The TV's on-screen keyboard is the platform's; the field takes the text it would send.
            compose.onNodeWithTag("playlists.add.name").selectWithRemote().performTextReplacement(name)
            keyAt("playlists.add.name", Key.DirectionDown)
            assertFocused("playlists.add.name.confirm")
            keyAt("playlists.add.name.confirm", Key.DirectionCenter)
            await("the playlist made on the server with the track") {
                server.playlists().entries.singleOrNull { it.value == name }?.let { server.playlistEntries(it.key) } == listOf(songs[0])
            }
            val id = server.playlists().entries.single { it.value == name }.key
            await("the chooser closed") { !exists("playlists.add") }

            // The album, from its header's queue button, into that playlist.
            focus("album.queue")
            key(Key.DirectionCenter)
            await("the album's queue dialog") { focused("queue.add.playNext") }
            stepTo("queue.add.playlist", Key.DirectionDown, from = "queue.add.playNext")
            keyAt("queue.add.playlist", Key.DirectionCenter)
            await("the chooser lists the playlist") { exists("playlists.add.item.0") && runCatching { cardWithText("playlists.add.item.", name) }.isSuccess }
            val row = cardWithText("playlists.add.item.", name)
            stepTo(row, Key.DirectionDown, from = "playlists.add.new")
            keyAt(row, Key.DirectionCenter)
            await("the album appended on the server") { server.playlistEntries(id) == listOf(songs[0]) + songs }
            await("the chooser closed") { !exists("playlists.add") }

            // The second track, from its row, into the same playlist: an append of one song by id.
            focus("album.track.1")
            repeat(4) { if (!focused("album.track.1.queue")) key(Key.DirectionRight) }
            assertFocused("album.track.1.queue")
            key(Key.DirectionCenter)
            await("the second track's queue dialog") { focused("queue.add.playNext") }
            stepTo("queue.add.playlist", Key.DirectionDown, from = "queue.add.playNext")
            keyAt("queue.add.playlist", Key.DirectionCenter)
            await("the chooser lists the playlist again") { runCatching { cardWithText("playlists.add.item.", name) }.isSuccess }
            val again = cardWithText("playlists.add.item.", name)
            stepTo(again, Key.DirectionDown, from = "playlists.add.new")
            keyAt(again, Key.DirectionCenter)
            val expected = listOf(songs[0]) + songs + songs[1]
            await("the second track appended on the server") { server.playlistEntries(id) == expected }
            await("the chooser closed") { !exists("playlists.add") }
            assertEquals(listOf(id), server.playlists().filterValues { it == name }.keys.toList(), "one playlist, every addition in it")
            assertNoCredentialLeak()
            println("TV PLAYLIST ADD OBSERVED album=$ALBUM entries=${expected.size} id-stable=true")
        } finally {
            service.destroy()
        }
    }

    /**
     * With the remote only: a playlist the account owns, made on the server for the run, opens from
     * Library > Playlists and its page offers Delete Playlist…, which asks first with focus on Cancel.
     * Cancel deletes nothing and leaves the page; Delete, reached with Down, removes the playlist on
     * the server, read back directly, and the page goes Back to the grid, which no longer shows it.
     */
    @Test fun aPlaylistIsDeletedWithTheRemoteOnlyAfterItAsksAndCancelKeepsIt() {
        environment.proxy.unpark()
        val server = environment.server
        val songs = server.get("getAlbum", mapOf("id" to server.albumId(ALBUM))).getJSONObject("album").getJSONArray("song")
            .let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
        val name = ProductionLibraryEnvironment.TEST_PLAYLIST_PREFIX + "tv delete"
        val id = server.createPlaylist(name, songs.take(2))
        // A second playlist of the account's own, which deleting the first must leave alone.
        val siblingName = ProductionLibraryEnvironment.TEST_PLAYLIST_PREFIX + "tv delete sibling"
        val sibling = server.createPlaylist(siblingName, songs.take(1))
        assertEquals(name, server.playlists()[id], "setup: the playlist is on the server")

        if (!exists("library.surface")) show("library.open")  // the launch screen; see openLibrary
        press("library.view.playlists")
        await("the playlists grid lists $name") { runCatching { cardWithText("library.playlists.item.", name) }.isSuccess }
        compose.onNodeWithTag("library.playlists").performScrollToNode(hasText(name))
        focus(cardWithText("library.playlists.item.", name))
        key(Key.DirectionCenter)
        await("the page of $name, Delete offered") { titleIs("playlist.title", name) && exists("playlist.delete") }

        // Delete Playlist… asks; Cancel, where focus lands, keeps the playlist.
        await("focus on the page's Play") { focused("playlist.play") }
        stepTo("playlist.delete", Key.DirectionRight, from = "playlist.play")
        key(Key.DirectionCenter)
        await("the question, focus on Cancel") { focused("playlist.delete.cancel") }
        assertTrue(texts("playlist.delete.line").single().contains(name), "the question names the playlist")
        keyAt("playlist.delete.cancel", Key.DirectionCenter)
        await("the question gone") { !exists("playlist.delete.dialog") }
        assertEquals(name, server.playlists()[id], "Cancel deleted nothing")
        assertTrue(titleIs("playlist.title", name), "Cancel leaves the page")
        await("focus back on Delete Playlist…") { focused("playlist.delete") }

        // The remote's Back is Cancel too.
        key(Key.DirectionCenter)
        await("the question, focus on Cancel") { focused("playlist.delete.cancel") }
        compose.runOnIdle { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        await("the question gone after Back") { !exists("playlist.delete.dialog") }
        assertEquals(name, server.playlists()[id], "Back deleted nothing")
        assertTrue(titleIs("playlist.title", name), "Back closed only the question")

        // Delete, reached with Down from Cancel, removes it on the server and goes Back to the grid.
        focus("playlist.delete")
        key(Key.DirectionCenter)
        await("the question again, focus on Cancel") { focused("playlist.delete.cancel") }
        stepTo("playlist.delete.confirm", Key.DirectionDown, from = "playlist.delete.cancel")
        keyAt("playlist.delete.confirm", Key.DirectionCenter)
        await("the playlist gone from the server") { id !in server.playlists() }
        assertEquals(siblingName, server.playlists()[sibling], "only the playlist asked about is deleted")
        await("Back on the grid, without it") {
            !exists("playlist.surface") && exists("library.playlists") &&
                compose.onAllNodes(hasText(name)).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Another user's public playlist opens on the TV read-only: it names its owner and offers Play
     * but no Delete Playlist…, and nothing is written to it. The account's own playlist, opened the
     * same way, offers Delete Playlist… (the control).
     */
    @Test fun anotherUsersPlaylistOffersNoDeleteOnTheTvAndTheOwnPlaylistIsTheControl() {
        environment.proxy.unpark()
        val server = environment.server
        val songs = server.get("getAlbum", mapOf("id" to server.albumId(ALBUM))).getJSONObject("album").getJSONArray("song")
            .let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
        val other = environment.otherUser()
        val othersName = ProductionLibraryEnvironment.TEST_PLAYLIST_PREFIX + "tv others"
        val othersId = other.createPlaylist(othersName, songs.take(2))
        other.makePublic(othersId)
        assertEquals(ProductionLibraryEnvironment.OTHER_USERNAME to true, server.playlistOwnership(othersId),
            "setup: this account sees the other user's playlist as theirs, and public")
        val ownName = ProductionLibraryEnvironment.TEST_PLAYLIST_PREFIX + "tv own"
        server.createPlaylist(ownName, songs.take(1))

        if (!exists("library.surface")) show("library.open")  // the launch screen; see openLibrary
        press("library.view.playlists")
        for (name in listOf(othersName, ownName)) {
            await("the playlists grid lists $name") { runCatching { cardWithText("library.playlists.item.", name) }.isSuccess }
            compose.onNodeWithTag("library.playlists").performScrollToNode(hasText(name))
            focus(cardWithText("library.playlists.item.", name))
            key(Key.DirectionCenter)
            await("the page of $name, Play offered") { titleIs("playlist.title", name) && exists("playlist.play") }
            if (name == othersName) {
                assertEquals(listOf("${ProductionLibraryEnvironment.OTHER_USERNAME}'s playlist · read-only"), texts("playlist.owner"))
                assertFalse(exists("playlist.delete"), "another user's playlist offers Delete Playlist…")
            } else {
                assertTrue(exists("playlist.delete"), "control: the account's own playlist offers Delete Playlist…")
                assertFalse(exists("playlist.owner"), "control: the account's own playlist names no other owner")
            }
            back()
            await("the grid again") { exists("library.playlists") && !exists("playlist.surface") }
        }
        assertEquals(songs.take(2), other.playlistEntries(othersId), "the other user's playlist is unchanged")
    }

    /**
     * With the remote only: the chooser for a track is open when the network goes; the playlist
     * chosen from it is changed at once on its page, which says "Not saved to your server yet", and
     * nothing reaches the server; when the network comes back the reconnect sends the addition, the
     * server holds it, and the page stops saying so.
     */
    @Test fun aTrackAddedAfterTheNetworkWentIsShownPendingOnThePlaylistAndSentAtReconnect() {
        environment.proxy.unpark()
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val binder = checkNotNull(service.get().onBind(Intent(PlaybackService.LOCAL_BIND))) { "setup: no local binder" }
            shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
                Intent(app, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND),
                ComponentName(app, PlaybackService::class.java),
                binder,
            )
            // The queue button, which carries Add to Playlist, is offered while a playback service is bound.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            checkNotNull(service.get().playback) { "setup: the service has no controller" }

            val server = environment.server
            val songs = server.get("getAlbum", mapOf("id" to server.albumId(ALBUM))).getJSONObject("album").getJSONArray("song")
                .let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
            assertTrue(songs.size >= 2, "setup: a multi-track album")
            val name = ProductionLibraryEnvironment.TEST_PLAYLIST_PREFIX + "tv pending"
            val id = server.createPlaylist(name, listOf(songs[0]))

            // The playlist and its page are seen once, so the chooser and the page have them offline.
            if (!exists("library.surface")) show("library.open")  // the launch screen; see openLibrary
            press("library.view.playlists")
            await("the playlists grid lists $name") { runCatching { cardWithText("library.playlists.item.", name) }.isSuccess }
            focus(cardWithText("library.playlists.item.", name))
            key(Key.DirectionCenter)
            await("the page with its one entry") { titleIs("playlist.title", name) && exists("playlist.entry.0") }
            assertFalse(exists("playlist.pending"), "control: nothing is pending before the addition")
            back()
            await("the grid again") { exists("library.playlists") && !exists("playlist.surface") }
            back()
            press("library.view.albums")
            await("the albums screen") { exists("library.albums.item.0") }
            compose.onNodeWithTag("library.albums").performScrollToNode(hasText(ALBUM))
            focus(cardWithText("library.albums.item.", ALBUM))
            key(Key.DirectionCenter)
            await("album $ALBUM open, focus on Play") { titleIs("album.title", ALBUM) && focused("album.play") }

            // The second track's chooser, open while the device still has its network.
            focus("album.track.1")
            repeat(4) { if (!focused("album.track.1.queue")) key(Key.DirectionRight) }
            assertFocused("album.track.1.queue")
            key(Key.DirectionCenter)
            await("the queue dialog, Play Next focused") { focused("queue.add.playNext") }
            stepTo("queue.add.playlist", Key.DirectionDown, from = "queue.add.playNext")
            keyAt("queue.add.playlist", Key.DirectionCenter)
            await("the chooser lists $name") { runCatching { cardWithText("playlists.add.item.", name) }.isSuccess }

            environment.network.lose()
            val row = cardWithText("playlists.add.item.", name)
            stepTo(row, Key.DirectionDown, from = "playlists.add.new")
            keyAt(row, Key.DirectionCenter)
            await("the chooser closed") { !exists("playlists.add") }
            assertEquals(listOf(songs[0]), server.playlistEntries(id), "nothing reaches the server while the network is gone")

            // Its page, by the remote: the addition shown, and said to be unsaved.
            back()
            await("the albums screen again") { exists("library.albums") && !exists("album.title") }
            back()
            press("library.view.playlists")
            await("the playlists grid lists $name") { runCatching { cardWithText("library.playlists.item.", name) }.isSuccess }
            focus(cardWithText("library.playlists.item.", name))
            key(Key.DirectionCenter)
            await("the page with both entries, said pending") {
                titleIs("playlist.title", name) && exists("playlist.entry.1") && exists("playlist.pending")
            }
            assertEquals(listOf("Not saved to your server yet"), texts("playlist.pending"))
            assertEquals(listOf(songs[0]), server.playlistEntries(id), "still nothing on the server")

            environment.network.restore()
            await("the addition sent at the reconnect") { server.playlistEntries(id) == listOf(songs[0], songs[1]) }
            await("the page no longer says it is unsaved") { !exists("playlist.pending") && exists("playlist.entry.1") }
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

    /** The remote's [key] delivered where [tag] is: a dialog is a window of its own, with its own root. */
    private fun keyAt(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /**
     * [key] pressed until [tag] holds focus, as a remote walks a list. Each press goes to the window
     * [from] is in -- a dialog's, not the screen beneath it, whose own focus stays where it was.
     */
    private fun stepTo(tag: String, key: Key, from: String) {
        repeat(12) {
            if (focused(tag)) return
            keyAt(from, key)
        }
        assertFocused(tag)
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
