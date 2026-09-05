# tvOS search UI evidence and navigation gap

OBSERVED on 2026-09-04 with Xcode 26.6.0, tvOS Simulator 26.5, and the disposable
Navidrome fixture at `http://127.0.0.1:4533`:

`DulcetTVUITests/DulcetTVUITests/testSimulatorSearchQueryRanksAndActivatesTrack`
types `UI Playback Canary` into the production tvOS Search field, checks the
field's own value, observes the rendered rank-zero Track button, focuses it with
the remote and presses Select, and observes the canary title, `Playing from
Search`, and advancing media time in Now Playing.

## Product gaps and the exact setup boundary

**OBSERVED: ordinary tvOS root navigation supplies no control that selects
Search. A person cannot reach Search through the current root's navigation.**
Source inspection of `DulcetRootView.swift` shows that tvOS renders only
`DulcetStateSurface` inside a `NavigationStack`; the sidebar is excluded on tvOS.
The Search surface requires `.search` to have already been selected. The test's
unseeded launch recorded a Connection screen with server, username, password,
local-HTTP toggle, and Connect controls, without a Search navigation control.
This runtime observation corroborates the root's source-level omission; it is
not a claim that a complete interactive login/navigation tour was exercised.

**OBSERVED: the original DulcetTV entry point had no account launch hook.**
This change adds DEBUG-only `-dulcet-debug-connect-account` setup using the live
production connector and Keychain store, restricted to the disposable loopback
URL. The separate `-dulcet-debug-open-search` hook selects Search after connection.
That hook was the only route used to reach Search in the passing control. Neither
hook injects the query, ranked results, activation, queue, or playback state.
Release builds contain neither hook. Interactive account entry, normal navigation
to Search, and production-signed Keychain attributes remain outside this proof.

OBSERVED: rendering results originally crashed the production tvOS root with:

> Fatal error: No Observable object of type DulcetPresentationStore found.

The simulator's application log reported that assertion after typed input, and
the crash report recorded `EXC_BREAKPOINT` / `SIGTRAP` in SwiftUI's environment
lookup. `DulcetArtworkView` reads the store from its environment, while the tvOS
root omitted the `.environment(store)` supplied by the other Apple roots. The
one-line production fix adds that environment value. The same activation probe
then passed. The account-only tvOS rendering tests had not exercised this path.

OBSERVED: building the initial probe with `CODE_SIGNING_ALLOWED=NO` reached a
live server success but rendered `The account could not be saved` and
`The server accepted the account, but the system Keychain did not save it.`
Rebuilding with normal simulator signing enabled allowed account setup and typed
entry. The control therefore requires simulator signing; it does not substitute
an in-memory credential store or silently skip a failed connection.

## Observed text entry, activation, and playback

The signed entry probe observed the field's exact `UI Playback Canary` value
after remote Select and `typeText`. Therefore a negative claim that tvOS text
entry cannot be automated is disproved by this run. Remote-only character entry
was not needed. The final control does use remote navigation to focus the system
keyboard's `done` button, then presses Select and checks that the keyboard closes
without changing the app field's value.

OBSERVED final green build: `FINAL_BUILD_EXIT=0`, `** TEST BUILD SUCCEEDED **`.
OBSERVED final green execution: `GREEN_EXIT=0`, `** TEST EXECUTE SUCCEEDED **`,
`Executed 1 test, with 0 failures (0 unexpected) in 19.970 (19.972) seconds`.

The test emitted:

```text
DULCET TV QUERY value=UI Playback Canary input=typeText
DULCET TV RANK0 label=UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track
DULCET TV SEARCH PASS query=typed rank0=rendered activation=remote-select source=search title=UI Playback Canary progress=0:02 of 0:31->0:03 of 0:31 setup=debug-account-and-destination
```

OBSERVED named-test guard:

```text
xcode test execution valid: test=DulcetTVUITests.DulcetTVUITests/testSimulatorSearchQueryRanksAndActivatesTrack terminal=Passed individual-results=1
```

## Reproduction and CI

Generate the project from `apple/project.yml` with `xcodegen generate` in `apple/`.
Supply an Android SDK through `ANDROID_HOME` and `ANDROID_SDK_ROOT`, because the
Xcode build invokes Gradle through a run-script phase. Choose a DerivedData
directory on the same physical volume as the system and resolve symlinks before
trusting the path: a test whose DerivedData resolves onto an external volume can
launch its host and then execute nothing, which reports as a pass rather than as
an error. Run against a single tvOS simulator with `-parallel-testing-enabled NO`,
because `xcodebuild test` otherwise clones the destination and the clone failure
reads as a test failure. Preserve simulator signing.

With `DERIVED_ROOT` and `TVOS_UDID` supplied, and the disposable fixture running:

