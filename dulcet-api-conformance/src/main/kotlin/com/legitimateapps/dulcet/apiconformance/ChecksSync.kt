package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.legitimateapps.dulcet.apiconformance.Profile.Sync
import java.util.UUID

class FeedRead(val changes: List<JsonNode>, val cursor: String, val phases: List<String>)

/** Reads the feed from [start] or [cursor] until `hasMore` is false. */
fun Ctx.feed(token: String = tokenA, cursor: String? = null, start: String? = null, types: String? = null, limit: Int = 200, maxPages: Int = 500): FeedRead {
    val changes = mutableListOf<JsonNode>()
    val phases = mutableListOf<String>()
    var next: String? = cursor
    var first = true
    repeat(maxPages) {
        val q = mutableListOf<Pair<String, String?>>("limit" to limit.toString(), "types" to types)
        if (first && start != null) q += "start" to start else q += "cursor" to next
        val ex = api("GET", "listChanges", "/sync/changes", token = token, query = q)
        expectStatus(ex, 200, what = "reading the change feed")
        val b = body(ex)
        b.path("changes").forEach { changes.add(it) }
        b.str("phase")?.let { phases += it }
        next = b.str("nextCursor") ?: fail("a feed page without nextCursor")
        first = false
        if (!b.path("hasMore").asBoolean()) return FeedRead(changes, next!!, phases)
    }
    fail("the feed still had more after $maxPages pages")
}

fun Ctx.startNow(token: String = tokenA, types: String? = null): String {
    val read = feed(token, start = "now", types = types)
    ensure(read.changes.isEmpty()) { "start=now returned ${read.changes.size} changes" }
    return read.cursor
}

private fun List<JsonNode>.about(type: String, id: String) = filter { it.str("type") == type && it.str("id") == id }

fun Ctx.requireLibrary(): LibraryFixture {
    requireFixture()
    adminToken
    val root = config.libraryDir ?: skip("pass --library-dir <dir> (a folder the disposable server scans) and DULCET_ADMIN_TOKEN to cover catalog changes")
    return run.library ?: LibraryFixture(root, run.runId).also { lib ->
        run.library = lib
        run.cleanups += "remove the suite's library files" to {
            lib.removeAll()
            runCatching { lib.scan(this, config.scanTimeoutSeconds) }
            Unit
        }
    }
}

private fun Ctx.findTrackByTitle(title: String): JsonNode? {
    val ex = api("GET", "search", "/search", query = listOf("q" to title, "types" to "track"))
    expectStatus(ex, 200)
    return body(ex).path("tracks").path("items").firstOrNull { it.str("title") == title }
        ?: walk("/tracks", "listTracks", query = listOf("limit" to "500", "sort" to "-addedAt"), maxPages = 20).firstOrNull { it.str("title") == title }
}

