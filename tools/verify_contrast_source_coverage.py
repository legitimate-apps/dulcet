#!/usr/bin/env python3

"""Require rendered content foregrounds to participate in the contrast catalog."""

from __future__ import annotations

import argparse
from pathlib import Path


WAIVER = "dulcet-contrast-waiver:"


class ContrastSourceCoverageError(RuntimeError):
    pass


def verify_source_root(source_root: Path) -> None:
    failures: list[str] = []
    for path in sorted(source_root.glob("*.swift")):
        for line_number, line in enumerate(path.read_text().splitlines(), start=1):
            if ".foregroundColor(" in line:
                failures.append(
                    f"{path.name}:{line_number}: foregroundColor bypasses DulcetRenderedContrastPair"
                )
            if ".foregroundStyle(" in line and WAIVER not in line:
                failures.append(
                    f"{path.name}:{line_number}: foregroundStyle bypasses DulcetRenderedContrastPair"
                )

    if failures:
        raise ContrastSourceCoverageError("\n".join(failures))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-root",
        type=Path,
        default=Path("apple/DulcetKit/Sources/DulcetKit"),
    )
    args = parser.parse_args()
    try:
        verify_source_root(args.source_root)
    except ContrastSourceCoverageError as error:
        raise SystemExit(f"CONTRAST SOURCE COVERAGE FAIL\n{error}") from error
    print(
        "CONTRAST SOURCE COVERAGE PASS "
        "content-foregrounds=catalog-backed explicit-waivers=decorative-or-control"
    )


if __name__ == "__main__":
    main()
