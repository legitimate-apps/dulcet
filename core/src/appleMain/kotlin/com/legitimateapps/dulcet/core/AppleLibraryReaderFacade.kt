package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The Apple facade over one account's reader (spec §16.18, the Apple paragraph; §7.1–§7.3).
 *
 * **What it is.** One [LibraryReaderSession] — the reader, its seen-cache, its favourites and its
 * searches — behind an Objective-C-shaped surface: library windows and search as event streams
 * (`subscribeX(listener) -> Subscription`, §7.2), favourites and ratings as calls whose outcomes
 * arrive on their own stream, and the connection lifecycle forwarded to the session. Every product
 * rule — freshness, coverage, playability, search scope, the overlay of a pending change — is
 * computed in the core and only copied here (§16.18 "core-owned rules, never shell-owned").
 *
 * **Threading, in both directions.** The reader is confined to one dedicated thread
 * ([newLibraryReaderDispatcher]) and checks it at every entry point. This facade owns the hop: it is
 * created on the caller's thread, builds the session ON the reader's thread (the reader records the
 * thread that created it), and runs every call there, in call order — the dispatcher is serial.
 * Every listener call and every completion is delivered on the main thread. Nothing here waits for
 * the reader's thread, so no call blocks the main thread.
 *
 * **No Kotlin exception crosses** (it would terminate the process). Every public method returns
 * normally; a throw on the reader's thread becomes a publication that says the screen failed — over
 * the content already shown, never instead of it — or an error kind on a completion.
 *
 * **Credentials.** The account is held only to build the transport; nothing this facade publishes
 * has a field that can carry a URL, a query string, server text or an exception message: errors
 * cross as a closed kind (CORPUS §4 line 5, CLAUDE.md trap 12).
 */