fun syncChecks(): List<Check> = listOf(
    Check("DA-SYNC-1", Sync, "start=now returns no changes and a usable cursor", "README §7 Three ways in") {
        requires("DA-PRE-1")
        val cursor = startNow()
        val again = feed(cursor = cursor)
        ensure(again.changes.isEmpty()) { "a cursor from start=now, read at once, returned ${again.changes.size} changes" }
    },
    Check("DA-SYNC-2", Sync, "start=snapshot delivers every visible catalog entity, then the changes phase", "README §7 Three ways in") {
        requireFixture()
        val read = feed(start = "snapshot", types = "artist,album,track", limit = 100)
        val firstChanges = read.phases.indexOf("changes")
        ensure(read.phases.isNotEmpty() && read.phases.last() == "changes") { "the snapshot did not end in the changes phase: ${read.phases.distinct()}" }
        ensure(firstChanges < 0 || read.phases.drop(firstChanges).none { it == "snapshot" }) { "a snapshot page came after a changes page" }
        val upserts = read.changes.filter { it.str("op") == "upsert" }
        for ((type, expected) in listOf("album" to fixture.albums, "track" to fixture.tracks, "artist" to fixture.artists)) {
            val got = upserts.filter { it.str("type") == type }.map { it.str("id") }.toSet()
            val want = expected.ids().toSet()
            ensure(want.all { it in got }) { "the snapshot is missing ${want.count { it !in got }} of ${want.size} ${type}s the catalog lists" }
        }
        val other = read.changes.mapNotNull { it.str("type") }.filter { it !in setOf("artist", "album", "track") }.distinct()
        ensure(other.isEmpty()) { "types=artist,album,track delivered $other" }
    },
    Check("DA-SYNC-3", Sync, "item-state writes arrive as itemState upserts", "README §7 The model, §8") {
        requireFixture()
        val t = fixture.track(1)
        val cursor = startNow()
        try {
            expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(t)}"), 200)
            expectStatus(api("PUT", "setRating", "/me/ratings/${id(t)}", body = mapOf("rating" to 6)), 200)
            expectStatus(api("PUT", "addToMyLibrary", "/me/library/${id(t)}"), 200)
            val read = feed(cursor = cursor)
            val last = read.changes.about("itemState", t).lastOrNull() ?: fail("no itemState change for the favourited track")
            ensure(last.str("op") == "upsert") { "itemState change op ${last.str("op")}" }
            val r = last.path("resource")
            ensure(!r.path("favoritedAt").isNull && r.path("rating").asInt() == 6 && !r.path("libraryAddedAt").isNull) { "the itemState resource does not show the favourite, rating and membership: $r" }
            api("DELETE", "removeFromMyLibrary", "/me/library/${id(t)}")
            val removed = feed(cursor = read.cursor).changes.about("itemState", t).lastOrNull() ?: fail("no itemState change for a library removal")
            ensure(removed.path("resource").path("libraryAddedAt").isNull) { "a library removal did not arrive as libraryAddedAt: null" }
        } finally {
            api("DELETE", "removeFavorite", "/me/favorites/${id(t)}")
            api("DELETE", "clearRating", "/me/ratings/${id(t)}")
            api("DELETE", "removeFromMyLibrary", "/me/library/${id(t)}")
        }
    },
    Check("DA-SYNC-4", Sync, "a cursor is replayable", "README §7 Feed rules") {
        requireFixture()
        val t = fixture.track(2)
        val cursor = startNow()
        try {
            expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(t)}"), 200)
            val a = feed(cursor = cursor).changes.map { it.str("type") to it.str("id") }.toSet()
            val b = feed(cursor = cursor).changes.map { it.str("type") to it.str("id") }.toSet()
            ensure(a.isNotEmpty()) { "the feed did not carry the favourite" }
            ensure(b.containsAll(a)) { "sending the same cursor again lost ${a.size - a.intersect(b).size} entities" }
        } finally {
            api("DELETE", "removeFavorite", "/me/favorites/${id(t)}")
        }
    },
    Check("DA-SYNC-5", Sync, "a cursor sent with different types answers 400 cursor-types-mismatch", "README §7 Feed rules") {
        requires("DA-PRE-1")
        val cursor = startNow(types = "album")
        val ex = api("GET", "listChanges", "/sync/changes", query = listOf("cursor" to cursor, "types" to "track"))
        expectProblem(ex, 400, "cursor-types-mismatch", what = "a types=album cursor sent with types=track")
        val widened = api("GET", "listChanges", "/sync/changes", query = listOf("cursor" to cursor))
        expectProblem(widened, 400, "cursor-types-mismatch", what = "a types=album cursor sent without types")
    },
    Check("DA-SYNC-6", Sync, "a type the token cannot read answers 403", "README §7 Feed rules") {
        requires("DA-PRE-1")
        val catalogOnly = mintToken(listOf("catalog:read"))
        val ex = api("GET", "listChanges", "/sync/changes", token = catalogOnly.secret, query = listOf("start" to "now", "types" to "itemState"))
        expectProblem(ex, 403, "insufficient-scope", what = "types=itemState with catalog:read only")
        val userOnly = mintToken(listOf("user:read"))
        val ex2 = api("GET", "listChanges", "/sync/changes", token = userOnly.secret, query = listOf("start" to "now", "types" to "album"))
        expectProblem(ex2, 403, "insufficient-scope", what = "types=album with user:read only")
    },
    Check("DA-SYNC-7", Sync, "reads carry a Dulcet-Sync-Cursor the feed accepts", "README §7 The model") {
        requireFixture()
        val t = fixture.track(3)
        val read = api("GET", "listFavorites", "/me/favorites")
        expectStatus(read, 200)
        val cursor = read.header("Dulcet-Sync-Cursor") ?: fail("GET /me/favorites carries no Dulcet-Sync-Cursor")
        ensure(api("GET", "listAlbums", "/albums", query = listOf("limit" to "1")).header("Dulcet-Sync-Cursor") != null) { "GET /albums carries no Dulcet-Sync-Cursor" }
        try {
            expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(t)}"), 200)
            val changes = feed(cursor = cursor).changes
            ensure(changes.about("itemState", t).isNotEmpty()) { "the feed from a read's Dulcet-Sync-Cursor missed a later favourite" }
        } finally {
            api("DELETE", "removeFavorite", "/me/favorites/${id(t)}")
        }
    },
    Check("DA-SYNC-8", Sync, "playlist edits and deletes arrive; a delete is a tombstone with reason deleted", "README §7") {
        requireFixture()
        val cursor = startNow()
        val (_, pid) = createPlaylist("conformance sync ${run.runId}", listOf(fixture.track(0)))
        val created = feed(cursor = cursor)
        ensure(created.changes.about("playlist", pid).any { it.str("op") == "upsert" }) { "creating a playlist produced no playlist upsert" }
        expectStatus(api("DELETE", "deletePlaylist", "/playlists/${id(pid)}"), 204)
        val gone = feed(cursor = created.cursor).changes.about("playlist", pid).lastOrNull() ?: fail("deleting a playlist produced no change")
        ensure(gone.str("op") == "delete" && gone.str("reason") == "deleted") { "playlist delete arrived as op=${gone.str("op")} reason=${gone.str("reason")}" }
    },
    Check("DA-SYNC-9", Sync, "a play arrives in the feed", "README §7") {
        requireFixture()
        val t = fixture.track(3)
        val cursor = startNow()
        val playId = UUID.randomUUID().toString()
        expectStatus(api("POST", "submitPlays", "/me/plays", body = mapOf("plays" to listOf(playBody(playId, t)))), 200)
        val changes = feed(cursor = cursor).changes
        ensure(changes.about("play", playId).any { it.str("op") == "upsert" }) { "a recorded play produced no play upsert" }
        ensure(changes.about("itemState", t).isNotEmpty()) { "a recorded play did not update the track's itemState (playCount)" }
    },
    Check("DA-SYNC-10", Sync, "user A's changes never reach user B's feed", "CONFORMANCE.md Sync") {
        requireFixture()
        val b = tokenB
        val t = fixture.track(1)
        val cursorB = startNow(token = b)
        try {
            expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(t)}"), 200)
            val (_, pid) = createPlaylist("conformance isolation ${run.runId}", listOf(t))
            val changes = feed(token = b, cursor = cursorB).changes
            ensure(changes.about("itemState", t).isEmpty()) { "user B's feed carries user A's item state" }
            ensure(changes.about("playlist", pid).isEmpty()) { "user B's feed carries user A's private playlist" }
        } finally {
            api("DELETE", "removeFavorite", "/me/favorites/${id(t)}")
        }
    },
    Check("DA-SYNC-11", Sync, "a cursor from another instance answers 410 cursor-expired", "README §7 Cursor too old, or another timeline") {
        val otherUrl = config.otherInstanceUrl ?: skip("set DULCET_OTHER_INSTANCE_URL and DULCET_OTHER_INSTANCE_TOKEN (a second disposable instance) to cover foreign cursors")
        val otherToken = config.otherInstanceToken ?: skip("DULCET_OTHER_INSTANCE_TOKEN is not set")
        val otherBase = java.net.URI(otherUrl.trimEnd('/')).let { "${it.scheme}://${it.rawAuthority}/dulcet/v1" }
        val foreign = send("GET", "listChanges", "$otherBase/sync/changes?start=now", token = otherToken)
        expectStatus(foreign, 200, what = "start=now on the other instance")
        val cursor = body(foreign).str("nextCursor")!!
        expectProblem(api("GET", "listChanges", "/sync/changes", query = listOf("cursor" to cursor)), 410, "cursor-expired", what = "a cursor from another instance")
    },
    Check("DA-SYNC-12", Sync, "a catalog add arrives as an upsert after a scan", "CONFORMANCE.md Sync") {
        val lib = requireLibrary()
        val title = "Conformance Add ${run.runId}"
        val cursor = startNow()
        val path = lib.writeTrack("add-${run.runId}.mp3", title, "Conformance Artist ${run.runId}", "Conformance Album ${run.runId}", 1)
        run.libraryPaths["add"] = path
        lib.scan(this, config.scanTimeoutSeconds)
        val track = findTrackByTitle(title) ?: fail("after a scan, the added track is not in the catalog")
        run.libraryIds["add"] = track.str("id")!!
        val changes = feed(cursor = cursor).changes
        ensure(changes.about("track", track.str("id")!!).any { it.str("op") == "upsert" }) { "the added track did not arrive as a track upsert" }
    },
    Check("DA-SYNC-13", Sync, "a retag keeps the track id and arrives as an upsert", "CONFORMANCE.md Core Ids, Sync") {
        val lib = requireLibrary()
        val path = run.libraryPaths["add"] ?: skip("needs the track added by DA-SYNC-12")
        val trackId = run.libraryIds["add"]!!
        val cursor = startNow()
        val title = "Conformance Retag ${run.runId}"
        lib.retag(path, title, "Conformance Artist ${run.runId}", "Conformance Album ${run.runId}", 1)
        lib.scan(this, config.scanTimeoutSeconds)
        ensure(track(trackId).str("title") == title) { "after a retag, the track id no longer reads with the new title (id not kept)" }
        val upsert = feed(cursor = cursor).changes.about("track", trackId).lastOrNull() ?: fail("the retag produced no track change")
        ensure(upsert.str("op") == "upsert" && upsert.path("resource").str("title") == title) { "the retag arrived as op=${upsert.str("op")}, title ${upsert.path("resource").str("title")}" }
    },
    Check("DA-SYNC-14", Sync, "a catalog delete arrives as a tombstone with reason deleted", "README §7, CONFORMANCE.md Sync") {
        val lib = requireLibrary()
        val path = run.libraryPaths["add"] ?: skip("needs the track added by DA-SYNC-12")
        val trackId = run.libraryIds["add"]!!
        val cursor = startNow()
        lib.delete(path)
        lib.scan(this, config.scanTimeoutSeconds)
        val tomb = feed(cursor = cursor).changes.about("track", trackId).lastOrNull() ?: fail("deleting the file produced no change for its track")
        ensure(tomb.str("op") == "delete" && tomb.str("reason") == "deleted") { "the delete arrived as op=${tomb.str("op")} reason=${tomb.str("reason")}" }
        val read = api("GET", "getTrack", "/tracks/${id(trackId)}")
        ensure(read.status == 404 || read.status == 410) { "the deleted track still reads as ${read.status}" }
    },
    Check("DA-SYNC-15", Sync, "taking a source away produces hidden tombstones; giving it back produces upserts", "README §7 Feed rules") {
        requireFixture()
        val admin = adminToken
        val b = tokenB
        val bUser = me(b)
        val bId = bUser.str("id")!!
        val original = bUser.path("sourceIds").map { it.asText() }
        if (original.isEmpty()) skip("user B's sourceIds is empty, which means no sources, so there is nothing to hide; give user B at least one source")
        val removed = original.first()
        val hiddenTracks = walk("/tracks", "listTracks", token = b, query = listOf("sourceId" to removed, "limit" to "500")).ids()
        ensure(hiddenTracks.isNotEmpty()) { "user B sees no tracks in source $removed" }
        val cursor = startNow(token = b, types = "track")
        val patch = api("PATCH", "updateUser", "/users/${id(bId)}", token = admin, body = mapOf("sourceIds" to original.drop(1)))
        expectStatus(patch, 200, what = "taking source $removed from user B")
        try {
            val ex = api("GET", "listChanges", "/sync/changes", token = b, query = listOf("cursor" to cursor, "types" to "track", "limit" to "500"))
            if (ex.status == 410) {
                expectProblem(ex, 410, "cursor-expired")
            } else {
                val changes = feed(token = b, cursor = cursor, types = "track").changes
                val hidden = changes.filter { it.str("op") == "delete" && it.str("reason") == "hidden" }.map { it.str("id") }.toSet()
                ensure(hiddenTracks.all { it in hidden }) { "${hiddenTracks.count { it !in hidden }} of ${hiddenTracks.size} tracks no longer visible got no hidden tombstone" }
                val after = feed(token = b, cursor = cursor, types = "track").cursor
                expectStatus(api("PATCH", "updateUser", "/users/${id(bId)}", token = admin, body = mapOf("sourceIds" to original)), 200)
                val back = feed(token = b, cursor = after, types = "track").changes.filter { it.str("op") == "upsert" }.map { it.str("id") }.toSet()
                ensure(hiddenTracks.all { it in back }) { "giving the source back did not upsert ${hiddenTracks.count { it !in back }} tracks" }
                // An empty list means no sources: the user sees no catalog (openapi User.sourceIds).
                expectStatus(api("PATCH", "updateUser", "/users/${id(bId)}", token = admin, body = mapOf("sourceIds" to emptyList<String>())), 200)
                val none = walk("/tracks", "listTracks", token = b, query = listOf("limit" to "500"))
                ensure(none.isEmpty()) { "with sourceIds [] user B still sees ${none.size} tracks" }
            }
        } finally {
            api("PATCH", "updateUser", "/users/${id(bId)}", token = admin, body = mapOf("sourceIds" to original))
        }
    },
    Check("DA-SYNC-16", Sync, "a merge carries user state, then a tombstone with replacedBy", "README §7 Merges carry the user's data; openapi Album merges") {
        val lib = requireLibrary()
        val artist = "Conformance Merge Artist ${run.runId}"
        val release = UUID.randomUUID().toString()
        val titleA = "Conformance Merge A ${run.runId}"
        val titleB = "Conformance Merge B ${run.runId}"
        val albumNameB = "Conformance Merge Album B ${run.runId}"
        lib.writeTrack("merge-a-${run.runId}.mp3", titleA, artist, "Conformance Merge Album A ${run.runId}", 1, release)
        lib.scan(this, config.scanTimeoutSeconds)
        val albumA = findTrackByTitle(titleA)?.path("album")?.str("id") ?: fail("after a scan, the first merge track is not in the catalog")
        val pathB = lib.writeTrack("merge-b-${run.runId}.mp3", titleB, artist, albumNameB, 1, UUID.randomUUID().toString())
        lib.scan(this, config.scanTimeoutSeconds)
        val albumB = findTrackByTitle(titleB)?.path("album")?.str("id") ?: fail("after a scan, the second merge track is not in the catalog")
        ensure(albumA != albumB) { "two albums with different MusicBrainz release ids were made one album" }
        expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(albumB)}"), 200, what = "favouriting the album that will be merged away")
        val cursor = startNow()
        // Giving the second album the first one's release id makes them one album; the first-issued id survives.
        lib.retag(pathB, titleB, artist, albumNameB, 1, release)
        lib.scan(this, config.scanTimeoutSeconds)
        val changes = feed(cursor = cursor).changes
        val tombIndex = changes.indexOfLast { it.str("type") == "album" && it.str("id") == albumB && it.str("op") == "delete" }
        ensure(tombIndex >= 0) { "the merged-away album got no tombstone" }
        val tomb = changes[tombIndex]
        ensure(tomb.str("reason") == "merged") { "the merged-away album's tombstone has reason ${tomb.str("reason")}, not merged" }
        val replacedBy = tomb.path("replacedBy").map { it.asText() }
        ensure(replacedBy == listOf(albumA)) { "replacedBy is $replacedBy, not the first-issued album [$albumA]" }
        val carried = changes.indexOfFirst { it.str("type") == "itemState" && it.str("id") == albumA && it.path("resource").str("favoritedAt") != null }
        ensure(carried in 0 until tombIndex) { "the favourite was not carried to the surviving album before the tombstone" }
        val survivor = body(api("GET", "getAlbum", "/albums/${id(albumA)}"))
        ensure(survivor.path("userState").str("favoritedAt") != null) { "the surviving album does not carry the favourite" }
        api("DELETE", "removeFavorite", "/me/favorites/${id(albumA)}")
    },
    Check("DA-SYNC-17", Sync, "a cursor older than retention answers 410 cursor-expired", "README §7 Cursor too old; Testing profile syncRetentionSeconds") {
        requireFixture()
        val track = fixture.track(0)
        val cursor = startNow()
        withTestSettings({ it.put("syncRetentionSeconds", 1) }) {
            expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(track)}"), 200, what = "a write after the cursor")
            Thread.sleep(2500)
            // Compaction runs at once and on every later write.
            expectStatus(api("DELETE", "removeFavorite", "/me/favorites/${id(track)}"), 204, what = "a later write")
            val ex = api("GET", "listChanges", "/sync/changes", query = listOf("cursor" to cursor))
            expectProblem(ex, 410, "cursor-expired", what = "a cursor older than syncRetentionSeconds")
        }
    },
    Check("DA-SYNC-18", Sync, "after a restore every earlier cursor, ETag and revision is refused and no id is reissued", "README §7 Restores; Testing profile checkpoints") {
        requireFixture()
        val admin = adminToken
        fun instanceId(): String = body(send("GET", "getDiscovery", run.origin + "/.well-known/dulcet")).path("server").str("instanceId")!!
        val before = instanceId()
        val (created, pid) = createPlaylist("conformance restore ${run.runId}", listOf(fixture.track(0)))
        val playlistEtag = created.header("ETag") ?: fail("creating a playlist returned no ETag")
        val albumEtag = api("GET", "getAlbum", "/albums/${id(fixture.album(0))}").header("ETag") ?: fail("album detail has no ETag")
        val cursor = startNow()
        val ckpt = api("POST", "createTestCheckpoint", "/admin/test/checkpoints", token = admin)
        expectStatus(ckpt, 201, what = "saving a checkpoint")
        ensure(body(ckpt).str("instanceId") == before) { "the checkpoint names instance ${body(ckpt).str("instanceId")}, not $before" }
        val (_, lateId) = createPlaylist("conformance after checkpoint ${run.runId}", emptyList())
        val restored = api("POST", "restoreTestCheckpoint", "/admin/test/checkpoints/${id(body(ckpt).str("id")!!)}/restore", token = admin)
        expectStatus(restored, 200, what = "restoring the checkpoint")
        // Overrides live in memory; put the suite's back in case the restore cleared them.
        if (run.testSettings.size() > 0) setTestSettings { }
        val after = instanceId()
        ensure(after != before && body(restored).str("instanceId") == after) { "the restore did not change instanceId ($before -> $after)" }
        expectProblem(api("GET", "listChanges", "/sync/changes", query = listOf("cursor" to cursor)), 410, "cursor-expired", what = "a cursor from before the restore")
        val album = api("GET", "getAlbum", "/albums/${id(fixture.album(0))}", headers = mapOf("If-None-Match" to albumEtag))
        ensure(album.status == 200) { "an album ETag from before the restore answered ${album.status}, not 200" }
        val playlist = api("GET", "getPlaylist", "/playlists/${id(pid)}", headers = mapOf("If-None-Match" to playlistEtag))
        ensure(playlist.status == 200) { "a playlist ETag from before the restore answered ${playlist.status}, not 200" }
        expectProblem(api("PATCH", "updatePlaylist", "/playlists/${id(pid)}", headers = mapOf("If-Match" to playlistEtag), body = mapOf("name" to "stale")),
            412, "revision-mismatch", what = "If-Match with a revision from before the restore")
        expectProblem(api("GET", "getPlaylist", "/playlists/${id(lateId)}"), 404, "not-found", what = "a playlist created after the checkpoint")
        val (_, newId) = createPlaylist("conformance after restore ${run.runId}", emptyList())
        ensure(newId != lateId) { "the restore reissued playlist id $lateId" }
    },
)
