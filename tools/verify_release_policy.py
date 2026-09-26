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
5. PROD has no configuration route for a preconfigured server (spec §22.3, §22.6), checked on the
   committed Xcode project as well as on apple/project.yml, because Xcode builds the former:
   - project.yml uses only the shape this reader understands (no configFiles, settingGroups,
     templates or include; no quoted keys, flow mappings, anchors or merge keys);
   - no build configuration is based on an xcconfig; project-level and PROD settings are allowlisted
     by name, linker flags by value, and no value on PROD's path holds a URL;
   - each PROD target (DulcetMacRelease, DulcetiOSRelease) reads its own plist from a directory only
     it reads, held to an exact key set, and the committed settings equal what project.yml declares;
   - each PROD target and its DEV twin build the same files, packages and dependencies, and differ in
     Release settings, declared Info.plist and entitlements only by §22.3's channel list;
   - PROD schemes carry no pre- or post-action and no element outside SCHEME_ELEMENTS; PROD targets
     carry no target key outside TARGET_OBJECT_KEYS, and twins resolve synchronized folders and
     packages to the same things (a package by its location and requirement, not its product name);
   - no script phase reads a variable outside SCRIPT_VARIABLES, so no script can branch on the channel,
     and release.yml passes only the environment names in RELEASE_ENV;
   - the server key appears only in a DEV-only plist, and the archive script runs
     tools/release/validate-app-bundle, which re-checks the built PROD bundle itself, including every
     http(s) host it contains.
   Every generated file is also required to be byte-for-byte what the pinned XcodeGen produces from
   project.yml (tools/verify_xcodegen_regeneration, run in apple-ci), which closes the class of hand
   edits to generated files as a whole; the direct checks here say which rule a route broke.
   What this cannot see is a server address compiled into code shared by both channels under another
   name -- that is review's job, not this gate's.

Reads files relative to the current directory, so tools/test-release-channel can run it against
mutated copies. Exits 1 with every violation listed. Anything it cannot read fails closed.

