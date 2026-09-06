# macOS search navigation minimum-height attribution — 2026-09-06

1. **Cause — OBSERVED**, using the existing native/AX instrument (`DulcetMacAccountConnectAppTest.swift:771`) plus temporary pass-through SwiftUI `Layout` logging.
`DulcetPlayerSearchErrorViews.swift:320` allows the summary to wrap; the old header modifier at `:330` fixed its vertical ideal size.
Command: `rg 'CAUSE SIZE (header-content|header-fixed|detail|split)' /tmp/dulcet-search-cause-header-test.log`.
Actual output (abridged): `header-content proposal=(width:0,height:nil) result=(0,1041)`; `header-fixed proposal=(0,0) result=(0,1041)`.
Actual output (abridged): `detail proposal=(0,0) result=(56,1138)`; `split proposal=(1024,625) result=(1024,1138)`.
OBSERVED: the table (`DulcetPlayerSearchErrorViews.swift:390`) accepts a zero-height proposal; the oversized minimum originates in the fixed header, not the table's ideal height.
OBSERVED: `DulcetRootView.swift:30` centers that oversized split; `(625-1138)/2 = -256.5`.
Command: `rg 'ANCESTOR.*NavigationSplitRepresentable|ROW table=SwiftUIOutlineTable.*index=0' /tmp/dulcet-search-cause-baseline-test.log`.
Actual output by phase: initial-library height792; idle-before-frame792; idle-after-frame625; results frame `(0,-256.5,1024,1138)`.
Actual output: table `visible.y=33.5`; row0 rect `(0,5,759,24) intersectsVisible=false native=nil`; both cells nil; rank0 lookup fails.
OBSERVED control from the prior instrument record: frame applied AFTER load has the SAME host1138/root625 geometry, but retained row0 height36 intersects visibility and passes.
ASSUMED: SwiftUI's private automatic-height virtualization tests provisional row geometry before measuring it; its internal cache/update schedule was not captured.

2. **Fix — OBSERVED:** `DulcetPlayerSearchErrorViews.swift:330` now uses `.layoutPriority(1)` so the header receives space before flexible results without imposing its wrapped ideal as a minimum.
OBSERVED proposal control: detail honors625; split honors625; the clean native instrument reads host `(0,0,1024,625)` and row0 native present.
No root sizing, table implementation, identifier, row selection, or scrolling behavior was changed.

3. **Recovery — OBSERVED:** `growWindowUntilRanksRealize` and its call are removed; it has no remaining justification.
Command: `rg growWindowUntilRanksRealize apple` → no matches, exit1. The existing measurement instrument remains unchanged.

4. **Regression — OBSERVED:** `DulcetMacAccountConnectAppTest.swift:125` applies frame `(0,60,1024,677)` BEFORE typing; `:153` probes and `:159` asserts navigation containment before the original rank lookups.
Exact runner: `/tmp/dulcet-cause-evidence/run.sh`; uses internal `/private/tmp/dulcet-search-internal-dd`, `-parallel-testing-enabled NO`, and the single named hosted search test.
For each sample: `/bin/zsh /tmp/dulcet-cause-evidence/run.sh cause-<sample>-build build-for-testing`; only after exit0, same command with `cause-<sample>-test test-without-building`.
Negative control, sample `mutation`: only restore the old header modifier, leaving the final test unchanged. BUILD exit0, `** TEST BUILD SUCCEEDED **`; TEST exit65.
Actual output: `Executed 1 test, with 3 failures`; containment `-256.5 < 0` and `881.5 > 625`; rank0 lookup fails, native row0 nil.
Restored fix, sample `final`: BUILD exit0, `** TEST BUILD SUCCEEDED **`; TEST exit0, `** TEST EXECUTE SUCCEEDED **`.
Command: `python3 tools/verify-xcode-test-execution /tmp/dulcet-search-cause-final-test.xcresult DulcetMacTests.DulcetMacAccountConnectAppTest searchQueryRanksAndActivatesTrackThroughHostedAppUI`.
Actual output: `terminal=Passed individual-results=1`. Earlier clean fixed sample also passed; each variant ran once, not an intermittency-rate estimate.
Command: `rg -c 'XCTAssertEqual\(' apple/DulcetMacTests/DulcetMacAccountConnectAppTest.swift /tmp/dulcet-cause-original-test.swift` → `26` in both; whole-order and every original per-rank assertion retained.
Command: `swift test --package-path apple/DulcetKit --scratch-path /private/tmp/dulcet-cause-package --jobs 2 --filter searchFieldResolvesToIntrinsicHeightAcrossFlexibleContentStates` → exit0, `Test run with 1 test in 0 suites passed` (idle/empty intrinsic heights preserved).

5. **Reachability verdict — OBSERVED for this Mac's shipping defaults:** the oversized-host defect is user-reachable; the missing-node reproduction is specific to the test window's geometry in these measurements.
A temporary observable store injection supplied the same fixture to the app's actual `WindowGroup` (`DulcetMacApp.swift:19`); no replacement window or product layout was used. All injection/proposal code was removed.
Command: `rg 'CAUSE REAL WINDOW|TABLE SwiftUIOutlineTable|ROW table=SwiftUIOutlineTable.*index=0' /tmp/dulcet-search-cause-real{window,minimum}-test.log`.
Actual output, ORIGINAL product: real window min `(900,652)`; frame1024x677 → root677, host1190, table visible.y7.5, row0 native present; BUILD0/TEST0.
Actual output: restored window1024x677 then pre-query resize to1180x652 (allowed minimum) → visible.y20, row0 native present; BUILD0/TEST0. One run per size.
The test's titled NSWindow (`DulcetMacAccountConnectAppTest.swift:53`) instead has root625 at frame677; that content/title-bar geometry clips the provisional row entirely. The real scene does not enforce the inflated navigation minimum by growing its window.
ASSUMED beyond these observations: other OS versions, display scales, localization and constrained screens may change reachability. No universal claim that real VoiceOver users cannot encounter it, or that they cannot work around it, is established.
No interactive mouse-drag, full launch-at-minimum, or VoiceOver navigation trial was performed; the existing real scene was resized before typing and a restored small scene was observed. No CI run was requested.
