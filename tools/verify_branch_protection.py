#!/usr/bin/env python3
"""Trusted push-to-main verification of required-checks manifest drift."""

from __future__ import annotations

import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from required_checks import load_required_checks, validate_live_required_checks


GITHUB_API_ROOT = "https://api.github.com"
GITHUB_API_VERSION = "2022-11-28"


def fail(message: str) -> None:
    raise ValueError(message)


def api_document(url: str, token: str) -> dict[str, object]:
    request = Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": GITHUB_API_VERSION,
        },
    )
    try:
        with urlopen(request, timeout=15) as response:
            text = response.read().decode("utf-8")
    except HTTPError as error:
        fail(f"GitHub branch-protection API returned HTTP {error.code}")
    except (URLError, TimeoutError) as error:
        fail(f"GitHub branch-protection API was unavailable: {type(error).__name__}")
    try:
        document = json.loads(text)
    except json.JSONDecodeError as error:
        fail(f"GitHub branch-protection API returned malformed JSON: {error}")
    if not isinstance(document, dict):
        fail("GitHub branch-protection API response must be an object")
    return document


try:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("BRANCH_PROTECTION_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        fail("GITHUB_REPOSITORY is absent or malformed")
    if not token:
        fail("BRANCH_PROTECTION_TOKEN is required")

    declared_branch, declared_contexts = load_required_checks()
    repository_document = api_document(f"{GITHUB_API_ROOT}/repos/{repository}", token)
    live_default_branch = repository_document.get("default_branch")
    if not isinstance(live_default_branch, str) or not live_default_branch:
        fail("GitHub repository API omitted default_branch")
    if live_default_branch != declared_branch:
        fail(
            f"required-checks default-branch drift: "
            f"manifest={declared_branch} live={live_default_branch}"
        )
    protection = api_document(
        f"{GITHUB_API_ROOT}/repos/{repository}/branches/"
        f"{quote(live_default_branch, safe='')}/protection/required_status_checks",
        token,
    )
    contexts = validate_live_required_checks(live_default_branch, protection, declared_contexts)
    print(
        f"branch protection matches required-checks manifest: "
        f"branch={live_default_branch} contexts={sorted(contexts)}"
    )
except (OSError, ValueError) as error:
    print(error, file=sys.stderr)
    raise SystemExit(1)
