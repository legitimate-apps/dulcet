# Committed-library local search

OBSERVED, 2026-09-07: this change builds on `d2a5b32` and removes its query-response cache. `AndroidSearchCache` and its cache-specific test are deleted. The original production app tests, Gradle live/broad selection boundary, and three negative controls remain.

## Implementation and boundaries

OBSERVED in the implementation and executed tests:

- `LocalLibrarySearch` lives in core commonMain. It queries SQLDelight artist, album, track, and credit rows at one transactionally pinned `schema_meta.committed_generation`, scoped to the provider. Uncommitted inserts, updates, and deletions cannot change the visible search generation.
- Candidate matching uses persisted normalized title/name/album-title columns, including every credit. The local source and server result ordering share the existing exact → prefix → word-start → substring ranker and track → album → artist type weighting. Local queries do not depend on any previous query string.
- Normalization shares NFKD, Unicode case folding, and diacritic stripping. The previous lowercase-only step now also applies Unicode 15.0 full-fold exceptions, including sharp S and final sigma, on both search paths.
- SQLDelight migration `4.sqm` upgrades schema v4 to v5, adding normalized columns and indexes. Sync writes their values in the same transactions as library rows. Existing row versions receive a transactional common-Kotlin backfill; SQLite `lower()` is not used as a normalization substitute.
- `search_index_meta` records completed backfill separately from schema migration. This matters because the JVM driver already advances `schema_meta` during migration. The marker and backfilled values commit together. Ordinary database opens do not rescan library rows for missing normalization.
- `AndroidLibraryDatabase` opens the existing `DulcetDriverFactory`/`AndroidSqliteDriver` behind a core facade. Android's `AndroidLibrarySearchSource` only forwards account-scoped queries. Production dependencies construct this adapter; no app test replaces `SearchHostDependencies`.
- App test setup synchronizes the disposable Navidrome through the real Android core facade, then seeds a minimal stale committed library in that test's local SQLite database. The app first types an unseen one-character query and must render local rows. Completing the query preserves the original assertions: stale overlap refreshed at index 0, local-only row retained at 1, server-only row appended at 2, no activation from rendering, and unchanged nonnumeric opaque ID through `SearchIntentRouter`. TV retains hardware text keys, asserted successive D-pad focus movement, and DirectionCenter activation.

Not implemented or verified: a user-facing Android sync trigger/browse lifecycle, Apple facade/UI adoption of this new local source, physical-device behavior, or audio playback. `AndroidLibraryDatabase.synchronize` is exercised by app-test setup; this change does not call it from the activities. Both Android `library.sync` cells remain blocked. Existing Apple search evidence is unchanged. Host tests still shadow only Android Keystore cryptography with host AES-GCM; they do not establish device Keystore behavior.

## Executed verification

All Gradle commands below used the mounted SDK (`ANDROID_HOME=/Volumes/AndroidSDK/sdk`, `ANDROID_SDK_ROOT=/Volumes/AndroidSDK/sdk`), one worker, and no concurrent builds.

OBSERVED:

```sh
./gradlew --no-daemon --max-workers=1 :core:generateCommonMainDulcetDatabaseSchema
./gradlew --no-daemon --max-workers=1 :core:verifySqlDelightMigration :core:jvmTest \
  :android:app:testDevDebugUnitTest :android:tv:testDebugUnitTest
```

The schema-generation task completed and wrote `5.db`. Verification ran separately because Gradle rejects scheduling the generator and migration verifier together without an explicit task dependency. Final verification output: `BUILD SUCCESSFUL in 47s`.

JUnit: 181 JVM tests, 8 mobile broad tests, and 1 TV broad test; zero failures/errors/skips. Neither live-test class appears in the broad app reports. Local core coverage includes unseen and first-character queries; all four match tiers and type ordering; accent/compatibility/case-fold normalization; secondary-credit matches; metadata and opaque IDs; account isolation; and pending-generation updates/deletions. A JVM test migrates the released `4.db` snapshot with accented legacy rows and proves search works after migration and a second reopen.

OBSERVED:

```sh
python3 tools/migration_gate.py
```

Output: `Migration gate valid: 5 fixture database(s), 4 protected table comparisons per fixture, download file reconciliation, and 9 explicit destructive negative controls`. The schema-v5 snapshot, migrated protected-data fixture, and implemented-table registry are included.

OBSERVED, with a reset disposable environment:

```sh
tools/conformance-env/linux-local run -- ./gradlew --no-daemon --max-workers=1 \
  -Pdulcet.productionSearchConformance :core:testAndroidHostTest \
  :core-conformance:testAndroidHostTest \
  :android:app:testDevDebugUnitTest :android:tv:testDebugUnitTest
```

Output: `BUILD SUCCESSFUL in 1m 1s`. JUnit reports 177 Android core tests and 48 Android live core-conformance tests passing, plus:

| App class | Test | Executions | Failures / errors / skips | Suite time |
| --- | --- | --- | --- | --- |
| AndroidProductionSearchAppConformanceTest | conf41ProductionQueryMergesAndRoutesOpaqueId | 1 | 0 / 0 / 0 | 5.963s |
| AndroidTvProductionSearchAppConformanceTest | conf41ProductionQueryMergesAndRoutesOpaqueId | 1 | 0 / 0 / 0 | 4.672s |

Both app reports contain `CONF-41 OBSERVED first-character query returns committed local library rows` and the original merge/opaque-activation observation. Reports are preserved in `.build/library-search-evidence/live/`.

OBSERVED stopped-server negative control: after `tools/conformance-env/linux-local down`, run the two live-mode app tasks with `--continue` and explicit disposable/loopback environment variables. Output: `BUILD FAILED in 19s`. Each class executed exactly one test and failed with `Disposable search3 must succeed (response redacted)`; neither skipped. Reports: `.build/library-search-evidence/server-absent/`.

OBSERVED evidence validation:

```sh
GITHUB_WORKFLOW=core-ci GITHUB_JOB=core-ci python3 tools/verify-parity-evidence \
  core-conformance/build/test-results/testAndroidHostTest \
  .build/library-search-evidence/live \
  .build/library-search-evidence/broad-app .build/library-search-evidence/broad-tv
```

Output: `executed parity evidence valid: workflow=core-ci job=core-ci tests=30 reports=11`.

OBSERVED negative control: the same command with the live directory omitted exits **1**, reporting `evidence test did not execute in core-ci/core-ci: AndroidProductionSearchAppConformanceTest/conf41ProductionQueryMergesAndRoutesOpaqueId`. This validates a missing execution, not merely a successful job status.

OBSERVED: literal username/password/token/salt/query canary checks pass; an audit of all 8 preserved app JUnit reports found zero canary leaks. Whole query strings remain redacted. No hex-pattern scan was used.

GitHub CI execution is **not verified**: the branch remains unpushed. The existing 15-minute CI timeout is unchanged; total GitHub job duration was not measured. No push, PR, or merge was performed.

OBSERVED final parity checks:

- `GITHUB_BASE_REF=main python3 tools/parity_gate.py`: `parity gate valid: 6 feature rows`.
- `python3 tools/test-parity-gate`: `parity-gate mutation and executed-evidence tests pass`.
- With the disposable server stopped, `GITHUB_BASE_REF=main tools/run-local-gates parity-gate`: `22 passed, 0 failed, 0 environment fault(s), 0 NOT covered`; `LOCAL GATE RESULT: COMPLETE PASS`.
- `git diff --check`: exit 0, no output.
