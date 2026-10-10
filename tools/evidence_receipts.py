"""Committed receipts for dispatch-only main runs that FEATURES.yml evidence may cite (spec §21.3.1).

A §21.3.1 evidence class runs only on a manual `workflow_dispatch` on main, so it can never be a
required pull-request status and `verify-parity-evidence` never runs inside a required job for it.
Its proof is instead recorded once, after an audited green run on main, as a receipt under
`evidence/receipts/<workflow>-<run_id>.json`. `tools/parity_gate.py` checks the receipt offline on
every pull request; `tools/verify-evidence-receipt` re-checks it against GitHub while the run's
artifact still exists.

This module is the one place that lists the dispatch-only evidence classes. Both tools import it.
"""

from __future__ import annotations

from pathlib import Path
import json
import re
import subprocess

REPOSITORY_SLUG = "legitimate-apps/dulcet"

# (workflow name, job id) -> §21.3.1 class. Only a job listed here may stand behind a receipt.
DISPATCH_ONLY_EVIDENCE_JOBS: dict[tuple[str, str], str] = {
    ("signed-mac-account-connect", "signed-account-connect"): "signed-entitled-host",
    ("signed-ipad-account-connect", "signed-ipad-account-connect"): "device-attached",
    ("signed-iphone-account-connect", "signed-iphone-account-connect"): "device-attached",
}

RECEIPT_KEYS = {
    "workflow", "job", "run_id", "run_url", "head_sha", "conclusion",
    "tests", "junit_sha256", "test_sources", "audit",
}
TEST_KEYS = {"name", "result"}
AUDIT_KEYS = {"tool", "result", "files_scanned", "tokens_checked"}
RECEIPT_PATH = re.compile(r"evidence/receipts/(?P<workflow>[a-z0-9][a-z0-9-]*)-(?P<run_id>[0-9]+)\.json")
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
RELATIVE_PATH = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_][A-Za-z0-9_.-]*)*")
TEST_IDENTITY = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_][A-Za-z0-9_]*")


class ReceiptError(ValueError):
    pass


def receipt_workflow(path_text: str) -> str | None:
    """The workflow a receipt path names, without reading the file (it may be gone in a base tree)."""
    match = RECEIPT_PATH.fullmatch(path_text)
    return match.group("workflow") if match else None


def _positive_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def load_receipt(path_text: str) -> dict:
    """Read one receipt and require its schema to be exact. Raises ReceiptError naming the defect."""
    match = RECEIPT_PATH.fullmatch(path_text)
    if not match:
        raise ReceiptError(
            f"evidence receipt path {path_text!r} must be evidence/receipts/<workflow>-<run_id>.json"
        )
    path = Path(path_text)
    if not path.is_file():
        raise ReceiptError(f"evidence receipt does not exist: {path_text}")
    try:
        receipt = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        raise ReceiptError(f"evidence receipt {path_text} is not JSON: {error}") from error

    def bad(detail: str) -> ReceiptError:
        return ReceiptError(f"evidence receipt {path_text} schema: {detail}")

    if not isinstance(receipt, dict) or set(receipt) != RECEIPT_KEYS:
        keys = sorted(receipt) if isinstance(receipt, dict) else type(receipt).__name__
        raise bad(f"requires exactly {sorted(RECEIPT_KEYS)}, found {keys}")
    for key in ("workflow", "job", "run_id", "run_url", "head_sha", "conclusion", "junit_sha256"):
        if not isinstance(receipt[key], str) or not receipt[key]:
            raise bad(f"{key} must be a non-empty string")
    if receipt["workflow"] != match.group("workflow") or receipt["run_id"] != match.group("run_id"):
        raise bad("workflow and run_id must match the file name")
    expected_url = f"https://github.com/{REPOSITORY_SLUG}/actions/runs/{receipt['run_id']}"
    if receipt["run_url"] != expected_url:
        raise bad(f"run_url must be {expected_url}")
    if not HEX40.fullmatch(receipt["head_sha"]):
        raise bad("head_sha must be a full 40-hex commit")
    if receipt["conclusion"] != "success":
        raise bad(f"conclusion must be success, found {receipt['conclusion']!r}")
    if not HEX64.fullmatch(receipt["junit_sha256"]):
        raise bad("junit_sha256 must be a 64-hex SHA-256 of the uploaded JUnit file")

    tests = receipt["tests"]
    if not isinstance(tests, list) or not tests:
        raise bad("tests must be a non-empty list")
    names: list[str] = []
    for test in tests:
        if not isinstance(test, dict) or set(test) != TEST_KEYS:
            raise bad(f"each test requires exactly {sorted(TEST_KEYS)}")
        if not isinstance(test["name"], str) or not TEST_IDENTITY.fullmatch(test["name"]):
            raise bad(f"test name {test['name']!r} must be <Class>/<method>")
        if test["result"] != "passed":
            raise bad(f"test {test['name']} result must be passed, found {test['result']!r}")
        names.append(test["name"])
    if len(names) != len(set(names)):
        raise bad("tests must not repeat a name")

    sources = receipt["test_sources"]
    if not isinstance(sources, dict) or not sources:
        raise bad("test_sources must map each test source path to its git blob SHA")
    for source, blob in sources.items():
        if not RELATIVE_PATH.fullmatch(source) or ".." in source.split("/"):
            raise bad(f"test_sources path {source!r} must be repository-relative")
        if not isinstance(blob, str) or not HEX40.fullmatch(blob):
            raise bad(f"test_sources {source} must be a 40-hex blob SHA")

    audit = receipt["audit"]
    if not isinstance(audit, dict) or set(audit) != AUDIT_KEYS:
        raise bad(f"audit requires exactly {sorted(AUDIT_KEYS)}")
    if not isinstance(audit["tool"], str) or not RELATIVE_PATH.fullmatch(audit["tool"]):
        raise bad("audit tool must be a repository-relative path")
    if audit["result"] != "pass":
        raise bad(f"audit result must be pass, found {audit['result']!r}")
    if not _positive_int(audit["files_scanned"]) or not _positive_int(audit["tokens_checked"]):
        raise bad("audit files_scanned and tokens_checked must be positive integers")
    return receipt


