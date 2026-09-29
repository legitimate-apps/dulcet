package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Which endpoint answers a lyrics read (§18.4, §10.4). The gate is a conjunction, as every gate
 * is (CORPUS §4 line 7): the structured endpoint is used only when the server speaks OpenSubsonic
 * AND advertises `songLyrics` at a version this client implements AND no registered quirk blocks
 * it. No permission, device capability or policy input applies to reading lyrics; the terms are
 * written out so one can be added without restructuring the gate.
 *
 * The legacy `getLyrics` is used ONLY when that conjunction is false — never as a fallback after a
 * failed `getLyricsBySongId`: one failed request never revokes an advertised capability (§10.4).
 */
internal enum class LyricsEndpoint(val endpointName: String) {
    /** `songLyrics` v1: `getLyricsBySongId` without `enhanced`. */
    SongLyrics("getLyricsBySongId"),

    /** `songLyrics` v2: `getLyricsBySongId&enhanced=true`, which adds the layer `kind`. */
    SongLyricsEnhanced("getLyricsBySongId"),

    /** Classic `getLyrics(artist, title)`: unsynced, one text, no language. */
    Legacy("getLyrics"),
}

internal fun lyricsEndpointFor(
    capabilities: CapabilitySet,
    knownQuirkBlocksSongLyrics: Boolean = false,
): LyricsEndpoint {
    val versions = capabilities.extensions[SONG_LYRICS_EXTENSION].orEmpty()
    val protocolSupports = !capabilities.legacySubsonic
    val extensionAdvertised = versions.any { it in SONG_LYRICS_IMPLEMENTED_VERSIONS }
    val structured = protocolSupports && extensionAdvertised && !knownQuirkBlocksSongLyrics
    return when {
        !structured -> LyricsEndpoint.Legacy
        2 in versions -> LyricsEndpoint.SongLyricsEnhanced
        else -> LyricsEndpoint.SongLyrics
    }
}

internal const val SONG_LYRICS_EXTENSION = "songLyrics"
private val SONG_LYRICS_IMPLEMENTED_VERSIONS = setOf(1, 2)

/** What the shell knows about the playing track: its opaque id, and what `getLyrics` is keyed by. */
internal data class LyricsTrack(
    val rawId: String,
    val artist: String?,
    val title: String?,
)

/**
 * One lyrics publication. [lyrics] is null only when [freshness] is `Unavailable`; a document whose
 * [TrackLyrics.selected] is null is the fact "no lyrics to show" (the server has none, or none
 * displayable), which the shell states rather than spinning.
 */
internal data class LyricsPublication(
    val lyrics: TrackLyrics?,
    val freshness: LibraryFreshness,
    /**
     * The §10.4 diagnostic: the endpoint's breaker is open and this publication sent nothing —
     * refused by the breaker, or answered from a remembered verdict or timeout while it was open.
     * The capability is still advertised and the endpoint is still the one the gate chose.
     */
    val breakerOpen: Boolean = false,
    /**
     * The server answered THIS read with code 70, "not found". A document with no layers is also
     * what a track without lyrics reads as, so this is the only way a consumer can prove it saw
     * code 70 (CLAUDE.md trap 41). It describes the answer, not where the document came from: a
     * later read, or [LibraryLyrics.cached], serving the stored result of a code-70 answer does
     * not carry it; and a code-70 answer from `getLyrics`, which is not stored (§18.4), carries it
     * on the publication of whatever the store already held.
     */
    val notFound: Boolean = false,
)

/**
 * Caps on one stored lyrics document (§18.4), on top of the seen-cache's count and byte budget.
 * ASSUMED bounds, set far above any song: [MAX_LYRICS_TEXT_BYTES] counts the UTF-8 bytes of every
 * string stored (line text, language, kind, display artist and title). An answer beyond them is
 * trimmed, not refused, while a main layer with text fits ([withinLyricsCaps]).
 */
internal const val MAX_LYRICS_LAYERS = 16
internal const val MAX_LYRICS_LINES = 2_000
internal const val MAX_LYRICS_TEXT_BYTES = 65_536

/**
 * The most bytes one lyrics response body may carry (§18.4), so the caps bound what a request
 * downloads and parses, not only what is stored. Sized so that a document at every cap is accepted
 * WITH the word- or syllable-level cues that `enhanced=true` adds and the core does not model:
 * Navidrome 0.63.2 sends each line's text three times (the line, its `cueLine`, and the cues) and
 * about 80 bytes of framing per cue. OBSERVED 2026-09-24 on a disposable Navidrome 0.63.2 (§18.4
 * names the measurement): one layer at the text cap as 65,536 one-byte cues of `&`, which JSON
 * escapes as six bytes, with nine-digit times, is 6,257,748 bytes — 89 bytes per cue beyond the
 * 393,956 the same lyrics take without cues — and sixteen layers at the line cap, 64,000 cues in
 * all, are 5,966,375. ASSUMED: at most one cue per byte of line text (the reference server's LRC
 * parser drops empty cues), and a line's text sent once per line, not once per singer. The limit
 * is a third again above the largest measured body. A body beyond it is refused as
 * [DomainError.Protocol.TooLarge]: on the JVM engine without being read to the end; on Darwin
 * after more of it has arrived, or as a timeout (§18.4 residual 1).
 */
