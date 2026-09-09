#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import json
import re
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
# The write may live in a script the step invokes rather than inline in the YAML. That is not a
# style choice: the "Assert Darwin conformance preconditions" step is at a hard GitHub workflow
# size ceiling -- MEASURED 2026-09-07, 29,893 characters of inline `run:` loads and 34,557 makes
# the whole file invalid, so NO job is created and apple-ci never appears as a check at all.
# Extraction is GitHub's own remedy for that, so this check follows the invocation instead of
# being blinded by it. Following it is also strictly stronger than scanning the YAML alone: a
# script that quietly stops writing a directory now fails here too.
invoked = sorted({Path(match) for match in re.findall(r"tools/ci/[\w.-]+", apple_ci)})
for script in invoked:
    if not script.is_file():
        errors.append(
            f".github/workflows/apple-ci.yml: invokes {script}, which does not exist",
        )
searched = apple_ci + "".join(
    script.read_text() for script in invoked if script.is_file()
)
written = set(re.findall(r"\$RUNNER_TEMP/([\w-]+-junit)/", searched))
read = set(re.findall(r'"\$RUNNER_TEMP/([\w-]+-junit)"', apple_ci))
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
if apple_ci:
    emitted_classes: set[str] = set()
    apple_lines = apple_ci.splitlines()
    for index, line in enumerate(apple_lines):
        if "tools/swift-testing-junit" not in line:
            continue
        # bundle path, output path, then the class name -- each on its own continued line
        for offset in range(1, 6):
            if index + offset >= len(apple_lines):
                break
            candidate = apple_lines[index + offset].strip()
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
        pending_method: str | None = None
        for index, line in enumerate(apple_lines):
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
                if index + offset >= len(apple_lines):
                    break
                candidate = apple_lines[index + offset].strip()
                if candidate.endswith("\\"):
                    continue
                if candidate not in allowed:
                    errors.append(
                        f".github/workflows/apple-ci.yml: the JUnit for {method} is emitted as "
                        f"{candidate!r}, but FEATURES.yml cites it as "
                        f"{sorted(allowed)}; verify-parity-evidence matches on that name",
                    )
                break

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