Every allowlist below is a spec §22.3 decision. A refusal names the constant to extend; extending it
is a reviewed change to this file, never a workaround elsewhere.
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
    # The PROD iOS profile. Optional in the signing wrapper, so its absence breaks no other plan;
    # it must be set in the environment before the first prod/ios dispatch.
    "DULCET_IOS_APP_STORE_PROFILE_BASE64",
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
# The artifact check: icons per platform, and PROD's plist allowlist and server-key scan.
VALIDATOR = Path("tools/release/validate-app-bundle")
# Xcode fills these into every plist XcodeGen writes; they name the bundle, never a server.
BUNDLE_KEYS = {
    "CFBundleDevelopmentRegion", "CFBundleDisplayName", "CFBundleExecutable", "CFBundleIdentifier",
    "CFBundleInfoDictionaryVersion", "CFBundleName", "CFBundlePackageType",
    "CFBundleShortVersionString", "CFBundleVersion", "ITSAppUsesNonExemptEncryption",
    "NSLocalNetworkUsageDescription",
}
SIGNING_SETTINGS = {
    "OTHER_LDFLAGS", "PRODUCT_BUNDLE_IDENTIFIER", "PRODUCT_NAME", "ASSETCATALOG_COMPILER_APPICON_NAME",
    "CODE_SIGN_STYLE", "CODE_SIGN_IDENTITY", "PROVISIONING_PROFILE_SPECIFIER",
}
PROJECT_YML = Path("apple/project.yml")
# What Xcode actually builds. project.yml is only its source, so every rule below is enforced on the
# committed project too, and the two are required to agree on the settings that matter here.
PBXPROJ = Path("apple/Dulcet.xcodeproj/project.pbxproj")
# The one sanctioned spelling of a preconfigured server (spec §22.3, §22.6): an Info.plist key set in
# a DEV-only plist. Nothing else under apple/ may contain these bytes, the DEV/PROD parity rule
# exempts exactly this key, and tools/release/validate-app-bundle fails any PROD bundle containing
# them. No build carries one today.
SERVER_KEY = "DulcetPreconfiguredServer"
# Every PROD target: its own plist, in a directory no other target reads, held to an exact key set,
# its own settings allowlisted by name, and a DEV twin it must match in everything but §22.3's list.
PROD_TARGETS = {
    "DulcetMacRelease": {
        "dev": "DulcetMac",
        "plist": Path("apple/DulcetMacRelease/Info.plist"),
        "keys": BUNDLE_KEYS | {
            "CFBundleIconFile", "CFBundleIconName", "LSApplicationCategoryType",
            "LSMinimumSystemVersion", "NSHumanReadableCopyright",
        },
        "settings": SIGNING_SETTINGS | {
            "PRODUCT_MODULE_NAME", "CODE_SIGN_ENTITLEMENTS", "ENABLE_HARDENED_RUNTIME",
            "CURRENT_PROJECT_VERSION",
        },
    },
    "DulcetiOSRelease": {
        "dev": "DulcetiOS",
        "plist": Path("apple/DulcetiOSRelease/Info.plist"),
        "keys": BUNDLE_KEYS | {
            "LSRequiresIPhoneOS", "UIBackgroundModes", "UILaunchScreen",
            "UISupportedInterfaceOrientations~iphone", "UISupportedInterfaceOrientations~ipad",
            "UTExportedTypeDeclarations",
        },
        "settings": SIGNING_SETTINGS | {"TARGETED_DEVICE_FAMILY"},
    },
}
PROJECT_SETTINGS = {
    "SWIFT_VERSION", "ARCHS", "ENABLE_USER_SCRIPT_SANDBOXING", "GENERATE_INFOPLIST_FILE",
    "CODE_SIGN_STYLE", "DEVELOPMENT_TEAM", "FRAMEWORK_SEARCH_PATHS", "OTHER_LDFLAGS",
    "MARKETING_VERSION",
}
# The project.yml shape this policy can read. Everything else XcodeGen accepts -- configFiles,
# settingGroups, targetTemplates, include, per-target templates -- can put settings into a build
# without naming them where this policy looks, so it is refused rather than parsed.
TOP_LEVEL_KEYS = {"name", "options", "settings", "packages", "targets", "schemes"}
OPTIONS_KEYS = {"bundleIdPrefix", "deploymentTarget"}
TARGET_KEYS = {"type", "platform", "sources", "dependencies", "settings", "info", "preBuildScripts"}
# Settings XcodeGen writes into a target configuration from its type, platform and `info:`.
XCODEGEN_TARGET_SETTINGS = {"INFOPLIST_FILE", "LD_RUNPATH_SEARCH_PATHS", "SDKROOT", "COMBINE_HIDPI_IMAGES"}
# ...and the ones its platform presets fill in when a target leaves them undeclared (an iOS Debug
# configuration gets an "iPhone Developer" signing identity).
XCODEGEN_TARGET_DEFAULTS = XCODEGEN_TARGET_SETTINGS | {"CODE_SIGN_IDENTITY"}
# XcodeGen 2.46.0's default project presets (OBSERVED in the committed project) and the deployment
# targets `options.deploymentTarget` writes, per configuration. A project-level name outside these
# reaches PROD, so it has to be added here, in review, first.
_XCODEGEN_PROJECT_COMMON = {
    "ALWAYS_SEARCH_USER_PATHS", "CLANG_ANALYZER_NONNULL", "CLANG_ANALYZER_NUMBER_OBJECT_CONVERSION",
    "CLANG_CXX_LANGUAGE_STANDARD", "CLANG_CXX_LIBRARY", "CLANG_ENABLE_MODULES", "CLANG_ENABLE_OBJC_ARC",
    "CLANG_ENABLE_OBJC_WEAK", "CLANG_WARN_BLOCK_CAPTURE_AUTORELEASING", "CLANG_WARN_BOOL_CONVERSION",
    "CLANG_WARN_COMMA", "CLANG_WARN_CONSTANT_CONVERSION", "CLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS",
    "CLANG_WARN_DIRECT_OBJC_ISA_USAGE", "CLANG_WARN_DOCUMENTATION_COMMENTS", "CLANG_WARN_EMPTY_BODY",
    "CLANG_WARN_ENUM_CONVERSION", "CLANG_WARN_INFINITE_RECURSION", "CLANG_WARN_INT_CONVERSION",
    "CLANG_WARN_NON_LITERAL_NULL_CONVERSION", "CLANG_WARN_OBJC_IMPLICIT_RETAIN_SELF",
    "CLANG_WARN_OBJC_LITERAL_CONVERSION", "CLANG_WARN_OBJC_ROOT_CLASS",
    "CLANG_WARN_QUOTED_INCLUDE_IN_FRAMEWORK_HEADER", "CLANG_WARN_RANGE_LOOP_ANALYSIS",
    "CLANG_WARN_STRICT_PROTOTYPES", "CLANG_WARN_SUSPICIOUS_MOVE", "CLANG_WARN_UNGUARDED_AVAILABILITY",
    "CLANG_WARN_UNREACHABLE_CODE", "CLANG_WARN__DUPLICATE_METHOD_MATCH", "COPY_PHASE_STRIP",
    "DEBUG_INFORMATION_FORMAT", "ENABLE_STRICT_OBJC_MSGSEND", "GCC_C_LANGUAGE_STANDARD",
    "GCC_NO_COMMON_BLOCKS", "GCC_WARN_64_TO_32_BIT_CONVERSION", "GCC_WARN_ABOUT_RETURN_TYPE",
    "GCC_WARN_UNDECLARED_SELECTOR", "GCC_WARN_UNINITIALIZED_AUTOS", "GCC_WARN_UNUSED_FUNCTION",
    "GCC_WARN_UNUSED_VARIABLE", "IPHONEOS_DEPLOYMENT_TARGET", "MACOSX_DEPLOYMENT_TARGET",
    "MTL_ENABLE_DEBUG_INFO", "MTL_FAST_MATH", "PRODUCT_NAME", "SWIFT_OPTIMIZATION_LEVEL",
    "TVOS_DEPLOYMENT_TARGET",
} | PROJECT_SETTINGS
XCODEGEN_PROJECT_SETTINGS = {
    "Debug": _XCODEGEN_PROJECT_COMMON | {
        "ENABLE_TESTABILITY", "GCC_DYNAMIC_NO_PIC", "GCC_OPTIMIZATION_LEVEL",
        "GCC_PREPROCESSOR_DEFINITIONS", "ONLY_ACTIVE_ARCH", "SWIFT_ACTIVE_COMPILATION_CONDITIONS",
    },
    "Release": _XCODEGEN_PROJECT_COMMON | {"ENABLE_NS_ASSERTIONS", "SWIFT_COMPILATION_MODE"},
}
# A linker flag can read a file into the binary (-sectcreate, @file), so the allowlisted linker
# settings are held to their exact values rather than their names.
PINNED_PROJECT_SETTINGS = {"OTHER_LDFLAGS": "$(inherited) -framework DulcetCore"}
PINNED_TARGET_SETTINGS = {"OTHER_LDFLAGS": "$(inherited) -Wl,-export_dynamic"}
# The build settings that may differ between a DEV target and its PROD twin: spec §22.3's list
# (identity, name, icon) plus what signing and the plist route need, and the build number the
# archive overrides anyway. Every other Release setting must be identical. INFOPLIST_KEY_* settings
# are compared as the plist keys they produce, below.
CHANNEL_SETTINGS = {
    "PRODUCT_BUNDLE_IDENTIFIER", "PRODUCT_NAME", "ASSETCATALOG_COMPILER_APPICON_NAME",
    "PROVISIONING_PROFILE_SPECIFIER", "INFOPLIST_FILE", "CODE_SIGN_ENTITLEMENTS",
    "CURRENT_PROJECT_VERSION",
}
# The Info.plist keys that may differ: name, icon, and the DEV-only server key.
CHANNEL_PLIST_KEYS = {"CFBundleDisplayName", "CFBundleIconFile", "CFBundleIconName", SERVER_KEY}
# Keys Xcode generates for a target whose plist it generates, which a hand-written PROD plist
# therefore spells out as build-setting references; neither side can carry a value in them.
IMPLICIT_PLIST_KEYS = {
    "CFBundleDevelopmentRegion", "CFBundleExecutable", "CFBundleIdentifier",
    "CFBundleInfoDictionaryVersion", "CFBundleName", "CFBundlePackageType",
    "CFBundleShortVersionString", "CFBundleVersion", "LSRequiresIPhoneOS",
}
# The keys a PROD target or its DEV twin may carry: what XcodeGen 2.46.0 writes, each compared between
# the twins below (fileSystemSynchronizedGroups by folder and by the exceptions that apply to each). A
# key outside this set is a way to add inputs this policy does not compare.
TARGET_OBJECT_KEYS = {
    "isa", "buildConfigurationList", "buildPhases", "buildRules", "dependencies", "name",
    "packageProductDependencies", "productName", "productReference", "productType",
    "fileSystemSynchronizedGroups",
}
# The elements a PROD scheme may contain, as XcodeGen writes them. PreActions and PostActions run a
# shell script inside the build with the target's settings in its environment, so they are refused by
# name as well as by absence from this list.
SCHEME_ELEMENTS = {
    "Scheme", "BuildAction", "BuildActionEntries", "BuildActionEntry", "BuildableReference",
    "TestAction", "MacroExpansion", "Testables", "LaunchAction", "BuildableProductRunnable",
    "ProfileAction", "AnalyzeAction", "ArchiveAction",
}
# The only variables a build-phase script may read. Every target runs the same script, so a script that
# reads the bundle identifier, configuration, product or target name, a plist setting, or anything
# from the build machine's environment could write something into PROD alone. Those names can never be
# added here; a new neutral one is a reviewed §22.3 change.
SCRIPT_VARIABLES = {"OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED", "SRCROOT"}
CHANNEL_VARIABLES = re.compile(
    r"PRODUCT_BUNDLE_IDENTIFIER|CONFIGURATION|PRODUCT_NAME|PRODUCT_MODULE_NAME|TARGET_NAME|INFOPLIST_"
    r"|DEV|PROD|RELEASE|DEBUG|CHANNEL|SERVER|ENDPOINT|URL")
