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
named passing xcresult.

## Realization geometry, OBSERVED 2026-09-06

**Remote CI execution happened and failed on this branch's first hosted run**, on PR #83, run
`34009275978`, job `101422106033`:

```text
DulcetMacTests/DulcetMacAccountConnectAppTest.swift:724: error: ... failed: caught error:
"missingAccessibilityElement("dulcet.search.result.0; count=161; tree=...")"
Test Case '...searchQueryRanksAndActivatesTrackThroughHostedAppUI' failed (6.467 seconds).
```

The dumped tree held `dulcet.search.result.3`, `.2` and `.1` with exactly their expected rank
labels, a `4 results` label and the typed query -- search worked and ranked correctly. Rank zero
alone had no accessibility node. Immediately before the three realized ranks, the tree carried
four bare `NSOutlineRow` placeholder objects with no identifier or label -- the outline table's
generic per-row proxies exist for all four logical rows; only three had a fully-realized
`SwiftUITableRowView`/`AccessibilityNode` subtree with the row's actual content.

### It is not a "not enough room" gap

Reproduced locally by changing only the test's `hostingView.frame` height literal (760 -> 400),
build-for-testing then test-without-building each time, same disposable fixture. 400 failed with
the identical signature, `dulcet.search.result.0` missing, `.1`-`.3` present, in three independent
rebuilds. Instrumenting the failure path (`realizationGeometryDiagnostic`, added by this session)
to report the window frame, hosting view frame, and the resolved table's own `numberOfRows` and
`documentVisibleRect` gave a result that rules out simple undersizing:

```text
height=400 (fails): window=(51, -168, 1180, 652) hostingView=(0, 0, 1180, 600)
                     table.frame=(0, 0, 892, 931) documentVisibleRect=(0, -28, 892, 959)
height=760 (passes): window=(51, 0, 1180, 844)   hostingView=(0, 0, 1180, 792)
                     table.frame=(0, 0, 892, 931) documentVisibleRect=(0, -28, 892, 959)
```

Two findings, both OBSERVED and reproduced across repeated runs:

