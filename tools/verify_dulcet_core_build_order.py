#!/usr/bin/env python3
"""Require every native Xcode target to link and obtain DulcetCore in order."""

from __future__ import annotations

import argparse
from collections import deque
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
from typing import Any


REPOSITORY = Path(__file__).resolve().parents[1]
DEFAULT_PROJECT = REPOSITORY / "apple/Dulcet.xcodeproj/project.pbxproj"
KOTLIN_PHASE_NAME = "Compile Kotlin Framework"
KOTLIN_GRADLE_TASK = ":core:embedAndSignAppleFrameworkForXcode"
KOTLIN_OVERRIDE_SETTING = "OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED"
SHELL_PHASE_ISA = "PBXShellScriptBuildPhase"
FRAMEWORK_PHASE_ISA = "PBXFrameworksBuildPhase"
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


def strongly_links_dulcet_core(tokens: list[str]) -> bool:
    """Accept only the literal strong-link contract, never weak or indirect linkage."""
    return any(
        token == "-framework" and index + 1 < len(tokens) and tokens[index + 1] == "DulcetCore"
        for index, token in enumerate(tokens)
    )


def runs_kotlin_gradle_task(shell_script: Any) -> bool:
    if not isinstance(shell_script, str):
        return False
    return re.search(
        rf"(?<![A-Za-z0-9_:-]){re.escape(KOTLIN_GRADLE_TASK)}(?![A-Za-z0-9_:-])",
        shell_script,
    ) is not None


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


def configuration_settings(
    objects: dict[str, Any],
    list_id: Any,
    owner: str,
) -> dict[str, dict[str, Any]]:
    if not isinstance(list_id, str):
        raise ProjectError(f"{owner}: missing XCConfigurationList reference")
    configuration_list = objects.get(list_id)
    if not isinstance(configuration_list, dict) or configuration_list.get("isa") != "XCConfigurationList":
        raise ProjectError(f"{owner}: missing XCConfigurationList {list_id}")

    configuration_ids = configuration_list.get("buildConfigurations")
    if not isinstance(configuration_ids, list) or not configuration_ids:
        raise ProjectError(f"{owner}: XCConfigurationList {list_id} contains no build configurations")

    configurations: dict[str, dict[str, Any]] = {}
    for configuration_id in configuration_ids:
        configuration = objects.get(configuration_id)
        if not isinstance(configuration, dict) or configuration.get("isa") != "XCBuildConfiguration":
            raise ProjectError(f"{owner}: missing XCBuildConfiguration {configuration_id}")
        name = configuration.get("name")
        settings = configuration.get("buildSettings")
        if not isinstance(name, str) or not isinstance(settings, dict):
            raise ProjectError(f"{owner}: invalid XCBuildConfiguration {configuration_id}")
        if configuration.get("baseConfigurationReference") is not None:
            raise ProjectError(
                f"{owner} configuration {name!r} uses an xcconfig; teach this guard to resolve it"
            )
        conditional_flags = sorted(key for key in settings if key.startswith("OTHER_LDFLAGS["))
        if conditional_flags:
            raise ProjectError(
                f"{owner} configuration {name!r} uses conditional linker flags "
                f"{conditional_flags}; teach this guard to resolve them"
            )
        if name in configurations:
            raise ProjectError(f"{owner}: duplicate build configuration {name!r}")
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


def native_targets(root: dict[str, Any], objects: dict[str, Any]) -> list[str]:
    root_targets = root.get("targets")
    if not isinstance(root_targets, list) or not root_targets:
        raise ProjectError("PBXProject contains no target references")

    target_ids: list[str] = []
    for target_id in root_targets:
        target = objects.get(target_id)
        if not isinstance(target_id, str) or not isinstance(target, dict):
            raise ProjectError(f"PBXProject contains dangling target reference {target_id!r}")
        target_isa = target.get("isa")
        if target_isa == "PBXNativeTarget":
            target_ids.append(target_id)
        elif target_isa != "PBXAggregateTarget":
            raise ProjectError(f"PBXProject target {target_id} has unsupported isa {target_isa!r}")
    if not target_ids:
        raise ProjectError("project contains no PBXNativeTarget objects")
    return target_ids


