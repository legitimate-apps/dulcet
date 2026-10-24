package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Generated data only. The count gates detect catalog-size-dependent database work, not machine speed. */
class DiscoverScaleTest {
    private fun mbid(n: Int) = "${n.toString().padStart(8, '0')}-1111-4111-8111-111111111111"
    private fun group(n: Int) = mbid(100_000 + n)
    private fun artist(n: Int) = mbid(200_000 + n)
    private fun recording(album: Int, position: Int) = mbid(300_000 + (album - 1) * 10 + position)
    private fun release(n: Int) = mbid(400_000 + n)
    private val origin = "http://127.0.0.1:9"

    private class Work : Db.WorkObserver {
        var statements = 0
        var transactions = 0
        var catalogReads = 0
        var rejectionReads = 0
        override fun transaction(write: Boolean) { transactions++ }
        override fun statement(sql: String) {
            statements++
            val s = sql.lowercase().replace(Regex("\\s+"), " ").replace(Regex("\\s*=\\s*"), "=")
            if (s.startsWith("select") && Regex("from track(?: t)? where").containsMatchIn(s) &&
                !s.contains("where id=") && !s.contains("where t.id=") &&
                !s.contains("album_id=?") && !s.contains("mbz_recording_id=?")) catalogReads++
            if (s.startsWith("select") && "from wishlist_rejected_match" in s) rejectionReads++
        }
        override fun toString() = "statements=$statements transactions=$transactions catalogReads=$catalogReads rejectionReads=$rejectionReads"
    }

    private class Catalog(val ctx: ServerContext, val first: String, val second: String) : AutoCloseable {
        override fun close() { ctx.close(); ctx.db.close() }
    }

