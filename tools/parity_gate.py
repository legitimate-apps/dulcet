#!/usr/bin/env python3
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

PLATFORMS = {"macos", "ios", "ipados", "tvos", "android", "androidtv"}
STATUSES = {"shipped", "partial", "planned", "blocked", "n/a"}
LOWER_THAN_SHIPPED = STATUSES - {"shipped"}
TOP_KEYS = {"schema_version", "accepted_regressions", "features"}
FEATURE_KEYS = {"id", "title", "spec", "gates", "conformance", "platforms"}
CELL_KEYS = {"status", "evidence", "reason", "blocked_by"}
GITHUB_API_ROOT = "https://api.github.com"
GITHUB_API_VERSION = "2022-11-28"


def fail(message: str) -> None:
    raise ValueError(message)


def load_text(text: str, source: str) -> dict:
    try:
        value = json.loads(text)
    except json.JSONDecodeError as error:
        fail(f"{source}: FEATURES.yml must remain JSON-compatible YAML: {error}")
    if not isinstance(value, dict):
        fail(f"{source}: top level must be an object")
    return value


def slug(heading: str) -> str:
    value = heading.strip().lower()
    value = re.sub(r"[`*_]", "", value)
    value = re.sub(r"[^\w\- ]", "", value)
    return re.sub(r"[ ]+", "-", value)


def spec_anchors(path: Path) -> set[str]:
    return {
        slug(match.group(1))
        for line in path.read_text().splitlines()
        if (match := re.match(r"^#{1,6}\s+(.+?)\s*$", line))
    }


def workflow_jobs() -> dict[tuple[str, str], str]:
    jobs: dict[tuple[str, str], str] = {}
    for workflow in Path(".github/workflows").glob("*.yml"):
        workflow_name = None
        lines = workflow.read_text().splitlines()
        for line in lines:
            if line.startswith("name:"):
                workflow_name = line.split(":", 1)[1].strip()
                break
        if workflow_name is None:
            continue
        job_starts = [
            (index, match.group(1))
            for index, line in enumerate(lines)
            if (match := re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line))
            and any(previous == "jobs:" for previous in lines[:index])
        ]
        for position, (start, job_name) in enumerate(job_starts):
            end = job_starts[position + 1][0] if position + 1 < len(job_starts) else len(lines)
            jobs[(workflow_name, job_name)] = "\n".join(lines[start:end])
    return jobs


def executed_evidence_jobs(jobs: dict[tuple[str, str], str]) -> set[tuple[str, str]]:
    return {
        identity
        for identity, body in jobs.items()
        if any(
            re.match(r"^\s*(?:run:\s*)?python3\s+tools/verify-parity-evidence\b", line)
            for line in body.splitlines()
        )
    }


def api_document(url: str, token: str) -> dict:
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


