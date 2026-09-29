package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException

/**
 * Lyrics for the Apple shells' Now Playing (spec §18.4), over the reader session of one
 * [AppleLibraryReaderClient]: the stored document first, with no request, then a live read written
 * through the seen-cache, so what is shown online is exactly what will be shown offline.
 *
 * **Shape** (§7.1–§7.3, CLAUDE.md trap 17). Every method returns an [AppleLibraryReaderOperation]
 * at once and completes exactly once, on the main thread, with an [AppleLibraryLyricsPublication];
 * the work runs on the reader's thread. No Kotlin exception crosses, and nothing published can carry
 * a URL or server error text.
 *
 * **Which endpoint.** The core's gate (`lyricsEndpointFor`, §18.4) needs the account's negotiated
 * extensions, which the Apple account does not carry across launches. This facade reads them itself
 * with the first live read — one `getOpenSubsonicExtensions` through the reader, so it is bounded,
 * ordered and refused offline like every other read — and keeps the answer for the life of the
 * client: a 404 is a legacy server, an `ok` envelope lists the extensions, any other envelope lists
 * none; each of those is an answer and is kept. A failed read is not an answer: nothing is kept, the
 * publication is the stored document (or `unavailable`) with that failure, and the next read asks
 * again. Every later decision — structured or legacy, the §10.4 breaker, the size caps, the layer
 * selection — is the core's [LibraryLyrics].
 *
 * **Synced lines** are followed with [AppleLibraryLyricsPublication.layer]'s core cursor
 * (`cursorAtMilliseconds`), never a shell-side rule.
 */
public class AppleLibraryLyricsClient(
    private val reader: AppleLibraryReaderClient,
    /** The person's languages, most preferred first (BCP 47 or ISO 639), for the layer choice. */
    private val preferredLanguages: List<String>,
) {
    // Reader-thread state.
    private var lyrics: LibraryLyrics? = null

    /** The stored document with no request — the first frame of a lyrics panel, `cached` or `unavailable`. */
    public fun cached(
        track: AppleLibraryLyricsTrack,
        completion: (AppleLibraryLyricsPublication) -> Unit,
    ): AppleLibraryReaderOperation = run(track, completion) { session, core ->
        (lyrics ?: provisional(session)).cached(core)
    }

    /**
     * Reads live when online and publishes from the cache; offline, or when the read fails, the
     * stored document with the reason, or `unavailable` with nothing stored (§18.4).
     */
    public fun read(
        track: AppleLibraryLyricsTrack,
        completion: (AppleLibraryLyricsPublication) -> Unit,
    ): AppleLibraryReaderOperation = run(track, completion) { session, core ->
        withLyrics(session, core) { it.read(core) }
    }

    /** [read] at the person's request: a Retry control, admitted past the breaker as its trial (§10.4). */
    public fun retry(
        track: AppleLibraryLyricsTrack,
        completion: (AppleLibraryLyricsPublication) -> Unit,
    ): AppleLibraryReaderOperation = run(track, completion) { session, core ->
        withLyrics(session, core) { it.retry(core) }
    }

    // ---- Internals ------------------------------------------------------------------------------------

    private fun run(
        track: AppleLibraryLyricsTrack,
        completion: (AppleLibraryLyricsPublication) -> Unit,
        work: suspend (LibraryReaderSession, LyricsTrack) -> LyricsPublication,
    ): AppleLibraryReaderOperation = reader.operation(
        completion,
        failed = { kind -> failedPublication(track.rawId, kind) },
    ) { session ->
        if (track.rawId.isBlank()) return@operation failedPublication(track.rawId, "input")
        work(session, LyricsTrack(track.rawId, track.artist, track.title)).toApple(track.rawId)
    }

    /**
     * The session's lyrics once the endpoint is known. Offline, nothing can be asked, and the
     * core's read publishes the stored document as offline before it looks at the endpoint.
     */
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
        val sent = session.reader.send("getOpenSubsonicExtensions")
        val response = sent.response
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

    /**
     * Lyrics over the store only, for a moment the endpoint cannot be known: its `cached`, and a
     * `read` while offline, never touch the endpoint. Never kept.
     */
    private fun provisional(session: LibraryReaderSession): LibraryLyrics =
        session.lyrics(capabilities(emptyMap(), legacy = false), preferredLanguages)
}

