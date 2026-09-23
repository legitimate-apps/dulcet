package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeenCacheStoreTest {
    // ---- CONF-80: binding --------------------------------------------------------------------------

    @Test
    fun conf80ADifferentServerUrlPurgesTheNamespaceBeforeAnyRowIsServed() = withSeenCache { store, _ ->
        val first = store.bind(BINDING)
        fillNamespace(first)
        val otherAccount = store.bind(OTHER_BINDING)
        fillNamespace(otherAccount)

        val rebound = store.bind(BINDING.copy(normalizedBaseUrl = "https://elsewhere.example"))

        assertTrue(rebound.purgedOnBind)
        assertNull(rebound.album("album:1"), "an album from the previous server was served")
        assertNull(rebound.track("track:1"))
        assertNull(rebound.listState(LIST))
        assertEquals(emptyList(), rebound.pins())
        assertNull(rebound.storedEpoch())
        assertEquals(0, rebound.counts().albums)
        // The purge is per namespace: the other provider instance is untouched.
        assertNotNull(store.bind(OTHER_BINDING).album("album:1"))
    }

    @Test
    fun conf80ADifferentUsernamePurgesToo() = withSeenCache { store, _ ->
        fillNamespace(store.bind(BINDING))
        val rebound = store.bind(BINDING.copy(username = "someone-else"))
        assertTrue(rebound.purgedOnBind)
        assertNull(rebound.album("album:1"))
    }

    @Test
    fun conf80TheSameBindingKeepsEverything() = withSeenCache { store, _ ->
        fillNamespace(store.bind(BINDING))
        val again = store.bind(BINDING)
        assertFalse(again.purgedOnBind)
        assertNotNull(again.album("album:1"))
        assertEquals(1, again.listMembers(LIST).size)
    }

    @Test
    fun conf80AnUnboundNamespaceAdoptsTheFirstBindingAndKeepsSeededRows() = withSeenCache { store, _ ->
        // Rows seeded by the additive migration carry no binding (spec §16.17).
        store.database.seenCacheQueries.insertAlbum(
            SERVER, "album:seeded", "Seeded", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, 0, 1,
        )
        val bound = store.bind(BINDING)
        assertFalse(bound.purgedOnBind)
        assertNotNull(bound.album("album:seeded"))
        // ...and from then on the adopted binding is enforced.
        assertTrue(store.bind(BINDING.copy(username = "x")).purgedOnBind)
    }

    // ---- Issue order --------------------------------------------------------------------------------

    @Test
    fun aSlowerAnswerToAnEarlierRequestNeverOverwritesALaterOne() = withSeenCache { store, _ ->
        val cache = store.bind(BINDING)
        val earlier = cache.issue()
        val later = cache.issue()
        assertTrue(later > earlier)

        cache.writeEntities(stamp(later), CacheEntitySource.ListPage, CacheEntities(albums = listOf(album("album:1", "Fresh"))))
        val written = cache.writeEntities(stamp(earlier), CacheEntitySource.ListPage, CacheEntities(albums = listOf(album("album:1", "Stale"))))

        assertEquals(emptySet(), written)
        assertEquals("Fresh", cache.album("album:1")?.record?.title)
        assertEquals(later, cache.album("album:1")?.row?.issueSeq)
    }

    @Test
    fun theIssueOrderSurvivesAReopenedStore() = withSeenCache { store, clock ->
        val before = store.bind(BINDING).issue()
        val reopened = SeenCacheStore(DulcetDatabaseStore.open(store.driverForTest()), clock).bind(BINDING)
        assertTrue(reopened.issue() > before, "a request issued after a relaunch must outrank rows written before it")
    }

    @Test
    fun aDetailThatOmitsATrackOutranksAnOlderListPageButNotANewerOne() = withSeenCache { store, _ ->
        val cache = store.bind(BINDING)
        val listBefore = cache.issue()
        val detail = cache.issue()
        val listAfter = cache.issue()
        cache.writeAlbumDetail(stamp(cache.issue()), album("album:1", "A"), listOf(track("track:1", "album:1"), track("track:2", "album:1")))
        // A LATER detail read omits track:2.
        val newestDetail = cache.issue()
        cache.writeAlbumDetail(stamp(newestDetail), album("album:1", "A"), listOf(track("track:1", "album:1")))
        assertEquals(true, cache.track("track:2")?.row?.gone)

        // A list page issued BEFORE that detail cannot resurrect the track...
        cache.writeEntities(stamp(listBefore), CacheEntitySource.ListPage, CacheEntities(tracks = listOf(track("track:2", "album:1"))))
        assertEquals(true, cache.track("track:2")?.row?.gone)
        assertTrue(detail < newestDetail && listAfter < newestDetail)
        // ...but one issued after it can: a list read that includes the track is newer evidence.
        cache.writeEntities(stamp(cache.issue()), CacheEntitySource.ListPage, CacheEntities(tracks = listOf(track("track:2", "album:1"))))
        assertEquals(false, cache.track("track:2")?.row?.gone)
    }

    @Test
    fun aSummaryNeverClearsDetailCompletenessOrTrackMembership() = withSeenCache { store, _ ->
        val cache = store.bind(BINDING)
        cache.writeAlbumDetail(stamp(cache.issue()), album("album:1", "A"), listOf(track("track:1", "album:1")))
        cache.writeEntities(stamp(cache.issue()), CacheEntitySource.ListPage, CacheEntities(albums = listOf(album("album:1", "Renamed"))))
        val cached = assertNotNull(cache.album("album:1"))
        assertEquals("Renamed", cached.record.title)
        assertTrue(cached.detailComplete)
        assertEquals(listOf("track:1"), cache.albumTracks("album:1").map { it.rawId })
    }

    // ---- CONF-83 at the store: gone-ness -----------------------------------------------------------

    @Test
    fun conf83StoreRulesForGoneness() = withSeenCache { store, _ ->
        val cache = store.bind(BINDING)
        cache.writeAlbumDetail(stamp(cache.issue()), album("album:1", "A"), listOf(track("track:1", "album:1"), track("track:2", "album:1")))
        cache.pin(CacheItemKind.Track, "track:2", CachePinReason.Download)

        // Rule 1: absent from a successful detail read -> gone, and a pinned row is KEPT.
        val result = cache.writeAlbumDetail(stamp(cache.issue()), album("album:1", "A"), listOf(track("track:1", "album:1")))
        assertEquals(listOf("track:2"), result.tracksMarkedGone)
        assertEquals(true, cache.track("track:2")?.row?.gone)
        assertEquals(listOf("track:1"), cache.albumTracks("album:1").map { it.rawId })

        // Rule 4: a getSong-style answer never restores presence.
        cache.writeEntities(stamp(cache.issue()), CacheEntitySource.SongLookup, CacheEntities(tracks = listOf(track("track:2", "album:1"))))
        assertEquals(true, cache.track("track:2")?.row?.gone, "getSong ok restored presence")

        // Rule 3: a successful detail with zero songs is gone, not empty.
        val empty = cache.writeAlbumDetail(stamp(cache.issue()), album("album:1", "A"), emptyList())
        assertTrue(empty.albumGone)
        assertEquals(true, cache.album("album:1")?.row?.gone)

        // Rule 2: code 70 on a detail read marks the album and its tracks gone.
        cache.writeAlbumDetail(stamp(cache.issue()), album("album:2", "B"), listOf(track("track:3", "album:2")))
        assertTrue(cache.markAlbumNotFound(cache.issue(), "album:2"))
        assertEquals(true, cache.album("album:2")?.row?.gone)
        assertEquals(true, cache.track("track:3")?.row?.gone)
    }

    // ---- CONF-78: eviction order and pins ----------------------------------------------------------

    @Test
    fun conf78EvictsLeastRecentlyAccessedWindowsThenOrphansAndPinsSurviveEveryCeiling() = withSeenCache(
        ceilings = SeenCacheCeilings(albums = 3, tracks = 1_000, artists = 1_000, lists = 2),
    ) { store, clock ->
        val cache = store.bind(BINDING)
        clock.now = 1_000
        writeWindow(cache, "list:one", listOf("album:a", "album:b"))
        clock.now = 2_000
        writeWindow(cache, "list:two", listOf("album:c"))
        clock.now = 5_000
        cache.touchList("list:one") // shown again: now the most recently accessed

        clock.now = 6_000
        writeWindow(cache, "list:three", listOf("album:d"))
        val report = cache.evictIfNeeded()

        assertEquals(listOf("list:two"), report.lists, "the least-recently-ACCESSED window goes first, not the oldest-fetched")
        assertEquals(listOf("album:c"), report.albums, "then the orphan it released")
        assertEquals(setOf("album:a", "album:b", "album:d"), cache.localAlbums().map { it.record.rawId }.toSet())
        assertNull(cache.listState("list:two"))
    }

    @Test
    fun conf78PinnedMetadataSurvivesEveryCeiling() = withSeenCache(
        ceilings = SeenCacheCeilings(albums = 1, tracks = 1, artists = 1, lists = 1),
    ) { store, clock ->
        val cache = store.bind(BINDING)
        clock.now = 1_000
        cache.writeAlbumDetail(stamp(cache.issue()), album("album:pinned", "Kept"), listOf(track("track:pinned", "album:pinned"), track("track:loose", "album:pinned")))
        cache.pin(CacheItemKind.Track, "track:pinned", CachePinReason.Download)
        cache.pin(CacheItemKind.Album, "album:pinned", CachePinReason.Download)
        clock.now = 2_000
        writeWindow(cache, "list:one", listOf("album:x", "album:y"))
        clock.now = 3_000
        writeWindow(cache, "list:two", listOf("album:z"))

        cache.evictIfNeeded()

        assertNotNull(cache.album("album:pinned"), "a pinned album was evicted")
        assertNotNull(cache.track("track:pinned"), "a pinned track was evicted")
        // Both windows were released to reach the album ceiling, and every unpinned orphan went.
        assertNull(cache.listState("list:one"))
        assertNull(cache.listState("list:two"))
        assertEquals(listOf("album:pinned"), cache.localAlbums().map { it.record.rawId })
        // The unpinned sibling of a detail-complete album is still referenced by that detail.
        assertNotNull(cache.track("track:loose"))

        // Unpinning releases it to the next pass.
        cache.unpin(CacheItemKind.Album, "album:pinned", CachePinReason.Download)
        cache.unpin(CacheItemKind.Track, "track:pinned", CachePinReason.Download)
        cache.writeEntities(stamp(cache.issue()), CacheEntitySource.ListPage, CacheEntities(albums = listOf(album("album:new", "New"))))
        cache.evictIfNeeded()
        assertEquals(1, cache.localAlbums().size)
    }

    @Test
    fun pinningAnUncachedTrackKeepsAnIdentityOnlyRow() = withSeenCache { store, _ ->
        val cache = store.bind(BINDING)
        cache.pin(CacheItemKind.Track, "track:unknown", CachePinReason.Queue)
        val row = assertNotNull(cache.track("track:unknown"))
        assertTrue(row.metadataMissing)
        assertNull(row.record)
        // Its next live read refills it.
        cache.writeEntities(stamp(cache.issue()), CacheEntitySource.ListPage, CacheEntities(tracks = listOf(track("track:unknown", null))))
        assertFalse(assertNotNull(cache.track("track:unknown")).metadataMissing)
    }

    @Test
    fun accountRemovalDeletesTheSeenCache() = withSeenCache { store, _ ->
        fillNamespace(store.bind(BINDING))
        assertTrue(store.database.serverDataQueries.countRowsForServer(SERVER).executeAsOne().sum!! > 0)
        store.database.serverDataQueries.deleteSeenCacheForServer(SERVER)
        assertEquals(0, store.database.serverDataQueries.countRowsForServer(SERVER).executeAsOne().sum)
    }

    @Test
    fun listKeysAreCanonicalAndCannotCollide() {
        assertEquals(
            "getAlbumList2?musicFolderId=1&type=alphabeticalByName",
            canonicalListKey("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "100", "offset" to "200", "musicFolderId" to "1", "u" to "user", "t" to "token", "s" to "salt")),
        )
        assertTrue(
            canonicalListKey("getAlbumList2", mapOf("genre" to "a&type=b")) !=
                canonicalListKey("getAlbumList2", mapOf("genre" to "a", "type" to "b")),
        )
        assertEquals("getPlaylist?id=p%26q", playlistDetailListKey("p&q"))
    }

    // ---- Fixtures -----------------------------------------------------------------------------------

    private fun fillNamespace(cache: BoundSeenCache) {
        val seq = cache.issue()
        cache.writeAlbumDetail(stamp(seq), album("album:1", "One"), listOf(track("track:1", "album:1")))
        writeWindow(cache, LIST, listOf("album:1"))
        cache.pin(CacheItemKind.Track, "track:1", CachePinReason.Download)
        cache.saveEpoch(StoredCatalogEpoch("2026-09-22T10:00:00.1+02:00", setOf("f1"), false, 1))
    }

    private fun writeWindow(cache: BoundSeenCache, key: String, albums: List<String>) {
        val seq = cache.issue()
        cache.writeWindowPage(
            stamp = stamp(seq),
            source = CacheEntitySource.ListPage,
            state = CachedListState(key, "epoch", setOf("f1"), 0, albums.size, null, CacheCoverage.Open, cache.now(), cache.now(), seq),
            pageStart = 0,
            replacedEnd = albums.size,
            members = albums.map { CacheListMember(CacheItemKind.Album, it) },
            entities = CacheEntities(albums = albums.map { album(it, it) }),
        )
    }

    private fun stamp(seq: Long) = CacheWriteStamp(seq, 10, "epoch")

    private fun album(rawId: String, title: String) = CacheAlbumRecord(
        rawId = rawId,
        title = title,
        credits = listOf(CacheCredit(CreditRole.AlbumArtist, "Artist", "artist:1")),
    )

    private fun track(rawId: String, album: String?) = CacheTrackRecord(rawId = rawId, albumRawId = album, title = rawId)

    private companion object {
        const val SERVER = "server:account"
        const val LIST = "getAlbumList2?type=alphabeticalByName"
        val BINDING = CacheBinding(SERVER, "https://music.example", "listener")
        val OTHER_BINDING = CacheBinding("server:other", "https://other.example", "listener")
    }
}

internal class ManualWallClock(var now: Long = 1_000) : SeenCacheWallClock {
    override fun nowEpochMilliseconds(): Long = now
}

/**
 * Opens the seen-cache over a private test database and closes the driver in `finally`: on
 * Kotlin/Native an unclosed driver used to leak its database into every later test in the
 * process, and a FAILING test leaks too, because the assertion throws before a trailing close.
 */
internal fun withSeenCache(
    ceilings: SeenCacheCeilings = SeenCacheCeilings.DEFAULT,
    block: (SeenCacheTestHandle, ManualWallClock) -> Unit,
) {
    val driver = createTestDriver()
    try {
        val clock = ManualWallClock()
        val database = DulcetDatabaseStore.open(driver)
        block(SeenCacheTestHandle(database, SeenCacheStore(database, clock, ceilings)), clock)
    } finally {
        driver.close()
    }
}

internal class SeenCacheTestHandle(val databaseStore: DulcetDatabaseStore, val store: SeenCacheStore) {
    val database get() = databaseStore.database
    fun bind(binding: CacheBinding) = store.bind(binding)
    fun driverForTest() = databaseStore.driver
}
