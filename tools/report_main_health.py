#!/usr/bin/env python3
"""Open or update one GitHub issue when a required check fails on `main`.

WHY THIS EXISTS
---------------
A branch has an owner watching it; `main` has nobody. Sessions watch a pull request until it
merges and then stop, so a check that goes red *after* the merge is invisible by construction
rather than by accident.

Measured 2026-09-02 with an authenticated sweep of 57 completed `apple-ci` runs: `main` appears in
the failure list **six times**, and nobody had noticed any of them. Two sessions had been working
the repository all day and both had been asserting "main is green" -- which turned out to be a
statement about the last thing either happened to look at.

The fix is not another watcher, because a watcher is a session and sessions end. It is a durable
artifact that survives everybody: one issue, opened automatically, that has to be closed by a
person. Silence then means healthy, and only then.

Idempotent by design: one open issue at a time, further failures append a comment. A flapping check
must not manufacture a hundred issues -- that is how a signal becomes noise and gets muted.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

MARKER = "<!-- dulcet-main-health -->"
API = "https://api.github.com"


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
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        # Report the endpoint, never the token, and never the full URL.
        raise SystemExit(f"{method} {path} failed: {failure.code} {failure.reason}")


def main() -> None:
    token = os.environ.get("GITHUB_TOKEN")
    repository = os.environ.get("GITHUB_REPOSITORY")
    workflow = os.environ.get("FAILED_WORKFLOW")
    conclusion = os.environ.get("FAILED_CONCLUSION")
    run_url = os.environ.get("FAILED_RUN_URL")
    head = os.environ.get("FAILED_HEAD_SHA", "")[:8]
    # head is required too: an issue that says "on `main` at ``" names no commit, and the first
    # thing anybody reads it for is which commit to look at.
    if not all([token, repository, workflow, conclusion, run_url, head]):
        raise SystemExit(
            "report_main_health: missing required environment. Refusing to run rather than "
            "reporting a health state it cannot establish."
        )

    title = "main is red: a required check is failing on the default branch"
    line = (
        f"- **{workflow}** concluded **{conclusion}** on `main` at `{head}` — {run_url}"
    )

    query = urllib.parse.quote(f'repo:{repository} is:issue is:open in:body "{MARKER}"')
    found = call("GET", f"/search/issues?q={query}", token)
    existing = (found or {}).get("items") or []

    if existing:
        number = existing[0]["number"]
        call(
            "POST",
            f"/repos/{repository}/issues/{number}/comments",
            token,
            {"body": f"Still red.\n\n{line}"},
        )
        print(f"MAIN HEALTH: appended to existing issue #{number}")
        return

    body = (
        f"{MARKER}\n"
        f"A required check failed on `main`. This issue was opened automatically because "
        f"**nobody watches `main`** — sessions watch a pull request until it merges and then stop, "
        f"so a post-merge failure is invisible unless something like this speaks up.\n\n"
        f"{line}\n\n"
        f"**Close this issue once `main` is green again.** It will not close itself: an automatic "
        f"close would restore exactly the silence this exists to break, and a check that flaps "
        f"green would erase the record of it having been red.\n\n"
        f"Further failures while this is open are appended as comments rather than opening new "
        f"issues."
    )
    created = call(
        "POST", f"/repos/{repository}/issues", token, {"title": title, "body": body}
    )
    print(f"MAIN HEALTH: opened issue #{created['number']}")


if __name__ == "__main__":
    main()
