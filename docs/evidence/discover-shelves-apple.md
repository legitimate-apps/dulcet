# Discover shelves in the Apple shells

This implements Task 3 of the [Discover shelves plan](../superpowers/plans/2026-10-27-discover-shelves.md) against its frozen core interface. Discover remains on the account's existing advertised OpenSubsonic extensions. No second authentication, HTTP, artwork or acquisition path is introduced. Platform support cells are unchanged pending integration evidence.

## Surfaces and navigation

- iPhone uses the five music destinations in order: Listen Now, Discover, Library, Browse, Search when the account advertises both Discover and My Library. Connection is available from a toolbar sheet in that window class. Accounts without Discover retain their existing navigation.
- iPad uses the existing sidebar at regular width and the phone tab shell at compact width. Discover paths, loaded list models, submitted playlist query and scroll targets live in the account-scoped presentation store, across shell reconstruction.
- Mac uses its native sidebar, detail navigation and full-window player bar. Music rows and wish actions are native buttons with separate labels and keyboard activation.
- TV excludes Discover navigation and never opens a Discover client. Its application and tests still compile with the additive Swift protocol.

Home presents Trending on ListenBrainz with Artists, Albums and Songs subrows, then New releases, Artists like yours, Public playlists and six Recent wishes. Native horizontal preview rows retain server order; each shelf owns its loading, empty, error, retry and cached-age presentation. Empty success never stands in for failure. Cached answers retain rows, fetched time and source age alongside a refresh failure. A retry deadline has visible explanatory copy. Credits name MusicBrainz and ListenBrainz, and suggestions separately credit ListenBrainz.

See all appears only with a cursor. Full shelf and public playlist lists, and individual public playlist pages, grow only through explicit Load more. Playlist entry identity is its one-based position, with positions visible and duplicate recordings preserved. Playlist summaries have no wish or bulk-play action. A missing recording MBID has visible neutral metadata copy and no wish action.

Wish commands use the original item retained by the Objective-C adapter, with the exact shelf or playlist source. Saved wishes have readable state text; pending and permanent failure retain Waiting to sync and Not saved. Recent wishes open Library > Wishlist and use the stable row key to reveal the chosen row. Held items use an unambiguous scoped local reference; a held album also requires known completeness. Other artist/album rows reuse the existing Discover pages. Songs never reinterpret an external recording identifier as a library identifier.

Cursor expiry starts a new null-cursor request and replaces the entire answer on success. A failed replacement retains existing rows. Query edits do not change a public playlist page's identity until explicit submission; a failed new query labels the retained previous answer. Selection generations fence late callbacks, operations cancel on departure/replacement, and reader reset clears retained navigation.

The shared native navigation helper preserves already displayed parent routes when a same-context path changes. This lets two successive Back actions pop playlist to list to home, including when a page's appearance precedes its path request. Account and destination changes still clear these acknowledgements. Explicit accessibility containment on list and recent-wish groups preserves child query and action identifiers on macOS.

## Proof entry points

`tools/ci/run-discover-wishlist-proofs --shelves` selects the step-3 native cases within the existing app proof suites. It requires the exact test names, observed platform identity and revision-bound shelf effect receipts. Arming a scenario alone is insufficient. The disposable fixture supplies a positively observed Navidrome catalog control, mixed, empty, stale and unavailable shelf answers, 24-row paging, duplicate playlist entries and missing recording metadata.

Hosted iPhone preparation and execution, with the existing fixture prerequisites and hosted runner admission:

```sh
tools/ci/run-discover-wishlist-proofs --shelves --build-for-testing iphone
DULCET_DISCOVER_PREBUILT=true tools/ci/with-discover-wishlist-fixture \
  tools/ci/run-discover-wishlist-proofs --shelves iphone
```

The iPhone cases are `testIPhoneDiscoverShelvesLargestTextPagesAndSources` and `testDiscoverShelvesEmptyCachedFailureAndNavidrome` in `DulcetDiscoverWishlistUITests`. They exercise largest text, title-specific VoiceOver labels, mixed shelves, all three pagination surfaces, persisted source values, duplicates, unwishable missing metadata and the positive Navidrome control.

