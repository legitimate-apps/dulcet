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
    "download_policy_state",
    "scrobble_outbox",
    "mutation_outbox",
    "artwork_cache",
    "resume_position",
    "sync_checkpoint",
    "sync_seen",
    "sync_generation",
    "deletion_reconciliation",
    "schema_meta",
    "cache_binding",
    "cache_epoch",
    "cache_artist",
    "cache_album",
    "cache_track",
    "cache_playlist",
    "cache_credit",
    "cache_list",
    "cache_list_member",
    "cache_pin",
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
    "download_policy_state",
    "scrobble_outbox",
    "mutation_outbox",
    "resume_position",
    "sync_checkpoint",
    "sync_seen",
    "sync_generation",
    "deletion_reconciliation",
    "schema_meta",
    "cache_pin",
    "cache_track",
    "cache_album",
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
    # Revision 99 (spec §11.4): the pins of downloaded and queued items are protected, because a
    # downloaded file with nothing to display is exactly the case offline use exists for.
    "cache_pin": (
        "server_id",
        "item_kind",
        "raw_id",
        "reason",
    ),
}
# A protected table that a released schema introduced is compared only from that version on; the
# migration that introduces it is held to the pin-coverage contract instead.
PROTECTED_SINCE_VERSION = {
    "cache_pin": 6,
}
PIN_COVERAGE_SINCE_VERSION = 6
# Cache tables are re-derivable and so not protected (§11.4), but a column a released schema added
# to one is compared WITH DATA from the first fixture that holds it: every later migration must keep
# those rows as they were. A cache rebuilt on purpose goes through §11.5 and changes this map.
COMPARED_CACHE_ROWS_SINCE_VERSION = {
    "cache_playlist": 7,
}


def protected_tables(version: int) -> tuple[str, ...]:
    return tuple(
        table
        for table in PROTECTED_COLUMNS
        if version >= PROTECTED_SINCE_VERSION.get(table, 1)
    )


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


def protected_schema(connection: sqlite3.Connection, version: int) -> dict[str, object]:
    tables: dict[str, object] = {}
    for table in protected_tables(version):
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
    version: int,
) -> tuple[dict[str, list[dict[str, object]]], list[str]]:
    state: dict[str, list[dict[str, object]]] = {}
    errors: list[str] = []
    for table in protected_tables(version):
        try:
            state[table] = canonical_rows(connection, table)
        except sqlite3.DatabaseError as failure:
            errors.append(f"{table}: cannot read protected rows: {failure}")
            state[table] = []
    downloads, file_errors = reconcile_download_files(state["download"], files)
    state["download"] = downloads
    errors.extend(file_errors)
    return state, errors


def pin_coverage_errors(connection: sqlite3.Connection) -> list[str]:
    """CONF-81: every download and queue entry pins its track, and every pin names a cached row."""
    errors: list[str] = []
    for table, reason in (("download", "download"), ("queue_entry", "queue")):
        for server_id, raw_id in connection.execute(
            f'SELECT server_id, raw_id FROM "{table}" AS source WHERE NOT EXISTS ('
            "SELECT 1 FROM cache_pin AS pin WHERE pin.server_id = source.server_id "
            "AND pin.item_kind = 'track' AND pin.raw_id = source.raw_id AND pin.reason = ?)",
            (reason,),
        ):
            errors.append(f"missing pin: {table} row {server_id}/{raw_id} has no {reason} pin")
    for server_id, kind, raw_id in connection.execute(
        "SELECT server_id, item_kind, raw_id FROM cache_pin AS pin WHERE "
        "(item_kind = 'track' AND NOT EXISTS (SELECT 1 FROM cache_track AS t "
        "WHERE t.server_id = pin.server_id AND t.raw_id = pin.raw_id)) OR "
        "(item_kind = 'album' AND NOT EXISTS (SELECT 1 FROM cache_album AS a "
        "WHERE a.server_id = pin.server_id AND a.raw_id = pin.raw_id))"
    ):
        errors.append(f"pinned {kind} has no cache row: {server_id}/{raw_id}")
    return errors


