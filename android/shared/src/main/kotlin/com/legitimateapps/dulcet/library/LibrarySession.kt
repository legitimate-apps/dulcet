package com.legitimateapps.dulcet.library

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryChangeOutcome
import com.legitimateapps.dulcet.core.AndroidLibraryConnection
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryHomeRow
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryOutcomeRegistration
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryQuery
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidLibraryReaderAccount
import com.legitimateapps.dulcet.core.AndroidLibraryWindow
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.search.SearchAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One screen host's view of the account's reader (spec §16.18, the Android paragraph).
 *
 * The reader itself is the process's one [AndroidLibraryReader] for the account; this class opens its
 * windows, turns each window's publications into a [StateFlow] for Compose — the only place a `Flow`
 * appears, inside the shell (§16.18) — and reports the platform's reachability and foreground state.
 * Freshness, coverage, playability and the overlay of a pending change all arrive in the core's
 * publications and are only drawn. What this class decides is the platform's part: when the app is
 * in the foreground, what the platform says about the network, and when to ask for a reconnect.
 *
 * **Freshness policy (§16.11).** There is no import and no timer here. The reader reads the catalog
 * epoch on reconnect and, while this session is started, every five minutes (core policy); a changed
 * epoch revalidates the visible windows. This session only says when the app is in the foreground.
 *
 * **Reachability (§16.14).** The reader has one way back online, its reconnect, and this session never
 * goes around it: the platform's default-network callback reports reachability with
 * [AndroidLibraryReader.setOnline] — whose `true`, while offline, REQUESTS a reconnect rather than
 * flipping any state — and [start] reconnects when the app comes to the foreground with a network. A
 * reconnect whose transport found the server unreachable reports it unreachable, so every screen says
 * offline rather than failing request by request. Any other failure (a timeout, credentials, TLS, the
 * server's own error) is [LibraryConnectionState.Failed]: when it left the reader offline the screens
 * state it with its reason and "Try again"; a reader that was online — a fresh one is — stays online
 * and its screens keep reading. While offline or failed-offline, the next network report, foreground,
 * [retry] or [refresh] tries again. Every callback of this class runs on the main thread, the
 * platform's network callbacks included.
 */
public class LibrarySession internal constructor(
    private val reader: AndroidLibraryReader,
    private val connectivity: ConnectivityManager?,
) : AutoCloseable {
    public constructor(context: Context, account: SearchAccount) : this(
        AndroidLibraryReader.forAccount(context, account.toReaderAccount()),
        context.applicationContext.getSystemService(ConnectivityManager::class.java),
    )

    private val observationState = MutableStateFlow(LibraryObservationState())

    /** Every publication each surface received, in order, and the reachability reports: for tests. */
    public val observation: StateFlow<LibraryObservationState> = observationState.asStateFlow()

    private val connectionState = MutableStateFlow<LibraryConnectionState>(LibraryConnectionState.Unknown)
    public val connection: StateFlow<LibraryConnectionState> = connectionState.asStateFlow()

    private fun setConnection(state: LibraryConnectionState) {
        connectionState.value = state
        observationState.update { it.copy(connections = (it.connections + state).takeLast(MAX_FRAMES)) }
    }

    private val latestOutcomes = MutableStateFlow<Map<AndroidLibraryEntity, AndroidLibraryChangeOutcome>>(emptyMap())

    /**
     * The latest favourite or rating outcome for each target, for a one-line message on the screen
     * showing that target; [Saved][AndroidLibraryChangeOutcome.Saved] needs none. Keyed by kind and
     * id, so an outcome about one entity is never shown on another's screen, and a later outcome
     * about a different one never hides it. A screen clears its own with [dismissOutcome] when it goes.
     */
    public val outcomes: StateFlow<Map<AndroidLibraryEntity, AndroidLibraryChangeOutcome>> = latestOutcomes.asStateFlow()

    public fun dismissOutcome(target: AndroidLibraryEntity) {
        latestOutcomes.update { it - target }
    }

    private val discarded = MutableStateFlow(0L)

    /**
     * Unsent changes the reader discarded because this device is now signed in as someone else
     * (§16.10): nonzero until [dismissDiscardedChanges], which acknowledges them on the process's
     * reader, so no later session — after a rotation, or a return to the screen — says it again.
     */
    public val discardedChanges: StateFlow<Long> = discarded.asStateFlow()
    private var discardedTold = false

    public fun dismissDiscardedChanges() {
        discarded.value = 0
        reader.acknowledgeDiscardedChanges()
    }

    private val surfaces = mutableListOf<LibrarySurface>()
    private var started = false
    private var closed = false

    /** Bumped by every reachability report, so an older reconnect's answer never overrides a newer report. */
    private var reachabilityGeneration = 0

    private val outcomeRegistration: AndroidLibraryOutcomeRegistration =
        reader.addChangeOutcomeListener { outcome ->
            latestOutcomes.update { it + (outcome.target to outcome) }
            observationState.update { it.copy(changeOutcomes = (it.changeOutcomes + outcome).takeLast(MAX_FRAMES)) }
        }

    /*
     * Every surface below is opened by the screen that shows it and closed when that screen goes, so
     * the reader's "visible screen" — what a reconnect or a changed epoch revalidates (§16.14 step 3) —
     * is exactly what is on screen. Whatever the order of a surface's open and [start], its first
     * publication is built from the cache before that window issues any request (CONF-76). On a
     * return to the foreground the windows are already open when [start] runs. At launch the order
     * differs by app. The phone restores its last tab; relaunched into the library, its screens are
     * composed inside its scaffold, whose content is composed during layout, after the lifecycle
     * effect that calls [start]: the reconnect's epoch read is issued first, and can still be in
     * flight when the windows open. The TV opens on search, and its library's windows open when the
     * person turns to it.
     */

    /** The home screen: N independent single-page rows (§16.9, CONF-86). The caller closes each. */
    public fun openHome(): List<LibraryHomeRowSurface> = HOME_ROWS.mapIndexed { index, row ->
        LibraryHomeRowSurface(row, openSurface("home.$index") { listener -> reader.openHomeRow(row, listener) })
    }

    /** The album grid, `alphabeticalByName`, windowed (§16.12). The caller closes it. */
    public fun openAlbums(): LibrarySurface = openSurface("albums") { listener ->
        reader.openWindow(AndroidLibraryQuery.AlbumList(AndroidAlbumListType.AlphabeticalByName), listener)
    }

    /** Every artist, one response (§16.9). The caller closes it. */
    public fun openArtists(): LibrarySurface = openSurface("artists") { listener ->
        reader.openWindow(AndroidLibraryQuery.Artists(), listener)
    }

    /** One album's detail. The caller closes it when the screen goes. */
    public fun openAlbum(rawId: String): LibrarySurface =
        openSurface("album:$rawId") { listener -> reader.openWindow(AndroidLibraryQuery.Album(rawId), listener) }

    /** One artist's detail: the artist and their albums. The caller closes it when the screen goes. */
    public fun openArtist(rawId: String): LibrarySurface =
        openSurface("artist:$rawId") { listener -> reader.openWindow(AndroidLibraryQuery.Artist(rawId), listener) }

    /**
     * Each album's track list, for playing an artist: every album's detail is opened, cached content
     * first, and [completion] receives each album's publication once its track list is present or
     * cannot be (offline and never opened, gone, failed). Albums are then closed. Nothing is read that
     * opening those albums one by one would not read. Returns a handle that abandons the collection.
     */
    public fun collectAlbums(albumRawIds: List<String>, completion: (List<AndroidLibraryPublication>) -> Unit): AutoCloseable {
        val settled = arrayOfNulls<AndroidLibraryPublication>(albumRawIds.size)
        val windows = mutableListOf<AndroidLibraryWindow>()
        var done = albumRawIds.isEmpty() || closed
        lateinit var handle: AutoCloseable
        fun finishIfSettled() {
            if (done || settled.any { it == null }) return
            done = true
            windows.forEach(AndroidLibraryWindow::close)
            collections -= handle
            completion(settled.map { it!! })
        }
        handle = AutoCloseable {
            if (!done) {
                done = true
                windows.forEach(AndroidLibraryWindow::close)
            }
            collections -= handle
        }
        if (closed) return handle
        collections += handle
        albumRawIds.forEachIndexed { index, rawId ->
            windows += reader.openWindow(AndroidLibraryQuery.Album(rawId)) { publication ->
                if (!done && publication.tracksSettled()) {
                    settled[index] = publication
                    finishIfSettled()
                }
            }
        }
        if (albumRawIds.isEmpty()) {
            collections -= handle
            completion(emptyList())
        }
        return handle
    }

    /** Collections still waiting for their albums; [close] abandons them. */
    private val collections = mutableListOf<AutoCloseable>()

    /** What this device has seen of these tracks, from the seen-cache alone; see [AndroidLibraryReader.seenTracks]. */
    public fun seenTracks(rawIds: List<String>, completion: (List<AndroidTrack>) -> Unit) {
        if (closed) completion(emptyList()) else reader.seenTracks(rawIds, completion)
    }

    /** Makes [target] a favourite or not; it shows in the next publication, before any request. */
    public fun toggleFavourite(target: AndroidLibraryEntity) {
        reader.toggleFavourite(target)
    }

    // ---- Lifecycle ----------------------------------------------------------------------------------

    /**
     * The app is in the foreground. Reports reachability and, with a network, reconnects (§16.14):
     * the outbox flush, the epoch read, and the visible screen revalidated. Nothing else.
     */
    public fun start() {
        if (closed || started) return
        started = true
        reader.setForeground(true)
        defaultNetwork = runCatching { connectivity?.activeNetwork }.getOrNull()
        registerNetworkCallback()
        if (networkAvailable()) {
            reportConstraint()
            // The platform's view, told again: the process's reader outlives sessions, and a
            // reconnect never changes what it was last told (§16.14).
            tell(true)
            reconnect()
        } else {
            report(false)
        }
    }

    /** The app left the foreground: nothing reads the epoch in the background (§16.11). */
    public fun stop() {
        if (!started) return
        started = false
        unregisterNetworkCallback()
        reader.setForeground(false)
    }

    /**
     * "Try again": a reconnect, the only way back online. With no network at all there is nothing to
     * try — the reader is not told the server is reachable when the platform says nothing is. With
     * one, the reader is told so first: a reconnect that found the server unreachable told it
     * otherwise ([reconnected]), and a reconnect never changes what the reader was last told.
     */
    public fun retry() {
        if (closed) return
        if (!networkAvailable()) {
            report(false)
            return
        }
        tell(true)
        reconnect()
    }

    /**
     * An explicit refresh (§16.11 rule 3): every open screen re-reads what is visible, whatever its
     * age. While the reader is offline — reported so, or left so by a failed reconnect — it is a
     * reconnect instead, because an offline reader reads nothing.
     */
    public fun refresh() {
        if (closed) return
        if (connectionState.value.readerOffline()) retry() else surfaces.toList().forEach(LibrarySurface::refresh)
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        outcomeRegistration.close()
        collections.toList().forEach(AutoCloseable::close)
        collections.clear()
        surfaces.toList().forEach(LibrarySurface::close)
        surfaces.clear()
    }

    // ---- Reachability ---------------------------------------------------------------------------------

    /** The default network last reported, so registering (which replays it) is not a change. */
    private var defaultNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == defaultNetwork) return
            defaultNetwork = network
            report(true)
        }

        override fun onLost(network: Network) {
            // A default network that another has already replaced is not a loss of reachability.
            if (network != defaultNetwork) return
            defaultNetwork = null
            report(networkAvailable())
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            reader.setNetworkConstrained(capabilities.isConstrained())
        }
    }

    private var callbackRegistered = false

    private fun registerNetworkCallback() {
        val manager = connectivity ?: return
        // On the main thread, like everything else here: without a handler the platform calls back on
        // its own thread, racing the reconnect outcomes this class handles on the main thread.
        callbackRegistered = runCatching {
            manager.registerDefaultNetworkCallback(networkCallback, Handler(Looper.getMainLooper()))
        }.isSuccess
    }

    private fun unregisterNetworkCallback() {
        if (!callbackRegistered) return
        callbackRegistered = false
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }

    private fun networkAvailable(): Boolean {
        val manager = connectivity ?: return true
        return runCatching { manager.activeNetwork != null }.getOrDefault(true)
    }

    private fun reportConstraint() {
        val manager = connectivity ?: return
        val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull() ?: return
        reader.setNetworkConstrained(capabilities.isConstrained())
    }

    private var lastReport: Boolean? = null

    /**
     * The platform's report, forwarded as is. Reachable while the reader is offline REQUESTS a
     * reconnect in the core; the [reconnect] call after it joins that one, for its outcome.
     */
    private fun report(reachable: Boolean) {
        if (closed || connectionState.value == LibraryConnectionState.Closed) return
        reachabilityGeneration += 1
        lastReport = reachable
        tell(reachable)
        if (!reachable) {
            setConnection(LibraryConnectionState.Offline(null))
        } else if (connectionState.value.readerOffline()) {
            // Offline, or a failed reconnect left the reader offline: a new network is a new try.
            reconnect()
        }
    }

    /**
     * What the reader is told the server's reachability is. The core keeps only the latest thing it
     * was told (§16.14): it never changes it itself, and it retries a reconnect that failed on its
     * own side only while that says reachable.
     */
    private fun tell(reachable: Boolean) {
        observationState.update { it.copy(reachabilityReports = it.reachabilityReports + reachable) }
        reader.setOnline(reachable)
    }

    /** Bumped by every [reconnect] call; only the latest call's outcome is acted on. */
    private var reconnectCalls = 0

    private fun reconnect() {
        // A closed reader cannot be reconnected; the state stays Closed.
        if (connectionState.value == LibraryConnectionState.Closed) return
        val generation = reachabilityGeneration
        val call = ++reconnectCalls
        setConnection(LibraryConnectionState.Connecting)
        observationState.update { it.copy(reconnects = it.reconnects + 1) }
        reader.reconnect { outcome -> if (call == reconnectCalls) reconnected(outcome, generation) }
    }

    private fun reconnected(outcome: AndroidLibraryConnection, generation: Int) {
        if (closed) return
        observationState.update { it.copy(reconnectAnswers = it.reconnectAnswers + 1) }
        if (outcome.closed) {
            // The process's reader was closed under this session: the account changed, or
            // closeCurrent closed it. Its screens receive nothing more and keep what they last
            // showed until they are replaced; nothing here can reconnect it, so no line and no
            // "Try again".
            setConnection(LibraryConnectionState.Closed)
            return
        }
        if (outcome.discardedPendingChanges > 0 && !discardedTold) {
            discardedTold = true
            discarded.value = outcome.discardedPendingChanges
        }
        // The platform reported the network gone while this ran: offline stands, whatever the
        // connection answer (a closed reader and a discard count are acted on above).
        if (generation != reachabilityGeneration && lastReport == false) return
        when {
            outcome.epochRead -> setConnection(LibraryConnectionState.Online(outcome.serverReportsNoEpoch))
            outcome.error.meansUnreachable() -> when {
                // Nothing changed while it ran: the server cannot be reached, and every screen says so.
                generation == reachabilityGeneration -> {
                    setConnection(LibraryConnectionState.Offline(outcome.error))
                    tell(false)
                }
                // A new network arrived while it ran: that network is tried once.
                else -> reconnect()
            }
            else -> setConnection(LibraryConnectionState.Failed(outcome.error, readerOffline = !outcome.readerOnline))
        }
    }

    // ---- Surfaces ---------------------------------------------------------------------------------------

    private fun openSurface(key: String, open: ((AndroidLibraryPublication) -> Unit) -> AndroidLibraryWindow): LibrarySurface {
        lateinit var surface: LibrarySurface
        val window = open { publication ->
            surface.deliver(publication)
            // Live content means the reader is reading again while this session still says it
            // reads nothing: its setup, which had failed, succeeded at a later call; the core's own
            // retry of a reconnect that failed on its side succeeded (§16.14), which no outcome
            // reports here; or another session's reconnect succeeded. The failure shown is stale.
            // In the foreground, a reconnect settles it, with the flush and the epoch read a fresh
            // reader has not had. After the core's own retry that is one sequence more than needed
            // (the epoch read and the visible screen again), because the facade cannot tell the two
            // apart. In the background nothing is read (§16.11), and start() reconnects on return.
            val state = connectionState.value
            if (started && publication.freshness == AndroidLibraryFreshness.Live &&
                state is LibraryConnectionState.Failed && state.readerOffline) {
                reconnect()
            }
            observationState.update { current ->
                val frames = current.surfaces[key].orEmpty() + LibraryFrame.of(publication)
                current.copy(surfaces = current.surfaces + (key to frames.takeLast(MAX_FRAMES)))
            }
        }
        surface = LibrarySurface(key, window) { surfaces -= it }
        surfaces += surface
        return surface
    }

    public companion object {
        /** The home rows, in order: recently added, recently played, most played, favourites. */
        public val HOME_ROWS: List<AndroidLibraryHomeRow> = listOf(
            AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest),
            AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Recent),
            AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Frequent),
            AndroidLibraryHomeRow.Favourites,
        )

        private const val MAX_FRAMES = 200
    }
}

