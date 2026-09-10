#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import json
import re
import shlex
import sys


MAPPING_ENTRY = re.compile(
    r"^(?P<indent>\s*)(?P<key>[A-Za-z0-9_-]+|'[^']+'|\"[^\"]+\")\s*:(?P<value>.*)$",
)


def code_before_comment(line: str) -> str:
    """Remove a YAML comment without treating a quoted # as a comment."""
    quote: str | None = None
    escaped = False
    for index, character in enumerate(line):
        if quote == '"' and character == "\\" and not escaped:
            escaped = True
            continue
        if character in ("'", '"') and not escaped:
            if quote is None:
                quote = character
            elif quote == character:
                quote = None
        if character == "#" and quote is None and (index == 0 or line[index - 1].isspace()):
            return line[:index]
        escaped = False
    return line


def mapping_entry(line: str) -> tuple[int, str, str] | None:
    match = MAPPING_ENTRY.match(code_before_comment(line).rstrip())
    if not match:
        return None
    key = match.group("key")
    if key[:1] == key[-1:] and key.startswith(("'", '"')):
        key = key[1:-1]
    return len(match.group("indent")), key, match.group("value").strip()


def block_end(lines: list[str], start: int, parent_indent: int) -> int:
    for index in range(start, len(lines)):
        code = code_before_comment(lines[index]).rstrip()
        if code and len(code) - len(code.lstrip()) <= parent_indent:
            return index
    return len(lines)


def workflow_triggers(lines: list[str]) -> set[str]:
    for index, line in enumerate(lines):
        entry = mapping_entry(line)
        if not entry or entry[0] != 0 or entry[1] != "on":
            continue
        _, _, value = entry
        if value:
            return set(re.findall(r"[A-Za-z_][A-Za-z0-9_-]*", value))

        end = block_end(lines, index + 1, 0)
        entries = [
            entry
            for nested_line in lines[index + 1:end]
            if (entry := mapping_entry(nested_line)) is not None
        ]
        if not entries:
            return set()
        trigger_indent = min(entry[0] for entry in entries)
        return {entry[1] for entry in entries if entry[0] == trigger_indent}
    return set()


def job_runner_values(lines: list[str]) -> list[tuple[str, str]]:
    """Return each direct job's name and complete runs-on YAML value."""
    for jobs_index, line in enumerate(lines):
        jobs_entry = mapping_entry(line)
        if jobs_entry and jobs_entry[0] == 0 and jobs_entry[1] == "jobs":
            break
    else:
        return []

    jobs_end = block_end(lines, jobs_index + 1, 0)
    entries = [
        (index, entry)
        for index in range(jobs_index + 1, jobs_end)
        if (entry := mapping_entry(lines[index])) is not None
    ]
    if not entries:
        return []
    job_indent = min(entry[0] for _, entry in entries)
    jobs = [(index, entry[1]) for index, entry in entries if entry[0] == job_indent]

    runner_values: list[tuple[str, str]] = []
    for job_position, (job_index, job_name) in enumerate(jobs):
        job_end = jobs[job_position + 1][0] if job_position + 1 < len(jobs) else jobs_end
        job_entries = [
            (index, entry)
            for index in range(job_index + 1, job_end)
            if (entry := mapping_entry(lines[index])) is not None
        ]
        if not job_entries:
            continue
        property_indent = min(entry[0] for _, entry in job_entries)
        for runs_on_index, (indent, key, value) in job_entries:
            if indent != property_indent or key != "runs-on":
                continue
            if value:
                runner_values.append((job_name, value))
            else:
                end = block_end(lines, runs_on_index + 1, indent)
                block_value = "\n".join(
                    code_before_comment(nested_line)
                    for nested_line in lines[runs_on_index + 1:end]
                )
                runner_values.append((job_name, block_value))
            break
    return runner_values


def contains_runner_label(value: str, label: str) -> bool:
    return re.search(
        rf"(?<![A-Za-z0-9_-]){re.escape(label)}(?![A-Za-z0-9_-])",
        value,
    ) is not None


