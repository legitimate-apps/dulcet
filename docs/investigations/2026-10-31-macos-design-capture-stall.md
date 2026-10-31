# Deterministic macOS capture: diagnose and bound a blocked process

## Observation and limits

**OBSERVED:** [apple-ci run 41192958497, attempt 2](https://github.com/legitimate-apps/dulcet/actions/runs/41192958497/attempts/2)
(source `9042809c`) reached “Capture deterministic macOS design evidence twice.” Run A
reported `PASS images=70` at 17:05:37 UTC, about 80 seconds after its launch. Run B's
SwiftPM build finished at 17:05:39; the process then produced no capture result before
the unchanged nine-minute step deadline, 17:13:29.

The host-pressure artifact contains 91 observations of run B's PID 75136 between
17:05:41 and 17:13:25. Every observation reports state `U<` (uninterruptible wait),
CPU time 0.27 seconds and approximately 35 MB RSS. Host load1 over the enclosing
capture window was 1.04–3.21. This evidence does not support CPU starvation as the
explanation for this particular wait.

**UNKNOWN:** the internal wait. There is no stack from this process. Its state does
not establish WindowServer, a modal dialog, activation, a missing window, teardown,
or a loader/exec operation as the cause. The capture executable previously emitted
only its terminal result and wrote its timing sidecar after rendering. The failure
artifact also excluded the capture directory; its absence is not evidence that run
B had not reached `main`.

**OBSERVED, bounded comparison:** the recent main-run census has ten distinct
successful capture executions and two skips after deduplicating inherited rerun
step timestamps. A further sample of the twelve latest failed runs in the
October 18–23 query window contains eight successful capture executions (213–243
seconds per pair) and two skips, including main run 39938927349 and unrelated PR
branches. No matching capture-process timeout appears in those samples. They do
not establish a prevalence rate or prove that this failure began with the PR.

## Change

`tools/run-design-capture` supervises the already built capture executable directly.
A small launch trampoline reports its PID before `exec`, then replaces itself with
the capture executable. The supervisor therefore knows the exact capture PID even
if exec, dynamic loading, or initialization before Swift `main` blocks. It does not
find processes by a name substring.

`DulcetCapture` writes flushed phase receipts before application initialization,
window construction/ordering/activation, host attachment/layout, bitmap draws,
layout diagnostics, encoding, detach/close, manifest writing and process exit.
State, appearance and preflight/recorded phase accompany render receipts.
Receipts and host records stay outside the byte-compared capture directories.

Sixty seconds without a valid phase receipt fails the invocation. Unrelated output
does not extend the bound. Before termination the supervisor records `ps` and a
one-second `sample` of the exact PID. Diagnostic commands and TERM/KILL reaping
are themselves bounded. An uninterruptible process that cannot be reaped is
reported as such; it cannot restore an unlimited supervisor wait. Exit-time pipe
draining is limited by both bytes and time because a descendant can inherit and
continuously write the pipe.

The workflow retains its nine-minute step cap and exact recursive byte comparison.
There is no retry and no suppressed failure. A PASS line followed by a nonzero exit
still fails; a zero exit without the capture completion line also fails. The
workflow uploads process diagnostics on success or failure when the capture step
was actually reached.

The sixty-second inactivity limit is conservative relative to per-operation work,
not a newly measured healthy maximum. Every invocation records its maximum observed
phase gap so the hosted proof supplies that measurement. This change makes a
recurrence actionable; it does not claim to repair the unknown underlying wait.

## Controls

`python3 tools/test-run-design-capture` exercises real child processes without
opening windows. The same blocked fixture stays alive under the former direct
invocation but fails with exit 124 and the last entered phase under the supervisor.
Additional controls cover SIGSTOP, silence before `main`, healthy phase progression
past the total inactivity interval, unrelated stderr, closed output while alive,
quiet and continuously writing inherited pipes, missing completion, nonzero exit
after PASS, bounded diagnostic/reap failure, and a usable macOS stack from the exact
sleeping child. The process controls inject a monotonic clock while keeping the
production sixty-second interval. Virtual time advances only after real flushed
receipts or readiness handshakes: startup and scheduler delays do not consume an
artificial one-second deadline. Boundary controls check 59.999 and 60 seconds;
healthy receipts advance fifty seconds each, exceeding the total interval while
preserving progress. Inherited-pipe controls check descendant liveness and pipe
closure rather than subsecond execution speed. Handshakes have a sixty-second
containment guard that reports the missing boundary and cleans up fixture PIDs.
Production always uses the real monotonic clock.

The real hosted capture pair remains the rendering and byte-determinism proof.

**OBSERVED local validation (macOS 27.2):** the full standard command rendered both
appearances of all states plus the pinned controls through the supervisor. Both
processes exited zero with 70 JPEGs each (68 rendered references, two pinned controls).
`diff -qr` reported no differences between the complete run directories, and
`verify_design_captures.py` passed all manifest, geometry, provenance and pinned-byte
checks. Run A took 37.190 seconds with a maximum receipt gap of 0.578 seconds;
run B took 36.659 seconds with a maximum gap of 0.173 seconds. This validates the
instrumented pair on this host; required CI still supplies the hosted-runner proof.
The capture product compiled. After removing scheduler-speed assertions, all 14
supervisor controls passed on this host.