internal const val MAX_LYRICS_RESPONSE_BYTES = 8 * 1_048_576

/**
 * Lyrics for one account, over the reader (§16.8): the live read is written through into the
 * seen-cache and the publication is built FROM the cache, so what is shown online is exactly what
 * will be shown offline. Confined to the reader's thread like every reader entry point.
 *
 * The §10.4 breaker is the reader's, never this object's: a breaker created per lyrics object
 * would start closed every time a shell asked for one, and would never hold; and the reader owns
 * the one offline-to-online transition that resets it: a reconnect's, once its epoch read
 * succeeds (§16.14).
 */
internal class LibraryLyrics(
    private val reader: LibraryReader,
    capabilities: CapabilitySet,
    private val preferredLanguages: List<String>,
) {
    val endpoint: LyricsEndpoint = lyricsEndpointFor(capabilities)

    private val breaker: EndpointCircuitBreaker get() = reader.breaker

    /** Whether the §10.4 breaker currently refuses this endpoint (a diagnostic for the shell). */
    val breakerOpen: Boolean
        get() {
            reader.checkConfined()
            return breaker.isOpen(endpoint.endpointName)
        }

    /**
     * The stored document, with no request: for the first frame of a Now Playing panel. It is
     * published as cached with its age, never as live (CORPUS §4 item 11), and it does not touch
     * the document: showing it is not a read, so it cannot reorder eviction.
     */
    fun cached(track: LyricsTrack): LyricsPublication {
        reader.checkConfined()
        return guarded {
            val stored = reader.cache.lyrics(track.rawId)
                ?: return@guarded LyricsPublication(null, LibraryFreshness.Unavailable(LibraryUnavailableReason.NotCachedOffline))
            LyricsPublication(stored.toTrackLyrics(), LibraryFreshness.Cached(stored.fetchedAtWall, LibraryCachedReason.Stale))
        }
    }

    /**
     * Reads live when online and publishes from the cache. Offline, or when the read fails, the
     * stored document is published as cached with the reason; with nothing stored the result is
     * `Unavailable`. A track the structured endpoint does not know (code 70) has no lyrics, and
     * that is stored; nothing else is written but a well-formed document, trimmed to the caps, so
     * a transient failure is never remembered as "no lyrics".
     *
     * An answer refused as [DomainError.Protocol.TooLarge] is remembered for the session: a later
     * read of the track sends nothing and publishes the stored document (or `Unavailable`) with
     * that failure. Verdicts follow the newest request: an older read's refusal landing after a
     * newer answer that fits changes nothing, and an older document does not undo a newer refusal.
     * A request of the track that timed out is remembered for one breaker period (§18.4 residual
     * 1): until then a read sends nothing and publishes the stored document (or `Unavailable`)
     * with that timeout — never as too large. A reconnect forgets it.
     *
     * An automatic read of a track that has a request in flight — automatic or explicit — joins it
     * instead of sending its own; with several in flight, it joins the newest. So automatic reads
     * never add a request while one of the track is in flight; only [retry] does. A remembered
     * verdict or timeout answers first: an automatic read never waits on a request in flight for a
     * track it already has an answer for. A request is joined only within the breaker's
     * observation window it was admitted in: after a reconnect an automatic read never joins a
     * request admitted before it, and sends its own (fifth review).
     *
     * While the §10.4 breaker is open nothing is sent — not this endpoint, and not the other one —
     * and the publication is the stored document (or `Unavailable`) with the latest failure and
     * [LyricsPublication.breakerOpen] set. One 429 opens it, for the server's `Retry-After` up to
     * [LIBRARY_BUSY_CAP] when that is longer than the period. A failure that is not the request's
     * (the device's store, before anything is sent) is published as the internal failure and never
     * reaches the breaker.
     */
    suspend fun read(track: LyricsTrack): LyricsPublication = read(track, explicit = false)

    /**
     * [read] at the person's request — a Retry control, never an automatic refresh. While the
     * breaker is open this is admitted as its single trial at once instead of waiting out the
     * period (§10.4); with a trial already in flight it is refused like any other call. It asks
     * again for a track whose answer was refused as too large or timed out, since the person
     * asked, and it always sends its own request rather than joining one in flight; an answer that
     * now fits forgets that verdict.
     */
    suspend fun retry(track: LyricsTrack): LyricsPublication = read(track, explicit = true)

    private suspend fun read(track: LyricsTrack, explicit: Boolean): LyricsPublication {
        reader.checkConfined()
        if (!reader.online) return fromCache(track, LibraryCachedReason.Offline, LibraryUnavailableReason.NotCachedOffline)
        if (endpoint == LyricsEndpoint.Legacy && track.title.isNullOrBlank()) {
            // `getLyrics` is keyed by title; without one there is nothing to ask for.
            return LyricsPublication(TrackLyrics(LyricsSource.LegacyGetLyrics, emptyList(), -1), LibraryFreshness.Live)
        }
        val key = LyricsReadKey(endpoint, track.rawId)
        while (true) {
            // The person's Retry always asks for itself: past a verdict, past a timeout, and
            // never joining a request in flight.
            if (!explicit) {
                if (reads.isTooLarge(key)) return tooLarge(track).nothingSent(key)
                if (reads.hasTimedOut(key, breaker)) return timedOut(track).nothingSent(key)
                // Only a request of the current observation window: one admitted before a reconnect
                // belongs to the network that went away, and its outcome says nothing about now.
                val flight = reads.inFlight(key, breaker.generation)
                if (flight != null) {
                    val shared = try {
                        flight.await()
                    } catch (failure: CancellationException) {
                        // This read was cancelled: that propagates. Or the read it joined was, and
                        // this one was not: it asks again — a verdict may have landed meanwhile.
                        currentCoroutineContext().ensureActive()
                        continue
                    }
                    // Null: the answer it joined was a miss (see [served]); asked again.
                        ?: continue
                    // The same answer, selected with this object's own languages.
                    return shared.copy(
                        lyrics = shared.lyrics?.let { TrackLyrics.of(it.source, it.layers, preferredLanguages, it.droppedLayers) },
                    )
                }
            }
            return send(track, key, explicit) ?: continue
        }
    }

    /**
     * Sends the request, as a flight any automatic read of the same track joins. Null when the
     * answer is a miss that must be asked again (see [served]).
     */
    private suspend fun send(track: LyricsTrack, key: LyricsReadKey, explicit: Boolean): LyricsPublication? {
        val name = key.endpoint.endpointName
        val admission = breaker.admit(name, explicit)
        if (admission is EndpointCircuitBreaker.Admission.Open) {
            // No request, and no other endpoint either: the capability is not revoked (§10.4).
            val error = admission.lastError
            return fromCache(track, LibraryCachedReason.Failed(error), LibraryUnavailableReason.Failed(error))
                .copy(breakerOpen = true)
        }
        admission as EndpointCircuitBreaker.Admission.Allowed
        val flight = reads.begin(key, admission.generation)
        try {
            return answer(track, key, admission).also { flight.complete(it) }
        } catch (failure: Throwable) {
            flight.completeExceptionally(failure)
            throw failure
        } finally {
            reads.end(key, flight)
        }
    }

    private suspend fun answer(
        track: LyricsTrack,
        key: LyricsReadKey,
        admission: EndpointCircuitBreaker.Admission.Allowed,
    ): LyricsPublication? {
        val name = key.endpoint.endpointName
        val answer = try {
            ask(track)
        } catch (failure: CancellationException) {
            // The breaker frees the trial slot only for the trial's own admission: a straggler
            // admitted before the breaker opened holds nothing, and freeing it would admit a second.
            breaker.abandon(name, admission)
            throw failure
        } ?: run {
            // What failed is not the request — most often the device's store before anything was
            // sent: the endpoint's health is not touched, neither charged nor cleared, and a trial
            // gives its slot back as a cancelled call does (§10.4). Published as the reader's own
            // failure, never as the server's.
            breaker.abandon(name, admission)
            return fromCache(track, LibraryCachedReason.InternalFailure, LibraryUnavailableReason.InternalFailure)
        }
        if (answer is LyricsAnswer.Refused) {
            // Refused unsent because the reader went offline after this read was admitted (§16.14):
            // nothing was asked, so the endpoint is neither charged nor cleared, a trial gives its
            // slot back, and the read is what an offline read is — never a failure of the server.
            breaker.abandon(name, admission)
            return fromCache(track, LibraryCachedReason.Offline, LibraryUnavailableReason.NotCachedOffline)
        }
        // The endpoint's health is decided by the server's answer alone, before anything touches
        // the device's store: a local database failure below is not the server's.
        if (answer is LyricsAnswer.Failed) {
            breaker.recordFailure(name, answer.error, admission)
        } else {
            breaker.recordSuccess(name, admission)
        }
        // A failure says nothing about the size; any other answer is a verdict, ordered by issue.
        // A timeout is remembered for a period (§18.4 residual 1), and never as a verdict.
        when (answer) {
            is LyricsAnswer.TooLarge -> reads.decide(key, answer.issueSeq, tooLarge = true)
            is LyricsAnswer.Document -> reads.decide(key, answer.issueSeq, tooLarge = false)
            is LyricsAnswer.NotFound -> reads.decide(key, answer.issueSeq, tooLarge = false)
            is LyricsAnswer.Failed -> if (answer.error == DomainError.Transport.Timeout) {
                reads.recordTimeout(key, answer.issueSeq, breaker.periodMark(admission))
            }
            LyricsAnswer.Refused -> Unit
        }
        return when (answer) {
            is LyricsAnswer.Document ->
                store(track, answer.issueSeq, answer.source, answer.layers, answer.droppedLayers, notFound = false)
            is LyricsAnswer.NotFound -> if (endpoint == LyricsEndpoint.Legacy) {
                fromCache(track, LibraryCachedReason.Failed(answer.error), LibraryUnavailableReason.Failed(answer.error))
                    .copy(notFound = true)
            } else {
                store(track, answer.issueSeq, LyricsSource.SongLyricsExtension, emptyList(), 0, notFound = true)
            }
            // A newer answer that fits already decided: what it stored is shown, as a document
            // that lost the issue-order race is — or, if that is gone, the read is asked again.
            is LyricsAnswer.TooLarge -> if (reads.isTooLarge(key)) tooLarge(track) else served(track)
            is LyricsAnswer.Failed ->
                fromCache(track, LibraryCachedReason.Failed(answer.error), LibraryUnavailableReason.Failed(answer.error))
            LyricsAnswer.Refused -> error("returned above")
        }
    }

    /** A publication that sent nothing says so when the breaker is open, as a refused one does. */
    private fun LyricsPublication.nothingSent(key: LyricsReadKey): LyricsPublication =
        if (breaker.isOpen(key.endpoint.endpointName)) copy(breakerOpen = true) else this

    /** The publication for a track whose answer was refused as too large: the store is untouched. */
    private fun tooLarge(track: LyricsTrack): LyricsPublication = DomainError.Protocol.TooLarge.let { error ->
        fromCache(track, LibraryCachedReason.Failed(error), LibraryUnavailableReason.Failed(error))
    }

    /** The publication for a track whose request timed out within the period: nothing was sent. */
    private fun timedOut(track: LyricsTrack): LyricsPublication = DomainError.Transport.Timeout.let { error ->
        fromCache(track, LibraryCachedReason.Failed(error), LibraryUnavailableReason.Failed(error))
    }

    /**
     * For an older answer refused as too large after a newer answer that fits decided: the
     * document that newer answer stored, live, read as an access. Null when it is no longer stored
     * — evicted since, which a store over its budget does at once (§18.4) — so the read is a miss
     * and is asked again; it is never published as the older answer's refusal (fourth review).
     */
    private fun served(track: LyricsTrack): LyricsPublication? {
        var missing = false
        val publication = guarded {
            val stored = reader.cache.lyrics(track.rawId)
            if (stored == null) {
                missing = true
                return@guarded LyricsPublication(null, LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure))
            }
            reader.cache.touchLyrics(track.rawId)
            LyricsPublication(stored.toTrackLyrics(), LibraryFreshness.Live)
        }
        return publication.takeUnless { missing }
    }

    /**
     * What the server said, classified for the breaker: [LyricsAnswer.Failed] is every answer that
     * counts against the endpoint, whatever its error class (§10.4). A code-70 envelope and an
     * answer too large to keep are item-scoped: answers about one track, from a healthy endpoint.
     * The body is parsed once: the envelope's status and the lyrics come from the same tree.
     *
     * Null when what failed is not the request: the device's own store — the issue sequence is
     * written before anything is sent, so its failure sends nothing — or a defect of this client.
     * The classification is the reader's, as its windows and the outbox use it: only a
     * [LibraryRequestFailure] is the request's failure, so only it is ever the server's — save a
     * [ReaderSendRefused], a request the reader refused unsent because it went offline, which is
     * [LyricsAnswer.Refused] and nobody's failure (§16.14).
     */
    private suspend fun ask(track: LyricsTrack): LyricsAnswer? {
        var issueSeq = 0L
        var answered = false
        return try {
            val name = endpoint.endpointName
            val parameters = lyricsParameters(endpoint, track)
            val sent = reader.send(name, parameters, maxBodyBytes = MAX_LYRICS_RESPONSE_BYTES) { issueSeq = it }
            answered = true
            val (source, layers) = parseLyricsPayload(endpoint, sent.response.okPayload())
            val trimmed = layers.withinLyricsCaps(preferredLanguages) ?: return LyricsAnswer.TooLarge(issueSeq)
            LyricsAnswer.Document(issueSeq, source, trimmed.kept, trimmed.dropped)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: ReaderSendRefused) {
            // Never sent: the reader went offline before it reached the front (§16.14).
            LyricsAnswer.Refused
        } catch (failure: LibraryRequestFailure) {
            val error = failure.error
            when {
                // A successful body beyond the response limit, refused by the transport.
                error == DomainError.Protocol.TooLarge -> LyricsAnswer.TooLarge(issueSeq)
                // Item-scoped only when the server itself sent it, in a well-formed envelope.
                error.isNotFound() && answered -> LyricsAnswer.NotFound(issueSeq, error)
                else -> LyricsAnswer.Failed(issueSeq, error)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun store(
        track: LyricsTrack,
        issueSeq: Long,
        source: LyricsSource,
        layers: List<LyricsLayer>,
        droppedLayers: Int,
        notFound: Boolean,
    ): LyricsPublication = guarded {
        val cache = reader.cache
        cache.writeLyrics(issueSeq, track.rawId, source, layers, droppedLayers)
        cache.touchLyrics(track.rawId)
        // Published from the cache, not from the response: a newer write that won the issue-order
        // race is what is shown, never the answer this call happened to receive.
        val stored = cache.lyrics(track.rawId)
            ?: return@guarded LyricsPublication(null, LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure))
        LyricsPublication(stored.toTrackLyrics(), LibraryFreshness.Live, notFound = notFound)
    }

    /** The stored document for a read that got no usable answer: served, so it counts as an access. */
    private fun fromCache(
        track: LyricsTrack,
        cachedReason: LibraryCachedReason,
        unavailableReason: LibraryUnavailableReason,
    ): LyricsPublication = guarded {
        val cache = reader.cache
        val stored = cache.lyrics(track.rawId)
            ?: return@guarded LyricsPublication(null, LibraryFreshness.Unavailable(unavailableReason))
        cache.touchLyrics(track.rawId)
        LyricsPublication(stored.toTrackLyrics(), LibraryFreshness.Cached(stored.fetchedAtWall, cachedReason))
    }

    /**
     * A failure of the device's own store is published as the reader's internal failure — never
     * as a server failure, never escaping to the shell — like every other reader publication.
     */
    private inline fun guarded(block: () -> LyricsPublication): LyricsPublication = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        LyricsPublication(null, LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure))
    }

    private fun CachedLyrics.toTrackLyrics(): TrackLyrics = TrackLyrics.of(source, layers, preferredLanguages, droppedLayers)

    private val reads: LyricsReads get() = reader.lyricsReads
}

