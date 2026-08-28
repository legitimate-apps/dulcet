"""Fail-closed test evidence extracted from Xcode result bundles."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


XCRESULT_SCHEMA_VERSION = "0.1.0"
TERMINAL_RESULTS = {"Passed", "Failed", "Skipped", "Expected Failure", "unknown"}


class EvidenceError(ValueError):
    pass


@dataclass(frozen=True)
class TestCaseEvidence:
    identifier: str
    name: str
    result: str
    duration_seconds: float | None
    bundle_name: str | None
    suite_names: tuple[str, ...]


def junit_name(name: str) -> str:
    return name[:-2] if name.endswith("()") else name


def _required_string(node: dict, key: str, context: str) -> str:
    value = node.get(key)
    if not isinstance(value, str) or not value:
        raise EvidenceError(f"{context} must carry a non-empty {key}")
    return value


def parse_tests_document(document: object) -> list[TestCaseEvidence]:
    if not isinstance(document, dict):
        raise EvidenceError("xcresult tests output must be a JSON object")
    roots = document.get("testNodes")
    if not isinstance(roots, list):
        raise EvidenceError(
            "xcresult tests output must contain a testNodes array; a run summary is not proof"
        )

    cases: list[TestCaseEvidence] = []

    def visit(
        node: object,
        *,
        bundle_name: str | None,
        suite_names: tuple[str, ...],
    ) -> None:
        if not isinstance(node, dict):
            raise EvidenceError("every xcresult test node must be a JSON object")
        node_type = _required_string(node, "nodeType", "xcresult test node")
        name = _required_string(node, "name", f"xcresult {node_type} node")
        next_bundle = bundle_name
        next_suites = suite_names
        if node_type in {"Unit test bundle", "UI test bundle"}:
            next_bundle = name.removesuffix(".xctest")
        elif node_type == "Test Suite":
            next_suites = (*suite_names, name)
        elif node_type == "Test Case":
            identifier = _required_string(node, "nodeIdentifier", f"test case {name!r}")
            result = _required_string(node, "result", f"test case {identifier!r}")
            if result not in TERMINAL_RESULTS:
                raise EvidenceError(
                    f"test case {identifier!r} has unknown terminal result {result!r}"
                )
            duration = node.get("durationInSeconds")
            if duration is not None and (
                not isinstance(duration, (int, float)) or isinstance(duration, bool) or duration < 0
            ):
                raise EvidenceError(
                    f"test case {identifier!r} has invalid durationInSeconds {duration!r}"
                )
            cases.append(
                TestCaseEvidence(
                    identifier=identifier,
                    name=junit_name(name),
                    result=result,
                    duration_seconds=float(duration) if duration is not None else None,
                    bundle_name=next_bundle,
                    suite_names=next_suites,
                )
            )

        children = node.get("children", [])
        if not isinstance(children, list):
            raise EvidenceError(f"xcresult {node_type} node {name!r} has non-array children")
        for child in children:
            visit(child, bundle_name=next_bundle, suite_names=next_suites)

    for root in roots:
        visit(root, bundle_name=None, suite_names=())

    if not cases:
        raise EvidenceError("xcresult contains no individual Test Case results")
    identifiers = [case.identifier for case in cases]
    if len(identifiers) != len(set(identifiers)):
        duplicates = sorted(
            identifier for identifier in set(identifiers) if identifiers.count(identifier) > 1
        )
        raise EvidenceError(f"xcresult contains duplicate test identities: {duplicates}")
    return cases


def parse_summary_document(document: object, cases: list[TestCaseEvidence]) -> None:
    if not isinstance(document, dict):
        raise EvidenceError("xcresult summary output must be a JSON object")
    required_counts = {
        "totalTestCount": len(cases),
        "passedTests": sum(case.result == "Passed" for case in cases),
        "failedTests": sum(case.result in {"Failed", "unknown"} for case in cases),
        "skippedTests": sum(case.result == "Skipped" for case in cases),
        "expectedFailures": sum(case.result == "Expected Failure" for case in cases),
    }
    for field, individual_count in required_counts.items():
        summary_count = document.get(field)
        if not isinstance(summary_count, int) or isinstance(summary_count, bool):
            raise EvidenceError(f"xcresult summary must carry integer {field}")
        if summary_count != individual_count:
            raise EvidenceError(
                "xcresult summary does not match individual Test Case results: "
                f"{field}={summary_count}, individual={individual_count}"
            )


def _read_xcresult_json(bundle: Path, report: str) -> object:
    command = [
        "xcrun",
        "xcresulttool",
        "get",
        "test-results",
        report,
        "--schema-version",
        XCRESULT_SCHEMA_VERSION,
        "--path",
        str(bundle),
        "--compact",
    ]
    try:
        result = subprocess.run(
            command,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            env=os.environ.copy(),
        )
    except OSError as error:
        raise EvidenceError(f"cannot execute xcresulttool: {error}") from error
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise EvidenceError(f"xcresulttool failed with exit {result.returncode}: {detail}")
    try:
        document = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise EvidenceError(f"xcresulttool emitted invalid JSON: {error}") from error
    return document


def read_xcresult(bundle: Path) -> list[TestCaseEvidence]:
    if bundle.suffix != ".xcresult" or not bundle.is_dir():
        raise EvidenceError(f"expected an existing .xcresult bundle directory: {bundle}")
    # The tests tree is the evidence. The summary is only a cross-check that detects an individual
    # result omitted from that tree; it can never substitute for Test Case nodes.
    cases = parse_tests_document(_read_xcresult_json(bundle, "tests"))
    parse_summary_document(_read_xcresult_json(bundle, "summary"), cases)
    return cases


def require_all_passed(cases: list[TestCaseEvidence]) -> None:
    non_passing = [
        f"{case.identifier}={case.result}" for case in cases if case.result != "Passed"
    ]
    if non_passing:
        raise EvidenceError(f"xcresult contains non-passing individual results: {non_passing}")


def write_junit(
    cases: list[TestCaseEvidence],
    output: Path,
    class_name: str,
) -> None:
    require_all_passed(cases)
    names = [case.name for case in cases]
    if len(names) != len(set(names)):
        duplicates = sorted(name for name in set(names) if names.count(name) > 1)
        raise EvidenceError(
            f"xcresult identities collapse to duplicate JUnit names for {class_name}: {duplicates}"
        )
    suite = ET.Element(
        "testsuite",
        name=class_name,
        tests=str(len(cases)),
        failures="0",
        errors="0",
        skipped="0",
    )
    for case in sorted(cases, key=lambda value: value.name):
        attributes = {"classname": class_name, "name": case.name}
        if case.duration_seconds is not None:
            attributes["time"] = format(case.duration_seconds, ".9g")
        ET.SubElement(suite, "testcase", attributes)
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(output, encoding="utf-8", xml_declaration=True)


def require_exact_test(
    cases: list[TestCaseEvidence],
    expected_class: str,
    expected_method: str,
) -> TestCaseEvidence:
    if len(cases) != 1:
        observed = [(case.identifier, case.result) for case in cases]
        raise EvidenceError(
            f"expected exactly one individual test result, observed {len(cases)}: {observed}"
        )
    case = cases[0]
    expected_parts = expected_class.split(".")
    expected_bundle = expected_parts[0]
    expected_suite = expected_parts[-1]
    observed_class = ".".join(
        value for value in (case.bundle_name, *case.suite_names) if value
    )
    if case.bundle_name != expected_bundle or expected_suite not in case.suite_names:
        raise EvidenceError(
            f"wrong test class: expected {expected_class}, observed {observed_class}"
        )
    if case.name != junit_name(expected_method):
        raise EvidenceError(
            f"wrong test executed: expected {expected_class}/{expected_method}, "
            f"observed {observed_class}/{case.name}"
        )
    if case.result != "Passed":
        raise EvidenceError(f"named control terminated as {case.result}, not Passed")
    return case
