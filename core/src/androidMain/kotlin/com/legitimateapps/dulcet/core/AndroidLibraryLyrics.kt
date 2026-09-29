package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException

/**
 * Lyrics for the Android shells' Now Playing (spec §18.4), over the reader session of one
 * [AndroidLibraryReader]: the stored document first, with no request, then a live read written
 * through the seen-cache, so what is shown online is exactly what will be shown offline.
 *
 * **Shape.** Every method returns at once and completes exactly once, on the main thread, with an
 * [AndroidLyricsPublication]; the work runs on the reader's thread (see
 * [AndroidLibraryReader.operation]). Nothing throws out of it, and nothing published can carry a URL
 * or server error text.
 *
 * **Which endpoint.** The core's gate (`lyricsEndpointFor`, §18.4) needs the account's negotiated
 * extensions, which the Android account does not carry across launches. So this reads them with its
 * first live read — one `getOpenSubsonicExtensions` through the reader, bounded, ordered and refused
 * offline like every other read — and keeps the answer for the life of this object: a 404 is a
 * legacy server, an `ok` envelope lists the extensions, any other envelope lists none; each is an
 * answer and is kept. A failed read is not an answer: nothing is kept, the publication is the stored
 * document (or `unavailable`) with that failure, and the next read asks again. Every later decision
 * — structured or legacy, the §10.4 breaker, the caps, the layer selection — is the core's. This is
 * the Apple facade's rule, so both platforms choose the same endpoint for the same server.
 *
 * **Synced lines** are followed with the core's cursor ([LyricsLayer.cursorAtMilliseconds] on
 * [AndroidLyricsPublication.layer]), never a shell-side rule.
 */
