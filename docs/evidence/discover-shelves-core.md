# Discover shelves core handoff

The [step-3 plan](../superpowers/plans/2026-10-27-discover-shelves.md) is the locked contract for the Apple and Android shell builders. This handoff records core behavior and verification; it claims no new native UI support and promotes no FEATURES cells.

## Delivered interface

`DiscoverClient`, `AndroidDiscoverClient` and `AppleDiscoverClient` add `refreshHome`, `shelf(id,cursor,completion)`, `playlists(query,cursor,completion)` and `playlist(mbid,cursor,completion)`. The existing publication gains nullable `home`. See the plan for complete types, constructor fields, copy, accessibility identifiers, routing and source-aware wishing.

New common values: `DiscoverShelfPage`, `DiscoverShelfState`, `DiscoverHome`, `DiscoverPlaylistSummary`, `DiscoverPlaylistList`, `DiscoverPlaylistEntry`, `DiscoverPlaylist`. New fixed Apple DTOs use the same names prefixed `Apple`, with separate nullable value/error result objects for each one-shot read. The [generated Objective-C declarations](discover-client-objc-signatures.h.txt) come from `:core:linkDebugFrameworkMacosArm64`. Compile against the full framework, and copy DTOs into Swift structs. Safe count/index accessors preserve nullable numbers and return null outside bounds. The existing Android shell source delegates the new interface; its search presentation double has compatibility implementations only, with no new shell UI.

Home publishes six shelf states in this order: trendingArtists, trendingAlbums, trendingSongs, newReleases, similarArtists, publicPlaylists. Each initial read asks for 12 rows; list/page operations ask for 20. Public playlists have summaries instead of music items. Existing default Wishlist snapshots and the durable outbox overlay supply six recent wishes, sorted by add/action time and stable key. A separate default Wishlist reader prevents a Library filter from narrowing home wishes.

Each visited read uses the existing account cache and LRU limits. Length-delimited keys distinguish opaque IDs, null/empty cursors, page size and playlist query. Reopening home publishes cached shelves before revalidation. A source failure or offline result retains a cached page with its original received/fetched timestamps, source age, `fromDeviceCache=true` and a typed `refreshError`. Home also puts that failure on its shelf state. No cached page means Failed, never an empty list. Storage failure during home hydration publishes settled storage errors instead of reaching the reader exception backstop. A red injected-cache-read test reproduced the missing-home failure before this fix.

Playlist entry positions and duplicate recordings remain intact. Missing MBIDs remain visible with no target and wishable=false. No client matching is added. Shelf/playlist wish commands are the existing durable `add` with `WishSource("shelf", shelfId)` or `WishSource("playlist", playlistId)`. Conformance reads the acknowledged server wishlist and checks these exact persisted sources.

All requests share existing reader admission, account identity authority, retirement, server backoff and credentials. Facade calls enter the reader owner, and publications/completions reach main through the existing delivery fence. Cancellation is one-shot, late results cannot cache or publish across retirement, and a newly requested home cancels older home shelf work. The new reads share the existing monotonic 429 deadline, including through a wall-clock rollback. No auth, playback, artwork-validation or outbox-delivery mechanism is replaced.

## Server contracts and approved transport

OBSERVED in `server/api/DiscoverApi.kt`, `server/v1/Discover.kt` and the extension documentation:

| Read | OpenSubsonic extension | Dulcet API resource |
|---|---|---|
| Six shelves | `getDulcetDiscoverShelf`, id/size/cursor | `/discover/shelves/{shelfId}`, limit/cursor |
| Public playlist list/search | `searchDulcetDiscoverPlaylists`, query/size/cursor | `/discover/playlists`, q/limit/cursor |
| Public playlist page | `getDulcetDiscoverPlaylist`, mbid/size/cursor | `/discover/playlists/{mbid}`, limit/cursor |

The server exposes all three through both contracts. Apps use advertised `dulcetDiscover` v1 and independently gate Wishlist v1 through `/rest`, preserving existing authentication. This is the approved transport decision from 2026-10-23 (design §5, main spec §30.3). Dulcet API adoption belongs to a separate whole-app lane; shells add no login or networking path.

Navidrome control first requires a successful login, non-empty catalog and an observed baseline request log. After that positive witness, production home and explicit shelf/list/playlist calls produce no Discover/Wishlist endpoint request and expose no shelf. The standard shared extension response supplies the confirmed absence.

## Fixture and conformance

The [fixture documentation](../../tools/discover-wishlist-fixtures/README.md) describes recorded source answers and acknowledged controls. Charts, releases and playlist summaries have 24 rows. Playlist pages have 24 positions, duplicated recording at 1/2 and a missing-MBID recording at 3. A first page of 20 continues to four rows. The ordinary account has no similar-artist signal, so its empty shelf triggers no similarity lookup.

- `shelves-mixed`: non-empty trending artists/new releases/public playlists, stale trending albums, unavailable trending songs and empty similar artists.
- `shelves-empty`: successful explicit empty arrays, without nextCursor.
- `shelves-stale`: rows with stale=true, original age 7200 seconds and fetched time.
- `shelves-unavailable`: typed upstream-unavailable failure, never empty success.

`DiscoverShelvesConformanceTest.shelvesPlaylistsSourcesCacheAndNavidromeControl` executes actual account-owned reads through the disposable server, including paging, both persisted WishSource values, home wishes, all four scenario states, effect receipts, retained cache and the healthy Navidrome control. The fixture wrapper confirms all five owned children reaped and its temporary root deleted. All automated requests target this local fixture; no upstream service or installed personal server is contacted.

## Observed checks

Final counts are read from JUnit XML, with no skipped tests. The implementation commit is `b614f2e7`; later documentation commits do not change the tested sources.

| Check | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| `:core:jvmTest` | 1319 | 0 | 0 | 0 |
| `:core:testAndroidHostTest` | 1635 | 0 | 0 | 0 |
| `:core:macosArm64Test` | 1433 | 0 | 0 | 0 |
| `:core-conformance:jvmTest`, new shelf conformance class | 1 | 0 | 0 | 0 |
| `tools/test-discover-wishlist-fixture` | 16 | 0 | 0 | 0 |

The new common `DiscoverShelvesTest` has eight tests. Android's existing `AndroidDiscoverTest` has ten, including shelf/playlist/home main delivery and cancellation. Apple facade/DTO tests add copied lists, nullable metadata, main delivery and cancellation checks. These run on macOS native; iOS is compile evidence only in this core part.

`:core:allMetadataJar`, `:core:bundleAndroidMainAar`, `:core:licensee`, `:core:compileTestKotlinIosSimulatorArm64` and `:core:linkDebugFrameworkMacosArm64` pass. Parity and CI policy checks pass. None of these compile/package tasks has a test count. Linux Android soak is dispatched after pushing, with task `:core:testAndroidHostTest`, filters `*AndroidDiscoverTest,*DiscoverShelvesTest` and 20 repetitions; its outcome is not claimed here. Native shell proofs and final platform support promotion remain integration work.

The combined native shells, merged-tree checks and hosted proof conditions are recorded in
[Discover shelves integration](discover-shelves-integration.md). The locked interface is unchanged.
