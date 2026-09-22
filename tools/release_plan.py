#!/usr/bin/env python3
"""Resolve a release.yml dispatch into one fixed, validated release plan (spec §22).

Input comes from the environment (RELEASE_CHANNEL, RELEASE_PLATFORM, RELEASE_DRY_RUN,
RELEASE_REF) and the plan is printed as GitHub step outputs (key=value lines). No secret is read
here, so this runs before any signing material is decoded.

Every (channel, platform) pair maps to exactly one scheme, bundle identifier and provisioning
profile. A pair without an entry is refused with the reason, never approximated by a neighbour:
a DEV dispatch that silently resolved to the PROD identity would be the most permanent mistake
this workflow can make (a bundle identifier freezes on its first upload).
"""

from __future__ import annotations

from pathlib import Path
import json
import os
import re
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
from required_checks import load_required_checks  # noqa: E402


TEAM_ID = "3LTL47SJ8C"
PROJECT = Path("apple/project.yml")
# Universal purchase (spec §22.2): exactly two identifiers, one App Store Connect record each, and
# every platform of a channel ships under its channel's identifier. Build numbers are taken across
# the whole family so the two records never hand out overlapping numbers.
FAMILY = "com.legitimateapps.dulcet"

# target is the project.yml target the scheme archives; its PROVISIONING_PROFILE_SPECIFIER must
# equal profile_name (tools/test-release-channel holds the two together).
PLANS: dict[tuple[str, str], dict[str, str]] = {
    ("dev", "macos"): {
        "scheme": "DulcetMac",
        "target": "DulcetMac",
        "bundle_id": "com.legitimateapps.dulcet.dev",
        "profile_name": "Dulcet CI Mac Dev App Store",
        "destination": "generic/platform=macOS",
        "package_kind": "pkg",
    },
    ("dev", "ios"): {
        "scheme": "DulcetiOS",
        "target": "DulcetiOS",
        "bundle_id": "com.legitimateapps.dulcet.dev",
        "profile_name": "Dulcet CI Dev iOS App Store",
        "destination": "generic/platform=iOS",
        "package_kind": "ipa",
    },
    ("prod", "macos"): {
        "scheme": "DulcetMacRelease",
        "target": "DulcetMacRelease",
        "bundle_id": "com.legitimateapps.dulcet",
        "profile_name": "Dulcet CI Mac App Store",
        "destination": "generic/platform=macOS",
        "package_kind": "pkg",
    },
}

REFUSALS: dict[tuple[str, str], str] = {
    ("prod", "ios"): (
        "there is no PROD iOS target yet (it will ship as com.legitimateapps.dulcet on the same "
        "record as macOS); PROD ships macOS first (spec §23.1)"
    ),
    ("dev", "tvos"): (
        "tvOS DEV is not deliverable yet: App Store Connect requires a layered tvOS app icon and "
        "a top-shelf image, which the DulcetTV target does not have"
    ),
    ("prod", "tvos"): "there is no PROD tvOS target yet",
}


class PlanError(Exception):
    pass


def target_block(project_text: str, target: str) -> str:
    """Return the lines of one `targets:` entry of project.yml (indent 2 under `targets:`)."""
    lines = project_text.splitlines()
    try:
        start = lines.index("targets:")
    except ValueError as error:
        raise PlanError("apple/project.yml has no targets: mapping") from error
    body: list[str] = []
    inside = False
    for line in lines[start + 1:]:
        if line and not line.startswith(" "):
            break
        if re.fullmatch(r"  [A-Za-z0-9_]+:\s*", line):
            if inside:
                break
            inside = line.strip() == f"{target}:"
            continue
        if inside:
            body.append(line)
    if not body:
        raise PlanError(f"apple/project.yml has no target named {target}")
    return "\n".join(body)


def setting(block: str, name: str) -> list[str]:
    return re.findall(rf'(?m)^\s+{re.escape(name)}:\s*"?([^"\n]*?)"?\s*$', block)


def marketing_version(project_text: str) -> str:
    head = project_text.split("\ntargets:", 1)[0]
    values = setting(head, "MARKETING_VERSION")
    if len(values) != 1 or not re.fullmatch(r"\d+(\.\d+){0,2}", values[0]):
        raise PlanError("apple/project.yml must declare exactly one numeric project-level MARKETING_VERSION")
    return values[0]


