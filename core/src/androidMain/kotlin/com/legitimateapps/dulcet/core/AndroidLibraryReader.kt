package com.legitimateapps.dulcet.core

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android composition root of the reader (spec §16.18, the Android paragraph): one
 * [LibraryReaderSession] per account — the reader, its seen-cache, its favourites and its searches —
 * over the app's database and the live transport.
 *
 * **One per account, per process.** [forAccount] returns the process's reader for an account and
 * creates it the first time; the phone's library and search tabs and the TV's screens therefore
 * share one seen-cache, one reachability model and one favourites overlay. v1 has one active account
 * (CORPUS §5a item 6): asking for a different account closes the previous one, and the new one opens
 * the database only once the previous one has terminated, waiting at most 45 s. A reader counts as
 * terminated only once its own predecessor has too, again waiting at most 45 s, so within those
 * bounds a chain of account changes never has two readers on the database. A reader whose setup
 * failed tries it again at its next call that needs the session — opening a window or search, a
 * favourite or rating change, a reconnect, [pendingChangeCount] or [seenTracks] — and the windows and
 * searches opened while it was failing are opened then. Every other call — on a window or search
 * already open, [addChangeOutcomeListener], a platform report — does not retry it.
 *
 * **Threading, in both directions.** The reader is confined to one dedicated thread
 * ([newLibraryReaderDispatcher]) and checks it at every entry point, so this class builds the session
 * ON that thread and runs every call there, in call order. Every listener call and every completion
 * is delivered on the main thread, and nothing here waits for the reader's thread, so no call blocks
 * the main thread. A publication already queued for the main thread when a window, a search or this
 * reader is closed is dropped there, not delivered.
 *
 * **Nothing throws out of it.** A failure on the reader's thread becomes a publication that says the
 * screen failed — over the content already shown, never instead of it — or an error on a completion.
 *
 * **Reachability — the one supported pattern (§16.14).** Call [setOnline] on every reachability
 * change the platform reports and [reconnect] when the app returns to the foreground online. A
 * reconnect is the only way back online: reporting the server reachable while the reader is offline
 * requests one, a reconnect already running is joined, and nothing is read before its outbox flush
 * and epoch read. The shell never flips the reader online itself.
 *
 * **Credentials** reach only the transport. Nothing published here has a field that can carry a URL,
 * a query string or server error text.
 *
 * The shell exposes these publications to Compose as `StateFlow`s; this public surface uses
 * listeners, so no `Flow` crosses the core's API (§16.18).
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public class AndroidLibraryReader internal constructor(
    /** The account this reader serves; null only for a reader composed by a test. */
    internal val account: AndroidLibraryReaderAccount?,
    private val compose: (CoroutineScope) -> AndroidLibraryReaderComposition,
    private val readerDispatcher: CloseableCoroutineDispatcher,
    mainDispatcher: CoroutineDispatcher,
    /**
     * The reader still closing when this one is created — the one it replaces, or one a sign-out
     * closed: this one composes only once that has terminated, or 45 s have passed.
     */
    predecessor: AndroidLibraryReader? = null,
) {
    /**
     * This reader waits for it before opening the database and, if it had not terminated by then,
     * again before counting as terminated itself. Dropped the moment it terminates, or when this
     * reader's close has finished waiting for it, so a chain of account changes, and the old account,
     * are not kept alive.
     */
    private val predecessor = AtomicReference(predecessor)

    /**
     * Drops [predecessor] once it terminates. Disposed when this reader terminates, so a predecessor
     * that never does cannot keep this reader alive either.
     */
    private val predecessorWatch: DisposableHandle? =
        predecessor?.terminated?.invokeOnCompletion { this.predecessor.compareAndSet(predecessor, null) }

    private val closed = AtomicBoolean(false)

    /** Read at every delivery, on the main thread. */
    internal val isClosed: Boolean get() = closed.get()

    /** Set on the reader's thread while building the session has failed; the next call tries again. */
    private val compositionFailed = AtomicBoolean(false)

    /** Changes discarded at binding (§16.10), once the person has been told: never reported again. */
    private val discardedAcknowledged = AtomicBoolean(false)

    /** The latest failures that reached a last-resort handler instead of a publication, bounded; for tests. */
    internal val uncaughtFailures: BoundedFailures = BoundedFailures()

    private val readerJob = SupervisorJob()

    private val readerScope = CoroutineScope(
        readerJob + readerDispatcher + CoroutineExceptionHandler { _, failure -> uncaughtFailures += failure },
    )

    /** A supervisor, so one listener's failure never stops another's deliveries (CONF-86). */
    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher + CoroutineExceptionHandler { _, _ -> })

    private val terminated = CompletableDeferred<Unit>()

    // ---- Reader-thread state. Touched only inside onReader blocks. -----------------------------------

    private var composition: AndroidLibraryReaderComposition? = null

    // What the platform last reported, applied again to a session built after a failed setup.
    private var reportedForeground = false
    private var reportedReachable: Boolean? = null
    private var reportedConstrained = false

    private val windows = mutableListOf<AndroidLibraryWindow>()
    private val searches = mutableListOf<AndroidLibrarySearch>()
    private val outcomeListeners = mutableListOf<AndroidLibraryOutcomeRegistration>()

    /** Completed by [close], so a reader closed while it waits for its predecessor stops waiting. */
    private val closeRequested = CompletableDeferred<Unit>()

    init {
        onReader {
            val predecessor = this.predecessor.get()
            if (predecessor != null) {
                // The previous reader may still be writing to the same database file. Blocking here
                // is deliberate: every call made meanwhile queues behind this one, on this thread, so
                // none can run against a session that does not exist yet. Bounded, so a wedged
                // predecessor cannot keep this reader from ever opening; and it ends when this reader
                // is closed, so this reader's close is not queued behind it. That close's completion
                // still waits for the predecessor, up to 45 s (stopReaderThread).
                runBlocking {
                    withTimeoutOrNull(PREDECESSOR_WAIT_MILLIS) {
                        select<Unit> {
                            predecessor.terminated.onAwait {}
                            // Closed meanwhile: this reader stops at once. It still counts as
                            // terminated only once the predecessor has (stopReaderThread).
                            closeRequested.onAwait {}
                        }
                    }
                }
            }
            // A reader closed before it was built never opens the database.
            if (!closed.get()) compose()
        }
    }

    /** Reader thread. Builds the session; on failure every entry point answers with a failure. */
    private fun compose() {
        var built: AndroidLibraryReaderComposition? = null
        composition = try {
            compose(readerScope).also { made ->
                built = made
                made.session.favourites.addOutcomeListener(::fanOutOutcome)
                made.session.reader.setNetworkConstrained(reportedConstrained)
                reportedReachable?.let(made.session::setOnline)
                // Last: in the foreground this starts the core's epoch cadence.
                made.session.reader.setForeground(reportedForeground)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A session built but not set up is released, so the next attempt does not open a second
            // one beside it. The throwable's text is dropped, because it may carry the address or
            // credentials.
            try {
                built?.release?.invoke()
            } catch (_: Throwable) {
            }
            null
        }
        compositionFailed.set(composition == null)
    }

    /**
     * Reader thread. The session, built again first if an earlier attempt failed (a database that
     * could not be opened yet). The windows and searches opened while it was failing were told so and
     * hold no session; they are opened now, so whoever holds this reader keeps working screens.
     */
    private fun composed(): AndroidLibraryReaderComposition? {
        if (composition == null && compositionFailed.get() && !closed.get()) {
            compose()
            composition?.let { built ->
                windows.toList().forEach { it.reopen(built) }
                searches.toList().forEach { it.reopen(built) }
            }
        }
        return composition
    }

    // ---- Windows ----------------------------------------------------------------------------------------

    /**
     * Opens one screen. The first publication is the cached content if there is any — built on the
     * reader's thread before any request of this open is issued (CONF-76) — then each later state.
     */
    public fun openWindow(query: AndroidLibraryQuery, listener: (AndroidLibraryPublication) -> Unit): AndroidLibraryWindow {
        val window = AndroidLibraryWindow(this, listener)
        onReader(onDropped = window::emitClosed) {
            window.open(composed()) { reader, publish -> reader.open(query.toCore(), publish) }
        }
        return window
    }

    /**
     * Opens one row of a home screen as its own window: each row paints from its own cache at once,
     * is replaced when its own read lands, and fails on its own (CONF-86).
     */
    public fun openHomeRow(row: AndroidLibraryHomeRow, listener: (AndroidLibraryPublication) -> Unit): AndroidLibraryWindow {
        val window = AndroidLibraryWindow(this, listener)
        onReader(onDropped = window::emitClosed) {
            window.open(composed()) { reader, publish ->
                val home = reader.openHome(listOf(row.toCore())) { _, publication -> publish(publication) }
                window.home = home
                home.row(0)
            }
        }
        return window
    }

    // ---- Search -------------------------------------------------------------------------------------------

    /** Search as you type over what this device has seen and the server (§16.15, §18.1). */
    public fun openSearch(listener: (AndroidLibrarySearchPublication) -> Unit): AndroidLibrarySearch {
        val search = AndroidLibrarySearch(this, listener)
        onReader(onDropped = search::emitClosed) { search.open(composed()) }
        return search
    }

    // ---- Favourites and ratings ---------------------------------------------------------------------------

    /**
     * Makes [target] a favourite or not. The change is in the next publication of every open window
     * and search that shows it, before any request (§16.20, CONF-84); how the send ended arrives on
     * [addChangeOutcomeListener]. Returns false, doing nothing, for a blank id or a closed reader.
     */
    public fun setFavourite(target: AndroidLibraryEntity, favourite: Boolean): Boolean =
        change(target, MutationField.Starred) { favourites, ref -> favourites.setFavourite(ref, favourite) }

    /** Flips the favourite state as shown (an unknown state becomes a favourite). See [setFavourite]. */
    public fun toggleFavourite(target: AndroidLibraryEntity): Boolean =
        change(target, MutationField.Starred) { favourites, ref -> favourites.toggleFavourite(ref) }

    /** A 1–5 rating, or 0 to remove it; any other value returns false. See [setFavourite]. */
    public fun setRating(target: AndroidLibraryEntity, rating: Int): Boolean {
        if (rating !in 0..5) return false
        return change(target, MutationField.Rating) { favourites, ref -> favourites.setRating(ref, rating) }
    }

    /** How each favourite or rating change ended, on the main thread, until the registration is closed. */
    public fun addChangeOutcomeListener(listener: (AndroidLibraryChangeOutcome) -> Unit): AndroidLibraryOutcomeRegistration {
        val registration = AndroidLibraryOutcomeRegistration(this, listener)
        onReader { outcomeListeners += registration }
        return registration
    }

    /**
     * The changes that have not reached the server, for the sign-out offer (§14.7): null when the
     * count cannot be read, never a guessed zero. Completes once, on the main thread.
     */
    public fun pendingChangeCount(completion: (Long?) -> Unit) {
        val delivered = AtomicBoolean(false)
        fun finish(count: Long?) {
            if (delivered.compareAndSet(false, true)) onMain(checkClosed = false) { completion(count) }
        }
        onReader(onDropped = { finish(null) }) {
            val count = try {
                composed()?.session?.favourites?.pendingCount()
            } catch (cancelled: CancellationException) {
                finish(null)
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            finish(count)
        }
    }

    /**
     * What this device has seen of these tracks, read from the seen-cache alone: no request is
     * issued, whatever the reachability. A track never seen, seen only as an identity with no title,
     * or known to be gone from the server is left out. Completes once, on the main thread. Restored Up Next rows take their titles
     * from it, as they did from the whole-library mirror.
     */
    public fun seenTracks(rawIds: List<String>, completion: (List<AndroidTrack>) -> Unit) {
        val delivered = AtomicBoolean(false)
        fun finish(tracks: List<AndroidTrack>) {
            if (delivered.compareAndSet(false, true)) onMain(checkClosed = false) { completion(tracks) }
        }
        onReader(onDropped = { finish(emptyList()) }) {
            val provider = account?.providerInstanceId
            val cache = composed()?.session?.reader?.cache
            val tracks = try {
                if (provider == null || cache == null) {
                    emptyList()
                } else {
                    rawIds.filter { it.isNotBlank() }.distinct().mapNotNull { rawId ->
                        val record = cache.track(rawId)?.takeIf { !it.row.gone }?.record ?: return@mapNotNull null
                        AndroidTrack(
                            providerInstanceId = provider,
                            rawId = rawId,
                            title = record.title,
                            artist = record.credits.firstOrNull()?.name,
                            album = record.albumTitle,
                            durationMilliseconds = record.durationMilliseconds,
                            artworkKey = record.artworkKey,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                finish(emptyList())
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            finish(tracks)
        }
    }

    // ---- Connection lifecycle ------------------------------------------------------------------------------

    /**
     * The app came back to the foreground online (§16.14): the outbox flush, the epoch read, and —
     * only if that read succeeded — back online, the visible screen revalidated (windows, then
     * searches) and the downloaded albums rechecked. Nothing else. A reconnect already running is
     * joined. [completion] says how THIS call ended, on the main thread.
     */
    public fun reconnect(completion: (AndroidLibraryConnection) -> Unit = {}) {
        val delivered = AtomicBoolean(false)
        fun finish(connection: AndroidLibraryConnection) {
            if (delivered.compareAndSet(false, true)) onMain(checkClosed = false) { completion(connection) }
        }
        onReader(onDropped = { finish(closedConnection()) }) {
            val session = composed()?.session
            if (session == null) {
                finish(internalFailureConnection())
                return@onReader
            }
            readerScope.launch {
                val outcome = try {
                    session.reader.reconnect()
                } catch (cancelled: CancellationException) {
                    finish(closedConnection())
                    throw cancelled
                } catch (_: Throwable) {
                    ReaderConnectionOutcome.InternalFailure
                }
                finish(session.connection(outcome, discardedAcknowledged.get()))
            }.invokeOnCompletion { cause -> if (cause != null) finish(closedConnection()) }
        }
    }

    /**
     * Reachability as the platform reports it; call it on every change. Unreachable takes the reader
     * offline at once — no request is issued offline. Reachable while offline REQUESTS a reconnect;
     * the reader is back online only once that reconnect has read the epoch.
     */
    public fun setOnline(reachable: Boolean) {
        // A platform report never retries a failed setup (it arrives often); a later call's will,
        // and applies what was reported.
        onReader {
            reportedReachable = reachable
            composition?.session?.setOnline(reachable)
        }
    }

    /** Foreground transitions: the epoch cadence while in the foreground is core policy (§16.11). */
    public fun setForeground(foreground: Boolean) {
        onReader {
            reportedForeground = foreground
            composition?.session?.reader?.setForeground(foreground)
        }
    }

    /** A metered connection: no speculative reads (§16.13). */
    public fun setNetworkConstrained(constrained: Boolean) {
        onReader {
            reportedConstrained = constrained
            composition?.session?.reader?.setNetworkConstrained(constrained)
        }
    }

    /**
     * The person has been told about the changes discarded at binding (§16.10): no later reconnect
     * reports them again, for the life of this reader, whichever screen asks.
     */
    public fun acknowledgeDiscardedChanges() {
        discardedAcknowledged.set(true)
    }

    /**
     * Closes every window and search, cancels every read and waits for it to finish unwinding,
     * releases the database and transport, and stops the reader's thread. [completion] runs on the
     * main thread when this reader has terminated ([awaitTermination]; §14.7 step 6 waits for it):
     * once the thread's executor has terminated, so no task runs on that thread again, or 30 s have
     * passed; and once the reader still closing when this one was created has terminated too, or 45 s
     * have passed. The waits bound cooperative work only. Tasks already queued on the thread run
     * before the close, and one of them that never finishes, or a release that never returns, holds
     * the close and [completion] indefinitely. Within that: 10 s for cancelled work to unwind (a read
     * that ignores cancellation for longer is released under); 30 s for the executor (if a task that
     * starts after the close task never finishes, [completion] runs after the 30 s anyway, and a
     * coroutine that resumes after the executor has stopped is run by kotlinx on another thread,
     * possibly after [completion]); and 45 s for that earlier reader. Idempotent; a second close's
     * completion waits for the same moment. Nothing is delivered to a listener after this returns.
     */
    public fun close(completion: () -> Unit = {}) {
        if (closed.compareAndSet(false, true)) {
            forget(this)
            closeRequested.complete(Unit)
            try {
                readerScope.launch {
                    val closing = composition
                    composition = null
                    try {
                        windows.toList().forEach(AndroidLibraryWindow::closeFromReader)
                        searches.toList().forEach(AndroidLibrarySearch::closeFromReader)
                        outcomeListeners.clear()
                        closing?.session?.reader?.setForeground(false)
                    } catch (_: Throwable) {
                        // Closing is best effort and exports nothing.
                    }
                    // Cancellation is cooperative: a cancelled read resumes later, to unwind. So every
                    // other coroutine of this reader is cancelled and then WAITED FOR, here on the
                    // reader's thread, before the database and transport are released; otherwise its
                    // cleanup would run against a released store. Repeated until none is left, since
                    // unwinding can launch more. Bounded: a read that ignores cancellation for longer
                    // is released under.
                    val self = coroutineContext[Job]
                    withTimeoutOrNull(CLOSE_DRAIN_MILLIS) {
                        while (true) {
                            val others = readerJob.children.filter { it !== self }.toList()
                            if (others.isEmpty()) break
                            others.forEach { it.cancel() }
                            others.joinAll()
                        }
                    }
                    try {
                        closing?.release?.invoke()
                    } catch (_: Throwable) {
                    }
                }.invokeOnCompletion {
                    readerScope.cancel()
                    stopReaderThread()
                }
            } catch (_: Throwable) {
                readerScope.cancel()
                stopReaderThread()
            }
        }
        terminated.invokeOnCompletion { onMain(checkClosed = false) { completion() } }
    }

    /**
     * Completes once no task can run on the reader's thread again or 30 s have passed since its
     * executor was shut down, and the reader still closing when this one was created has terminated
     * or 45 s have passed (see [close]).
     */
    internal suspend fun awaitTermination() = terminated.await()

    /** Whether [awaitTermination] would return at once; for tests. */
    internal val hasTerminated: Boolean get() = terminated.isCompleted

    // ---- Internals ------------------------------------------------------------------------------------------

    /**
     * Runs [block] on the reader's thread, after every call made before it. If it never runs — the
     * reader is closed, or a close cancels it before its turn — [onDropped] runs instead, once, so a
     * caller waiting on it always hears back.
     */
    internal fun onReader(onDropped: () -> Unit = {}, block: () -> Unit) {
        if (closed.get()) {
            onDropped()
            return
        }
        val ran = AtomicBoolean(false)
        try {
            readerScope.launch {
                ran.set(true)
                block()
            }.invokeOnCompletion { cause -> if (cause != null && !ran.get()) onDropped() }
        } catch (_: Throwable) {
            if (!ran.get()) onDropped()
        }
    }

    /**
     * Runs [block] on the main thread. With [checkClosed], a reader closed meanwhile drops it: the
     * close guarantee is enforced at DELIVERY, because a publication built before a close is already
     * queued here when the close runs. A failing listener stops only its own delivery.
     */
    internal fun onMain(checkClosed: Boolean = true, block: () -> Unit) {
        try {
            mainScope.launch {
                if (checkClosed && closed.get()) return@launch
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A listener's own failure is not the reader's to propagate.
                }
            }
        } catch (_: Throwable) {
            // The main dispatcher refused the block; there is nothing left to deliver to.
        }
    }

    internal fun register(window: AndroidLibraryWindow) {
        windows += window
    }

    internal fun unregister(window: AndroidLibraryWindow) {
        windows -= window
    }

    internal fun register(search: AndroidLibrarySearch) {
        searches += search
    }

    internal fun unregister(search: AndroidLibrarySearch) {
        searches -= search
    }

    internal fun unregister(registration: AndroidLibraryOutcomeRegistration) {
        outcomeListeners -= registration
    }

    private fun fanOutOutcome(outcome: MutationOutcome) {
        val converted = try {
            outcome.toAndroid()
        } catch (_: Throwable) {
            return
        }
        outcomeListeners.toList().forEach { it.emit(converted) }
    }

    private fun change(
        target: AndroidLibraryEntity,
        field: MutationField,
        apply: (LibraryFavourites, LibraryEntityRef) -> Unit,
    ): Boolean {
        if (target.rawId.isBlank() || closed.get()) return false
        val ref = LibraryEntityRef(target.kind.toCore(), target.rawId)
        onReader {
            val favourites = composed()?.session?.favourites
            if (favourites == null) {
                fanOutOutcome(MutationOutcome.NotRecorded(ref, field))
                return@onReader
            }
            try {
                // Through the favourites, never the outbox directly: they republish every window and
                // search showing the target synchronously, before any send (§16.20, CONF-84).
                apply(favourites, ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fanOutOutcome(MutationOutcome.NotRecorded(ref, field))
            }
        }
        return true
    }

    private fun stopReaderThread() {
        // Never on the reader's own thread, which is the one being waited for.
        try {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    try {
                        readerDispatcher.close()
                        // On the JVM, closing the dispatcher only SHUTS its executor down: work
                        // already queued still runs, and close returns at once. No task runs on the
                        // reader's thread again only once the executor has terminated.
                        ((readerDispatcher as? ExecutorCoroutineDispatcher)?.executor as? ExecutorService)
                            ?.awaitTermination(THREAD_STOP_WAIT_SECONDS, TimeUnit.SECONDS)
                    } catch (_: Throwable) {
                    }
                    // A reader whose predecessor had not terminated when it stopped waiting for it
                    // (closed while waiting, or its first 45 s had passed) is not terminated until
                    // that predecessor is, waiting at most 45 s more, or the next reader could open
                    // the database beside it.
                    try {
                        predecessor.getAndSet(null)?.let { previous ->
                            withTimeoutOrNull(PREDECESSOR_WAIT_MILLIS) { previous.terminated.await() }
                        }
                    } catch (_: Throwable) {
                    }
                } finally {
                    predecessorWatch?.dispose()
                    terminated.complete(Unit)
                }
            }
        } catch (_: Throwable) {
            // The waits could not be started at all, so nothing is waited for: the one path on which
            // this reader counts as terminated without them. The predecessor is dropped regardless.
            predecessor.set(null)
            predecessorWatch?.dispose()
            terminated.complete(Unit)
        }
    }

    public companion object {
        /**
         * For tests only, and null in the app: the in-foreground epoch cadence (§16.11 policy 1) of
         * every reader [forAccount] creates while it is set, so a test can observe the cadence run and
         * stop in seconds. At least 100 ms: anything shorter would poll the server in a tight loop.
         */
        @Volatile
        @JvmStatic
        public var testEpochIntervalMillis: Long? = null
            set(value) {
                require(value == null || value >= 100) { "an epoch cadence under 100 ms polls the server" }
                field = value
            }

        /**
         * Each wait for a predecessor: before opening the database, and before counting as
         * terminated. The first starts no earlier than about when the predecessor's close begins
         * ([obtain] builds the new reader just before it closes the old one, and a sign-out's close
         * has begun or is about to), and is longer than that close's own waits for cooperative work
         * (10 s + 30 s), though not than those plus the predecessor's own wait for its predecessor;
         * the second wait covers that. It bounds waiting, and does not guarantee that the
         * predecessor has let go (see [close]).
         */
        private const val PREDECESSOR_WAIT_MILLIS = 45_000L
        private const val CLOSE_DRAIN_MILLIS = 10_000L
        private const val THREAD_STOP_WAIT_SECONDS = 30L

        private val lock = Any()
        private var current: AndroidLibraryReader? = null

        /**
         * The last reader closed, from its close until it has terminated (see [awaitTermination]):
         * the next reader waits for it too, and so does a sign-out that finds no current reader.
         */
        private var closing: AndroidLibraryReader? = null

        /**
         * The process's reader for [account], created the first time and shared afterwards. A request
         * for a different account — another id, address, user or password — closes the previous
         * reader first: v1 has one active account.
         */
        @JvmStatic
        public fun forAccount(context: Context, account: AndroidLibraryReaderAccount): AndroidLibraryReader {
            val application = context.applicationContext
            val epochInterval = testEpochIntervalMillis
            return obtain(account) { previous ->
                AndroidLibraryReader(
                    account,
                    productionComposer(application, account, epochInterval),
                    newLibraryReaderDispatcher(),
                    Dispatchers.Main,
                    predecessor = previous,
                )
            }
        }

        /**
         * The process's reader for [account], or a new one from [build]. [build] receives the reader
         * still closing — the one replaced here, or one closed earlier that has not yet terminated —
         * so the new one can wait for it before opening the database.
         */
        internal fun obtain(
            account: AndroidLibraryReaderAccount,
            build: (predecessor: AndroidLibraryReader?) -> AndroidLibraryReader,
        ): AndroidLibraryReader {
            val previous: AndroidLibraryReader?
            val reader: AndroidLibraryReader
            synchronized(lock) {
                current?.takeIf { it.account == account && !it.isClosed }?.let { return it }
                previous = current
                reader = build(previous ?: closing?.takeUnless { it.hasTerminated })
                current = reader
            }
            previous?.close()
            return reader
        }

        /**
         * Closes the process's reader, if any, and calls [completion] on the main thread once it has
         * terminated (see [close]). A sign-out or account removal waits for it before deleting the
         * account's rows (§14.7 step 6); Android has neither yet, so only the tests call this.
         */
        @JvmStatic
        public fun closeCurrent(completion: () -> Unit = {}) {
            // The current reader, or the one still closing: either may still be using the database.
            // Recorded as closing here, under the lock, so an obtain() racing this hands it over.
            val reader = synchronized(lock) { (current.also { current = null } ?: closing)?.also { closing = it } }
            if (reader == null) {
                GlobalScope.launch(Dispatchers.Main) { completion() }
            } else {
                reader.close(completion)
            }
        }

        /** Called once, by the first close of [reader]. */
        private fun forget(reader: AndroidLibraryReader) {
            synchronized(lock) {
                if (current === reader) current = null
                closing = reader
            }
            reader.terminated.invokeOnCompletion {
                synchronized(lock) { if (closing === reader) closing = null }
            }
        }
    }
}

/** Credential-bearing account input; never rendered, logged or published. */
public class AndroidLibraryReaderAccount(
    public val providerInstanceId: String,
    public val normalizedBaseUrl: String,
    public val username: String,
    public val password: String,
    public val allowLocalHttp: Boolean,
) {
    init {
        require(providerInstanceId.isNotBlank())
        require(normalizedBaseUrl.isNotBlank())
    }

    override fun equals(other: Any?): Boolean = other is AndroidLibraryReaderAccount &&
        other.providerInstanceId == providerInstanceId && other.normalizedBaseUrl == normalizedBaseUrl &&
        other.username == username && other.password == password && other.allowLocalHttp == allowLocalHttp

    override fun hashCode(): Int = providerInstanceId.hashCode()

    override fun toString(): String = "AndroidLibraryReaderAccount(<redacted>)"
}

/** A session and what closing it releases; the production one owns its database and transport. */
internal class AndroidLibraryReaderComposition(
    val session: LibraryReaderSession,
    val searchConfig: LibrarySearchConfig = LibrarySearchConfig(),
    val release: () -> Unit = {},
)

/** Reader thread: [LibraryReader.online] is read where it is written. */
private fun LibraryReaderSession.connection(outcome: ReaderConnectionOutcome, discardedAcknowledged: Boolean): AndroidLibraryConnection {
    val discarded = if (discardedAcknowledged) 0 else discardedPendingChanges
    return when (outcome) {
        is ReaderConnectionOutcome.Read -> AndroidLibraryConnection(
            epochRead = true,
            serverReportsNoEpoch = outcome.epoch.stamp == null,
            discardedPendingChanges = discarded,
            error = null,
            internalFailure = false,
            closed = false,
            readerOnline = reader.online,
        )
        is ReaderConnectionOutcome.Failed -> AndroidLibraryConnection(
            false, false, discarded, outcome.error, internalFailure = false, closed = false, readerOnline = reader.online,
        )
        ReaderConnectionOutcome.InternalFailure -> AndroidLibraryConnection(
            false, false, discarded, null, internalFailure = true, closed = false, readerOnline = reader.online,
        )
    }
}

private fun internalFailureConnection() =
    AndroidLibraryConnection(false, false, 0, null, internalFailure = true, closed = false, readerOnline = false)

private fun closedConnection() =
    AndroidLibraryConnection(false, false, 0, null, internalFailure = false, closed = true, readerOnline = false)

private fun productionComposer(
    context: Context,
    account: AndroidLibraryReaderAccount,
    epochIntervalMillis: Long?,
): (CoroutineScope) -> AndroidLibraryReaderComposition = { scope ->
    var store: DulcetDatabaseStore? = null
    var transport: KtorLibraryEndpointTransport? = null
    try {
        val opened = DulcetDriverFactory(context).openDulcetDatabase().also { store = it }
        val live = KtorLibraryEndpointTransport(
            LibraryBrowseRequest(
                providerInstanceId = account.providerInstanceId,
                normalizedBaseUrl = account.normalizedBaseUrl,
                username = account.username,
                password = account.password,
                allowLocalHttp = account.allowLocalHttp,
            ),
            saltSource = null,
            logSink = null,
            hostResolver = systemHostResolver(),
        ).also { transport = it }
        val cache = SeenCacheStore(opened, AndroidLibraryReaderWallClock)
            .bind(CacheBinding(account.providerInstanceId, account.normalizedBaseUrl, account.username))
        AndroidLibraryReaderComposition(
            // No download source: downloads join the reader in phase R4, so nothing is published as
            // `downloaded` yet, and Android TV must be given none then either.
            session = LibraryReaderSession(
                opened.database, cache, live, scope,
                epochIntervalMillis?.let { LibraryReaderConfig(epochIntervalMillis = it) } ?: LibraryReaderConfig(),
            ),
            release = {
                live.close()
                opened.close()
            },
        )
    } catch (failure: Throwable) {
        try {
            transport?.close()
            store?.close()
        } catch (_: Throwable) {
        }
        throw failure
    }
}

/** Wall-clock milliseconds for the seen-cache's ages and retention; never used to order anything. */
private object AndroidLibraryReaderWallClock : SeenCacheWallClock {
    override fun nowEpochMilliseconds(): Long = System.currentTimeMillis()
}

// ---- Handles --------------------------------------------------------------------------------------------

/**
 * One open screen. Every method returns at once and runs on the reader's thread in call order;
 * publications arrive on the main thread. [close] is idempotent, cancels the screen's reads and drops
 * the listener: nothing is delivered after it returns, including a publication already on its way.
 */
public class AndroidLibraryWindow internal constructor(
    private val owner: AndroidLibraryReader,
    listener: (AndroidLibraryPublication) -> Unit,
) {
    private val listener = AtomicReference<((AndroidLibraryPublication) -> Unit)?>(listener)

    /** The latest publication DELIVERED to the listener: what the shell's indexes refer to. */
    private val delivered = AtomicReference<AndroidLibraryPublication?>(null)

    // Reader-thread state.
    private var handle: LibraryWindowHandle? = null
    internal var home: LibraryHomeHandle? = null
    private var opener: ((LibraryReader, (LibraryPublication) -> Unit) -> LibraryWindowHandle)? = null
    private var sequence = 0
    private var emitted: AndroidLibraryPublication? = null

    /** Reads the next page of a paged list, at most one page beyond the viewport. */
    public fun loadMore() {
        call { it.loadMore() }
    }

    /** Reads the page before a window that starts below the top (after a rebase, §16.12). */
    public fun loadBefore() {
        call { it.loadBefore() }
    }

    /** Re-reads the visible pages whatever their age. */
    public fun refresh() {
        call { it.refresh() }
    }

    /**
     * The visible range, as indexes into the latest publication THIS LISTENER RECEIVED. The reader may
     * already have emitted a newer one; the range is carried over to it by item identity and clamped,
     * so the reader rebases and looks ahead around what is on screen. A viewport is a hint: it never
     * publishes anything, and never a failure.
     */
    public fun setViewport(first: Int, last: Int) {
        val seen = delivered.get()?.let { publication ->
            SeenRange(publication.sequence, publication.items.getOrNull(first)?.key(), publication.items.getOrNull(last)?.key())
        }
        call(publishFailure = false) { current ->
            val (from, to) = translateRange(first, last, seen, emitted)
            current.setViewport(from, to)
        }
    }

    public fun close() {
        if (listener.getAndSet(null) == null) return
        owner.onReader(block = ::closeOnReader)
    }

    internal fun open(
        composition: AndroidLibraryReaderComposition?,
        opener: (LibraryReader, (LibraryPublication) -> Unit) -> LibraryWindowHandle,
    ) {
        if (listener.get() == null) return
        owner.register(this)
        this.opener = opener
        if (composition == null) {
            emit(failure(AndroidLibraryUnavailableReason.InternalFailure))
            return
        }
        start(composition, opener)
    }

    /** Opened while setup was failing, and setup has now succeeded: the window opens for real. */
    internal fun reopen(composition: AndroidLibraryReaderComposition) {
        val open = opener ?: return
        if (listener.get() == null || handle != null || home != null) return
        start(composition, open)
    }

    private fun start(
        composition: AndroidLibraryReaderComposition,
        opener: (LibraryReader, (LibraryPublication) -> Unit) -> LibraryWindowHandle,
    ) {
        try {
            handle = opener(composition.session.reader, ::publish)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A query naming nothing the core can open (a blank id) is a shell defect, not the server's.
            emit(failure(AndroidLibraryUnavailableReason.InternalFailure))
        }
    }

    internal fun closeFromReader() {
        listener.set(null)
        closeOnReader()
    }

    private fun closeOnReader() {
        try {
            home?.close() ?: handle?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
        }
        home = null
        handle = null
        owner.unregister(this)
    }

    private fun call(publishFailure: Boolean = true, action: (LibraryWindowHandle) -> Unit) {
        if (listener.get() == null) return
        owner.onReader {
            val current = handle ?: return@onReader
            try {
                action(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (publishFailure) emit(failureOver(emitted)) else owner.uncaughtFailures += failure
            }
        }
    }

    private fun publish(publication: LibraryPublication) {
        val converted = try {
            publication.toAndroid(sequence + 1)
        } catch (_: Throwable) {
            failureOver(emitted)
        }
        emit(converted)
    }

    /** The content already shown stays, under a failure label (an error never replaces content). */
    private fun failureOver(previous: AndroidLibraryPublication?): AndroidLibraryPublication =
        if (previous == null || previous.freshness is AndroidLibraryFreshness.Unavailable) {
            failure(AndroidLibraryUnavailableReason.InternalFailure)
        } else {
            previous.copy(
                sequence = sequence + 1,
                freshness = AndroidLibraryFreshness.Cached(
                    (previous.freshness as? AndroidLibraryFreshness.Cached)?.asOfEpochMillis,
                    AndroidLibraryCachedReason.InternalFailure,
                ),
            )
        }

    private fun failure(reason: AndroidLibraryUnavailableReason) = AndroidLibraryPublication(
        sequence = sequence + 1,
        freshness = AndroidLibraryFreshness.Unavailable(reason),
        coverage = null,
        total = null,
        leadingOffset = 0,
        header = null,
        items = emptyList(),
        itemsState = AndroidLibraryItemsState.Unavailable,
        order = AndroidLibraryItemsOrder.Server,
        anchorRawId = null,
        anchorIndex = null,
        itemsUnavailableReason = reason,
    )

    private fun emit(publication: AndroidLibraryPublication) {
        sequence = publication.sequence
        emitted = publication
        owner.onMain { deliver(publication) }
    }

    /** Main thread. The listener is read HERE, so a close that ran first drops this publication. */
    private fun deliver(publication: AndroidLibraryPublication) {
        val current = listener.get() ?: return
        delivered.set(publication)
        current(publication)
    }

    /** A window opened on a closed reader: one statement of fact, never silence. */
    internal fun emitClosed() {
        val publication = failure(AndroidLibraryUnavailableReason.Closed).copy(sequence = 1)
        owner.onMain(checkClosed = false) { listener.get()?.invoke(publication) }
    }
}

/**
 * One search (§16.15). [updateQuery] publishes the device's rows at once and the server's after the
 * core's debounce; [close] is idempotent and cancels the server request.
 */
public class AndroidLibrarySearch internal constructor(
    private val owner: AndroidLibraryReader,
    listener: (AndroidLibrarySearchPublication) -> Unit,
) {
    private val listener = AtomicReference<((AndroidLibrarySearchPublication) -> Unit)?>(listener)

    // Reader-thread state.
    private var session: LibrarySearchSession? = null
    private var minimumServerQueryLength = LibrarySearchConfig().minimumServerQueryLength
    private var sequence = 0
    private var emitted: AndroidLibrarySearchPublication? = null

    /** The latest query, typed even while setup was failing, for a search opened late. */
    private var query: String? = null

    public fun updateQuery(text: String) {
        if (listener.get() == null) return
        owner.onReader {
            query = text
            val current = session ?: return@onReader
            try {
                current.updateQuery(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emit(readerFailed())
            }
        }
    }

    /** Runs the current query again, as if retyped. */
    public fun refresh() {
        call { it.refresh() }
    }

    public fun close() {
        if (listener.getAndSet(null) == null) return
        owner.onReader(block = ::closeOnReader)
    }

    internal fun open(composition: AndroidLibraryReaderComposition?) {
        if (listener.get() == null) return
        owner.register(this)
        if (composition == null) {
            emit(readerFailed())
            return
        }
        start(composition)
    }

    /** Opened while setup was failing, and setup has now succeeded: the search opens for real. */
    internal fun reopen(composition: AndroidLibraryReaderComposition) {
        if (listener.get() == null || session != null) return
        start(composition)
        // Applied now, in this task: a query typed meanwhile is queued behind it and supersedes it.
        val text = query ?: return
        val current = session ?: return
        try {
            current.updateQuery(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emit(readerFailed())
        }
    }

    private fun start(composition: AndroidLibraryReaderComposition) {
        try {
            minimumServerQueryLength = composition.searchConfig.minimumServerQueryLength
            session = composition.session.openSearch(composition.searchConfig, ::publish)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emit(readerFailed())
        }
    }

    internal fun closeFromReader() {
        listener.set(null)
        closeOnReader()
    }

    private fun closeOnReader() {
        try {
            session?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
        }
        session = null
        owner.unregister(this)
    }

    private fun call(action: (LibrarySearchSession) -> Unit) {
        if (listener.get() == null) return
        owner.onReader {
            val current = session ?: return@onReader
            try {
                action(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emit(readerFailed())
            }
        }
    }

    private fun publish(publication: LibrarySearchPublication) {
        val converted = try {
            publication.toAndroid(sequence + 1, minimumServerQueryLength)
        } catch (_: Throwable) {
            readerFailed()
        }
        emit(converted)
    }

    /** The rows already shown stay, under a failed scope. */
    private fun readerFailed() = AndroidLibrarySearchPublication(
        query = emitted?.query ?: "",
        sequence = sequence + 1,
        scope = AndroidLibrarySearchScope.ReaderFailed,
        rows = emitted?.rows ?: emptyList(),
    )

    private fun emit(publication: AndroidLibrarySearchPublication) {
        sequence = publication.sequence
        emitted = publication
        owner.onMain { listener.get()?.invoke(publication) }
    }

    internal fun emitClosed() {
        val publication = AndroidLibrarySearchPublication("", 1, AndroidLibrarySearchScope.ReaderFailed, emptyList())
        owner.onMain(checkClosed = false) { listener.get()?.invoke(publication) }
    }
}

/** A favourite-outcome listener's registration. [close] is idempotent. */
public class AndroidLibraryOutcomeRegistration internal constructor(
    private val owner: AndroidLibraryReader,
    listener: (AndroidLibraryChangeOutcome) -> Unit,
) {
    private val listener = AtomicReference<((AndroidLibraryChangeOutcome) -> Unit)?>(listener)

    public fun close() {
        if (listener.getAndSet(null) == null) return
        owner.onReader { owner.unregister(this) }
    }

    /** Reader thread. */
    internal fun emit(outcome: AndroidLibraryChangeOutcome) {
        if (listener.get() == null) return
        owner.onMain { listener.get()?.invoke(outcome) }
    }
}

// ---- Viewport translation ---------------------------------------------------------------------------------

internal data class SeenRange(val sequence: Int, val first: String?, val last: String?)

private fun AndroidLibraryItem.key(): String = "${this::class.simpleName}\u0000$rawId"

/**
 * Carries a range of indexes into the publication the shell has seen over to the latest one the
 * reader has emitted, by item identity, and clamps it to that publication's items: a range past the
 * end (a list that shrank) becomes its last item, a reversed one its first index.
 */
internal fun translateRange(first: Int, last: Int, seen: SeenRange?, emitted: AndroidLibraryPublication?): Pair<Int, Int> {
    val carried = carryRange(first, last, seen, emitted)
    val lastIndex = emitted?.items?.lastIndex ?: return carried
    if (lastIndex < 0) return 0 to 0
    val from = carried.first.coerceIn(0, lastIndex)
    return from to carried.second.coerceIn(from, lastIndex)
}

private fun carryRange(first: Int, last: Int, seen: SeenRange?, emitted: AndroidLibraryPublication?): Pair<Int, Int> {
    if (seen == null || emitted == null || seen.sequence == emitted.sequence) return first to last
    val from = emitted.indexNearest(seen.first, first) ?: return first to last
    val to = emitted.indexNearest(seen.last, last) ?: (from + (last - first))
    return from to maxOf(from, minOf(to, emitted.items.lastIndex))
}

private fun AndroidLibraryPublication.indexNearest(key: String?, near: Int): Int? {
    key ?: return null
    var best = -1
    for (index in items.indices) {
        if (items[index].key() != key) continue
        if (best < 0 || kotlin.math.abs(index - near) < kotlin.math.abs(best - near)) best = index
    }
    return best.takeIf { it >= 0 }
}

/** The latest failures, at most [capacity]: enough to diagnose, never a leak in a long-lived process. */
internal class BoundedFailures(private val capacity: Int = 32) {
    private val failures = ArrayDeque<Throwable>()

    operator fun plusAssign(failure: Throwable) {
        synchronized(failures) {
            if (failures.size == capacity) failures.removeFirst()
            failures.addLast(failure)
        }
    }

    fun toList(): List<Throwable> = synchronized(failures) { failures.toList() }
}
