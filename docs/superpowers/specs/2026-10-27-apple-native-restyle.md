# Apple native presentation refresh

Status: proposed target for baseline review, 2026-10-27. No page has visual sign-off yet.
Deployment floors and product capabilities stay as defined in the main design specification.

## Evidence and sources

DOCUMENTED, retrieved 2026-10-27:

- [WWDC26: What's new in SwiftUI](https://developer.apple.com/videos/play/wwdc2026/269/),
  2:12 onwards: rebuilding with Xcode 27 adopts the refreshed system appearance. Adaptive layout
  matters on iPhone as well as iPad and Mac. System toolbars should adapt to available space.
- [WWDC26: Principles of great design](https://developer.apple.com/videos/play/wwdc2026/250/),
  6:04, 8:52, 11:13 and 13:42: familiar controls, platform-specific interaction, concise hierarchy
  and careful iteration support the experience. Simplicity preserves discoverable capabilities.
- [HIG: Materials](https://developer.apple.com/design/human-interface-guidelines/materials):
  glass belongs to navigation and floating controls. Content uses ordinary surfaces. Prefer regular
  glass for legibility; clear glass needs a visually rich background and sufficient contrast.
- [Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass):
  prefer standard bars, controls, sheets and popovers; remove backgrounds that obscure their effects.
  Check accessibility settings and use an adaptive navigation hierarchy.
- [SwiftUI auxiliary views](https://developer.apple.com/documentation/swiftui/view-auxiliary-views):
  `tabViewBottomAccessory`, `tabBarMinimizeBehavior`, and sidebar adaptation are system APIs.
- [Music controls, iOS 27](https://support.apple.com/guide/iphone/use-the-music-player-controls-iph676daac9b/27/ios/27):
  the MiniPlayer carries current-track context and transport, and opens Now Playing. Its presence
  does not require a second text-only current-track section above the library shelves.
- The Xcode 27.0 bundled guides, `SwiftUI-Implementing-Liquid-Glass-Design.md`,
  `SwiftUI-New-Toolbar-Features.md` and `AppKit-Implementing-Liquid-Glass-Design.md`, are the local
  implementation references. API availability will be checked against the selected SDK when used.
- [Hosted Xcode 27 image inventory](https://github.com/actions/runner-images/blob/main/images/macos/xcode-27-arm64-Readme.md),
  image 20261022.0305.3: standard `xcode-27`, Xcode 27.0 and 27.1, iOS and tvOS 27 simulators.

OBSERVED from the supplied iPhone Listen Now image: two competing large headings, introductory
copy, duplicate Library/Browse links, a text-only resume block, routine revalidation copy,
five tabs, and substantial whitespace before content. This is the initial visual defect list.

OBSERVED in source at bd69ec99: the compact shell uses legacy `tabItem` and one player overlay
per destination. The TV shell uses a custom section bar with a recorded selection-reset issue
from an uncommitted tvOS 26 experiment. That issue needs a reproducible 27 test before choosing
the TV implementation. Existing tours cover many screens but are not a complete census.

ASSUMED until captures and review: the following targets improve visual hierarchy on every
platform. They are acceptance criteria, not claims about screenshots already produced.

## Shared target

- One navigation title per destination. Native large title on iPhone root lists, inline title on
  compact detail pages, native toolbar/window titles on Mac. Avoid hand-padded title imitations.
- System semantic fonts and foregrounds. Body for rows, headline for primary row text, subheadline
  for metadata, title2 for shelves, footnote for useful status. No fixed text heights or letter
  stacking. Accessibility sizes wrap and move trailing controls below text when necessary.
- Use system list/form margins. Custom shelves start with 16-point compact horizontal margins,
  24-point regular margins, 8-point related gaps and 24-point section gaps; adjust against captures.
  These are starting values, not replacements for system geometry.
- Artwork leads content. Square album art, circular artist art where appropriate, restrained corner
  rounding, stable placeholders and no glass on every card. Titles and subtitles remain readable
  outside artwork. Horizontal shelves reveal the next item and have a named See All action.
- One shared track row: artwork where useful, title, artist/album, duration when space permits,
  download/state indicator and a native More menu. The row and its menu have separate hit targets.
- Pull to refresh on touch lists. Mac has a toolbar refresh and Command-R. TV has a focused toolbar
  refresh action. Routine background refresh does not insert layout-shifting paragraphs.
- Cached content remains honestly identified. Suppress routine revalidation chatter only when the
  last publication is recent; show a compact saved/offline/failed/stale status when it matters.
  Keep the original timestamp accessible. Staleness threshold is an explicit implementation choice
  to review against the reader contract; do not change freshness policy in a view.
- Empty states explain the situation and offer one relevant action. Initial load uses a native
  progress indicator within the destination. Errors retain cached content, offer retry, and avoid
  displaying raw server diagnostics. No em dashes in interface copy.
- Native menus, confirmation dialogs and sheets. Keep destructive actions distinct and preserve
  confirmation, permissions, capabilities and all existing accessibility identifiers.
- New APIs use availability guards. iOS 17, macOS 14 and tvOS 17 retain usable native fallbacks.

## Navigation and player by platform

| Platform | Navigation/account | Persistent playback | Detail presentation |
| --- | --- | --- | --- |
| iPhone | Listen Now, Library, Browse, Search with search role; account toolbar button opens Connection sheet | System TabView bottom accessory, minimizes with the tab bar; no empty accessory | Native player sheet with supported matched transition, native Back and dismissal |
| iPad | Adaptive tabs/sidebar, same four places; Account/Connection in sidebar footer | System accessory when tabs are shown; one shared persistent player in sidebar layout | NavigationSplitView, native sheet or inspector for auxiliary content |
| Mac | Native sidebar; Account and server controls in Settings window, with sidebar account shortcut | One window-level player/transport area, keyboard and pointer controls | Content/detail navigation; queue and lyrics in native inspector or player view |
| Apple TV | Native TabView section navigation including Connection; verify programmatic selection and focus restoration on 27 | Now Playing section and compact playback context where native layout supports it | Remote-driven stacks; Menu returns one level or restores tab focus |

On older iOS, four legacy tabs and one safe-area player are acceptable fallbacks. The fallback
also opens the same account sheet. Account changes preserve existing reconnect and sign-out flows.

## Page and state targets

Every row applies on all supported Apple surfaces. The coverage inventory distinguishes a
missing platform implementation from a missing screenshot. Neither is visual approval.

| Page or surface | Target and states to judge |
| --- | --- |
| Listen Now | One title, artwork-led shelves, Continue Listening only as a useful artwork card distinct from current transport; fresh, saved, offline, no history and resume states |
| Library root | Personal collection categories and artwork-led recent items, with a clear Browse invitation when empty; preserve ordinary-server fallback |
| Browse root | Server collection categories and useful discovery shelves; make Library ownership distinct without explanatory marketing copy |
| Recently Added | Native artwork grid with sort menu and single title; empty/loading/paged/error states |
| Recently Played | Same grid/rows, honest history scope, no duplicated current-player text |
| Most Played | Same grid; retain ranking and server availability semantics |
| Albums | Adaptive artwork grid and native sort menu; scannable metadata, loading and pagination |
| Artists | Portrait/grid or native list appropriate to width, reliable placeholder and context menu |
| Genres | Native list or artwork collection with clear drill-in and counts where available |
| Genre detail | Single title with associated album/track content and Play/Shuffle where supported |
| Songs | Native track list with readable actions at largest text; no separate destination invented when shown within another collection |
| Favourites | Album/artist/song grouping retains membership and empty invitation; consistent selected heart/star states |
| Album detail | Artwork, one album title, tappable artist, year/count metadata, paired Play and Shuffle, native More, full track list |
| Artist detail | Artwork and one artist title, primary actions, albums and related content shelves; no filler biography block when absent |
| Playlists | Native list/grid with artwork, title and count; New Playlist in toolbar; distinguish writable and read-only lists |
| Playlist detail | Artwork and one title, metadata, Play/Shuffle, More and list; long names and accessibility text remain usable |
| Playlist editor | Native reorder/delete affordances, Done in toolbar, no accidental row playback while editing |
| Playlist create/rename | Native prompt with clear label, keyboard, cancellation and disabled invalid confirmation |
| Add to Playlist | Native searchable selection sheet/list; loading, empty, success and server error remain visible |
| Delete Playlist | Native destructive confirmation naming the playlist; cancellation leaves it intact |
| Search landing/recents | Native search field and recent-query list; clear recents action and no decorative hero |
| Search results | Group songs, albums and artists with artwork and See All; preserve local/server/Everywhere scopes and keyboard focus |
| Discover shelves | Artwork shelves and native section links on main after the shelves change lands; supported, unsupported, stale and offline states |
| Discover artist | Image/title, useful metadata and release shelves; held/local navigation and remote metadata stay distinguishable |
| Discover album/edition | Artwork, edition picker and tracks; keep available-local playback versus request intent clear |
| Wishlist | Native status-grouped list with artwork, plain status and meaningful dates; pending, requested, searching, unavailable and fulfilled states |
| Wishlist detail/pending | Clear durable intent, retry/remove actions and notes; no spinner replacing saved content |
| MiniPlayer | Artwork, current title/artist, Play/Pause and Next; one accessory, paused/playing/buffering and no-track states |
| Now Playing | Artwork dominates; title/artist, scrubber, time, primary transport, route/volume and secondary actions in native controls; regular glass only for floating custom controls |
| Up Next | Native reorderable list, current track distinct, context/remove controls; empty and long queue states |
| Lyrics | Clear current line, readable unsynced text, native dismissal/toggle; loading, absent, failure and largest-text states |
| Downloads | Native track status and progress; downloading, queued, failed/retry, completed and unavailable-offline rows; preserve existing presentation locations |
| Storage/device-only | Grouped Form sections for device data and automatic downloads; precise destructive actions, offline collection stays playable |
| Connection/account | Native Form grouped into server, identity, security and preferences; account summary, reconnect and sign-out; keyboard-safe fields |
| Connection errors | Inline actionable input/auth/network/protocol/server errors; native TLS warning and persistence-recovery sheet, no duplicate full-page warning |
| Streaming quality | Native pickers/menus in settings, concise network-specific labels and visible current selection |
| Context menus | Native menus for album, artist, track and playlist; consistent symbols and destructive grouping |
| First run/empty player | Native unavailable/empty surface with account action; no fake transport or blank floating player |
| Global offline/error/loading | One compact status per relevant surface; retain useful content, actions and accessible explanations |

## Capture and review contract

Use `apple-design-tour` workflow dispatch against loopback disposable fixtures only. General tours
use `tools/design-tour-server` and `tools/design-tour-faults`; Discover/Wishlist use
`tools/ci/with-discover-wishlist-fixture`. Synthetic data is suitable for geometry and navigation,
but does not establish real-library artwork diversity or production performance.

The initial baseline dispatch covers existing general tours. Dedicated fixture coverage and any
missing surface paths must be added before checkpoint 1 is called complete. A missing shot, an
UNREACHED capture, fallback render without compositor glass, or unexecuted test is a coverage gap.

Review variants: iPhone light, dark and largest accessibility size; iPad landscape; Mac light and
dark; TV with the named control focused. Include Reduce Transparency and Increase Contrast
checks for shared custom controls. Capture only affected variants during iteration; full census
once at the final tree. `tools/apple-restyle-review` produces JPEGs no larger than 1000 pixels,
quality 55, labeled sheets and `review/manifest.json`. Its coverage report is not a review verdict.

Review batches contain at most eight page sheets. Record independent PASS/FAIL by page and round;
only the owning review process grants sign-off. No product changes before baseline/spec critique.

## Cross-platform information architecture

Android should mirror the four top-level destinations, the separation of personal Library from
server Browse, account access outside phone primary navigation, artwork-led Listen Now shelves,
one resume representation, grouped search results and recents, compact consequential freshness,
and consistent collection actions. Use Material 3 navigation and playback presentation there.
Apple glass, font sizes, toolbar geometry and TV focus implementation do not transfer literally.

## Delivery order

1. Baseline, census and target critique, then shared navigation/player/card/row foundation and Listen Now.
2. Library, Browse, collection lists and album/artist/playlist details.
3. Search, Discover and Wishlist on merged main, then player/queue/lyrics and account/offline states.

Record the navigation contract in main specification section 28 when implemented, including
account access and fallback behavior. Do not change FEATURES.yml until platform evidence changes.
