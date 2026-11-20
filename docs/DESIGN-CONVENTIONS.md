# Dulcet design conventions

How every Dulcet screen is laid out, on Apple platforms and on Android. A new screen, and every change
to an existing one, meets these rules; `tools/verify_design_tokens.py` and one design review per UI pull
request hold it to them ("How it is checked", below).

**The code is the source of truth.** Each rule names the token or component that carries it and the file
that token lives in. When a value here and a value in a token file disagree, the token file is right and
this document is corrected in the same change. Visual contracts live in the restyle specs:
`docs/superpowers/specs/2026-10-27-apple-native-restyle.md`,
`docs/superpowers/specs/2026-10-27-player-native-restyle.md` and
`docs/superpowers/specs/2026-11-02-android-material-restyle.md`.

## Where the tokens live

| Platform | Spacing and sizes | Shared components |
|---|---|---|
| iOS, iPadOS, macOS, tvOS | `DulcetSpacing` and `DulcetMetrics` in `apple/DulcetKit/Sources/DulcetKit/DulcetDesignSystem.swift`; `DulcetLibraryMetrics` and `DulcetSongRowMetrics` in `DulcetLibraryViews.swift`; `DulcetTVDetailMetrics` in `DulcetTVForms.swift` | `DulcetStateMessage.swift`, `DulcetProminentAction.swift`, `DulcetDetailHeader.swift`, `DulcetNativeFoundation.swift` (`DulcetDestinationStatus`, `DulcetShelfHeader`), `DulcetLibraryViews.swift` (`DulcetTrackRow`, `DulcetTrackListSeparator`, `DulcetEqualWidthActions`) |
| Android phone | `DulcetSpacing` and `DulcetSizes` in `android/app/src/main/kotlin/com/legitimateapps/dulcet/DulcetDimens.kt`; colour in `DulcetTheme.kt` | `PhoneComponents.kt` (`EmptyState`, `DulcetNotice`, `DulcetSectionHeader`, `DulcetDialog`), `PhoneLibraryVisuals.kt` (`TrackRowVisual`, `RankedMediaRow`, `AlbumCardVisual`, `CentredSlot`, `TrailingSlot`, `LibraryFilterChips`, `LibraryDetailHeader`, `MetadataText`) |
| Android TV | `TvSpacing` and the `TV_*` tokens in `android/tv/src/main/kotlin/com/legitimateapps/dulcet/tv/TvDimens.kt`; type and colour in `TvRestyle.kt` | `TvPageLayout.kt` (`TvPageHeader`, `TvPageNoticeSlot`, `TvRootTwoPane`), `TvDialogs.kt` (`TvDialogShell`, `TvConfirmDialog`, `TvMessageDialog`, `TvChoiceDialog`), `TvRestyle.kt` (`TvSettingsPage`, `TvSettingsHeader`, `TvNotice`, `TvEmptyState`) |

Paths in the Apple rows below are relative to `apple/DulcetKit/Sources/DulcetKit/`.

## Principles

1. **Native first.** Apple platforms use the system's controls, lists, forms, search and
   `ContentUnavailableView`; Android uses Material 3, and Android TV uses `androidx.tv:tv-material`. A
   custom component exists only where the system has none, and it is built from system parts.
2. **Tokens, not numbers.** Every spacing, size, radius and font size in a view names a token. A number
   that has no token yet gets one, in the token file, with a one-line comment naming its role.
3. **One component per pattern.** The song row, the detail header, the state message, the destination
   status or notice, the section heading and the dialog are each drawn by one shared component per
   platform. A screen composes them; it does not redraw them.
4. **One title, then content.** A page has one title. New, Edit, More, sort and filter live in the
   toolbar or top app bar.
5. **Every state is designed.** Every page has a designed content, empty, loading, error and offline
   state, in light and dark and at the largest text size.

## Spacing scale

**Apple: `DulcetSpacing`** (`DulcetDesignSystem.swift`). tvOS has its own values for the 10-foot screen.

