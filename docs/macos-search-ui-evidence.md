# macOS hosted search UI evidence

OBSERVED locally on 2026-09-04 with the disposable Navidrome fixture at
`http://127.0.0.1:4533`, macOS 26.6.2, and Xcode 26.6.0.

The control is
`DulcetMacTests/DulcetMacAccountConnectAppTest/searchQueryRanksAndActivatesTrackThroughHostedAppUI`.
It hosts `DulcetMacProduction.makeRootView` in the macOS application test process.
Account setup uses the live Kotlin connector, an in-memory credential store, and the live
server-search adapter. The test drives the production sidebar through accessibility row
selection, focuses the identified native search field, types through `NSApp.sendEvent`,
reads every rendered rank's accessibility text, selects the canary's rank through
accessibility, and sends Return through the app's event path. It observes the
playback-controller handoff and the resulting Now Playing UI through an injected intent
witness.

REVISED 2026-09-05: the control previously drove a query matching exactly one row. With a
single result, "rank zero" and "the only row" are the same assertion, and so are "activate
the row that was pressed" and "activate the first result", so the rank threaded through the
result view was unobservable. The query now matches four rows and the canary sits at rank
two. Every observation below that names a single result or rank zero describes that earlier
control and is retained as history, not as a description of the current one.

## Reproduction

Use a DerivedData directory on the same physical volume as the system, and resolve symlinks
before choosing it rather than trusting the path: an application-hosted macOS test whose
DerivedData resolves onto an external volume launches its host and then executes nothing,
which reports as a pass rather than as an error. Both `DULCET_TEST_CONFORMANCE_*` build
settings below are required because the scheme maps them into the test process environment;
shell environment variables alone do not reach it.

An Android SDK must be discoverable through `ANDROID_HOME`/`ANDROID_SDK_ROOT`, because the
Xcode build invokes Gradle through a run-script phase.

With the existing disposable fixture running, execute this from the worktree:

```bash
DERIVED_ROOT="$(mktemp -d)"
export DULCET_CONFORMANCE_BASE_URL=http://127.0.0.1:4533
export DULCET_CONFORMANCE_DISPOSABLE=true
for mode in build-for-testing test-without-building; do
  xcodebuild "$mode" \
    -project apple/Dulcet.xcodeproj -scheme DulcetMac -configuration Debug \
    -destination 'platform=macOS' \
    -derivedDataPath "$DERIVED_ROOT/dulcet-search-dd" \
    -resultBundlePath "$DERIVED_ROOT/dulcet-search-${mode}.xcresult" \
    -parallel-testing-enabled NO \
    -only-testing:DulcetMacTests/DulcetMacAccountConnectAppTest/searchQueryRanksAndActivatesTrackThroughHostedAppUI \
    CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- CODE_SIGN_ENTITLEMENTS= \
    DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
    DULCET_TEST_CONFORMANCE_BASE_URL=http://127.0.0.1:4533 \
    DULCET_TEST_CONFORMANCE_DISPOSABLE=true || exit "$?"
done
python3 tools/verify-xcode-test-execution \
  "$DERIVED_ROOT"/dulcet-search-test-without-building.xcresult \
  DulcetMacTests.DulcetMacAccountConnectAppTest \
  searchQueryRanksAndActivatesTrackThroughHostedAppUI
```

Use fresh result-bundle paths for subsequent runs; Xcode refuses to overwrite them.

## Observed red and green

The negative control temporarily removed only `onActivateResult(id)` from the macOS
search table's production `primaryAction` in `DulcetPlayerSearchErrorViews.swift`.
After rebuilding, the unchanged test failed in **10.596 seconds**, exit 65:

```text
Return on dulcet.search.result.0: queueReplacements=0 state=searchResults nowPlaying=nil; expected one search queue and UI Playback Canary
XCTAssertEqual failed: ("0") is not equal to ("1") - Return on rank zero must replace/play exactly one queue
** TEST EXECUTE FAILED **
```

The named-test guard rejected that result:

```text
xcode test execution invalid: named control terminated as Failed, not Passed
```

The production source was restored byte-for-byte (empty `git diff`), rebuilt, and the
same control passed in **0.600 seconds**, exit 0:

```text
MACOS SEARCH UI OBSERVED query=typed rank0=rendered result-count=1 activation=return queue-replacements=1 source=search now-playing=UI Playback Canary
** TEST EXECUTE SUCCEEDED **
xcode test execution valid: test=DulcetMacTests.DulcetMacAccountConnectAppTest/searchQueryRanksAndActivatesTrackThroughHostedAppUI terminal=Passed individual-results=1
```

## Ranked rendering, OBSERVED 2026-09-05

The four-row query passed with build exit 0 and test exit 0, emitting the rendered rank
order, the queue the activation built, and the index it started at:

