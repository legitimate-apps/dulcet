package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The reader of spec §16.8–§16.20, one per account: live source, seen-cache, catalog epoch,
 * windows, detail look-ahead and eviction. Every screen is published FROM the cache, online and
 * offline alike; a live response is written through first and never rendered beside a cached one.
 *
 * **Threading — confined, and enforced (§16.18).** A reader lives on ONE dedicated serial thread
 * that is never the main thread: production code runs it on [newLibraryReaderDispatcher]. It is
 * created on that thread, and every entry point — here and on every handle — checks that it is
 * called there and throws otherwise. All database work, including eviction, therefore happens off
 * the main thread, and nothing here needs a lock. Listeners are called on the reader's thread; the
 * platform facade hops each publication to the main thread. Nothing raised inside the reader
 * escapes [scope]: a handle converts a failure into a publication that says so, and
 * [uncaughtFailures] records anything that still reaches the backstop handler.
 *
 * **Hooks for other phases**, each a small interface with an inert default:
 * [LibraryMutationOverlay] (R1d: pending stars and ratings overlaid at publish time, §16.20),
 * [DownloadedTrackSource] (R4: playability and the downloaded-album recheck),
 * [ReconnectOutboxes] (the outbox flush that comes first on reconnect, §16.14), and
 * [LibraryPlaylistOverlay] (pending playlist edits overlaid on the playlist screens, §18.6).
 */
