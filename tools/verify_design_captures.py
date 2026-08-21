#!/usr/bin/env python3

"""Validate Dulcet's deterministic macOS design-capture artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


STATES = (
    "empty-library-no-account",
    "library-browse",
    "album-detail-multi-disc",
    "now-playing",
    "search-mixed-sources",
    "error-tls-untrusted",
    "offline-metadata-only",
)
APPEARANCES = ("light", "dark")
WIDTH = 1180
HEIGHT = 760
MIN_JPEG_BYTES = 5_000
MAX_JPEG_BYTES = 350_000


class CaptureVerificationError(RuntimeError):
    pass


def expected_standard_files() -> set[str]:
    files = {
        f"macos-{state}-{appearance}.jpg"
        for state in STATES
        for appearance in APPEARANCES
    }
    files.update(
        f"macos-CONTROL-DELIBERATELY-BAD-library-browse-{appearance}.jpg"
        for appearance in APPEARANCES
    )
    return files


def jpeg_dimensions(data: bytes) -> tuple[int, int]:
    if not data.startswith(b"\xff\xd8"):
        raise CaptureVerificationError("file does not start with the JPEG SOI marker")
    cursor = 2
    start_of_frame = {
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
        0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    }
    while cursor + 4 <= len(data):
        if data[cursor] != 0xFF:
            cursor += 1
            continue
        while cursor < len(data) and data[cursor] == 0xFF:
            cursor += 1
        if cursor >= len(data):
            break
        marker = data[cursor]
        cursor += 1
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        if cursor + 2 > len(data):
            break
        length = int.from_bytes(data[cursor:cursor + 2], "big")
        if length < 2 or cursor + length > len(data):
            raise CaptureVerificationError("JPEG has an invalid segment length")
        if marker in start_of_frame:
            if length < 7:
                raise CaptureVerificationError("JPEG start-of-frame segment is too short")
            height = int.from_bytes(data[cursor + 3:cursor + 5], "big")
            width = int.from_bytes(data[cursor + 5:cursor + 7], "big")
            return width, height
        cursor += length
    raise CaptureVerificationError("JPEG dimensions were not found")


def verify_set(directory: Path, expected: set[str]) -> None:
    if not directory.is_dir():
        raise CaptureVerificationError(f"missing capture directory: {directory.name}")
    manifest_path = directory / "manifest.json"
    if not manifest_path.is_file():
        raise CaptureVerificationError(f"missing manifest: {directory.name}/manifest.json")
    manifest_text = manifest_path.read_text()
    if "file://" in manifest_text:
        raise CaptureVerificationError(f"manifest contains a machine-specific path: {directory.name}")
    manifest = json.loads(manifest_text)
    contract = {
        "schemaVersion": 3,
        "widthPixels": WIDTH,
        "heightPixels": HEIGHT,
        "captureSurface": "titled-nswindow-with-standard-chrome",
        "windowTitlePolicy": "release-name-fixture-with-state-navigation-titles",
        "textSizingPolicy": "macos-system-semantic-fonts-no-dynamic-type-claim",
        "preflightRender": "discarded-library-browse-light-before-recording",
        "jpegCompression": 0.72,
        "locale": "en_US_POSIX",
        "calendar": "gregorian",
        "timeZone": "UTC",
        "fixedClock": "2026-08-21T14:32:00Z",
        "network": "disabled-by-fixture-source",
    }
    for key, expected_value in contract.items():
        if manifest.get(key) != expected_value:
            raise CaptureVerificationError(
                f"{directory.name} manifest {key} mismatch: "
                f"expected {expected_value!r}, observed {manifest.get(key)!r}"
            )

    expected_files = expected | {"manifest.json"}
    observed_entries = {path.name for path in directory.iterdir()}
    if observed_entries != expected_files:
        raise CaptureVerificationError(
            f"{directory.name} exact file set mismatch: "
            f"missing={sorted(expected_files - observed_entries)} "
            f"unexpected={sorted(observed_entries - expected_files)}"
        )

    records = manifest.get("captures")
    if not isinstance(records, list):
        raise CaptureVerificationError(f"{directory.name} manifest captures is not a list")
    records_by_file = {record.get("file"): record for record in records}
    if set(records_by_file) != expected or len(records_by_file) != len(records):
        raise CaptureVerificationError(f"{directory.name} manifest record set is not exact")

    for filename in sorted(expected):
        path = directory / filename
        data = path.read_bytes()
        if not MIN_JPEG_BYTES <= len(data) <= MAX_JPEG_BYTES:
            raise CaptureVerificationError(
                f"{directory.name}/{filename} size {len(data)} is outside "
                f"{MIN_JPEG_BYTES}..{MAX_JPEG_BYTES}"
            )
        dimensions = jpeg_dimensions(data)
        if dimensions != (WIDTH, HEIGHT):
            raise CaptureVerificationError(
                f"{directory.name}/{filename} dimensions mismatch: {dimensions}"
            )
        record = records_by_file[filename]
        expected_hash = hashlib.sha256(data).hexdigest()
        if record.get("sha256") != expected_hash:
            raise CaptureVerificationError(f"{directory.name}/{filename} SHA-256 mismatch")
        if record.get("jpegBytes") != len(data):
            raise CaptureVerificationError(f"{directory.name}/{filename} byte count mismatch")
        if "dynamicType" in record:
            raise CaptureVerificationError(
                f"{directory.name}/{filename} claims unsupported macOS dynamicType evidence"
            )
        is_control = filename.startswith("macos-CONTROL-DELIBERATELY-BAD-")
        expected_variant = "deliberately-bad-control" if is_control else "reference"
        if record.get("variant") != expected_variant:
            raise CaptureVerificationError(f"{directory.name}/{filename} variant mismatch")


def verify_no_byte_identical_dynamic_type_treatments(root: Path) -> None:
    records_by_state: dict[tuple[str, str, str], list[dict[str, object]]] = {}
    for manifest_path in root.rglob("manifest.json"):
        manifest = json.loads(manifest_path.read_text())
        for record in manifest.get("captures", []):
            if "dynamicType" not in record:
                continue
            key = (
                str(record.get("fixtureState")),
                str(record.get("appearance")),
                str(record.get("variant")),
            )
            records_by_state.setdefault(key, []).append(record)

    for key, records in records_by_state.items():
        for index, first in enumerate(records):
            for second in records[index + 1:]:
                if first.get("dynamicType") == second.get("dynamicType"):
                    continue
                if first.get("sha256") == second.get("sha256"):
                    raise CaptureVerificationError(
                        "dynamicType treatment is byte-identical to its counterpart: "
                        f"state={key[0]} appearance={key[1]} "
                        f"first={first.get('dynamicType')} second={second.get('dynamicType')}"
                    )


def verify_capture_root(root: Path) -> None:
    verify_no_byte_identical_dynamic_type_treatments(root)
    expected_entries = {"standard"}
    observed_entries = {path.name for path in root.iterdir()}
    if observed_entries != expected_entries:
        raise CaptureVerificationError(
            "capture root exact directory set mismatch: "
            f"missing={sorted(expected_entries - observed_entries)} "
            f"unexpected={sorted(observed_entries - expected_entries)}"
        )
    verify_set(root / "standard", expected_standard_files())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    try:
        verify_capture_root(args.root)
    except CaptureVerificationError as error:
        raise SystemExit(f"DESIGN CAPTURE FAIL {error}") from error
    print("DESIGN CAPTURE PASS standard=16 jpeg=16 size=1180x760 dynamic-type-claim=absent")


if __name__ == "__main__":
    main()
