package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.*
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.wishlist.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class WishlistWebhooksTest {
    private class Receiver:WebhookTransport {
        data class Request(val url:String,val body:ByteArray,val headers:Map<String,String>)
        val requests=mutableListOf<Request>();var status=204
        override fun validate(url:String) {}
        override fun send(url:String,body:ByteArray,headers:Map<String,String>,permitted:()->Boolean):WebhookResult {
            if(!permitted())return WebhookResult(null,"grant-revoked")
            requests+=Request(url,body,headers);return WebhookResult(status)
        }
    }
    private class F:AutoCloseable {
        val ctx=Fixtures.context(Files.createTempDirectory("wishlist-webhooks"));val receiver=Receiver();var time=System.currentTimeMillis()
        val hooks=ApiWebhooks(ctx,clock={time},transport=receiver)
        val owner=ctx.users.create("owner","password",false);val other=ctx.users.create("other","password",false);val admin=ctx.users.create("admin","password",true)
        fun p(user:com.legitimateapps.dulcet.server.auth.User,scopes:Set<String>)=ctx.apiAuth.authenticate("Bearer "+ctx.apiAuth.bootstrapPersonal(user,scopes))!!
        fun hook(p:Principal,event:String="*")=hooks.create(p,j("url" to "https://receiver.example/${p.user.id}","events" to listOf(event)))
        fun input()=AddWish(DiscoverKind.Song,mapOf(DiscoverKind.Song.registryKey to "11111111-1111-4111-8111-111111111111"),WishSnapshot("Song","Artist"),WishSource("api"),UUID.randomUUID().toString())
        fun deliver(){time=System.currentTimeMillis()+1000;hooks.maintenance()}
        override fun close(){ctx.close();ctx.db.close()}
    }
    private fun Receiver.Request.obj()=Json.parseToJsonElement(body.decodeToString()).jsonObject
    @Test fun requestedCrossesOwnersOnlyToAdminAndChangedStaysOwn()=F().use { f ->
        val owner=f.p(f.owner,setOf("account","user:read"));val other=f.p(f.other,setOf("account","user:read"));val admin=f.p(f.admin,setOf("account","admin"))
        f.hook(owner);f.hook(other);f.hook(admin,"wishlist.requested")
        assertEquals(403,assertFailsWith<ApiProblem>{f.hook(owner,"wishlist.requested")}.status)
        val w=f.ctx.wishlist.add(f.owner.id,f.input()).wish!!;f.deliver()
        assertEquals(2,f.receiver.requests.size)
        val changed=f.receiver.requests.single {it.obj()["type"]==JsonPrimitive("wishlist.changed")};assertEquals(f.owner.id,changed.obj()["userId"]!!.jsonPrimitive.content)
        val requested=f.receiver.requests.single {it.obj()["type"]==JsonPrimitive("wishlist.requested")}
        assertEquals(f.admin.id,requested.obj()["userId"]!!.jsonPrimitive.content);assertEquals(f.owner.id,requested.obj()["data"]!!.jsonObject["wish"]!!.jsonObject["userId"]!!.jsonPrimitive.content)
        assertEquals(w.id,requested.obj()["data"]!!.jsonObject["wish"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }
    @Test fun dedupeStateAndRemovalNeverRequestAgainAndReaddDoes()=F().use { f ->
        val admin=f.p(f.admin,setOf("account","admin","user:read"));f.hook(admin);f.hook(f.p(f.owner,setOf("account","user:read")))
        val a=f.input();val w=f.ctx.wishlist.add(f.owner.id,a).wish!!;f.ctx.wishlist.add(f.owner.id,a);f.ctx.wishlist.add(f.owner.id,a.copy(operationId=UUID.randomUUID().toString()))
        f.ctx.wishlist.setState(admin,w.id,"searching","note",null)
        f.ctx.wishlist.remove(f.owner.id,RemoveWish(w.id,null,UUID.randomUUID().toString(),System.currentTimeMillis()+1));f.deliver()
        assertEquals(1,f.receiver.requests.count{it.obj()["type"]==JsonPrimitive("wishlist.requested")})
        val delete=f.receiver.requests.single{it.obj()["data"]!!.jsonObject["op"]==JsonPrimitive("delete")}.obj()["data"]!!.jsonObject
        assertFalse("wish" in delete);assertEquals(w.id,delete["id"]!!.jsonPrimitive.content)
        f.ctx.wishlist.add(f.owner.id,a.copy(operationId=UUID.randomUUID().toString(),actionTime=System.currentTimeMillis()+10));f.deliver()
        assertEquals(2,f.receiver.requests.count{it.obj()["type"]==JsonPrimitive("wishlist.requested")})
    }
    @Test fun rollbackRestartRetryAndStandardSignaturePreserveExactBytes()=F().use { f ->
        val p=f.p(f.admin,setOf("account","admin"));val hook=f.hook(p,"wishlist.requested");val a=f.input()
        assertFailsWith<IllegalStateException>{f.ctx.db.write{f.ctx.wishlist.add(f.owner.id,a);error("rollback")}}
        assertEquals(0,f.ctx.db.read{long("SELECT count(*) FROM api_webhook_delivery")})
        f.ctx.wishlist.add(f.owner.id,a);f.receiver.status=500;f.deliver();val first=f.receiver.requests.single()
        val secret=hook["secret"]!!.jsonPrimitive.content.removePrefix("whsec_")
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(Base64.getDecoder().decode(secret),"HmacSHA256"))
        val signed="${first.headers["webhook-id"]}.${first.headers["webhook-timestamp"]}.".toByteArray()+first.body
        assertEquals("v1,"+Base64.getEncoder().encodeToString(mac.doFinal(signed)),first.headers["webhook-signature"])
        f.time+=60_000;ApiWebhooks(f.ctx,clock={f.time},transport=f.receiver).maintenance()
        assertEquals(2,f.receiver.requests.size);assertContentEquals(first.body,f.receiver.requests.last().body)
    }
    @Test fun scopeLossAndRevocationBeforeAttemptDropRequested()=F().use { f ->
        val p=f.p(f.admin,setOf("account","admin"));f.hook(p,"wishlist.requested");f.ctx.wishlist.add(f.owner.id,f.input())
        f.ctx.db.write{execute("UPDATE user SET is_admin=0 WHERE id=?",f.admin.id)};f.deliver();assertTrue(f.receiver.requests.isEmpty())
        f.ctx.db.write{execute("UPDATE api_grant SET revoked=1 WHERE id=?",p.tokenId)};f.deliver();assertTrue(f.receiver.requests.isEmpty())
        assertEquals(0,f.ctx.db.read{long("SELECT count(*) FROM api_webhook_delivery")})
    }
}