internal class LibraryReader(
    internal val cache: BoundSeenCache,
    private val transport: LibraryEndpointTransport,
    scope: CoroutineScope,
    internal val config: LibraryReaderConfig = LibraryReaderConfig(),
    internal val overlay: LibraryMutationOverlay = LibraryMutationOverlay.None,
    internal val downloads: DownloadedTrackSource = DownloadedTrackSource.None,
    private val outboxes: ReconnectOutboxes = ReconnectOutboxes.None,
    internal val playlistOverlay: LibraryPlaylistOverlay = LibraryPlaylistOverlay.None,
    /**
     * Observed endpoint health for this account (§10.4). The reader holds it because the reader
     * owns [online], and the breaker is reset on the one offline-to-online transition, whichever
     * entry point takes it ([setOnline] or [reconnect]).
     */
    internal val breaker: EndpointCircuitBreaker = EndpointCircuitBreaker(),
) {
    private val owner = currentThreadIdentity()

    /** Failures that reached the backstop handler instead of a publication; empty when healthy. */
    internal val uncaughtFailures = mutableListOf<Throwable>()

    /**
     * This session's lyrics reads (§18.4): the verdicts on answers refused as too large, the
     * requests that timed out, and the reads in flight that an automatic read of the same track
     * joins. Held by the reader for the reason [breaker] is: state per lyrics object would start
     * empty every time a shell asked for one. A reconnect keeps the verdicts — the size belongs to
     * the file, not the network — and forgets the timeouts, which are stamped with the breaker's
     * reset generation. After a reconnect no read joins a request admitted before it: flights are
     * stamped with the same generation.
     */
    internal val lyricsReads: LyricsReads = LyricsReads()

    /**
     * The reader's own scope: the caller's dispatcher (the reader's thread), a supervisor so one
     * failing read never cancels its siblings, and a handler so nothing escapes to the process.
     */
    internal val scope: CoroutineScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, failure -> uncaughtFailures += failure },
    )

    private val permits = Semaphore(config.serverConcurrency)
    private val handles = mutableListOf<ReaderHandle>()
    internal val lookAhead = DetailLookAhead(this)

    /** One writer per list: two handles on the same list serialize their live operations. */
    private val listLocks = mutableMapOf<String, Mutex>()

    internal fun listLock(listKey: String): Mutex = listLocks.getOrPut(listKey) { Mutex() }

    /**
     * Whether the reader is CONNECTED (§16.14): every window, search and look-ahead reads the server
     * only while this is true, and offline issues no request. It becomes true in exactly one place
     * — [reconnect], after the outbox flush and a successful epoch read — and false at once when the
     * platform reports the server unreachable, or when a step after that reconnect's transition
     * throws. A new reader starts connected.
     */
    val online: Boolean get() = connected

    private var connected = true

    /**
     * The only way [online] changes. Coming back online resets [breaker]: failures observed before
     * the network went away say nothing about an endpoint after it came back (§10.4). The one
     * offline-to-online transition is a reconnect's, whichever entry point requested it.
     */
    private fun changeOnline(value: Boolean) {
        if (value && !connected) breaker.reset()
        connected = value
    }

    /**
     * The platform's latest reachability report, and only that: nothing else writes it — a
     * [reconnect] does not — so after a failed reconnect the reader acts on the platform's last
     * report. See [canSend].
     */
    var reachable: Boolean = true
        private set

    /**
     * Whether the outbox may send now: the platform last reported the server [reachable], or the
     * reader is [online] (a successful reconnect made it so), or a reconnect is running — its flush
     * is its first step (§16.14 step 1), so user-authored data leaves before the epoch read and
     * before anything else is read. False after a failed reconnect while the platform's last report
     * said unreachable, so a tap is then kept, not sent.
     */
    internal val canSend: Boolean get() = reachable || online || inFlightReconnect != null

    /** The reconnect running now; every reconnect requested meanwhile joins it. */
    private var inFlightReconnect: Deferred<ReaderConnectionOutcome>? = null

    /** Low Data Mode or a metered connection: no speculative reads (§16.13). */
    var networkConstrained: Boolean = false
        private set

    /**
     * The latest epoch reading of this session, or null before the first one. Every successful
     * `getScanStatus` — the two-request reading and each page's *after* — updates it, keeping the
     * folder set of the last full reading.
     */
    internal var sessionEpoch: CatalogEpoch? = null
        private set

    /**
     * Whether the latest successful reading had no stamp at all (absent, or the first-scan
     * sentinel). This is the account-level fact §16.12 states once per account; a FAILED reading
     * never sets it, so a transient failure cannot label a server stamp-less.
     */
    val serverReportsNoEpoch: Boolean get() = sessionEpoch?.let { it.stamp == null } ?: false

    /** Detail rows read live in this session, for the 60-second revalidation rule. */
    internal val liveDetailReads = mutableMapOf<String, Long>()

    /** Albums whose latest detail read ran while the server was scanning: unverified (§16.12). */
    internal val detailsReadWhileScanning = mutableSetOf<String>()

    /** Lists read live in this session, by list key: the wall-clock of the latest live read. */
    internal val liveListReads = mutableMapOf<String, Long>()

    /** When each list last had its last-access refreshed; touches are throttled (review S7). */
    internal val lastTouches = mutableMapOf<String, Long>()

    /** Throws unless called on the reader's own thread (see the class comment). */
    internal fun checkConfined() {
        check(currentThreadIdentity() == owner) {
            "LibraryReader is confined to its own thread; dispatch onto its dispatcher before calling it"
        }
    }

    // ---- Lifecycle ----------------------------------------------------------------------------------

    /**
     * The connect-time epoch reading (two requests). Returns null when it could not be read. While
     * the reader is offline it issues no request and returns null: offline reads the device only,
     * and the epoch is read by the [reconnect] that brings the reader back.
     */
    suspend fun connect(): CatalogEpoch? {
        checkConfined()
        if (!online) return null
        return readEpoch()
    }

    /**
     * [connect], saying how it ended: the epoch THIS call read, or why it read none. While the
     * reader is offline: no request, and [ReaderConnectionOutcome.Failed] with `Unreachable`.
     */
    internal suspend fun connectReporting(): ReaderConnectionOutcome {
        checkConfined()
        if (!online) return ReaderConnectionOutcome.Failed(DomainError.Transport.Unreachable)
        return try {
            readEpochReporting()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            uncaughtFailures += failure
            ReaderConnectionOutcome.InternalFailure
        }
    }

    /**
     * Reconnect or return to the foreground online (§16.14), and the ONLY way back [online]. In this
     * order and nothing else: flush the outboxes (user-authored data first); read the catalog epoch;
     * then — only if that reading succeeded — mark the reader online and revalidate the visible
     * screen: every open handle, then every other visible surface ([addVisibleSurface]: open
     * searches); and, only if the epoch changed, re-read the albums that contain downloads, one at
     * a time. No catch-up walk, no bulk refetch, nothing re-read because it is old.
     *
     * **The offline-to-online transition.** For a reader that was offline, nothing reads the
     * server until the epoch reading has succeeded: it stays offline — every window and search
     * keeps saying so, and a keystroke searches only the device — so no search, page or look-ahead
     * can go out ahead of the flush. A reader that is already online (a foreground reconnect) stays
     * online throughout, and its screens and searches keep reading as usual, so one of their reads
     * can go out before the flush's send: a change made while connected can be overtaken (§18.3).
     *
     * A reading that fails is returned as [ReaderConnectionOutcome.Failed], and nothing after it
     * runs: no screen is revalidated or relabelled. If a step after the transition throws, the
     * reconnect returns [ReaderConnectionOutcome.InternalFailure] and takes the reader offline again
     * — exactly as an unreachable report does — so it is never left online with a screen that was
     * not revalidated. Either way the next reachability report or reconnect runs the whole
     * sequence again.
     *
     * For its own run a reconnect lets the outbox send ([canSend]), but it never overwrites the
     * platform's [reachable] report. One reconnect runs at a time: a call made while one is
     * running joins it and returns its outcome. Cancelling a caller stops only its wait; the
     * platform reporting the server unreachable cancels the reconnect itself, and its callers
     * return [ReaderConnectionOutcome.Failed] with `unreachable`.
     */
    suspend fun reconnect(): ReaderConnectionOutcome {
        checkConfined()
        val run = startReconnect()
        return try {
            run.await()
        } catch (cancelled: CancellationException) {
            // Either this caller was cancelled — rethrown here — or the platform reported the server
            // unreachable, which cancelled the reconnect itself.
            currentCoroutineContext().ensureActive()
            ReaderConnectionOutcome.Failed(DomainError.Transport.Unreachable)
        }
    }

    private fun startReconnect(): Deferred<ReaderConnectionOutcome> {
        inFlightReconnect?.let { return it }
        val run = scope.async(start = CoroutineStart.LAZY) { performReconnect() }
        inFlightReconnect = run
        run.invokeOnCompletion { if (inFlightReconnect === run) inFlightReconnect = null }
        run.start()
        return run
    }

    private suspend fun performReconnect(): ReaderConnectionOutcome {
        var transitioned = false
        return try {
            // Step 1 never stops step 2: an outbox that fails is recorded, and the epoch is still read.
            flushOutboxes()
            val before = (sessionEpoch ?: cache.storedEpoch()?.let(CatalogEpoch::fromStored))?.key
            when (val reading = readEpochReporting()) {
                is ReaderConnectionOutcome.Read -> {
                    if (!online) {
                        changeOnline(true)
                        transitioned = true
                        // Every screen says at once that it is coming back, rather than "offline"
                        // until its turn below: `revalidating` or `loading` where a read is coming.
                        visibleHandles().forEach(ReaderHandle::prepareForReconnect)
                    }
                    visibleHandles().forEach { it.revalidate(RevalidateCause.Reconnect) }
                    revalidateSurfaces()
                    if (before == null || before != reading.epoch.key) recheckDownloadedAlbums()
                    reading
                }
                else -> reading
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The reader's own failure (its database, a hook). Before the transition nothing was
            // marked online; after it, the reader goes offline again, so the next report or
            // reconnect runs the whole sequence rather than finding the reader online.
            uncaughtFailures += failure
            if (transitioned) {
                try {
                    goOffline() // marks the reader offline before anything in it can throw
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (again: Throwable) {
                    uncaughtFailures += again
                }
            }
            ReaderConnectionOutcome.InternalFailure
        }
    }

    /**
     * The in-foreground epoch cadence (§16.11 policy 1): every [LibraryReaderConfig.epochIntervalMillis]
     * while the app is in the foreground and a library screen is open. Nothing reads the epoch in
     * the background. The platform reports foreground transitions; the cadence itself is core policy.
     */
    fun setForeground(foreground: Boolean) {
        checkConfined()
        periodicEpoch?.cancel()
        periodicEpoch = null
        if (!foreground) return
        periodicEpoch = scope.launch {
            while (true) {
                delay(config.epochIntervalMillis)
                if (online && handles.isNotEmpty()) refreshEpoch()
            }
        }
    }

    private var periodicEpoch: Job? = null

    /**
     * Reads the epoch and, if the catalog or the scanning state changed, revalidates the visible
     * screen (§16.11 policy 3) and re-reads the albums that contain downloads (policy 4).
     */
    suspend fun refreshEpoch() {
        checkConfined()
        val previous = sessionEpoch
        val epoch = readEpoch() ?: return
        val changed = previous == null || previous.key != epoch.key
        if (changed || previous?.scanning != epoch.scanning) {
            visibleHandles().forEach { it.revalidate(RevalidateCause.EpochChanged) }
            // Open searches are part of the visible screen (§16.15); each applies its own rule, so
            // an answer read under the older epoch is re-read quietly.
            revalidateSurfaces()
        } else {
            // A window whose stamp kept moving is re-read at the next quiet reading, or its
            // `unverified(changing)` label would outlive the change that caused it.
            visibleHandles().filter { it.awaitsQuietEpoch() }.forEach { it.revalidate(RevalidateCause.EpochChanged) }
        }
        if (changed) recheckDownloadedAlbums()
    }

    /**
     * Reachability, as the platform reports it; call it on every change.
     *
     * - **Unreachable:** the reader is offline at once. Every window republishes — no request —
     *   every other visible surface revalidates (an open search says `deviceOffline`), look-ahead
     *   stops, and a reconnect in flight is cancelled.
     * - **Reachable, while offline:** REQUESTS a [reconnect]; it does not mark the reader online
     *   itself. The reconnect's flush, epoch read and revalidation run in §16.14's order, and a
     *   reconnect already running is joined — so a shell may report reachability and call
     *   [reconnect] in either order, or do only one of them, and nothing is read before the flush.
     * - **Reachable, while online:** no effect.
     */
    fun setOnline(reachable: Boolean) {
        checkConfined()
        this.reachable = reachable
        if (reachable) {
            if (!online) startReconnect()
            return
        }
        inFlightReconnect?.cancel()
        inFlightReconnect = null
        goOffline()
    }

    /** Offline at once: look-ahead stops, every window republishes and every search re-runs on the device. */
    private fun goOffline() {
        if (!online) return
        changeOnline(false)
        lookAhead.cancelAll()
        visibleHandles().forEach { it.republish() }
        revalidateSurfaces()
    }

    /**
     * Registers a visible surface that is not a window — the session's open searches (§16.15).
     * It is revalidated when the reader goes offline, and at a successful reconnect's revalidation
     * step, after the windows — the only way back online — so it is never left saying offline once
     * the reader is connected, and never re-run before the reconnect's flush and epoch read.
     */
    internal fun addVisibleSurface(surface: ReaderVisibleSurface) {
        checkConfined()
        surfaces += surface
    }

    private val surfaces = mutableListOf<ReaderVisibleSurface>()

    private fun revalidateSurfaces() {
        surfaces.toList().forEach { surface ->
            try {
                surface.revalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                uncaughtFailures += failure
            }
        }
    }

    fun setNetworkConstrained(constrained: Boolean) {
        checkConfined()
        networkConstrained = constrained
        if (constrained) lookAhead.cancelAll()
    }

    /**
     * R1d's hook: a pending local change to these entities must be in the next publication. Called
     * on the reader's thread (enforced), it republishes synchronously, before any request.
     */
    fun republishPendingChanges(rawIds: Set<String>) {
        checkConfined()
        visibleHandles().filter { it.mentionsAny(rawIds) }.forEach { it.republish() }
    }

    /**
     * Playlist editing's hook (§18.6): a pending playlist edit must be in the next publication of
     * the playlist list — which may not yet show the playlist at all (a create) — and of every screen
     * mentioning [rawIds]. Synchronous, before any request.
     */
    fun republishPlaylists(rawIds: Set<String>) {
        checkConfined()
        visibleHandles().filter { it.query == LibraryQuery.Playlists || it.mentionsAny(rawIds) }.forEach { it.republish() }
    }

    /**
     * Re-reads a one-response list live after the device itself changed it on the server (a
     * playlist created or deleted), so the cached list and every open screen on it show the server's
     * new answer: through an open handle when there is one — which shares the list's lock — else
     * through a detached window that is never published.
     */
    internal suspend fun rereadList(query: LibraryQuery) {
        val open = visibleHandles().filter { it.query == query }
        if (open.isNotEmpty()) {
            open.first().revalidate(RevalidateCause.Refresh)
            open.drop(1).forEach { it.republish() }
            return
        }
        ListWindow(this, query, ListRequestSpec.of(query)) { }.revalidate(RevalidateCause.Refresh)
    }

    // ---- Opening ------------------------------------------------------------------------------------

    /**
     * Opens one screen. The first publication — cached content if there is any — is delivered
     * synchronously, before this returns and therefore before any request is issued (CONF-76).
     */
    fun open(query: LibraryQuery, listener: (LibraryPublication) -> Unit): LibraryWindowHandle {
        checkConfined()
        val handle: ReaderHandle = when (query) {
            is LibraryQuery.Album -> AlbumDetailWindow(this, query, listener)
            is LibraryQuery.Artist -> CollectionDetailWindow(this, query, listener)
            is LibraryQuery.Playlist -> CollectionDetailWindow(this, query, listener)
            else -> ListWindow(this, query, ListRequestSpec.of(query), listener)
        }
        handles += handle
        handle.start()
        return handle
    }

    /**
     * A home screen: N independent single-page windows, one per row (§16.9). Each row paints from
     * its own cache at once and is replaced when its own read lands; a failing row keeps its cached
     * content with that failure, and the others are unaffected. The rows share the session's epoch
     * reading and never wait for one another.
     */
    fun openHome(
        rows: List<LibraryHomeRow>,
        listener: (rowIndex: Int, LibraryPublication) -> Unit,
    ): LibraryHomeHandle {
        checkConfined()
        require(rows.isNotEmpty())
        val windows = rows.mapIndexed { index, row ->
            ListWindow(this, row.query, ListRequestSpec.homeRow(row, config.homeRowSize)) { listener(index, it) }
        }
        handles += windows
        windows.forEach(ListWindow::start)
        return LibraryHomeHandle(windows)
    }

    internal fun closed(handle: ReaderHandle) {
        handles -= handle
    }

    private fun visibleHandles(): List<ReaderHandle> = handles.toList()

    // ---- Requests -----------------------------------------------------------------------------------

    /**
     * Every request goes through the per-server concurrency bound of §16.5 rule 6. Its issue
     * sequence, and the epoch reading it is checked against (its *before*), are both taken once the
     * request holds a slot and is about to be SENT — never while it waits — so a request that waited
     * is ordered by when it went out, and its *before* is provably earlier than the request.
     * [maxBodyBytes], when given, limits the response body (see [LibraryEndpointTransport.request]).
     * [issued] learns the sequence as the request goes out, so a caller can order an answer the
     * transport refused, which returns no [SentResponse].
     */
    internal suspend fun send(
        endpoint: String,
        parameters: Map<String, String> = emptyMap(),
        maxBodyBytes: Int? = null,
        issued: (Long) -> Unit = {},
    ): SentResponse =
        permits.withPermit {
            issue(issued) {
                if (maxBodyBytes == null) {
                    transport.request(endpoint, parameters)
                } else {
                    transport.request(endpoint, parameters, maxBodyBytes)
                }
            }
        }

    /** A sent request whose envelope must be `ok`; a failure envelope throws its [DomainError]. */
    internal suspend fun sendChecked(endpoint: String, parameters: Map<String, String> = emptyMap()): SentResponse =
        send(endpoint, parameters).requireOk(endpoint, parameters)

    /** [sendChecked] for parameters that repeat a name, in order (playlist edits, §18.6). */
    internal suspend fun sendRepeatedChecked(
        endpoint: String,
        parameters: List<Pair<String, String>>,
        formPost: Boolean,
    ): SentResponse = permits.withPermit {
        issue { transport.requestRepeated(endpoint, parameters, formPost) }
    }.requireOk(endpoint, emptyMap())

    /**
     * Runs [block] holding ONE slot of the per-server bound for its whole length: every request it
     * sends through the [HeldSlot] goes out on that slot, one after another, and no other request of
     * this reader is sent in between. Playlist editing holds one from its re-read to its write, so
     * the moment in which another client's change can land unseen is one round trip (§18.6).
     * [block] must send nothing any other way — with a bound of one, that would wait for ever.
     */
    internal suspend fun <T> withOneSlot(block: suspend (HeldSlot) -> T): T = permits.withPermit { block(HeldSlot()) }

    /** Requests sent on a slot already held by [withOneSlot]. Valid only inside that block. */
    internal inner class HeldSlot internal constructor() {
        suspend fun send(endpoint: String, parameters: Map<String, String>): SentResponse =
            issue { transport.request(endpoint, parameters) }

        suspend fun sendRepeatedChecked(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean): SentResponse =
            issue { transport.requestRepeated(endpoint, parameters, formPost) }.requireOk(endpoint, emptyMap())
    }

    /**
     * Takes the issue sequence and the *before* reading as the request goes out, on a held slot. A
     * failure of the request itself is thrown as a [LibraryRequestFailure]; anything else thrown here
     * — the device's own database failing — is not, so a caller never reports it as the server's.
     */
    private suspend fun issue(
        issued: (Long) -> Unit = {},
        request: suspend () -> LibraryEndpointResponse,
    ): SentResponse {
        val seq = cache.issue()
        issued(seq)
        val before = sessionEpoch?.let { ScanStatusReading(it.lastScan, it.scanning) }
        val response = try {
            request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LibraryRequestFailure) {
            throw failure
        } catch (failure: Throwable) {
            throw LibraryRequestFailure(failure.asReaderError())
        }
        return SentResponse(seq, before, response)
    }

    /** When the current wait ends, on [LibraryReaderConfig.monotonic]; null when none holds. */
    private var busyUntil: ComparableTimeMark? = null
    private var busyRetry: Job? = null

    /**
     * The server asked for quiet (an HTTP 429) with [retryAfter], if it said, answering a request of
     * the outbox whose run of 429s is [run]. Both outbox flushes then wait `max(Retry-After, floor)`,
     * never more than [LIBRARY_BUSY_CAP]: the floor starts at [LIBRARY_BUSY_FLOOR] and doubles with
     * each 429 of the run, so a server answering `Retry-After: 0` — or nothing — is not asked again
     * at once. A wait already running is never shortened: the later end of the two stands, so a 429
     * that another flush meets meanwhile cannot bring a server's longer `Retry-After` forward. Each
     * flush checks the wait before it begins each change, so neither begins a change until the wait
     * has passed, whatever triggers it — a change already under way when the wait begins is not
     * recalled, and may finish its requests; then one flush of every outbox runs, as a reconnect's
     * first step would, and a retry a later end replaced is cancelled. A 429 that does not [count] —
     * its change was withdrawn or undone while the request was out, or is a create deleted here, so
     * nothing of it is queued — still sets the wait, and its flush still counts it as met, so the run
     * goes on; but it neither begins nor lengthens the run. Returns whether this 429 began the run:
     * each outbox tells the person once per run, not once per retry.
     */
    internal fun noteBusy(run: BusyRun, retryAfter: Duration?, count: Boolean = true): Boolean {
        val streak = if (count) run.met() else run.streak
        val doublings = (streak - 1).coerceIn(0, BUSY_MAX_DOUBLINGS)
        val floor = (LIBRARY_BUSY_FLOOR * (1 shl doublings)).coerceAtMost(LIBRARY_BUSY_CAP)
        val wait = maxOf(retryAfter ?: Duration.ZERO, floor).coerceAtMost(LIBRARY_BUSY_CAP)
        val until = config.monotonic.markNow() + wait
        val current = busyUntil
        if (current == null || until > current) {
            busyUntil = until
            // The retry of the shorter wait is replaced; a longer wait keeps its own retry.
            busyRetry?.cancel()
            busyRetry = scope.launch {
                delay(wait)
                if (online) flushOutboxes()
            }
        }
        return count && streak == 1
    }

    /**
     * One authenticated `ping` after a request was refused ACCESS (§18.6 "Failures"): null when it is
     * answered — the refusal was that request's alone, a rule in front of one endpoint or one
     * request, and the change fails on its own — else the ping's own failure, which speaks for the
     * account and is what a flush stops with.
     */
    internal suspend fun pingAfterRefusal(): DomainError? = try {
        sendChecked("ping")
        null
    } catch (thrown: LibraryRequestFailure) {
        thrown.error
    }

    /**
     * [busyFor] as the error a flush stops with. Typed as the supertype on purpose: OBSERVED, a
     * Kotlin/Native build downcast a `DomainError?` variable initialised from a `Server.Busy?`
     * expression inside the suspending flush, and threw when the variable later held another error.
     */
    internal fun busyError(): DomainError? = busyFor()?.let { DomainError.Server.Busy(it) }

    /** How much longer the server asked outbox flushes to wait; null when they may send. */
    internal fun busyFor(): Duration? {
        val left = busyUntil?.let { -it.elapsedNow() } ?: return null
        if (left.isPositive()) return left
        busyUntil = null
        return null
    }

    private suspend fun flushOutboxes() {
        try {
            outboxes.flush()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            uncaughtFailures += failure
        }
    }

    private val bounded = LibraryEndpointTransport { endpoint, parameters -> send(endpoint, parameters).response }

    internal val epochReader = CatalogEpochReader(bounded)

    /** Reads the full epoch, stores it, and returns it; null when it could not be read. */
    internal suspend fun readEpoch(): CatalogEpoch? = (readEpochReporting() as? ReaderConnectionOutcome.Read)?.epoch

    /**
     * Reads the full epoch and stores it, saying why when it could not be read. Only the READ is
     * reported as [ReaderConnectionOutcome.Failed]; a failure to store it throws, as it always has.
     */
    internal suspend fun readEpochReporting(): ReaderConnectionOutcome {
        val epoch = try {
            epochReader.readFull()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            return ReaderConnectionOutcome.Failed(failure.asReaderError())
        }
        adoptEpoch(epoch)
        return ReaderConnectionOutcome.Read(epoch)
    }

    /** The session reading, read now if this session has none (a window cannot open without one). */
    internal suspend fun ensureEpoch(): CatalogEpoch? = sessionEpoch ?: readEpoch()

    /**
     * A page's *after* reading. Every successful one becomes the session's reading — it is the
     * newest thing known about the server, and it is the next page's *before* — keeping the folder
     * set of the last full reading, which is checked only at window open (§16.12's recorded exposure).
     */
    internal fun adoptScanStatus(after: ScanStatusReading): CatalogEpoch {
        val folders = sessionEpoch?.folderIds ?: emptySet()
        return CatalogEpoch(after.lastScan, folders, after.scanning).also(::adoptEpoch)
    }

    private fun adoptEpoch(epoch: CatalogEpoch) {
        if (sessionEpoch == epoch) return
        sessionEpoch = epoch
        cache.saveEpoch(StoredCatalogEpoch(epoch.lastScan, epoch.folderIds, epoch.scanning, cache.now()))
    }

    /** §16.11 rule 4 and §16.14 step 4: the albums that contain downloads, at concurrency 1. */
    private suspend fun recheckDownloadedAlbums() {
        val albums = downloads.downloadedTrackRawIds(cache.serverId)
            .mapNotNull { cache.track(it)?.record?.albumRawId }
            .distinct()
            .sorted()
        for (album in albums) {
            readAlbumDetail(album)
        }
        if (albums.isNotEmpty()) visibleHandles().forEach { it.republish() }
    }

    /**
     * One `getAlbum`, written through. Used by the album screen, look-ahead and the recheck. A
     * detail read while the server scans is stored with no detail epoch — never current — and
     * remembered, so the scan's end re-reads it like a window (§16.12, review finding S2).
     */
    internal suspend fun readAlbumDetail(albumRawId: String): DetailReadResult {
        var seq = 0L
        return try {
            val parameters = mapOf("id" to albumRawId)
            val sent = send("getAlbum", parameters)
            // The sequence is known before the envelope is judged: a not-found is recorded with it.
            seq = sent.issueSeq
            sent.requireOk("getAlbum", parameters)
            val scanning = sent.before?.scanning ?: true
            val epochKey = sessionEpoch?.key
            val (album, tracks) = parseReaderAlbum(sent.response.body, albumRawId)
            val write = cache.writeAlbumDetail(
                CacheWriteStamp(seq, cache.now(), epochKey),
                album,
                tracks,
                detailEpoch = if (scanning) null else epochKey,
            )
            if (scanning) detailsReadWhileScanning += albumRawId else detailsReadWhileScanning -= albumRawId
            liveDetailReads[albumRawId] = cache.now()
            cache.evictIfNeeded()
            if (write.albumGone) DetailReadResult.Gone else DetailReadResult.Read
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            val error = failure.asReaderError()
            if (error.isNotFound() && seq > 0) {
                cache.markAlbumNotFound(seq, albumRawId)
                liveDetailReads[albumRawId] = cache.now()
                DetailReadResult.Gone
            } else {
                DetailReadResult.Failed(error)
            }
        }
    }

    /**
     * Whether a detail was read live under the current epoch, with no scan running, within the
     * revalidation interval — judged by the epoch its MEMBERSHIP was read under (review finding S1).
     */
    internal fun detailIsFresh(rawId: String, detailFetchedEpoch: String?): Boolean {
        val readAt = liveDetailReads[rawId] ?: return false
        val current = sessionEpoch ?: return false
        return detailFetchedEpoch != null && detailFetchedEpoch == current.key &&
            cache.now() - readAt < config.revalidateWithinMillis
    }

    internal fun trackPlayability(rawId: String, downloaded: Set<String>): LibraryPlayability = when {
        rawId in downloaded -> LibraryPlayability.Downloaded
        online -> LibraryPlayability.Streamable
        else -> LibraryPlayability.UnavailableOffline
    }
}

/** A response, the issue sequence it was sent with, and the reading that was current as it went. */
internal class SentResponse(
    val issueSeq: Long,
    val before: ScanStatusReading?,
    val response: LibraryEndpointResponse,
) {
    /** Returns this response when its envelope is `ok`; a failure envelope throws its [DomainError]. */
    suspend fun requireOk(endpoint: String, parameters: Map<String, String>): SentResponse {
        LibraryEndpointTransport { _, _ -> response }.checkedRequest(endpoint, parameters)
        return this
    }
}

/**
 * The dispatcher a production reader runs on: one dedicated thread, never the main one. The facade
 * creates the reader on it and hops publications to the main thread (§16.18).
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
internal fun newLibraryReaderDispatcher(): CloseableCoroutineDispatcher = newSingleThreadContext("dulcet-library-reader")

/** An identity for the calling thread, for [LibraryReader.checkConfined]. */
internal expect fun currentThreadIdentity(): Long

internal sealed interface DetailReadResult {
    data object Read : DetailReadResult
    data object Gone : DetailReadResult
    data class Failed(val error: DomainError) : DetailReadResult
}

internal data class LibraryReaderConfig(
    /** Rows asked for per window page; the protocol caps a page at 500 (§16.2 rule 1). */
    val pageSize: Int = 100,
    /** Rows per home-screen row (ASSUMED size, §16.9). */
    val homeRowSize: Int = 20,
    /** A screen read live under the current epoch this recently is not re-read (ASSUMED, §16.11). */
    val revalidateWithinMillis: Long = 60_000,
    /** Per-server request concurrency (§16.5 rule 6). */
    val serverConcurrency: Int = 4,
    /** Detail look-ahead bounds (§16.13, ASSUMED values). */
    val lookAheadSettleMillis: Long = 300,
    val lookAheadMaxPerViewport: Int = 24,
    val lookAheadInFlight: Int = 2,
    /** How often a torn page is re-read before the window is labelled unverified instead. */
    val maxTearRetries: Int = 3,
    /** The in-foreground epoch cadence while a library screen is visible (ASSUMED, §16.11). */
    val epochIntervalMillis: Long = 5 * 60_000,
    /** How often showing a list refreshes its rows' last access (LRU needs minutes, not frames). */
    val touchIntervalMillis: Long = 60_000,
    /** The monotonic clock a `Retry-After` is measured on; a test passes its scheduler's. */
    val monotonic: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    init {
        require(pageSize in 1..500)
        require(homeRowSize in 1..500)
        require(serverConcurrency > 0 && lookAheadInFlight in 1..serverConcurrency)
        require(lookAheadMaxPerViewport >= 0 && lookAheadSettleMillis >= 0 && maxTearRetries > 0)
        require(epochIntervalMillis > 0)
    }
}

// ---- Queries ------------------------------------------------------------------------------------------

/** The closed set of screens of spec §16.9. Search is not here: it is §16.15's. */
internal sealed interface LibraryQuery {
    data class AlbumList(
        val type: AlbumListType,
        val fromYear: Int? = null,
        val toYear: Int? = null,
        val genre: String? = null,
        val musicFolderId: String? = null,
    ) : LibraryQuery {
        init {
            require(type != AlbumListType.ByYear || (fromYear != null && toYear != null))
            require(type != AlbumListType.ByGenre || !genre.isNullOrBlank())
        }
    }

    data class Artists(val musicFolderId: String? = null) : LibraryQuery
    data class Artist(val rawId: String) : LibraryQuery
    data class Album(val rawId: String) : LibraryQuery
    data object Playlists : LibraryQuery
    data class Playlist(val rawId: String) : LibraryQuery
    data object Starred : LibraryQuery
    data object Genres : LibraryQuery
    data class SongsByGenre(val genre: String, val musicFolderId: String? = null) : LibraryQuery
}

/**
 * `getAlbumList2` types. Activity-ordered ones and `random` are single pages, never offset-paged:
 * their order moves with the person's own plays, which no scan stamp sees, and `random` reseeds.
 */
internal enum class AlbumListType(val wireName: String, val paged: Boolean) {
    AlphabeticalByName("alphabeticalByName", true),
    AlphabeticalByArtist("alphabeticalByArtist", true),
    Newest("newest", true),
    ByYear("byYear", true),
    ByGenre("byGenre", true),
    Recent("recent", false),
    Frequent("frequent", false),
    Highest("highest", false),
    Starred("starred", false),
    Random("random", false),
}

/** One row of a home screen: a single page of an album list, or the favourites. */
internal sealed interface LibraryHomeRow {
    val query: LibraryQuery

    data class Albums(val type: AlbumListType) : LibraryHomeRow {
        override val query: LibraryQuery get() = LibraryQuery.AlbumList(type)
    }

    data object Favourites : LibraryHomeRow {
        override val query: LibraryQuery get() = LibraryQuery.Starred
    }
}

// ---- Publications -------------------------------------------------------------------------------------

/**
 * What a screen shows, computed in the core and copied to each shell (§16.14, §16.18). A window may
 * publish many times — cached, live, after a rebase, after a local change — and nothing about a
 * publication means "the open finished".
 */
internal data class LibraryPublication(
    val query: LibraryQuery,
    /** 1-based, per handle: the order publications were delivered in. */
    val sequence: Int,
    val freshness: LibraryFreshness,
    /** Lists only; null for a detail screen. */
    val coverage: LibraryCoverage?,
    /** The server's `X-Total-Count` when known. */
    val total: Int?,
    /** A detail screen's entity (album, artist, playlist); null for a list. */
    val header: LibraryItem?,
    val items: List<LibraryItem>,
    val itemsState: LibraryItemsState,
    /** Whether [items] are the server's order or a locally sorted offline view (§16.14). */
    val order: LibraryItemsOrder,
    /** After a rebase: where the viewport should be so the first visible item stays first. */
    val anchor: LibraryAnchor? = null,
    /**
     * The server position of `items[0]`. Above zero, rows precede the window that are not loaded:
     * the shell shows a gap there and calls [LibraryWindowHandle.loadBefore] as the person scrolls
     * up (§16.12: a rebased window is open on both sides of the viewport).
     */
    val leadingOffset: Int = 0,
)

/** The freshness of §16.14, plus [Loading], which is only ever published with nothing cached. */
internal sealed interface LibraryFreshness {
    /** Read from the server in this session under the current epoch. */
    data object Live : LibraryFreshness

    /** Served from the seen-cache; [asOfWall] is absent when the age is unknown (seeded rows). */
    data class Cached(val asOfWall: Long?, val reason: LibraryCachedReason) : LibraryFreshness

    /** Nothing cached and a live read in flight: the only state a loading indicator may show. */
    data object Loading : LibraryFreshness

    /** Nothing cached and nothing can be read. A statement of fact, never a spinner. */
    data class Unavailable(val reason: LibraryUnavailableReason) : LibraryFreshness
}

internal sealed interface LibraryCachedReason {
    data object Revalidating : LibraryCachedReason
    data object Offline : LibraryCachedReason
    data class Failed(val error: DomainError) : LibraryCachedReason
    data object Stale : LibraryCachedReason

    /** The reader itself failed while building or refreshing this screen (a defect, not the server). */
    data object InternalFailure : LibraryCachedReason
}

internal sealed interface LibraryUnavailableReason {
    /** Never read on this device, and offline: "Connect to your server to see it." */
    data object NotCachedOffline : LibraryUnavailableReason

    /** The server says it no longer exists (§16.11). */
    data object Gone : LibraryUnavailableReason

    data class Failed(val error: DomainError) : LibraryUnavailableReason

    /** The reader itself failed while building this screen (a defect, not the server). */
    data object InternalFailure : LibraryUnavailableReason
}

/**
 * Coverage of §16.12. [UnverifiedChanging]: the stamp moved on every re-read within the retry
 * bound while no scan was reported — the list is shown, and says it may not be one state.
 */
internal enum class LibraryCoverage { Complete, Open, UnverifiedScanning, UnverifiedNoEpoch, UnverifiedChanging }

/** For a detail screen: whether its child list is shown, still loading, or cannot be shown. */
internal enum class LibraryItemsState { Present, Loading, Unavailable }

internal enum class LibraryItemsOrder { Server, LocalView }

internal enum class LibraryPlayability { Downloaded, Streamable, UnavailableOffline }

internal data class LibraryAnchor(val itemRawId: String?, val index: Int)

/** One row, as flat values. Per-user state is the cached value with pending changes overlaid. */
internal sealed interface LibraryItem {
    val rawId: String

    data class Album(
        override val rawId: String,
        val title: String,
        val artistName: String?,
        val artistRawId: String?,
        val year: Int?,
        val genre: String?,
        val durationMilliseconds: Long?,
        val songCount: Int?,
        val artworkKey: String?,
        val starred: Boolean?,
        val userRating: Int?,
        val playCount: Long?,
        val detailComplete: Boolean,
    ) : LibraryItem

    data class Artist(
        override val rawId: String,
        val name: String,
        val albumCount: Int?,
        val artworkKey: String?,
        val starred: Boolean?,
        val userRating: Int?,
    ) : LibraryItem

    data class Track(
        override val rawId: String,
        val title: String?,
        val albumRawId: String?,
        val albumTitle: String?,
        val artistName: String?,
        val artistRawId: String?,
        val discNumber: Int?,
        val trackNumber: Int?,
        val durationMilliseconds: Long?,
        val sourceContainer: AudioContainer?,
        val artworkKey: String?,
        val starred: Boolean?,
        val userRating: Int?,
        val playCount: Long?,
        val playability: LibraryPlayability,
        val metadataMissing: Boolean,
    ) : LibraryItem

    data class Playlist(
        override val rawId: String,
        val name: String,
        val songCount: Int?,
        val durationMilliseconds: Long?,
        val owner: String?,
        val artworkKey: String?,
        val comment: String? = null,
        /** Null when the server did not say; Navidrome omits a `false`. */
        val isPublic: Boolean? = null,
        /**
         * Whether this account may edit it: the server said `readonly: false`, or — for a server that
         * does not say — the account owns it, its [owner] compared ignoring case. Never true for
         * another user's playlist (§18.6). REQUIREMENT on the shells, not yet met by any: show
         * [owner], and present a playlist that is not editable as read-only — no edit affordance at
         * all, never one that fails when used — so another user's playlist is recognisable as theirs.
         */
        val editable: Boolean = false,
        /** Edits made on this device that the server has not yet confirmed are shown (§18.6). */
        val pendingChanges: Boolean = false,
        /** Created on this device and not yet on the server: its id is a local one, never sent. */
        val local: Boolean = false,
    ) : LibraryItem

    data class Genre(override val rawId: String) : LibraryItem
}

// ---- Handles ------------------------------------------------------------------------------------------

internal interface LibraryWindowHandle {
    val query: LibraryQuery

    /** Reads the next page of a paged list, at most one page beyond the viewport. */
    fun loadMore()

    /**
     * Reads the page before a window that does not start at the top (after a rebase), at most one
     * page before the viewport — the other half of "open on both sides" (§16.12).
     */
    fun loadBefore()

    /**
     * The visible range, as indexes into the latest publication's [LibraryPublication.items].
     * Drives rebasing around the viewport and detail look-ahead.
     */
    fun setViewport(firstIndex: Int, lastIndex: Int)

    /** An explicit refresh: re-reads the visible pages whatever their age. */
    fun refresh()

    /** Idempotent; cancels this handle's in-flight reads. Nothing is published after it. */
    fun close()
}

/** Every method is confined to the reader's thread, like the handles it delegates to. */
internal class LibraryHomeHandle(private val rows: List<LibraryWindowHandle>) {
    fun row(index: Int): LibraryWindowHandle = rows[index]

    fun refresh() = rows.forEach(LibraryWindowHandle::refresh)

    fun close() = rows.forEach(LibraryWindowHandle::close)
}

internal enum class RevalidateCause { Open, Reconnect, Refresh, EpochChanged }

/**
 * How a connect-time epoch reading or a [LibraryReader.reconnect] ended. After [Failed] or
 * [InternalFailure] a reconnect's flush may already have sent changes. After [Failed] nothing after
 * the reading ran: a reader that was offline is still offline. After [InternalFailure] a reader
 * that was offline is offline again — if the failure came after the transition, every screen was
 * republished and every search re-run on the device, as for an unreachable report — and a reader
 * that was already online stays online, with whatever its screens' own revalidations published.
 */
internal sealed interface ReaderConnectionOutcome {
    /** THIS call read the epoch. After a reconnect, the reader is online and the screen revalidated. */
    data class Read(val epoch: CatalogEpoch) : ReaderConnectionOutcome

    /** The server could not be read, with [error]. */
    data class Failed(val error: DomainError) : ReaderConnectionOutcome

    /** The reader itself failed (its database, a hook); recorded in `uncaughtFailures`. */
    data object InternalFailure : ReaderConnectionOutcome
}

// ---- Hooks --------------------------------------------------------------------------------------------

/** A pending local change to one entity's user state (§16.20). Null fields are not pending. */
internal data class PendingUserState(val starred: Boolean? = null, val userRating: Int? = null)

/**
 * R1d implements this over `mutation_outbox`: the latest pending value per field, overlaid at
 * publish time. The cache row keeps what the server last said; the overlay is never written into it.
 */
internal fun interface LibraryMutationOverlay {
    /** Keyed by kind AND id: ids of different kinds may be equal (an artist and an album both `4`). */
    fun pending(serverId: String, targets: Set<CacheListMember>): Map<CacheListMember, PendingUserState>

    companion object {
        val None = LibraryMutationOverlay { _, _ -> emptyMap() }
    }
}

/**
 * Playlist editing (§18.6) implements this over `mutation_outbox`: pending creates, deletes, header
 * and entry changes, overlaid on the playlist screens at publish time. Like [LibraryMutationOverlay]
 * it is never written into the cache.
 */
internal interface LibraryPlaylistOverlay {
    /** The id a playlist screen reads: a playlist created here resolves to its server id once known. */
    fun resolve(rawId: String): String = rawId

    /** Whether [rawId] names a playlist not yet created on the server — an id that is never sent. */
    fun isLocal(rawId: String): Boolean = false

    /** The playlist list with pending creates, deletes and header changes applied. */
    fun overlayList(items: List<LibraryItem>): List<LibraryItem> = items

    /**
     * One playlist: its header and entries with pending changes applied. [entries] is null when the
     * server's entries are not cached; the result's entries are null when still unknown.
     */
    fun overlayDetail(rawId: String, header: LibraryItem.Playlist?, entries: List<LibraryItem>?): PlaylistOverlayView =
        PlaylistOverlayView(header, entries, deletedLocally = false)

    companion object {
        val None: LibraryPlaylistOverlay = object : LibraryPlaylistOverlay {}
    }
}

internal data class PlaylistOverlayView(
    val header: LibraryItem.Playlist?,
    val entries: List<LibraryItem>?,
    /** Deleted on this device: the screen says the playlist is gone before the server confirms it. */
    val deletedLocally: Boolean,
)

/** R4 implements this over the `download` table: the tracks whose files are complete. */
internal fun interface DownloadedTrackSource {
    fun downloadedTrackRawIds(serverId: String): Set<String>

    companion object {
        val None = DownloadedTrackSource { emptySet() }
    }
}

/**
 * A visible surface that is not a window: the session's open searches. [revalidate] runs on the
 * reader's thread, when the reader goes offline and at a successful reconnect's revalidation step
 * (§16.14 step 3), after the windows.
 */
internal fun interface ReaderVisibleSurface {
    fun revalidate()
}

/** The scrobble and mutation outboxes, flushed first on reconnect (§16.14 step 1). */
internal fun interface ReconnectOutboxes {
    suspend fun flush()

    companion object {
        val None = ReconnectOutboxes { }
    }
}

// ---- Errors -------------------------------------------------------------------------------------------

internal fun Throwable.asReaderError(): DomainError = when (this) {
    is TimeoutCancellationException -> DomainError.Transport.Timeout
    is LibraryRequestFailure -> error
    is AuthenticatedEndpointFailure -> error
    else -> mapAccountConnectionFailure(this)
}

/**
 * 401, 403 or 407 with no envelope: the server or a proxy in front of it refused ACCESS —
 * credentials, a proxy's own authentication, or a block. Whether every other change would meet it
 * too is not known from one request; [LibraryReader.pingAfterRefusal] asks.
 */
internal val DomainError.Server.HttpStatus.refusesAccess: Boolean get() = status == 401 || status == 403 || status == 407

/**
 * A refusal of ACCESS rather than of the change: credentials refused (envelope code 40 or 41, or a
 * bare 401, which reads as `Auth.InvalidCredentials` whoever sent it), or a bare 403 or 407. Code 50
 * (`Auth.Forbidden`) is not: it refuses this change for this user, and is a refusal.
 */
internal val DomainError.refusesAccess: Boolean
    get() = (this is DomainError.Auth && this != DomainError.Auth.Forbidden) || (this is DomainError.Server.HttpStatus && refusesAccess)

/**
 * One outbox's run of 429s (§18.6 "Failures"). It begins with the first 429 that outbox meets for a
 * change still queued — a 429 for one withdrawn or undone while its request was out begins nothing,
 * though its flush met it and so it keeps a run going — and ends when a flush of that outbox sends
 * something and meets no 429, when a flush finishes with nothing pending (defensive: every way a
 * queue empties here already ends the run), or when the queue empties here ([end]) — never on one
 * delivery, so a limiter that admits one request per window is one run: told once, its floor
 * doubling throughout ([LibraryReader.noteBusy]).
 */
internal class BusyRun {
    /** The 429s met in this run; 0 between runs. */
    var streak: Int = 0
        private set

    fun met(): Int {
        streak += 1
        return streak
    }

    fun end() {
        streak = 0
    }

    /** A flush of the outbox finished: [met429] this flush, having [sent] requests, with [pending] changes left. */
    fun flushed(met429: Boolean, sent: Int, pending: Int) {
        if (pending == 0 || (!met429 && sent > 0)) end()
    }
}

/** The first wait after a 429, doubled for each further 429 of the run (§18.6 "Failures"). ASSUMED. */
internal val LIBRARY_BUSY_FLOOR: Duration = 2.seconds

/**
 * The longest a flush waits after a 429, whatever `Retry-After` says (§18.6 "Failures"): a longer or
 * absurd value is read as this. ASSUMED: asking again after five minutes costs one request.
 */
internal val LIBRARY_BUSY_CAP: Duration = 5.minutes

/** The doublings after which the floor is past [LIBRARY_BUSY_CAP] anyway; bounds the shift. */
private const val BUSY_MAX_DOUBLINGS = 16

/** 413 or 414: the request was too large for the server or a proxy. The same request never fits. */
internal val DomainError.Server.HttpStatus.tooLarge: Boolean get() = status == 413 || status == 414

/**
 * A gateway that could not reach the server — as unreachable as no answer at all: 502, 503 or 504,
 * and the origin errors a CDN in front of the server answers with, 520 to 524 and 530 (OBSERVED
 * 2026-09-24, each listed at
 * https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/).
 * Like any 5xx it proves nothing about whether the server applied the request ([provesNotApplied]).
 */
internal val DomainError.Server.HttpStatus.gatewayCannotReachServer: Boolean
    get() = status in 502..504 || status in 520..524 || status == 530

/**
 * A 4xx refused the request before anything handled it; a 5xx may come from a gateway after the
 * server applied it, so it proves nothing.
 */
internal val DomainError.Server.HttpStatus.provesNotApplied: Boolean get() = status in 400..499

/** Subsonic code 70, "the requested data was not found". */
internal fun DomainError.isNotFound(): Boolean =
    (this is DomainError.Server.Known && code == 70) || (this is DomainError.Server.Unknown && code == 70)
