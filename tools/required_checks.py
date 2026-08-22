#!/usr/bin/env python3
"""Strict parsing shared by the PR parity gate and trusted drift verifier."""

from __future__ import annotations

import json
from pathlib import Path


MANIFEST_KEYS = {"schema_version", "default_branch", "contexts"}


def fail(message: str) -> None:
    raise ValueError(message)


def load_required_checks(path: Path = Path(".github/required-checks.json")) -> tuple[str, set[str]]:
    try:
        document = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        fail(f"required-checks manifest is unreadable: {error}")
    if not isinstance(document, dict) or set(document) != MANIFEST_KEYS:
        fail(f"required-checks manifest must contain exactly {sorted(MANIFEST_KEYS)}")
    if document.get("schema_version") != 1:
        fail("required-checks manifest schema_version must be 1")
    default_branch = document.get("default_branch")
    if not isinstance(default_branch, str) or not default_branch:
        fail("required-checks manifest default_branch must be a non-empty string")
    contexts = document.get("contexts")
    if (
        not isinstance(contexts, list)
        or not contexts
        or any(not isinstance(context, str) or not context for context in contexts)
        or len(contexts) != len(set(contexts))
    ):
        fail("required-checks manifest contexts must be unique non-empty names")
    return default_branch, set(contexts)


def validate_live_required_checks(
    default_branch: str,
    document: dict[str, object],
    declared_contexts: set[str],
) -> set[str]:
    if document.get("strict") is not True:
        fail(f"GitHub branch protection for {default_branch}: strict mode must be enabled")
    contexts = document.get("contexts")
    checks = document.get("checks")
    if (
        not isinstance(contexts, list)
        or not contexts
        or any(not isinstance(context, str) or not context for context in contexts)
        or len(contexts) != len(set(contexts))
    ):
        fail(f"GitHub branch protection for {default_branch}: contexts must be unique names")
    if (
        not isinstance(checks, list)
        or not checks
        or any(
            not isinstance(check, dict)
            or not isinstance(check.get("context"), str)
            or not check["context"]
            or not isinstance(check.get("app_id"), int)
            for check in checks
        )
    ):
        fail(f"GitHub branch protection for {default_branch}: checks are malformed")
    check_contexts = [check["context"] for check in checks]
    if len(check_contexts) != len(set(check_contexts)):
        fail(f"GitHub branch protection for {default_branch}: check contexts are duplicated")
    live_contexts = set(contexts)
    if live_contexts != set(check_contexts):
        fail(f"GitHub branch protection for {default_branch}: contexts and checks disagree")
    if live_contexts != declared_contexts:
        fail(
            f"required-checks drift for {default_branch}: "
            f"manifest={sorted(declared_contexts)} live={sorted(live_contexts)}"
        )
    return live_contexts
