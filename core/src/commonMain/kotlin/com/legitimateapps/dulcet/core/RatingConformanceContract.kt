package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * CONF-84's rating half against a real, disposable server (spec §16.20, §18.3). The reader and its
 * favourites are internal to the core, so the conformance module reaches them through this contract,
 * the pattern [PlaylistConformanceContract] follows: the PRODUCTION session on its own reader thread,
 * over the production transport, returns plain observations, and the tests assert on them.
 *
 * Every rating is read back with a raw `getAlbum` that shares nothing with the session but the
 * server. The track rated is left unrated in `finally`, whatever the run did to it.
 */
public data class RatingConformanceRequest(
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "RatingConformanceRequest(<redacted>)"
}

/** What one rating change looked like, from the tap to the server's own record of it. */
public data class RatingChangeObservation(
    val change: String,
    /** The value the change set; 0 removes the rating. */
    val value: Int,
    /** The track's rating in the first publication after the change, or null if none came. */
    val firstPublicationAfterChange: Int?,
    /** `setRating` requests sent before that publication was delivered: 0 means it came first. */
    val sendsBeforeFirstPublication: Int,
    /** How the change ended: `Saved`, `NotSaved`, `Superseded`, … */
    val outcome: String,
    /** The value the session's `Saved` outcome carried, when it saved. */
    val savedValue: Int?,
    /** The `setRating` requests the change produced, as their `rating` parameters, in order. */
    val sends: List<String>,
    /** The server's `userRating` for the track afterwards, read raw (0 when it reports none). */
    val serverRating: Int?,
    /** The track's rating in the last publication once the change ended. */
    val publishedAfterOutcome: Int?,
)

public data class RatingRoundTripResult(
    /** The track's rating on the server before the run, read raw; the control sets it to 0 first. */
    val serverRatingBefore: Int?,
    /** Rate 4, online. */
    val rate: RatingChangeObservation,
    /** Offline, rate 5 then 2; reconnect. Compaction: one send, of 2. */
    val compacted: RatingChangeObservation,
    /** No `setRating` was sent while the reader was offline. */
    val offlineSends: Int,
    /** Rate 0: the rating is removed. */
    val cleared: RatingChangeObservation,
)

