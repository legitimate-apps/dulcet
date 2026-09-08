# PR 103 repair evidence

Local controls use synthetic data; no archive, signing account, dispatch or upload is exercised.

## Finding 5 — script multiplicity

OBSERVED: new committed-pair mutation control failed against the old gate (gate returned PASS
with declared=8 generated=8). Original SHA-256:
`9448b65e2a29f66e890ed1a2d7df72abfe45b3fe8b5f1c955f54c5c1349c9c6d`;
after replacing exactly one Gradle invocation with `echo WRONG`:
`dad9f18ba8e807748066a2195378c44e5947edb3b582024913685eed5fd76ac9`.
With multiset comparison the same mutation returns FAIL, expected 8 copies / found 7.
`python3 tools/test-xcode-script-phases` passes. Parser and non-body limits are now explicit
in the tool and the working agreement; this is not a full XcodeGen regeneration gate.

## Finding 6 — killed lock holder

OBSERVED: the strengthened test failed on the old implementation: the first Gradle workload
was still alive after SIGKILL and wait of the Python holder. The fixture executes its timed
work in one PID, matching gradlew's final exec of Java. Python now execs gradlew and preserves
an inheritable flock descriptor; holder and invocation share a PID. The same test now verifies
that PID no longer exists and the replacement finishes. The unchanged unwrapped overlap control
still detects overlap. `python3 tools/test-run-gradle-exclusive` passes.
Descendants retaining the descriptor retain the lock; this is not a Gradle daemon shutdown tool.
