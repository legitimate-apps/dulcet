package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The pure half of §18.4: the selection policy and the playback cursor. */
class LyricsTest {
    // ---- Selection ----------------------------------------------------------------------------------

    @Test
    fun syncedBeatsUnsyncedEvenWhenOnlyTheUnsyncedLayerIsInThePreferredLanguage() {
        val layers = listOf(unsynced("por"), synced("deu"))
        assertEquals(1, selectLyricsLayer(layers, listOf("pt")))
    }

    @Test
    fun amongSyncedLayersThePreferredLanguageBeatsServerOrder() {
        val layers = listOf(synced("deu"), synced("eng"), unsynced("por"))
        assertEquals(1, selectLyricsLayer(layers, listOf("en")))
        assertEquals(0, selectLyricsLayer(layers, listOf("de")))
    }

    @Test
    fun preferenceOrderDecidesBetweenTwoPreferredLanguages() {
        val layers = listOf(synced("eng"), synced("por"))
        assertEquals(1, selectLyricsLayer(layers, listOf("pt-BR", "en-US")))
        assertEquals(0, selectLyricsLayer(layers, listOf("en-GB", "pt")))
    }

    @Test
    fun anUnknownLanguageRanksBelowAPreferredOneAndAboveAnyOther() {
        val unknownFirst = listOf(synced(null), synced("fra"), synced("eng"))
        assertEquals(2, selectLyricsLayer(unknownFirst, listOf("en")))
        val otherFirst = listOf(synced("fra"), synced(null))
        assertEquals(1, selectLyricsLayer(otherFirst, listOf("en")))
        assertEquals(1, selectLyricsLayer(otherFirst, emptyList()))
        // Spelled out, not null: `und` must still outrank a known other language that sorts first.
        assertEquals(1, selectLyricsLayer(listOf(synced("fra"), synced("und")), listOf("en")))
        assertEquals(1, selectLyricsLayer(listOf(synced("ara"), synced("xxx")), emptyList()))
    }

    @Test
    fun isoCodesMatchAcrossTheirSpellings() {
        assertEquals("de", lyricsLanguageKey("ger"))
        assertEquals("de", lyricsLanguageKey("deu"))
        assertEquals("de", lyricsLanguageKey("de-AT"))
        assertEquals("pt", lyricsLanguageKey("pt_BR"))
        assertEquals("ko", lyricsLanguageKey("ko"))
        assertEquals("tlh", lyricsLanguageKey("tlh"))
        assertNull(lyricsLanguageKey("xxx"))
        assertNull(lyricsLanguageKey("und"))
        assertNull(lyricsLanguageKey("  "))
        assertEquals(1, selectLyricsLayer(listOf(synced("fra"), synced("ger")), listOf("de-CH")))
    }

    @Test
    fun onlyMainLayersWithVisibleTextCompete() {
        val translation = synced("eng").copy(kind = "translation")
        val blank = synced("eng").copy(lines = listOf(LyricsLine("  ", 0.milliseconds)))
        val layers = listOf(translation, blank, unsynced("jpn"))
        assertEquals(2, selectLyricsLayer(layers, listOf("en")))
        assertEquals(-1, selectLyricsLayer(listOf(translation, blank), listOf("en")))
        assertEquals(-1, selectLyricsLayer(emptyList(), listOf("en")))
        assertTrue(synced("eng").copy(kind = "MAIN").isMain)
    }

    @Test
    fun remainingTiesGoToTheFirstLanguageCodeWhateverTheServerOrder() {
        // OBSERVED: the reference server's layer order changes between scans; the choice must not.
        assertEquals(0, selectLyricsLayer(listOf(synced("fra"), synced("ita")), listOf("en")))
        assertEquals(1, selectLyricsLayer(listOf(synced("ita"), synced("fra")), listOf("en")))
        assertEquals(2, selectLyricsLayer(listOf(synced("eng"), unsynced("por"), synced("deu")), emptyList()))
        assertEquals(0, selectLyricsLayer(listOf(synced("deu"), unsynced("por"), synced("eng")), emptyList()))
        // Identical ranks and codes keep the server's order.
        assertEquals(0, selectLyricsLayer(listOf(unsynced(null), unsynced(null)), emptyList()))
    }

    @Test
    fun theTieBreakIgnoresCaseAndSurroundingSpace() {
        assertEquals(1, selectLyricsLayer(listOf(synced("ITA"), synced("fra")), emptyList()))
        assertEquals(0, selectLyricsLayer(listOf(synced("fra"), synced("ITA")), emptyList()))
        assertEquals(1, selectLyricsLayer(listOf(synced("ita"), synced(" FRA ")), emptyList()))
    }

