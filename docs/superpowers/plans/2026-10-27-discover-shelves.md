# Discover Shelves Implementation Plan

> **For agentic workers:** The core contract below is the interface for the Apple and Android builders. Commit each green task. Shell builders consume the interface and generated header without reading the core implementation. Tasks use checkbox syntax. The integrator owns platform evidence and FEATURES promotion.

**Goal:** Design step 3: a Discover destination with Trending, New releases, Artists like yours, Public playlists and recent wishes on iPhone, iPad, Mac and Android phone. Artist/album pages, Wishlist, matching and the durable outbox already exist. TV is step 4.

**Base:** `a7047e4c`, after #371. **Architecture:** Account-owned Kotlin read models, cache and wish outbox, fixed Objective-C facades and native SwiftUI/Compose shells. No new runtime dependency or schema migration.

**Sources:** [Approved design](../specs/2026-10-22-discover-and-wishlist-design.md) §§5-6; [step-2 plan](2026-10-22-discover-wishlist-clients.md); [Discover extension](../../dulcet-server/dulcet-discover-extension.md); [TRAPS](../../TRAPS.md) 4-5, 17-20, 29-32.

## Global constraints

- Outside lookups and matching belong to the server. Devices request server artwork IDs only. No previews, acquisition, upstream accounts or automatic external-link opening.
- Reuse the existing reader admission, account lifetime, identity authority, credentials, reconnect and retirement. IDs and cursors remain opaque. A failed answer never becomes empty success; preserve cached data with age. Wall clock for persisted timestamps, monotonic clock for durations and shared Retry-After deadlines.
- Discover v1 and Wishlist v1 are independent. Definitive Discover absence hides the destination and sends no Discover endpoint request. Wishlist absence hides recent wishes and wish actions. An individual failed shelf never revokes the capability. Navidrome keeps its existing navigation and traffic.
- Preserve existing held/partial/unknown matching and routing policy, server-only binary validation, credential redaction and safe explicit listen/buy links. No new image loading path.
- Credit: **Data from MusicBrainz and ListenBrainz**. Trending heading: **Trending on ListenBrainz**. Suggestions explain **Suggestions use ListenBrainz**.
- Native collections are copied across Objective-C as count/index accessors; callbacks are on main and return cancellation handles synchronously. No Kotlin exception, generic result, Flow or SQL entity crosses Swift.
- Automated runs target only the finite disposable fixture. Core proofs do not claim shell behavior. No local iOS simulator is needed for this core part. No FEATURES cells are promoted here.

## Protocol finding and scope decision

**OBSERVED:** Server `api/DiscoverApi.kt` and `v1/Discover.kt` expose the same six shelf IDs and playlists through both contracts. `/rest/getDulcetDiscoverShelf` uses `id`, `size`, `cursor`; public playlists return `dulcetDiscoverPlaylists.playlist[]`, other shelves `dulcetDiscover.item[]`. `/rest/searchDulcetDiscoverPlaylists` uses `query`, `size`, `cursor`. `/rest/getDulcetDiscoverPlaylist` uses `mbid`, `size`, `cursor`, with `summary` and `entries.entry[]`.

Dulcet API equivalents are `/discover/shelves/{shelfId}`, `/discover/playlists?q=`, `/discover/playlists/{mbid}`, with `limit`, `cursor` and JSON `items[]`; they require Bearer credentials. API discovery advertises availability, not authentication. The current app account transport has only OpenSubsonic username/password credentials; step 2 explicitly deferred API authentication and whole-app migration. The core delivered here uses the advertised OpenSubsonic extension, matching step 2 and Listen Now. **The request to select Dulcet API on discovery is unresolved pending a scope decision about API authentication/account migration.** Shell builders must not create a second login or networking path. The interfaces below do not depend on that choice.

## Product decisions

