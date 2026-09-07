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
)

/** Service-owned composition root. Core policy, queue identities and outbox are reused unchanged. */
public class AndroidPlaybackController(context: Context, private val account: PlaybackEndpointAccount) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = DulcetDriverFactory(context.applicationContext).openDulcetDatabase()
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
    private var requestGeneration = 0L
    private var closed = false
    private var title = ""
    private var failure: DomainError? = null
    private var consumed = 0L
    private val mutableState = MutableStateFlow(AndroidPlaybackState())
    public val state: StateFlow<AndroidPlaybackState> = mutableState
    private val exo = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
        repeatMode = Player.REPEAT_MODE_OFF
    }
    private val engine = AndroidMedia3Engine(exo, prepareSource = { plan ->
        val factory = AndroidPlaybackDataSourceFactory(plan, AndroidHttpPlaybackResource(account, plan, requests)) { bytes ->
            scope.launch {
                if (queue.snapshot().currentSession?.currentAttempt?.attemptId == plan.attemptId) {
                    consumed += bytes; publish()
                }
            }
        }
        val item = MediaItem.Builder().setMediaId(plan.attemptId.value).setUri("dulcet://resource")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        exo.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(item))
    })

    /** Every system command takes the same path as an in-app command. Queue edits are private. */
    public val sessionPlayer: Player = object : ForwardingPlayer(exo) {
        override fun play() { this@AndroidPlaybackController.play() }
        override fun pause() { this@AndroidPlaybackController.pause() }
        override fun setPlayWhenReady(value: Boolean) { if (value) play() else pause() }
        override fun stop() { this@AndroidPlaybackController.stop() }
        override fun seekTo(positionMs: Long) { this@AndroidPlaybackController.seek(positionMs) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex == 0) this@AndroidPlaybackController.seek(positionMs)
        }
        override fun seekToNextMediaItem() { next() }
        override fun seekToNext() { next() }
        override fun seekToPreviousMediaItem() { previous() }
        override fun seekToPrevious() { previous() }
        override fun setVolume(volume: Float) { command(PlaybackCommand.SetVolume(id(), volume.toDouble())) }
        override fun setPlaybackSpeed(speed: Float) { command(PlaybackCommand.SetRate(id(), speed.toDouble())) }
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM)
            .remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE).build()
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        engine.setEventListener { event ->
            if (closed) return@setEventListener
            if (event is PlaybackEngineEvent.FailedBeforeStart) failure = event.error
            if (event is PlaybackEngineEvent.FailedAfterPartial) failure = event.error
            val transition = queue.recordPlaybackEvent(event)
            capture(transition.effects)
            publish()
            transition.startDirective?.let { start(it) }
        }
        scope.launch {
            for (event in deliveries) when (event) {
                is RecordedPlaybackEvent.NowPlaying -> sender.send(ScrobbleEndpointRequest(event))
                is RecordedPlaybackEvent.SubmittedPlay -> drain()
            }
        }
        scope.launch { drain() }
    }

    /** Resolves an opaque selected song id using this service's saved account, never intent credentials. */
    public fun playSong(providerInstanceId: String, rawId: String, displayTitle: String) {
        checkMain()
        if (providerInstanceId != account.providerInstanceId || rawId.isBlank()) {
            failure = DomainError.Auth.Forbidden; publish(); return
        }
        requestGeneration++
        val generation = requestGeneration
        resolution?.cancel()
        resolution = scope.launch {
            try {
                val song = loadSong(rawId)
                if (generation != requestGeneration) return@launch
                command(PlaybackCommand.Stop(id()))
                title = displayTitle
                failure = null; consumed = 0
                val transition = queue.replaceAndStart(PlaybackQueueRequest(
                    listOf(PlaybackQueueItem(ProviderItemId(providerInstanceId, rawId), song.duration)),
                    QueueSourceContext(QueueSourceKind.Search, null, "Search"), 0, false))
                capture(transition.effects)
                start(transition.startDirective!!, song)
            } catch (_: CancellationException) { throw CancellationException() }
            catch (error: AndroidPlaybackIOException) { failure = error.error; publish() }
            catch (_: Exception) { failure = DomainError.Transport.Unreachable; publish() }
        }
    }

    public fun play() { command(PlaybackCommand.Play(id())) }
    public fun pause() { command(PlaybackCommand.Pause(id())) }
    public fun seek(positionMilliseconds: Long) { command(PlaybackCommand.Seek(id(), positionMilliseconds.milliseconds)) }
    public fun stop() {
        checkMain(); requestGeneration++; resolution?.cancel()
        command(PlaybackCommand.Stop(id()))
    }
    public fun next() { checkMain(); transition(queue.next()) }
    public fun previous() { checkMain(); transition(queue.previous()) }

    private fun transition(transition: PlaybackQueueTransition) {
        command(PlaybackCommand.Stop(id()))
        capture(transition.effects)
        transition.startDirective?.let { start(it) }
        publish()
    }

    private data class Song(val container: AudioContainer, val duration: kotlin.time.Duration?)
    private suspend fun loadSong(rawId: String): Song {
        val response = requests.request("getSong", mapOf("id" to rawId))
        val envelope = parseLibraryEnvelope(response.body.decodeToString())
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
            else -> throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
        }
        return Song(container, (song["duration"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.seconds)
    }

    private fun start(directive: PlaybackQueueStartDirective, knownSong: Song? = null) {
        val session = directive.playbackSessionId
        scope.launch {
            try {
                val song = knownSong ?: loadSong(directive.itemId.rawId)
                val result = wire.resolve(PlaybackResolveRequest(session, directive.attemptId, directive.itemId,
                    song.container, false, ANDROID_PROFILE, LegacyPlaybackPreference(null, null)))
                if (queue.snapshot().currentSession?.playbackSessionId != session || closed) return@launch
                when (result) {
                    is PlaybackResolutionResult.Failed -> { failure = result.error; publish() }
                    is PlaybackResolutionResult.Resolved -> {
                        command(PlaybackCommand.Prepare(id(), result.plan.attemptId, result.plan))
                        if (directive.shouldAutoPlay) command(PlaybackCommand.Play(id()))
                    }
                }
            } catch (_: CancellationException) { throw CancellationException() }
            catch (error: AndroidPlaybackIOException) { failure = error.error; publish() }
            catch (_: Exception) { failure = DomainError.Transport.Unreachable; publish() }
        }
    }

    private fun capture(effects: List<PlaybackCoreEffect>) {
        for (effect in effects) when (effect) {
            is PlaybackCoreEffect.RecordPlaybackEvent -> {
                if (effect.event is RecordedPlaybackEvent.SubmittedPlay) outbox.persistSynchronously(effect.event)
                check(deliveries.trySend(effect.event).isSuccess)
            }
            is PlaybackCoreEffect.PersistResumePosition -> resumes.save(effect.itemId, effect.position)
            is PlaybackCoreEffect.ClearResumePosition -> resumes.clear(effect.itemId)
            is PlaybackCoreEffect.AccumulatorDiagnostic -> Unit
        }
    }

    private suspend fun drain() {
        val result = worker.onForeground()
        retryDelivery?.cancel()
        retryDelivery = result.nextRetryAfter?.let { delay -> scope.launch { delay(delay); drain() } }
    }

    private fun command(command: PlaybackCommand) {
        checkMain()
        val outcome = engine.executeOnPlayerThread(command)
        if (outcome is PlaybackCommandOutcome.CommandRejected) {
            failure = (outcome.reason as? PlaybackCommandRejectionReason.Failed)?.error
                ?: DomainError.Protocol.UnexpectedBinary
        }
        publish()
    }

    private fun publish() {
        val session = queue.snapshot().currentSession
        mutableState.value = AndroidPlaybackState(title,
            session?.currentAttempt?.phase?.name ?: "Stopped",
            exo.currentPosition.coerceAtLeast(0),
            exo.duration.takeIf { it != C.TIME_UNSET && it >= 0 },
            session?.queueEntryId?.value, session?.playbackSessionId?.value,
            session?.currentAttempt?.attemptId?.value, failure, consumed)
    }

    override fun close() {
        if (closed) return
        checkMain()
        command(PlaybackCommand.Release(id()))
        closed = true
        resolution?.cancel(); retryDelivery?.cancel(); deliveries.close(); scope.cancel()
        sender.close(); requests.close(); wire.close(); store.close()
    }

    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()); check(!closed) }
    private fun id() = PlaybackCommandId(UUID.randomUUID().toString())
}

private val ANDROID_PROFILE = PlaybackDeviceProfile("Dulcet", "Android", 1_411_200, 320_000,
    listOf(DirectPlayAudioProfile(AudioContainer.entries, listOf("mp3", "aac", "flac", "opus", "vorbis", "pcm"), maxAudioChannels = 2)),
    listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)))
