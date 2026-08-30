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
SHIPPING_FOREIGN_KEYS = 1

SCHEMA_INTENT_TABLES = {
    "server_account",
    "music_folder",
    "artist",
    "album",
    "track",
    "credit",
    "genre",
    "library_starred",
    "playlist",
    "playlist_entry",
    "queue_entry",
    "download",
    "scrobble_outbox",
    "mutation_outbox",
    "artwork_cache",
    "resume_position",
    "sync_checkpoint",
    "sync_seen",
    "sync_generation",
    "deletion_reconciliation",
    "schema_meta",
}
REQUIRED_IMPLEMENTED_TABLES = {
    "music_folder",
    "artist",
    "album",
    "track",
    "credit",
    "genre",
    "library_starred",
    "playlist",
    "playlist_entry",
    "queue_entry",
    "download",
    "scrobble_outbox",
    "mutation_outbox",
    "resume_position",
    "sync_checkpoint",
    "sync_seen",
    "sync_generation",
    "deletion_reconciliation",
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


def validate_schema_and_forbidden_column_name_contract(version: int) -> None:
    reservation_document = json.loads(RESERVATIONS.read_text())
    reserved = set(reservation_document["reserved_unpopulated_table_names"])
    declared_implemented = set(reservation_document["implemented_table_names"])
    with sqlite3.connect(SCHEMA_SNAPSHOTS / f"{version}.db") as connection:
        implemented = table_names(connection)
        if declared_implemented != implemented:
            raise MigrationGateError(
                "implemented table registry differs from the schema snapshot: "
                f"declared={sorted(declared_implemented)} actual={sorted(implemented)}"
            )
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


def reconcile_runtime_metadata(
    connection: sqlite3.Connection,
    target_schema_version: int,
    target_cache_format_version: int,
) -> None:
    connection.execute(
        "UPDATE schema_meta SET schema_version = ?, cache_format_version = ? "
        "WHERE singleton_id = 1",
        (target_schema_version, target_cache_format_version),
    )


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


SQL_TOKEN = re.compile(
    r"""'(?:''|[^'])*'|"(?:""|[^"])*"|`(?:``|[^`])*`|\[[^]]*\]|
        --[^\n]*(?:\n|$)|/\*.*?\*/|<=|>=|<>|!=|==|\|\||
        [A-Za-z_][A-Za-z0-9_$]*|(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?|\S""",
    re.VERBOSE | re.DOTALL,
)


def normalized_sql(sql: str | None) -> str | None:
    if sql is None:
        return None
    tokens: list[str] = []
    for match in SQL_TOKEN.finditer(sql):
        token = match.group(0)
        if token.startswith(("--", "/*")) or token == ";":
            continue
        tokens.append(token if token.startswith("'") else token.lower())
    return " ".join(tokens)


def protected_schema(connection: sqlite3.Connection) -> dict[str, object]:
    tables: dict[str, object] = {}
    for table in PROTECTED_COLUMNS:
        table_sql_row = connection.execute(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            (table,),
        ).fetchone()
        indexes: list[dict[str, object]] = []
        for index_row in connection.execute(f'PRAGMA index_list("{table}")'):
            index_name = index_row[1]
            index_sql_row = connection.execute(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
                (index_name,),
            ).fetchone()
            indexes.append(
                {
                    "name": index_name,
                    "unique": index_row[2],
                    "origin": index_row[3],
                    "partial": index_row[4],
                    "sql": normalized_sql(index_sql_row[0] if index_sql_row else None),
                    "columns": [
                        list(column)
                        for column in connection.execute(
                            f'PRAGMA index_xinfo("{index_name}")'
                        )
                    ],
                }
            )
        tables[table] = {
            "sql": normalized_sql(table_sql_row[0] if table_sql_row else None),
            "columns": [
                list(column)
                for column in connection.execute(f'PRAGMA table_xinfo("{table}")')
            ],
            "foreign_keys": [
                list(foreign_key)
                for foreign_key in connection.execute(f'PRAGMA foreign_key_list("{table}")')
            ],
            "indexes": sorted(indexes, key=lambda index: str(index["name"])),
        }
    triggers = [
        {
            "name": name,
            "table": table,
            "sql": normalized_sql(sql),
        }
        for name, table, sql in connection.execute(
            "SELECT name, tbl_name, sql FROM sqlite_master "
            "WHERE type = 'trigger' ORDER BY name"
        )
    ]
    return {"tables": tables, "triggers": triggers}


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
    expected_schema: dict[str, object],
    source_metadata: tuple[int, int, int],
    target_version: int,
    target_cache_format_version: int,
) -> None:
    errors: list[str] = []
    with sqlite3.connect(database) as connection:
        actual, state_errors = protected_state(connection, files)
        errors.extend(state_errors)
        actual_schema = protected_schema(connection)
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
    if actual_schema != expected_schema:
        errors.append(
            "protected schema or trigger definitions changed\n"
            f"expected={json.dumps(expected_schema, sort_keys=True)}\n"
            f"actual={json.dumps(actual_schema, sort_keys=True)}"
        )
    expected_metadata = [
        target_version,
        target_cache_format_version,
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
    target_cache_format_version: int | None = None,
) -> None:
    if target_cache_format_version is None:
        target_cache_format_version = current_cache_format_version()
    with tempfile.TemporaryDirectory(prefix=f"dulcet-migration-v{fixture_version}-") as temp:
        work = Path(temp)
        database = work / "database.db"
        files = work / "files"
        shutil.copy2(fixture / "database.db", database)
        shutil.copytree(fixture / "files", files)
        with sqlite3.connect(database) as connection:
            connection.execute(f"PRAGMA foreign_keys = {SHIPPING_FOREIGN_KEYS}")
            actual_foreign_keys = connection.execute("PRAGMA foreign_keys").fetchone()
            if actual_foreign_keys != (SHIPPING_FOREIGN_KEYS,):
                raise MigrationGateError(
                    "cannot configure the shipping foreign_keys pragma state: "
                    f"expected={SHIPPING_FOREIGN_KEYS} actual={actual_foreign_keys}"
                )
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
            expected_schema = protected_schema(connection)
            apply_released_migrations(connection, fixture_version, target_version)
            reconcile_runtime_metadata(
                connection,
                target_version,
                target_cache_format_version,
            )
            if destructive_sql is not None:
                connection.executescript(destructive_sql)
            connection.commit()
        assert_fixture_preserved(
            database,
            files,
            expected_state,
            expected_schema,
            source_metadata,
            target_version,
            target_cache_format_version,
        )


