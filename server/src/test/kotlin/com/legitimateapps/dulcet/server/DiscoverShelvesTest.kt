package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DiscoverShelvesTest {
    private inline fun <T> ServerContext.use(block:(ServerContext)->T):T = try { block(this) } finally { db.close() }
    private val a="11111111-1111-4111-8111-111111111111"
    private val b="22222222-2222-4222-8222-222222222222"
    private val x="33333333-3333-4333-8333-333333333333"
    private val y="44444444-4444-4444-8444-444444444444"
    @Test fun usersShareSeedAnswersButNotHistoryOrOrder() = runBlocking {
        val root=Files.createTempDirectory("discover-shelves")
        Fixtures.context(root).use { ctx -> FixtureDiscoverUpstream().use { f -> PoliteHttp().use { h ->
            val u=ctx.users.create("one","password",false); val v=ctx.users.create("two","password",false)
            for((name,id) in listOf("A" to a,"B" to b)) Fixtures.track(ctx.config.musicFolder.resolve("$name.flac"),tags=mapOf("artist" to name,"album" to name,"MUSICBRAINZ_ARTISTID" to id,"MUSICBRAINZ_ALBUMARTISTID" to id))
            ctx.scanner.scan()
            val artists=ctx.db.read { query("SELECT id,mbz_artist_id FROM artist") { it.string("mbz_artist_id") to it.string("id") }.toMap() }
            ctx.db.write {
                for((user,first,second) in listOf(Triple(u.id,a,b),Triple(v.id,b,a))) {
                    execute("INSERT INTO annotation(user_id,item_type,item_id,play_count) VALUES(?,'artist',?,10)",user,artists[first])
                    execute("INSERT INTO annotation(user_id,item_type,item_id,play_count) VALUES(?,'artist',?,1)",user,artists[second])
                }
            }
            h.configureOrigin(f.baseUrl,0)
            fun q(id:String)=mapOf("artist_mbids" to id,"algorithm" to ListenBrainzClient.SIMILAR_ALGORITHM)
            f.respond("/similar-artists/json",q(a),body="[{\"artist_mbid\":\"$x\",\"name\":\"X\",\"reference_mbid\":\"$a\"}]".toByteArray())
            f.respond("/similar-artists/json",q(b),body="[{\"artist_mbid\":\"$y\",\"name\":\"Y\",\"reference_mbid\":\"$b\"}]".toByteArray())
            val c=ListenBrainzClient(h,DiscoverCache(ctx.db),DiscoverSettings(listenBrainzLabsUrl=f.baseUrl))
            val shelves=DiscoverShelves(ctx.db,ctx.access,c)
            assertEquals(listOf("X","Y"),shelves.shelf(u.id,"similarArtists").value.map { it.title })
            assertEquals(listOf("Y","X"),shelves.shelf(v.id,"similarArtists").value.map { it.title })
            assertEquals(2,f.requests.size)
        } } }
    }
    @Test fun newReleasesPreferMyLibraryOverBrowse() = runBlocking {
        Fixtures.context(Files.createTempDirectory("discover-new")).use { ctx -> FixtureDiscoverUpstream().use { f -> PoliteHttp().use { h ->
            val u=ctx.users.create("member","password",false)
            Fixtures.track(ctx.config.musicFolder.resolve("a.flac"),tags=mapOf("artist" to "A","album" to "A","MUSICBRAINZ_ARTISTID" to a,"MUSICBRAINZ_ALBUMARTISTID" to a))
            ctx.scanner.scan()
            ctx.db.write { execute("INSERT INTO library_item(user_id,item_type,item_id,added) SELECT ?,'song',id,1 FROM track",u.id) }
            f.json("/1/explore/fresh-releases/","""{"payload":{"releases":[{"release_group_mbid":"$x","release_name":"Newer","artist_mbids":["$b"],"release_date":"2026-10-22"},{"release_group_mbid":"$y","release_name":"Mine","artist_mbids":["$a"],"release_date":"2026-10-01"}]}}""")
            val shelves=DiscoverShelves(ctx.db,ctx.access,ListenBrainzClient(h,DiscoverCache(ctx.db),DiscoverSettings(listenBrainzUrl=f.baseUrl)))
            assertEquals(listOf("Mine","Newer"),shelves.shelf(u.id,"newReleases").value.map { it.title })
        } } }
    }
    @Test fun noSeedsMakesNoRequestAndLabelsAreExact() = runBlocking {
        Fixtures.context(Files.createTempDirectory("discover-empty")).use { ctx -> FixtureDiscoverUpstream().use { f -> PoliteHttp().use { h ->
            val u=ctx.users.create("empty","password",false)
            val shelves=DiscoverShelves(ctx.db,ctx.access,ListenBrainzClient(h,DiscoverCache(ctx.db),DiscoverSettings(listenBrainzLabsUrl=f.baseUrl)))
            assertTrue(shelves.shelf(u.id,"similarArtists").value.isEmpty()); assertEquals(0,f.requests.size)
            assertEquals("Trending on ListenBrainz",DiscoverShelves.TRENDING_LABEL)
            assertEquals("Data from MusicBrainz and ListenBrainz",DiscoverShelves.CREDIT)
        } } }
    }
}
