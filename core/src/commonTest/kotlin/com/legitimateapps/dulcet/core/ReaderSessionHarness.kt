package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * [FakeReaderServer] plus the endpoints search and favourites need: `search3`, `star`, `unstar`,
 * `setRating`. Every request is logged in ISSUE order in [log]. Per-user state the base server does
 * not model (track and artist stars, ratings) is injected into every catalog response it answers.
 */
internal class SessionTestServer(val base: FakeReaderServer = FakeReaderServer()) : LibraryEndpointTransport {
    data class Request(val endpoint: String, val parameters: Map<String, String>)

    val log = mutableListOf<Request>()
    val starredIds = mutableSetOf<String>()
    val ratings = mutableMapOf<String, Int>()

    /** Ids `search3` never returns: the server's matcher disagreeing with the local one (CONF-43). */
    val searchExcludes = mutableSetOf<String>()

    /** Endpoint -> the Subsonic error code it answers with. */
    val failWithCode = mutableMapOf<String, Int>()

    /** Endpoint -> a transport failure thrown instead of answering (no server state changes). */
    val failWithError = mutableMapOf<String, DomainError>()

    /** Endpoints whose change IS applied but whose answer is lost: the at-least-once case. */
    val applyThenLose = mutableSetOf<String>()

    /** Endpoints held BEFORE they are applied, until [release]. */
    val holdBeforeApply = mutableSetOf<String>()

    /** Endpoints answered at once and then held, so the answer is older than its delivery. */
    val holdAfterAnswer = mutableSetOf<String>()

    private val held = mutableListOf<CompletableDeferred<Unit>>()
    val heldCount: Int get() = held.size

    fun release() {
        held.toList().forEach { it.complete(Unit) }
        held.clear()
    }

    fun count(endpoint: String): Int = log.count { it.endpoint == endpoint }

    fun endpoints(): List<String> = log.map { it.endpoint }

    private suspend fun hold() {
        val gate = CompletableDeferred<Unit>()
        held += gate
        gate.await()
    }

    override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
        log += Request(endpoint, parameters)
        failWithError[endpoint]?.let { throw LibraryRequestFailure(it) }
        failWithCode[endpoint]?.let { return envelope(""""error":{"code":$it,"message":"refused"}""") }
        if (endpoint in holdBeforeApply) hold()
        val response = when (endpoint) {
            "search3" -> search(parameters)
            "star", "unstar" -> {
                val starred = endpoint == "star"
                val id = parameters["id"] ?: parameters["albumId"] ?: parameters["artistId"] ?: error("no id")
                val album = base.albums.firstOrNull { it.id == id }
                when {
                    album != null -> album.starred = starred
                    starred -> starredIds += id
                    else -> starredIds -= id
                }
                envelope(null)
            }
            "setRating" -> {
                val rating = parameters.getValue("rating").toInt()
                if (rating == 0) ratings -= parameters.getValue("id") else ratings[parameters.getValue("id")] = rating
                envelope(null)
            }
            else -> inject(base.request(endpoint, parameters))
        }
        if (endpoint in applyThenLose) throw LibraryRequestFailure(DomainError.Transport.Timeout)
        if (endpoint in holdAfterAnswer) hold()
        return response
    }

    private fun search(p: Map<String, String>): LibraryEndpointResponse {
        val query = normalizeSearchText(p.getValue("query"))
        val albums = base.albums.filter { normalizeSearchText(it.name).contains(query) && it.id !in searchExcludes }
            .take(p.getValue("albumCount").toInt())
        val songs = base.albums.flatMap { a -> a.songs.map { a to it } }
            .filter { (_, song) -> normalizeSearchText("Song $song").contains(query) && song !in searchExcludes }
            .take(p.getValue("songCount").toInt())
        val albumJson = albums.joinToString(",") {
            """{"id":"${it.id}","name":"${it.name}","artist":"Artist One","artistId":"artist-1","songCount":${it.songs.size},"duration":120${if (it.starred) ",\"starred\":\"2026-09-22T10:00:00Z\"" else ""}}"""
        }
        val songJson = songs.joinToString(",") { (album, song) ->
            """{"id":"$song","title":"Song $song","album":"${album.name}","albumId":"${album.id}","artist":"Artist One","artistId":"artist-1","duration":60,"suffix":"flac"}"""
        }
        return inject(envelope(""""searchResult3":{"album":[$albumJson],"song":[$songJson]}"""))
    }

    /** Adds the per-user state this class owns to every object with a known id. */
    private fun inject(response: LibraryEndpointResponse): LibraryEndpointResponse {
        if (starredIds.isEmpty() && ratings.isEmpty()) return response
        val root = LIBRARY_JSON.parseToJsonElement(response.body)
        return response.copy(body = walk(root).toString())
    }

    private fun walk(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::walk))
        is JsonObject -> {
            val id = (element["id"] as? JsonPrimitive)?.content
            val fields = element.mapValues { walk(it.value) }.toMutableMap()
            if (id != null && (element.containsKey("name") || element.containsKey("title"))) {
                ratings[id]?.let { fields["userRating"] = JsonPrimitive(it) }
                if (id in starredIds) fields["starred"] = JsonPrimitive("2026-09-22T11:00:00Z")
            }
            JsonObject(fields)
        }
        else -> element
    }

    private fun envelope(body: String?): LibraryEndpointResponse = LibraryEndpointResponse(
        statusCode = 200,
        body = """{"subsonic-response":{"status":"${if (body?.startsWith("\"error\"") == true) "failed" else "ok"}","version":"1.16.1"${body?.let { ",$it" } ?: ""}}}""",
        redactedUrl = "http://fixture.invalid/rest",
    )
}

internal class SessionEnv(
    val server: SessionTestServer,
    val driver: SqlDriver,
    val database: DulcetDatabaseStore,
    val store: SeenCacheStore,
    val clock: ManualWallClock,
    val scope: CoroutineScope,
) {
    /** A new session over the same device database: a relaunch, as far as the core can tell. */
    fun session(
        binding: CacheBinding = BINDING,
        config: LibraryReaderConfig = LibraryReaderConfig(lookAheadMaxPerViewport = 0),
        otherOutboxes: ReconnectOutboxes = ReconnectOutboxes.None,
    ): LibraryReaderSession = LibraryReaderSession(
        database = database.database,
        cache = store.bind(binding),
        transport = server,
        scope = scope,
        config = config,
        otherOutboxes = otherOutboxes,
    )

    fun cache(): BoundSeenCache = store.bind(BINDING)

    companion object {
        val BINDING = CacheBinding("server:session", "https://music.example", "listener")
    }
}

internal fun sessionTest(block: suspend TestScope.(SessionEnv) -> Unit) = runTest {
    val driver = createTestDriver()
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    try {
        val database = DulcetDatabaseStore.open(driver)
        val clock = ManualWallClock(now = 2_000_000)
        block(SessionEnv(SessionTestServer(), driver, database, SeenCacheStore(database, clock), clock, scope))
    } finally {
        scope.cancel()
        driver.close()
    }
}

/** Collects every publication with the request count at delivery. */
internal class Recorder<T>(private val server: SessionTestServer) : (T) -> Unit {
    data class Seen<T>(val value: T, val requestsIssued: Int)

    val all = mutableListOf<Seen<T>>()
    val last: T get() = all.last().value

    override fun invoke(value: T) {
        all += Seen(value, server.log.size)
    }
}

internal fun LibraryPublication.album(rawId: String): LibraryItem.Album =
    items.filterIsInstance<LibraryItem.Album>().first { it.rawId == rawId }