def workflow_run_steps(text: str) -> list[dict[str, str]]:
    """Parse block-style jobs/steps mappings and their run scalars without dependencies.

    Only direct step properties count; env, names, comments and action inputs cannot
    supply a command. Flow mappings, aliases and folded scalars are not certified.
    A control using an unsupported representation must have a supported invocation.
    """
    lines = text.splitlines()
    steps: list[dict[str, str]] = []

    def default_shell(start: int, stop: int, indent: int) -> str | None:
        for i in range(start, stop):
            if mapping_entry(lines[i]) == (indent, "defaults", ""):
                end = min(block_end(lines, i + 1, indent), stop)
                for j in range(i + 1, end):
                    if mapping_entry(lines[j]) == (indent + 2, "run", ""):
                        run_end = min(block_end(lines, j + 1, indent + 2), end)
                        for raw in lines[j + 1:run_end]:
                            entry = mapping_entry(raw)
                            if entry and entry[:2] == (indent + 4, "shell"):
                                return entry[2].strip("\"'")
        return None

    inherited_shell = default_shell(0, len(lines), 0) or "bash"
    # Restrict discovery to jobs.<job>.steps, never a lookalike in env or run text.
    jobs_start = next((i for i, line in enumerate(lines)
                       if mapping_entry(line) == (0, "jobs", "")), None)
    if jobs_start is None:
        return steps
    jobs_end = block_end(lines, jobs_start + 1, 0)
    job_starts = [i for i in range(jobs_start + 1, jobs_end)
                  if (entry := mapping_entry(lines[i])) and entry[0] == 2]
    step_blocks: list[tuple[int, dict[str, str]]] = []
    for position, start in enumerate(job_starts):
        stop = job_starts[position + 1] if position + 1 < len(job_starts) else jobs_end
        metadata = {"inherited-shell": default_shell(start + 1, stop, 4) or inherited_shell}
        step_index = None
        for i in range(start + 1, stop):
            entry = mapping_entry(lines[i])
            if entry and entry[0] == 4:
                metadata[entry[1]] = entry[2]
                if entry[1:] == ("steps", ""):
                    step_index = i
        if step_index is not None:
            step_blocks.append((step_index, metadata))
    for index, job in step_blocks:
        indent = 4
        end = block_end(lines, index + 1, indent)
        starts = [i for i in range(index + 1, end)
                  if re.match(rf"^ {{{indent + 2}}}- ", lines[i])]
        for position, start in enumerate(starts):
            stop = starts[position + 1] if position + 1 < len(starts) else end
            properties: dict[str, str] = {}
            i = start
            while i < stop:
                raw = lines[i]
                if i == start:
                    raw = raw[:indent + 2] + "  " + raw[indent + 4:]
                prop = mapping_entry(raw)
                if prop and prop[0] == indent + 4:
                    _, key, value = prop
                    if value in {"|", "|-", "|+"}:
                        last = min(block_end(lines, i + 1, indent + 4), stop)
                        value = "\n".join(lines[i + 1:last])
                        i = last - 1
                    elif value.startswith('"'):
                        try:
                            value = json.loads(value)
                        except ValueError:
                            value = ""
                    elif value.startswith("'") and value.endswith("'"):
                        value = value[1:-1].replace("''", "'")
                    if key in properties:
                        # Duplicate properties are ambiguous, never a proof of wiring.
                        properties["continue-on-error"] = "ambiguous"
                    properties[key] = value
                i += 1
            properties.setdefault("shell", job["inherited-shell"])
            if job.get("continue-on-error", "false") != "false" or "if" in job:
                properties["continue-on-error"] = "job is not unconditionally blocking"
            steps.append(properties)
    return steps


