# Android Emulator reliability — 2026-10-30

## Cause and scope

OBSERVED: [run 41008830633](https://github.com/legitimate-apps/dulcet/actions/runs/41008830633)
launched Android Emulator 37.2.13 with the original SDK launcher and I/O. A read-only process
observer captured QEMU as a zombie with Linux wait status 139 (SIGSEGV), before the action's
cleanup. The host had more than 9 GiB available and cgroup OOM counters stayed zero. Guest ADB
and console endpoints disappeared; subsequent `pm clear`/night-mode failures were consequences.
The internal crashing instruction is unknown. The 37.2.13 release notes describe graphics-resource
cleanup changes; they do not prove which internal change caused this crash.

OBSERVED: changing only the action's `emulator-build` to 16428233 in
[run 41009859485](https://github.com/legitimate-apps/dulcet/actions/runs/41009859485)
launched 37.2.12.0/build 16428233 and completed 10 native proofs with zero failures, errors or skips.
That single comparison motivates a repeated hosted measurement; it is not by itself a pass-rate claim.

## Historical attempt census

OBSERVED: the [complete job-attempt census](android-emulator-historical-census.tsv) contains
44 Discover jobs across 30 run IDs: **32 failed, 11 passed, one cancelled**. Count every attempt,
including unsuccessful attempts followed by green reruns. The pass rate is 11/43 (25.6%) excluding
cancellation, or 11/44 (25.0%) including it. The maintainer supplied counts without the original
run IDs. The live newest-30 frame changed during retrieval; the published frame reconstructs
those counts by retaining the named run 40997684074 attempt1 and excluding newly completed
run 41060809631. It does not establish that these were the exact original 30 identities.

- 31 failed jobs lost emulator device/console service: one at boot, 29 during or between proofs,
  and one during night-mode restoration after all ten proofs passed. Final console access got
  `could not connect to TCP port 5554: Connection refused`. These are 31 infrastructure losses,
  **not 31 individually proven SIGSEGVs**. Two diagnostics captured a zombie; only run 41008830633
  recorded its exit signal. No SDK install/build failure accounts for this frame's Discover jobs.
- Two jobs also record the genuine assertion `Did not observe visible discover.item.artist.20`
  at `AndroidEmulatorDiscoverWishlistProofTest.kt:107`. [40952296653 attempt2](https://github.com/legitimate-apps/dulcet/actions/runs/40952296653/job/123729910641)
  is pure assertion failure: ten tests, one failure, zero errors; the other nine phases pass and
  emulator shutdown succeeds. [40976913838 attempt1](https://github.com/legitimate-apps/dulcet/actions/runs/40976913838/job/123807099063)
  is mixed: that assertion fails first, then emulator loss causes nine infrastructure errors.
  Categories overlap. The pin does not fix or suppress either assertion.
- Actual launched 37.2.13 / build 16740729: 31 failures, six successes, one cancellation.
  Actual 37.2.12 / build 16428233: five successes, zero failures. One boot diagnostic did not retain
  launch stdout, so its version is unknown. These historical versions include diagnostic variants;
  the matched fresh-runner comparison below is stronger evidence.

The first printed infrastructure phase brackets when access was lost; it is not a crash timestamp:

| First loss | Jobs |
|---|---:|
| Boot | 1 |
| everywherePagesAndHeldRoute / normal | 1 |
| offlineWishRelaunchRemoveRaceAndArrival / before-death | 5 |
| offlineWishRelaunchRemoveRaceAndArrival / after-death | 2 |
| busyStaleAndNavidromeControl / busy | 2 |
| busyStaleAndNavidromeControl / stale | 5 |
| busyStaleAndNavidromeControl / navidrome | 2 |
| accountSwitchIdentityFence / normal | 3 |
| largestFontAndTalkBackLabels / light | 6 |
| largestFontAndTalkBackLabels / dark | 2 |
| largestFontAndTalkBackLabels / largest | 1 |
| Cleanup after ten passing proofs | 1 |

In the same frame, phone playback jobs have37 successes, no failures, one cancellation.
TV has 34 successes, three failures, one cancellation. Two TV failures lose device/console
service ([40998323344](https://github.com/legitimate-apps/dulcet/actions/runs/40998323344/job/123876571435),
[40997560889](https://github.com/legitimate-apps/dulcet/actions/runs/40997560889/job/123874153382));
their signals were not recorded. The third is a genuine timeout waiting for the queued album,
`AndroidTvEmulatorLibraryAlbumPlayProofTest.kt:146`, with the emulator still available
([40952296653](https://github.com/legitimate-apps/dulcet/actions/runs/40952296653/job/123723279325)).

### What android-test-soak actually ran

Of its latest 30 runs,24 ran the ordinary JVM-host workflow:22 successes, one cancellation,
and one failure. The failure ([40462380890](https://github.com/legitimate-apps/dulcet/actions/runs/40462380890))
repeats `TvMyLibraryTest.supportedServerDoesNotFetchDiscoverUntilOpened` failure in all 20 iterations;
there is no emulator and it shares no observed QEMU cause.

The [workflow-body census](android-soak-workflow-census.tsv) identifies six emulator diagnostic
runs, including one successful run; five failed:

- The successful [40970314441](https://github.com/legitimate-apps/dulcet/actions/runs/40970314441)
  launches one replica of each engine; both complete10/10 and shut down normally.
- [40969206593](https://github.com/legitimate-apps/dulcet/actions/runs/40969206593) and
  [40969915139](https://github.com/legitimate-apps/dulcet/actions/runs/40969915139) fail before boot;
  loader probes identify missing `libpulse.so.0` and emulator exit 127 for both version archives.
- [40973534192](https://github.com/legitimate-apps/dulcet/actions/runs/40973534192): both delivery
  variants actually run37.2.13; one passes 10/10, one loses the emulator. Archive delivery alone
  changes no engine version.
- [40976834257](https://github.com/legitimate-apps/dulcet/actions/runs/40976834257): both actual
  37.2.12 / build 16428233 replicas pass 10/10 and shut down normally. Their red jobs are a diagnostic
  harness defect: `proof_status=$?` and `exit "$proof_status"` execute in separate action-script
  shells, so the latter gets `exit: Illegal number:`. Of two 37.2.13 replicas, one passes 10/10
  before the same harness error; the other loses the emulator with three infrastructure errors.
- [40978902527](https://github.com/legitimate-apps/dulcet/actions/runs/40978902527): both renderer
  choices run37.2.13 and each has one successful proof replica and one emulator loss. The
  evidence does not isolate a specific renderer or graphics function as the crashing site.


## Fix and pin resolution

Both core-ci host-engine paths (Discover and phone/TV playback) consume build 16428233 from
`gradle/libs.versions.toml` through `tools/export_toolchain.py`. The commit-pinned
[runner installer](https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/src/sdk-installer.ts#L54)
uses Google's exact archive `https://dl.google.com/android/repository/emulator-linux_x64-16428233.zip`.
After boot, `tools/ci/android-system-image` verifies the actual SDK executable reports
37.2.12.0 and build 16428233 before allowing any device proof. A different version or build is an
infrastructure failure, not a fallback to a new engine. Guest API34, image target/ABI, renderer,
app, instrumentation and proof assertions remain the same. No app/proof retry is added.

The optional Discover repetition matrix supplies independent runners/emulators/fixtures, unique
artifacts, fail-fast=false and a required result covering every member. Rep1 preserves the existing
artifact and producing-attempt output. Ordinary pushes/PRs still run one member.

## Matched hosted version comparison

OBSERVED: [pinned run 41072377154](https://github.com/legitimate-apps/dulcet/actions/runs/41072377154)
and [current-engine control 41072665016](https://github.com/legitimate-apps/dulcet/actions/runs/41072665016)
use identical app, core, server, instrumentation, proof runner, workflow and boot verifier source.
Only catalog engine version/build and the local fake-SDK test fixture differ. Read the actual
launched version and every terminal JUnit result from the [per-job sample table](android-emulator-matched-samples.tsv).
Every repetition has its own fresh hosted runner, emulator and fixture; no failed sample was rerun.

| Engine | Fresh jobs | Passing jobs | Native tests | Assertion failures | Infrastructure errors | Skips | Console losses |
|---|---:|---:|---:|---:|---:|---:|---:|
| 37.2.13 / 16740729 | 6 | 2 (33.3%) | 60 | 0 | 22 | 0 | 4 |
| 37.2.12 / 16428233, before helper fix | 10 | 9 (90.0%) | 100 | 1 | 0 | 0 | 0 |

The four current-engine losses first appear in before-death, busy, account-switch and dark-mode
phases. Every failed control has zero native assertion failures and subsequent ADB setup errors,
then console5554 refusal. The pinned engines all remain responsive through successful explicit
shutdown. This finite comparison supports the version pin as the infrastructure fix; it does not
identify an internal crashing instruction or promise a universal pass rate.

### A separate reconnect-helper race exposed by the healthy engine

Pinned [rep3, job124119526652](https://github.com/legitimate-apps/dulcet/actions/runs/41072377154/job/124119526652)
fails `accountSwitchIdentityFence`: `Did not observe displayed library.reconnect`. Its stack is
`accountSwitchIdentityFence:330 -> reconnect:402 -> click:456 -> ensureDisplayed:472 -> await:508`
in `AndroidEmulatorDiscoverWishlistProofTest.kt` before this change. The first six phases and the
last three font/theme phases pass; terminal JUnit is ten tests, one failure, zero errors/skips.
The emulator shuts down normally. This failure is preserved in the sample above.

OBSERVED: the captured failure semantics already contains the expected different-server identity
rejection and `library.connection.retry`; the original `library.reconnect` control is absent.
The helper had resumed the activity, observed that optional old control, then waited up to30seconds
for it to be displayed. `LibraryLifecycle` dispatches ON_START to `LibrarySession.start()`, which
already starts the normal reconnect. INFERENCE supported by the stack and captured semantics:
the automatic reconnect retired the manual fallback between its existence check and click.
This is a proof-helper race; it is not a dropped identity rejection or an emulator failure.

The helper now only performs the stop/start lifecycle transition. Each caller still requires its
original effect: delivered durable mutations/removal on the server, or the different-server notice,
replacement confirmation/cancellation, retained owner and absence of rejected-account mutations.
No outcome assertion, timeout, failure reporting or emulator-failure handling is weakened. PR #408
already owns the historical artist-pagination proof repair; those changes are not included here.
A fresh ten-runner measurement of this final helper is recorded below when complete.

## Local validation

- `tools/test-android-system-image`:23 tests passed,0 failed. Two new controls inject a different version
  or the correct version with the wrong build; both are rejected.
- Exact same two controls against the original image verifier:2 tests failed because it accepted
  either engine. With the version guard:2 passed. All temporary old source restored.
- CI policy, parity gate, actionlint and diff whitespace checks passed.

Primary context: [official emulator release notes](https://developer.android.com/studio/releases/emulator).
