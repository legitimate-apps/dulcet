#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
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
