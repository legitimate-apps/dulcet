#!/usr/bin/env python3
"""Hold release.yml to the properties that make it safe to exist in a public repository.

1. It is triggered by workflow_dispatch and nothing else, so no push, tag or fork pull request
   can start it, and its inputs are exactly channel, platform and dry_run (dry_run a boolean
   defaulting to true).
2. Every job runs in the `release` environment (maintainer approval, protected branches only)
   on the standard hosted `macos-latest` label, never a larger, billed one.
3. Only release.yml may name the release secrets or the release environment (in any YAML
   spelling), and it may name only the secrets that environment is documented to hold.
4. Every upload mechanism appears in exactly one step, guarded by the resolved plan's upload
   decision, which only an exact dry_run=false produces. No other workflow and no archive script
   contains one, and the export writes a package rather than uploading it.
5. PROD has no configuration route for a preconfigured server (spec §22.3). Its Info.plist sits in
   a directory only the PROD target reads and must hold exactly the allowlisted keys; its target
   settings and the project-level settings it inherits are allowlisted by NAME, so a server
   setting cannot hide behind an innocent one; no value on that path may hold a URL; and the
   archive passes no build setting but the build number. What this cannot see is a URL literal
   compiled into Swift shared by both channels -- that is review's job, not this gate's.

Reads files relative to the current directory, so tools/test-release-channel can run it against
mutated copies. Exits 1 with every violation listed.
"""

from __future__ import annotations