private sealed interface LyricsAnswer {
    class Document(val issueSeq: Long, val source: LyricsSource, val layers: List<LyricsLayer>, val droppedLayers: Int) : LyricsAnswer
    class NotFound(val issueSeq: Long, val error: DomainError) : LyricsAnswer
    class TooLarge(val issueSeq: Long) : LyricsAnswer

    /** [issueSeq] is 0 when the request never went out. */
    class Failed(val issueSeq: Long, val error: DomainError) : LyricsAnswer

    /** Refused unsent: the reader went offline first ([ReaderSendRefused]). Not the endpoint's answer. */
    data object Refused : LyricsAnswer
}

/**
 * A lyrics read's identity for its verdict, its flights and its timeout (§18.4): the endpoint as
 * the gate chose it, and the track. `songLyrics` v1 and v2 are separate reads — v2 asks for more
 * (`enhanced=true`), so its size says nothing about v1's, and a v1 read must not receive its
 * answer — although both call `getLyricsBySongId` and share that endpoint's breaker.
 */
internal data class LyricsReadKey(val endpoint: LyricsEndpoint, val trackRawId: String)

/**
 * How many tracks' verdicts, and separately how many tracks' timeouts, [LyricsReads] remembers:
 * the least recently used beyond it are forgotten, and are asked for again. ASSUMED: far above the
 * tracks one session plays; a track with a request in flight is never forgotten.
 */