    @Test
    fun theReviewersLanguageProbes() {
        // Macrolanguages: Bokmål and Nynorsk are Norwegian; Mandarin is what `zh` means.
        assertEquals(0, selectLyricsLayer(listOf(synced("nor"), synced("eng")), listOf("nb-NO")))
        assertEquals(0, selectLyricsLayer(listOf(synced("no"), synced("eng")), listOf("nn")))
        assertEquals(1, selectLyricsLayer(listOf(synced("eng"), synced("nob")), listOf("no")))
        assertEquals(1, selectLyricsLayer(listOf(synced("eng"), synced("cmn")), listOf("zh-CN")))
        assertEquals(0, selectLyricsLayer(listOf(synced("zho"), synced("eng")), listOf("cmn")))
        // An exact language beats its macrolanguage for the same preference.
        assertEquals(1, selectLyricsLayer(listOf(synced("nor"), synced("nob")), listOf("nb")))
        // Script subtags are honoured, and a Chinese region implies its script.
        assertEquals(1, selectLyricsLayer(listOf(synced("zh-Hans"), synced("zh-Hant")), listOf("zh-Hant")))
        assertEquals(0, selectLyricsLayer(listOf(synced("zh-Hant"), synced("zh-Hans")), listOf("zh-Hant-TW")))
        assertEquals(1, selectLyricsLayer(listOf(synced("zh-Hans"), synced("zh-TW")), listOf("zh-Hant")))
        assertEquals(1, selectLyricsLayer(listOf(synced("zh-Hant"), synced("zh-Hans")), listOf("zh-CN")))
        // Where the implied script alone decides: an explicit match beats an unstated script...
        assertEquals(0, selectLyricsLayer(listOf(synced("zh-Hans"), synced("zho")), listOf("zh-CN")))
        assertEquals(0, selectLyricsLayer(listOf(synced("zh-Hant"), synced("zho")), listOf("zh-TW")))
        assertEquals(0, selectLyricsLayer(listOf(synced("zh-Hant"), synced("zho")), listOf("zh-HK")))
        assertEquals(1, selectLyricsLayer(listOf(synced("zh-Hans"), synced("zh-Hant")), listOf("zh-TW")))
        // ...and a layer's region implies its script as a preference's does.
        assertEquals(1, selectLyricsLayer(listOf(synced("chi"), synced("zh-TW")), listOf("zh-Hant")))
        assertEquals(1, selectLyricsLayer(listOf(synced("chi"), synced("zh-SG")), listOf("zh-Hans")))
        assertEquals(0, selectLyricsLayer(listOf(synced("sr-Latn"), synced("sr-Cyrl")), listOf("sr-Latn")))
        // A script mismatch is still the person's language: it beats an unknown one...
        assertEquals(0, selectLyricsLayer(listOf(synced("zh-Hans"), synced("und")), listOf("zh-Hant")))
        // ...but an unstated script beats a different one,
        assertEquals(1, selectLyricsLayer(listOf(synced("zh-Hans"), synced("zho")), listOf("zh-Hant")))
        // and the same language in another script beats a relative in the right one.
        assertEquals(0, selectLyricsLayer(listOf(synced("zh-Hans"), synced("yue-Hant")), listOf("zh-Hant")))
        // The complete ISO 639-2 table: codes the partial table did not know.
        assertEquals(0, selectLyricsLayer(listOf(synced("pan"), synced("eng")), listOf("pa")))
        for ((alpha3, alpha2) in listOf(
            "pan" to "pa", "tel" to "te", "mar" to "mr", "mya" to "my", "bur" to "my",
            "bod" to "bo", "tib" to "bo", "mri" to "mi", "mao" to "mi", "nob" to "nb", "nno" to "nn",
            "cmn" to "zh", "fil" to "tl",
        )) {
            assertEquals(alpha2, lyricsLanguageKey(alpha3), "ISO 639-2 $alpha3")
        }
    }

