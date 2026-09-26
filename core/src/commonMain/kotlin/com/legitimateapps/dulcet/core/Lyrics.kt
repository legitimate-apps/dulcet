@file:OptIn(ExperimentalObjCRefinement::class)

package com.legitimateapps.dulcet.core

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lyrics as the core models them (spec §18.4). This file is PURE: the model, the selection policy
 * and the playback cursor, with no I/O, so every shell renders from the same decisions.
 *
 * Positions follow seam invariant 6 (§9.5): in Kotlin every time is a [Duration]. A `Duration`
 * does not survive the Objective-C boundary as a usable number — it arrives as its raw internal
 * encoding, which reads as an integer and is not one, or as an opaque box when nullable — so every
 * `Duration` member here is hidden from Objective-C and has a milliseconds twin, and every
 * constructor Swift can see takes milliseconds. An absent time is `null`, never a sentinel number:
 * -1 ms is a real time (a line at 0 with an offset of +1 is due at -1 ms).
 *
 * Nothing public here throws, for any value a caller or the store can hold: a Swift caller cannot
 * catch a Kotlin exception (CLAUDE.md trap 17). Times beyond [LYRICS_TIME_BOUND] are not trusted
 * (§18.4) — the parser keeps such a layer's text and drops its timing, and the cursor treats a
 * stored one as untimed — so no arithmetic here ever meets an infinite or overflowing duration.
 */

/** Where a lyrics document came from; both are `/rest` endpoints (CORPUS §4.1). */
public enum class LyricsSource {
    /** `getLyricsBySongId`, the `songLyrics` extension: structured, possibly synced, per language. */
    SongLyricsExtension,

    /** Classic `getLyrics`: artist/title-keyed, one unsynced text, no language. */
    LegacyGetLyrics,
}

/** One line. [start] is null exactly when the layer is unsynced. */
@ConsistentCopyVisibility
public data class LyricsLine internal constructor(
    val text: String,
    @property:HiddenFromObjC val start: Duration?,
) {
    /** The constructor Swift and Android see; [startMilliseconds] is null for an unsynced line. */
    public constructor(text: String, startMilliseconds: Long?) : this(text, startMilliseconds?.milliseconds)

    /** [start] in whole milliseconds, or null when the line has no timestamp. */
    val startMilliseconds: Long? get() = start?.inWholeMilliseconds

    /** A line with no visible text: an instrumental gap in a synced layer (§18.4). */
    val isBlank: Boolean get() = text.isBlank()
}

/**
 * One `structuredLyrics` entry: one language, synced or not.
 *
 * [language] is the server's code as sent, or null when the server said it does not know
 * (`und`, `xxx` or an empty value — the OpenSubsonic docs make `xxx` equivalent to `und`).
 * [offset] follows the OpenSubsonic definition: **positive means the lyrics appear sooner**, so a
 * line is due at `start - offset`; it is zero in an unsynced layer. [kind] is the `songLyrics` v2
 * layer classification; null means the server sent none, which is a `main` layer by the v1
 * definition.
 */
@ConsistentCopyVisibility
public data class LyricsLayer internal constructor(
    val language: String?,
    val synced: Boolean,
    @property:HiddenFromObjC val offset: Duration,
    val lines: List<LyricsLine>,
    val kind: String? = null,
    val displayArtist: String? = null,
    val displayTitle: String? = null,
) {
    /**
     * The constructor Swift and Android see. [lines] precedes [offsetMilliseconds] here because the
     * internal constructor's `Duration` compiles to a JVM `long`: in the same position the two
     * constructors would have one JVM signature.
     */
    public constructor(
        language: String?,
        synced: Boolean,
        lines: List<LyricsLine>,
        offsetMilliseconds: Long,
        kind: String? = null,
        displayArtist: String? = null,
        displayTitle: String? = null,
    ) : this(language, synced, offsetMilliseconds.milliseconds, lines, kind, displayArtist, displayTitle)

    /** [offset] in whole milliseconds. */
    val offsetMilliseconds: Long get() = offset.inWholeMilliseconds

    /** Whether this is the sung text rather than a translation or pronunciation layer. */
    val isMain: Boolean get() = kind == null || kind.equals(LYRICS_KIND_MAIN, ignoreCase = true)

    /** Whether any line carries visible text. A layer of only blank lines is not lyrics. */
    val hasText: Boolean get() = lines.any { !it.isBlank }

    /**
     * `(index into lines, effective start)` for every timed line, ordered by effective start and
     * stably, so lines sharing a time keep their order. Built once per layer, not per cursor query:
     * a Now Playing panel asks on every position tick.
     */
    internal val timeline: List<Pair<Int, Duration>> by lazy {
        lines.mapIndexedNotNull { index, line -> effectiveStart(line)?.let { index to it } }.sortedBy { it.second }
    }
}

