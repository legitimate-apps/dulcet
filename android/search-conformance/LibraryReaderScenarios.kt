package com.legitimateapps.dulcet.search.conformance

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import com.legitimateapps.dulcet.core.AndroidLibraryCoverage
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.AndroidLibrarySeenCounts
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibraryFrame
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibraryObservationState
import com.legitimateapps.dulcet.playback.PlaybackService
import com.legitimateapps.dulcet.search.SearchObservation
import com.legitimateapps.dulcet.search.SearchUiState
import java.time.Duration
import org.json.JSONObject
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

    /**
     * Activates a button or card the way a person does on this platform: a tap on a touch screen; on
     * a TV, which has no touch, focus on it and the remote's centre key.
     */
    fun activate(node: SemanticsNodeInteraction) {
        node.performClick()
    }

    /** Leaves a browse view (the albums grid) for the library's home, the way a person does on this platform. */
    fun leaveBrowseView()

    /**
     * Brings the albums grid's card at [index] (already drawn or within reach) on screen the way a
     * person scrolls on this platform; the grid reports its new viewport as it moves.
     */
    fun showAlbum(index: Int)

    /** Brings the albums grid's status lines (freshness, coverage, "Try again") on screen: the grid's top. */
    fun showListStatus()
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
        // A new process opened on a saved account sends nothing until the person chooses Reconnect
        // (spec §13.1, CONF-10b); the rows painted from the device alone.
        assertEquals(emptyList(), proxy.since(relaunchMark).map { it.endpoint }, "the relaunch sent nothing before Reconnect")
        ui.activate(compose.onNodeWithTag("library.reconnect"))
        // Reconnect reads the epoch and revalidates the screen (§16.11): the rows on it issue their
        // reads, which are held, so what is shown next is still the device's.
        await("the shown rows' reads to be issued after Reconnect, and held") {
            val held = proxy.since(relaunchMark).filter { !it.answered }
            ROW_READS.any { (endpoint, type) -> held.any { it.endpoint == endpoint && it.parameters["type"] == type } }
        }
        HOME.forEachIndexed { index, key ->
            val first = frames(key).first()
            assertIs<AndroidLibraryFreshness.Cached>(first.freshness, "$key's first publication after relaunch")
            assertEquals(liveCounts[index], first.itemCount, "$key painted what was last seen")
            assertTrue(frames(key).none { it.freshness == AndroidLibraryFreshness.Loading }, "$key published no loading state")
        }
        assertEquals(0, proxy.since(relaunchMark).count { it.answered && it.endpoint !in EPOCH_READS },
            "the paint used no content answer from the server")
        // Painted items, wherever the row was scrolled: a relaunch may restore the row where it was left.
        assertTrue(compose.onAllNodes(SemanticsMatcher("a first-row card") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("library.home.0.item.")
        }).fetchSemanticsNodes().isNotEmpty(), "the first row painted its cached items")
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
        val mark = proxy.log().size
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
        ui.activate(compose.onNodeWithTag("album.favourite"))
        await("the star to show offline") { last("album:$album").favourite == true }
        assertEquals(emptyList(), readerRequests(offlineMark).map { it.endpoint }, "a change made offline sends nothing")
        ui.backFromAlbum()
        await("the home rows to say offline") { HOME.all { last(it).freshness.isOfflineCached() } }

        val mark = proxy.log().size
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
        ui.activate(compose.onNodeWithTag("library.connection.retry"))
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
        val mark = proxy.log().size
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
            closeProcessReaderAndReconnect()
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
        val mark = proxy.log().size
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
        val mark = proxy.log().size
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
        ui.activate(compose.onNodeWithTag("album.refresh"))
        await("trying again to be answered") { observed().reconnectAnswers > answersBeforeTry }
        val told = observed().reachabilityReports.drop(reports)
        assertEquals(listOf(true), told, "trying again told the reader the platform's view, once")
        assertEquals(LibraryConnectionState.Offline(DomainError.Transport.Unreachable), observed().connections.last())

        // The server answers again, and the library comes back: by the reader's own retry, or by the
        // refresh, whichever comes first.
        proxy.drop(null)
        ui.activate(compose.onNodeWithTag("album.refresh"))
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
        val mark = proxy.log().size
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
        val mark = proxy.log().size
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
        // The core ends this wait after LibrarySearchConfig.serverAnswerDeadlineMillis (8 s) with
        // deviceServerFailed(timeout), so the hold is released within a few seconds of the first assertion.
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
        val mark = proxy.log().size
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
        ui.activate(compose.onNodeWithTag("album.favourite"))
        await("a publication after the tap") { frames("album:$album").size > before }
        assertEquals(true, frames("album:$album")[before].favourite, "the first publication after the tap shows the star")
        assertEquals(0, proxy.answered("star"), "before the send was answered")

        // A revalidation that lands before the send completes reads the server's old value.
        val reads = proxy.answered("getAlbum")
        ui.activate(compose.onNodeWithTag("album.refresh"))
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
        ui.activate(compose.onNodeWithTag("album.refresh"))
        await("the echo read") { proxy.answered("getAlbum") > echoed && last("album:$album").freshness == AndroidLibraryFreshness.Live }
        assertEquals(true, last("album:$album").favourite)

        // And back, through the app.
        ui.activate(compose.onNodeWithTag("album.favourite"))
        await("the star removed") { last("album:$album").favourite != true }
        await("the removal saved") {
            observed().changeOutcomes.count { it is AndroidLibraryChangeOutcome.Saved && it.target.rawId == album } >= 2
        }
        assertEquals(false, server.albumStarred(album))

        // Compaction: three taps while offline are one change, and one send when back.
        environment.network.lose()
        await("the album to say offline") { last("album:$album").freshness.isOfflineCached() }
        val beforeTaps = frames("album:$album").size
        repeat(3) { ui.activate(compose.onNodeWithTag("album.favourite")); compose.waitForIdle() }
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

    // ---- Favourites beyond albums ------------------------------------------------------------------

    /**
     * A song's heart in an album goes to the server as `star` for that song, the server holds it,
     * and the Favourites screen reads it back from the server (`getStarred2`) with its heart filled;
     * taken off there, the server lets it go and the row stays, hollow, until the list is read again.
     */
    fun aSongsHeartReachesTheServerAndTheFavouritesScreenReadsItBack() {
        ui.openLibrary()
        awaitHomeLive()
        val album = server.albumId(OPENED_ALBUM)
        val song = server.get("getAlbum", mapOf("id" to album)).getJSONObject("album").getJSONArray("song").getJSONObject(0)
        val songId = song.getString("id")
        val title = song.getString("title")
        assertEquals(false, server.songStarred(songId), "setup: the run starts with no favourites")
        openHomeAlbum(OPENED_ALBUM)
        await("the album live with its tracks") {
            last("album:$album").let { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present }
        }
        awaitQuiet()
        val song0 = AndroidLibraryEntity(AndroidLibraryEntityKind.Track, songId)
        fun saved() = observed().changeOutcomes.count { it is AndroidLibraryChangeOutcome.Saved && it.target == song0 }
        val mark = proxy.log().size
        assertEquals(false, selected("album.track.0.favourite"), "setup: the song's heart starts hollow")
        ui.activate(compose.onNodeWithTag("album.track.0.favourite"))
        await("the song's heart to fill") { selected("album.track.0.favourite") }
        await("the song's star saved") { saved() == 1 }
        awaitQuiet()
        val sends = readerRequests(mark).filter { it.endpoint == "star" || it.endpoint == "unstar" }
        assertEquals(listOf("star" to songId), sends.map { it.endpoint to it.parameters["id"] }, "one star, for that song")
        assertTrue(server.songStarred(songId), "the server holds the song's favourite")
        assertEquals(false, server.albumStarred(album), "and not the album's: the heart was the song's")
        assertEquals(true, selected("album.track.0.favourite"), "the saved heart stays filled")

        // The list's membership comes only from the server's `getStarred2`: the song can appear in it
        // only from a read made after the star. The home's own favourites row, re-opened on the way
        // back, may be the read that brings it, which the screen then shows as it is (same list).
        ui.backFromAlbum()
        ui.activate(compose.onNodeWithTag("library.view.favourites"))
        await("the favourites live") { frames("favourites").lastOrNull()?.freshness == AndroidLibraryFreshness.Live }
        await("the song on the favourites screen") { exists("library.favourites.track.0") }
        val starredReads = readerRequests(mark).filter { it.endpoint == "getStarred2" }
        assertTrue(starredReads.isNotEmpty(), "the list was read from the server after the star: ${readerRequests(mark).map { it.endpoint }}")
        compose.onNodeWithTag("library.favourites.track.0").assertTextContains(title, substring = true)
        assertEquals(true, selected("library.favourites.track.0.favourite"))

        ui.activate(compose.onNodeWithTag("library.favourites.track.0.favourite"))
        await("the heart emptied") { !selected("library.favourites.track.0.favourite") }
        await("the removal saved") { saved() == 2 }
        assertEquals(false, server.songStarred(songId), "the server let it go")
        assertTrue(exists("library.favourites.track.0"), "the row stays until the list is read again, so it can be put back")
        assertNoCredentialLeak()
        println("FAVOURITES OBSERVED $platform song-star-sent=1 server-starred=true getStarred2-after-star=${starredReads.size} " +
            "unstar-from-list=true cover-art=${coverArt()}")
    }

    /**
     * CONF-84's rating overlay through the app: a song's rating set from its row shows in the album's
     * first publication after the tap, stands through the send, reaches the server as one `setRating`
     * for that song — read back from the server's own `userRating` — and the star already shown, set
     * again, sends 0, which the server takes as no rating. [rate] sets [star] stars on the row tagged
     * with the given prefix the way a person does on this platform.
     */
    fun conf84RatingShowsWithTheTapReachesTheServerAndZeroClearsIt(rate: (row: String, star: Int) -> Unit) {
        ui.openLibrary()
        awaitHomeLive()
        val album = server.albumId(OPENED_ALBUM)
        val songId = server.get("getAlbum", mapOf("id" to album)).getJSONObject("album").getJSONArray("song").getJSONObject(0).getString("id")
        assertEquals(0, server.songRating(songId), "setup: the run starts with the song unrated")
        openHomeAlbum(OPENED_ALBUM)
        await("the album live with its tracks") {
            last("album:$album").let { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present }
        }
        awaitQuiet()
        assertEquals(songId, last("album:$album").itemRawIds.first(), "setup: the first row is the song read from the server")
        val song0 = AndroidLibraryEntity(AndroidLibraryEntityKind.Track, songId)
        fun saved(value: Int) = observed().changeOutcomes.count {
            it is AndroidLibraryChangeOutcome.Saved && it.target == song0 && it.value == value
        }
        val before = frames("album:$album").size
        val mark = proxy.log().size
        rate("album.track.0", 4)
        await("a publication after the tap") { frames("album:$album").size > before }
        assertEquals(4, frames("album:$album")[before].itemRatings.first(), "the first publication after the tap shows the rating")
        await("the rating saved") { saved(4) == 1 }
        awaitQuiet()
        val sends = readerRequests(mark).filter { it.endpoint == "setRating" }
        assertEquals(listOf(songId to "4"), sends.map { it.parameters["id"] to it.parameters["rating"] }, "one setRating, for that song")
        assertEquals(4, server.songRating(songId), "the server holds the rating")
        assertTrue(frames("album:$album").drop(before).all { it.itemRatings.first() == 4 }, "no publication since the tap dropped the rating")

        rate("album.track.0", 4)
        await("the rating removed") { last("album:$album").itemRatings.first() == 0 }
        await("the removal saved") { saved(0) == 1 }
        awaitQuiet()
        assertEquals("0", readerRequests(mark).last { it.endpoint == "setRating" }.parameters["rating"], "the shown star sends 0")
        assertEquals(0, server.songRating(songId), "the server let the rating go")
        assertNoCredentialLeak()
        println("CONF-84 RATING OBSERVED $platform first-publication-after-tap=4 server-userRating=4 " +
            "cleared-by-zero=true setRating-sent=${readerRequests(mark).count { it.endpoint == "setRating" }}")
    }

    // ---- Playlists (§18.6) and lyrics (§18.4) ------------------------------------------------------

    /**
     * A playlist made on the server by another client opens from Library > Playlists and plays in
     * ITS order — duplicates kept, not the album's — from the entry selected: the second of two
     * entries for the same song starts there, not at the first.
     */
    fun aPlaylistOpensAndPlaysInItsOwnOrderFromTheEntrySelected(afterPlay: () -> Unit) = withPlaybackService { playback ->
        val album = server.get("getAlbum", mapOf("id" to server.albumId(OPENED_ALBUM))).getJSONObject("album").getJSONArray("song")
        val songs = (0 until album.length()).map { album.getJSONObject(it).getString("id") }
        assertTrue(songs.size >= 3, "setup: the album needs three songs")
        // Not the album's order, and one song twice: only the playlist's own order can produce this.
        val order = listOf(songs[2], songs[0], songs[2], songs[1])
        val id = server.createPlaylist(PLAYLIST_ORDER, order)
        assertEquals(order, server.playlistEntries(id), "setup: the server holds the order as made")

        ui.openLibrary()
        awaitHomeLive()
        ui.activate(compose.onNodeWithTag("library.view.playlists"))
        // The TV's browse grids carry no observation, so the list is waited on by what it draws.
        await("the playlist listed") { listed("library.playlists.item", PLAYLIST_ORDER) != null }
        ui.activate(compose.onNodeWithTag(listed("library.playlists.item", PLAYLIST_ORDER)!!))
        await("the playlist live with its entries") {
            frames("playlist:$id").lastOrNull()?.let { it.freshness == AndroidLibraryFreshness.Live && it.itemRawIds == order } == true
        }
        compose.onNodeWithTag("playlist.title").assertTextEquals(PLAYLIST_ORDER)
        ui.select("playlist.entry.2", from = "playlist.entry.1")
        await("the playlist to be queued") {
            shadowOf(Looper.getMainLooper()).idle()
            playback.state.value.queue.isNotEmpty()
        }
        val state = playback.state.value
        assertEquals(order, state.queue.map { it.track.rawId }, "the queue is the playlist, in its own order, duplicates kept")
        assertEquals(2, state.currentIndex, "playing from the entry selected, the second of the song's two entries")
        afterPlay()
        assertNoCredentialLeak()
        println("PLAYLIST OBSERVED $platform play-order=playlist queue=${state.queue.size} current=${state.currentIndex}")
    }

    /**
     * The phone's whole edit path against the server, each step read back from the server directly:
     * create, add an album and a song from its menu, move, remove, rename, delete. Every write is one
     * of the three playlist writes, and the last of them is followed by a read of that playlist.
     */
    fun aPlaylistIsMadeFilledReorderedTrimmedRenamedAndDeletedOnTheServer() {
        val albumId = server.albumId(OPENED_ALBUM)
        val album = server.get("getAlbum", mapOf("id" to albumId)).getJSONObject("album").getJSONArray("song")
        val songs = (0 until album.length()).map { album.getJSONObject(it).getString("id") }
        assertTrue(songs.size >= 3, "setup: the album needs three songs")
        assertTrue(server.playlists().values.none { it.startsWith(ProductionLibraryEnvironment.TEST_PLAYLIST_PREFIX) },
            "setup: no test playlist is left from an earlier run")
        ui.openLibrary()
        awaitHomeLive()
        val mark = proxy.log().size

        // Create, from the list: the page opens on it at once and follows it to the server's id.
        ui.activate(compose.onNodeWithTag("library.view.playlists"))
        await("the playlists live") { frames("playlists").lastOrNull()?.freshness == AndroidLibraryFreshness.Live }
        compose.onNodeWithTag("library.playlists.new").performClick()
        compose.onNodeWithTag("library.playlists.name").performTextReplacement(PLAYLIST_EDIT)
        compose.onNodeWithTag("library.playlists.name.confirm").performClick()
        await("the playlist made on the server") { server.playlists().containsValue(PLAYLIST_EDIT) }
        val id = server.playlists().entries.single { it.value == PLAYLIST_EDIT }.key
        await("the page on the server's id, live") {
            frames("playlist:$id").lastOrNull()?.freshness == AndroidLibraryFreshness.Live && exists("playlist.title")
        }
        assertEquals(emptyList(), server.playlistEntries(id))
        assertTrue(observed().playlistOutcomes.any { it is AndroidPlaylistOutcome.Created && it.playlistId == id },
            "the create's outcome named the server's id")
        compose.onNodeWithTag("playlist.back").performClick()
        compose.waitForIdle()

        // Add the album, from its header, then one song from its row's menu.
        ui.activate(compose.onNodeWithTag("library.view.home"))
        openHomeAlbum(OPENED_ALBUM)
        await("the album live with its tracks") {
            last("album:$albumId").let { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present }
        }
        compose.onNodeWithTag("album.addToPlaylist").performClick()
        await("the sheet lists the playlist") { listed("playlists.add.item", PLAYLIST_EDIT) != null }
        compose.onNodeWithTag(listed("playlists.add.item", PLAYLIST_EDIT)!!).performClick()
        await("the album on the server") { server.playlistEntries(id) == songs }
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onNodeWithTag("album.track.0.menu.addToPlaylist").performClick()
        await("the sheet lists the playlist") { listed("playlists.add.item", PLAYLIST_EDIT) != null }
        compose.onNodeWithTag(listed("playlists.add.item", PLAYLIST_EDIT)!!).performClick()
        val filled = songs + songs[0]
        await("the song appended on the server") { server.playlistEntries(id) == filled }
        ui.backFromAlbum()

        // Reorder and remove on the page, in edit mode.
        ui.activate(compose.onNodeWithTag("library.view.playlists"))
        await("the playlist listed") { listed("library.playlists.item", PLAYLIST_EDIT) != null }
        ui.activate(compose.onNodeWithTag(listed("library.playlists.item", PLAYLIST_EDIT)!!))
        await("the page live with every entry") { frames("playlist:$id").lastOrNull()?.itemRawIds == filled }
        compose.onNodeWithTag("playlist.edit").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction() and hasTestTag("playlist.entries")).performScrollToNode(hasTestTag("playlist.entry.0.down"))
        compose.onNodeWithTag("playlist.entry.0.down").performClick()
        val moved = listOf(filled[1], filled[0]) + filled.drop(2)
        await("the move on the server") { server.playlistEntries(id) == moved }
        await("the page showing it") { frames("playlist:$id").lastOrNull()?.itemRawIds == moved }
        val last = moved.lastIndex
        compose.onNode(hasScrollToNodeAction() and hasTestTag("playlist.entries")).performScrollToNode(hasTestTag("playlist.entry.$last.remove"))
        compose.onNodeWithTag("playlist.entry.$last.remove").performClick()
        val trimmed = moved.dropLast(1)
        await("the removal on the server") { server.playlistEntries(id) == trimmed }
        await("the page showing it") { frames("playlist:$id").lastOrNull()?.itemRawIds == trimmed }

        // Rename, then delete.
        compose.onNodeWithTag("playlist.menu").performClick()
        compose.onNodeWithTag("playlist.rename").performClick()
        compose.onNodeWithTag("playlist.name").performTextReplacement(PLAYLIST_RENAMED)
        compose.onNodeWithTag("playlist.name.confirm").performClick()
        await("the new name on the server") { server.playlists()[id] == PLAYLIST_RENAMED }
        await("the page showing it") { runCatching { compose.onNodeWithTag("playlist.title").assertTextEquals(PLAYLIST_RENAMED) }.isSuccess }
        compose.onNodeWithTag("playlist.menu").performClick()
        compose.onNodeWithTag("playlist.delete").performClick()
        compose.onNodeWithTag("playlist.delete.confirm").performClick()
        await("the playlist gone from the server") { id !in server.playlists() }
        try {
            await("the page closed, back on the list") { !exists("playlist.surface") && exists("library.playlists") }
        } catch (failure: AssertionError) {
            val note = compose.onAllNodesWithTag("playlist.note").fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            throw AssertionError("${failure.message}; page=${exists("playlist.surface")} note=$note outcomes=${observed().playlistOutcomes}", failure)
        }
        awaitQuiet()

        val writes = readerRequests(mark).filter { it.endpoint in PLAYLIST_WRITES }
        assertTrue(writes.isNotEmpty(), "control: the edits were sent")
        val sent = readerRequests(mark)
        val endpoints = sent.map { it.endpoint }
        // Each write but the delete is read back before the next write is sent (§18.6: a send is at
        // least once, and only the server's answer to a read says what the playlist now holds).
        sent.forEachIndexed { index, request ->
            if (request.endpoint in PLAYLIST_WRITES && request.endpoint != "deletePlaylist") {
                val rest = endpoints.drop(index + 1)
                val nextWrite = rest.indexOfFirst { it in PLAYLIST_WRITES }.let { if (it < 0) rest.size else it }
                assertTrue("getPlaylist" in rest.take(nextWrite), "the ${request.endpoint} at $index was read back: $endpoints")
            }
        }
        // The only create that is not a replacement is the first; a reorder replaces the entries of THIS playlist.
        val creates = sent.filter { it.endpoint == "createPlaylist" }
        assertEquals(null, creates.first().parameters["playlistId"], "the first create makes the playlist")
        assertTrue(creates.drop(1).all { it.parameters["playlistId"] == id }, "every later create replaces this playlist: $creates")
        assertEquals(1, sent.count { it.endpoint == "deletePlaylist" && it.parameters["id"] == id }, "one delete, of this playlist")
        assertTrue(observed().playlistOutcomes.none { it !is AndroidPlaylistOutcome.Saved && it !is AndroidPlaylistOutcome.Created },
            "no edit ended any other way: ${observed().playlistOutcomes}")
        assertNoCredentialLeak()
        println("PLAYLIST EDIT OBSERVED $platform writes=${writes.groupingBy { it.endpoint }.eachCount()} " +
            "outcomes=${observed().playlistOutcomes.size} create+album+song+move+remove+rename+delete=server-read-back")
    }

    /**
     * CONF-90 through the phone's playlist page (§18.6): a removal tapped on a view that another client
     * has since changed on the server is refused — nothing written, no song removed, the page saying
     * so and showing the server's version — and the same removal on the now-current view (the
     * control) removes exactly the song intended.
     */
    fun conf90ARemovalOnAViewChangedElsewhereIsRefusedUnwrittenAndTheControlRemovesTheIntendedSong() {
        val songs = albumSongs(OPENED_ALBUM)
        assertTrue(songs.size >= 4, "setup: the album needs four songs")
        val made = songs.take(4)
        val id = server.createPlaylist(PLAYLIST_STALE, made)
        ui.openLibrary()
        awaitHomeLive()
        openPlaylist(PLAYLIST_STALE)
        await("the page live with every entry") { frames("playlist:$id").lastOrNull()?.let { it.freshness == AndroidLibraryFreshness.Live && it.itemRawIds == made } == true }
        compose.onNodeWithTag("playlist.edit").performClick()
        compose.waitForIdle()

        // Another client removes the first song. The page still shows four entries; the third of them
        // is the song meant, and the third of the server's list is now a different one.
        val elsewhere = made.drop(1)
        server.replacePlaylistEntries(id, elsewhere)
        assertEquals(elsewhere, server.playlistEntries(id), "setup: the other client's change is on the server")
        assertEquals(made, frames("playlist:$id").last().itemRawIds, "setup: the page still shows the view as it was")
        val meant = made[2]
        assertTrue(elsewhere[2] != meant, "setup: the stale position names a different song on the server")
        val mark = proxy.size()
        removeEntry(2)
        await("the change refused as made elsewhere") {
            observed().playlistOutcomes.any { it is AndroidPlaylistOutcome.ChangedElsewhere && it.playlistId == id }
        }
        await("the page showing the server's version") { frames("playlist:$id").lastOrNull()?.itemRawIds == elsewhere }
        awaitQuiet()
        val refusedWrites = readerRequests(mark).filter { it.endpoint in PLAYLIST_WRITES }
        assertEquals(emptyList(), refusedWrites.map { it.endpoint }, "nothing was written over the changed list")
        assertEquals(elsewhere, server.playlistEntries(id), "no song was removed")
        await("the page saying why") { exists("playlist.outcome") }
        compose.onNodeWithTag("playlist.outcome").assertTextEquals(CHANGED_ELSEWHERE_COPY)

        // The control: the same song removed from the view the server now holds.
        if (!exists("playlist.entry.0.remove")) {
            compose.onNodeWithTag("playlist.edit").performClick()
            compose.waitForIdle()
        }
        val controlMark = proxy.size()
        val position = elsewhere.indexOf(meant)
        removeEntry(position)
        val expected = elsewhere.filterIndexed { index, _ -> index != position }
        await("the removal on the server") { server.playlistEntries(id) == expected }
        await("the page showing it") { frames("playlist:$id").lastOrNull()?.itemRawIds == expected }
        awaitQuiet()
        assertEquals(1, observed().playlistOutcomes.count { it is AndroidPlaylistOutcome.ChangedElsewhere && it.playlistId == id },
            "the control was not refused: ${observed().playlistOutcomes}")
        assertTrue(observed().playlistOutcomes.any { it is AndroidPlaylistOutcome.Saved && it.playlistId == id },
            "the control was saved: ${observed().playlistOutcomes}")
        val controlWrites = readerRequests(controlMark).filter { it.endpoint in PLAYLIST_WRITES }
        assertTrue(controlWrites.isNotEmpty(), "control: the removal was sent")
        assertNoCredentialLeak()
        println("CONF-90 OBSERVED $platform view=${made.size} server-after-other-client=${elsewhere.size} refused-writes=0 " +
            "server-unchanged=true outcome=ChangedElsewhere control-writes=${controlWrites.map { it.endpoint }} " +
            "control-removed=meant-song-only")
    }

    /**
     * CONF-91 through the phone (§18.6, §10.4): another user's public playlist, seen by this
     * account — the fixture's admin, which the server would let edit it — opens read-only: the page
     * names its owner, offers no edit, rename or delete, and the add-to-playlist sheet does not list
     * it; nothing is written and the server's playlist is unchanged. The account's own playlist is
     * the control: its page offers editing and the sheet lists it.
     */
    fun conf91AnotherUsersPlaylistIsReadOnlyInTheAppAndTheOwnPlaylistIsTheControl() {
        val songs = albumSongs(OPENED_ALBUM)
        val other = environment.otherUser()
        val othersId = other.createPlaylist(PLAYLIST_OTHERS, songs.take(2))
        other.makePublic(othersId)
        assertEquals(ProductionLibraryEnvironment.OTHER_USERNAME to true, server.playlistOwnership(othersId),
            "setup: this account sees the other user's playlist as theirs, and public")
        val ownId = server.createPlaylist(PLAYLIST_OWN, songs.take(1))
        val mark = proxy.size()
        ui.openLibrary()
        awaitHomeLive()

        openPlaylist(PLAYLIST_OTHERS)
        await("the other user's page live") {
            frames("playlist:$othersId").lastOrNull()?.let { it.freshness == AndroidLibraryFreshness.Live && it.itemRawIds == songs.take(2) } == true
        }
        compose.onNodeWithTag("playlist.owner").assertTextEquals("${ProductionLibraryEnvironment.OTHER_USERNAME}'s playlist · read-only")
        for (control in listOf("playlist.edit", "playlist.menu", "playlist.entry.0.remove", "playlist.entry.0.down")) {
            assertTrue(!exists(control), "another user's playlist offers $control")
        }
        compose.onNodeWithTag("playlist.back").performClick()
        compose.waitForIdle()

        // The control: this account's own playlist offers editing.
        openPlaylist(PLAYLIST_OWN)
        await("the own page live") { frames("playlist:$ownId").lastOrNull()?.freshness == AndroidLibraryFreshness.Live && exists("playlist.title") }
        assertTrue(exists("playlist.edit") && exists("playlist.menu"), "control: the account's own playlist offers editing")
        assertTrue(!exists("playlist.owner"), "control: the account's own playlist names no other owner")
        compose.onNodeWithTag("playlist.back").performClick()
        compose.waitForIdle()

        // The add-to-playlist sheet lists only playlists this account may change.
        ui.activate(compose.onNodeWithTag("library.view.home"))
        openHomeAlbum(OPENED_ALBUM)
        await("the album live") { exists("album.addToPlaylist") }
        compose.onNodeWithTag("album.addToPlaylist").performClick()
        await("the sheet lists the own playlist") { listed("playlists.add.item", PLAYLIST_OWN) != null }
        assertEquals(null, listed("playlists.add.item", PLAYLIST_OTHERS), "the sheet offers another user's playlist")
        awaitQuiet()
        assertEquals(emptyList(), readerRequests(mark).filter { it.endpoint in PLAYLIST_WRITES }.map { it.endpoint },
            "nothing was written")
        assertEquals(songs.take(2), other.playlistEntries(othersId), "the other user's playlist is unchanged")
        assertNoCredentialLeak()
        println("CONF-91 OBSERVED $platform others-public-readonly-page=true owner-line=true edit-controls=0 " +
            "sheet-lists-others=false own-control-editable=true writes=0 server-unchanged=true")
    }

    private fun albumSongs(title: String): List<String> {
        val album = server.get("getAlbum", mapOf("id" to server.albumId(title))).getJSONObject("album").getJSONArray("song")
        return (0 until album.length()).map { album.getJSONObject(it).getString("id") }
    }

    private fun openPlaylist(name: String) {
        ui.activate(compose.onNodeWithTag("library.view.playlists"))
        await("$name listed") { listed("library.playlists.item", name) != null }
        ui.activate(compose.onNodeWithTag(listed("library.playlists.item", name)!!))
    }

    private fun removeEntry(position: Int) {
        compose.onNode(hasScrollToNodeAction() and hasTestTag("playlist.entries"))
            .performScrollToNode(hasTestTag("playlist.entry.$position.remove"))
        compose.onNodeWithTag("playlist.entry.$position.remove").performClick()
        compose.waitForIdle()
    }

    /**
     * A track with synced lyrics in three languages plays, and Now Playing's lyrics show the
     * server's English layer — the core's choice for this device — synced, read live through the
     * endpoint the server advertises; a track with none says so.
     */
    fun theLyricsOfThePlayingTrackAreReadLiveAndShownSynced(openLyrics: () -> Unit) = withPlaybackService { playback ->
        val lyricsSong = server.songId(LYRICS_TRACK)
        val albumId = server.get("getSong", mapOf("id" to lyricsSong)).getJSONObject("song").getString("albumId")
        val tracks = server.get("getAlbum", mapOf("id" to albumId)).getJSONObject("album").getJSONArray("song")
        val position = (0 until tracks.length()).first { tracks.getJSONObject(it).getString("id") == lyricsSong }
        ui.openLibrary()
        awaitHomeLive()
        openHomeAlbum(server.get("getSong", mapOf("id" to lyricsSong)).getJSONObject("song").getString("album"))
        await("the album live with its tracks") {
            last("album:$albumId").let { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present }
        }
        compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasTestTag("album.track.$position")))
            .performScrollToNode(hasTestTag("album.track.$position"))
        val mark = proxy.log().size
        ui.activate(compose.onNodeWithTag("album.track.$position"))
        await("the track to be current") {
            shadowOf(Looper.getMainLooper()).idle()
            playback.state.value.let { state -> state.queue.getOrNull(state.currentIndex ?: -1)?.track?.rawId == lyricsSong }
        }
        openLyrics()
        await("the lyrics read live") {
            observed().lyrics.lastOrNull()?.let { it.trackRawId == lyricsSong && it.freshness == AndroidLibraryFreshness.Live } == true
        }
        val shown = observed().lyrics.last()
        assertEquals(com.legitimateapps.dulcet.core.AndroidLyricsState.Lyrics, shown.state)
        assertTrue(shown.synced, "the synced layer")
        assertEquals("eng", shown.language, "the English layer, for this device's language")
        await("the lines drawn") { exists("lyrics.line.0") || exists("tv.player.lyrics.line.0") }
        val line = if (exists("lyrics.line.0")) "lyrics.line.0" else "tv.player.lyrics.line.0"
        compose.onNodeWithTag(line).assertTextEquals("Dulcet English line one")
        val reads = readerRequests(mark).map { it.endpoint to it.parameters["id"] }
        assertTrue(("getLyricsBySongId" to lyricsSong) in reads, "read through the advertised extension: $reads")
        assertEquals(1, reads.count { it.first == "getOpenSubsonicExtensions" }, "the extension list was read once: $reads")
        assertNoCredentialLeak()
        println("LYRICS OBSERVED $platform synced=${shown.synced} language=${shown.language} lines=${shown.lineCount} " +
            "endpoint=getLyricsBySongId extensions-reads=1")
    }

    private fun <T> withPlaybackService(block: (com.legitimateapps.dulcet.core.AndroidPlaybackController) -> T): T {
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val binder = checkNotNull(service.get().onBind(Intent(PlaybackService.LOCAL_BIND))) { "setup: no local binder" }
            shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
                Intent(app, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND),
                ComponentName(app, PlaybackService::class.java),
                binder,
            )
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            val playback = checkNotNull(service.get().playback) { "setup: the service has no controller" }
            return block(playback)
        } finally {
            service.destroy()
        }
    }

    private fun selected(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    /**
     * The tag of the row under [prefix] (`<prefix>.N`) that names [name], or null: the server may hold
     * other playlists, so a row is found by what it says, never by where it happens to be.
     */
    private fun listed(prefix: String, name: String): String? =
        compose.onAllNodes(hasText(name, substring = true) and SemanticsMatcher("tag under $prefix") { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" }.matches(Regex(Regex.escape(prefix) + "\\.\\d+"))
        }, useUnmergedTree = false).fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.TestTag)

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

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

    // ---- CONF-87 ----------------------------------------------------------------------------------

    /**
     * Detail look-ahead (§16.13) through the albums grid: the grid reports its viewport, the reader
     * reads album details around it. On a constrained network a settled grid reads none; on an
     * unconstrained one, with every `getAlbum` held, exactly two are in flight and no third starts
     * however long the grid stays still; released, every album is read at most once and no more than
     * the per-viewport cap; and an album read ahead opens with no request at all.
     *
     * The fixture has eight albums, all inside one look-ahead region, so the 24-per-viewport cap is
     * not reached here and a fling cannot be made; those two bounds are the core's evidence
     * (`DetailLookAheadTest`), not this test's.
     */
    fun conf87LookAheadIsBoundedSkipsAConstrainedNetworkAndOpensALookedAheadAlbumWithNoRequest() {
        ui.openLibrary()
        awaitHomeLive()
        awaitQuiet()
        val network = environment.network

        // A constrained network: the grid settles and nothing is read ahead. (Robolectric's default
        // network is metered, so each leg reports its constraint explicitly rather than inheriting it.)
        network.constrain(true)
        val constrainedMark = proxy.size()
        ui.activate(compose.onNodeWithTag("library.view.albums"))
        await("the albums grid live") { albumsGrid()?.let { last(it).freshness == AndroidLibraryFreshness.Live } == true }
        val grid = checkNotNull(albumsGrid())
        val albums = last(grid).itemRawIds
        assertTrue(albums.size >= 3, "setup: the grid must hold more albums than may be in flight, had ${albums.size}")
        idleRealTime(LOOK_AHEAD_STILL_MILLIS)
        awaitQuiet()
        assertEquals(emptyList(), detailReads(constrainedMark), "a settled grid on a constrained network read details ahead")
        ui.leaveBrowseView()
        awaitQuiet()
        network.constrain(false)
        awaitQuiet()

        // Unconstrained, every detail read held: two in flight, and no third while they are.
        proxy.hold { it.endpoint == "getAlbum" }
        val mark = proxy.size()
        ui.activate(compose.onNodeWithTag("library.view.albums"))
        // A finder, so the main looper and the frames that report the viewport run as the wait polls.
        await("the albums grid drawn") { exists("library.albums.item.0") }
        await("two look-ahead reads in flight") { detailReads(mark).size >= 2 }
        idleRealTime(LOOK_AHEAD_STILL_MILLIS)
        val held = detailReads(mark)
        assertEquals(2, held.size, "look-ahead reads in flight while two were unanswered: $held")
        assertTrue(proxy.since(mark).none { it.endpoint == "getAlbum" && it.answered }, "setup: the held reads stayed unanswered")
        proxy.release()
        awaitQuiet()
        val fetched = detailReads(mark)
        assertEquals(fetched.size, fetched.toSet().size, "an album was read ahead twice: $fetched")
        assertTrue(fetched.size in 3..LOOK_AHEAD_MAX_PER_VIEWPORT, "look-ahead read ${fetched.size} albums")
        assertTrue(albums.containsAll(fetched), "look-ahead read albums the grid does not hold: $fetched")

        // An album read ahead opens with no request, live and complete in its first publication.
        val index = albums.indexOfFirst { it in fetched }
        val opened = albums[index]
        val openMark = proxy.size()
        ui.activate(compose.onNodeWithTag("library.albums.item.$index"))
        await("the looked-ahead album published") { frames("album:$opened").isNotEmpty() }
        awaitQuiet()
        assertEquals(emptyList(), readerRequests(openMark).map { it.endpoint }, "opening a looked-ahead album issued a request")
        val published = frames("album:$opened")
        assertTrue(published.all { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present },
            "a looked-ahead album opens live with its tracks, never loading: ${published.map { it.freshness to it.itemsState }}")
        assertTrue(exists("album.track.0"), "the album's first track is drawn")
        assertNoCredentialLeak()
        println("CONF-87 OBSERVED $platform grid-albums=${albums.size} constrained-reads=0 held-in-flight=${held.size} " +
            "read-ahead=${fetched.size} cap=$LOOK_AHEAD_MAX_PER_VIEWPORT opened-index=$index open-requests=0 " +
            "album-publications=${published.size} cover-art=${coverArt()}")
    }

    /** The albums grid's surface key, whichever order it is in. */
    private fun albumsGrid(): String? = observed().surfaces.keys.firstOrNull { it.startsWith("albums") }

    /** The `getAlbum` reads issued since [mark], by album id. */
    private fun detailReads(mark: Int): List<String> =
        proxy.since(mark).filter { it.endpoint == "getAlbum" }.map { it.parameters.getValue("id") }

    // ---- CONF-82 ----------------------------------------------------------------------------------
    //
    // The window rules of §16.12 through the albums grid. The fixture has eight albums, so the process's
    // reader asks for [WINDOW_PAGE] row per page (a test-only seam, null in the app): the albums are a
    // list of eight pages, the grid holds more of them than are on screen, and every rule below runs
    // in the production reader exactly as it does at the app's 100 rows. A scan is a real scan of the
    // disposable server; only a scanning server, a server reporting no stamp or another library, a
    // failed status read and a missing X-Total-Count are presented by the forwarder, each named.

    /**
     * Tear rule 1 (§16.12), the rebase bounded to the viewport, and the anchor. The grid scrolls to its
     * end, the page it then asks for is held at the forwarder, and the server runs a real scan before
     * the page is answered, so the page's *after* reading carries a new stamp: the check fires, the page
     * is never appended, and only the pages on screen are re-read, under the new stamp. The album first
     * on screen stays first, by id. The rebase starts the window below the top, so the screen's own
     * scrolling then reads the rest under the check, and every album appears exactly once.
     */
    fun conf82AScanDuringAPageReadTearsTheWindowAndOnlyTheViewportPagesAreReRead() = withWindowPages {
        val grid = openPagedAlbums()
        val type = windowReads(0).last().parameters.getValue("type")
        val ids = server.albumIds(type)
        val opened = last(grid)
        val end = opened.itemCount
        assertEquals(AndroidLibraryCoverage.Open, opened.coverage, "setup: the grid holds part of the list")
        assertTrue(end in 2 until ids.size, "setup: the grid must hold part of the list, so it asks for more: $end of ${ids.size}")
        assertEquals(ids.take(end), opened.itemRawIds, "setup: the window is the server's list from its top")

        proxy.hold { it.endpoint == "getAlbumList2" && it.parameters["offset"] == end.toString() }
        val mark = proxy.size()
        ui.showAlbum(end - 1)
        await("the next page asked for, and held") { windowReads(mark).any { it.offset() == end && !it.answered } }
        awaitQuiet(held = true)
        val viewport = viewportPages(grid)
        val firstShown = firstShownAlbum(grid)
        val stampBefore = server.scanStatus().getString("lastScan")
        val stampAfter = server.scan()
        assertTrue(stampAfter != stampBefore, "setup: the scan moved the stamp")
        val releaseMark = proxy.size()
        val framesBefore = frames(grid).size
        proxy.release()
        await("the window rebased") { frames(grid).drop(framesBefore).any { it.anchorRawId != null } }
        awaitQuiet()

        val after = readerRequests(releaseMark)
        assertEquals("getScanStatus", after.first().endpoint, "the held page's after reading comes first: $after")
        assertTrue(proxy.log().single { it.endpoint == "getAlbumList2" && it.parameters["offset"] == end.toString() && it.sequence < releaseMark }.answered,
            "setup: the held page was answered")
        val rebaseReads = windowReads(releaseMark).take(viewport.size).map { it.offset() }
        assertEquals(viewport, rebaseReads.sorted(), "the rebase re-reads the pages on screen, and only those: $after")
        val rebased = frames(grid).drop(framesBefore).first { it.anchorRawId != null }
        val from = viewport.first()
        val expected = ids.subList(from, minOf(viewport.last() + WINDOW_PAGE, ids.size))
        assertEquals(expected, rebased.itemRawIds, "the rebased window is the viewport's pages, re-read; the torn page is not in it")
        assertEquals(from, rebased.leadingOffset, "the rebased window starts at the viewport's first page")
        assertEquals(firstShown, rebased.anchorRawId, "the core keeps the album first on screen first, by id")
        // The re-read viewport is shorter than the screen here, so the grid settles at its top and reads
        // the pages above (loadBefore) beneath its header: the anchored album stays on screen, by id.
        assertTrue(firstShown in visibleAlbums().map { last(grid).itemRawIds[it] }, "the screen still shows the anchored album")

        val final = last(grid)
        // The person's own "load more" torn by the scan is not retried by the core: it is owed to the
        // next revalidation, unless the screen asks again because the rebase changed what it shows.
        assertTrue(final.freshness == AndroidLibraryFreshness.Live ||
            (final.freshness as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Owed,
            "the rebased window is live, or owes the torn page to the next revalidation: ${final.freshness}")
        assertTrue(final.coverage == AndroidLibraryCoverage.Open || final.coverage == AndroidLibraryCoverage.Complete,
            "the window is guarded under the new stamp: ${final.coverage}")
        assertEquals(ids.subList(final.leadingOffset, final.leadingOffset + final.itemCount), final.itemRawIds,
            "the window is a run of the server's list, every album once")
        val dropped = (0 until end step WINDOW_PAGE).filter { it !in viewport }
        assertNoCredentialLeak()
        println("CONF-82 OBSERVED $platform tear=fired-check page=$WINDOW_PAGE held-offset=$end stamp-moved=true " +
            "viewport-pages=$viewport loaded-pages-off-screen=$dropped rebase-reads=$rebaseReads anchor=kept " +
            "rebased-rows=${rebased.itemCount} leading-offset=${rebased.leadingOffset} after=${after.map { it.toString() }} cover-art=${coverArt(releaseMark)}")
    }

    /**
     * Tear rules 2 and 3 (§16.12), and their control. The grid is left holding more pages than are on
     * screen, then the app is relaunched three times. With nothing changed, its first live read
     * revalidates the pages on screen in place and the window keeps every page. After a real scan (a
     * stored-epoch mismatch) and after the server reports another library for the account (a
     * folder-set change, stamp unchanged), the first live read is the viewport's pages under the new
     * epoch, the page below the screen is dropped, and the window is never extended before that read.
     */
    fun conf82AWindowSeenUnderAnotherEpochIsRebasedAtItsFirstLiveReadAndNeverExtended() = withWindowPages {
        val grid = openPagedAlbums()
        val type = windowReads(0).last().parameters.getValue("type")
        val ids = server.albumIds(type)
        val held = last(grid).itemCount
        val viewport = viewportPages(grid)
        val below = (0 until held step WINDOW_PAGE).filter { it !in viewport }
        assertTrue(below.isNotEmpty(), "setup: the grid must hold a page that is not on screen: holds $held, shows $viewport")
        ui.leaveBrowseView()
        awaitQuiet()

        var left = held
        fun relaunch(what: String): Pair<List<Int>, LibraryFrame> {
            val mark = proxy.size()
            relaunchIntoAlbums()
            val albums = checkNotNull(albumsGrid())
            val first = frames(albums).first()
            assertIs<AndroidLibraryFreshness.Cached>(first.freshness, "$what: the cached window paints first")
            assertEquals(left, first.itemCount, "$what: the cached window paints whole")
            val live = frames(albums).first { it.freshness == AndroidLibraryFreshness.Live }
            return windowReads(mark).map { it.offset() } to live
        }

        val (unchangedReads, unchanged) = relaunch("unchanged")
        assertTrue(unchangedReads.isNotEmpty() && unchangedReads.first() in viewport,
            "control: an unchanged epoch first revalidates on-screen pages in place (one read in the last minute is not re-read): $unchangedReads")
        assertEquals(ids.take(held), unchanged.itemRawIds, "control: an unchanged epoch keeps every page of the window")
        left = last(checkNotNull(albumsGrid())).itemCount
        ui.leaveBrowseView()
        awaitQuiet()

        val stampBefore = server.scanStatus().getString("lastScan")
        server.scan()
        val (scannedReads, scanned) = relaunch("after a scan")
        assertEquals(viewport, scannedReads.take(viewport.size).sorted(),
            "after a scan the first live read is the viewport's pages, never an extension: $scannedReads")
        assertTrue(scanned.itemCount in 1..viewport.size * WINDOW_PAGE && scanned.itemCount < left && scanned.itemRawIds == ids.take(scanned.itemCount),
            "after a scan the first live window is the viewport's pages re-read; the page below the screen is dropped: ${scanned.itemRawIds}")
        assertTrue(scannedReads.drop(viewport.size).all { it >= viewport.size * WINDOW_PAGE },
            "the window extends only after the rebase, from its new end: $scannedReads")
        await("the window extended again under the new stamp") { last(checkNotNull(albumsGrid())).itemCount > scanned.itemCount }
        awaitQuiet()
        left = last(checkNotNull(albumsGrid())).itemCount
        ui.leaveBrowseView()
        awaitQuiet()

        val stampHeld = server.scanStatus().getString("lastScan")
        proxy.rewrite({ it.endpoint == "getMusicFolders" }) { body -> withASecondLibrary(body) }
        val (foldersReads, folders) = try {
            relaunch("another library")
        } finally {
            proxy.rewrite(null)
        }
        assertEquals(stampHeld, server.scanStatus().getString("lastScan"), "setup: the stamp did not move")
        assertTrue(proxy.log().any { it.endpoint == "getMusicFolders" && it.rewritten }, "setup: the forwarder presented the second library")
        assertEquals(viewport, foldersReads.take(viewport.size).sorted(),
            "after a folder-set change the first live read is the viewport's pages, never an extension: $foldersReads")
        assertTrue(folders.itemCount in 1..viewport.size * WINDOW_PAGE && folders.itemCount < left && folders.itemRawIds == ids.take(folders.itemCount),
            "after a folder-set change the first live window is the viewport's pages re-read; the page below the screen is dropped: ${folders.itemRawIds}")
        assertNoCredentialLeak()
        println("CONF-82 OBSERVED $platform tear=stored-epoch+folder-set page=$WINDOW_PAGE window-rows=$held viewport-pages=$viewport " +
            "pages-below-screen=$below unchanged=${unchanged.itemCount}rows/$unchangedReads scanned=${scanned.itemCount}rows/$scannedReads " +
            "folders=${folders.itemCount}rows/$foldersReads stamp-before=${stampBefore != stampHeld}")
    }

    /**
     * Scanning mode (§16.12). With the server presented as scanning (its stamp as it really is), the
     * grid's pages append as the person scrolls — scrolling never freezes — each unguarded, and the list
     * says the server is updating its library. When the server is presented idle again under the SAME
     * stamp, the next epoch reading (a return to the foreground) rebases the window around the viewport
     * and the line goes: "scanning cleared" is the trigger, not a moved stamp.
     */
    fun conf82WhileTheServerScansPagesAppendUnguardedAndTheScanEndRebasesUnderAnUnchangedStamp() = withWindowPages {
        val stamp = server.scanStatus().getString("lastScan")
        val presented = proxy.size()
        proxy.rewrite({ it.endpoint == "getScanStatus" }) { body -> scanStatus(body) { it.put("scanning", true) } }
        try {
            relaunchIntoAlbums()
            val grid = checkNotNull(albumsGrid())
            val type = windowReads(0).last().parameters.getValue("type")
            val ids = server.albumIds(type)
            assertEquals(AndroidLibraryCoverage.UnverifiedScanning, last(grid).coverage, "a window read while scanning says so")
            val scanning = RuntimeEnvironment.getApplication().getString(com.legitimateapps.dulcet.shared.R.string.library_coverage_scanning)
            ui.showListStatus()
            compose.onNodeWithTag("library.albums.coverage").assertTextEquals(scanning)
            val opened = last(grid).itemCount
            assertTrue(opened >= 2, "pages append while scanning before the person scrolls: $opened")
            var steps = 0
            while (last(grid).itemCount < ids.size && steps++ < ids.size) {
                ui.showAlbum(last(grid).itemCount - 1)
                awaitQuiet()
            }
            assertEquals(ids, last(grid).itemRawIds, "scrolling never froze: every album appended while scanning")
            val scanningFrames = frames(grid).filter { it.freshness == AndroidLibraryFreshness.Live }
            assertTrue(scanningFrames.all { it.coverage == AndroidLibraryCoverage.UnverifiedScanning },
                "every page appended while scanning is unguarded: ${scanningFrames.map { it.coverage }}")
            ui.showListStatus()
            awaitQuiet()
            compose.onNodeWithTag("library.albums.coverage").assertTextEquals(scanning)
            // A read the app has not been answered yet presented nothing, so only answered reads
            // count; one that reached the app unrewritten (a forwarding failure among them) fails.
            val statusReads = proxy.since(presented).filter { it.endpoint == "getScanStatus" }
            assertTrue(
                statusReads.any { it.answered } && statusReads.filter { it.answered }.all { it.rewritten },
                "setup: every status read was presented as scanning: " +
                    statusReads.map { "#${it.sequence} answered=${it.answered} status=${it.status} rewritten=${it.rewritten}" },
            )

            proxy.rewrite(null)
            assertEquals(stamp, server.scanStatus().getString("lastScan"), "setup: the stamp is the one the window was read under")
            val viewport = viewportPages(grid)
            val mark = proxy.size()
            foregroundReturn()
            await("the window rebased and guarded") {
                last(grid).coverage.let { it == AndroidLibraryCoverage.Open || it == AndroidLibraryCoverage.Complete }
            }
            awaitQuiet()
            val reads = windowReads(mark).map { it.offset() }
            assertEquals(viewport, reads.take(viewport.size).sorted(), "the scan's end rebases the viewport's pages: $reads")
            ui.showListStatus()
            awaitQuiet()
            assertTrue(compose.onAllNodesWithTag("library.albums.coverage").fetchSemanticsNodes().isEmpty(), "the scanning line goes")
            assertEquals(stamp, server.scanStatus().getString("lastScan"), "the stamp never moved")
            assertNoCredentialLeak()
            println("CONF-82 OBSERVED $platform scanning page=$WINDOW_PAGE appended-rows=${ids.size} opened-rows=$opened " +
                "label=scanning scan-end-stamp=unchanged rebase-reads=$reads viewport-pages=$viewport final=${last(grid).coverage}")
        } finally {
            proxy.rewrite(null)
        }
    }

    /**
     * No epoch (§16.11, §16.12). The first-scan sentinel and an absent `lastScan` are "no epoch" whatever
     * `scanning` says, and never "unchanged": a window read live seconds earlier under a real stamp,
     * which an unchanged epoch leaves unread (the control), is re-read as soon as the server reports
     * the sentinel, and is labelled unverified; its pages still append. Each of the four presentations
     * makes the session say the server reports no epoch, and the library's home says so once; the real
     * stamp back, it no longer does.
     */
    fun conf82TheSentinelAndAnAbsentStampAreNoEpochWhateverScanningSaysAndNeverUnchanged() = withWindowPages {
        val grid = openPagedAlbums()
        val type = windowReads(0).last().parameters.getValue("type")
        val ids = server.albumIds(type)
        val viewport = viewportPages(grid)

        var mark = proxy.size()
        foregroundReturn()
        assertEquals(LibraryConnectionState.Online(serverReportsNoEpoch = false), observed().connections.last())
        assertEquals(emptyList(), windowReads(mark).map { it.offset() }, "control: an unchanged epoch leaves a window read seconds ago unread")

        proxy.rewrite({ it.endpoint == "getScanStatus" }) { body -> scanStatus(body) { it.put("lastScan", SENTINEL); it.put("scanning", false) } }
        mark = proxy.size()
        foregroundReturn()
        await("the window re-read with no epoch") { last(grid).coverage == AndroidLibraryCoverage.UnverifiedNoEpoch }
        awaitQuiet()
        assertEquals(LibraryConnectionState.Online(serverReportsNoEpoch = true), observed().connections.last(), "the sentinel is no epoch")
        val reread = windowReads(mark).map { it.offset() }
        assertEquals(viewport, reread.take(viewport.size).sorted(), "the sentinel is never unchanged: the window is re-read: $reread")
        var steps = 0
        while (last(grid).itemCount < ids.size && steps++ < ids.size) {
            ui.showAlbum(last(grid).itemCount - 1)
            awaitQuiet()
        }
        assertEquals(ids, last(grid).itemRawIds, "a window with no epoch still pages on, every album once")
        assertEquals(AndroidLibraryCoverage.UnverifiedNoEpoch, last(grid).coverage)

        val presentations = listOf<Pair<String, (JSONObject) -> Unit>>(
            "sentinel, scanning" to { it.put("lastScan", SENTINEL); it.put("scanning", true) },
            "absent, idle" to { it.remove("lastScan"); it.put("scanning", false) },
            "absent, scanning" to { it.remove("lastScan"); it.put("scanning", true) },
        )
        for ((name, change) in presentations) {
            proxy.rewrite({ it.endpoint == "getScanStatus" }) { body -> scanStatus(body, change) }
            foregroundReturn()
            assertEquals(LibraryConnectionState.Online(serverReportsNoEpoch = true), observed().connections.last(), "$name is no epoch")
        }
        ui.leaveBrowseView()
        awaitQuiet()
        val noEpoch = RuntimeEnvironment.getApplication().getString(com.legitimateapps.dulcet.shared.R.string.library_no_epoch)
        compose.onNodeWithTag("library.noEpoch").assertTextEquals(noEpoch)

        proxy.rewrite(null)
        foregroundReturn()
        assertEquals(LibraryConnectionState.Online(serverReportsNoEpoch = false), observed().connections.last(), "control: a real stamp is an epoch")
        assertTrue(compose.onAllNodesWithTag("library.noEpoch").fetchSemanticsNodes().isEmpty(), "the line goes with a real stamp")
        assertNoCredentialLeak()
        println("CONF-82 OBSERVED $platform no-epoch page=$WINDOW_PAGE control-reads=0 sentinel-reread=$reread " +
            "no-epoch-rows=${ids.size} presentations=sentinel/idle,${presentations.joinToString(",") { it.first }} line=shown-then-gone")
    }

    /**
     * A failed status read is *unread* (§16.12): with every `getScanStatus` failing, the page the grid
     * asks for as it scrolls is read but not used — the window keeps its rows and its label, never
     * "no epoch" — and the window says it is showing what it had because the read failed. The page
     * is still owed: once status reads succeed, the person asking for it again (scrolling to the end)
     * makes it. Leaving the end gives the page up: the window is what it was, live, with no request.
     */
    fun conf82AFailedStatusReadIsUnreadAndTheWindowKeepsItsPagesAndLabel() = withWindowPages {
        val grid = openPagedAlbums()
        val type = windowReads(0).last().parameters.getValue("type")
        val ids = server.albumIds(type)
        val end = last(grid).itemCount
        assertTrue(end < ids.size, "setup: the grid holds part of the list: $end")
        proxy.fail { it.endpoint == "getScanStatus" }
        val mark = proxy.size()
        try {
            ui.showAlbum(end - 1)
            await("the page read and its status read failed") {
                windowReads(mark).any { it.offset() == end && it.answered } &&
                    proxy.since(mark).any { it.endpoint == "getScanStatus" && it.status == 500 }
            }
            awaitQuiet()
            await("the failure published") { last(grid).freshness.isFailed() }
            val failed = last(grid)
            assertIs<AndroidLibraryFreshness.Cached>(failed.freshness, "the window keeps what it had, saying why: ${failed.freshness}")
            assertEquals(ids.take(end), failed.itemRawIds, "a page whose status read failed is not used")
            assertEquals(AndroidLibraryCoverage.Open, failed.coverage, "the window is not relabelled: never no epoch")
            assertEquals(LibraryConnectionState.Online(serverReportsNoEpoch = false), observed().connections.last(),
                "a failed status read is never evidence the server reports no epoch")
            assertTrue(frames(grid).none { it.itemCount > end }, "no publication ever carried the unread page")
        } finally {
            proxy.fail(null)
        }
        // To the list's status lines. Where that leaves the end (the phone: the window is taller than
        // the screen), the owed page is given up — live again, reading nothing — and is made when the
        // person scrolls back to the end. Where the end stays on screen (the TV: the window fits), the
        // failure stays, said on screen with "Try again", which makes the page.
        val leaveMark = proxy.size()
        ui.showListStatus()
        awaitQuiet()
        val path: String
        if (last(grid).freshness.isFailed()) {
            path = "try-again"
            compose.onNodeWithTag("library.albums.freshness").assertExists()
            assertEquals(emptyList(), windowReads(leaveMark), "nothing is read while the failure is shown")
            val retryMark = proxy.size()
            ui.activate(compose.onNodeWithTag("library.albums.retry"))
            await("the owed page made by Try again") { last(grid).itemCount > end }
            awaitQuiet()
            assertTrue(windowReads(retryMark).any { it.offset() == end }, "Try again reads the page that was unread")
        } else {
            path = "asked-again"
            assertEquals(AndroidLibraryFreshness.Live, last(grid).freshness, "leaving the end gives the owed page up")
            assertEquals(emptyList(), windowReads(leaveMark), "giving the page up reads nothing")
            assertEquals(ids.take(end), last(grid).itemRawIds)
            val againMark = proxy.size()
            ui.showAlbum(end - 1)
            await("the page made when asked for again") { last(grid).itemCount > end }
            awaitQuiet()
            assertEquals(end, windowReads(againMark).first().offset(), "the page asked for again is the one that was unread")
        }
        assertEquals(ids.subList(0, last(grid).itemCount), last(grid).itemRawIds)
        assertNoCredentialLeak()
        println("CONF-82 OBSERVED $platform unread page=$WINDOW_PAGE unread-offset=$end rows-kept=$end coverage-kept=Open " +
            "freshness=Cached(Failed) no-epoch=false then=$path rows=${last(grid).itemCount} cover-art=${coverArt(mark)}")
    }

    /**
     * A window whose answers carry no `X-Total-Count` has an unknown total (§16.12). The control first:
     * with the header, the grid scrolled to its end knows the total and completes on it. Then, on a
     * fresh install, with the forwarder withholding the header the server sends: every publication's
     * total is unknown, and the window is complete only once an empty page past the end confirms it.
     */
    fun conf82AWindowWithoutXTotalCountHasAnUnknownTotalAndConfirmsItsEnd() = withWindowPages {
        fun scrollToTheEnd(grid: String, rows: Int) {
            var steps = 0
            while (last(grid).coverage != AndroidLibraryCoverage.Complete && steps++ <= rows) {
                ui.showAlbum(last(grid).itemCount - 1)
                awaitQuiet()
            }
            assertEquals(AndroidLibraryCoverage.Complete, last(grid).coverage, "the grid scrolled to its end is complete")
        }

        var grid = openPagedAlbums()
        val type = windowReads(0).last().parameters.getValue("type")
        val ids = server.albumIds(type)
        scrollToTheEnd(grid, ids.size)
        val withTotal = frames(grid).filter { it.itemCount > 0 }
        assertTrue(withTotal.all { it.total == ids.size }, "control: with the header every publication knows the total: ${withTotal.map { it.total }}")
        val controlReads = windowReads(0).filter { it.parameters["type"] == type }.map { it.offset() }
        assertTrue(ids.size !in controlReads, "control: the window completes on the total, with no read past the end: $controlReads")

        ui.leaveBrowseView()
        awaitQuiet()
        closeProcessReaderAndReconnect()
        RuntimeEnvironment.getApplication().deleteDatabase("dulcet.db")
        proxy.withoutHeader({ it.endpoint == "getAlbumList2" }, "X-Total-Count")
        try {
            val mark = proxy.size()
            compose.activityRule.scenario.recreate()
            grid = openPagedAlbums()
            scrollToTheEnd(grid, ids.size + 1)
            val reads = windowReads(mark).filter { it.parameters["type"] == type }
            assertTrue(reads.all { it.headerWithheld && it.serverTotalCount == ids.size.toString() },
                "setup: the server sent X-Total-Count and the forwarder withheld it: ${reads.map { it.serverTotalCount to it.headerWithheld }}")
            val unknown = frames(grid).filter { it.itemCount > 0 }
            assertTrue(unknown.all { it.total == null }, "no header, no total: ${unknown.map { it.total }}")
            assertEquals(ids, last(grid).itemRawIds)
            val confirming = reads.filter { it.offset() == ids.size }
            assertEquals(1, confirming.size, "the end is confirmed by one read past it: ${reads.map { it.offset() }}")
            assertTrue(confirming.single().answered && confirming.single().status == 200)
            assertNoCredentialLeak()
            println("CONF-82 OBSERVED $platform no-total page=$WINDOW_PAGE control-reads=$controlReads control-total=${ids.size} " +
                "no-header-reads=${reads.map { it.offset() }} no-header-total=null confirming-read-offset=${ids.size}")
        } finally {
            proxy.withoutHeader(null)
        }
    }

    /** The pages that hold the albums on screen, as server offsets. */
    private fun viewportPages(grid: String): List<Int> {
        val leading = last(grid).leadingOffset
        return visibleAlbums().map { (leading + it) / WINDOW_PAGE * WINDOW_PAGE }.distinct().sorted()
    }

    /** The album the grid shows first, by id. */
    private fun firstShownAlbum(grid: String): String = last(grid).itemRawIds[visibleAlbums().first()]

    /** A cold start into the albums grid, live and quiet. */
    private fun relaunchIntoAlbums() {
        closeProcessReaderAndReconnect()
        compose.activityRule.scenario.recreate()
        openPagedAlbums()
    }

    /** The app stopped and brought back: the session reconnects, reading the epoch (§16.11 policy 1). */
    private fun foregroundReturn() {
        val before = observed().reconnects
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitQuiet(composed = false)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("the foreground reconnect to finish") {
            observed().reconnects > before && observed().connections.last() is LibraryConnectionState.Online
        }
        awaitQuiet()
    }

    /**
     * Runs [block] on a process reader whose window pages hold [WINDOW_PAGE] rows, so the disposable
     * library's albums are a list of several pages (§16.12); every other part of the reader is the
     * production one. A cold start follows, so the app's session obtains that reader.
     */
    private fun withWindowPages(block: () -> Unit) {
        AndroidLibraryReader.testPageSize = WINDOW_PAGE
        try {
            closeProcessReaderAndReconnect()
            compose.activityRule.scenario.recreate()
            block()
        } finally {
            AndroidLibraryReader.testPageSize = null
        }
    }

    /**
     * The library opened, then its albums grid, live. The network is reported constrained, so the
     * grid reads no album details ahead (§16.13) and every request counted is the window's own.
     */
    private fun openPagedAlbums(): String {
        ui.openLibrary()
        awaitHomeLive()
        environment.network.constrain(true)
        awaitQuiet()
        ui.activate(compose.onNodeWithTag("library.view.albums"))
        await("the albums grid live") { albumsGrid()?.let { last(it).freshness == AndroidLibraryFreshness.Live } == true }
        awaitQuiet()
        return checkNotNull(albumsGrid())
    }

    /** The albums window's page reads since [mark]: `getAlbumList2` with an offset (a home row has none). */
    private fun windowReads(mark: Int): List<CountingProxy.Seen> =
        proxy.since(mark).filter { it.endpoint == "getAlbumList2" && "offset" in it.parameters }

    private fun CountingProxy.Seen.offset(): Int = parameters.getValue("offset").toInt()

    /** The albums grid's cards on screen (any part of them inside the grid), by index into what it draws. */
    private fun visibleAlbums(): List<Int> {
        val bounds = compose.onNodeWithTag("library.albums").fetchSemanticsNode().boundsInRoot
        return compose.onAllNodes(SemanticsMatcher("an album card") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(ALBUM_CARD)
        }).fetchSemanticsNodes()
            .filter { it.boundsInRoot.overlaps(bounds) }
            .map { it.config[SemanticsProperties.TestTag].removePrefix(ALBUM_CARD).toInt() }
            .sorted()
    }

    // ---- Instruments ------------------------------------------------------------------------------

    /** Credentials ride in the query string; nothing the app logged may carry them (docs/TRAPS.md trap 12). */
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
        // Selected first, as a person does: the TV field is read-only until selected, so passing
        // over it with the D-pad brings up no keyboard. On the phone the tap only focuses it.
        compose.onNodeWithTag("search.query").performClick()
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
        // Each return from an album composes the home afresh, so its rows open new windows, and a
        // row draws nothing below its title until its first publication arrives. That publication
        // is built on the reader's thread, which no idling waits for: a finder run straight after
        // the return could see the row empty. Wait for the row's items (a finder, so the main
        // looper is idled as the wait polls; docs/TRAPS.md trap 46).
        await("the first home row's items on screen") { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row).performScrollToNode(hasText(title))
        ui.activate(compose.onAllNodes(hasText(title) and hasAnyAncestor(row)).onFirst())
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

        /** Five times the reader's 300 ms look-ahead settle (§16.13): a grid this still has settled. */
        const val LOOK_AHEAD_STILL_MILLIS = 1_500L
        const val LOOK_AHEAD_MAX_PER_VIEWPORT = 24

        /**
         * Rows per window page in the CONF-82 tests: the fixture's eight albums are eight pages, so on
         * both platforms the grid holds a page below the albums on screen and asks for more as it scrolls.
         */
        const val WINDOW_PAGE = 1
        const val SENTINEL = "0001-01-01T00:00:00Z"
        const val ALBUM_CARD = "library.albums.item."

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
        const val PLAYLIST_ORDER = "Dulcet test order"
        const val PLAYLIST_EDIT = "Dulcet test edits"
        const val PLAYLIST_RENAMED = "Dulcet test edits renamed"
        const val PLAYLIST_STALE = "Dulcet test stale view"
        const val PLAYLIST_OTHERS = "Dulcet test another user's"
        const val PLAYLIST_OWN = "Dulcet test own"
        const val CHANGED_ELSEWHERE_COPY =
            "This playlist changed on another device, so your change wasn't sent. Showing your server's version."
        val PLAYLIST_WRITES = setOf("createPlaylist", "updatePlaylist", "deletePlaylist")
        /** Embedded synced lyrics in three languages (the CONF-42 fixture). */
        const val LYRICS_TRACK = "Twenty Nine Seconds"
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

