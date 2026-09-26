package com.legitimateapps.dulcet.core

import android.content.Context
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
) {
    val hasSession: Boolean get() = playbackSessionId != null
}

/**
 * One automatic skip past a track that could not play. [sequence] tells two skips of the same
 * track apart; [title] is null when the skipped entry's title is not known, and the surface then
 * says "a track". [postedAtElapsedMillis] is on the monotonic clock and is never persisted.
 */
public data class AndroidSkipNotice(val sequence: Long, val title: String?, val postedAtElapsedMillis: Long)

/** Display metadata for one selectable song. Identity is the opaque pair; the rest is presentation. */
public data class AndroidTrack(
    val providerInstanceId: String,
    val rawId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMilliseconds: Long? = null,
    val artworkKey: String? = null,
) {
    init {
        require(providerInstanceId.isNotBlank())
        require(rawId.isNotBlank())
    }
}

/** One Up Next row. [queueEntryId] addresses it; the same song may appear more than once. */
public data class AndroidQueueEntry(val queueEntryId: String, val track: AndroidTrack)

public enum class AndroidQueueSource { Album, Artist, Library, Search }

public enum class AndroidRepeatMode { Off, All, One }

/** Service-owned composition root. Core policy, queue identities and outbox are reused unchanged. */
public class AndroidPlaybackController internal constructor(
    context: Context,
    private val account: PlaybackEndpointAccount,
    private val boundaries: AndroidPlaybackControllerBoundaries?,
) : AutoCloseable {
    public constructor(context: Context, account: PlaybackEndpointAccount) : this(context, account, null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = boundaries?.store ?: DulcetDriverFactory(context.applicationContext).openDulcetDatabase()
    private val resumes = PersistentResumePositionStore(store.database)
    private val queue = PlaybackQueueController(PersistentQueueStore(store.database), resumes,
        PlaybackIdentitySource { "$it:${UUID.randomUUID()}" })
    private val wire = PlaybackWireClient(account)
    private val requests = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(
        account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp), "playback.resource")
    private val sender = ScrobbleEndpointSender(account)
    private val wall = OutboxWallClock(System::currentTimeMillis)
    private val outbox = PersistentScrobbleOutbox(store.database, wall)
    private val worker = ScrobbleOutboxDeliveryWorker(ServerId(account.providerInstanceId), outbox, sender,
        wall, OutboxMonotonicClock { SystemClock.elapsedRealtime().milliseconds }, ScrobbleOutboxDiagnosticSink {})
    private val deliveries = Channel<RecordedPlaybackEvent>(Channel.UNLIMITED)
    private var retryDelivery: Job? = null
    private var resolution: Job? = null
    private var startJob: Job? = null
    private var activePlan: RemotePlaybackWirePlan? = null
    private var wantsPlay = false
    private var pendingResume: Long? = null
    private var requestGeneration = 0L
    private var closed = false
    // Presentation metadata by opaque identity. It never decides what plays: the core queue does.
    private val metadata = mutableMapOf<ProviderItemId, AndroidTrack>()
    private val metadataLoads = mutableSetOf<ProviderItemId>()
    private var metadataFill: Job? = null
    private var artworkJob: Job? = null
    private var artworkBytes: Pair<String, ByteArray>? = null
    private val artwork: AndroidArtworkRepository? =
        if (boundaries == null) AndroidArtworkRepository(context, account) else boundaries.artwork
    private var failure: DomainError? = null
    private var skipNotice: AndroidSkipNotice? = null
    private var skipNoticeSequence = 0L
    private var consumed = AtomicLong()
    private val mutableState = MutableStateFlow(AndroidPlaybackState())
    public val state: StateFlow<AndroidPlaybackState> = mutableState
    private val exo: Player = boundaries?.player ?: ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
        repeatMode = Player.REPEAT_MODE_OFF
    }
    private val engine = AndroidMedia3Engine(exo, onSystemPlayWhenReady = { wanted ->
        // A headphone unplug or a lost focus pauses the player; the requested state must follow,
        // or Pause would still be offered and the next song would start through the speaker.
        if (!closed) { wantsPlay = wanted; publish() }
    }, prepareSource = { plan ->
        val attemptConsumed = AtomicLong()
        consumed = attemptConsumed
        val factory = AndroidPlaybackDataSourceFactory(plan, AndroidHttpPlaybackResource(account, plan, requests)) { bytes ->
            attemptConsumed.addAndGet(bytes)
        }
        val item = mediaItem(plan)
        if (boundaries?.prepareSource != null) boundaries.prepareSource.invoke(plan)
        else (exo as ExoPlayer).setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(item))
    })

    /**
     * Every transport verb a system controller can send takes the same path as an in-app command;
     * none reaches the player directly. Queue edits and media-item replacement are not offered.
     */
    public val sessionPlayer: Player = object : ForwardingPlayer(exo) {
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
        engine.setEventListener { event ->
            if (closed) return@setEventListener
            if (event is PlaybackEngineEvent.FailedBeforeStart) failure = event.error
            if (event is PlaybackEngineEvent.FailedAfterPartial) failure = event.error
            val transition = queue.recordPlaybackEvent(event)
            capture(transition.effects)
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
            if (event is PlaybackEngineEvent.Ready) {
                pendingResume?.let { position ->
                    pendingResume = null
                    if (event.seekability == PlaybackSeekability.Seekable) seek(position)
                }
            }
            transition.startDirective?.let { start(it) }
        }
        scope.launch {
            for (event in deliveries) when (event) {
                is RecordedPlaybackEvent.NowPlaying -> sender.send(ScrobbleEndpointRequest(event))
                is RecordedPlaybackEvent.SubmittedPlay -> drain()
            }
        }
        scope.launch { drain() }
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
            if (track.providerInstanceId != account.providerInstanceId || track.title.isBlank()) continue
            val known = metadata[ProviderItemId(track.providerInstanceId, track.rawId)]
            if (known == null || known.title.isBlank()) { remember(track); added = true }
        }
        if (added) publish()
    }

    /** Resolves an opaque selected song id using this service's saved account, never intent credentials. */
    public fun playSong(providerInstanceId: String, rawId: String, displayTitle: String) {
        if (!live()) return
        if (providerInstanceId != account.providerInstanceId || rawId.isBlank()) {
            failure = DomainError.Auth.Forbidden; publish(); return
        }
        playQueue(listOf(AndroidTrack(providerInstanceId, rawId, displayTitle)), 0, AndroidQueueSource.Search, "Search")
    }

    /**
     * Replaces the queue with [tracks] and starts [startIndex]. Every track must belong to this
     * service's account; a foreign identity is refused rather than played with the wrong credentials.
     * An album or artist queue names its source by opaque [sourceRawId]; library and search do not.
     */
    public fun playQueue(
        tracks: List<AndroidTrack>,
        startIndex: Int,
        source: AndroidQueueSource,
        sourceName: String,
        sourceRawId: String? = null,
        shuffle: Boolean = false,
    ) {
        if (!live()) return
        val named = source == AndroidQueueSource.Album || source == AndroidQueueSource.Artist
        require(named == !sourceRawId.isNullOrBlank()) { "Album and artist queues, and only they, name a source" }
        if (tracks.isEmpty() || startIndex !in tracks.indices ||
            tracks.any { it.providerInstanceId != account.providerInstanceId }) {
            failure = DomainError.Auth.Forbidden; publish(); return
        }
        requestGeneration++
        wantsPlay = true
        val generation = requestGeneration
        resolution?.cancel()
        startJob?.cancel()
        tracks.forEach { remember(it) }
        val chosen = tracks[startIndex]
        resolution = scope.launch {
            try {
                val song = loadSong(chosen.rawId)
                if (generation != requestGeneration) return@launch
                command(PlaybackCommand.Stop(id()))
                failure = null; consumed = AtomicLong()
                val kind = when (source) {
                    AndroidQueueSource.Album -> QueueSourceKind.Album
                    AndroidQueueSource.Artist -> QueueSourceKind.Artist
                    AndroidQueueSource.Library -> QueueSourceKind.Library
                    AndroidQueueSource.Search -> QueueSourceKind.Search
                }
                val transition = queue.replaceAndStart(PlaybackQueueRequest(
                    tracks.map { track ->
                        val id = ProviderItemId(track.providerInstanceId, track.rawId)
                        PlaybackQueueItem(id, if (track.rawId == chosen.rawId) song.duration
                            else track.durationMilliseconds?.milliseconds)
                    },
                    QueueSourceContext(kind, sourceRawId?.let { ProviderItemId(account.providerInstanceId, it) },
                        sourceName.ifBlank { source.name }),
                    if (shuffle) null else startIndex, shuffle))
                capture(transition.effects)
                val directive = transition.startDirective!!
                start(directive, song.takeIf { directive.itemId.rawId == chosen.rawId })
            } catch (_: CancellationException) { throw CancellationException() }
            catch (error: AndroidPlaybackIOException) { failure = error.error; publish() }
            catch (_: Exception) { failure = DomainError.Transport.Unreachable; publish() }
        }
    }

    public fun play() {
        if (!live()) return
        if (activePlan == null) {
            if (resolution?.isActive == true || startJob?.isActive == true) {
                wantsPlay = true; recordPlayRequested(); return
            }
            if (refuseForeignQueue()) return
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
            transition(if (failed) queue.retryCurrent() else queue.restartCurrent(ServerId(account.providerInstanceId)))
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
        command(PlaybackCommand.Stop(id()))
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
            // it does wherever Previous moves (spec §12.12 rule 3); `restartCurrent` begins none.
            recordPlayRequested()
            transition(queue.restartCurrent(ServerId(account.providerInstanceId)))
        }
    }

    /** Plays the Up Next entry the user picked. Addressed by entry identity, never by row index. */
    public fun jumpTo(queueEntryId: String) {
        if (!live() || refuseForeignQueue()) return
        wantsPlay = true
        transition(queue.jumpTo(QueueEntryId(queueEntryId)))
    }

    public fun setShuffle(enabled: Boolean) {
        if (!live() || refuseForeignQueue()) return
        capture(queue.setShuffle(enabled).effects); publish()
    }

    public fun cycleRepeatMode() {
        if (!live() || refuseForeignQueue()) return
        capture(queue.cycleRepeatMode().effects); publish()
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
        command(PlaybackCommand.Stop(id()))
        capture(transition.effects)
        transition.startDirective?.let { start(it) }
        publish()
        fillQueueMetadata()
    }

    /** [container] is null for a format this device cannot play directly; it is then transcoded. */
    private data class Song(val container: AudioContainer?, val duration: kotlin.time.Duration?, val track: AndroidTrack)
    private suspend fun loadSong(rawId: String): Song {
        val response = boundaries?.loadSong?.invoke(rawId) ?: requests.request("getSong", mapOf("id" to rawId))
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
            field("title")?.takeIf { it.isNotBlank() } ?: known?.title.orEmpty(),
            field("artist")?.takeIf { it.isNotBlank() } ?: known?.artist,
            field("album")?.takeIf { it.isNotBlank() } ?: known?.album,
            duration?.inWholeMilliseconds ?: known?.durationMilliseconds,
            field("coverArt")?.takeIf { it.isNotBlank() } ?: known?.artworkKey)
        remember(track)
        return Song(container, duration, track)
    }

    private fun remember(track: AndroidTrack) {
        val id = ProviderItemId(track.providerInstanceId, track.rawId)
        val known = metadata[id]
        metadata[id] = if (known == null) track else AndroidTrack(track.providerInstanceId, track.rawId,
            track.title.ifBlank { known.title }, track.artist ?: known.artist, track.album ?: known.album,
            track.durationMilliseconds ?: known.durationMilliseconds, track.artworkKey ?: known.artworkKey)
    }

    private fun mediaItem(plan: RemotePlaybackWirePlan): MediaItem {
        val track = metadata[plan.itemId]
        val art = artworkBytes?.takeIf { it.first == track?.artworkKey }?.second
        val details = MediaMetadata.Builder()
            .setTitle(track?.title?.ifBlank { null })
            .setArtist(track?.artist)
            .setAlbumTitle(track?.album)
            .setIsPlayable(true).setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        track?.durationMilliseconds?.let { details.setDurationMs(it) }
        art?.let { details.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return MediaItem.Builder().setMediaId(plan.attemptId.value).setUri("dulcet://resource")
            .setMediaMetadata(details.build()).build()
    }

    /**
     * Cover art reaches the media session as validated bytes after preparation begins, so a slow
     * cover never delays sound. Only the attempt that asked for it may be updated.
     */
    private fun loadArtwork(plan: RemotePlaybackWirePlan) {
        artworkJob?.cancel()
        val key = metadata[plan.itemId]?.artworkKey ?: return
        if (artworkBytes?.first == key) return
        val repository = artwork ?: return
        artworkJob = scope.launch {
            val bytes = try { repository.load(key, SESSION_ARTWORK_PIXELS) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null } ?: return@launch
            artworkBytes = key to bytes
            if (closed || activePlan?.attemptId != plan.attemptId) return@launch
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
                try { loadSong(id.rawId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
                publish()
            }
        }
    }

    private fun start(directive: PlaybackQueueStartDirective, knownSong: Song? = null) {
        // The last line of defence: nothing is requested for another account's song.
        if (directive.itemId.providerInstanceId != account.providerInstanceId) {
            failure = DomainError.Auth.Forbidden; publish(); return
        }
        // A new attempt is under way, so the failure line belongs to one that is over: the
        // entry's preparing state replaces it (spec §12.12 rule 5). It is never left on screen
        // for a track that is not playing.
        failure = null
        val session = directive.playbackSessionId
        val generation = requestGeneration
        startJob?.cancel()
        startJob = scope.launch {
            try {
                val song = knownSong ?: loadSong(directive.itemId.rawId)
                // A format with no direct-play container goes through the profile's transcoding
                // target on the legacy path (`stream?format=`). That path reads the source container
                // only when no format is requested, so the target stands in for an unnamed source.
                val transcode = ANDROID_PROFILE.transcodingProfiles.first().container
                val request = PlaybackResolveRequest(session, directive.attemptId, directive.itemId,
                    song.container ?: transcode, false, ANDROID_PROFILE,
                    LegacyPlaybackPreference(if (song.container == null) transcode else null, null))
                val result = boundaries?.resolve?.invoke(request) ?: wire.resolve(request)
                if (generation != requestGeneration || queue.snapshot().currentSession?.playbackSessionId != session || closed) return@launch
                when (result) {
                    is PlaybackResolutionResult.Failed -> failBeforeStart(directive, result.error)
                    is PlaybackResolutionResult.Resolved -> {
                        command(PlaybackCommand.Stop(id()))
                        activePlan = result.plan
                        pendingResume = directive.resumePosition?.inWholeMilliseconds
                        command(PlaybackCommand.Prepare(id(), result.plan.attemptId, result.plan))
                        command(if (wantsPlay) PlaybackCommand.Play(id()) else PlaybackCommand.Pause(id()))
                        loadArtwork(result.plan)
                    }
                }
            } catch (_: CancellationException) { throw CancellationException() }
            catch (error: AndroidPlaybackIOException) { failBeforeStart(directive, error.error, generation) }
            catch (_: Exception) { failBeforeStart(directive, DomainError.Transport.Unreachable, generation) }
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
                else check(deliveries.trySend(effect.event).isSuccess)
            }
            is PlaybackCoreEffect.PersistResumePosition -> resumes.save(effect.itemId, effect.position)
            is PlaybackCoreEffect.ClearResumePosition -> resumes.clear(effect.itemId)
            is PlaybackCoreEffect.AccumulatorDiagnostic -> Unit
        }
    }

    private suspend fun drain() {
        val result = worker.onForeground()
        retryDelivery?.cancel()
        retryDelivery = result.nextRetryAfter?.let { wait -> scope.launch { delay(wait); retryDelivery = null; drain() } }
    }

    private fun command(command: PlaybackCommand) {
        checkMain()
        if (command is PlaybackCommand.Stop) { activePlan = null; pendingResume = null }
        val outcome = engine.executeOnPlayerThread(command)
        if (outcome is PlaybackCommandOutcome.CommandRejected) {
            val reason = outcome.reason
            // A refused transport verb (seek while not seekable, pause with nothing prepared, a
            // lock-screen button pressed between songs) is not a playback failure and must not
            // replace the screen with an error. A failed or unpreparable source is.
            if (reason is PlaybackCommandRejectionReason.Failed) failure = reason.error
            else if (command is PlaybackCommand.Prepare || command is PlaybackCommand.ReplaceCurrent)
                failure = DomainError.Protocol.UnexpectedBinary
        }
        publish()
    }

    private fun publish() {
        val snapshot = queue.snapshot()
        val session = snapshot.currentSession
        val current = session?.let { metadata[it.itemId] }
        val entries = snapshot.entries.map { entry ->
            AndroidQueueEntry(entry.queueEntryId.value, metadata[entry.itemId]
                ?: AndroidTrack(entry.itemId.providerInstanceId, entry.itemId.rawId, ""))
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
            skipNotice = skipNotice)
    }

    override fun close() {
        if (closed) return
        checkMain()
        command(PlaybackCommand.Release(id()))
        closed = true
        // The notice names a track of this account's queue, which goes with the controller: a
        // surface still holding this state must not show it for whatever comes next.
        skipNotice = null
        mutableState.value = mutableState.value.copy(skipNotice = null)
        resolution?.cancel(); startJob?.cancel(); retryDelivery?.cancel(); artworkJob?.cancel(); metadataFill?.cancel()
        deliveries.close(); scope.cancel()
        sender.close(); requests.close(); wire.close(); store.close()
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
)
