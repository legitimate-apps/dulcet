package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Android TV's layout tokens, for a 960 x 540 dp screen. A layout value in a TV screen file is one of these, never a
 * literal; a value no token fits gets a token here, with its role, in the same change. Type and colour stay in
 * TvRestyle.kt. Each token's KDoc names its role.
 *
 * The keyline rule the whole TV app follows: page content starts at [TV_PAGE_GUTTER] (56 dp, inside the 48 dp overscan
 * margin). A list row's focus surface spans the content column from that gutter, and tv-material draws the row's ink
 * [TV_ROW_INSET] (16 dp) further in. Every non-row element in a list column -- heading, footer, note, standalone button
 * -- takes [tvInkColumn] so it starts on that same ink line. Rows never bleed out into the gutter instead: that would put
 * a focus surface at 40 dp, inside the overscan margin.
 */

/** The TV spacing scale. 4 and 12 are for optical pairs inside one component, not for page layout. */
internal object TvSpacing {
    /** Inside one control group: an icon to its label, a title to its supporting line. */
    val xs = 4.dp
    /** Between related lines; a page notice's top; a page title to its subtitle. */
    val s = 8.dp
    /** Between the cards of a shelf or grid. */
    val m = 12.dp
    /** Between the rows of a settings page; a page header to its actions; a dialog's title to its body. */
    val l = 16.dp
    /** A page's top; a header to its content; a dialog's padding; before an action group. */
    val xl = 24.dp
    /** Above a section heading inside a page (Favorites, detail sections). */
    val xxl = 32.dp
    /** Room for a full empty state under a title. */
    val xxxl = 48.dp
}

/**
 * The overscan-safe area of a 10-foot screen: 5% of a 960 x 540 dp display, about 48 dp at the
 * sides and 27 dp at the top and bottom. Every TV page keeps its content inside it.
 */
internal val TV_SAFE_HORIZONTAL = 48.dp
internal val TV_SAFE_VERTICAL = 27.dp

/** Start and end padding of every page's content, a step inside the overscan margin. */
internal val TV_PAGE_GUTTER = 56.dp

/** A page's top padding under the tab bar. */
internal val TV_PAGE_TOP = TvSpacing.xl

/**
 * A TV page's bottom padding: the overscan margin and no more. It is also how far past its last row the page can scroll,
 * so with this a page whose last row just crosses the margin moves by that overlap, and its title stays where it was.
 */
internal val TV_PAGE_BOTTOM = TV_SAFE_VERTICAL

/** A page's content padding: the gutter at the sides, [TV_PAGE_TOP] above, [TV_PAGE_BOTTOM] (plus [extraBottom]) below. */
internal fun tvPagePadding(extraBottom: Dp = 0.dp): PaddingValues =
    PaddingValues(start = TV_PAGE_GUTTER, top = TV_PAGE_TOP, end = TV_PAGE_GUTTER, bottom = TV_PAGE_BOTTOM + extraBottom)

/**
 * tv-material `ListItem`'s inner horizontal padding (`ListItemDefaults.ContentPadding`, 16 dp): where a row's ink starts
 * inside its focus surface. Headings, notes and footers take it so they share the rows' ink column ([tvInkColumn]).
 */
internal val TV_ROW_INSET = 16.dp

/** tv-material `ListItem`'s gap from its leading content to its headline (`LeadingContentEndPadding`, 8 dp). */
internal val TV_ROW_LEADING_GAP = 8.dp

/** Puts a heading, footer, note or standalone button on the same ink column as row text. Rows never bleed instead. */
internal fun Modifier.tvInkColumn(): Modifier = padding(start = TV_ROW_INSET)

/** Row art: songs, playlists in a chooser, and a dialog header's cover. The queue keeps `QUEUE_ROW_ARTWORK_DP`. */
internal val TV_ROW_ART = 56.dp

/** The New playlist tile in Add to playlist: a 64 dp tile with a 32 dp glyph. */
internal val TV_ADD_ART = 64.dp

/** Toolbar controls on one line -- chips, dropdowns, actions -- share this height. */
internal val TV_CONTROL_HEIGHT = 40.dp

/** The inner padding of every dialog, owned by [TvDialogShell]; callers cannot set their own. */
internal val TV_DIALOG_PADDING = TvSpacing.xl

/** A form, or a message with three answers: a title, a body or fields, and a row of buttons. */
internal val TV_DIALOG_WIDTH = 640.dp

/**
 * A list of choices, a menu or a two-answer confirm: narrower, so a radio sits near its label and a body line stays
 * near 60 characters.
 */
internal val TV_DIALOG_WIDTH_LIST = 520.dp

/** The empty-state glyph's box; glyphs are outlined so their ink weight matches across states. */
internal val TV_EMPTY_ICON = 48.dp

/** The widest an empty state's hint runs, about 60 characters, so it breaks into two balanced lines at most. */
internal val TV_EMPTY_HINT_MAX = 520.dp

/**
 * Where an empty state starts under a filter's chips: the chips already part it from the title, and at
 * the full 48 dp its action falls inside the bottom room, so focusing it would move the page.
 */
internal val TV_EMPTY_UNDER_CHIPS = 36.dp

/** The chevron that says more of a page or menu lies below. */
internal val TV_SCROLL_CUE = 32.dp

/**
 * The band at the foot of a scrolling page that its chevron sits in: rows passing under it fade out, so the chevron is
 * never drawn over a row's words.
 */
internal val TV_SCROLL_CUE_BAND = 48.dp

/** A shelf tile's width on a page at rest. */
internal const val TV_SHELF_TILE = 180

/**
 * A shelf tile's width under a page notice. The notice takes space from artwork so the fixed
 * header and shelf spacing can stay in place and a two-line caption fits above the bottom fade.
 */
internal const val TV_NOTICE_SHELF_TILE = 136

/**
 * A focused card grows by half its excess width on each side, and the first column sits 56 dp in from
 * the screen edge: tv-material's 1.1 took a 200 dp cover to 46 dp, inside the 48 dp safe margin. 1.05 keeps
 * a 180 to 200 dp cover within 5 dp of growth, and the wide genre cards (about 340 dp) 1.03, 5 dp too.
 */
internal const val TV_CARD_FOCUS_SCALE = 1.05f
internal const val TV_WIDE_CARD_FOCUS_SCALE = 1.03f

/** The two-pane roots' left rail width (Library, Browse), gutter included. */
internal val TV_RAIL_WIDTH = 300.dp

/** The gap between a two-pane root's rail and its content pane. */
internal val TV_PANE_GAP = TvSpacing.xl
