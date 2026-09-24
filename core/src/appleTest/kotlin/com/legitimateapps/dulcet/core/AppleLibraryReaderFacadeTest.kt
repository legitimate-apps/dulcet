package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

        assertKinds(
            "sourceContainer",
            AudioContainer.entries.associateWith { it.appleKind() },
            mapOf(
                AudioContainer.Mp3 to "Mp3", AudioContainer.Mp4 to "Mp4", AudioContainer.Wav to "Wav",
                AudioContainer.Flac to "Flac", AudioContainer.Ogg to "Ogg", AudioContainer.AdtsAac to "AdtsAac",
            ),
        )
        fun searchRow(type: SearchResultType, playability: LibraryPlayability?) = LibrarySearchRow(
            SearchResultItem(ProviderItemId("server:x", "r1"), type, "Title", emptyList(), null, null, null, null, null, AudioContainer.Ogg, null, null),
            SearchResultSource.Device, favourite = null, rating = null, playability = playability,
        ).toApple()
        assertEquals(
            listOf("downloaded", "streamable", "unavailableOffline"),
            LibraryPlayability.entries.map { searchRow(SearchResultType.Track, it).playability },
        )
        assertEquals(null, searchRow(SearchResultType.Album, null).playability)
        assertEquals("Ogg", searchRow(SearchResultType.Track, LibraryPlayability.Streamable).sourceContainer)
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
     * `unavailable(notCachedOffline)` and a `localView`. Reachability alone then reconnects, and
     * every screen goes `live` — the album never read included, which is READ rather than left on a
     * spinner — without ever saying `stale` on the way; a foreground reconnect reports its reading.
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

        val marks = listOf(album.all.size, never.all.size)
        second.client.setOnline(true)
        pumpUntil("reachability to reconnect and revalidate the visible screens") {
            album.last.freshness.kind == "live" && local.last.freshness.kind == "live" && never.last.freshness.kind == "live"
        }
        assertTrue(album.all.drop(marks[0]).none { it.freshness.reason == "stale" }, "a screen said stale on the way back")
        assertTrue(never.all.drop(marks[1]).all { it.freshness.kind in setOf("loading", "live") }, "the album never read: ${never.all.drop(marks[1]).map { it.freshness.kind }}")
        assertEquals(listOf("streamable", "streamable"), album.last.items.map { it.playability })
        assertEquals("server", local.last.order)

        val reconnected = AtomicReference<AppleLibraryReaderConnection?>(null)
        second.client.reconnect { reconnected.store(it) }
        pumpUntil("a foreground reconnect") { reconnected.load() != null }
        assertEquals(listOf<Any?>(true, null), listOf(reconnected.load()?.epochKnown, reconnected.load()?.errorKind))
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

    /** A count the core cannot read reaches the shell as unknown, never as zero (§14.7). */
    @Test
    fun aPendingCountThatCannotBeReadIsUnknownNotZero() = facadeTest { h ->
        val c = h.client()
        c.client.setOnline(false)
        assertTrue(c.client.setFavourite("album", albumId(4), true))
        c.onReader { h.driver.failRead = { it.contains("mutation_outbox", ignoreCase = true) } }
        val unreadable = AtomicReference<AppleLibraryPendingChanges?>(null)
        c.client.pendingChangeCount { unreadable.store(it) }
        pumpUntil("the unreadable count") { unreadable.load() != null }
        val failedReads = c.onReader {
            h.driver.failRead = null
            h.driver.failedReads
        }
        assertTrue(failedReads > 0, "the injected read failure never fired; the test measured nothing")
        assertEquals(listOf<Any?>(null, "internalFailure"), listOf(unreadable.load()?.count, unreadable.load()?.errorKind))

        val readable = AtomicReference<AppleLibraryPendingChanges?>(null)
        c.client.pendingChangeCount { readable.store(it) }
        pumpUntil("the readable count") { readable.load() != null }
        assertEquals(listOf<Any?>(1L, null), listOf(readable.load()?.count, readable.load()?.errorKind))
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

    /** Reconnect alone re-runs an offline search without a keystroke: the core revalidates open searches. */
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

    /**
     * The same at the client level: what the reader queued for the main thread before the client
     * closed — a window's first frame and a search's device rows — is dropped, not delivered after.
     */
    @Test
    fun aClientCloseDropsWhatWasQueuedBeforeIt() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        val search = SearchRecorder()
        c.client.subscribeLibraryWindow(GRID, grid)
        c.client.subscribeSearch(search).updateQuery("Album 0001")
        c.onReader { } // both have run on the reader: their first publications are queued for the main thread
        assertTrue(c.onReader { h.server.log.isNotEmpty() }, "the open issued nothing; the test lost its precondition")
        c.client.close()
        pumpFor(300.milliseconds)
        assertEquals(listOf(0, 0), listOf(grid.all.size, search.all.size), "a publication was delivered after the client closed")
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

    // ---- Reachability, close and the review's pinned rules ------------------------------------------------------

    /**
     * A reconnect whose epoch read fails says so — the error kind, and no epoch known — and changes
     * nothing: the reader stays offline and the search keeps saying so. The next reachability report
     * is not swallowed: it reconnects, and the search comes back. `connect` names its failure too.
     */
    @Test
    fun aReconnectWhoseEpochReadFailsSaysSoAndTheNextReportRetries() = facadeTest { h ->
        val c = h.client()
        val connected = AtomicReference<AppleLibraryReaderConnection?>(null)
        c.client.connect { connected.store(it) }
        pumpUntil("connect") { connected.load() != null }
        assertEquals(listOf<Any?>(true, null), listOf(connected.load()?.epochKnown, connected.load()?.errorKind))
        val search = SearchRecorder()
        val sub = c.client.subscribeSearch(search)
        c.client.setOnline(false)
        sub.updateQuery("Album 0003")
        pumpUntil("the offline search") { search.any { it.query == "Album 0003" && it.scope == "deviceOffline" } }

        c.onReader { h.server.failWithError["getScanStatus"] = DomainError.Transport.Timeout }
        val scanReads = c.onReader { h.server.count("getScanStatus") }
        val reconnected = AtomicReference<AppleLibraryReaderConnection?>(null)
        c.client.reconnect { reconnected.store(it) }
        pumpUntil("the failed reconnect") { reconnected.load() != null }
        assertTrue(c.onReader { h.server.count("getScanStatus") } > scanReads, "fixture: the epoch read was attempted")
        assertEquals(
            listOf<Any?>(false, false, "timeout"),
            reconnected.load()!!.let { listOf(it.epochKnown, it.serverReportsNoEpoch, it.errorKind) },
            "a failed reconnect was reported as a success",
        )
        assertFalse(c.onReader { h.sessions.last().reader.online }, "a failed reconnect left the reader online")
        pumpFor(200.milliseconds)
        assertEquals("deviceOffline", search.last.scope)

        c.onReader { h.server.failWithError.clear() }
        c.client.setOnline(true)
        pumpUntil("the next report to reconnect and the search to come back") { search.last.scope == "serverAndDevice" }

        c.onReader { h.server.failWithError["getMusicFolders"] = DomainError.Transport.Unreachable }
        val failedConnect = AtomicReference<AppleLibraryReaderConnection?>(null)
        c.client.connect { failedConnect.store(it) }
        pumpUntil("the failed connect") { failedConnect.load() != null }
        assertEquals(listOf<Any?>(false, "unreachable"), listOf(failedConnect.load()?.epochKnown, failedConnect.load()?.errorKind))
    }

    /**
     * Cancelling a reconnect operation stops only the caller's wait (§7.2: one completion, as
     * `cancelled`); the reconnect itself, which a reachability report may share, runs on.
     */
    @Test
    fun cancellingAReconnectStopsTheWaitNotTheReconnect() = facadeTest { h ->
        val c = h.client()
        val search = SearchRecorder()
        val sub = c.client.subscribeSearch(search)
        c.client.setOnline(false)
        sub.updateQuery("Album 0003")
        pumpUntil("the offline search") { search.any { it.scope == "deviceOffline" } }
        c.onReader { h.server.base.holdMatching = { it.endpoint == "getScanStatus" } }
        val calls = AtomicInt(0)
        val result = AtomicReference<AppleLibraryReaderConnection?>(null)
        val op = c.client.reconnect { calls.addAndFetch(1); result.store(it) }
        waitOnReader(c, "the epoch read to be held") { c.onReader { h.server.base.heldCount } == 1 }
        op.cancel()
        op.cancel()
        pumpUntil("the cancelled completion") { result.load() != null }
        assertEquals("cancelled", result.load()?.errorKind)
        c.onReader {
            h.server.base.holdMatching = null
            h.server.base.release()
        }
        pumpUntil("the reconnect to finish anyway") { search.last.scope == "serverAndDevice" }
        assertEquals(1, calls.load(), "a completion ran twice")
    }

    /**
     * A closed search subscription is released: 40 closed searches — 20 that reached the server and
     * 20 of one character — leave nothing in the session's registries, and are collected. The
     * control proves the collector ran: a closed window subscription is collected too.
     *
     * The defect this pins kept all forty, through the favourites' change listeners, for the life
     * of the session. One exception is allowed, and bounded. In 13 of 200 rounds run on a fresh
     * database, exactly one closed subscription stayed reachable until the reader's thread stopped.
     * Its listener was dropped, its core search was closed and, in the one round that checked,
     * collected. Nothing the facade or the core keeps was holding it: it outlived dropping the
     * composition, cancelling the reader scope's work and cancelling the main scope. The cause is
     * not known. So at most one may outlive the collections here, it must be gone once the client
     * has closed, and any such one is printed into the test's output rather than hidden.
     */
    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, kotlin.experimental.ExperimentalNativeApi::class)
    @Test
    fun closedSearchSubscriptionsAreReleased() = facadeTest { h ->
        val c = h.client()
        val closedWindow = closeWindowAfterLive(c)
        val server = (1..20).map { closeSearchAfter(c, "Album 00" + (it % 10), serverAnswer = true) }
        val local = (1..20).map { closeSearchAfter(c, "A", serverAnswer = false) }
        c.client.setOnline(false)
        c.client.setOnline(true)
        c.onReader { }
        fun reachable() = server.withIndex().filter { it.value.value != null }.map { "server#${it.index}" } +
            local.withIndex().filter { it.value.value != null }.map { "local#${it.index}" }
        repeat(4) {
            pumpFor(100.milliseconds)
            c.onReader { }
            kotlin.native.runtime.GC.collect()
        }
        assertEquals(null, closedWindow.value, "control: a closed window subscription was not collected, so collection proves nothing")
        assertEquals(
            listOf(0, 0),
            c.onReader { h.sessions.last().let { listOf(it.favourites.changeListenerCount, it.openSearchCount) } },
            "the session still registers a closed search",
        )
        val kept = reachable()
        if (kept.isNotEmpty()) println("closedSearchSubscriptionsAreReleased: $kept still reachable after 4 collections")
        assertTrue(kept.size <= 1, "closed search subscriptions still reachable: $kept")

        c.close()
        repeat(4) {
            pumpFor(50.milliseconds)
            kotlin.native.runtime.GC.collect()
        }
        assertEquals(emptyList<String>(), reachable(), "a closed search subscription outlived its client")
    }

    // Opened and closed in their own frames, so no local on the test's stack keeps them reachable.
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    private fun closeWindowAfterLive(c: FacadeClient): kotlin.native.ref.WeakReference<Any> {
        val grid = WindowRecorder()
        val window = c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid") { grid.any { it.freshness.kind == "live" } }
        window.close()
        c.onReader { }
        return kotlin.native.ref.WeakReference(window)
    }

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    private fun closeSearchAfter(c: FacadeClient, query: String, serverAnswer: Boolean): kotlin.native.ref.WeakReference<Any> {
        val results = SearchRecorder()
        val sub = c.client.subscribeSearch(results)
        sub.updateQuery(query)
        pumpUntil("search $query") { results.any { it.query == query && (!serverAnswer || it.scope == "serverAndDevice") } }
        sub.close()
        c.onReader { }
        return kotlin.native.ref.WeakReference(sub)
    }

    /**
     * The realistic viewport trigger: the person sits near the end, a revalidation shortens the list,
     * and the shell's next viewport — indexes into the longer list it still shows — reaches the
     * reader after the shorter list was built. Accepted and clamped: the screen stays `live`, and a
     * viewport never publishes a failure, however far out of range.
     */
    @Test
    fun aViewportPastTheEndOfAListThatShrankNeverPublishesAFailure() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        val sub = c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid") { grid.any { it.freshness.kind == "live" && it.items.size == 100 } }
        sub.setViewport(90, 99)
        val listKey = ListRequestSpec.of(LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)).listKey
        c.onReader {
            h.server.base.albums.subList(50, h.server.base.albums.size).clear()
            h.server.base.lastScan = "2026-09-22T14:00:00+02:00"
        }
        sub.refresh()
        // Wait on the reader, NOT the main thread: the shell keeps showing the 100-item list.
        waitOnReader(c, "the shortened list to be written") {
            c.onReader { h.sessions.last().reader.cache.listState(listKey)?.total } == 50
        }
        c.onReader { }
        assertTrue(grid.last.items.size == 100, "the shortened list was delivered early; the test lost its precondition")

        sub.setViewport(95, 99)
        sub.setViewport(150, 160)
        pumpUntil("the shortened list") { grid.last.items.size == 50 }
        pumpFor(200.milliseconds)
        assertTrue(grid.none { it.freshness.reason == "internalFailure" }, "a viewport published a failure: ${grid.all.map { it.freshness.kind + "/" + it.freshness.reason }}")
        assertEquals(listOf<Any?>("live", 50), listOf(grid.last.freshness.kind, grid.last.items.size))
        assertEquals(emptyList(), c.onReader { c.client.uncaughtFailures.toList() + h.sessions.last().reader.uncaughtFailures })
    }

    /** The carry-over clamps to the newer publication: a shrunk list, a reversed range, a gone anchor. */
    @Test
    fun translateViewportClampsToTheReadersLatestPublication() {
        fun publication(sequence: Int, size: Int) = AppleLibraryWindowPublication(
            sequence, AppleLibraryReaderFreshness("live", null, null, null), "complete", size, 0, null,
            (0 until size).map { LibraryItem.Genre("g$it").toApple("server:x") }, "present", "server", null, null,
        )
        val shown = publication(1, 100)
        val shrunk = publication(2, 50)
        fun seen(first: Int, last: Int) = SeenViewport(shown.sequence, "genre\u0000g$first", "genre\u0000g$last")
        assertEquals(49 to 49, translateViewport(95, 99, seen(95, 99), shrunk), "anchors gone, range past the end")
        assertEquals(40 to 45, translateViewport(40, 45, seen(40, 45), shrunk), "anchors present")
        assertEquals(99 to 99, translateViewport(150, 160, null, shown), "nothing seen yet, far past the end")
        assertEquals(10 to 10, translateViewport(10, 5, null, shown), "reversed")
        assertEquals(0 to 3, translateViewport(-4, 3, null, shown), "before the start")
        assertEquals(7 to 9, translateViewport(7, 9, null, null), "nothing emitted: unchanged, the core clamps")
    }

    /**
     * The facade's own failure publication keeps what was shown, with its age: a `live` screen's
     * content is labelled with when it was published, never "age unknown" (CORPUS §4 item 11).
     */
    @Test
    fun theFacadesFailurePublicationKeepsTheContentAndItsAge() {
        val items = listOf(LibraryItem.Genre("Rock").toApple("server:x"))
        val live = AppleLibraryWindowPublication(
            4, AppleLibraryReaderFreshness("live", null, null, null), "complete", 1, 0, null, items, "present", "server", null, null,
        )
        val overLive = readerFailurePublication(5, live, errorKind = null, previousLiveAt = 1_234)
        assertEquals(listOf<Any?>(5, "cached", "internalFailure", null, 1_234L), listOf(overLive.sequence, overLive.freshness.kind, overLive.freshness.reason, overLive.freshness.errorKind, overLive.freshness.asOfEpochMillis))
        assertEquals(items, overLive.items)
        assertEquals(listOf<Any?>("complete", 1, "present"), listOf(overLive.coverage, overLive.total, overLive.itemsState))

        val cached = AppleLibraryWindowPublication(
            4, AppleLibraryReaderFreshness("cached", "stale", null, 77), "open", null, 0, null, items, "present", "server", null, null,
        )
        assertEquals(77L, readerFailurePublication(5, cached, errorKind = "input", previousLiveAt = 1_234).freshness.asOfEpochMillis, "a cached screen keeps its own age")
        val none = readerFailurePublication(1, null, errorKind = "closed")
        assertEquals(listOf<Any?>("unavailable", "failed", "closed", 0), listOf(none.freshness.kind, none.freshness.reason, none.freshness.errorKind, none.items.size))
    }

    /**
     * §7.2's cancel reaches an operation still queued behind other work: exactly one completion, as
     * `cancelled`. The control — the same queue with no cancel — completes with the count.
     */
    @Test
    fun cancellingAQueuedOperationCompletesItOnceAsCancelled() = facadeTest { h ->
        val c = h.client()
        c.client.setOnline(false)
        assertTrue(c.client.setFavourite("album", albumId(4), true))
        fun queued(cancel: Boolean): List<AppleLibraryPendingChanges> {
            val gate = kotlin.concurrent.atomics.AtomicBoolean(false)
            val entered = kotlin.concurrent.atomics.AtomicBoolean(false)
            assertTrue(c.client.onReader { entered.store(true); while (!gate.load()) platform.posix.usleep(1_000u) })
            val results = AtomicReference<List<AppleLibraryPendingChanges>>(emptyList())
            val op = c.client.pendingChangeCount { result -> results.store(results.load() + result) }
            val start = TimeSource.Monotonic.markNow()
            while (!entered.load()) {
                if (start.elapsedNow() > 10.seconds) fail("the reader never reached the gate")
                platform.posix.usleep(1_000u)
            }
            if (cancel) op.cancel()
            gate.store(true)
            pumpUntil("the completion") { results.load().isNotEmpty() }
            pumpFor(200.milliseconds)
            return results.load()
        }
        assertEquals(listOf<Any?>(1L, null), queued(cancel = false).single().let { listOf(it.count, it.errorKind) }, "control")
        assertEquals(listOf<Any?>(null, "cancelled"), queued(cancel = true).single().let { listOf(it.count, it.errorKind) })
    }

    /**
     * A completion whose work finished before [AppleLibraryReaderClient.close] is delivered after
     * close returns, with its result — a success that already completed wins (§7.2).
     */
    @Test
    fun aCompletionQueuedBeforeCloseArrivesWithItsResult() = facadeTest { h ->
        val c = h.client()
        c.client.setOnline(false)
        assertTrue(c.client.setFavourite("album", albumId(4), true))
        val result = AtomicReference<AppleLibraryPendingChanges?>(null)
        c.client.pendingChangeCount { result.store(it) }
        c.client.close()
        assertNull(result.load(), "fixture: nothing was delivered before close returned")
        pumpUntil("the completion") { result.load() != null }
        assertEquals(listOf<Any?>(1L, null), listOf(result.load()?.count, result.load()?.errorKind))
    }

    /** A held connect cancelled completes once, as `cancelled`, and its request is cancelled. */
    @Test
    fun cancellingAHeldConnectCompletesOnceAsCancelled() = facadeTest { h ->
        val c = h.client()
        c.onReader { h.server.base.holdMatching = { it.endpoint == "getScanStatus" } }
        val calls = AtomicInt(0)
        val result = AtomicReference<AppleLibraryReaderConnection?>(null)
        val op = c.client.connect { calls.addAndFetch(1); result.store(it) }
        waitOnReader(c, "the epoch read to be held") { c.onReader { h.server.base.heldCount } == 1 }
        op.cancel()
        op.cancel()
        pumpUntil("the cancelled completion") { result.load() != null }
        waitOnReader(c, "the held request to be cancelled") { c.onReader { h.server.base.cancelled } == 1 }
        pumpFor(200.milliseconds)
        assertEquals(listOf<Any?>(1, "cancelled"), listOf(calls.load(), result.load()?.errorKind))
    }

    /** Close cancels every read BEFORE it releases the database and transport. */
    @Test
    fun closeCancelsBeforeItReleases() = facadeTest { h ->
        val cancelledAtRelease = AtomicReference<Boolean?>(null)
        val c = h.client(compose = { scope ->
            val store = DulcetDatabaseStore.open(h.driver)
            val session = LibraryReaderSession(store.database, SeenCacheStore(store, h.clock).bind(BINDING), h.transport, scope)
            AppleLibraryReaderComposition(session, release = {
                cancelledAtRelease.store(scope.coroutineContext[kotlinx.coroutines.Job]?.isCancelled)
            })
        })
        c.onReader { h.server.base.holdMatching = { it.endpoint == "getAlbumList2" } }
        c.client.subscribeLibraryWindow(GRID, WindowRecorder())
        waitOnReader(c, "a read in flight at close") { c.onReader { h.server.base.heldCount } == 1 }
        c.close()
        assertEquals(true, cancelledAtRelease.load(), "the database was released while the reader's scope still ran")
    }

    /**
     * An outcome the reader queued for the main thread before a client close is dropped, not
     * delivered after it. The reader's thread is held across the close and the pump, so the
     * teardown — which also clears the subscription's listener — cannot run first: what drops the
     * outcome is the main-thread check of the client's closed flag alone. Without the hold, the
     * teardown usually wins that race, and the test passed with the check removed.
     */
    @Test
    fun anOutcomeQueuedBeforeAClientCloseIsDropped() = facadeTest { h ->
        fun queuedThenMaybeClosed(close: Boolean): Int {
            val c = h.client()
            val outcomes = OutcomeRecorder()
            c.client.subscribeFavouriteOutcomes(outcomes)
            val stars = c.onReader { h.server.count("star") }
            assertTrue(c.client.setFavourite("album", albumId(4 + stars), true))
            waitOnReader(c, "the send to be answered") { c.onReader { h.server.count("star") } == stars + 1 }
            c.onReader { } // the outcome has been queued for the main thread
            val release = c.holdReader()
            if (close) c.client.close()
            pumpFor(300.milliseconds)
            release()
            c.close()
            return outcomes.all.size
        }
        assertEquals(1, queuedThenMaybeClosed(close = false), "control: the outcome is delivered when the client stays open")
        assertEquals(0, queuedThenMaybeClosed(close = true), "an outcome was delivered after the client closed")
    }

    /**
     * `close(completion)` calls back on the main thread only once the reader's thread has stopped —
     * after every call queued before the close — and nothing touches the database after it. This is
     * what account deletion waits for (§14.7).
     */
    @Test
    fun closeWithACompletionCallsBackOnceTheReaderHasStopped() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid") { grid.any { it.freshness.kind == "live" } }
        repeat(20) { c.client.subscribeSearch(SearchRecorder()).updateQuery("Album 00$it") } // a backlog on the reader
        val stoppedAtCallback = AtomicReference<Boolean?>(null)
        val onMain = AtomicReference<Boolean?>(null)
        val calls = AtomicInt(0)
        c.client.close {
            calls.addAndFetch(1)
            onMain.store(NSThread.isMainThread)
            stoppedAtCallback.store(c.client.isTerminated)
        }
        val second = AtomicInt(0)
        c.client.close { second.addAndFetch(1) }
        pumpUntil("the close completion") { stoppedAtCallback.load() != null && second.load() == 1 }
        assertEquals(listOf<Any?>(true, true), listOf(stoppedAtCallback.load(), onMain.load()), "called back before the reader stopped, or off main")
        val statements = h.driver.statements.size
        pumpFor(300.milliseconds)
        assertEquals(statements, h.driver.statements.size, "the database was touched after the close completion")
        assertEquals(1, calls.load())
    }

    /** Offline, a search's track rows say they cannot play; online, streamable. Albums carry none. */
    @Test
    fun searchTrackRowsCrossWithTheirPlayability() = facadeTest { h ->
        val c = h.client()
        val grid = WindowRecorder()
        c.client.subscribeLibraryWindow(GRID, grid)
        pumpUntil("a live grid, so the device has seen the albums") { grid.any { it.freshness.kind == "live" } }
        val search = SearchRecorder()
        val sub = c.client.subscribeSearch(search)
        sub.updateQuery("Song album-0001")
        pumpUntil("the server's tracks") { search.any { it.scope == "serverAndDevice" && it.rows.isNotEmpty() } }
        assertEquals(setOf("streamable"), search.last.rows.filter { it.kind == "track" }.mapNotNull { it.playability }.toSet())
        c.client.setOnline(false)
        pumpUntil("the offline scope") { search.last.scope == "deviceOffline" }
        val tracks = search.last.rows.filter { it.kind == "track" }
        assertTrue(tracks.isNotEmpty(), "fixture: the device found the tracks")
        assertTrue(tracks.all { it.playability == "unavailableOffline" }, "offline tracks: ${tracks.map { it.playability }}")
        sub.updateQuery("Album 0001")
        pumpUntil("album rows") { search.last.query == "Album 0001" && search.last.rows.any { it.kind == "album" } }
        assertTrue(search.last.rows.filter { it.kind != "track" }.all { it.playability == null })
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

        /** Every session the default composition built; touched only on its reader's thread. */
        val sessions = mutableListOf<LibraryReaderSession>()

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
                        LibraryReaderSession(store.database, SeenCacheStore(store, clock).bind(BINDING), transport, scope, config, downloads)
                            .also { sessions += it },
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

        /**
         * Occupies the reader's thread until the returned function is called, so everything queued
         * there afterwards — a close's teardown — waits behind it. Never pumps main, and bounded, so
         * a failing test cannot hang.
         */
        fun holdReader(): () -> Unit {
            val state = AtomicInt(0) // 0 queued, 1 holding, 2 released
            CoroutineScope(dispatcher).launch {
                state.compareAndSet(0, 1)
                val held = TimeSource.Monotonic.markNow()
                while (state.load() == 1 && held.elapsedNow() < 20.seconds) platform.posix.usleep(1_000u)
            }
            val start = TimeSource.Monotonic.markNow()
            while (state.load() == 0) {
                check(start.elapsedNow() < 20.seconds) { "the reader's thread never took the hold" }
                platform.posix.usleep(1_000u)
            }
            return { state.store(2) }
        }

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
