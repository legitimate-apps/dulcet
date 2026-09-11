# Naming which loopback layer stopped answering

`tools/conformance-env/observe-transcode-cache` already distinguishes a stalled cache search from
an absent corpus item: `find_probe_song` raises `CacheSearchStalled` with
`"This is a stall, not an absent corpus item"` rather than blaming the fixture library. That closed
one conflation and left the next one open — *the loopback stalled* is where every investigation of
this failure has run out of instrument. `diagnose_loopback` answers the follow-up question.

All fixtures bind only `127.0.0.1:4533`; no production or personal server is exercised.

## What it does

On the stall path only, after the primary cause has been printed and flushed, three probes run
against **deliberately different layers** — two probes through one stack are one probe run twice:

| probe | layer | reaches |
|---|---|---|
| `socket.create_connection` | TCP | the listener, bypassing urllib entirely |
| `GET /ping` | HTTP | Navidrome's request loop, no database |
| `GET /rest/ping.view` | authenticated Subsonic | an authenticated query against a tiny indexed table |

Verdicts: `connection-refused`, `nothing-accepting`, `http-blocked`, `database-path-blocked`,
`server-answering`.

Two properties are easy to get wrong and are asserted, not assumed:

- **Classification is on ELAPSED TIME, not on whether a call raised.** A probe that fails in a
  millisecond has been *answered* — by a missing endpoint, or by a reply this probe cannot parse —
  and answering fast is the opposite of blocked. Keying on exception-vs-no-exception would repeat,
  inside the diagnosis, the exact conflation the tool was changed to stop making. Prompt HTTP and
  authentication failures stay anomalies under `server-answering`. Healthy layers answer in under
  ~15 ms and the stall under investigation runs to tens of seconds, so the 1.0 s cut is not delicate.
- **The exception text is redacted by construction.** `subsonic_url` puts `u`, `t` and `s` in the
  query string. No urllib exception reachable here currently embeds the URL, but that is an accident
  of their `__str__`, not a maintained guarantee.

## Budget

The diagnosis costs **at most `DIAGNOSIS_TIMEOUT_SECONDS` (1.25 s), shared by all three layers**,
enforced by an absolute deadline with `ITIMER_REAL` around each operation including response-body
reads. It is spent only on a run that has already failed, and adds nothing to a run that has not.
The search budgets (`SEARCH_DEADLINE_SECONDS`, `SEARCH_ATTEMPT_TIMEOUT_SECONDS`) and the stream
budget are unchanged.

Ordering is part of the contract: the primary cause is printed and flushed **before** the optional
diagnostic I/O, so it survives a diagnosis that itself fails or is cut short.

## Controls

| control | what it can prove | where it runs |
|---|---|---|
| `tools/test-cache-loopback-diagnostic --case branches` | 12 classification cases and 12 credential canaries, by mock injection under a virtual clock. Checks WHICH probes ran, not only the verdict. | anywhere |
| `tools/test-cache-loopback-diagnostic` (5 deadline cases) | the real failure path against a real stalling/dripping HTTP server: primary-before-diagnosis ordering, `attempts >= 2`, the verdict, the shared allowance, the exit code | needs `127.0.0.1:4533` free |
| `tools/test-cache-loopback-mutations` | 14 mutations, each deleting exactly one claimed property; each must fail an ASSERTION, not merely exit nonzero | needs `127.0.0.1:4533` free |

Both are wired as blocking steps in `core-ci.yml`'s `conformance-env-linux` job, ahead of the step
that starts the disposable Navidrome, so the port is free when they run. Measured cost: **35 s**.

The deadline cases drive the failure through a short search window
(`DULCET_TRANSCODE_SEARCH_DEADLINE_SECONDS=1.5`, `..._ATTEMPT_SECONDS=0.5`) so the suite costs
seconds rather than a minute. The shipped 60 s/10 s defaults are sized from CI measurement and are
proven by `tools/test-cache-search-readiness`; everything after the window closes is identical at
either scale.

### What `fresh-budget-per-layer` needed

Giving each layer `remaining = budget` instead of `deadline - start` does not change any verdict, so
the mutation was previously caught only by the process's **total elapsed time** landing 0.31 s past a
threshold. That is a margin, not a property. The control now parses `connect_seconds`,
`ping_seconds` and `rest_ping_seconds` out of the verdict and bounds their **sum** against the shared
allowance, which is the property itself:

```text
KILLED fresh-budget-per-layer exit=1 AssertionError: shared-rest-drip: layers consumed 2.022s
  against a shared 1.25s allowance: {'connect_seconds': '0.000', 'ping_seconds': '0.763',
  'rest_ping_seconds': '1.259'}
```

## OBSERVED

`python3 tools/test-cache-loopback-diagnostic` and `python3 tools/test-cache-loopback-mutations`,
2026-09-11, both exit 0: all 24 branch cases pass, all five deadline cases pass, all 14 mutations are
killed with an assertion.

```text
PASS ping-stall        primary_seconds=2.038 exit_seconds=3.365 verdict=http-blocked          layer_seconds_total=1.254
PASS ping-drip         primary_seconds=2.058 exit_seconds=3.330 verdict=http-blocked          layer_seconds_total=1.252
PASS rest-drip         primary_seconds=2.051 exit_seconds=3.374 verdict=database-path-blocked layer_seconds_total=1.251
PASS shared-rest-drip  primary_seconds=2.065 exit_seconds=3.337 verdict=database-path-blocked layer_seconds_total=1.256
PASS shared-rest-stall primary_seconds=2.054 exit_seconds=3.334 verdict=database-path-blocked layer_seconds_total=1.251
```

⚠️ **Where that run happened, and why it matters.** The port-bound cases were executed inside a
container with its own network namespace (`--network none`), because the development machine already
had a service on `127.0.0.1:4533` and the base-URL guard is deliberately not weakened to dodge it.
The container is Linux, same as `ubuntu-latest`, and the controls are stdlib-only — but it is not
the CI runner, and CI timings are its own observation. `core-ci` is the record.

⚠️ **What none of this establishes.** These controls prove the diagnosis classifies correctly and
stays inside its allowance. They say nothing about which verdict a real starved apple-ci runner
produces — that is the measurement the instrument exists to make, and it has not been made yet.
