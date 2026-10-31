package com.legitimateapps.dulcet.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

public data class AndroidPlaybackState(
    val title: String = "",
    val phase: String = "Stopped",
    val positionMilliseconds: Long = 0,
    val durationMilliseconds: Long? = null,
    val queueEntryId: String? = null,
    val playbackSessionId: String? = null,
    val attemptId: String? = null,
    val error: DomainError? = null,
    val validatedBytesConsumed: Long = 0,
    val artist: String? = null,
    val album: String? = null,
    val artworkKey: String? = null,
    /** The user's transport intent for the current session, not proof of sound. */
    val playWhenReady: Boolean = false,
    val queue: List<AndroidQueueEntry> = emptyList(),
    val currentIndex: Int? = null,
    val repeatMode: AndroidRepeatMode = AndroidRepeatMode.Off,
    val shuffle: Boolean = false,
    val seekable: Boolean = false,
    /** Next and Previous move within the queue: false at its ends unless repeat-all wraps. */
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    /** Previous would restart the current song: it is seekable and past the restart threshold. */
    val canRestart: Boolean = false,
    /**
     * The latest entry the queue moved past because its failure was the track's own (spec §12.12
     * rule 5). It is kept until another skip replaces it or the controller closes; how long it is
     * shown is the surface's decision, measured from [AndroidSkipNotice.postedAtElapsedMillis].
     */
    val skipNotice: AndroidSkipNotice? = null,
    /** The current attempt reads a downloaded file on this device, not the server (spec §14.5). */
    val playingDownload: Boolean = false,
    /**
     * Play Next and Add to Queue made while a new queue was loading, dropped because that queue never
     * started (spec §14.1). Kept until the surface has said so and calls
     * [AndroidPlaybackController.dismissDroppedAdditions], another drop replaces it, or the controller closes.
     */
    val droppedAdditions: AndroidDroppedAdditions? = null,
    /**
     * The selected song needs server contact that is not open (spec §13.1). The surface explains
     * the wait and offers Reconnect; a chosen connection's held selection follows automatic recovery.
     * Cleared by Connect/Reconnect, Stop, downloaded playback, or an authorized remote start,
     * including automatic recovery of the held selection and a new selection that supersedes it.
     */
    val needsReconnect: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val autoplayActive: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val sleepTimer: SleepTimerChoice? = null,
    val sleepRemainingMilliseconds: Long? = null,
    val queueConfirmation: AndroidQueueConfirmation? = null,
) {
    val hasSession: Boolean get() = playbackSessionId != null
}

/**
 * Where a downloaded song's local plan comes from: the account's download subsystem, which answers
 * null for a song with no complete, present download. Asked before anything is read from the
 * server, so a downloaded song starts with the network gone (spec §14.5).
 */
public fun interface AndroidLocalPlaybackSource {
    public suspend fun localPlan(rawId: String): LocalPlaybackPlan?

    /** Resolve and protect a file until this attempt releases it; probes use [localPlan]. */
    public suspend fun acquireLocalPlan(rawId: String): AndroidLocalPlaybackLease? = localPlan(rawId)?.let { resolved ->
        object : AndroidLocalPlaybackLease {
            override val plan: LocalPlaybackPlan = resolved
            override fun close() {}
        }
    }

    /**
     * The downloaded song's own metadata, kept with its download (spec §16.13), or null. Read from
     * the device alone: a restored or offline entry that is downloaded is named with no request.
     */
    public suspend fun localTrack(rawId: String): AndroidTrack? = null

    /** Current local playability, without networking or disk IO, for shared queue policy. */
    public fun isLocallyPlayable(rawId: String): Boolean = kotlinx.coroutines.runBlocking(Dispatchers.IO) { localPlan(rawId) != null }

    public fun setProtectedRawIds(rawIds: Set<String>) {}

    /** A best-effort tee of the engine's actual response; a cache failure never fails playback. */
    public fun beginStreamCacheWrite(
        plan: RemotePlaybackWirePlan, status: Int, contentType: String?, exactLength: Long?, position: Long,
    ): AndroidStreamCacheWrite? = null
}

/** A local file held against eviction from resolution through the playback attempt's lifetime. */
public interface AndroidLocalPlaybackLease : AutoCloseable {
    public val plan: LocalPlaybackPlan
    override fun close()
}

/**
 * Whether this process may contact the account's server on the person's behalf (spec §13.1). A launch
 * into a saved account sends nothing until the person chooses Connect or Reconnect. Until then the
 * controller restores the queue from the device, paused; plays a downloaded song from its file; keeps
 * a finished play in the outbox; reads nothing for titles or cover art that the device has not kept;
 * and says a song that needs the server needs Reconnect ([AndroidPlaybackState.needsReconnect]). Once
 * open it drains the outbox, names the queue and prepares the restored entry, still paused.
 */
public interface AndroidServerContact {
    /** True when the connection is chosen and the saved server identity is verified. */
    public fun isOpen(): Boolean

    /** A foreground host chose this connection, even if verification is temporarily unavailable. */
    public fun isChosen(): Boolean = isOpen()

    /** Returns once [isOpen] is true; at once when it already is. */
    public suspend fun awaitOpen()

    /** Waits for an already chosen connection's in-flight identity probe, without choosing Connect. */
    public suspend fun awaitValidation() {}
}

/**
 * One automatic skip past a track that could not play. [sequence] tells two skips of the same
 * track apart; [title] is null when the skipped entry's title is not known, and the surface then
 * says "a track". [postedAtElapsedMillis] is on the monotonic clock and is never persisted.
 */
public data class AndroidSkipNotice(val sequence: Long, val title: String?, val postedAtElapsedMillis: Long)

/**
 * Additions held for a queue that never started -- it failed, was replaced by another play, or was
 * stopped while loading -- and so were never applied. [trackCount] counts the tracks of every such
 * addition; [sequence] tells two drops apart.
 */
public data class AndroidDroppedAdditions(val sequence: Long, val trackCount: Int)

/** Display metadata for one selectable song. Identity is the opaque pair; the rest is presentation. */
public data class AndroidTrack(
    val providerInstanceId: String,
    val rawId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMilliseconds: Long? = null,
    val artworkKey: String? = null,
    val albumRawId: String? = null,
) {
    init {
        require(providerInstanceId.isNotBlank())
        require(rawId.isNotBlank())
    }
}

/** One Up Next row. [queueEntryId] addresses it; the same song may appear more than once. */
public data class AndroidQueueEntry(val queueEntryId: String, val track: AndroidTrack, val isAutoplay: Boolean = false)

/** An edit that has taken effect, claimed once by the foreground surface. */
public data class AndroidQueueConfirmation(val sequence: Long, val insertion: AndroidQueueInsertion, val trackCount: Int)

public enum class AndroidQueueSource { Album, Artist, Playlist, Library, Search }

/** Where [AndroidPlaybackController.addToQueue] puts tracks: straight after the current entry, or at the end. */
public enum class AndroidQueueInsertion { PlayNext, AddToQueue }

public enum class AndroidRepeatMode { Off, All, One }

