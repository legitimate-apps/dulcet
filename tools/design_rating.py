#!/usr/bin/env python3
"""Run and assemble isolated-per-appearance macOS design ratings.

The online boundary is deliberately one request per invocation. Run ``prepare`` once,
invoke ``rate-one`` fourteen times under ``secret exec``, then run ``assemble`` without
credentials. Raw provider responses are retained beside the mechanically assembled report.
"""

from __future__ import annotations

import argparse
import base64
from collections import OrderedDict
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
import os
from pathlib import Path
import secrets
import sys
from typing import Any
import urllib.error
import urllib.request


SCHEMA_VERSION = 1
DERIVATION_PROTOCOL = "isolated-per-appearance-v1"
CONTEXT_SCOPE = "fresh-no-prior-scores"
STATES = (
    "empty-library-no-account",
    "library-browse",
    "album-detail-multi-disc",
    "now-playing",
    "search-mixed-sources",
    "error-tls-untrusted",
    "offline-metadata-only",
)
APPEARANCES = ("light", "dark")
CATEGORY_WEIGHTS = OrderedDict(
    (
        ("platform_idiom", 25),
        ("hierarchy_and_typography", 20),
        ("spacing_and_density", 20),
        ("state_clarity_and_primary_action", 20),
        ("coherence_and_finish", 15),
    )
)
INFORMATION_DESIGN_CATEGORIES = tuple(CATEGORY_WEIGHTS)[1:]
ACCESSIBILITY_CATEGORIES = {
    "contrast",
    "text_scaling_and_reflow",
    "non_color_communication",
    "labels_and_roles",
    "keyboard_and_focus",
}
PROVIDER_ENDPOINTS = {
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
    "openrouter": "https://openrouter.ai/api/v1/chat/completions",
}


