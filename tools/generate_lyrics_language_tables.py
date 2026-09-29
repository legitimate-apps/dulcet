#!/usr/bin/env python3
"""Regenerate the language tables in LyricsLanguage.kt from their published sources.

The lyrics selection policy (spec §18.4 rule 3) folds ISO 639-2 codes onto ISO 639-1 and relates
the members of a macrolanguage. Both tables are generated, never hand-edited, from:

  ISO_639_2_TO_1    the Library of Congress ISO 639-2 registration authority's code list
  MACROLANGUAGE_OF  the ISO 639-3 registration authority's macrolanguage mappings

Each source is pinned by sha256 below, and the same URL, retrieval date and digest are cited in
the KDoc of the table it produces; this tool refuses a source that does not match its pin, and a
Kotlin file where either table's own KDoc does not cite its own source exactly. The check is per
table: a citation that appears only in the other table's KDoc does not count, so changing one
table's retrieval date fails even though the other still carries the old one
(tools/test-lyrics-language-tables is the control).

    tools/generate_lyrics_language_tables.py              # download, verify, compare (exit 1 on drift)
    tools/generate_lyrics_language_tables.py --write      # ...and rewrite the two tables in place
    tools/generate_lyrics_language_tables.py --source-dir DIR   # use local copies, no network
    tools/generate_lyrics_language_tables.py --citations-only   # check the citations, no network

A source that has changed upstream is a review, not a regeneration: read what changed, then update
the pin and retrieval date here and the citation in LyricsLanguage.kt together, and run --write.
"""
import argparse
import hashlib
import sys
import textwrap
import urllib.request
from pathlib import Path

KOTLIN = Path("core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/LyricsLanguage.kt")

SOURCES = {
    "iso639_2": {
        "table": "ISO_639_2_TO_1",
        "url": "https://www.loc.gov/standards/iso639-2/ISO-639-2_utf-8.txt",
        "file": "ISO-639-2_utf-8.txt",
        "retrieved": "2026-09-24",
        "sha256": "42b71885e4dc885559fda5ad059fd81838cf4782cedb5fd08cc3431f7d067371",
    },
    "macrolanguages": {
        "table": "MACROLANGUAGE_OF",
        "url": "https://iso639-3.sil.org/sites/iso639-3/files/downloads/iso-639-3-macrolanguages.tab",
        "file": "iso-639-3-macrolanguages.tab",
        "retrieved": "2026-09-24",
        "sha256": "fb01a86376d9c1abfc96d16be1b6dcffb4776a1f52fa22338d4979e9ffe1822f",
    },
}

# Must equal LYRICS_LANGUAGE_ALIASES in LyricsLanguage.kt, which the tool checks: codes folded onto
# another before the tables apply.
ALIASES = {"cmn": "zh", "fil": "tl", "iw": "he", "in": "id", "ji": "yi"}

WIDTH = 92


def fetch(name, source_dir):
    source = SOURCES[name]
    if source_dir:
        data = (Path(source_dir) / source["file"]).read_bytes()
    else:
        request = urllib.request.Request(source["url"], headers={"User-Agent": "dulcet-table-generator"})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != source["sha256"]:
        sys.exit(
            f"{source['url']} has sha256 {digest}, not the pinned {source['sha256']}.\n"
            "The source changed: review the change, then update the pin here and the citation in "
            f"{KOTLIN} together."
        )
    return data.decode("utf-8-sig")


def iso_639_2_to_1(text):
    """Every bibliographic and terminology code that has an ISO 639-1 code, onto that code."""
    table = {}
    for line in text.splitlines():
        parts = line.split("|")
        if len(parts) < 3:
            continue
        bibliographic, terminology, alpha2 = parts[0], parts[1], parts[2]
        if not alpha2:
            continue
        table[bibliographic] = alpha2
        if terminology:
            table[terminology] = alpha2
    return table


