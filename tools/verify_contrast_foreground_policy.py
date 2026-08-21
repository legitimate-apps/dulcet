#!/usr/bin/env python3

"""Require direct custom foreground modifiers to use the registered contrast policy.

Native controls, tint-driven styling, and implicit/default foregrounds are intentionally outside
this line-oriented source policy and are reported as such by its success diagnostic.
"""

from __future__ import annotations

import argparse
from pathlib import Path


WAIVER = "dulcet-contrast-waiver:"
ALLOWED_WAIVERS = {
    "catalog-applier",
    "decorative-artwork",
    "decorative-artwork-overlay",
    "deliberately-bad-control",
}


class ContrastForegroundPolicyError(RuntimeError):
    pass


def verify_source_root(source_root: Path) -> None:
    failures: list[str] = []
    for path in sorted(source_root.glob("*.swift")):
        for line_number, line in enumerate(path.read_text().splitlines(), start=1):
            if ".foregroundColor(" in line:
                failures.append(
                    f"{path.name}:{line_number}: foregroundColor bypasses "
                    "DulcetRegisteredContrastPair"
                )
            if ".foregroundStyle(" in line:
                if WAIVER not in line:
                    failures.append(
                        f"{path.name}:{line_number}: foregroundStyle bypasses "
                        "DulcetRegisteredContrastPair"
                    )
                else:
                    reason = line.partition(WAIVER)[2].strip()
                    if reason not in ALLOWED_WAIVERS:
                        failures.append(
                            f"{path.name}:{line_number}: unapproved contrast waiver: {reason}"
                        )

    if failures:
        raise ContrastForegroundPolicyError("\n".join(failures))


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
    except ContrastForegroundPolicyError as error:
        raise SystemExit(f"CONTRAST FOREGROUND POLICY FAIL\n{error}") from error
    print(
        "CONTRAST FOREGROUND POLICY PASS "
        "direct-foreground-modifiers=registry-or-approved-waiver "
        "native-control-and-tint-pairs=outside-policy"
    )


if __name__ == "__main__":
    main()
