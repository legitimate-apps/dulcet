package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.wishlist.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class WishlistSyncTest {
    private fun add(ctx:ServerContext,user:String)=ctx.wishlist.add(user,AddWish(DiscoverKind.Song,mapOf(DiscoverKind.Song.registryKey to "11111111-1111-4111-8111-111111111111"),WishSnapshot("Song","Artist"),WishSource("api"),UUID.randomUUID().toString())).wish!!
    @Test fun snapshotChangesDeletionAndIsolation() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("wish-sync"))
        try {
            val user=ctx.users.create("user","password",false);val other=ctx.users.create("other","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:read"));application {dulcetModule(ctx)}
            suspend fun page(cursor:String?=null,start:String?=null)=Json.parseToJsonElement(client.get("/dulcet/v1/sync/changes"){bearerAuth(token);parameter("types","wish");cursor?.let {parameter("cursor",it)};start?.let {parameter("start",it)}}.bodyAsText()).jsonObject
            val cursor=page(start="now")["nextCursor"]!!.jsonPrimitive.content
            add(ctx,other.id);val wish=add(ctx,user.id)
            val changed=page(cursor);val rows=changed["changes"]!!.jsonArray
            assertEquals(1,rows.size);assertEquals(wish.id,rows.single().jsonObject["id"]!!.jsonPrimitive.content)
            ctx.wishlist.remove(user.id,RemoveWish(wish.id,null,UUID.randomUUID().toString(),System.currentTimeMillis()+1))
            val deleted=page(changed["nextCursor"]!!.jsonPrimitive.content)["changes"]!!.jsonArray.single().jsonObject
            assertEquals("wish",deleted["type"]!!.jsonPrimitive.content);assertEquals("delete",deleted["op"]!!.jsonPrimitive.content);assertEquals("deleted",deleted["reason"]!!.jsonPrimitive.content);assertFalse("resource" in deleted)
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun preWishlistCursorWithoutTypesStillContinuesOriginalFilter() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("wish-old-cursor"))
        try {
            val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:read"));val old=ctx.journal.cursor(user.id,"itemState,play,playlist,queue")
            application {dulcetModule(ctx)}
            add(ctx,user.id)
            val response=client.get("/dulcet/v1/sync/changes"){bearerAuth(token);parameter("cursor",old)}
            assertEquals(200,response.status.value);assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject["changes"]!!.jsonArray.none {it.jsonObject["type"]!!.jsonPrimitive.content=="wish"})
            assertEquals(400,client.get("/dulcet/v1/sync/changes"){bearerAuth(token);parameter("cursor",old);parameter("types","wish")}.status.value)
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun disabledWishlistStillDeliversPreviouslyRecordedTombstones() = testApplication {
        val base=Fixtures.context(Files.createTempDirectory("wish-disabled-feed"));val config=base.config.copy(discover=base.config.discover.copy(wishlistEnabled=false));base.close();base.db.close()
        val ctx=ServerContext(config,com.legitimateapps.dulcet.server.db.Db.inMemory())
        try {
            val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:read"))
            // Simulate durable rows recorded before the switch was turned off.
            ctx.db.write {
                execute("INSERT INTO api_journal(type,id,user_id,op,at,reason) VALUES('wish','removed',?,'delete',?,'deleted')",user.id,System.currentTimeMillis())
            }
            application {dulcetModule(ctx)}
            val response=client.get("/dulcet/v1/sync/changes?types=wish&start=snapshot"){bearerAuth(token)}
            assertEquals(200,response.status.value)
            val cursor=ctx.journal.cursor(user.id,"wish",0)
            val changes=client.get("/dulcet/v1/sync/changes?types=wish&cursor=$cursor"){bearerAuth(token)}
            assertEquals(200,changes.status.value)
            assertEquals("delete",Json.parseToJsonElement(changes.bodyAsText()).jsonObject["changes"]!!.jsonArray.single().jsonObject["op"]!!.jsonPrimitive.content)
            assertEquals(404,client.get("/dulcet/v1/me/wishes"){bearerAuth(token)}.status.value)
        } finally {ctx.close();ctx.db.close()}
    }

}