# Commands that read the whole environment at once, which a variable allowlist cannot see through.
ENVIRONMENT_DUMPS = re.compile(r"\bprintenv\b|\benviron\b|\bENVIRON\b|(?:^|[;&|(\s])env(?:\s|$)|\bexport\s+-p\b"
                               r"|\bdeclare\s+-[a-z]*p|\bcompgen\b|(?:^|[;&|]\s*)set\s*(?:$|[;&|])", re.M)
# Every environment name release.yml may set, on the job or on a step. An allowlist rather than a list
# of suspicious spellings: `DULCET_LOCAL_ENDPOINT: ${{ vars.X }}` names no server and holds one.
RELEASE_ENV = {
    "TZ", "HOMEBREW_NO_AUTO_UPDATE", "RELEASE_CHANNEL", "RELEASE_PLATFORM", "RELEASE_DRY_RUN",
    "RELEASE_REF", "GITHUB_TOKEN", "BUNDLE_ID", "FAMILY", "UPLOAD", "RELEASE_SCHEME",
    "RELEASE_BUNDLE_ID", "RELEASE_PROFILE_NAME", "RELEASE_DESTINATION", "RELEASE_PACKAGE_KIND",
    "RELEASE_MARKETING_VERSION", "RELEASE_INTERNAL_ONLY", "RELEASE_BUILD_NUMBER", "PACKAGE", "APP_ID",
    "BUILD_NUMBER", "CHANNEL", "PLATFORM", "VERSION", "APP_RECORD", "OUTCOME",
    # exported inside run blocks
    "RELEASE_TAGS_AT_HEAD", "RELEASE_ROOT", "RELEASE_DERIVED_DATA",
} | RELEASE_SECRETS
POLICY_FILE = "tools/verify_release_policy.py"


def extend(constant: str) -> str:
    """The remedy every allowlist refusal carries: where the list lives and whose decision it is."""
    return (f" [allowlist: {constant} in {POLICY_FILE}; adding to it is a spec §22.3 decision made in "
            "review, never a workaround elsewhere]")


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

    # The environment reaches xcodebuild and every script phase, so release.yml may set only known
    # names, by `env:` or by `export`, and may not set any through $GITHUB_ENV, $GITHUB_PATH or `vars`.
    for name in sorted(set(release_env_names(lines)) - RELEASE_ENV):
        errors.append(f"{RELEASE}: sets environment variable {name}, which is not allowlisted; the environment "
                      "reaches the archive and every script phase" + extend("RELEASE_ENV"))
    for pattern, what in ((r"\bGITHUB_ENV\b", "$GITHUB_ENV"), (r"\bGITHUB_PATH\b", "$GITHUB_PATH"),
                          (r"\$\{\{[^}]*\bvars\.", "a repository variable (vars.)")):
        if any(re.search(pattern, code(line)) for line in lines):
            errors.append(f"{RELEASE}: uses {what}, which can hand the build a value no review sees; "
                          "PROD must be unable to carry a server (spec §22.3)")
    if any(re.search(r"://|-xcconfig|INFOPLIST_KEY_", code(line), re.I) for line in lines):
        errors.append(f"{RELEASE}: passes a URL, an xcconfig or a plist setting; PROD must be unable to carry a "
                      "server (spec §22.3)")

    if not ARCHIVE_SCRIPT.is_file():
        errors.append(f"{ARCHIVE_SCRIPT} is missing")
    else:
        script = "\n".join(code(line) for line in ARCHIVE_SCRIPT.read_text().splitlines())
        if UPLOAD_MECHANISMS.search(script):
            errors.append(f"{ARCHIVE_SCRIPT}: contains an upload mechanism; only release.yml's guarded step may upload")
        if '"destination": "export"' not in script:
            errors.append(f"{ARCHIVE_SCRIPT}: the export must write a package (destination export)")
        if not re.search(r'(?m)^/usr/bin/python3 tools/release/validate-app-bundle "\$RELEASE_PLATFORM" '
                         r'"\$RELEASE_CHANNEL" "\$app" \\\n\s+\|\| die ', script) \
                or not VALIDATOR.is_file():
            errors.append(f"{ARCHIVE_SCRIPT}: must run {VALIDATOR} on the archived app and die when it fails")
        if not re.search(r"xcodebuild archive \\\n(?:[^\n]*\\\n)*?\s+-configuration Release \\\n", script):
            errors.append(f"{ARCHIVE_SCRIPT}: the archive must pass -configuration Release explicitly")
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


