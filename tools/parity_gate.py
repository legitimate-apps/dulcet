#!/usr/bin/env python3
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from required_checks import load_required_checks
from feature_json import loads as strict_loads

PLATFORMS = {"macos", "ios", "ipados", "tvos", "android", "androidtv"}
STATUSES = {"shipped", "partial", "planned", "blocked", "n/a"}
LOWER_THAN_SHIPPED = STATUSES - {"shipped"}
STATUS_RANK = {"planned": 0, "blocked": 1, "partial": 2, "shipped": 3}
TOP_KEYS = {"schema_version", "accepted_regressions", "accepted_promotions", "features"}
FEATURE_KEYS = {
    "id",
    "title",
    "spec",
    "gates",
    "conformance",
    "platform_conformance",
    "platforms",
}
CELL_KEYS = {"status", "evidence", "reason", "blocked_by", "promotion_condition", "unevidenced_conformance"}


def fail(message: str) -> None:
    raise ValueError(message)


def load_text(text: str, source: str, *, historical: bool = False) -> dict:
    try:
        # Preserve the historical effective baseline so old duplicate keys can be repaired.
        # Every submitted matrix is strict, including duplicates nested in evidence/conditions.
        value = json.loads(text) if historical else strict_loads(text)
    except ValueError as error:
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


def validate_exceptions(document: dict, key: str, source: str) -> None:
    exceptions = document.get(key)
    if not isinstance(exceptions, list):
        fail(f"{source}: {key} must be a list")
    for item in exceptions:
        if not isinstance(item, dict) or set(item) != {"id", "platform", "reason", "pr"}:
            fail(f"{key} entries require exactly id, platform, reason, and pr")
        if (
            not isinstance(item["reason"], str)
            or not item["reason"].strip()
            or not isinstance(item["pr"], str)
            or not re.fullmatch(r"#[0-9]+", item["pr"])
        ):
            fail(f"{key} entries require a reason and #<number> PR")


