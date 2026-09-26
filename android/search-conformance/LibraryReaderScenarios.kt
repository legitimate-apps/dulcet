package com.legitimateapps.dulcet.search.conformance

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AndroidLibraryCachedReason
import com.legitimateapps.dulcet.core.AndroidLibraryChangeOutcome
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.AndroidLibrarySeenCounts
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibraryFrame
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibraryObservationState
import com.legitimateapps.dulcet.playback.PlaybackService
import com.legitimateapps.dulcet.search.SearchObservation
import com.legitimateapps.dulcet.search.SearchUiState
import java.time.Duration
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** How a platform's UI is driven; everything else in a scenario is shared, so phone and TV prove the same thing. */
interface ReaderAppUi {
    fun openLibrary()
    fun openSearch()
    fun backFromAlbum()

    /**
     * Selects the element tagged [tag] the way a person does on this platform. [from] is the
     * focusable element just above it, where a D-pad starts; a touch screen ignores it.
     */
    fun select(tag: String, from: String)
}

/**
 * The reader's conformance scenarios (CONF-76, 77, 79, 84, 86), and the session's own reachability
 * and album play beside them, driven through the production app —
 * its activity, its `LibrarySession`, the process's `AndroidLibraryReader` — against the disposable
 * server, counting requests at a loopback forwarder. Each platform's test class calls each scenario
 * from its own test method, so every CONF id has its own test identity per platform.
 *
 * Request counts exclude `getCoverArt`: cover art is fetched by the image pipeline when a cell is
 * drawn, not by the reader, and the reader's budget is what these tests measure. Every scenario
 * prints how many cover-art requests it saw. The image pipeline does not consult reachability, so
 * "the reader requests nothing offline" is what the offline legs prove — not that the app does.
 */