def resolve(channel: str, platform: str, dry_run: str, ref: str, project_text: str) -> dict[str, str]:
    if ref != "refs/heads/main":
        raise PlanError(f"releases are cut from refs/heads/main only; this dispatch ran on {ref!r}")
    # Exact spellings only. A value that merely looks false must never be read as "upload".
    if dry_run not in ("true", "false"):
        raise PlanError(f"dry_run must be exactly 'true' or 'false', got {dry_run!r}")
    key = (channel, platform)
    if key in REFUSALS:
        raise PlanError(f"{channel}/{platform} refused: {REFUSALS[key]}")
    if key not in PLANS:
        raise PlanError(f"unknown channel/platform {channel!r}/{platform!r}")
    plan = dict(PLANS[key])
    profiles = setting(target_block(project_text, plan["target"]), "PROVISIONING_PROFILE_SPECIFIER")
    if profiles != [plan["profile_name"]]:
        raise PlanError(
            f"{plan['target']} signs with {profiles} in apple/project.yml, but this plan installs "
            f"{plan['profile_name']!r}; the archive would not find its profile"
        )
    plan.update(
        channel=channel,
        platform=platform,
        upload="true" if dry_run == "false" else "false",
        marketing_version=marketing_version(project_text),
        family_bundle_id=FAMILY,
        # DEV builds are marked internal-only at export, so no DEV binary can reach external
        # TestFlight or App Store review even if someone tried (spec §22.2).
        internal_only="true" if channel == "dev" else "false",
        application_identifier=f"{TEAM_ID}.{plan['bundle_id']}",
    )
    return plan


def prod_gate(marketing: str, tags_at_head: list[str], check_runs: list[dict[str, str]],
              required: set[str]) -> None:
    """Spec §22.1's PROD cut gate, as far as a workflow can check it.

    A hand-cut `v<MARKETING_VERSION>` tag must point at the dispatched commit, and every required
    status check must have concluded `success` on that exact commit. The conformance suite runs
    inside those checks. "FEATURES.yml shows no undeclared regression" is parity-gate's verdict,
    which is one of the required checks.
    """
    expected_tag = f"v{marketing}"
    if expected_tag not in tags_at_head:
        raise PlanError(
            f"PROD needs the tag {expected_tag} on the dispatched commit; found {sorted(tags_at_head)}"
        )
    # Only GitHub Actions' own runs count: a third-party app can post a check run with any name.
    latest = {run["name"]: run.get("conclusion") for run in check_runs
              if run.get("app") == "github-actions"}
    missing = sorted(name for name in required if latest.get(name) != "success")
    if missing:
        raise PlanError(
            "PROD needs every required check green on this commit; not green: "
            + ", ".join(f"{name}={latest.get(name)}" for name in missing)
        )


def fetch_check_runs(repository: str, sha: str, token: str) -> list[dict[str, str]]:
    # filter=latest drops superseded runs of the same name, which would otherwise read as red.
    url = f"https://api.github.com/repos/{repository}/commits/{sha}/check-runs?filter=latest&per_page=100"
    request = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
    })
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            document = json.load(response)
    except (urllib.error.URLError, TimeoutError, ValueError) as error:
        raise PlanError(f"could not read this commit's check runs: {error}") from None
    return [{"name": run["name"], "conclusion": run.get("conclusion"),
             "app": (run.get("app") or {}).get("slug")}
            for run in document.get("check_runs", [])]


def main_prod_gate() -> int:
    try:
        _, required = load_required_checks()
        prod_gate(
            marketing_version(PROJECT.read_text()),
            os.environ.get("RELEASE_TAGS_AT_HEAD", "").split(),
            fetch_check_runs(os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"],
                             os.environ["GITHUB_TOKEN"]),
            required,
        )
    except (PlanError, ValueError) as error:
        print(f"PROD GATE REFUSED: {error}", file=sys.stderr)
        return 1
    print("PROD gate passed: tag present and every required check green on this commit")
    return 0


def main() -> int:
    if sys.argv[1:] == ["prod-gate"]:
        return main_prod_gate()
    try:
        plan = resolve(
            os.environ.get("RELEASE_CHANNEL", ""),
            os.environ.get("RELEASE_PLATFORM", ""),
            os.environ.get("RELEASE_DRY_RUN", ""),
            os.environ.get("RELEASE_REF", ""),
            PROJECT.read_text(),
        )
    except PlanError as error:
        print(f"RELEASE PLAN REFUSED: {error}", file=sys.stderr)
        return 1
    for key in sorted(plan):
        print(f"{key}={plan[key]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