internal const val MAX_LYRICS_READ_MEMORY = 1_000

/**
 * The lyrics reads of one session (§18.4), held by the reader so every lyrics object of the
 * session shares them. Confined to the reader's thread; it has no lock.
 *
 * - **Verdicts.** An answer refused as too large is remembered, so an automatic read of the track
 *   sends nothing. Verdicts are written in issue order: an answer only decides when no answer to a
 *   NEWER request of the same track already has, so an older read's refusal that lands after a
 *   newer Retry's document changes nothing. A verdict that the answer fits is kept only while a
 *   request of that track is in flight, which is as long as an older answer can still land.
 * - **Timeouts.** A request that timed out is remembered until the end of one breaker period, or
 *   until a reset of the breaker, or until an answer to a newer request decides (§18.4 residual 1).
 * - **Flights.** Every request of a track in flight, stamped with the breaker generation it was
 *   admitted in. An automatic read joins the newest one still in flight of the CURRENT generation,
 *   so within one observation window an automatic read adds no request while one of the track is
 *   in flight; after a reconnect it joins nothing admitted before it and sends its own. A request
 *   from an older generation still completes for its own caller, and what it leaves behind carries over
 *   only where it describes the file rather than the network: its document (a code-70 answer
 *   included, stored as a document with no lyrics) and a too-large verdict (all ordered by issue);
 *   its failure is ignored by the breaker and its timeout is never
 *   remembered (the mark it gets is from its own generation, which is no longer current).
 * - **Bounds.** At most [capacity] verdicts and [capacity] timeouts, least recently used forgotten
 *   first, never one of a track with a request in flight.
 */
