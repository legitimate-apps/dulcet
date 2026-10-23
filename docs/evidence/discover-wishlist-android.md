# Android phone Discover and Wishlist, step 2

Scope: plan Tasks 16–19 on the accepted core interface. REST extensions only, existing account
credentials, no OAuth, shelves or Android TV feature UI. No core changes or locked-core-interface
amendments. `DiscoverWishlistSource`/`DiscoverWishlistSession` compose `AndroidDiscoverClient`;
`LibrarySession` owns their account lifetime. No parallel transport, outbox or identity fence.

## Test-first evidence

| Task | Red | Green |
| --- | --- | --- |
| 16 | Session tests fail compilation before the source/session exist. | 4 lifecycle + 3 real SQL/account tests in each phone variant; 1 TV guard. |
| 17 | Named Search/page tests fail compilation before composables exist; real device baseline fails missing Everywhere. | 6 `PhoneDiscoverTest` cases in each variant. |
| 18 | Fixture-backed build fails unresolved `PhoneWishlist`/`PhoneWishlistEntry`. | 7 `PhoneWishlistTest` cases and 3 production-reader integration cases in each variant; Task 19 adds an eighth Wishlist test for contextual refresh failure. |
| 19 | Five pre-feature device methods execute and fail missing Everywhere. Native-graphics navigation test reproduces Library wrapping onto two lines at system scale 2.0. | Final results below. |

The SQL tests positively establish cache/intent before cache clearing or sign-out, verify independent
same-username account namespaces, and preserve the other owner. Presentation tests use explicit fake
publications; they do not claim real persistence or a server mutation. Production integration tests
use `AndroidLibraryReader`, SQL, `CoreDiscoverWishlistSource` and the disposable REST server, with
positive extension/Wishlist requests, actual server wishes, scan completion, correction back to
Wanted, and independently observed My Library membership before and after correction/removal.

## Full checks

Final ordinary suites: DEV 261, PROD 261, TV 95, with 0 failures, 0 errors and 0 skips.
DEV/PROD phone APKs, DEV instrumentation APK and TV APK all assemble.
Final emulator XML: 10 tests, 0 failures, 0 errors and 0 skips. The final fixture cold-started in
12.424 seconds. Its cleanup record confirms all five owned child PIDs reaped and its disposable
root deleted. The owned emulator was stopped by its recorded PID after the proof.

Local artifact root: `/Volumes/Crucial X8/dulcet-scratch/legs/discover-android/evidence/`.

- `full-final2-xml/`: final ordinary JUnit XML and per-task counts.
- `task18-integration2-xml/`: 3 DEV and 3 PROD production-reader integration cases.
- `task19-full4/`: ten phase-specific JUnit XML files, requests, fault acknowledgements, process
  evidence and 28 compressed JPEG captures. Light/dark/largest captures each include Search,
  artist, album, pending wish and Wishlist.
- `task19-full-fixture4/{ready,cleanup}.json`: final fixture start and cleanup records.
- `task19-fixture-full.xml`: nine fixture self-tests, including owned-PID cleanup on success,
  failure and signal, preserving an unrelated sentinel.
- `visual-final2.txt`: Opus medium's final review of five compressed captures, confirming whole
  navigation labels, separated Search status, visible pending copy and readable Wishlist.
- `android-final-evidence.json`: XML totals, Navidrome endpoint counts and fixture cleanup.

Navidrome's final phase observed nine `getAlbumList2` requests before asserting zero
Discover/Wishlist requests and absent feature controls. Process evidence observes old PID 4664,
new PID 4773, pending intent restored. The old instrumented process had already exited when the
runner checked it; no claim is made that explicit cleanup killed a still-live app process.

Gradle commands are run through a caller-owned build-lease wrapper. Final full run:

```sh
./gradlew :android:app:testDevDebugUnitTest --rerun :android:app:testProdDebugUnitTest --rerun \
  :android:tv:testDebugUnitTest --rerun :android:app:assembleDevDebug \
  :android:app:assembleProdDebug :android:app:assembleDevDebugAndroidTest :android:tv:assembleDebug
```

Real-server host integration is separate and excluded from the ordinary suite:

