package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.v1.*
import com.legitimateapps.dulcet.server.wishlist.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class RestDiscoverWishlistTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private fun op()=UUID.randomUUID().toString()
    private fun context(f:FixtureDiscoverUpstream,d:Boolean=true,w:Boolean=true):ServerContext {
        val root=Files.createTempDirectory("rest-discover-wishes")
        return ServerContext(Config(port=0,bind="127.0.0.1",musicFolder=root.resolve("music").also(Files::createDirectories),dataFolder=root.resolve("data"),ffmpeg=Fixtures.ffmpeg,ffprobe=Fixtures.ffprobe,scanOnStart=false,lrclibUrl=null,discover=DiscoverSettings(enabled=d,wishlistEnabled=w,musicBrainzUrl=f.baseUrl,musicBrainzMinGapMillis=0,coverArtArchiveUrl=f.baseUrl,listenBrainzUrl=f.baseUrl,listenBrainzLabsUrl=f.baseUrl)),Db.inMemory())
    }
    private fun JsonObject.child(name:String)=this[name]!!.jsonObject
    private fun parse(s:String)=Json.parseToJsonElement(s).jsonObject.child("subsonic-response")
    @Test fun advertisementIndependenceAndDisabledMethodRefusal() {
        for((d,w) in listOf(false to false,false to true,true to false,true to true))testApplication {
            FixtureDiscoverUpstream().use { f -> val ctx=context(f,d,w)
                try {
                    ctx.users.create("user","password",false);application {dulcetModule(ctx)}
                    val extensions=parse(client.get("/rest/getOpenSubsonicExtensions?f=json").bodyAsText())["openSubsonicExtensions"]!!.jsonArray.map {it.jsonObject["name"]!!.jsonPrimitive.content}
                    assertEquals(d,"dulcetDiscover" in extensions);assertEquals(w,"dulcetWishlist" in extensions)
                    if(!d)assertEquals(70,parse(client.get("/rest/searchDulcetDiscover?u=user&p=password&v=1.16.1&c=test&f=json&kind=artist&query=a").bodyAsText()).child("error")["code"]!!.jsonPrimitive.int)
                    if(!w)assertEquals(70,parse(client.get("/rest/getDulcetWishlist?u=user&p=password&v=1.16.1&c=test&f=json").bodyAsText()).child("error")["code"]!!.jsonPrimitive.int)
                    assertTrue(f.requests.isEmpty())
                } finally {ctx.close();ctx.db.close()}
            }
        }
    }
    @Test fun jsonXmlAndJsonpProjectSameFieldsAndEmptyLists() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f)
            try {
                ctx.users.create("user","password",false);f.json("/ws/2/artist","""{"count":1,"artists":[{"id":"$id","name":"A & <B>"}]}""");application {dulcetModule(ctx)}
                suspend fun rest(method:String,extra:String="",format:String="json")=client.get("/rest/$method?u=user&p=password&v=1.16.1&c=test&f=$format&$extra")
                val response=rest("searchDulcetDiscover","kind=artist&query=a");assertEquals(200,response.status.value)
                val envelope=parse(response.bodyAsText());assertEquals("1.16.1",envelope["version"]!!.jsonPrimitive.content)
                val item=envelope.child("dulcetDiscover")["item"]!!.jsonArray.single().jsonObject
                assertEquals("A & <B>",item["title"]!!.jsonPrimitive.content);assertEquals(id,item.child("externalIds")["musicbrainzArtistId"]!!.jsonPrimitive.content)
                assertTrue(rest("searchDulcetDiscover","kind=artist&query=a","xml").bodyAsText().contains("A &amp; &lt;B&gt;"))
                assertTrue(rest("getDulcetWishlist","callback=cb","jsonp").bodyAsText().startsWith("cb("))
                assertEquals("[]",parse(rest("getDulcetWishlist").bodyAsText()).child("dulcetWishlist")["wish"].toString())
                val xml=rest("getDulcetWishlist",format="xml").bodyAsText();assertTrue(xml.contains("<dulcetWishlist"))
                assertEquals(1,f.requests.size);assertFalse(f.requests.single().headers.toString().contains("password"))
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun wishClockCoversApiAdminAndScanButNotDuplicateAdd() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f)
            try {
                val user=ctx.users.create("user","password",false);val admin=ctx.users.create("admin","password",true);val p=ctx.apiAuth.authenticate("Bearer "+ctx.apiAuth.bootstrapPersonal(admin,setOf("admin")))!!
                application {dulcetModule(ctx)}
                suspend fun clock()=parse(client.get("/rest/getDulcetWishlist?u=user&p=password&v=1.16.1&c=test&f=json&size=1").bodyAsText()).child("dulcetWishlist")["changed"]?.jsonPrimitive?.content
                assertNull(clock());val input=AddWish(DiscoverKind.Song,mapOf(DiscoverKind.Song.registryKey to id),WishSnapshot("Song","Artist"),WishSource("api"),op())
                val wish=ctx.wishlist.add(user.id,input).wish!!;val added=clock();assertNotNull(added)
                ctx.wishlist.add(user.id,input);assertEquals(added,clock())
                ctx.wishlist.setState(p,wish.id,"searching","<note>",null);val updated=clock();assertNotEquals(added,updated)
                Fixtures.track(ctx.config.musicFolder.resolve("one.flac"),tags=mapOf("title" to "Song","artist" to "Artist","album" to "Album","MUSICBRAINZ_TRACKID" to id));ctx.scanner.scan();ctx.wishFulfilment.drain()
                assertEquals("fulfilled",ctx.wishlist.get(user.id,wish.id)!!.state);assertNotEquals(updated,clock())
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun discoverOffKeepsWishlistReadRemoveAndApiAdd() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f,false)
            try {
                val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:write"));application {dulcetModule(ctx)}
                suspend fun rest(method:String,extra:String="")=parse(client.get("/rest/$method?u=user&p=password&v=1.16.1&c=test&f=json&$extra").bodyAsText())
                assertEquals(50,rest("addToDulcetWishlist","kind=song&mbid=$id&title=Song&sourceType=search&operationId=${op()}").child("error")["code"]!!.jsonPrimitive.int)
                val body=j("kind" to "song","externalIds" to mapOf(DiscoverKind.Song.registryKey to id),"snapshot" to j("title" to "Song"),"source" to j("type" to "api"),"operationId" to op())
                val added=client.post("/dulcet/v1/me/wishes"){bearerAuth(token);header("Content-Type","application/json");setBody(body.toString())};assertEquals(201,added.status.value)
                val wish=Json.parseToJsonElement(added.bodyAsText()).jsonObject.child("wish")["id"]!!.jsonPrimitive.content
                assertEquals("ok",rest("getDulcetWishlist")["status"]!!.jsonPrimitive.content)
                assertEquals("ok",rest("removeFromDulcetWishlist","id=$wish&operationId=${op()}")["status"]!!.jsonPrimitive.content);assertTrue(f.requests.isEmpty())
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun errorsStayEnvelopedWithStableReasons() = testApplication {
        FixtureDiscoverUpstream().use { f -> val ctx=context(f)
            try {
                ctx.users.create("user","password",false);f.respond("/ws/2/artist",status=503,body=byteArrayOf())
                application {dulcetModule(ctx)}
                suspend fun error(extra:String)=parse(client.get("/rest/searchDulcetDiscover?u=user&p=password&v=1.16.1&c=test&f=json&$extra").bodyAsText()).child("error")
                assertEquals(10,error("query=a")["code"]!!.jsonPrimitive.int)
                assertEquals(0,error("kind=artist&query=a&size=101")["code"]!!.jsonPrimitive.int)
                val unavailable=error("kind=artist&query=a");assertEquals(0,unavailable["code"]!!.jsonPrimitive.int);assertEquals("upstream-unavailable",unavailable["reason"]!!.jsonPrimitive.content);assertNotNull(unavailable["retryAfterSeconds"])
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun retryRemoveBeforeAddAndIntentionalReaddSurviveRestart() {
        FixtureDiscoverUpstream().use { f ->
            val template=context(f);val config=template.config;template.close();template.db.close()
            val path=config.dataFolder.resolve("persistent.db");val add=op();val remove=op();var userId=""
            testApplication {
                val ctx=ServerContext(config,Db.open(path));userId=ctx.users.create("user","password",false).id
                try {
                    application {dulcetModule(ctx)}
                    val result=client.post("/rest/removeFromDulcetWishlist") {header("Content-Type","application/x-www-form-urlencoded");setBody("u=user&p=password&v=1.16.1&c=test&f=json&addOperationId=$add&operationId=$remove")}
                    assertEquals("ok",parse(result.bodyAsText())["status"]!!.jsonPrimitive.content)
                } finally {ctx.close();ctx.db.close()}
            }
            testApplication {
                val ctx=ServerContext(config,Db.open(path))
                try {
                    application {dulcetModule(ctx)}
                    suspend fun request(operation:String)=parse(client.post("/rest/addToDulcetWishlist.view") {header("Content-Type","application/x-www-form-urlencoded");setBody("u=user&p=password&v=1.16.1&c=test&f=json&kind=song&mbid=$id&title=A%20%26%20%3CB%3E&sourceType=search&operationId=$operation")}.bodyAsText()).child("dulcetWishMutation")
                    val cancelled=request(add);assertTrue(cancelled["removed"]!!.jsonPrimitive.boolean);assertFalse("wish" in cancelled)
                    val fresh=op();val added=request(fresh).child("wish");assertEquals("A & <B>",added.child("snapshot")["title"]!!.jsonPrimitive.content)
                    assertEquals(added["id"],request(fresh).child("wish")["id"])
                    val removed=parse(client.get("/rest/removeFromDulcetWishlist?u=user&p=password&v=1.16.1&c=test&f=json&addOperationId=$add&operationId=$remove").bodyAsText());assertEquals("ok",removed["status"]!!.jsonPrimitive.content)
                    assertEquals(1,ctx.wishlist.list(userId).size);assertTrue(f.requests.isEmpty())
                } finally {ctx.close();ctx.db.close()}
            }
        }
    }

}
