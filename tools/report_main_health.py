#!/usr/bin/env python3
"""Keep a durable GitHub issue aligned with protected-branch health on `main`.

Failures and timeouts open an alarm or append to it. A success event may close an existing alarm
only after every manifest-required context has a latest successful GitHub Actions check run on that
exact SHA. The branch head is checked before the audit comment and again immediately before close.
A later failure opens a fresh alarm, making the remaining API-round-trip race self-correcting.
"""
from __future__ import annotations

from dataclasses import dataclass
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
GITHUB_ACTIONS_APP_ID = 15368
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


def check_runs_for_head(repository: str, head: str, token: str) -> list[dict[str, object]]:
    """Return every check run reported for one exact commit SHA, failing on truncation."""
    runs: list[dict[str, object]] = []
    expected_total: int | None = None
    page = 1
    while True:
        query = urllib.parse.urlencode({"filter": "all", "per_page": 100, "page": page})
        document = call(
            "GET", f"/repos/{repository}/commits/{head}/check-runs?{query}", token
        )
        if not isinstance(document, dict):
            fail("GitHub check-runs API returned malformed JSON")
        total = document.get("total_count")
        batch = document.get("check_runs")
        if (
            not isinstance(total, int)
            or isinstance(total, bool)
            or total < 0
            or not isinstance(batch, list)
            or not all(isinstance(item, dict) for item in batch)
        ):
            fail("GitHub check-runs API returned a malformed document")
        if expected_total is None:
            expected_total = total
        elif total != expected_total:
            fail("GitHub check-runs API changed total_count during pagination")
        runs.extend(batch)
        if len(runs) > total:
            fail("GitHub check-runs API returned more entries than total_count")
        if len(runs) >= total:
            break
        if len(batch) < 100:
            fail("GitHub check-runs API ended pagination before total_count")
        page += 1
        if page > 100:
            fail("more than 10,000 check runs matched one head SHA")
    return runs


def commit_statuses_for_head(
    repository: str, head: str, token: str
) -> list[dict[str, object]]:
    """Return classic commit statuses so they cannot silently collide with check contexts."""
    statuses: list[dict[str, object]] = []
    page = 1
    while True:
        query = urllib.parse.urlencode({"per_page": 100, "page": page})
        document = call("GET", f"/repos/{repository}/commits/{head}/status?{query}", token)
        if not isinstance(document, dict):
            fail("GitHub commit-status API returned a malformed document")
        exact_response_sha(document.get("sha"), head, "GitHub commit-status API")
        if not isinstance(document.get("statuses"), list) or not all(
            isinstance(item, dict) for item in document["statuses"]
        ):
            fail("GitHub commit-status API returned a malformed document or different head SHA")
        batch = document["statuses"]
        statuses.extend(batch)
        if len(batch) < 100:
            break
        page += 1
        if page > 100:
            fail("more than 10,000 commit statuses matched one head SHA")
    return statuses


