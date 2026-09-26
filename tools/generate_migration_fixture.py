#!/usr/bin/env python3
"""Generate a released-schema migration fixture from the one before it.

tools/migration_gate.py migrates every fixture under tools/migration-fixtures/ to the current
schema and compares its protected rows. A fixture is therefore evidence only if it holds what an
installed copy of that schema would hold, and it can be regenerated only if the steps that made
it are written down. This tool is those steps:

    tools/migration-fixtures/v<N>/database.db
      + migrations/<N>.sqm, exactly as SQLDelight ships it
      + schema_meta.schema_version, PRAGMA user_version = N + 1
      + the rows in SEEDS[N + 1], which give the new tables something to migrate
      + VACUUM
    = tools/migration-fixtures/v<N+1>/database.db, with v<N>'s files/ copied beside it

    tools/generate_migration_fixture.py --to 8            # write v8 from v7
    tools/generate_migration_fixture.py --to 8 --check    # regenerate into a temporary
                                                           # directory; exit 1 unless the
                                                           # committed v8 is byte-identical
    tools/generate_migration_fixture.py --to 8 --check-semantic
                                                           # the same, compared by content:
                                                           # schema and rows, any SQLite
    tools/generate_migration_fixture.py --all --check-semantic
                                                           # every version with a seed (CI)

Fixtures v1-v3 were written with SQLite 3.50.6 and v4-v8 with SQLite 3.51.0, by Python's sqlite3
module; none reserves bytes per page. Writing and --check refuse any SQLite version but 3.51.0,
and refuse a result that reserves bytes, because a different build lays pages out differently and a
regeneration would then differ for reasons that have nothing to do with the schema. --check is
therefore a local check. --check-semantic runs anywhere, and is what CI runs: it compares the
normalised schema (sqlite_master, whitespace collapsed), PRAGMA user_version and every table's rows,
and the files/ tree, between the committed fixture and a regeneration.

A seed only adds to what the fixture before it holds: it never lowers a sequence an earlier seed
set (last_issued is raised with MAX), so seeds stack when a schema is renumbered.
"""

from __future__ import annotations

import argparse
import filecmp
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tools/migration-fixtures"
MIGRATIONS = ROOT / "core/src/commonMain/sqldelight/migrations"
SQLITE_VERSION = "3.51.0"
PAGE_SIZE = 4096


def lyrics_stored_bytes(server_id, raw_id, source, layers):
    """Mirrors lyricsStoredBytes in SeenCacheStore.kt: what the document's rows hold (spec §18.4)."""
    def utf8(text):
        return 0 if text is None else len(text.encode("utf-8"))
    integer = 8
    keys = 2 * (utf8(server_id) + utf8(raw_id))
    total = keys + utf8(source) + 5 * integer
    for layer in layers:
        ordinal = 2 * integer
        total += keys + ordinal + 2 * integer
        total += utf8(layer["language"]) + utf8(layer["kind"])
        total += utf8(layer["display_artist"]) + utf8(layer["display_title"])
        total += sum(keys + 2 * ordinal + integer + utf8(text) for _, text in layer["lines"])
    return total


def seed_v7(connection):
    """Playlist header fields (6.sqm): one playlist stating all three, one read-only one stating an
    empty comment, and one read before schema 7 that states none of them (spec §18.6)."""
    wall = 1_788_000_000_000
    playlists = [
        # server_id, raw_id, name, song_count, duration_ms, owner, issue_seq, comment, is_public, readonly
        ("server:fixture-alpha", "playlist:stated", "Road Trip", 3, 540_000, "listener", 11,
         "for the car", 1, 0),
        ("server:fixture-alpha", "playlist:readonly", "Shared", 1, 180_000, "someone-else", 12,
         "", 1, 1),
        ("server:fixture-beta", "playlist:unstated", "Morning", 0, 0, "listener", 13,
         None, None, None),
    ]
    for (server_id, raw_id, name, song_count, duration, owner, issue_seq,
         comment, is_public, readonly) in playlists:
        connection.execute(
            "INSERT INTO cache_playlist (server_id, raw_id, name, song_count, duration_milliseconds, "
            "owner, artwork_key, detail_complete, detail_issue_seq, fetched_at_wall, fetched_epoch, "
            "issue_seq, last_access_wall, gone, comment, is_public, readonly) "
            "VALUES (?, ?, ?, ?, ?, ?, NULL, 0, 0, ?, NULL, ?, ?, 0, ?, ?, ?)",
            (server_id, raw_id, name, song_count, duration, owner, wall, issue_seq, wall,
             comment, is_public, readonly),
        )
    # The next issue number the seen-cache hands out is past every seeded row's; raised with MAX, so
    # a seed never lowers what an earlier one set (v6 holds 0, so v7 is unchanged by the MAX).
    connection.execute("UPDATE cache_meta SET last_issued = MAX(last_issued, 13) WHERE singleton_id = 1")


