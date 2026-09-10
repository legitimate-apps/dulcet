# CI policy gate repair: mutation evidence

The three reported defects are repaired on `fix/restore-extracted-gate`. This report
compares the pre-repair verifier at `b07444b` with the implementation at `ddf5161`.
No workflow or extracted production script was edited. The restored class checks and
their source separation remain intact.

> **Citations corrected 2026-09-10.** This paragraph previously cited `a06ef7f` and
> `f578871`. Neither is reachable from this branch — they are pre-rebase copies of the
> two commits named above, so a reader following them got nothing. It also stated the
> base as `origin/main` at `2fbc37b`; the base is now `50c98de`. A verification report
> whose revisions cannot be checked out is not verification, and the rebase that
> orphaned them is routine, so assume any SHA written here needs re-checking after one.

## What changed

- Control wiring is collected from direct `jobs.<job>.steps` run properties, with
  step/job failure policy and inherited shell defaults. A control must be a direct
  shell command in a supported run scalar. Echoed text, comments, command lists
  that suppress failure, continued-error steps, and conditional wrappers do not
  count. Both inline and multiline `if false` are rejected. A known unreachable
  command is not acceptable as blocking control wiring.
- Every non-conformance Apple `(class, method)` cited in `FEATURES.yml` must have a
  resolved producer/emission association. Existing whole-target runs, the package
  scheme's declared test targets, and local function calls with positional/scalar
  arguments are accounted for. Unsupported computed selectors fail with the
  missing identity; exported workflow selectors are not silently skipped.
- Core and Apple case identities enter completion ledgers only after their
  assertions pass. Before printing success, the harness compares the completed
  identities and multiplicities to the required cases. Empty, partial, and duplicate
  execution cannot be hidden by the numerator. It does not freeze the test-case
  declarations against deliberate removal.

The source-seam fixture now includes a legitimate producer for its first emission,
then retains the dangling selector and conflicting emission in the next source.
That lets it satisfy the new coverage obligation while continuing to detect an
incorrect association across source boundaries. No existing verifier check was
relaxed to accept it.

## Before/after method

P1 and P2 mutations ran in temporary copies of the repository's six workflows,
`tools`, `FEATURES.yml`, and the package manifest. Neither the real workflows nor
production scripts were mutated. Each copy was evaluated by both verifier versions.
P1 replaced the real `test-health-request-policy` invocation. P2 deleted the macOS
library-sync emission and its consumer directory argument, or exported the selector
through workflow `env` and retained the wrong emitted class `DulcetMacTests`.

The new synthetic negative controls were also run before their corresponding fixes:
all six P1 controls failed with `expected exit 1, got 0`; both P2 controls failed with
`expected unresolved-association rejection, got exit 0`. After the fixes those
controls pass because the verifier rejects the mutated fixture with exit 1 and the
expected diagnostic. The unmutated repository verifier still exits 0.

### Real-workflow mutation output

```text
P1 echoed
  before: exit=0
CI policy valid across 6 workflows
  after: exit=1
tools/test-health-request-policy: no workflow invokes this control as a blocking command; require a direct run command with failure propagation and no conditional wrapper
P1 inline-comment
  before: exit=0
CI policy valid across 6 workflows
  after: exit=1
tools/test-health-request-policy: no workflow invokes this control as a blocking command; require a direct run command with failure propagation and no conditional wrapper
P1 suppressed
  before: exit=0
CI policy valid across 6 workflows
  after: exit=1
tools/test-health-request-policy: no workflow invokes this control as a blocking command; require a direct run command with failure propagation and no conditional wrapper
P1 continue-on-error
  before: exit=0
CI policy valid across 6 workflows
  after: exit=1
tools/test-health-request-policy: no workflow invokes this control as a blocking command; require a direct run command with failure propagation and no conditional wrapper
P1 empty-comment
  before: exit=0
CI policy valid across 6 workflows
  after: exit=1
tools/test-health-request-policy: no workflow invokes this control as a blocking command; require a direct run command with failure propagation and no conditional wrapper
P1 if-false
  before: exit=0
CI policy valid across 6 workflows
  after: exit=1
tools/test-health-request-policy: no workflow invokes this control as a blocking command; require a direct run command with failure propagation and no conditional wrapper
P2 deleted-emission
  before: exit=0
CI policy valid across 6 workflows
  after: exit=1
.github/workflows/apple-ci.yml: required method/emission association DulcetMacAccountConnectAppTest/librarySyncUsesCommittedGenerationsSchedulesRefreshAndReopensOffline cannot be resolved in any source; require a recognizable selector followed by its swift-testing-junit emission (no silent skip)
P2 exported-selector
  before: exit=0
CI policy valid across 6 workflows
  after: exit=1
.github/workflows/apple-ci.yml: required method/emission association DulcetMacAccountConnectAppTest/librarySyncUsesCommittedGenerationsSchedulesRefreshAndReopensOffline cannot be resolved in any source; require a recognizable selector followed by its swift-testing-junit emission (no silent skip)
```

### Completed-case summary mutation output