def release_env_names(lines: list[str]) -> list[str]:
    """Every name release.yml puts into an environment: keys of any `env:` mapping, and `export NAME`."""
    names = []
    for index, line in enumerate(lines):
        match = re.fullmatch(r"( *)(?:- )?env:", code(line))
        if match:
            indent = len(match.group(1)) + (2 if code(line).lstrip().startswith("- ") else 0)
            for nested in lines[index + 1:]:
                stripped = code(nested)
                if stripped.strip() and len(stripped) - len(stripped.lstrip()) <= indent:
                    break
                entry = re.fullmatch(r"( *)([^\s:#]+):.*", stripped)
                if entry and len(entry.group(1)) == indent + 2:
                    names.append(entry.group(2))
        names += re.findall(r"(?:^\s*|[;&|]\s*)export\s+([A-Za-z_][A-Za-z0-9_]*)", code(line))
    return names


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
    entries = [re.fullmatch(r"( *)([A-Za-z0-9_~]+):\s*(.*)", code(line)) for line in lines]
    entries = [entry for entry in entries if entry]
    if not entries:
        return {}
    indent = min(len(entry.group(1)) for entry in entries)
    return {entry.group(2): entry.group(3).strip('"\'') for entry in entries if len(entry.group(1)) == indent}


def structural_lines(lines: list[str]) -> list[tuple[int, str]]:
    """(line number, line) for every line that is YAML structure: no comments, no block-scalar
    bodies (the script phases), no blank lines."""
    found, block_indent = [], None
    for number, raw in enumerate(lines, 1):
        indent = len(raw) - len(raw.lstrip(" "))
        if block_indent is not None:
            if not raw.strip() or indent > block_indent:
                continue
            block_indent = None
        line = code(raw)
        if not line.strip():
            continue
        found.append((number, line))
        if re.search(r"(?:^\s*-|:)\s*[|>][-+0-9]*$", line):
            block_indent = indent
    return found


def check_yaml_shape(errors: list[str], project: Path, lines: list[str]) -> None:
    """Refuse every YAML spelling the line-based reader here would misread, and every XcodeGen
    feature that injects settings from somewhere this policy does not look."""
    structure = structural_lines(lines)
    for number, line in structure:
        where = f"{project}:{number}"
        if "\t" in line:
            errors.append(f"{where}: tab indentation; this policy reads block YAML with spaces only")
        if re.match(r"\s*(?:-\s+)?[\"']", line) and re.match(r"\s*(?:-\s+)?([\"'])[^\"']*\1\s*:(?:\s|$)", line):
            errors.append(f"{where}: quoted mapping key; write keys bare so this policy can read them")
        if re.search(r"(?:^\s*(?:-\s+)?|:\s+)\{(?!\s*\}\s*$)", line) or re.search(r"(?:^\s*(?:-\s+)?|:\s+)\[[^\]]*:", line):
            errors.append(f"{where}: flow-style mapping; write block YAML so this policy can read it")
        if re.search(r"(?:^|\s)(?:[&*!][^\s]|<<\s*:|\?\s)", line) or line.strip() == "---":
            errors.append(f"{where}: YAML anchor, alias, merge key, tag or complex key; not readable here")
        key = re.match(r"\s*(?:-\s+)?([^\s\"'#-][^:]*?):(?:\s|$)", line)
        if key and not re.fullmatch(r"[A-Za-z0-9_~]+", key.group(1)):
            errors.append(f"{where}: key {key.group(1)!r} is not one this policy can read (letters, digits, _ and ~); "
                          "a conditional or dotted key would be skipped rather than checked, so it fails closed "
                          "(spec §22.3)")
    for number, line in structure:
        match = re.fullmatch(r"([A-Za-z0-9_]+):(?:\s.*)?", line)
        if not line.startswith(" ") and (not match or match.group(1) not in TOP_LEVEL_KEYS):
            errors.append(f"{project}:{number}: top-level {line.split(':')[0]!r} is not one of "
                          f"{sorted(TOP_LEVEL_KEYS)}; configFiles, settingGroups, targetTemplates and "
                          "include can each put settings into a build unseen" + extend("TOP_LEVEL_KEYS"))
    for section, allowed in (("options", OPTIONS_KEYS), ("settings", {"base"})):
        for name in sorted(set(mapping(yaml_block(lines, [section]) or [])) - allowed):
            errors.append(f"{project}: {section}.{name} is not allowed; only {sorted(allowed)}"
                          + extend("OPTIONS_KEYS" if section == "options" else "the settings sections in check_yaml_shape"))
    targets = yaml_block(lines, ["targets"]) or []
    for target in re.findall(r"(?m)^  ([A-Za-z0-9_]+):\s*$", "\n".join(targets)):
        for key in sorted(set(mapping(yaml_block(targets, [target]) or [])) - TARGET_KEYS):
            errors.append(f"{project}: target {target} uses {key}, which is not one of {sorted(TARGET_KEYS)}"
                          + extend("TARGET_KEYS"))


def source_paths(target: list[str]) -> list[str]:
    items = [code(line) for line in yaml_block(target, ["sources"]) or [] if re.match(r"\s*-\s", code(line))]
    indent = min((len(item) - len(item.lstrip()) for item in items), default=0)
    paths = []
    for item in items:
        if len(item) - len(item.lstrip()) == indent:
            match = re.fullmatch(r"\s*-\s+(?:path:\s*)?[\"']?([^\"'\s]+)[\"']?\s*", item)
            paths.append(match.group(1) if match else item.strip())
    return sorted(paths)


def check_prod_configuration(errors: list[str]) -> None:
    project = PROJECT_YML
    if not project.is_file():
        errors.append(f"{project} is missing")
        return
    lines = project.read_text().splitlines()
    check_yaml_shape(errors, project, lines)
    for number, script in yaml_scripts(lines):
        errors.extend(script_problems(f"{project}:{number} script", script))

    inherited = mapping(yaml_block(lines, ["settings", "base"]) or [])
    for name in sorted(set(inherited) - PROJECT_SETTINGS):
        errors.append(f"{project}: project-level setting {name} is not allowlisted, and PROD inherits it"
                      + extend("PROJECT_SETTINGS"))

    target_names = re.findall(r"(?m)^  ([A-Za-z0-9_]+):\s*$", "\n".join(yaml_block(lines, ["targets"]) or []))
    for name, rules in PROD_TARGETS.items():
        check_prod_target(errors, project, lines, target_names, name, rules)
    check_pbxproj(errors, lines)


def holds_url(value) -> bool:
    if isinstance(value, dict):
        return any(holds_url(item) for item in value.values())
    if isinstance(value, list):
        return any(holds_url(item) for item in value)
    return isinstance(value, str) and "://" in value


