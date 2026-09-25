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

    tools/generate_migration_fixture.py --to 7            # write v7 from v6
    tools/generate_migration_fixture.py --to 7 --check    # regenerate into a temporary
                                                           # directory; exit 1 unless the
                                                           # committed v7 is byte-identical

The committed fixtures were written by Python's sqlite3 module linked against SQLite 3.51.0,
which reserves no bytes per page. The tool refuses to write with any other SQLite version, and
refuses a result that reserves bytes, because a different build lays pages out differently and a
regeneration would then differ for reasons that have nothing to do with the schema.
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
    # last_issued equals the highest seeded issue number, so the next one the seen-cache hands out
    # is past every seeded row's.
    connection.execute("UPDATE cache_meta SET last_issued = 13 WHERE singleton_id = 1")


SEEDS = {7: seed_v7}


def fail(message):
    sys.exit(f"generate_migration_fixture: {message}")


def header(path):
    data = path.read_bytes()[:100]
    return {
        "page_size": int.from_bytes(data[16:18], "big"),
        "reserved": data[20],
        "sqlite_version_number": int.from_bytes(data[96:100], "big"),
    }


def generate(target, output):
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
    if written["reserved"] != 0 or written["page_size"] != PAGE_SIZE:
        fail(f"{database} has page size {written['page_size']} and {written['reserved']} reserved bytes")


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
    parser.add_argument("--to", type=int, required=True, help="the schema version to generate")
    parser.add_argument("--check", action="store_true", help="compare with the committed fixture")
    args = parser.parse_args()
    if sqlite3.sqlite_version != SQLITE_VERSION:
        fail(f"the committed fixtures were written with SQLite {SQLITE_VERSION}; this is {sqlite3.sqlite_version}")
    committed = FIXTURES / f"v{args.to}"
    with tempfile.TemporaryDirectory(prefix="dulcet-fixture-") as directory:
        generated = Path(directory) / f"v{args.to}"
        generate(args.to, generated)
        if args.check:
            if not committed.is_dir() or not same_tree(generated, committed):
                fail(f"{committed} differs from what this tool generates; rerun without --check")
            print(f"{committed}: byte-identical to its regeneration")
            return
        if committed.exists():
            shutil.rmtree(committed)
        shutil.copytree(generated, committed)
    print(f"{committed}: written from v{args.to - 1} with SQLite {sqlite3.sqlite_version}")


if __name__ == "__main__":
    main()