@OptIn(ExperimentalAtomicApi::class, DelicateCoroutinesApi::class)
public class AppleLibraryReaderClient internal constructor(
    private val compose: (CoroutineScope) -> AppleLibraryReaderComposition,
    private val readerDispatcher: CloseableCoroutineDispatcher,
    mainDispatcher: CoroutineDispatcher,
) {
    /**
     * One account's reader over the app's database. [databaseName] is the same database the other
     * facades open. The account's credentials reach only the transport.
     */
    public constructor(databaseName: String, account: AppleLibraryReaderAccount) : this(
        productionComposer(databaseName, account),
        newLibraryReaderDispatcher(),
        Dispatchers.Main,
    )

    private val closed = AtomicBoolean(false)

    /**
     * Read at every delivery, on the main thread. A client-level [close] marks it on the caller's
     * thread and only then tells the reader, so a publication already queued for the main thread
     * when [close] returns is dropped rather than delivered after it.
     */
    internal val isClosed: Boolean get() = closed.load()

    /** Anything that reached a scope's last-resort handler instead of a publication; for tests. */
    internal val uncaughtFailures = mutableListOf<Throwable>()

    private val readerScope = CoroutineScope(
        SupervisorJob() + readerDispatcher + CoroutineExceptionHandler { _, failure -> uncaughtFailures += failure },
    )

    /** A supervisor, so one listener's failure never stops another's deliveries (CONF-86). */
    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher + CoroutineExceptionHandler { _, _ -> })

    private val terminated = CompletableDeferred<Unit>()

    // ---- Reader-thread state. Touched only inside onReader blocks. ---------------------------------------

    private var composition: AppleLibraryReaderComposition? = null
    private val windows = mutableListOf<AppleLibraryWindowSubscription>()
    private val searches = mutableListOf<AppleLibrarySearchSubscription>()
    private val outcomeSubscriptions = mutableListOf<AppleLibraryFavouriteOutcomeSubscription>()

    init {
        onReader {
            composition = try {
                compose(readerScope).also { built ->
                    built.session.favourites.addOutcomeListener(::fanOutOutcome)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Every entry point answers a missing session with a closed failure; the throwable's
                // text is dropped here, because it may carry the address or credentials.
                null
            }
        }
    }

    // ---- Library windows --------------------------------------------------------------------------------

    /**
     * Opens one screen as an event stream. The first publication is the cached content if there is
     * any — built before any request is issued (CONF-76) — then each later state. A home screen is a
     * list of independent row subscriptions, one `homeRow` request each (CONF-86).
     */
    public fun subscribeLibraryWindow(
        request: AppleLibraryWindowRequest,
        listener: AppleLibraryWindowListener,
    ): AppleLibraryWindowSubscription {
        val subscription = AppleLibraryWindowSubscription(this, listener)
        if (!onReader { subscription.open(composition, request) }) subscription.emitClosed()
        return subscription
    }

    // ---- Search -------------------------------------------------------------------------------------------

    /** Search as you type over what this device has seen and the server (§16.15, §18.1). */
    public fun subscribeSearch(listener: AppleLibrarySearchListener): AppleLibrarySearchSubscription {
        val subscription = AppleLibrarySearchSubscription(this, listener)
        if (!onReader { subscription.open(composition) }) subscription.emitClosed()
        return subscription
    }

    // ---- Favourites and ratings ----------------------------------------------------------------------------

    /**
     * Makes an `artist`, `album` or `track` a favourite or not. The change is in the next publication
     * of every open window and search that shows it, before any request (§16.20); how the send ended
     * arrives on [subscribeFavouriteOutcomes]. Returns false, and does nothing, for an unknown kind,
     * a blank id or a closed client.
     */
    public fun setFavourite(kind: String, rawId: String, favourite: Boolean): Boolean =
        change(kind, rawId, MutationField.Starred) { favourites, target -> favourites.setFavourite(target, favourite) }

    /** Flips the favourite state as shown (an unknown state becomes a favourite). See [setFavourite]. */
    public fun toggleFavourite(kind: String, rawId: String): Boolean =
        change(kind, rawId, MutationField.Starred) { favourites, target -> favourites.toggleFavourite(target) }

    /** Sets a rating of 1–5, or removes it with 0. Any other value returns false. See [setFavourite]. */
    public fun setRating(kind: String, rawId: String, rating: Int): Boolean {
        if (rating !in 0..5) return false
        return change(kind, rawId, MutationField.Rating) { favourites, target -> favourites.setRating(target, rating) }
    }

    /** How each favourite or rating change ended: `saved`, `notSaved`, `superseded` or `notRecorded`. */
    public fun subscribeFavouriteOutcomes(
        listener: AppleLibraryFavouriteOutcomeListener,
    ): AppleLibraryFavouriteOutcomeSubscription {
        val subscription = AppleLibraryFavouriteOutcomeSubscription(this, listener)
        onReader { outcomeSubscriptions += subscription }
        return subscription
    }

    /**
     * The changes that have not reached the server, for the sign-out offer (§14.7). The count is
     * read from the outbox itself: a failed read completes with a null count, never a zero.
     */
    public fun pendingChangeCount(completion: (AppleLibraryPendingChanges) -> Unit): AppleLibraryReaderOperation =
        operation(completion, failed = { kind -> AppleLibraryPendingChanges(null, kind) }) { session ->
            AppleLibraryPendingChanges(session.outbox.pendingCount(), null)
        }

    // ---- Connection lifecycle --------------------------------------------------------------------------------

    /** The connect-time epoch reading (two requests, §16.11). */
    public fun connect(completion: (AppleLibraryReaderConnection) -> Unit): AppleLibraryReaderOperation =
        operation(completion, failed = ::failedConnection) { session ->
            session.reader.connect()
            session.connection()
        }

    /**
     * Reachability returned, or the app came back to the foreground online (§16.14): the session
     * is told the server is reachable — which re-runs open searches — and then the reader's
     * reconnect runs in its fixed order: outbox flush, epoch read, visible-screen revalidation,
     * downloaded-album recheck, nothing else.
     */
    public fun reconnect(completion: (AppleLibraryReaderConnection) -> Unit): AppleLibraryReaderOperation =
        operation(completion, failed = ::failedConnection) { session ->
            session.setOnline(true)
            session.reader.reconnect()
            session.connection()
        }

    /** Reachability as the platform reports it. Offline issues no request. */
    public fun setOnline(reachable: Boolean) {
        onReader { composition?.session?.setOnline(reachable) }
    }

    /** Foreground transitions; the epoch cadence while in the foreground is core policy (§16.11). */
    public fun setForeground(foreground: Boolean) {
        onReader { composition?.session?.reader?.setForeground(foreground) }
    }

    /** Low Data Mode or a metered connection: no speculative reads (§16.13). */
    public fun setNetworkConstrained(constrained: Boolean) {
        onReader { composition?.session?.reader?.setNetworkConstrained(constrained) }
    }

    /**
     * Closes every subscription, cancels every in-flight read, releases the database and transport
     * and stops the reader's thread. Idempotent. Nothing is published after it returns — a
     * publication already queued for the main thread is dropped when it arrives — provided it is
     * called on the main thread; from another thread, a delivery already running may finish. A
     * completion still pending is delivered once, as `cancelled`.
     */
    public fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            readerScope.launch {
                val closing = composition
                composition = null
                try {
                    windows.toList().forEach(AppleLibraryWindowSubscription::closeFromClient)
                    searches.toList().forEach(AppleLibrarySearchSubscription::closeFromClient)
                    outcomeSubscriptions.toList().forEach(AppleLibraryFavouriteOutcomeSubscription::closeFromClient)
                    closing?.session?.reader?.setForeground(false)
                } catch (_: Throwable) {
                    // Closing is best effort and exports nothing.
                }
                // Every read is cancelled BEFORE the database and transport are released, so no
                // coroutine resumes into a closed store. This block runs on, synchronously.
                readerScope.cancel()
                try {
                    closing?.release?.invoke()
                } catch (_: Throwable) {
                }
            }.invokeOnCompletion { stopReaderThread() }
        } catch (_: Throwable) {
            stopReaderThread()
        }
    }

    /** Completes once the reader's thread has stopped; for tests, which then close their database. */
    internal suspend fun awaitTermination() = terminated.await()

    // ---- Internals ----------------------------------------------------------------------------------------------

    /**
     * Runs [block] on the reader's thread, after every call made before it. Returns false, running
     * nothing, once the client is closed.
     */
    internal fun onReader(block: () -> Unit): Boolean {
        if (closed.load()) return false
        return try {
            readerScope.launch { block() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Runs [block] on the main thread. A failing listener stops only its own delivery. */
    internal fun onMain(block: () -> Unit) {
        try {
            mainScope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A listener's own failure is not the facade's to propagate (§7.2).
                }
            }
        } catch (_: Throwable) {
            // The main dispatcher refused the block; there is nothing left to deliver to.
        }
    }

    internal fun register(subscription: AppleLibraryWindowSubscription) {
        windows += subscription
    }

    internal fun unregister(subscription: AppleLibraryWindowSubscription) {
        windows -= subscription
    }

    internal fun register(subscription: AppleLibrarySearchSubscription) {
        searches += subscription
    }

    internal fun unregister(subscription: AppleLibrarySearchSubscription) {
        searches -= subscription
    }

    internal fun unregister(subscription: AppleLibraryFavouriteOutcomeSubscription) {
        outcomeSubscriptions -= subscription
    }

    private fun fanOutOutcome(outcome: MutationOutcome) {
        val converted = try {
            outcome.toApple()
        } catch (_: Throwable) {
            return
        }
        outcomeSubscriptions.toList().forEach { it.emit(converted) }
    }

    private fun change(
        kind: String,
        rawId: String,
        field: MutationField,
        apply: (LibraryFavourites, LibraryEntityRef) -> Unit,
    ): Boolean {
        val entity = LibraryEntityKind.fromWireName(kind) ?: return false
        if (rawId.isBlank()) return false
        val target = LibraryEntityRef(entity, rawId)
        return onReader {
            val favourites = composition?.session?.favourites
            if (favourites == null) {
                fanOutOutcome(MutationOutcome.NotRecorded(target, field))
                return@onReader
            }
            try {
                // Through the favourites, never the outbox directly: they republish every window
                // and search showing the target synchronously, before any send (§16.20, CONF-84).
                apply(favourites, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fanOutOutcome(MutationOutcome.NotRecorded(target, field))
            }
        }
    }

    /**
     * §7.2's operation: returns a handle at once and completes exactly once, on the main thread —
     * with the result, or with [failed] of `cancelled`, `closed` or `internalFailure`.
     */
    private fun <T> operation(
        completion: (T) -> Unit,
        failed: (String) -> T,
        work: suspend (LibraryReaderSession) -> T,
    ): AppleLibraryReaderOperation {
        val operation = AppleLibraryReaderOperation()
        fun finish(result: T) {
            if (operation.claim()) onMain { completion(result) }
        }
        if (closed.load()) {
            finish(failed("closed"))
            return operation
        }
        try {
            val job = readerScope.launch(start = CoroutineStart.LAZY) {
                val session = composition?.session
                val result = if (session == null) {
                    failed("internalFailure")
                } else {
                    try {
                        work(session)
                    } catch (cancelled: CancellationException) {
                        finish(failed("cancelled"))
                        throw cancelled
                    } catch (_: Throwable) {
                        failed("internalFailure")
                    }
                }
                finish(result)
            }
            job.invokeOnCompletion { cause -> if (cause != null) finish(failed("cancelled")) }
            operation.attach(job)
            job.start()
        } catch (_: Throwable) {
            finish(failed("closed"))
        }
        return operation
    }

    private fun stopReaderThread() {
        // Never on the reader's own thread: closing the dispatcher waits for that thread to finish.
        try {
            GlobalScope.launch(Dispatchers.Default) {
                try {
                    readerDispatcher.close()
                } catch (_: Throwable) {
                }
                terminated.complete(Unit)
            }
        } catch (_: Throwable) {
            terminated.complete(Unit)
        }
    }
}

/** A session and what closing it releases; the production one owns its database and transport. */
internal class AppleLibraryReaderComposition(
    val session: LibraryReaderSession,
    val searchConfig: LibrarySearchConfig = LibrarySearchConfig(),
    val release: () -> Unit = {},
)

private fun LibraryReaderSession.connection() = AppleLibraryReaderConnection(
    epochKnown = reader.sessionEpoch != null,
    serverReportsNoEpoch = reader.serverReportsNoEpoch,
    discardedPendingChanges = discardedPendingChanges,
    errorKind = null,
)

private fun failedConnection(kind: String) = AppleLibraryReaderConnection(false, false, 0, kind)

private fun productionComposer(
    databaseName: String,
    account: AppleLibraryReaderAccount,
): (CoroutineScope) -> AppleLibraryReaderComposition = { scope ->
    var store: DulcetDatabaseStore? = null
    var transport: KtorLibraryEndpointTransport? = null
    try {
        require(databaseName.isNotBlank())
        val opened = DulcetDriverFactory(databaseName = databaseName).openDulcetDatabase().also { store = it }
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
        val cache = SeenCacheStore(opened, AppleLibraryReaderWallClock)
            .bind(CacheBinding(account.providerInstanceId, account.normalizedBaseUrl, account.username))
        AppleLibraryReaderComposition(
            // No download source: downloads join the reader in phase R4, and tvOS has none (§14.5),
            // so no Apple platform can publish `downloaded` until then.
            session = LibraryReaderSession(opened.database, cache, live, scope),
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
private object AppleLibraryReaderWallClock : SeenCacheWallClock {
    override fun nowEpochMilliseconds(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
}

// ---- Inputs ---------------------------------------------------------------------------------------------------

/** Credential-bearing account input copied from Swift; never rendered, logged or published. */
public class AppleLibraryReaderAccount(
    public val providerInstanceId: String,
    public val normalizedBaseUrl: String,
    public val username: String,
    public val password: String,
    public val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "AppleLibraryReaderAccount(<redacted>)"
}

/**
 * Which screen to open (§16.9). [kind] is one of:
 *
 * - `albumList` — [listType] is a `getAlbumList2` type: `alphabeticalByName`,
 *   `alphabeticalByArtist`, `newest`, `byYear` (needs [fromYear] and [toYear]), `byGenre` (needs
 *   [genre]), `recent`, `frequent`, `highest`, `starred` or `random`; [musicFolderId] optional.
 * - `artists` ([musicFolderId] optional), `playlists`, `starred`, `genres`.
 * - `artist`, `album`, `playlist` — [rawId] is the opaque id.
 * - `songsByGenre` — [genre]; [musicFolderId] optional.
 * - `homeRow` — one row of a home screen: [listType] is `favourites` or a single-page album list
 *   type. A home screen is one subscription per row, so each row publishes on its own (CONF-86).
 *
 * A request that names nothing the core can open publishes one `unavailable` publication whose
 * reason is `failed` with error kind `input`.
 */
public class AppleLibraryWindowRequest(
    public val kind: String,
    public val rawId: String?,
    public val listType: String?,
    public val genre: String?,
    /** `byYear` only; zero or negative means none. */
    public val fromYear: Int,
    /** `byYear` only; zero or negative means none. */
    public val toYear: Int,
    public val musicFolderId: String?,
)

/** What a request opens: one screen, or one home-screen row. */
internal sealed interface AppleLibraryWindowTarget {
    class Screen(val query: LibraryQuery) : AppleLibraryWindowTarget
    class HomeRow(val row: LibraryHomeRow) : AppleLibraryWindowTarget
}

/** Throws [IllegalArgumentException] for a request that names nothing the core can open. */
internal fun AppleLibraryWindowRequest.toTarget(): AppleLibraryWindowTarget {
    fun id(): String = requireNotNull(rawId?.takeIf { it.isNotBlank() }) { "missing id" }
    fun type(): AlbumListType = requireNotNull(AlbumListType.entries.firstOrNull { it.wireName == listType }) { "unknown list type" }
    fun year(value: Int): Int? = value.takeIf { it > 0 }
    val folder = musicFolderId?.takeIf { it.isNotBlank() }
    return when (kind) {
        "albumList" -> AppleLibraryWindowTarget.Screen(
            LibraryQuery.AlbumList(type(), year(fromYear), year(toYear), genre?.takeIf { it.isNotBlank() }, folder),
        )
        "artists" -> AppleLibraryWindowTarget.Screen(LibraryQuery.Artists(folder))
        "artist" -> AppleLibraryWindowTarget.Screen(LibraryQuery.Artist(id()))
        "album" -> AppleLibraryWindowTarget.Screen(LibraryQuery.Album(id()))
        "playlists" -> AppleLibraryWindowTarget.Screen(LibraryQuery.Playlists)
        "playlist" -> AppleLibraryWindowTarget.Screen(LibraryQuery.Playlist(id()))
        "starred" -> AppleLibraryWindowTarget.Screen(LibraryQuery.Starred)
        "genres" -> AppleLibraryWindowTarget.Screen(LibraryQuery.Genres)
        "songsByGenre" -> AppleLibraryWindowTarget.Screen(
            LibraryQuery.SongsByGenre(requireNotNull(genre?.takeIf { it.isNotBlank() }) { "missing genre" }, folder),
        )
        "homeRow" -> AppleLibraryWindowTarget.HomeRow(
            if (listType == "favourites") {
                LibraryHomeRow.Favourites
            } else {
                // A home row is one page of an album list, under the same rules as the list itself.
                val type = type()
                LibraryQuery.AlbumList(type)
                LibraryHomeRow.Albums(type)
            },
        )
        else -> throw IllegalArgumentException("unknown window kind")
    }
}

// ---- Listeners ------------------------------------------------------------------------------------------------

/** Receives a window's publications, on the main thread, until its subscription is closed. */
public interface AppleLibraryWindowListener {
    public fun onWindowPublication(publication: AppleLibraryWindowPublication)
}

/** Receives search publications, on the main thread, until the subscription is closed. */
public interface AppleLibrarySearchListener {
    public fun onSearchPublication(publication: AppleLibrarySearchPublication)
}

/** Receives how each favourite or rating change ended, on the main thread. */
public interface AppleLibraryFavouriteOutcomeListener {
    public fun onOutcome(outcome: AppleLibraryFavouriteOutcome)
}

/**
 * The synchronous handle of one asynchronous operation (§7.2). [cancel] is best-effort and
 * idempotent; the completion is always called exactly once, on the main thread, and a cancelled
 * operation completes with error kind `cancelled`. A success that has already completed wins.
 */
@OptIn(ExperimentalAtomicApi::class)
public class AppleLibraryReaderOperation internal constructor() {
    private val finished = AtomicBoolean(false)
    private val job = AtomicReference<kotlinx.coroutines.Job?>(null)

    public fun cancel() {
        try {
            job.load()?.cancel()
        } catch (_: Throwable) {
        }
    }

    internal fun attach(job: kotlinx.coroutines.Job) = this.job.store(job)

    /** The completion barrier: true exactly once. */
    internal fun claim(): Boolean = finished.compareAndSet(false, true)
}

// ---- Subscriptions --------------------------------------------------------------------------------------------

/**
 * One open screen (§16.18). Every method returns at once and runs on the reader's thread in call
 * order; publications arrive on the listener on the main thread.
 *
 * [close] is idempotent, cancels the screen's in-flight reads and drops the listener: nothing is
 * delivered after it returns — including a publication the reader built before the close and had
 * not yet delivered. Call it on the main thread for that guarantee; from another thread, a delivery
 * already running may finish.
 */
@OptIn(ExperimentalAtomicApi::class)
public class AppleLibraryWindowSubscription internal constructor(
    private val client: AppleLibraryReaderClient,
    listener: AppleLibraryWindowListener,
) {
    private val listener = AtomicReference<AppleLibraryWindowListener?>(listener)

    /** The latest publication DELIVERED to the listener — what the shell's indexes refer to. */
    private val delivered = AtomicReference<AppleLibraryWindowPublication?>(null)

    // Reader-thread state.
    private var handle: LibraryWindowHandle? = null
    private var home: LibraryHomeHandle? = null
    private var providerInstanceId = ""
    private var sequence = 0
    private var emitted: AppleLibraryWindowPublication? = null

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
     * The visible range, as indexes into the latest publication THIS LISTENER RECEIVED. The reader
     * may already have published a newer one that is still on its way to the main thread (a page
     * prepended, a rebase); the range is then carried over to that publication by item identity,
     * so the viewport the reader rebases and looks ahead around is the one on screen.
     */
    public fun setViewport(first: Int, last: Int) {
        val seen = delivered.load()
        val anchors = seen?.let { publication ->
            SeenViewport(publication.sequence, publication.items.getOrNull(first)?.key(), publication.items.getOrNull(last)?.key())
        }
        call { handle ->
            val (from, to) = translateViewport(first, last, anchors, emitted)
            handle.setViewport(from, to)
        }
    }

    public fun close() {
        if (listener.exchange(null) == null) return
        client.onReader(::closeOnReader)
    }

    // ---- Reader thread ----------------------------------------------------------------------------------------

    internal fun open(composition: AppleLibraryReaderComposition?, request: AppleLibraryWindowRequest) {
        if (listener.load() == null) return
        client.register(this)
        if (composition == null) {
            emit(readerFailurePublication(sequence + 1, emitted, errorKind = null))
            return
        }
        val reader = composition.session.reader
        providerInstanceId = reader.cache.serverId
        try {
            when (val target = request.toTarget()) {
                is AppleLibraryWindowTarget.Screen -> handle = reader.open(target.query, ::publish)
                is AppleLibraryWindowTarget.HomeRow -> {
                    val row = reader.openHome(listOf(target.row)) { _, publication -> publish(publication) }
                    home = row
                    handle = row.row(0)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            emit(readerFailurePublication(sequence + 1, emitted, errorKind = "input"))
        } catch (_: Throwable) {
            emit(readerFailurePublication(sequence + 1, emitted, errorKind = null))
        }
    }

    internal fun closeFromClient() {
        listener.store(null)
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
        client.unregister(this)
    }

    private fun call(action: (LibraryWindowHandle) -> Unit) {
        if (listener.load() == null) return
        client.onReader {
            val current = handle ?: return@onReader
            try {
                action(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emit(readerFailurePublication(sequence + 1, emitted, errorKind = null))
            }
        }
    }

    /** The core's publication, copied into the closed shape on the reader's thread. */
    private fun publish(publication: LibraryPublication) {
        val converted = try {
            publication.toApple(providerInstanceId, sequence + 1)
        } catch (_: Throwable) {
            readerFailurePublication(sequence + 1, emitted, errorKind = null)
        }
        emit(converted)
    }

    private fun emit(publication: AppleLibraryWindowPublication) {
        sequence = publication.sequence
        emitted = publication
        client.onMain { deliver(publication) }
    }

    /**
     * Main thread. The listener and the client's state are read HERE, so a close of this
     * subscription or of the client that ran first drops this publication.
     */
    private fun deliver(publication: AppleLibraryWindowPublication) {
        if (client.isClosed) return
        val current = listener.load() ?: return
        delivered.store(publication)
        current.onWindowPublication(publication)
    }

    /** A subscription made on a closed client: one statement of fact, never silence. */
    internal fun emitClosed() {
        val publication = readerFailurePublication(1, null, errorKind = "closed")
        client.onMain { listener.load()?.onWindowPublication(publication) }
    }
}

/**
 * One search (§16.15). [updateQuery] publishes the device's rows at once and the server's after
 * the core's debounce; [close] is idempotent and cancels the server request.
 */
@OptIn(ExperimentalAtomicApi::class)
public class AppleLibrarySearchSubscription internal constructor(
    private val client: AppleLibraryReaderClient,
    listener: AppleLibrarySearchListener,
) {
    private val listener = AtomicReference<AppleLibrarySearchListener?>(listener)

    // Reader-thread state.
    private var session: LibrarySearchSession? = null
    private var sequence = 0
    private var emitted: AppleLibrarySearchPublication? = null

    public fun updateQuery(text: String) {
        call { it.updateQuery(text) }
    }

    /** Runs the current query again, as if retyped. */
    public fun refresh() {
        call { it.refresh() }
    }

    public fun close() {
        if (listener.exchange(null) == null) return
        client.onReader(::closeOnReader)
    }

    internal fun open(composition: AppleLibraryReaderComposition?) {
        if (listener.load() == null) return
        client.register(this)
        if (composition == null) {
            emit(searchFailurePublication("internalFailure"))
            return
        }
        try {
            session = composition.session.openSearch(composition.searchConfig, ::publish)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emit(searchFailurePublication("internalFailure"))
        }
    }

    internal fun closeFromClient() {
        listener.store(null)
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
        client.unregister(this)
    }

    private fun call(action: (LibrarySearchSession) -> Unit) {
        if (listener.load() == null) return
        client.onReader {
            val current = session ?: return@onReader
            try {
                action(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emit(searchFailurePublication("internalFailure"))
            }
        }
    }

    private fun publish(publication: LibrarySearchPublication) {
        val converted = try {
            publication.toApple()
        } catch (_: Throwable) {
            searchFailurePublication("internalFailure")
        }
        emit(converted)
    }

    private fun emit(publication: AppleLibrarySearchPublication) {
        sequence += 1
        val numbered = AppleLibrarySearchPublication(
            publication.query, sequence, publication.scope, publication.errorKind,
            publication.seenArtistCount, publication.seenAlbumCount, publication.seenTrackCount, publication.rows,
        )
        emitted = numbered
        client.onMain { deliver(numbered) }
    }

    /** Main thread; see the window subscription's `deliver`. */
    private fun deliver(publication: AppleLibrarySearchPublication) {
        if (client.isClosed) return
        listener.load()?.onSearchPublication(publication)
    }

    /** The device's rows already shown stay, under a failed scope (an error never replaces content). */
    private fun searchFailurePublication(errorKind: String) = AppleLibrarySearchPublication(
        query = emitted?.query ?: "",
        sequence = 0,
        scope = "deviceServerFailed",
        errorKind = errorKind,
        seenArtistCount = null,
        seenAlbumCount = null,
        seenTrackCount = null,
        rows = emitted?.rows ?: emptyList(),
    )

    internal fun emitClosed() {
        val publication = AppleLibrarySearchPublication("", 1, "deviceServerFailed", "closed", null, null, null, emptyList())
        client.onMain { listener.load()?.onSearchPublication(publication) }
    }
}

/** The stream of favourite and rating outcomes. [close] is idempotent. */
@OptIn(ExperimentalAtomicApi::class)
public class AppleLibraryFavouriteOutcomeSubscription internal constructor(
    private val client: AppleLibraryReaderClient,
    listener: AppleLibraryFavouriteOutcomeListener,
) {
    private val listener = AtomicReference<AppleLibraryFavouriteOutcomeListener?>(listener)

    public fun close() {
        if (listener.exchange(null) == null) return
        client.onReader { client.unregister(this) }
    }

    internal fun closeFromClient() {
        listener.store(null)
        client.unregister(this)
    }

    /** Reader thread. */
    internal fun emit(outcome: AppleLibraryFavouriteOutcome) {
        if (listener.load() == null) return
        client.onMain { if (!client.isClosed) listener.load()?.onOutcome(outcome) }
    }
}

// ---- Viewport translation -----------------------------------------------------------------------------------

internal data class SeenViewport(val sequence: Int, val first: String?, val last: String?)

private fun AppleLibraryReaderItem.key(): String = "$kind\u0000$rawId"

/**
 * Carries a range of indexes into the publication the shell has seen ([seen]) over to the latest
 * one the reader has emitted, by item identity. Unchanged when they are the same publication or an
 * anchor is no longer present.
 */
internal fun translateViewport(
    first: Int,
    last: Int,
    seen: SeenViewport?,
    emitted: AppleLibraryWindowPublication?,
): Pair<Int, Int> {
    if (seen == null || emitted == null || seen.sequence == emitted.sequence) return first to last
    val from = emitted.indexNearest(seen.first, first) ?: return first to last
    val to = emitted.indexNearest(seen.last, last) ?: (from + (last - first))
    return from to maxOf(from, minOf(to, emitted.items.lastIndex))
}

private fun AppleLibraryWindowPublication.indexNearest(key: String?, near: Int): Int? {
    key ?: return null
    var best = -1
    for (index in items.indices) {
        if (items[index].key() != key) continue
        if (best < 0 || kotlin.math.abs(index - near) < kotlin.math.abs(best - near)) best = index
    }
    return best.takeIf { it >= 0 }
}
