# Failed playback presentation regression

Verified 2026-09-08 on macOS against the controller fix and regression test in
`bb5eda1` and `1cfab16` (rebased onto `0e7e3ea`).

`recordFailedAfterPartial` can successfully record an engine failure and return a
snapshot whose current session phase is `Failed`. The Apple controller previously
mapped that snapshot to preparing. It now calls the existing `publishFailure()`.
Phase filtering and catalog lookup have separate guards. No other phase changes
behavior, including `Stopped` and `TornDown`; a missing catalog item still maps to
preparing. No failure view or copy was added.

`Stopped` behavior is deliberately unchanged here and separately suspect. Source
tracing shows `disconnect()` sends `.stop` and publishes `.unavailable`; the engine
can then emit `.skipped` for an active attempt, Kotlin records `Stopped`, and the
controller's unchanged phase fallback publishes `.preparing`. That queued event
can overwrite the disconnect presentation despite no preparation being underway.
This is a pre-existing, out-of-scope lifecycle issue, source-traced rather than
reproduced in a running app. See `DulcetCorePlaybackController.disconnect()`,
`DulcetAVPlayerEngine`'s stop handling, and `PlaybackCoreStateMachine`'s skipped
handling.

## Regression coverage

`DulcetCorePlaybackPresentationTests/testFailedAfterPartialPublishesFailureThroughPresentationStore`
uses the Kotlin queue facade to start a session, record readiness and progress, and
record failure after partial playback. It asserts that the transition has no error
and its current session is `Failed`, then passes the transition to the production
controller's `publish(_:)` method. It checks both controller status `.failed` and
the connected presentation store's `.nowPlayingFailed` state. It also checks the
preparing fallback for a started session and a progressing session missing from
the catalog. The new test makes no network connection.

The existing `DulcetPlaybackIntegrationTests` target now compiles the production
controller source. The Swift package does not compile `DulcetAppleShared`, so a
package-only presentation test would not exercise this routing decision. Apple CI
explicitly selects the new test and uses `verify-xcode-test-execution` to require
exactly one passing individual result.

## Behavior-only mutation

The initial fixed test passed. Then only this routing call was changed, keeping
all symbols and the test present:

```diff
 if session.phase == "Failed" {
-    publishFailure()
+    publishPreparing()
     return
 }
```

The mutated controller compiled. `xcodebuild test` exited 65, with these actual
assertion messages and test count:

```text
XCTAssertEqual failed: ("preparing") is not equal to ("failed")
XCTAssertEqual failed: ("nowPlayingPreparing") is not equal to ("nowPlayingFailed")
Executed 1 test, with 2 failures (0 unexpected) in 0.971 (0.972) seconds
```

After restoring `publishFailure()`, the full integration target passed:

```text
Executed 2 tests, with 0 failures (0 unexpected) in 2.748 (2.750) seconds
** TEST SUCCEEDED **
```

The second test was the existing production resource-loader loopback test. Tests
with nonstandard method names selected separately by other CI steps were not part
of this ordinary target run. No live Navidrome conformance run was needed.

The restored test also passed using CI's focused `test-without-building` path:

```text
Executed 1 test, with 0 failures (0 unexpected) in 0.672 (0.672) seconds
xcode test execution valid: test=DulcetPlaybackIntegrationTests.DulcetCorePlaybackPresentationTests/testFailedAfterPartialPublishesFailureThroughPresentationStore terminal=Passed individual-results=1
```

All successful Apple runs used an internal-disk DerivedData directory. An initial
build using a directory that resolved through a symlink to an external disk failed
to load the test bundle and executed zero tests; it is excluded from pass counts.

To reproduce, generate the project with `xcodegen generate --spec apple/project.yml`
and use an internal-disk `DERIVED_DATA` path:

```sh
xcodebuild test \
  -project apple/Dulcet.xcodeproj \
  -scheme DulcetPlaybackIntegration \
  -configuration Debug \
  -destination 'platform=macOS' \
  -derivedDataPath "$DERIVED_DATA" \
  -parallel-testing-enabled NO \
  -only-testing:DulcetPlaybackIntegrationTests/DulcetCorePlaybackPresentationTests \
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- CODE_SIGN_ENTITLEMENTS= \
  DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER=
```

## Other verification

- Repository core command passed:
  `./gradlew :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest :core:bundleAndroidMainAar :core:licensee`.
- Test tasks were then freshly executed with
  `./gradlew :core:jvmTest --rerun :core:testAndroidHostTest --rerun`:
  184 JVM tests and 180 Android host tests, zero failures, errors, or skips.
- `python3 tools/verify_ci_policy.py`: valid across 6 workflows.
- `python3 tools/parity_gate.py`: valid, 6 feature rows.
- `python3 tools/verify_os_floors.py --configuration-only`: configuration agrees
  on macOS 14.0 and iOS/tvOS 17.0.
- `python3 tools/test-project-spec-paths`: accepts the complete project spec and
  rejects a missing referenced file.
- Independent source review found no defects in scope, routing, test wiring,
  CI execution, comments, or commit claims.

`FEATURES.yml` needs no change. Its `playback.stream` row already describes the
capability being repaired. This is a presentation bug fix, not a platform promotion
or new conformance claim. These checks exercise controller-to-store behavior;
they do not claim a newly driven rendered failure screen or physical audio output.
