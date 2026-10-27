# Discover and Wishlist on Apple TV

The TV shell enables the existing DulcetKit Discover source, value adapters and presentation models from the [Apple shelf implementation](discover-shelves-apple.md). It uses the account's advertised OpenSubsonic extensions and existing authenticated reader. There is no new core interface or networking path.

## Screens and remote behavior

Discover appears in the TV section bar only when the account advertises discovery. It presents Trending on ListenBrainz (artists, albums and songs), New releases, Artists like yours, Public playlists and Recent wishes. Each shelf keeps its shared loading, empty, cached-age, failure and Retry presentation. Horizontal previews use 440-point cards with 120-point artwork on TV; other platforms keep their existing dimensions. Cards and actions use native borderless focus effects, with vertical room for focus scaling.

See all opens the existing paged shelf or public playlist list. Load more is an explicit remote action. Public playlist pages preserve entry positions, duplicate recordings and the neutral missing-recording explanation. Missing recording metadata has no wish action. Wishes are explicit title-specific buttons next to the item, and preserve the original shelf or playlist source. Saved, pending and failed states use the same presentation as the other Apple shells.

Artist pages show their discography; album pages show edition selection and tracks. Search offers Your server and Everywhere, using the existing TV text-field style and system keyboard. Metadata pages opened by search remain in Search's navigation stack. Held items use the existing scoped local references and album-completeness checks when opening the library or player.

Library offers Wishlist with kind/state filters, history, Refresh, pending/failure text, arrival links and explicit Remove wish actions. The link is present in both the personal-library root and the ordinary reader root. Recent wishes open that same destination and retain the selected row key.

The TV shell combines reader, Discover and search routes in its native navigation stack. Discover reuses the existing path acknowledgement/replay helper. Menu pops a pushed page before it returns focus to the selected top-level section. Account-reader replacement clears Discover and search paths. The hosted proof asserts focus restoration after returning from an artist and from a playlist.

An account without the extensions has no Discover, Everywhere scope or Wishlist. Source construction itself sends nothing; the existing shared capability negotiation gates all feature requests.

## Hosted proof

`DulcetTVDiscoverWishlistUITests` contains exactly two remote-driven cases:

- `testTVDiscoverShelvesSearchWishScanAndRemoveByRemote`: opens Discover, wishes from a shelf, opens an artist discography, pages a shelf to its final row, opens an album, opens and pages a public playlist, preserves duplicate positions and unwishable missing metadata, checks authoritative wish sources, searches Everywhere through the system keyboard, wishes for a recording, sees it in Wishlist, triggers a real fixture scan, sees Arrived and removes that wish.
- `testTVDiscoverStatesAndNavidromeByRemote`: observes empty, cached and failed shelf effects, then launches against the disposable Navidrome control, positively observes a catalog and checks that Discover, Everywhere and Wishlist are absent and feature requests are zero.

Controls are activated with remote focus and Select. Menu navigates Back. No app destination or search query is injected. Fixture credentials are supplied through the existing DEBUG account hook, and runner environment values accept `TEST_RUNNER_*`. Genuine native JPEG captures accompany the test and server receipts.

The new `tvos` proof-runner mode rejects local execution and supports compile-only preparation. Its validator requires the exact two passing cases, observed TV identity, revision-bound shelf and completed scan effects, the complete wish-flow acknowledgement and a positive Navidrome control. Zero tests, an armed-only effect, missing receipts, a wrong device and failed cases cannot pass. The result bundle must finalize.

The `apple-ci` job `discover-wishlist-tvos` runs whenever the planner requests Discover, including a full dispatch. It waits for the iPhone and iPad proof jobs to preserve the four-hosted-runner envelope. The aggregate requires its result, downloads its JUnit and includes that directory in parity-evidence validation. No feature support cells are promoted here.

After the job provisions the pinned fixture, FFmpeg and selected Xcode, its exact preparation and proof commands are:

```sh
tools/run-gradle-exclusive --no-daemon --max-workers=2 \
  :server:installDist :core:linkDebugFrameworkTvosSimulatorArm64
DULCET_DISCOVER_PROOF_OUTPUT="$RUNNER_TEMP/discover-tvos/native" \
  tools/ci/run-discover-wishlist-proofs tvos --build-for-testing
DULCET_DISCOVER_PROOF_OUTPUT="$RUNNER_TEMP/discover-tvos/native" \
DULCET_DISCOVER_PREBUILT=true \
  tools/ci/with-discover-wishlist-fixture \
  --evidence "$RUNNER_TEMP/discover-tvos/fixture" -- \
  tools/ci/run-discover-wishlist-proofs tvos
```

The output is uploaded as `discover-wishlist-tvos-RUN-ATTEMPT`, including `native/proof.xcresult`, `native/counts.json`, `native/validated-report.json`, `native/junit.xml`, fixture receipts and `native/captures/`. Parity XML is uploaded separately as `dulcet-apple-parity-evidence-discover-wishlist-tvos-RUN-ATTEMPT`.

Expected successful result lines, not locally observed runtime results:

```text
DISCOVER_TV shelfPaging=24 album=true playlistPositions=24 sources=shelf,playlist searchEverywhere=true scanArrival=true removed=true remote=true
DISCOVER_TV empty=true cachedFailure=true navidromeHidden=true featureCalls=0 remote=true
```

## Verification boundary

OBSERVED: the serial DulcetKit package run finalized 434 passed, 0 failed and 0 skipped in its xcresult summary and exported JUnit. DulcetTV, DulcetiOS and DulcetMac built for testing with explicit DerivedData paths and serial test configuration. Every XCUITest source directory type-checks. Pinned XcodeGen regeneration and its self-test pass with 2.46.0. The proof runner has 25 passing self-tests, including TV missing/zero/failed/wrong-device and armed-only controls. Parity validates 44 rows and CI policy validates 13 workflows.

TV and iOS UI execution is reserved for hosted CI. No local TV or iOS simulator run, hosted dispatch or runtime result is claimed. Remote reachability, visual focus and end-to-end TV behavior remain unproven until that hosted proof succeeds. No locked-core interface request was needed.