| Token | iOS, iPadOS, macOS | tvOS | Use |
|---|---|---|---|
| `xxs` | 4 | 8 | label to sublabel, a field to its error |
| `xs` | 8 | 12 | row vertical padding, button pair gap, title to destination status |
| `sm` | 12 | 18 | section heading to its content, icon to text |
| `md` | 16 | 24 | tile gutter, header artwork to text |
| `lg` | 24 | 36 | destination status to first row, tvOS panel padding |
| `xl` | 32 | 52 | between sections, header block to first section |
| `xxl` | 44 | 72 | page-level separation, a state message below what is above it |

**Android phone: `DulcetSpacing`** (`DulcetDimens.kt`). An 8 dp rhythm; 4 and 12 are for optical pairs
inside one component.

| Token | dp | Use |
|---|---|---|
| `xs` | 4 | inside one control group: icon to label, rank to art |
| `s` (`headingToContent`) | 8 | between related lines and buttons in one group; below a section heading |
| `m` (`cardGap`) | 12 | between cards in rails and grids; between groups in a header |
| `l` (`pageGutter`) | 16 | page start and end padding; card and notice inner padding |
| `xl` (`sectionGap`) | 24 | above every section heading; before an action group |
| `xxl` | 32 | the top of a screen without a top app bar; around an empty state |

**Android TV: `TvSpacing`** (`TvDimens.kt`), for a 960 x 540 dp screen: `xs` 4, `s` 8, `m` 12, `l` 16,
`xl` 24, `xxl` 32, `xxxl` 48, with each role in its KDoc.

`0`, and hairlines of 0.5 or 1, are not spacing and need no token.

## Content margins and the page frame

| Platform | Margin | Token |
|---|---|---|
| iPhone, iPad | the system minimum layout margin: 16 below 414 pt wide, 20 above | `DulcetMetrics.contentMargin(forWidth:)` (`wideLayoutMarginThreshold`) |
| Mac | 24 at every width | `DulcetMetrics.contentMargin(forWidth:)` |
| Apple TV | the 80 pt safe area and nothing more | `DulcetMetrics.tvPageInset` (0); pages not yet moved use `DulcetLibraryMetrics.horizontalInset(forWidth:)` |
| Android phone | 16 dp | `DulcetSpacing.pageGutter` |
| Android TV | 56 dp, inside the 48 dp overscan margin | `TV_PAGE_GUTTER`, `tvPagePadding()`; overscan `TV_SAFE_HORIZONTAL` / `TV_SAFE_VERTICAL` |

- Tab roots, detail pages, shelves, song rows and the destination status all start on the margin. A
  row's first element (number or artwork) sits on the margin.
- Apple: apply the margin with `.contentMargins(.horizontal, margin, for: .scrollContent)`, so scroll
  indicators and edge effects stay at the window edge; outside a scroll view use
  `.dulcetContentMarginPadding()`.
- Android TV: a row's focus surface starts on the gutter and tv-material draws its ink `TV_ROW_INSET`
  (16 dp) further in. Headings, notes, footers and standalone buttons take `Modifier.tvInkColumn()` so
  they share that ink line. Rows never bleed into the overscan margin to meet a heading.
- Apple TV detail pages use `DulcetTVDetailColumns`, with `DulcetTVDetailMetrics.leadingColumnWidth` and
  `columnGap` (`DulcetSpacing.xl`). Focus platters grow into the margin; resting content stays on it.

## Element sizes

### Artwork

| Context | Apple | Android phone | Android TV |
|---|---|---|---|
| song and list row | `DulcetMetrics.rowArtworkSize`: 48 (iOS, scales with text up to `rowArtworkMaxSize` 88), 32 Mac, 72 tvOS; `stackedRowArtworkSize` 64 when a row stacks | `DulcetSizes.rowArt` 56 dp | `TV_ROW_ART` 56 dp |
| dense row, sheet header, mini player | `DulcetMetrics.denseRowArtworkSize` 28 | `DulcetSizes.compactArt` 48 dp | |
| detail header | `DulcetMetrics.detailHeaderArtworkSize` 220 beside the text; stacked between `detailHeaderStackedArtworkMinSize` and `detailHeaderStackedArtworkMaxSize`; tvOS `DulcetTVDetailMetrics.artworkSize` | `Modifier.detailHeroArt()`: 0.64 of the width, 0.45 at large text, at most `heroMax` 280 dp | |
| shelf tile | `DulcetMetrics.shelfTileWidth` 160 on iPhone; adaptive grid of `gridTileMinimumWidth` 120 on iPad and Mac | `Modifier.railTileWidth()`: `tileWidth` 160 dp, widened with the font up to 1.5x; grids `gridMinCell` 156 dp | `TV_SHELF_TILE` 180 (`TV_NOTICE_SHELF_TILE` 136 under a notice) |

