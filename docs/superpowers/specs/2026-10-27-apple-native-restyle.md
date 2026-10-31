# Apple native presentation refresh

Status: round 2 corrections after independent round 1 review, 2026-10-28. Variant-specific approvals and remaining gaps are recorded in the review manifest.
Deployment floors and product capabilities stay as defined in the main design specification.
The iPhone account toolbar button replacing the Connection tab and later Android Material 3
information-architecture parity are confirmed direction. The foundation implements the Apple navigation contract below.

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
| iPhone | Listen Now, Library, Browse, Search with search role; account toolbar button opens Account sheet | System TabView bottom accessory, minimizes with the tab bar; no empty accessory | Native player sheet with supported matched transition, native Back and dismissal |
| iPad | Adaptive tabs/sidebar, same four places; Account in the trailing toolbar only | System accessory when tabs are shown; one shared persistent player in sidebar layout | NavigationSplitView, native sheet or inspector for auxiliary content |
| Mac | Native sidebar; Account and server controls in Settings window, without a duplicate sidebar account row | One window-level player/transport area, keyboard and pointer controls | Content/detail navigation; queue and lyrics in native inspector or player view |
| Apple TV | Native TabView section navigation including Account; verify programmatic selection and focus restoration on 27 | Now Playing section and compact playback context where native layout supports it | Remote-driven stacks; Menu returns one level or restores tab focus |

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
| Recently Added | Native artwork grid, no sort menu (date order defines the page), single title; empty/loading/paged/error states |
| Recently Played | Same grid/rows, honest history scope, no duplicated current-player text |
| Most Played | Ordered grid/rows with visible rank, no sort menu; unavailable server ranking hides the shelf, never invents a ranking |
| Albums | Adaptive artwork grid and native sort menu; scannable metadata, loading and pagination |
| Artists | Portrait/grid or native list appropriate to width, reliable placeholder and context menu |
| Genres | Native list or artwork collection with clear drill-in and counts where available |
| Genre detail | Single title with associated album/track content and Play/Shuffle where supported |
| Songs | Native track list with readable actions at largest text; no separate destination invented when shown within another collection |
| Favourites | Album/artist/song grouping retains membership and empty invitation; heart means Favorite everywhere; numeric star ratings belong in More menus |
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
| Connection/account | Sign-in-only Form before connection; then account summary and links to separate Playback and Downloads & Storage pages; toolbar Sign In, cause-local errors and confirmed red Sign Out row |
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
only the owning review process grants sign-off. Baseline/spec critique is complete; implementation rounds require separate visual sign-off.

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

## Foundation decisions after baseline critique, 2026-10-27

