# Apple My Library proof

The app offers Library and Browse only when the connected account advertises `dulcetLibrary` v1.
Library displays the account publication; Browse retains the catalog reader. The core owns
optimistic membership, durable pending writes and their reconnect replay.

## Evidence status

OBSERVED on the final product tree: `swift test --no-parallel` in `apple/DulcetKit` passed
299 tests across 10 suites, including capability/navigation and current-account membership tests.
All 27 argument-free Python gates invoked by `apple-ci` passed; the host-pressure live control
passed on retry with four samples. XcodeGen regeneration and its self-test passed.

OBSERVED on 2026-10-13 against the real local Dulcet Server and disposable Navidrome:

| Platform | Membership / persistence / offline replay | Navidrome fallback | Visual variants |
| --- | --- | --- | --- |
| iPhone 17 Pro simulator | PASS (74.610 seconds) | PASS (10.664 seconds) | PASS; default, dark, largest text |
| iPad Pro 13-inch (M5) simulator | PASS (67.618 seconds) | PASS (10.117 seconds) | PASS; default, dark, largest text |
| Apple TV 4K (3rd generation), 1080p simulator | PASS (74.614 seconds), including remote press and hold | PASS (6.655 seconds) | PASS; default, dark, largest text |
| Mac | Pending: system notification dialog; runtime skipped at the operator's direction | Pending | Pending |

Each behavior case observes Add in Browse, the album in Library, a saved-account relaunch,
Remove, an offline Add, the pending write surviving an offline relaunch, and real-server membership
after reconnect. Independent direct server reads distinguish pending local state from a completed
server write. iPhone and iPad each passed all three cases in their final full runs. TV's first run
failed the test helper's post-relaunch focus navigation; after correcting focus on context-menu
cells and the horizontal fallback from a section with no downward neighbor, the affected behavior
case passed. Its visual and fallback cases passed separately. No full TV bundle pass is claimed.

The corrected visual runs use DEBUG-only light/dark launch flags at the SwiftUI root; the original
`AppleInterfaceStyle` launch argument did not change the simulator's appearance. The actual JPEGs
were inspected to confirm dark rendering. Nine compressed app JPEGs per platform are retained
outside the repository, with Library, Browse and album Add captures in the three variants.
The additional iPhone largest-text visibility case passed (21.375 seconds); it scrolls the album
to keep Add above the tab bar, asserts the visible button bounds, and replaces that capture.
The CI driver uses short, direction-aware drags with an end hold for this capture. A full swipe
on the hosted iPhone moved Add from below the visible area to above it; continuing upward
could not recover it. The bounded helper retains the hittable and above-tab-bar assertions and
also requires Add below the navigation bar.
Native app/UI targets compiled on all Apple platforms; Mac runtime remains unproven.
After the DEBUG appearance change, the final DulcetKit run again passed all 299 tests across
10 suites; the affected string-catalog and contrast source policies passed.

Cleanup was observed after the runs: five fixture processes stopped by their recorded PIDs,
all seven owned simulator leases released (the interrupted unleased clone was reaped),
DerivedData/build capacity leases released, and local app/package build products deleted.
`automationmodetool` reported Automation Mode disabled and authentication required to enable it.


## Runtime suite

`apple/DulcetMyLibraryUITestSupport/DulcetMyLibraryUITests.swift` is shared by
`DulcetiOSUITests` (iPhone and iPad), `DulcetTVUITests`, and `DulcetMacUITests`.
It drives the production UI against separate disposable users on a real local Dulcet Server:
Browse album Add, Library membership, durable relaunch, Remove, offline Add, and reconnect replay.
Direct reads bypass each platform's loopback network proxy to distinguish optimistic state from
server state. A separate disposable Navidrome is the positive catalog control for the absent split.
The TV variant also uses the remote's press and hold context menu.

The visual test attaches compressed JPEGs of Library, Browse and an album with Add in light,
dark and the largest Dynamic Type setting. The local proof retains these images outside the repository; no screenshots are committed.

### Fixture setup

Generate the copyright-free music with `python3 tools/seed-corpus --runtime native --output <music-directory>`.
Run Dulcet Server with that music on loopback port 4783 and create the disposable accounts below,
each with password `fixture-password`. Start `python3 tools/my-library-ui-proxy --port <proxy-port>
--upstream-port 4783` for each platform being tested. These are finite fixture processes; stop them
after the run. The proxy's control route cuts only app traffic while independent assertions keep
reading the real server.

| Platform | Disposable user | Proxy port | Scheme / UI target |
| --- | --- | --- | --- |
| iPhone | apple-iphone | 4786 | DulcetiOS / DulcetiOSUITests |
| iPad | apple-ipad | 4787 | DulcetiOS / DulcetiOSUITests |
| Mac | apple-mac | 4784 | DulcetMac / DulcetMacUITests |
| Apple TV | apple-tv | 4788 | DulcetTV / DulcetTVUITests |

