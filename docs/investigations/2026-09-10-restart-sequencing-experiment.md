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

  ⚠️ These two figures are the one place a re-derivation disagreed with the report this work came
  from, which quoted +0.125 and +0.248. The conclusion is unchanged — both readings are far too
  weak to support a decaying transient — but the restart #2 figure differs in sign, so the anchor
  definitions must differ. Anchor used here: the `No runtime specified, using ...` line that
  `simctl create` emits, to `TRANSCODE CACHE CLEARED`.

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

| outcome | libwait #2 / #1 | libwait #3 / #1 | reading |
|---|---|---|---|
| **supported** | falls to **< 1.5x**, direction no longer consistent | holds near **2.3x** | the adjacency is doing the work |
| **REFUTED** | holds near **3.6x** | holds near 2.3x | the association is with job position; the simulator is a bystander and the reorder should be reverted |
| **confounded** | falls | also falls | something other than the reorder changed; do not credit this change until the other change is identified |
| **inconclusive** | between 1.5x and 3.6x | holds | collect more runs before concluding either way |

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
  only add up to 20 s to every already-failed browse in a job that has hit a 75-minute ceiling five
  times. **The 5 s is correct and should stay.**
- **The 7 failures belong to the browse itself**, which runs on the core's 30 s Ktor budget. That
  budget is firing on a genuine stall — one `getMusicFolders` reached `http-started` at 113 ms and
  then produced nothing for 30.23 s — and the forensic report's own ranked-fixes table says not to
  touch it, because raising it converts a visible stall into a 75-minute job timeout.

Query used, with its positive control, because a clean-looking negative from a broken query is how
this project has lost the most time: `git grep -nE 'timeoutInterval[A-Za-z]*[[:space:]]*=[[:space:]]*[0-9]'`
returns the two 5 s lines on the pull request's branch and nothing on `main`. An earlier attempt
using `\s` returned nothing on *both*, because `git grep`'s POSIX engine does not accept it — the
positive control is what exposed that.

So no budget change is proposed for the browse path here. The stall it reports is real, and the
sequencing experiment above is the test of what causes it.

## If it is refuted

Revert the reorder. The elevation is real either way — three instruments re-derived here agree on
it, two client-side (the readiness wait and the stream probe's first response) and one entirely
inside the server process (`startupTime`) — so a refutation moves the question to what else
distinguishes restart #2's position, not to whether the elevation exists.
