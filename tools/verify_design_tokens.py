#!/usr/bin/env python3
"""Hold view code to the design tokens: a view never gains a raw spacing or size literal.

`docs/DESIGN-CONVENTIONS.md` says every spacing, size, radius and font size in a view names a token
from a token file. This gate counts the literals that remain, per file and per rule, and compares
them with the committed baseline `tools/design-token-baseline.json`:

- a count above its baseline fails, and so does any literal in a file the baseline does not list;
- a count below its baseline also fails until the baseline is lowered in the same change, so a
  removed literal cannot quietly come back later. `--update-baseline` lowers it and refuses to
  raise anything;
- a literal that must stay carries `design-token: allow <reason>` in a comment on its own line, or
  sits between `design-tokens:begin <reason>` and `design-tokens:end` comment lines (a definition
  block such as a type scale).

What counts as a literal is a number other than 0, 0.5 and 1 (hairlines and "nothing") written
where a spacing or size goes. A number next to `*` or `/` is a multiplier, not a size, and is not
counted. Comments and string contents are ignored.

Token declarations are exempt: on Apple, the bodies of `DulcetSpacing` and of every type or
extension named `Dulcet...Metrics`; on Android, the token files `DulcetDimens.kt`, `DulcetTheme.kt`
and `TvDimens.kt`.

    python3 tools/verify_design_tokens.py                    # the gate (parity-gate runs this)
    python3 tools/verify_design_tokens.py --platform apple   # one platform's files only
    python3 tools/verify_design_tokens.py --list FILE...     # every counted literal in FILE
    python3 tools/verify_design_tokens.py --update-baseline  # lower the baseline after removing literals
    python3 tools/verify_design_tokens.py --self-test        # prove each rule, waiver and ratchet case
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile


REPO = Path(__file__).resolve().parent.parent
BASELINE_NAME = "tools/design-token-baseline.json"
SCHEMA = 1

# Where view code lives. Test targets, capture tools and preview-only hosts are out of scope: their
# fixed geometry describes a capture window, not a screen.
SCAN_ROOTS = {
    "apple": (
        "apple/DulcetKit/Sources/DulcetKit",
        "apple/DulcetAppleShared",
        "apple/DulcetiOS",
        "apple/DulcetMac",
        "apple/DulcetTV",
        "apple/DulcetWidgets",
        "apple/DulcetSystemSurfaces",
    ),
    "android": (
        "android/app/src/main",
        "android/tv/src/main",
        "android/shared/src/main",
    ),
}
SUFFIX = {"apple": ".swift", "android": ".kt"}

# Android token files: every layout value a screen uses is declared here, so nothing in them counts.
ANDROID_TOKEN_FILES = frozenset({
    "android/app/src/main/kotlin/com/legitimateapps/dulcet/DulcetDimens.kt",
    "android/app/src/main/kotlin/com/legitimateapps/dulcet/DulcetTheme.kt",
    "android/tv/src/main/kotlin/com/legitimateapps/dulcet/tv/TvDimens.kt",
})

# Apple token scopes: the body of a type or extension with one of these names, wherever it lives.
APPLE_TOKEN_SCOPE = re.compile(
    r"^[ \t]*(?:@\w+[ \t]+)*(?:(?:public|internal|private|fileprivate)[ \t]+)?"
    r"(?:enum|struct|extension)[ \t]+(?P<name>DulcetSpacing|Dulcet\w*Metrics)\b[^{\n]*\{",
    re.M,
)

ALLOW = re.compile(r"design-token:\s*allow\b(?P<reason>.*)|design-tokens:(?P<kind>begin|end)\b(?P<range_reason>.*)")
MIN_REASON = 8

EXEMPT_VALUES = {0.0, 0.5, 1.0}
NUMBER = re.compile(r"(?<![\w.$#])(?P<n>\d+(?:\.\d+)?)(?:[fF])?(?![\w])")

# Apple rule order matters: a literal claimed by an earlier rule is not counted again by a later one.
APPLE_RULES = ("edge-insets", "stack-spacing", "padding", "frame", "offset", "corner-radius",
               "font-size", "artwork-size", "margins", "grid-item", "scaled-metric", "local-metric")
ANDROID_RULES = ("dp", "sp", "font-scale")


class GateError(RuntimeError):
    pass


@dataclass(frozen=True)
class Literal:
    path: str
    line: int
    rule: str
    text: str


# ---------------------------------------------------------------------------------------------
# Source masking: comments and string contents become spaces, so line and column positions hold.


def mask_source(text: str, platform: str) -> tuple[str, dict[int, list[str]]]:
    """Blank out comments and string contents. Returns the masked text and each line's comments."""
    out = list(text)
    comments: dict[int, list[str]] = {}
    line = 1
    i = 0
    n = len(text)
    # Each frame is ("code", closer_depth) or ("string", delimiter). Interpolations push code frames.
    stack: list[tuple[str, object]] = [("code", None)]
    interp_depth: list[int] = []

    def blank(start: int, end: int) -> None:
        for k in range(start, end):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        kind, data = stack[-1]
        ch = text[i]
        if kind == "code":
            if text.startswith("//", i):
                end = text.find("\n", i)
                end = n if end == -1 else end
                comments.setdefault(line, []).append(text[i + 2:end])
                blank(i, end)
                i = end
                continue
            if text.startswith("/*", i):
                depth = 0
                j = i
                start_line = line
                while j < n:
                    if text.startswith("/*", j):
                        depth += 1
                        j += 2
                    elif text.startswith("*/", j):
                        depth -= 1
                        j += 2
                        if depth == 0:
                            break
                    else:
                        if text[j] == "\n":
                            line += 1
                        j += 1
                comments.setdefault(start_line, []).append(text[i + 2:j - 2])
                blank(i, j)
                i = j
                continue
            if platform == "apple" and ch == "#" and re.match(r'#+"', text[i:]):
                hashes = len(re.match(r"#+", text[i:]).group(0))
                quote = '"""' if text.startswith('"""', i + hashes) else '"'
                closer = quote + "#" * hashes
                end = text.find(closer, i + hashes + len(quote))
                end = n if end == -1 else end + len(closer)
                line += text.count("\n", i, end)
                blank(i, end)
                i = end
                continue
            if text.startswith('"""', i):
                stack.append(("string", '"""'))
                i += 3
                continue
            if ch == '"':
                stack.append(("string", '"'))
                i += 1
                continue
            if platform == "android" and ch == "'":
                m = re.match(r"'(?:\\.|[^'\\])*'", text[i:])
                if m:
                    blank(i, i + len(m.group(0)))
                    i += len(m.group(0))
                    continue
            if interp_depth:
                if ch in "({":
                    interp_depth[-1] += 1
                elif ch in ")}":
                    interp_depth[-1] -= 1
                    if interp_depth[-1] == 0:
                        interp_depth.pop()
                        stack.pop()
                        out[i] = " "
                        i += 1
                        continue
            if ch == "\n":
                line += 1
            i += 1
            continue
        # Inside a string.
        delimiter = data
        if ch == "\\" and platform == "apple" and i + 1 < n and text[i + 1] == "(":
            blank(i, i + 2)
            stack.append(("code", None))
            interp_depth.append(1)
            i += 2
            continue
        if ch == "$" and platform == "android" and i + 1 < n and text[i + 1] == "{":
            blank(i, i + 2)
            stack.append(("code", None))
            interp_depth.append(1)
            i += 2
            continue
        if ch == "\\" and delimiter == '"':
            blank(i, min(i + 2, n))
            i += 2
            continue
        if text.startswith(delimiter, i):
            stack.pop()
            i += len(delimiter)
            continue
        if ch == "\n":
            line += 1
            if delimiter == '"':  # an unterminated one-line string; recover at the line end
                stack.pop()
            i += 1
            continue
        out[i] = " "
        i += 1
    return "".join(out), comments