from pathlib import Path
import plistlib
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
    "DULCET_DEV_IOS_APP_STORE_PROFILE_BASE64",
    "DULCET_DEV_TVOS_APP_STORE_PROFILE_BASE64",
    "DULCET_MAC_APP_STORE_PROFILE_BASE64",
    "DULCET_MAC_DEV_APP_STORE_PROFILE_BASE64",
}
# Anything that can move a build to App Store Connect. `pilot` and `upload_to_testflight` are the
# fastlane spellings; `destination` upload is the exportArchive one.
UPLOAD_MECHANISMS = re.compile(
    r"altool|upload-package|upload-app|iTMSTransporter|Transporter|app_store_connect\.py upload"
    r"|fastlane|upload_to_testflight|\bpilot\b|['\"]?destination['\"]?\s*[:=]\s*['\"]?upload",
    re.I,
)
ARCHIVE_SCRIPT = Path("tools/release/archive-and-export")
PROD_PLIST = Path("apple/DulcetMacRelease/Info.plist")
PROD_INFO_KEYS = {
    "CFBundleDevelopmentRegion", "CFBundleDisplayName", "CFBundleExecutable", "CFBundleIconFile",
    "CFBundleIconName", "CFBundleIdentifier", "CFBundleInfoDictionaryVersion", "CFBundleName",
    "CFBundlePackageType", "CFBundleShortVersionString", "CFBundleVersion",
    "ITSAppUsesNonExemptEncryption", "LSApplicationCategoryType", "LSMinimumSystemVersion",
    "NSHumanReadableCopyright", "NSLocalNetworkUsageDescription",
}
PROD_TARGET_SETTINGS = {
    "OTHER_LDFLAGS", "PRODUCT_BUNDLE_IDENTIFIER", "PRODUCT_MODULE_NAME", "PRODUCT_NAME",
    "CODE_SIGN_ENTITLEMENTS", "ASSETCATALOG_COMPILER_APPICON_NAME", "ENABLE_HARDENED_RUNTIME",
    "CODE_SIGN_STYLE", "CODE_SIGN_IDENTITY", "PROVISIONING_PROFILE_SPECIFIER",
    "CURRENT_PROJECT_VERSION",
}
PROJECT_SETTINGS = {
    "SWIFT_VERSION", "ARCHS", "ENABLE_USER_SCRIPT_SANDBOXING", "GENERATE_INFOPLIST_FILE",
    "CODE_SIGN_STYLE", "DEVELOPMENT_TEAM", "FRAMEWORK_SEARCH_PATHS", "OTHER_LDFLAGS",
    "MARKETING_VERSION",
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

    steps = re.split(r"(?m)^      - ", "\n".join(code(line) for line in lines))
    uploads = [step for step in steps if UPLOAD_MECHANISMS.search(step)]
    if len(uploads) != 1 or not re.search(
            r"(?m)^        if: \$\{\{ steps\.plan\.outputs\.upload == 'true' \}\}$", uploads[0]):
        errors.append(f"{RELEASE}: exactly one upload step, guarded by steps.plan.outputs.upload == 'true'; "
                      f"found {len(uploads)} step(s) with an upload mechanism")

    if any(re.search(r"server|://|-xcconfig|INFOPLIST_KEY_", code(line), re.I) for line in lines):
        errors.append(f"{RELEASE}: passes a server, URL or plist setting; PROD must be unable to carry one")

    if not ARCHIVE_SCRIPT.is_file():
        errors.append(f"{ARCHIVE_SCRIPT} is missing")
    else:
        script = "\n".join(code(line) for line in ARCHIVE_SCRIPT.read_text().splitlines())
        if UPLOAD_MECHANISMS.search(script):
            errors.append(f"{ARCHIVE_SCRIPT}: contains an upload mechanism; only release.yml's guarded step may upload")
        if '"destination": "export"' not in script:
            errors.append(f"{ARCHIVE_SCRIPT}: the export must write a package (destination export)")
        overrides = set(re.findall(r"(?m)^\s+([A-Z][A-Z0-9_]*)=", script))
        if overrides != {"CURRENT_PROJECT_VERSION"} or "-xcconfig" in script:
            errors.append(f"{ARCHIVE_SCRIPT}: the archive may override CURRENT_PROJECT_VERSION only, found {sorted(overrides)}")

    for workflow in sorted(Path(".github/workflows").glob("*.y*ml")):
        if workflow == RELEASE:
            continue
        other = workflow.read_text()
        leaked = sorted(set(re.findall(r"secrets\.(DULCET_[A-Za-z0-9_]*)", other)) & RELEASE_SECRETS)
        if leaked:
            errors.append(f"{workflow}: only release.yml may read the release secrets, found {leaked}")
        if re.search(r"(?m)^\s+environment:\s*(?:\n\s+)?(?:name:\s*)?['\"]?release['\"]?\s*$", other) \
                or re.search(r"environment:\s*\{[^}]*name:\s*['\"]?release['\"]?", other):
            errors.append(f"{workflow}: only release.yml may use the release environment")
        if UPLOAD_MECHANISMS.search("\n".join(code(line) for line in other.splitlines())):
            errors.append(f"{workflow}: contains an upload mechanism; only release.yml may upload")

    check_prod_configuration(errors)


def yaml_block(lines: list[str], path: list[str]) -> list[str] | None:
    """Lines under a nested key path of block-style YAML, by indentation (no dependency)."""
    body = lines
    indents = [len(code(line)) - len(code(line).lstrip()) for line in body if code(line).strip()]
    indent = min(indents, default=0)
    for key in path:
        found = None
        for index, line in enumerate(body):
            if code(line) == " " * indent + key + ":" or code(line).startswith(" " * indent + key + ": "):
                found = index
                break
        if found is None:
            return None
        nested = []
        for line in body[found + 1:]:
            stripped = code(line)
            if stripped and len(stripped) - len(stripped.lstrip()) <= indent:
                break
            nested.append(line)
        body = nested
        indents = [len(code(line)) - len(code(line).lstrip()) for line in body if code(line).strip()]
        indent = min(indents, default=indent + 2)
    return body


def mapping(lines: list[str]) -> dict[str, str]:
    entries = [re.fullmatch(r"( *)([A-Za-z0-9_]+):\s*(.*)", code(line)) for line in lines]
    entries = [entry for entry in entries if entry]
    if not entries:
        return {}
    indent = min(len(entry.group(1)) for entry in entries)
    return {entry.group(2): entry.group(3).strip('"\'') for entry in entries if len(entry.group(1)) == indent}


def check_prod_configuration(errors: list[str]) -> None:
    project = Path("apple/project.yml")
    if not project.is_file():
        errors.append(f"{project} is missing")
        return
    lines = project.read_text().splitlines()

    inherited = mapping(yaml_block(lines, ["settings", "base"]) or [])
    if yaml_block(lines, ["settings", "configs"]) is not None:
        errors.append(f"{project}: project-level configs would reach PROD unreviewed; use settings.base")
    for name in sorted(set(inherited) - PROJECT_SETTINGS):
        errors.append(f"{project}: project-level setting {name} is not allowlisted, and PROD inherits it")

    target = yaml_block(lines, ["targets", "DulcetMacRelease"])
    if target is None:
        errors.append(f"{project}: no DulcetMacRelease target")
        return
    settings = yaml_block(target, ["settings"]) or []
    own = mapping(yaml_block(settings, ["base"]) or [])
    if set(mapping(settings)) - {"base"}:
        errors.append(f"{project}: DulcetMacRelease may declare settings.base only")
    for name in sorted(set(own) - PROD_TARGET_SETTINGS):
        errors.append(f"{project}: PROD target DulcetMacRelease declares {name}, which is not allowlisted")
    for name, value in {**inherited, **own}.items():
        if "://" in value:
            errors.append(f"{project}: {name} carries a URL on the PROD configuration path")
    info = mapping(yaml_block(target, ["info"]) or [])
    if info.get("path") != "DulcetMacRelease/Info.plist":
        errors.append(f"{project}: DulcetMacRelease must use its own DulcetMacRelease/Info.plist")
    sources = [code(line).strip() for line in yaml_block(target, ["sources"]) or []]
    if any("DulcetMacRelease" in source for source in sources):
        errors.append(f"{project}: the PROD plist directory must not be a source folder")
    for other, other_lines in ((name, yaml_block(lines, ["targets", name]) or [])
                               for name in re.findall(r"(?m)^  ([A-Za-z0-9_]+):\s*$", "\n".join(
                                   yaml_block(lines, ["targets"]) or []))):
        if other != "DulcetMacRelease" and any("DulcetMacRelease" in code(line) for line in other_lines):
            errors.append(f"{project}: target {other} reads the PROD-only DulcetMacRelease directory")

    if not PROD_PLIST.is_file():
        errors.append(f"{PROD_PLIST} is missing")
        return
    try:
        document = plistlib.loads(PROD_PLIST.read_bytes())
    except Exception as error:  # noqa: BLE001 - any parse failure is a policy failure
        errors.append(f"{PROD_PLIST}: unreadable: {error}")
        return
    keys = set(document)
    if keys != PROD_INFO_KEYS:
        errors.append(f"{PROD_PLIST}: keys must be exactly the allowlist; extra {sorted(keys - PROD_INFO_KEYS)}, "
                      f"missing {sorted(PROD_INFO_KEYS - keys)}")
    for key, value in document.items():
        if isinstance(value, str) and "://" in value:
            errors.append(f"{PROD_PLIST}: {key} holds a URL")


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