def branch_protection_document() -> tuple[str, dict]:
    fixture = os.environ.get("DULCET_BRANCH_PROTECTION_FIXTURE")
    if fixture:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            fail("branch-protection fixtures are forbidden in GitHub Actions")
        path = Path(fixture)
        try:
            document = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError) as error:
            fail(f"branch-protection fixture is unreadable: {error}")
        if not isinstance(document, dict):
            fail("branch-protection fixture must be an object")
        return "main", document

    repository = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("BRANCH_PROTECTION_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        fail("GITHUB_REPOSITORY is absent or malformed")
    if not token:
        fail("BRANCH_PROTECTION_TOKEN is required")

    repository_document = api_document(f"{GITHUB_API_ROOT}/repos/{repository}", token)
    default_branch = repository_document.get("default_branch")
    if not isinstance(default_branch, str) or not default_branch:
        fail("GitHub repository API omitted default_branch")
    protection = api_document(
        f"{GITHUB_API_ROOT}/repos/{repository}/branches/"
        f"{quote(default_branch, safe='')}/protection/required_status_checks",
        token,
    )
    return default_branch, protection


def required_status_checks(_jobs: dict[tuple[str, str], str]) -> set[str]:
    default_branch, document = branch_protection_document()
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
    if set(contexts) != set(check_contexts):
        fail(f"GitHub branch protection for {default_branch}: contexts and checks disagree")
    return set(check_contexts)


def test_names() -> set[str]:
    names: set[str] = set()
    for path in Path(".").rglob("*"):
        if not path.is_file() or any(part.startswith(".") or part == "build" for part in path.parts):
            continue
        if path.suffix not in {".kt", ".swift", ".java"}:
            continue
        text = path.read_text(errors="ignore")
        names.update(re.findall(r"\b(?:fun|func|void)\s+([A-Za-z_][A-Za-z0-9_]*)", text))
    return names


def validate(document: dict, source: str) -> dict[str, dict]:
    unknown = set(document) - TOP_KEYS
    if unknown:
        fail(f"{source}: unknown top-level keys: {sorted(unknown)}")
    if document.get("schema_version") != 1:
        fail(f"{source}: schema_version must be 1")
    if not isinstance(document.get("accepted_regressions"), list):
        fail(f"{source}: accepted_regressions must be a list")
    features = document.get("features")
    if not isinstance(features, list):
        fail(f"{source}: features must be a list")

    conformance_text = Path("docs/CONFORMANCE.md").read_text()
    conformance_ids = set(re.findall(r"\bCONF-[0-9]+[a-z]?\b", conformance_text))
    jobs = workflow_jobs()
    execution_jobs = executed_evidence_jobs(jobs)
    required_checks = required_status_checks(jobs)
    tests = test_names()
    by_id: dict[str, dict] = {}

    for feature in features:
        if not isinstance(feature, dict):
            fail(f"{source}: feature rows must be objects")
        unknown = set(feature) - FEATURE_KEYS
        if unknown:
            fail(f"{source}: unknown feature keys: {sorted(unknown)}")
        feature_id = feature.get("id")
        if not isinstance(feature_id, str) or not re.fullmatch(r"[a-z0-9]+(?:[._][a-z0-9]+)*", feature_id):
            fail(f"{source}: invalid feature id {feature_id!r}")
        if feature_id in by_id:
            fail(f"{source}: duplicate feature id {feature_id}")
        by_id[feature_id] = feature

        spec = feature.get("spec", "")
        if "#" not in spec:
            fail(f"{source}: {feature_id} has no spec anchor")
        spec_path_text, anchor = spec.split("#", 1)
        spec_path = Path(spec_path_text)
        if not spec_path.is_file() or anchor not in spec_anchors(spec_path):
            fail(f"{source}: {feature_id} spec anchor does not resolve: {spec}")

        for conf in feature.get("conformance", []):
            if conf not in conformance_ids:
                fail(f"{source}: {feature_id} references unknown {conf}")

        platforms = feature.get("platforms")
        if not isinstance(platforms, dict) or set(platforms) != PLATFORMS:
            fail(f"{source}: {feature_id} must define exactly {sorted(PLATFORMS)}")
        for platform, cell in platforms.items():
            if not isinstance(cell, dict) or set(cell) - CELL_KEYS:
                fail(f"{source}: {feature_id}/{platform} has invalid cell keys")
            status = cell.get("status")
            if status not in STATUSES:
                fail(f"{source}: {feature_id}/{platform} has invalid status {status!r}")
            if status == "n/a" and not cell.get("reason"):
                fail(f"{source}: {feature_id}/{platform} n/a requires reason")
            if status == "blocked" and not cell.get("blocked_by"):
                fail(f"{source}: {feature_id}/{platform} blocked requires blocked_by")
            evidence = cell.get("evidence")
            if status == "shipped" and evidence is None:
                fail(f"{source}: {feature_id}/{platform} shipped requires workflow/job/test evidence")
            if evidence is not None:
                if (
                    not isinstance(evidence, dict)
                    or set(evidence) != {"workflow", "job", "test"}
                    or any(not isinstance(value, str) or not value for value in evidence.values())
                ):
                    fail(f"{source}: {feature_id}/{platform} evidence requires workflow/job/test strings")
                if (evidence["workflow"], evidence["job"]) not in jobs:
                    fail(f"{source}: {feature_id}/{platform} evidence workflow/job does not exist")
                if evidence["test"].split("/")[-1].split("#")[-1] not in tests:
                    fail(f"{source}: {feature_id}/{platform} evidence test does not exist")
                if evidence["job"] not in required_checks:
                    fail(
                        f"{source}: {feature_id}/{platform} evidence job is not required "
                        "by branch protection"
                    )
                if (evidence["workflow"], evidence["job"]) not in execution_jobs:
                    fail(
                        f"{source}: {feature_id}/{platform} evidence job is not wired "
                        "to executed-test verification"
                    )
    return by_id


def accepted(document: dict, feature_id: str, platform: str) -> bool:
    for item in document["accepted_regressions"]:
        if not isinstance(item, dict) or set(item) != {"id", "platform", "reason", "pr"}:
            fail("accepted_regressions entries require exactly id, platform, reason, and pr")
        if not item["reason"] or not re.fullmatch(r"#[0-9]+", str(item["pr"])):
            fail("accepted_regressions entries require a reason and #<number> PR")
        if item["id"] == feature_id and item["platform"] == platform:
            return True
    return False


def base_document() -> dict | None:
    base_ref = os.environ.get("GITHUB_BASE_REF")
    candidates = []
    if base_ref:
        candidates.append(f"origin/{base_ref}")
    candidates.append("HEAD^")
    for candidate in candidates:
        result = subprocess.run(
            ["git", "show", f"{candidate}:FEATURES.yml"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        if result.returncode == 0:
            return load_text(result.stdout, candidate)
    return None


try:
    current_document = load_text(Path("FEATURES.yml").read_text(), "FEATURES.yml")
    current = validate(current_document, "FEATURES.yml")
    previous_document = base_document()
    if previous_document is not None:
        previous = validate(previous_document, "base FEATURES.yml")
        for feature_id, old_feature in previous.items():
            if feature_id not in current:
                fail(f"feature row removed: {feature_id}")
            for platform in PLATFORMS:
                old_status = old_feature["platforms"][platform]["status"]
                new_status = current[feature_id]["platforms"][platform]["status"]
                if old_status == "shipped" and new_status in LOWER_THAN_SHIPPED:
                    if not accepted(current_document, feature_id, platform):
                        fail(f"undeclared regression: {feature_id}/{platform} shipped -> {new_status}")
    print(f"parity gate valid: {len(current)} feature rows")
except (OSError, ValueError) as error:
    print(error, file=sys.stderr)
    raise SystemExit(1)