# ---------------------------------------------------------------------------------------------
# Small parsing helpers over masked text.


OPEN = "([{"
CLOSE = ")]}"


def matching_close(text: str, open_index: int) -> int:
    depth = 0
    for k in range(open_index, len(text)):
        if text[k] in OPEN:
            depth += 1
        elif text[k] in CLOSE:
            depth -= 1
            if depth == 0:
                return k
    return len(text)


def value_end(text: str, start: int) -> int:
    """End of an argument value that starts at `start`: the next top-level `,`, closer or line end."""
    depth = 0
    seen = False
    for k in range(start, len(text)):
        c = text[k]
        if c in OPEN:
            depth += 1
        elif c in CLOSE:
            if depth == 0:
                return k
            depth -= 1
        elif depth == 0 and (c == "," or (c == "\n" and seen)):
            return k
        if not c.isspace():
            seen = True
    return len(text)


def size_numbers(text: str, start: int, end: int) -> list[int]:
    """Offsets of counted numbers in text[start:end]: not exempt, not a multiplier or divisor."""
    found = []
    for m in NUMBER.finditer(text, start, end):
        if float(m.group("n")) in EXEMPT_VALUES:
            continue
        before = text[start:m.start()].rstrip()
        after = text[m.end():end].lstrip()
        if before.endswith(("*", "/")) or after.startswith(("*", "/")):
            continue
        found.append(m.start())
    return found


def call_spans(text: str, pattern: re.Pattern[str]) -> list[tuple[int, int]]:
    """(start, end) of the argument list of every call whose opening is matched by `pattern`."""
    spans = []
    for m in pattern.finditer(text):
        open_index = m.end() - 1
        spans.append((open_index + 1, matching_close(text, open_index)))
    return spans


def labelled_values(text: str, start: int, end: int, labels: str) -> list[tuple[int, int]]:
    pattern = re.compile(rf"(?<![\w.])(?:{labels})\s*:")
    return [(m.end(), value_end(text, m.end())) for m in pattern.finditer(text, start, end)]


