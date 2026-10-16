package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class ApiIntegrationTest {
    private suspend fun HttpResponse.obj()=Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun fixture():ServerContext {
        val ctx=Fixtures.context(Files.createTempDirectory("api-integration"))
        listOf("Alpha","Beta","Gamma","Delta").forEachIndexed { i,title -> Fixtures.track(ctx.config.musicFolder.resolve("$i.flac"),tags=mapOf("title" to title,"album" to "Album $title","artist" to "Artist $title","genre" to "Jazz","track" to "1")) }
        ctx.scanner.scan();return ctx
    }
    @Test fun `API library writes advance the legacy clock and both protocols see the same members`() = testApplication {
        val ctx=fixture()
        val user=ctx.users.create("library-reader","password",false)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read","user:read","user:write","user:delete"))
        val track=ctx.db.read { string("SELECT id FROM track ORDER BY id LIMIT 1")!! }
        application { dulcetModule(ctx) }
        suspend fun library()=client.rest("getDulcetLibrary","library-reader","password")["dulcetLibrary"]!!.jsonObject
        try {
            // Seed the clock through /rest so a missing invalidation cannot hide behind an absent clock.
            client.rest("addToDulcetLibrary","library-reader","password","id" to track)
            val before=library()["changed"]
            assertNotNull(before)
            assertEquals(HttpStatusCode.NoContent,client.delete("/dulcet/v1/me/library/$track") { bearerAuth(token) }.status)
            val removed=library()
            assertNotEquals(before,removed["changed"])
            assertEquals(0,removed["songCount"]!!.jsonPrimitive.int)
            assertEquals(HttpStatusCode.OK,client.put("/dulcet/v1/me/library/$track") { bearerAuth(token) }.status)
            val added=library()
            assertNotEquals(removed["changed"],added["changed"])
            assertEquals(1,added["songCount"]!!.jsonPrimitive.int)
            assertEquals(track,added["song"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            client.rest("removeFromDulcetLibrary","library-reader","password","id" to track)
            val api=client.get("/dulcet/v1/me/library") { bearerAuth(token) }.obj()
            assertTrue(api["items"]!!.jsonArray.isEmpty())
        } finally { ctx.db.close() }
    }
    @Test fun `legacy incremental library reads include old offline additions after an equal count replacement`() = testApplication {
        val ctx=fixture()
        val user=ctx.users.create("offline-reader","password",false)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read","user:read","user:write","user:delete"))
        val ids=ctx.db.read { query("SELECT id FROM track ORDER BY id") { it.string("id") } }
        application { dulcetModule(ctx) }
        suspend fun add(id:String,at:String) {
            assertEquals(HttpStatusCode.OK,client.put("/dulcet/v1/me/library/$id") { bearerAuth(token);header("Dulcet-Action-Time",at) }.status)
        }
        suspend fun library(vararg params:Pair<String,String>)=client.rest("getDulcetLibrary","offline-reader","password",*params)["dulcetLibrary"]!!.jsonObject
        try {
            add(ids[0],"2020-01-01T00:00:00Z")
            add(ids[2],"2020-01-03T00:00:00Z")
            // The logical library clock can lead wall time after a clock adjustment or rapid writes.
            ctx.db.write { execute("UPDATE meta SET value=? WHERE key=?",(System.currentTimeMillis()+120000).toString(),"library_changed:${user.id}") }
            val held=library()
            assertEquals(2,held["songCount"]!!.jsonPrimitive.int)
            val since=java.time.Instant.parse(held["changed"]!!.jsonPrimitive.content).toEpochMilli()-60000
            assertEquals(HttpStatusCode.NoContent,client.delete("/dulcet/v1/me/library/${ids[0]}") { bearerAuth(token) }.status)
            add(ids[1],"2020-01-02T00:00:00Z")
            // The probe sees only the already-held newest member; the delta must reveal its replacement.
            val probe=library("size" to "1")
            assertEquals(ids[2],probe["song"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(2,probe["songCount"]!!.jsonPrimitive.int)
            // Receipt evidence must survive ordinary journal retention.
            ctx.db.write { execute("DELETE FROM api_journal") }
            val delta=library("since" to since.toString())
            val listed=delta["song"]?.jsonArray.orEmpty().map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
            assertTrue(ids[1] in listed,"The legacy delta must include the replayed old addition")
            assertTrue(ids[0] !in listed)
            val api=client.get("/dulcet/v1/me/library") { bearerAuth(token) }.obj()
            val replayed=api["items"]!!.jsonArray.single { it.jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content==ids[1] }.jsonObject
            assertEquals("2020-01-02T00:00:00.000Z",replayed["libraryAddedAt"]!!.jsonPrimitive.content)
        } finally { ctx.db.close() }
    }
    @Test fun `catalog canonical path state ETags keyset deletes and sync cursor agree`() = testApplication {
        val ctx=fixture();val user=ctx.users.create("reader","password",false)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read","user:read","user:write","media:read"))
        application { dulcetModule(ctx) }
        suspend fun get(path:String)=client.get("/dulcet/v1$path") { bearerAuth(token) }
        val discovery=client.get("/.well-known/dulcet").obj()
        assertEquals("dulcet",discovery["standard"]!!.jsonPrimitive.content)
        assertTrue(discovery["apis"]!!.jsonArray[0].jsonObject["baseUrl"]!!.jsonPrimitive.content.endsWith("/dulcet/v1"))
        assertEquals(ctx.journal.instanceId,discovery["server"]!!.jsonObject["instanceId"]!!.jsonPrimitive.content)
        val first=get("/albums?limit=2&sort=title");assertEquals(HttpStatusCode.OK,first.status)
        val page=first.obj();val items=page["items"]!!.jsonArray
        assertEquals(2,items.size);assertNotNull(first.headers["Dulcet-Sync-Cursor"])
        val id=items[0].jsonObject["id"]!!.jsonPrimitive.content
        val before=get("/albums/$id");val beforeBody=before.obj();val tag=before.headers["ETag"]!!
        assertEquals(HttpStatusCode.NotModified,client.get("/dulcet/v1/albums/$id") { bearerAuth(token);header("If-None-Match",tag) }.status)
        assertEquals(HttpStatusCode.NotFound,client.get("/api/v1/albums/$id") { bearerAuth(token) }.status)
        val favorite=client.put("/dulcet/v1/me/favorites/$id") { bearerAuth(token) };assertEquals(HttpStatusCode.OK,favorite.status)
        val changed=get("/albums/$id")
        assertNotEquals(tag,changed.headers["ETag"])
        assertEquals(beforeBody["revision"],changed.obj()["revision"])
        assertEquals("private",changed.headers["Cache-Control"]);assertEquals("Authorization",changed.headers["Vary"])
        val legacy=client.rest("getAlbum","reader","password","id" to id)
        assertNotNull(legacy["album"]!!.jsonObject["starred"])
        // Remove an earlier item while walking; the cursor must continue by key, not offset.
        val removed=ctx.db.read { string("SELECT id FROM track WHERE album_id=?",id)!! }
        ctx.db.write { execute("DELETE FROM track WHERE id=?",removed);execute("DELETE FROM album WHERE id=?",id) }
        val next=page["nextCursor"]!!.jsonPrimitive.content
        val second=get("/albums?limit=2&sort=title&cursor=${java.net.URLEncoder.encode(next,Charsets.UTF_8)}").obj()
        val seen=(items+second["items"]!!.jsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(4,seen.toSet().size);assertEquals(JsonNull,second["nextCursor"])
        ctx.db.close()
    }
    @Test fun `snapshot covers during-walk state change and feeds isolate users and types`() = testApplication {
        val ctx=fixture();val a=ctx.users.create("a","password",false);val b=ctx.users.create("b","password",false)
        val ta=ctx.apiAuth.bootstrapPersonal(a,setOf("catalog:read","user:read","user:write"));val tb=ctx.apiAuth.bootstrapPersonal(b,setOf("catalog:read","user:read","user:write"))
        application { dulcetModule(ctx) }
        suspend fun feed(path:String,token:String=ta)=client.get("/dulcet/v1/sync/changes?$path") { bearerAuth(token) }
        val first=feed("start=snapshot&limit=2").obj()
        assertEquals("snapshot",first["phase"]!!.jsonPrimitive.content)
        val track=ctx.db.read { string("SELECT id FROM track ORDER BY id LIMIT 1")!! }
        assertEquals(HttpStatusCode.OK,client.put("/dulcet/v1/me/favorites/$track") { bearerAuth(ta) }.status)
        assertEquals(HttpStatusCode.OK,client.put("/dulcet/v1/me/favorites/$track") { bearerAuth(tb) }.status)
        var page=first;val changes=mutableListOf<JsonObject>();var rounds=0
        while(true) {
            changes+=page["changes"]!!.jsonArray.map { it.jsonObject }
            if(!page["hasMore"]!!.jsonPrimitive.boolean) break
            assertTrue(rounds++<100)
            page=feed("limit=2&cursor=${java.net.URLEncoder.encode(page["nextCursor"]!!.jsonPrimitive.content,Charsets.UTF_8)}").obj()
        }
        val tracks=changes.filter { it["type"]!!.jsonPrimitive.content=="track" }
        assertEquals(4,tracks.map { it["id"]!!.jsonPrimitive.content }.toSet().size)
        assertTrue(tracks.all { "userState" !in it["resource"]!!.jsonObject })
        assertTrue(changes.any { it["type"]!!.jsonPrimitive.content=="itemState" && it["id"]!!.jsonPrimitive.content==track && it["resource"]!!.jsonObject["favoritedAt"]!=JsonNull })
        val now=feed("start=now&types=track").obj()
        assertTrue(now["changes"]!!.jsonArray.isEmpty())
        val cursor=now["nextCursor"]!!.jsonPrimitive.content
        val wrong=feed("types=album&cursor=${java.net.URLEncoder.encode(cursor,Charsets.UTF_8)}")
        assertEquals(HttpStatusCode.BadRequest,wrong.status);assertTrue(wrong.obj()["type"]!!.jsonPrimitive.content.endsWith("cursor-types-mismatch"))
        val revoked=client.get("/dulcet/v1/sync/changes?types=itemState") { bearerAuth(ctx.apiAuth.bootstrapPersonal(a,setOf("catalog:read"))) }
        assertEquals(HttpStatusCode.Forbidden,revoked.status)
        assertEquals(HttpStatusCode.BadRequest,feed("start=now&limit=invalid").status)
        ctx.db.close()
    }
    @Test fun `signed original and artwork grants range integrity and revocation`() = testApplication {
        val ctx=fixture();val u=ctx.users.create("media","password",false)
        val token=ctx.apiAuth.bootstrapPersonal(u,setOf("catalog:read","media:read","account"));val p=ctx.apiAuth.authenticate("Bearer $token")!!
        application { dulcetModule(ctx) }
        val track=ctx.db.read { string("SELECT id FROM track ORDER BY id LIMIT 1")!! }
        val plan=client.post("/dulcet/v1/playback/decisions") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("trackId" to track,"profile" to j("directPlay" to listOf(j("container" to "flac","codec" to "flac")))).toString()) }
        assertEquals(HttpStatusCode.OK,plan.status);assertEquals("no-store",plan.headers["Cache-Control"])
        ctx.db.write { execute("INSERT INTO api_user_access(user_id,permissions) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET permissions=excluded.permissions",u.id,j("download" to false).toString()) }
        val denied=client.post("/dulcet/v1/playback/decisions") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("trackId" to track,"purpose" to "download","profile" to j("directPlay" to listOf(j("container" to "flac","codec" to "flac")))).toString()) }
        assertEquals(HttpStatusCode.Forbidden,denied.status)
        assertEquals(JsonPrimitive("urn:dulcet:problem:permission-denied"),denied.obj()["type"])
        val deniedOriginal=client.post("/dulcet/v1/media/urls") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("requests" to listOf(j("kind" to "original","id" to track))).toString()) }
        assertEquals(HttpStatusCode.Forbidden,deniedOriginal.status)
        assertEquals(JsonPrimitive("urn:dulcet:problem:permission-denied"),deniedOriginal.obj()["type"])
        val stillStream=client.post("/dulcet/v1/playback/decisions") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("trackId" to track,"purpose" to "stream","profile" to j("directPlay" to listOf(j("container" to "flac","codec" to "flac")))).toString()) }
        assertEquals(HttpStatusCode.OK,stillStream.status)
        val incompatible=client.post("/dulcet/v1/playback/decisions") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("trackId" to track,"profile" to j("directPlay" to listOf(j("container" to "mp3","codec" to "mp3")))).toString()) }
        assertEquals(HttpStatusCode.UnprocessableEntity,incompatible.status)
        assertEquals(JsonPrimitive("urn:dulcet:problem:no-compatible-format"),incompatible.obj()["type"])
        val url=plan.obj()["url"]!!.jsonPrimitive.content;val path=url.substringAfter("localhost")
        val full=client.get(path);assertEquals(HttpStatusCode.OK,full.status)
        val file=ctx.db.read { string("SELECT path FROM track WHERE id=?",track)!! }
        assertContentEquals(Files.readAllBytes(ctx.config.musicFolder.resolve(file)),full.readRawBytes())
        val head=client.head(path);assertEquals(HttpStatusCode.OK,head.status);assertEquals(Files.size(ctx.config.musicFolder.resolve(file)).toString(),head.headers["Content-Length"]);assertTrue(head.readRawBytes().isEmpty())
        val range=client.get(path) { header("Range","bytes=0-15") };assertEquals(HttpStatusCode.PartialContent,range.status);assertEquals(16,range.readRawBytes().size)
        val unsatisfiable=client.get(path) { header("Range","bytes=99999999-") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable,unsatisfiable.status)
        assertEquals("bytes */${Files.size(ctx.config.musicFolder.resolve(file))}",unsatisfiable.headers[HttpHeaders.ContentRange])
        assertEquals(JsonPrimitive("urn:dulcet:problem:range-not-satisfiable"),unsatisfiable.obj()["type"])
        assertEquals(HttpStatusCode.Forbidden,client.get(path.dropLast(3)+"xyz").status)
        val read=client.get("/dulcet/v1/tracks/$track") { bearerAuth(token) }.obj()
        val art=read["artwork"]!!.jsonObject["id"]!!.jsonPrimitive.content
        suspend fun artwork()=client.post("/dulcet/v1/media/urls") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("requests" to listOf(j("kind" to "artwork","id" to art,"size" to 200))).toString()) }.obj()["urls"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content
        val a1=artwork();assertEquals(a1,artwork())
        assertEquals(HttpStatusCode.BadRequest,client.get("/dulcet/v1/artwork/$art?size=invalid") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest,client.post("/dulcet/v1/playback/decisions") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("trackId" to track,"startMs" to -1,"profile" to j("directPlay" to emptyList<JsonObject>())).toString()) }.status)
        val image=client.get(a1.substringAfter("localhost"));assertTrue(image.headers["Content-Type"]!!.startsWith("image/"));assertTrue(image.headers["Cache-Control"]!!.contains("immutable"))
        val imageRange=client.get(a1.substringAfter("localhost")) { header("Range","bytes=0-7") };assertEquals(HttpStatusCode.PartialContent,imageRange.status);assertEquals(8,imageRange.readRawBytes().size)
        assertEquals(0,ctx.db.read { long("SELECT COALESCE(SUM(play_count),0) FROM annotation WHERE user_id=?",u.id) })
        assertEquals(HttpStatusCode.NoContent,client.delete("/dulcet/v1/me/tokens/${p.tokenId}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Forbidden,client.get(path).status)
        assertEquals(HttpStatusCode.Forbidden,client.get(a1.substringAfter("localhost")).status)
        ctx.db.close()
    }
    @Test fun `idempotency scope errors unknown fields CORS and limiter operate over HTTP`() = testApplication {
        val ctx=fixture();val u=ctx.users.create("http","password",false)
        val token=ctx.apiAuth.bootstrapPersonal(u,setOf("user:read","user:write"))
        application { dulcetModule(ctx) }
        val preflight=client.options("/dulcet/v1/playlists") { header("Origin","https://player.example");header("Access-Control-Request-Method","POST");header("Access-Control-Request-Headers","Authorization, Dulcet-Action-Time") }
        assertEquals(HttpStatusCode.NoContent,preflight.status);assertEquals("*",preflight.headers["Access-Control-Allow-Origin"])
        assertTrue(preflight.headers["Access-Control-Allow-Headers"]!!.contains("Dulcet-Action-Time"))
        val key=UUID.randomUUID().toString()
        suspend fun create(name:String)=client.post("/dulcet/v1/playlists?future=ignored") { bearerAuth(token);header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(j("name" to name,"futureField" to true).toString()) }
        val first=create("Created once");assertEquals(HttpStatusCode.Created,first.status)
        val retry=create("Created once");assertEquals(first.bodyAsText(),retry.bodyAsText())
        assertEquals(1,ctx.db.read { long("SELECT COUNT(*) FROM playlist WHERE owner_id=?",u.id) })
        val otherGrant=ctx.apiAuth.bootstrapPersonal(u,setOf("user:read","user:write"))
        assertEquals(HttpStatusCode.UnprocessableEntity,client.post("/dulcet/v1/playlists") { bearerAuth(otherGrant);header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(j("name" to "Created once","futureField" to true).toString()) }.status)
        assertEquals(HttpStatusCode.UnprocessableEntity,create("Different").status)
        val forbidden=client.get("/dulcet/v1/tracks") { bearerAuth(token) };assertEquals(HttpStatusCode.Forbidden,forbidden.status);assertTrue(forbidden.headers["Content-Type"]!!.startsWith("application/problem+json"))
        assertEquals("catalog:read",forbidden.obj()["requiredScopes"]!!.jsonArray[0].jsonPrimitive.content)
        val queryToken=client.get("/dulcet/v1/me?access_token=$token")
        assertEquals(HttpStatusCode.Unauthorized,queryToken.status)
        assertEquals("urn:dulcet:problem:unauthenticated",queryToken.obj()["type"]!!.jsonPrimitive.content)
        assertTrue(queryToken.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer"))
        assertFalse(queryToken.bodyAsText().contains(token))
        assertEquals(HttpStatusCode.Unauthorized,client.get("/dulcet/v1/capabilities?token=canary").status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/dulcet/v1/capabilities?future=$token").status)
        assertEquals(HttpStatusCode.BadRequest,client.get("/dulcet/v1/me?access_token=$token") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge,client.post("/dulcet/v1/playlists") { bearerAuth(token);contentType(ContentType.Application.Json);setBody("{\"name\":\""+"a".repeat(1024*1024)+"\"}") }.status)
        assertEquals(HttpStatusCode.OK,client.get("/dulcet/v1/openapi.yaml").status)
        assertEquals(HttpStatusCode.OK,client.get("/dulcet/v1/me/queue") { bearerAuth(token) }.status)
        ctx.dulcetApi.limiter.maxRequests=1
        val limited=client.get("/dulcet/v1/capabilities")
        assertEquals(HttpStatusCode.TooManyRequests,limited.status);assertNotNull(limited.headers["Retry-After"])
        ctx.db.close()
    }
    @Test fun `search types walk their own keyset and ignore future query fields`() = testApplication {
        val ctx=fixture();val u=ctx.users.create("search","password",false)
        val token=ctx.apiAuth.bootstrapPersonal(u,setOf("catalog:read","user:write"))
        val other=ctx.users.create("other","password",false)
        val now=System.currentTimeMillis()
        ctx.db.write {
            listOf("Delta","Alpha","Charlie","Bravo").forEach { name -> execute("INSERT INTO playlist(id,owner_id,name,created,changed) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),u.id,"Mix $name",now,now) }
            execute("INSERT INTO playlist(id,owner_id,name,public,created,changed) VALUES(?,?,?,?,?,?)","private-other",other.id,"Mix private",false,now,now)
            execute("INSERT INTO playlist(id,owner_id,name,public,created,changed) VALUES(?,?,?,?,?,?)","public-other",other.id,"Mix public",true,now,now)
        }
        application { dulcetModule(ctx) }
        suspend fun search(path:String)=client.get("/dulcet/v1/search?$path") { bearerAuth(token) }
        val first=search("q=Mix&limit=2&future=one").obj()["playlists"]!!.jsonObject
        assertEquals(2,first["items"]!!.jsonArray.size)
        val next=first["nextCursor"]!!.jsonPrimitive.content
        val second=search("q=Mix&types=playlist&limit=100&future=two&cursor=${java.net.URLEncoder.encode(next,Charsets.UTF_8)}").obj()["playlists"]!!.jsonObject
        val all=(first["items"]!!.jsonArray+second["items"]!!.jsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Mix Alpha","Mix Bravo","Mix Charlie","Mix Delta","Mix public"),all)
        assertEquals(JsonNull,second["nextCursor"])
        assertEquals(HttpStatusCode.BadRequest,search("q=Mix&limit=invalid").status)
        val album=ctx.db.read { string("SELECT album_id FROM track ORDER BY id LIMIT 1")!! }
        assertEquals(HttpStatusCode.OK,client.put("/dulcet/v1/me/library/$album") { bearerAuth(token) }.status)
        assertEquals(1,search("q=Album&types=album&scope=library").obj()["albums"]!!.jsonObject["items"]!!.jsonArray.size)
        assertEquals(0,search("q=Mix&types=playlist&scope=library").obj()["playlists"]!!.jsonObject["items"]!!.jsonArray.size)
        // Artist album appearances must respect roles independently of albumArtist credits.
        val artist=ctx.db.read { string("SELECT id FROM artist ORDER BY id LIMIT 1")!! }
        val track=ctx.db.read { string("SELECT id FROM track WHERE album_id=?",album)!! }
        ctx.db.write { execute("INSERT OR IGNORE INTO track_artist(track_id,artist_id,role,name,position) VALUES(?,?,'composer','Composer',0)",track,artist) }
        val composed=client.get("/dulcet/v1/artists/$artist/albums?role=composer") { bearerAuth(token) }.obj()
        assertEquals(listOf(album),composed["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        ctx.db.close()
    }
    @Test fun `scan adapter rejects missing admin scope and reports completed catalog changes`() = testApplication {
        val ctx=fixture();val admin=ctx.users.create("admin","password",true)
        val token=ctx.apiAuth.bootstrapPersonal(admin,setOf("admin","catalog:read","user:read"))
        val readOnly=ctx.apiAuth.bootstrapPersonal(admin,setOf("catalog:read"))
        application { dulcetModule(ctx) }
        val denied=client.post("/dulcet/v1/admin/scans") { bearerAuth(readOnly);contentType(ContentType.Application.Json);setBody("{}") }
        assertEquals(HttpStatusCode.Forbidden,denied.status)
        Fixtures.track(ctx.config.musicFolder.resolve("new.flac"),tags=mapOf("title" to "New", "album" to "New album", "artist" to "New artist"))
        val response=client.post("/dulcet/v1/admin/scans") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("kind" to "quick").toString()) }
        assertEquals(HttpStatusCode.Accepted,response.status)
        val id=response.obj()["id"]!!.jsonPrimitive.content
        var completed=false
        repeat(100) {
            if(!completed) {
                val state=client.get("/dulcet/v1/admin/scans/$id") { bearerAuth(token) }.obj()
                val status=state["state"]!!.jsonPrimitive.content
                assertNotEquals("failed",status)
                completed=status=="completed"
                if(!completed) kotlinx.coroutines.delay(20)
            }
        }
        assertTrue(completed);assertEquals(5,ctx.db.read { long("SELECT COUNT(*) FROM track") })
        ctx.db.close()
    }

}
