#!/usr/bin/env python3
"""Keep one GitHub issue aligned with required-workflow health on `main`.

Failures and timeouts open the durable alarm or append to it. A success event closes an existing
alarm only after every required workflow has a successful, non-cancelled run for that event's exact
head SHA. The close comment records the SHA and the run IDs used for the decision.

Idempotent by design: one open issue at a time, and partial recovery never mutates the issue. A
flapping check must neither manufacture many issues nor turn one green workflow into a false
all-clear.
"""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

MARKER = "<!-- dulcet-main-health -->"
API = "https://api.github.com"
REQUIRED_WORKFLOWS = ("apple-ci", "core-ci", "parity-gate")
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


def workflow_runs_for_head(repository: str, head: str, token: str) -> list[dict[str, object]]:
    """Return every completed Actions run the API associates with one exact head SHA."""
    runs: list[dict[str, object]] = []
    page = 1
    while True:
        query = urllib.parse.urlencode(
            {
                "branch": "main",
                "head_sha": head,
                "status": "completed",
                "per_page": 100,
                "page": page,
            }
        )
        document = call("GET", f"/repos/{repository}/actions/runs?{query}", token)
        if not isinstance(document, dict) or not isinstance(document.get("workflow_runs"), list):
            fail("GitHub workflow-runs API returned malformed JSON")
        batch = document["workflow_runs"]
        if not all(isinstance(run, dict) for run in batch):
            fail("GitHub workflow-runs API returned a malformed run")
        runs.extend(batch)

        total = document.get("total_count")
        if isinstance(total, int) and total >= 0 and len(runs) >= total:
            break
        if len(batch) < 100:
            break
        page += 1
        if page > 100:
            fail("more than 10,000 workflow runs matched one head SHA")
    return runs


def run_id(run: dict[str, object]) -> int:
    identifier = run.get("id")
    if not isinstance(identifier, int) or identifier <= 0:
        fail("GitHub workflow-runs API returned a required run without a valid id")
    return identifier


def effective_required_runs(
    runs: list[dict[str, object]], head: str, current: dict[str, object]
) -> dict[str, dict[str, object]]:
    """Select the newest non-cancelled run per required workflow on the exact SHA."""
    selected: dict[str, dict[str, object]] = {}
    for run in [*runs, current]:
        name = run.get("name")
        if (
            name not in REQUIRED_WORKFLOWS
            or run.get("head_sha") != head
            or run.get("head_branch") != "main"
            or run.get("event") == "pull_request"
        ):
            continue
        conclusion = run.get("conclusion")
        if conclusion in IGNORED_CONCLUSIONS:
            continue
        if not isinstance(conclusion, str) or not conclusion:
            fail(f"GitHub workflow-runs API omitted the conclusion for {name}")
        identifier = run_id(run)
        previous = selected.get(name)
        if previous is None or identifier > run_id(previous):
            selected[name] = run
    return selected


def describe_health(runs: dict[str, dict[str, object]]) -> str:
    states = []
    for workflow in REQUIRED_WORKFLOWS:
        run = runs.get(workflow)
        if run is None:
            states.append(f"{workflow}=missing")
        else:
            states.append(f"{workflow}={run['conclusion']} (run {run_id(run)})")
    return ", ".join(states)


def current_main_head(repository: str, token: str) -> str:
    document = call("GET", f"/repos/{repository}/branches/main", token)
    commit = document.get("commit") if isinstance(document, dict) else None
    head = commit.get("sha") if isinstance(commit, dict) else None
    if not isinstance(head, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", head):
        fail("GitHub branches API omitted the current main head SHA")
    return head


def close_if_green(
    repository: str,
    token: str,
    issue_number: int,
    head: str,
    current: dict[str, object],
) -> None:
    runs = effective_required_runs(
        workflow_runs_for_head(repository, head, token), head, current
    )
    if set(runs) != set(REQUIRED_WORKFLOWS) or any(
        run["conclusion"] != "success" for run in runs.values()
    ):
        print(
            f"MAIN HEALTH: issue #{issue_number} remains open at {head[:8]}: "
            f"{describe_health(runs)}"
        )
        return

    # A delayed success for a superseded commit must not clear an alarm that may describe the
    # current head. The required runs above are still resolved for the event's SHA, never by global
    # recency; this final branch-head check only proves that SHA still represents `main`.
    live_head = current_main_head(repository, token)
    if live_head != head:
        print(
            f"MAIN HEALTH: issue #{issue_number} remains open; green SHA {head[:8]} "
            f"was superseded by {live_head[:8]}"
        )
        return

    evidence = []
    for workflow in REQUIRED_WORKFLOWS:
        run = runs[workflow]
        identifier = run_id(run)
        url = run.get("html_url")
        if not isinstance(url, str) or not url:
            url = f"https://github.com/{repository}/actions/runs/{identifier}"
        evidence.append(f"- **{workflow}** — run [`{identifier}`]({url})")
    evidence_text = "\n".join(evidence)
    comment = (
        f"All required checks are green on `main` at `{head}`.\n\n"
        f"The same-SHA runs used for this decision were:\n\n"
        f"{evidence_text}\n\n"
        f"Closing this alarm automatically."
    )
    call(
        "POST",
        f"/repos/{repository}/issues/{issue_number}/comments",
        token,
        {"body": comment},
    )
    call(
        "PATCH",
        f"/repos/{repository}/issues/{issue_number}",
        token,
        {"state": "closed"},
    )
    print(f"MAIN HEALTH: closed issue #{issue_number} at {head[:8]}")


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
        f"A required check failed on `main`. This issue was opened automatically because "
        f"post-merge failures otherwise have no durable owner.\n\n"
        f"{line}\n\n"
        f"This issue closes automatically only when `apple-ci`, `core-ci`, and `parity-gate` "
        f"are all green on the same head SHA. Further failures while it is open are appended "
        f"as comments."
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
    if workflow not in REQUIRED_WORKFLOWS:
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

    issue_number = open_health_issue(repository, token)
    if conclusion in ALARM_CONCLUSIONS:
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
        return
    if issue_number is None:
        print(f"MAIN HEALTH: {head[:8]} reported success; no open alarm")
        return

    close_if_green(
        repository,
        token,
        issue_number,
        head,
        {
            "id": identifier,
            "name": workflow,
            "head_sha": head,
            "head_branch": "main",
            "event": "workflow_run",
            "conclusion": conclusion,
            "html_url": run_url,
        },
    )


if __name__ == "__main__":
    main()
