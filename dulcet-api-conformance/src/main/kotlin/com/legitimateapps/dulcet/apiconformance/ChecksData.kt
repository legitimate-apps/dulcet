package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.legitimateapps.dulcet.apiconformance.Profile.Core
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private fun nowMs(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

fun Ctx.track(trackId: String, token: String = tokenA): JsonNode {
    val ex = api("GET", "getTrack", "/tracks/${id(trackId)}", token = token)
    expectStatus(ex, 200, what = "GET /tracks/{id}")
    return body(ex)
}

fun Ctx.userState(trackId: String, token: String = tokenA): JsonNode =
    track(trackId, token).path("userState").takeIf { it.isObject } ?: fail("GET /tracks/{id} with user:read carries no userState")

fun Ctx.createPlaylist(name: String, items: List<String>, visibility: String = "private", headers: Map<String, String> = emptyMap()): Pair<Exchange, String> {
    val ex = api("POST", "createPlaylist", "/playlists", headers = headers, body = mapOf("name" to name, "visibility" to visibility, "itemIds" to items))
    expectStatus(ex, 201, what = "creating a playlist")
    val pid = body(ex).str("id")!!
    run.cleanups += "delete playlist $pid" to { api("DELETE", "deletePlaylist", "/playlists/${id(pid)}"); Unit }
    return ex to pid
}

fun Ctx.playlistItems(pid: String): JsonNode {
    val ex = api("GET", "listPlaylistItems", "/playlists/${id(pid)}/items", query = listOf("limit" to "500"))
    expectStatus(ex, 200, what = "listing playlist entries")
    return body(ex)
}

private val PROBLEM_TYPES = mapOf(
    400 to setOf("invalid-request", "cursor-types-mismatch"),
    401 to setOf("unauthenticated"),
    403 to setOf("insufficient-scope", "permission-denied", "media-grant-expired", "reauthentication-required"),
    404 to setOf("not-found"),
    409 to setOf("conflict", "not-active-device", "device-unavailable"),
    410 to setOf("cursor-expired", "gone"),
    412 to setOf("revision-mismatch"),
    415 to setOf("unsupported-media"),
    422 to setOf("unknown-item", "idempotency-key-reused", "token-lifetime-too-long"),
    428 to setOf("precondition-required"),
    429 to setOf("rate-limited"),
    500 to setOf("server-error"),
    503 to setOf("unavailable"),
)

fun dataChecks(): List<Check> = listOf(
    // Catalog (README §4, §5)
    Check("DA-CAT-1", Core, "lists page to completion with no duplicates; total agrees", "README §5") {
        requireFixture()
        val small = walk("/albums", "listAlbums", query = listOf("limit" to "1"), maxPages = 2000).ids()
        ensure(small.size == small.toSet().size) { "paging /albums one at a time returned duplicates" }
        ensure(small.toSet() == fixture.albums.ids().toSet()) { "paging by 1 found ${small.size} albums, by 100 found ${fixture.albums.size}" }
        val first = body(api("GET", "listAlbums", "/albums", query = listOf("limit" to "1")))
        ensure(first.path("items").size() <= 1) { "limit=1 returned ${first.path("items").size()} items" }
        if (first.has("total")) ensure(first.path("total").asInt() == small.size) { "total ${first.path("total")} != ${small.size} albums walked" }
        val tracks = walk("/tracks", "listTracks", query = listOf("limit" to "3"), maxPages = 2000).ids()
        ensure(tracks.size == tracks.toSet().size && tracks.toSet() == fixture.tracks.ids().toSet()) { "paging /tracks by 3 disagrees with paging by 100" }
    },
    Check("DA-CAT-2", Core, "ids batch fetch returns exactly the requested items", "openapi Ids parameter") {
        requireFixture()
        val want = listOf(fixture.album(0), fixture.album(1))
        val ex = api("GET", "listAlbums", "/albums", query = listOf("ids" to want.joinToString(",")))
        expectStatus(ex, 200)
        ensure(body(ex).path("items").ids().toSet() == want.toSet()) { "ids=${want.size} ids returned ${body(ex).path("items").ids().size} items, not exactly those" }
    },
    Check("DA-CAT-3", Core, "ids are unique across resource types", "README §4") {
        requireFixture()
        val all = fixture.artists.ids() + fixture.albums.ids() + fixture.tracks.ids()
        val dup = all.groupBy { it }.filter { it.value.size > 1 }.keys
        ensure(dup.isEmpty()) { "${dup.size} ids are shared between types" }
    },
    Check("DA-CAT-4", Core, "album detail lists its tracks, which point back at the album", "openapi AlbumDetail") {
        requireFixture()
        val albumId = fixture.album(0)
        val trackIds = fixture.albumTracks[albumId] ?: fail("no album detail was read")
        ensure(trackIds.isNotEmpty()) { "album detail has no tracks" }
        for (t in trackIds.take(5)) ensure(track(t).path("album").str("id") == albumId) { "a track of album detail does not name the album" }
        val byAlbum = walk("/tracks", "listTracks", query = listOf("albumId" to albumId, "limit" to "100")).ids()
        ensure(byAlbum.toSet() == trackIds.toSet()) { "GET /tracks?albumId= returned ${byAlbum.size} tracks, the album detail ${trackIds.size}" }
    },
    Check("DA-CAT-5", Core, "an id never issued answers 404 not-found", "README §4, §14") {
        val ex = api("GET", "getAlbum", "/albums/${id("never-issued-" + run.runId)}")
        expectProblem(ex, 404, "not-found")
        expectProblem(api("GET", "getTrack", "/tracks/${id("never-issued-" + run.runId)}"), 404, "not-found")
    },
    Check("DA-CAT-6", Core, "unknown query parameters are ignored", "README §1") {
        requireFixture()
        expectStatus(api("GET", "listAlbums", "/albums", query = listOf("limit" to "2", "zzConformanceUnknown" to "1")), 200, what = "an unknown query parameter")
    },
    Check("DA-CAT-7", Core, "an out-of-range limit is a 400 invalid-request problem", "openapi Limit") {
        expectProblem(api("GET", "listAlbums", "/albums", query = listOf("limit" to "0")), 400, "invalid-request", what = "limit=0")
        expectProblem(api("GET", "listAlbums", "/albums", query = listOf("limit" to "501")), 400, "invalid-request", what = "limit=501")
    },
    Check("DA-CAT-8", Core, "catalog items read with user:read carry userState", "openapi UserState") {
        requireFixture()
        userState(fixture.track(0))
        ensure(body(api("GET", "getAlbum", "/albums/${id(fixture.album(0))}")).path("userState").isObject) { "album detail carries no userState" }
    },
    Check("DA-SRCH-1", Core, "search finds an album by its title", "README §11") {
        requireFixture()
        val album = fixture.albums.first()
        val q = album.str("title")!!
        val ex = api("GET", "search", "/search", query = listOf("q" to q, "types" to "album"))
        expectStatus(ex, 200)
        ensure(body(ex).path("albums").path("items").ids().contains(album.str("id"))) { "searching for an album's own title did not return it" }
    },

    // ETags (README §6)
    Check("DA-ETAG-1", Core, "If-None-Match answers 304; ETag responses are private and vary by Authorization", "README §6") {
        requireFixture()
        val path = "/albums/${id(fixture.album(0))}"
        val first = api("GET", "getAlbum", path)
        expectStatus(first, 200)
        val etag = first.header("ETag") ?: fail("album detail has no ETag")
        ensure(first.header("Cache-Control")?.contains("private") == true) { "Cache-Control is ${first.header("Cache-Control")}, expected private" }
        ensure(first.headerValues("Vary").any { it.contains("Authorization", ignoreCase = true) }) { "Vary does not include Authorization" }
        val second = api("GET", "getAlbum", path, headers = mapOf("If-None-Match" to etag))
        expectStatus(second, 304, what = "If-None-Match with the current ETag")
        ensure(second.body.isEmpty()) { "a 304 carried a body" }
        val stale = api("GET", "getAlbum", path, headers = mapOf("If-None-Match" to "\"conformance-stale-${run.runId}\""))
        expectStatus(stale, 200, what = "If-None-Match with a foreign ETag")
    },
    Check("DA-ETAG-2", Core, "favouriting changes the album's ETag for that user only, not its revision", "README §6") {
        requireFixture()
        val albumId = fixture.album(1)
        val path = "/albums/${id(albumId)}"
        api("DELETE", "removeFavorite", "/me/favorites/${id(albumId)}")
        val before = api("GET", "getAlbum", path)
        val beforeB = config.tokenB?.let { api("GET", "getAlbum", path, token = it) }
        expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(albumId)}"), 200, what = "favouriting the album")
        try {
            val after = api("GET", "getAlbum", path)
            ensure(after.header("ETag") != before.header("ETag")) { "the ETag did not change when the user favourited the album" }
            ensure(body(after).str("revision") == body(before).str("revision")) { "favouriting changed the album's revision" }
            val notModified = api("GET", "getAlbum", path, headers = mapOf("If-None-Match" to before.header("ETag")!!))
            expectStatus(notModified, 200, what = "If-None-Match with the pre-favourite ETag")
            if (beforeB != null) {
                val afterB = api("GET", "getAlbum", path, token = config.tokenB)
                ensure(afterB.header("ETag") == beforeB.header("ETag")) { "user A's favourite changed user B's ETag" }
            }
        } finally {
            api("DELETE", "removeFavorite", "/me/favorites/${id(albumId)}")
        }
    },
    Check("DA-ETAG-3", Core, "playlist ETag is the quoted revision; If-Match mismatch 412; replace without If-Match 428", "README §6") {
        requireFixture()
        val (created, pid) = createPlaylist("conformance etag ${run.runId}", listOf(fixture.track(0)))
        val rev = body(created).str("revision")!!
        ensure(created.header("ETag") == "\"$rev\"") { "the create response's ETag ${created.header("ETag")} is not the quoted revision" }
        val got = api("GET", "getPlaylist", "/playlists/${id(pid)}")
        ensure(got.header("ETag") == "\"${body(got).str("revision")}\"") { "GET playlist ETag is not the quoted revision" }
        val mismatch = api("PATCH", "updatePlaylist", "/playlists/${id(pid)}", headers = mapOf("If-Match" to "\"conformance-wrong\""), body = mapOf("name" to "renamed"))
        expectProblem(mismatch, 412, "revision-mismatch", what = "PATCH with a wrong If-Match")
        ensure(body(mismatch).path("current").isObject) { "the 412 does not carry current" }
        val noMatch = api("PUT", "replacePlaylistItems", "/playlists/${id(pid)}/items", body = mapOf("itemIds" to listOf(fixture.track(1))))
        expectProblem(noMatch, 428, "precondition-required", what = "replacing entries without If-Match")
        val ok = api("PATCH", "updatePlaylist", "/playlists/${id(pid)}", headers = mapOf("If-Match" to "\"$rev\""), body = mapOf("name" to "conformance etag renamed"))
        expectStatus(ok, 200, what = "PATCH with the right If-Match")
        ensure(body(ok).str("revision") != rev) { "an edit did not change the revision" }
        ensure(body(ok).str("name") == "conformance etag renamed") { "the write did not return the resulting representation" }
    },

    // Idempotency (README §6)
    Check("DA-IDEM-1", Core, "a repeated POST with one Idempotency-Key creates one resource", "README §6") {
        requireFixture()
        val key = UUID.randomUUID().toString()
        val name = "conformance idem ${run.runId}"
        val (first, pid) = createPlaylist(name, listOf(fixture.track(0)), headers = mapOf("Idempotency-Key" to key))
        val second = api("POST", "createPlaylist", "/playlists", headers = mapOf("Idempotency-Key" to key),
            body = mapOf("name" to name, "visibility" to "private", "itemIds" to listOf(fixture.track(0))))
        expectStatus(second, 201, 200, what = "repeating the POST")
        body(second).str("id")?.takeIf { it != pid }?.let { other -> api("DELETE", "deletePlaylist", "/playlists/${id(other)}") }
        ensure(body(second).str("id") == pid) { "the repeat created a second playlist instead of returning the first response" }
        val count = walk("/playlists", "listPlaylists", query = listOf("limit" to "500")).count { it.str("name") == name }
        ensure(count == 1) { "$count playlists named after one key" }
    },
    Check("DA-IDEM-2", Core, "the same key with a different body answers 422 idempotency-key-reused", "README §6") {
        requireFixture()
        val key = UUID.randomUUID().toString()
        createPlaylist("conformance idem2 ${run.runId}", listOf(fixture.track(0)), headers = mapOf("Idempotency-Key" to key))
        val other = api("POST", "createPlaylist", "/playlists", headers = mapOf("Idempotency-Key" to key),
            body = mapOf("name" to "conformance idem2 other ${run.runId}", "itemIds" to listOf(fixture.track(1))))
        if (other.status in 200..299) body(other).str("id")?.let { api("DELETE", "deletePlaylist", "/playlists/${id(it)}") }
        expectProblem(other, 422, "idempotency-key-reused", what = "the same key, a different body")
    },

    // Item state (README §6, openapi User state)
    Check("DA-STATE-1", Core, "favourite is idempotent, keeps its first timestamp and is listed", "README §6") {
        requireFixture()
        val t = fixture.track(2)
        api("DELETE", "removeFavorite", "/me/favorites/${id(t)}")
        try {
            val first = api("PUT", "addFavorite", "/me/favorites/${id(t)}")
            expectStatus(first, 200)
            val at = body(first).str("favoritedAt") ?: fail("favourite returned no favoritedAt")
            ensure(body(first).str("itemId") == t) { "ItemState names ${body(first).str("itemId")}, not the track" }
            Thread.sleep(5)
            val second = api("PUT", "addFavorite", "/me/favorites/${id(t)}")
            expectStatus(second, 200)
            ensure(body(second).str("favoritedAt") == at) { "a second favourite changed favoritedAt" }
            ensure(userState(t).str("favoritedAt") == at) { "the track's userState does not show the favourite" }
            val listed = walk("/me/favorites", "listFavorites", query = listOf("limit" to "500"))
            ensure(listed.any { it.path("item").str("id") == t }) { "GET /me/favorites does not list the favourite" }
        } finally {
            api("DELETE", "removeFavorite", "/me/favorites/${id(t)}")
        }
        ensure(userState(t).path("favoritedAt").isNull) { "removing the favourite left favoritedAt set" }
    },
    Check("DA-STATE-2", Core, "ratings set, validate 1 to 10, and clear", "openapi RatingSet") {
        requireFixture()
        val t = fixture.track(2)
        val set = api("PUT", "setRating", "/me/ratings/${id(t)}", body = mapOf("rating" to 7))
        expectStatus(set, 200)
        ensure(body(set).path("rating").asInt() == 7) { "rating write returned ${body(set).path("rating")}" }
        expectProblem(api("PUT", "setRating", "/me/ratings/${id(t)}", body = mapOf("rating" to 11)), 400, "invalid-request", what = "rating 11")
        ensure(userState(t).path("rating").asInt() == 7) { "the refused rating changed the stored one" }
        expectStatus(api("DELETE", "clearRating", "/me/ratings/${id(t)}"), 204, what = "clearing the rating")
        ensure(userState(t).path("rating").isNull) { "clearing left a rating" }
    },
    Check("DA-STATE-3", Core, "removing a missing or unknown item succeeds", "README §6") {
        val unknown = "never-issued-" + run.runId
        expectStatus(api("DELETE", "removeFavorite", "/me/favorites/${id(unknown)}"), 204, what = "unfavouriting an unknown id")
        expectStatus(api("DELETE", "clearRating", "/me/ratings/${id(unknown)}"), 204, what = "clearing an unknown id's rating")
        expectStatus(api("DELETE", "removeFromMyLibrary", "/me/library/${id(unknown)}"), 204, what = "removing an unknown id from My Library")
    },
    Check("DA-STATE-4", Core, "a write older than the applied one answers applied: false and changes nothing", "README §6 Offline writes") {
        requireFixture()
        val t = fixture.track(3)
        api("DELETE", "removeFavorite", "/me/favorites/${id(t)}")
        val now = nowMs()
        try {
            expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(t)}", headers = mapOf("Dulcet-Action-Time" to now.toString())), 200)
            val stale = api("DELETE", "removeFavorite", "/me/favorites/${id(t)}", headers = mapOf("Dulcet-Action-Time" to now.minusSeconds(3600).toString()))
            expectStatus(stale, 204, 200, what = "an unfavourite stamped an hour earlier")
            if (stale.status == 200) ensure(body(stale).path("applied").asBoolean(true) == false) { "a stale write answered applied: true" }
            ensure(!userState(t).path("favoritedAt").isNull) { "an unfavourite stamped before the favourite removed it" }
            val staleRate = api("PUT", "setRating", "/me/ratings/${id(t)}", headers = mapOf("Dulcet-Action-Time" to now.toString()), body = mapOf("rating" to 4))
            expectStatus(staleRate, 200)
            val older = api("PUT", "setRating", "/me/ratings/${id(t)}", headers = mapOf("Dulcet-Action-Time" to now.minusSeconds(60).toString()), body = mapOf("rating" to 9))
            expectStatus(older, 200)
            ensure(body(older).path("applied").asBoolean(true) == false) { "an older rating answered applied: ${body(older).path("applied")}" }
            ensure(userState(t).path("rating").asInt() == 4) { "an older rating replaced a newer one" }
        } finally {
            api("DELETE", "removeFavorite", "/me/favorites/${id(t)}")
            api("DELETE", "clearRating", "/me/ratings/${id(t)}")
        }
    },
    Check("DA-STATE-5", Core, "POST /me/batch reports a result per operation", "openapi UserStateBatch") {
        requireFixture()
        val t2 = fixture.track(2); val t3 = fixture.track(3)
        val ex = api("POST", "applyUserStateBatch", "/me/batch", body = mapOf("operations" to listOf(
            mapOf("op" to "favorite", "itemId" to t2),
            mapOf("op" to "rate", "itemId" to t3, "rating" to 5),
            mapOf("op" to "favorite", "itemId" to "never-issued-" + run.runId),
        )))
        try {
            expectStatus(ex, 200)
            val r = body(ex).path("results")
            ensure(r.size() == 3) { "3 operations gave ${r.size()} results" }
            ensure(r[0].path("status").asInt() in 200..299 && r[1].path("status").asInt() in 200..299) { "valid operations failed: ${r.map { it.path("status").asInt() }}" }
            ensure(r[2].path("status").asInt() in 400..499) { "favouriting an unknown id reported ${r[2].path("status")}" }
            ensure(!userState(t2).path("favoritedAt").isNull && userState(t3).path("rating").asInt() == 5) { "the batch reported success but the state did not change" }
        } finally {
            api("DELETE", "removeFavorite", "/me/favorites/${id(t2)}")
            api("DELETE", "clearRating", "/me/ratings/${id(t3)}")
        }
    },

    // My Library (README §8)
    Check("DA-LIB-1", Core, "adding to My Library is idempotent, listed newest first, and removable", "README §8") {
        requireFixture()
        val t0 = fixture.track(0); val t1 = fixture.track(1)
        for (t in listOf(t0, t1)) api("DELETE", "removeFromMyLibrary", "/me/library/${id(t)}")
        try {
            val a = api("PUT", "addToMyLibrary", "/me/library/${id(t0)}")
            expectStatus(a, 200)
            val at = body(a).str("libraryAddedAt") ?: fail("no libraryAddedAt")
            Thread.sleep(10)
            expectStatus(api("PUT", "addToMyLibrary", "/me/library/${id(t1)}"), 200)
            ensure(body(api("PUT", "addToMyLibrary", "/me/library/${id(t0)}")).str("libraryAddedAt") == at) { "adding a member again changed libraryAddedAt" }
            val list = walk("/me/library", "listMyLibrary", query = listOf("limit" to "500")).map { it.path("item").str("id") }
            val i0 = list.indexOf(t0); val i1 = list.indexOf(t1)
            ensure(i0 >= 0 && i1 >= 0) { "GET /me/library does not list both members" }
            ensure(i1 < i0) { "GET /me/library is not newest first" }
            ensure(userState(t0).str("libraryAddedAt") == at) { "userState.libraryAddedAt does not show membership" }
            expectStatus(api("DELETE", "removeFromMyLibrary", "/me/library/${id(t0)}"), 204)
            ensure(userState(t0).path("libraryAddedAt").isNull) { "removal left libraryAddedAt set" }
            ensure(walk("/me/library", "listMyLibrary", query = listOf("limit" to "500")).none { it.path("item").str("id") == t0 }) { "a removed member is still listed" }
        } finally {
            for (t in listOf(t0, t1)) api("DELETE", "removeFromMyLibrary", "/me/library/${id(t)}")
        }
    },
    Check("DA-LIB-2", Core, "membership is per item: adding an album adds none of its tracks", "README §8") {
        requireFixture()
        val albumId = fixture.album(0)
        val tracks = fixture.albumTracks[albumId].orEmpty()
        tracks.forEach { api("DELETE", "removeFromMyLibrary", "/me/library/${id(it)}") }
        try {
            expectStatus(api("PUT", "addToMyLibrary", "/me/library/${id(albumId)}"), 200)
            for (t in tracks.take(3)) ensure(userState(t).path("libraryAddedAt").isNull) { "adding an album added its track" }
            val members = walk("/me/library", "listMyLibrary", query = listOf("limit" to "500")).map { it.path("item").str("id") }
            ensure(tracks.none { it in members }) { "adding an album listed its tracks as members" }
        } finally {
            api("DELETE", "removeFromMyLibrary", "/me/library/${id(albumId)}")
        }
    },
    Check("DA-LIB-3", Core, "counts are whole-library and scope=library matches members", "README §8") {
        requireFixture()
        val t = fixture.track(1)
        try {
            expectStatus(api("PUT", "addToMyLibrary", "/me/library/${id(t)}"), 200)
            val page = body(api("GET", "listMyLibrary", "/me/library", query = listOf("limit" to "1")))
            val all = walk("/me/library", "listMyLibrary", query = listOf("limit" to "500"))
            val tracksInLibrary = all.filter { it.path("item").str("type") == "track" || it.path("item").str("id") in fixture.tracks.ids() }
            ensure(page.path("counts").path("track").asInt(-1) == tracksInLibrary.size) { "counts.track ${page.path("counts").path("track")} on a 1-item page != ${tracksInLibrary.size} track members" }
            val scoped = walk("/tracks", "listTracks", query = listOf("scope" to "library", "limit" to "500")).ids()
            ensure(t in scoped) { "GET /tracks?scope=library does not include a member" }
            ensure(scoped.toSet() == tracksInLibrary.map { it.path("item").str("id") }.toSet()) { "GET /tracks?scope=library differs from the track members" }
        } finally {
            api("DELETE", "removeFromMyLibrary", "/me/library/${id(t)}")
        }
    },
    Check("DA-LIB-4", Core, "My Library is isolated between users", "README §8") {
        requireFixture()
        val b = tokenB
        val t = fixture.track(2)
        try {
            expectStatus(api("PUT", "addToMyLibrary", "/me/library/${id(t)}"), 200)
            ensure(walk("/me/library", "listMyLibrary", token = b, query = listOf("limit" to "500")).none { it.path("item").str("id") == t }) {
                "user A's library member appears in user B's library"
            }
            ensure(userState(t, b).path("libraryAddedAt").isNull) { "user A's membership shows in user B's userState" }
        } finally {
            api("DELETE", "removeFromMyLibrary", "/me/library/${id(t)}")
        }
    },

    // Plays (README §10)
    Check("DA-PLAY-1", Core, "a play id is counted once however often it is submitted", "README §10") {
        requireFixture()
        val t = fixture.track(3)
        val before = userState(t).path("playCount").asInt()
        val playId = UUID.randomUUID().toString()
        val first = api("POST", "submitPlays", "/me/plays", body = mapOf("plays" to listOf(playBody(playId, t))))
        expectStatus(first, 200)
        ensure(body(first).path("results")[0]?.str("status") == "recorded") { "first submission: ${body(first).path("results")[0]?.str("status")}" }
        val again = api("POST", "submitPlays", "/me/plays", body = mapOf("plays" to listOf(playBody(playId, t), playBody(playId, t))))
        expectStatus(again, 200)
        ensure(body(again).path("results").all { it.str("status") == "duplicate" }) { "resubmission: ${body(again).path("results").map { it.str("status") }}, expected duplicate" }
        val after = userState(t).path("playCount").asInt()
        ensure(after == before + 1) { "playCount went from $before to $after after one play submitted three times" }
        ensure(walk("/me/plays", "listPlays", query = listOf("limit" to "100"), maxPages = 1).count { it.str("id") == playId } == 1) { "GET /me/plays does not list the play exactly once" }
    },
    Check("DA-PLAY-2", Core, "a play of an unknown track answers unknownTrack", "README §10") {
        val ex = api("POST", "submitPlays", "/me/plays", body = mapOf("plays" to listOf(playBody(UUID.randomUUID().toString(), "never-issued-" + run.runId))))
        expectStatus(ex, 200)
        ensure(body(ex).path("results")[0]?.str("status") == "unknownTrack") { "status ${body(ex).path("results")[0]?.str("status")}, expected unknownTrack" }
    },
    Check("DA-PLAY-3", Core, "a deleted play does not come back when resubmitted", "README §10") {
        requireFixture()
        val t = fixture.track(3)
        val playId = UUID.randomUUID().toString()
        expectStatus(api("POST", "submitPlays", "/me/plays", body = mapOf("plays" to listOf(playBody(playId, t)))), 200)
        val counted = userState(t).path("playCount").asInt()
        expectStatus(api("DELETE", "deletePlay", "/me/plays/${id(playId)}"), 204, what = "deleting the play")
        ensure(userState(t).path("playCount").asInt() == counted - 1) { "deleting a play did not remove it from the count" }
        val again = api("POST", "submitPlays", "/me/plays", body = mapOf("plays" to listOf(playBody(playId, t))))
        expectStatus(again, 200)
        ensure(body(again).path("results")[0]?.str("status") == "duplicate") { "resubmitting a deleted play: ${body(again).path("results")[0]?.str("status")}, expected duplicate" }
        ensure(userState(t).path("playCount").asInt() == counted - 1) { "a resubmitted deleted play was counted again" }
        ensure(walk("/me/plays", "listPlays", query = listOf("limit" to "100"), maxPages = 1).none { it.str("id") == playId }) { "a deleted play came back in history" }
    },
    Check("DA-PLAY-4", Core, "a playback decision and a media fetch record no play", "README §9 Asking is not playing") {
        requireFixture()
        val t = fixture.track(0)
        val before = userState(t).path("playCount").asInt()
        val plan = directPlan(t)
        fetchMedia(plan.str("url")!!)
        ensure(userState(t).path("playCount").asInt() == before) { "a decision and a full fetch changed playCount" }
    },
    Check("DA-NP-1", Core, "now playing changes no play count and clears by deviceId or Dulcet-Device-Id, never by neither", "README §10; openapi clearNowPlaying") {
        requireFixture()
        val t = fixture.track(0)
        val before = userState(t).path("playCount").asInt()
        val device = "conformance-" + run.runId
        val put = api("PUT", "setNowPlaying", "/me/now-playing", body = mapOf("deviceId" to device, "trackId" to t, "state" to "playing", "positionMs" to 1000, "ttlSeconds" to 60))
        expectStatus(put, 200)
        val listed = body(api("GET", "listNowPlaying", "/now-playing").also { expectStatus(it, 200) })
        ensure(listed.path("items").any { it.str("deviceId") == device }) { "GET /now-playing does not list the device" }
        if (config.tokenB != null) {
            val other = body(api("GET", "listNowPlaying", "/now-playing", token = config.tokenB))
            ensure(other.path("items").none { it.str("deviceId") == device }) { "another user sees now playing without sharing turned on" }
        }
        ensure(userState(t).path("playCount").asInt() == before) { "now playing changed playCount" }
        fun listed(d: String) = body(api("GET", "listNowPlaying", "/now-playing")).path("items").any { it.str("deviceId") == d }
        expectStatus(api("DELETE", "clearNowPlaying", "/me/now-playing", query = listOf("deviceId" to device)), 204, what = "clearing by the deviceId query")
        ensure(!listed(device)) { "clearing by deviceId left the device in now playing" }
        val byHeader = "$device-h"
        expectStatus(api("PUT", "setNowPlaying", "/me/now-playing", body = mapOf("deviceId" to byHeader, "trackId" to t, "state" to "playing", "positionMs" to 1000, "ttlSeconds" to 60)), 200)
        expectStatus(api("DELETE", "clearNowPlaying", "/me/now-playing", headers = mapOf("Dulcet-Device-Id" to byHeader)), 204, what = "clearing by the Dulcet-Device-Id header")
        ensure(!listed(byHeader)) { "clearing by Dulcet-Device-Id left the device in now playing" }
        expectProblem(api("DELETE", "clearNowPlaying", "/me/now-playing"), 400, "invalid-request", what = "clearing with neither deviceId nor Dulcet-Device-Id")
    },

    // Playlists (README §11)
    Check("DA-PL-1", Core, "entry ids let duplicates be moved and removed exactly; every edit changes revision", "README §11") {
        requireFixture()
        val t0 = fixture.track(0); val t1 = fixture.track(1)
        val (created, pid) = createPlaylist("conformance entries ${run.runId}", listOf(t0, t1, t0))
        ensure(created.header("Location") != null) { "201 without Location" }
        val items = playlistItems(pid)
        val entries = items.path("items").map { it.str("entryId")!! }
        ensure(items.path("items").map { it.str("itemId") } == listOf(t0, t1, t0)) { "entries are not in the created order" }
        ensure(entries.toSet().size == 3) { "duplicate tracks share an entry id" }
        ensure(items.path("items").map { it.path("position").asInt() } == listOf(0, 1, 2)) { "positions are not 0, 1, 2" }
        var rev = items.str("revision")!!
        val move = api("PATCH", "movePlaylistItem", "/playlists/${id(pid)}/items/${id(entries[2])}", headers = mapOf("If-Match" to "\"$rev\""), body = mapOf("beforeEntryId" to entries[0]))
        expectStatus(move, 200, what = "moving the second copy first")
        val moved = playlistItems(pid)
        ensure(moved.path("items").map { it.str("entryId") } == listOf(entries[2], entries[0], entries[1])) { "the move did not put exactly that entry first" }
        ensure(moved.str("revision") != rev) { "a move did not change revision" }
        rev = moved.str("revision")!!
        expectStatus(api("DELETE", "removePlaylistItem", "/playlists/${id(pid)}/items/${id(entries[0])}", headers = mapOf("If-Match" to "\"$rev\"")), 204, what = "removing one copy")
        val after = playlistItems(pid)
        ensure(after.path("items").map { it.str("entryId") } == listOf(entries[2], entries[1])) { "removing one entry removed the wrong one, or renamed entries" }
        ensure(after.str("revision") != rev) { "a removal did not change revision" }
    },
    Check("DA-PL-2", Core, "a walk across an edit answers 409", "README §11") {
        requireFixture()
        val (_, pid) = createPlaylist("conformance walk ${run.runId}", listOf(fixture.track(0), fixture.track(1), fixture.track(2)))
        val page = body(api("GET", "listPlaylistItems", "/playlists/${id(pid)}/items", query = listOf("limit" to "1")))
        val cursor = page.str("nextCursor") ?: fail("a 3-entry playlist at limit=1 gave no nextCursor")
        val rev = page.str("revision")!!
        expectStatus(api("POST", "insertPlaylistItems", "/playlists/${id(pid)}/items", headers = mapOf("If-Match" to "\"$rev\""), body = mapOf("itemIds" to listOf(fixture.track(3)))), 200, what = "inserting")
        val next = api("GET", "listPlaylistItems", "/playlists/${id(pid)}/items", query = listOf("limit" to "1", "cursor" to cursor))
        expectProblem(next, 409, "conflict", what = "continuing a walk after an edit")
    },
    Check("DA-PL-3", Core, "a public playlist is readOnly for another user", "openapi Playlist") {
        requireFixture()
        val b = tokenB
        val (_, pid) = createPlaylist("conformance public ${run.runId}", listOf(fixture.track(0)), visibility = "public")
        val seen = api("GET", "getPlaylist", "/playlists/${id(pid)}", token = b)
        expectStatus(seen, 200, what = "user B reading a public playlist")
        ensure(body(seen).path("readOnly").asBoolean(false)) { "a non-owner sees readOnly: false" }
        ensure(!body(api("GET", "getPlaylist", "/playlists/${id(pid)}")).path("readOnly").asBoolean(true)) { "the owner sees readOnly: true" }
        val (_, priv) = createPlaylist("conformance private ${run.runId}", listOf(fixture.track(0)))
        expectProblem(api("GET", "getPlaylist", "/playlists/${id(priv)}", token = b), 404, "not-found", what = "user B reading a private playlist")
    },
    Check("DA-PL-4", Core, "a deleted playlist answers 404", "openapi deletePlaylist") {
        requireFixture()
        val (_, pid) = createPlaylist("conformance delete ${run.runId}", listOf(fixture.track(0)))
        expectStatus(api("DELETE", "deletePlaylist", "/playlists/${id(pid)}"), 204)
        expectProblem(api("GET", "getPlaylist", "/playlists/${id(pid)}"), 404, "not-found", what = "reading the deleted playlist")
    },

    // Media (README §9)
    Check("DA-MEDIA-1", Core, "a direct plan's URL serves bytes without auth, with Range 206 and 416", "README §9 Signed URLs") {
        requireFixture()
        val plan = directPlan(fixture.track(0))
        val url = plan.str("url")!!
        ensure(!url.contains('?') || !url.substringAfter('?').contains("token", ignoreCase = true)) { "the media URL carries a token in its query" }
        val full = fetchMedia(url)
        expectStatus(full, 200, what = "GET the media URL")
        ensure(full.contentType?.startsWith("audio/") == true || full.contentType == "application/octet-stream") { "media served as ${full.contentType}" }
        ensure(full.body.size > 100) { "the media body is ${full.body.size} bytes" }
        if (plan.path("lengthExact").asBoolean() && plan.path("contentLength").isNumber) ensure(plan.path("contentLength").asLong() == full.body.size.toLong()) { "lengthExact plan said ${plan.path("contentLength")} bytes, served ${full.body.size}" }
        if (plan.path("byteRanges").asBoolean(true)) {
            val part = fetchMedia(url, "bytes=10-109")
            expectStatus(part, 206, what = "Range bytes=10-109")
            ensure(part.body.contentEquals(full.body.copyOfRange(10, 110))) { "the 206 body is not bytes 10-109 of the file" }
            ensure(part.header("Content-Range")?.startsWith("bytes 10-109/") == true) { "Content-Range is ${part.header("Content-Range")}" }
            val beyond = fetchMedia(url, "bytes=${full.body.size + 1000}-")
            expectStatus(beyond, 416, what = "a range past the end")
        }
    },
    Check("DA-MEDIA-2", Core, "a tampered grant answers 403 media-grant-expired", "README §9 Signed URLs") {
        requireFixture()
        val url = directPlan(fixture.track(1)).str("url")!!
        val grant = url.substringAfter("/media/").substringBefore('?')
        val flipped = grant.dropLast(2) + (if (grant.takeLast(2) == "AA") "BB" else "AA")
        expectProblem(fetchMedia(url.replace(grant, flipped)), 403, "media-grant-expired", what = "a tampered grant")
        expectProblem(fetchMedia(url.replace(grant, "conformance-not-a-grant")), 403, "media-grant-expired", what = "an invented grant")
    },
    Check("DA-MEDIA-3", Core, "an expired grant answers 403 media-grant-expired", "README §9 Signed URLs") {
        requireFixture()
        // The Testing profile's mediaUrlTtlSeconds shortens lifetimes of URLs issued from now on.
        val plan = withTestSettings({ it.put("mediaUrlTtlSeconds", 2) }) { directPlan(fixture.track(0)) }
        val expiresAt = java.time.Instant.parse(plan.str("expiresAt")!!)
        ensure(expiresAt <= java.time.Instant.now().plusSeconds(30)) { "with mediaUrlTtlSeconds=2 the plan still expires at $expiresAt" }
        val url = plan.str("url")!!
        expectStatus(fetchMedia(url, "bytes=0-15"), 200, 206, what = "the short-lived URL before it expires")
        Thread.sleep(java.time.Duration.between(java.time.Instant.now(), expiresAt).toMillis().coerceAtLeast(0) + 1500)
        expectProblem(fetchMedia(url, "bytes=0-15"), 403, "media-grant-expired", what = "the URL after its expiresAt")
    },
    Check("DA-MEDIA-4", Core, "artwork serves an image with private immutable caching; a signed artwork URL is stable", "README §6, §9 Artwork") {
        requireFixture()
        val art = (fixture.albums + fixture.tracks).firstNotNullOfOrNull { it.path("artwork").str("id") }
            ?: skip("no catalog item carries artwork; add a library with embedded art to cover this check")
        val ex = api("GET", "getArtwork", "/artwork/${id(art)}", query = listOf("size" to "300"))
        expectStatus(ex, 200)
        ensure(ex.contentType?.startsWith("image/") == true) { "artwork served as ${ex.contentType}" }
        val cc = ex.header("Cache-Control").orEmpty()
        ensure(cc.contains("private") && cc.contains("immutable")) { "artwork Cache-Control is '$cc', expected private, immutable" }
        val req = mapOf("requests" to listOf(mapOf("kind" to "artwork", "id" to art, "size" to 300)))
        val a = api("POST", "createMediaUrls", "/media/urls", body = req)
        val b = api("POST", "createMediaUrls", "/media/urls", body = req)
        expectStatus(a, 200); expectStatus(b, 200)
        ensure(body(a).path("urls")[0]?.str("url") == body(b).path("urls")[0]?.str("url")) { "two signed artwork URLs for one artwork and size differ" }
        expectStatus(fetchMedia(body(a).path("urls")[0].str("url")!!), 200, what = "the signed artwork URL")
    },

    // CORS (README §15)
    Check("DA-CORS-1", Core, "preflight allows the documented request headers", "README §15 CORS") {
        val ex = send("OPTIONS", null, run.apiBase + "/albums", headers = mapOf(
            "Origin" to "https://conformance-client.example",
            "Access-Control-Request-Method" to "GET",
            "Access-Control-Request-Headers" to "authorization,content-type,idempotency-key,if-match,if-none-match,last-event-id,dulcet-device-id,dulcet-action-time",
        ))
        ensure(ex.status in 200..299) { "preflight answered ${ex.status}" }
        val origin = ex.header("Access-Control-Allow-Origin") ?: fail("preflight without Access-Control-Allow-Origin")
        ensure(origin == "*" || origin == "https://conformance-client.example") { "Access-Control-Allow-Origin is $origin" }
        val allowed = ex.headerValues("Access-Control-Allow-Headers").flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()
        val needed = listOf("authorization", "content-type", "idempotency-key", "if-match", "if-none-match", "last-event-id", "dulcet-device-id", "dulcet-action-time")
        val missing = needed.filter { it !in allowed && !(it != "authorization" && "*" in allowed) }
        ensure(missing.isEmpty()) { "preflight does not allow $missing" }
        ensure(ex.header("Access-Control-Allow-Credentials")?.equals("true", true) != true) { "preflight allows credentials (cookies)" }
    },
    Check("DA-CORS-2", Core, "API responses expose the documented response headers", "README §15 CORS") {
        val ex = api("GET", "getCapabilities", "/capabilities", token = null, headers = mapOf("Origin" to "https://conformance-client.example"))
        expectStatus(ex, 200)
        ensure(ex.header("Access-Control-Allow-Origin") != null) { "no Access-Control-Allow-Origin on an API response" }
        val exposed = ex.headerValues("Access-Control-Expose-Headers").flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()
        val missing = listOf("etag", "location", "retry-after", "dulcet-sync-cursor", "deprecation", "sunset").filter { it !in exposed && "*" !in exposed }
        ensure(missing.isEmpty()) { "Access-Control-Expose-Headers lacks $missing" }
    },
)

