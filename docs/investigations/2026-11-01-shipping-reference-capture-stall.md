# Bound and diagnose a silent macOS shipping-reference capture

## Observed failure and limits

[apple-ci run 41289800644, attempt 1](https://github.com/legitimate-apps/dulcet/actions/runs/41289800644/attempts/1),
job 124798520931, completed its deterministic design pair at 08:57:24 UTC on
November 1. The advisory shipping-reference step started at 08:57:26 and SwiftPM
reported the shipping executable built at 08:57:30. It then emitted no capture
result until cancellation at 10:25:14: 5,268 seconds in that step.

**OBSERVED:** the host-pressure artifact contains 1,025 observations of shipping
PID 64674 from 08:57:31.524 through 10:25:14.849. Every observation reports `S<`
(sleeping), cumulative CPU time of 0.31 seconds, and RSS between 15,520 and
15,536 KB. The enclosing 1,026 pressure samples have load1 between 0.762 and
2.141. These observations do not support CPU starvation as the explanation.

**UNKNOWN:** the internal wait. There is no stack of this capture process, and
its previous logging did not identify intermediate operations. AppKit setup,
activation, ScreenCaptureKit content enumeration, image capture and teardown
remain possible boundaries; none is established as the historical cause. The
missing shipping artifact does not prove that `main` was never reached: an
empty root or hidden preflight content also produces no uploaded files.

The earlier deterministic capture incident documented by [PR #416](https://github.com/legitimate-apps/dulcet/pull/416)
reported an uninterruptible process state, whereas this process was sleeping.
Low CPU consumption in both incidents does not establish a shared cause.

A bounded census of the available run/job records, deduplicating inherited rerun
step timestamps, contains 55 successful shipping-reference executions lasting
53–66 seconds, seven skips, this one cancelled execution and one pending row.
The maximum successful example is [run 41014940360](https://github.com/legitimate-apps/dulcet/actions/runs/41014940360),
66 seconds. This is a comparison sample, not a project-wide prevalence estimate.

## Change

The workflow builds `DulcetShippingReferenceCapture` separately, then supervises
its exact executable PID with the shared `tools/run-design-capture` monitor in
`--kind shipping-reference` mode. A launch trampoline reports its PID before
replacing itself with the capture binary, permitting diagnosis even if exec or
initialization before `main` blocks.

The capture executable emits flushed receipts before application setup,
window ordering/activation, host attachment/layout, compositor settling,
ScreenCaptureKit content enumeration and image capture, file writing, and
window teardown. Render receipts identify preflight or recorded capture, state
and appearance. Sixty seconds without a valid receipt records bounded `ps` and
`sample` diagnostics and fails the invocation; unrelated output cannot extend
that deadline. Diagnostic commands, TERM/KILL cleanup and exit-time pipe draining
are also bounded. A new three-minute step cap contains a stalled build or supervisor
itself; the sampled healthy step maximum is 66 seconds, so this outer cap is more
than 2.7 times that observed maximum. It does not replace the sixty-second
phase-inactivity mechanism. There is no retry and no larger timeout.

The shared supervisor and unchanged design controls originate in PR #416 at
`2a93d64a`; this change adds shipping-specific completion and diagnostic labels
while retaining design mode as the default. It includes the shared files so the
shipping fix is usable independently; the overlapping files need to retain the
kind extension when both PRs are integrated.

Process sidecars are uploaded separately from image artifacts whenever the
shipping step was reached. Shipping images retain their existing advisory,
non-deterministic design-review policy. A terminal shipping completion can report
missing captures; it is not a pixel-regression pass. A nonzero exit, a stall or a
missing completion still fails the capture invocation. The workflow retains its
existing `continue-on-error` policy for this advisory reference and its artifacts;
the process/protocol controls are blocking checks.

The completion summary now derives its expected capture count from the state
and appearance sets, matching the manifest instead of displaying a stale `/14`.

## Validation

The unchanged shared controls exercise real fork/exec, pipes, signals and a
usable macOS sample. Shipping-specific controls apply the same process tests to
shipping completion, reject a design PASS as shipping completion, preserve a
partial shipping report, exercise CLI protocol selection, and retain the exact
last-entered ScreenCaptureKit stage when its completion is blocked.

Receipt-driven virtual time tests exact expiry at 60 seconds and accepted
completion at 59.999 seconds after real readiness. They do not require Python
startup or host scheduling to satisfy a subsecond deadline. Real captures use
the real monotonic clock. These controls establish diagnostic failure behavior;
they do not reproduce or explain the historical ScreenCaptureKit/AppKit wait.

The shared design suite has 14 controls and the shipping suite has 18, including
two blocked ScreenCaptureKit-stage subcases. All 32 controls passed locally. The
UI-test source typecheck passed every covered directory, and the CI policy and
Apple planning controls passed.

**OBSERVED local execution (macOS 27.2):** the actual shipping product compiled
successfully in 18.46 seconds. The supervised standard command completed its
preflight and wrote all 68 expected shipping references plus both pinned controls:
70 JPEGs, no missing captures, exit zero and a reaped child. The manifest records
68 images rendered by the current WindowServer capture and two copied pinned
resources. File sizes, JPEG signatures and SHA-256 values agree with every
manifest record. The process took 47.514 seconds and the maximum receipt gap was
0.533 seconds. This establishes successful local capture and supervision, not
run-to-run pixel determinism or a repair of the unknown historical wait.
