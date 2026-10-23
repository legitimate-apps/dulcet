package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MusicBrainzClientTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private fun resource(name:String)=javaClass.getResource("/discover/musicbrainz/$name.json")!!.readText()
    private fun settings(f:FixtureDiscoverUpstream)=DiscoverSettings(musicBrainzUrl=f.baseUrl,musicBrainzMinGapMillis=0)
    @Test fun oneKindSearchUsesCorrectEntityAndEscapesQuery() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            val c=MusicBrainzClient(h,DiscoverCache(db),settings(f))
            for((kind,entity) in listOf(DiscoverKind.Artist to "artist",DiscoverKind.Album to "release-group",DiscoverKind.Song to "recording")) {
                f.json("/ws/2/$entity",resource("$entity-search"))
                val result=c.search(kind,"Björk & AC/DC",0,20)
                assertEquals(kind,result.value.items.single().kind); assertEquals("Björk & AC/DC",f.requests.last().query["query"])
                assertEquals("json",f.requests.last().query["fmt"])
            }
            assertEquals(3,f.requests.size)
        } } }
    }
    @Test fun discographyPagesPastTwentyFiveNewestFirst() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            val c=MusicBrainzClient(h,DiscoverCache(db),settings(f))
            fun query(offset:String)=mapOf("artist" to id,"inc" to "artist-credits","limit" to "100","offset" to offset,"fmt" to "json")
            f.respond("/ws/2/release-group",query("0"),body=resource("discography-page-1").toByteArray())
            f.respond("/ws/2/release-group",query("25"),body=resource("discography-page-2").toByteArray())
            val rows=c.discography(id).value
            assertEquals(26,rows.size); assertEquals("Newest",rows.first().title); assertEquals("Unknown",rows.last().title)
            assertEquals("25",f.requests.last().query["offset"])
        } } }
    }
    @Test fun editionPagingUsesActualRowsAndEveryDisc() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            val c=MusicBrainzClient(h,DiscoverCache(db),settings(f))
            fun query(offset:String)=mapOf("release-group" to id,"inc" to "recordings+artist-credits+release-groups","limit" to "100","offset" to offset,"fmt" to "json")
            f.respond("/ws/2/release",query("0"),body=resource("editions-page-1").toByteArray())
            f.respond("/ws/2/release",query("2"),body=resource("editions-page-2").toByteArray())
            val rows=c.editions(id).value
            assertEquals(26,rows.size); assertEquals("2",f.requests.last().query["offset"])
            assertEquals(listOf(1,2),rows.first().tracks.map { it.disc }.distinct())
            assertEquals(3,rows.first().tracks.size)
            f.json("/ws/2/release/$id",resource("release"))
            assertEquals(3,c.release(id).value.tracks.size)
        } } }
    }
    @Test fun prematureEmptyBrowseAndMalformedSuccessAreUnavailable() = runBlocking {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            val c=MusicBrainzClient(h,DiscoverCache(db),settings(f))
            f.json("/ws/2/release-group","{\"release-group-count\":26,\"release-groups\":[]}")
            assertFailsWith<UpstreamUnavailable> { c.discography(id) }
            f.json("/ws/2/artist","{}")
            assertFailsWith<UpstreamUnavailable> { c.search(DiscoverKind.Artist,"bad",0,20) }
            f.json("/ws/2/artist/$id",resource("artist"))
            val artist=c.artist(id).value
            assertEquals(1,artist.links.size); assertTrue(artist.links.single().url.startsWith("https://"))
        } } }
    }
}