1. Show the five design sections in this order: Trending, New releases, Artists like yours, Public playlists, Recent wishes. Trending contains three separately titled subrows: Artists, Albums, Songs, in that order. All six server shelves have independent loading/error/empty state.
2. Home reads **12 rows per server shelf**. Preserve server order, including new-release preference ordering and unknown metadata. Show **See all** only when `nextCursor != null`. See all starts its own 20-row first page; subsequent pages use its exact cursor and 20 rows. No automatic prefetch or timed refresh. A detail list can grow only through explicit Load more. Never deduplicate playlist recordings: use one-based entry position as row identity within a playlist.
3. Home refreshes on open and explicit refresh, and the existing account reconnect revalidates an already opened home. Reopening first shows visited cached shelf pages, then each refreshed result. Existing account LRU limits remain 16 MiB JSON and 32 MiB artwork. There is no whole-library mirror or background upstream polling.
4. Six recent wishes come from the default existing owner-only wishlist snapshot plus pending outbox overlay. Sort by add/action time descending, stable key for ties. Do not fetch a new upstream recommendation for wishes. Home has its own default Wishlist reader so a Library filter cannot narrow Recent wishes. Route this section to Library > Wishlist; no new song-detail API is invented.
5. Empty successful shelves remain visible, with contextual copy below. No signal for similar artists is an honest empty result. Failed uncached reads show failure and Retry, never the empty message. Failed cached reads keep rows, age and the failure. Loading keeps old rows when available. Loading indicators settle per shelf.
6. Cached or stale pages show **Cached answer**, their fetched time/server age, and Offline when appropriate. Missing age says **Age unavailable**. Reuse step-2 monotonic elapsed-age presentation; do not infer freshness from a wall-clock rollback. `refreshError` is shown alongside cached content. Shared 429 backoff blocks all feature requests until its monotonic deadline; show the existing busy/retry countdown. Do not loop on errors.
7. There is no bulk playlist wish. Wish individual canonical artists/albums/songs with the existing outbox: `WishSource("shelf", shelfId)` or `WishSource("playlist", playlistId)`. Missing MBID means visible row with no wish action. Playlist summaries themselves are not wishable. Pending shows **Waiting to sync**, permanent refusal **Not saved**, matching step 2.
8. Album and artist rows route through the existing Discover artist/album pages using their `target.mbid`. A held item with an unambiguous scoped local reference uses the existing library route. Partial/unknown albums stay on the Discover page. Songs use the existing song action/detail treatment and explicit library references; do not turn an external recording MBID into a local song ID. Missing target/unknown kind has neutral information only. Recent wishes open Wishlist using stable row key; retain selected page/scroll through navigation and window changes.

## Locked core interface block

Package: `com.legitimateapps.dulcet.core`. Existing `DiscoverItem`, `DiscoverAge`, `DiscoverFailure`, `WishRow`, `WishSource`, `DiscoverResult<T>`, `DiscoverOperation`, registration and add/remove/reject contracts remain as in step 2. `DiscoverResult` is Value(value) or Failed(error). Every new common public model is hidden from Objective-C. All list fields below are immutable copied publications, not live stores.

```kotlin
data class DiscoverPlaylistSummary(val id: String, val title: String,
    val creator: String?, val trackCount: Int?)
data class DiscoverPlaylistEntry(val position: Int, val item: DiscoverItem)
data class DiscoverPlaylistList(val items: List<DiscoverPlaylistSummary>,
    val nextCursor: String?, val age: DiscoverAge, val refreshError: DiscoverFailure? = null)
data class DiscoverPlaylist(val summary: DiscoverPlaylistSummary,
    val entries: List<DiscoverPlaylistEntry>, val nextCursor: String?,
    val age: DiscoverAge, val refreshError: DiscoverFailure? = null)
data class DiscoverShelfPage(val id: String, val items: List<DiscoverItem>,
    val playlists: List<DiscoverPlaylistSummary>, val nextCursor: String?,
    val age: DiscoverAge, val refreshError: DiscoverFailure? = null)
data class DiscoverShelfState(val id: String, val page: DiscoverShelfPage?,
    val loading: Boolean, val error: DiscoverFailure?)
data class DiscoverHome(val shelves: List<DiscoverShelfState>,
    val recentWishes: List<WishRow>, val wishesLoading: Boolean,
    val wishesSettled: Boolean, val wishesError: DiscoverFailure?)
// Additive property on the existing DiscoverPublication:
val home: DiscoverHome? // null before refreshHome, default null in constructor

// Additive methods on DiscoverClient; AndroidDiscoverClient implements them:
fun refreshHome()
fun shelf(id: String, cursor: String?,
    completion: (DiscoverResult<DiscoverShelfPage>) -> Unit): DiscoverOperation
fun playlists(query: String, cursor: String?,
    completion: (DiscoverResult<DiscoverPlaylistList>) -> Unit): DiscoverOperation
fun playlist(mbid: String, cursor: String?,
    completion: (DiscoverResult<DiscoverPlaylist>) -> Unit): DiscoverOperation
// Existing wish command; use the item exactly as received:
fun add(item: DiscoverItem, source: WishSource,
    completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation
```