def blocking_controls(step: dict[str, str]) -> set[str]:
    """Recognize direct shell commands, not arbitrary shell programs as proofs.

    Commands in compound statements, pipelines, command lists or substitutions do
    not certify wiring. Runtime behavior inside the invoked tool remains its tests'
    responsibility. GitHub's default bash/sh run shell enables errexit.
    """
    if step.get("continue-on-error", "false") != "false" or "if" in step:
        return set()
    if step.get("shell", "bash") not in {"bash", "sh"}:
        return set()
    found: set[str] = set()
    depth = 0
    errexit = True
    for line in step.get("run", "").replace("\\\n", " ").splitlines():
        code = code_before_comment(line).strip()
        if not code:
            continue
        # Closing a block before considering the following direct command also
        # handles the multi-line if-false shape (not just a one-line spelling).
        if re.match(r"^(fi|done|esac)\b|^}", code):
            depth = max(0, depth - 1)
            continue
        if re.match(r"^(if|for|while|until|case|select)\b|^[\w]+\s*\(\)\s*{|^\{$", code):
            if not re.search(r";\s*(fi|done|esac)\s*$", code):
                depth += 1
            continue
        # A here-document contains data that can look exactly like commands.
        # Do not certify commands later in a block whose heredoc grammar we do
        # not interpret. Here-strings (<<<) are ordinary single-command input.
        if re.search(r"(?<!<)<<(?!<)", code):
            break
        if depth:
            continue
        if re.match(r"^set\s+\+\w*e", code):
            errexit = False
        if re.match(r"^set\s+-\w*e", code):
            errexit = True
        if re.match(r"^(exit|return|exec)\b", code):
            break
        # No shell operators, expansion-as-code or backgrounding in certified
        # commands. Quoted ordinary arguments and inline comments are supported.
        if not errexit or any(token in code for token in (";", "|", "&", "<", ">", "`", "$(")):
            continue
        try:
            words = shlex.split(code, comments=True)
        except ValueError:
            continue
        if not words:
            continue
        command = words[1] if words[0] == "python3" and len(words) > 1 else words[0]
        if re.fullmatch(r"(?:\./)?tools/test-[\w.-]+", command):
            found.add(command.removeprefix("./"))
    return found


def evidence_commands(source: str) -> list[list[str]]:
    """Expand local shell functions with positional/scalar arguments for inventory.

    This is deliberately bounded, not a shell interpreter: workflow environment,
    command substitutions and computed selectors remain unresolved. Definitions do
    not supply evidence until a call is present in this source. No bindings cross
    a source boundary. Runtime branch selection remains verify-parity-evidence's job.
    """
    lines = [code_before_comment(line).strip() for line in source.splitlines()]
    lines = "\n".join(lines).replace("\\\n", " ").splitlines()
    functions: dict[str, list[str]] = {}
    top: list[str] = []
    index = 0
    while index < len(lines):
        match = re.fullmatch(r"([A-Za-z_]\w*)\(\)\s*\{", lines[index])
        if match:
            body: list[str] = []
            index += 1
            while index < len(lines) and lines[index] != "}":
                body.append(lines[index])
                index += 1
            functions[match[1]] = body
        else:
            top.append(lines[index])
        index += 1

    def expand(body: list[str], values: dict[str, str], stack: tuple[str, ...]) -> list[list[str]]:
        commands: list[list[str]] = []
        for line in body:
            try:
                words = shlex.split(line, comments=True)
            except ValueError:
                continue
            words = [re.sub(r"\$(?:\{(\w+)\}|(\w+))",
                            lambda m: values.get(m[1] or m[2], m[0]), word)
                     for word in words]
            if not words:
                continue
            if len(words) == 1 and re.match(r"^[A-Za-z_]\w*=", words[0]):
                key, value = words[0].split("=", 1)
                values[key] = value
                continue
            if words[0] in functions:
                name = words[0]
                if name not in stack:
                    arguments = {str(i): value for i, value in enumerate(words[1:], 1)}
                    commands.extend(expand(functions[name], {**values, **arguments}, (*stack, name)))
                continue
            commands.append(words)
        return commands

    return expand(top, {}, ())


