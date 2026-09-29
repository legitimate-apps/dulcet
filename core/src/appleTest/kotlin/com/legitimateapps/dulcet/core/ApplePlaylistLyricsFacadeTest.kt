package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The Apple playlist and lyrics facades, driven end to end: the production [LibraryReaderSession]
 * on its real dedicated thread, over [FakePlaylistServer] (the reference server's measured playlist
 * semantics) or a lyrics transport, with every completion delivered through the real main
 * dispatcher. The tests run on the main thread and pump its run loop, so a delivery made anywhere
 * else is caught ([offMain]).
 */
class ApplePlaylistLyricsFacadeTest {

    // ---- Playlists ----------------------------------------------------------------------------------

    @Test
    fun aCreateIsShownAtOnceUnderALocalIdThenToldWithTheServersId() = facadeTest { h ->
        val c = h.client(h.playlists)
        val playlists = AppleLibraryPlaylistClient(c.client)
        val outcomes = mutableListOf<AppleLibraryPlaylistOutcome>()
        playlists.subscribeOutcomes(recorder(outcomes))
        val list = WindowRecorder()
        c.client.subscribeLibraryWindow(request("playlists"), list)
        pumpUntil("the playlists list") { list.all.any { it.freshness.kind == "live" } }

        val result = await<AppleLibraryPlaylistEditResult> { playlists.create("Road Trip", listOf("song-1", "song-2"), it) }
        assertEquals("pending", result.record)
        assertNull(result.errorKind)
        val localId = assertNotNull(result.localId)
        pumpUntil("the created playlist's outcome") { outcomes.any { it.kind == "created" } }
        val created = outcomes.single { it.kind == "created" }
        assertEquals(localId, created.localId)
        assertEquals("create", created.change)
        val serverId = created.playlistId
        assertEquals(listOf("song-1", "song-2"), c.onReader { h.playlists.playlist(serverId).entries.toList() })

        // Before the send, the list showed the local playlist as local, pending and editable.
        val local = list.all.flatMap { it.items }.first { it.rawId == localId }
        assertEquals(listOf(true, true, true), listOf(local.local, local.pendingChanges, local.editable))
        pumpUntil("the list shows the server's playlist") { list.last.items.any { it.rawId == serverId && !it.pendingChanges } }
        assertEquals(0, list.offMain)
    }

    @Test
    fun aRemovalAimedAtAStaleViewIsRefusedAndAFreshOneIsSavedAndReadBack() = facadeTest { h ->
        val p = h.playlists.add("Mix", listOf("song-1", "song-2", "song-3"))
        val c = h.client(h.playlists)
        val playlists = AppleLibraryPlaylistClient(c.client)
        val outcomes = mutableListOf<AppleLibraryPlaylistOutcome>()
        playlists.subscribeOutcomes(recorder(outcomes))
        val detail = WindowRecorder()
        c.client.subscribeLibraryWindow(request("playlist", rawId = p.id), detail)
        pumpUntil("the playlist's entries") { detail.all.any { it.freshness.kind == "live" && it.items.size == 3 } }
        val view = detail.last.items.map { it.rawId }
        assertTrue(detail.last.header!!.editable, "the account's own playlist is editable")

        val stale = await<AppleLibraryPlaylistEditResult> { playlists.remove(p.id, listOf(0), view.drop(1), it) }
        assertEquals("staleView", stale.record, "a view the core no longer publishes records nothing")

        val removed = await<AppleLibraryPlaylistEditResult> { playlists.remove(p.id, listOf(1), view, it) }
        assertEquals("pending", removed.record)
        pumpUntil("the removal's outcome") { outcomes.any { it.kind == "saved" } }
        assertEquals("entries", outcomes.single { it.kind == "saved" }.change)
        assertEquals(listOf("song-1", "song-3"), c.onReader { p.entries.toList() })
        pumpUntil("the settled publication") { detail.last.items.map { it.rawId } == listOf("song-1", "song-3") && !detail.last.header!!.pendingChanges }
        assertEquals(1, c.onReader { h.playlists.writes().size }, "one write for one removal")
        assertEquals(0, detail.offMain)
    }

