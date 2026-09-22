#!/usr/bin/env python3
"""Hold release.yml to the properties that make it safe to exist in a public repository.

1. It is triggered by workflow_dispatch and nothing else, so no push, tag or fork pull request
   can start it, and its inputs are exactly channel, platform and dry_run (dry_run a boolean
   defaulting to true).
2. Every job runs in the `release` environment (maintainer approval, protected branches only)
   on the standard hosted `macos-latest` label, never a larger, billed one.
3. Only release.yml may name the release secrets or the release environment, and it may name
   only the secrets that environment is documented to hold.
4. The upload step runs only when the resolved plan says upload, which only an exact
   dry_run=false produces.
5. PROD cannot carry a preconfigured server (spec §22.3): the PROD target declares nothing whose
   name mentions a server, and release.yml passes no server setting to any build.

Reads files relative to the current directory, so tools/test-release-channel can run it against
mutated copies. Exits 1 with every violation listed.
"""

from __future__ import annotations

from pathlib import Path
import re
import sys


RELEASE = Path(".github/workflows/release.yml")
RELEASE_SECRETS = {
    "DULCET_ASC_ISSUER_ID",
    "DULCET_ASC_KEY_ID",
    "DULCET_ASC_KEY_P8_BASE64",
    "DULCET_CI_INSTALLER_P12_BASE64",
    "DULCET_CI_INSTALLER_P12_PASSWORD",
    "DULCET_CI_SIGNING_P12_BASE64",
    "DULCET_CI_SIGNING_P12_PASSWORD",
    "DULCET_IOS_DEV_APP_STORE_PROFILE_BASE64",
    "DULCET_MAC_APP_STORE_PROFILE_BASE64",
    "DULCET_MAC_DEV_APP_STORE_PROFILE_BASE64",
    "DULCET_TVOS_DEV_APP_STORE_PROFILE_BASE64",
}


def code(line: str) -> str:
    """The line without a trailing comment (release.yml quotes no '#')."""
    return re.split(r"(?:^|\s)#", line, maxsplit=1)[0].rstrip()


def block(lines: list[str], header: str, indent: int) -> list[str]:
    """Lines nested under the first `header:` found at `indent` spaces."""
    for index, line in enumerate(lines):
        if code(line) == " " * indent + header + ":":
            body = []
            for nested in lines[index + 1:]:
                stripped = code(nested)
                if stripped and len(stripped) - len(stripped.lstrip()) <= indent:
                    break
                body.append(nested)
            return body
    return []


def keys_at(lines: list[str], indent: int) -> list[str]:
    found = []
    for line in lines:
        match = re.fullmatch(r"( *)([A-Za-z0-9_-]+):.*", code(line))
        if match and len(match.group(1)) == indent:
            found.append(match.group(2))
    return found


def check(errors: list[str]) -> None:
    if not RELEASE.is_file():
        errors.append(f"{RELEASE} is missing: the repository has no delivery channel")
        return
    text = RELEASE.read_text()
    lines = text.splitlines()

    triggers = keys_at(block(lines, "on", 0), 2)
    if triggers != ["workflow_dispatch"]:
        errors.append(f"{RELEASE}: must be triggered by workflow_dispatch only, found {triggers}")
    inputs = block(lines, "inputs", 4)
    names = keys_at(inputs, 6)
    if sorted(names) != ["channel", "dry_run", "platform"]:
        errors.append(f"{RELEASE}: dispatch inputs must be exactly channel, platform, dry_run; found {names}")
    dry_run = [code(line).strip() for line in block(inputs, "dry_run", 6)]
    if "type: boolean" not in dry_run or "default: true" not in dry_run:
        errors.append(f"{RELEASE}: dry_run must be a boolean input that defaults to true")

    jobs = block(lines, "jobs", 0)
    job_names = keys_at(jobs, 2)
    if not job_names:
        errors.append(f"{RELEASE}: no jobs")
    for job in job_names:
        properties = [code(line).strip() for line in block(jobs, job, 2)
                      if re.match(r"^    \S", code(line))]
        if "environment: release" not in properties:
            errors.append(f"{RELEASE}: job {job} must run in the release environment")
        if "runs-on: macos-latest" not in properties:
            errors.append(f"{RELEASE}: job {job} must run on exactly macos-latest")

    referenced = set(re.findall(r"secrets\.([A-Za-z_][A-Za-z0-9_]*)", text))
    unknown = sorted(referenced - RELEASE_SECRETS)
    if unknown:
        errors.append(f"{RELEASE}: references secrets the release environment does not hold: {unknown}")

    upload_steps = re.split(r"(?m)^      - ", text)
    uploads = [step for step in upload_steps if "app_store_connect.py upload" in step]
    if len(uploads) != 1 or not re.search(
            r"(?m)^        if: \$\{\{ steps\.plan\.outputs\.upload == 'true' \}\}$", uploads[0]):
        errors.append(f"{RELEASE}: exactly one upload step, guarded by steps.plan.outputs.upload == 'true'")

    if any(re.search("server", code(line), re.I) for line in lines):
        errors.append(f"{RELEASE}: passes a server setting; PROD must be unable to carry one")

    for workflow in sorted(Path(".github/workflows").glob("*.y*ml")):
        if workflow == RELEASE:
            continue
        other = workflow.read_text()
        leaked = sorted(set(re.findall(r"secrets\.(DULCET_[A-Za-z0-9_]*)", other)) & RELEASE_SECRETS)
        if leaked:
            errors.append(f"{workflow}: only release.yml may read the release secrets, found {leaked}")
        if re.search(r"(?m)^\s+environment:\s*(name:\s*)?release\s*$", other):
            errors.append(f"{workflow}: only release.yml may use the release environment")

    project = Path("apple/project.yml")
    if project.is_file():
        target = []
        inside = False
        for line in project.read_text().splitlines():
            if re.fullmatch(r"  [A-Za-z0-9_]+:\s*", line):
                inside = line.strip() == "DulcetMacRelease:"
                continue
            if line and not line.startswith(" "):
                inside = False
            if inside:
                target.append(code(line))
        if not target:
            errors.append(f"{project}: no DulcetMacRelease target")
        elif any(re.search("server", line, re.I) for line in target):
            errors.append(f"{project}: the PROD target DulcetMacRelease declares a server setting")


def main() -> int:
    errors: list[str] = []
    check(errors)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("release policy valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
