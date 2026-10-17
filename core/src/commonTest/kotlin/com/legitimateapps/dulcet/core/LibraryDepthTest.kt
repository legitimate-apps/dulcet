package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryDepthTest {
    private val binding = ReaderEnv.BINDING
    private fun stamp(cache: BoundSeenCache) = CacheWriteStamp(cache.issue(), cache.now(), null)
    private fun body(payload: String) = LibraryEndpointResponse(200, """{"subsonic-response":{"status":"ok",$payload}}""", "<redacted>")
    private fun track(id: String, count: Long = 0, starred: Boolean = false) = CacheTrackRecord(
        id, "album", id, credits = listOf(CacheCredit(CreditRole.Artist, "Artist One", "artist-1")),
        userState = CacheUserState(starred = starred, playCount = count),
    )

    @Test fun categoryPinsAndRecentSearchesSurviveRebindAndAreAccountScoped() = withSeenCache { store, _ ->
        val cache = store.bind(binding)
        cache.setLibraryCategory("myLibrary.albums", "releaseDate", "favorites")
        cache.setLibraryPin("album", "opaque:album", true)
        cache.setLibraryPin("song", "opaque:song", true)
        cache.recordSearch("  Café  ")
        val reopened = store.bind(binding).libraryDepthState()
        assertEquals("releaseDate", reopened.sort("mylibrary.albums"))
        assertEquals("favorites", reopened.filter("myLibrary.albums"))
        assertEquals(2, reopened.pinCount)
        assertEquals("track", reopened.pinKind(0))
        assertEquals("Café", reopened.recentSearch(0))
        val other = store.bind(binding.copy(serverId = "other")).libraryDepthState()
        assertEquals(0, other.pinCount)
        assertEquals("all", other.filter("albums"))
        val rebound = store.bind(binding.copy(username = "another")).libraryDepthState()
        assertEquals(0, rebound.pinCount)
        assertEquals(0, rebound.recentSearchCount)
    }

    @Test fun recentsDeduplicateBoundAndClearWithoutTouchingPins() = withSeenCache { store, _ ->
        val cache = store.bind(binding)
        repeat(15) { cache.recordSearch("Query $it") }
        cache.recordSearch(" QUERY 14 ")
        cache.recordSearch("  ")
        val state = cache.libraryDepthState()
        assertEquals(12, state.recentSearchCount)
        assertEquals("QUERY 14", state.recentSearch(0))
        assertEquals("Query 3", state.recentSearch(11))
        cache.setLibraryPin("playlist", "p", true)
        cache.clearRecentSearches()
        assertEquals(0, cache.libraryDepthState().recentSearchCount)
        assertEquals(1, cache.libraryDepthState().pinCount)
    }

    @Test fun releaseMetadataParsesPersistsAndEarlierReadCannotOverwriteIt() = withSeenCache { store, _ ->
        val cache = store.bind(binding)
        val parsed = parseReaderAlbumList(body(""""albumList2":{"album":[{"id":"new","name":"New","year":2026,"releaseTypes":["ep"],"originalReleaseDate":{"year":2026,"month":10,"day":16}}]}""").body).single()
        val old = stamp(cache)
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(albums = listOf(parsed)))
        cache.writeEntities(old, CacheEntitySource.ListPage, CacheEntities(albums = listOf(parsed.copy(releaseTypes = listOf("album"), originalReleaseDate = "1990"))))
        val record = store.bind(binding).album("new")!!.record
        assertEquals(listOf("ep"), record.releaseTypes)
        assertEquals("2026-10-16", record.originalReleaseDate)
        assertEquals("epsSingles", store.bind(binding).album("new")!!.toItem(null).discographyGroup)
    }

    @Test fun discographyUsesDateThenYearAndGroupsCompilationAndGuestCredits() = withSeenCache { store, _ ->
        val cache = store.bind(binding)
        val records = listOf(
            CacheAlbumRecord("old", "Old", year = 2020),
            CacheAlbumRecord("new", "New", year = 2026, releaseTypes = listOf("album", "compilation")),
            CacheAlbumRecord("guest", "Guest", credits = listOf(CacheCredit(CreditRole.AlbumArtist, "Other", "other")), year = 2025),
        )
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(albums = records))
        val sorted = newestDiscography(records.map { cache.album(it.rawId)!!.toItem(null) }, "artist-1")
        assertEquals(listOf("new", "guest", "old"), sorted.map { it.rawId })
        assertEquals("compilations", (sorted[0] as LibraryItem.Album).discographyGroup)
        assertEquals("appearsOn", (sorted[1] as LibraryItem.Album).discographyGroup)
    }

    @Test fun downloadedAndPinnedWindowsResolveMetadataOfflineWithoutServerReads() = readerTest { env ->
        val cache = env.store.bind(binding)
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(
            albums = listOf(CacheAlbumRecord("album", "Album")),
            artists = listOf(CacheArtistRecord("artist-1", "Artist One")),
            tracks = listOf(track("complete"), track("not-downloaded")),
            playlists = listOf(CachePlaylistRecord("playlist", "Mix")),
        ))
        listOf("album" to "album", "artist" to "artist-1", "track" to "complete", "playlist" to "playlist").forEach { cache.setLibraryPin(it.first, it.second, true) }
        val downloaded = mutableSetOf("complete")
        val reader = env.reader(downloads = DownloadedTrackSource { downloaded })
        reader.setOnline(false)
        val downloads = Publications(env.server)
        reader.open(LibraryQuery.Downloaded, downloads)
        assertEquals(listOf("complete"), downloads.ids())
        assertEquals(LibraryPlayability.Downloaded, (downloads.last.items.single() as LibraryItem.Track).playability)
        val pins = Publications(env.server)
        reader.open(LibraryQuery.Pinned, pins)
        assertEquals(4, pins.last.items.size)
        downloaded.clear()
        reader.downloadsChanged()
        assertTrue(downloads.last.items.isEmpty())
        assertTrue(env.server.log.isEmpty())
    }

    @Test fun userPinProtectsAlbumFromEvictionUntilRemoved() = withSeenCache(SeenCacheCeilings(1, 10, 10, 10)) { store, _ ->
        val cache = store.bind(binding)
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(albums = listOf(CacheAlbumRecord("kept", "Kept"))))
        cache.setLibraryPin("album", "kept", true)
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(albums = listOf(CacheAlbumRecord("evict", "Evict"))))
        cache.evictIfNeeded()
        assertNotNull(cache.album("kept"))
        assertEquals(null, cache.album("evict"))
        cache.setLibraryPin("album", "kept", false)
        assertEquals(0, cache.libraryDepthState().pinCount)
    }

    @Test fun topSongsUsesServerAnswerAndOwnPlaysFallbackOnRefusal() = readerTest { env ->
        val cache = env.store.bind(binding)
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(artists = listOf(CacheArtistRecord("artist-1", "Artist One")), tracks = listOf(track("played", 7), track("starred", 0, true), track("untouched"))))
        env.server.responseOverride = { request -> if (request.endpoint == "getTopSongs") body(""""topSongs":{"song":[{"id":"server-top","title":"Top","artist":"Artist One","artistId":"artist-1"}]}""") else null }
        val reader = env.reader()
        val top = Publications(env.server)
        reader.open(LibraryQuery.ArtistTopSongs("artist-1"), top)
        advanceUntilIdle()
        assertEquals(listOf("server-top"), top.ids())
        assertEquals("Artist One", env.server.log.single { it.endpoint == "getTopSongs" }.parameters["artist"])
        env.server.responseOverride = { request -> if (request.endpoint == "getTopSongs") LibraryEndpointResponse(500, "unavailable", "<redacted>") else null }
        val fallback = Publications(env.server)
        reader.open(LibraryQuery.ArtistTopSongs("artist-1"), fallback)
        advanceUntilIdle()
        assertEquals(listOf("played", "starred"), fallback.ids())
        assertIs<LibraryFreshness.Cached>(fallback.last.freshness)
    }

    @Test fun songsPagesUseSongOffsetAndRetainGuardedWindowCoverage() = readerTest { env ->
        env.server.responseOverride = { request -> if (request.endpoint == "search3") {
            val offset = request.parameters.getValue("songOffset").toInt()
            val count = request.parameters.getValue("songCount").toInt()
            body(""""searchResult3":{"song":[${(0 until 5).drop(offset).take(count).joinToString(",") { """{"id":"song-$it","title":"Song $it"}""" }}]}""")
        } else null }
        val reader = env.reader(config = LibraryReaderConfig(pageSize = 2))
        reader.connect()
        val pubs = Publications(env.server)
        val window = reader.open(LibraryQuery.Songs(), pubs)
        advanceUntilIdle()
        repeat(3) { window.setViewport(pubs.last.items.lastIndex, pubs.last.items.lastIndex); window.loadMore(); advanceUntilIdle() }
        assertEquals(5, pubs.last.items.size, env.server.log.toString() + " publications=" + pubs.all.map { it.publication.items.map { it.rawId } })
        assertEquals(LibraryCoverage.Complete, pubs.last.coverage)
        val requests = env.server.log.filter { it.endpoint == "search3" }
        assertTrue(requests.size >= 3)
        assertEquals(listOf("0", "2", "4"), requests.take(3).map { it.parameters["songOffset"] })
        assertTrue(requests.all { it.parameters["query"] == "" && it.parameters["artistCount"] == "0" && "offset" !in it.parameters })
    }

    @Test fun unsupportedSongEnumerationFallsBackToSeenWithExplicitFailureScope() = readerTest { env ->
        val cache = env.store.bind(binding)
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(tracks = listOf(track("seen"))))
        env.server.responseOverride = { request -> if (request.endpoint == "search3") LibraryEndpointResponse(500, "unsupported", "<redacted>") else null }
        val reader = env.reader()
        reader.connect()
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.Songs(), pubs)
        advanceUntilIdle()
        assertEquals(listOf("seen"), pubs.ids())
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
        assertIs<LibraryCachedReason.Failed>(assertIs<LibraryFreshness.Cached>(pubs.last.freshness).reason)
    }

    @Test fun shuffleRequestsBoundedRandomServerSelectionWithoutEnumeratingSongs() = readerTest { env ->
        env.server.responseOverride = { request -> if (request.endpoint == "getRandomSongs") body(""""randomSongs":{"song":[{"id":"random","title":"Random"}]}""") else null }
        val reader = env.reader()
        reader.connect()
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.ShuffleSongs, pubs)
        advanceUntilIdle()
        assertEquals(listOf("random"), pubs.ids())
        assertEquals("500", env.server.log.single { it.endpoint == "getRandomSongs" }.parameters["size"])
        assertFalse(env.server.log.any { it.endpoint in setOf("search3", "getAlbumList2", "getAlbum") })
    }

    @Test fun categoryPolicyAppliesFavoritesDownloadedAndDescendingTitles() = readerTest { env ->
        val cache = env.store.bind(binding)
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(tracks = listOf(track("A", 2, true), track("Z", 4))))
        val reader = env.reader(downloads = DownloadedTrackSource { setOf("Z") })
        val items = cache.localTracks().map { it.toItem(null, reader.trackPlayability(it.rawId, setOf("Z"))) }
        cache.setLibraryCategory("songs", "titleDescending", "all")
        assertEquals(listOf("Z", "A"), reader.applyCategory(items, "songs").map { it.rawId })
        cache.setLibraryCategory("songs", "title", "favorites")
        assertEquals(listOf("A"), reader.applyCategory(items, "songs").map { it.rawId })
        cache.setLibraryCategory("songs", "title", "downloaded")
        assertEquals(listOf("Z"), reader.applyCategory(items, "songs").map { it.rawId })
        assertEquals("title", cache.libraryDepthState().sort("artists"))
    }
    @Test fun sortedWindowKeepsAbsolutePositionsAndRebasesVisibleServerPages() = readerTest { env ->
        env.server.responseOverride = { request -> if (request.endpoint == "search3") {
            val offset = request.parameters.getValue("songOffset").toInt()
            val count = request.parameters.getValue("songCount").toInt()
            body("\"searchResult3\":{\"song\":[" + (0 until 10).drop(offset).take(count).joinToString(",") { "{\"id\":\"song-$it\",\"title\":\"Song $it\"}" } + "]}")
        } else null }
        val reader = env.reader(config = LibraryReaderConfig(pageSize = 2))
        reader.connect()
        val pubs = Publications(env.server)
        val window = reader.open(LibraryQuery.Songs(), pubs)
        advanceUntilIdle()
        repeat(6) { window.setViewport(pubs.last.items.lastIndex, pubs.last.items.lastIndex); window.loadMore(); advanceUntilIdle() }
        assertEquals(10, pubs.last.items.size)
        reader.cache.setLibraryCategory("songs", "titleDescending", "all")
        reader.downloadsChanged()
        assertEquals("song-9", pubs.last.items.first().rawId)
        window.setViewport(0, 1)
        env.server.lastScan = "new-scan"
        window.refresh()
        advanceUntilIdle()
        assertEquals(8, pubs.last.leadingOffset)
        assertEquals(listOf("song-9", "song-8"), pubs.ids())
        assertEquals(LibraryItemsOrder.LocalView, pubs.last.order)
    }

    @Test fun localCollectionChangesNotifyMyLibraryObserversWithoutServerReads() = readerTest { env ->
        val database = com.legitimateapps.dulcet.database.DulcetDatabase(env.driver)
        val localScope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = LibraryReaderSession(database, env.store.bind(binding), env.server, localScope,
            formPost = false, foreground = false)
        var notices = 0
        session.myLibrary.addChangeListener { notices++ }
        session.reader.setOnline(false)
        session.reader.downloadsChanged()
        assertEquals(1, notices)
        assertTrue(env.server.log.isEmpty())
        localScope.cancel()
    }

    @Test fun eachShuffleActionRequestsNewServerSelectionEvenWithinFreshnessInterval() = readerTest { env ->
        var sequence = 0
        env.server.responseOverride = { request -> if (request.endpoint == "getRandomSongs") {
            sequence++
            body("\"randomSongs\":{\"song\":[{\"id\":\"random-$sequence\",\"title\":\"Random\"}]}")
        } else null }
        val reader = env.reader()
        reader.connect()
        val first = Publications(env.server)
        reader.open(LibraryQuery.ShuffleSongs, first)
        advanceUntilIdle()
        val second = Publications(env.server)
        reader.open(LibraryQuery.ShuffleSongs, second)
        assertEquals(LibraryCachedReason.Revalidating, assertIs<LibraryFreshness.Cached>(second.last.freshness).reason)
        advanceUntilIdle()
        assertEquals(listOf("random-2"), second.ids())
        assertEquals(2, env.server.count("getRandomSongs"))
    }

    @Test fun downloadedAlbumAndArtistFilterUsesKnownTrackRelationshipsWithoutAlbumDetailRead() = readerTest { env ->
        val cache = env.store.bind(binding)
        cache.writeEntities(stamp(cache), CacheEntitySource.ListPage, CacheEntities(
            albums = listOf(CacheAlbumRecord("album", "Album"), CacheAlbumRecord("other", "Other")),
            artists = listOf(CacheArtistRecord("artist-1", "Artist One")),
            tracks = listOf(track("complete")),
        ))
        val reader = env.reader(downloads = DownloadedTrackSource { setOf("complete") })
        cache.setLibraryCategory("albums", "title", "downloaded")
        cache.setLibraryCategory("artists", "title", "downloaded")
        assertEquals(listOf("album"), reader.applyCategory(cache.localAlbums().map { it.toItem(null) }, "albums").map { it.rawId })
        assertEquals(listOf("artist-1"), reader.applyCategory(cache.localArtists().map { it.toItem(null) }, "artists").map { it.rawId })
        reader.setOnline(false)
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName), pubs)
        assertEquals(listOf("album"), pubs.ids())
        assertTrue(env.server.log.isEmpty())
    }

    @Test fun explicitLoadMoreTraversesHiddenPagesWithoutAutomaticCatalogWalk() = readerTest { env ->
        env.server.responseOverride = { request -> if (request.endpoint == "search3") {
            val offset = request.parameters.getValue("songOffset").toInt()
            val count = request.parameters.getValue("songCount").toInt()
            body("\"searchResult3\":{\"song\":[" + (0 until 6).drop(offset).take(count).joinToString(",") {
                val title = if (it == 4) "Needle" else "Other $it"
                "{\"id\":\"song-$it\",\"title\":\"$title\"}"
            } + "]}")
        } else null }
        val reader = env.reader(config = LibraryReaderConfig(pageSize = 2))
        reader.cache.setLibraryCategory("songs", "title", "text:Needle")
        reader.connect()
        val pubs = Publications(env.server)
        val window = reader.open(LibraryQuery.Songs(), pubs)
        advanceUntilIdle()
        assertTrue(pubs.last.items.isEmpty())
        assertEquals(1, env.server.count("search3"))
        window.loadMore()
        advanceUntilIdle()
        assertTrue(pubs.last.items.isEmpty())
        assertEquals(2, env.server.count("search3"))
        window.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("song-4"), pubs.ids())
        assertEquals(3, env.server.count("search3"))
        assertEquals(listOf("0", "2", "4"), env.server.log.filter { it.endpoint == "search3" }.map { it.parameters["songOffset"] })
    }

    @Test fun myLibraryCategoryUsesTitleDefaultAndExplicitRecentKeepsMembershipOrder() = readerTest { env ->
        val reader = env.reader()
        val items = listOf(track("Zulu"), track("Alpha"))
        fun sorted(fallback: String = "title") = reader.applyMyLibraryCategory(
            items, "myLibrary.songs", { it.rawId }, { if (it.rawId == "Zulu") 2L else 1L }, fallback,
        ) { LibraryItem.Track(it.rawId, it.title, it.albumRawId, it.albumTitle, null, null, null, null, null, null, null, false, null, 0, LibraryPlayability.Downloaded, false) }
        assertEquals("title", reader.cache.libraryDepthState().sort("myLibrary.songs"))
        assertEquals(listOf("Alpha", "Zulu"), sorted().map { it.rawId })
        assertEquals(listOf("Zulu", "Alpha"), sorted("recentlyAdded").map { it.rawId })
        reader.cache.setLibraryCategory("myLibrary.songs", "title", "all")
        assertEquals(listOf("Alpha", "Zulu"), sorted("recentlyAdded").map { it.rawId })
    }

    @Test fun queuedWriteFailureRemainsVisibleAcrossReadsUntilSuccessfulWrite() = readerTest { env ->
        val reader = env.reader()
        reader.changeLibraryDepth { it.cache.setLibraryCategory("songs", "artist", "all") }
        reader.changeLibraryDepth { error("private storage failure details") }
        repeat(2) {
            val state = reader.libraryDepthState()
            assertEquals("internalFailure", state.errorKind)
            assertEquals("artist", state.sort("songs"))
        }
        reader.changeLibraryDepth { it.cache.setLibraryCategory("songs", "title", "all") }
        assertEquals(null, reader.libraryDepthState().errorKind)
        assertEquals("title", reader.libraryDepthState().sort("songs"))
    }

}