/**
 * One track's stored lyrics document, and the layer the core chose to show.
 *
 * [layers] are the layers that were kept, in the server's order; [selectedIndex] is -1 when no
 * layer is displayable. [droppedLayers] counts the layers of the server's answer that did not fit
 * the caps (§18.4) and were not kept: when it is above zero the document is **not** everything the
 * server has, and a shell says so ("some lyrics were too large to show") rather than presenting it
 * as complete.
 */
public data class TrackLyrics(
    val source: LyricsSource,
    val layers: List<LyricsLayer>,
    val selectedIndex: Int,
    val droppedLayers: Int = 0,
) {
    val selected: LyricsLayer? get() = layers.getOrNull(selectedIndex)

    /** Whether layers of the server's answer were left out to fit the caps. */
    val isTrimmed: Boolean get() = droppedLayers > 0

    public companion object {
        /** Builds the document and runs the selection policy over [layers]. */
        public fun of(
            source: LyricsSource,
            layers: List<LyricsLayer>,
            preferredLanguages: List<String>,
            droppedLayers: Int = 0,
        ): TrackLyrics = TrackLyrics(source, layers, selectLyricsLayer(layers, preferredLanguages), droppedLayers)
    }
}

/**
 * The selection policy of §18.4. Returns the index into [layers] of the layer to display, or -1.
 *
 * 1. Only displayable layers compete: `main` layers (§18.4: translations and pronunciations are
 *    extra layers, never the lyrics) that carry visible text.
 * 2. **Synced beats unsynced**, whatever the language. Timing belongs to what is sung, and the
 *    scrolling display is the feature; an unsynced layer is shown only when no synced one exists.
 * 3. Among layers equal on (2), a layer in the person's most preferred language wins
 *    ([preferredLanguages] in preference order, BCP 47 or ISO 639 codes). **The person's own list
 *    outranks every relative**: a layer in any listed language beats a macrolanguage relative of
 *    an earlier one (`[zh-Hans, en]` over `yue` and `eng` shows `eng`), and a relative is used
 *    only when no layer is in a listed language — then in the order of the preference it is
 *    related to. Within one preference, the same script before an unstated one before a different
 *    one ([lyricsLanguageMatch]). **List order decides before script**: a layer is ranked at the
 *    FIRST preference in its language, and only there by script, so a later preference naming its
 *    script exactly is never reached. `[zh-Hans, zh-Hant]` over layers `zh-Hant` and `zh` selects
 *    `zh`: both rank at `zh-Hans`, where an unstated script beats a different one (decided by the
 *    maintainer at the third review). After those, a layer whose language the server does not
 *    know, which is usually the original; then any other language.
 * 4. Remaining ties go to the alphabetically first language code, then to the alphabetically
 *    first layer kind (both ignoring case and surrounding space), and only then to the server's
 *    order, which decides only between layers equal in all of these. Not the server's order
 *    earlier: OBSERVED on the reference server, the order of one track's language layers is
 *    stable between requests but differs between library scans, and between two instances built
 *    from the same fixture corpus ([por, deu, eng] on one, [eng, deu, por] on another,
 *    2026-09-24), so a tie left to it would change the shown language with nothing else having
 *    changed.
 */
public fun selectLyricsLayer(layers: List<LyricsLayer>, preferredLanguages: List<String>): Int {
    val preferred = preferredLanguages.mapNotNull(::lyricsLanguageTag)
    var bestIndex = -1
    var bestRank: LayerRank? = null
    layers.forEachIndexed { index, layer ->
        if (!layer.isDisplayable) return@forEachIndexed
        val rank = layerRank(layer, preferred)
        val current = bestRank
        if (current == null || rank < current) {
            bestIndex = index
            bestRank = rank
        }
    }
    return bestIndex
}

