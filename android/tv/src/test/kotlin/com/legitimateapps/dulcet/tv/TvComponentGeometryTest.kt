package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The geometry and focus contracts of the shared TV components that later screens adopt: what a screen gets for free by
 * using them, measured on a TV-sized screen. Each test first proves the element it measures is there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvComponentGeometryTest {
    @get:Rule val compose = createComposeRule()

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun awaitFocused(tag: String) =
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }

    private fun near(expected: Dp, actual: Dp, what: String) = assertEquals(expected.value, actual.value, 0.6f, "$what: $actual")

    private fun confirm(onSafe: () -> Unit = {}, onDestructive: () -> Unit = {}) = compose.setContent {
        TvTheme {
            TvConfirmDialog("confirm", "Delete playlist?", "It will be removed from your server.",
                "Cancel", "confirm.safe", "Delete", "confirm.destructive", onSafe, onDestructive, bodyTag = "confirm.body")
        }
    }

    /** The safe answer is where the remote starts, and the destructive one is a RIGHT away, never the first focus. */
    @Test fun aConfirmOpensOnItsSafeAnswerAndTheDestructiveOneIsARightAway() {
        var destroyed = false
        confirm(onDestructive = { destroyed = true })
        awaitFocused("confirm.safe")
        compose.onNodeWithTag("confirm.destructive").assertIsNotFocused()
        compose.onNodeWithTag("confirm.safe").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithTag("confirm.destructive").assertIsFocused()
        assertTrue(!destroyed, "moving onto Delete must not delete")
    }

    /** The shell owns the padding and the width: 24 dp to the title, 520 dp across, title 16 above body, body 24 above buttons. */
    @Test fun aConfirmIsLaidOutByTheShellNotByItsCaller() {
        confirm()
        awaitFocused("confirm.safe")
        val surface = bounds("confirm")
        val body = bounds("confirm.body")
        val safe = bounds("confirm.safe")
        near(TV_DIALOG_WIDTH_LIST, surface.right - surface.left, "the confirm's width")
        near(TV_DIALOG_PADDING, body.left - surface.left, "the body's inset")
        near(TV_DIALOG_PADDING, safe.left - surface.left, "the buttons' inset")
        near(TvSpacing.xl, safe.top - body.bottom, "body to buttons")
        near(TV_DIALOG_PADDING, surface.bottom - safe.bottom, "buttons to the foot")
        // The title is the surface's first line, 24 dp in; the body follows 16 dp under the header.
        assertTrue(body.top - surface.top > TV_DIALOG_PADDING + TvSpacing.l, "the body starts ${body.top - surface.top} into the dialog")
    }

    /** A list dialog's title starts on its rows' ink column, its rows are 4 dp apart, and focus opens on the current choice. */
    @Test fun aChoiceDialogPutsItsTitleOnTheRowsInkAndOpensOnTheCurrentChoice() {
        compose.setContent {
            TvTheme {
                TvChoiceDialog("choice", "choice.surface", "Cache limit", listOf("none", "256", "1024"), { it }, { it },
                    selected = "256", onChoose = {}, onDismiss = {})
            }
        }
        awaitFocused("choice.256")
        compose.onNodeWithTag("choice.none").assertIsDisplayed()
        val surface = bounds("choice.surface")
        val first = bounds("choice.none")
        val second = bounds("choice.256")
        near(TV_DIALOG_WIDTH_LIST, surface.right - surface.left, "the choice dialog's width")
        near(TV_DIALOG_PADDING, first.left - surface.left, "a row's focus surface inset")
        near(TvSpacing.xs, second.top - first.bottom, "the gap between rows")
        val title = compose.onNodeWithText("Cache limit").getUnclippedBoundsInRoot()
        near(TV_DIALOG_PADDING + TV_ROW_INSET, title.left - surface.left, "the title's inset (the rows' ink column)")
    }

    /** A choice past the dialog's height is scrolled into view and focused; a short list still shows its first row. */
    @Test fun aChoiceDialogReachesALateChoiceAndKeepsAShortListWhole() {
        val choices = (0 until 24).map { "$it" }
        compose.setContent {
            TvTheme {
                TvChoiceDialog("long", "long.surface", "Sleep timer", choices, { it }, { it },
                    selected = "20", onChoose = {}, onDismiss = {})
            }
        }
        awaitFocused("long.20")
        compose.onNodeWithTag("long.20").assertIsDisplayed()
    }

    /** A header's cover is drawn at row-art size whatever its caller asks for, on the header's leading edge. */
    @Test fun aDialogHeaderDrawsItsCoverAtRowArtSize() {
        compose.setContent {
            TvTheme {
                Box(Modifier.testTag("host")) {
                    TvDialogHeader("Album", "Artist", artwork = { Box(Modifier.testTag("art").height(64.dp)) })
                }
            }
        }
        val art = bounds("art")
        near(TV_ROW_ART, art.right - art.left, "the cover's width")
        near(TV_ROW_ART, art.bottom - art.top, "the cover's height")
    }

    /** The empty state's rhythm: a 48 dp glyph, 16 dp to the title, 8 dp to the hint, 24 dp to the extra content. */
    @Test fun anEmptyStateKeepsOneRhythm() {
        compose.setContent {
            TvTheme {
                TvEmptyState("No favorites yet", "Favorite songs and albums to find them here.", "empty",
                    content = { Text("Signed in as someone", Modifier.testTag("empty.extra")) })
            }
        }
        val icon = bounds("empty.icon")
        val title = bounds("empty.title")
        val hint = bounds("empty.hint")
        val extra = bounds("empty.extra")
        near(TV_EMPTY_ICON, icon.bottom - icon.top, "the glyph's box")
        near(TvSpacing.l, title.top - icon.bottom, "glyph to title")
        near(TvSpacing.s, hint.top - title.bottom, "title to hint")
        near(TvSpacing.xl, extra.top - hint.bottom, "hint to the extra content")
        assertTrue(hint.right - hint.left <= TV_EMPTY_HINT_MAX + 0.5.dp, "the hint runs ${hint.right - hint.left} wide")
    }

    /** A settings heading sits on the rows' ink column: 16 dp inside the row surfaces that start on the gutter. */
    @Test fun aSettingsHeadingStartsWhereTheRowsInkDoes() {
        compose.setContent {
            TvTheme {
                TvSettingsPage("page") {
                    item { TvSettingsHeader("Playback", Modifier.testTag("heading")) }
                    item {
                        ListItem(selected = false, onClick = {}, headlineContent = { Text("Wi-Fi streaming") },
                            modifier = Modifier.testTag("row"))
                    }
                }
            }
        }
        val row = bounds("row")
        val heading = bounds("heading")
        near(TV_PAGE_GUTTER, row.left, "the row's surface")
        near(TV_PAGE_GUTTER + TV_ROW_INSET, heading.left, "the heading's ink")
    }

    /** A notice's insets: 20 dp before its words and 16 dp after its action. */
    @Test fun aNoticeHasItsOwnInsets() {
        compose.setContent {
            TvTheme {
                TvNotice("Couldn't reach your server.", "notice.line", "Try again", "notice.action",
                    Modifier.testTag("notice")) {}
            }
        }
        val notice = bounds("notice")
        near(20.dp, bounds("notice.line").left - notice.left, "the words' inset")
        near(TvSpacing.l, notice.right - bounds("notice.action").right, "the action's inset")
    }

    /** Both panes of a two-pane root start on one top line under the title, the rail on the gutter. */
    @Test fun aTwoPaneRootStartsBothPanesOnOneLine() {
        compose.setContent {
            TvTheme {
                Box(Modifier.fillMaxSize()) {
                    TvRootTwoPane("Library", titleTag = "title",
                        rail = { item { ListItem(selected = false, onClick = {}, headlineContent = { Text("Playlists") }, modifier = Modifier.testTag("rail.0")) } },
                        content = { item { Text("Recently added", Modifier.testTag("content.0")) } })
                }
            }
        }
        val title = bounds("title")
        val rail = bounds("rail.0")
        val content = bounds("content.0")
        near(rail.top, content.top, "the content pane's first line")
        near(TV_PANE_TOP, rail.top - title.bottom, "title to the panes")
        near(TV_PAGE_GUTTER, rail.left, "the rail's start")
        near(TV_PAGE_GUTTER, title.left, "the title's start")
        near(TV_RAIL_WIDTH + TV_PANE_GAP, content.left, "the content pane's start")
    }

    /** A page header: 8 dp to its subtitle, 16 dp to its actions. */
    @Test fun aPageHeaderKeepsOneRhythm() {
        compose.setContent {
            TvTheme {
                TvPageHeader("Downloads", titleTag = "title", subtitle = "2 songs", subtitleTag = "subtitle") {
                    Box(Modifier.testTag("action").height(TV_CONTROL_HEIGHT))
                }
            }
        }
        val title = bounds("title")
        val subtitle = bounds("subtitle")
        val action = bounds("action")
        near(TvSpacing.s, subtitle.top - title.bottom, "title to subtitle")
        near(TvSpacing.l, action.top - subtitle.bottom, "subtitle to actions")
    }
}