    @Test
    fun anotherUsersPlaylistIsPublishedReadOnlyAndRefusesEveryEdit() = facadeTest { h ->
        val theirs = h.playlists.add("Theirs", listOf("song-1"), owner = "someone-else", isPublic = true)
        val c = h.client(h.playlists)
        val playlists = AppleLibraryPlaylistClient(c.client)
        val detail = WindowRecorder()
        c.client.subscribeLibraryWindow(request("playlist", rawId = theirs.id), detail)
        pumpUntil("their playlist") { detail.all.any { it.freshness.kind == "live" && it.header != null } }
        val header = detail.last.header!!
        assertFalse(header.editable)
        assertEquals("someone-else", header.owner, "the owner is published so a shell can show whose it is")
        assertEquals("notEditable", await<AppleLibraryPlaylistEditResult> { playlists.rename(theirs.id, "Mine now", it) }.record)
        assertEquals("notEditable", await<AppleLibraryPlaylistEditResult> { playlists.delete(theirs.id, it) }.record)
        assertTrue(c.onReader { h.playlists.writes().isEmpty() })
    }

    @Test
    fun aPendingChangeIsListedAndCanBeWithdrawnWhileOffline() = facadeTest { h ->
        val p = h.playlists.add("Mix", listOf("song-1"))
        val c = h.client(h.playlists)
        val playlists = AppleLibraryPlaylistClient(c.client)
        // Read once online: an edit needs the playlist as this device last saw it.
        val detail = WindowRecorder()
        c.client.subscribeLibraryWindow(request("playlist", rawId = p.id), detail)
        pumpUntil("the playlist") { detail.all.any { it.freshness.kind == "live" && it.header != null } }
        c.client.setOnline(false)
        assertEquals("pending", await<AppleLibraryPlaylistEditResult> { playlists.rename(p.id, "Renamed", it) }.record)
        val pending = await<AppleLibraryPlaylistPendingChanges> { playlists.pendingChanges(it) }
        assertNull(pending.errorKind)
        assertEquals(listOf(p.id to "details"), pending.changes.map { it.playlistId to it.change })
        assertFalse(pending.changes.single().inDoubt)
        assertEquals("compactedAway", await<AppleLibraryPlaylistEditResult> { playlists.withdraw(p.id, "details", it) }.record)
        assertEquals("invalid", await<AppleLibraryPlaylistEditResult> { playlists.withdraw(p.id, "no-such-kind", it) }.record)
        assertTrue(await<AppleLibraryPlaylistPendingChanges> { playlists.pendingChanges(it) }.changes.isEmpty())
        assertTrue(c.onReader { h.playlists.writes().isEmpty() }, "nothing was sent")
    }

    @Test
    fun anEditOnAClosedClientCompletesOnceAsClosed() = facadeTest { h ->
        val c = h.client(h.playlists)
        val playlists = AppleLibraryPlaylistClient(c.client)
        c.close()
        assertEquals("closed", await<AppleLibraryPlaylistEditResult> { playlists.create("Late", emptyList(), it) }.errorKind)
    }

