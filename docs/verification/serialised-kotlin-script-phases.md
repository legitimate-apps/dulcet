# Serialised Kotlin script phases: evidence

Branch `fix/serialise-kotlin-script-phases`, based on `main` at `1e5f9824`. The spec's §28 entry is
numbered **99 provisionally**: `main` tops out at 98 and other open branches carry 99–101, so it is
renumbered at rebase. Paths below are scrubbed: `<checkout>` is the repository root, `<home>` the
Gradle user home, `<scratch>` a temporary directory outside the repository.

## The failure, OBSERVED on `main`

Run 34635969077 (2026-09-11), job `apple-ci`, step *Launch iPadOS shell and prove layout plus
stored Keychain attributes*. `xcodebuild` started the `Compile Kotlin Framework` phase of
`DulcetiOS` and of `DulcetKitIOSTests` in the same second (19:24:59Z). The second reported
`Starting a Gradle Daemon, 1 busy and 1 stopped Daemons could not be reused` and, at 19:26:42Z:

```
Timeout waiting to lock Configuration Cache (<checkout>/.gradle/configuration-cache).
It is currently in use by another process.
Owner PID: 32156
Our PID: 48478
Lock file: <checkout>/.gradle/configuration-cache/configuration-cache.lock
BUILD FAILED in 1m 16s
Command PhaseScriptExecution failed with a nonzero exit code
```

Run 34127121022 (2026-09-07) had lost the user-home-scoped journal lock
(`Timeout waiting to lock journal cache (<home>/caches/journal-1)`) between the same two targets.
A required check therefore went red on a build-time lock race, not on the commit under test.

## Frequency, OBSERVED

Instrument: the 40 most recent `apple-ci` runs listed on 2026-09-11 (ids 34565396828 through
34639274065), each non-success run's failed-step log fetched with `run view <id> --log-failed`,
grepped for `Timeout waiting to lock`.

| conclusion | runs | inspectable | carrying the string |
|---|---|---|---|
| success | 19 | no failed step log | — |
| failure | 13 | 13 | **1** (34635969077) |
| cancelled | 6 | 0 (empty failed-step log) | unknown |
| in progress | 2 | not yet | — |

**1 of 40** by this instrument, validated against its own positive (the run above). The six
cancelled runs and the journal-lock instance of 2026-09-07 (older than the window) are outside
what it can see, so this is a floor, not a rate.

## Local reproduction, without Xcode

Two `:core:embedAndSignAppleFrameworkForXcode` invocations were started in the same second from
the checkout with the environment Xcode hands the phase for an iOS Simulator build
(`CONFIGURATION=Debug SDK_NAME=iphonesimulator26.4 PLATFORM_NAME=iphonesimulator ARCHS=arm64
TARGET_BUILD_DIR BUILT_PRODUCTS_DIR FRAMEWORKS_FOLDER_PATH=Dulcet.app/Frameworks
EXPANDED_CODE_SIGN_IDENTITY= ENABLE_USER_SCRIPT_SANDBOXING=NO`), stdin from `/dev/null` as the
phase does, output to `<scratch>`. Gradle 9.5.0, JDK 17, `org.gradle.configuration-cache=true`.

**Without the runner (bare `./gradlew`) — NOT reproduced, five attempts:**

| attempt | state before the pair | result |
|---|---|---|
| 1 | warm daemon, warm caches | 12 s / 19 s, both `BUILD SUCCESSFUL`; second waited about 7 s, then `Configuration cache entry reused` |
| 2 | `<checkout>/.gradle` and `core/build` removed, `--no-build-cache` | 27 s / 31 s, **both** `Configuration cache entry stored` |
| 3 | fresh shared `GRADLE_USER_HOME` (only the wrapper distribution pre-seeded), cold daemon, cold dependency resolution | 2m 12s / 2m 11s, **both** `Configuration cache entry stored` |
| 4 | after a `--no-daemon :core:linkDebugFrameworkIosSimulatorArm64` pre-step, as the CI job runs first | 1 s / 2 s, both reused |
| 5 | a seeded same-task entry, all daemons stopped, then the pair with a changed `BUILT_PRODUCTS_DIR` — the CI winner's exact `Calculating task graph as configuration cache cannot be reused because environment variable 'BUILT_PRODUCTS_DIR' has changed` line reproduced | 33 s / 32 s, **both** `Configuration cache entry stored` |

