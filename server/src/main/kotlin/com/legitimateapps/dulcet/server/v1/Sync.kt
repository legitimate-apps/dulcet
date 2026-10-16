package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Key-enumerated snapshots followed by the journal boundary captured at their start. */
class ApiSync(private val ctx:ServerContext) {
    private val codec=CursorCodec(ctx)
    fun install(route:Route) { route.get("/sync/changes") {
        val p=call.principal(ctx)
        val q=call.request.queryParameters
        val types=(q["types"]?.split(',') ?: syncTypes(p).split(',')).filter(String::isNotEmpty).distinct().sorted()
        if(types.isEmpty()) throw ApiProblem(403,"insufficient-scope",extra=j("requiredScopes" to listOf("catalog:read","user:read")))
        types.forEach { type ->
            val scope=when(type) {"artist","album","track","genre","source"->"catalog:read";"itemState","playlist","play","queue"->"user:read";else->throw ApiProblem(400,"invalid-request","Unknown change type")}
            if(scope !in p.scopes) throw ApiProblem(403,"insufficient-scope",extra=j("requiredScopes" to listOf(scope)))
        }
        val filter=types.joinToString(",")
        val limit=q["limit"]?.let { it.toIntOrNull() ?: throw ApiProblem(400,"invalid-request","Invalid limit") } ?: 500
        if(limit !in 1..500) throw ApiProblem(400,"invalid-request","Invalid limit")
        val cursor=q["cursor"]
        if(cursor!=null && q["start"]!=null) throw ApiProblem(400,"invalid-request","Choose cursor or start")
        val page=ctx.db.read {
            ctx.journal.prune()
            val head=ctx.journal.head()
            when {
                cursor==null && q["start"]=="now" -> j("changes" to emptyList<JsonObject>(),"nextCursor" to ctx.journal.cursor(p.user.id,filter,head),"hasMore" to false,"phase" to "changes")
                cursor==null && (q["start"]=="snapshot" || q["start"]==null) -> snapshot(p,types,head,"",limit)
                cursor==null -> throw ApiProblem(400,"invalid-request","Unknown start mode")
                cursor.startsWith("c_") -> {
                    val decoded=codec.decode(cursor)
                    if(decoded["user"]?.jsonPrimitive?.content!=p.user.id) throw ApiProblem(400,"invalid-request","Cursor user mismatch")
                    if(decoded["types"]?.jsonPrimitive?.content!=filter) throw ApiProblem(400,"cursor-types-mismatch")
                    val at=decoded["head"]?.jsonPrimitive?.longOrNull ?: throw ApiProblem(400,"invalid-request")
                    if(at<ctx.journal.floor() || at>head) throw ApiProblem(410,"cursor-expired")
                    snapshot(p,types,at,decoded["after"]!!.jsonPrimitive.content,limit)
                }
                else -> {
                    val position=try { ctx.journal.decodeCursor(p.user.id,filter,cursor) } catch(e:Journal.CursorException) {
                        throw ApiProblem(if(e.expired)410 else 400,if(e.expired)"cursor-expired" else if(e.message?.contains("type filter")==true)"cursor-types-mismatch" else "invalid-request")
                    }
                    changes(p,types,position,limit)
                }
            }
        }
        call.send(page)
    } }
    private fun allIds(p:Principal,type:String):List<String> = ctx.db.read {
        when(type) {
            "itemState" -> query("SELECT item_id FROM annotation WHERE user_id=? UNION SELECT item_id FROM library_item WHERE user_id=? ORDER BY item_id",p.user.id,p.user.id) { it.string("item_id") }
            "playlist" -> query("SELECT id FROM playlist WHERE owner_id=? OR public=1 ORDER BY id",p.user.id) { it.string("id") }
            "play" -> query("SELECT id FROM api_play WHERE user_id=? AND payload IS NOT NULL ORDER BY id",p.user.id) { it.string("id") }
            "queue" -> listOf("queue")
            else -> ctx.apiCatalog.ids(type,p)
        }
    }
    private fun resource(p:Principal,type:String,id:String):JsonObject? = if(type=="queue") ctx.apiQueue.resource(p) else if(type in setOf("itemState","playlist","play")) ctx.apiUserData.resource(type,id,p) else ctx.apiCatalog.resource(type,id,p)?.let { JsonObject(it.filterKeys { key->key!="userState" }) }
    private fun snapshot(p:Principal,types:List<String>,head:Long,after:String,limit:Int):JsonObject {
        val keys=types.flatMap { type->allIds(p,type).map { type to it } }.sortedBy { it.first+":"+it.second }.filter { it.first+":"+it.second>after }
        val selected=keys.take(limit)
        val changes=selected.mapNotNull { (type,id)->resource(p,type,id)?.let { value->j("op" to "upsert","type" to type,"id" to id,"revision" to (value["revision"] ?: json(ctx.journal.revision(type,id,if(type in setOf("itemState","play"))p.user.id else null))),"at" to iso(System.currentTimeMillis()),"resource" to value) } }
        val more=keys.size>limit
        val next=if(more)codec.encode(j("user" to p.user.id,"types" to types.joinToString(","),"head" to head,"after" to (selected.last().first+":"+selected.last().second))) else ctx.journal.cursor(p.user.id,types.joinToString(","),head)
        // Final snapshot page still hasMore, because the changes phase must always be visited.
        return j("changes" to changes,"nextCursor" to next,"hasMore" to true,"phase" to "snapshot")
    }
    private data class Row(val seq:Long,val type:String,val id:String,val user:String?,val op:String,val at:Long,val reason:String?,val replaced:String?,val sources:String?)
    private fun changes(p:Principal,types:List<String>,position:Long,limit:Int):JsonObject {
        val rows=ctx.db.read { query("SELECT j.*,v.source_ids AS visibility_sources FROM api_journal j LEFT JOIN api_journal_visibility v ON v.seq=j.seq WHERE j.seq>? AND NOT EXISTS (SELECT 1 FROM api_journal newer WHERE newer.seq>j.seq AND newer.type=j.type AND newer.id=j.id AND newer.user_id IS j.user_id) ORDER BY j.seq LIMIT ?",position,limit) { Row(it.long("seq"),it.string("type"),it.string("id"),it.stringOrNull("user_id"),it.string("op"),it.long("at"),it.stringOrNull("reason"),it.stringOrNull("replaced_by"),it.stringOrNull("visibility_sources")) } }
        val coalesced=rows.filter { it.type in types && (it.user==null || it.user==p.user.id) }.groupBy { it.type to it.id }.values.map { it.last() }.sortedBy { it.seq }
        val output=coalesced.mapNotNull { row ->
            val value=resource(p,row.type,row.id)
            if(row.op=="delete" && value==null && row.type in setOf("artist","album","track","genre","source")) {
                val sources=row.sources?.let { Json.parseToJsonElement(it).jsonArray.map { source->source.jsonPrimitive.content } }
                    ?: throw ApiProblem(410,"cursor-expired","Historical source visibility cannot be established")
                if(sources.none { ctx.access.canReadSource(p.user.id,it) }) return@mapNotNull null
            }
            if(row.op=="upsert" && value==null) null else {
                val fields=j("op" to if(value!=null) "upsert" else row.op,"type" to row.type,"id" to row.id,"revision" to (value?.get("revision") ?: json("r_${ctx.journal.instanceId}_${row.seq}")),"at" to iso(row.at))
                JsonObject(fields+if(value!=null) j("resource" to value) else j("reason" to (if(row.reason=="split") "merged" else row.reason ?: "deleted"))+if(row.replaced!=null)j("replacedBy" to ctx.apiCatalog.liveReplacements(row.type,Json.parseToJsonElement(row.replaced).jsonArray.map { it.jsonPrimitive.content }).filter { ctx.access.canReadCatalog(p.user.id,row.type,it) }) else j())
            }
        }
        val next=rows.lastOrNull()?.seq ?: ctx.journal.head()
        return j("changes" to output,"nextCursor" to ctx.journal.cursor(p.user.id,types.joinToString(","),next),"hasMore" to (next<ctx.journal.head()),"phase" to "changes")
    }
}
