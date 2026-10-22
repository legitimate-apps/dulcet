package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.wishlist.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.time.Instant

fun wishJson(w:Wish):JsonObject = j("id" to w.id,"userId" to w.userId,"kind" to w.kind.wire,"externalIds" to w.externalIds,"snapshot" to JsonObject(j("title" to w.snapshot.title,"artist" to w.snapshot.artist,"year" to w.snapshot.year,"artworkId" to w.snapshot.artworkId).filterValues { it!=JsonNull }),"source" to JsonObject(j("type" to w.source.type,"id" to w.source.id).filterValues { it!=JsonNull }),"addedAt" to iso(w.addedAt),"state" to w.state,"updatedAt" to iso(w.updatedAt),"note" to w.note,"noteAt" to w.noteAt?.let(::iso),"fulfilledAt" to w.fulfilledAt?.let(::iso),"libraryItem" to w.libraryItem?.let { j("id" to it.id,"type" to it.type) },"availability" to availabilityJson(w.availability),"revision" to w.revision)
fun mutationJson(m:WishMutation)=j("applied" to m.applied,"wish" to m.wish?.let(::wishJson),"removed" to m.removed)
internal fun JsonObject.wishString(name:String,required:Boolean=false):String? {
    val value=get(name)
    if(value==null || value==JsonNull) { if(required)throw ApiProblem(400,"invalid-request","$name is required");return null }
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ApiProblem(400,"invalid-request","$name must be a string")
}
internal fun JsonObject.wishObject(name:String):JsonObject=get(name) as? JsonObject ?: throw ApiProblem(400,"invalid-request","$name must be an object")
internal fun wishIds(value:JsonObject):ExternalIds=value.mapValues { (_,v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ApiProblem(400,"invalid-request","External ids must be strings") }
fun wishActionTime(value:String?):Long?=value?.let { try { Instant.parse(it).toEpochMilli().coerceAtMost(System.currentTimeMillis()) } catch(_:Exception) { throw ApiProblem(400,"invalid-request","Invalid action time") } }
fun addWishInput(body:JsonObject,actionTime:Long?=null):AddWish {
    val snapshot=body.wishObject("snapshot");val source=body.wishObject("source")
    val year=snapshot["year"]?.takeIf { it!=JsonNull }?.let { value -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw ApiProblem(400,"invalid-request","year must be an integer") }
    return AddWish(DiscoverKind.parse(body.wishString("kind",true)!!),wishIds(body.wishObject("externalIds")),WishSnapshot(snapshot.wishString("title",true)!!,snapshot.wishString("artist"),year,snapshot.wishString("artworkId")),WishSource(source.wishString("type",true)!!,source.wishString("id")),body.wishString("operationId",true)!!,actionTime)
}
class ApiWishlist(private val ctx:ServerContext) {
    private val cursors=CursorCodec(ctx)
    private fun projectMutation(m:WishMutation)=mutationJson(m.copy(wish=m.wish?.let(ctx.wishlist::present)))
    fun requireEnabled(operation:String) {
        if(operation=="/discover/rejected-matches") {if(!ctx.config.discover.enabled && !ctx.config.discover.wishlistEnabled)throw ApiProblem(404,"not-found")}
        else if(!ctx.config.discover.wishlistEnabled)throw ApiProblem(404,"not-found")
    }
    fun resource(userId:String,id:String):JsonObject?=ctx.wishlist.get(userId,id)?.let(::wishJson)
    fun page(actor:Principal,allUsers:Boolean,params:Map<String,String>):JsonObject {
        requireEnabled("/me/wishes");requireScopes(actor,if(allUsers)"admin" else "user:read")
        if(allUsers && (!actor.user.isAdmin || !ctx.apiAuth.isActive(actor)))throw ApiProblem(403,"permission-denied")
        val kind=params["kind"]?.let(DiscoverKind::parse);val state=params["state"]
        if(state!=null && state !in setOf("wanted","requested","searching","unavailable","fulfilled"))throw ApiProblem(400,"invalid-request")
        val history=params["includeFulfilledHistory"]?.let { it.toBooleanStrictOrNull() ?: throw ApiProblem(400,"invalid-request") }==true || state=="fulfilled"
        val limit=params["limit"]?.let { it.toIntOrNull() ?: throw ApiProblem(400,"invalid-request") } ?: 20
        if(limit !in 1..100)throw ApiProblem(400,"invalid-request")
        val owner=if(allUsers)params["userId"] else actor.user.id
        val binding=j("allUsers" to allUsers,"userId" to owner,"kind" to kind?.wire,"state" to state,"history" to history).toString()
        val generation=ctx.db.read {long("SELECT coalesce(max(seq),0) FROM api_revision WHERE type='wish' AND (? OR user_id=?)",allUsers,actor.user.id)}
        val cursor=params["cursor"]?.let(cursors::decode)
        if(cursor!=null && (cursor["kind"]?.jsonPrimitive?.content!="wishes" || cursor["user"]?.jsonPrimitive?.content!=actor.user.id || cursor["binding"]?.jsonPrimitive?.content!=binding))throw ApiProblem(400,"invalid-request","Cursor filter mismatch")
        if(cursor!=null && cursor["generation"]?.jsonPrimitive?.longOrNull!=generation)throw ApiProblem(410,"cursor-expired")
        val wishes=if(!allUsers)ctx.wishlist.list(actor.user.id,WishFilter(kind,state,history)) else ctx.db.read {
            query("SELECT * FROM wishlist_wish ORDER BY added DESC,id",map=ctx.wishlist.store::row)
        }.filter { (owner==null || it.userId==owner) && (kind==null || it.kind==kind) && (state==null || it.state==state) && (history || it.state!="fulfilled" || (it.fulfilledAt ?: 0)>System.currentTimeMillis()-30*DiscoverCache.DAY) }.map(ctx.wishlist::present)
        val after=cursor?.get("added")?.jsonPrimitive?.longOrNull
        val afterId=cursor?.get("id")?.jsonPrimitive?.content
        val ordered=wishes.filter { after==null || it.addedAt<after || (it.addedAt==after && it.id>afterId!!) }
        val selected=ordered.take(limit)
        val next=if(ordered.size>limit)selected.last().let { cursors.encode(j("kind" to "wishes","user" to actor.user.id,"binding" to binding,"generation" to generation,"added" to it.addedAt,"id" to it.id)) } else null
        return j("items" to selected.map(::wishJson),"nextCursor" to next)
    }
    suspend fun replayAdd(call:ApplicationCall,p:Principal,body:JsonObject) {
        requireEnabled("/me/wishes");requireScopes(p,"user:write");call.stamp(ctx,p)
        call.send(projectMutation(ctx.wishlist.add(p.user.id,addWishInput(body,wishActionTime(call.request.headers["Dulcet-Action-Time"])))),cacheable=false)
    }
    fun reject(user:String,body:JsonObject,wishId:String?=null):WishMutation {
        val target=if(body["target"]!=null)body.wishObject("target").let { ExternalTarget(DiscoverKind.parse(it.wishString("kind",true)!!),wishIds(it.wishObject("externalIds"))) }
            else wishId?.let { ctx.wishlist.get(user,it)?.let { w -> ExternalTarget(w.kind,w.externalIds) } } ?: throw ApiProblem(400,"invalid-request","target is required")
        val pair=body.wishObject("libraryItem")
        return ctx.wishlist.rejectMatch(user,target,LibraryRef(pair.wishString("id",true)!!,pair.wishString("type",true)!!),wishId,body.wishString("operationId",true)!!)
    }
    fun install(route:Route)=with(route) {
        get("/me/wishes") {val p=call.principal(ctx,"user:read");requireEnabled("/me/wishes");ctx.wishFulfilment.wake();call.stamp(ctx,p);call.send(page(p,false,call.request.queryParameters.entries().associate {it.key to it.value.first()}))}
        post("/me/wishes") {
            val p=call.principal(ctx,"user:write");requireEnabled("/me/wishes");val result=ctx.wishlist.add(p.user.id,addWishInput(call.body(),wishActionTime(call.request.headers["Dulcet-Action-Time"])));call.stamp(ctx,p)
            if(result.applied && result.wish!=null)call.response.headers.append(HttpHeaders.Location,"/dulcet/v1/me/wishes/${result.wish.id}")
            call.send(projectMutation(result),if(result.applied && result.wish!=null)201 else 200,cacheable=false)
        }
        post("/me/wishes/cancellations") {val p=call.principal(ctx,"user:delete");requireEnabled("/me/wishes");val body=call.body();val result=ctx.wishlist.remove(p.user.id,RemoveWish(null,body.wishString("addOperationId",true),body.wishString("operationId",true)!!,wishActionTime(call.request.headers["Dulcet-Action-Time"])));call.stamp(ctx,p);call.send(projectMutation(result),cacheable=false)}
        get("/me/wishes/{wishId}") {
            val p=call.principal(ctx,"user:read");requireEnabled("/me/wishes");ctx.wishFulfilment.wake();val id=call.parameters["wishId"]!!
            val value=resource(p.user.id,id) ?: throw ApiProblem(if(ctx.wishlist.wasRemoved(p.user.id,id))410 else 404,if(ctx.wishlist.wasRemoved(p.user.id,id))"gone" else "not-found")
            call.stamp(ctx,p);call.send(value,etag=value["revision"]!!.jsonPrimitive.content)
        }
        delete("/me/wishes/{wishId}") {val p=call.principal(ctx,"user:delete");requireEnabled("/me/wishes");ctx.wishlist.remove(p.user.id,RemoveWish(call.parameters["wishId"],null,call.request.queryParameters["operationId"] ?: throw ApiProblem(400,"invalid-request","operationId is required"),wishActionTime(call.request.headers["Dulcet-Action-Time"])));call.stamp(ctx,p);call.send(j(),204,cacheable=false)}
        post("/me/wishes/{wishId}/reject-match") {val p=call.principal(ctx,"user:write");requireEnabled("/me/wishes");val result=reject(p.user.id,call.body(),call.parameters["wishId"]!!);call.stamp(ctx,p);call.send(projectMutation(result),cacheable=false)}
        post("/discover/rejected-matches") {val p=call.principal(ctx,"user:write");requireEnabled("/discover/rejected-matches");val result=reject(p.user.id,call.body());call.stamp(ctx,p);call.send(projectMutation(result),cacheable=false)}
        get("/admin/wishes") {val p=call.principal(ctx,"admin");requireEnabled("/admin/wishes");call.stamp(ctx,p);call.send(page(p,true,call.request.queryParameters.entries().associate {it.key to it.value.first()}))}
        patch("/admin/wishes/{wishId}") {val p=call.principal(ctx,"admin");requireEnabled("/admin/wishes");val body=call.body();val wish=ctx.wishlist.setState(p,call.parameters["wishId"]!!,body.wishString("state",true)!!,body.wishString("note"),call.request.headers[HttpHeaders.IfMatch]?.removeSurrounding("\""));call.stamp(ctx,p);call.send(wishJson(ctx.wishlist.present(wish)),etag=wish.revision,cacheable=false)}
    }
}