# ---------------------------------------------------------------------------------------------
# Rules.


def apple_spans(masked: str) -> dict[str, list[tuple[int, int]]]:
    spans: dict[str, list[tuple[int, int]]] = {}
    spans["edge-insets"] = call_spans(masked, re.compile(r"\bEdgeInsets\s*\("))
    spans["stack-spacing"] = labelled_values(
        masked, 0, len(masked), r"spacing|horizontalSpacing|verticalSpacing|minLength")
    spans["stack-spacing"] += call_spans(masked, re.compile(r"\.(?:listRowSpacing|listSectionSpacing)\s*\("))
    spans["padding"] = call_spans(masked, re.compile(r"\.padding\s*\("))
    spans["frame"] = [
        span
        for start, end in call_spans(masked, re.compile(r"\.frame\s*\("))
        for span in labelled_values(
            masked, start, end,
            r"width|height|minWidth|idealWidth|maxWidth|minHeight|idealHeight|maxHeight")
    ]
    spans["offset"] = call_spans(masked, re.compile(r"\.offset\s*\("))
    spans["corner-radius"] = labelled_values(masked, 0, len(masked), r"cornerRadius")
    spans["corner-radius"] += call_spans(masked, re.compile(r"\.cornerRadius\s*\("))
    spans["corner-radius"] += [
        (m.end(), value_end(masked, m.end())) for m in re.finditer(r"\.cornerRadius\s*=(?!=)", masked)]
    spans["font-size"] = [
        span
        for start, end in call_spans(masked, re.compile(r"\.(?:system|custom)\s*\("))
        for span in labelled_values(masked, start, end, r"size|fixedSize")
    ]
    spans["artwork-size"] = [
        span
        for start, end in call_spans(masked, re.compile(r"\b\w*Artwork\w*\s*\("))
        for span in labelled_values(masked, start, end, r"size")
    ]
    spans["margins"] = call_spans(
        masked, re.compile(r"\.(?:safeAreaPadding|contentMargins|scenePadding|listRowInsets)\s*\("))
    spans["grid-item"] = call_spans(masked, re.compile(r"\bGridItem\s*\("))
    spans["scaled-metric"] = [
        (m.end(), value_end(masked, m.end()))
        for m in re.finditer(r"@ScaledMetric\b[^\n=]*=", masked)
    ]
    spans["local-metric"] = [
        (m.end(), value_end(masked, m.end()))
        for m in re.finditer(r"(?<![\w.])\w+\s*:\s*CGFloat\??\s*=(?!=)", masked)
    ]
    return spans


def apple_token_ranges(masked: str) -> list[tuple[int, int]]:
    ranges = []
    for m in APPLE_TOKEN_SCOPE.finditer(masked):
        open_index = m.end() - 1
        ranges.append((open_index, matching_close(masked, open_index)))
    return ranges


ANDROID_PATTERNS = {
    # 12.dp, 12f.dp is not Kotlin but (12).dp, (-12).dp, (x + 4).dp and Dp(12f) are.
    "dp": (
        re.compile(r"(?<![\w.])(?P<n>\d+(?:\.\d+)?)[fF]?\s*\)?\s*\.dp\b"),
        re.compile(r"\bDp\(\s*-?\s*(?P<n>\d+(?:\.\d+)?)[fF]?\s*\)"),
    ),
    "sp": (
        re.compile(r"(?<![\w.])(?P<n>\d+(?:\.\d+)?)[fF]?\s*\)?\s*\.sp\b"),
    ),
    "font-scale": (
        re.compile(r"\bfontScale\s*(?:>=|>|<=|<)\s*(?P<n>\d+(?:\.\d+)?)"),
        re.compile(r"(?P<n>\d+(?:\.\d+)?)[fF]?\s*(?:>=|>|<=|<)\s*[\w.]*fontScale\b"),
    ),
}


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def allow_lines(comments: dict[int, list[str]], path: str, problems: list[str]) -> tuple[set[int], list[tuple[int, int]]]:
    """Lines waived one at a time, and waived (begin, end) line ranges. Bad markers become problems."""
    single: set[int] = set()
    ranges: list[tuple[int, int]] = []
    open_line: int | None = None
    for line in sorted(comments):
        for comment in comments[line]:
            m = ALLOW.search(comment)
            if not m:
                continue
            kind = m.group("kind")
            reason = (m.group("reason") if kind is None else m.group("range_reason")).strip()
            if kind == "end":
                if open_line is None:
                    problems.append(f"{path}:{line}: design-tokens:end without a matching design-tokens:begin")
                else:
                    ranges.append((open_line, line))
                    open_line = None
                continue
            if len(reason) < MIN_REASON:
                problems.append(
                    f"{path}:{line}: design-token waiver needs a reason of at least {MIN_REASON} characters: "
                    + ("`design-token: allow <why this number has no token>`" if kind is None
                       else "`design-tokens:begin <why this block keeps its numbers>`"))
                continue
            if kind == "begin":
                if open_line is not None:
                    problems.append(f"{path}:{line}: design-tokens:begin inside another range (opened on line {open_line})")
                else:
                    open_line = line
            else:
                single.add(line)
    if open_line is not None:
        problems.append(f"{path}:{open_line}: design-tokens:begin never closed in this file")
    return single, ranges


