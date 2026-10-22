package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.v1.*
import com.legitimateapps.dulcet.server.wishlist.*
import kotlinx.serialization.json.*

fun wishlistHandlers(ctx:ServerContext):Map<String,Handler> {
    fun handler(reject:Boolean=false,block:suspend(Call)->Reply):Handler={c -> extensionCall {
        if(!ctx.config.discover.wishlistEnabled && !(reject && ctx.config.discover.enabled))throw SubsonicError.notFound("Wishlist")
        block(c)
    }}
    fun mutation(m:WishMutation)=extensionReply("dulcetWishMutation",mutationJson(m.copy(wish=m.wish?.let(ctx.wishlist::present))))
    return mapOf(
        "getDulcetWishlist" to handler {c ->
            val p=Principal(c.user,"",null,setOf("user:read"))
            val params=listOf("kind","state","includeFulfilledHistory","cursor").mapNotNull {key -> c.param(key)?.let {key to it}}.toMap()+mapOf("limit" to (c.param("size") ?: "20"))
            ctx.wishFulfilment.wake()
            val page=ctx.apiWishlist.page(p,false,params)
            val changed=ctx.db.read {string("SELECT value FROM meta WHERE key=?","wishlist_changed:${c.user.id}")?.toLongOrNull()}
            extensionReply("dulcetWishlist",JsonObject(page+j("changed" to changed?.let(::iso))),"wish")
        },
        "getDulcetWish" to handler {c -> extensionReply("dulcetWish",wishJson(ctx.wishlist.get(c.user.id,c.required("id")) ?: throw SubsonicError.notFound("Wish")))},
        "addToDulcetWishlist" to handler {c ->
            if(!ctx.config.discover.enabled)throw SubsonicError.notAuthorized()
            val kind=DiscoverKind.parse(c.required("kind"));val mbid=c.required("mbid")
            val year=c.param("year")?.let {it.toIntOrNull() ?: throw SubsonicError(0,"Invalid year","invalid-request")}
            mutation(ctx.wishlist.add(c.user.id,AddWish(kind,mapOf(kind.registryKey to mbid),WishSnapshot(c.required("title"),c.param("artist"),year,c.param("artworkId")),WishSource(c.required("sourceType"),c.param("sourceId")),c.required("operationId"),wishActionTime(c.param("actionTime")))))
        },
        "removeFromDulcetWishlist" to handler {c ->
            if(c.param("id")==null && c.param("addOperationId")==null)throw SubsonicError.missing("id or addOperationId")
            mutation(ctx.wishlist.remove(c.user.id,RemoveWish(c.param("id"),c.param("addOperationId"),c.required("operationId"),wishActionTime(c.param("actionTime")))))
        },
        "rejectDulcetDiscoverMatch" to handler(reject=true) {c ->
            val kind=DiscoverKind.parse(c.required("kind"));val type=c.required("libraryType").let {if(it=="song")"track" else it}
            mutation(ctx.wishlist.rejectMatch(c.user.id,ExternalTarget(kind,mapOf(kind.registryKey to c.required("mbid"))),LibraryRef(c.required("libraryId"),type),c.param("wishId"),c.required("operationId")))
        },
    )
}
