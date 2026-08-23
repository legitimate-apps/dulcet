#!/usr/bin/env python3
"""Resolve machine-readable partial-feature promotion conditions against the repository."""

from __future__ import annotations

import json
from pathlib import Path
import re
import sys
from typing import Any


WORKFLOW_SUFFIXES = {".yml", ".yaml"}
TEST_SOURCE_SUFFIXES = {".swift", ".kt", ".kts"}
TEST_ROOTS = ("apple", "core", "core-conformance")


def unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def workflow_inventory(root: Path) -> dict[str, dict[str, Any]]:
    inventory: dict[str, dict[str, Any]] = {}
    workflow_root = root / ".github/workflows"
    for path in sorted(workflow_root.iterdir() if workflow_root.is_dir() else []):
        if path.suffix not in WORKFLOW_SUFFIXES or not path.is_file():
            continue
        lines = path.read_text().splitlines()
        display_name = None
        for line in lines:
            match = re.match(r"^name:\s*(.+?)\s*$", line)
            if match:
                display_name = unquote(match.group(1))
                break
        jobs: set[str] = set()
        in_jobs = False
        for index, line in enumerate(lines):
            if re.match(r"^jobs:\s*$", line):
                in_jobs = True
                continue
            if not in_jobs:
                continue
            if line and not line[0].isspace():
                break
            job_match = re.match(r"^  ([A-Za-z0-9_-]+):\s*(?:#.*)?$", line)
            if not job_match:
                continue
            job_key = job_match.group(1)
            jobs.add(job_key)
            for nested in lines[index + 1 :]:
                if re.match(r"^  [A-Za-z0-9_-]+:\s*(?:#.*)?$", nested):
                    break
                if nested and not nested[0].isspace():
                    break
                name_match = re.match(r"^    name:\s*(.+?)\s*$", nested)
                if name_match:
                    jobs.add(unquote(name_match.group(1)))
                    break
        record = {
            "path": path,
            "identifiers": {path.stem, path.name, *(set() if display_name is None else {display_name})},
            "jobs": jobs,
        }
        for identifier in record["identifiers"]:
            inventory[identifier] = record
    return inventory


def test_sources(root: Path) -> list[Path]:
    result: list[Path] = []
    for relative in TEST_ROOTS:
        source_root = root / relative
        if not source_root.is_dir():
            continue
        result.extend(
            path
            for path in source_root.rglob("*")
            if path.is_file() and path.suffix in TEST_SOURCE_SUFFIXES
        )
    return sorted(result)


def test_resolves(root: Path, name: str) -> bool:
    if "/" not in name:
        return False
    suite, test = name.split("/", 1)
    if not suite or not test or "/" in test:
        return False
    suite_pattern = re.compile(
        rf"\b(?:class|struct|enum|object|extension)\s+{re.escape(suite)}\b"
    )
    test_pattern = re.compile(rf"\b(?:func|fun|void)\s+{re.escape(test)}\s*\(")
    for path in test_sources(root):
        source = path.read_text(errors="replace")
        if name in source or (suite_pattern.search(source) and test_pattern.search(source)):
            return True
    return False


def target_error(
    root: Path,
    target: Any,
    workflows: dict[str, dict[str, Any]],
) -> str | None:
    if not isinstance(target, dict):
        return "promotion target must be an object"
    kind = target.get("kind")
    name = target.get("name")
    if not isinstance(name, str) or not name:
        return "promotion target name must be a non-empty string"
    if kind == "workflow":
        if set(target) != {"kind", "name"}:
            return f"workflow target {name!r} must contain only kind and name"
        if name not in workflows:
            return f"workflow {name!r} does not resolve under .github/workflows"
        return None
    if kind == "job":
        if set(target) != {"kind", "workflow", "name"}:
            return f"job target {name!r} must contain kind, workflow, and name"
        workflow = target.get("workflow")
        if not isinstance(workflow, str) or workflow not in workflows:
            return f"job {name!r} names unresolved workflow {workflow!r}"
        if name not in workflows[workflow]["jobs"]:
            return f"job {name!r} does not resolve in workflow {workflow!r}"
        return None
    if kind == "test":
        if set(target) != {"kind", "name"}:
            return f"test target {name!r} must contain only kind and name"
        if not test_resolves(root, name):
            return f"test {name!r} does not resolve in repository test sources"
        return None
    return f"promotion target kind must be workflow, job, or test; got {kind!r}"


def validate(root: Path, features_path: Path | None = None) -> list[str]:
    root = root.resolve()
    features_path = features_path or root / "FEATURES.yml"
    try:
        features = json.loads(features_path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        return [f"cannot read {features_path}: {error}"]
    workflows = workflow_inventory(root)
    errors: list[str] = []
    for feature in features.get("features", []):
        feature_id = feature.get("id", "<missing-feature-id>")
        for platform, declaration in feature.get("platforms", {}).items():
            if not isinstance(declaration, dict):
                continue
            label = f"{feature_id}/{platform}"
            condition = declaration.get("promotion_condition")
            if declaration.get("status") != "partial":
                if condition is not None:
                    errors.append(
                        f"{label} may declare promotion_condition only while status is partial"
                    )
                continue
            if not isinstance(condition, dict):
                errors.append(
                    f"{label} is partial but has no machine-readable promotion_condition"
                )
                continue
            status = condition.get("status")
            if status == "blocked":
                expected = {"status", "blocked_on", "reason"}
                if set(condition) != expected:
                    errors.append(
                        f"{label} blocked promotion_condition must contain exactly "
                        "status, blocked_on, and reason; named targets are not permitted"
                    )
                if not isinstance(condition.get("blocked_on"), str) or not condition["blocked_on"].strip():
                    errors.append(f"{label} blocked promotion_condition needs blocked_on")
                if not isinstance(condition.get("reason"), str) or len(condition["reason"].strip()) < 20:
                    errors.append(f"{label} blocked promotion_condition needs a specific reason")
                continue
            if status == "named":
                if set(condition) != {"status", "targets"}:
                    errors.append(
                        f"{label} named promotion_condition must contain exactly status and targets"
                    )
                targets = condition.get("targets")
                if not isinstance(targets, list) or not targets:
                    errors.append(f"{label} named promotion_condition needs at least one target")
                    continue
                for target in targets:
                    error = target_error(root, target, workflows)
                    if error:
                        errors.append(f"{label} promotion target {error}")
                continue
            errors.append(
                f"{label} promotion_condition status must be named or blocked; got {status!r}"
            )
    return errors


def main() -> int:
    errors = validate(Path.cwd())
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("partial-feature promotion conditions are resolvable or explicitly blocked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
