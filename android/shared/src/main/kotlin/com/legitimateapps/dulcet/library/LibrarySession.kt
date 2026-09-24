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
 * offline rather than failing request by request; any other failure (a timeout, credentials, TLS, the
 * server's own error) is [LibraryConnectionState.Failed], which the screens state with its reason. The
 * next network change, foreground, [retry] or [refresh] tries again. Every callback of this class runs
 * on the main thread, the platform's network callbacks included.
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

    private val lastOutcome = MutableStateFlow<AndroidLibraryChangeOutcome?>(null)

    /**
     * The latest favourite or rating outcome, for a one-line message on the screen showing its
     * target; [Saved][AndroidLibraryChangeOutcome.Saved] needs none. A screen clears it with
     * [dismissOutcome] when it goes, so it is never shown again elsewhere.
     */
    public val outcomes: StateFlow<AndroidLibraryChangeOutcome?> = lastOutcome.asStateFlow()

    /** Clears the outcome message if it is about [rawId]. */
    public fun dismissOutcome(rawId: String) {
        lastOutcome.update { current -> if (current?.target?.rawId == rawId) null else current }
    }

    private val discarded = MutableStateFlow(0L)

    /**
     * Unsent changes the reader discarded because this device is now signed in as someone else
     * (§16.10), to be told once: nonzero until [dismissDiscardedChanges], and never raised again for
     * the same session.
     */
    public val discardedChanges: StateFlow<Long> = discarded.asStateFlow()
    private var discardedTold = false

    public fun dismissDiscardedChanges() {
        discarded.value = 0
    }

    private val surfaces = mutableListOf<LibrarySurface>()
    private var started = false
    private var closed = false

    /** Bumped by every reachability report, so an older reconnect's answer never overrides a newer report. */
    private var reachabilityGeneration = 0

    private val outcomeRegistration: AndroidLibraryOutcomeRegistration =
        reader.addChangeOutcomeListener { outcome ->
            lastOutcome.value = outcome
            observationState.update { it.copy(changeOutcomes = (it.changeOutcomes + outcome).takeLast(MAX_FRAMES)) }
        }

    /*
     * Every surface below is opened by the screen that shows it and closed when that screen goes, so
     * the reader's "visible screen" — what a reconnect or a changed epoch revalidates (§16.14 step 3) —
     * is exactly what is on screen. Whatever the order of a surface's open and [start], its first
     * publication is built from the cache before that window issues any request (CONF-76). The order
     * differs by app: the TV's screens are composed before the lifecycle effect that calls [start];
     * the phone's are composed inside its scaffold, whose content is composed during layout, after
     * that effect has run, so on the phone [start]'s epoch read is issued first.
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
     * try — the reader is not told the server is reachable when the platform says nothing is.
     */
    public fun retry() {
        if (closed) return
        if (!networkAvailable()) {
            report(false)
            return
        }
        reconnect()
    }

    /**
     * An explicit refresh (§16.11 rule 3): every open screen re-reads what is visible, whatever its
     * age. While the reader is not online — offline, or its last reconnect failed — it is a
     * reconnect instead, because an offline reader reads nothing.
     */
    public fun refresh() {
        if (closed) return
        when (connectionState.value) {
            is LibraryConnectionState.Offline, is LibraryConnectionState.Failed -> retry()
            else -> surfaces.toList().forEach(LibrarySurface::refresh)
        }
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
        if (closed) return
        reachabilityGeneration += 1
        lastReport = reachable
        observationState.update { it.copy(reachabilityReports = it.reachabilityReports + reachable) }
        reader.setOnline(reachable)
        if (!reachable) {
            setConnection(LibraryConnectionState.Offline(null))
        } else if (connectionState.value is LibraryConnectionState.Offline) {
            reconnect()
        }
    }

    /** Bumped by every [reconnect] call; only the latest call's outcome is acted on. */
    private var reconnectCalls = 0

    private fun reconnect() {
        val generation = reachabilityGeneration
        val call = ++reconnectCalls
        setConnection(LibraryConnectionState.Connecting)
        observationState.update { it.copy(reconnects = it.reconnects + 1) }
        reader.reconnect { outcome -> if (call == reconnectCalls) reconnected(outcome, generation) }
    }

    private fun reconnected(outcome: AndroidLibraryConnection, generation: Int) {
        if (closed || outcome.closed) return
        if (outcome.discardedPendingChanges > 0 && !discardedTold) {
            discardedTold = true
            discarded.value = outcome.discardedPendingChanges
        }
        when {
            outcome.epochRead -> setConnection(LibraryConnectionState.Online(outcome.serverReportsNoEpoch))
            outcome.error.meansUnreachable() -> when {
                // Nothing changed while it ran: the server cannot be reached, and every screen says so.
                generation == reachabilityGeneration -> {
                    setConnection(LibraryConnectionState.Offline(outcome.error))
                    reader.setOnline(false)
                }
                // A new network arrived while it ran: that network is tried once.
                lastReport == true -> reconnect()
                // The platform reported the network gone meanwhile; the reader is offline already.
                else -> setConnection(LibraryConnectionState.Offline(outcome.error))
            }
            else -> setConnection(LibraryConnectionState.Failed(outcome.error))
        }
    }

    // ---- Surfaces ---------------------------------------------------------------------------------------

    private fun openSurface(key: String, open: ((AndroidLibraryPublication) -> Unit) -> AndroidLibraryWindow): LibrarySurface {
        lateinit var surface: LibrarySurface
        val window = open { publication ->
            surface.deliver(publication)
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

    /** The epoch could not be read for another reason (credentials, TLS, the server's own failure). */
    public data class Failed(val error: DomainError?) : LibraryConnectionState
}

/**
 * State observations for tests. They carry no account data: catalog ids and freshness only.
 * [surfaces] maps a surface key (`home.0`, `albums`, `album:<id>`) to every publication it received.
 */
public data class LibraryObservationState(
    val surfaces: Map<String, List<LibraryFrame>> = emptyMap(),
    val reachabilityReports: List<Boolean> = emptyList(),
    val reconnects: Int = 0,
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
public fun AndroidLibraryPublication.playableTracks(providerInstanceId: String): List<AndroidTrack> {
    val album = header as? AndroidLibraryItem.Album
    if (itemsState != AndroidLibraryItemsState.Present) return emptyList()
    return items.filterIsInstance<AndroidLibraryItem.Track>()
        .filter { it.playability != AndroidLibraryPlayability.UnavailableOffline }
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
