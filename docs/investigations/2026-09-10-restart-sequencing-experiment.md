# Does a freshly booted simulator slow the Navidrome restart next to it?

**Status: OPEN EXPERIMENT.** Pre-registered 2026-09-10, before any run of the changed workflow.
The reorder in `.github/workflows/apple-ci.yml` **is the test**, not the fix. Nothing below claims
the reorder improves the pass rate.

## The observation

`apple-ci`'s Darwin conformance step restarts the disposable Navidrome three times, each time
running the same two probes: `await-library-ready --after-last-scan` (readiness) and
`observe-transcode-cache` (one real transcode stream). Across the 167 job logs in which that step
executed between 2026-09-02 and 2026-09-10, **99 of them green**, the two probes are elevated at
exactly the restarts that a `xcrun simctl create` + `boot` immediately precedes.

Absolute, green runs only, n=99. `libwait` is `TRANSCODE CACHE CLEARED` -> `library ready:`.

| restart | preceded by `simctl create` | libwait median | libwait p90 | libwait max |
|---|---|---|---|---|
| #1 (macOS suite) | **0 / 99** | **2.61 s** | 2.78 s | 4.11 s |
| #2 (iOS suite) | **99 / 99** | **9.49 s** | 37.88 s | **89.01 s** |
| #3 (tvOS suite) | **99 / 99** | 5.84 s | 23.84 s | 52.02 s |

Paired **within each run**, which removes runner identity — the confound this project's own
noise-floor note warns about:

| paired ratio, green runs | median | direction consistent | sign test |
|---|---|---|---|
| libwait #2 / #1 | **3.61x** | **99 / 99** | 3.2e-30 |
| libwait #3 / #1 | 2.28x | **99 / 99** | 3.2e-30 |
| stream first response, obs#2 / obs#1 | **5.47x** | 97 / 99 | 1.6e-26 |
| stream first response, obs#3 / obs#1 | 1.96x | 82 / 99 | — |
| stream first response, obs#3 / obs#2 | 0.40x | 23 / 99 | — |

Two different tools, hitting two different endpoints, move together and in the same order.

## What is already ruled out