For this comparison, only the indicated execution loops were changed to iterate
empty tuples. The before run used both tools from `a06ef7f`; the after run used the
repaired tools. Harness self-mutation children omit recursive self-mutation only.
All three new controls failed before the ledger fix because the mutated harness
returned 0 and printed success; all three now pass by requiring exit 1 and no
success summary. Output below retains the summary or final assertion line and
omits ordinary case PASS lines and traceback frames.

```text
P3 empty core loops
  before: exit=0
CI policy control passed: 6/6 core cases, 6/6 apple cases, 10 extra checks: a script emitting the cited class name is accepted, a script emitting the target name where the class is cited is rejected, a correct per-method emission is accepted, a wrong emission is rejected even though the cited class is emitted elsewhere, a method dangling at a source boundary does not pair across it, a write inside a transitively invoked script satisfies the bijection, an unwired control is rejected, a wired control is accepted, an orphan whose name prefixes a wired control is still rejected, a control named only in a comment is not wired
  after: exit=1
AssertionError: CI policy control incomplete: core completed 0/6; missing=['self-hosted with workflow_dispatch only', 'self-hosted with pull_request', 'self-hosted with push', 'self-hosted in a comment with a hosted runner', 'xlarge runner with workflow_dispatch only', 'missing timeout-minutes']; unexpected=[]
P3 empty apple loops
  before: exit=0
CI policy control passed: 6/6 core cases, 6/6 apple cases, 10 extra checks: a script emitting the cited class name is accepted, a script emitting the target name where the class is cited is rejected, a correct per-method emission is accepted, a wrong emission is rejected even though the cited class is emitted elsewhere, a method dangling at a source boundary does not pair across it, a write inside a transitively invoked script satisfies the bijection, an unwired control is rejected, a wired control is accepted, an orphan whose name prefixes a wired control is still rejected, a control named only in a comment is not wired
  after: exit=1
AssertionError: CI policy control incomplete: apple completed 0/6; missing=['a script that writes the directory satisfies the bijection', 'a script that stops writing the directory is rejected', 'invoking a script that does not exist is rejected', 'an inline write still satisfies the bijection, unchanged', 'a directory written but never passed to verify-parity-evidence is rejected', 'a tools/ci path named only in a comment is not an invocation']; unexpected=[]
P3 empty core+apple loops
  before: exit=0
CI policy control passed: 6/6 core cases, 6/6 apple cases, 10 extra checks: a script emitting the cited class name is accepted, a script emitting the target name where the class is cited is rejected, a correct per-method emission is accepted, a wrong emission is rejected even though the cited class is emitted elsewhere, a method dangling at a source boundary does not pair across it, a write inside a transitively invoked script satisfies the bijection, an unwired control is rejected, a wired control is accepted, an orphan whose name prefixes a wired control is still rejected, a control named only in a comment is not wired
  after: exit=1
AssertionError: CI policy control incomplete: core completed 0/6; missing=['self-hosted with workflow_dispatch only', 'self-hosted with pull_request', 'self-hosted with push', 'self-hosted in a comment with a hosted runner', 'xlarge runner with workflow_dispatch only', 'missing timeout-minutes']; unexpected=[]; apple completed 0/6; missing=['a script that writes the directory satisfies the bijection', 'a script that stops writing the directory is rejected', 'invoking a script that does not exist is rejected', 'an inline write still satisfies the bijection, unchanged', 'a directory written but never passed to verify-parity-evidence is rejected', 'a tools/ci path named only in a comment is not an invocation']; unexpected=[]
```

## What this gate still does not verify

These are measured limits of **this policy gate**, not claims that a full CI run
would accept the same deletions:

1. Delete a `tools/test-*` control together with its workflow invocation. The orphan
   inventory is derived from existing files; there is no immutable required-control
   manifest. Erasing a control's assertions also passes because policy validation
   inspects wiring, not the meaning of a tool's implementation.
   **Relocation is no longer in this bucket** — see the 2026-09-10 repairs below.
2. Delete `tools/swift-testing-junit` itself. The inventory recognizes converter
   command text without checking that converter's implementation exists. Actually
   running its workflow command would fail.
3. Remove the direct repository `python3 tools/verify_ci_policy.py` step. The
   `test-verify-ci-policy` step remains wired, but its synthetic fixtures do not
   substitute for evaluating the real repository. Non-`test-*` workflow commands
   are not covered by the orphan rule.
4. Remove only `tools/ci/run-app-host-library-sync-proof macos` from the workflow.
   The simulator invocation still discovers that script; its text still contains
   the macOS selector, function call, and emission. Method accounting resolves
   source associations, not the workflow's argument-dependent execution paths.
   Runtime parity evidence is still needed to prove the cited method actually ran.

```text
LIMIT remove-control-and-invocation
  after: exit=0
CI policy valid across 6 workflows
LIMIT delete-converter-tool
  after: exit=0
CI policy valid across 6 workflows
LIMIT remove-policy-verifier-step
  after: exit=0
CI policy valid across 6 workflows
LIMIT remove-macos-helper-invocation
  after: exit=0
CI policy valid across 6 workflows
LIMIT erase-control-assertions
  after: exit=0
CI policy valid across 6 workflows
```

