# Simulator apsd pressure experiment, Refs #343

**OBSERVED:** retained iPhone job [121330195594](https://github.com/legitimate-apps/dulcet/actions/runs/40246865463/job/121330195594)
and iPad job [121287620055](https://github.com/legitimate-apps/dulcet/actions/runs/40232687092/job/121287620055)
contain simulator APNs client-identity failure loops. Their exported simulator archives contain
2,627,454 and 1,518,716 `apsd` events respectively (`log stats --archive ... --count 15`).
The host CoreSimulator logs report MobileActivation API denial with code -25. The existing
`HOST PRESSURE CPU phase=...` lines merge processes with the same basename. They cannot tell
which `diagnosticd` incurred the CPU cost, or establish that stopping this producer helps XCTest.

**ASSUMED, to test:** the identity loop generates enough log work to consume a material share of
the small hosted VM, and removing that work frees time for accessibility queries. This branch
adds a measurement and an opt-in experiment. It is not a starvation fix.

## One scheduled dispatch

Run after hosted macOS capacity is available:

```sh
ghx workflow run apple-ci.yml --repo legitimate-apps/dulcet \
  --ref ci/diagnosticd-pressure-343 \
  -f conformance_only=true -f apsd_experiment=true
```

The default is `apsd_experiment=false`. Only an explicitly opted-in `workflow_dispatch` enables
it, in each iPhone/iPad device job. The core conformance leg runs normally. No additional jobs,
simulator boots between arms, test retries, changed assertions or larger test allowances are
introduced. Existing job/step deadlines remain in force; exhaustion is an incomplete experiment.

Each device job does this on its own VM:

1. Boot only its selected device and require stable service counts. Before warm-up or any
   accessibility test, select `com.apple.apsd` through that device's `launchctl list`.
2. Require that PID's executable to equal the selected `SIMULATOR_ROOT/usr/libexec/apsd`,
   that exactly the selected device is booted, and that the process is not already stopped.
   Keep PID, launch time and full executable path. Never target the host launchd or host apsd.
3. Measure equal idle windows A1/B/A2, each nominally 60 seconds: running, `SIGSTOP`, `SIGCONT`.
   Record actual window lengths, CPU snapshots, paging, swap, load and state witnesses. There
   is no quiet-window selection within this sequence. Transitions and collection have separate
   phase markers. After all three windows, replay each bounded interval with device-scoped
   `log show --info --debug --loss`, not `log stream`. Collection work cannot contaminate the
   measured idle windows. A zero in a running arm fails the positive log control, rather than
   establishing quiet. The comparison still runs so the CPU and XCTest evidence is retained;
   the overall experimental verdict fails when measurement controls are invalid.
4. Run the existing in-memory connect-screen warm-up and pressure admission. Then run the
   unchanged `testBrowseAddRemoveRelaunchAndOfflineReplay` consecutively in A1/B/A2, each
   against a fresh Dulcet Server/proxy fixture, with a new test account UUID. Only B has apsd
   suspended. The test selector, test allowance, app, simulator and Xcode invocation options
   are shared across these three comparisons. There is no extra admission selection between
   them. Each finite comparison command has a 900-second outer cleanup bound; a bound expiry
   aborts the comparison and restores apsd, rather than running another test beside an orphan.
5. With apsd restored, run the original full My Library suite against a fourth fresh fixture,
   including its original warm-up and pressure prerequisite. Its normal bundle and JUnit
   paths supply required parity evidence. Comparison bundles never supply parity evidence.
   Any failed comparison or required suite makes the step fail. A later pass never replaces
   an earlier failure. Later playback, scrobble, lyrics and other conformance proofs run only
   after restoration.
6. Restore the exact launch in `finally`, including SIGINT/SIGTERM, and in a separate always-run
   CI cleanup step. Persist suspension intent before STOP. Missing/reused identities are never
   signaled; a process replacement invalidates the comparison. No launchd disable state,
   runtime file, application setting or production configuration changes. A forcibly destroyed
   runner may not execute cleanup; subsequent proofs cannot proceed past its failed step.

**ASSUMED:** suspension of this daemon is tolerable for the isolated browse comparison. Even
without explicit app dependencies, simulator clients may wait for apsd. This experiment observes
that behavior rather than assuming a suspended daemon is safe as a permanent CI configuration.

## Measurement and artifacts

**OBSERVED in the implementation:** `host-pressure` now records unaggregated `ps` own user+system
cumulative CPU time, PID, start identity, parent PID, state and untruncated executable on every
sample and at phase boundaries. `stop` closes the last phase with a final CPU snapshot. Existing
`HOST PRESSURE`, `TOP`, `CPU` and `STACK` lines keep their formats and meaning. New
`HOST PRESSURE CPU-TIME {json}` records give each identity's `cpuLowerBoundSeconds`,
`observedSeconds`, interval/reset counts and presence at both boundaries. Same-named host and
runtime processes remain separate, including diagnosticd, logd, apsd and XCTest helpers.

These are lower bounds, not an exact ledger of all CPU: a process born and exited between reads
is invisible, initial/final unsampled lifetime CPU is excluded, and a reset is a gap. Launch time
has the precision reported by `ps lstart`. Normalize daemon cost by its observed seconds and
inspect missing coverage. Compare full executable paths, not basenames or lifetime totals.
Kernel paging counters and load retain their existing cumulative/peak semantics. Load1 lags
changes, so examine interval CPU and paging alongside its trajectory; do not infer an effect
from a peak moving to a different phase.

The always-uploaded `dulcet-apsd-experiment-<device>-<run>-<attempt>` artifact includes:

- `experiment.jsonl`: target identity, transitions, actual window lengths, persisted log counts,
  certificate-abort/identity message counts, arm exits and measurement validity.
- `idle-<arm>.json`: full readings and CPU deltas for every observed identity, with the paths.
- `idle-<arm>-apsd.ndjson` and replay stderr: bounded offline messages and reported losses.
- `<arm>/my-library.xcresult`, `<arm>/my-library.log`: all comparison evidence, on any verdict.
- `<arm>/browse-activities-legacy.json` and `browse-metrics.json`: exact XCTest activity start
  and finish times, first automation offset for that method, its duration/status and query
  durations. Activities are nested; do not sum their durations. Missing exports are explicit.
- The raw host-pressure record and original full My Library log/bundle, including on success.

Persisted log rates are a subset of emitted traffic. Info/debug retention and loss can differ
between arms. Replay CLI bounds round outward to whole seconds; event timestamps are filtered back to the
recorded millisecond window. Unparseable target timestamps invalidate that rate. Zero matching
loss records does not
prove no logging was lost. Inspect both running-arm positive controls before interpreting B.

**OBSERVED offline validation:** applying `activity_metrics` to the retained iPhone result
bundle from job 121330195594 yields a 252.678638-second failed browse test and its slowest query
at 19.255 seconds. That distinguishes the query itself from its enclosing 20-second wait. Its
first automation offset for this method is 4.379 seconds; the whole suite's first automation is
a separate observation in the original full log. The dispatch will preserve the same data for
all three comparison arms.

## Dependencies and proof meaning

**OBSERVED source audit:** searching Apple sources/configuration for `registerForRemote`,
`aps-environment`, `remote-notification`, `CloudKit`, `CKContainer`, `NSUbiquitous` and `iCloud`
finds no registration, entitlement or integration. The browse method uses loopback REST,
production membership/download/offline playback, relaunch and accessibility assertions
(`apple/DulcetMyLibraryUITestSupport/DulcetMyLibraryUITests.swift`). Its fixture selects the
**Threshold Boundary** album, not the **UI Playback Canary** used by later scrobble proofs.
Each fixture root is created without reuse (`tools/ci/with-my-library-fixture`).

**OBSERVED:** `DulcetSystemMediaControls.swift` uses `MPRemoteCommandCenter`. Apple describes
[that API](https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter) as handling
media remote-control events. Apple separately documents [APNs registration and its push
entitlement](https://developer.apple.com/documentation/usernotifications/registering-your-app-with-apns).
**ASSUMED:** these local media controls do not need apsd for this browse flow. The suspended
comparison retains its actual offline playback and all assertions; it would fail if suspension
breaks a dependency. The full required suite and subsequent media-control proofs run restored.
This audit supports a bounded experiment, not a claim that all simulator services are independent
of apsd or a decision to disable it for every proof.

## Reading the result

- **Supports the proposed chain:** running idle arms reproduce the identity loop; B suppresses
  its event rate and apsd CPU; diagnosticd/logd and host CPU/paging costs fall below both running
  arms and rebound on restoration. In consecutive browse comparisons, the same query activities
  and automation setup shorten in B and worsen again in A2, with matching CPU relief and no
  assertion change. This would support a causal contribution on these VMs, not estimate the
  flake rate or justify a permanent fix by itself.
- **Producer is real, but not the starvation cause in this run:** the B manipulation is valid
  and apsd traffic/cost drops, but diagnosticd/host pressure or starved query durations do not.
  If diagnosticd cost falls but queries stay slow, the logging work contributes CPU without
  evidence it is the bottleneck for this proof. Investigate other measured processes.
- **Favors ordinary decay/caching:** A1 to B to A2 improves monotonically, with no restoration
  rebound. Attribute no fix to apsd on that evidence. A2's later position deliberately gives
  decay/caching a chance to outperform B. The first measurement windows also add post-boot
  idle time; a passing required suite can result from that delay alone.
- **Inconclusive:** no running-arm loop, no starved queries, missing log/CPU coverage, replaced
  daemon, failed restoration, test not executed, timeout, or changed query path after a failed
  assertion. Report that limitation. Successful execution of different assertions is not a
  matched query comparison. One A/B/A pair can still coincide with an unrelated background
  burst, so inspect aligned timestamps and each device independently.

Local validation commands are `tools/test-host-pressure`, `python3 tools/test-apsd-experiment`,
`python3 tools/verify_ci_policy.py`, `python3 tools/parity_gate.py` and
`python3 tools/test-plan-apple-legs`. The new controls use mocks and one owned disposable host
`sleep` process; they do not boot or drive an iOS simulator. Only the scheduled hosted dispatch
can validate the daemon manipulation and its effect on real accessibility queries.
