#!/usr/bin/env python3
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from required_checks import load_required_checks

PLATFORMS = {"macos", "ios", "ipados", "tvos", "android", "androidtv"}
STATUSES = {"shipped", "partial", "planned", "blocked", "n/a"}
LOWER_THAN_SHIPPED = STATUSES - {"shipped"}
TOP_KEYS = {"schema_version", "accepted_regressions", "features"}
FEATURE_KEYS = {"id", "title", "spec", "gates", "conformance", "platforms"}
CELL_KEYS = {"status", "evidence", "reason", "blocked_by", "promotion_condition"}


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


def required_status_checks(_jobs: dict[tuple[str, str], str]) -> set[str]:
    _default_branch, contexts = load_required_checks()
    return contexts


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


SPEC_PATH = Path("docs/superpowers/specs/2026-08-18-dulcet-design.md")


def require_registry_matches_spec(conformance_text: str) -> None:
    """Require the registry and the design's representative-test table to name the same ids.

    The registry reserves the identifiers and the design carries each one's detailed assertion, so
    they are one id space by construction. Nothing enforced that, and they drifted: five ids named
    entirely different tests in the two documents -- CONF-41 was "local and server search merge" in
    one and "getCoverArt size behavior" in the other -- while five more existed only in the design.

    This gate could not see any of it. It checked that a declared id EXISTS in the registry and is
    evidenced exactly once, never what the id meant, so a shipped cell could carry evidence labelled
    with one test while citing an id the design assigns to a different one, and stay green. That is
    worse than a broken gate, because the evidence then reads as verified.

    Set equality is deliberately all this checks. Comparing prose would be brittle and would fail on
    harmless rewording; a one-sided id is unambiguous and is the shape every observed drift took.
    """
    table_row = re.compile(r"^\|\s*(CONF-[0-9]+[a-z]?)\s*\|", re.MULTILINE)
    registry = set(table_row.findall(conformance_text))
    spec = set(table_row.findall(SPEC_PATH.read_text()))
    if registry == spec:
        return
    fail(
        "docs/CONFORMANCE.md and the design's representative-test table must name the same "
        f"conformance ids; only in registry={sorted(registry - spec)}; "
        f"only in design={sorted(spec - registry)}"
    )


def validate(document: dict, source: str) -> dict[str, dict]:
    unknown = set(document) - TOP_KEYS
    if unknown:
        fail(f"{source}: unknown top-level keys: {sorted(unknown)}")
    schema_version = document.get("schema_version")
    if schema_version not in {1, 2}:
        fail(f"{source}: schema_version must be 1 or 2")
    if not isinstance(document.get("accepted_regressions"), list):
        fail(f"{source}: accepted_regressions must be a list")
    features = document.get("features")
    if not isinstance(features, list):
        fail(f"{source}: features must be a list")

    conformance_text = Path("docs/CONFORMANCE.md").read_text()
    conformance_ids = set(re.findall(r"\bCONF-[0-9]+[a-z]?\b", conformance_text))
    require_registry_matches_spec(conformance_text)
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
                if schema_version == 1:
                    entries = [evidence]
                    required_evidence_keys = {"workflow", "job", "test"}
                else:
                    if not isinstance(evidence, list) or not evidence:
                        fail(f"{source}: {feature_id}/{platform} evidence must be a non-empty list")
                    entries = evidence
                    required_evidence_keys = {"conformance", "workflow", "job", "test"}

                evidence_conformance: list[str] = []
                for entry in entries:
                    if (
                        not isinstance(entry, dict)
                        or set(entry) != required_evidence_keys
                        or any(not isinstance(value, str) or not value for value in entry.values())
                    ):
                        fail(
                            f"{source}: {feature_id}/{platform} evidence entries require "
                            f"{sorted(required_evidence_keys)} strings"
                        )
                    if schema_version == 2:
                        evidence_conformance.append(entry["conformance"])
                    if (entry["workflow"], entry["job"]) not in jobs:
                        fail(f"{source}: {feature_id}/{platform} evidence workflow/job does not exist")
                    if entry["test"].split("/")[-1].split("#")[-1] not in tests:
                        fail(f"{source}: {feature_id}/{platform} evidence test does not exist")
                    if entry["job"] not in required_checks:
                        fail(
                            f"{source}: {feature_id}/{platform} evidence job is not required "
                            "by branch protection"
                        )
                    if (entry["workflow"], entry["job"]) not in execution_jobs:
                        fail(
                            f"{source}: {feature_id}/{platform} evidence job is not wired "
                            "to executed-test verification"
                        )

                # One test may not stand as evidence for more than one conformance id on the
                # same platform. The loop above checks that a cited test EXISTS in the tree,
                # which is not the same as checking that it exercises the id it is cited for --
                # so one unrelated test could be pasted against every declared id and every
                # check above would pass. That produces a row which LOOKS evidenced, which is
                # worse than an unevidenced one because it stops anybody looking further.
                #
                # A distinct conformance requirement generally warrants its own test. Where one
                # genuinely covers two, split it or widen this rule deliberately -- do not leave
                # the duplication implicit.
                #
                # OBSERVED 2026-09-02: zero violations across all 144 evidence rows then on
                # main, so this codifies existing practice rather than imposing a new one.
                if schema_version == 2:
                    cited_by: dict[str, list[str]] = {}
                    for entry in entries:
                        cited_by.setdefault(entry["test"], []).append(entry["conformance"])
                    for cited_test, ids in sorted(cited_by.items()):
                        if len(ids) > 1:
                            fail(
                                f"{source}: {feature_id}/{platform} cites one test for "
                                f"{len(ids)} conformance ids ({', '.join(sorted(ids))}): "
                                f"{cited_test}. A test that exists is not a test that "
                                "exercises the id it is cited for; give each id its own evidence."
                            )

                if schema_version == 2:
                    declared_conformance = feature.get("conformance", [])
                    if (
                        len(evidence_conformance) != len(set(evidence_conformance))
                        or set(evidence_conformance) != set(declared_conformance)
                    ):
                        fail(
                            f"{source}: {feature_id}/{platform} evidence must cover each "
                            f"declared conformance id exactly once; declared={declared_conformance}, "
                            f"evidenced={evidence_conformance}"
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
