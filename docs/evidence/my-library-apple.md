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
Native app/UI targets compiled on all Apple platforms; Mac runtime remains unproven.

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
XCTest authorization.

## Limits

Visual inspection found that the catalog root under Browse still displays its legacy Library title.
At the largest iPhone text setting, the existing catalog section icons crowd the multiline labels;
the iPad sidebar also wraps narrow labels. The passing interaction cases do not claim these layouts
are polished. The iPhone album capture is scrolled to show Add at the largest size.


The unsigned Mac fixture uses the existing DEBUG disk credential store under a per-test UUID.
Its relaunch proves durable My Library and outbox behavior with that account store; it does not
claim signed, entitled Keychain persistence. The test host compiles the production app sources
under a disposable bundle identity. iPhone, iPad and TV use their normal simulator app targets.

The core song facade currently lacks artist IDs and offline playability. Membership actions are
available on unavailable tracks; this shell does not infer offline playback availability.
`FEATURES.yml` remains partial until required CI runs provision and execute this runtime suite.
Local runtime results, when recorded, do not assert that CI has executed the new tests.