    private suspend fun catalog(): Catalog {
        val root = Files.createTempDirectory("discover-generated-scale")
        val ctx = ServerContext(Config(musicFolder=root.resolve("music").also(Files::createDirectories),
            dataFolder=root.resolve("data"), port=0, bind="127.0.0.1", ffmpeg=Fixtures.ffmpeg, ffprobe=Fixtures.ffprobe, scanOnStart=false, lrclibUrl=null,
            discover=DiscoverSettings(musicBrainzUrl=origin, listenBrainzUrl=origin,
                listenBrainzLabsUrl=origin, coverArtArchiveUrl=origin, musicBrainzMinGapMillis=0)), Db.inMemory())
        val first = ctx.users.create("generated-first", "generated-password", false).id
        val second = ctx.users.create("generated-second", "generated-password", false).id
        ctx.db.write {
            execute("INSERT INTO music_folder VALUES(2,'Hidden generated source','generated-hidden')")
            execute("INSERT INTO api_user_access(user_id,source_ids,permissions) VALUES(?,'[\"1\"]','{}')", first)
            execute("INSERT INTO api_user_access(user_id,source_ids,permissions) VALUES(?,'[\"1\",\"2\"]','{}')", second)
            for (album in 1..4_000) {
                val a = "generated-album-$album"; val ar = "generated-artist-$album"
                execute("""INSERT INTO artist(id,name,sort_name,album_count,song_count,search,mbz_artist_id)
                    VALUES(?,?,?,1,10,?,?)""", ar, "Artist $album", "artist $album", "artist $album", artist(album))
                execute("""INSERT INTO album(id,name,sort_name,album_artist,artist_id,compilation,song_count,duration,size,created,search)
                    VALUES(?,?,?,?,?,0,10,100,1000,1,?)""", a, "Album $album", "album $album", "Artist $album", ar, "album $album")
                execute("INSERT INTO album_artist VALUES(?,?,?,0)", a, ar, "Artist $album")
                for (position in 1..10) {
                    val id = "generated-track-$album-$position"
                    val title = "Song $album $position" + if (album == 4 && position == 1) " (live)" else ""
                    execute("""INSERT INTO track(id,path,folder_id,parent_dir,mtime,size,title,album,album_id,artist,artist_id,
                        album_artist,album_artist_id,duration,bitrate,suffix,content_type,created,updated,search,
                        mbz_recording_id,mbz_release_group_id,disc_no,track_no)
                        VALUES(?,?,?,'',1,100,?,?,?,?,?,?,?,10,128,'flac','audio/flac',1,1,'',?,?,1,?)""",
                        id, "$id.flac", if (album == 3) 2 else 1, title, "Album $album", a,
                        "Artist $album", ar, "Artist $album", ar, recording(album,position), group(album), position)
                    execute("INSERT INTO track_artist VALUES(?,'artist',?,?,0)", id, ar, "Artist $album")
                }
            }
            for ((user, album) in listOf(first to 1, second to 2)) execute("""INSERT INTO wishlist_rejected_match
                VALUES(?,'album',?,'album',?,1)""", user, group(album), "generated-album-$album")
            execute("INSERT INTO annotation(user_id,item_type,item_id,play_count,played) VALUES(?,'song','generated-track-5-1',3,?)", first, System.currentTimeMillis())
            for (album in 1..5) execute("""INSERT INTO wishlist_wish(id,user_id,kind,canonical_mbid,external_ids,title,artist,source_type,added,state,updated)
                VALUES(?,?,'album',?,?,?,?,'search',1,'wanted',1)""", "generated-wish-$album", first, group(album),
                "{\"musicbrainzReleaseGroupId\":\"${group(album)}\"}", "Album $album", "Artist $album")
        }
        assertEquals(40_000, ctx.db.read { long("SELECT COUNT(*) FROM track") })
        assertEquals(4_000, ctx.db.read { long("SELECT COUNT(*) FROM album") })
        suspend fun cached(path: String, query: Map<String,String>, body: String) {
            ctx.discoverCache.read(CacheKey(origin,path,query),DiscoverCache.DAY,{ it.decodeToString() }) {
                UpstreamResponse(200, emptyMap(), body.toByteArray())
            }
        }
        fun albumJson(n: Int) = """{"id":"${group(n)}","title":"Album $n","artist-credit":[{"name":"Artist $n","artist":{"id":"${artist(n)}"}}]}"""
        val albums=(1..100).joinToString(",",transform=::albumJson)
        cached("/ws/2/release-group",mapOf("query" to "generated","offset" to "0","limit" to "100","fmt" to "json"),"""{"count":100,"release-groups":[$albums]}""")
        cached("/ws/2/release-group",mapOf("artist" to artist(1),"inc" to "artist-credits","limit" to "100","offset" to "0","fmt" to "json"),"""{"release-group-count":5,"release-groups":[${(1..5).joinToString(",",transform=::albumJson)}]}""")
        cached("/ws/2/artist",mapOf("query" to "generated","offset" to "0","limit" to "100","fmt" to "json"),"""{"count":5,"artists":[${(1..5).joinToString(",") { """{"id":"${artist(it)}","name":"Artist $it"}""" }}]}""")
        cached("/ws/2/artist/${artist(1)}",mapOf("inc" to "url-rels","fmt" to "json"),"""{"id":"${artist(1)}","name":"Artist 1"}""")
        cached("/ws/2/recording",mapOf("query" to "generated","offset" to "0","limit" to "100","fmt" to "json"),"""{"count":5,"recordings":[${(1..5).joinToString(",") { """{"id":"${recording(5,it)}","title":"Song 5 $it","artist-credit":[{"name":"Artist 5"}]}""" }}]}""")
        for (album in 1..5) {
            cached("/ws/2/release-group/${group(album)}",mapOf("inc" to "artist-credits+url-rels","fmt" to "json"),albumJson(album))
            val tracks=(1..10).joinToString(",") { p -> """{"position":$p,"title":"Song $album $p","recording":{"id":"${recording(album,p)}","title":"Song $album $p"},"artist-credit":[{"name":"Artist $album"}]}""" }
            cached("/ws/2/release",mapOf("release-group" to group(album),"inc" to "recordings+artist-credits+release-groups","limit" to "100","offset" to "0","fmt" to "json"),"""{"release-count":1,"releases":[{"id":"${release(album)}","release-group":{"id":"${group(album)}"},"media":[{"position":1,"track-count":10,"tracks":[$tracks]}]}]}""")
        }
        cached("/1/stats/sitewide/release-groups",mapOf("range" to "week"),"""{"payload":{"release_groups":[${(1..5).joinToString(",") { """{"release_group_mbid":"${group(it)}","release_group_name":"Album $it","artist_name":"Artist $it","listen_count":10}""" }}]}}""")
        cached("/1/playlist/${mbid(900_000)}",emptyMap(),"""{"playlist":{"identifier":"https://listenbrainz.org/playlist/${mbid(900_000)}","title":"Generated","track":[${(1..5).joinToString(",") { """{"title":"Song 5 $it","creator":"Artist 5","identifier":["https://musicbrainz.org/recording/${recording(5,it)}"]}""" }}]}}""")
        return Catalog(ctx,first,second)
    }

    private suspend fun <T> measure(ctx: ServerContext, name: String, action: suspend () -> T): Pair<T,Work> {
        action() // Decode/cache/opaque-ID warmup is outside the measured request.
        val work=Work(); ctx.db.workObserver=work
        val started=System.nanoTime()
        val result=try { action() } finally { ctx.db.workObserver=null }
        println("GENERATED_DISCOVER_SCALE path=$name tracks=40000 albums=4000 $work elapsedMillis=${(System.nanoTime()-started)/1_000_000}")
        return result to work
    }

    private fun bounded(name: String, work: Work, statements: Int=600, transactions: Int=120) {
        assertTrue(work.statements<=statements,"$name exceeded bounded SQL budget: $work limit=$statements")
        assertTrue(work.transactions<=transactions,"$name exceeded bounded transaction budget: $work limit=$transactions")
        assertTrue(work.catalogReads<=1,"$name reread the whole visible track catalog: $work")
        assertTrue(work.rejectionReads<=2,"$name performed per-album rejection reads: $work")
    }

