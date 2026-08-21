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
PINNED_CONTROL_SHA256 = {
    "light": "3c46bfa842033834d417f276c43ee29ce85e1f4eefd8cbea17faedecf1d6c60f",
    "dark": "ba23a4b9b8f257a747cf9050a03b54e5fb2e1f8f18ecca97ec1db8fce2cc74f6",
}


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


def expected_binding(filename: str) -> tuple[str, str, str]:
    bindings = {
        f"macos-{state}-{appearance}.jpg": (state, appearance, "reference")
        for state in STATES
        for appearance in APPEARANCES
    }
    bindings.update({
        f"macos-CONTROL-DELIBERATELY-BAD-library-browse-{appearance}.jpg": (
            "library-browse", appearance, "deliberately-bad-control"
        )
        for appearance in APPEARANCES
    })
    try:
        return bindings[filename]
    except KeyError as error:
        raise CaptureVerificationError(f"unexpected capture filename: {filename}") from error


def jpeg_payload_binding(data: bytes) -> tuple[dict[str, str], bytes]:
    if not data.startswith(b"\xff\xd8\xff\xfe") or len(data) < 8:
        raise CaptureVerificationError("JPEG is missing its payload binding comment")
    segment_length = int.from_bytes(data[4:6], "big")
    segment_end = 4 + segment_length
    if segment_length < 2 or segment_end > len(data):
        raise CaptureVerificationError("JPEG payload binding has an invalid segment length")
    try:
        lines = data[6:segment_end].decode("ascii").splitlines()
    except UnicodeDecodeError as error:
        raise CaptureVerificationError("JPEG payload binding is not ASCII") from error
    if not lines or lines[0] != "DULCET-CAPTURE-BINDING-V1":
        raise CaptureVerificationError("JPEG payload binding version mismatch")
    fields: dict[str, str] = {}
    for line in lines[1:]:
        key, separator, value = line.partition("=")
        if not separator or not key or key in fields:
            raise CaptureVerificationError("JPEG payload binding fields are malformed")
        fields[key] = value
    expected_fields = {"fixtureState", "appearance", "variant", "jpegPayloadSha256"}
    if set(fields) != expected_fields:
        raise CaptureVerificationError("JPEG payload binding field set mismatch")
    payload = data[:2] + data[segment_end:]
    if hashlib.sha256(payload).hexdigest() != fields["jpegPayloadSha256"]:
        raise CaptureVerificationError("JPEG payload binding SHA-256 mismatch")
    return fields, payload


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
        "schemaVersion": 8,
        "widthPixels": WIDTH,
        "heightPixels": HEIGHT,
        "captureSurface": "titled-nswindow-with-standard-chrome",
        "windowTitlePolicy": "hidden-redundant-window-title-content-headings-visible",
        "textSizingPolicy": "macos-system-semantic-fonts-no-dynamic-type-claim",
        "preflightRender": "discarded-library-browse-light-before-recording",
        "jpegCompression": 0.72,
        "locale": "en_US_POSIX",
        "calendar": "gregorian",
        "timeZone": "UTC",
        "fixedClock": "2026-08-21T14:32:00Z",
        "network": "disabled-by-fixture-source",
        "controlBaselinePolicy": "bundled-reviewed-resources-explicit-regeneration-only",
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

    observed_hashes: dict[str, str] = {}
    observed_payload_hashes: dict[str, str] = {}
    bindings_by_file: dict[str, dict[str, str]] = {}
    for filename in sorted(expected):
        data = (directory / filename).read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if digest in observed_hashes:
            raise CaptureVerificationError(
                "captures are not pairwise distinct: "
                f"{observed_hashes[digest]} and {filename} are byte-identical"
            )
        observed_hashes[digest] = filename
        binding, payload = jpeg_payload_binding(data)
        payload_digest = hashlib.sha256(payload).hexdigest()
        if payload_digest in observed_payload_hashes:
            raise CaptureVerificationError(
                "capture visual payloads are not pairwise distinct: "
                f"{observed_payload_hashes[payload_digest]} and {filename} "
                "contain the same compressed image bytes"
            )
        observed_payload_hashes[payload_digest] = filename
        bindings_by_file[filename] = binding

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
        if record.get("controlActiveState") != "key":
            raise CaptureVerificationError(
                f"{directory.name}/{filename} controlActiveState is not key"
            )
        geometry_contract = {
            "windowFrameWidthPoints": WIDTH,
            "windowFrameHeightPoints": HEIGHT,
            "captureBoundsXPoints": 0,
            "captureBoundsYPoints": 0,
            "captureBoundsWidthPoints": WIDTH,
            "captureBoundsHeightPoints": HEIGHT,
        }
        for key, expected_value in geometry_contract.items():
            if record.get(key) != expected_value:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} {key} mismatch: "
                    f"expected {expected_value}, observed {record.get(key)!r}"
                )
        expected_state, expected_appearance, expected_variant = expected_binding(filename)
        if record.get("fixtureState") != expected_state:
            raise CaptureVerificationError(f"{directory.name}/{filename} fixtureState mismatch")
        if record.get("appearance") != expected_appearance:
            raise CaptureVerificationError(f"{directory.name}/{filename} appearance mismatch")
        if record.get("variant") != expected_variant:
            raise CaptureVerificationError(f"{directory.name}/{filename} variant mismatch")
        if expected_variant == "deliberately-bad-control":
            if record.get("captureProvenance") != "bundled-pinned-resource":
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} pinned control provenance mismatch"
                )
            pinned_hash = PINNED_CONTROL_SHA256[expected_appearance]
            if expected_hash != pinned_hash:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} pinned control bytes mismatch"
                )
            if record.get("pinnedControlSha256") != pinned_hash:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} pinnedControlSha256 mismatch"
                )
        else:
            if record.get("captureProvenance") != "rendered-current-run":
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} reference capture provenance mismatch"
                )
            if "pinnedControlSha256" in record:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} reference capture claims a pinned control hash"
                )
        binding = bindings_by_file[filename]
        for field, expected_value in (
            ("fixtureState", expected_state),
            ("appearance", expected_appearance),
            ("variant", expected_variant),
        ):
            if binding[field] != expected_value:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} embedded {field} mismatch"
                )


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
    print(
        "DESIGN CAPTURE PASS standard=16 jpeg=16 size=1180x760 "
        "frame=1180x760 capture-bounds=0,0,1180x760 control-active-state=key "
        "pairwise-distinct=true visual-payloads-distinct=true "
        "payload-label-bindings=true dynamic-type-claim=absent "
        "pinned-controls=true control-provenance=verified"
    )


if __name__ == "__main__":
    main()
