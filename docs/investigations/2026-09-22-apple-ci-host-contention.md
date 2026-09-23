# apple-ci reliability: what fails, why, and what changed (2026-09-22)

**Status: fix on `fix/apple-ci-reliability` (#136). Before: 47/111 attempts passed (42.3%). After:
1/1 on #136, which is not a rate. The suite soak's first run drew no samples (§4); re-dispatch
`apple-contention-soak` after merge, and read the first 30 post-merge `apple-ci` attempts.**

## 1. Every failed attempt since 2026-09-08, classified by step and test

Source: every `apple-ci` run created 2026-09-08 through 2026-09-22, **every attempt** (a re-run
keeps its run id but each attempt is its own job), 153 job records. Each failure is classified from
its job log, sliced to the first failing step's time window with the step's own echoed script
removed, and read at the test identity (`<Class>.<test>[<target>] FAILED`, XCTest `Test Case ...
failed`, or the tool's own error line) — never from the job conclusion or the step name alone.

**Denominator.** 111 attempts ran to a verdict: **47 passed, 63 failed, 1 hit the 120-minute job
cap.** 42 further attempts ended `cancelled`; 40 were superseded by a newer run on the same ref
(a newer run existed before the cancellation completed), 1 was the job timeout just counted, and
1 was cancelled by hand at 30 minutes with no newer run. Superseded runs are neither passes nor
failures and are excluded. **Pass rate 47/111 = 42.3%.**

| class | n | first failing step | test / signature |
|---|---|---|---|
| **host-contention stall** | **12** | Assert Darwin conformance preconditions | `DulcetAppleDownloadIntegrationTest` (iPhone): `library browse failed: kind=timeout` |
| | **5** | same | `AccountConnectConformanceTest.slowSelfHostedServerCanCompleteAccountNegotiation[iosSimulatorArm64]` |
| | **2** | same | `AccountConnectConformanceTest.localHttpRequiresExplicitConsent[iosSimulatorArm64]` |
| | **2** | same | `LibrarySyncConformanceTest.conf31GenerationPinnedReads[iosSimulatorArm64]` precondition `Failed` |
| | **2** | same | `PlaybackScrobbleConformanceTest.conf14a…/conf17…[iosSimulatorArm64]` `Timeout`/`Unreachable` |
| | **2** | same | `DarwinProxyAuthenticationConformanceTest.proxyChallengeFailsClosedWithoutAmbientCredentials[iosSimulatorArm64]` |
| | **3** | same | host `observe-transcode-cache`: `cache search transport error: timed out` |
| *subtotal* | ***28*** | | ***44% of failures*** |
| fixed since by a merged change | 4 | same | iPad playback canary `play count mismatch … observed 0` (#133) |
| | 6 | Verify iPhone uses the full display | 5-minute step cap (5) and launch timeout (1), cap raised to 12 minutes by #118 |
| | 1 | Verify compact sidebar re-selection restores the detail | 5-minute step cap (now 12) |
| environment / infrastructure | 2 | Launch iPadOS shell … | no concrete iPad simulator enumerated (open: #120) |
| | 2 | Launch iPadOS shell … | Gradle configuration-cache lock between two Xcode script phases (open: #134) |
| | 1 | Install and verify checksum-pinned Darwin conformance closure | Homebrew pin drift, run 35796413810 on `main` (open: #135) |
| | 1 | Assert Darwin conformance preconditions | DNS failure resolving the Maven repository during compile |
| | 1 | same | `xcodebuild` destination query timed out after 60 s (iPad discovery) |
| | 1 | Prove the measurement rejects the observed first-only state | HLS spike port handshake 10.18 s against a 10 s budget |
| | 1 | Verify iPhone uses the full display | XCUITest could not acquire a background assertion |
| UI assertion | 7 | Assert Darwin conformance preconditions | search activation: iPad 3, iPhone 2, tvOS 2 (ranking on an in-development branch, typed text, reachability) |
| | 1 | Prove iPhone playback … | back navigation did not expose Library |
| branch under development | 5 | Build all Apple frameworks / composite | compile errors (2), core unit tests (1), app-hosted library sync (1), empty live library (1) |
| evidence bookkeeping | 1 | Assert Darwin conformance preconditions | `xcode test execution invalid`: 2 results where 1 was expected |
| step cap | 1 | Assert Darwin conformance preconditions | the step's own 67-minute cap |
| **total** | **63** | | |

By first failing step: the conformance composite 45, *Verify iPhone uses the full display* 7,
*Launch iPadOS shell* 4, *Build all Apple frameworks* 3, one each for four others. The composite
hides at least nine causes, which is why its name is not a classification.

**`main` specifically:** 7 of 16 attempts passed. Run **35796413810** (2026-09-22, `fe0a7fb2`)
failed on Homebrew pin drift — `ffmpeg` 9.0.1_1 -> 9.0.2, `xz` 5.8.3 -> 5.8.4, `libvmaf`
3.2.0 -> 3.2.1, and bottle rebuilds of `mpg123`, `libvpx` and `x265` — after **55.6 minutes**, of
which the drift check itself took 49 seconds.

Wall time of the 47 passes: median 91.6 min, p90 108.3, **max 118.8 against the 120-minute cap.**

## 2. The proxy-auth "hang" was not a hang

PR #135's first attempt (run 35796782723) failed with
`HttpRequestTimeoutException … url=http://127.0.0.1:4543/observations/proxy-auth, request_timeout=10000 ms`
— the bound `3aed7a0a` added so a hang would name itself. It did name the request, but the
fixture's own access log (`dulcet-conformance-access-4543-*.log`, in the run's failure-diagnostics
artifact) shows the fixture answered:

| event | macOS pass | iOS-simulator pass (failed) |
|---|---|---|
| test `observation-client-create` (builds an `HttpClient`, no I/O) | 35 ms | **3,656 ms** |
| test `credential-fixture` (Keychain write) | 275 ms | 1,230 ms |
| `connect` through the proxy (407 challenge) | 268 ms | 1,786 ms |
| client begins `observation-get` -> fixture logs `arrival` | ~6 ms | **5,044 ms** |
| fixture handler, `arrival` -> `completion` (status 200) | 0.28 ms | **985 ms** |
| fixture `completion` -> client gives up | — | 4,238 ms later, at the 10 s bound |
| in-process watchdog gap (`watchdogGapMs`) | — | **12,844 ms** |

The fixture wrote `200` six seconds into a ten-second budget and the client still did not read it.
Every party — the test binary's in-process work, the Python fixture, the watchdog — slowed by one to
two orders of magnitude at the same moment. That is a host-wide stall, the same substrate as every
other row of the *host-contention* class, and **no change to the fixture or to the test's bound would
fix it**: the fixture already answers in well under a millisecond, and raising the bound converts a
named 10-second stall into an unnamed longer one.

A 100x slowdown of pure in-process work (building a client object) is not what CPU contention on
three cores produces for a runnable thread; it is what paging produces. Nothing in `apple-ci`
measured memory until this change, so that reading is **ASSUMED** until §4's numbers.

## 3. Where the stall sits: next to a freshly booted simulator

The restart-sequencing experiment (`2026-09-10-restart-sequencing-experiment.md`) was read against
its pre-registered criteria over 23 green runs and is **SUPPORTED**: the restart moved ahead of its
`simctl create`+`boot` fell from 3.61x baseline to 1.02x, while the unmoved restart that still
follows one stayed at 2.13x, slower than baseline in 23 of 23 runs.

Every stall row in §1 runs after the platform legs, which each boot a simulator and never shut it
down, and the iOS conformance rows additionally follow a freshly created device. **Correction:** an
earlier draft said three devices were still booted at the composite's start. PR #136's first run
measured **one** (`SIMULATOR ISOLATION phase=macos-app-and-download shut_down=1`), so resident
count there is lower than assumed; the swap figures in §4 are what carry the argument.
On the 3 vCPU / 7 GB hosted runner the single-use Gradle daemon is allowed `-Xmx3g`, and it linked
the iOS test binary in the same process that then ran the suite.

## 4. Soak: the arrangement, isolated

**First soak, run 35804385105: the suite comparison produced NO samples.** In both arms,
`reset-transcode-cache` refused to run because the soak script had not exported
`DULCET_CONFORMANCE_DISPOSABLE=true` (apple-ci's composite gets it from its step env). So Navidrome was
stopped and never restarted, and all 10 iterations of each arm ended `restart-failed` with 0 tests.
The script is fixed. **The before/after suite pass rate is still unmeasured by soak.**

What the run did measure is the host-pressure record over the same ten ~3-minute windows per arm,
with Navidrome down and no suite running. The only difference was the resident simulators:

| per restart window, iterations 2–10 (n = 9 each; iteration 1's window was ~2 s) | `current`: 3 idle booted simulators | `isolated`: none |
|---|---|---|
| swap in use, max | **1.93 – 2.11 GB in 9/9** | **0 in 9/9** |
| compressor, max | 2.62 – 2.70 GB | 0.49 – 0.52 GB |
| load average (1 min), max | **355 – 819** | 1.3 – 13.7 (13.7 in the first window only) |
| swap-outs per window | 0 – 224 MB | 0 in 9/9 |

Three **idle** simulators alone hold about 2 GB of swap on the 7 GB runner and drive load averages in
the hundreds on 3 CPUs. That supports the §2 paging reading. It does not yet show that the suite
fails because of it; the fixed soak does that.

**PR #136's own `apple-ci` run 35805274097 (green, 81.7 min; job max before this change 118.8, median
91.6).** Every isolation call reported `isolated=true`. Host pressure by phase: the platform legs
before the composite reached **2.07 GB swap, 3.3 GB swapped out, load max 594**. Inside the isolated
composite, swap peaked at 0.87 GB (conformance-ios, the separate link JVM's window) and 0.42 – 0.81 GB
elsewhere. Restart #3, next to its fresh tvOS simulator, read libwait `elapsed_seconds=2.304`, the
same as #1 (2.254) and #2 (2.141), against a pre-change 2.13x median ratio. That is one run: a
consistency check, not a rate.

## 5. What changed

1. **Fail a pin drift before the builds.** The closure install (whose first act is the Homebrew drift
   check) now runs right after Xcode selection. A drift costs about a minute of a hosted macOS slot
   instead of 55.
2. **One simulator per phase** (`tools/ci/isolate-simulator`). Inside the conformance composite,
   each phase that talks to the loopback fixtures starts with exactly the device it needs booted,
   waited on with `bootstatus -b`, and prints
   `SIMULATOR ISOLATION phase=… keep=… shut_down=N booted_after=[…] isolated=true|false`. Its offline
   control proves it keeps the device `OS=latest` would pick (numeric runtime order, available only).
3. **Links in their own JVM.** The iOS and tvOS conformance binaries are linked by a Gradle
   invocation that exits before the suite starts.
4. **A job-wide host-pressure record** (`tools/ci/host-pressure`): `vm_stat`, swap and load every
   5 s, summarised per isolation phase in every run, green or red, and carried in the failure upload.
   Its control proves it reports a known delta, refuses a record with no phases, and parses the live
   host's `vm_stat`.

## 6. Not changed, deliberately

- **No timeout was raised**, including the proxy observation's 10 s bound: §2 shows the bound fired on
  a genuine host-wide stall, which is its job.
- **The job was not split.** See spec §21.5 for the analysis and the condition under which it is right.
- **Nothing moved off the pull-request gate.** See spec §21.5.
