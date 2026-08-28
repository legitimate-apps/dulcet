#!/usr/bin/env python3
"""Protected-data migration gate for every released Dulcet schema fixture."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_SNAPSHOTS = ROOT / "core/src/commonMain/sqldelight/databases"
SQLDELIGHT_ROOT = ROOT / "core/src/commonMain/sqldelight"
FIXTURES = ROOT / "tools/migration-fixtures"
RESERVATIONS = FIXTURES / "reserved-tables.json"

SCHEMA_INTENT_TABLES = {
    "server_account",
    "artist",
    "album",
    "track",
    "credit",
    "playlist",
    "playlist_entry",
    "queue_entry",
    "download",
    "scrobble_outbox",
    "mutation_outbox",
    "artwork_cache",
    "resume_position",
    "sync_checkpoint",
    "schema_meta",
}
REQUIRED_IMPLEMENTED_TABLES = {
    "queue_entry",
    "download",
    "scrobble_outbox",
    "mutation_outbox",
    "resume_position",
    "schema_meta",
}
PROTECTED_COLUMNS = {
    "scrobble_outbox": (
        "server_id",
        "raw_id",
        "session_start_wall_clock",
        "created_at_wall_clock",
        "attempt_count",
    ),
    "mutation_outbox": (
        "server_id",
        "target_id",
        "field",
        "value",
        "local_sequence",
        "wall_clock",
    ),
    "download": (
        "server_id",
        "raw_id",
        "transcode_profile",
        "download_id",
        "state",
        "file_relative_path",
        "expected_byte_length",
        "file_size_bytes",
        "platform_resume_data",
        "resume_data_created_at_wall_clock",
    ),
    "resume_position": (
        "server_id",
        "raw_id",
        "position_milliseconds",
    ),
}


class MigrationGateError(AssertionError):
    pass


def numbered_paths(directory: Path, suffix: str) -> dict[int, Path]:
    result: dict[int, Path] = {}
    for path in directory.glob(f"*{suffix}"):
        try:
            version = int(path.name.removesuffix(suffix))
        except ValueError:
            continue
        result[version] = path
    return result


def current_schema_version() -> int:
    snapshots = numbered_paths(SCHEMA_SNAPSHOTS, ".db")
    if not snapshots:
        raise MigrationGateError("no numbered SQLDelight schema snapshots found")
    return max(snapshots)


def current_cache_format_version() -> int:
    source = (
        ROOT / "core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DulcetDatabase.kt"
    ).read_text()
    match = re.search(r"DULCET_CACHE_FORMAT_VERSION:\s*Long\s*=\s*(\d+)", source)
    if match is None:
        raise MigrationGateError("cannot resolve current cache-format version")
    return int(match.group(1))


def table_names(connection: sqlite3.Connection) -> set[str]:
    return {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
        )
    }


def validate_schema_name_contract(version: int) -> None:
    reservation_document = json.loads(RESERVATIONS.read_text())
    reserved = set(reservation_document["reserved_unpopulated_table_names"])
    with sqlite3.connect(SCHEMA_SNAPSHOTS / f"{version}.db") as connection:
        implemented = table_names(connection)
        missing = REQUIRED_IMPLEMENTED_TABLES - implemented
        if missing:
            raise MigrationGateError(
                f"schema v{version} is missing implemented tables: {sorted(missing)}"
            )
        collision = reserved & implemented
        if collision:
            raise MigrationGateError(
                f"implemented tables remain incorrectly reserved: {sorted(collision)}"
            )
        unaccounted = SCHEMA_INTENT_TABLES - implemented - reserved
        if unaccounted:
            raise MigrationGateError(
                f"schema intent names are neither implemented nor reserved: {sorted(unaccounted)}"
            )
        for table in implemented:
            for column in connection.execute(f'PRAGMA table_info("{table}")'):
                normalized = column[1].lower()
                if "monotonic" in normalized:
                    raise MigrationGateError(
                        f"{table}.{column[1]} persists a forbidden monotonic value"
                    )
                if any(token in normalized for token in ("password", "credential", "secret", "salt")):
                    raise MigrationGateError(
                        f"{table}.{column[1]} is a forbidden credential-bearing column"
                    )


def fixture_versions() -> dict[int, Path]:
    fixtures: dict[int, Path] = {}
    for path in FIXTURES.glob("v*"):
        if not path.is_dir():
            continue
        try:
            version = int(path.name[1:])
        except ValueError:
            continue
        fixtures[version] = path
    return fixtures


def apply_released_migrations(
    connection: sqlite3.Connection,
    fixture_version: int,
    target_version: int,
) -> None:
    migrations = {
        int(path.stem): path
        for path in SQLDELIGHT_ROOT.rglob("*.sqm")
        if path.stem.isdigit()
    }
    for version in range(fixture_version, target_version):
        migration = migrations.get(version)
        if migration is None:
            raise MigrationGateError(
                f"missing SQLDelight migration {version}.sqm for v{version} -> v{version + 1}"
            )
        connection.executescript(migration.read_text())


def canonical_rows(connection: sqlite3.Connection, table: str) -> list[dict[str, object]]:
    columns = PROTECTED_COLUMNS[table]
    selected = ", ".join(f'"{column}"' for column in columns)
    rows = []
    for raw_row in connection.execute(f'SELECT {selected} FROM "{table}"'):
        row: dict[str, object] = {}
        for column, value in zip(columns, raw_row, strict=True):
            row[column] = value.hex() if isinstance(value, bytes) else value
        rows.append(row)
    return sorted(rows, key=lambda row: json.dumps(row, sort_keys=True))


def reconcile_download_files(
    downloads: list[dict[str, object]],
    files_root: Path,
) -> tuple[list[dict[str, object]], list[str]]:
    errors: list[str] = []
    referenced: set[str] = set()
    enriched: list[dict[str, object]] = []
    for row in downloads:
        relative = str(row["file_relative_path"])
        pure = PurePosixPath(relative)
        if pure.is_absolute() or ".." in pure.parts:
            errors.append(f"download has unsafe relative path: {relative}")
            enriched.append(row)
            continue
        referenced.add(relative)
        path = files_root.joinpath(*pure.parts)
        enriched_row = dict(row)
        if not path.is_file():
            errors.append(f"missing download file for row: {relative}")
        else:
            content = path.read_bytes()
            enriched_row["reconciled_file_size"] = len(content)
            enriched_row["reconciled_file_sha256"] = hashlib.sha256(content).hexdigest()
            if row["file_size_bytes"] != len(content):
                errors.append(
                    f"download file size differs from row for {relative}: "
                    f"row={row['file_size_bytes']} file={len(content)}"
                )
        enriched.append(enriched_row)
    actual_files = {
        path.relative_to(files_root).as_posix()
        for path in files_root.rglob("*")
        if path.is_file()
    }
    for orphan in sorted(actual_files - referenced):
        errors.append(f"orphan download fixture file: {orphan}")
    return enriched, errors


def protected_state(
    connection: sqlite3.Connection,
    files: Path,
) -> tuple[dict[str, list[dict[str, object]]], list[str]]:
    state: dict[str, list[dict[str, object]]] = {}
    errors: list[str] = []
    for table in PROTECTED_COLUMNS:
        try:
            state[table] = canonical_rows(connection, table)
        except sqlite3.DatabaseError as failure:
            errors.append(f"{table}: cannot read protected rows: {failure}")
            state[table] = []
    downloads, file_errors = reconcile_download_files(state["download"], files)
    state["download"] = downloads
    errors.extend(file_errors)
    return state, errors


def assert_fixture_preserved(
    database: Path,
    files: Path,
    expected_state: dict[str, list[dict[str, object]]],
    source_metadata: tuple[int, int, int],
    target_version: int,
) -> None:
    errors: list[str] = []
    with sqlite3.connect(database) as connection:
        actual, state_errors = protected_state(connection, files)
        errors.extend(state_errors)
        metadata = connection.execute(
            "SELECT schema_version, cache_format_version, committed_generation "
            "FROM schema_meta WHERE singleton_id = 1"
        ).fetchone()
        actual["schema_meta"] = list(metadata) if metadata is not None else None
    for table in PROTECTED_COLUMNS:
        if actual[table] != expected_state[table]:
            errors.append(
                f"{table}: protected rows changed\n"
                f"expected={json.dumps(expected_state[table], sort_keys=True)}\n"
                f"actual={json.dumps(actual[table], sort_keys=True)}"
            )
    expected_metadata = [
        target_version,
        current_cache_format_version(),
        source_metadata[2],
    ]
    if actual["schema_meta"] != expected_metadata:
        errors.append(
            "schema_meta: target version/cache format or committed generation changed incorrectly\n"
            f"expected={json.dumps(expected_metadata)}\n"
            f"actual={json.dumps(actual['schema_meta'])}"
        )
    if errors:
        raise MigrationGateError("\n".join(errors))


def migrate_and_assert_fixture(
    fixture_version: int,
    fixture: Path,
    target_version: int,
    destructive_sql: str | None = None,
) -> None:
    with tempfile.TemporaryDirectory(prefix=f"dulcet-migration-v{fixture_version}-") as temp:
        work = Path(temp)
        database = work / "database.db"
        files = work / "files"
        shutil.copy2(fixture / "database.db", database)
        shutil.copytree(fixture / "files", files)
        with sqlite3.connect(database) as connection:
            connection.execute("PRAGMA foreign_keys = ON")
            source_metadata = connection.execute(
                "SELECT schema_version, cache_format_version, committed_generation "
                "FROM schema_meta WHERE singleton_id = 1"
            ).fetchone()
            if source_metadata is None or source_metadata[0] != fixture_version:
                raise MigrationGateError(
                    f"fixture v{fixture_version} schema_meta has the wrong source version: "
                    f"actual={source_metadata}"
                )
            expected_state, fixture_errors = protected_state(connection, files)
            if fixture_errors:
                raise MigrationGateError(
                    "invalid pre-migration fixture:\n" + "\n".join(fixture_errors)
                )
            apply_released_migrations(connection, fixture_version, target_version)
            if destructive_sql is not None:
                connection.executescript(destructive_sql)
            connection.commit()
        assert_fixture_preserved(
            database,
            files,
            expected_state,
            source_metadata,
            target_version,
        )


DESTRUCTIVE_SAME_NAME_MIGRATION = """
UPDATE scrobble_outbox SET session_start_wall_clock = 0;
UPDATE mutation_outbox SET value = 'destroyed-by-negative-control';
UPDATE download SET file_relative_path = 'downloads/missing-after-migration.bin';
UPDATE resume_position SET position_milliseconds = 0;
"""


def prove_destructive_migration_is_rejected(fixture: Path, version: int) -> None:
    try:
        migrate_and_assert_fixture(version, fixture, version, DESTRUCTIVE_SAME_NAME_MIGRATION)
    except MigrationGateError as failure:
        message = str(failure)
        required_evidence = {
            "scrobble_outbox",
            "mutation_outbox",
            "download",
            "resume_position",
            "missing download file",
        }
        missing = {marker for marker in required_evidence if marker not in message}
        if missing:
            raise MigrationGateError(
                f"negative migration failed for incomplete reasons; missing {sorted(missing)}\n{message}"
            ) from failure
        return
    raise MigrationGateError(
        "destructive same-name migration unexpectedly preserved protected semantics"
    )


def main() -> None:
    current = current_schema_version()
    validate_schema_name_contract(current)
    fixtures = fixture_versions()
    expected_versions = set(range(1, current + 1))
    if set(fixtures) != expected_versions:
        raise MigrationGateError(
            f"fixture versions differ from released schemas: "
            f"expected={sorted(expected_versions)} actual={sorted(fixtures)}"
        )
    for version, fixture in sorted(fixtures.items()):
        migrate_and_assert_fixture(version, fixture, current)
    prove_destructive_migration_is_rejected(fixtures[current], current)
    print(
        f"Migration gate valid: {len(fixtures)} fixture database(s), "
        f"{len(PROTECTED_COLUMNS)} protected table comparisons per fixture, "
        "download file reconciliation, and 1 destructive same-name negative control"
    )


if __name__ == "__main__":
    main()