1. **The window does not stay at the requested size.** Asking for content height 400 does not
   produce a 400pt-tall window -- something in the production view hierarchy enforces a larger
   floor, and AppKit grows the window past the request, shifting its origin negative (the window's
   bottom edge ends up 168pt below the screen's own origin) rather than simply refusing or
   clamping. This means "pick a bigger literal" cannot be verified by reasoning about the
   requested number alone; only the resolved geometry says what the table actually received.
2. **`table.frame` and `documentVisibleRect` are byte-identical between the failing and passing
   run**, and the visible rect (959pt) is already taller than the table's own content (931pt) in
   *both* cases. The final, settled geometry cannot be what decides realization -- if it were,
   these two runs would behave identically. Whatever decides which rows get realized must be
   evaluated against a transient, earlier layout state that this snapshot, taken after
   `layoutSubtreeIfNeeded()` has already run, cannot see. This is why CI can fail at the shipped
   760: that literal is not what makes 760 safe locally, so nothing here shows CI's environment
   must fall on the same side of it.

### Two dead ends, each cheap and worth recording

- **Waiting longer does not recover it.** The existing accessibility-lookup poll already retries
  every 50ms for 5 seconds, calling `layoutSubtreeIfNeeded()` each time. CI's own failure exhausted
  that timeout (6.467s wall time including the earlier waits) with the row still missing, and the
  local height=400 reproduction exhausted the identical timeout the same way. A longer poll is not
  a fix for a state that does not change on its own.
- **Forcing AppKit's `reloadData()` on the resolved table crashes the process**, not merely
  failing to help:
  ```text
  SwiftUICore/Environment+Objects.swift:34: Fatal error: No Observable object of type
  DulcetPresentationStore found. A View.environmentObject(_:) for DulcetPresentationStore may be
  missing as an ancestor of this view.
  ```
  SwiftUI's `Table` backing view cannot be safely driven through public AppKit reload APIs outside
  SwiftUI's own diffing cycle -- this rules out any fix that pokes the resolved `NSTableView`
  directly, however tempting a one-line `reloadData()` looks.

### The recovery that works: a genuine, later resize

At the reproducing height=400 geometry above, calling `window.setContentSize(...)` with a real,
executed size delta *after* the initial layout had already settled, then re-running
`layoutSubtreeIfNeeded()`, brought rank zero's node into existence on the first attempt:

```text
pre-resize:  realized=["dulcet.search.result.1", "dulcet.search.result.2", "dulcet.search.result.3"]
post-resize: realized=["dulcet.search.result.0", "dulcet.search.result.1", "dulcet.search.result.2",
                        "dulcet.search.result.3"]
```

Repeated with an unconditional (not just on-failure) geometry snapshot: identical outcome. This is
consistent with the outline table's realized-row set being decided once, during the window's
initial creation-and-first-layout pass, and never revisited against the settled geometry unless a
separate, genuine resize event fires the AppKit resize-notification chain that keeps an
`NSClipView` and its `NSTableView` in sync. The fix, `growWindowUntilRanksRealize`, performs
exactly this: a bounded (3 attempts, +300pt each), observable resize loop run once the model is
confirmed correct and before any per-rank accessibility lookup. It reports how many attempts were
needed (`MACOS SEARCH UI RESIZE-RECOVERY attempts=N`) rather than only the final pass/fail state,
per CLAUDE.md trap 41 -- a control that cannot prove what it did is not a control.

Verified against both the shipped geometry and the reproduction:

```text
height=760 (already healthy): RESIZE-RECOVERY attempts=0, test passed (0.66s)
height=400 (reproduces CI):   RESIZE-RECOVERY attempts=1, test passed (0.70s)
```

**Not established**: the exact mechanism inside AppKit's private `SwiftUIOutlineTableView` that
decides realization during the first layout pass, or precisely why CI's environment lands on the
losing side of it at the same literal (760) that is comfortably safe on this session's own
machine. Candidate contributors not distinguished from one another: CI's non-Retina backing scale
against this machine's 2.0 (a scale-dependent row-height rounding difference), and CI's slower or
differently-ordered first layout pass. This repository has direct precedent for a superficially
similar "CI-only, text-layout-adjacent" hypothesis (cold vs. warm font-metric cache) being
*refuted* by soak evidence for the capture-flake investigation
([[project_dulcet_capture_h6_cache_warmth]]) -- that history is a reason for caution about
asserting a specific cause here, not a reason to expect the same cause. No further CI run was
available to this session to discriminate further (repository policy: no push to the branch under
investigation), so this is reported as the mechanism's effect and a working, evidenced recovery,
not as a fully attributed root cause.

### Accessibility-only, or also invisible to a sighted user? Not established

This session confirmed the *accessibility* node for rank zero is absent under the reproducing
geometry -- a real VoiceOver user hitting this same layout state could not perceive or reach the
top (best-match) search result through accessibility, independent of whatever a sighted user sees.
Whether the row is *also* absent from the drawn pixels was attempted via
`NSView.cacheDisplay(in:to:)` and was inconclusive: for this layer-backed, SwiftUI-hosted view
hierarchy, an offscreen `cacheDisplay` render does not reliably reproduce the actual composited
window contents (the captured image showed only a single row of content against blank white,
matching neither the passing nor failing geometry's true on-screen appearance), so no claim is
made either way about the visual truth. A proper answer would need a window-level capture (for
example `CGWindowListCreateImage` keyed by the test window's `windowNumber`) and was not attempted
here for lack of remaining budget in this investigation.

### Mutation proof, gated on the BUILD exit code

Ranks 0 and 1 swapped in both `rankedLabels` and `rankedTitles` (not a real query result):

```text
build exit: 0
test exit:  65
"Thirty One Seconds", ...] is not equal to ["Twenty Nine Seconds", "Thirty One Seconds", ...] - Rendered search rank order
dulcet.search.result.0 rendered accessibility text (title, credits, album, kind) mismatch
```

The order assertion, the per-rank accessibility-text check, and the `waitUntil` guard on the
model's own ranked titles all independently named the drift. Reverted afterward; restored file
confirmed to build and pass again (build exit 0, test exit 0) before this fix was considered done.

### Siblings: does this fix apply to iOS, iPadOS, or tvOS?

No, by mechanism, and none of the three sibling tests were changed:

- **tvOS** (`DulcetTVUITests.testSimulatorSearchQueryRanksAndActivatesTrack`) already has its own
  defense, unrelated to this fix: when rank zero's `waitForExistence(timeout: 30)` fails, it
  presses the remote's Down button up to 8 times, re-checking `.exists` after each press. This is
  the right recovery for a remote-driven, focus-based navigation model and needed no change.
- **iOS and iPadOS** (`DulcetiOSUITests`, shared implementation for both) read all four ranks via
  a plain `waitForExistence(timeout: 10)` with no analogous retry, then separately swipe-scroll
  the list to reach the *canary's* row for activation (not rank zero, and only after every rank's
  label was already read). These remain what the prior session called them: architecturally
  exposed, not confirmed broken. This session did not attempt a fix here.
- **Why no fix was ported**: this test hosts the production view in-process via a raw
  `NSHostingView`/`NSWindow` the test itself creates and can resize; the macOS search table is a
  SwiftUI `Table` backed by AppKit's `NSOutlineView`/`NSClipView`. The iOS/iPadOS/tvOS siblings
  drive a real app process through `XCUIApplication` on a simulator, whose search list is a
  `ScrollView`/`LazyVStack` -- a different framework (UIKit-hosted SwiftUI, not AppKit) with a
  different virtualization implementation. `window.setContentSize` has no equivalent there: there
  is no test-owned window to resize, and no evidence ties this AppKit-specific notification-chain
  gap to SwiftUI's iOS/tvOS list virtualization. Applying an unmotivated, unverified "just in case"
  retry to a currently-untested-but-not-observed-failing sibling would add complexity without
  evidence, which is the same standard this fix itself was held to.