- **"Later in the job is slower."** Refuted: restart #3 is *below* restart #2 on both probes
  (obs#3/obs#2 median 0.40x, above 1 in only 23 of 99 runs). The effect is positional, not
  cumulative.
- **"The whole runner is slow in some runs."** Two independent compute-bound workloads in the same
  job correlate (Spearman rho **+0.507**, n=99, between the macOS and iOS Gradle task durations),
  so a slow-runner signal exists and the instrument can see it. The loopback probes do not ride on
  it: rho **-0.102** and **+0.007** against those same two Gradle durations. The negative is
  validated by a positive control that fires on the same data with the same code.
- **"A client bug or a network fault."** Navidrome's own self-reported `startupTime` — pure local
  CPU and disk work measured inside the server process, with no network and no Dulcet code
  involved — shows the same ordering, and paired within each run it is the sharpest signal in the
  set:

  | server-internal `startupTime`, green runs, n=94 | median | p90 | max |
  |---|---|---|---|
  | after restart #1 | **40.2 ms** | 89.3 ms | 0.17 s |
  | after restart #2 | **524.5 ms** | 2539 ms | 12.76 s |
  | after restart #3 | 305.8 ms | 1761 ms | 17.78 s |

  Paired within run: #2/#1 median **12.66x**, above 1 in **94 / 94**; #3/#1 median 7.16x, 93 / 94.
  (94 of the 99 green runs carry all three values. The step's cleanup prints `tail -200` of the
  Navidrome log, which retains the last three of the four launches — the three restarts. That
  mapping is ASSUMED from the tail length, and it is corroborated by the shape: the elevated value
  is the middle one, which fits restart #2 and does not fit the alternative reading where the first
  retained line is the job's initial launch.)
- **"A transient boot storm still in flight."** Time from the `simctl create` to the restart does
  not predict the restart's readiness window: rho **-0.080** at restart #2 and **+0.239** at
  restart #3 (n=99 each, median gap 49.1 s and 38.9 s). Neither is strong, and the two point in
  opposite directions, which is what noise looks like rather than a decaying transient. So the
  association is with a booted simulator being *present*, not with a boot still settling.

  ⚠️ **Resolved.** These two figures looked like a disagreement with the forensic report this work
  came from, which quoted +0.125 and +0.248, and the first version of this doc guessed that the
  *anchors* must differ. They do not. Independent review computed both anchors against three
  outcome variables over the same 99 green runs and reproduced all four numbers from **one**
  anchor — `No runtime specified, using ...` (emitted by `simctl create`) → `TRANSCODE CACHE
  CLEARED`. What differs is the **outcome**:

  | outcome measured from that one anchor | restart #2 | restart #3 |
  |---|---|---|
  | stream `first_response_seconds` | **+0.125** | **+0.248** |
  | `libwait` (CLEARED → `library ready:`) | **−0.080** | **+0.239** |
  | whole window (CLEARED → OBSERVATION) | −0.036 | +0.161 |

  So restart #3's apparent agreement (+0.248 vs +0.239) is coincidence — two different variables
  landing together there and splitting at restart #2. The conclusion is unaffected: the strongest
  of the four is +0.248, and a **sign flip between two latency measurements of the same restart**
  is itself evidence that neither is a real effect.

  🚨 One anchor–outcome pair must stay excluded: `simctl create` → `library ready:` gives rho
  **+0.448 / +0.618**, which looks like the transient everyone is hunting for and is **circular** —
  `libwait` is a component of that interval, so the outcome is inside its own predictor. It is
  omitted deliberately, not by oversight.

## What is NOT established

**That the adjacency is causal.** The correlation is perfect (99/99 versus 0/99), and a perfect
correlation is exactly where a confound hides. Restart #2 differs from restart #1 in more than
simulator adjacency — it also follows a different Gradle target and sits at a different point in
the step. The remaining candidate mechanism, that a booted simulator's steady-state daemons
contend for a 3-core runner, is **ASSUMED**. (The runner is 3-core: OBSERVED from Gradle's own
`w: The number of threads 4 is more than the number of processors 3`.)

## The design

- **Restart #2 is moved ahead of its `simctl create` + `boot`.** Nothing between the restart and
  the suite streams audio, so the empty-cache assertion the restart makes still holds at the
  suite's first request. Only the adjacency changes.
- **Restart #3 is deliberately left where it is.** It is the in-run control. Every future run then
  carries its own comparison between a moved restart and an unmoved one, which is far stronger than
  comparing runs to each other.
- **Restart #1 stays the baseline**, and remains the denominator for both paired ratios.

🚨 **"Baseline" means no `xcrun simctl create` has run in the step. It does NOT mean no simulator
is running.** Four `xcodebuild` invocations above it use iOS and iPadOS Simulator destinations, and
nothing shuts them down before restart #1 — the only `simctl shutdown` calls come after restarts #1
and #2. So the *number of booted simulators* also differs across the three arms, and the marker
field `simulator_created_before` is a statement about `simctl create` alone.

That is not a weakness to apologise for; it is the sharpest thing in the dataset. **Restart #1 is
the fastest of the three despite four simulator boots immediately before it.** Whatever the
mechanism is, "a simulator is running" does not capture it — a freshly *created* device does, which
is what the experiment manipulates.

Each restart announces itself, so the arm is a fact in the log rather than something inferred from
nearby `simctl` lines:

```
RESTART SEQUENCING index=1 phase=macos arm=baseline     simulator_created_before=false
RESTART SEQUENCING index=2 phase=ios   arm=resequenced  simulator_created_before=false
RESTART SEQUENCING index=3 phase=tvos  arm=unchanged    simulator_created_before=true
```

`tools/test-restart-sequencing-experiment` checks each declared arm and each declared adjacency
against the order of the commands themselves, and fails if either arm disappears — because a
one-armed experiment still *looks* measurable.

## How to read the result

Take the same paired ratios from runs of the changed workflow, green runs only, restart #1 as the
denominator in both cases. About 30 runs is ample: the pre-change effect is 99/99 and 97/99
directional, so a sign test on 30 paired runs detects a collapse comfortably.

Tolerances are numeric on purpose. "Near 3.6x" is not a criterion — a #2/#1 of 2.9x would be
"inconclusive" by this table while still reading as "near 3.6x" to someone who wanted it to.

| outcome | libwait #2 / #1 | libwait #3 / #1 | reading |
|---|---|---|---|
| **supported** | **< 1.5x**, and no longer directionally consistent (below 1 in ≥ 20% of runs) | **≥ 1.8x** | the adjacency is doing the work |
| **REFUTED** | **≥ 3.0x** | ≥ 1.8x | the association is with job position; the simulator is a bystander and the reorder should be reverted |
| **confounded** | **< 1.5x** | **< 1.8x** | both arms moved; something other than the reorder changed, and this change must not be credited until that is identified |
| **inconclusive** | 1.5x – 3.0x | any | collect more runs before concluding either way |
| **anomalous** | **> 3.6x** (rose), or #3/#1 rose above 2.6x | any | the manipulation made it worse, or the environment changed under the experiment; stop and re-measure the baseline before reading anything else into it |

The pre-change values these are judged against are #2/#1 = **3.613x** and #3/#1 = **2.280x**, both
99/99 directional. Any row is read only from **green** runs, with restart #1 as the denominator.

The "confounded" row matters: this branch also changes two probe budgets in the same series of
commits. Those changes alter what happens *after* a stall, not how long the restart's readiness
window is, so they should not move these ratios — but if both arms move together, that prediction
was wrong and the reorder has not been tested.

Recipe, from a job log:

- `RESTART SEQUENCING` lines give the index, phase and arm.
- `TRANSCODE CACHE CLEARED` -> `library ready:` gives libwait for the restart that follows.
- `library ready:` now also reports `attempts=` and `elapsed_seconds=` for the readiness poll
  itself, so the wait no longer has to be inferred from the gap between two adjacent lines.
- `TRANSCODE CACHE OBSERVATION ... first_response_seconds=` gives the stream probe.
- `TRANSCODE CACHE SEARCH attempts=... elapsed_seconds=` gives the search phase directly, instead
  of by subtracting the stream time from the surrounding wall clock.

## Adjacent finding: the "5 s iOS browse probe" is not a gate, and is not on `main`

The forensic pass that produced this experiment also flagged an "iOS `LiveDownloadTrackSeed` browse
probe" with a 5 s budget, against a 7.4 s p90 in the neighbouring window, and attributed 7 step
failures to it. Re-derived against `main`, that row conflates two different budgets:

- **The 5 s belongs to a post-failure diagnostic**, and only exists on the branch of the open pull
  request that adds it, not on `main`. It is invoked as
  `seed == nil ? await probeDisposableServer(baseURL:) : ""` — it runs *only after* the browse
  under test has already returned nothing, and its own comment says it "does not change the timeout
  of the request under test". It cannot cause a failure, so raising it cannot prevent one; it would
  only add up to 20 s to every already-failed browse in a job that is already the slowest thing in
  the queue. **The 5 s is correct and should stay.**
- **The 7 failures belong to the browse itself**, which runs on the core's 30 s Ktor budget. That
  budget is firing on a genuine stall — one `getMusicFolders` reached `http-started` at 113 ms and
  then produced nothing for 30.23 s — and the forensic report's own ranked-fixes table says not to
  touch it, because raising it converts a visible stall into a job that runs to its time limit.

Query used, with its positive control, because a clean-looking negative from a broken query is how
this project has lost the most time: `git grep -nE 'timeoutInterval[A-Za-z]*[[:space:]]*=[[:space:]]*[0-9]'`
returns **4 hits on the pull request's branch and 2 on `main`**: the two 5 s lines at
`DulcetAppleDownloadIntegrationTest.swift:544-545` are branch-specific, and two unrelated `= 0.5`
lines are present on both refs. Only the 5 s pair is branch-specific, and `probeDisposableServer`
exists on no other ref. An earlier attempt using `\s` returned nothing on *both* refs, because
`git grep`'s POSIX engine does not accept it — the positive control is what exposed that.

⚠️ The first version of this paragraph recorded the outcome as "the two 5 s lines on the branch and
nothing on `main`", which is wrong in the one paragraph whose whole subject is that a mis-recorded
query result costs this project time. The substantive claim was right; the recorded result was not.

So no budget change is proposed for the browse path here. The stall it reports is real, and the
sequencing experiment above is the test of what causes it.

## Corrections to earlier statements on this branch

Kept here because two of them live in commit messages that are already pushed and cannot be
amended without rewriting published history. This section is where the correction lives instead.

| stated | where | correct |
|---|---|---|
| "a job that has hit its 75-minute ceiling five times" | commit `dd91418`, and this doc before 2026-09-10 | The apple-ci job is `timeout-minutes: 120` and its conformance step is `timeout-minutes: 67`. `git log -S'timeout-minutes: 75'` shows `9f58ddb` moved it to 120. **0 of the 167 corpus logs carry a max-execution-time cancellation** — the 22 carrying `##[error]The operation was canceled` are `concurrency` cancels. Measured step durations: median 20.2 min, p90 48.1, max **57.8** of 67. |
| "the retry has fired ZERO times in 167 runs" | commit `bfbaedd` | True but the denominator is inflated. The retry and its message arrived with `084007c`; re-derived with `git merge-base --is-ancestor` over every run head, **94** contain it, **71** predate it and had no retry path at all, 2 are unresolvable locally. The finding is **0 of 94**, which is the stronger sentence. |
| "one per failed run" | commit `bfbaedd` | One each in five of the **46** failed runs. |
| the recorded `git grep` result | this doc, before 2026-09-10 | See the adjacent-finding section: 4 hits on the branch, 2 on `main`, only the 5 s pair branch-specific. |
| "the anchor definitions must differ" | this doc, before 2026-09-10 | It is an outcome difference, not an anchor difference. See the rho table above. |
| Navidrome "answers this exact query in 150-710 ms" | commit `8e36da3` | Those are times until `context canceled` — a **lower** bound on the answer time, not the answer time. The per-attempt sizing argument survives; the ratio claim built on it did not. |

## If it is refuted

Revert the reorder. The elevation is real either way — three instruments re-derived here agree on
it, two client-side (the readiness wait and the stream probe's first response) and one entirely
inside the server process (`startupTime`) — so a refutation moves the question to what else
distinguishes restart #2's position, not to whether the elevation exists.