private fun NetworkCapabilities.isConstrained(): Boolean =
    !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

/** The transport's own finding that the server cannot be reached; a slow server is not an offline one. */
private fun DomainError?.meansUnreachable(): Boolean = this == DomainError.Transport.Unreachable

/** One open window's publications as Compose state. */
public class LibrarySurface internal constructor(
    public val key: String,
    private val window: AndroidLibraryWindow,
    private val onClose: (LibrarySurface) -> Unit,
) {
    private val mutableState = MutableStateFlow<AndroidLibraryPublication?>(null)

    /** The latest publication, or null before the first one arrives. */
    public val state: StateFlow<AndroidLibraryPublication?> = mutableState.asStateFlow()

    internal fun deliver(publication: AndroidLibraryPublication) {
        mutableState.value = publication
    }

    public fun loadMore(): Unit = window.loadMore()

    public fun loadBefore(): Unit = window.loadBefore()

    public fun setViewport(first: Int, last: Int): Unit = window.setViewport(first, last)

    public fun refresh(): Unit = window.refresh()

    public fun close() {
        window.close()
        onClose(this)
    }
}

public class LibraryHomeRowSurface internal constructor(
    public val row: AndroidLibraryHomeRow,
    public val surface: LibrarySurface,
)

/** What the shell knows about the connection; the screens' own freshness is what they draw. */
public sealed interface LibraryConnectionState {
    public data object Unknown : LibraryConnectionState
    public data object Connecting : LibraryConnectionState
    public data class Online(val serverReportsNoEpoch: Boolean) : LibraryConnectionState