def table_structure(connection: sqlite3.Connection) -> dict[str, dict[str, object]]:
    """Every table and index as SQLite reports its structure: a table's columns in order (name,
    type, NOT NULL, default, key position, hidden) and its foreign keys; an index's table and
    columns. The CREATE text is deliberately not compared: SQLite records `ALTER TABLE ... ADD
    COLUMN` by editing the stored text, so an upgraded table and a fresh one are the same table with
    different text."""
    structure: dict[str, dict[str, object]] = {}
    for kind, name, table in connection.execute(
        "SELECT type, name, tbl_name FROM sqlite_master "
        "WHERE type IN ('table', 'index') AND name NOT LIKE 'sqlite_%' ORDER BY type, name"
    ).fetchall():
        entry: dict[str, object] = {"on": table}
        if kind == "table":
            entry["columns"] = [
                list(column) for column in connection.execute(f'PRAGMA table_xinfo("{name}")')
            ]
            entry["foreign_keys"] = sorted(
                list(foreign_key)[2:]
                for foreign_key in connection.execute(f'PRAGMA foreign_key_list("{name}")')
            )
        else:
            entry["columns"] = [
                list(column) for column in connection.execute(f'PRAGMA index_xinfo("{name}")')
            ]
        structure[f"{kind} {name}"] = entry
    return structure


def fresh_install_structure(version: int) -> dict[str, dict[str, object]]:
    """The schema a fresh install of [version] creates: SQLDelight's own snapshot of the .sq files."""
    snapshot = SCHEMA_SNAPSHOTS / f"{version}.db"
    connection = sqlite3.connect(f"file:{snapshot}?mode=ro", uri=True)
    try:
        structure = table_structure(connection)
    finally:
        connection.close()
    # The instrument must have read a schema, or every comparison with it is vacuous.
    if not any(key.startswith("table ") for key in structure):
        raise MigrationGateError(f"fresh-install snapshot {snapshot.name} holds no tables")
    return structure


def upgrade_structure_errors(
    expected: dict[str, dict[str, object]],
    actual: dict[str, dict[str, object]],
) -> list[str]:
    """How an upgraded database differs from a fresh install of the same version."""
    errors: list[str] = []
    for key in sorted(set(expected) | set(actual)):
        if key not in actual:
            errors.append(f"{key}: missing")
            continue
        if key not in expected:
            errors.append(f"{key}: not in a fresh install")
            continue
        for field in sorted(set(expected[key]) | set(actual[key])):
            wanted, found = expected[key].get(field), actual[key].get(field)
            if wanted == found:
                continue
            if field == "columns" and key.startswith("table "):
                wanted_names = [column[1] for column in wanted or []]
                found_names = [column[1] for column in found or []]
                missing = [name for name in wanted_names if name not in found_names]
                extra = [name for name in found_names if name not in wanted_names]
                if missing:
                    errors.append(f"{key}: missing columns {missing}")
                if extra:
                    errors.append(f"{key}: extra columns {extra}")
                if not missing and not extra:
                    errors.append(f"{key}: column definitions differ: fresh={wanted} upgraded={found}")
            else:
                errors.append(f"{key}: {field} differs: fresh={wanted} upgraded={found}")
    return errors


def compared_cache_rows(
    connection: sqlite3.Connection,
    version: int,
) -> dict[str, tuple[list[str], list[dict[str, object]]]]:
    """Each compared cache table the fixture [version] holds: its columns, and its rows by them."""
    compared: dict[str, tuple[list[str], list[dict[str, object]]]] = {}
    for table, since in COMPARED_CACHE_ROWS_SINCE_VERSION.items():
        if version < since:
            continue
        columns = [column[1] for column in connection.execute(f'PRAGMA table_info("{table}")')]
        compared[table] = (columns, cache_rows(connection, table, columns))
    return compared


