package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListenNowTest {
    private fun envelope(content: String) = LibraryEndpointResponse(200,
        """{"subsonic-response":{"status":"ok",$content}}""", "https://music.example/<redacted>")

    @Test fun advertisedHomeIsUsedAndCachedBeforeAnyRelaunchRequest() = readerTest { env ->
        val log = mutableListOf<String>()
        val wire = LibraryEndpointTransport { endpoint, parameters ->
            log += endpoint
            when (endpoint) {
                "getOpenSubsonicExtensions" -> envelope(""""openSubsonicExtensions":[{"name":"dulcetHome","versions":[1]}]""")
                "getDulcetHome" -> envelope(""""dulcetHome":{"album":[{"id":"opaque-album","name":"Imported favourite"}],"song":[]}""")
                else -> env.server.request(endpoint, parameters)
            }
        }
        val reader = env.reader(transport = wire)
        reader.connect()
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.ListenNow(ListenNowShelf.Recent), pubs)
        advanceUntilIdle()
        assertEquals(listOf("opaque-album"), pubs.ids())
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertEquals(1, log.count { it == "getOpenSubsonicExtensions" })
        assertEquals(1, log.count { it == "getDulcetHome" })
        reader.setOnline(false)
        val restored = env.reader(transport = wire)
        restored.setOnline(false)
        val before = log.size
        val cached = Publications(env.server)
        restored.open(LibraryQuery.ListenNow(ListenNowShelf.Recent), cached)
        assertEquals(listOf("opaque-album"), cached.ids())
        assertIs<LibraryFreshness.Cached>(cached.last.freshness)
        advanceUntilIdle()
        assertEquals(before, log.size)
        assertTrue(env.uncaught.isEmpty())
    }

    @Test fun plainServerUsesOnlyRestAndNeverCallsRandomAPersonalMix() = readerTest { env ->
        val log = mutableListOf<String>()
        val wire = LibraryEndpointTransport { endpoint, parameters ->
            log += endpoint
            when (endpoint) {
                "getOpenSubsonicExtensions" -> envelope(""""openSubsonicExtensions":[]""")
                "ping" -> envelope("\"version\":\"1.16.1\"")
                else -> env.server.request(endpoint, parameters)
            }
        }
        val reader = env.reader(transport = wire)
        reader.connect()
        val recent = Publications(env.server)
        val mix = Publications(env.server)
        reader.open(LibraryQuery.ListenNow(ListenNowShelf.Recent), recent)
        reader.open(LibraryQuery.ListenNow(ListenNowShelf.DailyMix), mix)
        advanceUntilIdle()
        assertEquals(20, recent.last.items.size)
        assertTrue(mix.last.items.isEmpty())
        assertEquals(LibraryFreshness.Live, mix.last.freshness)
        assertFalse("getDulcetHome" in log)
        assertEquals(1, log.count { it == "getOpenSubsonicExtensions" })
        assertTrue(env.uncaught.isEmpty())
    }

    @Test fun discoveryFailureRetainsCachedHomeAndDoesNotInventFallback() = readerTest { env ->
        var failed = false
        var fallback = 0
        val wire = LibraryEndpointTransport { endpoint, parameters ->
            when (endpoint) {
                "getOpenSubsonicExtensions" -> if(failed) envelope(""""error":{"code":40}""").copy(body = """{"subsonic-response":{"status":"failed","error":{"code":40}}}""")
                    else envelope(""""openSubsonicExtensions":[{"name":"dulcetHome","versions":[1]}]""")
                "getDulcetHome" -> envelope(""""dulcetHome":{"song":[{"id":"song","title":"Favourite","duration":120}]}""")
                "getAlbumList2" -> { fallback++; env.server.request(endpoint, parameters) }
                else -> env.server.request(endpoint, parameters)
            }
        }
        val reader = env.reader(transport=wire); reader.connect()
        reader.open(LibraryQuery.ListenNow(ListenNowShelf.Favourites)) { }
        advanceUntilIdle(); reader.setOnline(false); failed = true
        val next = env.reader(transport=wire); next.connect()
        val pubs = Publications(env.server)
        next.open(LibraryQuery.ListenNow(ListenNowShelf.Favourites), pubs)
        advanceUntilIdle()
        assertEquals(listOf("song"), pubs.ids())
        assertIs<LibraryFreshness.Cached>(pubs.last.freshness)
        assertEquals(0, fallback)
        assertTrue(env.uncaught.isEmpty())
    }
    @Test fun playlistHomeItemsKeepTheirCollectionIdentityInTheSharedCache() = readerTest { env ->
        val wire = LibraryEndpointTransport { endpoint, parameters ->
            when(endpoint) {
                "getOpenSubsonicExtensions" -> envelope(""""openSubsonicExtensions":[{"name":"dulcetHome","versions":[1]}]""")
                "getDulcetHome" -> envelope(""""dulcetHome":{"playlist":[{"id":"playlist-1","name":"Last night's mix","songCount":8}]}""")
                else -> env.server.request(endpoint,parameters)
            }
        }
        val reader=env.reader(transport=wire); reader.connect()
        val pubs=Publications(env.server)
        reader.open(LibraryQuery.ListenNow(ListenNowShelf.RecentPlaylists),pubs)
        advanceUntilIdle()
        assertEquals(listOf("playlist-1"),pubs.ids())
        assertEquals(LibraryFreshness.Live,pubs.last.freshness)
        assertTrue(env.uncaught.isEmpty())
    }

    @Test fun aPlainServerStationUsesARealRecentAlbumAndHonoursMissingSimilarSongs() = readerTest { env ->
        val requested=mutableListOf<Pair<String,Map<String,String>>>()
        val wire=LibraryEndpointTransport { endpoint, parameters ->
            requested += endpoint to parameters
            when(endpoint) {
                "getOpenSubsonicExtensions" -> envelope(""""openSubsonicExtensions":[]""")
                "getAlbumList2" -> envelope(""""albumList2":{"album":[{"id":"recent-album","name":"Recent"}]}""")
                "getAlbum" -> envelope(""""album":{"id":"recent-album","name":"Recent","song":[{"id":"recent-song","title":"Representative song"}]}""")
                "getSimilarSongs2" -> LibraryEndpointResponse(200,"""{"subsonic-response":{"status":"failed","error":{"code":70}}}""", "https://music.example/<redacted>")
                "ping" -> envelope("\"version\":\"1.16.1\"")
                else -> env.server.request(endpoint,parameters)
            }
        }
        val reader=env.reader(transport=wire); reader.connect()
        val pubs=Publications(env.server)
        reader.open(LibraryQuery.ListenNow(ListenNowShelf.ArtistStation),pubs)
        advanceUntilIdle()
        assertEquals("recent-song",requested.single { it.first=="getSimilarSongs2" }.second["id"])
        assertTrue(pubs.last.items.isEmpty())
        assertEquals(LibraryFreshness.Live,pubs.last.freshness)
        assertTrue(env.uncaught.isEmpty())
    }

}
