#!/usr/bin/env python3
"""Raise a durable GitHub issue when a protected-branch health signal fails on `main`.

Failures and timeouts open the alarm or append to it. Success is deliberately non-mutating: GitHub
does not offer an atomic operation that couples the observed branch/check state to an issue close,
so an asynchronous success handler cannot prove that its evidence is still current at mutation
time. Closing remains a human action after current required contexts and live protection are
verified.
"""
from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from required_checks import load_required_checks

MARKER = "<!-- dulcet-main-health -->"
API = "https://api.github.com"
MANIFEST = Path(__file__).resolve().parent.parent / ".github/required-checks.json"
BRANCH_PROTECTION_WORKFLOW = "branch-protection-drift"
ALARM_CONCLUSIONS = frozenset({"failure", "timed_out"})
IGNORED_CONCLUSIONS = frozenset({"cancelled"})


def call(method: str, path: str, token: str, body: dict | None = None) -> object:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        f"{API}{path}",
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        # Report the endpoint, never the token, and never the full URL.
        raise SystemExit(f"{method} {path} failed: {failure.code} {failure.reason}")
    except (urllib.error.URLError, TimeoutError) as failure:
        raise SystemExit(f"{method} {path} failed: {type(failure).__name__}")


def fail(message: str) -> None:
    raise SystemExit(f"report_main_health: {message}")


def open_health_issue(repository: str, token: str) -> int | None:
    query = urllib.parse.quote(f'repo:{repository} is:issue is:open in:body "{MARKER}"')
    found = call("GET", f"/search/issues?q={query}", token)
    if not isinstance(found, dict) or not isinstance(found.get("items"), list):
        fail("GitHub issue search returned malformed JSON")
    existing = found["items"]
    if not existing:
        return None
    number = existing[0].get("number") if isinstance(existing[0], dict) else None
    if not isinstance(number, int) or number <= 0:
        fail("GitHub issue search returned a malformed issue")
    return number


def report_alarm(
    repository: str,
    token: str,
    issue_number: int | None,
    workflow: str,
    conclusion: str,
    run_url: str,
    head: str,
    identifier: int,
) -> None:
    line = (
        f"- **{workflow}** concluded **{conclusion}** on `main` at `{head[:8]}` — "
        f"[run `{identifier}`]({run_url})"
    )
    if issue_number is not None:
        call(
            "POST",
            f"/repos/{repository}/issues/{issue_number}/comments",
            token,
            {"body": f"Still red.\n\n{line}"},
        )
        print(f"MAIN HEALTH: appended to existing issue #{issue_number}")
        return

    body = (
        f"{MARKER}\n"
        f"A protected-branch health signal failed on `main`. This issue was opened "
        f"automatically because post-merge failures otherwise have no durable owner.\n\n"
        f"{line}\n\n"
        f"Further failures while it is open are appended as comments. Automatic closing is "
        f"disabled because an asynchronous workflow cannot atomically prove that the observed "
        f"branch and required-check state is still current when the issue mutation occurs. "
        f"Close manually only after verifying the current required contexts and live branch "
        f"protection."
    )
    created = call(
        "POST",
        f"/repos/{repository}/issues",
        token,
        {"title": "main is red: a required check is failing on the default branch", "body": body},
    )
    if not isinstance(created, dict) or not isinstance(created.get("number"), int):
        fail("GitHub create-issue API returned malformed JSON")
    print(f"MAIN HEALTH: opened issue #{created['number']}")


def main() -> None:
    token = os.environ.get("GITHUB_TOKEN", "")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    workflow = os.environ.get("RUN_WORKFLOW", "")
    conclusion = os.environ.get("RUN_CONCLUSION", "")
    run_url = os.environ.get("RUN_URL", "")
    head = os.environ.get("RUN_HEAD_SHA", "")
    raw_identifier = os.environ.get("RUN_ID", "")
    if not all([token, repository, workflow, conclusion, run_url, head, raw_identifier]):
        fail(
            "missing required environment. Refusing to run rather than reporting a health "
            "state it cannot establish."
        )
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        fail("GITHUB_REPOSITORY is malformed")
    try:
        default_branch, required_contexts = load_required_checks(MANIFEST)
    except (OSError, ValueError) as error:
        fail(str(error))
    monitored_workflows = required_contexts | {BRANCH_PROTECTION_WORKFLOW}
    if default_branch != "main":
        fail(f"required-checks manifest default branch is {default_branch!r}, expected 'main'")
    if workflow not in monitored_workflows:
        fail(f"unexpected workflow {workflow!r}")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", head):
        fail("RUN_HEAD_SHA must be a full 40-character commit SHA")
    if not raw_identifier.isdigit() or int(raw_identifier) <= 0:
        fail("RUN_ID must be a positive integer")
    identifier = int(raw_identifier)

    # A cancelled workflow is deliberately not a health signal. Keep this defense even though the
    # workflow-level condition prevents cancelled events from starting the job.
    if conclusion in IGNORED_CONCLUSIONS:
        print(f"MAIN HEALTH: ignored cancelled run {identifier}")
        return
    if conclusion not in ALARM_CONCLUSIONS | {"success"}:
        fail(f"unexpected conclusion {conclusion!r}")

    # This is intentionally before issue lookup: success must not mutate or even inspect alarm
    # state. There is no compare-and-swap spanning GitHub's branch/check and issue APIs, so every
    # automatic close has an unavoidable stale-green interval.
    if conclusion == "success":
        print(
            f"MAIN HEALTH: {workflow} succeeded at {head[:8]}; "
            f"automatic alarm closing is disabled"
        )
        return

    issue_number = open_health_issue(repository, token)
    report_alarm(
        repository,
        token,
        issue_number,
        workflow,
        conclusion,
        run_url,
        head,
        identifier,
    )


if __name__ == "__main__":
    main()
