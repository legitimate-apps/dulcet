package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** The fixture adapter and local administrators can trigger and observe the same scanner. */
class ApiScans(private val ctx:ServerContext) {
    init { ctx.db.write {
        execute("CREATE TABLE IF NOT EXISTS api_scan(id TEXT PRIMARY KEY,created INTEGER NOT NULL,payload TEXT NOT NULL)")
        // The scanner runs in this process. An unfinished persisted record cannot be resumed after
        // restart and must not prevent a new scan from being requested.
        query("SELECT id,payload FROM api_scan") { it.string("id") to Json.parseToJsonElement(it.string("payload")).jsonObject }
            .filter { (_,value) -> value["state"]?.jsonPrimitive?.content in setOf("queued","running") }
            .forEach { (id,value) -> execute("UPDATE api_scan SET payload=? WHERE id=?",JsonObject(value+j("state" to "failed","finishedAt" to iso(System.currentTimeMillis()),"error" to "Scan interrupted by server restart; request a new scan")).toString(),id) }
    } }
    private val observed=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun observe(run:com.legitimateapps.dulcet.server.scan.Scanner.Run,value:JsonObject) {
        if(!observed.add(run.id)) return
        run.completion.whenComplete { result,error ->
            val outcome=if(error!=null || result==null) {
                j("state" to "failed","error" to "Scan failed; catalog retained")
            } else {
                j("state" to if(result.refused==null) "completed" else "failed",
                    "counts" to j("added" to result.added,"updated" to result.updated,"removed" to result.removed)) +
                    if(result.refused!=null) j("error" to result.refused) else j()
            }
            val completed=JsonObject(value+outcome+j("finishedAt" to iso(System.currentTimeMillis())))
            try { ctx.db.write { execute("UPDATE api_scan SET payload=? WHERE id=?",completed.toString(),run.id) } }
            finally { observed.remove(run.id) }
        }
    }
    private fun find(id:String):JsonObject?=ctx.db.read { string("SELECT payload FROM api_scan WHERE id=?",id)?.let { Json.parseToJsonElement(it).jsonObject } }
    fun install(route:Route) {
        route.get("/admin/scans") {
            call.principal(ctx,"admin")
            call.send(j("items" to ctx.db.read { query("SELECT payload FROM api_scan ORDER BY created DESC,id DESC LIMIT 100") { Json.parseToJsonElement(it.string("payload")) } }))
        }
        route.get("/admin/scans/{scanId}") {
            call.principal(ctx,"admin");call.send(find(call.parameters["scanId"]!!) ?: throw ApiProblem(404,"not-found"))
        }
        route.post("/admin/scans") {
            call.principal(ctx,"admin")
            val body=if(call.request.headers["Content-Length"]=="0" || call.request.headers["Content-Type"]==null) j() else call.body()
            val kind=body["kind"]?.jsonPrimitive?.content ?: "quick"
            if(kind !in setOf("quick","full")) throw ApiProblem(400,"invalid-request")
            val result=ctx.db.write {
                val run=ctx.scanner.startOrJoin(force=kind=="full") { work -> com.legitimateapps.dulcet.server.api.launchBackground(work) }
                val value=find(run.id) ?: j("id" to run.id,"kind" to if(run.force) "full" else "quick","state" to "running","startedAt" to iso(run.startedAt),"finishedAt" to null).also {
                    execute("INSERT INTO api_scan VALUES(?,?,?)",run.id,run.startedAt,it.toString())
                }
                observe(run,value)
                find(run.id) ?: value
            }
            call.response.headers.append("Location","/dulcet/v1/admin/scans/${result["id"]!!.jsonPrimitive.content}")
            call.send(result,202)
        }
    }
}