public object RatingConformanceContract {
    public suspend fun roundTrip(request: RatingConformanceRequest): RatingRoundTripResult {
        val dispatcher = newLibraryReaderDispatcher()
        val database = createLibrarySyncControlDatabase()
        val raw = KtorLibraryEndpointTransport(
            LibraryBrowseRequest("conformance-raw", request.normalizedBaseUrl, request.username, request.password, request.allowLocalHttp),
            null, null, systemHostResolver(),
        )
        val transport = KtorLibraryEndpointTransport(
            LibraryBrowseRequest("conformance-ratings", request.normalizedBaseUrl, request.username, request.password, request.allowLocalHttp),
            null, null, systemHostResolver(),
        )
        val sends = mutableListOf<String>()
        val recording = object : LibraryEndpointTransport {
            override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
                if (endpoint == "setRating") sends += parameters["rating"].orEmpty()
                return transport.request(endpoint, parameters)
            }

            override suspend fun requestRepeated(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean): LibraryEndpointResponse {
                if (endpoint == "setRating") sends += parameters.firstOrNull { it.first == "rating" }?.second.orEmpty()
                return transport.requestRepeated(endpoint, parameters, formPost)
            }
        }
        var rated: Pair<String, String>? = null
        try {
            val (albumId, trackId) = firstTrack(raw)
            rated = albumId to trackId
            // Control: the run starts from no rating, so a value read afterwards is this run's.
            raw.checkedRequest("setRating", mapOf("id" to trackId, "rating" to "0"))
            val before = serverRating(raw, albumId, trackId)
            return withContext(dispatcher) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                try {
                    val store = SeenCacheStore(database.primary, SeenCacheWallClock { Clock.System.now().toEpochMilliseconds() })
                    val session = LibraryReaderSession(
                        database.primary.database,
                        store.bind(CacheBinding("conformance:${request.username}", request.normalizedBaseUrl, request.username)),
                        recording,
                        scope,
                        LibraryReaderConfig(lookAheadMaxPerViewport = 0),
                        formPost = false, foreground = false,
                    )
                    val outcomes = mutableListOf<MutationOutcome>()
                    session.favourites.addOutcomeListener(outcomes::add)
                    session.reader.connect()
                    val publications = mutableListOf<Pair<LibraryPublication, Int>>()
                    session.reader.open(LibraryQuery.Album(albumId)) { publications += it to sends.size }
                    awaitLive(publications)
                    val target = LibraryEntityRef(LibraryEntityKind.Track, trackId)
                    fun published(): Int? = publications.lastOrNull()?.first?.trackRating(trackId)

                    suspend fun observe(change: String, value: Int, act: suspend () -> Unit): RatingChangeObservation {
                        val markPublications = publications.size
                        val markSends = sends.size
                        val markOutcomes = outcomes.size
                        act()
                        val first = publications.drop(markPublications).firstOrNull()
                        val outcome = awaitOutcome(outcomes, markOutcomes)
                        // The acknowledgement republishes; let it land before the last value is read.
                        delay(SETTLE_MILLIS)
                        return RatingChangeObservation(
                            change = change,
                            value = value,
                            firstPublicationAfterChange = first?.first?.trackRating(trackId),
                            sendsBeforeFirstPublication = first?.second?.minus(markSends) ?: -1,
                            outcome = outcome?.let(::outcomeName) ?: "none",
                            savedValue = (outcome as? MutationOutcome.Saved)?.value,
                            sends = sends.drop(markSends),
                            serverRating = serverRating(raw, albumId, trackId),
                            publishedAfterOutcome = published(),
                        )
                    }

                    val rate = observe("rate", 4) { session.favourites.setRating(target, 4) }

                    // Offline first, and settled, so the next publication is the change's own.
                    session.setOnline(false)
                    delay(SETTLE_MILLIS)
                    var offlineSends = 0
                    val compacted = observe("offline 5 then 2", 2) {
                        val mark = sends.size
                        session.favourites.setRating(target, 5)
                        session.favourites.setRating(target, 2)
                        delay(SETTLE_MILLIS)
                        offlineSends = sends.size - mark
                        // Reachable again: the reconnect flushes the outbox before it reads (§16.14).
                        session.setOnline(true)
                        session.reader.reconnect()
                    }

                    val cleared = observe("clear", 0) { session.favourites.setRating(target, 0) }
                    RatingRoundTripResult(before, rate, compacted, offlineSends, cleared)
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            rated?.let { (_, trackId) -> runCatching { raw.checkedRequest("setRating", mapOf("id" to trackId, "rating" to "0")) } }
            raw.close()
            transport.close()
            database.close()
            dispatcher.close()
        }
    }

    /** The first track of the first album, alphabetically: any track the corpus has. */
    private suspend fun firstTrack(raw: LibraryEndpointTransport): Pair<String, String> {
        val albums = parseReaderAlbumList(raw.checkedRequest("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "5")))
        for (album in albums) {
            val track = parseReaderAlbum(raw.checkedRequest("getAlbum", mapOf("id" to album.rawId)), album.rawId).second.firstOrNull()
            if (track != null) return album.rawId to track.rawId
        }
        error("the conformance corpus has no album with a track")
    }

    /** The server's `userRating` for [trackId], read raw; 0 when the server reports none. */
    private suspend fun serverRating(raw: LibraryEndpointTransport, albumId: String, trackId: String): Int? =
        parseReaderAlbum(raw.checkedRequest("getAlbum", mapOf("id" to albumId)), albumId).second
            .firstOrNull { it.rawId == trackId }?.userState?.userRating

    private fun LibraryPublication.trackRating(trackId: String): Int? =
        items.filterIsInstance<LibraryItem.Track>().firstOrNull { it.rawId == trackId }?.userRating

    private suspend fun awaitLive(publications: List<Pair<LibraryPublication, Int>>) {
        repeat(AWAIT_POLLS) {
            val last = publications.lastOrNull()?.first
            if (last?.freshness == LibraryFreshness.Live && last.items.isNotEmpty()) return
            delay(AWAIT_POLL_MILLIS)
        }
        error("the album never published live: ${publications.lastOrNull()?.first?.freshness}")
    }

    private suspend fun awaitOutcome(outcomes: List<MutationOutcome>, mark: Int): MutationOutcome? {
        repeat(AWAIT_POLLS) {
            outcomes.drop(mark).lastOrNull()?.let { return it }
            delay(AWAIT_POLL_MILLIS)
        }
        return null
    }

    private fun outcomeName(outcome: MutationOutcome): String = outcome::class.simpleName ?: "unknown"

    private const val AWAIT_POLLS = 200
    private const val AWAIT_POLL_MILLIS = 50L
    private const val SETTLE_MILLIS = 300L
}