def target_phase_provider(
    target_name: str,
    phase_ids: Any,
    objects: dict[str, Any],
) -> tuple[bool, list[str]]:
    if not isinstance(phase_ids, list) or not phase_ids:
        raise ProjectError(f"target {target_name} contains no build phases")

    phases: list[dict[str, Any]] = []
    for phase_id in phase_ids:
        phase = objects.get(phase_id)
        if not isinstance(phase_id, str) or not isinstance(phase, dict):
            raise ProjectError(f"target {target_name} contains dangling build phase {phase_id!r}")
        if not isinstance(phase.get("isa"), str):
            raise ProjectError(f"target {target_name} build phase {phase_id} has no isa")
        phases.append(phase)

    framework_indexes = [
        index for index, phase in enumerate(phases) if phase["isa"] == FRAMEWORK_PHASE_ISA
    ]
    non_shell_indexes = [
        index for index, phase in enumerate(phases) if phase["isa"] != SHELL_PHASE_ISA
    ]
    rejections: list[str] = []
    ordered_provider = False

    for index, phase in enumerate(phases):
        if not (
            phase["isa"] == SHELL_PHASE_ISA
            and phase.get("name") == KOTLIN_PHASE_NAME
            and runs_kotlin_gradle_task(phase.get("shellScript"))
        ):
            continue

        if framework_indexes:
            if index < min(framework_indexes):
                ordered_provider = True
            else:
                rendered = ",".join(str(value) for value in framework_indexes)
                rejections.append(
                    f"local Kotlin phase index={index} must precede "
                    f"{FRAMEWORK_PHASE_ISA} indexes={rendered}"
                )
            continue

        if not non_shell_indexes:
            rejections.append(
                "local Kotlin phase has no non-shell build phase against which ordering can be proven"
            )
        elif index < min(non_shell_indexes):
            ordered_provider = True
        else:
            rejections.append(
                f"local Kotlin phase index={index} must precede first non-shell "
                f"build phase index={min(non_shell_indexes)}"
            )

    return ordered_provider, rejections