@dataclass
class FileScan:
    literals: list[Literal]
    waived: int
    problems: list[str]


def scan_text(path: str, text: str, platform: str) -> FileScan:
    problems: list[str] = []
    masked, comments = mask_source(text, platform)
    single, ranges = allow_lines(comments, path, problems)
    raw_lines = text.splitlines()

    def source_line(line: int) -> str:
        return raw_lines[line - 1].strip() if line - 1 < len(raw_lines) else ""

    candidates: list[tuple[int, str]] = []  # (offset, rule)
    if platform == "apple":
        token_ranges = apple_token_ranges(masked)
        claimed: set[int] = set()
        spans = apple_spans(masked)
        for rule in APPLE_RULES:
            for start, end in spans[rule]:
                for offset in size_numbers(masked, start, end):
                    if offset in claimed:
                        continue
                    claimed.add(offset)
                    if any(lo < offset < hi for lo, hi in token_ranges):
                        continue
                    candidates.append((offset, rule))
    else:
        if path in ANDROID_TOKEN_FILES:
            return FileScan([], 0, problems)
        for rule in ANDROID_RULES:
            seen: set[int] = set()
            for pattern in ANDROID_PATTERNS[rule]:
                for m in pattern.finditer(masked):
                    offset = m.start("n")
                    if offset in seen:
                        continue
                    seen.add(offset)
                    if rule != "font-scale" and float(m.group("n")) in EXEMPT_VALUES:
                        continue
                    candidates.append((offset, rule))

    literals: list[Literal] = []
    waived_lines: set[int] = set()
    waived = 0
    for offset, rule in sorted(candidates):
        line = line_of(masked, offset)
        if line in single or any(lo <= line <= hi for lo, hi in ranges):
            waived += 1
            waived_lines.add(line)
            continue
        literals.append(Literal(path, line, rule, source_line(line)))
    for line in sorted(single - waived_lines):
        problems.append(f"{path}:{line}: design-token: allow waives nothing on this line; remove it")
    return FileScan(literals, waived, problems)


# ---------------------------------------------------------------------------------------------
# Repository scan, baseline and ratchet.


def platform_of(path: str) -> str:
    return "apple" if path.startswith("apple/") else "android"


def source_files(root: Path, platforms: tuple[str, ...]) -> list[str]:
    files = []
    for platform in platforms:
        for scan_root in SCAN_ROOTS[platform]:
            base = root / scan_root
            if base.is_dir():
                files.extend(
                    p.relative_to(root).as_posix() for p in base.rglob("*" + SUFFIX[platform]) if p.is_file())
    return sorted(set(files))


@dataclass
class RepoScan:
    counts: dict[str, Counter]
    literals: dict[str, list[Literal]]
    waived: Counter
    problems: list[str]
    root: Path | None = None


def scan_repo(root: Path, platforms: tuple[str, ...]) -> RepoScan:
    counts: dict[str, Counter] = {}
    literals: dict[str, list[Literal]] = {}
    waived: Counter = Counter()
    problems: list[str] = []
    for path in source_files(root, platforms):
        result = scan_text(path, (root / path).read_text(encoding="utf-8"), platform_of(path))
        problems.extend(result.problems)
        waived[platform_of(path)] += result.waived
        if result.literals:
            literals[path] = result.literals
            counts[path] = Counter(literal.rule for literal in result.literals)
    return RepoScan(counts, literals, waived, problems, root)


def load_baseline(root: Path) -> dict[str, dict[str, int]]:
    path = root / BASELINE_NAME
    if not path.exists():
        raise GateError(f"{BASELINE_NAME} is missing; it is committed with this tool")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != SCHEMA or not isinstance(data.get("files"), dict):
        raise GateError(f"{BASELINE_NAME}: expected schema {SCHEMA} with a `files` object")
    return {file: dict(rules) for file, rules in data["files"].items()}


def write_baseline(root: Path, counts: dict[str, Counter]) -> None:
    files = {
        path: {rule: counts[path][rule] for rule in sorted(counts[path]) if counts[path][rule] > 0}
        for path in sorted(counts)
        if any(counts[path].values())
    }
    data = {
        "schema": SCHEMA,
        "about": "Raw spacing and size literals left in view code, per file and rule. Written only by "
                 "tools/verify_design_tokens.py --update-baseline, which lowers counts and never raises "
                 "them. See docs/DESIGN-CONVENTIONS.md.",
        "files": files,
    }
    (root / BASELINE_NAME).write_text(json.dumps(data, indent=2, sort_keys=False) + "\n", encoding="utf-8")


