package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * CONF-84's star half against a real, disposable server (spec §16.20, §18.3), beside
 * [RatingConformanceContract]'s rating half: the PRODUCTION session on its own reader thread, over the
 * production transport, returns plain observations, and the tests assert on them.
 *
 * The `star` send is held at the transport, before it reaches the server, while the screen is
 * re-read: the server answers that re-read with the track not starred, so the overlay alone keeps the
 * star on. The server's own record is read with a raw `getAlbum` that shares nothing with the session
 * but the server. The track starred is unstarred in `finally`, whatever the run did to it.
 */
public data class StarConformanceRequest(
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "StarConformanceRequest(<redacted>)"
}

public data class StarRoundTripResult(
    /** The track's star on the server before the run, read raw; the control unstars it first. */
    val serverStarredBefore: Boolean?,
    /** The track's star in the first publication after the tap, or null if none came. */
    val firstPublicationAfterTap: Boolean?,
    /** Requests of any kind sent between the tap and that publication: 0 means it came first. */
    val requestsBeforeFirstPublication: Int,
    /** The `star` request reached the transport and was held there, not yet sent to the server. */
    val sendHeld: Boolean,
    /** `getAlbum` reads the session sent while the star was held: the revalidation reached the server. */
    val revalidationReads: Int,
    /** The server's star for the track while the send was held, read raw: the re-read's answer. */
    val serverStarredDuringHold: Boolean?,
    /** A live publication was delivered while the send was held. */
    val livePublicationDuringHold: Boolean,
    /** The track's star in every publication from the tap until the outcome; null where absent. */
    val starredFromTapToOutcome: List<Boolean?>,
    /** How the change ended: `Saved`, `NotSaved`, `Superseded`, … */
    val outcome: String,
    /** The value the session's `Saved` outcome carried, when it saved. */
    val savedValue: Int?,
    /** The star-or-unstar requests the change produced, by endpoint, in order. */
    val sends: List<String>,
    /** The server's star for the track afterwards, read raw. */
    val serverStarredAfter: Boolean?,
    /** Pending changes once the outcome landed: 0 means the acknowledged row was removed. */
    val pendingAfterOutcome: Long?,
    /** What the session now holds as the track's star with nothing pending: the adopted echo. */
    val adoptedStarred: Boolean?,
    /** The track's star in the publication of a fresh re-read after the outcome. */
    val publishedAfterReread: Boolean?,
)

