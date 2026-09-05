# tvOS search UI investigation

OBSERVED on 2026-09-04: this investigation has **not** produced runtime evidence
for `platform-observed-search-activation`. It also has **not** established that
tvOS text entry is unsupported. The runtime experiment remains pending an
exclusive simulator slot. No feature status or promotion condition changes are
justified by these findings.

## Source and SDK observations

OBSERVED by reading `apple/DulcetTV/DulcetTVApp.swift`: the tvOS entry point
creates the production presentation store and root view. It has no equivalent
of the iOS DEBUG account-connection launch-argument hook.

OBSERVED by reading `apple/DulcetKit/Sources/DulcetKit/DulcetRootView.swift`:
its tvOS branch renders `DulcetStateSurface` inside a `NavigationStack`;
`DulcetSidebar` is excluded on tvOS. The state surface renders Search when the
selected destination is `.search`, but this root supplies no tvOS navigation
control for selecting that destination. The library's connection button selects
Settings. This is a source-level reachability finding, not a runtime focus test.

OBSERVED in the AppleTVSimulator XCUIAutomation headers supplied by Xcode
26.6.0: `XCUIElement.typeText` is declared without a tvOS unavailability marker;
`tap` is explicitly `API_UNAVAILABLE(tvos)`. `XCUIRemote` exposes directional,
Select, and Menu button presses. Header availability alone does not prove that
text synthesis succeeds with the tvOS system keyboard.

OBSERVED: a temporary `DulcetTVUITests` bundle compiled a probe that launched
DulcetTV, located `dulcet.search.field`, required `hasFocus`, pressed the remote
Select button, called `field.typeText("UI Playback Canary")`, and asserted the
field's exact value. The temporary DEBUG hook connected the production store
to the disposable loopback fixture, then selected Search when account connection
completed. It supplied no query, ranked results, or playback state. The probe
ended with an unconditional failure stating that activation had not been exercised.
The temporary harness was removed from the tracked project after the build.

Build command, with `DERIVED_ROOT` resolving onto the system volume and the
Android SDK supplied through `ANDROID_HOME` and `ANDROID_SDK_ROOT`:

```sh
xcodebuild build-for-testing \
  -project apple/Dulcet.xcodeproj -scheme DulcetTV -configuration Debug \
  -destination 'generic/platform=tvOS Simulator' \
  -derivedDataPath "$DERIVED_ROOT" -parallel-testing-enabled NO \
  -only-testing:DulcetTVUITests CODE_SIGNING_ALLOWED=NO
```

OBSERVED output: `BUILD_EXIT=0` and `** TEST BUILD SUCCEEDED **`.
This was a build, not a green test run. No test-without-building command ran,
no test execution count exists, and no activation mutation ran.

## Remaining experiment and nearest honest instrument

OBSERVED: simulator enumeration showed another project's simulator booted.
The required single-simulator constraint prevented starting tvOS alongside it;
permission to shut it down was requested and had not been received when these
findings were recorded. No simulator was borrowed or shut down. This operational
blocker says nothing about tvOS's text-entry capabilities.

ASSUMED, pending runtime measurement: the nearest candidate is an XCUITest
bundle targeting DulcetTV, using remote focus/Select and `typeText` on the
production field. A DEBUG hook may establish the disposable account and initial
Search destination, but those setup boundaries must be reported: it would not
prove ordinary navigation to Search or interactive account entry. A query must
never be injected into the store in a control claiming typed-query evidence.

If text synthesis fails at runtime, preserve that exact failure and the focused
keyboard accessibility tree. Then attempt system-keyboard entry using remote
navigation and Select; a failure of `typeText` alone does not establish that all
tvOS UI entry is unavailable. Only actual failure of the available entry paths
can support the requested negative finding.

If entry succeeds, require the exact field value, the rank-zero button's rendered
canary title and Track kind, real remote focus and Select on that button, and
Now Playing's title and Search queue source. Require playback evidence according
to the observed tvOS surface rather than assuming an iOS slider exists there.
Build an activation no-op mutation successfully before running its negative
control; require a failure at the activation outcome, then restore and rebuild.
Finally wire the passing control into the disposable-fixture CI lifetime with
`tools/verify-xcode-test-execution` guarding its exact name. Until then, no
passing target, evidence entry, or promotion claim is appropriate.
