package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ListenBrainzClientTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private fun resource(name:String)=javaClass.getResource("/discover/$name.json")!!.readText()
    @Test fun parsesObservedPayloadShapes() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            h.configureOrigin(f.baseUrl,0)
            val c=ListenBrainzClient(h,DiscoverCache(db),DiscoverSettings(listenBrainzUrl=f.baseUrl,listenBrainzLabsUrl=f.baseUrl))
            for((kind,entity) in listOf(DiscoverKind.Artist to "artists",DiscoverKind.Album to "release-groups",DiscoverKind.Song to "recordings")) {
                f.json("/1/stats/sitewide/$entity",resource("listenbrainz/trending-$entity"))
                val items=c.trending(kind).value
                assertTrue(items.isNotEmpty()); assertEquals(kind,items.first().kind); assertNotNull(items.first().listenCount)
                assertEquals("week",f.requests.last().query["range"])
            }
            f.json("/1/explore/fresh-releases/",resource("listenbrainz/fresh-releases"))
            val fresh=c.freshReleases().value.first()
            assertNotNull(fresh.releaseDate); assertNotNull(fresh.externalIds["musicbrainzReleaseId"]); assertNotNull(fresh.externalIds[DiscoverKind.Artist.registryKey])
            assertEquals("7",f.requests.last().query["days"])
            f.json("/1/playlist/search",resource("listenbrainz/playlist-search"))
            assertEquals("Public Mix",c.searchPlaylists("",100).value.single().title)
        } } }
    }
    @Test fun playlistPreservesDuplicatesAndUnknownRecording() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            h.configureOrigin(f.baseUrl,0)
            val c=ListenBrainzClient(h,DiscoverCache(db),DiscoverSettings(listenBrainzUrl=f.baseUrl))
            f.json("/1/playlist/$id",resource("listenbrainz/playlist"))
            val rows=c.playlist(id).value.entries
            assertEquals(listOf(1,2,3),rows.map { it.position }); assertEquals(rows[0].item.externalIds,rows[1].item.externalIds)
            assertFalse(rows[2].item.wishable)
        } } }
    }
    @Test fun ttlsAreSixHoursSevenDaysAndOneHour() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            val clock=object:DiscoverClock {
                var now=1_000_000L
                override fun wallMillis()=now
                override fun monotonicNanos()=now*1_000_000
                override suspend fun sleepMillis(millis:Long) { now+=millis }
            }
            h.configureOrigin(f.baseUrl,0)
            val c=ListenBrainzClient(h,DiscoverCache(db,clock),DiscoverSettings(listenBrainzUrl=f.baseUrl,listenBrainzLabsUrl=f.baseUrl))
            f.json("/1/stats/sitewide/artists",resource("listenbrainz/trending-artists"))
            f.json("/similar-artists/json",resource("labs/similar-artists"))
            f.json("/1/playlist/search",resource("listenbrainz/playlist-search"))
            c.trending(DiscoverKind.Artist); c.similarArtists(id); c.searchPlaylists("",100)
            clock.now+=DiscoverCache.HOUR
            c.trending(DiscoverKind.Artist); c.similarArtists(id); c.searchPlaylists("",100)
            assertEquals(4,f.requests.size)
            clock.now+=5*DiscoverCache.HOUR
            c.trending(DiscoverKind.Artist); c.similarArtists(id)
            assertEquals(5,f.requests.size)
            clock.now+=7*DiscoverCache.DAY-6*DiscoverCache.HOUR
            c.similarArtists(id); assertEquals(6,f.requests.size)
        } } }
    }
    @Test fun similarUsesOneSeedAndNoIdentity() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            h.configureOrigin(f.baseUrl,0)
            val c=ListenBrainzClient(h,DiscoverCache(db),DiscoverSettings(listenBrainzLabsUrl=f.baseUrl,listenBrainzToken="reserved-canary"))
            f.json("/similar-artists/json",resource("labs/similar-artists"))
            assertTrue(c.similarArtists(id).value.isNotEmpty())
            assertEquals(id,f.requests.single().query["artist_mbids"])
            assertEquals(setOf("artist_mbids","algorithm"),f.requests.single().query.keys)
            assertNull(f.requests.single().headers["authorization"])
        } } }
    }
}