- The ordinary OpenSubsonic server and Dulcet Server have the same four phone tabs: Listen Now, Library, Browse, Search. Search has the system search role. Account is a toolbar sheet on iPhone, accessible in the adaptive sidebar on iPad, a sidebar shortcut plus Settings window on Mac, and a native Account tab on TV.
- On Dulcet Server, Library categories are Playlists, Artists, Albums, Songs, Downloaded (where supported), Favorites and Wishlist (where supported), followed by personal Recently Added artwork. There are no Server Songs or Shuffle Server Songs controls in Library. On an ordinary server without the personal-collection extension, Library offers Favorites, Playlists and Downloaded, plus Wishlist only when advertised. It does not label the complete server catalog as personal content. Favorite albums, artists and songs remain grouped in Favorites.
- Browse on either server offers server Recently Added and other supported discovery shelves, then Albums, Artists, Genres, Playlists and Songs. Shuffle Server Songs acts on that server catalog. Browse has no Downloaded or Favorites row. Device pins remain available through More and a Pinned collection in Library; pinning does not change personal membership.
- One title then content. No hero, subtitle or explanatory paragraph under a root title. New, Edit, More, sort and filter belong in toolbars. Touch refresh is pull to refresh; Mac refresh is a toolbar item and Command-R. Recently Added and Most Played have no sort menu; Most Played shows rank.
- Track rows use 48-point square artwork (artists use circular art), title and secondary metadata, at most one status glyph and one trailing More menu. Accessibility text reflows metadata below the title. Favorite and pin actions move into More/context menus and swipe actions. Numeric ratings remain in a Rating submenu. The same vocabulary applies to Up Next.
- Artwork fills its frame with no inset backing card. Compact grids use two columns; accessibility text uses readable rows. Regular grids adapt to width; TV uses large focusable lockups and context actions instead of a More pill under each tile. Shelves reveal the next item and section headers lead to See All. Empty shelves disappear. A one-item shelf may remain when it carries a distinct useful category; hiding the only available new album would remove useful content. Avoid duplication between personalized shelves where the underlying reader permits it.
- Library, Discover and Wishlist album details share one header system: artwork, one title, tappable artist, one metadata line, paired Play/Shuffle or intent buttons, toolbar More and a labelled edition picker when needed. Partly held releases distinguish playable tracks from missing tracks. Pending intent is a compact status, not a list of plain-text actions.
- One compact offline/error status per destination, never per shelf. Recent background revalidation is silent visually; cached timestamp and complete freshness remain accessible. Offline, failed refresh, scanning/changing coverage and materially stale content remain visible without raw hosts. This changes presentation, not core freshness or paging policy.
- Account is sign-in only until connected. System fields have one label, Sign In is a validation-disabled toolbar action, field errors follow their cause, and account summary links to Playback (quality/crossfade) and Downloads & Storage. One short footer per section. Sign Out is a red row with confirmation. Mac uses a grouped Settings form; TV uses full-screen pages.
- iPhone modal pages use inline titles, close buttons and appropriate detents. Alerts name their object; invalid playlist creation is disabled. Cancel is the default in Mac destructive alerts. The TV entry/picker fallback is full screen because the current TV alert text-field route is unreliable.
- Visual approval targets OS 27 on every Apple platform. Availability fallbacks retain floors iOS/tvOS 17 and macOS 14. If an older hosted iOS runtime is available, record Listen Now and Account fallback smoke photos. Phone glass pages also require Reduce Transparency and Increase Contrast variants.

Baseline critique identified route mismatches and incomplete data states. A screenshot is comparable only when its manifest records the server type, fixture state, tab set and verified destination. Repaired baseline states accompany the next round: populated Songs; Favorites with albums/artists; matching Mac light/dark data; visible phone storage rows; per-destination errors; downloading/queued/failed downloads; and saved/offline/no-history/resume Listen Now. Existing TV focus capture blockers wait for the shared toolchain fix rather than a parallel repair.


## Foundation implementation notes, 2026-10-28

Catalog categories push on Browse's navigation stack on every platform. Selecting Browse again
returns to its category root. Sidebar selection follows the displayed route, rather than the
last category selected before returning to the root. Personal categories push from Library in
the same way. The ordinary-server Browse root did not exist in the paired baseline source;
its before cell remains an explicit absent-route gap rather than an Albums photograph.

Downloads & Storage retains the existing automatic-download setting, storage totals, retention
choices and confirmed Clear Streaming Cache action. Removing every downloaded song in one action
is not an existing capability; this presentation change does not add that operation.

UI proofs retain their test and action identifiers when an action moves into a native More menu.
Server read-back still proves Favorite and membership writes. A menu action is not an inline
heart, so the UI proof no longer claims that an inline heart fills before the server answers.
The shared state tests continue to verify optimistic publication semantics.

### Status placement after baseline capture

Destination status lives in a safe-area banner outside its scrolling content. A status reflow
must not insert or resize a sibling above the first lazy artwork grid. This also keeps offline
and error feedback visible when the person scrolls a long collection. Account open and close
actions attach to the current navigation content, including Playback and Downloads & Storage.
The Mac current-track toolbar exposes numeric Rating through a native More submenu; Favorite
remains a direct action. Queue entries can inject Move and Remove into the shared row's More
and context menus through `additionalMenuActions`.

### 2026-10-28 status reflow correction