def method_emission_associations(source: str, cited: dict[str, set[str]]) -> set[tuple[str, str]]:
    """Account for cited identities via method, whole-target or package test runs."""
    resolved: set[tuple[str, str]] = set()
    selected: set[tuple[str, str]] = set()
    package = None
    for words in evidence_commands(source):
        if words[0] == "cd" and len(words) == 2:
            package = Path(words[1]) / "Package.swift"
        # Environment assignments may precede the executable.
        command = next((i for i, word in enumerate(words) if not re.match(r"^\w+=", word)), 0)
        if words[command] == "xcodebuild":
            selected = set()
            selectors = [word.split(":", 1)[1] for word in words if word.startswith("-only-testing:")]
            for selector in selectors:
                parts = selector.split("/")
                if any(not re.fullmatch(r"[A-Za-z_]\w*", part) for part in parts):
                    continue
                if len(parts) == 3:
                    selected.update((name, parts[2]) for name in cited.get(parts[2], set()))
                elif len(parts) == 1:
                    selected.update((parts[0], method) for method, names in cited.items()
                                    if parts[0] in names)
            # An unfiltered package scheme runs its declared test targets. Read the
            # actual manifest; merely emitting a target's name is never sufficient.
            if not selectors and package and package.is_file() and "-scheme" in words:
                scheme = words[words.index("-scheme") + 1]
                if scheme == package.parent.name + "-Package":
                    targets = re.findall(r'\.testTarget\(\s*name:\s*"(\w+)"', package.read_text())
                    selected.update((target, method) for target in targets
                                    for method, names in cited.items() if target in names)
            # Filtering an otherwise whole-suite invocation needs explicit support.
            if any(word.startswith("-skip-testing:") for word in words):
                selected = set()
        if words[:2] == ["python3", "tools/swift-testing-junit"]:
            if len(words) == 5:
                resolved.update(pair for pair in selected if pair[0] == words[4])
            selected = set()
    return resolved


errors: list[str] = []
workflows = sorted(Path(".github/workflows").glob("*.yml"))
if not workflows:
    errors.append("no workflows found")

for workflow in workflows:
    text = workflow.read_text()
    if "cancel-in-progress: true" not in text:
        errors.append(f"{workflow}: missing cancel-in-progress")
    if "timeout-minutes:" not in text:
        errors.append(f"{workflow}: missing per-job timeout")
    for forbidden in ("-large", "-xlarge"):
        if forbidden in text:
            errors.append(f"{workflow}: forbidden runner token {forbidden}")
    lines = text.splitlines()
    dispatch_only = workflow_triggers(lines) == {"workflow_dispatch"}
    for job, runner_value in job_runner_values(lines):
        if contains_runner_label(runner_value, "self-hosted") and not dispatch_only:
            errors.append(
                f"{workflow}: job {job} uses a self-hosted runner in a workflow "
                "that is not workflow_dispatch-only",
            )
    for action, ref in re.findall(r"uses:\s+([^@\s]+)@([^\s#]+)", text):
        if not re.fullmatch(r"[0-9a-f]{40}", ref):
            errors.append(f"{workflow}: {action} is not pinned to an immutable commit")