def in_platforms(path: str, platforms: tuple[str, ...]) -> bool:
    return platform_of(path) in platforms


def added_lines(root: Path, path: str) -> set[int] | None:
    """Lines of `path` added since the branch left its base, or None when git cannot say."""
    import os
    base_ref = "origin/" + (os.environ.get("GITHUB_BASE_REF") or "main")
    try:
        base = subprocess.run(["git", "-C", str(root), "merge-base", "HEAD", base_ref],
                              capture_output=True, text=True, check=True).stdout.strip()
        diff = subprocess.run(["git", "-C", str(root), "diff", "-U0", base, "--", path],
                              capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    lines: set[int] = set()
    for m in re.finditer(r"^@@ -\S+ \+(\d+)(?:,(\d+))? @@", diff, re.M):
        start, count = int(m.group(1)), int(m.group(2) or "1")
        lines.update(range(start, start + count))
    return lines


def compare(scan: RepoScan, baseline: dict[str, dict[str, int]], platforms: tuple[str, ...]) -> tuple[list[str], list[str]]:
    """(raised, stale): counts above the baseline, and counts below it."""
    raised: list[str] = []
    stale: list[str] = []
    paths = sorted(set(scan.counts) | {p for p in baseline if in_platforms(p, platforms)})
    for path in paths:
        current = scan.counts.get(path, Counter())
        allowed = baseline.get(path, {})
        for rule in sorted(set(current) | set(allowed)):
            have, may = current.get(rule, 0), allowed.get(rule, 0)
            if have > may:
                lines = [lit for lit in scan.literals.get(path, []) if lit.rule == rule]
                where = "a file the baseline does not list starts at 0" if path not in baseline else f"baseline {may}"
                block = [f"{path}: {rule} {have} > {may} ({where}). Use a token, or waive the line with "
                         f"`design-token: allow <reason>`:"]
                fresh = added_lines(scan.root, path) if scan.root else None
                shown = [lit for lit in lines if fresh and lit.line in fresh] or lines
                block += [f"  {lit.path}:{lit.line}: {lit.rule}: {lit.text}" for lit in shown]
                if len(shown) < len(lines):
                    block.append(f"  ({len(lines) - len(shown)} older {rule} literal(s) in this file not shown; "
                                 f"--list {path} shows every one)")
                raised.append("\n".join(block))
            elif have < may:
                stale.append(f"{path}: {rule} {have} < baseline {may}")
    return raised, stale


def totals(counts: dict[str, Counter], platform: str) -> int:
    return sum(sum(c.values()) for path, c in counts.items() if platform_of(path) == platform)


def baseline_totals(baseline: dict[str, dict[str, int]], platform: str) -> int:
    return sum(sum(r.values()) for path, r in baseline.items() if platform_of(path) == platform)


def run_check(root: Path, platforms: tuple[str, ...], out=sys.stdout, err=sys.stderr) -> int:
    baseline = load_baseline(root)
    scan = scan_repo(root, platforms)
    raised, stale = compare(scan, baseline, platforms)
    failures = list(scan.problems)
    failures += raised
    if stale:
        failures.append(
            "Literals were removed but the baseline still allows them. Lock the gain in with\n"
            "  python3 tools/verify_design_tokens.py --update-baseline\n"
            "and commit tools/design-token-baseline.json in the same change:\n  "
            + "\n  ".join(stale))
    summary = "; ".join(
        f"{platform} literals={totals(scan.counts, platform)} baseline={baseline_totals(baseline, platform)} "
        f"files={sum(1 for p in scan.counts if platform_of(p) == platform)} waived={scan.waived[platform]}"
        for platform in platforms)
    if failures:
        print("\n".join(failures), file=err)
        print(f"design-tokens: FAIL ({summary})", file=err)
        return 1
    print(f"design-tokens: OK ({summary})", file=out)
    return 0


def run_update(root: Path, out=sys.stdout, err=sys.stderr) -> int:
    platforms = tuple(SCAN_ROOTS)
    baseline = load_baseline(root)
    scan = scan_repo(root, platforms)
    raised, stale = compare(scan, baseline, platforms)
    if raised:
        print("\n".join(raised), file=err)
        print("design-tokens: --update-baseline refuses to raise a count. Use a token or a waiver.", file=err)
        return 1
    write_baseline(root, scan.counts)
    print(f"design-tokens: baseline lowered ({len(stale)} count(s)); "
          + "; ".join(f"{p} literals={totals(scan.counts, p)}" for p in platforms), file=out)
    return 0


def run_list(root: Path, paths: list[str], out=sys.stdout) -> int:
    for raw in paths:
        path = Path(raw).resolve()
        try:
            rel = path.relative_to(root).as_posix()
        except ValueError:
            rel = raw
        result = scan_text(rel, path.read_text(encoding="utf-8"), platform_of(rel))
        for lit in result.literals:
            print(f"{lit.path}:{lit.line}: {lit.rule}: {lit.text}", file=out)
        print(f"{rel}: {len(result.literals)} literal(s), {result.waived} waived", file=out)
    return 0


# ---------------------------------------------------------------------------------------------
# Self-test.


SWIFT_CASES = [
    # (name, source, expected rule counts)
    ("padding forms", """
        .padding(13)
        .padding(.top, 6)
        .padding([.leading, .trailing], -4)
        .padding(.horizontal, DulcetSpacing.md + 2)
        .padding(DulcetSpacing.md)
        .padding(.horizontal)
        .padding(0)
        """, {"padding": 4}),
    ("edge insets claim their numbers once", """
        .padding(EdgeInsets(top: 2, leading: 16, bottom: 0, trailing: 16))
        .listRowInsets(EdgeInsets(top: 4, leading: DulcetSpacing.md, bottom: 4, trailing: 0))
        """, {"edge-insets": 5}),
    ("stack and grid spacing", """
        VStack(alignment: .leading, spacing: 6) {}
        HStack(spacing: DulcetSpacing.xs) {}
        LazyVGrid(columns: columns, spacing: 10) {}
        Grid(horizontalSpacing: 12, verticalSpacing: 0) {}
        Spacer(minLength: 20)
        List {}.listRowSpacing(3)
        VStack(spacing: 0) {}
        """, {"stack-spacing": 5}),
    ("frame labels, ternaries, multipliers and exempt values", """
        .frame(width: 44, height: 44)
        .frame(maxWidth: .infinity, minHeight: 1)
        .frame(width: size * 0.5, height: size / 3)
        .frame(width: compact ? 120 : 160)
        .frame(width: 0.5)
        .frame(width: DulcetMetrics.rowArtworkSize, alignment: .leading)
        """, {"frame": 4}),
    ("a frame argument on its own line counts on that line", """
        .frame(
            width: DulcetMetrics.rowArtworkSize,
            height: 30
        )
        """, {"frame": 1}),
    ("offset, corner radius, font size", """
        .offset(x: 3, y: -2)
        RoundedRectangle(cornerRadius: 10, style: .continuous)
        .cornerRadius(8)
        .clipShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius))
        .font(.system(size: 34, weight: .semibold))
        .font(.system(.body))
        """, {"offset": 2, "corner-radius": 2, "font-size": 1}),
    ("margins and grid items", """
        .safeAreaPadding(.horizontal, 24)
        .contentMargins(.horizontal, margin, for: .scrollContent)
        .contentMargins(.vertical, 12, for: .scrollContent)
        GridItem(.adaptive(minimum: 150, maximum: 200), spacing: 16)
        GridItem(.fixed(90))
        GridItem(.flexible())
        """, {"margins": 2, "grid-item": 3, "stack-spacing": 1}),
    ("scaled metrics and private CGFloat constants", """
        @ScaledMetric(relativeTo: .body) private var iconSize = 28
        @ScaledMetric private var gap: CGFloat = DulcetSpacing.sm
        private let headerHeight: CGFloat = 64
        static let ratio: Double = 4
        init(regularMinimum: CGFloat = 180, compact: CGFloat? = nil) {}
        """, {"scaled-metric": 1, "local-metric": 2}),
    ("artwork sizes and layer radii", """
        DulcetArtworkView(artwork: item.artwork, size: 88, role: .cover)
        DulcetPlayerQueueArtwork(artwork: track.artwork, size: DulcetMetrics.rowArtworkSize)
        DulcetArtworkView(artwork: album.artwork, size: tileWidth ?? 126)
        view.layer.cornerRadius = presenting ? 24 : 0
        """, {"artwork-size": 2, "corner-radius": 1}),
    ("comments and strings never count", """
        // .padding(13) in a comment
        /* .frame(width: 44) in a block /* nested .padding(9) */ still comment */
        Text("Use .padding(13) and spacing: 9")
        Text("value \\(model.count) .frame(width: 12)")
        let s = \"\"\"
            .padding(14)
            \"\"\"
        """, {}),
    ("an interpolation is code, its literal counts", """
        Text("\\(Text("x").padding(5))")
        """, {"padding": 1}),
    ("token scopes are exempt", """
        enum DulcetSpacing {
            static let xs: CGFloat = 8
        }
        extension DulcetMetrics {
            static let shelfTileWidth: CGFloat = 160
            static func rowMinHeight(artwork: Bool) -> CGFloat { artwork ? 56 : 44 }
        }
        struct DulcetSongRowMetrics: DynamicProperty {
            @ScaledMetric(relativeTo: .body) private var art = 48
        }
        struct NotATokenType {
            private let raw: CGFloat = 12
        }
        """, {"local-metric": 1}),
    ("line waivers and ranges", """
        .padding(.top, 2) // design-token: allow optical baseline of the glyph
        .frame(width: 13) // design-token: allow matches the system toggle width
        // design-tokens:begin capture window geometry for previews
        .frame(width: 1180, height: 732)
        .padding(30)
        // design-tokens:end
        .padding(13)
        """, {"padding": 1}),
]

KOTLIN_CASES = [
    ("dp forms", """
        Modifier.padding(13.dp)
        Modifier.size(48.dp).offset(x = (-12).dp)
        Modifier.height(0.dp).border(1.dp, color).width(0.5.dp)
        Spacer(Modifier.height(DulcetSpacing.l))
        val w = Dp(24f)
        val x = (base + 4).dp
        """, {"dp": 5}),
    ("sp and font scale", """
        fontSize = 14.sp
        if (LocalDensity.current.fontScale >= 1.3f) {}
        val big = 1.5f <= density.fontScale
        """, {"sp": 1, "font-scale": 2}),
    ("comments, strings and chars never count", """
        // Modifier.padding(13.dp)
        /* 16.dp */
        val label = "12.dp ${'$'}{x}"
        val c = '1'
        """, {}),
    ("a template expression is code", """
        Text("${Modifier.padding(7.dp)}")
        """, {"dp": 1}),
    ("waivers", """
        Modifier.padding(top = 2.dp) // design-token: allow optical nudge onto the baseline
        // design-tokens:begin type scale definitions live here
        val a = 22.sp
        val b = 40.dp
        // design-tokens:end
        Modifier.padding(13.dp)
        """, {"dp": 1}),
]


def _check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def _dedent(source: str) -> str:
    import textwrap
    return textwrap.dedent(source).strip("\n") + "\n"


def _counts(path: str, source: str, platform: str) -> FileScan:
    return scan_text(path, _dedent(source), platform)


def self_test() -> None:
    proven = 0
    for platform, cases, path in (("apple", SWIFT_CASES, "apple/DulcetKit/Sources/DulcetKit/Fixture.swift"),
                                  ("android", KOTLIN_CASES, "android/app/src/main/kotlin/Fixture.kt")):
        for name, source, expected in cases:
            result = _counts(path, source, platform)
            actual = dict(Counter(lit.rule for lit in result.literals))
            _check(actual == expected, f"{platform} '{name}': expected {expected}, counted {actual}: "
                   + "; ".join(f"{l.line}:{l.rule}" for l in result.literals))
            _check(not result.problems, f"{platform} '{name}': unexpected problems {result.problems}")
            proven += 1

    # The multi-line frame literal is reported on the line it is written on.
    multi = _counts("apple/DulcetKit/Sources/DulcetKit/F.swift", SWIFT_CASES[4][1], "apple")
    _check([l.line for l in multi.literals] == [3], f"multi-line frame reported on {[l.line for l in multi.literals]}")
    # Waivers were met, not merely tolerated: each case waived exactly the lines it marks.
    waived_swift = _counts("apple/DulcetKit/Sources/DulcetKit/F.swift", SWIFT_CASES[-1][1], "apple")
    _check(waived_swift.waived == 5 and [l.line for l in waived_swift.literals] == [7],
           f"swift waivers: waived={waived_swift.waived} left={[l.line for l in waived_swift.literals]}")
    waived_kotlin = _counts("android/app/src/main/kotlin/F.kt", KOTLIN_CASES[-1][1], "android")
    _check(waived_kotlin.waived == 3, f"kotlin waivers: waived={waived_kotlin.waived}")

    # Bad waivers are problems in their own right.
    for source, needle in (
        (".padding(13) // design-token: allow\n", "needs a reason"),
        (".padding(13) // design-token: allow ok\n", "needs a reason"),
        (".padding(DulcetSpacing.md) // design-token: allow no literal on this line\n", "waives nothing"),
        ("// design-tokens:begin an unclosed range here\n.padding(13)\n", "never closed"),
        ("// design-tokens:end\n", "without a matching"),
        ("// design-tokens:begin first range opens\n// design-tokens:begin second range opens\n"
         "// design-tokens:end\n", "inside another range"),
    ):
        result = scan_text("apple/DulcetKit/Sources/DulcetKit/F.swift", source, "apple")
        _check(any(needle in p for p in result.problems), f"waiver problem '{needle}' not reported: {result.problems}")
        proven += 1

    # Android token files are exempt by path; the same text elsewhere counts.
    token_path = sorted(ANDROID_TOKEN_FILES)[0]
    _check(not scan_text(token_path, "val s = 16.dp\n", "android").literals, "android token file not exempt")
    _check(len(scan_text("android/app/src/main/kotlin/X.kt", "val s = 16.dp\n", "android").literals) == 1,
           "android screen file literal not counted")
    proven += 2

    # The ratchet, end to end through the command line, in a scratch repository.
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        swift = root / "apple/DulcetKit/Sources/DulcetKit/View.swift"
        kotlin = root / "android/app/src/main/kotlin/com/legitimateapps/dulcet/Screen.kt"
        swift.parent.mkdir(parents=True)
        kotlin.parent.mkdir(parents=True)
        (root / "tools").mkdir()
        swift.write_text(".padding(13)\n.frame(width: 44)\n")
        kotlin.write_text("Modifier.padding(13.dp)\n")

        def cli(*args: str) -> tuple[int, str]:
            proc = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--root", str(root), *args],
                                  capture_output=True, text=True)
            return proc.returncode, proc.stdout + proc.stderr

        code, output = cli()
        _check(code == 1 and "baseline.json is missing" in output, f"missing baseline: {code} {output}")
        code, output = cli("--write-initial-baseline")
        _check(code == 0, f"initial baseline: {output}")
        code, output = cli("--write-initial-baseline")
        _check(code == 1 and "already exists" in output, "initial baseline overwrote an existing one")
        baseline = json.loads((root / BASELINE_NAME).read_text())["files"]
        _check(baseline == {
            "android/app/src/main/kotlin/com/legitimateapps/dulcet/Screen.kt": {"dp": 1},
            "apple/DulcetKit/Sources/DulcetKit/View.swift": {"frame": 1, "padding": 1},
        }, f"baseline content {baseline}")
        code, output = cli()
        _check(code == 0 and "apple literals=2 baseline=2" in output and "android literals=1 baseline=1" in output,
               f"clean check: {code} {output}")

        # A raised count fails and names the line; --update-baseline refuses it and writes nothing.
        swift.write_text(".padding(13)\n.frame(width: 44)\n.padding(.top, 13)\n")
        before = (root / BASELINE_NAME).read_text()
        code, output = cli()
        _check(code == 1 and "padding 2 > 1" in output and "View.swift:3: padding" in output, f"raise: {output}")
        code, output = cli("--update-baseline")
        _check(code == 1 and "refuses to raise" in output, f"update raise: {output}")
        _check((root / BASELINE_NAME).read_text() == before, "a refused update changed the baseline")

        # The waiver passes the same line.
        swift.write_text(".padding(13)\n.frame(width: 44)\n.padding(.top, 13) // design-token: allow scratch proof of the waiver\n")
        code, output = cli()
        _check(code == 0 and "waived=1" in output, f"waiver: {output}")

        # A new file starts at 0.
        (swift.parent / "New.swift").write_text(".frame(height: 30)\n")
        code, output = cli()
        _check(code == 1 and "New.swift: frame 1 > 0 (a file the baseline does not list starts at 0)" in output,
               f"new file: {output}")
        (swift.parent / "New.swift").unlink()

        # A lowered count fails until the baseline is lowered; then it passes and cannot rise again.
        kotlin.write_text("Modifier.padding(DulcetSpacing.l)\n")
        code, output = cli()
        _check(code == 1 and "dp 0 < baseline 1" in output and "--update-baseline" in output, f"stale: {output}")
        code, output = cli("--update-baseline")
        _check(code == 0, f"update lower: {output}")
        _check("android/app/src/main/kotlin/com/legitimateapps/dulcet/Screen.kt"
               not in json.loads((root / BASELINE_NAME).read_text())["files"], "zero entry kept")
        code, output = cli()
        _check(code == 0, f"after lowering: {output}")
        kotlin.write_text("Modifier.padding(13.dp)\n")
        code, output = cli()
        _check(code == 1 and "dp 1 > 0" in output, f"regained literal: {output}")
        kotlin.write_text("Modifier.padding(DulcetSpacing.l)\n")

        # --platform narrows both the scan and the baseline comparison.
        code, output = cli("--platform", "android")
        _check(code == 0 and "apple" not in output.split("(", 1)[1], f"platform filter: {output}")
        proven += 9

    print(f"design-tokens self-test: {proven} cases proven "
          f"({len(SWIFT_CASES)} Swift and {len(KOTLIN_CASES)} Kotlin rule fixtures with exact counts, "
          "waiver faults, token exemptions, ratchet raise/new-file/stale/refused-update)")


# ---------------------------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--platform", choices=("apple", "android", "all"), default="all")
    parser.add_argument("--update-baseline", action="store_true",
                        help="lower the baseline to the current counts; refuses to raise any count")
    parser.add_argument("--write-initial-baseline", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--list", nargs="+", metavar="FILE", help="print every counted literal in FILE")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--root", type=Path, default=REPO, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        if args.self_test:
            self_test()
            return 0
        if args.list:
            return run_list(root, args.list)
        if args.write_initial_baseline:
            if (root / BASELINE_NAME).exists():
                raise GateError(f"{BASELINE_NAME} already exists; use --update-baseline, which only lowers")
            scan = scan_repo(root, tuple(SCAN_ROOTS))
            write_baseline(root, scan.counts)
            print(f"design-tokens: wrote {BASELINE_NAME}")
            return 0
        if args.update_baseline:
            return run_update(root)
        platforms = tuple(SCAN_ROOTS) if args.platform == "all" else (args.platform,)
        return run_check(root, platforms)
    except GateError as error:
        print(f"design-tokens: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
