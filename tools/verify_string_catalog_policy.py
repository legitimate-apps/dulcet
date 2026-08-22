#!/usr/bin/env python3

"""Reject string literals passed directly to SwiftUI user-facing copy surfaces.

The policy intentionally follows copy-bearing arguments rather than every string in a view file.
That keeps SF Symbol names, link destinations, accessibility identifiers, logs, and debug text
outside the check. ``Text(verbatim:)`` is also explicitly exempt for non-linguistic content.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


DEFAULT_SOURCE_ROOTS = (
    Path("apple/DulcetKit/Sources"),
    Path("apple/DulcetMac"),
)

# These initializers expose user-facing copy in their first, unlabeled argument.
COPY_INITIALIZERS = {
    "Button",
    "GroupBox",
    "Label",
    "Link",
    "Menu",
    "Picker",
    "Section",
    "SecureField",
    "TableColumn",
    "Text",
    "TextField",
    "Toggle",
}

# These modifiers expose user-facing copy in their first, unlabeled argument.
COPY_MODIFIERS = {
    "accessibilityHint",
    "accessibilityLabel",
    "accessibilityValue",
    "alert",
    "confirmationDialog",
    "help",
    "navigationTitle",
}

OPENING_DELIMITERS = {"(", "[", "{"}
CLOSING_DELIMITERS = {")", "]", "}"}


class StringCatalogPolicyError(RuntimeError):
    pass


@dataclass(frozen=True)
class Token:
    kind: str
    value: str
    line: int
    column: int


def _tokenize_swift(source: str) -> list[Token]:
    """Return the identifiers, delimiters, and string literals needed by this policy."""

    tokens: list[Token] = []
    index = 0
    line = 1
    column = 1

    def advance(count: int = 1) -> None:
        nonlocal index, line, column
        for character in source[index : index + count]:
            if character == "\n":
                line += 1
                column = 1
            else:
                column += 1
        index += count

    while index < len(source):
        character = source[index]

        if character.isspace():
            advance()
            continue

        if source.startswith("//", index):
            newline = source.find("\n", index + 2)
            advance((len(source) if newline == -1 else newline) - index)
            continue

        if source.startswith("/*", index):
            depth = 1
            advance(2)
            while index < len(source) and depth:
                if source.startswith("/*", index):
                    depth += 1
                    advance(2)
                elif source.startswith("*/", index):
                    depth -= 1
                    advance(2)
                else:
                    advance()
            continue

        raw_hash_count = 0
        if character == "#":
            while index + raw_hash_count < len(source) and source[index + raw_hash_count] == "#":
                raw_hash_count += 1
        quote_index = index + raw_hash_count
        if quote_index < len(source) and source[quote_index] == '"':
            token_line = line
            token_column = column
            opening_length = raw_hash_count + (3 if source.startswith('"""', quote_index) else 1)
            multiline = opening_length - raw_hash_count == 3
            advance(opening_length)
            closing = ('"""' if multiline else '"') + ("#" * raw_hash_count)
            while index < len(source):
                if source.startswith(closing, index):
                    advance(len(closing))
                    break
                if not multiline and raw_hash_count == 0 and source[index] == "\\":
                    advance(min(2, len(source) - index))
                else:
                    advance()
            tokens.append(Token("string", "string literal", token_line, token_column))
            continue

        if character.isalpha() or character == "_":
            token_index = index
            token_line = line
            token_column = column
            while index < len(source) and (source[index].isalnum() or source[index] == "_"):
                advance()
            tokens.append(Token("identifier", source[token_index:index], token_line, token_column))
            continue

        if character in "()[]{},:.":
            tokens.append(Token("punctuation", character, line, column))
        advance()

    return tokens


def _first_argument(tokens: Sequence[Token], opening_parenthesis: int) -> Sequence[Token]:
    depth = 0
    argument: list[Token] = []
    for token in tokens[opening_parenthesis + 1 :]:
        if token.value in OPENING_DELIMITERS:
            depth += 1
        elif token.value in CLOSING_DELIMITERS:
            if depth == 0:
                break
            depth -= 1
        elif token.value == "," and depth == 0:
            break
        argument.append(token)
    return argument


def _is_labeled_argument(argument: Sequence[Token]) -> bool:
    return (
        len(argument) >= 2
        and argument[0].kind == "identifier"
        and argument[1].value == ":"
    )


def _violations_in_file(path: Path) -> list[str]:
    tokens = _tokenize_swift(path.read_text())
    failures: list[str] = []

    for index, token in enumerate(tokens[:-1]):
        if token.kind != "identifier" or tokens[index + 1].value != "(":
            continue

        is_initializer = token.value in COPY_INITIALIZERS
        is_modifier = (
            token.value in COPY_MODIFIERS
            and index > 0
            and tokens[index - 1].value == "."
        )
        if not (is_initializer or is_modifier):
            continue

        argument = _first_argument(tokens, index + 1)
        if not argument or _is_labeled_argument(argument):
            # This includes the explicit Text(verbatim:) non-linguistic-content exemption.
            continue

        for literal in (candidate for candidate in argument if candidate.kind == "string"):
            failures.append(
                f"{path.as_posix()}:{literal.line}:{literal.column}: "
                f"string literal in {token.value} copy argument must use DulcetStrings"
            )

    return failures


def _swift_files(source_paths: Iterable[Path]) -> list[Path]:
    files: set[Path] = set()
    missing: list[Path] = []
    for source_path in source_paths:
        if source_path.is_file():
            if source_path.suffix == ".swift":
                files.add(source_path)
        elif source_path.is_dir():
            files.update(source_path.rglob("*.swift"))
        else:
            missing.append(source_path)

    if missing:
        rendered = ", ".join(path.as_posix() for path in missing)
        raise StringCatalogPolicyError(f"source path does not exist: {rendered}")
    if not files:
        raise StringCatalogPolicyError("no Swift source files found")
    return sorted(files)


def verify_source_paths(source_paths: Iterable[Path]) -> None:
    failures: list[str] = []
    for path in _swift_files(source_paths):
        failures.extend(_violations_in_file(path))
    if failures:
        raise StringCatalogPolicyError("\n".join(failures))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-root",
        action="append",
        dest="source_roots",
        type=Path,
        help="Swift source file or directory to scan; repeat for multiple roots",
    )
    args = parser.parse_args()
    source_roots = args.source_roots or DEFAULT_SOURCE_ROOTS

    try:
        verify_source_paths(source_roots)
    except StringCatalogPolicyError as error:
        raise SystemExit(f"STRING CATALOG POLICY FAIL\n{error}") from error

    print(
        "STRING CATALOG POLICY PASS "
        "swiftui-copy-literals=catalogued "
        "text-verbatim=exempt "
        "non-copy-arguments=outside-policy"
    )


if __name__ == "__main__":
    main()