def git(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *arguments], text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False,
    )


def working_tree_blob(path_text: str) -> str | None:
    """The blob SHA git would record for this file now, or None if it does not exist."""
    if not Path(path_text).is_file():
        return None
    result = git("hash-object", "--", path_text)
    return result.stdout.strip() if result.returncode == 0 else None


def check_receipt_in_tree(path_text: str, receipt: dict, test: str) -> None:
    """The offline checks the parity gate makes for one cited receipt row.

    Raises ReceiptError. The caller checks that the cited test name exists in the tree.
    """
    identity = (receipt["workflow"], receipt["job"])
    if identity not in DISPATCH_ONLY_EVIDENCE_JOBS:
        raise ReceiptError(
            f"evidence receipt {path_text} job {identity[0]}/{identity[1]} is not a §21.3.1 "
            f"dispatch-only evidence class; known: {sorted('/'.join(i) for i in DISPATCH_ONLY_EVIDENCE_JOBS)}"
        )
    if not any(entry["name"] == test and entry["result"] == "passed" for entry in receipt["tests"]):
        raise ReceiptError(f"evidence receipt {path_text} does not record {test} as passed")
    method = test.split("/")[-1]
    defining = [
        source for source in receipt["test_sources"]
        if Path(source).is_file()
        and re.search(rf"\b(?:fun|func|void)\s+{re.escape(method)}\b", Path(source).read_text(errors="ignore"))
    ]
    for source, recorded in sorted(receipt["test_sources"].items()):
        current = working_tree_blob(source)
        if current != recorded:
            now = "it no longer exists" if current is None else f"now {current}"
            raise ReceiptError(
                f"evidence receipt {path_text} is stale: {source} changed since run "
                f"{receipt['run_id']} (recorded blob {recorded}, {now}); re-run {receipt['workflow']} "
                "on main and commit a new receipt"
            )
    if not defining:
        raise ReceiptError(
            f"evidence receipt {path_text}: {test} is not defined in any of its test_sources"
        )
    head = receipt["head_sha"]
    if git("cat-file", "-e", f"{head}^{{commit}}").returncode != 0:
        if git("rev-parse", "--is-shallow-repository").stdout.strip() == "true":
            raise ReceiptError(
                f"evidence receipt {path_text}: head_sha {head} is not in this shallow clone; "
                "fetch the required history (parity-gate checks out with fetch-depth: 0)"
            )
        raise ReceiptError(f"evidence receipt {path_text}: head_sha {head} is not in this repository")
    if git("merge-base", "--is-ancestor", head, "HEAD").returncode != 0:
        raise ReceiptError(f"evidence receipt {path_text}: head_sha {head} is not reachable from HEAD")