/** The disposable fixture's song id for [title], read from the server itself. */
fun DisposableServer.songId(title: String): String {
    val songs = get("search3", mapOf("query" to title, "songCount" to "20", "albumCount" to "0", "artistCount" to "0"))
        .getJSONObject("searchResult3").getJSONArray("song")
    for (index in 0 until songs.length()) {
        val song = songs.getJSONObject(index)
        if (song.getString("title") == title) return song.getString("id")
    }
    error("The disposable fixture has no song titled $title")
}

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

/** A `getScanStatus` answer changed by [change], as a server reporting that status would answer. */
internal fun scanStatus(body: ByteArray, change: (JSONObject) -> Unit): ByteArray {
    val document = JSONObject(String(body, Charsets.UTF_8))
    change(document.getJSONObject("subsonic-response").getJSONObject("scanStatus"))
    return document.toString().toByteArray(Charsets.UTF_8)
}

/** A `getMusicFolders` answer with one more library in it, as a server that gave the account a second one would answer. */
internal fun withASecondLibrary(body: ByteArray): ByteArray {
    val document = JSONObject(String(body, Charsets.UTF_8))
    val folders = document.getJSONObject("subsonic-response").getJSONObject("musicFolders").getJSONArray("musicFolder")
    check(folders.length() >= 1) { "setup: the server lists its library" }
    folders.put(JSONObject().put("id", 990_001).put("name", "Second Library"))
    return document.toString().toByteArray(Charsets.UTF_8)
}
