# Android native Material restyle: phone and Android TV

Checkpoint 1 · 2026-11-02 · baseline `dab11e7e` · design target, not implementation approval.

## Purpose and boundaries

Dulcet is an open-source multiplatform client for OpenSubsonic-compatible music servers, with
Navidrome as the reference and primary tested server. The baseline compatibility contract is Subsonic
REST API 1.16.1; OpenSubsonic extensions are negotiated at runtime.

The Android restyle follows the signed-off Apple information architecture in
`origin/feat/ios27-restyle:docs/superpowers/specs/2026-10-27-apple-native-restyle.md`, using native
Material on phone and Compose for TV on television. This checkpoint inventories, specifies and
photographs the existing UI. Production UI, contracts and FEATURES.yml are unchanged. No §28 entry
is needed. The baseline package is for independent critique; no screenshot has visual sign-off yet.

Settled: four phone content tabs on both server kinds (Listen Now, Library, Browse, Search), Account
in the root toolbar; personal rows in Library, server rows in Browse; Playlists in Library and
history in Listen Now. Heart means Favorite, ratings are in More. Ranked Most Played and Top Songs
show rank at every font scale and have no sort. Browse has no Shuffle. Rows, artwork and status
badges share components; TV may use long-Select. Android TV keeps its existing download support.

Connection and related source awaits the integration of #408/#420; Wishlist teardown awaits #409.
Those pages remain in this inventory; advanced connection settings have dependency-absent baseline cells until those PRs integrate. This checkpoint does not edit those sources.

## Research, checked 2026-11-02

Sources distinguish publication/release date from retrieval date. Undated living guidance is
marked retrieved rather than assigned a fabricated publication date. Official documentation was
retrieved through Google developer knowledge, Context7, web retrieval and `websearch fetch`.

- **BOM (OBSERVED)**: the live [2026.06.01 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom)
  maps `androidx.compose.material3:material3` to **1.4.0**, matching the project BOM pin. BOM dates
  do not imply every published API is stable. The [Material3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3)
  retrieved 2026-11-02 list stable 1.4.0 and 1.5.0-beta02 as of **2026-10-21**. Keep the current BOM.
