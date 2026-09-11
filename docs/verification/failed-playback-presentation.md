# Failed playback presentation regression

Verified 2026-09-08 on macOS against the controller fix and regression test in
`bb5eda1` and `1cfab16` (rebased onto `0e7e3ea`).

`recordFailedAfterPartial` can successfully record an engine failure and return a
snapshot whose current session phase is `Failed`. The Apple controller previously
mapped that snapshot to preparing. It now calls the existing `publishFailure()`.
Phase filtering and catalog lookup have separate guards. No other phase changes
behavior, including `Stopped` and `TornDown`; a missing catalog item still maps to
preparing. No failure view or copy was added.

`Stopped` behavior was deliberately unchanged here and separately suspect. Source
tracing showed `disconnect()` sends `.stop` and publishes `.unavailable`; the engine
can then emit `.skipped` for an active attempt, Kotlin records `Stopped`, and the
controller's unchanged phase fallback publishes `.preparing`. That queued event
can overwrite the disconnect presentation despite no preparation being underway.
This was a pre-existing, out-of-scope lifecycle issue, source-traced rather than
reproduced in a running app. See `DulcetCorePlaybackController.disconnect()`,
`DulcetAVPlayerEngine`'s stop handling, and `PlaybackCoreStateMachine`'s skipped
handling.

**CLOSED 2026-09-11.** The trace above was correct, and the mechanism was confirmed
unchanged on `main`: `PlaybackCoreStateMachine` maps `Skipped` to `Stopped` by
COPYING the current session rather than retiring it, so the queued event carries a
live `currentSession` and reaches the fallback. The phase mapping is now total —
`Stopped` and `TornDown` present as `unavailable`, an unrecognised phase presents as
`unavailable` rather than `preparing`, and `tools/verify-playback-phase-parity` fails
the build when the Apple shell and the Kotlin enum stop naming the same set. Spec
§12.2 carries the contract; revision 100 records it.

`DulcetCorePlaybackPresentationTests/testDisconnectIsNotOverwrittenByTheStopItIssued`
drives the real ordering: a fixture engine that emits `.skipped` from its stop exactly
as `DulcetAVPlayerEngine` does, through the event listener the production initializer
installs. It requires the core to have actually recorded `Stopped` before asserting
the presentation, so a fixture that emitted nothing fails rather than passing.

## Mapping control (initial coverage)

`DulcetCorePlaybackPresentationTests/testFailedAfterPartialPublishesFailureThroughPresentationStore`
uses the Kotlin queue facade to start a session, record readiness and progress, and
record failure after partial playback. It asserts that the transition has no error
and its current session is `Failed`, then passes the transition to the production
controller's `publish(_:)` method. It checks both controller status `.failed` and
the connected presentation store's `.nowPlayingFailed` state. It also checks the
preparing fallback for a started session and a progressing session missing from
the catalog. This mapping control makes no network connection. It bypasses engine-event
delivery, so its original routing mutation did not certify that delivery path.
The follow-up below adds separate coverage of that boundary.

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


## Engine-event delivery follow-up — 2026-09-09

Independent review suppressed publication only for `.failedAfterPartial` in
`receiveEngineEvent`. The original mapping control still passed because it called
`publish(_:)` directly with a transition from a separate queue. That was a real
coverage gap: the session could fail without the listener receiving a failure.
The focused mapping test remains useful and is retained unchanged.

`testEngineFailureAfterPlayingReachesPresentationStore` now gives the controller a
controlled engine, a real Kotlin queue, and a populated track catalog. The default
production initializer still creates the same concrete AVPlayer engine and queue;
both initializers share listener registration. The test sends ready, progress,
and failure events through that registered listener. It never calls `publish(_:)`
or `receiveEngineEvent(_:)` directly.

Before delivering failure, it requires the controller and store to show the
catalog track playing, with `.progressing`, `progressBegan == true`, the expected
queue, and the session identity. After delivery, it requires:

- The controller's own queue to record `Failed` at 2,000 ms, preserving the attempt,
  session, and queue-entry identities.
- Controller status `.failed` with no now-playing item.
- Store state `.nowPlayingFailed` with no now-playing item.

The position assertion also distinguishes partial-failure recording from a
before-start failure that would leave the earlier position intact. The controlled
engine invokes the callback actually installed by the production controller; it
does not emulate the queue reducer, event handler, or presentation mapping.

### Suppression mutation and restored results

With the correct `Failed -> publishFailure()` routing still present, only the
production event-publication site was mutated:

```swift
if case .failedAfterPartial = event {
    // Failure-event presentation delivery removed.
} else {
    publish(transition)
}
```

The final new test compiled and failed under this mutation. `xcodebuild test`
exited 65. Actual assertion-message excerpts and test count:

```text
XCTAssertEqual failed: ("ready") is not equal to ("failed")
XCTAssertEqual failed: ("nowPlaying") is not equal to ("nowPlayingFailed")
Executed 1 test, with 4 failures (0 unexpected) in 2.812 (2.813) seconds
** TEST FAILED **
```

The other two failures were the controller and store `XCTAssertNil` checks: both
still held the catalog track with `isPlaying: true` and phase `.progressing`.
Queue phase, position, and identity assertions passed, isolating the missing
publication from a recording failure.

After restoring the unconditional `publish(transition)`, the full integration
target passed, including the retained mapping control and existing loader test:

```text
Executed 3 tests, with 0 failures (0 unexpected) in 1.263 (1.265) seconds
** TEST SUCCEEDED **
```

CI now selects each presentation test separately and guards each result. The new
one was also run using that focused `test-without-building` command:

```text
Executed 1 test, with 0 failures (0 unexpected) in 0.052 (0.053) seconds
** TEST EXECUTE SUCCEEDED **
xcode test execution valid: test=DulcetPlaybackIntegrationTests.DulcetCorePlaybackPresentationTests/testEngineFailureAfterPlayingReachesPresentationStore terminal=Passed individual-results=1
```

The internal-disk DerivedData directory was checked using `realpath` before the
runs. These were native macOS tests; no simulator or live server was required by
the new control. CI policy (6 workflows), parity (6 feature rows), and OS-floor
configuration checks passed again. The core results above belong to the initial
verification; unchanged core tasks were not rerun for this Swift coverage change.

### What could I delete from the product while this suite still passes?

Within the new control's event-to-store boundary, removing listener registration,
failure recording, failure publication, the failure presentation callback, or the
store subscription cannot satisfy the playing-to-failed assertions. Suppressing
failure publication was verified by execution; the other deletion conclusions
are from source review, not additional mutation runs.

Outside that boundary, these tests can still pass if real AVPlayer failure-event
emission, production playback-start/catalog-loading code, remote-command effects,
other event branches, stale-session handling, or the rendered SwiftUI failure view
are removed. The fixture supplies the engine event source and initial queue/catalog
state. This is engine-event-to-presentation-store coverage, not an assertion that
those other product paths have been tested. The separately suspect disconnect to
`Stopped` path remains deliberately unchanged and was not reproduced here.

Independent source review found no remaining delivery-path gap and prompted the
additional failure-position assertion. At final verification, fetched `origin/main`
remained `0e7e3ea`, already an ancestor of this branch; no further rebase was needed.
`FEATURES.yml` remains unchanged because this adds regression coverage, not a new
capability or platform promotion.