def check_prod_target(errors: list[str], project: Path, lines: list[str], target_names: list[str],
                      name: str, rules: dict) -> None:
    plist_path: Path = rules["plist"]
    directory = plist_path.parent.name
    target = yaml_block(lines, ["targets", name])
    if target is None:
        errors.append(f"{project}: no {name} target")
        return
    settings = yaml_block(target, ["settings"]) or []
    own = mapping(yaml_block(settings, ["base"]) or [])
    if set(mapping(settings)) - {"base"}:
        errors.append(f"{project}: {name} may declare settings.base only")
    for setting in sorted(set(own) - rules["settings"]):
        errors.append(f"{project}: PROD target {name} declares {setting}, which is not allowlisted"
                      + extend(f"PROD_TARGETS[{name!r}]['settings']"))
    inherited = mapping(yaml_block(lines, ["settings", "base"]) or [])
    for setting, value in {**inherited, **own}.items():
        if "://" in value:
            errors.append(f"{project}: {setting} carries a URL on {name}'s PROD configuration path")
    info = mapping(yaml_block(target, ["info"]) or [])
    if info.get("path") != f"{directory}/Info.plist":
        errors.append(f"{project}: {name} must use its own {directory}/Info.plist")
    sources = [code(line).strip() for line in yaml_block(target, ["sources"]) or []]
    if any(directory in source for source in sources):
        errors.append(f"{project}: the PROD plist directory {directory} must not be a source folder")
    for other in target_names:
        if other != name and any(directory in code(line)
                                 for line in yaml_block(lines, ["targets", other]) or []):
            errors.append(f"{project}: target {other} reads the PROD-only {directory} directory")
    dev = yaml_block(lines, ["targets", rules["dev"]])
    if dev is None:
        errors.append(f"{project}: no {rules['dev']} target, the DEV twin of {name}")
    else:
        if source_paths(target) != source_paths(dev):
            errors.append(f"{project}: {name} must compile exactly the source folders {rules['dev']} does "
                          f"(excludes may only remove); found {source_paths(target)} against {source_paths(dev)}")
        deps = [code(line).strip() for line in yaml_block(target, ["dependencies"]) or [] if code(line).strip()]
        dev_deps = [code(line).strip() for line in yaml_block(dev, ["dependencies"]) or [] if code(line).strip()]
        if deps != dev_deps:
            errors.append(f"{project}: {name} must have exactly {rules['dev']}'s dependencies")

    scheme = [code(line).strip() for line in yaml_block(lines, ["schemes", name]) or [] if code(line).strip()]
    if scheme != ["build:", "targets:", f"{name}: all", "archive:", "config: Release"]:
        errors.append(f"{project}: scheme {name} must build {name} alone and archive Release, found {scheme}")
    check_scheme_file(errors, name)

    if not plist_path.is_file():
        errors.append(f"{plist_path} is missing")
        return
    try:
        document = plistlib.loads(plist_path.read_bytes())
    except Exception as error:  # noqa: BLE001 - any parse failure is a policy failure
        errors.append(f"{plist_path}: unreadable: {error}")
        return
    keys = set(document)
    if keys != rules["keys"]:
        errors.append(f"{plist_path}: keys must be exactly the allowlist; extra {sorted(keys - rules['keys'])}, "
                      f"missing {sorted(rules['keys'] - keys)}" + extend(f"PROD_TARGETS[{name!r}]['keys']"))
    for key, value in document.items():
        if holds_url(value):
            errors.append(f"{plist_path}: {key} holds a URL")
    # XcodeGen writes the plist from `info.properties`; a property the committed plist lacks was
    # never regenerated, and would appear the next time someone regenerates.
    properties = yaml_block(target, ["info", "properties"]) or []
    for key in sorted(set(mapping(properties)) - keys):
        errors.append(f"{project}: {name} info.properties declares {key}, which {plist_path} lacks; "
                      "regenerate with the pinned XcodeGen")
    if any("://" in code(line) for line in properties):
        errors.append(f"{project}: {name} info.properties carries a URL")


def check_scheme_file(errors: list[str], name: str) -> None:
    """xcodebuild reads the generated scheme, not project.yml: it must build the PROD target alone
    and archive Release, where the project-level Debug configuration's DEBUG condition is off."""
    path = PBXPROJ.parent / f"xcshareddata/xcschemes/{name}.xcscheme"
    try:
        import xml.etree.ElementTree as ElementTree
        tree = ElementTree.parse(path)
    except Exception as error:  # noqa: BLE001 - a missing or broken scheme is a policy failure
        errors.append(f"{path}: unreadable: {error}")
        return
    blueprints = {item.get("BlueprintName") for item in tree.iter("BuildableReference")}
    archive = tree.find("ArchiveAction")
    if blueprints != {name} or archive is None or archive.get("buildConfiguration") != "Release":
        errors.append(f"{path}: must reference {name} alone and archive Release; found {sorted(map(str, blueprints))}, "
                      f"archive {None if archive is None else archive.get('buildConfiguration')}")
    for element in sorted({item.tag for item in tree.iter()}):
        if element in ("PreActions", "PostActions", "ExecutionAction"):
            errors.append(f"{path}: has {element}; a scheme action runs a shell script inside the PROD build with "
                          "its settings in the environment, so no PROD scheme may carry one (spec §22.3)")
        elif element not in SCHEME_ELEMENTS:
            errors.append(f"{path}: element {element} is not one XcodeGen writes for a PROD scheme"
                          + extend("SCHEME_ELEMENTS"))
    containers = {item.get("ReferencedContainer") for item in tree.iter("BuildableReference")}
    if containers != {"container:Dulcet.xcodeproj"}:
        errors.append(f"{path}: must reference targets in container:Dulcet.xcodeproj only, found {sorted(map(str, containers))}")


# --- the committed Xcode project -------------------------------------------------------------------

_PBX_TOKEN = re.compile(r'\s+|/\*.*?\*/|//[^\n]*|"((?:[^"\\]|\\.)*)"|([A-Za-z0-9_$./:+\-]+)|([{}()=;,])', re.S)