    @Test
    fun thePersonsOwnListOutranksEveryRelatedLanguage() {
        // A maintainer decision (§18.4 rule 3): a later listed language beats an earlier one's relative.
        assertEquals(1, selectLyricsLayer(listOf(synced("yue"), synced("eng")), listOf("zh-Hans", "en")))
        assertEquals(1, selectLyricsLayer(listOf(synced("srp"), synced("eng")), listOf("hr", "en")))
        assertEquals(1, selectLyricsLayer(listOf(synced("ind"), synced("eng")), listOf("ms", "en")))
        // Server order is not what decides it.
        assertEquals(0, selectLyricsLayer(listOf(synced("eng"), synced("yue")), listOf("zh-Hans", "en")))
        // A relative is used when nothing in the list is offered...
        assertEquals(0, selectLyricsLayer(listOf(synced("srp"), synced("deu")), listOf("hr", "en")))
        assertEquals(0, selectLyricsLayer(listOf(synced("ind"), synced("und")), listOf("ms")))
        // ...in the order of the preference it is related to...
        assertEquals(1, selectLyricsLayer(listOf(synced("ind"), synced("srp")), listOf("hr", "ms")))
        assertEquals(0, selectLyricsLayer(listOf(synced("ind"), synced("srp")), listOf("ms", "hr")))
        // A relative of two preferences ranks by the earlier: `nor` relates to `nb` (first) and `nn` (last).
        assertEquals(1, selectLyricsLayer(listOf(synced("ind"), synced("nor")), listOf("nb", "ms", "nn")))
        // ...and still ahead of an unknown language and any other.
        assertEquals(1, selectLyricsLayer(listOf(synced(null), synced("srp"), synced("fra")), listOf("hr", "en")))
        // Synced still decides first: an unsynced listed language never beats a synced relative.
        assertEquals(1, selectLyricsLayer(listOf(unsynced("eng"), synced("yue")), listOf("zh-Hans", "en")))
    }

    @Test
    fun listOrderDecidesBeforeScriptSoALaterExactScriptPreferenceLoses() {
        // Decided by the maintainer at the third review (§18.4 rule 3): a layer ranks at the FIRST
        // preference in its language. Both layers rank at zh-Hans, where an unstated script (zh)
        // beats a different one (zh-Hant); the later zh-Hant preference is never reached.
        assertEquals(1, selectLyricsLayer(listOf(synced("zh-Hant"), synced("zh")), listOf("zh-Hans", "zh-Hant")))
        assertEquals(1, selectLyricsLayer(listOf(synced("zh"), synced("zh-Hant")), listOf("zh-Hant", "zh-Hans")))
        // The positive control: with the exact script first in the list, it wins.
        assertEquals(0, selectLyricsLayer(listOf(synced("zh-Hant"), synced("zh")), listOf("zh-Hant", "zh-Hans")))
        assertEquals(1, selectLyricsLayer(listOf(synced("zh"), synced("zh-Hans")), listOf("zh-Hans", "zh-Hant")))
    }

    @Test
    fun theCapsConsiderLayersInTheSelectionsOrder() {
        val translationEng = unsynced("eng").copy(kind = "translation")
        val translationDeu = unsynced("deu").copy(kind = "translation")
        val blank = synced("eng").copy(lines = listOf(LyricsLine("", 0.milliseconds)))
        val layers = listOf(unsynced("eng"), translationDeu, blank, synced("fra"), translationEng, synced("eng"))
        // The selection's layer first (synced English), then the other displayable ones by rank
        // (synced French before unsynced English: rule 2), then extras by language (English, the
        // person's, before German), then what has no text.
        assertEquals(listOf(5, 3, 0, 4, 1, 2), lyricsLayerRankOrder(layers, listOf("en")))
        assertEquals(selectLyricsLayer(layers, listOf("en")), lyricsLayerRankOrder(layers, listOf("en")).first())
        // Equal ranks keep the server's order.
        assertEquals(listOf(0, 1), lyricsLayerRankOrder(listOf(synced("eng"), synced("eng")), listOf("en")))
    }