The fallback case also requires disposable Navidrome on port 4785 with the same corpus and
published canary credentials `dulcet-admin` / `dulcet-ci-canary-password`.
Run Debug `xcodebuild test` with the selected scheme, an explicit simulator destination, no parallel
testing, and `-only-testing:<UI target>/DulcetMyLibraryUITests`. Simulator app signing must be enabled:
unsigned builds can reach the server but fail to save the account in Keychain. Use a local
`CONFIGURATION_BUILD_DIR` if build products on removable storage trigger an OS access prompt.
The Mac scheme uses its dedicated proof host; runtime automation still requires the host's normal
XCTest authorization. The Mac driver recognizes the Python fixture's local-network permission
prompt, allows it, and requires dismissal. It selects the identified sidebar row rather than a
similarly named section heading. The app clamps its preferred 1180 × 760 window size to the
visible display with room for window chrome. The driver requires each launched or relaunched
window to stay on screen before it proceeds, retaining every membership and server-read assertion.
The hosted failure had restored a 1180-point window at x = -78 on a 1024-point display; an
oversized preferred window must not make the sidebar or title-bar controls unreachable.
The album helper waits for the catalog scroll view containing album cards before it scrolls.
The first Mac scroll view is the sidebar: scrolling it while catalog publication was pending
hid Library above the viewport and caused the subsequent membership navigation to fail.

## Limits

The earlier Round 2 captures recorded a legacy Library title under Browse and crowded or wrapped
catalog/sidebar labels at the largest text size. Those captures precede subsequent UI commits;
they are historical evidence. The Round 3 functional checks below do not claim a new visual matrix.
The earlier iPhone album capture is scrolled to show Add at the largest size.


The CI Mac proof host is signed ad hoc with entitlements cleared and uses the existing DEBUG
disk credential store under a per-test UUID.
Its relaunch proves durable My Library and outbox behavior with that account store; it does not
claim entitled Keychain persistence. The test host compiles the production app sources
under a disposable bundle identity. iPhone, iPad and TV use their normal simulator app targets.

The core song facade currently lacks artist IDs and offline playability. Membership actions are
available on unavailable tracks; this shell does not infer offline playback availability.
Required CI provisions this recipe through `tools/ci/with-my-library-fixture` and executes it
through `tools/ci/run-my-library-proofs`. The conformance-core job runs Mac and TV; the existing
iPhone/iPad matrix runs each device separately. The helper uses the job's disposable Navidrome
on 4533 through a loopback proxy on 4785 for the fallback control. `:server:installDist` supplies
the server binary from the checkout; fresh data and all fixture processes live only for the proof.
The iPad driver waits directly on the identified sidebar text, including during the initial
account publication; choosing a label-based cell before publication can leave a wait on an
unlabeled cell query. Explicit selectors exclude the iPhone-only visibility case on other devices. Each selected case
must appear once, Passed, in the individual xcresult records before JUnit is generated. Each
platform gets its own JUnit class name so the required aggregator resolves distinct UI evidence.
The visual case's nine compressed JPEG attachments are exported to a separate capture directory
and retained alongside the JUnit in the existing parity artifact.
`FEATURES.yml` remains partial until these required CI runs have executed successfully.
Local runtime results, when recorded, do not assert that CI has executed the new tests.


## Round 3 conformance recovery — 2026-10-13

Base `7dd989f0` already contains core fix `53dc6e47`; no further rebase was needed. Investigation
used the failures from apple-ci run `38403774798` and a disposable Navidrome 0.63.2 corpus.
Navidrome does not advertise My Library.

- **OBSERVED product bug, iPad:** opening Search from a pushed reader album crashes with
  `SwiftUI.AnyNavigationPath.Error.comparisonTypeMismatch`. The split column retained navigation
  state while the reader and snapshot stacks supplied different route types. Commit `c473bb8d`
  gives both stacks one route type and captures the originating destination in the reader binding.
  The saved-account Library/Search/favourite test passes unchanged.
- **OBSERVED test-path bug, iPhone:** at the largest text size, the first Skip Probe row is
  hittable before the second row is realized below the larger header. The helper now reveals
  both rows before checking their order, retaining its six-swipe bound, first-row tap, playback
  and notice-clearance assertions. Product scrolling and playback were not changed for this case.
- **OBSERVED test-readiness bug, Mac:** the Play node is a SwiftUI `AccessibilityNode`; its
  `NSAccessibilityProtocol` cast returns nil. The old test treated nil as enabled. With a one-second
  playlist-response delay it pressed the loading node with zero track rows, then reached the
  original 20-second “now playing=nil” timeout. Failure-stack reporting stalled; a process sample
  captured that `XCTFail`, and the owned diagnostic was stopped. Commit `6ad38bc9` reads the actual
  AX enabled getter on a freshly queried Play node. The product's disabled-while-loading behavior
  and the test's first-entry, queue-order and playback-progress assertions remain intact.

**OBSERVED local Debug results:** iPad 3/3 (saved account, ranked search, server star ratings);
iPhone 4/4 (largest-text short and long skip notices, ordinary skip, saved account); Mac 4/4
(playlist playback/order, playlist edits, stars and hearts). Each xcresult summary reports Passed,
zero failures and zero skips. All four Mac cases ran with the one-second playlist-response delay.
The exact previously failing cases ran in these bundles. The class-only Mac selector ran zero tests
and is excluded from these counts; the validated bundle selected all four methods explicitly.

**OBSERVED final gates:** all 27 argument-free apple-ci Python checks passed, and
`swift test --no-parallel` passed 299 tests in 10 suites. Diagnostics and result bundles remain
outside the repository. These are local results; a new apple-ci dispatch will establish CI results.
The pending My Library Mac notification-dialog runtime proof and signed receipt renewals from
Round 2 remain pending; this hosted Mac conformance proof does not replace them.