- **M3 / Expressive (DOCUMENTED)**: [Building with M3 Expressive](https://m3.material.io/blog/building-with-m3-expressive)
  and the [May 13, 2025 Android announcement](https://blog.google/products-and-platforms/platforms/android/material-3-expressive-android-wearos-launch/)
  describe an evolution of Material 3 through hierarchy, color, shape, size, containment and motion.
  [Google's research](https://design.google/library/expressive-material-design-google-research?pubDate=20250521)
  (May 2025; retrieved 2026-11-02) specifically warns that an unstructured art collage performed
  worse than a familiar playlist list. Use expressive emphasis for Play and key state changes,
  with conventional artwork shelves and vertical song lists. It does not justify decorating every row.
- **Stable API boundary (OBSERVED/DOCUMENTED)**: Context7's current reference marks
  `ContainedLoadingIndicator` with `ExperimentalMaterial3ExpressiveApi`, and dates
  `MotionScheme.expressive()` to **1.5.0-alpha29**. Do not select these merely because a living
  design page demonstrates them. Stable `MaterialTheme`, `NavigationBar`, `TopAppBar`, `ListItem`,
  buttons, menus, text fields and dialogs provide this checkpoint's target vocabulary. Before each
  implementation, resolve its exact signature in the 1.4.0 sources; experimental opt-ins require an
  explicit implementation decision, not a blanket Expressive migration. A direct inspection of the
  [1.4.0 Android sources](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar)
  on 2026-11-02 confirms `MotionScheme.expressive()`, `MediumFlexibleTopAppBar` and
  `LargeFlexibleTopAppBar` are internal in that artifact, and no `LoadingIndicator.kt` ships.
  Their presence in newer public references is not availability to this pinned app.
- **NAV**: [Material navigation bar](https://m3.material.io/components/navigation-bar/overview),
  [top app bars](https://m3.material.io/components/app-bars/overview) (Expressive guidance updated
  May 2025; retrieved 2026-11-02). Use four labeled destinations, visible selected state and one
  root title with trailing Account. The living May 2025 guidance replaces the baseline bar with a shorter flexible bar and replaces
  medium/large app bars with flexible variants. Use those only where the pinned 1.4.0 API supports
  them; otherwise retain native stable M3 components with the same hierarchy. Secondary category
  choices push a page instead of forming an
  eight-chip navigation strip above every screen. Respect edge-to-edge system insets.
- **LIST**: [Material lists](https://m3.material.io/components/lists/guidelines) (living guidance,
  retrieved 2026-11-02) and the Expressive research above. Use leading art, primary title,
  secondary metadata, one trailing More. Baseline target: 48dp leading phone art, minimum 48dp
  interaction targets, 16dp page gutters, 8dp spacing rhythm; these are Dulcet choices guided by
  Material rather than claims that every dimension is mandated. Default compact albums use two
  columns; font scale 2 uses rows. Never use ellipsis to hide a ranked list's position.
- **DIALOG**: [Material dialogs](https://m3.material.io/components/dialogs/overview),
  [bottom sheets](https://m3.material.io/components/bottom-sheets/overview) (living guidance,
  retrieved 2026-11-02). Short confirmations name the object, separate destructive action and
  Cancel, and preserve disabled invalid submissions. Use dedicated pages for settings depth; an
  Account AlertDialog containing scrolling storage and network options is too much hierarchy in one modal.
- **SEARCH**: [Material search](https://m3.material.io/components/search/overview), retrieved
  2026-11-02. Search landing, query, result categories, scope and recents stay one coherent journey.
  Keyboard-visible and keyboard-dismissed captures are different states.
- **TV**: [Compose for TV release notes](https://developer.android.com/jetpack/androidx/releases/tv)
  release **1.1.0 on May 6, 2026**, changing focus entry/exit APIs and fixing jittery scaled text.
  Artifact is `androidx.tv:tv-material`, package `androidx.tv.material3`; do not mix mobile
  MaterialTheme into TV. [Adaptive TV guidance](https://developer.android.com/develop/adaptive-apps/guides/tv/build-adaptive-apps-for-tv)
  and [TV cards](https://developer.android.com/design/ui/tv/guides/components/cards), retrieved
  2026-11-02, call for large high-contrast content, D-pad access, visible focus, landscape and
  roughly 5% safe margins. TV card guidance supports square art and peeking rows. At 1080p judge
  distance legibility, focus clipping, neighboring-card scale space and return-focus behavior.
- **MUSIC comparators (DOCUMENTED; retrieved 2026-11-02)**:
  [YouTube Music navigation](https://support.google.com/youtubemusic/answer/13401025?hl=en-GB)
  separates recommendation Home, Explore, saved Library and category-filtered search; account
  settings are behind the profile image. [Spotify Library](https://support.spotify.com/us/article/your-library/)
  groups saved content with top filters/search, and keeps album/artist saves independent of song
  saves. [Apple Music on Android](https://support.apple.com/en-us/101645) and
  [Apple Music](https://www.apple.com/apple-music/) establish the Android client and music-first
  artwork/collection emphasis. Apple's Android support retrieval was rate-limited, so exact current
  Android pixel geometry is **not observed** here. Comparator docs support the hierarchy, not an
  unverified claim that all their present app builds look alike. Dulcet keeps its settled tabs and
  server semantics instead of importing competitors' subscriptions, podcasts or promotional rails.

## Shared design system and acceptance criteria

Phone uses semantic dynamic/light/dark colors with Dulcet fallback, neutral content surfaces,
consistent artwork corners and typography, filled primary Play and tonal secondary Shuffle. Artwork
must fill its frame, with placeholders matching the same geometry. Artist placeholders use a
consistent portrait treatment. Status should not resemble a second navigation control. No decorative
glass, faux iOS bars, repeated card-on-card framing or multiple unrelated action styles in one row.

Font scale 2.0 is Android's largest standard font-size setting for the capture matrix; verify the
runtime configuration, not just the adb setting. Content reflows and remains scrollable; metadata
may move under title. Navigation labels must retain their full semantic names. TalkBack order,
selected values, disabled states and minimum targets require semantics checks as well as images.

TV uses native TV Material focus color/scale with ample room, one initial focus on each destination,
logical four-way traversal, long-Select actions and Back returning to origin. Focus is distinct from
selection, favorite and now-playing state. Keyboard entry, long strings, bottom rows and overscan
margins are acceptance checks. A test requesting focus can capture its appearance but does not
prove every spatial D-pad path; record that limitation in the review package.

## Inventory and per-page targets

The first 39 groups map one-to-one to the Apple table; groups 40–45 make additional Android routes
or OS surfaces explicit. A group is not necessarily a standalone screen. State names are the
census, not a claim they were all photographed. `inventory.json` is the machine-readable version;
`review/baseline/coverage.json` records each requested variant and capture eligibility.
Source file names below resolve under `android/app`, `android/tv` or `android/shared`.

### A01 · Listen Now

**Current (OBSERVED in source):** Heading, two introductory lines and Refresh precede shelves; resume repeats album/title as text. Each shelf can print its own freshness. Source: `PhoneListenNow.kt; TvListenNow.kt`.

**Phone target:** One top app bar with Account; artwork-led recent/favorite/rotation shelves; one useful resume card, no introductory copy. Deduplicate identical shelves.

**TV target:** Focusable artwork shelves with a visible next card; one resume lockup, no phone-sized text controls.

**States:** fresh, no-history, resume, saved, offline. **Rationale:** M3, LIST, TV above.

### A02 · Library root

**Current (OBSERVED in source):** Ordinary servers expose server categories through a horizontal chip strip. Personal membership uses a different root. Account remains a phone navigation item. Source: `PhoneLibrary.kt; PhoneMyLibrary.kt; TvLibrary.kt; TvMyLibrary.kt`.

**Phone target:** Four stable content tabs; Library lists Playlists, Favorites, Downloaded and advertised Wishlist; personal Artists/Albums/Songs only when supported. Account moves to root toolbar.

**TV target:** TV root categories use large list items/cards; personal ownership follows the phone. Retain native Account destination.

**States:** ordinary, personal, empty, populated. **Rationale:** NAV, LIST, TV above.

### A03 · Browse root

**Current (OBSERVED in source):** Browse exists only when the personal-library extension is available and reuses LibraryHome, including filters and explanatory text. Source: `PhoneApp.kt; TvLibrary.kt`.

**Phone target:** Make Browse available on both server kinds; server Albums, Artists, Genres, Songs and discovery shelves. Playlists remain in Library, history in Listen Now; no Shuffle in Browse.

**TV target:** Same ownership, using a TV section and focused category row rather than a phone chip strip.

**States:** ordinary, personal, empty, populated. **Rationale:** NAV, TV above.

### A04 · Recently Added

**Current (OBSERVED in source):** An Albums sort choice (Newest), rather than a distinct push destination; current header and sort remain. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** Artwork grid with one title and date-defined order, no sort control. Largest font uses readable rows.

**TV target:** Square cards with title/year below, clear focus and no per-card action row.

**States:** populated, empty, loading, paged, error. **Rationale:** LIST, TV above.

### A05 · Recently Played

**Current (OBSERVED in source):** History exists in Listen Now shelves and Albums Recent sort. No dedicated See All route from the shelf. Source: `PhoneListenNow.kt; PhoneLibrary.kt; TvListenNow.kt`.

**Phone target:** History belongs in Listen Now; concise artwork shelf and See All list/grid, preserving real ordering and scope.

**TV target:** Same ownership with focus memory on return to the selected album.

**States:** populated, empty, offline. **Rationale:** MUSIC, TV above.

### A06 · Most Played

**Current (OBSERVED in source):** Albums Frequent sort; generic cards do not show the ranking. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** Dedicated ordered list/grid with rank always visible, no sort menu. Hide unsupported rankings rather than inferring one.

**TV target:** Large rank beside/below art within the card focus boundary; order survives remote navigation.

**States:** populated, empty, large-font. **Rationale:** LIST, TV above.

### A07 · Albums

**Current (OBSERVED in source):** Adaptive 156dp phone grid, category chips, Sort and separate card action affordances; TV has a large sort action row. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** Two columns at compact default width, responsive rows at font scale 2; toolbar Sort, edge-to-edge artwork within its frame, consistent metadata baseline.

**TV target:** TV cards with persistent titles and sparse toolbar sorting; restore focus after detail return.

**States:** populated, empty, loading, paged, sort. **Rationale:** LIST, TV above.

### A08 · Artists

**Current (OBSERVED in source):** Phone text rows, TV grid; several pin/membership actions can compete with each title. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** Shared artist rows with circular art/placeholder, title, secondary count and one More.

**TV target:** Large portrait or circular-art lockups, long-Select context actions and title visible outside focus.

**States:** populated, empty, loading. **Rationale:** LIST, TV above.

### A09 · Genres

**Current (OBSERVED in source):** Phone clickable text rows and TV genre grid lead to track lists. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** Simple Material list with a clear trailing navigation affordance; useful counts only when provided.

**TV target:** Wide focusable list/cards with readable genre name and consistent spacing.

**States:** populated, empty. **Rationale:** LIST, TV above.

### A10 · Genre detail

**Current (OBSERVED in source):** Title and Play/Shuffle precede tracks, with refresh/status and per-track actions. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** Single app-bar title, concise count and paired playback actions; shared track rows with consistent status treatment.

**TV target:** Large title, primary Play/Shuffle and generous track rows; restore genre focus on Back.

**States:** populated, empty, offline. **Rationale:** LIST, TV above.

### A11 · Songs

**Current (OBSERVED in source):** Scope explanations, filter and sort rows, Shuffle All and pin buttons precede/compete with tracks. Source: `PhoneLibraryDepth.kt; TvLibraryDepth.kt`.

**Phone target:** Toolbar Sort and restrained filter chips; shared artwork/title/artist rows with one More. Preserve existing shuffle behavior outside Browse root.

**TV target:** Readable track list and compact filter/sort controls; primary target distinct from secondary actions.

**States:** populated, empty, paged, offline, filter. **Rationale:** LIST, TV above.

### A12 · Favourites

**Current (OBSERVED in source):** Separate album, artist and track sections; inline favorite/rating/pin affordances vary. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** Use Favorite vocabulary and heart for server star; grouped Albums, Artists, Songs with shared rows/cards and one empty invitation. Rating moves to More.

**TV target:** Same grouping and vocabulary, with focusable cards/rows and long-Select context actions.

**States:** populated, empty, pending, failed. **Rationale:** LIST, TV above.

### A13 · Album detail

**Current (OBSERVED in source):** Artwork, centered title/metadata, several toolbar actions, favorite, rating, download and pin controls; phone rows expose many actions. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** Artwork-led unified header: title, tappable artist, metadata, prominent Play plus tonal Shuffle, toolbar More. One shared track row.

**TV target:** Split artwork and metadata/actions beside a track list; primary playback gets strongest focus emphasis.

**States:** populated, long-title, offline, partial, menu. **Rationale:** M3, LIST, TV above.

### A14 · Artist detail

**Current (OBSERVED in source):** Title/metadata, playback controls, top songs, release-group controls and album grid. Source: `PhoneLibrary.kt; TvLibrary.kt`.

**Phone target:** One artist header, useful metadata only; ranked Top Songs without sort; shared albums by release group.

**TV target:** Large artist lockup with clear content regions and no repeated focused-looking actions.

**States:** populated, no-biography, top-songs, release-groups. **Rationale:** LIST, TV above.

### A15 · Playlists

**Current (OBSERVED in source):** Phone has New Playlist inline above list. TV opens playlist cards and offers creation through Add to Playlist. Source: `PhonePlaylists.kt; TvLibrary.kt`.

**Phone target:** Toolbar New; artwork list, title/count, writable/read-only distinction and pending state.

**TV target:** Grid of large playlist cards; preserve existing creation capability through chooser, and document any missing standalone action.

**States:** populated, empty, read-only, pending. **Rationale:** LIST, DIALOG, TV above.

### A16 · Playlist detail

**Current (OBSERVED in source):** Centered header, inline Edit and More on phone; TV Play/Shuffle/Delete above entries, no standalone reorder editor. Source: `PhonePlaylists.kt; TvLibrary.kt`.

**Phone target:** Unified album-style header, toolbar Edit/More, shared track rows and local pending intent.

**TV target:** Large artwork and primary transport; contextual delete/queue actions with accurate ownership restrictions.

**States:** populated, empty, read-only, long-name, pending. **Rationale:** LIST, TV above.

### A17 · Playlist editor

**Current (OBSERVED in source):** Phone switches entries to move-up/down/remove controls. TV has no equivalent standalone edit screen. Source: `PhonePlaylists.kt`.

**Phone target:** Native editable list with obvious Done and separate reorder/delete targets; no row playback in editing mode.

**TV target:** Implementation absent: do not call a queue editor a playlist editor or add functionality as styling.

**States:** editing, long-list. **Rationale:** LIST above.

### A18 · Playlist create/rename

**Current (OBSERVED in source):** Phone NameEditor is an inline column, not a dialog; TV chooser swaps to a focused text field. TV standalone rename is absent. Source: `PhonePlaylists.kt; TvPlaylistAdd.kt`.

**Phone target:** Material AlertDialog or dedicated sheet with labeled field, disabled blank confirmation, Cancel and Save/Create.

**TV target:** Full-screen/large dialog entry compatible with TV IME; focus returns to originating chooser.

**States:** create-empty, create-valid, rename, cancel. **Rationale:** DIALOG, TV above.

### A19 · Add to Playlist

**Current (OBSERVED in source):** Phone ModalBottomSheet and TV dialog list writable playlists with New entry; busy and failure text share content. Source: `PhonePlaylists.kt; TvPlaylistAdd.kt`.

**Phone target:** Labeled modal selection surface with artwork and counts, clear progress/failure and optional search for long lists; no duplicate submits.

**TV target:** Large focusable selection list, one initial focus, Back cancels and restores the source.

**States:** populated, empty, submitting, failed, new. **Rationale:** DIALOG, TV above.

### A20 · Delete Playlist

**Current (OBSERVED in source):** Phone AlertDialog; TV wide dialog naming the playlist. Source: `PhonePlaylists.kt; TvPlaylistAdd.kt`.

**Phone target:** Material destructive confirmation names the playlist, Cancel and Delete differentiated; retain the confirmation contract.

**TV target:** Cancel initially focused, remote-safe destructive action; no deletion is performed by the baseline tour.

**States:** confirmation, cancel. **Rationale:** DIALOG, TV above.

### A21 · Search landing/recents

**Current (OBSERVED in source):** Phone outlined text field and recent-query buttons; TV keyboard field, recent actions and navigation. Source: `MobileSearchScreen.kt; TvSearchActivity.kt`.

**Phone target:** Material search pattern with recent-query rows, clear-recents action and visible scope; remove decorative introductions.

**TV target:** Search entry and on-screen keyboard with explicit focus, recent-query list and Back behavior.

**States:** landing, recents, keyboard. **Rationale:** SEARCH, TV above.

### A22 · Search results

**Current (OBSERVED in source):** Grouped results exist; phone scopes switch to Everywhere when supported. Song rows have queue/play controls. Source: `MobileSearchScreen.kt; PhoneDiscover.kt; TvSearchActivity.kt`.

**Phone target:** Grouped Songs/Albums/Artists with consistent rows, See All and persistent scope; retain keyboard state and empty/retry content.

**TV target:** Large grouped result cards/rows, legible scope and search field, explicit focus on the chosen result.

**States:** populated, empty, loading, failed, local, server, everywhere, see-all. **Rationale:** SEARCH, TV above.

### A23 · Discover shelves

**Current (OBSERVED in source):** Phone-only separate Discover tab with shelves, attribution, curated playlists and recent wishes. Android TV implementation absent. Source: `PhoneDiscoverShelves.kt`.

**Phone target:** Place server discovery within Browse while preserving source attribution, supported/unsupported detection and cached content; artwork-led shelves and See All.

**TV target:** Unsupported: record as platform absence, not a screenshot gap or permission to implement a new feature.

**States:** supported, unsupported, stale, offline, empty, failed. **Rationale:** M3, LIST above.

### A24 · Discover artist

**Current (OBSERVED in source):** Phone metadata artist page with release grid and local/held routing. TV absent. Source: `PhoneDiscoverPages.kt`.

**Phone target:** Shared artist header language, concise metadata, local availability clearly distinguished from external metadata; release shelf/grid and source attribution.

**TV target:** Unsupported. Preserve platform feature declaration.

**States:** loaded, held, missing, failed. **Rationale:** LIST above.

### A25 · Discover album/edition

**Current (OBSERVED in source):** Phone external album with edition choices, partial availability and request controls. TV absent. Source: `PhoneDiscoverPages.kt; PhoneDiscoverDetail.kt`.

**Phone target:** Shared album header, labeled edition selector and availability per track; Play held tracks versus Request missing tracks remain distinct.

**TV target:** Unsupported.

**States:** loaded, edition, partial, held, missing, failed. **Rationale:** LIST, DIALOG above.

### A26 · Wishlist

**Current (OBSERVED in source):** Phone list of durable wish statuses and actions; TV absent. Source deferred until Wishlist teardown integration merges. Source: `PhoneWishlist.kt`.

**Phone target:** Artwork rows grouped by status, plain status/date and precise retry/remove; pending intent remains visible offline.

**TV target:** Unsupported.

**States:** pending, requested, searching, unavailable, fulfilled, empty, offline. **Rationale:** LIST above.

### A27 · Wishlist detail/pending

**Current (OBSERVED in source):** Expanded actions live in the list rather than a dedicated detail page; request intents can wait to sync. Source: `PhoneWishlist.kt`.

**Phone target:** Keep row expansion or a native sheet with clear durable intent, notes, retry/remove and object-naming confirmation.

**TV target:** Unsupported.

**States:** pending, note, retry, remove-confirmation. **Rationale:** DIALOG, LIST above.

### A28 · MiniPlayer

**Current (OBSERVED in source):** Phone compact artwork/title/transport Surface above bottom navigation; no separate TV mini-player equivalent. Source: `PhoneNowPlaying.kt`.

**Phone target:** One tonal playback surface, 48dp targets, artwork/title/artist, Play/Pause/Next; hide when no session and accommodate font scale 2.

**TV target:** TV uses Now Playing section/transport context rather than a touch bar.

**States:** playing, paused, buffering, reconnect, no-track. **Rationale:** M3, LIST above.

### A29 · Now Playing

**Current (OBSERVED in source):** Gradient artwork background and custom adaptive layout; visible numeric rating, heart and transport. TV uses large cover and short queue panel. Source: `PhoneNowPlaying.kt; TvPlaybackActivity.kt`.

**Phone target:** Artwork dominates; semantic color roles with sufficient contrast, clear Play/Pause hierarchy, scrubber/time, secondary queue/lyrics/More. Ratings in More.

**TV target:** Large cover and transport beside lyrics/queue, TV focus scale and D-pad seeking; no numeric star strip competing with transport.

**States:** playing, paused, buffering, error, reconnect, long-title. **Rationale:** M3, TV above.

### A30 · Up Next

**Current (OBSERVED in source):** Phone bottom sheet with row menus; TV short panel with edit menu. Source: `PhoneNowPlaying.kt; TvQueueEditing.kt; TvPlaybackActivity.kt`.

**Phone target:** Consistent media rows, current indicator, native reorder/remove menu, clear upcoming action and empty state; keep queue identities intact.

**TV target:** Large focused row distinct from current-track indicator; edit dialog and predictable return focus.

**States:** populated, empty, long, edit-menu, confirmation. **Rationale:** LIST, DIALOG, TV above.

### A31 · Lyrics

**Current (OBSERVED in source):** Phone sheet and TV fixed-height panel show synced/unsynced text and availability/status lines. Source: `PhoneLyrics.kt; TvLyrics.kt`.

**Phone target:** Generous type, clear current line, readable line wrapping at largest font and sheet dismissal; retain loading/absent/trimmed/failure information.

**TV target:** Readable from viewing distance, active line high contrast and stable focus during auto-follow.

**States:** synced, unsynced, loading, absent, failed, trimmed. **Rationale:** LIST, TV above.

### A32 · Downloads

**Current (OBSERVED in source):** Downloaded is a Library category; progress, download/remove and failure statuses occur in album/track rows. Android TV supports downloads unlike Apple TV. Source: `PhoneLibraryDepth.kt; PhoneLibrary.kt; TvLibraryDepth.kt`.

**Phone target:** Shared status glyph/progress and actions in rows; completed, queued, downloading, failed/retry and unavailable-offline distinguishable without repeated button strips.

**TV target:** Retain TV download support with TV context actions and readable progress, never infer Apple TV parity.

**States:** empty, queued, downloading, failed, completed, offline. **Rationale:** LIST, TV above.

### A33 · Storage/device-only

**Current (OBSERVED in source):** Storage is embedded in phone Account AlertDialog; TV embeds it below quality in Account. Budget values are a horizontal row. Source: `PhoneOfflineStorage.kt; TvOfflineStorage.kt`.

**Phone target:** Dedicated Downloads & Storage settings page with grouped rows, automatic-download switch, usage, budget picker and precise Clear Streaming Cache action.

**TV target:** Full-screen settings list with visible selected choices and focusable cache action.

**States:** automatic-off, automatic-on, usage, budget, clear. **Rationale:** LIST, DIALOG, TV above.

### A34 · Connection/account

**Current (OBSERVED in source):** Phone sign-in column and account AlertDialog; TV full-screen connection/account. Account contains quality/storage; saved servers show switch/add actions. Source: `MainActivity.kt; SignOutSheet.kt; TvConnectScreen.kt; TvLibrary.kt`.

**Phone target:** Sign-in-only form until connected, labeled fields and one primary Sign In; connected Account summary links to Playback and Storage, destructive Sign Out confirmation.

**TV target:** TV native entry and account/settings pages, with wide legible fields and clear focus. Leave connection source alone until dependent PRs merge.

**States:** empty, filled, connecting, connected, saved, signout. **Rationale:** DIALOG, TV above.

### A35 · Connection errors

**Current (OBSERVED in source):** Status cards/lines describe input, auth, transport, TLS/protocol/server and persistence errors. Source: `MainActivity.kt; TvConnectScreen.kt`. Advanced connection sections belong to pending #408/#420, not this baseline.

**Phone target:** Cause-local error text and recovery; preserve TLS safety, local HTTP consent and persistence-recovery behavior. Avoid a redundant warning card at every level.

**TV target:** Large inline status and focused recovery action, no dense dashboard; capture/spec now and defer source changes pending connection PRs.

**States:** input, auth, transport, security, protocol, server, tls, persistence. **Rationale:** DIALOG, TV above.

### A36 · Streaming quality

**Current (OBSERVED in source):** Two network-specific settings in Account; phone dropdown menus and TV rows of bitrate buttons. Source: `PhoneStreamingQuality.kt; TvLibrary.kt`.

**Phone target:** Playback page with one selected value per network class and radio/list picker; apply next track, preserve current playback.

**TV target:** Full-screen list/choice dialog; mark selected independently of focus.

**States:** unmetered, metered, options. **Rationale:** DIALOG, TV above.

### A37 · Context menus

**Current (OBSERVED in source):** Phone inline actions/More and TV card options/queue dialogs coexist; rating stars occupy multiple targets. Source: `PhoneLibraryVisuals.kt; PhoneMediaRow.kt; TvCardOptions.kt`.

**Phone target:** Shared More with Favorite, Rating submenu, playlist/queue/download/pin/membership actions gated by capability; destructive group separated.

**TV target:** Long-Select on content card, readable option list, Cancel and return focus.

**States:** album, artist, track, playlist, rating, sort. **Rationale:** DIALOG, TV above.

### A38 · First run/empty player

**Current (OBSERVED in source):** No account opens connection form. Empty phone player has unavailable text; TV can launch empty Now Playing. Source: `MainActivity.kt; TvConnectScreen.kt; PhoneNowPlaying.kt; TvPlaybackActivity.kt`.

**Phone target:** Useful sign-in invitation and no empty compact player; full-player unavailable state with one way back.

**TV target:** Readable unavailable state, focused Back/Account rather than disabled-looking transport.

**States:** first-run, no-session. **Rationale:** M3, TV above.

### A39 · Global offline/error/loading

**Current (OBSERVED in source):** Independent surfaces repeat freshness/status and retry. Cached content can coexist with shelf-specific error text. Source: `PhoneLibrary.kt; TvLibrary.kt; PhoneDiscoverShelves.kt`.

**Phone target:** One compact consequential status per destination, retaining useful cached content and accessible timestamp/details; reserve spinner for actual loading.

**TV target:** One destination status, legible recovery and stable focus while data revalidates; avoid shifting card geometry.

**States:** loading, offline, failed, stale, changing, retry. **Rationale:** M3, LIST, TV above.

### A40 · Discover curated playlists

**Current (OBSERVED in source):** Phone Discover has curated-playlist directory plus entry-detail routes beyond album/artist pages. TV absent. Source: `PhoneDiscoverShelves.kt; PhoneDiscoverPages.kt`.

**Phone target:** Browse discovery section links to artwork playlist list, then consistent external detail header, availability and request controls.

**TV target:** Unsupported.

**States:** directory, entries, paged, pending, offline. **Rationale:** LIST above.

### A41 · Pinned collection

**Current (OBSERVED in source):** Device pins appear in a Library shelf and individual Pin text buttons; no dedicated root row on ordinary servers. Source: `PhoneLibraryDepth.kt; TvLibraryDepth.kt`.

**Phone target:** Library Pinned collection with shared rows and consistent menu actions; pin never means Favorite or personal membership.

**TV target:** Large focusable shelf/cards with contextual Unpin.

**States:** populated, empty. **Rationale:** LIST, TV above.

### A42 · Listening session controls

**Current (OBSERVED in source):** Phone player has autoplay switch and sleep/crossfade menus; TV has a session button and nested choice dialogs. Source: `PhoneListeningSession.kt; TvListeningSession.kt`.

**Phone target:** Consolidate under Playback/More, preserving current autoplay, timer and crossfade states; clear selected values and cancellation.

**TV target:** TV choice dialog with one initial focus and selected state independent of focus.

**States:** autoplay, sleep-menu, sleep-active, crossfade-menu, crossfade-active. **Rationale:** DIALOG, TV above.

### A43 · Server management

**Current (OBSERVED in source):** Saved server selection and Add Server exist in Account; replacement and pending-write confirmation are separate states. Source: `SignOutSheet.kt; MainActivity.kt; TvAccountSignOut.kt`.

**Phone target:** Grouped account server list with current marker, Add Server entry, clear replacement confirmation and preserved pending writes.

**TV target:** Full-screen list and entry/confirmation flows with safe initial focus.

**States:** saved-servers, add, replace, pending-signout, unknown-signout, failed-signout. **Rationale:** DIALOG, TV above.

### A44 · Connection settings and diagnostics

**Current (OBSERVED in file inventory):** The advanced connection/diagnostics sections named in the brief are absent from baseline `dab11e7e`; the named files belong to pending connection work. Current MainActivity/TvConnectScreen forms can be captured now. This group is dependency-absent until integration, then requires a new census and baseline.

**Phone target:** After integration, inspect the actual settings and diagnostics and apply grouped Material rows with concise policy explanation, readable results and cause-local recovery. Capture before restyling.

**TV target:** Large grouped TV settings and focused actions; avoid phone widget reuse.

**States:** settings, diagnostics, failure, recovery. **Rationale:** LIST, TV above.

### A45 · Android system media controls

**Current (OBSERVED in source):** Android media session exposes system notification/lockscreen controls; widget is a separate Android-only surface. Source: `DulcetPlaybackWidget.kt; shared playback service`.

**Phone target:** Keep platform-standard media controls and coherent artwork/title/action semantics; fit widget sizes and empty/reconnect state without exposing diagnostics.

**TV target:** TV system Now Playing card is owned by the OS; app playback still needs session metadata.

**States:** notification, lockscreen, widget-playing, widget-empty, system-now-playing. **Rationale:** M3, TV above.

## Platform differences and capture contract

Android TV currently has no Discover, Wishlist or standalone playlist editor/rename implementation;
these are platform absences, not captured placeholders. Its downloads remain supported. Phone uses
one bottom playback surface whereas TV has a separate playback activity; neither may be claimed
as a screenshot of the other. Ranked and recent destinations currently appear as Albums sort modes,
which is an architectural baseline mismatch to correct after critique, not three finished pages.

All automated writes use local disposable fixtures. General tour uses seeded Navidrome; Discover,
Wishlist and personal-collection coverage requires the separate Dulcet fixture. A Navidrome shot
cannot prove a Dulcet-only route. Capture labels record source, variant, foreground and destination.
The canonical variants are phone-light (1.0), phone-dark (1.0), phone-large (light, 2.0), and TV at
1920×1080. Captured JPEGs are at most 1000px long edge, quality 55, accompanied by accessibility
bounds/state XML when using DesignTourShots. Review sheets label each variant and missing state;
no UNREACHED image can become an eligible capture by renaming it.

`tools/design-tour-android` extends the production-navigation tours. `tools/android-restyle-review`
packages captures and enumerates every missing page/state/variant. Each batch has at most eight
sheets. Missing routes, fixture prerequisites, runtime failures and platform absences are kept
separate. The owning review process critiques every page and later grants implementation sign-off.
Synthetic tones and placeholder artwork establish layout and navigation only, not production art
variety, streaming performance or real-library behavior. No approval is implied by a green tour.
