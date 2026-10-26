# apsd experiment result and retained CPU attribution, Refs #343

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

## A follow-up that can answer the missing questions

**OBSERVED scope:** the merge candidate contains no apsd suspension or experiment switch.
The separate ref `ci/apsd-pressure-rerun-343` retains the explicit opt-in A/B/A protocol,
corrects the guard to the observed runtime framework executable and emits target-check
witnesses before any signal. It retains exact PID/start/path checks, single-device isolation,
restoration and fresh fixtures. Its control includes the observed framework path, a host path,
a different runtime path and a wrong PID. The repeated browse method and required full suite
share the main branch's current device allowance; the experiment does not increase it.

**ASSUMED next test:** the corrected path must pass the new target witness before this run
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