def prove_cache_format_bump_is_legal(fixture: Path, version: int) -> None:
    migrate_and_assert_fixture(
        fixture_version=version,
        fixture=fixture,
        target_version=version,
        target_cache_format_version=current_cache_format_version() + 1,
    )


NEGATIVE_CONTROLS = (
    (
        "trigger_change",
        """
        CREATE TRIGGER delete_scrobbles_after_resume_insert
        AFTER INSERT ON resume_position
        BEGIN
          DELETE FROM scrobble_outbox;
        END;
        """,
        ("protected schema or trigger definitions changed",),
    ),
    (
        "scrobble_session_key_collapse",
        """
        ALTER TABLE scrobble_outbox RENAME TO scrobble_outbox_before_key_collapse;
        CREATE TABLE scrobble_outbox (
          server_id TEXT NOT NULL,
          raw_id TEXT NOT NULL,
          session_start_wall_clock INTEGER NOT NULL,
          created_at_wall_clock INTEGER NOT NULL,
          attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
          PRIMARY KEY (server_id, raw_id)
        );
        INSERT OR IGNORE INTO scrobble_outbox
        SELECT * FROM scrobble_outbox_before_key_collapse;
        DROP TABLE scrobble_outbox_before_key_collapse;
        """,
        (
            "scrobble_outbox: protected rows changed",
            "protected schema or trigger definitions changed",
        ),
    ),
    (
        "download_profile_key_collapse",
        """
        ALTER TABLE download RENAME TO download_before_key_collapse;
        CREATE TABLE download (
          server_id TEXT NOT NULL,
          raw_id TEXT NOT NULL,
          transcode_profile TEXT NOT NULL,
          download_id TEXT NOT NULL,
          state TEXT NOT NULL CHECK (
            state IN ('queued', 'downloading', 'interrupted', 'complete', 'stale')
          ),
          file_relative_path TEXT NOT NULL,
          expected_byte_length INTEGER,
          file_size_bytes INTEGER NOT NULL DEFAULT 0 CHECK (file_size_bytes >= 0),
          platform_resume_data BLOB,
          resume_data_created_at_wall_clock INTEGER,
          PRIMARY KEY (server_id, raw_id),
          UNIQUE (download_id),
          UNIQUE (file_relative_path),
          CHECK (expected_byte_length IS NULL OR expected_byte_length >= 0)
        );
        INSERT OR IGNORE INTO download
        SELECT * FROM download_before_key_collapse;
        DROP TABLE download_before_key_collapse;
        """,
        (
            "download: protected rows changed",
            "protected schema or trigger definitions changed",
        ),
    ),
    (
        "mutation_constraints_stripped",
        """
        ALTER TABLE mutation_outbox RENAME TO mutation_outbox_before_constraints_stripped;
        CREATE TABLE mutation_outbox (
          server_id TEXT,
          target_id TEXT,
          field TEXT,
          value TEXT,
          local_sequence INTEGER,
          wall_clock INTEGER
        );
        INSERT INTO mutation_outbox
        SELECT * FROM mutation_outbox_before_constraints_stripped;
        DROP TABLE mutation_outbox_before_constraints_stripped;
        """,
        ("protected schema or trigger definitions changed",),
    ),
    (
        "predicate_deletes",
        """
        DELETE FROM scrobble_outbox WHERE attempt_count = 0;
        DELETE FROM mutation_outbox WHERE field = 'favorite';
        DELETE FROM download WHERE state IN ('queued', 'stale');
        DELETE FROM resume_position WHERE position_milliseconds < 1000;
        """,
        tuple(f"{table}: protected rows changed" for table in PROTECTED_COLUMNS),
    ),
    (
        "download_resume_data_nulled",
        """
        UPDATE download
        SET platform_resume_data = NULL,
            resume_data_created_at_wall_clock = NULL;
        """,
        ("download: protected rows changed",),
    ),
    (
        "position_units_reinterpreted",
        """
        UPDATE resume_position
        SET position_milliseconds = position_milliseconds / 1000;
        """,
        ("resume_position: protected rows changed",),
    ),
    (
        "session_wall_clock_failed_conversion",
        """
        UPDATE scrobble_outbox
        SET session_start_wall_clock =
          CAST('invalid-' || session_start_wall_clock AS INTEGER) + rowid;
        """,
        ("scrobble_outbox: protected rows changed",),
    ),
    (
        "forged_golden_values",
        """
        UPDATE resume_position SET position_milliseconds = 0;
        UPDATE scrobble_outbox SET attempt_count = 0;
        """,
        (
            "scrobble_outbox: protected rows changed",
            "resume_position: protected rows changed",
        ),
    ),
)


