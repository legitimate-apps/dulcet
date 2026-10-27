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