Home shelf IDs/order: `trendingArtists`, `trendingAlbums`, `trendingSongs`, `newReleases`, `similarArtists`, `publicPlaylists`. A shelf page contains items or playlist summaries, never both. Home is published through the existing `observe` registration and its monotonically increasing publication generation. An absent capability has no shelves. `refreshHome` does capability detection first and sends no feature read on confirmed absence; constructing/observing a facade remains lazy. Reuse the existing account startup capability detection for destination visibility, and mount/refresh home on selection only.

A successful empty list is distinct from null page, loading and error. A cached fallback is Value with `age.fromDeviceCache=true` and `refreshError`; Failed has no usable cached page for that exact read. Home exposes the same refresh error in its shelf state's `error`. Cursor expiry is Failed `cursor-expired`; a shell replaces the entire list by a new null-cursor request, never appends a replacement first page. Preserve existing rows if that replacement fails. Cancel page operations on departure, cursor/query replacement or account retirement; close subscriptions on teardown. New home refresh cancels its prior shelf work. The account identity fence also guards queued main-thread delivery.

**Apple:** `AppleDiscoverClient` gains `refreshHome()`, `shelf(id:cursor:completion:)`, `playlists(query:cursor:completion:)`, `playlist(mbid:cursor:completion:)`, returning `AppleLibraryReaderOperation`. Completion classes are `AppleDiscoverShelfPageResult`, `AppleDiscoverPlaylistListResult`, `AppleDiscoverPlaylistResult`, each with nullable `value`/`error`. Fixed DTOs have the same fields prefixed `Apple`; `AppleDiscoverPublication.home` is nullable. List accessors: Home `shelfCount/shelfAt`, `recentWishCount/recentWishAt`; ShelfPage `itemCount/itemAt`, `playlistCount/playlistAt`; PlaylistList `itemCount/itemAt`; Playlist `entryCount/entryAt`. Out-of-bounds returns null, nullable numbers remain boxed nullable numbers. Copy these into handwritten Swift structs and convert `AppleDiscoverItem` through the existing item conversion. Wish with the existing copied item and `AppleWishSource(type:"shelf"|"playlist", id:...)`. Use the generated header in `docs/evidence/discover-client-objc-signatures.h.txt`, not guessed Swift selectors.

**Android:** existing `CoreDiscoverWishlistSource`/`DiscoverWishlistSession` delegate the new methods; observe `publication.home`. No second coroutine-owned account, direct HTTP, SQL access or feature credential store. Shell test doubles implement the additive methods.

## Shared copy, states and accessibility identifiers

Use native headings, scroll containers, focus and accessibility semantics. Labels include title, artist when present, availability and pending state. Wish is a separately named button, **Wish for {title}**, distinct from favourite or Add to My Library. No text-only color state. Support largest accessibility text, VoiceOver/TalkBack and keyboard activation. Decorative artwork is hidden from accessibility.