Hosted iPad preparation and execution:

```sh
tools/ci/run-discover-wishlist-proofs --shelves --build-for-testing ipad
DULCET_DISCOVER_PREBUILT=true tools/ci/with-discover-wishlist-fixture \
  tools/ci/run-discover-wishlist-proofs --shelves ipad
```

The iPad cases are `testIPadDiscoverShelvesSidebarCompactResizeAndBack` and `testDiscoverShelvesEmptyCachedFailureAndNavidrome` in `DulcetDiscoverWishlistIPadUITests`. The former requires an actual sidebar, native corner resize to at most 600 points, compact tabs, the retained playlist tail, and Back to the public playlist list. Rotation alone cannot satisfy it.

Mac execution uses the same fixture:

```sh
tools/ci/with-discover-wishlist-fixture tools/ci/run-discover-wishlist-proofs --shelves mac
```

Its three cases are `testDiscoverShelvesMixedPagingAndShelfWish`, `testDiscoverPublicPlaylistPositionsWishKeyboardAndResize` and `testDiscoverShelvesEmptyFailureCachedVariantsAndNavidrome` in `DulcetMacDiscoverWishlistAppTest`. Captures are genuine native view renders; vibrant materials are not represented by that capture method. They accompany accessibility and server assertions, rather than replacing them.

## Verification

Observed on the final implementation tree:

| Check | Result |
| --- | --- |
| DulcetMac, DulcetiOS, DulcetTV build for testing | All three passed; generic destinations for iOS and TV |
| DulcetKit package tests, serial | 434 passed, 0 failed, 0 skipped, from finalized xcresult summary; JUnit also records 434 |
| Native Mac shelf proofs | 3 passed, 0 failed, 0 skipped; finalized result bundle and fixture effect validation |
| XCUITest source type check | Clean for every covered UI test directory |
| Proof runner self-test | 20 passed |
| Pinned XcodeGen regeneration self-test | Passed with XcodeGen 2.46.0 |
| Parity gate | 44 feature rows passed |
| CI policy | 13 workflow files passed |

The package result includes eight new shelf presentation tests and the shared navigation acknowledgement regression. It ran from fresh internal-disk DerivedData, which was removed after the run. The package bundle, summary and JUnit are retained by the Apple build leg as `final-tree-package-tests.xcresult`, `final-tree-package-summary.json` and `final-tree-package-tests.xml`.

The accepted Mac run is `build/discover-proofs/macos/20261027T050701Z`. Its `counts.json`, `validated-report.json`, `proof.xcresult`, `junit.xml`, `captures/`, `fixture-requests.json` and `fixture-state.json` are local evidence artifacts. The invocation records the preceding committed head `79057273`; the working implementation tested in that run includes the final navigation, accessibility and presentation fixes committed with this evidence. No source edits followed the accepted run.

The native result lines are:

```text
DISCOVER_SHELVES_MAC mixed=true shelfPaging=24 shelfSource=trendingArtists
DISCOVER_SHELVES_MAC playlistPositions=24 duplicates=true missingMbidUnwishable=true keyboard=true resize=true playlistSource=true recentWishes=true
DISCOVER_SHELVES_MAC empty=true cachedFailure=true uncachedFailure=true variants=light,dark,largest navidromeHidden=true featureCalls=0
```

An Opus 5.5 medium visual review of the revised playlist and dark cached-failure renders returned PASS with no remaining visual blocker. It confirmed readable saved-wish state, visible duplicate positions, missing-recording explanation and a reason for disabled Retry. That review does not establish toolbar material contrast, text scaling or VoiceOver behavior. The native Mac variant loop requests largest text but its view-render capture does not prove effective Mac text scaling.

No local iOS simulator execution or hosted dispatch is claimed. The iPhone and iPad proofs are prepared and type-checked; their runtime results belong to the integration run. No locked-core interface change was needed.
