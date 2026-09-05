# macOS hosted search UI evidence

OBSERVED locally on 2026-09-04 with the disposable Navidrome fixture at
`http://127.0.0.1:4533`, macOS 26.6.2, and Xcode 26.6.0.

The control is
`DulcetMacTests/DulcetMacAccountConnectAppTest/searchQueryRanksAndActivatesTrackThroughHostedAppUI`.
It hosts `DulcetMacProduction.makeRootView` in the macOS application test process.
Account setup uses the live Kotlin connector, an in-memory credential store, and the live
server-search adapter. The test drives the production sidebar through accessibility row
selection, focuses the identified native search field, types through `NSApp.sendEvent`,
reads the rendered result's accessibility text, selects rank zero through accessibility,
and sends Return through the app's event path. It observes the playback-controller handoff
and the resulting Now Playing UI through an injected intent witness.

## Reproduction

Use an internal-disk DerivedData directory. Resolve symlinks before choosing it: the usual
Xcode DerivedData location on the test machine resolved onto an external volume, where the
host launched without executing this control. `/private/tmp` was on the internal disk.
Both `DULCET_TEST_CONFORMANCE_*` build settings below are necessary because the scheme maps
them into the test process environment; shell environment variables alone were insufficient.

With the existing disposable fixture running, execute this from the worktree:

```bash
export ANDROID_HOME=/Volumes/AndroidSDK/sdk ANDROID_SDK_ROOT=/Volumes/AndroidSDK/sdk
export DULCET_CONFORMANCE_BASE_URL=http://127.0.0.1:4533
export DULCET_CONFORMANCE_DISPOSABLE=true
for mode in build-for-testing test-without-building; do
  xcodebuild "$mode" \
    -project apple/Dulcet.xcodeproj -scheme DulcetMac -configuration Debug \
    -destination 'platform=macOS' \
    -derivedDataPath /private/tmp/dulcet-search-internal-dd \
    -resultBundlePath "/private/tmp/dulcet-search-${mode}.xcresult" \
    -parallel-testing-enabled NO \
    -only-testing:DulcetMacTests/DulcetMacAccountConnectAppTest/searchQueryRanksAndActivatesTrackThroughHostedAppUI \
    CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- CODE_SIGN_ENTITLEMENTS= \
    DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
    DULCET_TEST_CONFORMANCE_BASE_URL=http://127.0.0.1:4533 \
    DULCET_TEST_CONFORMANCE_DISPOSABLE=true || exit "$?"
done
python3 tools/verify-xcode-test-execution \
  /private/tmp/dulcet-search-test-without-building.xcresult \
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

Final-run artifacts were `/private/tmp/dulcet-search-green-final.xcresult` and
`/private/tmp/dulcet-search-green-final.log`; the negative-control equivalents were
`/private/tmp/dulcet-search-red-activation.xcresult` and `.log`.
These local temporary artifacts are not committed.

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
selected row and the resulting presentation destination. The rendered rank-zero accessibility
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
named passing xcresult. Remote CI execution is unobserved; this branch was not pushed.