def parse_pbxproj(text: str):
    """The old-style (OpenStep) property list an .xcodeproj is written in. Stdlib only, because this
    gate runs on Linux where plutil does not exist."""
    tokens, position = [], 0
    while position < len(text):
        match = _PBX_TOKEN.match(text, position)
        if not match:
            raise ValueError(f"unreadable at offset {position}: {text[position:position + 30]!r}")
        position = match.end()
        if match.group(1) is not None:
            raw = match.group(1)
            tokens.append(("s", raw.encode("latin-1", "backslashreplace").decode("unicode_escape")
                           if "\\" in raw else raw))
        elif match.group(2) is not None:
            tokens.append(("s", match.group(2)))
        elif match.group(3) is not None:
            tokens.append(("p", match.group(3)))
    index = 0

    def value():
        nonlocal index
        kind, token = tokens[index]
        index += 1
        if kind == "s":
            return token
        if token == "{":
            result = {}
            while tokens[index] != ("p", "}"):
                key = value()
                if tokens[index] != ("p", "="):
                    raise ValueError(f"expected '=' after {str(key)[:40]!r}")
                index += 1
                if key in result:
                    raise ValueError(f"duplicate key {str(key)[:40]!r} in one dictionary (an object id or a setting "
                                     "written twice). Xcode keeps one copy and this policy cannot know which, and "
                                     "XcodeGen never writes one: regenerate with the pinned XcodeGen")
                result[key] = value()
                if tokens[index] != ("p", ";"):
                    raise ValueError(f"expected ';' after {str(key)[:40]!r}")
                index += 1
            index += 1
            return result
        if token == "(":
            result = []
            while tokens[index] != ("p", ")"):
                result.append(value())
                if tokens[index] == ("p", ","):
                    index += 1
            index += 1
            return result
        raise ValueError(f"unexpected {token!r}")

    try:
        root = value()
    except IndexError as error:
        raise ValueError("the file ends inside an open { or ( -- truncated, or braces that do not balance; "
                         "regenerate with the pinned XcodeGen") from error
    if not isinstance(root, dict) or not isinstance(root.get("objects"), dict):
        raise ValueError("not an Xcode project")
    return root


class GeneratedPlistValue(str):
    """An Info.plist value Xcode generates from an INFOPLIST_KEY_*_Generation setting."""


def plist_from_setting(name: str, value: str):
    """The Info.plist entry an INFOPLIST_KEY_* build setting produces."""
    key = name[len("INFOPLIST_KEY_"):]
    generated = re.fullmatch(r"(.+)_Generation", key)
    if generated:
        # `<Key>_Generation = YES` makes Xcode write <Key> with content it generates. The launch screen's
        # is an empty dictionary (OBSERVED in both iOS channels' Release builds). Any other generated
        # content cannot be predicted here, so it compares as a marker a hand-written plist never equals,
        # and the refusal says so (see check_twins).
        if value != "YES":
            return None, None
        return generated.group(1), {} if generated.group(1) == "UILaunchScreen" else GeneratedPlistValue(name)
    suffix = re.fullmatch(r"(.+)_(iPhone|iPad)", key)
    if suffix:
        key = f"{suffix.group(1)}~{suffix.group(2).lower()}"
    if key.startswith("UISupportedInterfaceOrientations"):
        return key, value.split()
    if value in ("YES", "NO"):
        return key, value == "YES"
    return key, value


def check_pbxproj(errors: list[str], lines: list[str]) -> None:
    if not PBXPROJ.is_file():
        errors.append(f"{PBXPROJ} is missing")
        dev_only_plists: set[Path] = set()
    else:
        try:
            root = parse_pbxproj(PBXPROJ.read_text())
        except ValueError as error:
            errors.append(f"{PBXPROJ}: unreadable: {error}")
            root = None
        dev_only_plists = check_project_objects(errors, lines, root) if root else set()
    check_server_marker(errors, dev_only_plists)


def check_project_objects(errors: list[str], lines: list[str], root: dict) -> set[Path]:
    objects = root["objects"]

    def configurations(owner: dict) -> dict[str, dict]:
        listed = objects.get(owner.get("buildConfigurationList"), {})
        return {objects[item]["name"]: objects[item].get("buildSettings", {})
                for item in listed.get("buildConfigurations", []) if item in objects}

    for identifier, item in objects.items():
        if item.get("isa") == "XCBuildConfiguration" and "baseConfigurationReference" in item:
            errors.append(f"{PBXPROJ}: configuration {item.get('name')} ({identifier}) is based on an xcconfig, "
                          "whose settings nothing here can see; no configuration may have one")

    for identifier, item in objects.items():
        if item.get("isa") == "PBXShellScriptBuildPhase":
            where = f"{PBXPROJ}: script phase {item.get('name')!r} ({identifier})"
            errors.extend(script_problems(where, "\n".join(
                [str(item.get("shellScript", ""))] + [str(path) for key in ("inputPaths", "outputPaths",
                 "inputFileListPaths", "outputFileListPaths") for path in item.get(key, [])])))
            if item.get("shellPath") != "/bin/sh":
                errors.append(f"{where}: shellPath must be /bin/sh, found {item.get('shellPath')!r}; another "
                              "interpreter would read the script in a way this policy does not (spec §22.3)")

    project_configs = configurations(objects.get(root.get("rootObject"), {}))
    declared = mapping(yaml_block(lines, ["settings", "base"]) or [])
    for configuration, settings in project_configs.items():
        allowed = XCODEGEN_PROJECT_SETTINGS.get(configuration)
        if allowed is None:
            errors.append(f"{PBXPROJ}: unexpected project configuration {configuration}")
            continue
        for name in sorted(set(settings) - allowed):
            errors.append(f"{PBXPROJ}: project-level {configuration} setting {name} is not allowlisted, "
                          "and PROD inherits it" + extend("XCODEGEN_PROJECT_SETTINGS (or PROJECT_SETTINGS)"))
        check_values(errors, f"project-level {configuration}", settings, PINNED_PROJECT_SETTINGS)
        for name, value in declared.items():
            if settings.get(name) != value:
                errors.append(f"{PBXPROJ}: project-level {name} is {settings.get(name)!r} where project.yml "
                              f"says {value!r}; regenerate with the pinned XcodeGen")

    targets = {item["name"]: item for item in objects.values() if item.get("isa") == "PBXNativeTarget"}
    for name, rules in PROD_TARGETS.items():
        prod, dev = targets.get(name), targets.get(rules["dev"])
        if prod is None or dev is None:
            errors.append(f"{PBXPROJ}: no {name if prod is None else rules['dev']} target")
            continue
        prod_configs, dev_configs = configurations(prod), configurations(dev)
        plist_setting = str(rules["plist"].relative_to("apple"))
        for configuration, settings in prod_configs.items():
            for setting in sorted(set(settings) - rules["settings"] - XCODEGEN_TARGET_SETTINGS):
                errors.append(f"{PBXPROJ}: PROD target {name} ({configuration}) sets {setting}, "
                              "which is not allowlisted" + extend(f"PROD_TARGETS[{name!r}]['settings']"))
            if settings.get("INFOPLIST_FILE") != plist_setting:
                errors.append(f"{PBXPROJ}: PROD target {name} ({configuration}) must read {plist_setting}")
            check_values(errors, f"PROD target {name} ({configuration})", settings, PINNED_TARGET_SETTINGS)
        for target, target_configs in ((name, prod_configs), (rules["dev"], dev_configs)):
            check_generated(errors, lines, target, target_configs)
        if "Release" not in prod_configs or "Release" not in dev_configs:
            errors.append(f"{PBXPROJ}: {name} and {rules['dev']} must both have a Release configuration")
            continue
        check_twins(errors, objects, name, prod, prod_configs["Release"], rules["dev"], dev,
                    dev_configs["Release"], rules["plist"])

    dev_only = set()
    for item in targets.values():
        if item.get("productType") == "com.apple.product-type.application" and item["name"] not in PROD_TARGETS:
            for settings in configurations(item).values():
                if settings.get("INFOPLIST_FILE"):
                    dev_only.add(Path("apple") / settings["INFOPLIST_FILE"])
    return dev_only