```text
MACOS SEARCH UI OBSERVED query=typed ranks=["Thirty One Seconds", "Twenty Nine Seconds", "UI Playback Canary", "Threshold Boundary"] result-count=4 activated-rank=2 activation=return queue=["Thirty One Seconds", "Twenty Nine Seconds", "UI Playback Canary"] start-index=2 queue-replacements=1 source=search now-playing=UI Playback Canary
Executed 1 test, with 0 failures (0 unexpected) in 0.716 (0.717) seconds
```

**FINDING, OBSERVED: the rendered order is the app's, not the server's.** For this query the
server returns one album and three songs, and its response lists the album ahead of every
track. The app re-ranks by match quality, then by kind with tracks ahead of albums, then by
the order the server returned them, so the album that arrives first renders last. The
rendered list is four rows in an order the server never emits, which is why the control
asserts the whole order rather than assuming the server's.

Only tracks are playable, so the activated queue is the ranked list without its album row,
and the pressed row's index in that queue is two. Rank two is also where the canary renders;
those are separate facts and the control asserts them separately.

## Identifier mutation control, OBSERVED 2026-09-05

The macOS row title's rank identifier was temporarily replaced with the constant
`dulcet.search.result.0`, so that every row claimed rank zero. No test assertion changed.
`build-for-testing` returned exit 0 and `** TEST BUILD SUCCEEDED **`; the mutation run was
gated on that recorded zero exit before `test-without-building` was invoked against the same
DerivedData, because a mutation that does not compile re-runs the previous binary and reports
a pass.

`test-without-building` returned exit 65 with two failures. The first named the wrong row at
rank zero; the second could not resolve rank one at all, and its diagnostic tree recorded all
four rows carrying the same identifier:

```text
XCTAssertEqual failed: ("Optional("Threshold Boundary, Dulcet Fixtures, Album")") is not equal to ("Optional("Thirty One Seconds, Dulcet Fixtures - Threshold Boundary, Track")") - dulcet.search.result.0 rendered accessibility text (title, credits, album, kind)
failed: caught error: "missingAccessibilityElement("dulcet.search.result.1; ...
Executed 1 test, with 2 failures (1 unexpected) in 5.642 (5.643) seconds
```

The production source was then restored, verified by an empty `git diff` over the framework
sources. The mutation is not committed. Under the previous single-result query this same
mutation left the control green, which is the coverage gap it was written to close.

Each run writes an `.xcresult` bundle and a log beside it, for the green run and for the
negative control alike. They are local scratch artifacts and are deliberately not committed;
CI publishes its own from `RUNNER_TEMP`.

## Accessibility findings and scope

OBSERVED: without enhanced accessibility, the hosting root exposed no accessibility
children. Enabling `AXEnhancedUserInterface` materialized SwiftUI `AccessibilityNode`
objects. Those nodes implement object-valued Objective-C accessibility getters but do not
conform to the complete `NSAccessibilityProtocol`, and they are neither `NSView` nor the
concrete `NSAccessibilityElement` class. The former legacy walker therefore could not
reach the declared identifiers. The replacement checks getter selectors, traverses native
hosting boundaries and accessibility children, deduplicates objects, and uses the minimal
`NSAccessibilityElementProtocol` for its typed frame getter. The enhanced-accessibility flag
is restored after the test.

OBSERVED: Swift's typed `accessibilityRows()` bridge crashed with
`Expected NSAccessibilityRow but found NSOutlineRow`. Reading the public Objective-C getter
as an object array and invoking `setAccessibilitySelectedRows:` avoids that incompatible
protocol-array bridge. This changes the app's actual selection; the test asserts the native
selected row and the resulting presentation destination. Each rendered rank's accessibility
text includes title, credits, album, and kind, all checked against the fixed fixture text.

OBSERVED: pointer attempts reached the correct outline row but did not select it while the
host reported `active=false key=false`. The final control uses accessibility selection and
Return, with native field/table focus, instead of relying on foreground desktop mouse input.

Deliberately outside this control: double-click delivery, audio decoding/output, device UI,
local/server merge correctness, and feature-status promotion. Account connection is setup;
this control does not claim to drive the connection form. The queue observation stops at the
production presentation-to-playback intent boundary. The injected witness, rather than the
production audio engine, supplies the subsequent Now Playing presentation.

OBSERVED validation:

```text
python3 tools/parity_gate.py
parity gate valid: 6 feature rows
python3 tools/verify_ci_policy.py
CI policy valid across 6 workflows
actionlint .github/workflows/apple-ci.yml
# exit 0, no diagnostics
```

The workflow now runs the control beside the existing macOS app tests and requires its exact
named passing xcresult. Remote CI execution is unobserved: no apple-ci run has reported on this branch yet.
