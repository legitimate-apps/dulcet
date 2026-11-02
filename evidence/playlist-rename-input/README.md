# Playlist Rename input evidence

Source: `apple-ci` run 40939608799, attempt 2, `apple-conformance-iphone`, PR #397
head `edac5a6f`. Artifact:
`dulcet-apple-failure-diagnostics-apple-conformance-ipad-iphone-iphone-40939608799-2`.
The selected test is
`DulcetiOSUITests/testAPlaylistOpensPlaysInItsOrderAndARenameReachesTheServer`.

OBSERVED: the exact field-value guard passed. XCTest synthesized the Rename input at
01:23:17.700 UTC (test time 77.72 s). The field's new name remained in the open alert;
the page title and server held `Dulcet UI Proof F221D62E` rather than its ` Renamed` suffix.

These JPEGs are frames exported from the xcresult's screen recording, reduced to 800 pixels
on the long side before inspection. Timestamps are seconds into the recording:

- [Before the input, 77.302 s](before-confirm.jpg).
- [After the input, 79.325 s](after-confirm.jpg).
- [Still open near the end of the server wait, 110.328 s](still-open.jpg).

The system's first-use Slide to Type introduction is open over the keyboard, with Continue
still awaiting input. Rename is visible above it. The app-side XCTest log resolves the exact
`dulcet.playlist.name.confirm` button at 01:23:17.509 UTC, and the contemporaneous snapshot
includes `UIContinuousPathIntroductionView` in a separate keyboard window. The
[app-log excerpt](app-log-excerpt.txt) retains the original line numbers.

The original build did not record a rename-handler receipt or passive touch phases. Its
“Synthesize event” entry proves what XCTest attempted, not that the app's Rename handler ran.
The new proof therefore checks that handler directly before checking the page and server.

The decisive evidence is the [BackBoard HID log](hid-log-excerpt.txt) exported from the
same xcresult's simulator `system.logarchive`, rather than the keyboard introduction.
OBSERVED at 01:23:17.614–.616 UTC: the new Recap digitizer sender `105368200008F`
is initialized twice, with “already have a main display digitizer” and “unknown digitizer”.
BackBoard repeatedly skips `pathIndex:2` events because it did not see the preceding down.
At .797–.798 that sender is detached while `touchIdentifier:139` is still touching/locked
on Dulcet's window context `0x33B355E7`; BackBoard explicitly cancels that path. UIKit
records event dispatch again at .799. This establishes a cancelled synthetic contact. Together with the persistent alert, it
strongly supports the inference that Rename was not activated. The original build did not
instrument its handler, so that final link is inferred, not an observed handler log. There
is no evidence that a recorded product edit was dropped. No product mutation code changes.

ASSUMED: runner contention exposed the Recap registration/delivery race. The logs establish
the input failure, not which resource contention caused it. The visible keyboard introduction
is context, not an established cause; the final fix does not manipulate keyboard settings.

## Regression controls

The deterministic UI control withholds the first Rename synthesis **in the test runner**;
it does not replace the app handler or server transport. With the original one-input
behavior (`maximumInputs = 1`), it fails at the missing-handler assertion: **1 test,
1 failure, 0 skipped**, 51.661 seconds. Restoring the bounded second input must produce
a fresh handler receipt followed by the original title and server assertions.

`tools/test-playlist-rename-input` compiles and executes the proof's actual receipt parser:
**19 checks pass**. Three unsafe variants are each rejected: retrying a normally ended
touch, accepting a different playlist's receipt, and accepting a stale receipt. These
controls run as a blocking step in `apple-platform`. The deterministic UI control runs
beside the original iPhone playlist proof in the conformance leg, and both exact test
identities are required before its JUnit is emitted.

An unchanged-code local run passed once on iOS 26.5; that is not evidence of reproducing
the hosted cancellation. The original hosted failure and its HID evidence establish the
incident, while withholding input makes the old assumption fail deterministically.

Final local iPhone iOS 26.5 execution (`-parallel-testing-enabled NO`): **2 tests,
2 passed, 0 failed, 0 skipped**, read from the xcresult test tree and emitted JUnit.
The ordinary proof passed in 49.070 seconds (receipt on attempt 1); the withheld-input
control passed in 52.908 seconds (receipt on attempt 2 after `no-touch` recovery).
Both reported the exact renamed title and read-back server name.
