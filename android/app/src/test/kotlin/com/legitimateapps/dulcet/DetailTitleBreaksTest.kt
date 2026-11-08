package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLibraryCachedReason
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.DiscoverAge
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverHome
import com.legitimateapps.dulcet.core.DiscoverShelfPage
import com.legitimateapps.dulcet.core.DiscoverShelfState
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where real titles and status lines break. Native graphics, because only its text layout really wraps;
 * each width is found with a control measurement that proves the condition under test is met there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class DetailTitleBreaksTest {
    @get:Rule val compose = createComposeRule()
    private val title = "A deliberately long playlist title — live recordings, alternate takes, and late-night favorites"
    private val gluedTitle = title.replace(" — ", " — ")
    private var widthPx by mutableStateOf(1000)
    private lateinit var measurer: TextMeasurer
    private lateinit var heroStyle: androidx.compose.ui.text.TextStyle

    private fun show(fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    measurer = rememberTextMeasurer()
                    heroStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Box(Modifier.width(with(LocalDensity.current) { widthPx.toDp() })) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun TextLayoutResult.lines() = (0 until lineCount).map {
        layoutInput.text.text.substring(getLineStart(it), getLineEnd(it, visibleEnd = true)).replace(' ', ' ').replace("⁠", "").trim()
    }

    private fun shownLines(tag: String): List<String> {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.last().lines()
    }

    private fun simple(text: String, width: Int, maxLines: Int, style: androidx.compose.ui.text.TextStyle = heroStyle) =
        measurer.measure(text, style, androidx.compose.ui.text.style.TextOverflow.Ellipsis, maxLines = maxLines, constraints = Constraints(maxWidth = width)).lines()

    @androidx.compose.runtime.Composable private fun hero(tag: String = "hero") = LibraryDetailHeader(title, artwork = {}, titleTag = tag)
    private val appDensity get() = Density(RuntimeEnvironment.getApplication().resources.displayMetrics.density)
    private val heroPadding get() = with(appDensity) { 32.dp.roundToPx() }
    private val screenPx get() = with(appDensity) { 411.dp.roundToPx() }
    private fun widthWhere(condition: (Int) -> Boolean): Int? = (screenPx downTo 300 step 2).firstOrNull(condition)

    private fun words(line: String) = line.split(' ').count { word -> word.any(Char::isLetterOrDigit) }

    /** Round 14's complaint: this title needed no help, and balancing moved its breaks. */
    @Test fun aTitleWithNoLoneLastWordKeepsItsOrdinaryBreaks() {
        show { hero() }
        val width = compose.runOnIdle {
            assertNotNull(widthWhere { w -> simple(gluedTitle, w - heroPadding, 3).let { it.size in 2..3 && words(it.last()) > 1 } })
        }
        compose.runOnIdle { widthPx = width }
        compose.waitForIdle()
        val ordinary = compose.runOnIdle { simple(gluedTitle, width - heroPadding, 3) }
        assertEquals(ordinary, shownLines("hero"))
        compose.onNodeWithTag("hero").assertTextEquals(title)
    }

    @Test fun aHeroLineNeverOpensWithADash() {
        show { hero() }
        val width = compose.runOnIdle {
            assertNotNull(widthWhere { w -> simple(title, w - heroPadding, 3).any { it.startsWith("—") } }, "control: an unglued title opens a line with the dash")
        }
        compose.runOnIdle { widthPx = width }
        compose.waitForIdle()
        assertFalse(shownLines("hero").any { it.startsWith("—") }, shownLines("hero").toString())
    }

    @Test fun aTitleThatWouldEndOnOneWordIsBalanced() {
        show(2f) { hero() }
        val width = compose.runOnIdle {
            assertNotNull(widthWhere { w -> simple(gluedTitle, w - heroPadding, 6).let { it.size in 2..5 && words(it.last()) == 1 } }, "control: ordinary wrapping leaves one word")
        }
        compose.runOnIdle { widthPx = width }
        compose.waitForIdle()
        val lines = shownLines("hero")
        assertTrue(words(lines.last()) > 1, lines.toString())
        assertFalse(lines.any { it.startsWith("—") }, lines.toString())
    }

    @Test fun aBarTitleLineNeverOpensWithADash() {
        val name = "A long playlist name \u2014 live recordings"
        show(2f) { DetailAppBarTitle(name, null, collapsed = true, tag = "bar") }
        val width = compose.runOnIdle {
            val style = androidx.compose.ui.text.TextStyle.Default.merge(TITLE_LARGE_PROBE).copy(lineBreak = LineBreak.Heading)
            assertNotNull((screenPx downTo 300 step 2).firstOrNull { w -> simple(name, w, 2, style).any { it.startsWith("—") } }, "control: an unglued bar title opens a line with the dash")
        }
        compose.runOnIdle { widthPx = width }
        compose.waitForIdle()
        val lines = shownLines("bar.collapsed.title")
        assertFalse(lines.any { it.startsWith("—") }, lines.toString())
        compose.onNodeWithTag("bar.collapsed.title").assertTextEquals(name)
    }

    /** The shared notice: album detail, Listen Now and every list failure at the largest font. */
    @Test fun theSharedNoticeKeepsTheAgePhraseOnOneLine() {
        val now = System.currentTimeMillis()
        val line = assertNotNull(RuntimeEnvironment.getApplication().resources.freshnessLine(
            AndroidLibraryFreshness.Cached(now - 2 * 3_600_000L, AndroidLibraryCachedReason.Offline), now))
        widthPx = screenPx
        show(2f) { ServerStateNotice(line, "notice", "Try again", "retry", {}) }
        val lines = shownLines("notice")
        assertTrue(lines.any { "Last updated 2 hours ago" in it }, lines.toString())
    }

    /** A23 and A39 at the largest font: three sentences, each whole. */
    @Test fun discoversThreeSentencesBreakBetweenSentencesAtTheLargestFont() {
        val source = FakeDiscoverWishlistSource()
        val session = DiscoverWishlistSession(source)
        try {
            val failure = DiscoverFailure("upstream-unavailable", System.currentTimeMillis() + 60_000, true)
            val age = DiscoverAge(null, 0, System.currentTimeMillis() - 2 * 3_600_000L, true, true)
            val page = DiscoverShelfPage("trendingArtists", listOf(source.item("artist")), emptyList(), null, age).copy(refreshError = failure)
            source.publish(source.state.copy(home = DiscoverHome(listOf(DiscoverShelfState("trendingArtists", page, false, failure)), emptyList(), false, true, null)))
            widthPx = screenPx
            show(2f) { PhoneDiscoverHome(session, {}, {}, {}, {}, {}, {}, {}) }
            val lines = shownLines("discover.home.status.error.message")
            assertEquals(listOf("Server is busy.", "Last updated 2 hours ago.", "Try again in 1 minute."), lines)
        } finally {
            session.close()
        }
    }

    private companion object {
        val TITLE_LARGE_PROBE = androidx.compose.material3.Typography().titleLarge
    }
}