public object StarConformanceContract {
    public suspend fun roundTrip(request: StarConformanceRequest): StarRoundTripResult {
        val dispatcher = newLibraryReaderDispatcher()
        val database = createLibrarySyncControlDatabase()
        val raw = KtorLibraryEndpointTransport(
            LibraryBrowseRequest("conformance-raw", request.normalizedBaseUrl, request.username, request.password, request.allowLocalHttp),
            null, null, systemHostResolver(),
        )
        val transport = KtorLibraryEndpointTransport(
            LibraryBrowseRequest("conformance-stars", request.normalizedBaseUrl, request.username, request.password, request.allowLocalHttp),
            null, null, systemHostResolver(),
        )
        val requests = mutableListOf<String>()
        val sends = mutableListOf<String>()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recording = object : LibraryEndpointTransport {
            override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
                requests += endpoint
                if (endpoint == "star" || endpoint == "unstar") {
                    sends += endpoint
                    if (endpoint == "star" && !release.isCompleted) {
                        held.complete(Unit)
                        release.await()
                    }
                }
                return transport.request(endpoint, parameters)
            }

            override suspend fun requestRepeated(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean): LibraryEndpointResponse {
                requests += endpoint
                if (endpoint == "star" || endpoint == "unstar") sends += endpoint
                return transport.requestRepeated(endpoint, parameters, formPost)
            }
        }
        var starred: String? = null
        try {
            val (albumId, trackId) = firstTrack(raw)
            starred = trackId
            // Control: the run starts from no star, so a star read afterwards is this run's.
            raw.checkedRequest("unstar", mapOf("id" to trackId))
            val before = serverStarred(raw, albumId, trackId)
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
                    val handle = session.reader.open(LibraryQuery.Album(albumId)) { publications += it to requests.size }
                    awaitLive(publications)
                    delay(SETTLE_MILLIS)
                    val target = LibraryEntityRef(LibraryEntityKind.Track, trackId)

                    val tapped = publications.size
                    val requestsAtTap = requests.size
                    session.favourites.setFavourite(target, true)
                    val first = publications.drop(tapped).firstOrNull()
                    val sendHeld = awaitCompleted(held)

                    // The send is held before the server; re-read the screen, which the server answers
                    // with the track not starred.
                    val readsMark = requests.size
                    val holdMark = publications.size
                    handle.refresh()
                    awaitLiveAfter(publications, holdMark)
                    val revalidationReads = requests.drop(readsMark).count { it == "getAlbum" }
                    val serverDuringHold = serverStarred(raw, albumId, trackId)
                    val liveDuringHold = publications.drop(holdMark).any { it.first.freshness == LibraryFreshness.Live }

                    release.complete(Unit)
                    val outcome = awaitOutcome(outcomes, 0)
                    // The acknowledgement republishes; let it land before the frames are read.
                    delay(SETTLE_MILLIS)
                    val fromTap = publications.drop(tapped).map { it.first.trackStarred(trackId) }
                    val pending = session.favourites.pendingCount()
                    val adopted = session.favourites.isFavourite(target)

                    val rereadMark = publications.size
                    handle.refresh()
                    awaitLiveAfter(publications, rereadMark)

                    StarRoundTripResult(
                        serverStarredBefore = before,
                        firstPublicationAfterTap = first?.first?.trackStarred(trackId),
                        requestsBeforeFirstPublication = first?.second?.minus(requestsAtTap) ?: -1,
                        sendHeld = sendHeld,
                        revalidationReads = revalidationReads,
                        serverStarredDuringHold = serverDuringHold,
                        livePublicationDuringHold = liveDuringHold,
                        starredFromTapToOutcome = fromTap,
                        outcome = outcome?.let { it::class.simpleName ?: "unknown" } ?: "none",
                        savedValue = (outcome as? MutationOutcome.Saved)?.value,
                        sends = sends.toList(),
                        serverStarredAfter = serverStarred(raw, albumId, trackId),
                        pendingAfterOutcome = pending,
                        adoptedStarred = adopted,
                        publishedAfterReread = publications.lastOrNull()?.first?.trackStarred(trackId),
                    )
                } finally {
                    release.complete(Unit)
                    scope.cancel()
                }
            }
        } finally {
            starred?.let { trackId -> runCatching { raw.checkedRequest("unstar", mapOf("id" to trackId)) } }
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

    /** The server's star for [trackId], read raw. */
    private suspend fun serverStarred(raw: LibraryEndpointTransport, albumId: String, trackId: String): Boolean? =
        parseReaderAlbum(raw.checkedRequest("getAlbum", mapOf("id" to albumId)), albumId).second
            .firstOrNull { it.rawId == trackId }?.userState?.starred

    private fun LibraryPublication.trackStarred(trackId: String): Boolean? =
        items.filterIsInstance<LibraryItem.Track>().firstOrNull { it.rawId == trackId }?.starred

    private suspend fun awaitLive(publications: List<Pair<LibraryPublication, Int>>) {
        repeat(AWAIT_POLLS) {
            val last = publications.lastOrNull()?.first
            if (last?.freshness == LibraryFreshness.Live && last.items.isNotEmpty()) return
            delay(AWAIT_POLL_MILLIS)
        }
        error("the album never published live: ${publications.lastOrNull()?.first?.freshness}")
    }

    /** Waits for a live publication delivered after [mark]; returns without one when none comes. */
    private suspend fun awaitLiveAfter(publications: List<Pair<LibraryPublication, Int>>, mark: Int) {
        repeat(AWAIT_POLLS) {
            if (publications.drop(mark).any { it.first.freshness == LibraryFreshness.Live }) return
            delay(AWAIT_POLL_MILLIS)
        }
    }

    private suspend fun awaitCompleted(deferred: CompletableDeferred<Unit>): Boolean {
        repeat(AWAIT_POLLS) {
            if (deferred.isCompleted) return true
            delay(AWAIT_POLL_MILLIS)
        }
        return false
    }

    private suspend fun awaitOutcome(outcomes: List<MutationOutcome>, mark: Int): MutationOutcome? {
        repeat(AWAIT_POLLS) {
            outcomes.drop(mark).lastOrNull()?.let { return it }
            delay(AWAIT_POLL_MILLIS)
        }
        return null
    }

    private const val AWAIT_POLLS = 200
    private const val AWAIT_POLL_MILLIS = 50L
    private const val SETTLE_MILLIS = 300L
}