internal class LyricsReads(private val capacity: Int = MAX_LYRICS_READ_MEMORY) {
    private class Verdict(val issueSeq: Long, val tooLarge: Boolean)
    private class TimedOut(val issueSeq: Long, val mark: EndpointCircuitBreaker.PeriodMark)

    // Iteration order is recency: an entry is moved to the end when it is written or consulted.
    private val verdicts = LinkedHashMap<LyricsReadKey, Verdict>()
    private val timeouts = LinkedHashMap<LyricsReadKey, TimedOut>()
    private class Flight(val answer: CompletableDeferred<LyricsPublication?>, val generation: Long)
    private val flights = mutableMapOf<LyricsReadKey, MutableList<Flight>>()

    init {
        require(capacity > 0)
    }

    fun isTooLarge(key: LyricsReadKey): Boolean {
        val verdict = verdicts[key] ?: return false
        if (!verdict.tooLarge) return false
        verdicts.remove(key)
        verdicts[key] = verdict
        return true
    }

    /** The answer to the request [issueSeq] of [key], unless an answer to a newer one decided. */
    fun decide(key: LyricsReadKey, issueSeq: Long, tooLarge: Boolean) {
        val current = verdicts[key]
        if (current != null && current.issueSeq > issueSeq) return
        verdicts.remove(key)
        verdicts[key] = Verdict(issueSeq, tooLarge)
        timeouts[key]?.let { if (it.issueSeq < issueSeq) timeouts.remove(key) }
        bound(verdicts)
    }