- Apple cover corners scale with size through `DulcetMetrics.artworkCornerRadius(forSize:)`: 5 up to
  32 pt, 6 up to 64, 8 up to 120, then 12.
- Artists are circles and covers are never circles, on every platform.
- A context-menu preview uses the item's own shape (`contextMenuCoverPreviewSize`,
  `contextMenuArtistPreviewSize`).

### Rows

- Android phone genre rows use a tonal `DulcetSizes.genreTile` (40 dp).

- **Apple:** song rows are `DulcetTrackRow`, measured by `DulcetSongRowMetrics`. Minimum height is
  `DulcetMetrics.rowMinHeight(artwork:)` (56 / 44 on iPhone and iPad, 44 / 28 on Mac, 88 / 66 on tvOS).
  The trailing status slot (`trailingStatusSlotWidth`) is always reserved, so durations share one edge.
  Separators are `DulcetTrackListSeparator` (or `.dulcetSongRowSeparator(...)` in lazy lists), inset to
  the title's leading edge.
- **Android phone:** rows follow M3 `ListItem` (56, 72 and 88 dp for one, two and three lines, growing
  with text). Slots are `CentredSlot`, top-aligned `DulcetSizes.slotTopOffset` below the content top at
  large text, and the row ends in `TrailingSlot`, which puts More's ink on the 16 dp keyline. A status
  glyph sits in a reserved `DulcetSizes.icon` box before More; ranked rows use `RankedLeading` with
  `DulcetSizes.rankSlot`; album track numbers use `DulcetSizes.numberSlot`.
- **Android TV:** song rows are `TvTrackRow`; focus shows by fill, not scale. Cards scale by
  `TV_CARD_FOCUS_SCALE` (1.05) and reserve two title lines. Wide genre cards leave a small inset for
  the same focus treatment.
- A row shows at most one status glyph and one trailing More menu.

### Targets and icons

- **iPhone and iPad:** every tappable element has a `DulcetMetrics.minimumTouchTarget` (44 x 44 pt) hit
  area.
- **Android phone:** every interactive element is at least `DulcetSizes.touchTarget` (48 dp). Glyphs are
  `DulcetSizes.icon` (24 dp); an empty state's glyph is `DulcetSizes.emptyIcon` (48 dp).
- **tvOS:** a focusable element lifts with the system focus effect or `DulcetTVFocusedRowButtonStyle`,
  with no plate at rest, and neighbours keep at least `DulcetSpacing.md` apart so a lifted control never
  touches another.
- **Android TV:** toolbar controls on one line share `TV_CONTROL_HEIGHT` (40 dp); focus surfaces stay
  inside `TV_PAGE_GUTTER`.
- **Mac:** regular controls; the one primary action of an empty or first-run state is
  `.controlSize(.large)`.
- **Apple icons** use SF Symbols at the text style's size. A list icon column is
  `DulcetMetrics.listIconColumnWidth`, scaled with `@ScaledMetric`. A fixed `.system(size:)` symbol
  appears only inside `DulcetStateMessage`.

### Widths

- State-message body: `DulcetMetrics.stateMessageMaxWidth` (420; 880 on tvOS). Android:
  `DulcetSizes.emptyMaxWidth` (360 dp); TV hint `TV_EMPTY_HINT_MAX` (520 dp).
- Mac Settings form: `DulcetMetrics.macFormMaxWidth` (520). tvOS forms: `DulcetTVFormPage`.
- Detail header actions: `DulcetMetrics.detailHeaderActionsMaxWidth` (400).
- Dialogs: phone `DulcetSizes.dialogMinWidth` to `dialogMaxWidth` (280 to 560 dp); TV `TvDialogWidth`
  (`List` 520 dp for choices and two-answer confirms, `Form` 640 dp for forms and three-answer messages),
  set by `TvDialogShell`.

## Type roles

A screen picks a text style; it never sets a point or sp size.

