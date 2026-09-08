#!/usr/bin/env python3
"""Require every `script:` in apple/project.yml to be present in the committed Xcode project.

`apple/project.yml` is the SOURCE; `apple/Dulcet.xcodeproj` is generated from it by XcodeGen and
committed. Nothing rebuilds it during a build, so an edit to `project.yml` alone changes what the
repository documents and NOT what Xcode runs.

That gap is not theoretical: `tools/verify_dulcet_core_build_order.py` reads the **pbxproj**, so a
project.yml-only edit leaves it reporting PASS about the old script while the new one has never run.
This stdlib-only Linux gate compares multisets of encoded script bodies: each declaration
must have exactly one generated occurrence, including duplicate bodies. It reads shellScript
assignments, never arbitrary substring occurrences.

LIMITS: this is not a full YAML/OpenStep parser or a replacement for regenerating with XcodeGen.
Only literal `script: |` blocks with the repository's two-space body indent are understood;
other script forms and external `path:` scripts are NOT checked. Target attachment, phase ordering,
names, shellPath, dependency-analysis flags, input/output files and file lists are NOT checked.
The assignment matcher assumes normal generated pbxproj text (not assignments inside comments).
Changes to these properties require regeneration and review of the generated diff.

"""

from __future__ import annotations

from collections import Counter
from pathlib import Path
import re
import sys

REPOSITORY = Path(__file__).resolve().parent.parent
DEFAULT_SPECIFICATION = REPOSITORY / "apple/project.yml"
DEFAULT_PROJECT = REPOSITORY / "apple/Dulcet.xcodeproj/project.pbxproj"

SCRIPT_BLOCK = re.compile(r"^(?P<indent>\s*)script: \|\s*$")
PHASE_NAME = re.compile(r"^\s*- name:\s*(?P<name>.+?)\s*$")
PBXPROJ_SCRIPT = re.compile(r"shellScript = \"(?P<body>(?:[^\"\\]|\\.)*)\";")


def specification_scripts(text: str) -> list[tuple[str, str]]:
    """Return (phase name, shell body) for every `script: |` block, in file order."""
    lines = text.splitlines()
    found: list[tuple[str, str]] = []
    for index, line in enumerate(lines):
        header = SCRIPT_BLOCK.match(line)
        if not header:
            continue
        indent = len(header.group("indent"))
        name = "<unnamed>"
        for previous in range(index - 1, max(index - 12, -1), -1):
            candidate = PHASE_NAME.match(lines[previous])
            if candidate:
                name = candidate.group("name")
                break
        body: list[str] = []
        for following in lines[index + 1:]:
            if following.strip() and (len(following) - len(following.lstrip())) <= indent:
                break
            body.append(following[indent + 2:] if following.strip() else "")
        found.append((name, "".join(part + "\n" for part in body)))
    return found


def encode(body: str) -> str:
    """Escape a shell body the way XcodeGen writes it into `shellScript = "...";`."""
    return body.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def main(argv: list[str]) -> int:
    specification_path = Path(argv[0]) if argv else DEFAULT_SPECIFICATION
    project_path = Path(argv[1]) if len(argv) > 1 else DEFAULT_PROJECT

    specification = specification_path.read_text()
    project = project_path.read_text()

    declared = specification_scripts(specification)
    generated = PBXPROJ_SCRIPT.findall(project)

    errors: list[str] = []
    expected = Counter(encode(body) for _, body in declared)
    actual = Counter(generated)
    for body, count in expected.items():
        if actual[body] != count:
            names = sorted({name for name, text in declared if encode(text) == body})
            errors.append(
                f"phase {names!r}: expected {count} copies, found {actual[body]}; "
                "regenerate the Xcode project with the pinned XcodeGen"
            )
    if actual - expected:
        errors.append("project carries unexpected or excess shellScript bodies; regenerate the Xcode project")

    if len(generated) != len(declared):
        errors.append(
            f"{specification_path.name} declares {len(declared)} script phase(s) but "
            f"{project_path.name} carries {len(generated)}"
        )

    if errors:
        print("XCODE SCRIPT PHASES FAIL")
        for error in errors:
            print(f"- {error}")
        return 1

    print(
        "XCODE SCRIPT PHASES PASS "
        f"declared={len(declared)} generated={len(generated)} "
        f"names={','.join(sorted({name for name, _ in declared}))}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