    @Test
    fun theSelectionDoesNotDependOnTheServersOrderOfLayers() {
        // OBSERVED 2026-09-24: one file's embedded layers came back as [por, deu, eng] from one
        // Navidrome 0.63.2 instance and [eng, deu, por] from another.
        val one = listOf(unsynced("por"), unsynced("deu"), unsynced("eng"))
        val other = listOf(unsynced("eng"), unsynced("deu"), unsynced("por"))
        for (languages in listOf(listOf("fr"), listOf("en"), emptyList())) {
            assertEquals(
                one[selectLyricsLayer(one, languages)],
                other[selectLyricsLayer(other, languages)],
                "the selection for $languages followed the server's order",
            )
        }
        assertEquals("deu", one[selectLyricsLayer(one, listOf("fr"))].language, "no preference matches: the first code")
        // Equal in language, the kind decides before the server's order: a layer without a kind
        // before one marked main, in either order.
        val unmarked = synced("eng").copy(lines = listOf(LyricsLine("unmarked", 0.milliseconds)))
        val marked = synced("eng").copy(kind = "main", lines = listOf(LyricsLine("marked", 0.milliseconds)))
        for (layers in listOf(listOf(unmarked, marked), listOf(marked, unmarked))) {
            assertEquals(unmarked, layers[selectLyricsLayer(layers, listOf("en"))])
        }
        // And the caps consider extras of one language by kind, whatever the server's order.
        val pronunciation = unsynced("eng").copy(kind = "pronunciation")
        val translation = unsynced("eng").copy(kind = "translation")
        for (layers in listOf(listOf(translation, pronunciation), listOf(pronunciation, translation))) {
            assertEquals(listOf(pronunciation, translation), lyricsLayerRankOrder(layers, listOf("en")).map { layers[it] })
        }
    }

    @Test
    fun trackLyricsSaysWhenItWasTrimmed() {
        val whole = TrackLyrics.of(LyricsSource.SongLyricsExtension, listOf(synced("eng")), listOf("en"))
        assertEquals(0, whole.droppedLayers)
        assertFalse(whole.isTrimmed)
        val trimmed = TrackLyrics.of(LyricsSource.SongLyricsExtension, listOf(synced("eng")), listOf("en"), droppedLayers = 2)
        assertEquals(2, trimmed.droppedLayers)
        assertTrue(trimmed.isTrimmed)
        assertEquals(0, trimmed.selectedIndex)
    }

    @Test
    fun trackLyricsCarriesTheSelection() {
        val lyrics = TrackLyrics.of(LyricsSource.SongLyricsExtension, listOf(synced("deu"), synced("eng")), listOf("en"))
        assertEquals(1, lyrics.selectedIndex)
        assertEquals("eng", lyrics.selected?.language)
        assertNull(TrackLyrics(LyricsSource.LegacyGetLyrics, emptyList(), -1).selected)
    }

    // ---- Cursor --------------------------------------------------------------------------------------

    private val song = LyricsLayer(
        language = null,
        synced = true,
        offset = Duration.ZERO,
        lines = listOf(
            LyricsLine("first", 500.milliseconds),
            LyricsLine("second", 2_000.milliseconds),
            LyricsLine("", 3_000.milliseconds),
            LyricsLine("fourth", 4_250.milliseconds),
        ),
    )

    @Test
    fun beforeTheFirstLineIsAnInterludeWithNoCurrentLine() {
        val cursor = song.cursorAt(0.milliseconds)
        assertEquals(-1, cursor.index)
        assertTrue(cursor.isInterlude)
        assertEquals(500.milliseconds, cursor.nextLineStart)
        assertEquals(-1, song.cursorAt(499.milliseconds).index)
    }

    @Test
    fun aLineBecomesCurrentExactlyAtItsStart() {
        assertEquals(0, song.cursorAt(500.milliseconds).index)
        assertEquals(0, song.cursorAt(1_999.milliseconds).index)
        assertEquals(1, song.cursorAt(2_000.milliseconds).index)
        val second = song.cursorAt(2_500.milliseconds)
        assertEquals(2_000.milliseconds, second.lineStart)
        assertEquals(3_000.milliseconds, second.nextLineStart)
        assertFalse(second.isInterlude)
    }

    @Test
    fun aBlankLineIsAnInterludeAndTheNextLineResumes() {
        val gap = song.cursorAt(3_100.milliseconds)
        assertEquals(2, gap.index)
        assertTrue(gap.isInterlude)
        val resumed = song.cursorAt(4_250.milliseconds)
        assertEquals(3, resumed.index)
        assertFalse(resumed.isInterlude)
    }

    @Test
    fun afterTheLastLineTheLastLineStaysCurrent() {
        val end = song.cursorAt(60_000.milliseconds)
        assertEquals(3, end.index)
        assertNull(end.nextLineStart)
        assertNull(end.nextLineStartMilliseconds, "no next line is null, never a number that could be a time")
    }