    @Test
    fun everyPlaylistRecordAndOutcomeMapsToADistinctClosedWord() {
        val records = PlaylistEditRecord.entries.map { it.appleKind() }
        assertEquals(records.size, records.toSet().size, "two records share a word: $records")
        assertEquals(PlaylistRowKind.entries.map { it.wireName }, PlaylistRowKind.entries.map { it.appleKind() })
        val refused = DomainError.Auth.InvalidCredentials
        val outcomes = listOf(
            PlaylistEditOutcome.Saved("p", PlaylistRowKind.Entries),
            PlaylistEditOutcome.Created("local", "p"),
            PlaylistEditOutcome.NotSaved("p", PlaylistRowKind.Details, DomainError.Auth.Forbidden),
            PlaylistEditOutcome.ChangedElsewhere("p", listOf("a")),
            PlaylistEditOutcome.Diverged("p", PlaylistRowKind.Entries, listOf("a"), listOf("b")),
            PlaylistEditOutcome.Held("p", PlaylistRowKind.Entries, refused),
            PlaylistEditOutcome.Superseded("p", setOf(PlaylistDetailField.Public, PlaylistDetailField.Name)),
            PlaylistEditOutcome.NotRecorded("p"),
            PlaylistEditOutcome.PossiblyCreated("local", "Mix", listOf("c1")),
            PlaylistEditOutcome.PossibleDuplicate("local", "Mix", listOf("c1", "c2")),
        ).map { it.toApple() }
        assertEquals(outcomes.size, outcomes.map { it.kind }.toSet().size)
        assertEquals("forbidden", outcomes[2].errorKind)
        assertEquals("invalidCredentials", outcomes[5].errorKind)
        assertEquals(listOf("name", "public"), outcomes[6].fields)
        assertEquals(listOf("a") to listOf("b"), outcomes[4].serverEntries to outcomes[4].intendedEntries)
        assertEquals(listOf("c1", "c2"), outcomes[9].candidates)
        assertEquals("local", outcomes[9].playlistId)
    }

    // ---- Lyrics -------------------------------------------------------------------------------------

    @Test
    fun theFirstReadLearnsTheEndpointOnceThenFollowsTheCoresSyncedCursor() = facadeTest { h ->
        h.lyrics.extensions = """[{"name":"songLyrics","versions":[1]}]"""
        h.lyrics.bodies["getLyricsBySongId"] = SYNCED
        val c = h.client(h.lyrics)
        val lyrics = AppleLibraryLyricsClient(c.client, listOf("en"))
        val track = AppleLibraryLyricsTrack("song-1", "Artist", "Title")

        val first = await<AppleLibraryLyricsPublication> { lyrics.cached(track, it) }
        assertEquals(listOf("unavailable", "notCachedOffline"), listOf(first.state, first.freshness.reason), "nothing stored yet")
        assertTrue(c.onReader { h.lyrics.log.isEmpty() }, "the stored document is read with no request")

        val live = await<AppleLibraryLyricsPublication> { lyrics.read(track, it) }
        assertEquals("lyrics", live.state)
        assertEquals("live", live.freshness.kind)
        assertTrue(live.synced)
        assertEquals(listOf("first line", "second line", "", "fourth line"), live.lines.map { it.text })
        // The offset (+250 ms) moves every line sooner.
        assertEquals(listOf(250L, 1_750L, 2_750L, 4_000L), live.lines.map { it.effectiveStartMilliseconds })
        val layer = assertNotNull(live.layer)
        assertEquals(1, layer.cursorAtMilliseconds(2_000).index)
        assertTrue(layer.cursorAtMilliseconds(3_000).isInterlude, "a blank line is the instrumental gap")
        assertEquals(-1, layer.cursorAtMilliseconds(0).index)

        await<AppleLibraryLyricsPublication> { lyrics.read(AppleLibraryLyricsTrack("song-2", null, "Other"), it) }
        assertEquals(
            listOf("getOpenSubsonicExtensions", "getLyricsBySongId", "getLyricsBySongId"),
            c.onReader { h.lyrics.log.toList() },
            "the extensions are read once for the client",
        )
        val stored = await<AppleLibraryLyricsPublication> { lyrics.cached(track, it) }
        assertEquals(listOf("lyrics", "cached"), listOf(stored.state, stored.freshness.kind))
    }

