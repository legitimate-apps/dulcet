package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.discover.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class ApiDiscover(private val ctx:ServerContext) {
    private fun ApplicationCall.reader():Principal {
        val p=principal(ctx,"catalog:read")
        if(!ctx.config.discover.enabled)throw ApiProblem(404,"not-found")
        stamp(ctx,p);return p
    }
    private fun ApplicationCall.required(name:String)=request.queryParameters[name] ?: throw ApiProblem(400,"invalid-request","$name is required")
    private fun ApplicationCall.mbid()=parameters["mbid"] ?: throw ApiProblem(400,"invalid-request")
    private fun ApplicationCall.page()=PageRequest(request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw ApiProblem(400,"invalid-request") } ?: 20,request.queryParameters["cursor"])
    fun install(route:Route)=with(route) {
        route("/discover") {
            get("/search") {
                val p=call.reader()
                call.send(pageJson(ctx.discover.search(p.user.id,DiscoverKind.parse(call.required("kind")),call.required("q"),call.page()),::itemJson))
            }
            get("/shelves/{shelfId}") {
                val p=call.reader();val id=call.parameters["shelfId"]!!
                if(id=="publicPlaylists")call.send(pageJson(ctx.discover.playlists(p.user.id,"",call.page()),::summaryJson))
                else call.send(pageJson(ctx.discover.shelf(p.user.id,id,call.page()),::itemJson))
            }
            get("/artists/{mbid}") {val p=call.reader();call.send(artistJson(ctx.discover.artist(p.user.id,call.mbid())))}
            get("/artists/{mbid}/albums") {val p=call.reader();call.send(pageJson(ctx.discover.albums(p.user.id,call.mbid(),call.page()),::itemJson))}
            get("/albums/{mbid}") {val p=call.reader();call.send(albumJson(ctx.discover.album(p.user.id,call.mbid(),call.request.queryParameters["releaseId"])))}
            get("/albums/{mbid}/tracks") {val p=call.reader();call.send(pageJson(ctx.discover.albumTracks(p.user.id,call.mbid(),call.required("releaseId"),call.page()),::itemJson))}
            get("/playlists") {val p=call.reader();call.send(pageJson(ctx.discover.playlists(p.user.id,call.request.queryParameters["q"].orEmpty(),call.page()),::summaryJson))}
            get("/playlists/{mbid}") {val p=call.reader();call.send(playlistJson(ctx.discover.playlist(p.user.id,call.mbid(),call.page())))}
            get("/artwork/{artworkId}") {
                val p=call.reader();val image=ctx.discover.artwork(p.user.id,call.parameters["artworkId"]!!)
                val etag="\"${ctx.journal.instanceId}.${hash(image.bytes)}\""
                call.response.headers.append(HttpHeaders.ETag,etag);call.response.headers.append(HttpHeaders.CacheControl,"private");call.response.headers.append(HttpHeaders.Vary,"Authorization")
                call.response.headers.append("Dulcet-Sync-Cursor",ctx.journal.cursor(p.user.id,syncTypes(p)))
                if(call.request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.map(String::trim)?.any { it==etag || it=="W/$etag" || it=="*" }==true)call.respond(HttpStatusCode.NotModified)
                else call.respondBytes(image.bytes,ContentType.parse(image.contentType))
            }
        }
    }
}