# Every JUnit directory an Apple step writes must reach verify-parity-evidence, and every
# directory it reads must be written by a step. PR #65 failed apple-ci with "evidence test did not
# execute" for a test the same log showed passing: the macOS app-host target was the one Apple
# target with no swift-testing-junit call, so citing ANY test in it was unprovable by construction.
# Nothing failed until a FEATURES.yml row cited one, which is a 65-minute round trip away from the
# edit that caused it.
# Guarded on membership in the discovered set rather than on the path existing: this script is
# also run by tools/test-verify-ci-policy against synthetic single-workflow fixtures, where
# apple-ci.yml is legitimately absent. In the repository it is always in `workflows`.
apple_ci_path = Path(".github/workflows/apple-ci.yml")
apple_ci = apple_ci_path.read_text() if apple_ci_path in workflows else ""
# The write may live in a script the step invokes rather than inline in the YAML, and that
# extraction is not a style choice. A `run:` block containing a `${{ }}` expression is a TEMPLATE,
# and a template is capped far below a plain block -- MEASURED 2026-09-07: 29,212 characters WITH
# an expression is rejected, while 29,893 WITHOUT one loads. A rejected file creates no job at all,
# so apple-ci never appears as a check rather than failing visibly.
# (An earlier version of this comment blamed a plain size ceiling. That was wrong and was retracted
# once the counter-example was measured. It is corrected here rather than left to send the next
# reader looking for a limit that does not exist.)
#
# Extraction is GitHub's own remedy, so every check below reads the invoked scripts as well as the
# YAML. Be precise about what that buys: this is a TEXTUAL inventory of what the workflow and its
# scripts SAY, not proof that any line executed. A commented-out invocation is excluded, but a
# branch that never runs at runtime is still counted. Proving execution stays
# verify-parity-evidence's job.
def ci_script_invocations(text: str) -> set[Path]:
    """Every `tools/ci/` path named by a non-comment line of `text`.

    A `#` comment cannot invoke anything. Counting one made a retired script named only in a
    comment fail the run with "invokes ..., which does not exist".
    """
    return {
        Path(match)
        for line in text.splitlines()
        if not line.lstrip().startswith("#")
        for match in re.findall(r"tools/ci/[\w.-]+", line)
    }


# Discovery is TRANSITIVE: a script the workflow invokes may itself invoke another, and a
# one-level scan would leave the inner script's JUnit writes in neither `written` nor `read`.
# That was survivable while this was the only consumer of the set; it is not once a second rule
# (the step-ordering check) is built on top of it, because the second rule inherits the blind spot
# rather than introducing it, and nothing would point at the cause.
# The `seen` guard makes a cycle terminate rather than spin.
invoked_set: set[Path] = set()
frontier = ci_script_invocations(apple_ci)
while frontier:
    script = frontier.pop()
    if script in invoked_set:
        continue
    invoked_set.add(script)
    if script.is_file():
        frontier |= ci_script_invocations(script.read_text()) - invoked_set
invoked = sorted(invoked_set)
for script in invoked:
    if not script.is_file():
        errors.append(
            f".github/workflows/apple-ci.yml: invokes {script}, which does not exist",
        )
# Each source is kept SEPARATE rather than concatenated. The method-to-emission pairing further down
# is order-sensitive within a file, so joining the sources would let a trailing -only-testing in one
# file pair with the first emission in the next and report a mismatch that exists only at the seam.
sources: list[tuple[str, str]] = [(str(apple_ci_path), apple_ci)]
sources += [(str(script), script.read_text()) for script in invoked if script.is_file()]
searched = "".join(text for _, text in sources)
written = set(re.findall(r"\$RUNNER_TEMP/([\w-]+-junit)/", searched))
# Reads are searched in the SAME scope as writes. Scanning only the YAML here reported a directory
# that a script both wrote and passed on as "written but never passed" -- an asymmetry that turns a
# correct extraction into a false failure.
read = set(re.findall(r'"\$RUNNER_TEMP/([\w-]+-junit)"', searched))
for orphan in sorted(written - read):
    errors.append(
        f".github/workflows/apple-ci.yml: JUnit directory {orphan} is written but never passed "
        "to verify-parity-evidence, so evidence citing tests in it cannot be proven to have run",
    )
for missing in sorted(read - written):
    errors.append(
        f".github/workflows/apple-ci.yml: verify-parity-evidence reads {missing}, which no step "
        "writes",
    )

