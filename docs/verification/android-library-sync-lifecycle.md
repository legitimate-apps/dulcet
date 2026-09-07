# Android library sync lifecycle evidence

OBSERVED, 2026-09-07: implemented on `feat/android-library-sync-integration`, based on `a2474ab`. This builds on the existing AndroidLibraryDatabase and committed local search; neither the sync engine nor the normalized search index was replaced.

## Production behavior

OBSERVED in the real mobile and TV activity tests:

- Both activities expose a Library entry. Opening it reads the saved committed artist/album/track metadata through the production AndroidSqliteDriver facade without starting a sync. The screen offers an explicit Sync library action.
- LibrarySession starts production synchronization, reads and renders the committed snapshot, and enables a 15-minute Handler refresh timer after a successful sync. Handler deadlines use monotonic uptime. The callback itself increments the scheduled-refresh observation and emits `LIBRARY_REFRESH_FIRED monotonic=true` before synchronization.
- ON_STOP cancels the timer and current operation. ON_START rearms the timer for a previously connected session. Disposal removes pending callbacks and cancels the scope. Recreated saved-account sessions start disconnected and only read saved metadata.
- The tests trigger the first sync through the UI and observe generation 1. They stop the activity, advance the monotonic interval, and require no refresh marker. After resuming, advancing the interval invokes the production callback and commits generation 2. There is no second manual sync call.
- The offline control requires a ConnectException from 127.0.0.1:1, then preserves the saved account ID while pointing its encrypted credential record at that refused endpoint. Activity recreation creates a fresh library session and reopens the store. It renders the identical generation-2 snapshot with zero sync starts and zero scheduled refreshes, even after another refresh interval. The test scrolls to and renders the saved health-probe track. The global refresh marker count remains one, also detecting an orphaned timer from the disposed session.
- TV uses DirectionCenter on the focused sync control and DirectionDown into the first library card, with focus asserted. These are Robolectric-generated key events, not physical-remote observations.

No library/search dependency or data source is substituted. The existing host-only Keystore cipher shadow remains; encrypted record persistence and production account loading execute. Test setup writes only its local credential record/database. Production sync reads the disposable loopback Navidrome. No personal/production server is used.

## Commands and executed evidence

All Gradle commands below used `ANDROID_HOME=/Volumes/AndroidSDK/sdk`, `ANDROID_SDK_ROOT=/Volumes/AndroidSDK/sdk`, `--no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m`. Builds were sequential.

OBSERVED, after starting/resetting the disposable environment:

```sh
tools/conformance-env/linux-local run -- ./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs=-Xmx1536m -Pdulcet.productionSearchConformance \
  :core-conformance:testAndroidHostTest \
  :android:app:testDevDebugUnitTest :android:tv:testDebugUnitTest
```

Output: `BUILD SUCCESSFUL in 47s`. The 48 Android core-conformance tests and all four live app tests passed, with zero failures/errors/skips. The original search proofs remain unchanged.

| New app class | Test | Executions | Failures/errors/skips | Suite duration |
| --- | --- | --- | --- | --- |
| AndroidProductionLibrarySyncAppConformanceTest | librarySyncSchedulesSecondGenerationAndReopensOffline | 1 | 0/0/0 | 5.412s |
| AndroidTvProductionLibrarySyncAppConformanceTest | librarySyncSchedulesSecondGenerationAndReopensOffline | 1 | 0/0/0 | 4.750s |

OBSERVED in both JUnit reports, preserved in `.build/libsync-evidence/live/`:

```text
LIBRARY_OFFLINE_CONTROL connection-refused=true
LIBRARY_SAVED_READ generation=0 syncStarts=0
LIBRARY_COMMITTED generation=1
LIBRARY_REFRESH_FIRED monotonic=true
LIBRARY_COMMITTED generation=2
LIBRARY_SAVED_READ generation=2 syncStarts=0
LIBRARY APP OBSERVED generations=1,2 scheduler-fired=true offline-refusal=true reopened-generation=2 offline-sync-starts=0
```

The saved-read/commit/refresh lines are copied from production Log emissions that the tests also assert; they are not independently invented success messages. Robolectric advances the actual Handler's monotonic clock; this is not a claim that 15 minutes of wall time elapsed.

OBSERVED stopped-server negative control: after `tools/conformance-env/linux-local down`, run both app tasks with the live flag, `--continue`, and explicit `DULCET_CONFORMANCE_DISPOSABLE=true DULCET_CONFORMANCE_BASE_URL=http://127.0.0.1:4533`. Output: `BUILD FAILED in 14s`; four executed tests, four failures, zero skips. The library failures are `Disposable library server must be reachable (endpoint redacted)`; the original search failures remain `Disposable search3 must succeed (response redacted)`. Reports: `.build/libsync-evidence/absent/`.

OBSERVED broad selection control:

```sh
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  :android:app:testDevDebugUnitTest :android:tv:testDebugUnitTest
```

Output: `BUILD SUCCESSFUL in 15s`; 8 mobile and 1 TV tests passed. JUnit contains neither production search nor production library live-test class. Both Gradle files explicitly exclude both patterns without the live flag and include both with it; live runs retain disabled up-to-date reuse. The existing disposable Linux job and evidence upload already cover these tasks/reports.

OBSERVED executed-evidence control:

```sh
GITHUB_WORKFLOW=core-ci GITHUB_JOB=core-ci python3 tools/verify-parity-evidence \
  core-conformance/build/test-results/testAndroidHostTest \
  .build/libsync-evidence/broad-app .build/libsync-evidence/broad-tv \
  .build/libsync-evidence/live
```

Output: `executed parity evidence valid: workflow=core-ci job=core-ci tests=32 reports=13`.

OBSERVED negative controls with the same command and only a subset of live reports:

- Search reports only: exit 1, `evidence test did not execute in core-ci/core-ci: AndroidProductionLibrarySyncAppConformanceTest/librarySyncSchedulesSecondGenerationAndReopensOffline`.
- Library reports only: exit 1, `evidence test did not execute in core-ci/core-ci: AndroidProductionSearchAppConformanceTest/conf41ProductionQueryMergesAndRoutesOpaqueId`.

OBSERVED canary audit: 12 preserved app JUnit reports, zero literal credential/token/salt/query canary leaks. The original whole-query redaction assertions remain and pass.

OBSERVED gates:

- `GITHUB_BASE_REF=main python3 tools/parity_gate.py`: `parity gate valid: 6 feature rows`.
- `python3 tools/test-parity-gate`: `parity-gate mutation and executed-evidence tests pass`.
- `GITHUB_BASE_REF=main tools/run-local-gates parity-gate`: `22 passed, 0 failed, 0 environment fault(s), 0 NOT covered`; `LOCAL GATE RESULT: COMPLETE PASS`.
- `git diff --check`: exit 0, no output.

Both library.sync Android cells are shipped, each with one added observation row and its promotion condition removed. The hand-maintained parity fixture includes both new identities. The existing 15-minute CI timeout is unchanged; the measured local final command took 47 seconds, and total GitHub runner duration is not measured.

Not verified: GitHub CI execution of this unpushed work, physical devices/remotes, device Keystore, or audio/offline media files. The work proves offline metadata browsing, not offline audio. No background worker sync, Apple changes, push, PR, or merge was added/performed.