```sh
tools/ci/with-discover-wishlist-fixture -- tools/run-gradle-exclusive \
  :android:app:testDevDebugUnitTest --rerun :android:app:testProdDebugUnitTest --rerun \
  -Pdulcet.discoverWishlistConformance
```

It executes 3/3 per variant with zero failures/errors/skips; XML is retained separately from the
ordinary run. The first full TV run exposed an incorrect new test assumption that TV must query
extensions. The corrected control independently verifies the fixture advertisement, then witnesses
actual TV catalog browsing and no Discover/Wishlist requests. TV production code is unchanged.

## Emulator trigger chains

`AndroidEmulatorDiscoverWishlistProofTest`, real DEV phone on API 34, explicit caller-leased emulator:

- `everywherePagesAndHeldRoute`: old/new input, incremental groups/load-more, artist → album,
  edition selection, server-issued local song reference → observed playback queue raw ID.
- `offlineWishRelaunchRemoveRaceAndArrival`: pending offline add, actual process/PID change with
  saved app data, one acknowledged wish; lost receipt/removal; completed scan and fulfilled server
  wish → Arrived → exact local playback ID; removal retains My Library; held add/removal overlap.
- `busyStaleAndNavidromeControl`: revision-bound served 429/120-second backoff, disabled retry and
  no early request; stale 7,200-second age with rows; healthy Navidrome login/browse before zero
  feature traffic and absent Everywhere/Wishlist controls.
- `accountSwitchIdentityFence`: A pending wish across B and back, independent B server list, new
  A pending mutation, rejected replacement, explicit Add Server replacement consent cancelled,
  original saved account retained and no wish mutation requests after rejection.
- `largestFontAndTalkBackLabels`: real light/dark scale 1.0 and dark system scale 2.0; labeled,
  reachable Compose actions and captures. This does not claim running TalkBack-service traversal.

Five identities expand to ten independent runner phases. Missing tests, skipped tests, infrastructure
errors, or unexpected terminal records fail the runner. Process proof records the old PID and verifies
a different new app PID; the instrumented process may already have exited before explicit cleanup.
Per-phase `requests.json`, `fixture-state.json` and pulled `acknowledgements.jsonl` retain the observed
revision-bound fault/scan chain. The race fixture adds `holdReleased` and successful upstream
`delivered` events; a concurrent Remove releases Add only after upstream removal commits, with the
bounded hold timeout retained for a serialized sender. The phone proof checks the hold before and
after the durable optimistic removal, then waits for both upstream mutations to complete before
asserting final absence. The fixture self-test has 9 executed tests, no failures/errors/skips,
including the new acknowledgement control and all three owned-PID cleanup modes. Fixture cleanup records owned PIDs reaped and disposable root deleted.

The visual finding at 2.0 is fixed only for short bottom-bar labels, capped at scale 1.0 so whole words
fit. Full accessibility names are preserved; body/dialog text remains at the system scale. The contextual Wishlist refresh failure also has a red/green presentation test; saved rows remain
visible. Native-font
layout tests provide the positive measurement that legacy Robolectric font stubs could not.

## Integration handoff

Feature evidence for the Android phone: `discover.search`, `wishlist.add`, `wishlist.state`,
`wishlist.fulfil`; `discover.pages` stays partial until step 3. Shelves and TV cells stay planned.
`wishlist.api` remains server-contract evidence. This lane promotes no `FEATURES.yml` cells.

Task 20 must combine the Apple/Android branches of `run-discover-wishlist-proofs`, invoke
`tools/test-discover-wishlist-fixture` directly as a blocking workflow control, and wire the Android
proof plus Discover-specific host-conformance fixture/property. Local parity is valid (44 rows).
Local CI policy fails only the intentionally unwired fixture self-test workflow requirement.

Changed host classes for the later Linux soak: `DiscoverWishlistSessionTest`,
`DiscoverWishlistAccountIntegrationTest`, `PhoneDiscoverTest`, `PhoneWishlistTest`,
`PhoneWishlistIntegrationTest`, `PhoneNavigationTest` in `:android:app`, and `TvMyLibraryTest`
in `:android:tv`. The production integration class needs the Discover fixture/property above;
ordinary default tests intentionally exclude it. No Linux soak, push or PR is performed in this lane.