def write_forged_expected_file(fixture: Path, version: int, sql: str) -> None:
    with tempfile.TemporaryDirectory(prefix="dulcet-forged-golden-") as temp:
        shadow_database = Path(temp) / "database.db"
        shutil.copy2(fixture / "database.db", shadow_database)
        with sqlite3.connect(shadow_database) as connection:
            connection.executescript(sql)
            forged_state, errors = protected_state(connection, fixture / "files")
            if errors:
                raise MigrationGateError(
                    "cannot build forged expected file:\n" + "\n".join(errors)
                )
            metadata = connection.execute(
                "SELECT schema_version, cache_format_version, committed_generation "
                "FROM schema_meta WHERE singleton_id = 1"
            ).fetchone()
    if metadata is None:
        raise MigrationGateError("cannot build forged expected file without schema_meta")
    forged_state["schema_meta"] = {
        "source_schema_version": version,
        "cache_format_version": metadata[1],
        "committed_generation": metadata[2],
    }
    (fixture / "expected.json").write_text(
        json.dumps(forged_state, indent=2, sort_keys=True) + "\n"
    )


def prove_destructive_migrations_are_rejected(fixture: Path, version: int) -> None:
    for name, sql, required_evidence in NEGATIVE_CONTROLS:
        temporary_fixture = None
        try:
            control_fixture = fixture
            if name == "forged_golden_values":
                temporary_fixture = tempfile.TemporaryDirectory(
                    prefix="dulcet-forged-fixture-"
                )
                control_fixture = Path(temporary_fixture.name) / fixture.name
                shutil.copytree(fixture, control_fixture)
                write_forged_expected_file(control_fixture, version, sql)
            try:
                migrate_and_assert_fixture(version, control_fixture, version, sql)
            except MigrationGateError as failure:
                message = str(failure)
                missing = {marker for marker in required_evidence if marker not in message}
                if missing:
                    raise MigrationGateError(
                        f"negative control {name} failed for incomplete reasons; "
                        f"missing {sorted(missing)}\n{message}"
                    ) from failure
                continue
            raise MigrationGateError(f"negative control {name} was unexpectedly accepted")
        finally:
            if temporary_fixture is not None:
                temporary_fixture.cleanup()


def main() -> None:
    current = current_schema_version()
    validate_schema_and_forbidden_column_name_contract(current)
    fixtures = fixture_versions()
    expected_versions = set(range(1, current + 1))
    if set(fixtures) != expected_versions:
        raise MigrationGateError(
            f"fixture versions differ from released schemas: "
            f"expected={sorted(expected_versions)} actual={sorted(fixtures)}"
        )
    for version, fixture in sorted(fixtures.items()):
        migrate_and_assert_fixture(version, fixture, current)
    prove_cache_format_bump_is_legal(fixtures[current], current)
    prove_destructive_migrations_are_rejected(fixtures[1], 1)
    print(
        f"Migration gate valid: {len(fixtures)} fixture database(s), "
        f"{len(PROTECTED_COLUMNS)} protected table comparisons per fixture, "
        f"download file reconciliation, and {len(NEGATIVE_CONTROLS)} explicit destructive "
        "negative controls"
    )


if __name__ == "__main__":
    main()