    /** Whether a request of [key] timed out within the current period of [breaker]. */
    fun hasTimedOut(key: LyricsReadKey, breaker: EndpointCircuitBreaker): Boolean {
        val timeout = timeouts[key] ?: return false
        timeouts.remove(key)
        if (!breaker.isCurrent(timeout.mark)) return false
        timeouts[key] = timeout
        return true
    }

    /** The request [issueSeq] of [key] timed out, unless an answer to a newer one decided. */
    fun recordTimeout(key: LyricsReadKey, issueSeq: Long, mark: EndpointCircuitBreaker.PeriodMark) {
        val verdict = verdicts[key]
        if (verdict != null && verdict.issueSeq > issueSeq) return
        val current = timeouts[key]
        if (current != null && current.issueSeq > issueSeq) return
        timeouts.remove(key)
        timeouts[key] = TimedOut(issueSeq, mark)
        bound(timeouts)
    }

    /**
     * The newest request of [key] still in flight that was sent in [generation], for an automatic
     * read to join; null when none is.
     */
    fun inFlight(key: LyricsReadKey, generation: Long): CompletableDeferred<LyricsPublication?>? =
        flights[key]?.lastOrNull { it.generation == generation }?.answer

    /** A request of [key] goes out in [generation]; its publication completes the returned flight. */
    fun begin(key: LyricsReadKey, generation: Long): CompletableDeferred<LyricsPublication?> {
        val flight = CompletableDeferred<LyricsPublication?>()
        flights.getOrPut(key) { mutableListOf() } += Flight(flight, generation)
        return flight
    }

    /** The request that [begin] returned [flight] for has ended, however it ended. */
    fun end(key: LyricsReadKey, flight: CompletableDeferred<LyricsPublication?>) {
        val inFlight = flights[key] ?: return
        inFlight.removeAll { it.answer === flight }
        if (inFlight.isNotEmpty()) return
        flights.remove(key)
        if (verdicts[key]?.tooLarge == false) verdicts.remove(key)
    }

