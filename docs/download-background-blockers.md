# Offline download background continuity blockers

Both blockers are open on macOS, iOS, and iPadOS. All three `downloads.offline`
cells remain partial. Each cell names the first unmet requirement in `blocked_on`
and retains the independent OS-delivery requirement in its promotion reason.
Closing either blocker alone is insufficient for promotion.

This document registers the two IDs. The existing parity workflow's
`tools/test-downloads-macos-review-regressions` checks their presence and the three
cells' references. `tools/parity_gate.py` has no blocker-ID registry;
`tools/verify_promotion_conditions.py` validates blocked-condition structure.
No validator or Apple CI step is removed or relaxed by this split.

## killed-process-background-session-handoff

Status: open on macOS, iOS, and iPadOS.

Reason: the measurable claim combines termination while a background URLSession
download is outstanding with production reconciliation of its secured artifact
into the durable downloaded row after an explicit harness-driven relaunch.
Observe and record the outstanding task, process exit, replacement process, and
resulting persisted row/artifact identity separately on each platform. Explicit
relaunch must be labelled harness-driven; it cannot establish OS delivery.

OBSERVED by source inspection (not newly executed runtime evidence):

- `apple/DulcetPlaybackIntegrationTests/DulcetAppleDownloadIntegrationTest.swift`,
  `backgroundSessionHandoffWaitsUntilDelegateFinishesEvents`, plays the OS by
  invoking `urlSessionDidFinishEvents(forBackgroundURLSession:)` itself. It checks
  that the handoff waits and then finishes. It does not terminate a process,
  deliver a download artifact, or assert a durable downloaded row.
- In that same file, `downloadDelegateSecuresTemporaryFileBeforeFacadeReconciliation`
  invokes the download delegate directly and checks a moved file and inbox sidecar.
  It does not assert reconciliation into a downloaded row after process death.
- CONF-51's platform wrappers call `proveDownloadTriggerPromotesValidatedResponseAtomically`;
  CONF-52's wrappers call `proveOfflinePlaybackLoadsIdenticalBytesAfterNetworkClientsClose`.
  Both complete the download in the original process. Closing network clients is
  not terminating the app.
- `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/DownloadPolicyTest.kt`,
  `relaunchReconciliationInterruptsMissingTasksCancelsOrphansAndDeletesCrashTemps`,
  creates another policy engine in the same process. It asserts interruption and
  cleanup, not an Apple background-session completion becoming a downloaded row.

No cell is promoted: none of these controls observes the full handoff sequence.
Source presence is not a claim that these tests executed in this change.

## os-initiated-background-session-delivery

Status: independently open on macOS, iOS, and iPadOS.

Reason: evidence must observe the OS reclaiming the app with an outstanding
background session and later relaunching or resuming it for that session's
completion. A harness invocation of the finish delegate or an explicit app launch
cannot close this blocker, even if the handoff blocker is subsequently closed.

OBSERVED in the maintainer-supplied measurement, not rerun for this change: on a
leased ephemeral iOS 26.5 simulator, a positive control first produced 12,888
RunningBoard rows. `simctl terminate` on MobileSafari then produced:

```text
Received termination request from [osservice<com.apple.SpringBoard>:32752]
  with context <RBSTerminateContext| domain:10 code:0xFBFBFBFB
                explanation:Termination requested by simulator host>
```

The termination routes through SpringBoard, the user force-quit path, rather than
OS-initiated reclaim. `xcrun simctl help` exposes `spawn` and `terminate` but no
memory-pressure or jetsam verb. A simctl-driven termination therefore cannot
produce the reclaim trigger needed by the iOS/iPadOS claim; a resulting absence
of relaunch is not negative evidence about OS delivery. Do not repeat this probe.
Closure requires an environment that can observe the actual OS reclaim and
session-delivery trigger, with per-platform evidence; simulator force-quit is
not a substitute.

ASSUMED: macOS relaunches a terminated app for this background session. The iOS
simulator result does not establish macOS lifecycle behavior. Closing the macOS
blocker requires separate macOS observations of the initiating event and delivery.
The production SwiftUI `.backgroundTask(.urlSession(...))` registration and
`sessionSendsLaunchEvents = true` are OBSERVED in the supplied brief; registration
and configuration alone do not establish an OS-delivered event on any platform.