    /** Unreachable: reported by the platform (no error) or found by a reconnect. */
    public data class Offline(val error: DomainError?) : LibraryConnectionState

    /**
     * The epoch could not be read for another reason (a timeout, credentials, TLS, the server's own
     * failure; null for the device's own, including a setup that failed). [readerOffline] when the
     * reader reads nothing after it — it was offline, and only an epoch read brings it back (§16.14),
     * or its setup failed — which is when the screens say so, with this reason.
     */
    public data class Failed(val error: DomainError?, val readerOffline: Boolean) : LibraryConnectionState

    /**
     * The process's reader was closed under this session (the account changed, or closeCurrent
     * closed it). Final: no report or reconnect changes it.
     */
    public data object Closed : LibraryConnectionState
}

/** Whether the reader reads nothing now, and only a reconnect changes that. */
internal fun LibraryConnectionState.readerOffline(): Boolean =
    this is LibraryConnectionState.Offline || (this as? LibraryConnectionState.Failed)?.readerOffline == true

/**
 * State observations for tests. They carry no account data: catalog ids and freshness only.
 * [surfaces] maps a surface key (`home.0`, `albums`, `album:<id>`) to every publication it received.
 */
public data class LibraryObservationState(
    val surfaces: Map<String, List<LibraryFrame>> = emptyMap(),
    /**
     * Every reachability the session told the reader, in order: the platform's reports, and the
     * session's own — unreachable after a reconnect found the server so, reachable again before a
     * reconnect it starts with a network.
     */
    val reachabilityReports: List<Boolean> = emptyList(),
    val reconnects: Int = 0,
    /** Reconnect outcomes this session received, acted on or not. */
    val reconnectAnswers: Int = 0,
    /** Every connection state this session reported, in order. */
    val connections: List<LibraryConnectionState> = emptyList(),
    /** Every favourite or rating outcome, in order: entity ids and values, never account data. */
    val changeOutcomes: List<AndroidLibraryChangeOutcome> = emptyList(),
)