    private fun <V> bound(entries: LinkedHashMap<LyricsReadKey, V>) {
        val keys = entries.keys.iterator()
        while (entries.size > capacity && keys.hasNext()) {
            if (keys.next() !in flights) keys.remove()
        }
    }
}

/** The layers of an answer that fit the caps, and how many did not. */
internal data class LyricsTrim(val kept: List<LyricsLayer>, val dropped: Int)

/**
 * The layers of an answer that are stored (§18.4), in the server's order, or null when the answer
 * is refused as too large.
 *
 * Layers are considered in the selection's own order ([lyricsLayerRankOrder]): the layer the
 * selection would show first, then the other displayable layers by rank, then the extra layers
 * (translations, pronunciations) by the same rank of language, then layers without text. A layer
 * is kept when it fits the caps together with every layer kept before it, and dropped otherwise,
 * and the next one is considered: so a layer is never dropped to make room for one the selection
 * ranks below it, and a large layer does not take the smaller ones after it down with it. The
 * answer is refused only when it has a main layer with text and none is kept — that is, when every
 * one of them alone exceeds the caps, since the first one considered is measured against nothing.
 * A main layer without text never counts as kept: it cannot be shown, so it cannot stand in for a
 * main layer that did not fit (third review).
 */
internal fun List<LyricsLayer>.withinLyricsCaps(preferredLanguages: List<String>): LyricsTrim? {
    val kept = BooleanArray(size)
    var layers = 0
    var lines = 0
    var bytes = 0L
    fun consider(index: Int) {
        val layer = this[index]
        val layerBytes = layer.utf8Bytes()
        if (layers + 1 > MAX_LYRICS_LAYERS) return
        if (lines + layer.lines.size > MAX_LYRICS_LINES) return
        if (bytes + layerBytes > MAX_LYRICS_TEXT_BYTES) return
        kept[index] = true
        layers += 1
        lines += layer.lines.size
        bytes += layerBytes
    }
    lyricsLayerRankOrder(this, preferredLanguages).forEach(::consider)
    if (any { it.isDisplayable } && indices.none { kept[it] && this[it].isDisplayable }) return null
    return LyricsTrim(filterIndexed { index, _ -> kept[index] }, kept.count { !it })
}

/** Every string of [this] layer that is stored, in UTF-8 bytes. */
private fun LyricsLayer.utf8Bytes(): Long =
    lyricsUtf8Length(language) + lyricsUtf8Length(kind) + lyricsUtf8Length(displayArtist) +
        lyricsUtf8Length(displayTitle) + lines.sumOf { lyricsUtf8Length(it.text) }

/** [text]'s length in UTF-8 bytes, without encoding it; zero for null. */
internal fun lyricsUtf8Length(text: String?): Long {
    if (text == null) return 0
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val char = text[index]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                index += 1
                4
            }
            else -> 3
        }
        index += 1
    }
    return bytes
}

internal fun lyricsParameters(endpoint: LyricsEndpoint, track: LyricsTrack): Map<String, String> = when (endpoint) {
    LyricsEndpoint.SongLyrics -> mapOf("id" to track.rawId)
    LyricsEndpoint.SongLyricsEnhanced -> mapOf("id" to track.rawId, "enhanced" to "true")
    LyricsEndpoint.Legacy -> buildMap {
        track.artist?.takeIf(String::isNotBlank)?.let { put("artist", it) }
        track.title?.takeIf(String::isNotBlank)?.let { put("title", it) }
    }
}

/**
 * Parses an `ok` envelope from [endpoint] into the layers to store. Tolerant where the contract
 * allows (unknown fields, a missing `structuredLyrics` meaning none, a missing `offset` meaning 0)
 * and strict where it does not (a line without `value`, a synced line without a time, a legacy
 * answer without its `lyrics` object).
 *
 * A time is whole milliseconds: a JSON number, or a string holding one, since a server may derive
 * its JSON from XML attributes (ASSUMED; not seen on the reference server). A fraction is
 * truncated; NaN, infinities and anything that is not a decimal number are not times. A synced
 * layer with any time beyond ±24 h ([LYRICS_TIME_BOUND]) is not trusted: its text is kept and its
 * timing dropped, so it is stored and shown unsynced, and no stored value can break the cursor.
 */
internal fun parseLyricsResponse(endpoint: LyricsEndpoint, body: String): Pair<LyricsSource, List<LyricsLayer>> =
    parseLyricsPayload(endpoint, parseLibraryEnvelope(body)?.payload ?: lyricsMalformed())

/**
 * [parseLyricsResponse] from an envelope's payload already parsed: the read parses a body once
 * ([okPayload]), since a body at the response limit costs a large share of a second to parse on
 * Apple platforms (§18.4).
 */
