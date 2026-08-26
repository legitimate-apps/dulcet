#!/usr/bin/env python3

"""Validate Dulcet's deterministic macOS design-capture artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


# The source of truth is the Swift enum DulcetPresentationState in
# apple/DulcetKit/Sources/DulcetKit/PresentationModels.swift, and this tuple mirrors it in the same
# order so a drift is visible in a side-by-side read. Adding a case there without adding it here
# fails this gate with an "unexpected" file set rather than a missing one, which reads like a
# capture defect and is not — it is this list being stale. That is what happened when the library
# states below were introduced.
STATES = (
    "account-connect-idle",
    "account-connecting",
    "account-connected",
    "account-error-input",
    "account-error-transport",
    "account-error-security",
    "account-error-protocol",
    "account-error-server",
    "account-error-authentication",
    "account-error-capability",
    "account-error-persistence",
    "empty-library-no-account",
    "empty-library-connected",
    "library-loading",
    "library-error",
    "library-browse",
    "album-detail-multi-disc",
    "now-playing",
    "now-playing-unavailable",
    "search-idle",
    "search-loading",
    "search-results",
    "search-empty",
    "search-error",
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
        raise CaptureVerificationError("JPEG is missing its embedded consistency comment")
    segment_length = int.from_bytes(data[4:6], "big")
    segment_end = 4 + segment_length
    if segment_length < 2 or segment_end > len(data):
        raise CaptureVerificationError("JPEG consistency comment has an invalid segment length")
    try:
        lines = data[6:segment_end].decode("ascii").splitlines()
    except UnicodeDecodeError as error:
        raise CaptureVerificationError("JPEG consistency comment is not ASCII") from error
    if not lines or lines[0] != "DULCET-CAPTURE-BINDING-V1":
        raise CaptureVerificationError("JPEG consistency comment version mismatch")
    fields: dict[str, str] = {}
    for line in lines[1:]:
        key, separator, value = line.partition("=")
        if not separator or not key or key in fields:
            raise CaptureVerificationError("JPEG consistency comment fields are malformed")
        fields[key] = value
    expected_fields = {"fixtureState", "appearance", "variant", "jpegPayloadSha256"}
    if set(fields) != expected_fields:
        raise CaptureVerificationError("JPEG consistency comment field set mismatch")
    payload = data[:2] + data[segment_end:]
    if hashlib.sha256(payload).hexdigest() != fields["jpegPayloadSha256"]:
        raise CaptureVerificationError("JPEG consistency comment SHA-256 mismatch")
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


def decoded_pixel_digest(path: Path) -> str:
    """Return a digest of the normalized top-to-bottom decoded BGR pixel rows."""
    with tempfile.TemporaryDirectory(prefix="dulcet-decoded-pixels-") as temporary:
        bitmap_path = Path(temporary) / "decoded.bmp"
        result = subprocess.run(
            ["/usr/bin/sips", "-s", "format", "bmp", str(path), "--out", str(bitmap_path)],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if result.returncode != 0 or not bitmap_path.is_file():
            raise CaptureVerificationError(f"could not decode JPEG pixels: {path.name}")
        bitmap = bitmap_path.read_bytes()

    if len(bitmap) < 54 or bitmap[:2] != b"BM":
        raise CaptureVerificationError(f"decoded pixel bitmap is malformed: {path.name}")
    pixel_offset = int.from_bytes(bitmap[10:14], "little")
    dib_size = int.from_bytes(bitmap[14:18], "little")
    width = int.from_bytes(bitmap[18:22], "little", signed=True)
    height = int.from_bytes(bitmap[22:26], "little", signed=True)
    planes = int.from_bytes(bitmap[26:28], "little")
    bits_per_pixel = int.from_bytes(bitmap[28:30], "little")
    compression = int.from_bytes(bitmap[30:34], "little")
    if (
        dib_size < 40
        or width <= 0
        or height == 0
        or planes != 1
        or bits_per_pixel != 24
        or compression != 0
    ):
        raise CaptureVerificationError(f"decoded pixel bitmap format is unsupported: {path.name}")

    row_bytes = width * 3
    stride = (row_bytes + 3) & ~3
    row_count = abs(height)
    pixel_end = pixel_offset + stride * row_count
    if pixel_offset < 14 + dib_size or pixel_end > len(bitmap):
        raise CaptureVerificationError(f"decoded pixel bitmap is truncated: {path.name}")

    rows = [
        bitmap[pixel_offset + row * stride:pixel_offset + row * stride + row_bytes]
        for row in range(row_count)
    ]
    if height > 0:
        rows.reverse()
    digest = hashlib.sha256()
    digest.update(width.to_bytes(4, "big"))
    digest.update(row_count.to_bytes(4, "big"))
    for row in rows:
        digest.update(row)
    return digest.hexdigest()


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
        "schemaVersion": 9,
        "widthPixels": WIDTH,
        "heightPixels": HEIGHT,
        "captureSurface": "titled-nswindow-with-standard-chrome",
        "windowTitlePolicy": "visible-centered-standard-window-title",
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

    observed_pixel_hashes: dict[str, str] = {}
    bindings_by_file: dict[str, dict[str, str]] = {}
    for filename in sorted(expected):
        path = directory / filename
        data = path.read_bytes()
        binding, _ = jpeg_payload_binding(data)
        pixel_digest = decoded_pixel_digest(path)
        if pixel_digest in observed_pixel_hashes:
            raise CaptureVerificationError(
                "capture decoded pixels are not pairwise distinct: "
                f"{observed_pixel_hashes[pixel_digest]} and {filename} "
                "decode to identical pixel content"
            )
        observed_pixel_hashes[pixel_digest] = filename
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
                    f"{directory.name}/{filename} pinned control provenance declaration mismatch"
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
                    f"{directory.name}/{filename} reference provenance declaration mismatch"
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
    # Counted, never spelled out. This line was a hardcoded "standard=38 jpeg=38" while the tool
    # verified a different number of files, so the evidence line asserted something the run had not
    # measured — the failure mode a stale comment causes, applied to the artifact's own summary.
    verified_files = len(expected_standard_files())
    print(
        f"DESIGN CAPTURE PASS standard={verified_files} jpeg={verified_files} size=1180x760 "
        "frame=1180x760 capture-bounds=0,0,1180x760 control-active-state=key "
        "decoded-pixels-pairwise-distinct=true "
        "filename-manifest-embedded-labels-consistent=true dynamic-type-claim=absent "
        "pinned-control-bytes-match-reviewed-sha256=true "
        "capture-provenance-declarations-consistent=true"
    )


if __name__ == "__main__":
    main()