/** Rule 1: a `main` layer with visible text. Only these can be selected. */
internal val LyricsLayer.isDisplayable: Boolean get() = isMain && hasText

/**
 * The indices of [layers] in the order the caps consider them (§18.4): the order of the selection
 * itself, so a layer is never dropped to make room for one the selection ranks below it.
 *
 * First the displayable layers ([isDisplayable]) by the rank of [selectLyricsLayer] — the first of
 * them is the layer the selection would show; then the extra layers (translations,
 * pronunciations) that carry text, by the same rank of language; then every layer without visible
 * text, which nothing can show. Within each group ties are broken as the selection breaks them
 * (rule 4): language code, then kind, and the server's order last.
 */
internal fun lyricsLayerRankOrder(layers: List<LyricsLayer>, preferredLanguages: List<String>): List<Int> {
    val preferred = preferredLanguages.mapNotNull(::lyricsLanguageTag)
    fun group(layer: LyricsLayer): Int = when {
        layer.isDisplayable -> 0
        layer.hasText -> 1
        else -> 2
    }
    val ranks = layers.map { layerRank(it, preferred) }
    return layers.indices.sortedWith(
        compareBy<Int> { group(layers[it]) }.thenBy { ranks[it] }.thenBy { it },
    )
}

/**
 * The language tiers of rule 3, lower being better: a listed language at its position (0 until
 * n), a relative of one at n plus that position, then unknown (2n), then any other (2n + 1).
 */
private fun layerRank(layer: LyricsLayer, preferred: List<LyricsLanguageTag>): LayerRank {
    val sync = if (layer.synced) 0 else 1
    val code = layer.language?.trim()?.lowercase().orEmpty()
    val kind = layer.kind?.trim()?.lowercase().orEmpty()
    val tag = layer.language?.let(::lyricsLanguageTag)
        ?: return LayerRank(sync, 2 * preferred.size, 0, code, kind)
    var related: LayerRank? = null
    preferred.forEachIndexed { position, wanted ->
        val match = lyricsLanguageMatch(wanted, tag) ?: return@forEachIndexed
        if (!match.related) return LayerRank(sync, position, match.script, code, kind)
        if (related == null) related = LayerRank(sync, preferred.size + position, match.script, code, kind)
    }
    return related ?: LayerRank(sync, 2 * preferred.size + 1, 0, code, kind)
}

/** Lower is better; compared field by field. A strict comparison keeps the first of equals. */
private data class LayerRank(
    val sync: Int,
    val language: Int,
    val match: Int,
    val code: String,
    val kind: String,
) : Comparable<LayerRank> {
    override fun compareTo(other: LayerRank): Int =
        compareValuesBy(this, other, LayerRank::sync, LayerRank::language, LayerRank::match, LayerRank::code, LayerRank::kind)
}

/**
 * Where playback is within a synced layer.
 *
 * [index] is the first line of the current group, or -1 before the first line is due (and always
 * for an unsynced layer); [lastIndex] is the group's last line. A group is the lines sharing one
 * effective start — a bilingual LRC lists the original and its translation under one timestamp —
 * and a shell highlights `index..lastIndex`, which are consecutive in a layer the parser built
 * (it stores synced lines in start order). [isInterlude] is true before the first line and when
 * every line of the group is blank: the shell shows the instrumental state rather than
 * highlighting empty text. [lineStart] and [nextLineStart] are the effective (offset-applied)
 * times bounding the current group, for a progress animation; [nextLineStart] is null after the
 * last line and [lineStart] before the first.
 */