def validate(document: dict, source: str) -> dict[str, dict]:
    unknown = set(document) - TOP_KEYS
    if unknown:
        fail(f"{source}: unknown top-level keys: {sorted(unknown)}")
    schema_version = document.get("schema_version")
    if schema_version not in {1, 2}:
        fail(f"{source}: schema_version must be 1 or 2")
    validate_exceptions(document, "accepted_regressions", source)
    validate_exceptions(document, "accepted_promotions", source)
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

        universal_conformance = feature.get("conformance", [])
        if (
            not isinstance(universal_conformance, list)
            or not all(isinstance(conf, str) and conf for conf in universal_conformance)
            or len(universal_conformance) != len(set(universal_conformance))
        ):
            fail(f"{source}: {feature_id} conformance must be a list of unique non-empty ids")
        for conf in universal_conformance:
            if conf not in conformance_ids:
                fail(f"{source}: {feature_id} references unknown {conf}")

        platform_conformance = feature.get("platform_conformance", {})
        if not isinstance(platform_conformance, dict) or set(platform_conformance) - PLATFORMS:
            fail(
                f"{source}: {feature_id} platform_conformance must map known platforms to id lists"
            )
        for platform, ids in platform_conformance.items():
            if (
                not isinstance(ids, list)
                or not ids
                or not all(isinstance(conf, str) and conf for conf in ids)
                or len(ids) != len(set(ids))
            ):
                fail(
                    f"{source}: {feature_id}/{platform} platform_conformance must be a list "
                    "of unique non-empty ids"
                )
            overlap = set(ids) & set(universal_conformance)
            if overlap:
                fail(
                    f"{source}: {feature_id}/{platform} repeats universal conformance ids "
                    f"{sorted(overlap)}"
                )
            for conf in ids:
                if conf not in conformance_ids:
                    fail(f"{source}: {feature_id}/{platform} references unknown {conf}")

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
            gaps = cell.get("unevidenced_conformance", {})
            declared_ids = set(universal_conformance) | set(platform_conformance.get(platform, []))
            if (not isinstance(gaps, dict) or set(gaps) - declared_ids
                    or any(not isinstance(reason, str) or not reason.strip() for reason in gaps.values())):
                fail(f"{source}: {feature_id}/{platform} unevidenced_conformance requires declared ids and nonblank reasons")
            if gaps and (schema_version != 2 or status == "shipped"):
                fail(f"{source}: {feature_id}/{platform} cannot ship with unevidenced conformance")
            evidence = cell.get("evidence")
            if status == "shipped" and evidence is None:
                fail(f"{source}: {feature_id}/{platform} shipped requires workflow/job/test evidence")
            if evidence is not None:
                if schema_version == 1:
                    entries = [evidence]
                    evidence_shapes = {"legacy": {"workflow", "job", "test"}}
                else:
                    if not isinstance(evidence, list) or not evidence:
                        fail(f"{source}: {feature_id}/{platform} evidence must be a non-empty list")
                    entries = evidence
                    # Two evidence shapes, exact and mutually exclusive by key set.
                    # `conformance` cites a registered contract, including protocol, presentation
                    # and platform-security contracts. `observes` supplies a claim without a
                    # matching registry id; platform/UI behavior is not excluded from the registry.
                    # Either shape still requires an executed, passing test and is not by itself
                    # a status promotion.
                    evidence_shapes = {
                        "conformance": {"conformance", "workflow", "job", "test"},
                        "observation": {"observes", "workflow", "job", "test"},
                    }

                evidence_conformance: list[str] = []
                conformance_cited_by: dict[str, list[str]] = {}
                observation_cited_by: dict[str, list[str]] = {}
                for entry in entries:
                    shape_matches = (
                        [name for name, keys in evidence_shapes.items() if set(entry) == keys]
                        if isinstance(entry, dict)
                        else []
                    )
                    if len(shape_matches) != 1:
                        expected = " or ".join(str(sorted(keys)) for keys in evidence_shapes.values())
                        fail(
                            f"{source}: {feature_id}/{platform} evidence entries require exactly "
                            f"{expected}"
                        )
                    shape = shape_matches[0]

                    if shape == "observation":
                        observes = entry["observes"]
                        if not isinstance(observes, str) or not observes.strip():
                            fail(
                                f"{source}: {feature_id}/{platform} evidence observes must be "
                                "a non-empty string"
                            )
                        if any(
                            not isinstance(entry[key], str) or not entry[key]
                            for key in ("workflow", "job", "test")
                        ):
                            fail(
                                f"{source}: {feature_id}/{platform} evidence entries require "
                                f"{sorted(evidence_shapes['observation'])} strings"
                            )
                        observation_cited_by.setdefault(entry["test"], []).append(observes)
                    else:
                        if any(not isinstance(value, str) or not value for value in entry.values()):
                            fail(
                                f"{source}: {feature_id}/{platform} evidence entries require "
                                f"{sorted(evidence_shapes[shape])} strings"
                            )
                        if shape == "conformance":
                            evidence_conformance.append(entry["conformance"])
                            conformance_cited_by.setdefault(entry["test"], []).append(entry["conformance"])

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
                for cited_test, ids in sorted(conformance_cited_by.items()):
                    if len(ids) > 1:
                        fail(
                            f"{source}: {feature_id}/{platform} cites one test for "
                            f"{len(ids)} conformance ids ({', '.join(sorted(ids))}): "
                            f"{cited_test}. A test that exists is not a test that "
                            "exercises the id it is cited for; give each id its own evidence."
                        )

                # The observation shape drops the conformance id, not the risk it guards
                # against: a test cited for two different `observes` claims in one platform cell
                # is the same laundering move as a test cited for two different conformance ids,
                # so the identical one-test-one-claim rule applies here too, tracked separately
                # from conformance citations because a conformance id and an observation string
                # are different claims about the same test and neither should be free to hide
                # behind the other's count.
                for cited_test, claims in sorted(observation_cited_by.items()):
                    if len(claims) > 1:
                        fail(
                            f"{source}: {feature_id}/{platform} cites one test for "
                            f"{len(claims)} observation claims ({', '.join(sorted(claims))}): "
                            f"{cited_test}. A test that exists is not a test that "
                            "demonstrates the claim it is cited for; give each observation its own evidence."
                        )

                # Observation-only cells do not assert conformance coverage. Once any
                # conformance row is cited, require every declared id to have evidence or a
                # named gap. Shipped cells are forbidden from carrying gaps above.
                if schema_version == 2 and (evidence_conformance or status == "shipped"):
                    declared_conformance = [
                        *universal_conformance,
                        *platform_conformance.get(platform, []),
                    ]
                    if (
                        len(evidence_conformance) != len(set(evidence_conformance))
                        or set(evidence_conformance) & set(gaps)
                        or set(evidence_conformance) | set(gaps) != set(declared_conformance)
                    ):
                        fail(
                            f"{source}: {feature_id}/{platform} evidence and named gaps must cover each "
                            f"declared conformance id exactly once; declared={declared_conformance}, "
                            f"evidenced={evidence_conformance}"
                        )
    return by_id