public data class LibraryFrame(
    val sequence: Int,
    val freshness: AndroidLibraryFreshness,
    val itemCount: Int,
    val itemsState: AndroidLibraryItemsState,
    val headerRawId: String?,
    val favourite: Boolean?,
    val itemsUnavailableReason: com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason? = null,
) {
    internal companion object {
        fun of(publication: AndroidLibraryPublication) = LibraryFrame(
            sequence = publication.sequence,
            freshness = publication.freshness,
            itemCount = publication.items.size,
            itemsState = publication.itemsState,
            headerRawId = publication.header?.rawId,
            favourite = when (val header = publication.header) {
                is AndroidLibraryItem.Album -> header.favourite
                is AndroidLibraryItem.Artist -> header.favourite
                is AndroidLibraryItem.Track -> header.favourite
                else -> null
            },
            itemsUnavailableReason = publication.itemsUnavailableReason,
        )
    }
}

public fun SearchAccount.toReaderAccount(): AndroidLibraryReaderAccount =
    AndroidLibraryReaderAccount(providerInstanceId, normalizedBaseUrl, username, password, allowLocalHttp)

// ---- Playback adapters ---------------------------------------------------------------------------------

/**
 * The album header's tracks as queue entries: those the core says can play now, by their playability
 * (§16.14), and never one with no metadata to show.
 */
