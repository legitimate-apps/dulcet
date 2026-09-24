package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The Apple reader facade, driven end to end: the production [LibraryReaderSession] over the core's
 * own test server ([SessionTestServer] on [FakeReaderServer]) and a private test database, on the
 * reader's real dedicated thread, with every delivery made through the real main dispatcher.
 *
 * **These tests run on the main thread and pump its run loop** to receive deliveries, because the
 * claim under test is that every listener call is made there; a test that swapped the main
 * dispatcher for an inline one could not tell. [FacadeClient.onReader] runs a block on the
 * reader's thread WITHOUT pumping the main thread, which is what lets a test hold a publication
 * between "built" and "delivered". Nothing here reads wall time as evidence: waits are bounded
 * polls whose timeout only turns a hang into a failure.
 */
@OptIn(ExperimentalAtomicApi::class)
class AppleLibraryReaderFacadeTest {

    // ---- Closed kinds ---------------------------------------------------------------------------------

    /**
     * Every core value maps to its closed kind, and no two values of one family share a kind. The
     * mapping functions are exhaustive `when`s with no `else`, so a value the core adds later
     * fails compilation; this table pins what each existing value becomes.
     */
    @Test
    fun everyCoreValueMapsToItsClosedKind() {
        val failed = DomainError.Transport.Timeout
        val freshness = listOf(
            LibraryFreshness.Live to listOf("live", null, null, null),
            LibraryFreshness.Loading to listOf("loading", null, null, null),
            LibraryFreshness.Cached(5, LibraryCachedReason.Revalidating) to listOf("cached", "revalidating", null, 5L),
            LibraryFreshness.Cached(null, LibraryCachedReason.Offline) to listOf("cached", "offline", null, null),
            LibraryFreshness.Cached(7, LibraryCachedReason.Failed(failed)) to listOf("cached", "failed", "timeout", 7L),
            LibraryFreshness.Cached(8, LibraryCachedReason.Stale) to listOf("cached", "stale", null, 8L),
            LibraryFreshness.Cached(9, LibraryCachedReason.InternalFailure) to listOf("cached", "internalFailure", null, 9L),
            LibraryFreshness.Unavailable(LibraryUnavailableReason.NotCachedOffline) to listOf("unavailable", "notCachedOffline", null, null),
            LibraryFreshness.Unavailable(LibraryUnavailableReason.Gone) to listOf("unavailable", "gone", null, null),
            LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Auth.InvalidCredentials)) to
                listOf("unavailable", "failed", "invalidCredentials", null),
            LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure) to listOf("unavailable", "internalFailure", null, null),
        )
        freshness.forEach { (core, expected) ->
            val apple = core.toApple()
            assertEquals(expected, listOf(apple.kind, apple.reason, apple.errorKind, apple.asOfEpochMillis), "freshness $core")
        }
        assertDistinct("freshness", freshness.map { it.first.toApple().let { a -> a.kind + "/" + a.reason } })

        assertKinds(
            "coverage",
            LibraryCoverage.entries.associateWith { it.appleKind() },
            mapOf(
                LibraryCoverage.Complete to "complete", LibraryCoverage.Open to "open",
                LibraryCoverage.UnverifiedScanning to "unverifiedScanning",
                LibraryCoverage.UnverifiedNoEpoch to "unverifiedNoEpoch",
                LibraryCoverage.UnverifiedChanging to "unverifiedChanging",
            ),
        )
        assertKinds(
            "playability",
            LibraryPlayability.entries.associateWith { it.appleKind() },
            mapOf(
                LibraryPlayability.Downloaded to "downloaded",
                LibraryPlayability.Streamable to "streamable",
                LibraryPlayability.UnavailableOffline to "unavailableOffline",
            ),
        )
        assertKinds(
            "itemsState",
            LibraryItemsState.entries.associateWith { it.appleKind() },
            mapOf(LibraryItemsState.Present to "present", LibraryItemsState.Loading to "loading", LibraryItemsState.Unavailable to "unavailable"),
        )
        assertKinds(
            "order",
            LibraryItemsOrder.entries.associateWith { it.appleKind() },
            mapOf(LibraryItemsOrder.Server to "server", LibraryItemsOrder.LocalView to "localView"),
        )
        assertKinds(
            "source",
            SearchResultSource.entries.associateWith { it.appleKind() },
            mapOf(SearchResultSource.Server to "server", SearchResultSource.Device to "device"),
        )

        val seen = SeenCacheCounts(artists = 3, albums = 120, tracks = 900)
        val scopes = listOf(
            SearchScope.ServerAndDevice to listOf("serverAndDevice", null, null, null, null),
            SearchScope.DeviceWhileServerPending to listOf("deviceWhileServerPending", null, null, null, null),
            SearchScope.DeviceOffline(seen) to listOf("deviceOffline", null, 3L, 120L, 900L),
            SearchScope.DeviceServerFailed(DomainError.Server.Busy(null), seen) to listOf("deviceServerFailed", "serverBusy", 3L, 120L, 900L),
        )
        scopes.forEach { (scope, expected) ->
            val apple = LibrarySearchPublication("q", 1, scope, emptyList()).toApple()
            assertEquals(expected, listOf(apple.scope, apple.errorKind, apple.seenArtistCount, apple.seenAlbumCount, apple.seenTrackCount), "scope $scope")
        }
        assertDistinct("scope", scopes.map { it.first.appleKind() })

        val album = LibraryEntityRef(LibraryEntityKind.Album, "album-1")
        val outcomes = listOf(
            MutationOutcome.Saved(album, MutationField.Starred, 1) to listOf("saved", "album", "favourite", 1, null, null),
            MutationOutcome.NotSaved(album, MutationField.Rating, DomainError.Server.Known(70)) to
                listOf("notSaved", "album", "rating", null, null, "notFound"),
            MutationOutcome.Superseded(album, MutationField.Rating, 4) to listOf("superseded", "album", "rating", null, 4, null),
            MutationOutcome.NotRecorded(LibraryEntityRef(LibraryEntityKind.Track, "t"), MutationField.Starred) to
                listOf("notRecorded", "track", "favourite", null, null, null),
        )
        outcomes.forEach { (outcome, expected) ->
            val apple = outcome.toApple()
            assertEquals(expected, listOf(apple.kind, apple.targetKind, apple.field, apple.value, apple.serverValue, apple.errorKind), "outcome $outcome")
        }

        val errors = listOf(
            DomainError.Transport.Cancelled to "cancelled",
            DomainError.Transport.Timeout to "timeout",
            DomainError.Transport.Unreachable to "unreachable",
            DomainError.Security.TlsUntrusted(TlsTrustFailure.entries.first()) to "tlsUntrusted",
            DomainError.Security.LocalExceptionViolated to "security",
            DomainError.Security.RedirectRejected(RedirectRejectionReason.entries.first()) to "security",
            DomainError.Auth.InvalidCredentials to "invalidCredentials",
            DomainError.Auth.Forbidden to "forbidden",
            DomainError.Auth.TokenAuthUnsupported to "authentication",
            DomainError.Auth.UnsupportedAuthenticationChallenge to "authentication",
            DomainError.Auth.CrossOriginRedirectRejected(RedirectTargetHost("music.example")) to "authentication",
            DomainError.Protocol.MalformedEnvelope to "protocol",
            DomainError.Protocol.UnexpectedBinary to "protocol",
            DomainError.Protocol.NotASubsonicServer to "protocol",
            DomainError.Protocol.Incompatible(ProtocolVersionLevel(1, 16), null) to "protocol",
            DomainError.Protocol.UnexpectedContentType(ObservedPlaybackContentType.entries.first(), AudioContainer.Flac) to "protocol",
            DomainError.Server.Busy(null) to "serverBusy",
            DomainError.Server.Known(0) to "server",
            DomainError.Server.Known(70) to "notFound",
            DomainError.Server.Unknown(70) to "notFound",
            DomainError.Server.Unknown(99) to "server",
            DomainError.Playback.NoPlayableSource to "playback",
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.entries.first()) to "input",
            DomainError.CapabilityUnsupported(CapabilityFeature.entries.first()) to "capability",
        )
        errors.forEach { (error, kind) -> assertEquals(kind, error.readerErrorKind(), "error $error") }

        val track = LibraryItem.Track(
            "t1", "Song", "a1", "Album", "Artist", "ar1", 1, 2, 60_000, AudioContainer.Flac, "art",
            starred = true, userRating = 3, playCount = 4, playability = LibraryPlayability.UnavailableOffline, metadataMissing = false,
        )
        val items = listOf(
            LibraryItem.Album("a1", "Album", "Artist", "ar1", 2001, "Rock", 120_000, 12, "art", false, 5, 9, true) to "album",
            LibraryItem.Artist("ar1", "Artist", 3, "art", null, null) to "artist",
            track to "track",
            LibraryItem.Playlist("p1", "Mix", 10, 3_000, "owner", "art") to "playlist",
            LibraryItem.Genre("Rock") to "genre",
        )
        items.forEach { (item, kind) ->
            val apple = item.toApple("server:x")
            assertEquals(kind, apple.kind)
            assertEquals("server:x", apple.providerInstanceId)
            assertEquals(item.rawId, apple.rawId)
            assertEquals(if (item is LibraryItem.Track) "unavailableOffline" else null, apple.playability, "only tracks carry playability")
        }
        val appleTrack = track.toApple("server:x")
        assertEquals(listOf<Any?>(true, 3, "Flac", "Album", "a1"), listOf(appleTrack.favourite, appleTrack.rating, appleTrack.sourceContainer, appleTrack.albumTitle, appleTrack.albumRawId))
    }

    // ---- Threading ------------------------------------------------------------------------------------------

    /** Every listener call and completion is made on the main thread; the reader runs elsewhere. */
    @Test
    fun everyDeliveryIsOnTheMainThreadAndTheReaderRunsOnItsOwn() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        val album = WindowRecorder()
        val search = SearchRecorder()
        val outcomes = OutcomeRecorder()
        val connection = AtomicReference<AppleLibraryReaderConnection?>(null)
        val completionOnMain = AtomicReference<Boolean?>(null)
        c.client.connect { result ->
            completionOnMain.store(NSThread.isMainThread)
            connection.store(result)
        }
        c.client.subscribeLibraryWindow(GRID, grid)
        c.client.subscribeLibraryWindow(request("album", rawId = albumId(0)), album)
        c.client.subscribeSearch(search).updateQuery("Album 0001")
        c.client.subscribeFavouriteOutcomes(outcomes)
        pumpUntil("the grid, the album, the search and connect") {
            grid.any { it.freshness.kind == "live" } && album.any { it.freshness.kind == "live" } &&
                search.any { it.scope == "serverAndDevice" } && connection.load() != null
        }
        assertTrue(c.client.toggleFavourite("album", albumId(1)))
        pumpUntil("the favourite's outcome") { outcomes.any { it.kind == "saved" } }

        assertEquals(0, grid.offMain + album.offMain + search.offMain + outcomes.offMain, "a listener was called off the main thread")
        assertEquals(true, completionOnMain.load(), "a completion was called off the main thread")
        assertFalse(c.onReader { NSThread.isMainThread }, "the reader must not run on the main thread")
        assertEquals(listOf("streamable", "streamable"), album.last.items.map { it.playability })
        assertEquals(null, connection.load()?.errorKind)
        assertEquals(true, connection.load()?.epochKnown)
        assertEquals(emptyList(), c.onReader { c.client.uncaughtFailures.toList() })
    }

    // ---- Freshness, coverage, playability through the real session --------------------------------------------

    /**
     * A relaunch paints what the device saw before any loading state, then each freshness kind
     * crosses as the core computed it: `cached(revalidating)` → `live`; offline `cached(offline)`,
     * `unavailable(notCachedOffline)` and a `localView`; back online `cached(stale)`; reconnect `live`.
     */
    @Test
    fun aRelaunchPaintsTheCacheFirstAndEachFreshnessCrossesAsItsKind() = facadeTest { h ->
        val first = h.client()
        val seenBefore = WindowRecorder()
        first.client.subscribeLibraryWindow(GRID, seenBefore)
        first.client.subscribeLibraryWindow(request("album", rawId = albumId(0)), WindowRecorder())
        pumpUntil("the first session's live grid") { seenBefore.any { it.freshness.kind == "live" } }
        val readAt = h.clock.now
        first.close()

        val second = h.client()
        val grid = WindowRecorder()
        second.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("the relaunched grid to go live") { grid.any { it.freshness.kind == "live" } }
        val paint = grid.all.first()
        assertEquals(listOf<Any?>("cached", "revalidating", readAt), listOf(paint.freshness.kind, paint.freshness.reason, paint.freshness.asOfEpochMillis))
        assertEquals(100, paint.items.size)
        assertEquals(listOf<Any?>("open", 250, 0), listOf(paint.coverage, paint.total, paint.leadingOffset))
        assertTrue(grid.none { it.freshness.kind == "loading" }, "a loading frame preceded or followed a cached paint")

        second.client.setOnline(false)
        pumpUntil("the grid to say offline") { grid.last.freshness.reason == "offline" }
        val album = WindowRecorder()
        val never = WindowRecorder()
        val local = WindowRecorder()
        second.client.subscribeLibraryWindow(request("album", rawId = albumId(0)), album)
        second.client.subscribeLibraryWindow(request("album", rawId = albumId(200)), never)
        second.client.subscribeLibraryWindow(request("albumList", listType = "newest"), local)
        pumpUntil("three offline screens") { album.any() && never.any() && local.any() }
        assertEquals(listOf("cached", "offline"), listOf(album.last.freshness.kind, album.last.freshness.reason))
        assertEquals(listOf("unavailableOffline", "unavailableOffline"), album.last.items.map { it.playability })
        assertEquals(listOf<Any?>("unavailable", "notCachedOffline", "unavailable"), listOf(never.last.freshness.kind, never.last.freshness.reason, never.last.itemsState))
        assertEquals(listOf<Any?>("cached", "offline", null, "localView"), listOf(local.last.freshness.kind, local.last.freshness.reason, local.last.freshness.asOfEpochMillis, local.last.order))
        assertTrue(local.last.items.isNotEmpty())

        second.client.setOnline(true)
        pumpUntil("the album opened offline to say stale") { album.last.freshness.reason == "stale" }
        val reconnected = AtomicReference<AppleLibraryReaderConnection?>(null)
        second.client.reconnect { reconnected.store(it) }
        pumpUntil("reconnect to revalidate the visible screens") {
            reconnected.load() != null && album.last.freshness.kind == "live" && local.last.freshness.kind == "live"
        }
        assertEquals(listOf("streamable", "streamable"), album.last.items.map { it.playability })
        assertEquals("server", local.last.order)
    }

    /** Failures, gone-ness and loading cross as their closed kinds; an error never replaces content. */
    @Test
    fun failuresGoneAndLoadingCrossAsTheirKinds() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        val gridSubscription = c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid") { grid.any { it.freshness.kind == "live" } }

        c.onReader { h.server.base.removedAlbums += albumId(6) }
        val gone = WindowRecorder()
        c.client.subscribeLibraryWindow(request("album", rawId = albumId(6)), gone)
        pumpUntil("the removed album") { gone.any { it.freshness.reason == "gone" } }
        assertEquals("unavailable", gone.last.freshness.kind)

        c.onReader { h.server.base.failing += "getAlbumList2" }
        gridSubscription.refresh()
        pumpUntil("the failed refresh") { grid.last.freshness.reason == "failed" }
        // The content shown stays under the failure — minus the album the detail read found gone,
        // which the cache excludes from every list (§16.11 gone rule 2).
        assertEquals(
            listOf<Any?>("cached", "server", 99, false),
            listOf(grid.last.freshness.kind, grid.last.freshness.errorKind, grid.last.items.size, grid.last.items.any { it.rawId == albumId(6) }),
        )

        c.onReader { h.server.base.failing += "getAlbum" }
        // An album the grid has shown keeps its header from the grid's row under the failure; its
        // track list, never read, is `unavailable`. One the device has never seen has nothing to keep.
        val shown = WindowRecorder()
        val unseen = WindowRecorder()
        c.client.subscribeLibraryWindow(request("album", rawId = albumId(9)), shown)
        c.client.subscribeLibraryWindow(request("album", rawId = albumId(209)), unseen)
        pumpUntil("the failed albums") { shown.any { it.freshness.reason == "failed" } && unseen.any { it.freshness.reason == "failed" } }
        assertEquals(
            listOf<Any?>("cached", "server", albumId(9), "unavailable"),
            listOf(shown.last.freshness.kind, shown.last.freshness.errorKind, shown.last.header?.rawId, shown.last.itemsState),
        )
        assertEquals(
            listOf<Any?>("unavailable", "server", null, "unavailable"),
            listOf(unseen.last.freshness.kind, unseen.last.freshness.errorKind, unseen.last.header, unseen.last.itemsState),
        )

        c.onReader {
            h.server.base.failing.clear()
            h.server.base.holdMatching = { it.parameters["type"] == "frequent" }
        }
        val loading = WindowRecorder()
        c.client.subscribeLibraryWindow(request("albumList", listType = "frequent"), loading)
        pumpUntil("the loading frame") { loading.any() }
        assertEquals(listOf("loading", "loading"), listOf(loading.last.freshness.kind, loading.last.itemsState))
        c.onReader { h.server.base.release() }
        pumpUntil("the list read to land") { loading.last.freshness.kind == "live" }
    }

    @Test
    fun coverageCompleteAndScanningCrossAsTheirKinds() = facadeTest { h ->
        val c = h.client()
        val artists = WindowRecorder()
        c.client.subscribeLibraryWindow(request("artists"), artists)
        pumpUntil("the artists") { artists.any { it.freshness.kind == "live" } }
        assertEquals(listOf<Any?>("complete", "artist"), listOf(artists.last.coverage, artists.last.items.first().kind))

        c.onReader { h.server.base.scanning = true }
        val scanning = WindowRecorder()
        c.client.subscribeLibraryWindow(request("albumList", listType = "alphabeticalByArtist"), scanning)
        pumpUntil("the grid read during a scan") { scanning.any { it.coverage == "unverifiedScanning" } }
    }

    @Test
    fun aServerWithNoScanStampIsStatedForTheAccountAndPerList() = facadeTest { h ->
        h.server.base.lastScan = null
        val c = h.client()
        val connection = AtomicReference<AppleLibraryReaderConnection?>(null)
        c.client.connect { connection.store(it) }
        val grid = WindowRecorder()
        c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("connect and the grid") { connection.load() != null && grid.any { it.coverage == "unverifiedNoEpoch" } }
        assertEquals(true, connection.load()?.serverReportsNoEpoch)
    }

    @Test
    fun aDownloadedTrackCrossesAsDownloaded() = facadeTest { h ->
        val c = h.client(downloads = { setOf("${albumId(0)}-track-0") })
        val album = WindowRecorder()
        c.client.subscribeLibraryWindow(request("album", rawId = albumId(0)), album)
        pumpUntil("the album") { album.any { it.freshness.kind == "live" } }
        assertEquals(listOf("downloaded", "streamable"), album.last.items.map { it.playability })
    }

    /** `leadingOffset` crosses: a rebased window says where it starts, and `loadBefore` reaches the top. */
    @Test
    fun aRebasedWindowCarriesItsLeadingOffset() = facadeTest { h ->
        h.server.base.scanning = true
        h.server.base.lastScan = "2026-09-22T12:00:00+02:00"
        val c = h.client()
        val grid = WindowRecorder()
        val sub = c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("the first page read during a scan") { grid.any { it.items.size == 100 && it.coverage == "unverifiedScanning" } }
        sub.setViewport(90, 99)
        sub.loadMore()
        pumpUntil("the second page") { grid.last.items.size == 200 }

        c.onReader { h.server.base.scanning = false }
        sub.setViewport(190, 199)
        sub.loadMore()
        pumpUntil("the scan's end to rebase around the viewport") { grid.last.leadingOffset == 100 }
        assertEquals((100 until 200).map(::albumId), grid.last.items.map { it.rawId })
        assertEquals("open", grid.last.coverage)

        sub.loadBefore()
        pumpUntil("the page before the window") { grid.last.leadingOffset == 0 && grid.last.items.size == 200 }
        assertEquals((0 until 200).map(::albumId), grid.last.items.map { it.rawId })
    }

    /**
     * The shell's viewport indexes refer to the publication IT has seen. Here the reader has
     * already prepended a page the main thread has not yet received, so index 0 on screen is server
     * position 100, not 0 — and the next page is read only because the facade carried the range
     * over to the reader's newer publication.
     */
    @Test
    fun aViewportIsReadAgainstThePublicationTheShellHasSeen() = facadeTest { h ->
        h.server.base.scanning = true
        h.server.base.lastScan = "2026-09-22T12:00:00+02:00"
        val c = h.client()
        val grid = WindowRecorder()
        val sub = c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("the first page") { grid.any { it.items.size == 100 } }
        sub.setViewport(90, 99)
        sub.loadMore()
        pumpUntil("the second page") { grid.last.items.size == 200 }
        c.onReader { h.server.base.scanning = false }
        sub.setViewport(190, 199)
        sub.loadMore()
        pumpUntil("the rebase") { grid.last.leadingOffset == 100 }
        val seen = grid.all.size

        // Prepend page 0 WITHOUT pumping the main thread: the shell still shows the rebased window.
        sub.loadBefore()
        waitOnReader(c, "the prepended page to be published") { c.onReader { h.server.base.log.count { it.parameters["offset"] == "0" } } >= 1 }
        c.onReader { } // the prepend's publication has been built by now
        assertEquals(seen, grid.all.size, "the prepended publication was delivered early; the test lost its precondition")
        assertEquals(100, grid.last.leadingOffset)

        val requestsBefore = c.onReader { h.server.base.log.size }
        sub.setViewport(0, 9) // server positions 100..109 in the publication on screen
        sub.loadMore()
        c.onReader { }
        waitOnReader(c, "the page after the window") { c.onReader { h.server.base.log.drop(requestsBefore).any { it.parameters["offset"] == "200" } } }
    }

    // ---- Home rows (CONF-86) ---------------------------------------------------------------------------------

    /** Each home row is its own subscription: one failing row, or one failing listener, leaves the rest live. */
    @Test
    fun homeRowsPublishIndependentlyAndOneFailingRowLeavesTheOthersLive() = facadeTest { h ->
        h.server.base.failing += "getAlbumList2[type=frequent]"
        val c = h.client()
        val recent = WindowRecorder()
        val frequent = WindowRecorder()
        val newest = WindowRecorder(throwAfterRecording = true)
        val favourites = WindowRecorder()
        val recentRow = c.client.subscribeLibraryWindow(request("homeRow", listType = "recent"), recent)
        val frequentRow = c.client.subscribeLibraryWindow(request("homeRow", listType = "frequent"), frequent)
        c.client.subscribeLibraryWindow(request("homeRow", listType = "newest"), newest)
        c.client.subscribeLibraryWindow(request("homeRow", listType = "favourites"), favourites)
        pumpUntil("every row to settle") {
            recent.any { it.freshness.kind == "live" } && newest.any { it.freshness.kind == "live" } &&
                favourites.any { it.freshness.kind == "live" } && frequent.any { it.freshness.reason == "failed" }
        }
        assertEquals(listOf(20, 20), listOf(recent.last.items.size, newest.last.items.size))
        assertEquals(listOf("unavailable", "server"), listOf(frequent.last.freshness.kind, frequent.last.freshness.errorKind!!))

        frequentRow.close()
        val before = recent.all.size
        recentRow.refresh()
        pumpUntil("the surviving row to publish again") { recent.all.size > before && recent.last.freshness.kind == "live" }
        assertEquals(listOf(20), listOf(recent.last.items.size))
    }

    // ---- Favourites and ratings (CONF-84, facade leg) ------------------------------------------------------------

    /**
     * A star is in the publication that follows the tap — before the server has applied it — and
     * stays through the send; the outcome arrives on its own stream.
     */
    @Test
    fun aStarShowsInThePublicationOfTheTap() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        val outcomes = OutcomeRecorder()
        c.client.subscribeFavouriteOutcomes(outcomes)
        c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid") { grid.any { it.freshness.kind == "live" } }
        c.onReader { h.server.holdBeforeApply += "star" }
        val before = grid.all.size

        assertTrue(c.client.toggleFavourite("album", albumId(3)))
        pumpUntil("the tap's publication") { grid.all.size > before }
        val tap = grid.all[before]
        assertEquals(true, tap.items.first { it.rawId == albumId(3) }.favourite, "the tap's publication did not carry the star")
        waitOnReader(c, "the send to be in flight") { c.onReader { h.server.heldCount } == 1 }
        assertFalse(c.onReader { h.server.base.albums[3].starred }, "the server applied the star before the test released it")

        c.onReader { h.server.release() }
        pumpUntil("the saved outcome") { outcomes.any { it.kind == "saved" } }
        val saved = outcomes.all.single()
        assertEquals(listOf<Any?>("album", albumId(3), "favourite", 1), listOf(saved.targetKind, saved.rawId, saved.field, saved.value))
        assertEquals(true, grid.last.items.first { it.rawId == albumId(3) }.favourite)
        assertTrue(grid.all.drop(before).all { pub -> pub.items.first { it.rawId == albumId(3) }.favourite == true }, "the star flickered off")
    }

    /** Offline, a change shows with no request at all, and counts as pending for the sign-out offer. */
    @Test
    fun offlineChangesShowAtOnceAndArePendingForSignOut() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid") { grid.any { it.freshness.kind == "live" } }
        c.client.setOnline(false)
        pumpUntil("offline") { grid.last.freshness.reason == "offline" }
        val requests = c.onReader { h.server.log.size }

        assertTrue(c.client.setFavourite("album", albumId(4), true))
        assertTrue(c.client.setRating("album", albumId(5), 4))
        assertFalse(c.client.setRating("album", albumId(5), 6), "a rating outside 0...5 was accepted")
        assertFalse(c.client.setFavourite("playlist", "p", true), "an unknown kind was accepted")
        assertFalse(c.client.setFavourite("album", " ", true), "a blank id was accepted")
        pumpUntil("both changes on screen") {
            grid.last.items.first { it.rawId == albumId(4) }.favourite == true && grid.last.items.first { it.rawId == albumId(5) }.rating == 4
        }
        val pending = AtomicReference<AppleLibraryPendingChanges?>(null)
        val onMain = AtomicReference<Boolean?>(null)
        c.client.pendingChangeCount { onMain.store(NSThread.isMainThread); pending.store(it) }
        pumpUntil("the pending count") { pending.load() != null }
        assertEquals(listOf<Any?>(2L, null, true), listOf(pending.load()?.count, pending.load()?.errorKind, onMain.load()))
        assertEquals(requests, c.onReader { h.server.log.size }, "an offline change issued a request")
    }

    // ---- Search (CONF-79, facade leg) --------------------------------------------------------------------------------

    @Test
    fun searchPublishesEachScopeWithTheDevicesCounts() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid") { grid.any { it.freshness.kind == "live" } }
        val search = SearchRecorder()
        val sub = c.client.subscribeSearch(search)

        sub.updateQuery("A")
        pumpUntil("a one-character search") { search.any { it.query == "A" } }
        assertEquals("deviceWhileServerPending", search.last.scope)

        sub.updateQuery("Album 0001")
        pumpUntil("the server's answer") { search.any { it.query == "Album 0001" && it.scope == "serverAndDevice" } }
        val merged = search.last
        assertEquals("server", merged.rows.first { it.rawId == albumId(1) }.source)
        assertEquals(listOf<Any?>(null, null, null, null), listOf(merged.errorKind, merged.seenArtistCount, merged.seenAlbumCount, merged.seenTrackCount))

        c.client.setOnline(false)
        pumpUntil("the offline scope") { search.last.scope == "deviceOffline" }
        assertTrue((search.last.seenAlbumCount ?: 0) >= 100, "offline scope without the device's counts")
        assertNotNull(search.last.seenTrackCount)

        c.client.setOnline(true)
        pumpUntil("the server again") { search.last.scope == "serverAndDevice" }
        c.onReader { h.server.failWithCode["search3"] = 40 }
        sub.updateQuery("Album 0002")
        pumpUntil("the failed search") { search.any { it.query == "Album 0002" && it.scope == "deviceServerFailed" } }
        val failed = search.last
        assertEquals("invalidCredentials", failed.errorKind)
        assertEquals("device", failed.rows.first { it.rawId == albumId(2) }.source, "the device's rows were dropped by a failure")
        assertNotNull(failed.seenAlbumCount)
    }

    /** Reconnect tells the session the server is back, so an offline search re-runs without a keystroke. */
    @Test
    fun reconnectRerunsAnOfflineSearchAndReportsTheSession() = facadeTest { h ->
        val c = h.client()
        val search = SearchRecorder()
        val sub = c.client.subscribeSearch(search)
        c.client.setOnline(false)
        sub.updateQuery("Album 0003")
        pumpUntil("the offline search") { search.any { it.query == "Album 0003" && it.scope == "deviceOffline" } }
        val connection = AtomicReference<AppleLibraryReaderConnection?>(null)
        c.client.reconnect { connection.store(it) }
        pumpUntil("reconnect and the server's answer") { connection.load() != null && search.last.scope == "serverAndDevice" }
        assertEquals(listOf<Any?>(true, false, 0L, null), connection.load()!!.let { listOf(it.epochKnown, it.serverReportsNoEpoch, it.discardedPendingChanges, it.errorKind) })
    }

    // ---- Close ----------------------------------------------------------------------------------------------------

    /**
     * A publication the reader built BEFORE close, still on its way to the main thread, is never
     * delivered after close returns. And a second close is a no-op.
     */
    @Test
    fun aPublicationBuiltBeforeCloseIsNeverDeliveredAfterIt() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        val sub = c.client.subscribeLibraryWindow(GRID, grid)
        c.onReader { } // the open has run: its publications are queued for the main thread
        assertTrue(c.onReader { h.server.log.isNotEmpty() }, "the open issued nothing; the test lost its precondition")
        sub.close()
        sub.close()
        pumpFor(300.milliseconds)
        assertEquals(0, grid.all.size, "a publication was delivered after close")

        // The client is still healthy: another subscription publishes.
        val other = WindowRecorder()
        c.client.subscribeLibraryWindow(GRID, other)
        pumpUntil("another subscription") { other.any { it.freshness.kind == "live" } }
    }

    /** Close cancels the screen's in-flight read, and nothing is published after it. */
    @Test
    fun closeCancelsTheReadInFlight() = facadeTest { h ->
        val c = h.client()
        c.onReader { h.server.base.holdMatching = { it.endpoint == "getAlbumList2" } }
        val grid = WindowRecorder()
        val sub = c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("the loading frame") { grid.any() }
        waitOnReader(c, "the page read to be held") { c.onReader { h.server.base.heldCount } == 1 }
        val delivered = grid.all.size
        sub.close()
        c.onReader { }
        waitOnReader(c, "the held read to be cancelled") { c.onReader { h.server.base.cancelled } == 1 }
        pumpFor(200.milliseconds)
        assertEquals(delivered, grid.all.size)
    }

    /** A closed client answers every entry point without a crash, and says it is closed. */
    @Test
    fun aClosedClientAnswersEveryEntryPoint() = facadeTest { h ->
        val c = h.client()
        val sub = c.client.subscribeLibraryWindow(GRID, WindowRecorder())
        c.close()
        c.client.close()
        sub.loadMore()
        sub.setViewport(0, 3)
        sub.close()
        c.client.setOnline(false)
        c.client.setForeground(true)
        c.client.setNetworkConstrained(true)
        assertFalse(c.client.toggleFavourite("album", albumId(1)))
        val window = WindowRecorder()
        val search = SearchRecorder()
        c.client.subscribeLibraryWindow(GRID, window)
        c.client.subscribeSearch(search).updateQuery("x")
        val connection = AtomicReference<AppleLibraryReaderConnection?>(null)
        c.client.connect { connection.store(it) }
        pumpUntil("the closed answers") { window.any() && search.any() && connection.load() != null }
        assertEquals(listOf<Any?>("unavailable", "failed", "closed"), listOf(window.last.freshness.kind, window.last.freshness.reason, window.last.freshness.errorKind))
        assertEquals("closed", search.last.errorKind)
        assertEquals("closed", connection.load()?.errorKind)
    }

    // ---- No exception crosses ----------------------------------------------------------------------------------

    /** A transport that throws — with a credential-bearing URL in its message — is a closed kind, not a crash. */
    @Test
    fun aThrowingTransportPublishesAFailureAndNoUrl() = facadeTest { h ->
        val c = h.client()
        c.onReader { h.transport.throwFor += "getAlbum" }
        val album = WindowRecorder()
        c.client.subscribeLibraryWindow(request("album", rawId = albumId(7)), album)
        pumpUntil("the failure") { album.any { it.freshness.reason == "failed" } }
        assertEquals(listOf<Any?>("unavailable", "unreachable"), listOf(album.last.freshness.kind, album.last.freshness.errorKind))
        assertNoCanary(album.all)
        assertEquals(emptyList(), c.onReader { c.client.uncaughtFailures.toList() })
    }

    /** The reader failing to write keeps what was shown, labelled as the reader's own failure. */
    @Test
    fun aThrowingReaderKeepsTheContentAndSaysItFailed() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        val sub = c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid") { grid.any { it.freshness.kind == "live" } }
        c.onReader {
            h.driver.failWrite = { it.contains("cache_list_member", ignoreCase = true) && it.trimStart().startsWith("INSERT", ignoreCase = true) }
            h.server.base.lastScan = "2026-09-22T13:00:00+02:00" // forces a rebase, which rewrites the window
        }
        sub.refresh()
        pumpUntil("the internal failure") { grid.last.freshness.reason == "internalFailure" }
        assertEquals(listOf<Any?>("cached", 100), listOf(grid.last.freshness.kind, grid.last.items.size))
    }

    /** A request the core cannot open is an `input` failure publication; nothing throws. */
    @Test
    fun anInvalidRequestIsAnInputFailure() = facadeTest { h ->
        val c = h.client()
        val requests = listOf(
            request("nonsense"),
            request("album"),
            request("albumList", listType = "byYear"),
            request("albumList", listType = "sideways"),
            request("homeRow", listType = "byGenre"),
            request("songsByGenre"),
        )
        val recorders = requests.map { req -> WindowRecorder().also { c.client.subscribeLibraryWindow(req, it) } }
        pumpUntil("every invalid request to answer") { recorders.all { it.any() } }
        recorders.forEachIndexed { index, recorder ->
            val freshness = recorder.last.freshness
            assertEquals(listOf<Any?>("unavailable", "failed", "input"), listOf(freshness.kind, freshness.reason, freshness.errorKind), "request $index")
        }
        assertEquals(emptyList(), c.onReader { c.client.uncaughtFailures.toList() })
    }

    /**
     * A session that cannot be built — its failure text carrying an address and credentials — is
     * answered by every entry point with a closed kind, and that text reaches nothing.
     */
    @Test
    fun aSessionThatCannotBeBuiltAnswersEveryEntryPoint() = facadeTest { h ->
        val c = h.client(compose = { error("GET https://music.example/rest/ping.view?u=$CANARY-user&p=$CANARY-password failed") })
        val window = WindowRecorder()
        val search = SearchRecorder()
        val outcomes = OutcomeRecorder()
        c.client.subscribeFavouriteOutcomes(outcomes)
        c.client.subscribeLibraryWindow(GRID, window)
        c.client.subscribeSearch(search).updateQuery("abc")
        assertTrue(c.client.toggleFavourite("album", albumId(1)))
        val pending = AtomicReference<AppleLibraryPendingChanges?>(null)
        val connection = AtomicReference<AppleLibraryReaderConnection?>(null)
        c.client.pendingChangeCount { pending.store(it) }
        c.client.connect { connection.store(it) }
        pumpUntil("every entry point to answer") {
            window.any() && search.any() && outcomes.any() && pending.load() != null && connection.load() != null
        }
        assertEquals(listOf<Any?>("unavailable", "internalFailure", null), listOf(window.last.freshness.kind, window.last.freshness.reason, window.last.freshness.errorKind))
        assertEquals(listOf<Any?>("deviceServerFailed", "internalFailure"), listOf(search.last.scope, search.last.errorKind))
        assertEquals("notRecorded", outcomes.last.kind)
        assertEquals(listOf<Any?>(null, "internalFailure"), listOf(pending.load()?.count, pending.load()?.errorKind))
        assertEquals("internalFailure", connection.load()?.errorKind)
        assertNoCanary(window.all)
        assertTrue(search.all.none { CANARY in it.query || CANARY in (it.errorKind ?: "") }, "failure text reached a search publication")
    }

    @Test
    fun theAccountNeverRendersItsCredentials() {
        val account = AppleLibraryReaderAccount("server:x", "https://$CANARY.example", "$CANARY-user", "$CANARY-password", false)
        assertFalse(CANARY in account.toString(), "the account rendered a credential: $account")
    }

    // ---- Harness -----------------------------------------------------------------------------------------------

    private class ThrowingTransport(private val delegate: LibraryEndpointTransport) : LibraryEndpointTransport {
        /** Endpoints that throw an arbitrary exception whose message carries a credential-bearing URL. */
        val throwFor = mutableSetOf<String>()

        override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
            if (endpoint in throwFor) {
                throw IllegalStateException("GET https://music.example/rest/$endpoint.view?u=$CANARY-user&t=$CANARY-token&s=$CANARY-salt failed")
            }
            return delegate.request(endpoint, parameters)
        }
    }

    private class Harness(val driver: CountingSqlDriver, val server: SessionTestServer, val clock: ManualWallClock) {
        val transport = ThrowingTransport(server)
        val clients = mutableListOf<FacadeClient>()

        fun client(
            config: LibraryReaderConfig = LibraryReaderConfig(lookAheadMaxPerViewport = 0),
            search: LibrarySearchConfig = LibrarySearchConfig(debounceMillis = 0),
            downloads: DownloadedTrackSource = DownloadedTrackSource.None,
            compose: ((CoroutineScope) -> AppleLibraryReaderComposition)? = null,
        ): FacadeClient {
            val dispatcher = newLibraryReaderDispatcher()
            val client = AppleLibraryReaderClient(
                compose ?: { scope ->
                    val store = DulcetDatabaseStore.open(driver)
                    AppleLibraryReaderComposition(
                        LibraryReaderSession(store.database, SeenCacheStore(store, clock).bind(BINDING), transport, scope, config, downloads),
                        search,
                    )
                },
                dispatcher,
                Dispatchers.Main,
            )
            return FacadeClient(client, dispatcher).also { clients += it }
        }
    }

    private class FacadeClient(val client: AppleLibraryReaderClient, private val dispatcher: CloseableCoroutineDispatcher) {
        private var closed = false

        /** Runs [block] on the reader's thread after everything queued before it. Never pumps main. */
        fun <T> onReader(block: () -> T): T = runBlocking(dispatcher) { block() }

        /** Closes the client and waits for its thread to stop, so the test may close the database. */
        fun close() {
            if (closed) return
            closed = true
            client.close()
            runBlocking { withTimeout(15.seconds) { client.awaitTermination() } }
        }
    }

    private fun facadeTest(block: (Harness) -> Unit) {
        check(NSThread.isMainThread) { "facade tests assert main-thread delivery and must run on the main thread" }
        val driver = CountingSqlDriver(createTestDriver())
        val harness = Harness(driver, SessionTestServer(FakeReaderServer()), ManualWallClock(now = 3_000_000))
        try {
            block(harness)
        } finally {
            harness.clients.forEach { runCatching { it.close() } }
            // The reader threads have stopped; nothing touches the database after this.
            driver.close()
        }
    }

    private class WindowRecorder(private val throwAfterRecording: Boolean = false) : AppleLibraryWindowListener {
        private val seen = AtomicReference<List<AppleLibraryWindowPublication>>(emptyList())
        private val offMainCalls = AtomicInt(0)
        val all: List<AppleLibraryWindowPublication> get() = seen.load()
        val last: AppleLibraryWindowPublication get() = all.last()
        val offMain: Int get() = offMainCalls.load()

        fun any(predicate: (AppleLibraryWindowPublication) -> Boolean = { true }) = all.any(predicate)
        fun none(predicate: (AppleLibraryWindowPublication) -> Boolean) = all.none(predicate)

        override fun onWindowPublication(publication: AppleLibraryWindowPublication) {
            if (!NSThread.isMainThread) offMainCalls.addAndFetch(1)
            while (true) {
                val current = seen.load()
                if (seen.compareAndSet(current, current + publication)) break
            }
            if (throwAfterRecording) throw IllegalStateException("a listener that fails")
        }
    }

    private class SearchRecorder : AppleLibrarySearchListener {
        private val seen = AtomicReference<List<AppleLibrarySearchPublication>>(emptyList())
        private val offMainCalls = AtomicInt(0)
        val all: List<AppleLibrarySearchPublication> get() = seen.load()
        val last: AppleLibrarySearchPublication get() = all.last()
        val offMain: Int get() = offMainCalls.load()

        fun any(predicate: (AppleLibrarySearchPublication) -> Boolean = { true }) = all.any(predicate)

        override fun onSearchPublication(publication: AppleLibrarySearchPublication) {
            if (!NSThread.isMainThread) offMainCalls.addAndFetch(1)
            while (true) {
                val current = seen.load()
                if (seen.compareAndSet(current, current + publication)) break
            }
        }
    }

    private class OutcomeRecorder : AppleLibraryFavouriteOutcomeListener {
        private val seen = AtomicReference<List<AppleLibraryFavouriteOutcome>>(emptyList())
        private val offMainCalls = AtomicInt(0)
        val all: List<AppleLibraryFavouriteOutcome> get() = seen.load()
        val last: AppleLibraryFavouriteOutcome get() = all.last()
        val offMain: Int get() = offMainCalls.load()

        fun any(predicate: (AppleLibraryFavouriteOutcome) -> Boolean = { true }) = all.any(predicate)

        override fun onOutcome(outcome: AppleLibraryFavouriteOutcome) {
            if (!NSThread.isMainThread) offMainCalls.addAndFetch(1)
            while (true) {
                val current = seen.load()
                if (seen.compareAndSet(current, current + outcome)) break
            }
        }
    }

    private companion object {
        const val CANARY = "canary7Q"
        val BINDING = CacheBinding("server:facade", "https://music.example", "listener")
        val GRID = request("albumList", listType = "alphabeticalByName")

        fun request(kind: String, rawId: String? = null, listType: String? = null, genre: String? = null) =
            AppleLibraryWindowRequest(kind, rawId, listType, genre, 0, 0, null)

        /** Runs the main run loop, delivering whatever the facade queued for the main thread. */
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

        /** Polls reader-thread state WITHOUT delivering anything to the main thread. */
        fun waitOnReader(client: FacadeClient, what: String, timeout: Duration = 20.seconds, condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (start.elapsedNow() > timeout) fail("timed out after $timeout waiting for $what")
                client.onReader { }
                platform.posix.usleep(2_000u)
            }
        }

        fun assertKinds(family: String, actual: Map<*, String>, expected: Map<*, String>) {
            assertEquals(expected, actual, "$family kinds")
            assertDistinct(family, actual.values.toList())
        }

        fun assertDistinct(family: String, kinds: List<String>) {
            assertEquals(kinds.size, kinds.toSet().size, "two $family values share a kind: $kinds")
        }

        fun assertNoCanary(publications: List<AppleLibraryWindowPublication>) {
            val strings = publications.flatMap { publication ->
                listOfNotNull(
                    publication.freshness.kind, publication.freshness.reason, publication.freshness.errorKind,
                    publication.coverage, publication.itemsState, publication.order, publication.anchorRawId,
                ) + (publication.items + listOfNotNull(publication.header)).flatMap { item ->
                    listOfNotNull(
                        item.kind, item.providerInstanceId, item.rawId, item.title, item.artistName, item.artistRawId,
                        item.albumTitle, item.albumRawId, item.genre, item.sourceContainer, item.artworkKey, item.owner, item.playability,
                    )
                }
            }
            assertTrue(publications.isNotEmpty())
            assertTrue(strings.none { CANARY in it }, "a published string carries the failure's URL: ${strings.filter { CANARY in it }}")
        }
    }
}