def seed_v8(connection):
    """Lyrics (7.sqm): one structured document with a synced layer and one legacy empty one."""
    documents = [
        {
            "server_id": "server:fixture-alpha",
            "raw_id": "track:download-opaque",
            "source": "songLyrics",
            "issue_seq": 43,
            "dropped_layers": 2,
            "layers": [
                {
                    "language": "eng",
                    "synced": 1,
                    "offset": 250,
                    "kind": "main",
                    "display_artist": "Fixture Artist",
                    "display_title": "Fixture Title",
                    "lines": [(500, "first line"), (2000, "")],
                },
            ],
        },
        {
            "server_id": "server:fixture-beta",
            "raw_id": "track:download-stale",
            "source": "getLyrics",
            "issue_seq": 44,
            "dropped_layers": 0,
            "layers": [],
        },
    ]
    wall = 1_790_000_000_000
    for document in documents:
        stored = lyrics_stored_bytes(
            document["server_id"], document["raw_id"], document["source"], document["layers"]
        )
        connection.execute(
            "INSERT INTO cache_lyrics (server_id, raw_id, source, fetched_at_wall, issue_seq, "
            "last_access_wall, dropped_layers, stored_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (document["server_id"], document["raw_id"], document["source"], wall,
             document["issue_seq"], wall, document["dropped_layers"], stored),
        )
        for ordinal, layer in enumerate(document["layers"]):
            connection.execute(
                "INSERT INTO cache_lyrics_layer (server_id, raw_id, layer_ordinal, language, synced, "
                "offset_milliseconds, kind, display_artist, display_title) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (document["server_id"], document["raw_id"], ordinal, layer["language"],
                 layer["synced"], layer["offset"], layer["kind"], layer["display_artist"],
                 layer["display_title"]),
            )
            for line_ordinal, (start, text) in enumerate(layer["lines"]):
                connection.execute(
                    "INSERT INTO cache_lyrics_line (server_id, raw_id, layer_ordinal, line_ordinal, "
                    "start_milliseconds, text) VALUES (?, ?, ?, ?, ?, ?)",
                    (document["server_id"], document["raw_id"], ordinal, line_ordinal, start, text),
                )
    # The issue sequence the seen-cache hands out next must be past every seeded row's, and must
    # never be lowered below what an earlier seed set.
    connection.execute("UPDATE cache_meta SET last_issued = MAX(last_issued, 44) WHERE singleton_id = 1")


SEEDS = {7: seed_v7, 8: seed_v8}


def fail(message):
    sys.exit(f"generate_migration_fixture: {message}")


def header(path):
    data = path.read_bytes()[:100]
    return {
        "page_size": int.from_bytes(data[16:18], "big"),
        "reserved": data[20],
        "sqlite_version_number": int.from_bytes(data[96:100], "big"),
    }


def generate(target, output, require_layout=True):
    source = FIXTURES / f"v{target - 1}"
    migration = MIGRATIONS / f"{target - 1}.sqm"
    if not (source / "database.db").is_file():
        fail(f"no fixture {source}")
    if not migration.is_file():
        fail(f"no migration {migration}")
    if target not in SEEDS:
        fail(f"no seed for v{target}; add one to SEEDS")
    output.mkdir(parents=True, exist_ok=True)
    database = output / "database.db"
    shutil.copyfile(source / "database.db", database)
    connection = sqlite3.connect(database, isolation_level=None)
    try:
        if connection.execute("PRAGMA user_version").fetchone()[0] != target - 1:
            fail(f"{source} is not schema {target - 1}")
        connection.executescript(migration.read_text(encoding="utf-8"))
        connection.execute("BEGIN")
        connection.execute(
            "UPDATE schema_meta SET schema_version = ? WHERE singleton_id = 1", (target,)
        )
        SEEDS[target](connection)
        connection.execute("COMMIT")
        connection.execute(f"PRAGMA user_version = {target}")
        connection.execute("VACUUM")
        problems = connection.execute("PRAGMA integrity_check").fetchall()
        if problems != [("ok",)]:
            fail(f"integrity_check: {problems}")
        violations = connection.execute("PRAGMA foreign_key_check").fetchall()
        if violations:
            fail(f"foreign_key_check: {violations}")
    finally:
        connection.close()
    shutil.copytree(source / "files", output / "files", dirs_exist_ok=True)
    written = header(database)
    if require_layout and (written["reserved"] != 0 or written["page_size"] != PAGE_SIZE):
        fail(f"{database} has page size {written['page_size']} and {written['reserved']} reserved bytes")


