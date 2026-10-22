package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ApiDiscoverTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private fun context(f:FixtureDiscoverUpstream,enabled:Boolean=true):ServerContext {
        val root=Files.createTempDirectory("api-discover")
        return ServerContext(Config(port=0,bind="127.0.0.1",musicFolder=root.resolve("music").also(Files::createDirectories),dataFolder=root.resolve("data"),ffmpeg=Fixtures.ffmpeg,ffprobe=Fixtures.ffprobe,scanOnStart=false,lrclibUrl=null,discover=DiscoverSettings(enabled=enabled,musicBrainzUrl=f.baseUrl,musicBrainzMinGapMillis=0,coverArtArchiveUrl=f.baseUrl,listenBrainzUrl=f.baseUrl,listenBrainzLabsUrl=f.baseUrl)),Db.inMemory())
    }
    private fun resource(name:String)=javaClass.getResource("/discover/$name.json")!!.readText()
    private fun search(f:FixtureDiscoverUpstream,count:Int=40) { f.json("/ws/2/artist","""{"count":$count,"artists":[${(1..count).joinToString(",") { """{"id":"${it.toString().padStart(8,'0')}-1111-4111-8111-111111111111","name":"Artist $it"}""" }}]}""") }
    @Test fun capabilitySwitchesAndEveryScope() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f,false)
            try {
                val u=ctx.users.create("user","password",false);val read=ctx.apiAuth.bootstrapPersonal(u,setOf("catalog:read"));val wrong=ctx.apiAuth.bootstrapPersonal(u,setOf("user:read"))
                application { dulcetModule(ctx) }
                val caps=Json.parseToJsonElement(client.get("/dulcet/v1/capabilities").bodyAsText()).jsonObject["capabilities"]!!.jsonObject
                assertFalse("dulcet.discover" in caps);assertEquals("[1]",caps["dulcet.wishlist"]!!.jsonObject["versions"].toString())
                val paths=listOf("/search?kind=artist&q=a","/shelves/trendingArtists","/artists/$id","/artists/$id/albums","/albums/$id","/albums/$id/tracks?releaseId=$id","/playlists?q=","/playlists/$id","/artwork/da_unknown")
                for(path in paths) {
                    assertEquals(401,client.get("/dulcet/v1/discover$path").status.value)
                    assertEquals(403,client.get("/dulcet/v1/discover$path") { bearerAuth(wrong) }.status.value)
                    assertEquals(404,client.get("/dulcet/v1/discover$path") { bearerAuth(read) }.status.value)
                }
                assertEquals(0,f.requests.size)
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun cursorGenerationAndQueryMismatchNeverMixPages() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f)
            try {
                val u=ctx.users.create("user","password",false);val v=ctx.users.create("other","password",false)
                val token=ctx.apiAuth.bootstrapPersonal(u,setOf("catalog:read"));val other=ctx.apiAuth.bootstrapPersonal(v,setOf("catalog:read"));search(f)
                application { dulcetModule(ctx) }
                val first=client.get("/dulcet/v1/discover/search?kind=artist&q=a") { bearerAuth(token) }
                assertEquals(200,first.status.value);assertEquals("private",first.headers["Cache-Control"]);assertEquals("Authorization",first.headers["Vary"])
                val page=Json.parseToJsonElement(first.bodyAsText()).jsonObject
                assertEquals(20,page["items"]!!.jsonArray.size);val cursor=page["nextCursor"]!!.jsonPrimitive.content
                val next=client.get("/dulcet/v1/discover/search") { bearerAuth(token);parameter("kind","artist");parameter("q","a");parameter("cursor",cursor) }
                assertEquals(20,Json.parseToJsonElement(next.bodyAsText()).jsonObject["items"]!!.jsonArray.size);assertEquals(1,f.requests.size)
                for((who,q,expected) in listOf(Triple(other,"a",400),Triple(token,"b",400))) assertEquals(expected,client.get("/dulcet/v1/discover/search") {bearerAuth(who);parameter("kind","artist");parameter("q",q);parameter("cursor",cursor)}.status.value)
                ctx.db.write { execute("DELETE FROM discover_cache") }
                assertEquals(410,client.get("/dulcet/v1/discover/search") {bearerAuth(token);parameter("kind","artist");parameter("q","a");parameter("cursor",cursor)}.status.value)
                for(limit in listOf(0,101))assertEquals(400,client.get("/dulcet/v1/discover/search?kind=artist&q=a&limit=$limit") {bearerAuth(token)}.status.value)
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun allPathsProjectContractAndServerArtwork() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f)
            try {
                val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"))
                f.json("/ws/2/artist/$id",resource("musicbrainz/artist"));f.json("/ws/2/release-group","""{"release-group-count":1,"release-groups":[{"id":"$id","title":"Album","first-release-date":"2026-10","artist-credit":[{"name":"Artist"}]}]}""")
                f.json("/ws/2/release-group/$id",resource("musicbrainz/release-group"));f.json("/ws/2/release","""{"release-count":1,"releases":[${resource("musicbrainz/release")}]}""")
                f.json("/1/playlist/search",resource("listenbrainz/playlist-search"));f.json("/1/playlist/$id",resource("listenbrainz/playlist"));f.json("/1/stats/sitewide/artists",resource("listenbrainz/trending-artists"))
                f.respond("/release-group/$id/front-250",body=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0))
                application { dulcetModule(ctx) }
                suspend fun get(path:String)=client.get("/dulcet/v1/discover$path") { bearerAuth(token) }
                for(path in listOf("/shelves/trendingArtists","/shelves/publicPlaylists","/artists/$id","/artists/$id/albums","/playlists?q=","/playlists/$id"))assertEquals(200,get(path).status.value,path)
                val response=get("/albums/$id");assertEquals(200,response.status.value)
                val album=Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val item=album["item"]!!.jsonObject;assertTrue(item["id"]!!.jsonPrimitive.content.startsWith("di_"))
                val art=item["artworkId"]!!.jsonPrimitive.content;assertTrue(art.startsWith("da_"))
                assertEquals("image/png",get("/artwork/$art").headers["Content-Type"])
                val release=album["selectedReleaseId"]!!.jsonPrimitive.content
                assertEquals(200,get("/albums/$id/tracks?releaseId=$release").status.value)
                val etag=response.headers["ETag"]!!
                assertEquals(304,client.get("/dulcet/v1/discover/albums/$id") {bearerAuth(token);header("If-None-Match",etag)}.status.value)
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun emptySuccessDiffersFromUncachedOutage() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f)
            try {
                val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"));f.json("/ws/2/artist","{\"count\":0,\"artists\":[]}")
                application { dulcetModule(ctx) }
                assertEquals(200,client.get("/dulcet/v1/discover/search?kind=artist&q=empty") {bearerAuth(token)}.status.value)
                f.json("/ws/2/artist","{}",503,mapOf("Retry-After" to "120"))
                val failure=client.get("/dulcet/v1/discover/search?kind=artist&q=missing") {bearerAuth(token)}
                assertEquals(503,failure.status.value);assertEquals("120",failure.headers["Retry-After"])
                assertTrue(failure.bodyAsText().contains("upstream-unavailable"))
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun sharedCacheDoesNotLeakPrivateMatchesOrEtags() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f)
            try {
                val u=ctx.users.create("user","password",false);val v=ctx.users.create("other","password",false)
                val a=ctx.apiAuth.bootstrapPersonal(u,setOf("catalog:read"));val b=ctx.apiAuth.bootstrapPersonal(v,setOf("catalog:read"))
                Fixtures.track(ctx.config.musicFolder.resolve("one.flac"),tags=mapOf("artist" to "Artist","album" to "Album","MUSICBRAINZ_ARTISTID" to id,"MUSICBRAINZ_ALBUMARTISTID" to id));ctx.scanner.scan()
                f.json("/ws/2/artist","""{"count":1,"artists":[{"id":"$id","name":"Artist"}]}""")
                val local=ctx.db.read { string("SELECT id FROM artist WHERE mbz_artist_id=?",id) }!!
                ctx.wishlist.rejectMatch(u.id,com.legitimateapps.dulcet.server.wishlist.ExternalTarget(DiscoverKind.Artist,mapOf(DiscoverKind.Artist.registryKey to id)),LibraryRef(local,"artist"),null,java.util.UUID.randomUUID().toString())
                application { dulcetModule(ctx) }
                suspend fun result(token:String)=client.get("/dulcet/v1/discover/search?kind=artist&q=artist") {bearerAuth(token)}
                val one=result(a);val two=result(b)
                fun status(text:String)=Json.parseToJsonElement(text).jsonObject["items"]!!.jsonArray.single().jsonObject["availability"]!!.jsonObject["status"]!!.jsonPrimitive.content
                assertEquals("notHeld",status(one.bodyAsText()));assertEquals("held",status(two.bodyAsText()))
                assertNotEquals(one.headers["ETag"],two.headers["ETag"]);assertEquals(1,f.requests.size)
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun searchContinuesPastFirstHundredWithoutPublicOffsets() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f)
            try {
                val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"))
                fun rows(range:IntRange)=range.joinToString(",") { """{"id":"${it.toString().padStart(8,'0')}-1111-4111-8111-111111111111","name":"Artist $it"}""" }
                f.respond("/ws/2/artist",mapOf("query" to "many","offset" to "0","limit" to "100","fmt" to "json"),body="""{"count":120,"artists":[${rows(1..100)}]}""".toByteArray())
                f.respond("/ws/2/artist",mapOf("query" to "many","offset" to "100","limit" to "100","fmt" to "json"),body="""{"count":120,"artists":[${rows(101..120)}]}""".toByteArray())
                application { dulcetModule(ctx) }
                val first=Json.parseToJsonElement(client.get("/dulcet/v1/discover/search?kind=artist&q=many&limit=100") {bearerAuth(token)}.bodyAsText()).jsonObject
                assertEquals(100,first["items"]!!.jsonArray.size)
                val next=client.get("/dulcet/v1/discover/search") {bearerAuth(token);parameter("kind","artist");parameter("q","many");parameter("cursor",first["nextCursor"]!!.jsonPrimitive.content)}
                val page=Json.parseToJsonElement(next.bodyAsText()).jsonObject
                assertEquals(20,page["items"]!!.jsonArray.size);assertEquals("Artist 120",page["items"]!!.jsonArray.last().jsonObject["title"]!!.jsonPrimitive.content)
                assertEquals(2,f.requests.size)
            } finally {ctx.close();ctx.db.close()}
        }
    }

}