| Role | Apple | Android phone | Android TV |
|---|---|---|---|
| page title | system large title (`.navigationTitle`); tvOS `DulcetTVPageTitle` (`DulcetDesignSystem.swift`) | `TopAppBar` title (`LargeTopAppBar` on roots) | `TvPageHeader`: `displaySmall` |
| detail title | `DulcetDetailHeader`: `.largeTitle` bold beside the artwork, `.title2` bold stacked | `LibraryDetailHeader` | the TV detail page's header |
| section heading | `DulcetShelfHeader` | `DulcetSectionHeader(role = Shelf)`: `titleLarge`; `role = Detail`: `titleMedium` | shelf `headlineSmall`; settings `TvSettingsHeader` |
| row title / secondary | `.body` / `.subheadline` in the secondary colour; duration `.monospacedDigit()` | `bodyLarge` / `bodyMedium` `onSurfaceVariant`; meta lines `MetadataText` | tv-material `ListItem` defaults; meta lines `tvMetaLineStyle()` |
| status, footers, field errors | `.footnote`, errors in the danger colour | `DulcetNotice`'s styles | `TvNotice`'s styles |
| state message | `ContentUnavailableView`'s own fonts | `EmptyState` | `TvEmptyState` |

- Apple colours come from registered contrast pairs (`DulcetRegisteredContrastPair`, checked by
  `tools/verify_contrast_foreground_policy.py`). A view that sets a foreground scopes it to its own
  `Text`.
- Android colours come from the `ColorScheme` (`DulcetTheme.kt`; dynamic colour on Android 12 and
  later). TV type is defined once, in `TvTypography` in `TvRestyle.kt`.

## Alignment

- One leading edge per page: the content margin. Android TV has one ink column,
  `TV_PAGE_GUTTER + TV_ROW_INSET`.
- Detail header: artwork, then `DulcetSpacing.md`, then text, top-aligned; the iPhone artist and album
  headers are centred. Its actions slot holds Play and Shuffle through `DulcetEqualWidthActions`
  (`fillsWidth: true`, `DulcetSpacing.xs` gap); every other page action goes in the toolbar More menu.
- Android trailing controls end their ink on the 16 dp keyline through `TrailingSlot` or
  `Modifier.trailingOptical()`; a screen adds no `offset` of its own.
- Android phone playback uses five equal transport columns, with Lyrics under Previous, the sleep
  status under Play, and Up Next under Next. Short windows fold these secondary actions into the
  header. In the stacked player, artwork, title and scrubber share the cover's resolved keyline
  when it is wide enough for the time readouts (`DulcetPlayerSizes.sharedColumnMinWidth`). Roomy
  stacked layouts reserve two title lines, keep metadata anchored above the scrubber, and leave
  at least 16 dp between the artwork and title.
- Android phone player item sheets share `PlayerSheetHeader` with `pageGutter` and `compactArt`.
  Lyrics use the 24 dp reading inset below that header.
- Android phone playlist edit and Up Next put the reorder grip on that keyline. Remove or More
  sits immediately before the grip.
- Metadata lines join parts with `METADATA_SEPARATOR` (" · ") and wrap with `breakMetadata`, so a wrapped
  line never starts or ends with the mark.
- Status glyphs sit in a reserved trailing box, so durations line up whether or not a row has one.
- A button pair keeps one gap and equal widths. A prompt's confirm button is disabled until its input is
  valid and is the default action.

## Section rhythm

- **Apple:** `DulcetSpacing.xl` between sections, measured from the end of one section's content to the
  next heading, and `DulcetSpacing.sm` from heading to content. A destination status sits
  `DulcetSpacing.xs` under the title and `DulcetSpacing.lg` above the first row. Grids and shelves use one
  gutter (`DulcetLibraryMetrics.shelfTileSpacing`); tile titles reserve two lines
  (`.lineLimit(2, reservesSpace: true)`).
- **Android phone:** `DulcetSectionHeader` owns `sectionGap` (24) above a heading and `headingToContent`
  (8) below it; the caller adds no spacer. Rails use `cardGap` and snap to a card's leading edge. Detail
  pages run hero, action group (Play filled and Shuffle tonal, equal halves, stacked at large text), then
  sections.