public fun AndroidLibraryPublication.playableTracks(providerInstanceId: String): List<AndroidTrack> =
    titledTracks(providerInstanceId, playableOnly = true)

/**
 * Every track of the album header that has a title, playable now or not: what a queue already
 * holding them needs to show them, which does not depend on whether they can play at this moment.
 */
public fun AndroidLibraryPublication.titledTracks(providerInstanceId: String, playableOnly: Boolean = false): List<AndroidTrack> {
    val album = header as? AndroidLibraryItem.Album
    if (itemsState != AndroidLibraryItemsState.Present) return emptyList()
    return items.filterIsInstance<AndroidLibraryItem.Track>()
        .filter { !playableOnly || it.playability != AndroidLibraryPlayability.UnavailableOffline }
        .mapNotNull { it.toTrack(providerInstanceId, album) }
}

/** A queue entry for one track; a track without its own artwork or credits uses its album's. */
public fun AndroidLibraryItem.Track.toTrack(providerInstanceId: String, album: AndroidLibraryItem.Album? = null): AndroidTrack? {
    val name = title?.takeIf { it.isNotBlank() } ?: return null
    return AndroidTrack(
        providerInstanceId = providerInstanceId,
        rawId = rawId,
        title = name,
        artist = artistName?.takeIf { it.isNotBlank() } ?: album?.artistName?.takeIf { it.isNotBlank() },
        album = albumTitle ?: album?.title,
        durationMilliseconds = durationMilliseconds,
        artworkKey = artworkKey ?: album?.artworkKey,
    )
}

/** A track list that is present, or that this device cannot show at all right now. */
private fun AndroidLibraryPublication.tracksSettled(): Boolean =
    itemsState == AndroidLibraryItemsState.Present || freshness is AndroidLibraryFreshness.Unavailable ||
        (itemsState == AndroidLibraryItemsState.Unavailable && freshness != AndroidLibraryFreshness.Loading)

/** Whether a publication's freshness says the reader could not reach the server for it. */
public fun AndroidLibraryFreshness.isOffline(): Boolean = when (this) {
    is AndroidLibraryFreshness.Cached -> reason == com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.Offline
    is AndroidLibraryFreshness.Unavailable -> reason == com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason.NotCachedOffline
    else -> false
}
