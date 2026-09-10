# Android playback rebase verification — 2026-09-08

The 18-commit Android playback branch was rebased from base `247d655` onto
`origin/main` at `14a9eb5`. Original tip: `b8ff616`; rebased implementation tip:
`88281e8`. This report is a subsequent documentation-only commit.

## Conflict and preservation audit

The only conflict was in the design spec revision history. Main added revisions
94 and 95 for stale-selection restoration and the correction preserving all queue
entries. The playback branch independently used revision 94 for explicit-seek
scrobble accounting. The latter is now revision 96, above main's unchanged records.
The current restoration contract retains all queue entries; the historical revision
94 deletion policy was not restored as current behavior.

`git range-diff 247d655..b8ff616 14a9eb5..88281e8` reports 17 unchanged patches
and one patch differing only in that revision number and surrounding context.
A complete four-tree content comparison accounted for all 448 tracked paths:

- 435 unchanged or branch-only paths match the original branch exactly.
- 12 main-only changed paths match main exactly.
- The sole path changed by both sides is the design spec. Removing main's exact
  13-line restoration insertion and 14-line history insertion, then reversing
  the revision-number adjustment, reproduces the original branch spec exactly.

The pre-to-post diff contains 13 paths. Every non-spec difference is precisely a
main-side change. In particular, `apple-ci.yml` equals main byte-for-byte, including
its added production-controller restoration check and the surrounding capture step.
Main's two restoration tools and resource-loader readiness changes survive intact;
`tools/test-downloads-macos-review-regressions` equals the original branch exactly.
No workflow, feature-matrix, Kotlin or tools conflict occurred. An independent
read-only review confirmed the patch and file comparisons and found no unexplained
deletion or unresolved hunk. `git diff --check` passed.

## Fresh execution

```sh
./gradlew :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest :core:bundleAndroidMainAar :core:licensee --rerun-tasks
python3 tools/verify_ci_policy.py
python3 tools/parity_gate.py
python3 tools/verify_os_floors.py --configuration-only
```

Gradle reported `BUILD SUCCESSFUL in 37s`, with 44 actionable tasks, all 44 executed.
Counts read from the resulting JUnit XML:

| Task | Suites | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `:core:jvmTest` | 26 | 185 | 0 | 0 | 0 |
| `:core:testAndroidHostTest` | 29 | 208 | 0 | 0 | 0 |

Metadata JAR, Android AAR and license audit tasks completed. CI policy passed across
6 workflows; parity passed for 6 feature rows; OS-floor configuration agreed on
macOS 14.0 and iOS/tvOS 17.0. Compilation emitted expect/actual Beta warnings and
two existing Android fixture warnings (nullable receiver and unused expression).
There were no compilation or test failures.

## Scope and remaining limits

Main has not independently implemented or obsoleted the Android Media3 composition.
Track details expose a Play entry that opens the playback activity and service-owned
controller; search-row activation itself still opens details. The engine, inline
resource validation, common queue/scrobble policy and persistent outbox remain intact.

Main's catalog-aware stale-selection recovery is used by Apple. Android still calls
`restoreCurrentPaused()`, whose implementation main did not change. Android therefore
does not inherit that recovery behavior. This is a pre-existing integration gap,
not a rebase regression; switching APIs requires an Android catalog contract.

This run did not execute emulator/device evidence, app-module tests or Apple tests.
It does not establish decoder progression, background execution, TV interaction,
acoustic output or independently measured server play-count delivery. Feature status
was not promoted. No push or pull-request operation was performed.