    @Test
    fun aServerWithoutTheExtensionListIsAskedTheLegacyWay() = facadeTest { h ->
        h.lyrics.extensionsStatus = 404
        h.lyrics.bodies["getLyrics"] = ok(""""lyrics":{"artist":"Artist","title":"Title","value":"plain words\nsecond"}""")
        val c = h.client(h.lyrics)
        val lyrics = AppleLibraryLyricsClient(c.client, emptyList())
        val live = await<AppleLibraryLyricsPublication> { lyrics.read(AppleLibraryLyricsTrack("song-1", "Artist", "Title"), it) }
        assertEquals("lyrics", live.state)
        assertFalse(live.synced)
        assertTrue(live.lines.all { it.effectiveStartMilliseconds == null })
        assertEquals(listOf("getOpenSubsonicExtensions", "getLyrics"), c.onReader { h.lyrics.log.toList() })
    }

    @Test
    fun aFailedDiscoveryIsNotRememberedAndTheTrackWithoutLyricsSaysNone() = facadeTest { h ->
        h.lyrics.failWith["getOpenSubsonicExtensions"] = DomainError.Transport.Timeout
        h.lyrics.extensions = """[{"name":"songLyrics","versions":[1]}]"""
        h.lyrics.bodies["getLyricsBySongId"] = ok(""""lyricsList":{"structuredLyrics":[]}""")
        val c = h.client(h.lyrics)
        val lyrics = AppleLibraryLyricsClient(c.client, emptyList())
        val track = AppleLibraryLyricsTrack("song-1", "Artist", "Title")
        val failed = await<AppleLibraryLyricsPublication> { lyrics.read(track, it) }
        assertEquals(listOf("unavailable", "unavailable", "failed", "timeout"), listOf(failed.state, failed.freshness.kind, failed.freshness.reason, failed.freshness.errorKind))
        c.onReader { h.lyrics.failWith.clear() }
        val none = await<AppleLibraryLyricsPublication> { lyrics.read(track, it) }
        assertEquals("none", none.state, "a document with no layer is the fact 'no lyrics', never a spinner")
        assertEquals(
            listOf("getOpenSubsonicExtensions", "getOpenSubsonicExtensions", "getLyricsBySongId"),
            c.onReader { h.lyrics.log.toList() },
            "the failed discovery was asked again",
        )
    }

    @Test
    fun offlineNothingIsAskedAndTheScreenSaysOffline() = facadeTest { h ->
        val c = h.client(h.lyrics)
        val lyrics = AppleLibraryLyricsClient(c.client, emptyList())
        c.client.setOnline(false)
        val offline = await<AppleLibraryLyricsPublication> { lyrics.read(AppleLibraryLyricsTrack("song-1", "A", "T"), it) }
        assertEquals(listOf("unavailable", "notCachedOffline"), listOf(offline.state, offline.freshness.reason))
        assertTrue(c.onReader { h.lyrics.log.isEmpty() })
        assertEquals("input", await<AppleLibraryLyricsPublication> { lyrics.read(AppleLibraryLyricsTrack(" ", null, null), it) }.errorKind)
    }

    // ---- Harness ------------------------------------------------------------------------------------

    private class LyricsServer : LibraryEndpointTransport {
        val log = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        val failWith = mutableMapOf<String, DomainError>()
        var extensions: String = "[]"
        var extensionsStatus: Int = 200

