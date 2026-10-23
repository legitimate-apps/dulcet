package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.v1.ApiProblem
import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import kotlinx.serialization.json.*

/** Explicit array names keep the XML and JSON contracts equivalent, including empty pages. */
internal fun extensionNode(name:String,value:JsonObject,items:String="item"):Node = Node(name).apply {
    for((key,v) in value)when(v) {
        JsonNull -> Unit
        is JsonPrimitive -> this[key]=if(v.isString)v.content else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
        is JsonObject -> put(extensionNode(key,if(key=="libraryItem" && v["type"]==JsonPrimitive("track"))JsonObject(v+ ("type" to JsonPrimitive("song"))) else v,when(key){"albums"->"album";"tracks"->"song";"entries"->"entry";else->"item"}))
        is JsonArray -> {
            val row=when(key){"items"->items;"links"->"link";"libraryItems"->"item";"editions"->"edition";else->key}
            val target=if(key in setOf("links","libraryItems","editions"))child(key) else this
            if(v.all {it is JsonPrimitive}) { if(v.isNotEmpty())target.strings(row,v.map {it.jsonPrimitive.content}) else target.emptyList(row) }
            else {target.emptyList(row);v.forEach { entry ->
                val obj=entry.jsonObject
                val mapped=if(key=="libraryItems" && obj["type"]==JsonPrimitive("track"))JsonObject(obj+("type" to JsonPrimitive("song"))) else obj
                target.add(extensionNode(row,mapped))
            }}
        }
    }
}
internal fun extensionReply(name:String,value:JsonObject,items:String="item")=ok {put(extensionNode(name,value,items))}
internal suspend fun extensionCall(block:suspend()->Reply):Reply=try {block()}
    catch(e:ApiProblem){throw SubsonicError(if(e.status in setOf(404,410))70 else if(e.status in setOf(401,403))50 else 0,e.code,e.code)}
    catch(e:UpstreamNotFound){throw SubsonicError.notFound()}
    catch(e:UpstreamUnavailable){throw SubsonicError(0,"Discovery source temporarily unavailable","upstream-unavailable",((e.retryAfterMillis ?: 60_000)+999)/1000)}
    catch(e:IllegalArgumentException){throw SubsonicError(0,"Invalid extension parameter","invalid-request")}
internal fun Call.discoverPage()=PageRequest(param("size")?.let {it.toIntOrNull() ?: throw SubsonicError(0,"Invalid size","invalid-request")} ?: 20,param("cursor"))

fun discoverHandlers(ctx:ServerContext):Map<String,Handler> {
    fun handler(block:suspend(Call)->Reply):Handler={call -> extensionCall {if(!ctx.config.discover.enabled)throw SubsonicError.notFound("Discover");block(call)}}
    return mapOf(
        "searchDulcetDiscover" to handler {c -> extensionReply("dulcetDiscover",pageJson(ctx.discover.search(c.user.id,DiscoverKind.parse(c.required("kind")),c.required("query"),c.discoverPage()),::itemJson))},
        "getDulcetDiscoverShelf" to handler {c -> val id=c.required("id");if(id=="publicPlaylists")extensionReply("dulcetDiscoverPlaylists",pageJson(ctx.discover.playlists(c.user.id,"",c.discoverPage()),::summaryJson),"playlist") else extensionReply("dulcetDiscover",pageJson(ctx.discover.shelf(c.user.id,id,c.discoverPage()),::itemJson))},
        "getDulcetDiscoverArtist" to handler {c -> extensionReply("dulcetDiscoverArtist",artistJson(ctx.discover.artist(c.user.id,c.required("mbid"))))},
        "getDulcetDiscoverArtistAlbums" to handler {c -> extensionReply("dulcetDiscover",pageJson(ctx.discover.albums(c.user.id,c.required("mbid"),c.discoverPage()),::itemJson),"album")},
        "getDulcetDiscoverAlbum" to handler {c -> extensionReply("dulcetDiscoverAlbum",albumJson(ctx.discover.album(c.user.id,c.required("mbid"),c.param("releaseId"))))},
        "getDulcetDiscoverAlbumTracks" to handler {c -> extensionReply("dulcetDiscover",pageJson(ctx.discover.albumTracks(c.user.id,c.required("mbid"),c.required("releaseId"),c.discoverPage()),::itemJson),"song")},
        "searchDulcetDiscoverPlaylists" to handler {c -> extensionReply("dulcetDiscoverPlaylists",pageJson(ctx.discover.playlists(c.user.id,c.param("query").orEmpty(),c.discoverPage()),::summaryJson),"playlist")},
        "getDulcetDiscoverPlaylist" to handler {c -> extensionReply("dulcetDiscoverPlaylist",playlistJson(ctx.discover.playlist(c.user.id,c.required("mbid"),c.discoverPage())))},
        "getDulcetDiscoverCoverArt" to handler {c -> val art=ctx.discover.artwork(c.user.id,c.required("id"));c.ktor.respondBytes(art.bytes,ContentType.parse(art.contentType));Reply.Handled},
    )
}