class RatingError(ValueError):
    """A rating input or derivation does not satisfy the repository contract."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise RatingError(f"cannot read JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise RatingError(f"JSON root must be an object: {path}")
    return value


def write_json_exclusive(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as handle:
        json.dump(value, handle, indent=2, sort_keys=False)
        handle.write("\n")


def replace_json(path: Path, value: Any) -> None:
    temporary = path.with_name(f".{path.name}.{secrets.token_hex(8)}.tmp")
    try:
        write_json_exclusive(temporary, value)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def round_integer(value: float) -> int:
    return int(Decimal(str(value)).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def capture_index(manifest: dict[str, Any]) -> dict[tuple[str, str, str], dict[str, Any]]:
    captures = manifest.get("captures")
    if not isinstance(captures, list):
        raise RatingError("artifact manifest captures must be an array")
    result: dict[tuple[str, str, str], dict[str, Any]] = {}
    for capture in captures:
        if not isinstance(capture, dict):
            raise RatingError("artifact manifest capture must be an object")
        key = (
            str(capture.get("variant")),
            str(capture.get("fixtureState")),
            str(capture.get("appearance")),
        )
        if key in result:
            raise RatingError(f"artifact manifest has duplicate capture {key}")
        result[key] = capture
    return result


def checked_image(
    artifact: Path,
    capture: dict[str, Any],
    *,
    role: str,
) -> dict[str, str]:
    filename = capture.get("file")
    expected_digest = capture.get("sha256")
    if not isinstance(filename, str) or Path(filename).name != filename:
        raise RatingError(f"capture filename is not a basename: {filename!r}")
    if not isinstance(expected_digest, str) or len(expected_digest) != 64:
        raise RatingError(f"capture {filename} has an invalid SHA-256")
    path = artifact / filename
    if not path.is_file():
        raise RatingError(f"artifact image is missing: {filename}")
    actual_digest = sha256(path)
    if actual_digest != expected_digest:
        raise RatingError(
            f"artifact image digest mismatch for {filename}: "
            f"manifest={expected_digest} actual={actual_digest}"
        )
    if role == "control" and capture.get("pinnedControlSha256") != actual_digest:
        raise RatingError(f"pinned control digest mismatch for {filename}")
    return {"role": role, "filename": filename, "sha256": actual_digest}


def validate_artifact(artifact: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    artifact = artifact.resolve()
    manifest_path = artifact / "manifest.json"
    manifest = read_json(manifest_path)
    if manifest.get("artifactClass") != "NON-DETERMINISTIC-SHIPPING-DESIGN-REFERENCE":
        raise RatingError("artifact is not a shipping design reference")
    if "pairwise-design-rating" not in manifest.get("admissibleUses", []):
        raise RatingError("artifact does not admit pairwise design rating")
    missing = manifest.get("missingCaptures")
    if missing != []:
        raise RatingError(f"artifact manifest has missing captures: {missing!r}")

    captures = capture_index(manifest)
    requests: list[dict[str, Any]] = []
    for state in STATES:
        for appearance in APPEARANCES:
            standard_key = ("shipping-reference", state, appearance)
            if standard_key not in captures:
                raise RatingError(f"artifact is missing standard capture {state}/{appearance}")
            inputs = [checked_image(artifact, captures[standard_key], role="standard")]
            if state == "library-browse":
                control_key = ("deliberately-bad-control", state, appearance)
                if control_key not in captures:
                    raise RatingError(f"artifact is missing pinned control {appearance}")
                inputs.append(checked_image(artifact, captures[control_key], role="control"))
            requests.append(
                {
                    "index": len(requests),
                    "state": state,
                    "appearance": appearance,
                    "request_nonce": secrets.token_hex(16),
                    "context_scope": CONTEXT_SCOPE,
                    "input_images": inputs,
                }
            )
    if len({request["request_nonce"] for request in requests}) != 14:
        raise RatingError("harness generated repeated request nonces")
    return manifest, requests


def prepare_run(args: argparse.Namespace) -> None:
    artifact = Path(args.artifact).resolve()
    output = Path(args.output).resolve()
    if output.exists():
        raise RatingError(f"output directory already exists: {output}")
    if args.provider not in PROVIDER_ENDPOINTS:
        raise RatingError(f"unsupported provider: {args.provider}")
    for name, value in (("model", args.model), ("model family", args.model_family)):
        if not value or not value.strip():
            raise RatingError(f"{name} must be non-empty")

    manifest, requests = validate_artifact(artifact)
    plan = {
        "schema_version": SCHEMA_VERSION,
        "derivation_protocol": DERIVATION_PROTOCOL,
        "artifact": {
            "directory": str(artifact),
            "manifest_filename": "manifest.json",
            "manifest_sha256": sha256(artifact / "manifest.json"),
            "artifact_class": manifest["artifactClass"],
            "expected_standard_record_count": 14,
        },
        "rater": {
            "provider": args.provider,
            "model_family": args.model_family.strip(),
            "model": args.model.strip(),
        },
        "requests": requests,
    }
    output.mkdir(parents=True)
    (output / "raw").mkdir()
    (output / "records").mkdir()
    write_json_exclusive(output / "plan.json", plan)
    print(f"prepared 14 isolated requests in {output}")


def expected_response_shape(request: dict[str, Any]) -> str:
    control = (
        'an object with "categories" using the same five keys and a "findings" array'
        if request["state"] == "library-browse"
        else "null"
    )
    return f"""