internal fun parseLyricsPayload(endpoint: LyricsEndpoint, payload: JsonObject): Pair<LyricsSource, List<LyricsLayer>> =
    when (endpoint) {
        LyricsEndpoint.Legacy -> LyricsSource.LegacyGetLyrics to parseLegacyLyrics(payload)
        LyricsEndpoint.SongLyrics, LyricsEndpoint.SongLyricsEnhanced ->
            LyricsSource.SongLyricsExtension to parseStructuredLyrics(payload)
    }

private fun parseStructuredLyrics(payload: JsonObject): List<LyricsLayer> {
    val list = payload["lyricsList"] as? JsonObject ?: lyricsMalformed()
    val entries = when (val value = list["structuredLyrics"]) {
        null -> return emptyList()
        is JsonArray -> value
        is JsonObject -> JsonArray(listOf(value))
        else -> lyricsMalformed()
    }
    return entries.map { element ->
        val entry = element as? JsonObject ?: lyricsMalformed()
        val synced = (entry["synced"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: lyricsMalformed()
        val lines = when (val value = entry["line"]) {
            null -> JsonArray(emptyList())
            is JsonArray -> value
            is JsonObject -> JsonArray(listOf(value))
            else -> lyricsMalformed()
        }.map { lineElement ->
            val line = lineElement as? JsonObject ?: lyricsMalformed()
            val text = line.lyricsString("value") ?: lyricsMalformed()
            val start = line.lyricsTime("start")
            if (synced && start == null) lyricsMalformed()
            text to start
        }
        // An unsynced layer has no timing at all: its lines carry no start even if a server sends
        // one, and its offset is irrelevant, so it is neither read nor validated.
        val offset = if (!synced || entry.isAbsent("offset")) 0L else entry.lyricsTime("offset") ?: lyricsMalformed()
        val trusted = synced && offset.milliseconds.isTrustedLyricsTime() &&
            lines.all { (_, start) -> checkNotNull(start).milliseconds.isTrustedLyricsTime() }
        LyricsLayer(
            language = entry.lyricsString("lang")?.trim()?.takeUnless { lyricsLanguageKey(it) == null },
            synced = trusted,
            offset = if (trusted) offset.milliseconds else Duration.ZERO,
            lines = if (trusted) {
                lines.map { (text, start) -> LyricsLine(text = text, start = checkNotNull(start).milliseconds) }
                    .sortedBy { it.start }
            } else {
                lines.map { (text, _) -> LyricsLine(text = text, start = null) }
            },
            kind = entry.lyricsString("kind"),
            displayArtist = entry.lyricsString("displayArtist"),
            displayTitle = entry.lyricsString("displayTitle"),
        )
    }
}

/**
 * Classic `getLyrics`: one text. The `lyrics` object is the answer, so a body without one (or with
 * something else in its place) is malformed, not "none". An empty or absent `value` means none
 * (OBSERVED on the reference server: an unmatched artist/title answers `ok` with `value: ""`); a
 * `value` that is not a string is malformed. Leading and trailing blank lines are dropped; blank
 * lines inside are stanza breaks and kept.
 */
private fun parseLegacyLyrics(payload: JsonObject): List<LyricsLayer> {
    val lyrics = payload["lyrics"] as? JsonObject ?: lyricsMalformed()
    val value = when (val raw = lyrics["value"]) {
        null, JsonNull -> return emptyList()
        is JsonPrimitive -> if (raw.isString) raw.content else lyricsMalformed()
        else -> lyricsMalformed()
    }
    val lines = value.split("\r\n", "\n", "\r").dropWhile(String::isBlank).dropLastWhile(String::isBlank)
    if (lines.isEmpty()) return emptyList()
    return listOf(
        LyricsLayer(
            language = null,
            synced = false,
            offset = Duration.ZERO,
            lines = lines.map { LyricsLine(text = it, start = null) },
            kind = null,
            displayArtist = lyrics.lyricsString("artist"),
            displayTitle = lyrics.lyricsString("title"),
        ),
    )
}

private fun JsonObject.lyricsString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.isAbsent(name: String): Boolean = get(name).let { it == null || it is JsonNull }

/** A decimal number as JSON writes one; leading zeros are tolerated in a string. */
private val LYRICS_TIME_PATTERN = Regex("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/** A time in whole milliseconds (see [parseLyricsResponse]), or null when the value is not one. */
private fun JsonObject.lyricsTime(name: String): Long? {
    val primitive = get(name) as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val content = primitive.content
    if (!LYRICS_TIME_PATTERN.matches(content)) return null
    val value = content.toDouble()
    // Finite, then truncated toward zero; a finite value beyond a Long saturates, and is untrusted.
    return if (value.isFinite()) value.toLong() else null
}

private fun lyricsMalformed(): Nothing = throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