def positive_identifier(value: object, source: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        fail(f"{source} omitted a positive integer id")
    return value


def exact_response_sha(value: object, head: str, source: str) -> None:
    if not isinstance(value, str) or value.lower() != head.lower():
        fail(f"{source} returned a different head SHA")


@dataclass(frozen=True)
class CheckEvidence:
    context: str
    identifier: int
    url: str


def required_check_evidence(
    required_contexts: set[str],
    head: str,
    checks: list[dict[str, object]],
    statuses: list[dict[str, object]],
) -> tuple[dict[str, CheckEvidence], list[str]]:
    expected: dict[str, list[dict[str, object]]] = {
        context: [] for context in required_contexts
    }
    foreign: dict[str, list[int]] = {context: [] for context in required_contexts}
    classic: dict[str, list[int]] = {context: [] for context in required_contexts}
    seen_check_ids: set[int] = set()
    for item in checks:
        identifier = positive_identifier(item.get("id"), "GitHub check-runs API")
        if identifier in seen_check_ids:
            fail(f"GitHub check-runs API duplicated check run {identifier}")
        seen_check_ids.add(identifier)
        exact_response_sha(item.get("head_sha"), head, f"check run {identifier}")
        name = item.get("name")
        status = item.get("status")
        conclusion = item.get("conclusion")
        app = item.get("app")
        url = item.get("html_url")
        if (
            not isinstance(name, str)
            or not name
            or not isinstance(status, str)
            or not status
            or (
                conclusion is not None
                and (not isinstance(conclusion, str) or not conclusion)
            )
            or not isinstance(app, dict)
            or not isinstance(app.get("id"), int)
            or isinstance(app.get("id"), bool)
            or app.get("id", 0) <= 0
            or not isinstance(url, str)
            or not url
        ):
            fail(f"GitHub check-runs API returned malformed check run {identifier}")
        if name not in required_contexts:
            continue
        if app["id"] == GITHUB_ACTIONS_APP_ID:
            expected[name].append(item)
        else:
            foreign[name].append(identifier)

    seen_status_ids: set[int] = set()
    for item in statuses:
        identifier = positive_identifier(item.get("id"), "GitHub commit-status API")
        if identifier in seen_status_ids:
            fail(f"GitHub commit-status API duplicated status {identifier}")
        seen_status_ids.add(identifier)
        exact_response_sha(item.get("sha"), head, f"commit status {identifier}")
        context = item.get("context")
        state = item.get("state")
        if not isinstance(context, str) or not context or not isinstance(state, str) or not state:
            fail(f"GitHub commit-status API returned malformed status {identifier}")
        if context in required_contexts:
            classic[context].append(identifier)

    evidence: dict[str, CheckEvidence] = {}
    problems: list[str] = []
    for context in sorted(required_contexts):
        if classic[context]:
            problems.append(
                f"{context}=ambiguous classic status id(s) "
                f"{','.join(str(item) for item in classic[context])}"
            )
        if foreign[context]:
            problems.append(
                f"{context}=wrong-app check run id(s) "
                f"{','.join(str(item) for item in foreign[context])}"
            )
        candidates = expected[context]
        if not candidates:
            problems.append(f"{context}=missing")
            continue
        latest = max(candidates, key=lambda item: positive_identifier(item["id"], "check run"))
        identifier = positive_identifier(latest["id"], "check run")
        if latest["status"] != "completed" or latest["conclusion"] != "success":
            problems.append(
                f"{context}={latest['status']}/{latest['conclusion']} (check run {identifier})"
            )
            continue
        evidence[context] = CheckEvidence(context, identifier, str(latest["html_url"]))
    return evidence, problems


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
    required_contexts: set[str],
) -> None:
    evidence, problems = required_check_evidence(
        required_contexts,
        head,
        check_runs_for_head(repository, head, token),
        commit_statuses_for_head(repository, head, token),
    )
    if problems or set(evidence) != required_contexts:
        detail = ", ".join(problems) or "required context evidence incomplete"
        print(f"MAIN HEALTH: issue #{issue_number} remains open at {head[:8]}: {detail}")
        return

    live_head = current_main_head(repository, token)
    if live_head != head:
        print(
            f"MAIN HEALTH: issue #{issue_number} remains open; green SHA {head[:8]} "
            f"was superseded by {live_head[:8]}"
        )
        return

    evidence_text = "\n".join(
        f"- **{context}** — check run [`{evidence[context].identifier}`]"
        f"({evidence[context].url})"
        for context in sorted(required_contexts)
    )
    comment = (
        f"All manifest-required check contexts were observed **success** on `main` at "
        f"`{head}`.\n\n"
        f"The exact-SHA GitHub Actions check runs used were:\n\n{evidence_text}\n\n"
        f"The branch head will be read again immediately after this audit record and before "
        f"the close mutation. If a later required workflow fails on `main`, main-health will "
        f"open a fresh alarm issue."
    )
    call(
        "POST",
        f"/repos/{repository}/issues/{issue_number}/comments",
        token,
        {"body": comment},
    )

    # Keep this read adjacent to the state mutation. A newer failure will create a fresh issue even
    # if main moves in the remaining API round trip, but do not knowingly close a superseded alarm.
    final_head = current_main_head(repository, token)
    if final_head != head:
        call(
            "POST",
            f"/repos/{repository}/issues/{issue_number}/comments",
            token,
            {
                "body": (
                    f"Automatic close aborted: `main` moved from `{head}` to `{final_head}` "
                    f"before the close mutation."
                )
            },
        )
        print(
            f"MAIN HEALTH: issue #{issue_number} remains open; main moved to {final_head[:8]} "
            f"immediately before close"
        )
        return

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
        f"A protected-branch health signal failed on `main`. This issue was opened "
        f"automatically because post-merge failures otherwise have no durable owner.\n\n"
        f"{line}\n\n"
        f"Further failures while it is open are appended as comments. It closes automatically "
        f"only after every manifest-required context has a successful GitHub Actions check run "
        f"on one exact current head SHA. A later failure opens a fresh alarm issue."
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
    close_if_green(repository, token, issue_number, head, required_contexts)


if __name__ == "__main__":
    main()
