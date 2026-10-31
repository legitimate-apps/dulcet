#!/usr/bin/env python3
"""Pin reviewed ownership sources; fail when transport addresses enter ownership.

Run `python3 tools/verify_connection_ownership.py --self-test` after adding a
route integration. The JSON inventory is a code-review artifact, not generated
by CI: each changed ownership source needs a fresh source trace.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ROOTS = (
    "core/src/commonMain", "core/src/androidMain", "core/src/appleMain",
    "android/shared/src/main", "android/app/src/main", "android/tv/src/main",
    "apple/DulcetAppleShared", "apple/DulcetKit/Sources",
)
OWNERS = {"PlaybackEndpointAccount", "CacheBinding", "DownloadMediaBinding"}
SNAPSHOTS = {"AuthenticatedEndpointCredentials", "ArtworkFetchRequest", "PlaybackWireClient"}
EQUALITIES = {"sameEndpoint", "sameAccount", "savedServerEndpointMatches"}
NAMES = OWNERS | SNAPSHOTS | EQUALITIES
INVENTORY = ROOT / "tools/connection_ownership_inventory.json"


def mask_non_code(text: str) -> str:
    # Preserve positions, including interpolation. No identifier in a literal or
    # comment is a call. Kotlin raw strings and Swift multiline strings are masked.
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda m: re.sub(r"[^\n]", " ", m.group()), text)


def arguments(text: str, mask: str, opening: int) -> list[str]:
    depth = 0
    start = opening + 1
    result: list[str] = []
    for index in range(opening + 1, len(mask)):
        char = mask[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            if char == ")" and depth == 0:
                result.append(text[start:index].strip())
                return result
            depth -= 1
        elif char == "," and depth == 0:
            result.append(text[start:index].strip())
            start = index + 1
    raise ValueError("Unclosed source call")


def clean(expression: str) -> str:
    return re.sub(r"\s+", " ", expression).strip()


def source_rows(path: str, text: str) -> list[dict[str, str]]:
    mask = mask_non_code(text)
    rows = []
    for match in re.finditer(r"\b(" + "|".join(sorted(NAMES)) + r")\s*\(", mask):
        name = match.group(1)
        prefix = mask[max(0, match.start() - 80):match.start()]
        if re.search(r"\b(?:class|fun)\s*$", prefix):
            continue
        args = arguments(text, mask, match.end() - 1)
        if name in OWNERS | SNAPSHOTS:
            named = next((arg for arg in args if re.match(r"normalizedBaseUrl\s*[:=]", arg)), None)
            if named is not None:
                expression = re.split(r"[:=]", named, maxsplit=1)[1]
            elif name == "PlaybackWireClient":
                expression = args[0]
            elif name == "AuthenticatedEndpointCredentials":
                expression = args[0]
            elif name == "ArtworkFetchRequest":
                expression = args[3]
            else:
                expression = args[1]
            kind = "ownership" if name in OWNERS else "transport_snapshot"
        else:
            expression = ", ".join(args)
            kind = "equality"
        rows.append({"file": path, "kind": kind, "type": name, "source": clean(expression)})
    # Catch data-class ownership copies even though the receiver's static type
    # isn't available to this lightweight parser. No production path currently
    # needs to change an account/binding URL via copy; future uses need review.
    for match in re.finditer(r"\bcopy\s*\(", mask):
        for arg in arguments(text, mask, match.end() - 1):
            if re.match(r"normalizedBaseUrl\s*=", arg):
                rows.append({"file": path, "kind": "ownership_copy", "type": "copy", "source": clean(arg)})
    return rows


def collect(root: Path = ROOT) -> list[dict[str, str]]:
    result = []
    for directory in ROOTS:
        for path in sorted((root / directory).rglob("*")):
            if path.suffix in {".kt", ".swift"}:
                result.extend(source_rows(path.relative_to(root).as_posix(), path.read_text()))
    return result


def signature(row: dict[str, str]) -> str:
    return json.dumps(row, sort_keys=True)


def compare(actual: list[dict[str, str]], expected: list[dict[str, str]]) -> list[str]:
    found = Counter(map(signature, actual))
    reviewed = Counter(map(signature, expected))
    errors = [f"Unreviewed source ({count}): {row}" for row, count in (found - reviewed).items()]
    errors += [f"Reviewed source disappeared ({count}): {row}" for row, count in (reviewed - found).items()]
    return errors


def self_test() -> None:
    baseline = 'val owner = PlaybackEndpointAccount(id, account.normalizedBaseUrl, user, secret, false)'
    reviewed = source_rows("fixture.kt", baseline)
    assert not compare(source_rows("fixture.kt", baseline), reviewed)
    for kind, invalid in (
        ("route constructor", baseline.replace("account.normalizedBaseUrl", "lease.baseUrl")),
        ("route variable", baseline.replace("account.normalizedBaseUrl", "selectedAddress")),
        ("binding constructor", 'val binding = CacheBinding(id, lease.baseUrl, user)'),
        ("media constructor", 'val binding = DownloadMediaBinding(id, lease.baseUrl, user)'),
        ("copy", 'val owner = account.copy(normalizedBaseUrl = lease.baseUrl)'),
    ):
        assert compare(source_rows("fixture.kt", invalid), reviewed), kind
    assert not source_rows("fixture.kt", '// PlaybackEndpointAccount(id, lease.baseUrl, user)\nval s="CacheBinding(id, route, user)"')
    print("Ownership negative fixtures: 5 rejected; reviewed anchor positive accepted")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    reviewed = json.loads(INVENTORY.read_text())["sources"]
    actual = collect()
    errors = compare(actual, reviewed)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    counts = Counter(row["kind"] for row in actual)
    print("Ownership sources match reviewed inventory: " + ", ".join(f"{key}={value}" for key, value in sorted(counts.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
