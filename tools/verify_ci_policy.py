#!/usr/bin/env python3
from pathlib import Path
import re
import sys

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
    for forbidden in ("self-hosted", "-large", "-xlarge"):
        if forbidden in text:
            errors.append(f"{workflow}: forbidden runner token {forbidden}")
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