| Surface | Identifier | Copy or behavior |
|---|---|---|
| Destination | `discover.destination` | Discover; fifth phone destination ordered Listen Now, Discover, Library, Browse, Search; sidebar item on iPad/Mac |
| Home | `discover.home` | Discover heading; native refresh action `discover.home.refresh` |
| Shelf | `discover.shelf.{id}` | Trending on ListenBrainz with Artists/Albums/Songs subheadings; New releases; Artists like yours; Public playlists |
| Shelf states | `discover.shelf.{id}.loading`, `.empty`, `.error`, `.retry`, `.age` | Loading; empty copy below; error retains old rows and has Retry; busy uses step-2 countdown |
| Shelf row | `discover.shelf.{id}.item.{opaqueId}` | Existing item availability and action treatment |
| Shelf playlist | `discover.shelf.publicPlaylists.playlist.{playlistId}` | Title, creator if present, track count if known |
| See all | `discover.shelf.{id}.all` | See all; appears only with a cursor |
| Shelf list | `discover.shelfList.{id}` | Same heading/copy, page operations; Load more `discover.shelfList.{id}.more` |
| Public playlist list | `discover.playlists` | Public playlists; optional native query field `discover.playlists.query`, submit explicitly; empty query lists public playlists |
| Playlist list row | `discover.playlists.item.{playlistId}` | Open public playlist page |
| Playlist list pagination | `discover.playlists.more` | Load more, only with cursor; distinct loading/error/empty/retry controls |
| Playlist page | `discover.playlist.{playlistId}` | Title, creator, count; ordered entries, no bulk play or bulk wish |
| Playlist entry | `discover.playlist.{playlistId}.entry.{position}` | Preserve duplicates and source position |
| Playlist page states | `discover.playlist.{playlistId}.loading`, `.empty`, `.error`, `.retry`, `.age`, `.more` | Load more only with cursor; retain entries on failure |
| Wish action | append `.wish` to row identifier | Wish for {title}; source shelf ID or playlist ID as above |
| Recent wishes | `discover.recentWishes` | Recent wishes; `discover.recentWishes.item.{rowKey}`, `.all` opens Wishlist |
| Recent wishes states | `discover.recentWishes.loading`, `.empty`, `.error`, `.retry` | Existing wishlist loading/error state; hide whole section if Wishlist absent |
| Credits | `discover.credit`, `discover.suggestionsCredit` | Data from MusicBrainz and ListenBrainz; Suggestions use ListenBrainz |

Opaque IDs are interpolated as received, never parsed or case-normalized; use native test tag/identifier APIs without CSS selectors. `position` is the only playlist-entry key and is one-based. Playlist summaries use their playlist ID. Error controls are separate from empty controls.

Empty copy: trending **No trending artists/albums/songs right now.**; new releases **No new releases right now.**; similar artists **Play or favourite music on your server to find artists like yours.**; public playlists **No public playlists found.**; playlist entries **This playlist has no songs.**; recent wishes **Your recent wishes will appear here.** Uncached source failure: **Discovery is temporarily unavailable.** Offline without cached data: **Connect to your server to explore music.** Permanent auth/permission/identity failures reuse existing account treatment; no autonomous retry. Unknown metadata remains visible with neutral controls.

## Builder tasks

### Task 1: Core reads and account home (core builder)

- [ ] Add all six shelf reads and public playlist list/page read models, wire adapters and typed failures.
- [ ] Add visited-read cache entries with cursor/size/query isolation, last-good fallback and original age; reuse existing admission, retirement and backoff.
- [ ] Publish independent home shelves plus six recent wishes from default owner snapshot/outbox; preserve Library filters.
- [ ] Test opaque cursors, empty versus failed, stale/offline fallback, shared 429 deadline, late cancellation/identity, ordering, duplicates, missing MBIDs and both WishSource values.
- [ ] Resolve protocol scope above before claiming API selection is complete.

### Task 2: Facades and fixture (core builder)

- [ ] Extend Android and Objective-C facades, safe DTOs and main/cancel/close tests. Save compiler-generated signatures and core handoff evidence.
- [ ] Record 24-row chart/release/playlist answers with paginated entries, duplicate recordings and missing-MBID recording. Similar shelf has a no-signal empty control.
- [ ] Add `shelves-mixed`, `shelves-empty`, `shelves-stale`, `shelves-unavailable` controls and revision-bound effect receipts. Require the fault actually fired.
- [ ] Conformance uses real disposable server reads/outbox/cache and a positively observed healthy Navidrome control, with zero feature requests there.

