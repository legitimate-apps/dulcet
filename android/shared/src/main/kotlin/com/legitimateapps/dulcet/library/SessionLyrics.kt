package com.legitimateapps.dulcet.library

import android.content.res.Resources
import android.os.LocaleList
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLyricsPublication
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidLyricsTrack
import com.legitimateapps.dulcet.core.cursorAtMilliseconds
import com.legitimateapps.dulcet.shared.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The lyrics of the track Now Playing shows (spec §18.4), for one panel, on the main thread.
 *
 * [show] names the current track. Its stored document is published first, with no request, then the
 * live read; an answer about a track that is no longer current is dropped, so a quick skip never
 * shows the previous song's words. With nothing stored, that first answer is not published: it says
 * only that this device has not seen the words, which the live read is about to settle, and shown
 * it would tell a connected person to connect. The panel stays loading until the read answers —
 * offline that answer is the same statement, a moment later (Apple's panel shows loading too). Every decision — the endpoint, the breaker, the layer — is the
 * core's; this only keeps the latest publication for the current track.
 */
public class SessionLyrics internal constructor(
    private val session: LibrarySession,
    private val preferredLanguages: List<String>,
) {
    private val mutable = MutableStateFlow<AndroidLyricsPublication?>(null)

    /** The current track's latest publication; null before the first one, and while no track is current. */
    public val state: StateFlow<AndroidLyricsPublication?> = mutable.asStateFlow()

    private var current: AndroidLyricsTrack? = null
    private var generation = 0
    private val order = LatestAnswer()

    /** The track now current, or null. The same track again reads nothing. */
    public fun show(track: AndroidLyricsTrack?) {
        if (track == current) return
        current = track
        generation += 1
        order.reset()
        mutable.value = null
        if (track == null || session.isClosed) return
        val asked = generation
        val lyrics = session.lyricsFacade(preferredLanguages)
        val stored = order.next()
        lyrics.cached(track) { if (it.freshness !is AndroidLibraryFreshness.Unavailable) deliver(asked, stored, it) }
        val live = order.next()
        lyrics.read(track) { deliver(asked, live, it) }
    }

    /** "Try again": admitted past the breaker as its trial (§10.4). */
    public fun retry() {
        val track = current ?: return
        if (session.isClosed) return
        val asked = generation
        val ticket = order.next()
        session.lyricsFacade(preferredLanguages).retry(track) { deliver(asked, ticket, it) }
    }

    /**
     * Reads again when what is shown was not read live — back online after a panel opened offline.
     * Live words are not read twice.
     */
    public fun refreshIfNotLive() {
        val track = current ?: return
        val shown = mutable.value ?: return
        if (shown.freshness == AndroidLibraryFreshness.Live || session.isClosed) return
        val asked = generation
        val ticket = order.next()
        session.lyricsFacade(preferredLanguages).read(track) { deliver(asked, ticket, it) }
    }

    /**
     * An answer about another track is dropped, and so is one to a request older than the answer
     * shown (§18.4, the rule for the platform bridges): a read begun before a reconnect answers after
     * it, and may be a failure arriving after a newer read already showed live lyrics.
     */
    private fun deliver(asked: Int, ticket: Int, publication: AndroidLyricsPublication) {
        if (asked != generation || publication.trackRawId != current?.rawId) return
        if (!order.accept(ticket)) return
        mutable.value = publication
        session.recordLyrics(publication)
    }
}

/**
 * Orders answers by when their requests were made: [next] numbers a request, and [accept] admits an
 * answer only when no answer to a later request has been shown. Main thread only.
 */
public class LatestAnswer {
    private var issued = 0
    private var shown = 0

    public fun next(): Int = ++issued

    public fun accept(ticket: Int): Boolean {
        if (ticket < shown) return false
        shown = ticket
        return true
    }

    /** A new subject: every earlier request is forgotten, and none of its answers is shown. */
    public fun reset() {
        shown = issued + 1
    }
}

/** The lines lit at a playback position: [first]..[last], or none; [interlude] between sung lines. */
public data class LyricsHighlight(val first: Int, val last: Int, val interlude: Boolean) {
    public fun lights(index: Int): Boolean = first >= 0 && index in first..last
}

/**
 * The core's cursor ([cursorAtMilliseconds]) at [positionMilliseconds], or null for lyrics that are
 * not synced, which light nothing and scroll freely. Indices are into [AndroidLyricsPublication.lines].
 */
public fun AndroidLyricsPublication.highlightAt(positionMilliseconds: Long): LyricsHighlight? {
    val layer = layer ?: return null
    if (!synced || state != AndroidLyricsState.Lyrics) return null
    val cursor = layer.cursorAtMilliseconds(positionMilliseconds)
    return LyricsHighlight(cursor.index, cursor.lastIndex, cursor.isInterlude)
}

/**
 * Media time between the controller's samples (about every 500 ms): the last sample, advanced by the
 * monotonic time since it arrived while playback is progressing, and never past [durationMilliseconds].
 * A line lit half a second late reads as out of sync; a guess never outruns the next real sample by
 * more than one interval.
 */
public fun interpolatedPosition(
    sampledPositionMilliseconds: Long,
    sampledAtElapsedMillis: Long,
    nowElapsedMillis: Long,
    progressing: Boolean,
    durationMilliseconds: Long?,
): Long {
    if (!progressing) return sampledPositionMilliseconds
    val advanced = sampledPositionMilliseconds + (nowElapsedMillis - sampledAtElapsedMillis).coerceIn(0, MAX_INTERPOLATION_MS)
    return durationMilliseconds?.takeIf { it > 0 }?.let { advanced.coerceAtMost(it) } ?: advanced
}

/** No more than two sample intervals are ever guessed. */
private const val MAX_INTERPOLATION_MS = 1_000L

/** The phase in which media time advances (§12.3). */
public const val PHASE_PROGRESSING: String = "Progressing"

/**
 * The statement a lyrics panel shows instead of lines, or null when it shows lines (§18.4): never a
 * spinner for a track with no lyrics. Null while nothing has been published yet (the panel shows its
 * loading state then).
 */
public fun Resources.lyricsStatement(publication: AndroidLyricsPublication?): String? = when {
    publication == null -> null
    publication.state == AndroidLyricsState.Lyrics -> null
    publication.state == AndroidLyricsState.None -> getString(R.string.lyrics_none)
    publication.breakerOpen -> getString(R.string.lyrics_unavailable_paused)
    else -> when (val freshness = publication.freshness) {
        is AndroidLibraryFreshness.Unavailable -> unavailableLine(freshness.reason, LibrarySubject.List).let {
            if (freshness.reason == com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason.NotCachedOffline) {
                getString(R.string.lyrics_unavailable_offline)
            } else {
                it
            }
        }
        else -> getString(R.string.lyrics_unavailable)
    }
}

/** The person's languages, most preferred first, as BCP 47 tags, for the core's layer choice. */
public fun deviceLanguages(): List<String> {
    val locales = LocaleList.getDefault()
    return (0 until locales.size()).map { locales[it].toLanguageTag() }
}