OBSERVED: on this host the configuration-cache lock is a short critical section — two concurrent
builds of the same checkout, cold or warm, both store an entry and neither waits 60 s. The CI
runner held it for more than 60 s (the loser waited 1m 16s). **ASSUMED**: whatever Gradle does
under that lock takes longer than its 60 s timeout on the hosted runner and seconds here; the
mechanism of the longer window was not observed locally and is not claimed. The fix does not
depend on it: the runner serialises the *whole* invocation, so no Gradle critical section of any
length can overlap another phase's.

**With the runner (`tools/run-gradle-exclusive`) — OBSERVED, both complete:**

```
mode=exclusive
run-gradle-exclusive: acquired <home>/dulcet-embed-framework.lock after 0.0s
run-gradle-exclusive: acquired <checkout>/.gradle/dulcet-embed-framework.lock after 0.0s
run-gradle-exclusive: holding 2 lock(s); running :core:embedAndSignAppleFrameworkForXcode
run-gradle-exclusive: another Gradle build phase holds <home>/dulcet-embed-framework.lock; waiting
run-gradle-exclusive: acquired <home>/dulcet-embed-framework.lock after 0.5s
run-gradle-exclusive: acquired <checkout>/.gradle/dulcet-embed-framework.lock after 0.0s
run-gradle-exclusive: holding 2 lock(s); running :core:embedAndSignAppleFrameworkForXcode
invocation=1 exit=0   BUILD SUCCESSFUL in 482ms   Configuration cache entry reused.
invocation=2 exit=0   BUILD SUCCESSFUL in 409ms   Configuration cache entry reused.
```

The second invocation's "waiting" line is the marker that the handler ran; the pair is disjoint
by construction rather than by luck.

## Controls, OBSERVED

`python3 tools/test-run-gradle-exclusive` — PASS. It carries a negative control (the unwrapped
pair overlaps, so the detector fires) and one case per lock key. Mutation: replacing the runner
with the earlier single-lock version keyed on the Gradle user home fails exactly *one checkout,
two Gradle user homes* (2 checks); a variant keyed on the checkout alone fails exactly *two
checkouts, one Gradle user home* (2 checks). Every other case passes under both mutants, so the
two cases are the ones that pin the keys.

`python3 tools/test-xcode-script-phases` — PASS; corrupting one of the eight committed bodies
(sha256 `f095679a…` → `48e9585…`) is rejected with `expected 8 copies, found 7`.
`python3 tools/verify_xcode_script_phases.py` — `PASS declared=8 generated=8`.
`python3 tools/test-dulcet-core-build-order --xcodegen-post-build` — all 11 cases pass on the
regenerated project; `python3 tools/verify_dulcet_core_build_order.py` —
`PASS checked_targets=13 expected_native_targets=13`. Both pbxproj verifiers agree.

XcodeGen 2.46.0 regenerated `main`'s committed project byte-for-byte before the edit, so the
project diff is exactly the eight `shellScript` assignments.

`python3 tools/parity_gate.py`, `verify_ci_policy.py`, `test-verify-ci-policy` (22/22),
`test-spec-evidence-boundaries`, `test-spec-evidence-boundary-mutations`, `test-run-local-gates`,
`test-required-checks`, `test-pr-workflow-secret-boundary`, `test-project-spec-paths`,
`verify_project_spec_paths.py` — all exit 0. `git diff --check` clean.

## Expected effect on run time, ASSUMED until a CI run

The reviewer's estimate from the same 40-run sample: about **−1 to −2 minutes per run**, because
each avoided second daemon start (four per job across the concurrent pairs) is roughly 15–30 s of
JVM start plus configuration that the loser used to spend before failing or waiting; **worst case
about +40 s** when a pair that would have overlapped harmlessly now runs back-to-back. Neither
figure is measured; the first CI run of this branch against the duration trajectory is the
measurement.

## Not exercised

No `xcodebuild`, simulator, or CI run of this branch. That the serialised phases pass the
iPadOS step on a hosted runner is the claim CI makes when this branch is checked; it is not
made here. `tools/test-run-gradle-exclusive` runs in `parity-gate` on `ubuntu-latest` only, so
CI observes the runner's `flock` behaviour on Linux; its behaviour on macOS, where the script
phases actually run, is OBSERVED only on the Mac that produced this note. The lock timeout
itself was not reproduced locally (five attempts above).