OBSERVED in iOS 27 capture diagnostics: a top safe-area inset still changes a lazy grid's screen position and can leave its accessibility node stale after status changes. Destination status now uses an overlay outside the scrolling content, without changing its insets. This supersedes the earlier safe-area-inset placement note. Loading and account changes must not insert status above an existing lazy grid. The existing offline pending-add UI proof remains the behavioral gate.

## Round 2 decisions, 2026-10-28

- iPhone and iPad keep Listen Now, Library, Browse and the static search-role Search tab on every
  server type. Search uses the system search affordance. Account is a trailing toolbar button;
  iPad has no sidebar footer duplicate. Mac Account is in Settings, reachable with the system
  Settings command and the no-server action, with no second selected-looking sidebar row.
- Mac and iPad retain a Browse root. The sidebar lists root destinations, without repeating the
  catalog categories. Browse lists Albums, Artists, Genres and Songs, followed by Recently Added
  and Most Played shelves. Playlists are personal and appear only in Library. Recently Played
  appears only in Listen Now. The same Browse composition applies on every Apple platform.
- Ordinary OpenSubsonic Library has Playlists, Downloaded when supported, and Favorites; a server
  with personal membership also has Artists, Albums, Songs and its personal Recently Added shelf.
  Pinned items and an advertised Wishlist remain available. On Mac, ordinary-server Favorites
  can preview album and artist rows in the Library root. Rows have native minimum heights.
- Heavy Rotation is omitted when its ordered item identities exactly repeat Recently Played.
  Fixed-width, leading-aligned shelves remain appropriate with a single item; a singleton is
  useful when it does not repeat another shelf. Empty shelves remain hidden.
- Loaded artwork has no neutral backing card or default white border. Captures use original,
  opaque square fixture covers so transparency does not disguise the geometry.
- Native bottom scroll edges use the soft system scroll-edge effect. A destination push or tab
  change resets native tab minimization; only direct scrolling away from the top enables the
  system on-scroll-down policy. Programmatic offsets do not minimize a newly pushed page.
- Empty status overlays draw no background. A visible status stays outside lazy scrolling
  content and its background does not extend into navigation safe areas. This addresses the
  source-level occluder found above clipped titles; runtime photographs must verify the result.
- Mac Settings uses three real tabs, Account, Playback and Downloads & Storage, without pushed
  sibling pages. Sign In and Reconnect are bottom default buttons. The saved account explicitly
  says Not Connected. Address rows show the normalized host, nondefault port and base path,
  without user information, query or fragment. Sign Out remains confirmed and destructive.
- tvOS retains its v1 no-download contract. Account offers Playback without a Downloads & Storage link. Android TV retains its existing download support.
- Storage has Offline Music, Storage and Streaming Cache sections, with relevant section footers.
  Clear Streaming Cache names the cache being cleared. Remove All Downloads awaits a controller
  operation that defines removal of active, automatic and pinned downloads; account-data removal
  is not an equivalent operation.
- Mac retains streaming quality for limited networks. The production path monitor treats either
  `NWPath.isExpensive` or `isConstrained` as limited on Mac too, so labels describe those actual
  conditions rather than a cellular radio. This is a supported platform difference from Music.
- Android mirrors destination ownership, playlist/history placement, singleton deduplication,
  Favorite vocabulary, clear-cache scope and account states in native Material 3. Apple bar,
  sheet, toolbar and Settings-window presentation remains platform-specific.

Implementation references checked against the Xcode 27 SDK and Apple documentation on 2026-10-28:
[Search tab role](https://developer.apple.com/documentation/swiftui/tabrole/search),
[tab bar minimization](https://developer.apple.com/documentation/swiftui/tabbarminimizebehavior),
[system scroll-edge style](https://developer.apple.com/documentation/swiftui/view/scrolledgeeffectstyle(_:for:)),
and [HIG search fields](https://developer.apple.com/design/human-interface-guidelines/search-fields).
The installed SwiftUI interface declares the scroll-edge and minimization APIs at iOS 26;
availability-gated native fallbacks retain the deployment floors.