/** Service-owned composition root. Core policy, queue identities and outbox are reused unchanged. */
public class AndroidPlaybackController internal constructor(
    context: Context,
    @Volatile private var account: PlaybackEndpointAccount,
    private val boundaries: AndroidPlaybackControllerBoundaries?,
    private val localPlans: AndroidLocalPlaybackSource? = boundaries?.localPlans,
    /** Null: the server may always be contacted, as for an account connected in this process. */
    private val serverContact: AndroidServerContact? = null,
) : AutoCloseable {
    init { require(MediaBindingIdentity.isValidAccount(account)) { "Invalid media binding" } }

    public constructor(context: Context, account: PlaybackEndpointAccount) : this(context, account, boundaries = null, localPlans = null)
    public constructor(context: Context, account: PlaybackEndpointAccount, localPlans: AndroidLocalPlaybackSource?) :
        this(context, account, boundaries = null, localPlans = localPlans)
    public constructor(
        context: Context,
        account: PlaybackEndpointAccount,
        localPlans: AndroidLocalPlaybackSource?,
        serverContact: AndroidServerContact?,
    ) : this(context, account, boundaries = null, localPlans = localPlans, serverContact = serverContact)
    private val untitledTitle = context.getString(R.string.playback_untitled)
    private val preferences = context.applicationContext.getSharedPreferences("listening-session", Context.MODE_PRIVATE)
    private var crossfadeSeconds = PlaybackCrossfade.decodeSeconds(preferences.getInt("crossfade-seconds", 0))
    private val sleepCountdown = SleepTimerCountdown()
    private var sleepTimer: SleepTimerChoice? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var queueConfirmation: AndroidQueueConfirmation? = null
    private var confirmationSequence = 0L
    private var autoplayJob: Job? = null
    private var autoplayKey: String? = null
    private var autoplayRetryAt = 0L
    private var autoplayAuthenticationRefused = false
    private val autoplayRetry = Runnable { answerAutoplay() }
    private var autoplayTransport: KtorLibraryEndpointTransport? = null
    private val retiredNetworkClients = mutableListOf<() -> Unit>()
    private val timerTick = object : Runnable {
        override fun run() {
            if (closed || sleepTimer == null) return
            tickSleepTimer()
            if (sleepTimer != null) timerHandler.postDelayed(this, 250)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = boundaries?.store ?: DulcetDriverFactory(context.applicationContext).openDulcetDatabase()
    private val resumes = PersistentResumePositionStore(store.database)
    @Volatile private var serverAvailable = AndroidLibraryReader.currentFor(account.providerInstanceId)?.serverAvailableForPlayback ?: true
    private var remoteRetryAttempt: AttemptId? = null
    // Only the explicit remote selection being held for contact may admit unavailable entries.
    // Ordinary offline queues that can start locally still filter out remote-only tracks.
    private var heldQueueAuthorization: Long? = null
    private var networkAvailable = true
    private val queue = PlaybackQueueController(PersistentQueueStore(store.database), resumes,
        PlaybackIdentitySource { "$it:${UUID.randomUUID()}" }, canPlayItem = { item ->
            // An explicit selection may be retained while this chosen connection revalidates.
            // This admits queue identity only; start() still gates every remote request.
            val reconnecting = serverContact?.isChosen() == true &&
                heldQueueAuthorization != null &&
                heldQueueAuthorization == AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)
            val remote = networkAvailable && (serverAvailable || localPlans == null || reconnecting)
            val local = !remote && localPlans?.isLocallyPlayable(item.rawId) == true
            remote || local
        })
    private var wire = PlaybackWireClient(account)
    /** The person's streaming quality and the network the service last reported (spec §12.5). */
    private val streamingQuality = StreamingQualityPolicy()
    @Volatile private var requests = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(
        account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp), "playback.resource")
    private var scrobbleRequests = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(
        account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp), "playback.scrobble")
    // Keep the delivery worker's retry/refusal state when credentials refresh; each future send
    // obtains the current client, while an already running send retains its original client.
    private val sender = ScrobbleEndpointSender(object : ScrobbleEndpointTransport {
        override suspend fun request(parameters: Map<String, String>): AuthenticatedEndpointResponse =
            scrobbleRequests.request("scrobble", parameters)

        override suspend fun extensions(): AuthenticatedEndpointResponse =
            scrobbleRequests.request("getOpenSubsonicExtensions", emptyMap())
    }, authorizeRequest = {
        when {
            AndroidServerIdentity.isRejected(account.providerInstanceId) -> DomainError.Protocol.ServerIdentityChanged
            closed || !contactOpen() -> DomainError.Transport.Cancelled
            else -> null
        }
    }, identityAuthorization = { AndroidServerIdentity.authorizationEpoch(account.providerInstanceId) })
    private val wall = OutboxWallClock(System::currentTimeMillis)
    private val outbox = PersistentScrobbleOutbox(store.database, wall)
    private val refusedDrops = AtomicLong()
    private val worker = ScrobbleOutboxDeliveryWorker(ServerId(account.providerInstanceId), outbox, sender,
        wall, OutboxMonotonicClock { SystemClock.elapsedRealtime().milliseconds },
        ScrobbleOutboxDiagnosticSink { event ->
            if (event is ScrobbleOutboxDiagnosticEvent.RefusedDropped) refusedDrops.incrementAndGet()
        }, validateServerIdentity = {
            AndroidServerIdentity.validate(account.providerInstanceId) {
                ServerIdentityGate(store.database, account.providerInstanceId,
                    AccountConnectionRequest(account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp)).validate()
            }
        })
    /**
     * Plays the server refused as its own (error 70, the track is gone) three times while it went on
     * accepting others, which the outbox therefore dropped since this controller was created (spec
     * §15.3). A diagnostic counter only: nothing reads it yet but tests, and the person is not told.
     */
    public val refusedPlaysDropped: Long get() = refusedDrops.get()
    private sealed interface DeliveryWork {
        data class Event(val event: RecordedPlaybackEvent) : DeliveryWork
        data class Barrier(val pending: CompletableDeferred<Int>) : DeliveryWork
    }
    private val deliveries = Channel<DeliveryWork>(Channel.UNLIMITED)
    private val retryDelivery = ScrobbleOutboxRetryLoop(scope, { worker.onForeground() })
    private var resolution: Job? = null
    private var startJob: Job? = null
    private var activePlan: PlaybackPlan? = null
    private var wantsPlay = false
    /**
     * The entry the core started while the server could not be contacted (spec §13.1): selected and
     * paused, nothing requested for it. Prepared, still paused, once the person connects.
     */
    private var heldStart: PlaybackQueueStartDirective? = null
    private var needsReconnect = false
    private var pendingResume: Long? = null
    private var requestGeneration = 0L
    // Play Next and Add to Queue accepted while a new queue was still resolving; applied, in the order
    // given, the moment that queue is in place. They belong to the resolution in flight and to no
    // other. Every path that cancels or ends that resolution without starting it drops them
    // (dropHeldAdditions) at that moment: another play (playQueue), Stop, a move within the old
    // queue -- Next, Previous, a Jump, a retry or restart, all through transition -- and a failed
    // read. So whatever is held is always the current resolution's. Close cancels the resolution,
    // and a closed controller applies nothing.
    private val heldAdditions = mutableListOf<HeldAddition>()
    private var droppedAdditions: AndroidDroppedAdditions? = null
    private var droppedAdditionsSequence = 0L
    @Volatile private var closed = false
    public val isClosed: Boolean get() = closed
    // Presentation metadata by opaque identity. It never decides what plays: the core queue does.
    private val metadata = mutableMapOf<ProviderItemId, AndroidTrack>()
    private val metadataLoads = mutableSetOf<ProviderItemId>()
    private var metadataFill: Job? = null
    private var artworkJob: Job? = null
    private var artworkBytes: Pair<String, ByteArray>? = null
    private var artwork: AndroidArtworkRepository? =
        if (boundaries == null) AndroidArtworkRepository(context, account) else boundaries.artwork
    private var failure: DomainError? = null
    private var skipNotice: AndroidSkipNotice? = null
    private var skipNoticeSequence = 0L
    private var consumed = AtomicLong()
    private var preloadConsumed: AtomicLong? = null
    private val mutableState = MutableStateFlow(AndroidPlaybackState(
        autoplayEnabled = preferences.getBoolean("autoplay", true), crossfadeSeconds = crossfadeSeconds))
    public val state: StateFlow<AndroidPlaybackState> = mutableState
    private val playbackContext = context.applicationContext
    private var preloadJob: Job? = null
    private var preloadAttempt: AttemptId? = null
    private var preloadTriedBoundary: String? = null
    private var progressedAttempt: AttemptId? = null
    private var preloadBlockedUntilMillis = 0L
    private val initialPlayer: Player = boundaries?.player ?: newPlayer()
    private fun newPlayer(handleFocus: Boolean = true): Player = ExoPlayer.Builder(playbackContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), handleFocus)
        setHandleAudioBecomingNoisy(true)
        repeatMode = Player.REPEAT_MODE_OFF
    }
    private val sessionBridge = if (boundaries?.player == null) AndroidSwitchingSessionPlayer(initialPlayer) else null
    private val exo: Player get() = engine.player
    private val engine: AndroidMedia3Engine = AndroidMedia3Engine(initialPlayer, onSystemPlayWhenReady = { wanted ->
        if (!closed) { wantsPlay = wanted; publish() }
    }, prepareSource = { plan -> preparePlaybackSource(plan, exo) },
        createOverlapPlayer = if (boundaries?.player == null) { { newPlayer(handleFocus = false) } } else boundaries.overlapPlayer,
        prepareOverlapSource = { plan, player -> preparePlaybackSource(plan, player) },
        onPlayerChanged = { player, plan ->
            activePlan = plan
            consumed = preloadConsumed ?: AtomicLong()
            preloadConsumed = null
            preloadAttempt = null
            sessionBridge?.switchTo(player)
        },
        onPreloadFailed = { attempt, plan, error ->
            if (plan is RemotePlaybackWirePlan) notePreloadFailure(plan, error)
            discardOverlap(attempt)
        })

    private val streamResources = mutableMapOf<AttemptId, AndroidCachingPlaybackResource>()
    private val preparedRawIds = mutableMapOf<AttemptId, String>()
    private val localLeases = mutableMapOf<AttemptId, AndroidLocalPlaybackLease>()

    private fun preparePlaybackSource(plan: PlaybackPlan, player: Player) {
        preparedRawIds[checkNotNull(plan.androidAttemptId)] = checkNotNull(plan.androidItemId).rawId
        localPlans?.setProtectedRawIds(setOfNotNull(activePlan?.androidItemId?.rawId,
            preloadAttempt?.let(preparedRawIds::get), plan.androidItemId?.rawId))
        val attemptConsumed = AtomicLong()
        if (player === engine.player) consumed = attemptConsumed else preloadConsumed = attemptConsumed
        val count: (Long) -> Unit = { bytes -> attemptConsumed.addAndGet(bytes) }
        val item = mediaItem(plan)
        when (plan) {
            is AndroidLocalPlaybackPlan -> if (boundaries?.prepareLocalSource != null) boundaries.prepareLocalSource.invoke(plan)
                else (player as ExoPlayer).setMediaSource(ProgressiveMediaSource.Factory(AndroidPlaybackDataSourceFactory(
                    plan.local.container,
                    AndroidLocalFilePlaybackResource(File(plan.local.absolutePath), plan.local.exactByteLength), count,
                )).createMediaSource(item))
            is RemotePlaybackWirePlan -> if (boundaries?.prepareSource != null) boundaries.prepareSource.invoke(plan)
                else (player as ExoPlayer).setMediaSource(ProgressiveMediaSource.Factory(
                    AndroidPlaybackDataSourceFactory(plan, AndroidCachingPlaybackResource(
                        httpPlaybackResource(plan), plan, localPlans,
                    ).also { streamResources.put(plan.attemptId, it)?.close() }, count),
                ).createMediaSource(item))
            else -> error("unsupported plan")
        }
    }

    internal fun httpPlaybackResource(plan: RemotePlaybackWirePlan): AndroidPlaybackResource {
        val resourceAccount = account
        return AndroidHttpPlaybackResource(resourceAccount, plan, requests) {
            if (!closed && !AndroidServerIdentity.isRejected(resourceAccount.providerInstanceId) &&
                AndroidMediaBindingIdentity.sameAccount(store, resourceAccount, account)) requests else null
        }
    }

    /**
     * Every transport verb a system controller can send takes the same path as an in-app command;
     * none reaches the player directly. Queue edits and media-item replacement are not offered.
     */
    public val sessionPlayer: Player = object : ForwardingPlayer(sessionBridge ?: initialPlayer) {
        override fun play() { this@AndroidPlaybackController.play() }
        override fun pause() { this@AndroidPlaybackController.pause() }
        override fun setPlayWhenReady(value: Boolean) { if (value) play() else pause() }
        override fun stop() { this@AndroidPlaybackController.stop() }
        // The controller prepares sources itself; a controller's generic prepare has nothing to add.
        override fun prepare() = Unit
        override fun seekTo(positionMs: Long) { this@AndroidPlaybackController.seek(positionMs) }
        // The player timeline holds only the current item, index 0. Any other index names nothing.
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex == 0) this@AndroidPlaybackController.seek(positionMs)
        }
        override fun seekToDefaultPosition() { this@AndroidPlaybackController.seek(0) }
        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            if (mediaItemIndex == 0) this@AndroidPlaybackController.seek(0)
        }
        override fun seekBack() { this@AndroidPlaybackController.seekBy(-exo.seekBackIncrement) }
        override fun seekForward() { this@AndroidPlaybackController.seekBy(exo.seekForwardIncrement) }
        override fun seekToNextMediaItem() { next() }
        override fun seekToNext() { next() }
        override fun seekToPreviousMediaItem() { previous() }
        override fun seekToPrevious() { skipToPrevious() }
        override fun setVolume(volume: Float) { if (live()) command(PlaybackCommand.SetVolume(id(), volume.toDouble())) }
        override fun setPlaybackSpeed(speed: Float) { if (live()) command(PlaybackCommand.SetRate(id(), speed.toDouble())) }
        // Media3 sees one item at a time because the core owns the queue. Skip commands are
        // therefore advertised from the core's queue, not from the one-item player timeline, or
        // the notification and lock screen would never offer next/previous.
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM)
            .remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE)
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        override fun hasNextMediaItem(): Boolean = !closed && queueHasNext()
        override fun hasPreviousMediaItem(): Boolean = !closed && queueHasPrevious()
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        instances += this
        queue.selectAccount(account.providerInstanceId)
        if (ownsActiveQueue() || queue.activeServerId() == null) {
            queue.setAutoplay(preferences.getBoolean("autoplay", true))
            queue.setCrossfade(crossfadeSeconds.seconds)
        }
        engine.setEventListener { event ->
            if (closed) return@setEventListener
            if (event is PlaybackEngineEvent.PlaybackProgressBegan) {
                progressedAttempt = event.attemptId
                // A local retry proves only that its file plays. URL resolution and a prepared
                // remote source also say nothing about contact: only the current remote attempt's
                // actual progress establishes recovery, never an outgoing/preloaded attempt.
                val remote = activePlan as? RemotePlaybackWirePlan
                if (networkAvailable && contactOpen() && remote?.attemptId == event.attemptId &&
                    queue.snapshot().currentSession?.currentAttempt?.attemptId == event.attemptId) serverAvailable = true
            }
            if (event is PlaybackEngineEvent.FailedBeforeStart) failure = event.error
            if (event is PlaybackEngineEvent.FailedAfterPartial) failure = event.error
            val failedAttempt = when (event) {
                is PlaybackEngineEvent.FailedBeforeStart -> event.attemptId.takeIf { event.error == DomainError.Transport.Unreachable }
                is PlaybackEngineEvent.FailedAfterPartial -> event.attemptId.takeIf { event.error == DomainError.Transport.Unreachable }
                else -> null
            }
            if (failedAttempt != null && failedAttempt == queue.snapshot().currentSession?.currentAttempt?.attemptId) serverAvailable = false
            if (event is PlaybackEngineEvent.EndedNaturally && !engine.isAdvancingOverlap)
                preloadAttempt?.let(::discardOverlap)
            val transition = queue.recordPlaybackEvent(event)
            capture(transition.effects)
            if (transition.stopPointReached) {
                wantsPlay = false
                clearSleepTimer()
            }
            transition.discardedPreloadAttemptId?.let(::discardOverlap)
            noteSkippedAfterFailure(transition)
            // The engine's attempt is over once it ends, fails, or the core moves on from it, and
            // the controller drops its plan. Nothing then seeks or restarts that attempt: seeks
            // from the app and the media session are refused, restart is not offered, and
            // Previous moves to the entry before instead of seeking a track that is over. While
            // the next entry resolves, Play takes the branch for an entry still resolving, whose
            // report begins the pass (spec §12.12 rule 3); Pause still reaches the engine, so it
            // stops asking to play.
            val failed = event is PlaybackEngineEvent.FailedBeforeStart || event is PlaybackEngineEvent.FailedAfterPartial
            if (event is PlaybackEngineEvent.EndedNaturally || failed || transition.startDirective != null) activePlan = null
            // A failure the core stops on -- a connection failure, not the track's own -- leaves
            // the entry selected and not playing, so the app and the system offer Play, and Play
            // begins a new attempt for it (a retry) instead of addressing the failed one.
            if (failed && transition.startDirective == null) wantsPlay = false
            publish()
            if (event is PlaybackEngineEvent.AdvancedToPreloaded) loadArtwork(checkNotNull(activePlan))
            if (event is PlaybackEngineEvent.Ready) {
                pendingResume?.let { position ->
                    pendingResume = null
                    if (event.seekability == PlaybackSeekability.Seekable) seek(position)
                }
            }
            transition.startDirective?.let { start(it) }
        }
        scope.launch {
            for (work in deliveries) when (work) {
                is DeliveryWork.Barrier -> work.pending.complete(outbox.pending(ServerId(account.providerInstanceId)).size)
                is DeliveryWork.Event -> when (val event = work.event) {
                    // Saved and not connected (§13.1): a downloaded song playing is not announced,
                    // and a finished play waits in the outbox, where capture() already put it.
                    is RecordedPlaybackEvent.NowPlaying -> if (contactOpen()) sender.send(ScrobbleEndpointRequest(event))
                    is RecordedPlaybackEvent.SubmittedPlay -> if (contactOpen()) drain()
                }
            }
        }
        if (contactOpen()) scope.launch { drain() }
        else serverContact?.let { contact -> scope.launch { contact.awaitOpen(); contactOpened() } }
        // Restore only this service's account. Another account's persisted queue is not even
        // opened as a core session: its entries would otherwise be reachable by Next, Previous
        // and Up Next and requested with this account's credentials.
        if (ownsActiveQueue()) {
            queue.restoreCurrentPaused().startDirective?.let {
                // The title arrives with the song's own metadata; nothing is invented meanwhile.
                start(it)
            }
            fillQueueMetadata()
        }
    }

    /** A known replacement always closes contact, including callers without a [serverContact]. */
    private fun contactOpen(): Boolean = !AndroidServerIdentity.isRejected(account.providerInstanceId) &&
        (serverContact?.isOpen() ?: true)

    private fun authorizesRemoteReply(authorization: Long?): Boolean = !closed && contactOpen() &&
        authorization != null && authorization == AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)

    /**
     * The person chose Connect or Reconnect: what waited for it goes now, as it does for an account
     * connected in this process. The outbox drains, the queue is named, and the restored entry is
     * prepared -- paused, because the person asked for no sound by connecting.
     */
    private fun contactOpened() {
        if (closed) return
        needsReconnect = false
        scope.launch { drain() }
        heldStart?.let { held ->
            heldStart = null
            if (activePlan == null && startJob?.isActive != true && resolution?.isActive != true &&
                queue.snapshot().currentSession?.playbackSessionId == held.playbackSessionId) start(held)
        }
        activePlan?.let(::loadArtwork)
        publish()
        fillQueueMetadata()
    }

    /** True when the core's active queue belongs to this service's account. */
    private fun ownsActiveQueue(): Boolean = queue.activeServerId() == ServerId(account.providerInstanceId)

    /** Refuses a queue verb on another account's queue before any request is made. */
    private fun refuseForeignQueue(): Boolean {
        if (ownsActiveQueue() || queue.activeServerId() == null) return false
        failure = DomainError.Auth.Forbidden; publish(); return true
    }

    /** Titles for queue rows from rows the caller already holds, such as the saved library. No request. */
    public fun rememberTracks(tracks: List<AndroidTrack>) {
        if (!live()) return
        var added = false
        for (track in tracks) {
            if (track.providerInstanceId != account.providerInstanceId) continue
            val known = metadata[ProviderItemId(track.providerInstanceId, track.rawId)]
            if (known == null || known.title.isBlank() || (known.title == untitledTitle && track.title.isNotBlank())) {
                remember(track); added = true
            }
        }
        if (added) {
            // Media3 keeps its own metadata snapshot; replacing the same item preserves transport.
            activePlan?.let { plan ->
                val player = exo as? ExoPlayer
                if (player?.mediaItemCount == 1) player.replaceMediaItem(0, mediaItem(plan))
            }
            publish()
        }
    }

    /**
     * The person's streaming-quality choice. It applies from the next item resolved; what is
     * playing is never restarted for it (spec §12.5). A download is never capped.
     */
    public fun setStreamingQuality(preference: StreamingQualityPreference) {
        streamingQuality.setPreference(preference)
    }

    /** The quality the next item is resolved at: the choice for the network last reported. */
    public val nextStreamingQuality: StreamingQuality get() = streamingQuality.currentQuality

    /** What the current network costs, as the service reads it from the OS. */
    /** Platform reachability for the shared queue's offline continuation policy. */
    public fun setNetworkAvailable(available: Boolean) {
        // Connectivity callbacks arrive off-main; serialize capability generations with delivery.
        scope.launch {
            if (closed) return@launch
            if (available) sender.invalidateCapabilities()
            networkAvailable = available
            if (available) answerAutoplay()
        }
    }

    public fun setNetworkCostClass(network: NetworkCostClass) {
        streamingQuality.setNetwork(network)
    }

    /** Resolves an opaque selected song id using this service's saved account, never intent credentials. */
    public fun playSong(providerInstanceId: String, rawId: String, displayTitle: String) {
        if (!live()) return
        if (providerInstanceId != account.providerInstanceId || rawId.isBlank()) {
            failure = DomainError.Auth.Forbidden; publish(); return
        }
        val known = metadata[ProviderItemId(providerInstanceId, rawId)]
        selectQueue(listOf(known ?: AndroidTrack(providerInstanceId, rawId, displayTitle)), 0,
            AndroidQueueSource.Search, "Search", resolveMetadata = true)
    }

    /**
     * Replaces the queue with [tracks] and starts [startIndex]. Every track must belong to this
     * service's account; a foreign identity is refused rather than played with the wrong credentials.
     * An album, artist or playlist queue names its source by opaque [sourceRawId]; library and search do not.
     * Duration may come from the supplied track, learned metadata, a local file or the decoder.
     * Missing duration never excludes a selected track or its neighbors.
     */
    public fun playQueue(
        tracks: List<AndroidTrack>,
        startIndex: Int,
        source: AndroidQueueSource,
        sourceName: String,
        sourceRawId: String? = null,
        shuffle: Boolean = false,
    ) {
        selectQueue(tracks, startIndex, source, sourceName, sourceRawId, shuffle)
    }

    private fun selectQueue(
        tracks: List<AndroidTrack>, startIndex: Int, source: AndroidQueueSource, sourceName: String,
        sourceRawId: String? = null, shuffle: Boolean = false, resolveMetadata: Boolean = false,
    ) {
        if (!live()) { android.util.Log.d("DulcetPlayQueue", "refused closed"); return }
        android.util.Log.d("DulcetPlayQueue", "received source=$source tracks=${tracks.size} start=$startIndex contact=${contactOpen()}")
        val named = source == AndroidQueueSource.Album || source == AndroidQueueSource.Artist || source == AndroidQueueSource.Playlist
        require(named == !sourceRawId.isNullOrBlank()) { "Album, artist and playlist queues, and only they, name a source" }
        if (tracks.isEmpty() || startIndex !in tracks.indices ||
            tracks.any { it.providerInstanceId != account.providerInstanceId }) {
            android.util.Log.d("DulcetPlayQueue", "refused invalid-selection")
            failure = DomainError.Auth.Forbidden; publish(); return
        }
        val admittedTracks = tracks.map(::withKnownDuration)
        val admittedStartIndex = startIndex
        requestGeneration++
        heldQueueAuthorization = null
        val previousWantsPlay = wantsPlay
        wantsPlay = true
        val generation = requestGeneration
        val authorization = AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)
        // Additions held for a queue this one supersedes would have been replaced by it.
        dropHeldAdditions()
        resolution?.cancel()
        startJob?.cancel()
        admittedTracks.forEach { remember(it) }
        val chosen = admittedTracks[admittedStartIndex]
        fun replaceSelection(song: Song?): PlaybackQueueStartDirective? {
            queueConfirmation = null
            command(PlaybackCommand.Stop(id()))
            failure = null; consumed = AtomicLong()
            val kind = when (source) {
                AndroidQueueSource.Album -> QueueSourceKind.Album
                AndroidQueueSource.Artist -> QueueSourceKind.Artist
                AndroidQueueSource.Playlist -> QueueSourceKind.Playlist
                AndroidQueueSource.Library -> QueueSourceKind.Library
                AndroidQueueSource.Search -> QueueSourceKind.Search
            }
            val transition = queue.replaceAndStart(PlaybackQueueRequest(
                admittedTracks.map { track ->
                    val id = ProviderItemId(track.providerInstanceId, track.rawId)
                    val selected = song?.takeIf { track.rawId == chosen.rawId }
                    val kept = metadata[id]
                    PlaybackQueueItem(id, selected?.track?.durationMilliseconds?.milliseconds
                        ?: track.durationMilliseconds?.milliseconds ?: kept?.durationMilliseconds?.milliseconds,
                        track.albumRawId ?: selected?.track?.albumRawId ?: kept?.albumRawId
                        ?: if (source == AndroidQueueSource.Album) sourceRawId else null)
                },
                QueueSourceContext(kind, sourceRawId?.let { ProviderItemId(account.providerInstanceId, it) },
                    sourceName.ifBlank { source.name }),
                if (shuffle) null else admittedStartIndex, shuffle))
            android.util.Log.d("DulcetPlayQueue", "accepted entries=${queue.snapshot().entries.size} directive=${transition.startDirective != null} generation=$generation")
            capture(transition.effects)
            captureEdit(queue.setAutoplay(preferences.getBoolean("autoplay", true)))
            captureEdit(queue.setCrossfade(crossfadeSeconds.seconds))
            applyHeldAdditions()
            return transition.startDirective
        }
        fun holdSelectionForContact(): Boolean {
            val contact = serverContact ?: return false
            // A saved account with no connection choice still follows §13.1. Only an already
            // chosen connection may retain Play across a transient loss of verification.
            if (contactOpen() || !contact.isChosen() || authorization == null ||
                authorization != AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)) return false
            // A raw-ID request waits for metadata after contact recovers; duration may stay unknown.
            if (resolveMetadata) return false
            heldQueueAuthorization = authorization
            val directive = replaceSelection(null) ?: run { heldQueueAuthorization = null; return false }
            needsReconnect = true
            publish()
            if (queue.snapshot().currentSession?.playbackSessionId == directive.playbackSessionId)
                start(directive, authorization = authorization)
            return true
        }
        var recoveredRawContact = false
        suspend fun awaitRawContact(): Boolean {
            val contact = serverContact ?: return false
            if (!resolveMetadata || contactOpen() || !contact.isChosen() || authorization == null ||
                authorization != AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)) return false
            needsReconnect = true; publish()
            do {
                contact.awaitOpen()
                coroutineContext.ensureActive()
                if (generation != requestGeneration || closed ||
                    authorization != AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)) return false
            } while (!contactOpen())
            recoveredRawContact = true
            return true
        }
        resolution = scope.launch {
            try {
                // Selection needs availability only. The selected attempt acquires and validates
                // its protected file in start(); an unleased preflight would leave a gap where
                // Clear Cache can remove it and also touches LRU for a choice not yet started.
                val downloaded = localPlans?.isLocallyPlayable(chosen.rawId) == true
                val localMetadata = if (downloaded) localTrack(chosen.rawId) else null
                if (generation != requestGeneration || closed) return@launch
                localMetadata?.let(::remember)
                // Cached neighbors need their album identity too: it decides gapless boundaries
                // even when the selection came from Search or a library list. This only enriches
                // metadata; an absent duration or local record never excludes an entry.
                for (track in admittedTracks.distinctBy { it.rawId }) {
                    if (track.rawId == chosen.rawId) continue
                    val known = metadata[ProviderItemId(track.providerInstanceId, track.rawId)]
                    if (known?.durationMilliseconds != null && known.albumRawId != null) continue
                    val kept = localTrack(track.rawId)
                    coroutineContext.ensureActive()
                    if (generation != requestGeneration || closed) return@launch
                    kept?.let(::remember)
                }
                // Wait for an in-flight lineage check before resolving this selection. A rejected
                // probe follows the ordinary Reconnect path below. Local playback never waits.
                if (!downloaded) {
                    android.util.Log.d("DulcetPlayQueue", "validation-await generation=$generation")
                    serverContact?.awaitValidation()
                    android.util.Log.d("DulcetPlayQueue", "validation-finished contact=${contactOpen()} current=${generation == requestGeneration} generation=$generation")
                }
                if (!downloaded) awaitRawContact()
                if (generation != requestGeneration || closed) return@launch
                if (!downloaded && !authorizesRemoteReply(authorization) && holdSelectionForContact()) return@launch
                if (!downloaded && !authorizesRemoteReply(authorization)) {
                    android.util.Log.d("DulcetPlayQueue", "refused before-metadata contact=${contactOpen()} authorizationPresent=${authorization != null} epochMatches=${authorization != null && authorization == AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)} generation=$generation")
                    // Saved and not connected (§13.1): the song needs the server, so nothing is
                    // sent and the queue stays as it was; the surface offers Reconnect.
                    if (generation == requestGeneration) {
                        dropHeldAdditions(); wantsPlay = false; needsReconnect = true; publish()
                    }
                    return@launch
                }
                val knownOffline = !networkAvailable || (!serverAvailable && localPlans != null && !recoveredRawContact)
                if (!downloaded && knownOffline && !shuffle) {
                    android.util.Log.d("DulcetPlayQueue", "refused offline generation=$generation")
                    if (generation == requestGeneration) {
                        wantsPlay = previousWantsPlay; failure = DomainError.Transport.Unreachable; publish()
                    }
                    return@launch
                }
                val song = if (downloaded || knownOffline) null else {
                    var resolved: Song? = null
                    while (resolved == null) {
                        try { resolved = loadSong(chosen.rawId) }
                        catch (cancelled: CancellationException) {
                            coroutineContext.ensureActive()
                            if (!awaitRawContact()) throw cancelled
                        }
                    }
                    resolved
                }
                if (generation != requestGeneration || closed) return@launch
                if (!downloaded && !authorizesRemoteReply(authorization) && holdSelectionForContact()) return@launch
                if (!downloaded && !authorizesRemoteReply(authorization)) {
                    android.util.Log.d("DulcetPlayQueue", "refused after-metadata contact=${contactOpen()} authorizationPresent=${authorization != null} epochMatches=${authorization != null && authorization == AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)} generation=$generation")
                    dropHeldAdditions(); wantsPlay = false; needsReconnect = true; publish()
                    return@launch
                }
                // Carry only this recovered selection through the reader's lagging outage hint.
                // start() consumes this authorization after local acquisition, as for a held queue.
                if (recoveredRawContact) heldQueueAuthorization = authorization
                val directive = replaceSelection(song) ?: run {
                    wantsPlay = false; failure = DomainError.Transport.Unreachable; publish()
                    return@launch
                }
                // Guard: a held addition never starts anything (see applyHeldAdditions). Were one ever
                // to, the core's choice is already starting, and starting this entry too would cancel it.
                if (queue.snapshot().currentSession?.playbackSessionId == directive.playbackSessionId) {
                    start(directive, song?.takeIf { directive.itemId.rawId == chosen.rawId }, authorization)
                }
            } catch (cancelled: CancellationException) {
                android.util.Log.d("DulcetPlayQueue", "cancelled current=${generation == requestGeneration} contact=${contactOpen()}")
                if (generation == requestGeneration && !closed && !contactOpen()) {
                    if (coroutineContext.isActive && holdSelectionForContact()) return@launch
                    dropHeldAdditions(); wantsPlay = false; needsReconnect = true; publish()
                }
                throw cancelled
            }
            // A queue that never came to be takes what was added to it along with it. Cancellation
            // drops nothing here: whoever cancelled -- a new play or Stop -- has dropped them already.
            // Nor does a read that fails after this resolution was superseded -- a read that ignores
            // cancellation does exactly that: what is held then is the newer queue's, and the failure
            // is of a queue nobody is waiting for.
            catch (error: AndroidPlaybackIOException) {
                android.util.Log.d("DulcetPlayQueue", "failed playback-io current=${generation == requestGeneration}")
                if (generation == requestGeneration) { dropHeldAdditions(); failure = error.error; publish() }
            } catch (_: Exception) {
                android.util.Log.d("DulcetPlayQueue", "failed unexpected current=${generation == requestGeneration}")
                if (generation == requestGeneration) { dropHeldAdditions(); failure = DomainError.Transport.Unreachable; publish() }
            }
        }
    }

    public fun play() {
        if (!live()) return
        if (activePlan == null) {
            if (resolution?.isActive == true || startJob?.isActive == true) {
                wantsPlay = true; recordPlayRequested(); return
            }
            if (refuseForeignQueue()) return
            if (heldStart != null && !contactOpen()) {
                // The entry needs the server, which waits for the person (§13.1): say so, send nothing.
                needsReconnect = true; publish(); return
            }
            wantsPlay = true
            recordPlayRequested()
            // The engine holds nothing live -- after Stop, after a failure the core stopped on, or
            // once the queue has finished. The Play reported above began a new pass; a finished
            // queue has no session to report it on, and its natural end already began one.
            // After a failure, Play is Android's Try Again (spec §12.1): a further attempt of the
            // same play, inside its session, resuming where the failure saved its position (or,
            // after a failure at or past the end, replaying it as a new session), so one
            // listen interrupted by a failure scrobbles once. Otherwise the selected entry starts
            // a new session.
            val failed = queue.snapshot().currentSession?.currentAttempt?.phase == PlaybackAttemptPhase.Failed
            // Try Again may probe the selected song while the server is known unreachable, but
            // it does not admit other remote songs. start() still validates any local file first;
            // a missing/stale file can fall back to this deliberate remote retry. Until genuine
            // remote progress, continuation and overlap keep using the last observed outage.
            val allowRemoteRetry = networkAvailable && contactOpen()
            val retry = if (failed) queue.retryCurrent(allowUnavailableCurrent = allowRemoteRetry)
                else queue.restartCurrent(ServerId(account.providerInstanceId))
            remoteRetryAttempt = retry.startDirective?.attemptId?.takeIf { failed && allowRemoteRetry }
            if (failed && retry.startDirective == null) {
                wantsPlay = false
                if (!contactOpen()) needsReconnect = true
                publish()
                return
            }
            transition(retry)
        } else { wantsPlay = true; recordPlayRequested(); command(PlaybackCommand.Play(id())) }
    }

    /**
     * Tells the core the person pressed Play (spec §12.12 rules 3 and 4), before the engine hears
     * it and whatever its readiness: it begins a new pass, and it lets a skip past a restored
     * entry that fails before Ready play on instead of staying paused.
     */
    private fun recordPlayRequested() {
        val session = queue.snapshot().currentSession ?: return
        capture(queue.recordPlayRequested(session.playbackSessionId).effects)
    }
    public fun pause() {
        if (!live()) return
        wantsPlay = false
        // Always to the engine, even with no attempt in progress: one that is over, or one of a
        // queue that has ended, would otherwise go on asking to play, and the media session would
        // report it. An engine holding nothing refuses the Pause, which is not a failure.
        command(PlaybackCommand.Pause(id()))
    }
    public fun togglePlayPause() {
        if (!live()) return
        if (wantsPlay && queue.snapshot().currentSession != null) pause() else play()
    }
    /**
     * Seeks the attempt in progress. With none there is nothing to seek: an entry still resolving,
     * or an attempt that is over -- ended, failed, or moved past by a skip.
     */
    public fun seek(positionMilliseconds: Long) {
        if (!live() || activePlan == null) return
        command(PlaybackCommand.Seek(id(), positionMilliseconds.coerceAtLeast(0).milliseconds))
    }
    private fun seekBy(deltaMilliseconds: Long) {
        if (!live() || activePlan == null) return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
        val target = (exo.currentPosition + deltaMilliseconds).coerceAtLeast(0)
        seek(duration?.let { target.coerceAtMost(it) } ?: target)
    }
    public fun stop() {
        if (!live()) return
        requestGeneration++; resolution?.cancel(); startJob?.cancel(); wantsPlay = false
        heldStart = null; heldQueueAuthorization = null; needsReconnect = false
        queueConfirmation = null
        dropHeldAdditions()
        command(PlaybackCommand.Stop(id()))
    }

    /**
     * Observes completion of playback delivery work queued before this call, without initiating
     * a send or cancelling one. Returns the durable plays still pending (for example after a
     * refused send). Call after a session ends to witness its complete successful delivery
     * window; while playback continues, later events can of course queue more work.
     */
    public suspend fun awaitQueuedPlaybackDelivery(): Int = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "The playback controller is closed" }
        val pending = CompletableDeferred<Int>()
        deliveries.send(DeliveryWork.Barrier(pending))
        pending.await()
    }

    /** Moves to the next entry. At the end of the queue without repeat-all it does nothing. */
    public fun next() {
        if (!live() || refuseForeignQueue() || !queueHasNext()) return
        transition(queue.next())
    }

    /**
     * Moves to the previous entry. At the start of the queue without repeat-all there is none, so
     * the current song restarts instead; the queue is never cleared by Previous.
     */
    public fun previous() {
        if (!live() || refuseForeignQueue()) return
        if (queueHasPrevious()) transition(queue.previous()) else restartCurrentSong()
    }

    /** Restarts the current song once it has played a few seconds, as a music player's back button does. */
    public fun skipToPrevious() {
        if (!live()) return
        if (activePlan != null && exo.currentPosition > RESTART_THRESHOLD_MILLISECONDS &&
            engine.seekability == PlaybackSeekability.Seekable) seek(0) else previous()
    }

    private fun restartCurrentSong() {
        if (activePlan == null) return
        if (engine.seekability == PlaybackSeekability.Seekable) seek(0)
        else {
            // An unseekable stream restarts as a new session of the same entry, which re-requests
            // it. That reaches the entry anew, so the person's Previous begins a new pass here as
            // it does wherever Previous moves (spec §12.12 rule 3). It restarts from the top and
            // clears the saved position, as a seek to zero would leave it: `replayCurrent`, not
            // `restartCurrent`, which resumes the listen (Play after a stop, spec §15.5).
            recordPlayRequested()
            transition(queue.replayCurrent(ServerId(account.providerInstanceId)))
        }
    }

    /** Plays the Up Next entry the user picked. Addressed by entry identity, never by row index. */
    public fun jumpTo(queueEntryId: String) {
        if (!live() || refuseForeignQueue()) return
        wantsPlay = true
        transition(queue.jumpTo(QueueEntryId(queueEntryId)))
    }

    /**
     * Play Next and Add to Queue (spec §14.1): [tracks] go straight after the current entry, in the
     * order given, or at the end. With no queue of this account's to add to, they play instead, as
     * a new queue from the first of them -- "Play Next" in an empty player means "play this".
     * Adding never touches the current session. The same rules as the Apple shells', through the
     * same core call.
     *
     * While a new queue is still resolving the addition is accepted and held, then applied to that
     * queue the moment it is in place, in the order the additions were made -- exactly where it would
     * have landed had the queue already started, as the Apple shells accept it. Held additions share
     * that queue's fate: one that is superseded, stopped or fails before it starts takes them with it,
     * and [AndroidPlaybackState.droppedAdditions] says so.
     *
     * False, and nothing changed, when refused: a closed controller, no tracks, or a track of another
     * account. A duration that the server omitted is learned from the decoder when available.
     */
    public fun addToQueue(
        tracks: List<AndroidTrack>,
        insertion: AndroidQueueInsertion,
        source: AndroidQueueSource,
        sourceName: String,
        sourceRawId: String? = null,
    ): Boolean {
        if (!live()) return false
        val named = source == AndroidQueueSource.Album || source == AndroidQueueSource.Artist || source == AndroidQueueSource.Playlist
        require(named == !sourceRawId.isNullOrBlank()) { "Album, artist and playlist queues, and only they, name a source" }
        if (tracks.isEmpty() || tracks.any { it.providerInstanceId != account.providerInstanceId }) return false
        val admitted = tracks.map(::withKnownDuration)
        if (queueLoading()) {
            heldAdditions += HeldAddition(admitted, insertion, source, sourceName, sourceRawId)
            return true
        }
        // The core's queue decides emptiness, not what a surface shows: an entry still resolving
        // shows no title yet, and playing instead would throw away what the person just started.
        if (!ownsActiveQueue() || queue.snapshot().entries.isEmpty()) {
            playQueue(admitted, 0, source, sourceName, sourceRawId)
            return true
        }
        return enqueue(admitted, insertion, source, sourceName, sourceRawId)
    }

    private class HeldAddition(
        val tracks: List<AndroidTrack>,
        val insertion: AndroidQueueInsertion,
        val source: AndroidQueueSource,
        val sourceName: String,
        val sourceRawId: String?,
    )

    /** True while a new queue is resolving: the core still holds the queue it will replace. */
    private fun queueLoading(): Boolean = resolution?.isActive == true

    /**
     * The held additions, applied to the queue just put in place in the order they were made. They are
     * all this resolution's: nothing else is ever held (see [heldAdditions]).
     *
     * This queue was replaced a moment ago, so no preload holds a natural end yet. The edit cannot
     * start an entry; [playQueue] guards the start that follows regardless.
     */
    private fun applyHeldAdditions() {
        val held = heldAdditions.toList()
        heldAdditions.clear()
        for (addition in held) {
            enqueue(addition.tracks, addition.insertion, addition.source, addition.sourceName, addition.sourceRawId)
        }
    }

    /** Drops the held additions with the resolution they were for, and says so when there were any. */
    private fun dropHeldAdditions() {
        if (heldAdditions.isEmpty()) return
        val tracks = heldAdditions.sumOf { it.tracks.size }
        heldAdditions.clear()
        droppedAdditions = AndroidDroppedAdditions(++droppedAdditionsSequence, tracks)
        publish()
    }

    /**
     * Claims drop [sequence] for a surface about to tell the person: true for the one call that
     * clears it, false once it is cleared or replaced, so two surfaces never both say it. A later drop
     * is kept.
     */
    public fun dismissDroppedAdditions(sequence: Long): Boolean {
        if (!live() || droppedAdditions?.sequence != sequence) return false
        droppedAdditions = null
        publish()
        return true
    }

    private fun enqueue(
        tracks: List<AndroidTrack>,
        insertion: AndroidQueueInsertion,
        source: AndroidQueueSource,
        sourceName: String,
        sourceRawId: String?,
    ): Boolean {
        tracks.forEach { remember(it) }
        val accepted = edit {
            queue.enqueue(PlaybackQueueInsertion(
                tracks.map { PlaybackQueueItem(ProviderItemId(it.providerInstanceId, it.rawId), it.durationMilliseconds?.milliseconds, it.albumRawId) },
                QueueSourceContext(source.kind(), sourceRawId?.let { ProviderItemId(account.providerInstanceId, it) },
                    sourceName.ifBlank { source.name }),
                when (insertion) {
                    AndroidQueueInsertion.PlayNext -> QueueInsertionMode.PlayNext
                    AndroidQueueInsertion.AddToQueue -> QueueInsertionMode.Append
                }))
        }
        if (accepted) {
            queueConfirmation = AndroidQueueConfirmation(++confirmationSequence, insertion, tracks.size)
            publish()
        }
        return accepted
    }

    /**
     * Moves the entry named by [queueEntryId] to [toIndex] in [AndroidPlaybackState.queue], which is
     * the whole queue in the order the listener sees. The current session plays on. False, and
     * nothing changed, for an entry no longer queued or a position outside the queue.
     *
     * Only Up Next is reordered, as on the Apple shells, whose list moves only the rows after the
     * playing one: the current entry, an entry already played, and a position at or before the
     * current entry are refused. Moving onto the current entry's position would put the entry
     * before it, where Next never reaches it. With no current entry the whole queue is Up Next.
     *
     * Refused while a new queue is loading: the edit would change the queue about to be replaced.
     */
    public fun moveEntry(queueEntryId: String, toIndex: Int): Boolean {
        if (!live() || queueLoading() || refuseForeignQueue()) return false
        val snapshot = queue.snapshot()
        snapshot.currentIndex?.let { current ->
            val from = snapshot.entries.indexOfFirst { it.queueEntryId.value == queueEntryId }
            if ((from != -1 && from <= current) || toIndex <= current) return false
        }
        return edit { queue.move(QueueEntryId(queueEntryId), toIndex) }
    }

    /**
     * Removes an entry that is not playing. The current entry is refused rather than interpreted
     * (spec §14.1): removing it would either stop the music or start something, and Next and Pause
     * already say which. False, and nothing changed, for the current entry or one no longer queued,
     * or while a new queue is loading (the queue shown is about to be replaced).
     */
    public fun removeEntry(queueEntryId: String): Boolean {
        if (!live() || queueLoading() || refuseForeignQueue()) return false
        return edit { queue.remove(QueueEntryId(queueEntryId)) }
    }

    /**
     * Removes every entry after the current one (spec §14.1). The current session plays on. False,
     * and nothing changed, while a new queue is loading (the queue shown is about to be replaced).
     */
    public fun clearUpcoming(): Boolean {
        if (!live() || queueLoading() || refuseForeignQueue()) return false
        return edit { queue.clearUpcoming() }
    }

    /**
     * Applies a queue edit. No edit is a session boundary (spec §14.1), so unlike [transition] this
     * stops nothing and starts nothing: it republishes the queue. If the edit discards a preload
     * holding a natural end, the core supplies the replacement start here.
     */
    private inline fun edit(change: () -> PlaybackQueueTransition): Boolean {
        val result = try { change() } catch (_: IllegalArgumentException) { return false }
        if (result.startDirective != null) { transition(result); return true }
        result.discardedPreloadAttemptId?.let(::discardOverlap)
        capture(result.effects)
        publish()
        fillQueueMetadata()
        return true
    }

    private fun AndroidQueueSource.kind(): QueueSourceKind = when (this) {
        AndroidQueueSource.Album -> QueueSourceKind.Album
        AndroidQueueSource.Artist -> QueueSourceKind.Artist
        AndroidQueueSource.Playlist -> QueueSourceKind.Playlist
        AndroidQueueSource.Library -> QueueSourceKind.Library
        AndroidQueueSource.Search -> QueueSourceKind.Search
    }

    private fun captureEdit(result: PlaybackQueueTransition) {
        if (result.stopPointReached) {
            wantsPlay = false
            clearSleepTimer()
        }
        result.discardedPreloadAttemptId?.let(::discardOverlap)
        capture(result.effects)
        result.startDirective?.let(::start)
    }

    public fun setAutoplay(enabled: Boolean) {
        if (!live()) return
        preferences.edit().putBoolean("autoplay", enabled).apply()
        if (ownsActiveQueue() || queue.activeServerId() == null) captureEdit(queue.setAutoplay(enabled))
        publish()
    }

    public fun setCrossfade(seconds: Int) {
        if (!live()) return
        crossfadeSeconds = PlaybackCrossfade.decodeSeconds(seconds)
        preferences.edit().putInt("crossfade-seconds", crossfadeSeconds).apply()
        if (ownsActiveQueue() || queue.activeServerId() == null) captureEdit(queue.setCrossfade(crossfadeSeconds.seconds))
        publish()
    }

    public fun setSleepTimer(choice: SleepTimerChoice?) {
        if (!live() || refuseForeignQueue()) return
        clearSleepTimer()
        sleepTimer = choice
        val stopPoint = when (choice) {
            SleepTimerChoice.EndOfTrack -> PlaybackStopPoint.EndOfCurrentEntry
            SleepTimerChoice.EndOfQueue -> PlaybackStopPoint.EndOfQueue
            else -> null
        }
        captureEdit(queue.setStopPoint(stopPoint))
        choice?.let { SleepTimerCountdown.lengthMilliseconds(it) }?.let {
            sleepCountdown.start(it, SystemClock.elapsedRealtime())
            timerHandler.postDelayed(timerTick, 250)
        }
        publish()
    }

    private fun clearSleepTimer() {
        sleepTimer = null
        sleepCountdown.cancel()
        timerHandler.removeCallbacks(timerTick)
        if (ownsActiveQueue() || queue.activeServerId() == null) captureEdit(queue.setStopPoint(null))
        engine.setSleepGain(1.0)
    }

    internal fun tickSleepTimer() {
        val now = SystemClock.elapsedRealtime()
        engine.setSleepGain(sleepCountdown.gain(now))
        if (sleepCountdown.remainingMilliseconds(now) == 0L) {
            pause()
            clearSleepTimer()
        }
        publish()
    }

    public fun dismissQueueConfirmation(sequence: Long): Boolean {
        if (!live() || queueConfirmation?.sequence != sequence) return false
        queueConfirmation = null
        publish()
        return true
    }

    private fun answerAutoplay() {
        if (closed || !ownsActiveQueue() || !contactOpen() || !networkAvailable || autoplayAuthenticationRefused || autoplayJob != null) return
        val remaining = autoplayRetryAt - android.os.SystemClock.elapsedRealtime()
        if (remaining > 0) {
            timerHandler.removeCallbacks(autoplayRetry)
            timerHandler.postDelayed(autoplayRetry, remaining)
            return
        }
        val authorization = AndroidServerIdentity.authorizationEpoch(account.providerInstanceId) ?: return
        val request = queue.takeAutoplayRequest() ?: return
        // Start dispatched: publication must finish before an immediate fake transport answers.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val tracks = try {
                boundaries?.autoplayCandidates?.invoke(request.seed.rawId, request.excludedRawIds, request.count)
                    ?: run {
                        val transport = autoplayTransport ?: KtorLibraryEndpointTransport(
                            LibraryBrowseRequest(account.providerInstanceId, account.normalizedBaseUrl,
                                account.username, account.password, account.allowLocalHttp),
                            saltSource = null, logSink = null, hostResolver = systemHostResolver(),
                        ).also { autoplayTransport = it }
                        AutoplayCandidateSource(account.providerInstanceId, transport) {
                            if (closed || !contactOpen() ||
                                authorization != AndroidServerIdentity.authorizationEpoch(account.providerInstanceId))
                                throw CancellationException("autoplay authorization retired")
                        }.candidates(request.seed.rawId, request.excludedRawIds, request.count).map { track ->
                                AndroidTrack(track.id.providerInstanceId, track.id.rawId, track.title,
                                    track.credits.joinToString { it.name }, track.albumTitle,
                                    track.duration.inWholeMilliseconds, track.artworkKey, track.albumRawId)
                            }
                    }
            } catch (failure: Exception) {
                // Retired operations cannot change a replacement's quiet period or credentials state.
                if (closed || autoplayJob !== coroutineContext[Job]) return@launch
                val error = failure.asLibraryError()
                val delay = maxOf(5_000L, (error as? DomainError.Server.Busy)?.retryAfter?.inWholeMilliseconds ?: 0)
                autoplayRetryAt = android.os.SystemClock.elapsedRealtime().let { now ->
                    if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
                }
                // A refusal waits for credentials to change; transient errors get a timed retry.
                if (error is DomainError.Auth) autoplayAuthenticationRefused = true
                if (!closed) queue.releaseAutoplayRequest(request.key)
                if (failure is CancellationException) throw failure
                return@launch
            }
            if (closed || autoplayJob !== coroutineContext[Job] || !networkAvailable || !contactOpen() ||
                authorization != AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)) {
                if (!closed) queue.releaseAutoplayRequest(request.key)
                return@launch
            }
            tracks.forEach(::remember)
            captureEdit(queue.appendAutoplay(request.key, tracks.map {
                PlaybackQueueItem(ProviderItemId(it.providerInstanceId, it.rawId), it.durationMilliseconds?.milliseconds, it.albumRawId)
            }))
            publish()
        }
        autoplayJob = job
        autoplayKey = request.key
        job.invokeOnCompletion {
            if (!closed) queue.releaseAutoplayRequest(request.key)
            if (autoplayJob === job) {
                autoplayJob = null
                autoplayKey = null
                if (!closed) timerHandler.post(autoplayRetry)
            }
        }
        job.start()
    }

    public fun setShuffle(enabled: Boolean) {
        if (!live() || refuseForeignQueue()) return
        captureEdit(queue.setShuffle(enabled)); publish()
    }

    public fun cycleRepeatMode() {
        if (!live() || refuseForeignQueue()) return
        captureEdit(queue.cycleRepeatMode()); publish()
    }

    private fun queueHasNext(): Boolean {
        val snapshot = queue.snapshot()
        val index = snapshot.currentIndex ?: return false
        return index < snapshot.entries.lastIndex || snapshot.repeatMode == QueueRepeatMode.All
    }

    private fun queueHasPrevious(): Boolean {
        val snapshot = queue.snapshot()
        val index = snapshot.currentIndex ?: return false
        return index > 0 || snapshot.repeatMode == QueueRepeatMode.All
    }

    private fun transition(transition: PlaybackQueueTransition) {
        requestGeneration++; resolution?.cancel(); startJob?.cancel()
        // A move within the queue the core holds abandons a new queue still loading, and with it
        // what was held for that queue.
        dropHeldAdditions()
        command(PlaybackCommand.Stop(id()))
        capture(transition.effects)
        transition.startDirective?.let { start(it) }
        publish()
        fillQueueMetadata()
    }

    /** [container] is null for a format this device cannot play directly; it is then transcoded. */
    private data class Song(val container: AudioContainer?, val duration: kotlin.time.Duration?, val track: AndroidTrack)
    private suspend fun loadSong(rawId: String): Song {
        val authorization = AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)
        if (!authorizesRemoteReply(authorization)) throw CancellationException("metadata authorization retired")
        val response = boundaries?.loadSong?.invoke(rawId) ?: requests.request("getSong", mapOf("id" to rawId))
        if (!authorizesRemoteReply(authorization)) throw CancellationException("metadata authorization retired")
        val envelope = parseLibraryEnvelope(response.body.decodeToString())
        if (response.statusCode in 200..299 && envelope != null && envelope.status == "failed") {
            // The server's own answer names whose failure it is: code 70 is a song it no longer
            // has, which is the track's (spec §12.12); an unknown code is not guessed at.
            val error = envelope.payload["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
            throw AndroidPlaybackIOException(AccountConnectionContract.mapSubsonicError(code, "", response.redactedUrl))
        }
        if (response.statusCode !in 200..299 || envelope?.status != "ok")
            throw AndroidPlaybackIOException(DomainError.Protocol.MalformedEnvelope)
        val song = envelope.payload["song"] as? JsonObject
            ?: throw AndroidPlaybackIOException(DomainError.Protocol.MalformedEnvelope)
        fun field(name: String) = (song[name] as? JsonPrimitive)?.contentOrNull
        if (field("id") != rawId) throw AndroidPlaybackIOException(DomainError.Protocol.MalformedEnvelope)
        val container = when (field("suffix")?.lowercase()) {
            "mp3" -> AudioContainer.Mp3
            "mp4", "m4a" -> AudioContainer.Mp4
            "flac" -> AudioContainer.Flac
            "ogg", "opus" -> AudioContainer.Ogg
            "wav" -> AudioContainer.Wav
            "aac" -> AudioContainer.AdtsAac
            // wma, aiff, ape, dsf, m4b and anything unknown: not refused, transcoded (see start()).
            else -> null
        }
        val duration = (song["duration"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.seconds
        val known = metadata[ProviderItemId(account.providerInstanceId, rawId)]
        val track = AndroidTrack(account.providerInstanceId, rawId,
            field("title")?.takeIf { it.isNotBlank() } ?: known?.title?.takeIf { it.isNotBlank() } ?: untitledTitle,
            field("artist")?.takeIf { it.isNotBlank() } ?: known?.artist,
            field("album")?.takeIf { it.isNotBlank() } ?: known?.album,
            duration?.inWholeMilliseconds ?: known?.durationMilliseconds,
            field("coverArt")?.takeIf { it.isNotBlank() } ?: known?.artworkKey,
            field("albumId") ?: known?.albumRawId)
        if (authorizesRemoteReply(authorization)) remember(track)
        return Song(container, duration, track)
    }

    /** [rawId]'s metadata as its download kept it, or null; never a request. */
    private suspend fun localTrack(rawId: String): AndroidTrack? = localPlans?.let { source ->
        try { source.localTrack(rawId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }?.takeIf { it.providerInstanceId == account.providerInstanceId && it.rawId == rawId }

    private fun remember(track: AndroidTrack) {
        val id = ProviderItemId(track.providerInstanceId, track.rawId)
        val known = metadata[id]
        val titled = if (track.title.isBlank()) track.copy(title = known?.title?.takeIf { it.isNotBlank() } ?: untitledTitle) else track
        metadata[id] = if (known == null) titled else AndroidTrack(track.providerInstanceId, track.rawId,
            titled.title, track.artist ?: known.artist, track.album ?: known.album,
            track.durationMilliseconds ?: known.durationMilliseconds, track.artworkKey ?: known.artworkKey,
            track.albumRawId ?: known.albumRawId)
    }

    private fun withKnownDuration(track: AndroidTrack): AndroidTrack =
        if (track.durationMilliseconds != null) track else track.copy(durationMilliseconds =
            metadata[ProviderItemId(track.providerInstanceId, track.rawId)]?.durationMilliseconds)

    private fun mediaItem(plan: PlaybackPlan): MediaItem {
        val track = plan.androidItemId?.let { metadata[it] }
        val art = artworkBytes?.takeIf { it.first == track?.artworkKey }?.second
        val details = MediaMetadata.Builder()
            .setTitle(track?.title?.ifBlank { null })
            .setArtist(track?.artist)
            .setAlbumTitle(track?.album)
            .setIsPlayable(true).setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        track?.durationMilliseconds?.let { details.setDurationMs(it) }
        art?.let { details.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return MediaItem.Builder().setMediaId(checkNotNull(plan.androidAttemptId).value).setUri("dulcet://resource")
            .setMediaMetadata(details.build()).build()
    }

    /**
     * Cover art reaches the media session as validated bytes after preparation begins, so a slow
     * cover never delays sound. Only the attempt that asked for it may be updated.
     */
    private fun loadArtwork(plan: PlaybackPlan) {
        artworkJob?.cancel()
        val key = plan.androidItemId?.let { metadata[it] }?.artworkKey ?: return
        if (artworkBytes?.first == key) return
        val repository = artwork ?: return
        val authorization = AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)
        val cachedOnly = !contactOpen()
        artworkJob = scope.launch {
            // Saved and not connected (§13.1): only what this device has kept.
            val bytes = try { repository.load(key, SESSION_ARTWORK_PIXELS, cachedOnly = cachedOnly) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null } ?: return@launch
            if (closed || activePlan?.androidAttemptId != plan.androidAttemptId ||
                (!cachedOnly && !authorizesRemoteReply(authorization))) return@launch
            artworkBytes = key to bytes
            val player = exo as? ExoPlayer ?: return@launch
            if (player.mediaItemCount == 1) player.replaceMediaItem(0, mediaItem(plan))
        }
    }

    /**
     * Up Next rows restored from a previous run have identities but no titles. Titles supplied by
     * [rememberTracks] cost nothing; the rest are read for a window around the current entry, once
     * per queue position rather than per publication, and each song at most once per process.
     */
    private fun fillQueueMetadata() {
        if (metadataFill?.isActive == true || closed) return
        val snapshot = queue.snapshot()
        val from = ((snapshot.currentIndex ?: 0) - 2).coerceAtLeast(0)
        val missing = snapshot.entries.drop(from).take(METADATA_FILL_LIMIT).map { it.itemId }
            .filter { it.providerInstanceId == account.providerInstanceId && it !in metadata && it !in metadataLoads }
            .distinct()
        if (missing.isEmpty()) return
        metadataLoads += missing
        metadataFill = scope.launch {
            for (id in missing) {
                // A downloaded song is named from what its download kept: no request, and offline
                // the song's own read would fail and leave the row untitled.
                val kept = localTrack(id.rawId)
                if (kept != null) { remember(kept); publish(); continue }
                // Saved and not connected (§13.1): read once the person connects, not now.
                if (!contactOpen()) { metadataLoads -= id; continue }
                try { loadSong(id.rawId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
                publish()
            }
        }
    }

    /** Late remote replies do not reopen a connection rejected while they were in flight. */
    private fun holdRemoteStartIfContactClosed(directive: PlaybackQueueStartDirective): Boolean {
        if (contactOpen()) return false
        heldStart = directive
        if (wantsPlay) { wantsPlay = false; needsReconnect = true }
        publish()
        return true
    }

    private fun start(
        directive: PlaybackQueueStartDirective,
        knownSong: Song? = null,
        authorization: Long? = AndroidServerIdentity.authorizationEpoch(account.providerInstanceId),
    ) {
        if (!directive.shouldAutoPlay) wantsPlay = false
        // The last line of defence: nothing is requested for another account's song.
        if (directive.itemId.providerInstanceId != account.providerInstanceId) {
            failure = DomainError.Auth.Forbidden; publish(); return
        }
        // A new attempt is under way, so the failure line belongs to one that is over: the
        // entry's preparing state replaces it (spec §12.12 rule 5). It is never left on screen
        // for a track that is not playing.
        failure = null
        heldStart = null
        val session = directive.playbackSessionId
        val generation = requestGeneration
        startJob?.cancel()
        startJob = scope.launch {
            suspend fun authorized(): Boolean {
                if (authorizesRemoteReply(authorization)) {
                    // An authorized start ends the explanation, even if a new selection already
                    // superseded the held token before its contact waiter could return.
                    needsReconnect = false
                    // Contact may have reopened during asynchronous local-file acquisition.
                    if (heldQueueAuthorization != null && heldQueueAuthorization == authorization) {
                        heldQueueAuthorization = null
                        remoteRetryAttempt = directive.attemptId
                    }
                    return true
                }
                val contact = serverContact
                if (contact != null && contact.isChosen() && authorization != null &&
                    authorization == AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)) {
                    needsReconnect = true
                    android.util.Log.d("DulcetPlayQueue", "held contact-closed generation=$generation")
                    publish()
                    // Observe the foreground reader's existing recovery. No request or timer is
                    // started here. Stop, a newer selection or moving within the queue cancels it;
                    // Pause keeps the selected entry but withdraws its request for sound.
                    do {
                        contact.awaitOpen()
                        if (generation != requestGeneration || closed ||
                            queue.snapshot().currentSession?.playbackSessionId != session) return false
                        if (authorization != AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)) {
                            wantsPlay = false
                            failure = DomainError.Protocol.ServerIdentityChanged
                            android.util.Log.d("DulcetPlayQueue", "refused held-authorization generation=$generation")
                            publish()
                            return false
                        }
                    } while (!contactOpen())
                    needsReconnect = false
                    heldQueueAuthorization = null
                    // Identity opens before the reader publishes its later availability hint.
                    // The held selection may prepare once against that observed outage, like
                    // Try Again, but never OS-offline. Pause still prepares without sound.
                    remoteRetryAttempt = directive.attemptId
                    android.util.Log.d("DulcetPlayQueue", "resumed contact-open generation=$generation")
                    return true
                }
                holdRemoteStartIfContactClosed(directive)
                return false
            }
            var pendingLease: AndroidLocalPlaybackLease? = null
            try {
                // A downloaded song plays from its file, and nothing is asked of the server first:
                // offline, the song's own read would fail before the file was ever tried.
                pendingLease = localPlans?.let { source ->
                    try { withContext(NonCancellable) { source.acquireLocalPlan(directive.itemId.rawId) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                coroutineContext.ensureActive()
                val local = pendingLease?.plan
                if (generation != requestGeneration || queue.snapshot().currentSession?.playbackSessionId != session || closed) return@launch
                if (local != null) {
                    // A restored or untitled entry takes its title from what the download kept,
                    // before the media item is built from it.
                    if (metadata[directive.itemId]?.title.isNullOrBlank()) {
                        localTrack(directive.itemId.rawId)?.let(::remember)
                        if (generation != requestGeneration || queue.snapshot().currentSession?.playbackSessionId != session || closed) return@launch
                    }
                    command(PlaybackCommand.Stop(id()))
                    needsReconnect = false
                    heldQueueAuthorization = null
                    val plan = AndroidLocalPlaybackPlan(session, directive.attemptId, directive.itemId, local)
                    activePlan = plan
                    localLeases.put(directive.attemptId, checkNotNull(pendingLease))?.close()
                    pendingLease = null
                    pendingResume = directive.resumePosition?.inWholeMilliseconds
                    command(PlaybackCommand.Prepare(id(), plan.attemptId, plan))
                    command(if (wantsPlay) PlaybackCommand.Play(id()) else PlaybackCommand.Pause(id()))
                    loadArtwork(plan)
                    return@launch
                }
                if (!authorized()) return@launch
                // Availability is deliberately cheap; a file may disappear between queue
                // selection and validation. Only an authorized explicit retry may probe past an
                // observed outage, and OS loss still refuses the wire even for that retry.
                if (!networkAvailable || (!serverAvailable && localPlans != null && remoteRetryAttempt != directive.attemptId)) {
                    failBeforeStart(directive, DomainError.Transport.Unreachable, generation)
                    return@launch
                }
                val song = knownSong ?: loadSong(directive.itemId.rawId)
                if (generation != requestGeneration || queue.snapshot().currentSession?.playbackSessionId != session || closed) return@launch
                if (!authorized()) return@launch
                // A format with no direct-play container goes through the profile's transcoding
                // target on the legacy path (`stream?format=`). That path reads the source container
                // only when no format is requested, so the target stands in for an unnamed source.
                val transcode = ANDROID_PROFILE.transcodingProfiles.first().container
                val request = PlaybackResolveRequest(session, directive.attemptId, directive.itemId,
                    song.container ?: transcode, false, ANDROID_PROFILE,
                    LegacyPlaybackPreference(if (song.container == null) transcode else null, null))
                    .withStreamingQuality(streamingQuality.currentQuality)
                val result = boundaries?.resolve?.invoke(request) ?: wire.resolve(request)
                if (generation != requestGeneration || queue.snapshot().currentSession?.playbackSessionId != session || closed) return@launch
                if (!authorized()) return@launch
                when (result) {
                    is PlaybackResolutionResult.Failed -> failBeforeStart(directive, result.error)
                    is PlaybackResolutionResult.Resolved -> {
                        command(PlaybackCommand.Stop(id()))
                        activePlan = result.plan
                        pendingResume = directive.resumePosition?.inWholeMilliseconds
                        android.util.Log.d("DulcetPlayQueue", "prepared remote generation=$generation")
                        command(PlaybackCommand.Prepare(id(), result.plan.attemptId, result.plan))
                        command(if (wantsPlay) PlaybackCommand.Play(id()) else PlaybackCommand.Pause(id()))
                        loadArtwork(result.plan)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (generation == requestGeneration && !closed) {
                    if (coroutineContext.isActive && !contactOpen() && serverContact?.isChosen() == true &&
                        authorization != null && authorization == AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)) {
                        if (authorized()) start(directive, authorization = authorization)
                        return@launch
                    }
                    holdRemoteStartIfContactClosed(directive)
                }
                throw cancelled
            }
            catch (error: AndroidPlaybackIOException) { failBeforeStart(directive, error.error, generation) }
            catch (_: Exception) { failBeforeStart(directive, DomainError.Transport.Unreachable, generation) }
            finally { pendingLease?.close() }
        }
    }

    /**
     * A start that failed before the engine had it: the song could not be read or resolved. It is
     * the entry's failure as much as an engine's would be, so the core hears it the same way and
     * may skip past it (spec §12.12); otherwise it is presented. The recursion through [start] is
     * bounded by the core's chain guard and its one pass.
     */
    private fun failBeforeStart(directive: PlaybackQueueStartDirective, error: DomainError, generation: Long = requestGeneration) {
        failure = error
        if (generation == requestGeneration && !closed &&
            queue.snapshot().currentSession?.currentAttempt?.attemptId == directive.attemptId) {
            if (error == DomainError.Transport.Unreachable) serverAvailable = false
            val transition = queue.recordPlaybackEvent(PlaybackEngineEvent.FailedBeforeStart(directive.attemptId, error))
            capture(transition.effects)
            noteSkippedAfterFailure(transition)
            // Stopped on the entry, as for an engine's connection failure: Play retries it.
            if (transition.startDirective == null) wantsPlay = false
            publish()
            transition.startDirective?.let { start(it) }
        } else publish()
    }

    /**
     * The core moved past a track whose failure was its own (spec §12.12 rule 5): say which, once.
     * The failure line goes here, not only when [start] begins the next entry: the notice is
     * published before that start, and a surface draws every publication it is handed.
     */
    private fun noteSkippedAfterFailure(transition: PlaybackQueueTransition) {
        val skipped = transition.skippedAfterFailure ?: return
        val entry = transition.snapshot.entries.firstOrNull { it.queueEntryId == skipped }
        val title = entry?.let { metadata[it.itemId]?.title }?.takeIf { it.isNotBlank() }
        failure = null
        skipNotice = AndroidSkipNotice(++skipNoticeSequence, title, SystemClock.elapsedRealtime())
    }

    private fun capture(effects: List<PlaybackCoreEffect>) {
        for (effect in effects) when (effect) {
            is PlaybackCoreEffect.RecordPlaybackEvent -> {
                if (effect.event is RecordedPlaybackEvent.SubmittedPlay) outbox.persistSynchronously(effect.event)
                if (boundaries?.enqueueDelivery != null) boundaries.enqueueDelivery.invoke(effect.event)
                else check(deliveries.trySend(DeliveryWork.Event(effect.event)).isSuccess)
            }
            is PlaybackCoreEffect.PersistResumePosition -> resumes.save(effect.itemId, effect.position)
            is PlaybackCoreEffect.ClearResumePosition -> resumes.clear(effect.itemId)
            is PlaybackCoreEffect.AccumulatorDiagnostic -> Unit
        }
    }

    /** One drain; a play left unsent is retried on the worker's backoff while the service lives (§15.3). */
    private suspend fun drain() = retryDelivery.drainNow()

    private fun command(command: PlaybackCommand) {
        checkMain()
        if (command is PlaybackCommand.Stop || command is PlaybackCommand.Release) {
            preloadJob?.cancel(); preloadJob = null
            preloadAttempt?.let { queue.discardPreload(it) }
            preloadAttempt = null
            activePlan = null; pendingResume = null
        }
        val outcome = engine.executeOnPlayerThread(command)
        if (command !is PlaybackCommand.Release) sessionBridge?.refresh()
        if (outcome is PlaybackCommandOutcome.CommandRejected) {
            val reason = outcome.reason
            // A refused transport verb (seek while not seekable, pause with nothing prepared, a
            // lock-screen button pressed between songs) is not a playback failure and must not
            // replace the screen with an error. A failed or unpreparable source is.
            if (reason is PlaybackCommandRejectionReason.Failed) failure = reason.error
            else if (command is PlaybackCommand.Prepare || command is PlaybackCommand.ReplaceCurrent)
                failure = DomainError.Protocol.UnexpectedBinary
            if (command is PlaybackCommand.Prepare || command is PlaybackCommand.ReplaceCurrent) {
                activePlan?.androidAttemptId?.let { localLeases.remove(it)?.close() }
                activePlan = null
            }
        }
        publish()
    }

    private fun publish() {
        val snapshot = queue.snapshot()
        val session = snapshot.currentSession
        val retainedAttempts = setOfNotNull(activePlan?.androidAttemptId, preloadAttempt)
        streamResources.keys.filterNot { it in retainedAttempts }.forEach { streamResources.remove(it)?.close() }
        preparedRawIds.keys.retainAll(retainedAttempts)
        localLeases.keys.filterNot { it in retainedAttempts }.forEach { localLeases.remove(it)?.close() }
        localPlans?.setProtectedRawIds(buildSet {
            activePlan?.androidItemId?.rawId?.let(::add)
            preloadAttempt?.let(preparedRawIds::get)?.let(::add)
        })
        val current = session?.let { metadata[it.itemId] }
        val entries = snapshot.entries.map { entry ->
            AndroidQueueEntry(entry.queueEntryId.value, metadata[entry.itemId]
                ?: AndroidTrack(entry.itemId.providerInstanceId, entry.itemId.rawId, ""), entry.isAutoplay)
        }
        // Without a session the player still holds the last song's position; it describes nothing.
        mutableState.value = AndroidPlaybackState(current?.title.orEmpty(),
            session?.currentAttempt?.phase?.name ?: "Stopped",
            if (session == null) 0 else exo.currentPosition.coerceAtLeast(0),
            if (session == null) null
            else exo.duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: current?.durationMilliseconds,
            session?.queueEntryId?.value, session?.playbackSessionId?.value,
            session?.currentAttempt?.attemptId?.value, failure, consumed.get(),
            artist = current?.artist, album = current?.album, artworkKey = current?.artworkKey,
            playWhenReady = wantsPlay && session != null,
            queue = entries, currentIndex = snapshot.currentIndex,
            repeatMode = when (snapshot.repeatMode) {
                QueueRepeatMode.Off -> AndroidRepeatMode.Off
                QueueRepeatMode.All -> AndroidRepeatMode.All
                QueueRepeatMode.One -> AndroidRepeatMode.One
            },
            shuffle = snapshot.shuffleState == QueueShuffleState.Enabled,
            seekable = session?.currentAttempt?.seekability == PlaybackSeekability.Seekable,
            canGoNext = session != null && queueHasNext(),
            canGoPrevious = session != null && queueHasPrevious(),
            canRestart = session != null && activePlan != null &&
                engine.seekability == PlaybackSeekability.Seekable &&
                exo.currentPosition > RESTART_THRESHOLD_MILLISECONDS,
            skipNotice = skipNotice,
            playingDownload = session != null && activePlan is AndroidLocalPlaybackPlan,
            droppedAdditions = droppedAdditions,
            needsReconnect = needsReconnect,
            autoplayEnabled = snapshot.autoplayEnabled, autoplayActive = snapshot.autoplayActive,
            crossfadeSeconds = crossfadeSeconds, sleepTimer = sleepTimer,
            sleepRemainingMilliseconds = sleepCountdown.remainingMilliseconds(SystemClock.elapsedRealtime()),
            queueConfirmation = queueConfirmation)
        engine.setCrossfade(snapshot.crossfadeIntoNext.inWholeMilliseconds)
        if (snapshot.crossfadeIntoNext.inWholeMilliseconds == 0L) {
            // An intentional zero boundary (Off or a sleep stop point) cancels this choice.
            // Turning it back on may preload again; a failure at an unchanged boundary may not.
            preloadTriedBoundary = null
            preloadAttempt?.let(::discardOverlap)
        }
        else prepareOverlap()
        answerAutoplay()
    }

    private fun notePreloadFailure(plan: RemotePlaybackWirePlan, error: DomainError) =
        notePreloadResolutionFailure(error, plan.isTranscoded())

    private fun notePreloadResolutionFailure(error: DomainError, isTranscoded: Boolean) {
        wire.transcodeBudget.observeFailure(PlaybackWireRequestPurpose.Preload, isTranscoded, error)
        if (error is DomainError.Server.Busy) preloadBlockedUntilMillis = SystemClock.elapsedRealtime() +
            (error.retryAfter?.inWholeMilliseconds ?: 5_000L).coerceAtLeast(1_000L)
    }

    private fun discardOverlap(attempt: AttemptId) {
        localLeases.remove(attempt)?.close()
        streamResources.remove(attempt)?.close()
        preparedRawIds.remove(attempt)
        if (preloadAttempt == attempt) {
            preloadJob?.cancel(); preloadJob = null; preloadAttempt = null; preloadConsumed = null
        }
        engine.executeOnPlayerThread(PlaybackCommand.DiscardPreloaded(id(), attempt))
        val result = queue.discardPreload(attempt)
        capture(result.effects)
        result.startDirective?.let(::start)
        // Discard emits no current-player event. Withdraw raw-id protection here as well as the
        // resolution lease, otherwise a failed/cancelled preload could remain pinned indefinitely.
        publish()
    }

    /** Resolve only core-approved overlap boundaries; a zero boundary keeps the existing path. */
    private fun prepareOverlap() {
        if (closed || !ownsActiveQueue() || preloadAttempt != null || activePlan == null || engine.isOverlapping ||
            SystemClock.elapsedRealtime() < preloadBlockedUntilMillis) return
        if (boundaries?.player != null && boundaries.overlapPlayer == null) return
        val snapshot = queue.snapshot()
        val session = snapshot.currentSession ?: return
        if (progressedAttempt != session.currentAttempt.attemptId) return
        val next = snapshot.entries.getOrNull((snapshot.currentIndex ?: -1) + 1)
            ?: snapshot.entries.firstOrNull()
        val boundary = "${session.currentAttempt.attemptId}:${next?.queueEntryId}:${snapshot.crossfadeIntoNext}"
        if (preloadTriedBoundary == boundary) return
        preloadTriedBoundary = boundary
        val directive = queue.preloadNext(session.playbackSessionId).preloadDirective ?: return
        preloadAttempt = directive.attemptId
        val authorization = AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)
        preloadJob = scope.launch {
            var pendingLease: AndroidLocalPlaybackLease? = null
            try {
                pendingLease = withContext(NonCancellable) { localPlans?.acquireLocalPlan(directive.itemId.rawId) }
                coroutineContext.ensureActive()
                val local = pendingLease?.plan
                val plan = if (local != null) AndroidLocalPlaybackPlan(directive.playbackSessionId,
                    directive.attemptId, directive.itemId, local)
                else {
                    if (!authorizesRemoteReply(authorization)) { discardOverlap(directive.attemptId); return@launch }
                    val song = loadSong(directive.itemId.rawId)
                    if (closed || preloadAttempt != directive.attemptId) return@launch
                    if (!authorizesRemoteReply(authorization)) { discardOverlap(directive.attemptId); return@launch }
                    val target = ANDROID_PROFILE.transcodingProfiles.first().container
                    val request = PlaybackResolveRequest(directive.playbackSessionId, directive.attemptId,
                        directive.itemId, song.container ?: target, false, ANDROID_PROFILE,
                        LegacyPlaybackPreference(if (song.container == null) target else null, null))
                        .withStreamingQuality(streamingQuality.currentQuality)
                    val result = boundaries?.resolve?.invoke(request) ?: wire.resolve(request)
                    if (closed || preloadAttempt != directive.attemptId) return@launch
                    if (!authorizesRemoteReply(authorization)) { discardOverlap(directive.attemptId); return@launch }
                    when (result) {
                        is PlaybackResolutionResult.Resolved -> result.plan
                        is PlaybackResolutionResult.Failed -> {
                            notePreloadResolutionFailure(result.error, song.container == null || streamingQuality.currentQuality.maxBitRateKbps != null)
                            discardOverlap(directive.attemptId); return@launch
                        }
                    }
                }
                if (closed || preloadAttempt != directive.attemptId) return@launch
                if (plan is RemotePlaybackWirePlan && !authorizesRemoteReply(authorization)) { discardOverlap(directive.attemptId); return@launch }
                if (plan is RemotePlaybackWirePlan && !wire.transcodeBudget.maySchedule(
                    PlaybackWireRequestPurpose.Preload, plan.isTranscoded(),
                    ActiveTranscodeCounts(currentPlayback = if ((activePlan as? RemotePlaybackWirePlan)?.isTranscoded() == true) 1 else 0))) {
                    discardOverlap(directive.attemptId); return@launch
                }
                pendingLease?.let { localLeases.put(directive.attemptId, it)?.close(); pendingLease = null }
                val outcome = engine.executeOnPlayerThread(PlaybackCommand.PreloadNext(id(), directive.attemptId, plan))
                if (outcome is PlaybackCommandOutcome.CommandRejected) discardOverlap(directive.attemptId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { discardOverlap(directive.attemptId) }
            finally { pendingLease?.close() }
        }
    }

    /** Refresh future requests without replacing the current item, prepared item or their resources. */
    public fun refreshAccount(expected: PlaybackEndpointAccount): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || !MediaBindingIdentity.isValidAccount(expected) || !AndroidMediaBindingIdentity.sameAccount(store, account, expected)) return false
        if (account == expected) {
            sender.invalidateCapabilities()
            autoplayAuthenticationRefused = false
            answerAutoplay()
            return true
        }
        val oldWire = wire
        val oldRequests = requests
        val oldScrobbles = scrobbleRequests
        retiredNetworkClients += oldWire::close
        retiredNetworkClients += oldRequests::close
        retiredNetworkClients += oldScrobbles::close
        autoplayTransport?.let { old -> retiredNetworkClients += old::close }
        autoplayTransport = null
        val retiredAutoplay = autoplayJob
        autoplayJob = null
        autoplayKey?.let(queue::releaseAutoplayRequest); autoplayKey = null
        retiredAutoplay?.cancel()
        autoplayAuthenticationRefused = false
        account = expected
        wire = PlaybackWireClient(expected,
            KtorPlaybackEndpointTransport(expected, null, null, systemHostResolver()),
            transcodeBudget = oldWire.transcodeBudget)
        requests = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(
            expected.normalizedBaseUrl, expected.username, expected.password, expected.allowLocalHttp), "playback.resource")
        scrobbleRequests = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(
            expected.normalizedBaseUrl, expected.username, expected.password, expected.allowLocalHttp), "playback.scrobble")
        sender.invalidateCapabilities()
        if (boundaries == null) artwork = AndroidArtworkRepository(playbackContext, expected)
        answerAutoplay()
        return true
    }

    override fun close() {
        if (closed) return
        checkMain()
        command(PlaybackCommand.Release(id()))
        closed = true
        instances -= this
        localPlans?.setProtectedRawIds(emptySet())
        // The notice names a track of this account's queue, which goes with the controller: a
        // surface still holding this state must not show it for whatever comes next.
        skipNotice = null
        droppedAdditions = null
        queueConfirmation = null
        mutableState.value = mutableState.value.copy(skipNotice = null, droppedAdditions = null, queueConfirmation = null)
        resolution?.cancel(); startJob?.cancel(); retryDelivery.cancel(); artworkJob?.cancel(); metadataFill?.cancel()
        timerHandler.removeCallbacks(timerTick)
        timerHandler.removeCallbacks(autoplayRetry)
        autoplayJob?.cancel(); autoplayJob = null
        deliveries.close(); scope.cancel()
        autoplayTransport?.close()
        retiredNetworkClients.forEach { it() }
        retiredNetworkClients.clear()
        sender.close(); scrobbleRequests.close(); requests.close(); wire.close(); store.close()
    }

    public companion object {
        private val instances = java.util.concurrent.CopyOnWriteArraySet<AndroidPlaybackController>()
        /** Must complete before a download controller can unlink the former binding's files. */
        public fun prepareForMediaBinding(account: PlaybackEndpointAccount) {
            if (!MediaBindingIdentity.isValidAccount(account) || instances.isEmpty()) return
            val prepare = {
                instances.toList().forEach { controller ->
                    if (!controller.refreshAccount(account)) controller.close()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) prepare()
            else runBlocking(Dispatchers.Main.immediate) { prepare() }
        }
        internal fun notifyServerAvailable(serverId: String, available: Boolean) {
            instances.filter { it.account.providerInstanceId == serverId }.forEach { controller ->
                // The library reader reconnects on its own thread, independently of OS reachability.
                // Order its revalidation with delivery just like a network availability report.
                controller.scope.launch {
                    if (controller.closed) return@launch
                    if (available) controller.sender.invalidateCapabilities()
                    controller.serverAvailable = available
                }
            }
        }
    }

    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()); check(!closed) }

    /**
     * Public verbs run on the main thread and are ignored once closed: a screen or a media key can
     * outlive the service that owned this controller, and must not crash the app by pressing it.
     */
    private fun live(): Boolean { check(Looper.myLooper() == Looper.getMainLooper()); return !closed }
    private fun id() = PlaybackCommandId(UUID.randomUUID().toString())
}

private const val RESTART_THRESHOLD_MILLISECONDS = 3_000L
private const val SESSION_ARTWORK_PIXELS = 512
private const val METADATA_FILL_LIMIT = 25

private val ANDROID_PROFILE = PlaybackDeviceProfile("Dulcet", "Android", 1_411_200, 320_000,
    listOf(DirectPlayAudioProfile(AudioContainer.entries, listOf("mp3", "aac", "flac", "opus", "vorbis", "pcm"), maxAudioChannels = 2)),
    listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)))

/** External boundaries only. Tests retain the real controller, engine, reducer and SQLDelight stores. */
internal class AndroidPlaybackControllerBoundaries(
    val store: DulcetDatabaseStore,
    val player: Player?,
    val prepareSource: ((RemotePlaybackWirePlan) -> Unit)?,
    val loadSong: suspend (String) -> AuthenticatedEndpointResponse,
    val resolve: (suspend (PlaybackResolveRequest) -> PlaybackResolutionResult)? = null,
    val enqueueDelivery: ((RecordedPlaybackEvent) -> Unit)?,
    val artwork: AndroidArtworkRepository? = null,
    val localPlans: AndroidLocalPlaybackSource? = null,
    val prepareLocalSource: ((AndroidLocalPlaybackPlan) -> Unit)? = null,
    val overlapPlayer: (() -> Player)? = null,
    val autoplayCandidates: (suspend (String, Set<String>, Int) -> List<AndroidTrack>)? = { _, _, _ -> emptyList() },
)
