# apsd experiment results and retained CPU attribution, Refs #343

## Corrected dispatch 40272074448

**OBSERVED outcome:** the corrected guard passed on both devices. Suspending the selected
simulator apsd suppressed its identity-failure loop and reduced CPU work in both simulator
and host diagnosticd. iPad completed a reversible A/B/A browse comparison with a faster B
arm. iPhone's B and A2 fixtures failed before XCTest, leaving its query comparison
**inconclusive**. This supports apsd as a contributor, but does not establish a permanent
remedy for the previously failing, approximately 19-second accessibility queries.

**OBSERVED recommendation:** keep #395 measurement-only. Request one further comparison on
the separate experimental ref after repairing the fixture preflight described below. Do not
merge proof-time suspension from this run. The missing iPhone comparison and the absence of
severe query stalls in the iPad A arms leave the starvation question open.

**OBSERVED sources:** [run 40272074448](https://github.com/legitimate-apps/dulcet/actions/runs/40272074448),
head `d04d5760`, inputs `conformance_only=true`, `apsd_experiment=true`;
[iPhone job 121406872049](https://github.com/legitimate-apps/dulcet/actions/runs/40272074448/job/121406872049)
and [iPad job 121406872074](https://github.com/legitimate-apps/dulcet/actions/runs/40272074448/job/121406872074).
Fetch logs with `ghx api repos/legitimate-apps/dulcet/actions/jobs/<job-id>/logs` and artifacts
with `ghx run download 40272074448 --repo legitimate-apps/dulcet`. Tables below derive from
`dulcet-apsd-experiment-<device>-40272074448-1`, its `dulcet-apsd-experiment/experiment.jsonl`,
`idle-<arm>.json`, `<arm>/browse-metrics.json`, and raw `dulcet-host-pressure.log`.

### Guard, suppression and restoration

**OBSERVED:** `target-check` matched scoped launchctl PID, full framework executable and
launch identity: iPhone PID 20241, started 07:43:38 UTC; iPad PID 17399, started 07:58:07 UTC.
Both runtime roots were the iOS 26.5 build 23F77 path recorded below. Neither host apsd nor
another simulator was accepted. Every idle state witness passed. All emitted proof-arm
records report `identityAndStateValid=true`; B's sampled apsd CPU delta is zero. Each A2
resumes the same identity, and persisted messages and daemon CPU return. Both final
`restore.json` files and cleanup events have `suspended=false`, `targetExited=false` in
cleanup; both workflow restore steps succeeded. No identity replacement or failed
restoration was reported. These are observed guards and state witnesses, not proof of
absence of every possible mid-arm state transition.

| Idle measurement, A1 / B / A2 | iPhone | iPad |
|---|---:|---:|
| Window seconds | 60.711 / 60.222 / 60.269 | 60.258 / 60.262 / 60.529 |
| Persisted target apsd events | 188940 / 0 / 372566 | 166713 / 228 / 104206 |
| Events/second | 3112.12 / 0 / 6181.72 | 2766.65 / 3.78 / 1721.59 |
| Certificate-abort/identity events | 908 / 0 / 1580 | 803 / 0 / 479 |
| Replay exit / loss records / malformed target timestamps, every arm | 0 / 0 / 0 | 0 / 0 / 0 |

**OBSERVED:** iPad B retained 228 events, all timestamped in its last approximately 47 ms;
its recorded apsd CPU is zero across the whole observed window and identity-event count is
zero. B reduced persisted events by 99.86% versus A1 and 99.78% versus A2. The reason for
those residual timestamps is **ASSUMED/unresolved**, so this is strong suppression, not a
claim of zero emitted messages. Replay counts a persisted subset, not all messages emitted.
The idle positive controls reproduce the loop on both devices.

### Idle CPU and host pressure

**OBSERVED:** cumulative own user+system CPU-seconds, with A1 / B / A2 in every cell. Each
listed daemon covers its entire idle window, without reset. Simulator diagnosticd PIDs
20234/17391, host diagnosticd 20239/17393, simulator logd 20193/17347, host logd 86/86
(iPhone/iPad). Executable paths distinguish the instances; no basename totals are substituted.

| Idle CPU or pressure | iPhone A1 / B / A2 | iPad A1 / B / A2 |
|---|---:|---:|
| Simulator apsd CPU-s | 15.00 / 0 / 20.06 | 14.44 / 0 / 8.63 |
| Simulator diagnosticd CPU-s | 28.03 / 6.43 / 33.76 | 29.05 / 11.86 / 17.61 |
| Host diagnosticd CPU-s | 15.72 / 3.43 / 20.16 | 16.46 / 6.22 / 9.94 |
| Simulator logd CPU-s | 0.75 / 0.27 / 1.12 | 0.98 / 0.47 / 0.52 |
| Host logd CPU-s | 0.17 / 0.09 / 0.23 | 0.17 / 0.05 / 0.08 |
| All observed processes CPU-s, lower bound | 149.74 / 90.13 / 144.02 | 141.95 / 129.13 / 151.43 |
| Above divided by observed seconds, CPU cores | 2.466 / 1.497 / 2.390 | 2.356 / 2.143 / 2.502 |
| Page-ins, MiB | 809.27 / 871.16 / 881.50 | 418.47 / 606.91 / 343.05 |
| Load1 at window end | 468.85 / 169.20 / 84.96 | 402.69 / 148.40 / 101.19 |

**OBSERVED interpretation:** daemon and aggregate observed CPU fall in B and rise in A2,
which is stronger attribution than a before/after quiet window. Page-ins do not fall in B,
and idle load decays through A2 even as daemon CPU returns. Do not attribute all host load,
paging or its decay to apsd. The CPU sum is a lower bound over sampled process identities,
not an exact VM utilization counter; short-lived processes and unsampled initial/final work
are excluded. No live log consumer is named by this intervention.

### Browse proof arms and matched queries

**OBSERVED:** every completed browse method passed its unchanged assertions. The table
separates full fixture-command wall time from XCTest method duration. iPhone B/A2 each hit
`OSError: [Errno 48] Address already in use` at `with-my-library-fixture:91`, before a test
started (job log lines 8868 and 8878). Their approximately half-second command failures are
not fast tests. Their query/test durations and CPU deltas are unavailable, not zero.

| Browse measurement, A1 / B / A2 | iPhone | iPad |
|---|---:|---:|
| Fixture command exit | 0 / 1 / 1 | 0 / 0 / 0 |
| Fixture command seconds | 632.451 / 0.507 / 0.454 | 372.664 / 256.492 / 333.336 |
| XCTest method seconds | 278.108 / unavailable / unavailable | 200.087 / 151.654 / 195.548 |
| First automation offset, seconds | 17.594 / unavailable / unavailable | 12.498 / 8.514 / 9.320 |
| Longest query activity, seconds | 17.617 / unavailable / unavailable | 3.970 / 2.748 / 7.784 |
| Query activity count | 108 / unavailable / unavailable | 91 / 90 / 91 |

**OBSERVED matched-query controls, iPad:** for the identical `Find the "dulcet.library.album"
Button` title, all arms contain eight occurrences. Their median durations are
0.2310 / 0.1765 / 0.2545 seconds. The first occurrence is 0.247 / 0.525 / 0.370 seconds,
so not every query is faster in B. The first `Checking existence of` that album button is
3.949 / 2.748 / 2.244 seconds, a monotone improvement through A2. The extra existence poll
in A1 prevents matching all existence calls by ordinal. Maxima are different activities
and must not be treated as the same query. Nested activity durations overlap and are not
summed. These controls distinguish comparable operations from cherry-picked maxima.

**OBSERVED CPU during complete fixture commands:** use only snapshots inside the marked
arm and its completion event. The following are cumulative CPU-s in A1 / B / A2. Each
listed daemon spans its full observed interval with no reset; actual fixture durations
above extend slightly beyond these sampled endpoints.

| Fixture-arm CPU or pressure | iPhone A1 / B / A2 | iPad A1 / B / A2 |
|---|---:|---:|
| Observed seconds | 629.561 / unavailable / unavailable | 372.525 / 252.508 / 329.871 |
| Simulator apsd CPU-s | 167.44 / unavailable / unavailable | 96.25 / 0 / 76.41 |
| Simulator diagnosticd CPU-s | 258.12 / unavailable / unavailable | 158.56 / 19.90 / 126.98 |
| Host diagnosticd CPU-s | 145.37 / unavailable / unavailable | 87.72 / 10.80 / 71.68 |
| Simulator logd CPU-s | 6.87 / unavailable / unavailable | 4.74 / 1.04 / 3.84 |
| Host logd CPU-s | 1.93 / unavailable / unavailable | 1.50 / 0.64 / 1.07 |
| All observed processes CPU-s, lower bound | 1597.93 / unavailable / unavailable | 835.28 / 299.64 / 636.60 |
| Above divided by observed seconds, CPU cores | 2.538 / unavailable / unavailable | 2.242 / 1.187 / 1.930 |
| Maximum load1 | 319.34 / unavailable / unavailable | 150.86 / 65.90 / 112.74 |
| Page-ins, MiB | 5095.03 / unavailable / unavailable | 4670.09 / 3591.42 / 3984.61 |

**OBSERVED CPU restricted to XCTest activity windows:** the observed endpoints cover
270.621 seconds of iPhone A1 and 198.735 / 146.217 / 186.739 seconds of iPad A1/B/A2.
iPhone simulator apsd, simulator diagnosticd, host diagnosticd consume 64.12, 106.06,
60.15 CPU-s; all observed processes consume 671.06 CPU-s, or 2.480 cores. iPad values are:

| Within-test CPU | A1 | B | A2 |
|---|---:|---:|---:|
| Simulator apsd CPU-s | 40.45 | 0 | 50.25 |
| Simulator diagnosticd CPU-s | 73.89 | 17.64 | 86.65 |
| Host diagnosticd CPU-s | 41.52 | 9.56 | 49.12 |
| Simulator logd CPU-s | 2.29 | 0.82 | 2.59 |
| Host logd CPU-s | 0.77 | 0.30 | 0.56 |
| All observed processes CPU-s, lower bound | 484.56 | 240.68 | 455.70 |
| Above divided by observed seconds, CPU cores | 2.438 | 1.646 | 2.440 |
| Maximum load1 | 150.86 | 43.97 | 112.74 |
| Page-ins, MiB | 1803.30 | 1419.73 | 1369.70 |

**OBSERVED interpretation by device:**

- **iPhone: inconclusive for query relief**, because B and A2 never ran the method. Valid idle
  suppression and CPU reversal support a logging/CPU contributor. A1 reproduced a 17.617-second
  query, but supplies no valid B/A2 comparison. `measurementValid=true` in the final event
  checks idle replay/CPU availability only; it does not make the missing proof comparison valid.
- **iPad: support for a performance contribution on this VM**, with valid suppression and
  restoration, lower daemon and observed process CPU, a B test 24.2% faster than A1 and 22.4%
  faster than A2, and a rebound after restoring apsd. It does not land in the negative
  "suppression without relief" row. **Inconclusive for prevention of the earlier severe
  starvation failure:** neither running A arm reproduced its roughly 19-second query timeout.
  First automation partly improves monotonically; paging continues and decreases through A2.
  The strongest support is the reversible CPU/test-duration effect, not every query or host metric.

**OBSERVED:** restored canonical My Library suites passed all seven iPhone and five iPad
methods. Their fixture commands took 914.057 / 792.949 seconds, respectively. iPad's whole
conformance job passed. The iPhone job and run failed because experimental B/A2 failed,
with final exits `[0,1,1,0]`; no passing canonical result replaced those failures. These
passes exercise restored behavior only. Suspended iPad's unchanged browse assertions supply
one safety observation, not coverage of every proof or APNs-dependent application behavior.

### Next comparison and merge evidence

**OBSERVED local reproduction:** on a disposable loopback port, after an accepted connection
is closed by its server, a plain bind fails with errno 48 while a bind with `SO_REUSEADDR`
succeeds. The current fixture's preflight uses a plain bind, while its Python HTTP proxies
use reusable listener sockets. **ASSUMED cause of the hosted fixture error:** recently closed
connections can explain it, but the job did not record the failing port or socket owner, so
a remaining live listener cannot be excluded.

**OBSERVED follow-up scope:** the separate experiment ref gives the preflight reusable-bind
semantics, still refuses a live listener, and reports the failed port and errno. Local
socket controls require both rejection of an occupied listener and acceptance of a reusable
closed-connection port. This repair is kept outside #395. It changes neither assertions,
fixture contents nor test allowances. Main updates are integrated before the next comparison.

Only the maintainer schedules the next run after capacity is available:

```sh
ghx workflow run apple-ci.yml --repo legitimate-apps/dulcet \
  --ref ci/apsd-pressure-rerun-343 \
  -f conformance_only=true -f apsd_experiment=true
```

**ASSUMED decision that another run can make:** complete the missing iPhone B/A2 workload and
check whether its long query shortens with suppression and returns after restoration; repeat
the iPad CPU/test-duration reversal on a second VM. Valid suppression with persistent long
queries would argue against apsd being the bottleneck for those stalls. Repeated relief with
restoration reversal would support a narrow, proof-scoped intervention for review. A missing
loop or severe stall, monotone improvement, fixture failure, identity/restoration failure or
missing coverage remains inconclusive for starvation, even if all proofs pass.

**ASSUMED merge threshold for any intervention:** reproducible complete comparisons on both
devices, retained query identities/timings, CPU coverage and restoration; explicit dependency
review for every affected assertion; and full conformance under the proposed actual scope.
The earlier audit found no APNs registration, push entitlement, CloudKit or ubiquity calls;
MPRemoteCommandCenter is present and local media controls still need their own proof. That
source audit alone does not establish that every simulator service interaction is unaffected.
No permanent simulator configuration, larger allowance or retry is proposed. #395 can merge
its measurement improvement after required CI independently of this experiment.

## Earlier dispatch 40261216579

**OBSERVED:** [run 40261216579](https://github.com/legitimate-apps/dulcet/actions/runs/40261216579)
ran head `0bc8771c` with `conformance_only=true` and `apsd_experiment=true`. Both device jobs
failed before any intervention or test. The core conformance job passed. Both device results
land in the original interpretation table's **inconclusive** row, not the row that rejects apsd
as the driver. No permanent or proof-time quiescence is justified by this run.

**OBSERVED recommendation implemented in #395:** retain per-PID CPU attribution and upload its
raw record on every verdict. Remove the experiment input, workflow invocation/cleanup,
suspension tool, experimental test entry point and experiment-only control from the merge
candidate. Required proofs have their normal behavior. A separate experimental ref is provided
below for a corrected comparison; it is not intended for merge.

## What failed on each device

**OBSERVED source:** device job logs fetched with
`ghx api repos/legitimate-apps/dulcet/actions/jobs/<job-id>/logs`, and artifacts downloaded with
`ghx run download 40261216579 --repo legitimate-apps/dulcet`.

| Observation | iPhone | iPad |
|---|---|---|
| Job | [121373856471](https://github.com/legitimate-apps/dulcet/actions/runs/40261216579/job/121373856471) | [121373856508](https://github.com/legitimate-apps/dulcet/actions/runs/40261216579/job/121373856508) |
| Guard failure, UTC | 03:03:48.795 | 02:59:52.873 |
| Job log line of error | 8401 | 8410 |
| Completed idle windows | 0 | 0 |
| Completed A/B/A proof arms | 0 | 0 |
| Experiment result bundles | 0 | 0 |
| apsd persisted event rate | unavailable | unavailable |
| Accessibility query durations | unavailable | unavailable |
| Raw sampler samples | 112 | 72 |
| Sampler errors | 1 TimeoutExpired | 1 TimeoutExpired |

**OBSERVED:** both errors are `apsd PID does not have the selected runtime executable`.
The guard expected the selected runtime's `usr/libexec/apsd`. The actual process executable in
both raw records was:

```text
/Library/Developer/CoreSimulator/Volumes/iOS_23F77/Library/Developer/CoreSimulator/Profiles/Runtimes/iOS 26.5.simruntime/Contents/Resources/RuntimeRoot/System/Library/PrivateFrameworks/ApplePushService.framework/apsd
```

**OBSERVED:** the guard rejected the PID before saving a target or reaching `set_suspended`.
There was no STOP/CONT operation, idle window, warm-up, test arm or full My Library execution
in either device job. Cleanup records have `suspended: null`; the always-run restore steps
succeeded because no suspension state existed. Missing query durations are not zero-duration
queries. The failure is a harness defect, not an application failure or evidence that stopping
apsd helps or harms the proof.

**OBSERVED implementation error:** the original guard and its offline control shared the same
incorrect `usr/libexec/apsd` path assumption. The control established the guard's implementation,
not that path's validity on this runtime. The guard did not retain the selected launchctl PID
or its expected root, so an additional lookup race cannot be excluded. The corrected experimental ref tests the framework
path actually retained by this run and logs expected/observed target details before signaling.

**ASSUMED, still unresolved:** earlier retained archives' identity-failure loop might drive
logging work and starvation. This run measures apsd CPU but collects no loop event rate and
performs no intervention. It therefore neither supports nor refutes that causal chain, and
says nothing about the safety of running the browse proof with apsd suspended.

## Why the attribution is worth retaining

**OBSERVED:** per-PID executable paths distinguish simulator diagnosticd from host diagnosticd.
The following are cumulative own user+system CPU deltas over a common interval in which all
listed identities were observed, using the raw `cpuProcesses` and `cpuObservedMillis` fields.
They are not the old peak percent-CPU snapshots, nor costs during a My Library test.

| Common observation window, UTC | iPhone | iPad |
|---|---:|---:|
| Start | 03:00:35.251 | 02:57:22.408 |
| End | 03:04:08.068 | 02:59:58.982 |
| Observed seconds | 212.817 | 156.574 |
| Simulator diagnosticd CPU-seconds | 67.56 (PID 15364) | 34.50 (PID 9485) |
| Host diagnosticd CPU-seconds | 38.78 (PID 15370) | 20.97 (PID 9487) |
| Simulator apsd CPU-seconds | 26.36 (PID 15380) | 7.41 (PID 9488) |
| Simulator logd CPU-seconds | 2.45 (PID 15316) | 1.54 (PID 9442) |
| Host logd CPU-seconds | 0.47 (PID 86) | 0.28 (PID 86) |

**OBSERVED paths:** host daemons are `/usr/libexec/diagnosticd` and `/usr/libexec/logd`.
Simulator diagnosticd/logd paths are the runtime root above followed by
`usr/libexec/diagnosticd` and `usr/libexec/logd`. The complete path, PID and launch identity are
kept in each raw record and CPU-TIME summary row. No listed identity had a CPU reset in these
common windows; each daemon's observed coverage equals the table's interval.

**OBSERVED distinction:** basename aggregation hid both diagnosticd processes and their
separate cumulative costs. The simulator instance consumed more CPU than the host instance
on each VM in these windows. **ASSUMED:** either instance's work could matter to starvation;
these costs alone do not identify the live consumer, the messages causing its work or the
bottleneck for accessibility. No XCTest comparison ran. Even the recorded job load peaks
cannot bridge that missing intervention.

**OBSERVED retained implementation:** `host-pressure` records cumulative own CPU, PID, launch
identity, parent PID, state and untruncated executable per sample and at phase boundaries.
`stop` adds a closing CPU snapshot. Existing HOST PRESSURE, TOP, CPU and STACK line formats
remain intact. New `HOST PRESSURE CPU-TIME {json}` records carry `cpuLowerBoundSeconds`,
`observedSeconds`, interval/reset counts and boundary presence. A new always-run upload keeps
`dulcet-host-pressure.log` for passing and failing platform/core/device jobs.

**OBSERVED limits:** processes born and exited between readings are invisible; unsampled
initial/final CPU is excluded. Launch identity has `ps lstart` precision. Missing coverage,
reused identities and reset counters cannot establish quiet. Normalize cost by the reported
observed seconds and inspect paths. Load1 is delayed and the existing peak remains a peak.
The common windows above cross boot and failure-reporting phases, not an accessibility phase.

To reproduce the table after downloading each `dulcet-apsd-experiment-<device>-40261216579-1`
artifact, apply this from the repository root with `PRESSURE` pointing at its raw log:

```sh
PRESSURE=evidence/dulcet-host-pressure.log python3 - <<'PY'
import json, os, runpy
m = runpy.run_path('tools/ci/host-pressure')
r = [json.loads(line) for line in open(os.environ['PRESSURE']) if line.strip()]
s = [x for x in r if x.get('cpuProcesses')]
key = lambda p: (p['pid'], p['started'], p['executable'])
names = {'diagnosticd', 'apsd', 'logd'}
ids = {key(p) for p in s[-1]['cpuProcesses'] if p['name'] in names}
i = next(i for i, x in enumerate(s) if ids <= {key(p) for p in x['cpuProcesses']})
print(s[i]['cpuObservedMillis'], s[-1]['cpuObservedMillis'])
for p in m['cpu_deltas'](s[i:]):
    if key(p) in ids:
        print(p['pid'], p['cpuLowerBoundSeconds'], p['observedSeconds'], p['executable'])
PY
```

## Historical corrected protocol, subsequently run as 40272074448

**OBSERVED scope:** the merge candidate contains no apsd suspension or experiment switch.
The separate ref `ci/apsd-pressure-rerun-343` retains the explicit opt-in A/B/A protocol,
corrects the guard to the observed runtime framework executable and emits target-check
witnesses before any signal. It retains exact PID/start/path checks, single-device isolation,
restoration and fresh fixtures. Its control includes the observed framework path, a host path,
a different runtime path and a wrong PID. The repeated browse method and required full suite
share the main branch's current device allowance; the experiment does not increase it.

**ASSUMED at the time, now superseded by the result above:** the corrected path must pass the new target witness before this run
can answer either causal question. Schedule this only after hosted capacity is free:

```sh
ghx workflow run apple-ci.yml --repo legitimate-apps/dulcet \
  --ref ci/apsd-pressure-rerun-343 \
  -f conformance_only=true -f apsd_experiment=true
```

**ASSUMED interpretation:** running/suspended/restored idle windows would first establish
whether the loop reproduces and whether suppression lowers apsd and diagnosticd/logd CPU.
Consecutive fresh-fixture browse A/B/A arms would then compare exact query activities and
first automation offsets without interposing the rest of the suite. The original full suite
runs restored afterward; no pass replaces a failure, and comparison bundles supply no parity
evidence. Offline log replay occurs after idle measurements, without a new live consumer.

- **ASSUMED support criterion:** B suppresses the loop and daemon cost, host CPU/paging improves,
  matched query durations shorten and A2 reverses the change. That supports a contribution on
  these VMs, not a permanent quiesce or a flake-rate claim.
- **ASSUMED negative criterion:** valid suppression leaves host pressure and starved queries
  unchanged. That argues against apsd driving those stalls. CPU relief without query relief
  identifies work without establishing it as the bottleneck.
- **ASSUMED inconclusive criterion:** no reproduced loop/starved queries, invalid identity or
  restoration, missing coverage, timeout, different assertion path, or monotone improvement
  through A2. Natural decay/caching and the extra pre-proof time remain confounds.

**OBSERVED merge recommendation:** merge only the attribution improvement after required CI;
keep any further intervention on the experimental ref. This failed experiment supplies no
basis for permanent quiescence. A quiet pressure gate or a passing restored suite alone must
not be described as an apsd fix.