def verify(path: Path) -> int:
    project_file = load_project(path)
    objects = project_file["objects"]
    root_id = project_file.get("rootObject")
    root = objects.get(root_id)
    if not isinstance(root, dict) or root.get("isa") != "PBXProject":
        raise ProjectError(f"missing PBXProject root object {root_id!r}")

    target_ids = native_targets(root, objects)
    project_owner = f"project {path.parent.stem}"
    project_configurations = configuration_settings(
        objects,
        root.get("buildConfigurationList"),
        project_owner,
    )

    target_names: dict[str, str] = {}
    for target_id in target_ids:
        target_name = objects[target_id].get("name")
        if not isinstance(target_name, str) or not target_name:
            raise ProjectError(f"PBXNativeTarget {target_id} has no name")
        target_names[target_id] = target_name

    failures: list[str] = []
    for configuration_name, settings in project_configurations.items():
        if KOTLIN_OVERRIDE_SETTING in settings:
            failures.append(
                f"owner={project_owner} configuration={configuration_name} defines forbidden "
                f"{KOTLIN_OVERRIDE_SETTING}={settings[KOTLIN_OVERRIDE_SETTING]!r}"
            )

    phase_providers: set[str] = set()
    provider_rejections: dict[str, list[str]] = {target_id: [] for target_id in target_ids}
    dependencies: dict[str, list[str]] = {target_id: [] for target_id in target_ids}
    aggregate_dependencies: dict[str, list[str]] = {target_id: [] for target_id in target_ids}
    target_configurations_by_id: dict[str, dict[str, dict[str, Any]]] = {}

    for target_id in target_ids:
        target = objects[target_id]
        is_provider, rejections = target_phase_provider(
            target_names[target_id],
            target.get("buildPhases"),
            objects,
        )
        if is_provider:
            phase_providers.add(target_id)
        provider_rejections[target_id].extend(rejections)

        dependency_object_ids = target.get("dependencies")
        if not isinstance(dependency_object_ids, list):
            raise ProjectError(f"target {target_names[target_id]} has invalid dependencies list")
        for dependency_object_id in dependency_object_ids:
            dependency_object = objects.get(dependency_object_id)
            if not isinstance(dependency_object, dict) or dependency_object.get("isa") != "PBXTargetDependency":
                raise ProjectError(
                    f"target {target_names[target_id]} has invalid dependency {dependency_object_id}"
                )
            dependency_target_id = dependency_object.get("target")
            if not isinstance(dependency_target_id, str):
                raise ProjectError(
                    f"target {target_names[target_id]} dependency {dependency_object_id} "
                    "has no target reference"
                )
            dependency_target = objects.get(dependency_target_id)
            if not isinstance(dependency_target, dict):
                raise ProjectError(
                    f"target {target_names[target_id]} dependency {dependency_object_id} "
                    f"refers to dangling target {dependency_target_id}"
                )
            dependency_isa = dependency_target.get("isa")
            if dependency_isa == "PBXNativeTarget":
                if dependency_target_id not in dependencies:
                    raise ProjectError(
                        f"target {target_names[target_id]} dependency {dependency_object_id} "
                        f"refers to native target {dependency_target_id} outside PBXProject.targets"
                    )
                dependencies[target_id].append(dependency_target_id)
            elif dependency_isa == "PBXAggregateTarget":
                aggregate_name = dependency_target.get("name", dependency_target_id)
                aggregate_dependencies[target_id].append(str(aggregate_name))
            else:
                raise ProjectError(
                    f"target {target_names[target_id]} dependency {dependency_object_id} "
                    f"refers to unsupported target isa {dependency_isa!r}"
                )

        target_owner = f"target {target_names[target_id]}"
        target_configurations = configuration_settings(
            objects,
            target.get("buildConfigurationList"),
            target_owner,
        )
        target_configurations_by_id[target_id] = target_configurations
        for configuration_name, settings in target_configurations.items():
            if KOTLIN_OVERRIDE_SETTING in settings:
                failures.append(
                    f"owner={target_owner} configuration={configuration_name} defines forbidden "
                    f"{KOTLIN_OVERRIDE_SETTING}={settings[KOTLIN_OVERRIDE_SETTING]!r}"
                )

    passes: list[tuple[str, list[str], list[str]]] = []
    for target_id in sorted(target_ids, key=lambda item: target_names[item]):
        target_configurations = target_configurations_by_id[target_id]
        missing_target_configurations = [
            name for name in project_configurations if name not in target_configurations
        ]
        if missing_target_configurations:
            raise ProjectError(
                f"target {target_names[target_id]} is missing project configurations "
                f"{missing_target_configurations}"
            )
        strong_configurations: list[str] = []
        for configuration_name, target_settings in target_configurations.items():
            if configuration_name not in project_configurations:
                raise ProjectError(
                    f"target {target_names[target_id]} configuration {configuration_name!r} "
                    "has no matching project configuration"
                )
            project_value = project_configurations[configuration_name].get("OTHER_LDFLAGS")
            effective_flags = effective_other_ldflags(project_value, target_settings)
            if strongly_links_dulcet_core(effective_flags):
                strong_configurations.append(configuration_name)

        missing_configurations = [
            name for name in target_configurations if name not in strong_configurations
        ]
        scope_valid = not missing_configurations
        if missing_configurations:
            failures.append(
                f"target={target_names[target_id]} configurations={','.join(missing_configurations)} "
                "do not expose the required literal strong '-framework DulcetCore' contract"
            )

        path_ids = provider_path(target_id, phase_providers, dependencies)
        provider_valid = path_ids is not None
        if path_ids is None:
            detail: list[str] = []
            if provider_rejections[target_id]:
                detail.extend(provider_rejections[target_id])
            if aggregate_dependencies[target_id]:
                detail.append(
                    "aggregate dependencies cannot provide DulcetCore: "
                    + ",".join(aggregate_dependencies[target_id])
                )
            suffix = f"; {'; '.join(detail)}" if detail else ""
            failures.append(
                f"target={target_names[target_id]} has no reachable ordered "
                f"{KOTLIN_PHASE_NAME!r} phase running {KOTLIN_GRADLE_TASK}{suffix}"
            )

        if scope_valid and provider_valid:
            passes.append(
                (
                    target_names[target_id],
                    strong_configurations,
                    [target_names[path_id] for path_id in path_ids],
                )
            )

    if failures:
        print("DULCET CORE BUILD ORDER FAIL", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    if len(passes) != len(target_ids):
        raise ProjectError(
            f"internal scope mismatch: checked {len(passes)} of {len(target_ids)} native targets"
        )

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
        f"checked_targets={len(passes)} expected_native_targets={len(target_ids)} "
        f"self_providers={self_providers} dependency_providers={dependency_providers}"
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
