package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * The production lyrics path — [LibraryReaderSession], [LibraryLyrics], the seen-cache and the real
 * authenticated transport — opened over a temporary database, for the conformance suite (CONF-42).
 * The reader is internal to the core, so this is how a test in another module drives the code the
 * shells will run rather than a copy of it; nothing here is a second implementation.
 */
public object LyricsContract {
    /** Opens a session. Every call on it runs on the session's own reader thread. */
    public suspend fun open(request: LyricsControlRequest): LyricsControlSession =
        LyricsControlSession.open(request)
}

/** Credentials are copied, used for requests and never rendered. */
public data class LyricsControlRequest(
    val providerInstanceId: String,
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
    /** The account's negotiated capability set; the endpoint gate reads it. */
    val capabilities: CapabilitySet,
    val preferredLanguages: List<String>,
) {
    override fun toString(): String = "LyricsControlRequest(<redacted>)"
}

/** How a publication was served, flattened for assertions. */
public enum class LyricsControlFreshness { Live, CachedOffline, CachedFailed, CachedStale, Unavailable }

/**
 * One publication plus the requests it caused. [endpoints] lists every `/rest` endpoint the call
 * sent, in order, so a test can prove an offline read sent nothing and name which endpoint answered.
 */
public data class LyricsControlPublication(
    val lyrics: TrackLyrics?,
    val freshness: LyricsControlFreshness,
    val failure: DomainError?,
    val endpoints: List<String>,
    /**
     * The server answered this read with code 70, "not found": what proves a test saw that answer,
     * since a live document with no layers is also what a track without lyrics reads as.
     */
    val notFound: Boolean = false,
)

public class LyricsControlSession private constructor(
    private val database: LibrarySyncControlDatabase,
    private val dispatcher: kotlinx.coroutines.CloseableCoroutineDispatcher,
    private val scope: CoroutineScope,
    private val transport: RecordingLyricsTransport,
    private val session: LibraryReaderSession,
    private val lyrics: LibraryLyrics,
) {
    /** The endpoint the gate chose: `getLyricsBySongId` or `getLyrics`. */
    public val endpointName: String get() = lyrics.endpoint.endpointName

    /** Whether the structured read asks for the v2 (`enhanced`) shape. */
    public val requestsEnhanced: Boolean get() = lyrics.endpoint == LyricsEndpoint.SongLyricsEnhanced

    /**
     * Lyrics requests this session sent WITHOUT a response size limit. Every lyrics request carries
     * one (§18.4), through the transport that stops reading at it; a nonzero count means a layer
     * between the reader and that transport dropped the limit. The reader's own requests — a
     * reconnect's epoch read — carry no lyrics limit and are not counted.
     */
    public val requestsWithoutSizeLimit: Int get() = transport.unlimited

    public suspend fun read(trackRawId: String, artist: String?, title: String?): LyricsControlPublication =
        withContext(dispatcher) {
            val before = transport.sent.size
            val publication = lyrics.read(LyricsTrack(trackRawId, artist, title))
            publication.toControl(transport.sent.drop(before))
        }

    public suspend fun cached(trackRawId: String): LyricsControlPublication = withContext(dispatcher) {
        val before = transport.sent.size
        lyrics.cached(LyricsTrack(trackRawId, null, null)).toControl(transport.sent.drop(before))
    }

    /**
     * The reachability report (§16.14). Unreachable takes the reader offline at once. Reachable
     * while offline requests a reconnect, and this returns once that reconnect has finished: the
     * reader is online if its epoch read succeeded, and otherwise still offline, which the next read
     * publishes. Its requests are all made before this returns, so none is ever in a lyrics call's
     * [LyricsControlPublication.endpoints].
     */
    public suspend fun setOnline(reachable: Boolean): Unit = withContext(dispatcher) {
        session.setOnline(reachable)
        if (reachable && !session.reader.online) session.reader.reconnect()
    }

    public fun close() {
        try {
            scope.cancel()
            transport.close()
        } finally {
            try {
                database.close()
            } finally {
                dispatcher.close()
            }
        }
    }

    internal companion object {
        suspend fun open(request: LyricsControlRequest): LyricsControlSession {
            val database = createLibrarySyncControlDatabase()
            val dispatcher = newLibraryReaderDispatcher()
            try {
                return withContext(dispatcher) {
                    val scope = CoroutineScope(dispatcher)
                    val store = SeenCacheStore(database.primary, SeenCacheWallClock { Clock.System.now().toEpochMilliseconds() })
                    val cache = store.bind(
                        CacheBinding(request.providerInstanceId, request.normalizedBaseUrl, request.username),
                    )
                    val transport = RecordingLyricsTransport(
                        KtorLibraryEndpointTransport(
                            LibraryBrowseRequest(
                                providerInstanceId = request.providerInstanceId,
                                normalizedBaseUrl = request.normalizedBaseUrl,
                                username = request.username,
                                password = request.password,
                                allowLocalHttp = request.allowLocalHttp,
                            ),
                            saltSource = null,
                            logSink = null,
                            hostResolver = systemHostResolver(),
                            operationName = "lyrics.read",
                        ),
                    )
                    // This session reads lyrics and edits no playlist, so whether the server takes
                    // `formPost` is never consulted; false is the value that sends nothing new.
                    // It is not in the foreground, as the playlist contract's session is not: nothing
                    // here is shown to a person, and in the background nothing reads on a timer
                    // (§16.14), so every endpoint a call records is one that call caused.
                    val session = LibraryReaderSession(
                        database.primary.database, cache, transport, scope, formPost = false, foreground = false,
                    )
                    val lyrics = session.lyrics(request.capabilities, request.preferredLanguages)
                    LyricsControlSession(database, dispatcher, scope, transport, session, lyrics)
                }
            } catch (failure: Throwable) {
                try {
                    database.close()
                } finally {
                    dispatcher.close()
                }
                throw failure
            }
        }
    }
}

internal class RecordingLyricsTransport(
    private val delegate: KtorLibraryEndpointTransport,
) : LibraryEndpointTransport {
    val sent = mutableListOf<String>()
    var unlimited = 0
        private set

    override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
        sent += endpoint
        if (LyricsEndpoint.entries.any { it.endpointName == endpoint }) unlimited += 1
        return delegate.request(endpoint, parameters)
    }

    /** Forwarded, not inherited: the inherited overload would read the whole body before limiting. */
    override suspend fun request(
        endpoint: String,
        parameters: Map<String, String>,
        maxBodyBytes: Int,
    ): LibraryEndpointResponse {
        sent += endpoint
        return delegate.request(endpoint, parameters, maxBodyBytes)
    }

    fun close() = delegate.close()
}

private fun LyricsPublication.toControl(endpoints: List<String>): LyricsControlPublication {
    val (freshness, failure) = when (val value = this.freshness) {
        LibraryFreshness.Live -> LyricsControlFreshness.Live to null
        is LibraryFreshness.Cached -> when (val reason = value.reason) {
            LibraryCachedReason.Offline -> LyricsControlFreshness.CachedOffline to null
            is LibraryCachedReason.Failed -> LyricsControlFreshness.CachedFailed to reason.error
            else -> LyricsControlFreshness.CachedStale to null
        }
        is LibraryFreshness.Unavailable ->
            LyricsControlFreshness.Unavailable to (value.reason as? LibraryUnavailableReason.Failed)?.error
        LibraryFreshness.Loading -> LyricsControlFreshness.Unavailable to null
    }
    return LyricsControlPublication(lyrics, freshness, failure, endpoints, notFound)
}