def check_values(errors: list[str], where: str, settings: dict, pinned: dict[str, str]) -> None:
    for setting, value in settings.items():
        if holds_url(value):
            errors.append(f"{PBXPROJ}: {where} {setting} carries a URL")
    for setting, value in pinned.items():
        if setting in settings and settings[setting] != value:
            errors.append(f"{PBXPROJ}: {where} {setting} must be exactly {value!r}, found {settings[setting]!r}"
                          + extend("PINNED_PROJECT_SETTINGS / PINNED_TARGET_SETTINGS"))


def check_generated(errors: list[str], lines: list[str], target: str, configs: dict[str, dict]) -> None:
    """The committed project must carry exactly the target settings project.yml declares, so a
    project.yml edit that was never regenerated -- or a project hand-edit -- is a failure here."""
    declared = yaml_block(lines, ["targets", target, "settings"]) or []
    base = mapping(yaml_block(declared, ["base"]) or [])
    for configuration, settings in configs.items():
        expected = {**base, **mapping(yaml_block(declared, ["configs", configuration]) or [])}
        for setting in sorted(set(expected) | set(settings)):
            if setting in XCODEGEN_TARGET_DEFAULTS and setting not in expected:
                continue
            if settings.get(setting) != expected.get(setting):
                errors.append(f"{PBXPROJ}: {target} ({configuration}) {setting} is {settings.get(setting)!r} where "
                              f"project.yml says {expected.get(setting)!r}; regenerate with the pinned XcodeGen")


def declared_plist(settings: dict) -> tuple[dict, list[str]]:
    """The Info.plist a target declares: its INFOPLIST_FILE plus its INFOPLIST_KEY_* settings."""
    problems, document = [], {}
    path = Path("apple") / settings.get("INFOPLIST_FILE", "")
    if settings.get("INFOPLIST_FILE"):
        try:
            document = plistlib.loads(path.read_bytes())
        except Exception as error:  # noqa: BLE001 - any parse failure is a policy failure
            problems.append(f"{path}: unreadable: {error}")
    for setting, value in settings.items():
        if setting.startswith("INFOPLIST_KEY_"):
            key, entry = plist_from_setting(setting, value)
            if key is not None:
                document[key] = entry
    return document, problems