@ConsistentCopyVisibility
public data class LyricsCursor internal constructor(
    val index: Int,
    val lastIndex: Int,
    val isInterlude: Boolean,
    @property:HiddenFromObjC val lineStart: Duration?,
    @property:HiddenFromObjC val nextLineStart: Duration?,
) {
    /** [lineStart] in whole milliseconds, or null before the first line. */
    val lineStartMilliseconds: Long? get() = lineStart?.inWholeMilliseconds

    /** [nextLineStart] in whole milliseconds, or null after the last line. */
    val nextLineStartMilliseconds: Long? get() = nextLineStart?.inWholeMilliseconds

    public companion object {
        public val NONE: LyricsCursor =
            LyricsCursor(index = -1, lastIndex = -1, isInterlude = false, lineStart = null, nextLineStart = null)
    }
}

/**
 * The bound on a trusted time (§18.4): a line start or an offset beyond ±24 hours is not a
 * position in a song. ASSUMED as the bound, deliberately generous; what it guarantees is that
 * `start - offset` of two trusted times is finite and far from overflow.
 */
internal val LYRICS_TIME_BOUND: Duration = 24.hours

internal fun Duration.isTrustedLyricsTime(): Boolean = this >= -LYRICS_TIME_BOUND && this <= LYRICS_TIME_BOUND

/**
 * The time at which [line] of this layer becomes current: its start moved by the offset. Null when
 * the line has no start, or when the start or the layer's offset is not a trusted time.
 */
@HiddenFromObjC
public fun LyricsLayer.effectiveStart(line: LyricsLine): Duration? {
    val start = line.start ?: return null
    if (!start.isTrustedLyricsTime() || !offset.isTrustedLyricsTime()) return null
    return start - offset
}

/** [effectiveStart] in whole milliseconds. */
public fun LyricsLayer.effectiveStartMilliseconds(line: LyricsLine): Long? = effectiveStart(line)?.inWholeMilliseconds

/**
 * The current line group for a playback [position] (media time, §12.3). Pure and stateless, so a
 * seek in either direction needs nothing but the new position: the answer is the LAST effective
 * start at or before [position], and of the lines sharing it, the FIRST is [LyricsCursor.index]
 * and the last [LyricsCursor.lastIndex]. An unsynced layer has no current line
 * ([LyricsCursor.NONE]).
 *
 * Lines are expected in start order (the OpenSubsonic contract), but the answer does not depend on
 * it: the timed lines are ordered by effective start (stably) before a binary search. Indices are
 * into [LyricsLayer.lines] as given. Lines without a trusted start are never current. Total: any
 * position, including ±infinity, has an answer.
 */
@HiddenFromObjC
public fun LyricsLayer.cursorAt(position: Duration): LyricsCursor {
    if (!synced) return LyricsCursor.NONE
    val timed = timeline
    if (timed.isEmpty()) return LyricsCursor.NONE
    // Last timed entry with effective start <= position.
    var low = 0
    var high = timed.size - 1
    var found = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (timed[middle].second <= position) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    if (found < 0) {
        return LyricsCursor(index = -1, lastIndex = -1, isInterlude = true, lineStart = null, nextLineStart = timed[0].second)
    }
    val start = timed[found].second
    var first = found
    while (first > 0 && timed[first - 1].second == start) first -= 1
    return LyricsCursor(
        index = timed[first].first,
        lastIndex = timed[found].first,
        isInterlude = (first..found).all { lines[timed[it].first].isBlank },
        lineStart = start,
        nextLineStart = timed.getOrNull(found + 1)?.second,
    )
}

/** [cursorAt] for a position in milliseconds: the entry point Swift sees. */
public fun LyricsLayer.cursorAtMilliseconds(positionMilliseconds: Long): LyricsCursor =
    cursorAt(positionMilliseconds.milliseconds)

internal const val LYRICS_KIND_MAIN = "main"

/**
 * A comparable key for a language code: the primary subtag, lower-cased, with the aliases and the
 * ISO 639-2 table of `LyricsLanguage.kt` applied. `und`, `xxx`, `zxx`, `mul`, `mis` and blanks
 * are "not a language" and return null. A code no table knows is compared as written, so an
 * exact match still works.
 */
public fun lyricsLanguageKey(code: String): String? {
    val primary = code.trim().substringBefore('-').substringBefore('_').lowercase()
    if (primary.isEmpty() || primary in UNSPECIFIED_LANGUAGE_CODES) return null
    return LYRICS_LANGUAGE_ALIASES[primary] ?: ISO_639_2_TO_1[primary] ?: primary
}
