package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.v1.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

/** Real loopback HTTP server, real tag probing/scanner, persistent DB, real webhook transport. */
class DiscoverWishlistIntegrationTest {
    private val group="11111111-1111-4111-8111-111111111111"
    private val artist="22222222-2222-4222-8222-222222222222"
    private fun recording(n:Int)="${n.toString().padStart(8,'0')}-3333-4333-8333-333333333333"
    private fun op()=UUID.randomUUID().toString()
    private fun config(f:FixtureDiscoverUpstream):Config {
        val root=Files.createTempDirectory("discover-wishlist-http")
        return Config(port=0,bind="127.0.0.1",musicFolder=root.resolve("music").also(Files::createDirectories),dataFolder=root.resolve("data"),ffmpeg=Fixtures.ffmpeg,ffprobe=Fixtures.ffprobe,lrclibUrl=null,scanOnStart=false,testMode=true,discover=DiscoverSettings(musicBrainzUrl=f.baseUrl,musicBrainzMinGapMillis=0,listenBrainzUrl=f.baseUrl,listenBrainzLabsUrl=f.baseUrl,coverArtArchiveUrl=f.baseUrl))
    }
    private class Live(val config:Config):AutoCloseable {
        val ctx=ServerContext(config,Db.open(config.dataFolder.resolve("dulcet.db")))
        private val server=embeddedServer(CIO,host="127.0.0.1",port=0){dulcetModule(ctx)}.start(wait=false)
        val base="http://127.0.0.1:"+runBlocking{server.engine.resolvedConnectors().first().port}
        private val http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
        fun request(method:String,path:String,token:String?=null,body:JsonObject?=null,action:String?=null):HttpResponse<String> {
            val b=HttpRequest.newBuilder(URI(base+path)).timeout(java.time.Duration.ofSeconds(15))
            token?.let{b.header("Authorization","Bearer $it")};action?.let{b.header("Dulcet-Action-Time",it)}
            if(body!=null)b.header("Content-Type","application/json")
            return http.send(b.method(method,body?.let{HttpRequest.BodyPublishers.ofString(it.toString())} ?: HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
        }
        fun api(path:String,token:String,method:String="GET",body:JsonObject?=null,status:Int=200):JsonObject {
            val r=request(method,"/dulcet/v1$path",token,body);assertEquals(status,r.statusCode(),r.body());return Json.parseToJsonElement(r.body()).jsonObject
        }
        fun token(user:com.legitimateapps.dulcet.server.auth.User,scopes:Set<String> = setOf("account","catalog:read","user:read","user:write","user:delete"))=ctx.apiAuth.bootstrapPersonal(user,scopes)
        fun scan(){ctx.scanner.scan();runBlocking{ctx.wishFulfilment.drain()}}
        override fun close(){server.stop(0,1000);ctx.close();ctx.db.close()}
    }
    private fun addBody(kind:DiscoverKind,operation:String=op())=j("kind" to kind.wire,"externalIds" to mapOf(kind.registryKey to if(kind==DiscoverKind.Album)group else recording(1)),"snapshot" to j("title" to if(kind==DiscoverKind.Album)"Album" else "Song 1","artist" to "Artist"),"source" to j("type" to "api"),"operationId" to operation)
    private fun track(c:Config,n:Int,album:String="Album")=Fixtures.track(c.musicFolder.resolve("$album/$n.flac"),tags=mapOf("title" to "Song $n","album" to album,"artist" to "Artist","tracknumber" to "$n","MUSICBRAINZ_RELEASEGROUPID" to group,"MUSICBRAINZ_ARTISTID" to artist,"MUSICBRAINZ_ALBUMARTISTID" to artist,"MUSICBRAINZ_TRACKID" to recording(n)))
    private fun editions(f:FixtureDiscoverUpstream,count:Int) {
        f.json("/ws/2/release","""{"release-count":1,"releases":[{"id":"${recording(99)}","release-group":{"id":"$group"},"artist-credit":[{"name":"Artist"}],"media":[{"position":1,"track-count":$count,"tracks":[${(1..count).joinToString(","){"""{"position":$it,"title":"Song $it","recording":{"id":"${recording(it)}","title":"Song $it"}}"""}}]}]}]}""")
    }
    @Test fun discoverWishWebhookScanArrivedLibraryAndFeed() {
        FixtureDiscoverUpstream().use { f ->Live(config(f)).use { s ->
            val owner=s.ctx.users.create("owner","password",false);val other=s.ctx.users.create("other","password",false);val admin=s.ctx.users.create("admin","password",true)
            val token=s.token(owner);val otherToken=s.token(other);val adminToken=s.token(admin,API_SCOPES)
            s.ctx.testing.replace(j("webhooks" to j("allowPrivateTargets" to true)))
            f.respond("/events",status=204,body=byteArrayOf())
            val adminHook=s.api("/webhooks",adminToken,"POST",j("url" to "${f.baseUrl}/events","events" to listOf("wishlist.requested")),201)
            s.api("/webhooks",token,"POST",j("url" to "${f.baseUrl}/events","events" to listOf("wishlist.changed")),201)
            f.json("/ws/2/recording","""{"count":1,"recordings":[{"id":"${recording(1)}","title":"Song 1","artist-credit":[{"name":"Artist"}]}]}""")
            val found=s.api("/discover/search?kind=song&q=Song",token)["items"]!!.jsonArray.single().jsonObject
            assertTrue(found["wishable"]!!.jsonPrimitive.boolean)
            val before=s.api("/sync/changes?types=wish&start=now",token)["nextCursor"]!!.jsonPrimitive.content
            val otherBefore=s.api("/sync/changes?types=wish&start=now",otherToken)["nextCursor"]!!.jsonPrimitive.content
            val wish=s.api("/me/wishes",token,"POST",addBody(DiscoverKind.Song),201)["wish"]!!.jsonObject
            val id=wish["id"]!!.jsonPrimitive.content
            track(s.config,1);s.scan()
            val arrived=s.api("/me/wishes/$id",token);assertEquals("fulfilled",arrived["state"]!!.jsonPrimitive.content)
            val local=arrived["libraryItem"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val members=s.api("/me/library?types=track",token)["items"]!!.jsonArray
            assertEquals(1,members.size);assertEquals(local,members.single().jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertTrue(s.api("/me/library",otherToken)["items"]!!.jsonArray.isEmpty())
            val changes=s.api("/sync/changes?types=wish&cursor=$before",token)["changes"]!!.jsonArray
            assertEquals(id,changes.single().jsonObject["id"]!!.jsonPrimitive.content);assertEquals("fulfilled",changes.single().jsonObject["resource"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertTrue(s.api("/sync/changes?types=wish&cursor=$otherBefore",otherToken)["changes"]!!.jsonArray.isEmpty())
            s.ctx.apiWebhooks.maintenance()
            val deliveries=f.requests.filter{it.path=="/events"};val requested=deliveries.filter{Json.parseToJsonElement(it.body.decodeToString()).jsonObject["type"]==JsonPrimitive("wishlist.requested")}
            assertEquals(1,requested.size);assertTrue(deliveries.any{Json.parseToJsonElement(it.body.decodeToString()).jsonObject["data"]!!.jsonObject["wish"]?.jsonObject?.get("state")==JsonPrimitive("fulfilled")})
            val r=requested.single();val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(Base64.getDecoder().decode(adminHook["secret"]!!.jsonPrimitive.content.removePrefix("whsec_")),"HmacSHA256"))
            val signed="${r.headers["webhook-id"]}.${r.headers["webhook-timestamp"]}.".toByteArray()+r.body
            assertEquals("v1,"+Base64.getEncoder().encodeToString(mac.doFinal(signed)),r.headers["webhook-signature"])
        }}
    }
    @Test fun partialCachedOutageRejectedPairAndAlternativeArrivalSurviveRestart() {
        FixtureDiscoverUpstream().use { f ->val c=config(f);var user="";var token="";var id=""
            Live(c).use { s ->
                val owner=s.ctx.users.create("owner","password",false);user=owner.id;token=s.token(owner)
                id=s.api("/me/wishes",token,"POST",addBody(DiscoverKind.Album),201)["wish"]!!.jsonObject["id"]!!.jsonPrimitive.content
                editions(f,12);(1..8).forEach{track(c,it)};s.scan()
                val partial=s.api("/me/wishes/$id",token);val a=partial["availability"]!!.jsonObject
                assertEquals("wanted",partial["state"]!!.jsonPrimitive.content);assertEquals(8,a["matchedTrackCount"]!!.jsonPrimitive.int);assertEquals(12,a["totalTrackCount"]!!.jsonPrimitive.int)
                s.ctx.db.write{execute("UPDATE discover_cache SET fetched=fetched-?",2*DiscoverCache.DAY)}
                f.json("/ws/2/release","{}",503);(9..12).forEach{track(c,it)};s.scan()
                val arrived=s.api("/me/wishes/$id",token);assertEquals("fulfilled",arrived["state"]!!.jsonPrimitive.content)
                val local=arrived["libraryItem"]!!.jsonObject
                val rejected=s.api("/me/wishes/$id/reject-match",token,"POST",j("operationId" to op(),"libraryItem" to local))
                assertEquals("wanted",rejected["wish"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            }
            val count=f.requests.size
            Live(c.copy(discover=c.discover.copy(enabled=false))).use { s ->
                (1..12).forEach{track(c,it,"Alternative")};s.scan()
                val arrived=s.api("/me/wishes/$id",token);assertEquals("fulfilled",arrived["state"]!!.jsonPrimitive.content)
                assertEquals(1,s.ctx.wishlist.list(user).size);assertEquals(count,f.requests.size)
                assertEquals(2,s.ctx.db.read{long("SELECT count(*) FROM library_item")})
            }
        }
    }
    @Test fun wishlistDisabledRetainsRowsAndResumesPendingScan() {
        FixtureDiscoverUpstream().use { f ->val c=config(f).let{it.copy(discover=it.discover.copy(enabled=false))};var token="";var id=""
            Live(c).use{s ->val u=s.ctx.users.create("owner","password",false);token=s.token(u);id=s.api("/me/wishes",token,"POST",addBody(DiscoverKind.Song),201)["wish"]!!.jsonObject["id"]!!.jsonPrimitive.content}
            Live(c.copy(discover=c.discover.copy(wishlistEnabled=false))).use{s ->track(c,1);s.scan();assertEquals(404,s.request("GET","/dulcet/v1/me/wishes/$id",token).statusCode());assertEquals("wanted",s.ctx.db.read{string("SELECT state FROM wishlist_wish WHERE id=?",id)});assertEquals(1,s.ctx.db.read{long("SELECT count(*) FROM wishlist_scan_pending")})}
            Live(c).use{s ->runBlocking{s.ctx.wishFulfilment.drain()};assertEquals("fulfilled",s.api("/me/wishes/$id",token)["state"]!!.jsonPrimitive.content);assertEquals(1,s.ctx.db.read{long("SELECT count(*) FROM library_item")})}
            assertTrue(f.requests.isEmpty())
        }
    }
    @Test fun futureActionTimeClampDoesNotChangeRetryFingerprint() {
        FixtureDiscoverUpstream().use{f ->Live(config(f).let{it.copy(discover=it.discover.copy(enabled=false))}).use{s ->
            val token=s.token(s.ctx.users.create("owner","password",false));val body=addBody(DiscoverKind.Song);val future="2099-01-01T00:00:00Z"
            val first=s.request("POST","/dulcet/v1/me/wishes",token,body,future);assertEquals(201,first.statusCode(),first.body())
            val repeated=s.request("POST","/dulcet/v1/me/wishes",token,body,future);assertEquals(200,repeated.statusCode(),repeated.body())
            val w=Json.parseToJsonElement(repeated.body()).jsonObject["wish"]!!.jsonObject
            assertTrue(java.time.Instant.parse(w["addedAt"]!!.jsonPrimitive.content).toEpochMilli()<=System.currentTimeMillis())
        }}
    }
}