public class AndroidLibraryLyrics(
    private val reader: AndroidLibraryReader,
    /** The person's languages, most preferred first (BCP 47), for the layer choice. */
    private val preferredLanguages: List<String>,
) {
    // Reader-thread state.
    private var lyrics: LibraryLyrics? = null

    /** The stored document with no request: a lyrics panel's first frame, `cached` or `unavailable`. */
    public fun cached(track: AndroidLyricsTrack, completion: (AndroidLyricsPublication) -> Unit) {
        run(track, completion) { session, core -> (lyrics ?: provisional(session)).cached(core) }
    }

    /**
     * Reads live when online and publishes from the cache; offline, or when the read fails, the
     * stored document with the reason, or `unavailable` with nothing stored (§18.4).
     */
    public fun read(track: AndroidLyricsTrack, completion: (AndroidLyricsPublication) -> Unit) {
        run(track, completion) { session, core -> withLyrics(session, core) { it.read(core) } }
    }

    /** [read] at the person's request: a Retry control, admitted past the breaker as its trial (§10.4). */
    public fun retry(track: AndroidLyricsTrack, completion: (AndroidLyricsPublication) -> Unit) {
        run(track, completion) { session, core -> withLyrics(session, core) { it.retry(core) } }
    }

    private fun run(
        track: AndroidLyricsTrack,
        completion: (AndroidLyricsPublication) -> Unit,
        work: suspend (LibraryReaderSession, LyricsTrack) -> LyricsPublication,
    ) {
        reader.operation(completion, { failure -> failedPublication(track.rawId, failure) }) { session ->
            if (track.rawId.isBlank()) return@operation failedPublication(track.rawId, AndroidOperationFailure.InternalFailure)
            work(session, LyricsTrack(track.rawId, track.artist, track.title)).toAndroid(track.rawId)
        }
    }

    /** The session's lyrics once the endpoint is known; offline the core publishes the stored document first. */
    private suspend fun withLyrics(
        session: LibraryReaderSession,
        track: LyricsTrack,
        read: suspend (LibraryLyrics) -> LyricsPublication,
    ): LyricsPublication {
        lyrics?.let { return read(it) }
        if (!session.reader.online) return read(provisional(session))
        val capabilities = try {
            discover(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LibraryRequestFailure) {
            if (failure is ReaderSendRefused) return read(provisional(session))
            return provisional(session).cached(track).failedWith(failure.error)
        }
        val known = session.lyrics(capabilities, preferredLanguages).also { lyrics = it }
        return read(known)
    }

    /** One `getOpenSubsonicExtensions` read (§10.3). Throws [LibraryRequestFailure] when it failed. */
    private suspend fun discover(session: LibraryReaderSession): CapabilitySet {
        val response = session.reader.send("getOpenSubsonicExtensions").response
        if (response.statusCode == 404) return capabilities(emptyMap(), legacy = true)
        val envelope = parseLibraryEnvelope(response.body)
            ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
        if (envelope.status != "ok") return capabilities(emptyMap(), legacy = false)
        val extensions = envelope.payload["openSubsonicExtensions"].toExtensionMapOrNull()
            ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
        return capabilities(extensions, legacy = false)
    }

    private fun capabilities(extensions: Map<String, Set<Int>>, legacy: Boolean) = CapabilitySet(
        extensions = extensions,
        // No permission applies to reading lyrics (§18.4); the gate does not read these.
        permissions = UserPermissions(download = false, playlist = false, share = false, jukebox = false, admin = false),
        legacySubsonic = legacy,
    )

    /** Lyrics over the store only, for a moment the endpoint cannot be known. Never kept. */
    private fun provisional(session: LibraryReaderSession): LibraryLyrics =
        session.lyrics(capabilities(emptyMap(), legacy = false), preferredLanguages)
}

/** What the shell knows about the track: its opaque id, and what the legacy `getLyrics` is keyed by. */
public data class AndroidLyricsTrack(val rawId: String, val artist: String?, val title: String?)

/** One line as drawn: its text, and when it becomes current (offset applied); null when untimed. */
public data class AndroidLyricsLine(val text: String, val effectiveStartMilliseconds: Long?)

/** What a lyrics panel shows (§18.4). */
public enum class AndroidLyricsState {
    /** A layer to show: [AndroidLyricsPublication.lines]. */
    Lyrics,

    /** The server has no displayable lyrics for this track: say so, never spin. */
    None,

    /** Nothing stored and nothing could be read; the freshness says why. */
    Unavailable,
}

/**
 * One lyrics publication (§18.4). [freshness] is the windows' vocabulary. [layer] is the core's
 * selected layer, for [LyricsLayer.cursorAtMilliseconds] as playback progresses. [trimmed]: layers
 * did not fit the caps and were left out. [breakerOpen]: the endpoint's breaker is open and nothing
 * was sent (§10.4). [failure]: the read never ran.
 */
public data class AndroidLyricsPublication(
    val trackRawId: String,
    val state: AndroidLyricsState,
    val freshness: AndroidLibraryFreshness,
    val synced: Boolean,
    val lines: List<AndroidLyricsLine>,
    val language: String?,
    val layer: LyricsLayer?,
    val trimmed: Boolean,
    val breakerOpen: Boolean,
    val failure: AndroidOperationFailure?,
)

/** The stored document's publication, re-stated as the failure that kept it from being read. */
private fun LyricsPublication.failedWith(error: DomainError): LyricsPublication = copy(
    freshness = when (val current = freshness) {
        is LibraryFreshness.Cached -> LibraryFreshness.Cached(current.asOfWall, LibraryCachedReason.Failed(error))
        else -> LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(error))
    },
)

internal fun LyricsPublication.toAndroid(trackRawId: String): AndroidLyricsPublication {
    val selected = lyrics?.selected
    return AndroidLyricsPublication(
        trackRawId = trackRawId,
        state = when {
            lyrics == null -> AndroidLyricsState.Unavailable
            selected == null -> AndroidLyricsState.None
            else -> AndroidLyricsState.Lyrics
        },
        freshness = freshness.toAndroid(),
        synced = selected?.synced == true,
        lines = selected?.lines.orEmpty().map { line ->
            AndroidLyricsLine(line.text, if (selected!!.synced) selected.effectiveStartMilliseconds(line) else null)
        },
        language = selected?.language,
        layer = selected,
        trimmed = lyrics?.isTrimmed == true,
        breakerOpen = breakerOpen,
        failure = null,
    )
}

private fun failedPublication(trackRawId: String, failure: AndroidOperationFailure) = AndroidLyricsPublication(
    trackRawId = trackRawId,
    state = AndroidLyricsState.Unavailable,
    freshness = AndroidLibraryFreshness.Unavailable(
        when (failure) {
            AndroidOperationFailure.Closed -> AndroidLibraryUnavailableReason.Closed
            AndroidOperationFailure.InternalFailure -> AndroidLibraryUnavailableReason.InternalFailure
        },
    ),
    synced = false,
    lines = emptyList(),
    language = null,
    layer = null,
    trimmed = false,
    breakerOpen = false,
    failure = failure,
)
