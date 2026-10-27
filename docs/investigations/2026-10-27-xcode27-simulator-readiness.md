# Xcode 27 simulator readiness, 2026-10-27

The Xcode 27 migration keeps simulator readiness outside application-test clocks.
The sampled standard hosted runners report 3 CPUs and 7 GB memory. No larger
runner, retry or application-test allowance change is part of this adjustment.

## Observed timing

The device-member harness previously captured `bootstatus` output instead of
printing its stages. Its successful boot-readiness bound below is the elapsed
time from `MY LIBRARY FIXTURE ready` to `SIMULATOR SETTLE`, minus that marker's
own service-settle duration. This includes process startup, inventory and the
separate `simctl boot` RPC. It is an upper bound on boot readiness, not a direct
measurement of data migration or a statistical percentile.

| Image and run | Device | Readiness bound, seconds | Source job |
| --- | --- | ---: | --- |
| macos-26, 40271828529 | iPhone | 89.7 | [121406155983](https://github.com/legitimate-apps/dulcet/actions/runs/40271828529/job/121406155983) |
| macos-26, 40271828529 | iPad | 98.3 | [121406156010](https://github.com/legitimate-apps/dulcet/actions/runs/40271828529/job/121406156010) |
| macos-26, 40442634538 | iPhone | 173.0 | [121986368101](https://github.com/legitimate-apps/dulcet/actions/runs/40442634538/job/121986368101) |
| macos-26, 40442634538 | iPad | 94.5 | [121986368094](https://github.com/legitimate-apps/dulcet/actions/runs/40442634538/job/121986368094) |
| macos-26, 40155382994 | iPad | 87.8 | [121055592958](https://github.com/legitimate-apps/dulcet/actions/runs/40155382994/job/121055592958) |
| macos-26, 40232090256 | iPad | 146.2 | [121285814453](https://github.com/legitimate-apps/dulcet/actions/runs/40232090256/job/121285814453) |
| xcode-27, 40547199270 | iPhone | 264.0 | [122340970378](https://github.com/legitimate-apps/dulcet/actions/runs/40547199270/job/122340970378) |
| xcode-27, 40547199270 | iPad | Incomplete at 432.9 overall; `bootstatus` timed out at 300 | [122340970599](https://github.com/legitimate-apps/dulcet/actions/runs/40547199270/job/122340970599) |

The two older main core jobs report first-iPhone isolation totals of 102.9 and
164.0 seconds, and first-iPad totals of 87.3 and 163.8 seconds. Their markers
include inventory and, for the iPad, shutdown of the preceding iPhone. In Xcode 27
run 40547199270, [core job 122340970605](https://github.com/legitimate-apps/dulcet/actions/runs/40547199270/job/122340970605)
reports first-iPhone isolation of 140.1 seconds, first-iPad isolation of 311.7
seconds and first-tvOS isolation of 49.5 seconds. Its complete three-device
first-boot prerequisite passed in 592 seconds (17:18:57 to 17:28:49 UTC).
This successful iPad witness rules out a universally unbootable image. The
sample does not separate runtime initialization cost from host scheduling.

The failed Xcode 27 iPad's host-pressure artifact observes
`com.apple.datamigrator`, `com.apple.migrationpluginwrapper` and `launchd_sim`
at 17:18:50 UTC, during first boot. Sampling then has a gap until 17:25:37;
the final samples reach load 695.1 and 1116.56 MB swap. This establishes that
migration work ran, but does not identify the exact stage that delayed readiness.

## Readiness adjustment

`isolate-simulator` now uses one `simctl bootstatus <udid> -b` invocation, which
boots a shut-down device itself, with a 660-second monotonic subprocess deadline.
660 is `ceil(1.5 * max(311.7, 432.9) / 60) * 60`, using both the successful
iPad prerequisite and the failed attempt's later boundary. The incomplete iPad observation is censored;
this budget is an allowance above the observed failure boundary, not a claim
that a percentile or worst case has been established. Successful final-head
execution must validate it. BEGIN and completion markers report elapsed time,
budget, readiness and exit status. CoreSimulator's own stages stream to the log
even if the deadline expires. A failed boot stops before service settling or
application warm-up. The platform iPad uses the same guard.

First boot remains after compilation and idle-build-daemon cleanup. It can run
concurrently with a build in principle, but this sample already paged heavily
without compilation. Overlapping Gradle/Swift compilation with the migration
burst on the same 7 GB runner would add contention; this change keeps the existing
ordering and the later positive service/pressure admission checks.

The runner-images open-issue search on 2026-10-27 found no report specifically
describing iOS 27 boot latency. [Issue 14404](https://github.com/actions/runner-images/issues/14404)
describes the preview image. [Issue 14946](https://github.com/actions/runner-images/issues/14946)
describes duplicate watchOS runtimes; this run supplies no evidence linking that
issue to its iPad boot. The open simulator-boot report
[12777](https://github.com/actions/runner-images/issues/12777) concerns a macOS 15
image and is not evidence of the same Xcode 27 failure.

## Native queue fixture

[PR platform job 122359653166](https://github.com/legitimate-apps/dulcet/actions/runs/40552454388/job/122359653166)
passed the 425 macOS tests and both corrected capture passes, then failed one
of 407 iOS package tests. `aPreloadedAdvanceWhilePausedInheritsNoPlayRequest`
observed the expected engine boundary but found `AVQueuePlayer.currentItem == nil`
instead of the preloaded item. The native log also reports an unsupported-URL
error around that transition. No matching failure was found among platform jobs
in the last 30 main runs and their recorded attempts.

The boundary helper had supplied resources that never completed any byte request.
That is an unstable fixture for a test that requires AVFoundation to retain a
playable queue item. The helper now supplies ten minutes of valid PCM, checks
incoming decoder readiness, and retains the exact current-item identity check.
The engine still reads only the manual clock's samples. The requested-play,
paused inheritance, resumed positive control, event ordering and real queue
transition assertions remain. Local macOS execution passed all 42 engine tests;
iOS validation requires the final hosted run. The log does not establish why
AVFoundation retired the original item, so the unsupported-URL diagnostic alone
is not treated as a production resource-loader defect.

## Discover startup and runtime selection

[Discover iPhone job 122360940843](https://github.com/legitimate-apps/dulcet/actions/runs/40547199270/job/122360940843)
reached its 45-minute workflow step limit. Startup occupied 18:16:01 through
18:31:47 UTC, including 656.9 seconds in shared service/pressure admission and
app warm-up. The first four native cases passed in 346.961, 201.255, 484.178
and 514.808 seconds. The fifth was underway when the workflow cancelled the
fixture; its later connection-refused failure follows fixture shutdown and does
not establish an application failure. Main run 40442634538's successful
[Discover iPhone job 122035150007](https://github.com/legitimate-apps/dulcet/actions/runs/40442634538/job/122035150007)
spent 32m47s in the same whole step.

The step envelope is now 70 minutes, `ceil(1.5 * 45 / 5) * 5`, with the job
envelope increased by the same 25 minutes. The 45-minute observation is censored,
so final-head execution remains the validation. Individual test assertion and
fixture request deadlines remain unchanged.

The failed job's `native/device.json` also records iOS 27.1. The hosted fallback
had sorted all installed runtimes and chosen the last iPhone entry, ignoring the
requested device. It now chooses the requested iPhone 18 Pro on the runtime
matching `xcrun --sdk iphonesimulator --show-sdk-version` from the selected Xcode.
An explicit runtime override is honored; missing or unavailable matches fail
clearly. New controls reject a newer installed runtime, a wrong device and an
unavailable device. This makes the default proof comparable with the local
Xcode 27.0/iOS 27.0 toolchain; it does not establish runtime selection as the cause
of the timing difference.

The final [Discover iPad job 122375819971](https://github.com/legitimate-apps/dulcet/actions/runs/40547199270/job/122375819971)
also reached its workflow step limit, at 60 minutes. Startup ran from 19:07:47
through 19:26:05 UTC (18m18s), including 634.5 seconds in shared admission and
warm-up. Its iPad Pro 13-inch (M5) was on iOS 27.0. The first four native cases
passed in 262.218, 160.269, 448.134 and 948.470 seconds (30m19s total); the
fifth was still performing UI interactions when cancelled. Main run 40442634538's
[successful iPad retry 122075346929](https://github.com/legitimate-apps/dulcet/actions/runs/40442634538/job/122075346929)
completed the same whole step in 46m47s. Its earlier failed attempt ended via
`test-not-passed`, rather than this step timeout, so it is not an established
match for this failure.

The iPad whole-step allowance is provisionally 90 minutes,
`ceil(1.5 * 60 / 5) * 5`, with the job envelope increased by the same 30 minutes.
This uses a censored timeout observation, not a claimed successful maximum.
Final-head execution must validate it. The adjustment leaves every individual
assertion and fixture request deadline intact.


## tvOS lazy grid navigation

The same run's [core job 122340970605](https://github.com/legitimate-apps/dulcet/actions/runs/40547199270/job/122340970605)
completed the earlier Darwin proofs and failed the largest-text tvOS album tour.
Its failed-test hierarchy shows the Albums section selected and six first-row
album buttons starting at y=559, with heights up to 818.5 points in a 1080-point
window. The named Threshold Boundary album is in the next lazy row and is absent
from the hierarchy. The test waited for it without navigating the grid. The
helper now focuses a published first-row tile and uses bounded remote Down
navigation to reveal the target before the existing existence, activation and
album-title assertions. Light and dark variants passed before this failure.