    @Test fun cachedFiveAlbumSearchIsBoundedAndKeepsPersonalAvailabilityAtFortyThousandTracks() = runBlocking {
        catalog().use { c ->
            val (page,work)=measure(c.ctx,"album-search-five") { c.ctx.discover.search(c.first,DiscoverKind.Album,"generated",PageRequest(5)) }
            assertEquals(5,page.items.size)
            assertEquals(listOf("notHeld","held","notHeld","partlyHeld","held"),page.items.map { it.availability.status })
            assertEquals(9,page.items[3].availability.matchedTrackCount)
            assertEquals(10,page.items[3].availability.totalTrackCount)
            assertTrue(page.items.all { it.artworkId?.startsWith("da_")==true })
            assertEquals(listOf("generated-album-2"),page.items[1].availability.libraryItems.map {it.id})
            val other=c.ctx.discover.search(c.second,DiscoverKind.Album,"generated",PageRequest(5))
            assertEquals(listOf("held","notHeld","held","partlyHeld","held"),other.items.map { it.availability.status },"Shared upstream cache must not share user rejections/source visibility")
            c.ctx.db.write {
                execute("UPDATE api_user_access SET source_ids='[\"1\",\"2\"]' WHERE user_id=?",c.first)
                execute("DELETE FROM wishlist_rejected_match WHERE user_id=?",c.first)
                execute("UPDATE track SET title='Song 4 1' WHERE id='generated-track-4-1'")
            }
            val refreshed=c.ctx.discover.search(c.first,DiscoverKind.Album,"generated",PageRequest(5))
            assertTrue(refreshed.items.all { it.availability.status=="held" },"Next request must see catalog, source-policy and rejection changes despite a shared upstream cache")
            bounded("album-search-five",work)
        }
    }

    @Test fun generatedCatalogSweepKeepsOtherMatchingPathsBounded() = runBlocking {
        catalog().use { c ->
            val failures=mutableListOf<String>()
            suspend fun path(name:String, action:suspend()->Any) {
                val (_,work)=measure(c.ctx,name,action)
                try { bounded(name,work) } catch (error:AssertionError) { failures+=error.message.orEmpty() }
            }
            path("artist-search-five") { c.ctx.discover.search(c.first,DiscoverKind.Artist,"generated",PageRequest(5)).also { assertEquals(5,it.items.size);assertEquals("notHeld",it.items[2].availability.status) } }
            path("song-search-five") { c.ctx.discover.search(c.first,DiscoverKind.Song,"generated",PageRequest(5)).also { assertEquals(5,it.items.size);assertTrue(it.items.all { row->row.availability.status=="held" }) } }
            path("artist-albums-five") { c.ctx.discover.albums(c.first,artist(1),PageRequest(5)).also {assertEquals(5,it.items.size)} }
            path("artist-detail") { c.ctx.discover.artist(c.first,artist(1)).also {assertEquals(5,it.albums.items.size)} }
            path("album-detail-with-ten-tracks") { c.ctx.discover.album(c.first,group(5)).also {assertEquals("held",it.item.availability.status);assertEquals(10,it.tracks.items.size)} }
            path("album-tracks-ten") { c.ctx.discover.albumTracks(c.first,group(5),release(5),PageRequest(10)).also {assertEquals(10,it.items.size)} }
            path("trending-albums-five") { c.ctx.discover.shelf(c.first,"trendingAlbums",PageRequest(5)).also {assertEquals(5,it.items.size)} }
            path("playlist-five") { c.ctx.discover.playlist(c.first,mbid(900_000),PageRequest(5)).also {assertEquals(5,it.entries.items.size)} }
            path("wishlist-list-five") { c.ctx.wishlist.list(c.first).also {assertEquals(5,it.size)} }
            path("listen-now") { c.ctx.home.shelves(c.first,5).also {assertTrue(it.any {shelf->shelf.id=="recent" && shelf.ids.isNotEmpty()})} }
            assertTrue(failures.isEmpty(),failures.joinToString("\n"))
        }
    }

    @Test fun generatedWishlistAndListenNowHttpProjectionRemainBounded() = testApplication {
        catalog().use { c ->
            val user=c.ctx.users.find("generated-first")!!
            val token=c.ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read","user:read"))
            application { dulcetModule(c.ctx) }
            for ((name,path) in listOf("wishlist-http-five" to "/dulcet/v1/me/wishes?limit=5",
                "listen-now-http" to "/dulcet/v1/recommendations/home?limit=5")) {
                val (doc,work)=measure(c.ctx,name) {
                    val response=client.get(path) {bearerAuth(token)}
                    assertEquals(200,response.status.value)
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject
                }
                if(name=="wishlist-http-five")assertEquals(5,doc["items"]!!.jsonArray.size)
                else assertTrue(doc["shelves"]!!.jsonArray.any { row->
                    row.jsonObject["id"]!!.jsonPrimitive.content=="recent" && row.jsonObject["items"]!!.jsonArray.isNotEmpty()
                })
                bounded(name,work,1_500,200)
            }
        }
    }
}