# The directory wiring above is necessary and was not sufficient. verify-parity-evidence matches on
# (class, method), and the macOS emissions passed the TARGET name DulcetMacTests where the evidence
# rows cite the CLASS name DulcetMacAccountConnectAppTest. Every file was written, every directory
# was read, and the run still failed with "evidence test did not execute" plus
# nearby=['DulcetMacTests/librarySync...'] -- the method matched and the class did not.
#
# So: every non-conformance class that a FEATURES.yml row cites for apple-ci must be emitted under
# exactly that name by some swift-testing-junit call. Conformance classes are excluded because their
# JUnit comes from the Gradle core-conformance result directories, not from this workflow.
#
# 🚨 Both scans below read `sources`, not the YAML alone. They previously read only the YAML, so
# moving an emission into tools/ci/ silently removed it from their input -- MEASURED 2026-09-08:
# emitting the macOS library-sync JUnit under the target name instead of the cited class was
# rejected before the extraction and ACCEPTED after it. Extraction must never be able to retire a
# check by relocating the thing it checks.
if apple_ci:
    emitted_classes: set[str] = set()
    for _, source in sources:
        source_lines = source.splitlines()
        for index, line in enumerate(source_lines):
            if "tools/swift-testing-junit" not in line:
                continue
            # bundle path, output path, then the class name -- each on its own continued line
            for offset in range(1, 6):
                if index + offset >= len(source_lines):
                    break
                candidate = source_lines[index + offset].strip()
                if candidate.endswith("\\"):
                    continue
                if re.fullmatch(r"[A-Za-z_][\w.]*", candidate):
                    emitted_classes.add(candidate)
                break
    try:
        feature_document = json.loads(Path("FEATURES.yml").read_text())
    except (OSError, ValueError):
        feature_document = None
    if feature_document is not None:
        for feature in feature_document.get("features", []):
            for platform, cell in (feature.get("platforms") or {}).items():
                for row in cell.get("evidence") or []:
                    if row.get("workflow") != "apple-ci":
                        continue
                    cited = row.get("test", "").split("/")[0]
                    if not cited or "ConformanceTest" in cited:
                        continue
                    if cited not in emitted_classes:
                        errors.append(
                            f".github/workflows/apple-ci.yml: {feature['id']}/{platform} cites "
                            f"{cited}, which no swift-testing-junit call emits under that name; "
                            "verify-parity-evidence matches on the class, not the target",
                        )

        # Emitting the cited class SOMEWHERE is necessary and not sufficient. Measured: reverting
        # one of three macOS emissions to the target name left this file passing, because the other
        # two still emitted the cited name -- while the bundle actually holding the cited test wrote
        # unusable evidence. So pair each emission with the -only-testing it follows and require the
        # emitted name to be one the rows use for THAT method.
        #
        # Deliberately not "emitted name == Swift class": this repository legitimately cites some
        # tests by TARGET (DulcetKeychainIOSTests) where the Swift class is different
        # (DulcetKeychainAttributeTests). What must agree is the emission and the citation.
        cited_classes_by_method: dict[str, set[str]] = {}
        for feature in feature_document.get("features", []):
            for cell in (feature.get("platforms") or {}).values():
                for row in cell.get("evidence") or []:
                    if row.get("workflow") != "apple-ci":
                        continue
                    parts = row.get("test", "").split("/")
                    if len(parts) == 2 and "ConformanceTest" not in parts[0]:
                        cited_classes_by_method.setdefault(parts[1], set()).add(parts[0])
        resolved_associations = set().union(*(
            method_emission_associations(source, cited_classes_by_method)
            for _, source in sources
        ))
        for label, source in sources:
            source_lines = source.splitlines()
            # Reset per source: a method left pending at the end of one file must not pair with an
            # emission at the top of the next.
            pending_method: str | None = None
            for index, line in enumerate(source_lines):
                stripped = line.strip()
                only = re.search(r"-only-testing:[\w.]+/[A-Za-z_][\w.]*/([A-Za-z_]\w*)", stripped)
                if only:
                    pending_method = only.group(1)
                    continue
                if "tools/swift-testing-junit" not in stripped:
                    continue
                method, pending_method = pending_method, None
                allowed = cited_classes_by_method.get(method or "")
                if not allowed:
                    continue
                for offset in range(1, 6):
                    if index + offset >= len(source_lines):
                        break
                    candidate = source_lines[index + offset].strip()
                    if candidate.endswith("\\"):
                        continue
                    if candidate not in allowed:
                        errors.append(
                            f"{label}: the JUnit for {method} is emitted as "
                            f"{candidate!r}, but FEATURES.yml cites it as "
                            f"{sorted(allowed)}; verify-parity-evidence matches on that name",
                        )
                    break

        for method, classes in sorted(cited_classes_by_method.items()):
            for cited in sorted(classes):
                if (cited, method) not in resolved_associations:
                    errors.append(
                        f".github/workflows/apple-ci.yml: required method/emission association "
                        f"{cited}/{method} cannot be resolved in any source; require a recognizable "
                        "selector followed by its swift-testing-junit emission (no silent skip)",
                    )