def cache_rows(
    connection: sqlite3.Connection,
    table: str,
    columns: list[str],
) -> list[dict[str, object]]:
    selected = ", ".join(f'"{column}"' for column in columns)
    rows = [
        {column: value.hex() if isinstance(value, bytes) else value for column, value in zip(columns, row, strict=True)}
        for row in connection.execute(f'SELECT {selected} FROM "{table}"')
    ]
    return sorted(rows, key=lambda row: json.dumps(row, sort_keys=True))


def assert_fixture_preserved(
    database: Path,
    files: Path,
    expected_state: dict[str, list[dict[str, object]]],
    expected_schema: dict[str, object],
    source_metadata: tuple[int, int, int],
    target_version: int,
    target_cache_format_version: int,
    expected_cache_rows: dict[str, tuple[list[str], list[dict[str, object]]]] | None = None,
) -> None:
    errors: list[str] = []
    source_version = source_metadata[0]
    with sqlite3.connect(database) as connection:
        actual, state_errors = protected_state(connection, files, source_version)
        errors.extend(state_errors)
        actual_schema = protected_schema(connection, source_version)
        if target_version >= PIN_COVERAGE_SINCE_VERSION:
            errors.extend(pin_coverage_errors(connection))
        # An upgrade must end where a fresh install starts: the same tables, columns and indexes.
        if target_version == current_schema_version():
            structure_errors = upgrade_structure_errors(
                fresh_install_structure(target_version), table_structure(connection)
            )
            if structure_errors:
                errors.append(
                    f"upgraded schema differs from a fresh install of v{target_version}:\n"
                    + "\n".join(structure_errors)
                )
        for table, (columns, rows) in (expected_cache_rows or {}).items():
            try:
                found = cache_rows(connection, table, columns)
            except sqlite3.DatabaseError as failure:
                errors.append(f"{table}: cannot read compared cache rows: {failure}")
                continue
            if found != rows:
                errors.append(
                    f"{table}: compared cache rows changed\n"
                    f"expected={json.dumps(rows, sort_keys=True)}\n"
                    f"actual={json.dumps(found, sort_keys=True)}"
                )
        metadata = connection.execute(
            "SELECT schema_version, cache_format_version, committed_generation "
            "FROM schema_meta WHERE singleton_id = 1"
        ).fetchone()
        actual["schema_meta"] = list(metadata) if metadata is not None else None
    for table in protected_tables(source_version):
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
            expected_state, fixture_errors = protected_state(connection, files, fixture_version)
            if fixture_errors:
                raise MigrationGateError(
                    "invalid pre-migration fixture:\n" + "\n".join(fixture_errors)
                )
            expected_schema = protected_schema(connection, fixture_version)
            expected_cache_rows = compared_cache_rows(connection, fixture_version)
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
            expected_cache_rows,
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
        tuple(f"{table}: protected rows changed" for table in protected_tables(1)),
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


# Pin controls run on the first fixture that the pin rules apply to, migrated to the current
# schema, so they exercise the introducing migration and every later one.
PIN_NEGATIVE_CONTROLS = (
    (
        "queue_pins_dropped",
        "DELETE FROM cache_pin WHERE reason = 'queue';",
        ("missing pin: queue_entry row",),
    ),
    (
        "pinned_rows_dropped",
        "DELETE FROM cache_track WHERE metadata_missing = 1;",
        ("pinned track has no cache row",),
    ),
)
# The upgrade controls run on the fixture before the one that added the compared cache columns,
# migrated to the current schema; the cache-row controls on the first fixture that holds them.
UPGRADE_NEGATIVE_CONTROLS = (
    (
        "added_column_dropped",
        "ALTER TABLE cache_playlist DROP COLUMN readonly;",
        (
            "upgraded schema differs from a fresh install",
            "table cache_playlist: missing columns ['readonly']",
        ),
    ),
)
CACHE_ROW_NEGATIVE_CONTROLS = (
    (
        "added_fields_cleared",
        "UPDATE cache_playlist SET comment = NULL, is_public = NULL, readonly = NULL;",
        ("cache_playlist: compared cache rows changed",),
    ),
)
# Once pins exist in a released fixture they are protected rows like any other.
PROTECTED_PIN_NEGATIVE_CONTROLS = (
    (
        "download_pins_dropped",
        "DELETE FROM cache_pin WHERE reason = 'download';",
        ("cache_pin: protected rows changed", "missing pin: download row"),
    ),
)