def semantic(path):
    """What a fixture holds, independent of how SQLite laid it out: schema, version, rows."""
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        schema = sorted(
            (kind, name, table, " ".join((sql or "").split()))
            for kind, name, table, sql in connection.execute(
                "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
            )
        )
        tables = [name for kind, name, _, _ in schema if kind == "table"]
        rows = {}
        for table in tables:
            fetched = connection.execute(f'SELECT * FROM "{table}"').fetchall()
            rows[table] = sorted(fetched, key=repr)
        version = connection.execute("PRAGMA user_version").fetchone()[0]
    finally:
        connection.close()
    return {"schema": schema, "user_version": version, "rows": rows}


def semantic_differences(generated, committed):
    left, right = semantic(generated), semantic(committed)
    differences = []
    if left["user_version"] != right["user_version"]:
        differences.append(f"user_version {right['user_version']} != {left['user_version']}")
    if left["schema"] != right["schema"]:
        differences.append("schema differs")
    for table in sorted(set(left["rows"]) | set(right["rows"])):
        if left["rows"].get(table) != right["rows"].get(table):
            differences.append(f"rows of {table} differ")
    return differences


def same_files(left, right):
    """The files/ trees, byte for byte (they are copied, never written by SQLite)."""
    if not Path(left).exists() and not Path(right).exists():
        return True
    return Path(left).exists() and Path(right).exists() and same_tree(left, right)


def same_tree(left, right):
    comparison = filecmp.dircmp(left, right)
    if comparison.left_only or comparison.right_only or comparison.funny_files:
        return False
    _, mismatch, errors = filecmp.cmpfiles(left, right, comparison.common_files, shallow=False)
    if mismatch or errors:
        return False
    return all(same_tree(Path(left) / name, Path(right) / name) for name in comparison.common_dirs)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--to", type=int, help="the schema version to generate")
    target.add_argument("--all", action="store_true", help="every version this tool has a seed for (checks only)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="compare with the committed fixture, byte for byte")
    mode.add_argument(
        "--check-semantic",
        action="store_true",
        help="compare with the committed fixture by schema and rows; any SQLite version",
    )
    args = parser.parse_args()
    if args.all:
        if not (args.check or args.check_semantic):
            fail("--all only checks; write one version at a time with --to")
        for version in sorted(SEEDS):
            check(version, args.check_semantic)
        return
    if args.check or args.check_semantic:
        check(args.to, args.check_semantic)
        return
    if sqlite3.sqlite_version != SQLITE_VERSION:
        fail(f"fixtures from v4 were written with SQLite {SQLITE_VERSION}; this is {sqlite3.sqlite_version}")
    committed = FIXTURES / f"v{args.to}"
    with tempfile.TemporaryDirectory(prefix="dulcet-fixture-") as directory:
        generated = Path(directory) / f"v{args.to}"
        generate(args.to, generated)
        if committed.exists():
            shutil.rmtree(committed)
        shutil.copytree(generated, committed)
    print(f"{committed}: written from v{args.to - 1} with SQLite {sqlite3.sqlite_version}")


def check(version, semantic_only):
    committed = FIXTURES / f"v{version}"
    if semantic_only:
        with tempfile.TemporaryDirectory(prefix="dulcet-fixture-") as directory:
            generated = Path(directory) / f"v{version}"
            generate(version, generated, require_layout=False)
            if not (committed / "database.db").is_file():
                fail(f"no committed fixture {committed}")
            differences = semantic_differences(generated / "database.db", committed / "database.db")
            if not same_files(generated / "files", committed / "files"):
                differences.append("files/ differs")
            if differences:
                fail(f"{committed} differs from what this tool generates: {'; '.join(differences)}")
        print(f"{committed}: same schema and rows as its regeneration (SQLite {sqlite3.sqlite_version})")
        return
    if sqlite3.sqlite_version != SQLITE_VERSION:
        fail(f"fixtures from v4 were written with SQLite {SQLITE_VERSION}; this is {sqlite3.sqlite_version}")
    with tempfile.TemporaryDirectory(prefix="dulcet-fixture-") as directory:
        generated = Path(directory) / f"v{version}"
        generate(version, generated)
        if not committed.is_dir() or not same_tree(generated, committed):
            fail(f"{committed} differs from what this tool generates; rerun without --check")
    print(f"{committed}: byte-identical to its regeneration")


if __name__ == "__main__":
    main()
