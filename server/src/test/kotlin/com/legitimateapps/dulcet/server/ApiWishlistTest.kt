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

class ApiWishlistTest {
    private val mbid="11111111-1111-4111-8111-111111111111"
    private fun op()=UUID.randomUUID().toString()
    private fun input(operation:String=op())=j("kind" to "album","externalIds" to j("musicbrainzReleaseGroupId" to mbid),"snapshot" to j("title" to "A & <B>","artist" to "Artist"),"source" to j("type" to "api"),"operationId" to operation)
    @Test fun offlineSnapshotAddRemovalAndCachedReplayStayTerminal() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("api-wishes"))
        try {
            val u=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(u,setOf("user:read","user:write","user:delete"));application {dulcetModule(ctx)}
            val body=input();val key=op()
            suspend fun add()=client.post("/dulcet/v1/me/wishes") {bearerAuth(token);header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(body.toString())}
            val first=add();assertEquals(201,first.status.value)
            val wish=Json.parseToJsonElement(first.bodyAsText()).jsonObject["wish"]!!.jsonObject;val id=wish["id"]!!.jsonPrimitive.content
            assertEquals("A & <B>",wish["snapshot"]!!.jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(204,client.delete("/dulcet/v1/me/wishes/$id?operationId=${op()}"){bearerAuth(token)}.status.value)
            for(expired in listOf(false,true)) {
                if(expired)ctx.db.write {execute("UPDATE api_idempotency SET at=0")}
                val retry=add();assertEquals(200,retry.status.value)
                val value=Json.parseToJsonElement(retry.bodyAsText()).jsonObject
                assertEquals(JsonNull,value["wish"]);assertTrue(value["removed"]!!.jsonPrimitive.boolean);assertFalse(value["applied"]!!.jsonPrimitive.boolean)
            }
            assertEquals(410,client.get("/dulcet/v1/me/wishes/$id"){bearerAuth(token)}.status.value)
            assertEquals(0,ctx.db.read {long("SELECT count(*) FROM wishlist_wish")})
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun everyOperationChecksLiveScopeAndOwnership() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("api-wish-scopes"))
        try {
            val u=ctx.users.create("user","password",false);val v=ctx.users.create("other","password",false);val admin=ctx.users.create("admin","password",true)
            val write=ctx.apiAuth.bootstrapPersonal(u,setOf("user:write"));val read=ctx.apiAuth.bootstrapPersonal(u,setOf("user:read"));val other=ctx.apiAuth.bootstrapPersonal(v,setOf("user:read","user:delete"));val down=ctx.apiAuth.bootstrapPersonal(admin,setOf("user:read"));val full=ctx.apiAuth.bootstrapPersonal(admin,setOf("admin"))
            application {dulcetModule(ctx)}
            val created=client.post("/dulcet/v1/me/wishes"){bearerAuth(write);contentType(ContentType.Application.Json);setBody(input().toString())}
            assertEquals(201,created.status.value);val id=Json.parseToJsonElement(created.bodyAsText()).jsonObject["wish"]!!.jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(401,client.get("/dulcet/v1/me/wishes").status.value)
            assertEquals(403,client.get("/dulcet/v1/me/wishes"){bearerAuth(write)}.status.value)
            assertEquals(404,client.get("/dulcet/v1/me/wishes/$id"){bearerAuth(other)}.status.value)
            assertEquals(404,client.delete("/dulcet/v1/me/wishes/$id?operationId=${op()}"){bearerAuth(other)}.status.value)
            assertEquals(403,client.get("/dulcet/v1/admin/wishes"){bearerAuth(down)}.status.value)
            assertEquals(200,client.get("/dulcet/v1/admin/wishes"){bearerAuth(full)}.status.value)
            assertEquals(422,client.patch("/dulcet/v1/admin/wishes/$id"){bearerAuth(full);contentType(ContentType.Application.Json);setBody(j("state" to "fulfilled").toString())}.status.value)
            val update=client.patch("/dulcet/v1/admin/wishes/$id"){bearerAuth(full);contentType(ContentType.Application.Json);setBody(j("state" to "requested","note" to "Look & listen").toString())}
            assertEquals(200,update.status.value)
            assertEquals(403,client.post("/dulcet/v1/me/wishes/cancellations"){bearerAuth(write);contentType(ContentType.Application.Json);setBody(j("addOperationId" to op(),"operationId" to op()).toString())}.status.value)
            for(path in listOf("/me/wishes/$id/reject-match","/discover/rejected-matches"))assertEquals(403,client.post("/dulcet/v1$path"){bearerAuth(read);contentType(ContentType.Application.Json);setBody("{}")}.status.value)
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun cancelBeforeAddAndIdempotencyNamespaceConflicts() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("api-cancel"))
        try {
            val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:write","user:delete"));application {dulcetModule(ctx)}
            val add=op();val key=op()
            assertEquals(200,client.post("/dulcet/v1/me/wishes/cancellations"){bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("operationId" to op(),"addOperationId" to add).toString())}.status.value)
            val body=input(add)
            val cancelled=client.post("/dulcet/v1/me/wishes"){bearerAuth(token);header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(body.toString())}
            assertEquals(200,cancelled.status.value);assertEquals(JsonNull,Json.parseToJsonElement(cancelled.bodyAsText()).jsonObject["wish"])
            assertEquals(422,client.post("/dulcet/v1/me/wishes"){bearerAuth(token);header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(input().toString())}.status.value)
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun stateOnlyPatchPreservesNoteAndTimestampWhileExplicitNullClearsBoth() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("wish-note-patch"))
        try {
            val user=ctx.users.create("user","password",false);val admin=ctx.users.create("admin","password",true)
            val token=ctx.apiAuth.bootstrapPersonal(admin,setOf("admin"))
            val wish=ctx.wishlist.add(user.id,addWishInput(input())).wish!!
            application {dulcetModule(ctx)}
            suspend fun patch(body:JsonObject):JsonObject {
                val response=client.patch("/dulcet/v1/admin/wishes/${wish.id}") {bearerAuth(token);contentType(ContentType.Application.Json);setBody(body.toString())}
                assertEquals(200,response.status.value,response.bodyAsText())
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            val set=patch(j("state" to "requested","note" to "Check the edition"))
            assertEquals("Check the edition",set["note"]!!.jsonPrimitive.content);assertNotEquals(JsonNull,set["noteAt"])
            val kept=patch(j("state" to "searching"))
            assertEquals(set["note"],kept["note"],"Omitted note preserves its value")
            assertEquals(set["noteAt"],kept["noteAt"],"State changes must not refresh noteAt")
            val cleared=patch(j("state" to "unavailable","note" to JsonNull))
            assertEquals(JsonNull,cleared["note"]);assertEquals(JsonNull,cleared["noteAt"])
            val reset=patch(j("state" to "requested","note" to "New note"))
            assertEquals("New note",reset["note"]!!.jsonPrimitive.content);assertNotEquals(JsonNull,reset["noteAt"])
            val noOp=patch(j("state" to "requested"))
            assertEquals(reset["revision"],noOp["revision"]);assertEquals(reset["noteAt"],noOp["noteAt"])
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun historyRemainsAddressableAndVisibilityChangesInvalidateEtag() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("wish-history"))
        try {
            val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:read","user:write"))
            Fixtures.track(ctx.config.musicFolder.resolve("one.flac"),tags=mapOf("title" to "One","album" to "Album","artist" to "Artist"));ctx.scanner.scan()
            application {dulcetModule(ctx)}
            val first=client.post("/dulcet/v1/me/wishes"){bearerAuth(token);contentType(ContentType.Application.Json);setBody(input().toString())}
            val id=Json.parseToJsonElement(first.bodyAsText()).jsonObject["wish"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val track=ctx.db.read {string("SELECT id FROM track")!!}
            ctx.db.write {execute("UPDATE wishlist_wish SET state='fulfilled',fulfilled=1,library_id=?,library_type='track' WHERE id=?",track,id)}
            suspend fun get(etag:String?=null)=client.get("/dulcet/v1/me/wishes/$id"){bearerAuth(token);etag?.let {header(HttpHeaders.IfNoneMatch,it)}}
            val visible=get();assertTrue(visible.bodyAsText().contains(track));val etag=visible.headers[HttpHeaders.ETag]!!
            assertEquals(304,get(etag).status.value)
            assertTrue(Json.parseToJsonElement(client.get("/dulcet/v1/me/wishes"){bearerAuth(token)}.bodyAsText()).jsonObject["items"]!!.jsonArray.isEmpty())
            assertEquals(1,Json.parseToJsonElement(client.get("/dulcet/v1/me/wishes?state=fulfilled"){bearerAuth(token)}.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
            ctx.apiUsers.update(user.id,j("sourceIds" to emptyList<String>()))
            val hidden=get(etag);assertEquals(200,hidden.status.value);assertFalse(hidden.bodyAsText().contains(track));assertTrue(hidden.bodyAsText().contains("fulfilled"))
        } finally {ctx.close();ctx.db.close()}
    }

}