def prove_negative_controls(
    fixture: Path,
    fixture_version: int,
    target_version: int,
    controls: tuple[tuple[str, str, tuple[str, ...]], ...],
) -> None:
    for name, sql, required_evidence in controls:
        try:
            migrate_and_assert_fixture(fixture_version, fixture, target_version, sql)
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


def write_forged_expected_file(fixture: Path, version: int, sql: str) -> None:
    with tempfile.TemporaryDirectory(prefix="dulcet-forged-golden-") as temp:
        shadow_database = Path(temp) / "database.db"
        shutil.copy2(fixture / "database.db", shadow_database)
        with sqlite3.connect(shadow_database) as connection:
            connection.executescript(sql)
            forged_state, errors = protected_state(connection, fixture / "files", version)
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
    compared_since = COMPARED_CACHE_ROWS_SINCE_VERSION["cache_playlist"]
    with sqlite3.connect(f"file:{fixtures[compared_since] / 'database.db'}?mode=ro", uri=True) as connection:
        compared_rows = compared_cache_rows(connection, compared_since)["cache_playlist"][1]
    # The control for the row comparison: the fixture carries the added fields WITH values, so the
    # comparison above compared data, not two empty lists.
    stated = [row for row in compared_rows if None not in (row["comment"], row["is_public"], row["readonly"])]
    if not stated:
        raise MigrationGateError(
            f"fixture v{compared_since} holds no cache_playlist row with its added fields stated"
        )
    prove_cache_format_bump_is_legal(fixtures[current], current)
    prove_destructive_migrations_are_rejected(fixtures[1], 1)
    pin_fixture_version = PIN_COVERAGE_SINCE_VERSION - 1
    prove_negative_controls(
        fixtures[pin_fixture_version], pin_fixture_version, current, PIN_NEGATIVE_CONTROLS
    )
    protected_pin_version = PROTECTED_SINCE_VERSION["cache_pin"]
    prove_negative_controls(
        fixtures[protected_pin_version],
        protected_pin_version,
        current,
        PROTECTED_PIN_NEGATIVE_CONTROLS,
    )
    prove_negative_controls(
        fixtures[compared_since - 1], compared_since - 1, current, UPGRADE_NEGATIVE_CONTROLS
    )
    prove_negative_controls(
        fixtures[compared_since], compared_since, current, CACHE_ROW_NEGATIVE_CONTROLS
    )
    control_count = (
        len(NEGATIVE_CONTROLS)
        + len(PIN_NEGATIVE_CONTROLS)
        + len(PROTECTED_PIN_NEGATIVE_CONTROLS)
        + len(UPGRADE_NEGATIVE_CONTROLS)
        + len(CACHE_ROW_NEGATIVE_CONTROLS)
    )
    print(
        f"Migration gate valid: {len(fixtures)} fixture database(s), "
        f"{len(protected_tables(current))} protected table comparisons at v{current}, "
        f"pin coverage from v{PIN_COVERAGE_SINCE_VERSION}, "
        f"every fixture upgraded to v{current} equal to a fresh install "
        f"({len(fresh_install_structure(current))} tables and indexes), "
        f"cache_playlist rows compared from v{compared_since} ({len(compared_rows)} rows, "
        f"{len(stated)} with the added fields stated), "
        f"download file reconciliation, and {control_count} explicit destructive "
        "negative controls"
    )


if __name__ == "__main__":
    main()
