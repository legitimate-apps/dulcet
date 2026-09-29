package com.legitimateapps.dulcet.core

/**
 * An in-process server with the playlist semantics MEASURED on the reference server (spec §18.6,
 * CONF-88) — so the editor's tests run against the behaviour it has to survive, not a kinder one:
 *
 * - `updatePlaylist?songIndexToRemove=` applies every index to the list as it was when the request
 *   arrived; an index outside it is silently ignored; the answer is an empty `ok`.
 * - `songIdToAdd` appends; an id the server does not know is silently dropped.
 * - `createPlaylist` with `playlistId` replaces the entries and keeps the id, name, comment and
 *   visibility; with no `songId` at all it changes nothing.
 * - `updatePlaylist` with an empty `comment` clears it; an empty `name` is ignored.
 * - another user's playlist is `readonly: true`; editing it answers code 50; a private one of
 *   another user is code 70 to a non-admin.
 *
 * Every request is logged with its parameters in order. [beforeWrite] runs after a write request is
 * received and before it is applied: another client racing the write.
 */
internal class FakePlaylistServer(
    val user: String = "listener",
    val songs: MutableSet<String> = (1..3000).map { "song-$it" }.toMutableSet(),
    /** The server's clock, epoch milliseconds: the `created` stamp of every playlist it makes. */
    var now: () -> Long = { 0L },
) : LibraryEndpointTransport {
    data class Request(val endpoint: String, val parameters: List<Pair<String, String>>, val formPost: Boolean) {
        fun all(name: String): List<String> = parameters.filter { it.first == name }.map { it.second }
        fun one(name: String): String? = all(name).singleOrNull()
    }

    class Playlist(
        val id: String,
        var name: String,
        val owner: String,
        val entries: MutableList<String> = mutableListOf(),
        var comment: String? = null,
        var isPublic: Boolean = false,
        val created: Long = 0L,
    )

    val playlists = mutableListOf<Playlist>()

    /** The songs `star` has starred and `unstar` has not since unstarred. */
    val starredSongs = mutableSetOf<String>()
    val log = mutableListOf<Request>()
    private var nextId = 1

    /** Endpoint -> the Subsonic error code it answers with, without applying anything. */
    val failWithCode = mutableMapOf<String, Int>()

    /** Endpoint -> a transport failure thrown instead of answering (nothing applied). */
    val failWithError = mutableMapOf<String, DomainError>()

    /** Endpoint -> what the HTTP client itself throws instead of answering (nothing applied). */
    val failWithThrowable = mutableMapOf<String, Throwable>()

    /** Endpoints whose change IS applied but whose answer is lost: the at-least-once case. */
    val applyThenLose = mutableSetOf<String>()

    /** Another client, between a write's arrival and its application. */
    var beforeWrite: (Request) -> Unit = {}

    /** Whether `createPlaylist` answers with the playlist (OpenSubsonic) or an empty `ok`. */
    var createAnswersWithPlaylist = true

    /** A server whose `createPlaylist` answers `ok` and makes nothing. */
    var createIgnored = false

    /** A server that does not send `readonly` at all. */
    var omitReadonly = false

    /** A server that does not say who owns a playlist. */
    var omitOwner = false

    /** A server that answers `ok` to a comment change and does not apply it. */
    var ignoreComment = false

    /** Endpoint -> an HTTP status answered with a non-envelope body, nothing applied (a proxy's 414). */
    val httpStatus = mutableMapOf<String, Int>()

    /** Endpoint -> an HTTP status answered AFTER the change was applied (a gateway timing out on it). */
    val applyThenStatus = mutableMapOf<String, Int>()

    /** The `Retry-After` header sent with every [httpStatus] answer. */
    var retryAfter: String? = null

    /** How `created` is written; by default ISO-8601 in UTC to the millisecond, as a stamp allows. */
    var createdText: (Long) -> String = { kotlin.time.Instant.fromEpochMilliseconds(it).toString() }

    /** Every request's parameter bytes as a query string carries them, encoded by the HTTP client. */
    val urlLengths = mutableListOf<Int>()

    fun playlist(id: String): Playlist = playlists.first { it.id == id }

    fun add(name: String, entries: List<String>, owner: String = user, isPublic: Boolean = false, created: Long = now()): Playlist =
        Playlist("pl-${nextId++}", name, owner, entries.toMutableList(), isPublic = isPublic, created = created).also { playlists += it }

    fun writes(): List<Request> = log.filter { it.endpoint in WRITES }

    fun count(endpoint: String): Int = log.count { it.endpoint == endpoint }

    override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse =
        answer(Request(endpoint, parameters.toList(), formPost = false))

    override suspend fun requestRepeated(
        endpoint: String,
        parameters: List<Pair<String, String>>,
        formPost: Boolean,
    ): LibraryEndpointResponse = answer(Request(endpoint, parameters, formPost))

    private suspend fun answer(request: Request): LibraryEndpointResponse {
        log += request
        urlLengths += queryStringBytes(request.parameters)
        // A real transport suspends: other coroutines may run while a request is out.
        kotlinx.coroutines.yield()
        failWithError[request.endpoint]?.let { throw LibraryRequestFailure(it) }
        failWithThrowable[request.endpoint]?.let { throw it }
        httpStatus[request.endpoint]?.let { return LibraryEndpointResponse(it, "<html>request rejected</html>", "http://fixture.invalid/rest", retryAfter = retryAfter) }
        failWithCode[request.endpoint]?.let { return error(it) }
        if (request.endpoint in WRITES) beforeWrite(request)
        val response = respond(request)
        if (request.endpoint in applyThenLose) throw LibraryRequestFailure(DomainError.Transport.Timeout)
        applyThenStatus[request.endpoint]?.let { return LibraryEndpointResponse(it, "<html>bad gateway</html>", "http://fixture.invalid/rest") }
        return response
    }

    private fun visible(p: Playlist) = p.owner == user || p.isPublic

    private fun respond(request: Request): LibraryEndpointResponse = when (request.endpoint) {
        "ping" -> ok(null)
        // Favourites: a song's star is kept, so a re-read answers what the server holds; the rest is
        // answered and counted only.
        "star" -> ok(null).also { request.all("id").forEach { starredSongs += it } }
        "unstar" -> ok(null).also { request.all("id").forEach { starredSongs -= it } }
        "setRating" -> ok(null)
        "getScanStatus" -> ok(""""scanStatus":{"scanning":false,"count":12,"lastScan":"2026-09-23T10:00:00Z"}""")
        "getMusicFolders" -> ok(""""musicFolders":{"musicFolder":[{"id":"1","name":"Music"}]}""")
        "getAlbum" -> ok(
            """"album":{"id":"album-1","name":"Album One","songCount":3,"song":[${listOf("song-10", "song-11", "song-12").joinToString(",") { songJson(it) }}]}""",
        )
        "getPlaylists" -> ok(""""playlists":{"playlist":[${playlists.filter(::visible).sortedBy { it.name.lowercase() }.joinToString(",") { header(it) }}]}""")
        "getPlaylist" -> {
            val p = playlists.firstOrNull { it.id == request.one("id") }?.takeIf(::visible)
            if (p == null) error(70) else ok(""""playlist":{${header(p).removeSurrounding("{", "}")},"entry":[${p.entries.joinToString(",") { songJson(it) }}]}""")
        }
        "createPlaylist" -> {
            val id = request.one("playlistId")
            val songIds = request.all("songId").filter { it in songs }
            if (id == null) {
                val name = request.one("name")
                if (name.isNullOrEmpty()) {
                    error(0)
                } else if (createIgnored) {
                    ok(null)
                } else {
                    val created = add(name, songIds)
                    if (createAnswersWithPlaylist) ok(""""playlist":${header(created)}""") else ok(null)
                }
            } else {
                val p = playlists.firstOrNull { it.id == id }
                when {
                    p == null || !visible(p) -> error(70)
                    p.owner != user -> error(50)
                    else -> {
                        if (request.all("songId").isNotEmpty()) {
                            p.entries.clear()
                            p.entries += songIds
                        }
                        ok(""""playlist":${header(p)}""")
                    }
                }
            }
        }
        "updatePlaylist" -> {
            val p = playlists.firstOrNull { it.id == request.one("playlistId") }
            when {
                p == null || !visible(p) -> error(70)
                p.owner != user -> error(50)
                else -> {
                    request.one("name")?.takeIf(String::isNotEmpty)?.let { p.name = it }
                    if (!ignoreComment) request.one("comment")?.let { p.comment = it.ifEmpty { null } }
                    request.one("public")?.let { p.isPublic = it == "true" }
                    val remove = request.all("songIndexToRemove").mapNotNull(String::toIntOrNull).toSet()
                    val kept = p.entries.filterIndexed { index, _ -> index !in remove }
                    p.entries.clear()
                    p.entries += kept + request.all("songIdToAdd").filter { it in songs }
                    ok(null)
                }
            }
        }
        "deletePlaylist" -> {
            val p = playlists.firstOrNull { it.id == request.one("id") }
            when {
                p == null || !visible(p) -> error(70)
                p.owner != user -> error(50)
                else -> {
                    playlists -= p
                    ok(null)
                }
            }
        }
        else -> error("unexpected endpoint ${request.endpoint}")
    }

    private fun header(p: Playlist): String = buildString {
        append("""{"id":"${p.id}","name":"${p.name}","songCount":${p.entries.size},"duration":${p.entries.size * 60}""")
        if (!omitOwner) append(""","owner":"${p.owner}"""")
        p.comment?.let { append(""","comment":"$it"""") }
        if (p.isPublic) append(""","public":true""")
        append(",\"created\":\"" + createdText(p.created) + "\"")
        if (!omitReadonly) append(",\"readonly\":" + (p.owner != user))
        append(",\"coverArt\":\"pl-" + p.id + "\"}")
    }

    private fun songJson(id: String): String {
        val starred = if (id in starredSongs) ",\"starred\":\"2026-09-23T10:00:00Z\"" else ""
        return """{"id":"$id","title":"Title $id","album":"Album One","albumId":"album-1","artist":"Artist","artistId":"artist-1","duration":60,"suffix":"flac"$starred}"""
    }

    private fun ok(body: String?) = LibraryEndpointResponse(
        200,
        """{"subsonic-response":{"status":"ok","version":"1.16.1"${body?.let { ",$it" } ?: ""}}}""",
        "http://fixture.invalid/rest",
    )

    private fun error(code: Int) = LibraryEndpointResponse(
        200,
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"refused"}}}""",
        "http://fixture.invalid/rest",
    )

    companion object {
        val WRITES = setOf("createPlaylist", "updatePlaylist", "deletePlaylist")
    }
}