- **Android TV:** `TvPageHeader` puts `TvSpacing.s` between title and subtitle, `TvSpacing.l` before the
  actions and `TvSpacing.xl` before the content. Settings pages are `TvSettingsPage`. Two-pane roots are
  `TvRootTwoPane`, so the rail's first row and the pane's first heading share one top line. A page that
  scrolls shows `TV_SCROLL_CUE` in a `TV_SCROLL_CUE_BAND` fade at its foot.
- A component with nothing to show draws nothing: a shelf without a notice has no notice box.

## Empty, error and offline

- **No content to show** (empty, first run, loading, an error or offline with nothing cached): Apple uses
  `DulcetStateMessage` (`DulcetStateMessage.swift`): the system `ContentUnavailableView` with one symbol, a
  title, a body and at most one prominent action, centred, scrolling at the largest sizes; inside a page
  that already scrolls, `.embeddedInScrollingPage()`. Android uses `EmptyState` (`PhoneComponents.kt`) and
  `TvEmptyState` (`TvRestyle.kt`): a 48 dp outlined glyph, 16 dp, the title, 8 dp, one sentence, 24 dp,
  at most one action.
- **Content shown, but offline, stale or failed to refresh:** one status per page. Apple:
  `DulcetDestinationStatus` (`DulcetNativeFoundation.swift`), a `Label` with "Try Again" inline as a small
  bordered button; on Mac, refresh is also a toolbar item with Command-R. Android: one `DulcetNotice` as
  the first item inside the scrolling content; TV `TvNotice` in `TvPageNoticeSlot`. A page never draws a
  status per shelf.
- **Primary action:** Apple uses `DulcetProminentAction` or `.buttonStyle(.dulcetProminent)`
  (`DulcetProminentAction.swift`), never `.borderedProminent` directly.
- **Recovery words:** "Try Again" when the server is unreachable and "Sign In Again" when credentials
  were refused (Apple Title Case; Android writes "Try again" and "Sign in again"). A retry appears only
  when retrying can help.
- **Account forms:** a field error sits in its field's row, `DulcetSpacing.xxs` below the field. While
  connecting, a small progress indicator sits where the error would, and the fields are disabled. On
  Android a busy button keeps its label, disabled, with a `DulcetSizes.progressIndicator` spinner.
- **Dialogs on Android TV:** every dialog is `TvDialogShell`, which owns its padding, width and overscan
  bound. A confirm is `TvConfirmDialog` (safe answer first and focused, destructive answer to its right);
  a single choice is `TvChoiceDialog`; a third answer uses `TvMessageDialog` with `TvDialogButton`.

## Dark mode and the largest text sizes

- Every screen is designed and checked in light and dark, and at the largest accessibility text size.
- Colours are semantic: registered contrast pairs on Apple, `ColorScheme` roles on Android. The Mac is
  also checked with an inactive window, so labels never depend on `controlActiveState`.
- **Apple, at `dynamicTypeSize.isAccessibilitySize`:** rows stack (artwork at `stackedRowArtworkSize`
  above the text, or metadata below the title) through `DulcetStackedAtAccessibilitySizesLabelStyle`;
  long page titles switch to an inline title; a header's trailing action moves to its own line
  (`ViewThatFits`); address fields wrap (`axis: .vertical`); a name or address the user must read is
  never truncated.
- **Android, at `isLargeText()`** (`DulcetDimens.kt`, font scale 1.5 and above; no screen writes its own
  `fontScale` comparison): hero art drops to 0.45 of the width, Play and Shuffle stack, tiles widen with
  the font up to 1.5x, row slots top-align, and nothing else changes size.
- Bottom chrome never covers content; Apple scroll roots keep the system scroll-edge effect.

## Words

US English everywhere. "songs", never "tracks", in anything a user reads; "albums" for counts;
"Favorites". Apple buttons, empty-state titles and error titles use Title Case; Android uses sentence case
except for product names used on every platform ("Listen Now"). User-visible text lives in the string
catalog or string resources.

## How a new screen meets this

1. **Compose it from the shared components** in the table at the top, with system controls for everything
   else. If a pattern has a shared component, use it rather than drawing a variant.
2. **Name a token for every number.** If no token fits, add one to the token file in the same change, with
   a one-line comment naming its role, and add it to this document. On Apple, a component-local metric
   type named `Dulcet...Metrics` is also a token scope.
