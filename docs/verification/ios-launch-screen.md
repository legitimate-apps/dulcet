# iPhone full-display regression verification

Measured on an iPhone 17 Pro simulator running iOS 26.5 (23F77), with Xcode 26.6
(17F113) and XcodeGen 2.46.0. Baseline: `origin/main` at `4022bcc`, plus the new
geometry test. Before and after used the same simulator and account-connect layout
fixture. No account or server is required by the geometry test.

## Configuration

The installed Xcode's `CoreBuildSystem.xcspec` declares
`INFOPLIST_KEY_UILaunchScreen_Generation` as a Boolean named “Launch Screen
(Generation)”, generating a `UILaunchScreen` dictionary when
`GENERATE_INFOPLIST_FILE` is enabled. See also Apple's
[build-settings reference](https://developer.apple.com/documentation/xcode/build-settings-reference#Launch-Screen-Generation).

The fix adds that setting only to `DulcetiOS.settings.base` in `apple/project.yml`.
Regeneration adds precisely two lines to `project.pbxproj`: the setting is `YES`
in DulcetiOS Debug and Release. Release `-showBuildSettings` confirms the effective
setting; the tested Debug product contains the synthesized dictionary. Comparing
the built plists before and after, `UILaunchScreen` is the only changed key.
Xcode 26.6 emits it as `{"UILaunchScreen": {"UILaunchScreen": {}}}`.

Both `DulcetMac` and `DulcetMacRelease` use `INFOPLIST_FILE = DulcetMac/Info.plist`;
the explicit YAML `info:` declaration belongs to `DulcetMacRelease`.
No tvOS or macOS target setting changes. `TARGETED_DEVICE_FAMILY` remains `1,2`.
No additional iPadOS setting is proposed: it shares this application target.
iPadOS multitasking, rotation, and physical devices were not measured in this run.

## Actual geometry

All window measurements are points. UIKit was queried in the running app with
LLDB using `[[UIApplication sharedApplication] windows]`; XCUITest separately
recorded `app.windows.firstMatch.frame` and the display screenshot image size.

| Measurement | Before | After |
| --- | --- | --- |
| UIKit app window `(x, y, width, height)` | `(0, 0, 320, 480)` | `(0, 0, 402, 874)` |
| XCUITest app window `(x, y, width, height)` | `(0, 135.5, 402, 603)` | `(0, 0, 402, 874)` |
| Display image size in XCUITest | `402 × 874` | `402 × 874` |

The former 402 × 603 region was itself letterboxed on the 402 × 874 display.
The fix removes both the 320 × 480 compatibility canvas and its scaled, vertically
inset presentation.

`testIPhoneWindowUsesFullDisplay` checks the window origin and display aspect ratio,
without hardcoding one phone's resolution. Against the baseline, one test executed
and failed two assertions: vertical origin and aspect ratio. With the fix it passes.
The Apple CI workflow runs it in a separate result bundle and checks that exactly
one named test executed, rejecting a zero-test success.

## Existing search test

`testSimulatorSearchQueryRanksAndActivatesTrackOnIPhone` passed against an isolated
Navidrome 0.63.2 instance with the repository's 314-file synthetic corpus and pinned
FFmpeg 9.0.1. That particular passing transcript contains no swipe. The scrolling workaround
remains in the test, with its conditional reachability check unchanged; only its
comment changed. This does not establish that the workaround is never exercised
on other runs or layouts. No coordinate correction or weakened assertion was needed. The comment now describes the conditional behavior instead of presenting
the legacy short viewport as the normal compact layout.

## Validation results

- `./gradlew :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest :core:bundleAndroidMainAar :core:licensee`
  passed: 184 JVM tests in 26 suites, 180 Android host tests in 25 suites, zero
  failures/errors/skips. 44 tasks: 24 executed, 19 from cache, one up-to-date.
- DulcetiOS Debug simulator build and selected tests passed: 76 DulcetKit Swift
  Testing tests, one app-hosted iOS Keychain test, and two UI tests (geometry and
  live search), totaling 79 tests. The XCTest zero-test preamble for the Swift
  Testing bundle is not its result: the Swift Testing terminal record explicitly
  reports 76 passing tests.
- The separate CI-shaped geometry invocation passed one additional test execution;
  `tools/verify-xcode-test-execution` confirmed exactly one named result.
- All simulator tests used `-parallel-testing-enabled NO`. The build products were
  on a resolved internal-disk DerivedData directory, and executed-test counts were
  checked explicitly.
- `python3 tools/verify_ci_policy.py`: passed, six workflows.
- `python3 tools/parity_gate.py`: passed, six feature rows.
- `python3 tools/verify_os_floors.py --configuration-only`: passed; macOS 14.0,
  iOS/tvOS 17.0 unchanged.
- `git diff --check`: passed.

`FEATURES.yml` needs no change. This fixes the presentation of the existing iOS
surface without adding a capability, promoting a platform cell, or changing any
conformance claim. macOS and tvOS were not built or tested for this fix. Release
settings were inspected; no Release archive was built.

## Content-proof review follow-up

An independent blank-root mutation showed the original geometry-only test passed
with an empty full-size window. The test now also queries the account heading
(`dulcet.account-connect.title`) and server-address text field
(`dulcet.account-connect.server-address`) **under the measured window**. Each must
exist, have positive width and height, be hittable, and have its entire frame
contained in the measured window. These identifiers were confirmed in the fixture's
accessibility tree and again in the restored passing run.

Verification used one iPhone 17 Pro simulator on iOS 26.5 (23F77), Xcode 26.6
(17F113), after the branch rebase onto `14a9eb5`. Mutations ran in a disposable copy
of commit `10ac759`; the final restored run built the unchanged commit from the
branch worktree. The test source was identical in all three runs.

| Case | Only mutation | Executed | Result |
| --- | --- | --- | --- |
| Blank root | Replace `DulcetRootView(store: presentation)` with `Color.clear` | 1 | Failed, 2 assertion failures |
| Missing launch declaration | Remove the iOS launch-screen generation setting and regenerate | 1 | Failed, 3 failures |
| Restored application | No mutation | 1 | Passed, 0 failures |

Blank-root output (source path prefixes omitted):

```text
DULCET DISPLAY GEOMETRY window=(0.0, 0.0, 402.0, 874.0) displayImageSize=(402.0, 874.0)
failed - Full-display content missing from measured window: dulcet.account-connect.title
failed - Full-display content missing from measured window: dulcet.account-connect.server-address
Executed 1 test, with 2 failures (0 unexpected)
```

The setting-only mutation still reports the original two geometry failures:

```text
DULCET DISPLAY GEOMETRY window=(0.0, 135.5, 402.0, 603.0) displayImageSize=(402.0, 874.0)
XCTAssertEqualWithAccuracy failed: ("135.5") is not equal to ("0.0") +/- ("1.0")
XCTAssertEqualWithAccuracy failed: ("1.5") is not equal to ("2.174129353233831") +/- ("0.01")
```

It also reports that the account heading's activation point is invalid when
hittability is queried in the letterboxed layout. That additional visibility
failure does not replace the original geometry assertions.

Restored application output:

```text
DULCET DISPLAY GEOMETRY window=(0.0, 0.0, 402.0, 874.0) displayImageSize=(402.0, 874.0)
DULCET DISPLAY CONTENT id=dulcet.account-connect.title frame=(32.0, 200.01212565104163, 220.0, 81.66666666666663) window=(0.0, 0.0, 402.0, 874.0) hittable=true
DULCET DISPLAY CONTENT id=dulcet.account-connect.server-address frame=(179.0, 405.0, 175.0, 34.0) window=(0.0, 0.0, 402.0, 874.0) hittable=true
Executed 1 test, with 0 failures (0 unexpected)
```

The three result-bundle summaries each confirm one executed test and zero skips;
the restored bundle also passes `tools/verify-xcode-test-execution`. All runs used
`-parallel-testing-enabled NO` and resolved internal-disk DerivedData. Both
mutations were restored. CI policy (six workflows), parity (six feature rows),
OS-floor configuration, and diff whitespace checks passed again. The core suite
and live search were not rerun for this test-only follow-up; their results above
belong to the original launch-screen verification.