/** What the shell knows about the track: its opaque id, and what the legacy `getLyrics` is keyed by. */
public class AppleLibraryLyricsTrack(
    public val rawId: String,
    public val artist: String?,
    public val title: String?,
)

/** One line as drawn: its text, and when it becomes current (offset applied), null when untimed. */
public class AppleLibraryLyricsLine internal constructor(
    public val text: String,
    public val effectiveStartMilliseconds: Long?,
)

/**
 * One lyrics publication (§18.4).
 *
 * - [state]: `lyrics` (a layer to show: [lines], [synced], [layer]), `none` (the server has no
 *   displayable lyrics for this track — say so, never spin) or `unavailable` (nothing stored and
 *   nothing could be read; [freshness] says why).
 * - [freshness]: the windows' vocabulary — `live`, `cached` with its reason and age, or
 *   `unavailable` with its reason.
 * - [layer]: the core's selected layer, for `cursorAtMilliseconds` as playback progresses.
 * - [trimmed]: layers of the server's answer did not fit the caps and were left out; say so.
 * - [breakerOpen]: the endpoint's breaker is open and nothing was sent (§10.4).
 * - [errorKind]: `cancelled`, `closed`, `input` or `internalFailure` when the read never ran.
 */
public class AppleLibraryLyricsPublication internal constructor(
    public val trackRawId: String,
    public val state: String,
    public val freshness: AppleLibraryReaderFreshness,
    public val synced: Boolean,
    public val lines: List<AppleLibraryLyricsLine>,
    public val language: String?,
    public val layer: LyricsLayer?,
    public val trimmed: Boolean,
    public val breakerOpen: Boolean,
    public val errorKind: String?,
)

// ---- Mapping -----------------------------------------------------------------------------------------------

/** The stored document's publication, re-stated as the failure that kept it from being read. */
private fun LyricsPublication.failedWith(error: DomainError): LyricsPublication = copy(
    freshness = when (val current = freshness) {
        is LibraryFreshness.Cached -> LibraryFreshness.Cached(current.asOfWall, LibraryCachedReason.Failed(error))
        else -> LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(error))
    },
)

internal fun LyricsPublication.toApple(trackRawId: String): AppleLibraryLyricsPublication {
    val selected = lyrics?.selected
    val state = when {
        lyrics == null -> "unavailable"
        selected == null -> "none"
        else -> "lyrics"
    }
    return AppleLibraryLyricsPublication(
        trackRawId = trackRawId,
        state = state,
        freshness = freshness.toApple(),
        synced = selected?.synced == true,
        lines = selected?.lines.orEmpty().map { line ->
            AppleLibraryLyricsLine(line.text, if (selected!!.synced) selected.effectiveStartMilliseconds(line) else null)
        },
        language = selected?.language,
        layer = selected,
        trimmed = lyrics?.isTrimmed == true,
        breakerOpen = breakerOpen,
        errorKind = null,
    )
}

private fun failedPublication(trackRawId: String, errorKind: String) = AppleLibraryLyricsPublication(
    trackRawId = trackRawId,
    state = "unavailable",
    freshness = LibraryFreshness.Unavailable(
        if (errorKind == "internalFailure") LibraryUnavailableReason.InternalFailure else LibraryUnavailableReason.NotCachedOffline,
    ).toApple(),
    synced = false,
    lines = emptyList(),
    language = null,
    layer = null,
    trimmed = false,
    breakerOpen = false,
    errorKind = errorKind,
)