    @Test
    fun aPositiveOffsetShowsLinesSoonerAndANegativeOneLater() {
        // OpenSubsonic: "Positive means lyrics appear sooner".
        val sooner = song.copy(offset = 250.milliseconds)
        assertEquals(0, sooner.cursorAt(1_749.milliseconds).index)
        assertEquals(1, sooner.cursorAt(1_750.milliseconds).index)
        assertEquals(1, sooner.cursorAt(1_760.milliseconds).index)
        assertEquals(1_750.milliseconds, sooner.cursorAt(1_760.milliseconds).lineStart)
        assertEquals(0, sooner.cursorAt(250.milliseconds).index)
        val later = song.copy(offset = (-250).milliseconds)
        assertEquals(0, later.cursorAt(2_000.milliseconds).index)
        assertEquals(1, later.cursorAt(2_250.milliseconds).index)
        assertEquals(-1, later.cursorAt(700.milliseconds).index)
        assertEquals(750.milliseconds, later.effectiveStart(song.lines[0]))
    }

    @Test
    fun seekingInEitherDirectionNeedsOnlyTheNewPosition() {
        val positions = listOf(4_300, 600, 3_050, 0, 2_000, 1_999, 10_000, 500).map { it.milliseconds }
        val expected = listOf(3, 0, 2, -1, 1, 0, 3, 0)
        assertEquals(expected, positions.map { song.cursorAt(it).index })
        // Asking again gives the same answers: nothing is carried between calls.
        assertEquals(expected, positions.map { song.cursorAt(it).index })
    }

    @Test
    fun linesSharingOneTimestampResolveToTheFirstOne() {
        // A bilingual LRC lists the original first and its translation under the same timestamp.
        val layer = song.copy(
            lines = listOf(
                LyricsLine("a", 1_000.milliseconds),
                LyricsLine("b", 1_000.milliseconds),
                LyricsLine("c", 2_000.milliseconds),
            ),
        )
        assertEquals(0, layer.cursorAt(1_000.milliseconds).index)
        assertEquals(0, layer.cursorAt(1_500.milliseconds).index)
        assertEquals(2_000.milliseconds, layer.cursorAt(1_500.milliseconds).nextLineStart)
        assertEquals(2, layer.cursorAt(2_000.milliseconds).index)
        // The group is index..lastIndex, so a shell can highlight the original and its translation.
        assertEquals(1, layer.cursorAt(1_500.milliseconds).lastIndex)
        assertEquals(2, layer.cursorAt(2_000.milliseconds).lastIndex)
        assertEquals(-1, layer.cursorAt(999.milliseconds).lastIndex)
        assertEquals(-1, LyricsCursor.NONE.lastIndex)
        // A group is an interlude only when every line in it is blank.
        val blankFirst = layer.copy(lines = listOf(LyricsLine("", 1_000.milliseconds), LyricsLine("b", 1_000.milliseconds)))
        assertFalse(blankFirst.cursorAt(1_000.milliseconds).isInterlude)
        // The reviewer's N6: a group judged by its last line alone calls this one an interlude.
        val blankLast = layer.copy(lines = listOf(LyricsLine("a", 1_000.milliseconds), LyricsLine("", 1_000.milliseconds)))
        assertFalse(blankLast.cursorAt(1_000.milliseconds).isInterlude)
        assertEquals(0 to 1, blankLast.cursorAt(1_000.milliseconds).let { it.index to it.lastIndex })
        val allBlank = layer.copy(lines = listOf(LyricsLine("", 1_000.milliseconds), LyricsLine(" ", 1_000.milliseconds)))
        assertTrue(allBlank.cursorAt(1_000.milliseconds).isInterlude)
        assertEquals(0 to 1, allBlank.cursorAt(1_000.milliseconds).let { it.index to it.lastIndex })
    }

    @Test
    fun aTimeThatHappensToBeMinusOneIsATimeNotASentinel() {
        // The reviewer's probe: start 0 with offset +1 is due at -1 ms, a real time.
        val layer = song.copy(offset = 1.milliseconds, lines = listOf(LyricsLine("a", Duration.ZERO), LyricsLine("b", 1_000.milliseconds)))
        val first = layer.cursorAtMilliseconds(0)
        assertEquals(0, first.index)
        assertEquals(-1L, first.lineStartMilliseconds)
        assertEquals(-1L, layer.effectiveStartMilliseconds(layer.lines[0]))
        // Before the first line there is no line start: null, distinguishable from any time.
        val before = layer.cursorAtMilliseconds(-2)
        assertEquals(-1, before.index)
        assertNull(before.lineStartMilliseconds)
        assertEquals(-1L, before.nextLineStartMilliseconds)
        assertNull(LyricsCursor.NONE.lineStartMilliseconds)
        assertNull(LyricsCursor.NONE.nextLineStartMilliseconds)
        assertNull(unsynced("eng").effectiveStartMilliseconds(unsynced("eng").lines[0]))
    }

