package com.legitimateapps.dulcet.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The crossfade setting (§12.8): off, or an overlap of one to twelve whole seconds. */
public object PlaybackCrossfade {
    public const val MAXIMUM_SECONDS: Int = 12

    internal val MAXIMUM: Duration = MAXIMUM_SECONDS.seconds

    /** A track must be at least this many crossfade lengths long to overlap its neighbour. */
    internal const val MINIMUM_TRACK_MULTIPLE: Int = 3

    /** The stored setting, clamped to what the player can do. Unknown or absent is off. */
    public fun decodeSeconds(stored: Int?): Int = (stored ?: 0).coerceIn(0, MAXIMUM_SECONDS)
}

/**
 * What a sleep timer waits for (§12.13). The timed lengths count down on the monotonic clock; the
 * other two wait for the music and are carried out by the queue as a [PlaybackStopPoint].
 */
public enum class SleepTimerChoice(
    /** The countdown, or null when the timer waits for a track or the queue to end. */
    public val minutes: Int?,
    /** The stable name a choice is stored or logged under. */
    public val wireName: String,
) {
    Minutes15(15, "15m"),
    Minutes30(30, "30m"),
    Minutes45(45, "45m"),
    Minutes60(60, "60m"),
    EndOfTrack(null, "endOfTrack"),
    EndOfQueue(null, "endOfQueue"),
    ;

    public companion object {
        public fun fromWireName(name: String?): SleepTimerChoice? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * The countdown of a timed sleep timer (§12.13). Pure: every reading is taken at a monotonic time
 * the owner supplies (milliseconds of a clock that keeps counting while the device sleeps), so the
 * deadline survives backgrounding and a wall-clock change cannot move it. A monotonic value is
 * never persisted, so a timer does not outlive its process.
 *
 * Over the last [fadeMilliseconds] the playback gain falls linearly from 1 to 0; at the deadline
 * the owner pauses and restores the gain.
 */
public class SleepTimerCountdown(
    public val fadeMilliseconds: Long = DEFAULT_FADE.inWholeMilliseconds,
) {
    private var deadline: Long? = null

    init {
        require(fadeMilliseconds >= 0)
    }

    public val isRunning: Boolean get() = deadline != null

    /** Starts, or restarts, a countdown of [lengthMilliseconds] from [nowMilliseconds]. */
    public fun start(lengthMilliseconds: Long, nowMilliseconds: Long) {
        require(lengthMilliseconds > 0)
        deadline = nowMilliseconds + lengthMilliseconds
    }

    public fun cancel() {
        deadline = null
    }

    /** The time left, never negative; null when no countdown runs. */
    public fun remainingMilliseconds(nowMilliseconds: Long): Long? =
        deadline?.let { (it - nowMilliseconds).coerceAtLeast(0) }

    /** The gain to play at: 1 until the fade begins, then down to 0 at the deadline. */
    public fun gain(nowMilliseconds: Long): Double {
        val remaining = remainingMilliseconds(nowMilliseconds) ?: return 1.0
        if (fadeMilliseconds == 0L || remaining >= fadeMilliseconds) return if (remaining == 0L) 0.0 else 1.0
        return remaining.toDouble() / fadeMilliseconds.toDouble()
    }

    /**
     * True once, at or after the deadline: the countdown ends there, and the owner pauses. A
     * countdown read late -- the process was suspended across its deadline -- expires on the first
     * reading after it.
     */
    public fun expire(nowMilliseconds: Long): Boolean {
        val end = deadline ?: return false
        if (nowMilliseconds < end) return false
        deadline = null
        return true
    }

    public companion object {
        public val DEFAULT_FADE: Duration = 10.seconds

        /** The countdown for a timed [choice], in milliseconds; null for one that waits for the music. */
        public fun lengthMilliseconds(choice: SleepTimerChoice): Long? =
            choice.minutes?.minutes?.inWholeMilliseconds
    }
}

/**
 * Finds AutoPlay tracks on an OpenSubsonic server (§14.8): tracks like a seed, read from the
 * person's own server and never from anywhere else. Each source is tried in turn until [count]
 * new tracks are found:
 * 1. `getSimilarSongs2` for the seed's artist, then `getSimilarSongs` for the seed itself;
 * 2. `getTopSongs` for the seed's artist;
 * 3. `getRandomSongs` in the seed's genre, then from the whole library.
 * A source that fails or is not offered is skipped; refused credentials end the search, since
 * every later request would be refused too.
 */
internal class AutoplayCandidateSource(
    private val providerInstanceId: String,
    private val transport: LibraryEndpointTransport,
) {
    suspend fun candidates(seedRawId: String, excludedRawIds: Set<String>, count: Int): List<LibraryTrack> {
        require(count > 0)
        val seen = excludedRawIds.toMutableSet().apply { add(seedRawId) }
        val found = mutableListOf<LibraryTrack>()
        val seed = attempt { transport.checkedRequest("getSong", mapOf("id" to seedRawId)) }
            ?.let { body -> parseLibraryEnvelope(body)?.payload?.get("song") as? JsonObject }
        val artistId = seed?.optionalSourceId("artistId")
        val artistName = seed?.sourceString("artist")
        val genre = seed?.sourceString("genre")
        val sources = buildList {
            if (artistId != null) add(AutoplaySource("getSimilarSongs2", "similarSongs2", mapOf("id" to artistId, "count" to "$count")))
            add(AutoplaySource("getSimilarSongs", "similarSongs", mapOf("id" to seedRawId, "count" to "$count")))
            if (artistName != null) add(AutoplaySource("getTopSongs", "topSongs", mapOf("artist" to artistName, "count" to "$count")))
            if (genre != null) add(AutoplaySource("getRandomSongs", "randomSongs", mapOf("genre" to genre, "size" to "$count")))
            add(AutoplaySource("getRandomSongs", "randomSongs", mapOf("size" to "$count")))
        }
        for (source in sources) {
            if (found.size >= count) break
            val body = attempt { transport.checkedRequest(source.endpoint, source.parameters) } ?: continue
            val songs = (parseLibraryEnvelope(body)?.payload?.get(source.container) as? JsonObject)
                ?.get("song") as? JsonArray ?: continue
            for (element in songs) {
                if (found.size >= count) break
                val track = (element as? JsonObject)?.let { runCatching { it.libraryTrack(providerInstanceId) }.getOrNull() }
                    ?: continue
                if (seen.add(track.id.rawId)) found += track
            }
        }
        return found
    }

    /** One request; null when it failed for any reason but refused credentials or cancellation. */
    private suspend fun attempt(request: suspend () -> String): String? = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LibraryRequestFailure) {
        if (failure.error is DomainError.Auth) throw failure
        null
    } catch (failure: AuthenticatedEndpointFailure) {
        if (failure.error is DomainError.Auth) throw failure
        null
    } catch (_: Exception) {
        null
    }

    private data class AutoplaySource(
        val endpoint: String,
        val container: String,
        val parameters: Map<String, String>,
    )
}

private fun JsonObject.sourceString(name: String): String? =
    (get(name) as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.optionalSourceId(name: String): String? = sourceString(name)