3. **Waive only a number that cannot be a token** (an optical nudge onto a glyph's baseline, a system
   metric with no API, capture-window geometry), with a reason on the same line:
   `.padding(.top, 2) // design-token: allow optical nudge onto the glyph baseline`. A definition block
   that must keep its numbers in a component file goes between `// design-tokens:begin <reason>` and
   `// design-tokens:end` (as `TvTypography` does).
4. **Design every state:** content, empty, loading, error and offline, in light and dark and at the
   largest text size, on each platform the feature ships to.
5. **Add the screen and its states to the design tour** (`tools/design-tour-apple`,
   `tools/design-tour-android`, `tools/design-tour-android-discover`), so its captures exist before
   review.
6. **When you remove literals, lower the baseline** in the same commit:
   `python3 tools/verify_design_tokens.py --update-baseline`.

## How it is checked

### The token lint: `tools/verify_design_tokens.py`

It runs in the `parity-gate` workflow, a required check on every pull request and push to `main`, as two
steps: `--self-test`, then the gate. Locally, `python3 tools/verify_design_tokens.py` (or
`tools/run-local-gates`, which runs every `parity-gate` step).

- **What it counts:** a raw number other than 0, 0.5 and 1 where a spacing or size goes. A number beside
  `*` or `/` is a multiplier and is not counted; comments and string contents are ignored.
  - Swift (`apple/DulcetKit/Sources/DulcetKit` and the app targets): `.padding(...)`, `EdgeInsets(...)`,
    `spacing:` and its relatives (`horizontalSpacing:`, `verticalSpacing:`, `minLength:`,
    `.listRowSpacing`), `.frame(...)` sizes, `.offset(...)`, corner radii, `.system(size:)`, artwork
    `size:` arguments, `.safeAreaPadding` / `.contentMargins` / `.scenePadding` / `.listRowInsets`,
    `GridItem(...)` sizes, `@ScaledMetric` seeds, and `name: CGFloat = N` constants and defaults.
  - Kotlin (`android/app`, `android/tv`, `android/shared` main sources): `N.dp`, `(-N).dp`, `Dp(N)`,
    `N.sp`, and inline `fontScale` comparisons.
- **Exempt:** the Apple token scopes (`DulcetSpacing` and every type or extension named
  `Dulcet...Metrics`), and the Android token files `DulcetDimens.kt`, `DulcetTheme.kt` and `TvDimens.kt`.
- **Waivers:** `design-token: allow <reason>` on the line, or a `design-tokens:begin <reason>` /
  `design-tokens:end` range. A reason shorter than 8 characters, an unclosed or nested range, and a
  waiver on a line with no literal all fail.
- **Ratchet:** `tools/design-token-baseline.json` records the literals left in each file, per rule. A
  count above its baseline fails and lists the new lines; a file the baseline does not list starts at 0.
  A count below its baseline also fails until the baseline is lowered, so a removed literal cannot come
  back. `--update-baseline` lowers counts and refuses to raise any. `--list FILE` prints every counted
  literal in a file, which is where a polish change starts.
- **Renaming a file** that has literals moves its baseline entry by hand in the same commit; the diff
  shows the move.

### One design review per UI pull request

A pull request that changes what a user sees gets one design review of the screens it touches, before
merge.

- **Input:** design-tour captures of the touched screens on the pull request's head (and its base, for a
  before and after): `.github/workflows/apple-design-tour.yml` (inputs `device` and `screens`) and
  `.github/workflows/android-test-soak.yml` (inputs `design_tour` and `design_section`), grouped by
  `tools/design-tour-contact-sheets`. The capture gates (`tools/verify_design_captures.py`,
  `tools/design-capture-pair-detector`) pass first, so the review never judges a duplicate or stale
  frame.
- **Variants:** light and dark; the largest text size on iPhone and Android phone; iPad when the screen is
  on iPad; the Mac with a key and an inactive window; Apple TV and Android TV when the screen ships there.
- **The look:** one pass over the whole of each touched screen against this document, not only the
  change's own claim. Broken findings are fixed and re-captured, and only those screens are looked at
  again. Everything else becomes a logged follow-up. A frame identical to another state, or a state the
  tour did not reach, is a capture fault and is reported as one.
- **Not covered by stills:** motion, focus transitions and timing. The review says so rather than passing
  them.
