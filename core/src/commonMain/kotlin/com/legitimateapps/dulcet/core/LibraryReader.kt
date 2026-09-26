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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
     * only while this is true, so offline none of them issues a request. Offline, the only requests
     * are a running reconnect's own (its flush and its epoch read) and the outbox's sends, and those
     * only while [canSend]. [send] enforces it for every request, whenever that request was started:
     * one that reaches the front of the queue after the reader went offline is refused unsent
     * ([ReaderSendRefused]) — the outbox's too, once [canSend] is false — and a page answered after
     * the report sends neither its *after* reading nor a re-anchor's read. A refused read is not a
     * failed one: the screen records no failure, and the read is owed to the next reconnect.
     * It becomes true in exactly one place
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
     * before anything else is read. False only while the platform's last report says unreachable,
     * the reader is offline and no reconnect runs — after a failed reconnect, for instance — so a
     * tap is then kept, not sent.
     */
    internal val canSend: Boolean get() = reachable || online || inFlightReconnect != null

    /** The reconnect running now; every reconnect requested meanwhile joins it. */
    private var inFlightReconnect: Deferred<ReaderConnectionOutcome>? = null

    /**
     * The retry a TRANSIENT failure of a reconnect's epoch read scheduled while the app is in the
     * foreground and the platform reports the server reachable ([scheduleReconnectRetry]); null when
     * none is pending.
     */
    private var reconnectRetry: Job? = null

    /**
     * The wait before the next such retry: [LibraryReaderConfig.reconnectRetryInitialMillis],
     * doubled by each retry scheduled, capped at [LibraryReaderConfig.reconnectRetryMaxMillis], and
     * reset by a reconnect that reads the epoch and by a return to the foreground. A server's
     * `Retry-After` is a floor under it. A duration measured by the coroutine clock (the monotonic
     * one, as the epoch cadence's); it lives in memory only and is never persisted.
     */
    private var reconnectRetryDelayMillis: Long = config.reconnectRetryInitialMillis

    /**
     * Whether the platform last reported the app in the foreground ([setForeground]). A new reader
     * is not: nothing reads on a timer — the epoch cadence or a reconnect retry — until the platform
     * says the app is in the foreground (§16.11: nothing reads the epoch in the background).
     */
    var foreground: Boolean = false
        private set

    /**
     * Why an offline reader that the platform reports reachable is not coming back by itself: its
     * last reconnect failed in a way no timer retries — the server refused it (authentication,
     * security, not a Subsonic server, an incompatible protocol) or the reader itself failed. Every
     * screen says so instead of `offline`, until the next reachability report, return to the
     * foreground or reconnect settles it. Null otherwise; always null while [online].
     */
    internal var standstill: ReaderStandstill? = null
        private set

    /**
     * True from a reconnect's offline-to-online transition until its sequence has run every step:
     * no screen says `live` meanwhile — one whose read is coming says `revalidating` (or `loading`),
     * and one with nothing coming publishes nothing — and at the end of the sequence every screen is
     * published once more: `live` if the sequence completed, what failed if it did not. A failed
     * sequence therefore never shows a screen `live` and then takes it back.
     */
    internal var completingReconnect: Boolean = false
        private set

    /**
     * The epoch key under which the §16.14 / §16.11 sequence last ran EVERY step — the visible
     * screen revalidated and the downloaded albums rechecked — or null before any sequence started.
     * [reconnect] and [refreshEpoch] decide "the epoch changed" against this, never against
     * [sessionEpoch]: a sequence that adopted a reading and then failed has not completed, so the
     * next one still sees the change and runs every step again, the recheck included.
     */
    private var completedSequence: CompletedSequence? = null

    /** [completedSequence]'s value: [epochKey] is null when no epoch was known when it was pinned. */
    private data class CompletedSequence(val epochKey: String?)

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
     * From the transition until the last step, no screen says `live` ([completingReconnect]): the
     * reader is back online only when the whole sequence has run.
     *
     * A reading that fails is returned as [ReaderConnectionOutcome.Failed], and nothing after it
     * runs: no screen is revalidated. If a step after the transition throws, the reconnect returns
     * [ReaderConnectionOutcome.InternalFailure] and takes the reader offline again — exactly as an
     * unreachable report does — so it is never left online with a screen that was not revalidated.
     * A reading is adopted only once it is stored, and "the epoch changed" is judged against the
     * epoch at which the sequence last COMPLETED ([completedSequence]), so the next sequence after
     * any failure runs every step again, the downloaded-album recheck included.
     *
     * **What runs it again, for a reader left offline while the platform reports the server
     * reachable.** A TRANSIENT failure of the epoch read — a timeout, `unreachable`, a busy server
     * (its `Retry-After` a floor under the wait) or an unknown server error — is retried by itself
     * after a bounded backoff, but only while the app is in the foreground ([scheduleReconnectRetry]).
     * Any other failure — authentication, security, not a Subsonic server, an incompatible protocol,
     * or the reader's own — is never retried on a timer: it waits for the next reachability report,
     * the next return to the foreground or a reconnect the person asks for, and every screen says
     * what it is ([standstill]). A reader that was already online stays online; its screens were
     * revalidated or keep saying what failed, and the next epoch-cadence reading or reconnect runs
     * the steps it still owes.
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
        // A reconnect starting now makes a pending retry redundant.
        reconnectRetry?.cancel()
        reconnectRetry = null
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
            // Pinned BEFORE the reading is adopted: if a later step throws, the next sequence still
            // compares against the epoch at which one last completed, and runs every step again.
            val baseline = completedSequence
                ?: CompletedSequence((sessionEpoch ?: cache.storedEpoch()?.let(CatalogEpoch::fromStored))?.key)
            completedSequence = baseline
            // The reconnect's own epoch read is the one read an offline reader sends (§16.14).
            when (val reading = readEpochReporting(reconnectEpochReader)) {
                is ReaderConnectionOutcome.Read -> {
                    if (!online) {
                        changeOnline(true)
                        transitioned = true
                        completingReconnect = true
                        // Every screen says at once that it is coming back, rather than "offline"
                        // until its turn below — `revalidating` or `loading` — and none says `live`
                        // until the sequence has run every step.
                        visibleHandles().forEach(ReaderHandle::prepareForReconnect)
                    }
                    visibleHandles().forEach { it.revalidate(RevalidateCause.Reconnect) }
                    revalidateSurfaces()
                    if (baseline.epochKey == null || baseline.epochKey != reading.epoch.key) recheckDownloadedAlbums()
                    completedSequence = CompletedSequence(reading.epoch.key)
                    reconnectRetryDelayMillis = config.reconnectRetryInitialMillis
                    standstill = null
                    if (transitioned) {
                        completingReconnect = false
                        visibleHandles().forEach(ReaderHandle::republish)
                    }
                    reading
                }
                is ReaderConnectionOutcome.Failed -> {
                    afterFailedRead(reading.error)
                    reading
                }
                else -> reading
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The reader's own failure (its database, a hook). Before the transition nothing was
            // marked online; after it, the reader goes offline again, so the next report or
            // reconnect runs the whole sequence rather than finding the reader online. It is never
            // retried on a timer: a reader that failed once will usually fail the same way again.
            uncaughtFailures += failure
            completingReconnect = false
            try {
                if (transitioned) {
                    if (reachable) standstill = ReaderStandstill.InternalFailure
                    goOffline() // marks the reader offline before anything in it can throw, and republishes
                } else if (!online && reachable) {
                    settle(ReaderStandstill.InternalFailure)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (again: Throwable) {
                uncaughtFailures += again
            }
            ReaderConnectionOutcome.InternalFailure
        }
    }

    /**
     * A reconnect's epoch read failed, and the reader is offline while the platform reports the
     * server reachable. A transient failure is retried by itself, in the foreground; any other is
     * shown for what it is and waits for a report, the foreground or the person.
     */
    private fun afterFailedRead(error: DomainError) {
        if (online || !reachable) return
        val floor = error.automaticRetryFloorMillis()
        if (floor == null) {
            settle(ReaderStandstill.Failed(error))
            return
        }
        settle(null)
        if (foreground) scheduleReconnectRetry(floor)
    }

    /** Sets [standstill], republishing every screen of an offline reader when it changes. */
    private fun settle(next: ReaderStandstill?) {
        if (standstill == next) return
        standstill = next
        if (!online) visibleHandles().forEach(ReaderHandle::republish)
    }

    /**
     * After a transient failure of a reconnect's epoch read left the reader offline, in the
     * foreground, while the platform reports the server reachable: one reconnect after
     * [reconnectRetryDelayMillis] — or [floorMillis], a busy server's `Retry-After`, if longer — the
     * wait then doubled up to its cap (§16.14; the figures are ASSUMED). It runs only if, when it
     * fires, the app is still in the foreground, the server still reported reachable and the reader
     * still offline. Cancelled by a move to the background, an unreachable report, any reconnect
     * starting first, and with the reader's scope when the reader is closed.
     */
    private fun scheduleReconnectRetry(floorMillis: Long) {
        reconnectRetry?.cancel()
        val base = reconnectRetryDelayMillis
        reconnectRetryDelayMillis = minOf(base * 2, config.reconnectRetryMaxMillis)
        val wait = maxOf(base, floorMillis)
        reconnectRetry = scope.launch {
            delay(wait)
            reconnectRetry = null
            if (foreground && reachable && !online) startReconnect()
        }
    }

    /**
     * The in-foreground epoch cadence (§16.11 policy 1): every [LibraryReaderConfig.epochIntervalMillis]
     * while the app is in the foreground and a library screen is open. Nothing reads the epoch in
     * the background. The platform reports foreground transitions; the cadence itself is core policy.
     * One reading that throws (the epoch cannot be stored, a hook fails) is recorded in
     * [uncaughtFailures] and never ends the cadence: the next reading comes at its usual time.
     *
     * The background also stops a pending reconnect retry; a return to the foreground while the
     * reader is offline and the platform reports the server reachable starts a fresh reconnect,
     * with the retry's backoff reset — whatever ended the last one.
     */
    fun setForeground(foreground: Boolean) {
        checkConfined()
        this.foreground = foreground
        periodicEpoch?.cancel()
        periodicEpoch = null
        if (!foreground) {
            reconnectRetry?.cancel()
            reconnectRetry = null
            return
        }
        if (!online && reachable) {
            reconnectRetryDelayMillis = config.reconnectRetryInitialMillis
            startReconnect()
        }
        periodicEpoch = scope.launch {
            while (true) {
                delay(config.epochIntervalMillis)
                if (online && handles.isNotEmpty()) {
                    try {
                        refreshEpoch()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        uncaughtFailures += failure
                    }
                }
            }
        }
    }

    private var periodicEpoch: Job? = null

    /**
     * Reads the epoch and, if the catalog or the scanning state changed, revalidates the visible
     * screen (§16.11 policy 3) and re-reads the albums that contain downloads (policy 4). "Changed"
     * is judged against the epoch at which a sequence last completed ([completedSequence]), so a
     * reconnect or a reading that failed part-way is completed by this one.
     */
    suspend fun refreshEpoch() {
        checkConfined()
        val previous = sessionEpoch
        val baseline = completedSequence ?: CompletedSequence(previous?.key)
        completedSequence = baseline
        val epoch = readEpoch() ?: return
        val changed = baseline.epochKey == null || baseline.epochKey != epoch.key
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
        completedSequence = CompletedSequence(epoch.key)
    }

    /**
     * Reachability, as the platform reports it; call it on every change.
     *
     * - **Unreachable:** the reader is offline at once. Every window republishes — no request —
     *   every other visible surface revalidates (an open search says `deviceOffline`), look-ahead
     *   stops, a reconnect in flight or a retry pending is cancelled, and a [standstill] is cleared:
     *   every screen says `offline`.
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
        reconnectRetry?.cancel()
        reconnectRetry = null
        // Unreachable is now the whole truth: a screen that said why the reader was not coming back
        // says `offline` again.
        settle(null)
        goOffline()
    }

    /** Offline at once: look-ahead stops, every window republishes and every search re-runs on the device. */
    private fun goOffline() {
        completingReconnect = false
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
     *
     * **Offline, nothing is sent (§16.14).** Checked at the same moment, so it holds for a request
     * started before the reader went offline, too: while [online] is false the request is refused
     * unsent with [ReaderSendRefused], unless [whileOffline] says it is one of the two kinds an
     * offline reader sends — the outbox's and a running reconnect's epoch read — AND [canSend] still
     * holds. So an outbox send that waited for a slot across an unreachable report is refused too.
     */
    internal suspend fun send(
        endpoint: String,
        parameters: Map<String, String> = emptyMap(),
        maxBodyBytes: Int? = null,
        issued: (Long) -> Unit = {},
        whileOffline: Boolean = false,
    ): SentResponse =
        permits.withPermit {
            issue(whileOffline, issued) {
                if (maxBodyBytes == null) {
                    transport.request(endpoint, parameters)
                } else {
                    transport.request(endpoint, parameters, maxBodyBytes)
                }
            }
        }

    /** A sent request whose envelope must be `ok`; a failure envelope throws its [DomainError]. */
    internal suspend fun sendChecked(
        endpoint: String,
        parameters: Map<String, String> = emptyMap(),
        whileOffline: Boolean = false,
    ): SentResponse = send(endpoint, parameters, whileOffline).requireOk(endpoint, parameters)

    /** [sendChecked] for parameters that repeat a name, in order (playlist edits, §18.6). */
    internal suspend fun sendRepeatedChecked(
        endpoint: String,
        parameters: List<Pair<String, String>>,
        formPost: Boolean,
    ): SentResponse = permits.withPermit {
        issue(whileOffline = false) { transport.requestRepeated(endpoint, parameters, formPost) }
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
            issue(whileOffline = false) { transport.request(endpoint, parameters) }

        suspend fun sendRepeatedChecked(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean): SentResponse =
            issue(whileOffline = false) { transport.requestRepeated(endpoint, parameters, formPost) }.requireOk(endpoint, emptyMap())
    }

    /**
     * Takes the issue sequence and the *before* reading as the request goes out, on a held slot. A
     * failure of the request itself is thrown as a [LibraryRequestFailure]; anything else thrown here
     * — the device's own database failing — is not, so a caller never reports it as the server's.
     *
     * Every request passes here, so this is where the offline rule of [send] is enforced — for a
     * request on a held slot and a repeated-parameter request as much as a plain one. An outbox's
     * flush runs in [OutboxRequests], so every request it makes — its writes, the reads it makes to
     * decide them, and the `ping` after a refusal — is an outbox request, as [whileOffline] marks
     * one sent any other way.
     */
    private suspend fun issue(
        whileOffline: Boolean,
        issued: (Long) -> Unit = {},
        request: suspend () -> LibraryEndpointResponse,
    ): SentResponse {
        val outbox = whileOffline || currentCoroutineContext()[OutboxRequestsKey] != null
        if (!online && (!outbox || !canSend)) throw ReaderSendRefused()
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
                if (canSend) flushOutboxes()
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

    /** The reconnect's own epoch reader: the one read an offline reader sends besides the outbox's. */
    private val reconnectEpochReader = CatalogEpochReader(
        LibraryEndpointTransport { endpoint, parameters -> send(endpoint, parameters, whileOffline = true).response },
    )

    /** Reads the full epoch, stores it, and returns it; null when it could not be read. */
    internal suspend fun readEpoch(): CatalogEpoch? = (readEpochReporting() as? ReaderConnectionOutcome.Read)?.epoch

    /**
     * Reads the full epoch and stores it, saying why when it could not be read. Only the READ is
     * reported as [ReaderConnectionOutcome.Failed]; a failure to store it throws, as it always has,
     * and then the reading is not adopted ([adoptEpoch]).
     */
    internal suspend fun readEpochReporting(epochs: CatalogEpochReader = epochReader): ReaderConnectionOutcome {
        val epoch = try {
            epochs.readFull()
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

    /**
     * Stores a reading, THEN makes it the session's. If storing throws, the session keeps the reading
     * it had, so the next reading still sees the change and revalidates: a reading the reader could
     * not store never makes a screen that was not re-read look current.
     */
    private fun adoptEpoch(epoch: CatalogEpoch) {
        if (sessionEpoch == epoch) return
        cache.saveEpoch(StoredCatalogEpoch(epoch.lastScan, epoch.folderIds, epoch.scanning, cache.now()))
        sessionEpoch = epoch
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
        } catch (refused: ReaderSendRefused) {
            DetailReadResult.Refused
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

    /** Not sent: the reader went offline while the read waited ([ReaderSendRefused]). Owed, not failed. */
    data object Refused : DetailReadResult
}

/**
 * Marks a coroutine as an outbox's flush (§16.14 step 1, §16.20): every request made in it is one
 * of the kinds an offline reader sends, while [LibraryReader.canSend] holds when the request goes
 * out. Carried by the context rather than a flag on each call, so a read an outbox makes to decide
 * a change, or its `ping` after a refusal, is covered without every helper passing it on.
 */
internal object OutboxRequests : AbstractCoroutineContextElement(OutboxRequestsKey)

internal object OutboxRequestsKey : CoroutineContext.Key<OutboxRequests>

/**
 * [LibraryReader.send]'s refusal of a request that reached the front of the queue after the reader
 * went offline (§16.14). The request was never sent, so it proves nothing about the server: a read
 * refused this way is NOT a failed read — the screen records no failure and keeps saying what it
 * said (offline) — and the read is owed to the next reconnect. Its [error] is `unreachable`, for any
 * caller that does not tell the two apart (the outbox keeps the change, as for any unreachable).
 */
internal class ReaderSendRefused : LibraryRequestFailure(DomainError.Transport.Unreachable)

/**
 * Why an offline reader the platform reports reachable is not coming back by itself
 * ([LibraryReader.standstill]); every screen shows it in place of `offline`.
 */
internal sealed interface ReaderStandstill {
    /** The server refused the reconnect's epoch read in a way no timer retries. */
    data class Failed(val error: DomainError) : ReaderStandstill

    /** The reader itself failed during the reconnect (its database, a hook). */
    data object InternalFailure : ReaderStandstill
}

/**
 * The floor under an automatic reconnect retry's wait, in milliseconds, for a TRANSIENT failure of
 * the epoch read; null for a failure no timer retries (§16.14). The epoch read goes through the
 * library's checked request path ([checkedRequest]), which names what the server or a proxy in
 * front of it answered. Transient are exactly:
 * - a timeout, and `unreachable` (the caller retries only while the platform reports the server
 *   reachable);
 * - `Server.Busy`: an HTTP 429, named from its STATUS whatever the body says — the reference
 *   server's limiter answers with an envelope carrying only the generic code 0 (CLAUDE.md trap 24).
 *   Its `Retry-After` is the floor, read as at most [LIBRARY_BUSY_CAP], as an outbox reads it;
 * - `Server.HttpStatus` from a gateway that could not reach the server
 *   ([gatewayCannotReachServer]: 502, 503, 504, and a CDN's origin errors), typically a reverse
 *   proxy's page while the server restarts;
 * - `Server.Unknown`: an error code the protocol does not define.
 *
 * Everything else needs a person: authentication (a bare 401 included), security, a protocol
 * failure, every other status with no envelope (403 and 407 refuse access, 413 and 414 say the
 * request is too large, a bare 500), and an error envelope with a defined code — the generic code 0
 * at HTTP 200 included, which is the server's answer, not a capacity signal.
 */
internal fun DomainError.automaticRetryFloorMillis(): Long? = when (this) {
    DomainError.Transport.Timeout, DomainError.Transport.Unreachable -> 0L
    is DomainError.Server.Busy -> (retryAfter ?: Duration.ZERO).coerceAtMost(LIBRARY_BUSY_CAP).inWholeMilliseconds
    is DomainError.Server.HttpStatus -> if (gatewayCannotReachServer) 0L else null
    is DomainError.Server.Unknown -> 0L
    else -> null
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
    /**
     * The first wait before a reconnect that failed internally is retried while the server is
     * reported reachable, doubled by each retry up to [reconnectRetryMaxMillis] (ASSUMED, §16.14).
     */
    val reconnectRetryInitialMillis: Long = 2_000,
    val reconnectRetryMaxMillis: Long = 60_000,
) {
    init {
        require(pageSize in 1..500)
        require(homeRowSize in 1..500)
        require(serverConcurrency > 0 && lookAheadInFlight in 1..serverConcurrency)
        require(lookAheadMaxPerViewport >= 0 && lookAheadSettleMillis >= 0 && maxTearRetries > 0)
        require(epochIntervalMillis > 0)
        require(reconnectRetryInitialMillis > 0 && reconnectRetryMaxMillis >= reconnectRetryInitialMillis)
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

    /**
     * An explicit refresh: re-reads the visible pages whatever their age. It does nothing while the
     * reader is offline; a "Try again" for an offline screen must call reconnect instead (§16.14).
     */
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
 * republished and every search re-run on the device, as for an unreachable report — and, while
 * the platform reports the server reachable, retries after a bounded backoff. A reader that was
 * already online stays online, with whatever its screens' own revalidations published. Either way
 * a reading the reader could not store was not adopted, and the next sequence — the retry, a
 * report, a reconnect or an epoch-cadence reading — still sees the epoch change the failed one saw,
 * so it revalidates the visible screen and rechecks the downloaded albums again.
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
