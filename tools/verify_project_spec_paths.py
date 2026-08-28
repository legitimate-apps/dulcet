#!/usr/bin/env python3
"""Require every file path named in apple/project.yml to exist.

apple/project.yml is the source the Xcode project is generated from, but CI builds the COMMITTED
.xcodeproj and never runs xcodegen. So a path can rot in project.yml while every workflow stays
green, and the failure surfaces only when someone regenerates -- which is exactly what happened:
origin/main referenced Fixtures/realistic-tone.mp3, a file that is not tracked and does not exist,
so `xcodegen generate` failed on a clean checkout while CI was entirely green.

This checks the generator's own inputs, which is the thing nothing else looks at.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

SPEC = Path("apple/project.yml")
ROOT = SPEC.parent


def fail(message: str) -> None:
    print(f"project spec invalid: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if not SPEC.is_file():
        fail(f"{SPEC} is absent")
    missing: list[tuple[int, str]] = []
    checked = 0
    for number, line in enumerate(SPEC.read_text().splitlines(), start=1):
        match = re.match(r"\s*-?\s*path:\s*(\S+)\s*$", line)
        if match is None:
            continue
        raw = match.group(1).strip("\"'")
        # Only file paths carry a suffix; bare directory entries are groups and are allowed to be
        # created by the generator rather than to pre-exist.
        if not Path(raw).suffix:
            continue
        checked += 1
        if not (ROOT / raw).exists():
            missing.append((number, raw))
    if missing:
        rendered = "; ".join(f"{SPEC}:{n} -> {p}" for n, p in missing)
        fail(f"paths named in the project spec do not exist: {rendered}")
    print(f"project spec paths valid: {checked} file path(s) referenced by {SPEC} all exist")


if __name__ == "__main__":
    main()