def macrolanguage_of(text, iso):
    """Each active member's key onto its macrolanguage's key, dropping members keyed as the macro."""

    def key(code):
        return ALIASES.get(code) or iso.get(code) or code

    table = {}
    rows = text.splitlines()
    if rows[0].split("\t")[:3] != ["M_Id", "I_Id", "I_Status"]:
        sys.exit(f"unexpected macrolanguage header: {rows[0]!r}")
    for line in rows[1:]:
        if not line:
            continue
        macro, member, status = line.split("\t")
        if status != "A":
            continue
        macro_key, member_key = key(macro), key(member)
        if macro_key == member_key:
            continue
        if table.get(member_key, macro_key) != macro_key:
            sys.exit(f"{member_key} maps to both {table[member_key]} and {macro_key}")
        table[member_key] = macro_key
    return table


def kotlin_chunks(table):
    """The pairTable argument: space-separated `key:value` pairs, wrapped into string literals."""
    lines = textwrap.wrap(" ".join(f"{a}:{b}" for a, b in sorted(table.items())), width=WIDTH)
    return "\n".join(
        f'    "{line}' + ('",' if n == len(lines) - 1 else ' " +') for n, line in enumerate(lines)
    )


def replace_table(kotlin, name, body):
    start_marker = f"internal val {name}: Map<String, String> = pairTable(\n"
    start = kotlin.index(start_marker) + len(start_marker)
    end = kotlin.index("\n)\n", start)
    return kotlin[:start] + body + kotlin[end:]


def table_kdoc(kotlin, name):
    """The KDoc directly above `internal val <name>`, its `*` gutters removed and its words joined
    by single spaces, so a citation wrapped across lines reads as one phrase."""
    declaration = kotlin.index(f"internal val {name}: Map<String, String> = pairTable(\n")
    end = kotlin.rindex("*/", 0, declaration)
    if kotlin[end + 2:declaration].strip():
        sys.exit(f"{KOTLIN}: {name} has no KDoc directly above it")
    start = kotlin.rindex("/**", 0, end)
    lines = (line.strip().lstrip("*").strip() for line in kotlin[start + 3:end].splitlines())
    return " ".join(" ".join(lines).split())


def citation(source):
    return f"{source['url']} (retrieved {source['retrieved']}, sha256 {source['sha256']})"


def check_citations(kotlin):
    for source in SOURCES.values():
        kdoc = table_kdoc(kotlin, source["table"])
        if citation(source) not in kdoc:
            sys.exit(f"{KOTLIN}: the KDoc of {source['table']} does not cite {citation(source)!r}")
    for code, target in ALIASES.items():
        if f'"{code}" to "{target}"' not in kotlin:
            sys.exit(f"{KOTLIN} LYRICS_LANGUAGE_ALIASES does not fold {code} onto {target}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true", help="rewrite the tables in place")
    parser.add_argument("--source-dir", help="read the pinned files from here instead of downloading")
    parser.add_argument("--citations-only", action="store_true", help="check the citations and stop")
    args = parser.parse_args()

    current = KOTLIN.read_text(encoding="utf-8")
    check_citations(current)
    if args.citations_only:
        print(f"{KOTLIN}: each table cites its own source")
        return

    iso = iso_639_2_to_1(fetch("iso639_2", args.source_dir))
    macro = macrolanguage_of(fetch("macrolanguages", args.source_dir), iso)

    generated = replace_table(current, "ISO_639_2_TO_1", kotlin_chunks(iso))
    generated = replace_table(generated, "MACROLANGUAGE_OF", kotlin_chunks(macro))
    print(f"ISO_639_2_TO_1: {len(iso)} codes; MACROLANGUAGE_OF: {len(macro)} members")
    if generated == current:
        print(f"{KOTLIN}: tables match their sources")
        return
    if args.write:
        KOTLIN.write_text(generated, encoding="utf-8")
        print(f"{KOTLIN}: tables rewritten")
        return
    sys.exit(f"{KOTLIN}: tables differ from their sources; run with --write")


if __name__ == "__main__":
    main()