    @Test
    fun theMillisecondsConstructorsBuildTheSameModel() {
        // What Swift and Android construct with, since the Duration forms are hidden from Objective-C.
        val fromMilliseconds = LyricsLayer(
            language = "eng",
            synced = true,
            offsetMilliseconds = 250L,
            lines = listOf(LyricsLine("a", startMilliseconds = 1_000L), LyricsLine("b", startMilliseconds = null)),
            kind = "main",
        )
        val fromDurations = LyricsLayer(
            language = "eng",
            synced = true,
            offset = 250.milliseconds,
            lines = listOf(LyricsLine("a", 1_000.milliseconds), LyricsLine("b", start = null)),
            kind = "main",
        )
        assertEquals(fromDurations, fromMilliseconds)
    }

    @Test
    fun theCursorNeverThrowsForAnyStoredValue() {
        // Every value a Long can hold, as a stored start, offset or position (§18.4: the cursor is
        // total, because an exception that reaches Swift ends the process).
        val extremes = listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -4_611_686_018_427_387_904L, -86_400_001L, -1L, 0L, 1L,
            86_400_000L, 4_611_686_018_427_387_903L, 5_000_000_000_000_000_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
        )
        val durations = extremes.map { it.milliseconds } + listOf(Duration.INFINITE, -Duration.INFINITE, Duration.ZERO)
        var checked = 0
        for (start in durations) {
            for (offset in durations) {
                val layer = song.copy(offset = offset, lines = listOf(LyricsLine("a", start), LyricsLine("", start), LyricsLine("b", 1_000.milliseconds)))
                for (position in extremes) {
                    val cursor = layer.cursorAtMilliseconds(position)
                    assertTrue(cursor.index in -1 until layer.lines.size, "index ${cursor.index}")
                    assertEquals(cursor, layer.cursorAt(position.milliseconds))
                    checked += 1
                }
                layer.effectiveStart(layer.lines[0])
            }
        }
        assertEquals(durations.size * durations.size * extremes.size, checked)
    }

    @Test
    fun theTimelineIsBuiltOncePerLayer() {
        // A Now Playing panel asks on every position tick; the ordering is not redone each time.
        assertSame(song.timeline, song.timeline)
        song.cursorAt(1_000.milliseconds)
        assertSame(song.timeline, song.timeline)
    }

    @Test
    fun anUnsortedLayerStillAnswersWithIndicesIntoItsOwnLines() {
        val layer = song.copy(
            lines = listOf(
                LyricsLine("late", 3_000.milliseconds),
                LyricsLine("early", 1_000.milliseconds),
                LyricsLine("middle", 2_000.milliseconds),
            ),
        )
        assertEquals(1, layer.cursorAt(1_500.milliseconds).index)
        assertEquals(2, layer.cursorAt(2_500.milliseconds).index)
        assertEquals(0, layer.cursorAt(3_500.milliseconds).index)
    }

    @Test
    fun anUnsyncedLayerHasNoCurrentLine() {
        assertEquals(LyricsCursor.NONE, unsynced("eng").cursorAt(5_000.milliseconds))
        // An unsynced layer's lines carry no time to honour even if a caller built them with one.
        assertEquals(LyricsCursor.NONE, song.copy(synced = false).cursorAt(5_000.milliseconds))
        assertEquals(LyricsCursor.NONE, song.copy(lines = emptyList()).cursorAt(5_000.milliseconds))
        assertNull(LyricsLine("x", start = null).startMilliseconds)
    }

    @Test
    fun theMillisecondsEntryPointAgreesWithTheDurationOne() {
        val withOffset = song.copy(offset = 250.milliseconds)
        for (position in listOf(0L, 249L, 250L, 1_749L, 1_750L, 2_750L, 4_000L, 9_999L)) {
            assertEquals(withOffset.cursorAt(position.milliseconds), withOffset.cursorAtMilliseconds(position))
        }
        assertEquals(1_750L, withOffset.cursorAtMilliseconds(1_800).lineStartMilliseconds)
        assertEquals(250L, withOffset.offsetMilliseconds)
    }

    private fun synced(language: String?) = LyricsLayer(
        language = language,
        synced = true,
        offset = Duration.ZERO,
        lines = listOf(LyricsLine("line in $language", 1_000.milliseconds)),
    )

    private fun unsynced(language: String?) = LyricsLayer(
        language = language,
        synced = false,
        offset = Duration.ZERO,
        lines = listOf(LyricsLine("line in $language", start = null)),
    )
}
