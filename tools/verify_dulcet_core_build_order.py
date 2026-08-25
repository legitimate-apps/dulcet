#!/usr/bin/env python3
"""Require every Xcode target linking DulcetCore to reach its build phase."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys
from collections import deque
from typing import Any


REPOSITORY = Path(__file__).resolve().parents[1]
DEFAULT_PROJECT = REPOSITORY / "apple/Dulcet.xcodeproj/project.pbxproj"
KOTLIN_PHASE_NAME = "Compile Kotlin Framework"
KOTLIN_GRADLE_TASK = ":core:embedAndSignAppleFrameworkForXcode"
INHERITED_TOKENS = {"$(inherited)", "${inherited}"}


class ProjectError(Exception):
    """The committed Xcode project could not be inspected safely."""


def as_tokens(value: Any) -> list[str]:
    if value is None:
        return []
    values = value if isinstance(value, list) else [value]
    tokens: list[str] = []
    for item in values:
        if not isinstance(item, str):
            raise ProjectError(f"unexpected build-setting value {item!r}")
        try:
            tokens.extend(shlex.split(item))
        except ValueError as error:
            raise ProjectError(f"cannot parse build-setting value {item!r}: {error}") from error
    return tokens


def effective_other_ldflags(project_value: Any, target_settings: dict[str, Any]) -> list[str]:
    project_tokens = as_tokens(project_value)
    if "OTHER_LDFLAGS" not in target_settings:
        return project_tokens

    result: list[str] = []
    for token in as_tokens(target_settings["OTHER_LDFLAGS"]):
        if token in INHERITED_TOKENS:
            result.extend(project_tokens)
        else:
            result.append(token)
    return result


def links_dulcet_core(tokens: list[str]) -> bool:
    return any(
        token == "-framework" and index + 1 < len(tokens) and tokens[index + 1] == "DulcetCore"
        for index, token in enumerate(tokens)
    )


def load_project(path: Path) -> dict[str, Any]:
    try:
        conversion = subprocess.run(
            ["plutil", "-convert", "json", "-o", "-", str(path)],
            check=False,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError as error:
        raise ProjectError("plutil is required to inspect the Xcode project") from error
    if conversion.returncode != 0:
        detail = conversion.stderr.strip() or conversion.stdout.strip()
        raise ProjectError(f"plutil rejected {path}: {detail}")
    try:
        project = json.loads(conversion.stdout)
    except json.JSONDecodeError as error:
        raise ProjectError(f"plutil returned invalid JSON for {path}: {error}") from error
    if not isinstance(project, dict) or not isinstance(project.get("objects"), dict):
        raise ProjectError(f"{path} is not an Xcode project property list")
    return project


def configuration_settings(objects: dict[str, Any], list_id: str) -> dict[str, dict[str, Any]]:
    configuration_list = objects.get(list_id)
    if not isinstance(configuration_list, dict) or configuration_list.get("isa") != "XCConfigurationList":
        raise ProjectError(f"missing XCConfigurationList {list_id}")

    configuration_ids = configuration_list.get("buildConfigurations")
    if not isinstance(configuration_ids, list) or not configuration_ids:
        raise ProjectError(f"XCConfigurationList {list_id} contains no build configurations")

    configurations: dict[str, dict[str, Any]] = {}
    for configuration_id in configuration_ids:
        configuration = objects.get(configuration_id)
        if not isinstance(configuration, dict) or configuration.get("isa") != "XCBuildConfiguration":
            raise ProjectError(f"missing XCBuildConfiguration {configuration_id}")
        name = configuration.get("name")
        settings = configuration.get("buildSettings")
        if not isinstance(name, str) or not isinstance(settings, dict):
            raise ProjectError(f"invalid XCBuildConfiguration {configuration_id}")
        if configuration.get("baseConfigurationReference") is not None:
            raise ProjectError(
                f"build configuration {name!r} uses an xcconfig; teach this guard to resolve it"
            )
        conditional_flags = sorted(
            key for key in settings if key.startswith("OTHER_LDFLAGS[")
        )
        if conditional_flags:
            raise ProjectError(
                f"build configuration {name!r} uses conditional linker flags "
                f"{conditional_flags}; teach this guard to resolve them"
            )
        if name in configurations:
            raise ProjectError(f"duplicate build configuration {name!r}")
        configurations[name] = settings
    return configurations


def provider_path(
    start: str,
    providers: set[str],
    dependencies: dict[str, list[str]],
) -> list[str] | None:
    queue: deque[tuple[str, list[str]]] = deque([(start, [start])])
    visited = {start}
    while queue:
        target_id, path = queue.popleft()
        if target_id in providers:
            return path
        for dependency_id in dependencies[target_id]:
            if dependency_id not in visited:
                visited.add(dependency_id)
                queue.append((dependency_id, [*path, dependency_id]))
    return None


def verify(path: Path) -> int:
    project_file = load_project(path)
    objects = project_file["objects"]
    root_id = project_file.get("rootObject")
    root = objects.get(root_id)
    if not isinstance(root, dict) or root.get("isa") != "PBXProject":
        raise ProjectError(f"missing PBXProject root object {root_id!r}")

    target_ids = [
        target_id
        for target_id in root.get("targets", [])
        if isinstance(objects.get(target_id), dict)
        and objects[target_id].get("isa") == "PBXNativeTarget"
    ]
    if not target_ids:
        raise ProjectError("project contains no PBXNativeTarget objects")

    project_configurations = configuration_settings(objects, root["buildConfigurationList"])
    target_names: dict[str, str] = {}
    for target_id in target_ids:
        target_name = objects[target_id].get("name")
        if not isinstance(target_name, str) or not target_name:
            raise ProjectError(f"PBXNativeTarget {target_id} has no name")
        target_names[target_id] = target_name

    phase_providers: set[str] = set()
    dependencies: dict[str, list[str]] = {target_id: [] for target_id in target_ids}
    linked_configurations: dict[str, list[str]] = {target_id: [] for target_id in target_ids}

    for target_id in target_ids:
        target = objects[target_id]
        for phase_id in target.get("buildPhases", []):
            phase = objects.get(phase_id)
            if (
                isinstance(phase, dict)
                and phase.get("isa") == "PBXShellScriptBuildPhase"
                and phase.get("name") == KOTLIN_PHASE_NAME
                and KOTLIN_GRADLE_TASK in phase.get("shellScript", "")
            ):
                phase_providers.add(target_id)

        for dependency_object_id in target.get("dependencies", []):
            dependency_object = objects.get(dependency_object_id)
            if not isinstance(dependency_object, dict) or dependency_object.get("isa") != "PBXTargetDependency":
                raise ProjectError(
                    f"target {target_names[target_id]} has invalid dependency {dependency_object_id}"
                )
            dependency_target_id = dependency_object.get("target")
            if dependency_target_id in dependencies:
                dependencies[target_id].append(dependency_target_id)

        target_configurations = configuration_settings(objects, target["buildConfigurationList"])
        for configuration_name, target_settings in target_configurations.items():
            if configuration_name not in project_configurations:
                raise ProjectError(
                    f"target {target_names[target_id]} configuration {configuration_name!r} "
                    "has no matching project configuration"
                )
            project_value = project_configurations[configuration_name].get("OTHER_LDFLAGS")
            if links_dulcet_core(effective_other_ldflags(project_value, target_settings)):
                linked_configurations[target_id].append(configuration_name)

    failures: list[str] = []
    passes: list[tuple[str, list[str], list[str]]] = []
    for target_id in sorted(target_ids, key=lambda item: target_names[item]):
        configurations = linked_configurations[target_id]
        if not configurations:
            continue
        path_ids = provider_path(target_id, phase_providers, dependencies)
        if path_ids is None:
            failures.append(
                f"target={target_names[target_id]} configurations={','.join(configurations)} "
                f"has no reachable {KOTLIN_PHASE_NAME!r} phase running {KOTLIN_GRADLE_TASK}"
            )
            continue
        passes.append(
            (
                str(target_names[target_id]),
                configurations,
                [str(target_names[path_id]) for path_id in path_ids],
            )
        )

    if failures:
        print("DULCET CORE BUILD ORDER FAIL", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    self_providers = 0
    dependency_providers = 0
    for target_name, configurations, path_names in passes:
        mode = "self" if len(path_names) == 1 else "dependency"
        if mode == "self":
            self_providers += 1
        else:
            dependency_providers += 1
        print(
            f"DULCET CORE BUILD ORDER target={target_name} "
            f"configurations={','.join(configurations)} provider={mode} "
            f"path={' -> '.join(path_names)}"
        )
    print(
        "DULCET CORE BUILD ORDER PASS "
        f"linked_targets={len(passes)} self_providers={self_providers} "
        f"dependency_providers={dependency_providers}"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "project",
        nargs="?",
        type=Path,
        default=DEFAULT_PROJECT,
        help="project.pbxproj to inspect (defaults to the committed Dulcet project)",
    )
    arguments = parser.parse_args()
    try:
        return verify(arguments.project.resolve())
    except ProjectError as error:
        print(f"DULCET CORE BUILD ORDER ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