The command inventory supports the repository's block-style YAML and a bounded
shell syntax; it is not a general YAML or Bash evaluator. Unsupported aliases,
flow mappings, folded run scalars, custom shells, and conditional control commands
cannot provide wiring credit. It does not prove workflow triggering, the full job
dependency graph, shell/tool runtime behavior, or test assertions. The Apple
producer/directory inventory remains static and does not inherit the stronger
blocking-command guarantee of the control-wiring check. The success message must
not be read as a certificate that every workflow action executed or that nothing
important can be deleted.

## Validation

Both required commands passed on the repaired implementation:

```text
$ python3 tools/verify_ci_policy.py
CI policy valid across 6 workflows

$ python3 tools/test-verify-ci-policy
CI policy control passed: 6/6 core cases, 6/6 apple cases, 33 extra checks: ...
```

The final summary above abbreviates only the list of completed extra-check names.
Additional controls cover real/quoted commands, here-documents, inherited custom
shells, disabled errexit, property ordering, whole-target producer deletion, and
parameterized helper call deletion/wrong-class emission. Original restoration
fixtures continue to pass. The previously supplied three-version restoration,
transitive discovery, filename-boundary, and workflow preservation investigations
were not repeated.

## Repairs from the independent review, 2026-09-10

An adversarial review of this branch ran 19 repository mutations and reproduced the
headline table exactly. It found two defects that this document had claimed were
absent. Both are repaired here, and each repair is demonstrated by a mutation that
fails without it.

**The suite printed a total it did not count.** `tools/test-verify-ci-policy` ended with
a literal `Diagnostic wiring controls passed: 22/22 cases`. Emptying every diagnostic
case left that line unchanged and the exit status 0 — the precise defect this file
exists to prevent, roughly seventy lines below the fix for it. The suite now records
each case it runs and asserts the count against a declared constant.

**Relocation still retired a check.** The orphan sweep globbed `tools/test-*`
non-recursively, so moving a wired control into `tools/ci/` and dropping its invocation
removed it from CI while the gate still printed `CI policy valid`. `tools/ci/` is
exactly where this repository has already relocated a check for real, which is the
extraction defect this branch exists to repair. The sweep now uses `rglob`.
**MEASURED:** recursive and non-recursive find the same 42 files today, so this closes
the hole at no cost in false positives.

Two further holes surfaced while repairing the first one, neither reported:

- **Deriving the expected total from the contract is self-consistent.** A count of
  `1 + len(DIAGNOSTIC_CONTROLS) * 6 + 3` falls to match the actual when the contract is
  emptied, and passes. There is now an explicit floor that does not move with it.
- **The contract was declared twice.** `verify_ci_policy.py` and
  `test-verify-ci-policy` each held their own copy, so growing the verifier's list
  would leave the suite proving the old one and reporting a pass. The suite now reads
  the contract out of the verifier and fails if they disagree.

The first attempt at that last check used a regex for the parenthesised block and
smeared: `\((.*?)^\)` ran to the next line-initial `)` and returned every quoted token
in between, including regex source and comment prose. It read correctly on the
unmutated file and failed only under mutation. It is now an `ast` parse.

```text
M1 empty the per-control mutation loop          exit=1  ran 4 cases, expected 22
M2 empty the contract in both files             exit=1  shrank to 0 controls
M3 drift the two contract copies                exit=1  1 drift error
M4 relocate into tools/ci/ + drop invocation    exit=1  tools/ci/test-required-checks: no workflow invokes
clean tree                                      exit=0  CI policy valid across 6 workflows
                                                        Diagnostic wiring controls passed: 22/22 cases
```

**Control inventory, re-measured 2026-09-10** (the earlier "39 files / 36 wired" was
stale):

| ref | controls | named by a workflow | unnamed |
|---|---|---|---|
| `origin/main` `50c98de` | 42 | 39 | 3 |
| this branch | 42 | 42 | 0 |

The three unnamed on `main` are `tools/test-cache-search-readiness`,
`tools/test-design-capture-variant-census` and `tools/test-health-request-policy` —
the orphans this branch wires.

### Reported and deliberately not fixed here

- **"Wired" credits a workflow that never runs on a pull request.**
  `tools/test-design-capture-pair-detector` is named only by `capture-soak.yml`, which
  is `workflow_dispatch`-only, so the sweep counts it as wired while no pull request
  ever runs it. Real, and a separate change: deciding which controls belong on the
  pull-request path is a scope question, not a gate repair.
- **Three verifier mutations the suite does not pin** — dropping the step-level `if:`
  guard, dropping the job-level guard, and reverting the `read` scope. Current
  behaviour is correct (`if: always()` is rejected at repository level, confirmed);
  nothing pins it.
- **Deleting `run_orphan_control_case()`** drops extras 33 to 16 and still exits 0:
  core and apple have an identity ledger, the extras do not.