Return exactly one JSON object with these keys and no markdown:
{{
  "derivation": {{
    "request_nonce": {json.dumps(request['request_nonce'])},
    "context_scope": {json.dumps(CONTEXT_SCOPE)},
    "input_images": {json.dumps(request['input_images'])},
    "appearance_specific_observations": [
      {{"category": "one of the five taste category keys", "observation": "specific visible evidence in this appearance"}}
    ]
  }},
  "categories": {{
    "platform_idiom": "integer 0..25",
    "hierarchy_and_typography": "integer 0..20",
    "spacing_and_density": "integer 0..20",
    "state_clarity_and_primary_action": "integer 0..20",
    "coherence_and_finish": "integer 0..15"
  }},
  "findings": [
    {{"rubric": "taste or accessibility", "category": "category key", "finding": "specific visible evidence"}}
  ],
  "accessibility_findings": [
    {{"status": "OBSERVED or ASSUMED", "category": "one of contrast, text_scaling_and_reflow, non_color_communication, labels_and_roles, keyboard_and_focus", "finding": "bounded claim", "evidence": "visible element or evidence needed"}}
  ],
  "claims": [
    {{"status": "OBSERVED", "claim": "appearance-bounded claim", "evidence": {json.dumps(request['input_images'][0]['filename'])}}},
    {{"status": "ASSUMED", "claim": "interaction claim not established by pixels", "evidence_needed": "interaction trace"}}
  ],
  "control_assessment": {control}
}}
Do not return a taste total, light/dark delta, lens score, state mean, product score, control gap,
or pass/fail verdict. The harness computes all of them.
""".strip()


def build_messages(request: dict[str, Any], artifact: Path) -> list[dict[str, Any]]:
    state_questions = {
        "empty-library-no-account": "Is the restraint intentional, and is connecting the unmistakable next step?",
        "library-browse": "Does density survive large, Unicode, missing, multi-artist, and long-title data without looking like demo data?",
        "album-detail-multi-disc": "Are disc boundaries and track-number restarts immediately legible?",
        "now-playing": "Do transport, progress, identity, output, and queue read in the right order?",
        "search-mixed-sources": "Can local, server, and refreshed results be distinguished without badges overwhelming relevance?",
        "error-tls-untrusted": "Is the limitation safe, specific, actionable, and free of a bypass action?",
        "offline-metadata-only": "Is it clear that browsing works while playback does not?",
    }
    system = """You are an independent external macOS design rater. This is a fresh request with no
prior scores or opposite-appearance image. Judge only visible evidence in the supplied image inputs.
Be critical and literal; do not reward intent that is not visible. Accessibility interaction claims
remain ASSUMED from pixels. Text scaling and reflow are unscored because this artifact has no
platform-applicable macOS text-resizing evidence.

