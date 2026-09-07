# Android production search path — local execution evidence

OBSERVED, 2026-09-07: work performed on `feat/android-production-search-path`, based on `b0c6b7d0ee16`. No push, PR, or merge was performed. GitHub CI execution is **not verified**; the workflow changes below have been exercised locally, not on a GitHub runner.

## Production path and test boundary

OBSERVED in the source and passing app executions:

- Both tests use `Application`, which is asserted not to implement `SearchHostDependencyOwner`, and launch the real mobile/TV activities. The activities select `ProductionSearchHostDependencies`.
- Setup saves an encrypted account record through `AndroidAccountCredentialStore`; production loads it through the same store. Robolectric shadows only the private Android Keystore cipher with host AES-GCM. Android device Keystore behavior is **not verified** by these tests.
- Production presenter creation now supplies an account-scoped `AndroidSearchCache` to `CoreSearchDataSource`. Previously this path used an empty local lambda. Successful server pages populate this bounded, persistent query-page cache. It preserves full row metadata and opaque identities.
- Setup discovers reference IDs with real `ServerSearch`, then seeds a stale overlapping cache row and a local-only row. The app enters its query through Compose (mobile) or hardware letter-key events (TV). The rendered result assertions require refreshed index 0, retained local-only index 1, and server-only index 2. An app that reads only the seeded cache cannot pass.
- Rendering starts no activity. Activation uses `SearchIntentRouter` and preserves `local:opaque/CONF-41:not-an-integer` verbatim in the search-sourced detail intent. TV also asserts focus on successive rows under DirectionDown before DirectionCenter activation. No audio or physical-device claim is made.
- Tests check credential canaries against the intent/account diagnostics and captured Android logs. Separate username/password/token/salt/query canaries require the URL diagnostic to redact the entire query string.

## Executed commands and outputs

All Gradle commands used the mounted SDK via `ANDROID_HOME=/Volumes/AndroidSDK/sdk` and `ANDROID_SDK_ROOT=/Volumes/AndroidSDK/sdk`, with one worker and no concurrent builds.

OBSERVED, after `tools/conformance-env/linux-local up` and a reset between live runs:

```sh
tools/conformance-env/linux-local run -- ./gradlew --no-daemon --max-workers=1 \
  -Pdulcet.productionSearchConformance \
  :android:app:testDevDebugUnitTest :android:tv:testDebugUnitTest
```

Output: `BUILD SUCCESSFUL in 50s`. JUnit:

| Class | Test | Tests | Failures / errors / skips | Test time |
| --- | --- | --- | --- | --- |
| AndroidProductionSearchAppConformanceTest | conf41ProductionQueryMergesAndRoutesOpaqueId | 1 | 0 / 0 / 0 | 12.808s |
| AndroidTvProductionSearchAppConformanceTest | conf41ProductionQueryMergesAndRoutesOpaqueId | 1 | 0 / 0 / 0 | 5.594s |

Reports are preserved locally under `.build/search-evidence/live/`. The first live run, including more compilation, took 62 seconds. No CI timeout increase was made; total GitHub job duration is **not measured**.

OBSERVED negative control: after `tools/conformance-env/linux-local down`, run the same Gradle tasks with `--continue` and explicit `DULCET_CONFORMANCE_DISPOSABLE=true DULCET_CONFORMANCE_BASE_URL=http://127.0.0.1:4533`. Output: `BUILD FAILED in 11s`. Each new class executed one test with one failure and zero skips. Both failures were `Disposable search3 must succeed (response redacted)`. Reports: `.build/search-evidence/server-absent/`.

OBSERVED broad unit-task control, with no live-mode property:

```sh
./gradlew --no-daemon --max-workers=1 \
  :android:app:testDevDebugUnitTest :android:tv:testDebugUnitTest
```

Output: `BUILD SUCCESSFUL in 15s`. Mobile: 9 tests; TV: 2 tests; zero failures/errors/skips. JUnit contains the cache round-trip test in each module and neither production live-test class. Reports: `.build/search-evidence/broad-app/` and `broad-tv/`.

OBSERVED core Android conformance after a fresh disposable environment startup:

```sh
tools/conformance-env/linux-local run -- ./gradlew --no-daemon --max-workers=1 \
  :core-conformance:testAndroidHostTest
```

Output: `BUILD SUCCESSFUL in 24s`; 48 tests, zero failures/errors/skips. This includes `SearchConformanceTest/conf41LocalAndServerSearchMergeReplacesWithoutDuplicatingOrDropping`.

OBSERVED executed-evidence validation:

```sh
GITHUB_WORKFLOW=core-ci GITHUB_JOB=core-ci python3 tools/verify-parity-evidence \
  core-conformance/build/test-results/testAndroidHostTest \
  .build/search-evidence/live .build/search-evidence/broad-app .build/search-evidence/broad-tv
```

Output: `executed parity evidence valid: workflow=core-ci job=core-ci tests=30 reports=13`. These environment variables select the real verifier's expected evidence set; they do **not** imply a GitHub execution. Omitting the live reports exits 1 with `evidence test did not execute` naming the new mobile test. A literal-canary audit across the 12 preserved app JUnit reports found zero leaks; no hex-pattern heuristic was used.

## CI selection

OBSERVED in `.github/workflows/core-ci.yml` and both app Gradle files: the ordinary unit-test mode explicitly excludes `*ProductionSearchAppConformanceTest`. The disposable Linux job alone opts in using `-Pdulcet.productionSearchConformance`; live mode includes only those classes and disables up-to-date reuse. Missing configuration fails setup, and the stopped-server control above proves server absence fails rather than skips. Live JUnit reports join the Android core evidence artifact downloaded by required `core-ci`, which already runs `verify-parity-evidence`. A job pass without those reports cannot satisfy the added FEATURES rows.

OBSERVED: `FEATURES.yml` promotes both search cells to `shipped`, retains their earlier fixture controls, adds one production `observes` row each, and removes both promotion conditions. `tools/test-parity-gate` includes both new test identities in its hand-maintained passing fixture.

OBSERVED: `GITHUB_BASE_REF=main python3 tools/parity_gate.py` outputs `parity gate valid: 6 feature rows`; `python3 tools/test-parity-gate` outputs `parity-gate mutation and executed-evidence tests pass`.

The first full local parity run encountered an environment fault because its transcode timeout control also needs port 4533. OBSERVED: after stopping the disposable server, `GITHUB_BASE_REF=main tools/run-local-gates parity-gate` reports `22 passed, 0 failed, 0 environment fault(s), 0 NOT covered` and `LOCAL GATE RESULT: COMPLETE PASS`.