        override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
            log += endpoint
            failWith[endpoint]?.let { throw LibraryRequestFailure(it) }
            if (endpoint == "getOpenSubsonicExtensions") {
                return if (extensionsStatus == 404) {
                    LibraryEndpointResponse(404, "<html>404</html>", "http://fixture.invalid/rest")
                } else {
                    LibraryEndpointResponse(200, ok(""""openSubsonicExtensions":$extensions"""), "http://fixture.invalid/rest")
                }
            }
            val body = bodies[endpoint] ?: fail("unexpected endpoint $endpoint")
            return LibraryEndpointResponse(200, body, "http://fixture.invalid/rest")
        }
    }

    private class Harness(val driver: app.cash.sqldelight.db.SqlDriver) {
        val playlists = FakePlaylistServer()
        val lyrics = LyricsServer()
        val clock = ManualWallClock(now = 3_000_000)
        val clients = mutableListOf<FacadeClient>()

        fun client(transport: LibraryEndpointTransport): FacadeClient {
            val dispatcher = newLibraryReaderDispatcher()
            val client = AppleLibraryReaderClient(
                { scope, foreground ->
                    val store = DulcetDatabaseStore.open(driver)
                    AppleLibraryReaderComposition(
                        LibraryReaderSession(
                            store.database, SeenCacheStore(store, clock).bind(BINDING), transport, scope,
                            LibraryReaderConfig(lookAheadMaxPerViewport = 0), formPost = false, foreground = foreground,
                        ),
                    )
                },
                false,
                dispatcher,
                Dispatchers.Main,
            )
            return FacadeClient(client, dispatcher).also { clients += it }
        }
    }

    private class FacadeClient(val client: AppleLibraryReaderClient, private val dispatcher: CloseableCoroutineDispatcher) {
        private var closed = false

        /** Runs [block] on the reader's thread, where the fake servers are touched. Never pumps main. */
        fun <T> onReader(block: () -> T): T = runBlocking(dispatcher) { block() }

        fun close() {
            if (closed) return
            closed = true
            client.close()
            runBlocking { withTimeout(15.seconds) { client.awaitTermination() } }
        }
    }

    private class WindowRecorder : AppleLibraryWindowListener {
        val all = mutableListOf<AppleLibraryWindowPublication>()
        var offMain = 0
        val last: AppleLibraryWindowPublication get() = all.last()

        override fun onWindowPublication(publication: AppleLibraryWindowPublication) {
            if (!NSThread.isMainThread) offMain += 1
            all += publication
        }
    }

    private fun facadeTest(block: (Harness) -> Unit) {
        check(NSThread.isMainThread) { "facade tests assert main-thread delivery and must run on the main thread" }
        val driver = createTestDriver()
        val harness = Harness(driver)
        try {
            block(harness)
        } finally {
            harness.clients.forEach { runCatching { it.close() } }
            driver.close()
        }
    }

    private companion object {
        val BINDING = CacheBinding("server:facade", "https://music.example", "listener")

        val SYNCED = ok(
            """"lyricsList":{"structuredLyrics":[{"kind":"main","lang":"eng","line":[{"start":500,"value":"first line"},{"start":2000,"value":"second line"},{"start":3000,"value":""},{"start":4250,"value":"fourth line"}],"offset":250,"synced":true}]}""",
        )

        fun ok(body: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true,$body}}"""

        fun request(kind: String, rawId: String? = null) = AppleLibraryWindowRequest(kind, rawId, null, null, 0, 0, null)

        fun recorder(into: MutableList<AppleLibraryPlaylistOutcome>) = object : AppleLibraryPlaylistOutcomeListener {
            override fun onPlaylistOutcome(outcome: AppleLibraryPlaylistOutcome) {
                check(NSThread.isMainThread) { "an outcome was delivered off the main thread" }
                into += outcome
            }
        }

        /** Starts an operation and pumps the main run loop until its one completion arrives there. */
        fun <T : Any> await(start: ((T) -> Unit) -> Unit): T {
            val results = mutableListOf<T>()
            start { result ->
                check(NSThread.isMainThread) { "a completion was delivered off the main thread" }
                results += result
            }
            pumpUntil("the operation's completion") { results.isNotEmpty() }
            pumpFor(0.05.seconds)
            assertEquals(1, results.size, "an operation completes exactly once")
            return results.single()
        }

        fun pumpUntil(what: String, timeout: Duration = 20.seconds, condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (start.elapsedNow() > timeout) fail("timed out after $timeout waiting for $what")
                NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, NSDate.dateWithTimeIntervalSinceNow(0.005))
            }
        }

        fun pumpFor(duration: Duration) {
            val start = TimeSource.Monotonic.markNow()
            while (start.elapsedNow() < duration) {
                NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, NSDate.dateWithTimeIntervalSinceNow(0.005))
            }
        }
    }
}