```sh
export TEST_RUNNER_DULCET_UI_TEST_SERVER_URL=http://127.0.0.1:4533
export TEST_RUNNER_DULCET_UI_TEST_USERNAME=dulcet-admin
export TEST_RUNNER_DULCET_UI_TEST_PASSWORD=dulcet-ci-canary-password
xcodebuild build-for-testing \
  -project apple/Dulcet.xcodeproj -scheme DulcetTV -configuration Debug \
  -destination 'generic/platform=tvOS Simulator' \
  -derivedDataPath "$DERIVED_ROOT" -parallel-testing-enabled NO \
  -only-testing:DulcetTVUITests || exit "$?"
xcodebuild test-without-building \
  -project apple/Dulcet.xcodeproj -scheme DulcetTV -configuration Debug \
  -destination "platform=tvOS Simulator,id=$TVOS_UDID" \
  -derivedDataPath "$DERIVED_ROOT" -parallel-testing-enabled NO \
  -resultBundlePath "$RESULT_BUNDLE" \
  -only-testing:DulcetTVUITests/DulcetTVUITests/testSimulatorSearchQueryRanksAndActivatesTrack \
  || exit "$?"
python3 tools/verify-xcode-test-execution "$RESULT_BUNDLE" \
  DulcetTVUITests.DulcetTVUITests testSimulatorSearchQueryRanksAndActivatesTrack
```

OBSERVED in `.github/workflows/apple-ci.yml`: the new control runs inside the
existing disposable-fixture shell, after the iOS/iPadOS search controls. It has
an exact named-test execution guard and a JUnit conversion; the JUnit directory
is included in parity evidence verification and existing failure-artifact globs.
Missing fixture environment, wrong server URL, missing field, wrong result,
unfocused activation, and non-advancing playback all fail the control.
The complete hosted workflow has not been run by this investigation.

`FEATURES.yml` is unchanged. Its evidence-list schema requires a conformance ID;
this UI control is not CONF-41's local/server merge test, so no misleading
CONF-41 evidence entry is added. The root-navigation gap is deliberately left
visible for product work rather than treated as solved by DEBUG setup.

## Activation mutation control

OBSERVED: the non-macOS result Button action was temporarily changed from
`onActivateResult(result.id)` to an empty closure. No test assertion changed.
`build-for-testing` returned `MUTATION2_BUILD_EXIT=0` and
`** TEST BUILD SUCCEEDED **`. The mutation run was gated on that recorded zero
exit code before invoking `test-without-building` against the same DerivedData.

The mutated binary still emitted the exact typed-query and rank-zero label
receipts. After focus was checked and remote Select was pressed, the test failed
at the activation outcome:

```text
XCTAssertTrue failed - Activating rank zero must present Now Playing
Executed 1 test, with 1 failure (0 unexpected) in 30.663 (30.666) seconds
```

OBSERVED completed mutation execution: `RED_FINAL_EXIT=65` and
`** TEST EXECUTE FAILED **`. The exact named-test guard rejected this bundle:
`xcode test execution invalid: named control terminated as Failed, not Passed`,
with `RED_GUARD_EXIT=1`.

The completed mutation run used `-collect-test-diagnostics never`, which Xcode
help documents as controlling verbose diagnostics such as sysdiagnose. An earlier
run reached the same expected failure but stalled during diagnostic collection;
it was terminated with exit 143 and is not used as completed-result evidence.
The retry rebuilt the same no-op successfully and changed no test assertions.

The production action was restored after that failure. The no-op mutation is not
committed. This is an executed behavioral negative control, not a compilation
failure or a rerun of a previously passing binary.

OBSERVED restored build: `RESTORED_FINAL_BUILD_EXIT=0`,
`** TEST BUILD SUCCEEDED **`. A fresh simulator then ran the restored binary:
`GREEN_RESTORED_EXIT=0`, `** TEST EXECUTE SUCCEEDED **`,
`Executed 1 test, with 0 failures (0 unexpected) in 19.830 (19.832) seconds`.
The exact named-test guard again reported `terminal=Passed individual-results=1`,
and the test again observed `progress=0:02 of 0:31->0:03 of 0:31`.

OBSERVED repository validation after restoring the production action:

- `python3 tools/parity_gate.py`: `parity gate valid: 6 feature rows`.
- `python3 tools/verify_ci_policy.py`: `CI policy valid across 6 workflows`.
- `actionlint .github/workflows/apple-ci.yml`: exit 0, no diagnostics.
- Regenerating in `apple/` and comparing the pbxproj byte-for-byte: `cmp` exit 0.
- `git diff --check`: exit 0, no diagnostics.

The production environment fix is the commit "Stop tvOS search-result artwork from
crashing the app"; the passing control, DEBUG setup boundaries, generated target
and CI wiring are the commit "Exercise tvOS search typing and remote activation
against the disposable server". They are named rather than cited by hash because
this branch is rebased onto `main` before merging under `strict: true`, which
rewrites every hash on it - the previous revision of this line cited two hashes
that no longer existed in any published history.