/** Checks over everything the run saw; they run last. */
fun aggregateChecks(): List<Check> = listOf(
    Check("DA-ERR-1", Core, "every error body is problem+json, and a listed type comes with its status", "README §14") {
        // A status the contract documents with no body (getMedia 416 carries only Content-Range) is judged by its schema, not here.
        val bad = run.exchanges.filter { it.status >= 400 && it.operationId != null && it.operationId !in OAUTH_OPERATIONS }
            .filterNot { it.body.isEmpty() && run.contract.documentsEmptyBody(it.operationId!!, it.status) }
        ensure(bad.isNotEmpty()) { "the run saw no error responses to judge" }
        val wrong = bad.mapNotNull { ex ->
            val type = ex.json?.str("type")
            when {
                ex.contentType != "application/problem+json" -> "${ex.describe()} is ${ex.contentType}"
                type == null || !type.startsWith("urn:dulcet:problem:") -> "${ex.describe()} type $type"
                // The type list is an open set (README §14: an unknown type is handled by its status), so only a
                // listed type answered with a status the table does not give it is wrong.
                PROBLEM_TYPES.filterValues { type.removePrefix("urn:dulcet:problem:") in it }.keys.let { it.isNotEmpty() && ex.status !in it } ->
                    "${ex.describe()} type $type belongs to status ${PROBLEM_TYPES.filterValues { type.removePrefix("urn:dulcet:problem:") in it }.keys}"
                ex.json?.path("status")?.asInt() != ex.status -> "${ex.describe()} body status ${ex.json?.path("status")}"
                else -> null
            }
        }
        ensure(wrong.isEmpty()) { "${wrong.size} of ${bad.size} errors: " + wrong.take(4).joinToString("; ") }
    },
    Check("DA-CORS-3", Core, "no API response sets a cookie", "README §15 CORS") {
        val api = run.exchanges.filter { it.url.startsWith(run.apiBase) }
        ensure(api.isNotEmpty()) { "no API responses seen" }
        val cookies = api.filter { it.headerValues("Set-Cookie").isNotEmpty() }
        ensure(cookies.isEmpty()) { "${cookies.size} API responses set a cookie, e.g. ${cookies.first().describe()}" }
    },
    Check("DA-LOG-1", Core, "no response carries a secret outside the response that issued it", "README §15") {
        val tokenIssuers = setOf("requestToken", "authorize", "createPersonalToken", "requestDeviceAuthorization", "createWebhook")
        val grantIssuers = setOf("createPlaybackDecision", "createMediaUrls")
        val secrets = run.secrets.all()
        val leaks = mutableListOf<String>()
        for (ex in run.exchanges) {
            val text = ex.text() + "\n" + ex.headers.entries.joinToString("\n") { (k, v) -> "$k: ${v.joinToString()}" }
            for ((value, kind) in secrets) {
                if (!text.contains(value)) continue
                val allowed = when (kind) {
                    SecretKind.TOKEN -> ex.operationId in tokenIssuers
                    SecretKind.GRANT -> ex.operationId in grantIssuers
                    else -> false
                }
                if (!allowed) leaks += "${ex.describe()} carries a ${kind.name.lowercase()}"
            }
        }
        ensure(leaks.isEmpty()) { leaks.distinct().take(5).joinToString("; ") }
    },
    Check("DA-LOG-2", Core, "the server's log holds no token, canary or media grant", "README §15 No credential reaches a log") {
        val log = config.serverLog?.let { file ->
            val bytes = java.nio.file.Files.readAllBytes(file)
            val from = if (run.serverLogStart in 1..bytes.size.toLong()) run.serverLogStart.toInt() else 0
            String(bytes, from, bytes.size - from, Charsets.UTF_8)
        } ?: config.serverLogCommand?.let { command ->
            // The operator's own capture command (e.g. `docker logs NAME`); stdout and stderr both count.
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes()
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); fail("--server-log-command did not finish within 120 s") }
            ensure(process.exitValue() == 0) { "--server-log-command exited ${process.exitValue()}" }
            String(output, Charsets.UTF_8)
        } ?: skip("pass --server-log <file> or --server-log-command <cmd> (the test server's log) to scan it for canaries")
        ensure(log.isNotBlank()) { "the server log is empty, so the scan proves nothing; point --server-log at the log the server writes" }
        val found = run.secrets.all().filter { (value, _) -> log.contains(value) }
        ensure(found.isEmpty()) { "the server log contains ${found.size} secrets: ${found.values.groupingBy { it }.eachCount()}" }
    },
)