class LibraryReaderScenarios<A : ComponentActivity>(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<A>, A>,
    private val environment: ProductionLibraryEnvironment,
    private val ui: ReaderAppUi,
    private val platform: String,
) {
    private val proxy get() = environment.proxy
    private val server get() = environment.server

    // ---- CONF-76 ----------------------------------------------------------------------------------

    fun conf76CachedPaintBeforeAnyRequestAndOfflineUnavailable() {
        ui.openLibrary()
        awaitHomeLive()
        awaitQuiet()
        val firstLaunchOrder = readerRequests(0).map { it.endpoint }
        val liveCounts = HOME.map { last(it).itemCount }
        assertTrue(liveCounts[0] > 0, "setup: the newest row must have albums")
        val opened = server.albumId(OPENED_ALBUM)
        val neverOpened = server.albumId(NEVER_OPENED_ALBUM)
        // The server's own first track of that album, read directly: the row is checked against it.
        val firstTitle = server.get("getAlbum", mapOf("id" to opened)).getJSONObject("album")
            .getJSONArray("song").getJSONObject(0).getString("title")
        openHomeAlbum(OPENED_ALBUM)
        await("the opened album live") { last("album:$opened").let { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present } }
        val tracks = last("album:$opened").itemCount
        ui.backFromAlbum()
        awaitQuiet()

        // A cold relaunch: the reader's thread is stopped (a new process, as far as the core can tell)
        // and every content read the new one issues is held. The epoch reads are answered, so each
        // row's own read is issued — and is still unanswered when the row paints.
        val relaunchMark = proxy.size()
        proxy.hold { it.endpoint !in EPOCH_READS }
        closeProcessReader()
        compose.activityRule.scenario.recreate()
        ui.openLibrary()
        await("every home row to paint") { HOME.all { frames(it).isNotEmpty() } }
        await("each row's own read to be issued, and held") {
            val held = proxy.since(relaunchMark).filter { !it.answered }
            ROW_READS.all { (endpoint, type) -> held.any { it.endpoint == endpoint && it.parameters["type"] == type } }
        }
        HOME.forEachIndexed { index, key ->
            val first = frames(key).first()
            assertIs<AndroidLibraryFreshness.Cached>(first.freshness, "$key's first publication after relaunch")
            assertEquals(liveCounts[index], first.itemCount, "$key painted what was last seen")
            assertTrue(frames(key).none { it.freshness == AndroidLibraryFreshness.Loading }, "$key published no loading state")
        }
        assertEquals(0, proxy.since(relaunchMark).count { it.answered && it.endpoint !in EPOCH_READS },
            "the paint used no content answer from the server")
        compose.onNodeWithTag("library.home.0.item.0").assertExists()
        compose.onNodeWithTag("library.home.0.freshness").assertTextContains("Showing what you last saw", substring = true)
        openHomeAlbum(OPENED_ALBUM)
        await("the opened album to paint") { frames("album:$opened").isNotEmpty() }
        val album = frames("album:$opened").first()
        assertIs<AndroidLibraryFreshness.Cached>(album.freshness)
        assertEquals(AndroidLibraryItemsState.Present, album.itemsState)
        assertEquals(tracks, album.itemCount)
        assertEquals(0, proxy.since(relaunchMark).count { it.answered && it.endpoint !in EPOCH_READS },
            "the album painted with no content answer either")
        ui.backFromAlbum()

        // Online, an album seen only in a grid paints its cached header at once, with its track list
        // loading — never a whole-screen spinner — while its own read is held.
        val gridOnly = server.albumId(GRID_ONLY_ALBUM)
        openHomeAlbum(GRID_ONLY_ALBUM)
        await("the grid-only album to paint") { frames("album:$gridOnly").isNotEmpty() }
        val header = frames("album:$gridOnly").first()
        assertIs<AndroidLibraryFreshness.Cached>(header.freshness, "a grid-only album's first publication is its cached header")
        assertEquals(gridOnly, header.headerRawId)
        assertEquals(AndroidLibraryItemsState.Loading, header.itemsState, "its track list says it is loading")
        await("its own read to be issued, and held") {
            proxy.since(relaunchMark).any { it.endpoint == "getAlbum" && it.parameters["id"] == gridOnly && !it.answered }
        }
        ui.backFromAlbum()
        awaitQuiet(held = true)
        val heldOrder = readerRequests(relaunchMark).map { it.endpoint }
        proxy.release()
        awaitHomeLive()
        awaitQuiet()
        val relaunchOrder = readerRequests(relaunchMark).map { it.endpoint }

        // Offline, an album this device never opened says so — no spinner, and nothing is asked.
        environment.network.lose()
        await("the home rows to say offline") {
            (last("home.0").freshness as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Offline
        }
        val mark = proxy.size()
        openHomeAlbum(NEVER_OPENED_ALBUM)
        await("the never-opened album to publish") { frames("album:$neverOpened").isNotEmpty() }
        val offline = frames("album:$neverOpened")
        assertTrue(offline.none { it.freshness == AndroidLibraryFreshness.Loading || it.itemsState == AndroidLibraryItemsState.Loading },
            "no loading state offline, for the screen or its track list")
        val final = offline.last()
        // Seen in a grid, so its header is cached; its track list was never read.
        assertTrue((final.freshness as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Offline,
            "the cached header says offline, was ${final.freshness}")
        assertEquals(AndroidLibraryItemsState.Unavailable, final.itemsState)
        assertEquals(AndroidLibraryUnavailableReason.NotCachedOffline, final.itemsUnavailableReason,
            "the track list is unavailable because it was never opened and the device is offline")
        compose.onNodeWithTag("album.tracks.unavailable").assertTextEquals(UNAVAILABLE_ALBUM_COPY)

        // An opened album's tracks are listed offline, and a tap on one that cannot play says why
        // rather than doing nothing (§16.14).
        ui.backFromAlbum()
        openHomeAlbum(OPENED_ALBUM)
        await("the opened album offline, with its tracks") {
            last("album:$opened").let { it.freshness.isOfflineCached() && it.itemsState == AndroidLibraryItemsState.Present }
        }
        // One accessibility node a screen reader can activate, on both platforms: the row's own texts
        // are merged into it, and it has a click action, which says why the track cannot play.
        compose.onNodeWithTag("album.track.0")
            .assertTextContains(firstTitle)
            .assertTextContains(NOT_AVAILABLE_OFFLINE_COPY)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        ui.select("album.track.0", from = "album.favourite")
        compose.onNodeWithTag("album.note").assertTextEquals(PLAYS_ON_RECONNECT_COPY)
        assertEquals(emptyList(), readerRequests(mark).map { it.endpoint }, "the reader requests nothing offline")
        assertNoCredentialLeak()
        println("CONF-76 OBSERVED $platform relaunch-first-publication=cached rows=${liveCounts} album-tracks=$tracks " +
            "answered-before-paint=0 offline-never-opened=${final.freshness::class.simpleName}/${final.itemsState} " +
            "offline-requests=0 cover-art=${coverArt()} first-launch-order=$firstLaunchOrder relaunch-held-order=$heldOrder relaunch-order=$relaunchOrder")
    }

    // ---- CONF-77 ----------------------------------------------------------------------------------

    fun conf77ReconnectFlushesReadsTheEpochRevalidatesAndNothingElse() {
        ui.openLibrary()
        awaitHomeLive()
        val album = server.albumId(OPENED_ALBUM)
        openHomeAlbum(OPENED_ALBUM)
        await("the album live") { last("album:$album").freshness == AndroidLibraryFreshness.Live }
        awaitQuiet()

        environment.network.lose()
        await("the album to say offline") { last("album:$album").freshness.isOfflineCached() }
        val offlineMark = proxy.size()
        compose.onNodeWithTag("album.favourite").performClick()
        await("the star to show offline") { last("album:$album").favourite == true }
        assertEquals(emptyList(), readerRequests(offlineMark).map { it.endpoint }, "a change made offline sends nothing")
        ui.backFromAlbum()
        await("the home rows to say offline") { HOME.all { last(it).freshness.isOfflineCached() } }

        val mark = proxy.size()
        val reconnectsBefore = observed().reconnects
        val framesBefore = HOME.associateWith { frames(it).size }
        // The last visible read is held, so the sequence has a step left after the others are
        // answered. The reader revalidates the rows one after another, so holding an earlier row's
        // read would stall the rows behind it until the request timed out (30 s), and the sequence
        // would end with that row failed instead of still coming.
        val heldRow = { seen: CountingProxy.Seen -> seen.endpoint == "getStarred2" }
        proxy.hold(heldRow)
        environment.network.restore()
        await("every visible read but the held one answered") {
            val since = proxy.since(mark)
            since.any { heldRow(it) && !it.answered } && since.count { it.endpoint == "getAlbumList2" && it.answered } == 3
        }
        awaitQuiet(held = true)
        // Positive, so a row still showing its offline frame cannot pass it: every row says it is
        // revalidating, the three answered ones included, and none says live.
        assertTrue(HOME.all { (last(it).freshness as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Revalidating },
            "every row says revalidating, and none live, while the reconnect still has a read to make: ${HOME.map { last(it).freshness }}")
        proxy.release()
        await("the reconnect to finish") {
            observed().reconnects > reconnectsBefore && observed().connections.last() is LibraryConnectionState.Online &&
                HOME.all { last(it).freshness == AndroidLibraryFreshness.Live }
        }
        awaitQuiet()
        // Each row says `live` exactly once during the reconnect, as its last publication (§16.14);
        // that none said it early was asserted above, while a read was held.
        val reconnectFrames = HOME.associateWith { frames(it).drop(framesBefore.getValue(it)).map { frame -> frame.freshness } }
        for ((row, seen) in reconnectFrames) {
            assertEquals(1, seen.count { it == AndroidLibraryFreshness.Live }, "$row says live once: $seen")
            assertEquals(AndroidLibraryFreshness.Live, seen.last(), "$row says live at the end: $seen")
        }
        val sent = readerRequests(mark)
        val names = sent.map { it.endpoint }
        assertEquals("star", names.firstOrNull(), "the outbox flush goes first: $names")
        assertEquals(1, names.count { it == "star" }, "one change, one send: $names")
        assertEquals(listOf("getMusicFolders", "getScanStatus"), names.subList(1, 3).sorted(), "then the epoch read: $names")
        assertEquals(VISIBLE_HOME_READS, names.drop(3).sorted(), "then the visible screen, and nothing else: $names")
        assertEquals(setOf("newest", "recent", "frequent"),
            sent.filter { it.endpoint == "getAlbumList2" }.mapNotNull { it.parameters["type"] }.toSet())
        assertTrue(server.albumStarred(album),
            "the flushed change reached the server: sent=$sent outcomes=${observed().changeOutcomes}")

        // A reconnect that reaches the server but cannot read the epoch is not "offline": the
        // connection line says why, with "Try again". The reader stays offline until one succeeds,
        // and both a new network and "Try again" are ways back.
        fun failAReconnect() {
            environment.network.lose()
            await("offline again") { observed().connections.last() is LibraryConnectionState.Offline }
            proxy.fail { it.endpoint == "getScanStatus" }
            environment.network.restore()
            await("the reconnect to fail") { observed().connections.last() is LibraryConnectionState.Failed }
            assertTrue((observed().connections.last() as LibraryConnectionState.Failed).readerOffline, "the reader is still offline")
            compose.onNodeWithTag("library.connection").assertTextContains("Couldn't connect to your server — ", substring = true)
            // An answer that is not a Subsonic envelope is not a failure the reader retries by itself,
            // so every screen says what failed rather than "offline" (§16.14), and offers "Try again".
            await("every home row to state the failure") {
                HOME.all { (last(it).freshness as? AndroidLibraryFreshness.Cached)?.reason is AndroidLibraryCachedReason.Failed }
            }
            compose.onNodeWithTag("library.home.0.freshness").assertTextContains("your server couldn't answer", substring = true)
            compose.onNodeWithTag("library.home.0.freshness").assert(!hasText("offline", substring = true))
            compose.onNodeWithTag("library.home.0.retry").assertExists()
            proxy.fail(null)
        }
        failAReconnect()
        val reconnectsBeforeSwitch = observed().reconnects
        environment.network.switchNetwork()
        await("a new network to reconnect") {
            observed().reconnects > reconnectsBeforeSwitch && observed().connections.last() is LibraryConnectionState.Online
        }
        assertTrue(compose.onAllNodesWithTag("library.connection").fetchSemanticsNodes().isEmpty(), "the line goes once connected")
        failAReconnect()
        compose.onNodeWithTag("library.connection.retry").performClick()
        await("trying again to reconnect") { observed().connections.last() is LibraryConnectionState.Online }
        assertTrue(compose.onAllNodesWithTag("library.connection").fetchSemanticsNodes().isEmpty(), "the line goes once connected")
        assertNoCredentialLeak()
        println("CONF-77 OBSERVED $platform reconnect requests=${names.size} flush=1 epoch=2 revalidated=4 " +
            "downloaded-recheck=0 (no downloads in this phase) other=0 order=$names cover-art=${coverArt(mark)}")
    }

    fun conf77ForegroundReturnReadsTheEpochAndTheVisibleScreenOnly() {
        ui.openLibrary()
        awaitHomeLive()
        awaitQuiet()
        val mark = proxy.size()
        val reconnectsBefore = observed().reconnects
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Longer than the retired fifteen-minute import cadence: a stopped app reads nothing.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(16))
        awaitQuiet(composed = false)
        assertEquals(emptyList(), readerRequests(mark).map { it.endpoint }, "nothing is read in the background")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("the foreground reconnect to finish") {
            observed().reconnects > reconnectsBefore && observed().connections.last() is LibraryConnectionState.Online &&
                HOME.all { last(it).freshness == AndroidLibraryFreshness.Live }
        }
        awaitQuiet()
        val names = readerRequests(mark).map { it.endpoint }
        assertEquals((listOf("getMusicFolders", "getScanStatus") + VISIBLE_HOME_READS).sorted(), names.sorted(),
            "the epoch read and the visible screen, nothing else: $names")
        assertNoCredentialLeak()
        println("CONF-77 OBSERVED $platform foreground-return background-requests=0 after-16min requests=${names.size} " +
            "epoch=2 revalidated=4 order=$names cover-art=${coverArt(mark)}")
    }

    /**
     * The core's in-foreground epoch cadence (§16.11 policy 1) runs while the app is in the foreground
     * with a library screen open, reads only the epoch while it is unchanged, and stops in the
     * background. The cadence runs on the reader's own thread in real time, so this test gives the
     * process's next reader a short interval and waits in real time.
     */
    fun conf77EpochCadenceRunsInTheForegroundOnly() {
        AndroidLibraryReader.testEpochIntervalMillis = CADENCE_MILLIS
        try {
            closeProcessReader()
            compose.activityRule.scenario.recreate()
            ui.openLibrary()
            awaitHomeLive()
            // The launch's own reads are over before counting starts; the cadence's go on meanwhile.
            awaitQuiet(ignoring = EPOCH_READS)
            val foregroundMark = proxy.size()
            idleRealTime(CADENCE_MILLIS * 8)
            val ticks = readerRequests(foregroundMark).map { it.endpoint }
            assertTrue(ticks.count { it == "getScanStatus" } >= 3, "control: the cadence ran in the foreground: $ticks")
            assertEquals(setOf("getScanStatus", "getMusicFolders"), ticks.toSet(),
                "an unchanged epoch revalidates nothing: $ticks")

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            awaitQuiet(composed = false)
            val backgroundMark = proxy.size()
            idleRealTime(CADENCE_MILLIS * 6)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(16))
            assertEquals(emptyList(), readerRequests(backgroundMark).map { it.endpoint }, "the cadence stops in the background")
            assertNoCredentialLeak()
            println("CONF-77 OBSERVED $platform cadence interval-ms=$CADENCE_MILLIS foreground-epoch-reads=" +
                "${ticks.count { it == "getScanStatus" }} foreground-other=0 background-requests=0 " +
                "cover-art=${coverArt(foregroundMark)}")
        } finally {
            AndroidLibraryReader.testEpochIntervalMillis = null
        }
    }

    /** Real time passing with the main looper serviced, for work on the reader's own thread. */
    private fun idleRealTime(millis: Long) {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < millis) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    // ---- The session's own reachability ------------------------------------------------------------

    /**
     * A reconnect answered after the platform reported the network gone leaves the library offline.
     * The session hears the answer — its count of reconnect answers moves, the marker that the
     * handling ran — and does not act on it: no second reconnect starts, and nothing more is read.
     */
    fun aReconnectAnsweredAfterTheNetworkWentAwayLeavesTheLibraryOffline() {
        ui.openLibrary()
        awaitHomeLive()
        awaitQuiet()
        environment.network.lose()
        await("the library to say offline") {
            observed().connections.last() is LibraryConnectionState.Offline && HOME.all { last(it).freshness.isOfflineCached() }
        }
        awaitQuiet()

        // The network comes back, and the reconnect it starts is held at its epoch read.
        val mark = proxy.size()
        val reconnectsBefore = observed().reconnects
        proxy.hold { it.endpoint in EPOCH_READS }
        environment.network.restore()
        await("the reconnect's epoch read to be issued, and held") {
            observed().reconnects > reconnectsBefore && proxy.since(mark).any { it.endpoint in EPOCH_READS && !it.answered }
        }
        val reconnects = observed().reconnects
        val answers = observed().reconnectAnswers

        // The network goes again before the reconnect answers.
        val lossMark = proxy.size()
        environment.network.lose()
        await("the reconnect's answer to reach the session") { observed().reconnectAnswers > answers }
        awaitQuiet(held = true)
        assertIs<LibraryConnectionState.Offline>(observed().connections.last(), "offline stands: ${observed().connections}")
        assertEquals(reconnects, observed().reconnects, "the late answer starts no reconnect")
        assertEquals(emptyList(), readerRequests(lossMark).map { it.endpoint }, "nothing is read after the loss")
        assertTrue(HOME.all { last(it).freshness.isOfflineCached() }, "every row still says offline")
        // Measured here, before the clean-up below reconnects.
        val observedLine = "answers-after-loss=${observed().reconnectAnswers - answers} " +
            "reconnects-after-loss=${observed().reconnects - reconnects} requests-after-loss=${readerRequests(lossMark).size}"

        proxy.release()
        environment.network.restore()
        await("the library back online") { observed().connections.last() is LibraryConnectionState.Online }
        assertNoCredentialLeak()
        println("SHELL OBSERVED $platform late-reconnect-answer $observedLine")
    }

    /**
     * A reconnect that finds the server unreachable while the platform reports a network takes the
     * reader offline, and every screen says offline. The reader was online, so the session takes it
     * offline with an unreachable report and then tells it the platform's view again — reachable — so
     * the reader goes on retrying by itself. A return to the foreground and trying again each tell
     * the reader the platform's view before reconnecting, and a reconnect that fails while the reader
     * is already offline tells it nothing.
     */
    fun anUnreachableServerTakesTheReaderOfflineAndTryingAgainTellsItTheNetworkIsBack() {
        ui.openLibrary()
        awaitHomeLive()
        val album = server.albumId(OPENED_ALBUM)
        openHomeAlbum(OPENED_ALBUM)
        await("the album live") { last("album:$album").freshness == AndroidLibraryFreshness.Live }
        awaitQuiet()

        // Every connection is closed unanswered, and the app returns to the foreground.
        proxy.drop { true }
        val mark = proxy.size()
        val reconnectsBefore = observed().reconnects
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("the foreground reconnect to find the server unreachable") {
            observed().reconnects > reconnectsBefore &&
                observed().connections.last() == LibraryConnectionState.Offline(DomainError.Transport.Unreachable)
        }
        await("the album to say offline") { last("album:$album").freshness.isOfflineCached() }
        assertTrue(proxy.since(mark).any { it.endpoint in EPOCH_READS && it.answered && it.status == 0 },
            "control: the reconnect's epoch read reached the proxy and was dropped: ${proxy.since(mark)}")
        assertEquals(listOf(false, true), observed().reachabilityReports.takeLast(2),
            "the online reader was taken offline, then told the platform's view again")

        // Back to the foreground while the server still cannot be reached. The platform reports a
        // network, so the reader is told so before the reconnect; the reconnect found the reader
        // offline, so nothing more is told.
        val beforeReturn = observed().reachabilityReports.size
        val answersBeforeReturn = observed().reconnectAnswers
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("the second foreground reconnect to be answered") { observed().reconnectAnswers > answersBeforeReturn }
        assertEquals(listOf(true), observed().reachabilityReports.drop(beforeReturn),
            "a return to the foreground told the reader the platform's view, and the failed reconnect nothing")

        // "Try again", from the album's refresh, while the server still cannot be reached: the reader
        // is told reachable once, and reconnects.
        val reports = observed().reachabilityReports.size
        val answersBeforeTry = observed().reconnectAnswers
        compose.onNodeWithTag("album.refresh").performClick()
        await("trying again to be answered") { observed().reconnectAnswers > answersBeforeTry }
        val told = observed().reachabilityReports.drop(reports)
        assertEquals(listOf(true), told, "trying again told the reader the platform's view, once")
        assertEquals(LibraryConnectionState.Offline(DomainError.Transport.Unreachable), observed().connections.last())

        // The server answers again, and the library comes back: by the reader's own retry, or by the
        // refresh, whichever comes first.
        proxy.drop(null)
        compose.onNodeWithTag("album.refresh").performClick()
        await("the album live again") {
            observed().connections.last() is LibraryConnectionState.Online && last("album:$album").freshness == AndroidLibraryFreshness.Live
        }
        assertNoCredentialLeak()
        println("SHELL OBSERVED $platform unreachable-server offline=${LibraryConnectionState.Offline(DomainError.Transport.Unreachable)} " +
            "told-after-try-again=$told")
    }

    /**
     * A reconnect whose epoch read fails transiently is retried by the reader itself while the app is
     * in the foreground (§16.14), and not while it is in the background. Every epoch read's
     * connection is closed unanswered — the server unreachable while the platform reports a network —
     * so each attempt shows at the forwarder, and the session's own reconnect count shows that none
     * of the retries is the session's. Once the server answers again, the reader's retry brings the
     * library back with no action from the person, and the session follows it online without a
     * reconnect of its own.
     */
    fun aTransientReconnectFailureIsRetriedInTheForegroundOnly() {
        ui.openLibrary()
        awaitHomeLive()
        awaitQuiet()
        environment.network.lose()
        await("the library to say offline") {
            observed().connections.last() is LibraryConnectionState.Offline && HOME.all { last(it).freshness.isOfflineCached() }
        }
        awaitQuiet()

        // The network returns and the session's reconnect finds the server unreachable.
        proxy.drop { it.endpoint == "getScanStatus" }
        val mark = proxy.size()
        val reconnects = observed().reconnects
        val answers = observed().reconnectAnswers
        environment.network.restore()
        await("the session's reconnect to find the server unreachable") {
            observed().reconnectAnswers > answers &&
                observed().connections.last() == LibraryConnectionState.Offline(DomainError.Transport.Unreachable)
        }
        assertEquals(true, observed().reachabilityReports.last(), "the reader is left told the platform's view")
        val sessionReads = scanStatusReads(mark)
        assertTrue(sessionReads >= 1, "control: the session's reconnect read the epoch: ${proxy.since(mark)}")

        // In the foreground the reader tries again by itself: at least twice.
        await("the reader to retry by itself") { scanStatusReads(mark) >= sessionReads + 2 }
        assertEquals(reconnects + 1, observed().reconnects, "the session started one reconnect; the retries are the reader's")
        assertTrue(HOME.all { last(it).freshness.isOfflineCached() }, "a failure the reader retries says offline")
        assertTrue(proxy.since(mark).filter { it.endpoint == "getScanStatus" }.all { it.status == 0 },
            "control: every epoch read was dropped: ${proxy.since(mark)}")
        val foregroundRetries = scanStatusReads(mark) - sessionReads

        // In the background it stops: nothing for longer than the next wait. (The session is read
        // through the screen, so its counts are taken while the activity is started.)
        val returnAnswers = observed().reconnectAnswers
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitQuiet(composed = false)
        val backgroundMark = proxy.size()
        idleRealTime(BACKGROUND_RETRY_WATCH_MILLIS)
        assertEquals(emptyList(), readerRequests(backgroundMark).map { it.endpoint }, "no retry in the background")

        // Back in the foreground, still unreachable: the session's reconnect fails again, and the
        // reader's retry — its wait reset by the return — succeeds once the server answers.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("the return's reconnect to find the server unreachable") {
            observed().reconnectAnswers > returnAnswers &&
                observed().connections.last() == LibraryConnectionState.Offline(DomainError.Transport.Unreachable)
        }
        val reconnectsBeforeRecovery = observed().reconnects
        val recoveryMark = proxy.size()
        proxy.drop(null)
        await("the reader's own retry to bring the library back") {
            observed().connections.last() is LibraryConnectionState.Online && HOME.all { last(it).freshness == AndroidLibraryFreshness.Live }
        }
        assertTrue(proxy.since(recoveryMark).any { it.endpoint == "getScanStatus" && it.status == 200 },
            "control: an epoch read was answered: ${proxy.since(recoveryMark)}")
        assertEquals(reconnectsBeforeRecovery, observed().reconnects,
            "the session followed the reader's own retry online without a reconnect of its own")
        assertNoCredentialLeak()
        println("SHELL OBSERVED $platform transient-retry session-reconnects=1 foreground-retries=$foregroundRetries " +
            "background-requests=0 background-watch-ms=$BACKGROUND_RETRY_WATCH_MILLIS " +
            "recovered-by-reader-retry=true session-reconnects-on-recovery=0")
    }

    /**
     * A reconnect that found the server unreachable with the reader online takes the reader offline
     * with an unreachable report, and in the foreground tells it the platform's view again, which
     * starts a read. When that answer arrives after the app has left the foreground, the session
     * tells the reader only that the server is unreachable: nothing is read in the background, and
     * the return to the foreground tells the platform's view once.
     */
    fun anUnreachableAnswerArrivingInTheBackgroundStartsNoRead() {
        ui.openLibrary()
        awaitHomeLive()
        awaitQuiet()

        // Leave the foreground, then come back with the return's epoch read held, and later dropped.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitQuiet(composed = false)
        proxy.hold { it.endpoint == "getScanStatus" }
        proxy.drop { it.endpoint == "getScanStatus" }
        val mark = proxy.size()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("the return's epoch read to be issued, and held") {
            proxy.since(mark).any { it.endpoint == "getScanStatus" && !it.answered }
        }
        val reports = observed().reachabilityReports.size
        val answers = observed().reconnectAnswers
        assertIs<LibraryConnectionState.Connecting>(observed().connections.last(), "setup: the return's reconnect is running")

        // The app leaves the foreground, and only then is the read answered: its connection closes.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idle()
        val releaseMark = proxy.size()
        proxy.release()
        val dropped = kotlin.time.TimeSource.Monotonic.markNow()
        while (proxy.since(mark).any { it.endpoint == "getScanStatus" && !it.answered }) {
            check(dropped.elapsedNow().inWholeMilliseconds < WAIT_MILLIS) { "the held read was never answered: ${proxy.since(mark)}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        idleRealTime(BACKGROUND_ANSWER_WATCH_MILLIS)
        assertEquals(emptyList(), readerRequests(releaseMark).map { it.endpoint }, "nothing is read in the background")

        // Back in the foreground: the answer reached the session while it was stopped — the
        // unreachable report it made then is the marker — and the return tells the platform's view once.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("the second return's reconnect to find the server unreachable") {
            observed().reconnectAnswers >= answers + 2 &&
                observed().connections.last() == LibraryConnectionState.Offline(DomainError.Transport.Unreachable)
        }
        val told = observed().reachabilityReports.drop(reports)
        assertEquals(listOf(false, true), told,
            "the background answer told unreachable only; the return told the platform's view once")
        proxy.drop(null)
        await("the library back online") {
            observed().connections.last() is LibraryConnectionState.Online && HOME.all { last(it).freshness == AndroidLibraryFreshness.Live }
        }
        assertNoCredentialLeak()
        println("SHELL OBSERVED $platform background-unreachable-answer told=$told background-requests=0 " +
            "background-watch-ms=$BACKGROUND_ANSWER_WATCH_MILLIS")
    }

    private fun scanStatusReads(mark: Int) = proxy.since(mark).count { it.endpoint == "getScanStatus" }

    /**
     * A playable track selected on an album screen plays the album from that track (§14.1): the
     * production playback service's queue is the album's tracks in the server's order, current at
     * the selected one. [afterPlay] then checks what the platform does next.
     */
    fun aTrackSelectedPlaysTheAlbumFromThatTrack(afterPlay: () -> Unit) {
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

            ui.openLibrary()
            awaitHomeLive()
            val album = server.albumId(OPENED_ALBUM)
            val songs = server.get("getAlbum", mapOf("id" to album)).getJSONObject("album").getJSONArray("song")
            val expected = (0 until songs.length()).map { songs.getJSONObject(it).getString("id") }
            assertTrue(expected.size >= 3, "setup: the album needs a track before and after the one selected")
            openHomeAlbum(OPENED_ALBUM)
            await("the album live, with its tracks") {
                last("album:$album").let { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present }
            }
            ui.select("album.track.1", from = "album.track.0")
            try {
                // The controller runs on the main thread, and its song read resumes there by a message
                // posted from the HTTP client's thread. The host runtime's main looper runs posted
                // messages only when something idles it. The other waits here read the screen through
                // Compose finders, which idle it; this one reads the controller's state directly, so
                // nothing did, and the queue was published in 2 of 8 runs before this. A device's
                // main looper always runs; idling it here is that.
                await("the album to be queued") {
                    shadowOf(Looper.getMainLooper()).idle()
                    playback.state.value.queue.isNotEmpty()
                }
            } catch (failure: AssertionError) {
                // What the controller made of the selection, and what each request was answered with.
                throw AssertionError("${failure.message}; playback=${playback.state.value}; answered=${proxy.log()}", failure)
            }
            val state = playback.state.value
            assertEquals(expected, state.queue.map { it.track.rawId }, "the queue is the album, in the server's order")
            assertEquals(1, state.currentIndex, "playing from the selected track")
            afterPlay()
            assertNoCredentialLeak()
            println("PLAYBACK OBSERVED $platform album-play queue=${state.queue.size} current=${state.currentIndex}")
        } finally {
            service.destroy()
        }
    }

    // ---- CONF-79 ----------------------------------------------------------------------------------

    fun conf79SearchPublishesEachScopeWithSeenCountsOffline() {
        ui.openLibrary()
        awaitHomeLive()
        openHomeAlbum(OPENED_ALBUM)
        await("the album live") { last("album:${server.albumId(OPENED_ALBUM)}").freshness == AndroidLibraryFreshness.Live }
        ui.backFromAlbum()
        ui.openSearch()

        // serverAndDevice: the server answered, and nothing needs saying.
        type("Double")
        await("serverAndDevice") { search().answered == "Double" && search().scope == AndroidLibrarySearchScope.ServerAndDevice }
        assertTrue(search().rows.any { it.source == AndroidLibrarySearchRowSource.Server }, "the server's rows are shown")
        assertTrue(compose.onAllNodesWithTag("search.scope").fetchSemanticsNodes().isEmpty(), "no scope line when the server answered")

        // deviceWhileServerPending: the device's rows at once, labelled, while the server is asked.
        proxy.hold { it.endpoint == "search3" }
        val answeredBefore = proxy.answered("search3")
        type("Lines")
        await("deviceWhileServerPending") { search().answered == "Lines" && search().scope == AndroidLibrarySearchScope.DeviceWhileServerPending }
        assertTrue(search().rows.isNotEmpty() && search().rows.all { it.source == AndroidLibrarySearchRowSource.Device })
        compose.onNodeWithTag("search.scope").assertTextEquals("On this device")
        assertEquals(answeredBefore, proxy.answered("search3"), "the server's answer is held")
        proxy.release()
        await("the held query answered") { search().answered == "Lines" && search().scope == AndroidLibrarySearchScope.ServerAndDevice }

        // deviceServerFailed: the device's rows stay, and the line says the server failed.
        proxy.fail { it.endpoint == "search3" }
        type("Doub")
        await("deviceServerFailed") { search().answered == "Doub" && search().scope is AndroidLibrarySearchScope.DeviceServerFailed }
        val failed = search().scope as AndroidLibrarySearchScope.DeviceServerFailed
        assertEquals(seenCounts(), failed.seen)
        compose.onNodeWithTag("search.scope").assertTextContains("On this device — ", substring = true)
        proxy.fail(null)

        // deviceOffline: the seen-cache's own counts, stated.
        environment.network.lose()
        val mark = proxy.size()
        type("Thresh")
        await("deviceOffline") { search().answered == "Thresh" && search().scope is AndroidLibrarySearchScope.DeviceOffline }
        val counts = seenCounts()
        assertEquals(AndroidLibrarySearchScope.DeviceOffline(counts), search().scope)
        assertTrue(counts.albums > 0 && counts.tracks > 0, "control: the device has seen albums and tracks")
        compose.onNodeWithTag("search.scope").assertTextEquals(
            "Searching what's available offline — ${counts.albums} albums and ${counts.tracks} tracks on this device")
        assertEquals(emptyList(), readerRequests(mark).map { it.endpoint }, "an offline search asks the reader's server nothing")
        assertNoCredentialLeak()
        println("CONF-79 OBSERVED $platform scopes=serverAndDevice,deviceWhileServerPending,deviceServerFailed,deviceOffline " +
            "offline-counts=${counts.artists}/${counts.albums}/${counts.tracks} offline-requests=0 offline-cover-art=${coverArt(mark)}")
    }

    // ---- CONF-84 ----------------------------------------------------------------------------------

    fun conf84StarShowsWithTheTapSurvivesARevalidationAndAdoptsTheEcho() {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        ui.openLibrary()
        awaitHomeLive()
        val album = server.albumId(OPENED_ALBUM)
        openHomeAlbum(OPENED_ALBUM)
        await("the album live") { last("album:$album").let { it.freshness == AndroidLibraryFreshness.Live && it.favourite != true } }
        awaitQuiet()

        proxy.hold { it.endpoint == "star" }
        val before = frames("album:$album").size
        compose.onNodeWithTag("album.favourite").performClick()
        await("a publication after the tap") { frames("album:$album").size > before }
        assertEquals(true, frames("album:$album")[before].favourite, "the first publication after the tap shows the star")
        assertEquals(0, proxy.answered("star"), "before the send was answered")

        // A revalidation that lands before the send completes reads the server's old value.
        val reads = proxy.answered("getAlbum")
        compose.onNodeWithTag("album.refresh").performClick()
        await("the revalidation to land") { proxy.answered("getAlbum") > reads && last("album:$album").freshness == AndroidLibraryFreshness.Live }
        assertEquals(false, server.albumStarred(album), "the server had not been told when the revalidation read it")
        assertEquals(0, proxy.answered("star"), "the send was still held")
        assertTrue(frames("album:$album").drop(before).all { it.favourite == true }, "no publication after the tap dropped the star")

        // The send completes. Its acknowledgement is the echo (§16.20): the sent value is written into
        // the cache and the pending change is removed, so the star stays with no read in between.
        val readsBeforeSend = proxy.answered("getAlbum")
        proxy.release()
        await("the change to be saved") { observed().changeOutcomes.any { it is AndroidLibraryChangeOutcome.Saved && it.target.rawId == album } }
        awaitQuiet()
        assertEquals(readsBeforeSend, proxy.answered("getAlbum"), "setup: no read has landed since the send")
        assertTrue(server.albumStarred(album))
        assertEquals(1, databaseLong("SELECT starred FROM cache_album WHERE raw_id = ?", album), "the acknowledgement was adopted into the cache")
        assertEquals(0, databaseLong("SELECT count(*) FROM mutation_outbox"), "nothing is pending")
        assertEquals(true, last("album:$album").favourite, "the star stands on the adopted value alone")
        assertTrue(frames("album:$album").drop(before).all { it.favourite == true }, "no publication since the tap dropped the star")
        // And a later read agrees with it.
        val echoed = proxy.answered("getAlbum")
        compose.onNodeWithTag("album.refresh").performClick()
        await("the echo read") { proxy.answered("getAlbum") > echoed && last("album:$album").freshness == AndroidLibraryFreshness.Live }
        assertEquals(true, last("album:$album").favourite)

        // And back, through the app.
        compose.onNodeWithTag("album.favourite").performClick()
        await("the star removed") { last("album:$album").favourite != true }
        await("the removal saved") {
            observed().changeOutcomes.count { it is AndroidLibraryChangeOutcome.Saved && it.target.rawId == album } >= 2
        }
        assertEquals(false, server.albumStarred(album))

        // Compaction: three taps while offline are one change, and one send when back.
        environment.network.lose()
        await("the album to say offline") { last("album:$album").freshness.isOfflineCached() }
        val beforeTaps = frames("album:$album").size
        repeat(3) { compose.onNodeWithTag("album.favourite").performClick(); compose.waitForIdle() }
        await("each of the three taps to show") {
            frames("album:$album").drop(beforeTaps).mapNotNull { it.favourite }.containsInOrder(listOf(true, false, true)) &&
                last("album:$album").favourite == true
        }
        assertEquals(1, databaseLong("SELECT count(*) FROM mutation_outbox"), "three taps are one pending change")
        val compactMark = proxy.size()
        val savedBefore = observed().changeOutcomes.count { it is AndroidLibraryChangeOutcome.Saved && it.target.rawId == album }
        environment.network.restore()
        await("the compacted change saved") {
            observed().changeOutcomes.count { it is AndroidLibraryChangeOutcome.Saved && it.target.rawId == album } > savedBefore
        }
        awaitQuiet()
        val sends = readerRequests(compactMark).map { it.endpoint }.filter { it == "star" || it == "unstar" }
        assertEquals(listOf("star"), sends, "only the last value is sent")
        assertTrue(server.albumStarred(album))
        assertNoCredentialLeak()
        println("CONF-84 OBSERVED $platform same-publication-as-tap=true survived-revalidation-before-send=true " +
            "echo-adopted-without-a-read=true outbox-after=0 compacted-3-taps-to=$sends " +
            "body-ms=${started.elapsedNow().inWholeMilliseconds} cover-art=${coverArt()}")
    }

    // ---- CONF-86 ----------------------------------------------------------------------------------

    fun conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive() {
        proxy.fail { it.endpoint == "getStarred2" }
        proxy.hold { it.endpoint == "getAlbumList2" && it.parameters["type"] == "frequent" }
        ui.openLibrary()
        await("rows 0 and 1 live and row 3 failed, while row 2 is held") {
            last("home.0").freshness == AndroidLibraryFreshness.Live && last("home.1").freshness == AndroidLibraryFreshness.Live &&
                last("home.3").freshness.isFailed()
        }
        assertEquals(0, proxy.log().count { it.endpoint == "getAlbumList2" && it.parameters["type"] == "frequent" && it.answered },
            "row 2's answer had not arrived")
        assertTrue(proxy.log().any { it.endpoint == "getAlbumList2" && it.parameters["type"] == "frequent" }, "control: row 2 did ask")
        assertEquals(AndroidLibraryFreshness.Loading, last("home.2").freshness, "row 2 still says it is loading")
        compose.onNodeWithTag("library.home.0.item.0").assertExists()
        compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasTestTag("library.home.0")))
            .performScrollToNode(hasTestTag("library.home.3.unavailable"))
        compose.onNodeWithTag("library.home.3.unavailable").assertTextContains("Couldn't load this — ", substring = true)
        proxy.release()
        await("row 2 live") { last("home.2").freshness == AndroidLibraryFreshness.Live }
        assertEquals(AndroidLibraryFreshness.Live, last("home.0").freshness)
        assertEquals(AndroidLibraryFreshness.Live, last("home.1").freshness)
        assertTrue(last("home.3").freshness.isFailed(), "the failed row stays failed on its own")
        proxy.fail(null)
        assertNoCredentialLeak()
        println("CONF-86 OBSERVED $platform rows-live-before-held-row=0,1 failed-row=3 other-rows-live=true " +
            "row-sequences=${HOME.map { frames(it).size }} cover-art=${coverArt()}")
    }

    // ---- Instruments ------------------------------------------------------------------------------

    /** Credentials ride in the query string; nothing the app logged may carry them (CLAUDE.md trap 12). */
    private fun assertNoCredentialLeak() {
        val logged = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        for (canary in listOf(ProductionLibraryEnvironment.USERNAME, ProductionLibraryEnvironment.PASSWORD)) {
            assertTrue(!logged.contains(canary), "A credential reached the log")
        }
        assertTrue(proxy.log().none { entry -> entry.parameters.values.any { it.contains(ProductionLibraryEnvironment.PASSWORD) } })
    }

    private fun observed(): LibraryObservationState =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(LibraryObservation)).fetchSemanticsNodes()
            .firstOrNull()?.config?.get(LibraryObservation) ?: LibraryObservationState()

    private fun frames(key: String): List<LibraryFrame> = observed().surfaces[key].orEmpty()

    private fun last(key: String): LibraryFrame = frames(key).lastOrNull() ?: fail("$key has published nothing")

    private fun search(): SearchUiState =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SearchObservation)).fetchSemanticsNodes()
            .firstOrNull()?.config?.get(SearchObservation) ?: SearchUiState()

    private fun type(text: String) {
        compose.onNodeWithTag("search.query").performTextReplacement(text)
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(WAIT_MILLIS) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; requests=${proxy.log().map { it.endpoint }}", timeout)
        }
    }

    private fun awaitHomeLive() = await("every home row live") {
        HOME.all { frames(it).lastOrNull()?.freshness == AndroidLibraryFreshness.Live }
    }

    /**
     * Nothing in flight, and nothing new for 20 polls: counts taken after this are complete. With
     * [held], requests the proxy is holding may stay open; requests to [ignoring] are not counted.
     */
    private fun awaitQuiet(composed: Boolean = true, held: Boolean = false, ignoring: Set<String> = emptySet()) {
        var stable = 0
        var size = -1
        repeat(2_000) {
            if (composed) compose.waitForIdle() else shadowOf(Looper.getMainLooper()).idle()
            val counted = proxy.log().filter { it.endpoint !in ignoring }
            val now = counted.size
            if ((held || counted.none { !it.answered }) && now == size) stable++ else stable = 0
            size = now
            if (stable >= 20) return
            Thread.sleep(25)
        }
        fail("The app never went quiet; requests=${proxy.log().map { it.endpoint }}")
    }

    private fun openHomeAlbum(title: String) {
        val row = hasTestTag("library.home.0.items")
        compose.onNode(row).performScrollToNode(hasText(title))
        compose.onAllNodes(hasText(title) and hasAnyAncestor(row)).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun readerRequests(mark: Int = 0) = proxy.since(mark).filter { it.endpoint != "getCoverArt" }

    private fun coverArt(mark: Int = 0) = proxy.since(mark).count { it.endpoint == "getCoverArt" }

    private fun seenCounts(): AndroidLibrarySeenCounts = AndroidLibrarySeenCounts(
        artists = databaseLong("SELECT count(*) FROM cache_artist WHERE server_id = ? AND gone = 0", serverId()),
        albums = databaseLong("SELECT count(*) FROM cache_album WHERE server_id = ? AND gone = 0", serverId()),
        tracks = databaseLong("SELECT count(*) FROM cache_track WHERE server_id = ? AND gone = 0 AND metadata_missing = 0", serverId()),
    )

    private fun serverId(): String =
        checkNotNull(AndroidAccountCredentialStore(RuntimeEnvironment.getApplication()).load()).id

    /** A read of the app's own database file, beside the reader: an instrument the app does not draw. */
    private fun databaseLong(sql: String, vararg arguments: String): Long {
        val app = RuntimeEnvironment.getApplication()
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            app.getDatabasePath("dulcet.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            return database.rawQuery(sql, arguments).use { cursor ->
                check(cursor.moveToFirst()) { "no row" }
                cursor.getLong(0)
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        val HOME = listOf("home.0", "home.1", "home.2", "home.3")
        val VISIBLE_HOME_READS = listOf("getAlbumList2", "getAlbumList2", "getAlbumList2", "getStarred2")
        val EPOCH_READS = setOf("getScanStatus", "getMusicFolders")
        /** Each home row's own read: the endpoint and, for an album list, its type. */
        val ROW_READS = listOf("getAlbumList2" to "newest", "getAlbumList2" to "recent", "getAlbumList2" to "frequent",
            "getStarred2" to null)
        const val OPENED_ALBUM = "Double Lines"
        const val NEVER_OPENED_ALBUM = "Threshold Boundary"
        /** Seen in the home row, never opened, and never read by CONF-76's offline leg. */
        const val GRID_ONLY_ALBUM = "Paging Atlas"
        const val CADENCE_MILLIS = 400L

        /**
         * Longer than the reader's next retry wait after two retries (8 s: 2 s doubling, ASSUMED
         * figures, §16.14), so a retry the background failed to stop would land inside it.
         */
        const val BACKGROUND_RETRY_WATCH_MILLIS = 12_000L

        /**
         * Watched from the held read's close, not from the session handling the answer: long enough
         * for a read started at once by that answer to show. The reports assertion after it does not
         * depend on this length.
         */
        const val BACKGROUND_ANSWER_WATCH_MILLIS = 3_000L
        const val UNAVAILABLE_ALBUM_COPY = "You haven't opened this album on this device. Connect to your server to see it."
        const val PLAYS_ON_RECONNECT_COPY = "Not downloaded. It'll play when you reconnect."
        const val NOT_AVAILABLE_OFFLINE_COPY = "Not available offline"
        const val WAIT_MILLIS = 60_000L
    }
}

/** Whether [expected] occurs in this list in order, other elements allowed between. */
private fun <T> List<T>.containsInOrder(expected: List<T>): Boolean {
    var next = 0
    for (element in this) if (next < expected.size && element == expected[next]) next++
    return next == expected.size
}

private fun AndroidLibraryFreshness.isOfflineCached(): Boolean =
    (this as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Offline

private fun AndroidLibraryFreshness.isFailed(): Boolean =
    (this as? AndroidLibraryFreshness.Unavailable)?.reason is AndroidLibraryUnavailableReason.Failed ||
        (this as? AndroidLibraryFreshness.Cached)?.reason is AndroidLibraryCachedReason.Failed

/** The disposable fixture's album id for [name], read from the server itself. */
fun DisposableServer.albumId(name: String): String {
    val albums = get("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500"))
        .getJSONObject("albumList2").getJSONArray("album")
    for (index in 0 until albums.length()) {
        val album = albums.getJSONObject(index)
        if (album.getString("name") == name) return album.getString("id")
    }
    error("The disposable fixture has no album named $name")
}
