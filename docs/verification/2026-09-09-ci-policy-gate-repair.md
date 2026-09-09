# CI policy gate repair: mutation evidence

The three reported defects are repaired on `fix/restore-extracted-gate`, based on
`origin/main` at `2fbc37b`. This report compares the pre-repair verifier at `a06ef7f`
with the implementation at `f578871`. No workflow or extracted production script
was edited. The restored class checks and their source separation remain intact.

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