def check_twins(errors: list[str], objects: dict, name: str, prod: dict, prod_settings: dict,
                dev_name: str, dev: dict, dev_settings: dict, prod_plist: Path) -> None:
    """Spec §22.3: a DEV target and its PROD twin differ only in identity, name, icon and the
    preconfigured server. Anything else differing means DEV no longer predicts PROD."""
    for setting in sorted(set(prod_settings) | set(dev_settings)):
        if setting in CHANNEL_SETTINGS or setting.startswith("INFOPLIST_KEY_"):
            continue
        if prod_settings.get(setting) != dev_settings.get(setting):
            errors.append(f"{PBXPROJ}: Release {setting} differs between {dev_name} "
                          f"({dev_settings.get(setting)!r}) and {name} ({prod_settings.get(setting)!r}); "
                          "only spec §22.3's channel settings may; set it identically on both" + extend("CHANNEL_SETTINGS"))
    dev_plist, problems = declared_plist(dev_settings)
    prod_document, prod_problems = declared_plist(prod_settings)
    errors.extend(problems + prod_problems)
    for key in sorted((set(dev_plist) | set(prod_document)) - IMPLICIT_PLIST_KEYS - CHANNEL_PLIST_KEYS):
        if dev_plist.get(key) != prod_document.get(key):
            side = f"{dev_name} lacks it" if key not in dev_plist else \
                f"{prod_plist} lacks it" if key not in prod_document else "the values differ"
            generated = [value for value in (dev_plist.get(key), prod_document.get(key))
                         if isinstance(value, GeneratedPlistValue)]
            if generated:
                side += (f"; {generated[0]} makes Xcode generate {key}, whose content a hand-written PROD plist "
                         f"cannot be compared with, so declare {key} explicitly in both plists instead")
            errors.append(f"Info.plist {key}: {dev_name} and {name} must declare it identically ({side}); "
                          "only name, icon and the DEV-only server key may differ" + extend("CHANNEL_PLIST_KEYS"))

    dev_id, prod_id = dev_settings.get("PRODUCT_BUNDLE_IDENTIFIER", ""), prod_settings.get("PRODUCT_BUNDLE_IDENTIFIER", "")

    def entitlements(settings: dict, rename: bool):
        if not settings.get("CODE_SIGN_ENTITLEMENTS"):
            return None
        path = Path("apple") / settings["CODE_SIGN_ENTITLEMENTS"]
        try:
            text = path.read_text()
        except OSError as error:
            errors.append(f"{path}: unreadable: {error}")
            return None
        if rename and dev_id:
            text = text.replace(dev_id, prod_id)
        return plistlib.loads(text.encode())

    if entitlements(dev_settings, True) != entitlements(prod_settings, False):
        errors.append(f"entitlements of {dev_name} and {name} must match apart from the bundle identifier "
                      "(spec §22.3); change both files together")

    def package(identifier: str):
        """A package product by what it resolves to: its name and the package it comes from, located by
        path or URL with its version requirement. The product name alone lets PROD link another package."""
        product = objects.get(identifier, {})
        reference = objects.get(product.get("package"), {}) if "package" in product else None
        return (product.get("productName", "?"),
                None if reference is None else tuple(sorted((key, str(value)) for key, value in reference.items())))

    def synchronized(target: dict, identifier: str):
        """A synchronized folder, with only the exception sets that apply to this target."""
        group = dict(objects.get(identifier, {}))
        exceptions = [objects.get(item, {}) for item in group.pop("exceptions", [])]
        own = sorted(str(sorted((key, str(value)) for key, value in item.items() if key != "target"))
                     for item in exceptions if objects.get(item.get("target"), {}) is target)
        return str(sorted((key, str(value)) for key, value in group.items())), own

    def membership(target: dict) -> dict[str, object]:
        phases = []
        for identifier in target.get("buildPhases", []):
            phase = objects.get(identifier, {})
            if phase.get("isa") == "PBXShellScriptBuildPhase":
                phases.append(str(sorted((key, str(value)) for key, value in phase.items())))
                continue
            members = []
            for entry in phase.get("files", []):
                build_file = dict(objects.get(entry, {}))
                if "productRef" in build_file:
                    build_file["productRef"] = package(build_file["productRef"])
                members.append(str(sorted((key, str(value)) for key, value in build_file.items())))
            phases.append((phase.get("isa"), phase.get("dstSubfolderSpec"), phase.get("dstPath"), tuple(sorted(members))))
        return {
            "build phases (files, per-file settings and scripts)": phases,
            "packages (by location and requirement, not product name)":
                sorted(package(item) for item in target.get("packageProductDependencies", [])),
            "dependencies": sorted(objects.get(objects.get(item, {}).get("target"), {}).get("name", "?")
                                   for item in target.get("dependencies", [])),
            "build rules": sorted(str(sorted((key, str(value)) for key, value in objects.get(item, {}).items()))
                                  for item in target.get("buildRules", [])),
            "synchronized folders": sorted(synchronized(target, item)
                                           for item in target.get("fileSystemSynchronizedGroups", [])),
        }

    for target_name, target in ((name, prod), (dev_name, dev)):
        for key in sorted(set(target) - TARGET_OBJECT_KEYS):
            errors.append(f"{PBXPROJ}: target {target_name} carries {key}, which XcodeGen does not write for it and "
                          "this policy does not compare" + extend("TARGET_OBJECT_KEYS"))
    prod_members, dev_members = membership(prod), membership(dev)
    for aspect in prod_members:
        if prod_members[aspect] != dev_members[aspect]:
            errors.append(f"{PBXPROJ}: {name} must build exactly {dev_name}'s files, script phases, packages and "
                          f"dependencies; their {aspect} differ, and an input only PROD carries is a route for a "
                          "server (spec §22.3)")


def script_problems(where: str, script: str) -> list[str]:
    """A build-phase script may read only SCRIPT_VARIABLES, and nothing that dumps the environment.
    Every target runs the same script, so this is what stops one script branching on the channel."""
    problems = []
    body = "\n".join(line for line in script.splitlines() if not line.lstrip().startswith("#"))
    names = set(re.findall(r"\$\{?#?!?([A-Za-z_][A-Za-z0-9_]*)|\$\(([A-Za-z_][A-Za-z0-9_]*)\)", body))
    for name in sorted({first or second for first, second in names} - SCRIPT_VARIABLES):
        kind = "a channel-distinguishing variable" if CHANNEL_VARIABLES.search(name) else "a variable"
        problems.append(f"{where}: reads {kind} ${name}; a script every target runs could use it to write something "
                        "into PROD alone (spec §22.3)" + extend("SCRIPT_VARIABLES"))
    if ENVIRONMENT_DUMPS.search(body):
        problems.append(f"{where}: reads the whole environment ({ENVIRONMENT_DUMPS.search(body).group(0).strip()}), "
                        "which a variable allowlist cannot see through (spec §22.3)")
    return problems


def yaml_scripts(lines: list[str]) -> list[tuple[int, str]]:
    """(line number, body) of every block scalar in project.yml: the script phases."""
    scripts, start, indent = [], None, 0
    for number, raw in enumerate(lines + ["\x00"], 1):
        if start is not None:
            if raw.strip() and len(raw) - len(raw.lstrip(" ")) <= indent or raw == "\x00":
                scripts.append((start, "\n".join(lines[start:number - 1])))
                start = None
            else:
                continue
        if re.search(r"(?:^\s*-|:)\s*[|>][-+0-9]*$", code(raw)):
            start, indent = number, len(raw) - len(raw.lstrip(" "))
    return scripts


def check_server_marker(errors: list[str], dev_only_plists: set[Path]) -> None:
    """The server key may live only in a DEV-only plist: not in shared code, not in a PROD plist, not
    in a build setting. tools/release/validate-app-bundle re-checks the built PROD bundle itself."""
    skip = {".build", "build", "DerivedData", "xcuserdata", ".swiftpm"}
    for path in sorted(Path("apple").rglob("*")):
        if any(part in skip for part in path.parts) or not path.is_file() or path in dev_only_plists:
            continue
        if SERVER_KEY.encode() in path.read_bytes():
            errors.append(f"{path}: contains {SERVER_KEY}, which may appear only in a DEV-only plist "
                          f"({', '.join(sorted(map(str, dev_only_plists))) or 'none found'}); spec §22.3, §22.6")


def main() -> int:
    errors: list[str] = []
    try:
        check(errors)
    except Exception as error:  # noqa: BLE001 - a policy that cannot read its input must not pass it
        errors.append(f"the release policy could not read this repository ({type(error).__name__}: {error}); "
                      "it fails closed rather than skip what it could not read (spec §22.3)")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        print(f"\n{len(errors)} violation(s) of spec §22.3/§22.6 (PROD must be unable to carry a server). The rules "
              f"and every allowlist live in {POLICY_FILE}; a refusal marked [allowlist: …] names the constant to "
              "extend, in review. Generated files are fixed by regenerating with the pinned XcodeGen, never by hand.",
              file=sys.stderr)
        return 1
    print("release policy valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