### Task 3: Apple shells (Apple builder, independent of Task 4)

- [ ] Extend DulcetKit structs/source protocol, core-to-Swift copies, production source and test doubles strictly from the locked interface. No transport logic in Swift.
- [ ] Add top-level Discover destination on iPhone/iPad/Mac only, capability-gated, preserving existing five-destination order, sidebar state, account teardown and player bar.
- [ ] Build home shelf components, full shelf list, public playlist list/page and recent wishes. Implement the shared states, copy, IDs and source-aware wishing. Route existing artist/album/library actions; cancel obsolete detail/query work and retain scroll/selection through Back/resize.
- [ ] Add presentation tests for independent states, copied nullable metadata, source IDs, duplicate playlist positions, unavailable recording action, partial albums, unknown kind, offline and cursor replacement. Compile the real generated framework integration, not only protocol doubles.
- [ ] Prepare iPhone, iPad and Mac native UI proofs before claiming parity. Reuse fixture controls/effects, existing consent and account proof harness. iPad tests use actual sidebar/compact-window transition; Mac tests exercise keyboard navigation and resize; iPhone exercises largest text/VoiceOver labels. Do not promote FEATURES here.

### Task 4: Android phone shell (Android builder)

- [ ] Consume the additive methods through the existing DiscoverWishlistSession/source. Update affected doubles and add phone destination without TV wiring.
- [ ] Compose home, full shelf list and public playlist list/page using shared states/copy/IDs. Show pending wishes from publications; source-aware adds use the existing facade. Preserve duplicate entries by position, library/Discover routes and player bar.
- [ ] Exercise TalkBack labels, keyboard/focus, largest font and narrow/wide phone layout, Back/rotation and disposal/account change. No direct network or image URL path.
- [ ] Host-test state/action/routing seams and dispatch changed Android host test classes to `android-test-soak`, 20 repetitions, after pushing. Report run ID without waiting inside the builder.
- [ ] Add emulator proof using real disposable server, with non-empty controls and acknowledged mixed-state faults. No FEATURES promotion here.

### Task 5: Proofs and integration (integrator)

- [ ] Reconcile both shell summaries, compile all integrations and run core gates once on final tree. Resolve the protocol decision and amend this contract explicitly if it changes.
- [ ] Wire/execute hosted Apple and Android proofs for each of the four surfaces. Non-empty shelves must include artist/album routing, shelf wishes, playlist page duplicate/missing-MBID rows and playlist wishes. Show cache age, failure with retained content, empty copy, pending acknowledgement, offline/reconnect and healthy Navidrome absence.
- [ ] Assert fixture scenario revision/effect, account identity, platform/window class and actual test counts. Preserve artifacts with source SHA; compile-only and core tests are not UI evidence.
- [ ] Promote only the proven `discover.shelves` and public-playlist portion of `discover.pages` cells, preserving existing search/pages/wishlist proof and TV deferrals. Update evidence and main-spec §28 for the final user-facing behavior.
- [ ] Run parity and CI policy checks. Open/merge/release only under the integration brief's authority.

## Core checks

```sh
./gradlew :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest \
  :core:bundleAndroidMainAar :core:licensee \
  :core:compileTestKotlinIosSimulatorArm64 :core:macosArm64Test
python3 tools/parity_gate.py
python3 tools/verify_ci_policy.py
# Build server fixture first, then run inside with-discover-wishlist-fixture:
./gradlew -Pdulcet.discoverWishlistConformance :core-conformance:jvmTest \
  --tests '*DiscoverShelvesConformanceTest'
```

Read executed counts from `core/build/test-results/<task>/*.xml`, including failures/errors/skips. Native compile and AAR tasks have no test counts. Header comes from `:core:linkDebugFrameworkMacosArm64`. Core part pushes `feat/discover-shelves`; it opens no PR, dispatches no Apple CI, watches no CI and sends no notifications.
