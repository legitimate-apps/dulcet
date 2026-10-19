package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.ServerContext

/** Apply the same live account policy to legacy calls before binary or catalog handlers run. */
internal fun authorizeRest(ctx: ServerContext, call: Call) {
    if(call.method == "download" && !ctx.access.download(call.user.id)) throw SubsonicError.notAuthorized()
    if(call.method in setOf("getShares", "createShare", "updateShare", "deleteShare") && !ctx.access.share(call.user.id)) throw SubsonicError.notAuthorized()
    fun requireVisible(type: String, id: String) {
        if(!ctx.access.canReadCatalog(call.user.id,type,id)) throw SubsonicError.notFound()
    }
    when(call.method) {
        "getSong", "stream", "download", "getLyricsBySongId" -> call.param("id")?.let { requireVisible("track",it) }
        "getAlbum", "getAlbumInfo", "getAlbumInfo2" -> call.param("id")?.let { requireVisible("album",it) }
        "getArtist", "getArtistInfo", "getArtistInfo2" -> call.param("id")?.let { requireVisible("artist",it) }
        "getMusicDirectory" -> call.param("id")?.let { requireVisible(ctx.catalog.kindOf(it) ?: throw SubsonicError.notFound(),it) }
        "getTranscodeDecision", "getTranscodeStream" -> call.param("mediaId")?.let { requireVisible("track",it) }
        "getCoverArt" -> call.param("id")?.let { if(!ctx.access.canReadArtwork(call.user,it)) throw SubsonicError.notFound("Artwork") }
    }
}