def accepted(document: dict, key: str, feature_id: str, platform: str) -> bool:
    for item in document.get(key, []):
        if item["id"] == feature_id and item["platform"] == platform:
            return True
    return False


def evidence_rows(cell: dict) -> set[frozenset[tuple[str, str]]]:
    evidence = cell.get("evidence")
    if evidence is None:
        return set()
    entries = evidence if isinstance(evidence, list) else [evidence]
    # Normalize prose whitespace, including wrapped lines and repeated separators. Keep
    # every key, prose word, case/punctuation and executable identifier in the row identity.
    return {
        frozenset((key, " ".join(value.split()) if key == "observes" else value)
                  for key, value in entry.items())
        for entry in entries
    }


def base_document() -> dict:
    base_ref = os.environ.get("GITHUB_BASE_REF")
    candidate = f"origin/{base_ref}" if base_ref else "HEAD^"
    document_ref = f"{candidate}:FEATURES.yml"
    result = subprocess.run(
        ["git", "show", document_ref],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    if result.returncode != 0:
        fail(f"cannot resolve base document {document_ref}; fetch the required history")
    return load_text(result.stdout, candidate, historical=True)


try:
    current_document = load_text(Path("FEATURES.yml").read_text(), "FEATURES.yml")
    current = validate(current_document, "FEATURES.yml")
    previous_document = base_document()
    # `accepted_promotions` is introduced by this change. A pre-change merge base has no
    # declarations, which is semantically the same as the new list being empty; current
    # documents still have to carry the structural key and are validated above.
    previous_document.setdefault("accepted_promotions", [])
    previous = validate(previous_document, "base FEATURES.yml")
    for feature_id, old_feature in previous.items():
        if feature_id not in current:
            fail(f"feature row removed: {feature_id}")
        for platform in PLATFORMS:
            old_cell = old_feature["platforms"][platform]
            new_cell = current[feature_id]["platforms"][platform]
            old_status = old_cell["status"]
            new_status = new_cell["status"]
            if old_status == "shipped" and new_status in LOWER_THAN_SHIPPED:
                if not accepted(
                    current_document, "accepted_regressions", feature_id, platform
                ):
                    fail(f"undeclared regression: {feature_id}/{platform} shipped -> {new_status}")
            if (
                old_status in STATUS_RANK
                and new_status in STATUS_RANK
                and STATUS_RANK[new_status] > STATUS_RANK[old_status]
                and not evidence_rows(new_cell) - evidence_rows(old_cell)
                and not accepted(
                    current_document, "accepted_promotions", feature_id, platform
                )
            ):
                fail(
                    f"promotion without added evidence: {feature_id}/{platform} "
                    f"{old_status} -> {new_status}; no evidence row was added"
                )
    print(f"parity gate valid: {len(current)} feature rows")
except (OSError, ValueError) as error:
    print(error, file=sys.stderr)
    raise SystemExit(1)