# JUnit-directory coverage does not establish that standalone diagnostic controls execute.
# Keep these in unconditional steps of the required Apple job: macOS must run the real stack
# control. This is an explicit contract for these suites, not discovery of every tools/test-* file.
# Synthetic policy fixtures opt in by creating core-conformance, as the real repository does.
DIAGNOSTIC_CONTROLS = (
    "tools/test-conformance-access-log",
    "tools/test-capture-conformance-stall",
    "tools/test-measure-conformance-phase-gaps",
)
if Path("core-conformance").is_dir():
    job = re.search(r"(?m)^  apple-ci:\s*$", apple_ci)
    job_text = ""
    if job:
        lines = apple_ci[job.end():].splitlines()
        job_text = "\n".join(lines[:block_end(lines, 0, 2)])
    job_unconditional = not re.search(r"(?m)^    (?:if|continue-on-error):", job_text)
    job_mac = any(name == "apple-ci" and re.search(r"macos-", runner)
                  for name, runner in job_runner_values(apple_ci.splitlines()))
    steps = re.split(r"(?m)^      - ", job_text)[1:]
    for control in DIAGNOSTIC_CONTROLS:
        invoked = any(
            not re.search(r"(?m)^        (?:if|continue-on-error):", step)
            and re.search(r"(?m)^        run: python3 " + re.escape(control) + r"\s*$", step)
            for step in steps
        )
        if (not Path(control).is_file() or not invoked or not job_unconditional or not job_mac
                or "pull_request" not in workflow_triggers(apple_ci.splitlines())):
            errors.append(f".github/workflows/apple-ci.yml: required diagnostic control {control} "
                          "must exist and run unconditionally in the macOS apple-ci PR job")

# A control that no workflow names never runs. There is no glob runner here -- every control is
# wired by an explicit `run: python3 tools/test-<name>` line -- so an unwired control is INERT while
# looking exactly like a gate: executable, carrying its own positive and negative controls, printing
# PASS when a person runs it by hand. MEASURED 2026-09-08: three of thirty-nine controls were
# orphaned this way, and one of the three was also asserting the wrong thing. Repairing that one's
# assertion alone would have read as a complete fix and changed nothing, because two independent
# reasons kept the defect alive and each was sufficient on its own.
#
# ➡️ "Is it wired?" is the FIRST question about a control, before "what does it assert?" -- the
# second is moot without the first.
wired_controls = set().union(*(
    blocking_controls(step)
    for workflow in workflows
    for step in workflow_run_steps(workflow.read_text())
))
# rglob, not glob: a non-recursive sweep lets relocation retire a check silently. Moving a wired
# control into tools/ci/ and dropping its invocation removed it from CI entirely while this gate
# still printed "CI policy valid" -- and tools/ci/ is exactly where this repository has already
# relocated a check for real, which is what the extraction defect above was. A sweep that only
# looks where controls used to live cannot answer the question it was added to answer.
# MEASURED: recursive and non-recursive both find 42 files today, so this closes the hole at no
# cost in false positives.
for control in sorted(Path("tools").rglob("test-*")):
    if control.is_file() and str(control) not in wired_controls:
        errors.append(
            f"{control}: no workflow invokes this control as a blocking command; "
            "require a direct run command with failure propagation and no conditional wrapper",
        )

core_ci = Path(".github/workflows/core-ci.yml").read_text()
for required in (
    "python3 tools/migration_gate.py",
    ":core:verifySqlDelightMigration",
):
    if required not in core_ci:
        errors.append(f".github/workflows/core-ci.yml: missing database gate {required}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print(f"CI policy valid across {len(workflows)} workflows")