Taste rubric (100 points): platform_idiom 25; hierarchy_and_typography 20;
spacing_and_density 20; state_clarity_and_primary_action 20; coherence_and_finish 15.
Use the full point ranges. Native macOS idiom means source-list navigation, platform controls,
Mac-appropriate density, and no ported-web feel. Findings must name a concrete visible element.
The deliberately bad control, when present, is a calibration image and must be scored independently
with the identical taste rubric. Do not compare against any unseen image or appearance.
"""
    text = (
        f"State: {request['state']}\n"
        f"Appearance: {request['appearance']}\n"
        f"State-specific question: {state_questions[request['state']]}\n\n"
        + expected_response_shape(request)
    )
    content: list[dict[str, Any]] = [{"type": "text", "text": text}]
    for image in request["input_images"]:
        path = artifact / image["filename"]
        encoded = base64.b64encode(path.read_bytes()).decode("ascii")
        content.append(
            {
                "type": "image_url",
                "image_url": {"url": f"data:image/jpeg;base64,{encoded}"},
            }
        )
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": content},
    ]


def secret_value() -> str:
    raw = os.environ.get("SECRET")
    if not raw:
        raise RatingError("SECRET is unavailable; invoke rate-one through secret exec")
    if not raw.lstrip().startswith("{"):
        return raw
    try:
        entry = json.loads(raw)
    except json.JSONDecodeError as error:
        raise RatingError("SECRET looks like JSON but is malformed") from error
    if not isinstance(entry, dict):
        raise RatingError("SECRET JSON must be an object")
    for key in ("api_key", "api_token", "token", "password"):
        value = entry.get(key)
        if isinstance(value, str) and value:
            return value
    raise RatingError("SECRET JSON has no supported API-key field")


def provider_request(
    provider: str,
    model: str,
    messages: list[dict[str, Any]],
    api_key: str,
) -> tuple[urllib.request.Request, dict[str, Any]]:
    if provider not in PROVIDER_ENDPOINTS:
        raise RatingError(f"unsupported provider: {provider}")
    body = {
        "model": model,
        "messages": messages,
        "response_format": {"type": "json_object"},
        "max_tokens": 8000,
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    if provider == "openrouter":
        headers["X-Title"] = "Dulcet design evaluation harness"
    request = urllib.request.Request(
        PROVIDER_ENDPOINTS[provider],
        data=json.dumps(body).encode(),
        headers=headers,
        method="POST",
    )
    return request, body


def response_content(response: dict[str, Any]) -> str:
    try:
        content = response["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as error:
        raise RatingError("provider response has no choices[0].message.content") from error
    if not isinstance(content, str):
        raise RatingError("provider response content is not text")
    stripped = content.strip()
    if stripped.startswith("```json"):
        stripped = stripped[len("```json") :]
    elif stripped.startswith("```"):
        stripped = stripped[3:]
    if stripped.endswith("```"):
        stripped = stripped[:-3]
    return stripped.strip()


def validate_categories(value: Any, label: str) -> list[str]:
    errors: list[str] = []
    if not isinstance(value, dict):
        return [f"{label} must be an object"]
    actual = set(value)
    expected = set(CATEGORY_WEIGHTS)
    if actual != expected:
        errors.append(
            f"{label} category keys mismatch: missing={sorted(expected - actual)} "
            f"unexpected={sorted(actual - expected)}"
        )
    for category, maximum in CATEGORY_WEIGHTS.items():
        score = value.get(category)
        if isinstance(score, bool) or not isinstance(score, int) or not 0 <= score <= maximum:
            errors.append(f"{label}.{category} must be an integer 0..{maximum}")
    return errors


def validate_record(value: Any, request: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if not isinstance(value, dict):
        return ["record root must be an object"]
    expected_keys = {
        "derivation",
        "categories",
        "findings",
        "accessibility_findings",
        "claims",
        "control_assessment",
    }
    if set(value) != expected_keys:
        errors.append(
            f"record keys mismatch: missing={sorted(expected_keys - set(value))} "
            f"unexpected={sorted(set(value) - expected_keys)}"
        )
    derivation = value.get("derivation")
    if not isinstance(derivation, dict):
        errors.append("derivation must be an object")
    else:
        for key, expected in (
            ("request_nonce", request["request_nonce"]),
            ("context_scope", CONTEXT_SCOPE),
            ("input_images", request["input_images"]),
        ):
            if derivation.get(key) != expected:
                errors.append(f"derivation.{key} does not match the harness plan")
        observations = derivation.get("appearance_specific_observations")
        if not isinstance(observations, list) or len(observations) < 2:
            errors.append("derivation.appearance_specific_observations needs at least two entries")
        else:
            for index, observation in enumerate(observations):
                if not isinstance(observation, dict):
                    errors.append(f"appearance observation {index} must be an object")
                    continue
                if observation.get("category") not in CATEGORY_WEIGHTS:
                    errors.append(f"appearance observation {index} has an unknown category")
                text = observation.get("observation")
                if not isinstance(text, str) or len(text.strip()) < 20:
                    errors.append(f"appearance observation {index} lacks specific visible evidence")

    errors.extend(validate_categories(value.get("categories"), "categories"))
    for field in ("findings", "accessibility_findings", "claims"):
        if not isinstance(value.get(field), list):
            errors.append(f"{field} must be an array")

    for index, finding in enumerate(value.get("accessibility_findings", [])):
        if not isinstance(finding, dict):
            errors.append(f"accessibility finding {index} must be an object")
            continue
        if finding.get("status") not in {"OBSERVED", "ASSUMED"}:
            errors.append(f"accessibility finding {index} needs OBSERVED or ASSUMED status")
        if finding.get("category") not in ACCESSIBILITY_CATEGORIES:
            errors.append(f"accessibility finding {index} has an unknown category")

    for index, claim in enumerate(value.get("claims", [])):
        if not isinstance(claim, dict) or claim.get("status") not in {"OBSERVED", "ASSUMED"}:
            errors.append(f"claim {index} needs an OBSERVED or ASSUMED status")

    control = value.get("control_assessment")
    if request["state"] == "library-browse":
        if not isinstance(control, dict):
            errors.append("browse record requires a control_assessment object")
        else:
            if set(control) != {"categories", "findings"}:
                errors.append("control_assessment must contain only categories and findings")
            errors.extend(validate_categories(control.get("categories"), "control_assessment.categories"))
            if not isinstance(control.get("findings"), list):
                errors.append("control_assessment.findings must be an array")
    elif control is not None:
        errors.append("non-browse record control_assessment must be null")
    return errors


def request_for_index(plan: dict[str, Any], index: int) -> dict[str, Any]:
    requests = plan.get("requests")
    if not isinstance(requests, list) or len(requests) != 14:
        raise RatingError("plan must contain exactly 14 requests")
    if not 0 <= index < len(requests):
        raise RatingError(f"request index must be 0..13, got {index}")
    request = requests[index]
    if request.get("index") != index:
        raise RatingError(f"plan request index mismatch at {index}")
    return request


def record_filename(request: dict[str, Any]) -> str:
    return f"{request['index']:02d}-{request['request_nonce']}.json"


def rate_one(args: argparse.Namespace) -> None:
    run = Path(args.run).resolve()
    plan = read_json(run / "plan.json")
    request_plan = request_for_index(plan, args.index)
    artifact = Path(plan["artifact"]["directory"])
    if sha256(artifact / "manifest.json") != plan["artifact"]["manifest_sha256"]:
        raise RatingError("artifact manifest changed after the rating plan was prepared")
    for image in request_plan["input_images"]:
        if sha256(artifact / image["filename"]) != image["sha256"]:
            raise RatingError(f"artifact input changed after planning: {image['filename']}")

    filename = record_filename(request_plan)
    raw_path = run / "raw" / filename
    record_path = run / "records" / filename
    if raw_path.exists() or record_path.exists():
        raise RatingError(f"request {args.index} already has retained output")

    messages = build_messages(request_plan, artifact)
    api_request, api_body = provider_request(
        plan["rater"]["provider"],
        plan["rater"]["model"],
        messages,
        secret_value(),
    )
    try:
        with urllib.request.urlopen(api_request, timeout=120) as response:
            provider_response = json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read(2000).decode(errors="replace")
        raise RatingError(f"provider HTTP {error.code}: {body}") from error
    except (urllib.error.URLError, TimeoutError) as error:
        raise RatingError(f"provider request failed: {error}") from error

    retained_request = {
        "provider": plan["rater"]["provider"],
        "model": plan["rater"]["model"],
        "request_nonce": request_plan["request_nonce"],
        "messages": messages,
        "input_images": request_plan["input_images"],
        "api_body_without_embedded_image_bytes": {
            "model": api_body["model"],
            "response_format": api_body["response_format"],
            "max_tokens": api_body["max_tokens"],
        },
    }
    write_json_exclusive(
        raw_path,
        {"request": retained_request, "provider_response": provider_response},
    )
    try:
        parsed = json.loads(response_content(provider_response))
    except json.JSONDecodeError as error:
        raise RatingError(f"provider content is not JSON; raw response retained at {raw_path}") from error
    errors = validate_record(parsed, request_plan)
    if errors:
        raise RatingError(
            f"provider record violates the derivation contract; raw response retained at {raw_path}: "
            + "; ".join(errors)
        )
    write_json_exclusive(record_path, parsed)
    print(
        f"rated {args.index + 1}/14 {request_plan['state']}/{request_plan['appearance']} "
        f"nonce={request_plan['request_nonce']}"
    )


def replace_failed_request(args: argparse.Namespace) -> None:
    run = Path(args.run).resolve()
    plan_path = run / "plan.json"
    plan = read_json(plan_path)
    request = request_for_index(plan, args.index)
    filename = record_filename(request)
    raw_path = run / "raw" / filename
    record_path = run / "records" / filename
    if not raw_path.is_file():
        raise RatingError(
            f"request {args.index} has no retained failed response; replacement is not justified"
        )
    if record_path.exists():
        raise RatingError(f"request {args.index} already has a valid record")
    old_request = dict(request)
    new_nonce = secrets.token_hex(16)
    existing_nonces = {item["request_nonce"] for item in plan["requests"]}
    while new_nonce in existing_nonces:
        new_nonce = secrets.token_hex(16)
    request["request_nonce"] = new_nonce
    plan.setdefault("superseded_requests", []).append(
        {
            **old_request,
            "raw_response": f"raw/{filename}",
            "replacement_reason": "malformed-or-incomplete-provider-record",
        }
    )
    replace_json(plan_path, plan)
    print(
        f"replaced failed request {args.index} with new nonce={new_nonce}; "
        f"retained raw/{filename}"
    )


def taste(categories: dict[str, int]) -> int:
    return sum(categories[category] for category in CATEGORY_WEIGHTS)


def diagnostic_appearance(request: dict[str, Any], record: dict[str, Any] | None) -> dict[str, Any]:
    if record is None:
        return {
            "derivation": {
                "request_nonce": request["request_nonce"],
                "context_scope": request["context_scope"],
                "input_images": request["input_images"],
                "appearance_specific_observations": [],
            },
            "taste": None,
            "categories": {category: None for category in CATEGORY_WEIGHTS},
            "findings": [],
        }
    result = {
        "derivation": record["derivation"],
        "taste": None,
        "categories": record["categories"],
        "findings": record["findings"],
    }
    if request["state"] == "library-browse":
        result["control_assessment"] = {
            "taste": taste(record["control_assessment"]["categories"]),
            "categories": record["control_assessment"]["categories"],
            "findings": record["control_assessment"]["findings"],
        }
    return result


def assemble(run: Path) -> tuple[dict[str, Any], list[str]]:
    run = run.resolve()
    plan = read_json(run / "plan.json")
    if plan.get("derivation_protocol") != DERIVATION_PROTOCOL:
        raise RatingError("plan has an unsupported derivation protocol")
    requests = plan.get("requests")
    if not isinstance(requests, list) or len(requests) != 14:
        raise RatingError("plan must contain exactly 14 requests")
    nonces = [request.get("request_nonce") for request in requests]
    assembly_errors: list[str] = []
    if len(set(nonces)) != 14:
        assembly_errors.append("the 14 planned request nonces are not unique")

    records: dict[tuple[str, str], dict[str, Any] | None] = {}
    accessibility_findings: list[dict[str, Any]] = []
    claims: list[dict[str, Any]] = []
    for index, request in enumerate(requests):
        if request.get("index") != index:
            assembly_errors.append(f"plan request index mismatch at position {index}")
        filename = record_filename(request)
        raw_path = run / "raw" / filename
        record_path = run / "records" / filename
        if not raw_path.is_file():
            assembly_errors.append(f"missing raw response for request {index}")
        if not record_path.is_file():
            assembly_errors.append(f"missing valid record for request {index}")
            records[(request["state"], request["appearance"])] = None
            continue
        try:
            record = read_json(record_path)
        except RatingError as error:
            assembly_errors.append(str(error))
            records[(request["state"], request["appearance"])] = None
            continue
        errors = validate_record(record, request)
        if errors:
            assembly_errors.extend(f"request {index}: {error}" for error in errors)
            records[(request["state"], request["appearance"])] = None
            continue
        records[(request["state"], request["appearance"])] = record
        standard_filename = request["input_images"][0]["filename"]
        for finding in record["accessibility_findings"]:
            accessibility_findings.append({"filename": standard_filename, **finding})
        for claim in record["claims"]:
            candidate = {"filename": standard_filename, **claim}
            if candidate not in claims:
                claims.append(candidate)

    derivation_valid = not assembly_errors
    control_gate: dict[str, Any] = {}
    calibration_valid = derivation_valid
    if derivation_valid:
        for appearance in APPEARANCES:
            browse = records[("library-browse", appearance)]
            assert browse is not None
            browse_score = taste(browse["categories"])
            control_categories = browse["control_assessment"]["categories"]
            control_score = taste(control_categories)
            other_scores = [
                taste(records[(state, appearance)]["categories"])  # type: ignore[index]
                for state in STATES
            ]
            information_gap = round(
                (
                    sum(browse["categories"][key] for key in INFORMATION_DESIGN_CATEGORIES)
                    - sum(control_categories[key] for key in INFORMATION_DESIGN_CATEGORIES)
                )
                / 75
                * 10,
                2,
            )
            platform_gap = round(
                (browse["categories"]["platform_idiom"] - control_categories["platform_idiom"])
                / 25
                * 10,
                2,
            )
            lowest = control_score == min([control_score, *other_scores])
            passed = (
                control_score <= 40
                and browse_score - control_score >= 25
                and lowest
                and information_gap >= 1.0
                and platform_gap >= 1.0
            )
            control_gate[appearance] = {
                "score": control_score,
                "browse_delta": browse_score - control_score,
                "lowest": lowest,
                "information_design_gap_0_to_10": information_gap,
                "platform_idiom_gap_0_to_10": platform_gap,
                "pass": passed,
            }
            calibration_valid = calibration_valid and passed
    else:
        for appearance in APPEARANCES:
            control_gate[appearance] = {
                "score": None,
                "browse_delta": None,
                "lowest": None,
                "information_design_gap_0_to_10": None,
                "platform_idiom_gap_0_to_10": None,
                "pass": False,
            }

    run_valid = derivation_valid and calibration_valid
    control_gate["run_valid"] = run_valid
    states: dict[str, Any] = {}
    all_standard_scores: list[int] = []
    platform_scores: list[int] = []
    information_scores: list[int] = []
    state_raw_means: dict[str, float] = {}
    for state in STATES:
        state_output: dict[str, Any] = {}
        state_scores: list[int] = []
        for appearance in APPEARANCES:
            request = next(
                item for item in requests
                if item["state"] == state and item["appearance"] == appearance
            )
            record = records.get((state, appearance))
            appearance_output = diagnostic_appearance(request, record)
            if record is not None:
                score = taste(record["categories"])
                state_scores.append(score)
                all_standard_scores.append(score)
                platform_scores.append(record["categories"]["platform_idiom"])
                information_scores.append(
                    sum(record["categories"][key] for key in INFORMATION_DESIGN_CATEGORIES)
                )
                if run_valid:
                    appearance_output["taste"] = score
            state_output[appearance] = appearance_output
        if run_valid:
            raw_mean = sum(state_scores) / 2
            state_raw_means[state] = raw_mean
            state_output["state_mean"] = round_integer(raw_mean)
            state_output["light_dark_delta"] = state_scores[1] - state_scores[0]
            state_output["light_dark_inconsistency"] = abs(state_scores[1] - state_scores[0]) > 12
        else:
            state_output["state_mean"] = None
            state_output["light_dark_delta"] = None
            state_output["light_dark_inconsistency"] = None
        states[state] = state_output

    if run_valid:
        lens_scores = {
            "platform_idiom": round_integer(sum(platform_scores) / 14),
            "information_design": round_integer(sum(information_scores) / 14),
        }
        product_score = round_integer(sum(all_standard_scores) / 14)
        weakest = min(STATES, key=lambda state: state_raw_means[state])
        strongest = max(STATES, key=lambda state: state_raw_means[state])
    else:
        lens_scores = {"platform_idiom": None, "information_design": None}
        product_score = None
        weakest = None
        strongest = None

    report = {
        "schema_version": SCHEMA_VERSION,
        "rater": {
            "model_family": plan["rater"]["model_family"],
            "model": plan["rater"]["model"],
        },
        "artifact": {
            "manifest_sha256": plan["artifact"]["manifest_sha256"],
            "artifact_class": plan["artifact"]["artifact_class"],
            "standard_record_count": 14,
        },
        "derivation_protocol": DERIVATION_PROTOCOL,
        "derivation_valid": derivation_valid,
        "derivation_errors": assembly_errors,
        "control_gate": control_gate,
        "states": states,
        "lens_scores": lens_scores,
        "product_taste_score": product_score,
        "accessibility": {
            "contrast": None,
            "text_scaling_and_reflow": None,
            "non_color_communication": None,
            "labels_and_roles": None,
            "keyboard_and_focus": None,
            "score": None,
            "findings": accessibility_findings,
        },
        "weakest_reference_state": weakest,
        "strongest_reference_state": strongest,
        "claims": claims,
    }
    return report, assembly_errors


def assemble_run(args: argparse.Namespace) -> None:
    run = Path(args.run).resolve()
    report_path = run / "report.json"
    if report_path.exists():
        raise RatingError(f"assembled report already exists: {report_path}")
    report, errors = assemble(run)
    write_json_exclusive(report_path, report)
    if not report["control_gate"]["run_valid"]:
        reasons = errors or ["one or more negative-control gate conditions failed"]
        raise RatingError(
            f"rating run is void; null report retained at {report_path}: " + "; ".join(reasons)
        )
    print(f"assembled valid 14-record rating at {report_path}")


def combine_reports(args: argparse.Namespace) -> None:
    first_path = Path(args.first).resolve()
    second_path = Path(args.second).resolve()
    output = Path(args.output).resolve()
    if output.exists():
        raise RatingError(f"combined output already exists: {output}")
    first = read_json(first_path)
    second = read_json(second_path)
    for path, report in ((first_path, first), (second_path, second)):
        if report.get("derivation_protocol") != DERIVATION_PROTOCOL:
            raise RatingError(f"report has the wrong derivation protocol: {path}")
        if not report.get("control_gate", {}).get("run_valid"):
            raise RatingError(f"cannot combine a void rating: {path}")
    families = [first["rater"]["model_family"], second["rater"]["model_family"]]
    if families[0].casefold() == families[1].casefold():
        raise RatingError("two-rater run requires two distinct model families")
    if first.get("artifact") != second.get("artifact"):
        raise RatingError("two raters did not use the same immutable artifact manifest")
    taste_gap = abs(first["product_taste_score"] - second["product_taste_score"])
    accessibility_scores = [first["accessibility"]["score"], second["accessibility"]["score"]]
    accessibility_gap = (
        abs(accessibility_scores[0] - accessibility_scores[1])
        if all(score is not None for score in accessibility_scores)
        else None
    )
    combined = {
        "schema_version": SCHEMA_VERSION,
        "derivation_protocol": DERIVATION_PROTOCOL,
        "artifact": first["artifact"],
        "model_families": families,
        "product_taste_score_gap": taste_gap,
        "accessibility_score_gap": accessibility_gap,
        "adjudication_required": taste_gap > 20 or (
            accessibility_gap is not None and accessibility_gap > 15
        ),
        "ratings": [first, second],
    }
    write_json_exclusive(output, combined)
    print(f"combined two independent model families at {output}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)

    prepare = commands.add_parser("prepare", help="validate an artifact and create 14 request plans")
    prepare.add_argument("artifact")
    prepare.add_argument("output")
    prepare.add_argument("--provider", choices=sorted(PROVIDER_ENDPOINTS), required=True)
    prepare.add_argument("--model", required=True)
    prepare.add_argument("--model-family", required=True)
    prepare.set_defaults(function=prepare_run)

    one = commands.add_parser("rate-one", help="make one fresh provider request using SECRET")
    one.add_argument("--run", required=True)
    one.add_argument("--index", type=int, required=True)
    one.set_defaults(function=rate_one)

    replacement = commands.add_parser(
        "replace", help="supersede one failed raw response with a fresh nonce"
    )
    replacement.add_argument("--run", required=True)
    replacement.add_argument("--index", type=int, required=True)
    replacement.set_defaults(function=replace_failed_request)

    assemble_command = commands.add_parser("assemble", help="mechanically assemble 14 records")
    assemble_command.add_argument("--run", required=True)
    assemble_command.set_defaults(function=assemble_run)

    combine = commands.add_parser("combine", help="combine two valid independent-family reports")
    combine.add_argument("--first", required=True)
    combine.add_argument("--second", required=True)
    combine.add_argument("--output", required=True)
    combine.set_defaults(function=combine_reports)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.function(args)
    except RatingError as error:
        print(f"DESIGN RATING ERROR {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
