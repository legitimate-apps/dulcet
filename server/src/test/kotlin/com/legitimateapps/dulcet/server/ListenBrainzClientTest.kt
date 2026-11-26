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

    /** ListenBrainz's weekly stats listed BTS's ARIRANG twice, once per release counted (household server, 2026-11-26);
     * the trending shelf shows each release group once, the most-listened row first, and keeps id-less rows. */
    @Test fun trendingShelvesShowEachReleaseGroupOnce() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            h.configureOrigin(f.baseUrl,0)
            val c=ListenBrainzClient(h,DiscoverCache(db),DiscoverSettings(listenBrainzUrl=f.baseUrl,listenBrainzLabsUrl=f.baseUrl))
            val group="21159e3f-172e-43f6-aa7d-8e06a81fea49"; val other="ddbf733c-1182-42a2-9bae-059afcbc3b93"
            fun row(group:String?,release:String,count:Int,name:String="ARIRANG")="""{${if(group==null)"" else "\"release_group_mbid\":\"$group\","}"release_group_name":"$name","artist_name":"BTS","caa_release_mbid":"$release","listen_count":$count}"""
            f.json("/1/stats/sitewide/release-groups","""{"payload":{"release_groups":[${listOf(
                row(group,"909d000d-56e9-4b38-aa3f-008d38436ea2",90),row(group,"20af35dd-dda2-47de-8ea5-78211c0dfe9c",80),
                row(other,"cc613cb1-be50-4627-9ca5-68b09f58a8e4",70,"WINGS"),row(null,"183b9a00-3169-402a-835a-dca1918906de",60,"Indigo"),
                row(null,"183b9a00-3169-402a-835a-dca1918906de",50,"Indigo")).joinToString(",")}]}}""")
            assertEquals(5,c.trending(DiscoverKind.Album).value.size,"the source really lists the group twice")
            val shelf=com.legitimateapps.dulcet.server.discover.DiscoverShelves(db,com.legitimateapps.dulcet.server.auth.AccountAccess(db),c).shelf("nobody","trendingAlbums").value
            assertEquals(listOf("ARIRANG" to 90L,"WINGS" to 70L,"Indigo" to 60L,"Indigo" to 50L),shelf.map { it.title to it.listenCount })
            assertEquals("909d000d-56e9-4b38-aa3f-008d38436ea2",shelf.first().externalIds[ListenBrainzClient.COVER_ART_RELEASE])
        } } }
    }
}
