#!/usr/bin/env python3

"""Validate Dulcet's deterministic macOS design-capture artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
import subprocess
import tempfile


# The source of truth is the Swift enum DulcetPresentationState in
# apple/DulcetKit/Sources/DulcetKit/PresentationModels.swift, and this tuple mirrors it in the same
# order. A comment saying "keep these in sync" is not a check, and this drifted twice anyway: adding
# a case there without adding it here fails with an "unexpected" file set rather than a missing one,
# which reads like a capture defect and is not. assert_states_match_swift_enum below now enforces
# the mirror directly, so the drift fails with its own cause named instead of as a capture symptom.
STATES = (
    "account-connect-idle",
    "account-connect-empty",
    "account-connecting",
    "account-connected",
    "account-removing",
    "account-removal-error",
    "account-saved-disconnected",
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
    "artist-detail",
    "now-playing",
    "now-playing-preparing",
    "now-playing-failed",
    "now-playing-unavailable",
    "search-idle",
    "search-loading",
    "search-results",
    "search-empty",
    "search-error",
    "error-tls-untrusted",
    "error-tls-untrusted-populated-form",
    "offline-metadata-only",
)
PRESENTATION_STATE_SWIFT = Path(__file__).resolve().parent.parent / (
    "apple/DulcetKit/Sources/DulcetKit/PresentationModels.swift"
)


def assert_states_match_swift_enum() -> None:
    """Fail with the real cause when STATES and the Swift enum drift apart.

    Reported as its own error rather than as an "unexpected file" mismatch, because that symptom
    reads like capture non-determinism and has twice sent a reader looking for a flake that was not
    there.
    """
    if not PRESENTATION_STATE_SWIFT.exists():
        raise SystemExit(
            f"cannot verify the presentation-state mirror: {PRESENTATION_STATE_SWIFT} is absent"
        )
    source = PRESENTATION_STATE_SWIFT.read_text()
    enum_body = re.search(
        r"enum DulcetPresentationState[^{]*\{(.*?)\n\}", source, re.S
    )
    if enum_body is None:
        raise SystemExit(
            "cannot verify the presentation-state mirror: DulcetPresentationState was not found "
            f"in {PRESENTATION_STATE_SWIFT}"
        )
    swift_states = tuple(re.findall(r'case \w+ = "([a-z0-9-]+)"', enum_body.group(1)))
    if swift_states != STATES:
        missing = [state for state in swift_states if state not in STATES]
        extra = [state for state in STATES if state not in swift_states]
        raise SystemExit(
            "STATES no longer mirrors DulcetPresentationState. "
            f"in Swift but not STATES={missing}; in STATES but not Swift={extra}; "
            f"order_matches={list(swift_states) == list(STATES)}"
        )


APPEARANCES = ("light", "dark")
WINDOW_WIDTH_POINTS = 1180
WINDOW_HEIGHT_POINTS = 760
REFERENCE_CAPTURE_WIDTH_POINTS = 1180
REFERENCE_CAPTURE_HEIGHT_POINTS = 728
# This is deliberately one contract value. The renderer uses it for layout, layer contents, and
# bitmap density, so the verifier must reject any manifest that claims those grids differ.
CAPTURE_SCALE = 1.0
CAPTURE_WIDTH_PIXELS = int(REFERENCE_CAPTURE_WIDTH_POINTS * CAPTURE_SCALE)
CAPTURE_HEIGHT_PIXELS = int(REFERENCE_CAPTURE_HEIGHT_POINTS * CAPTURE_SCALE)
PINNED_CONTROL_WIDTH_PIXELS = 1180
PINNED_CONTROL_HEIGHT_PIXELS = 760
RENDERED_FOCUS_STATE = "no-focused-control"
PINNED_RESOURCE_FOCUS_STATE = "not-applicable-bundled-resource"
# The window's backing scale is ambient: hosted runners can be 1x while Retina Macs are 2x. The
# manifest records the resolved value, but it does not select any of the three capture grids. The
# workflow's exact-byte run-a/run-b diff still rejects a scale that changes between processes.
MIN_JPEG_BYTES = 5_000
MAX_REFERENCE_JPEG_BYTES = 350_000
MAX_PINNED_CONTROL_JPEG_BYTES = 350_000
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
        "schemaVersion": 14,
        "referenceWidthPixels": CAPTURE_WIDTH_PIXELS,
        "referenceHeightPixels": CAPTURE_HEIGHT_PIXELS,
        "pinnedControlWidthPixels": PINNED_CONTROL_WIDTH_PIXELS,
        "pinnedControlHeightPixels": PINNED_CONTROL_HEIGHT_PIXELS,
        "captureSurface": "ns-hosting-view-content-only-rendered-references",
        "windowTitlePolicy": (
            "configured-visible-but-excluded-from-rendered-reference-surface"
        ),
        "textSizingPolicy": "macos-system-semantic-fonts-no-dynamic-type-claim",
        "fontLineHeightPolicy": (
            "NSLayoutManager.defaultLineHeight(for:)-after-frame-convergence"
        ),
        "preflightRender": "discarded-all-states-all-appearances-before-recording",
        "appearanceResolutionPolicy": (
            "requested-appearance-current-before-host-construction"
        ),
        "layoutDiagnosticsPolicy": (
            "resolved-root-and-native-controls-after-frame-convergence"
        ),
        "settlePathPolicy": (
            "per-render-comparisons-until-first-identical-consecutive-frame-pair"
        ),
        "focusStatePolicy": (
            "capture-fixture-programmatic-focus-disabled-window-first-responder-"
            "asserted-every-bitmap-draw"
        ),
        "bitmapPixelsPerPoint": CAPTURE_SCALE,
        "fontSmoothingPolicy": "disabled-explicit-bitmap-context",
        "fontSubpixelPositioningPolicy": "disabled-explicit-bitmap-context",
        "fontSubpixelQuantizationPolicy": "disabled-explicit-bitmap-context",
        "hostLayerContentsScale": CAPTURE_SCALE,
        "jpegCompression": 0.72,
        "layoutDisplayScale": CAPTURE_SCALE,
        "resolvedAppleLanguagesCollectionStage": (
            "after-all-rendered-references-converged"
        ),
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

    resolved_apple_languages = manifest.get("resolvedAppleLanguages")
    if (
        not isinstance(resolved_apple_languages, list)
        or not resolved_apple_languages
        or any(
            not isinstance(language, str) or not language
            for language in resolved_apple_languages
        )
    ):
        raise CaptureVerificationError(
            f"{directory.name} manifest resolvedAppleLanguages is not a nonempty string array: "
            f"observed {resolved_apple_languages!r}"
        )

    window_backing_scale_factor = manifest.get("windowBackingScaleFactor")
    if (
        isinstance(window_backing_scale_factor, bool)
        or not isinstance(window_backing_scale_factor, (int, float))
        or not math.isfinite(window_backing_scale_factor)
        or window_backing_scale_factor <= 0
    ):
        raise CaptureVerificationError(
            f"{directory.name} manifest windowBackingScaleFactor is not positive: "
            f"observed {window_backing_scale_factor!r}"
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
        expected_state, expected_appearance, expected_variant = expected_binding(filename)
        if expected_variant == "deliberately-bad-control":
            maximum_jpeg_bytes = MAX_PINNED_CONTROL_JPEG_BYTES
            expected_dimensions = (
                PINNED_CONTROL_WIDTH_PIXELS,
                PINNED_CONTROL_HEIGHT_PIXELS,
            )
        else:
            maximum_jpeg_bytes = MAX_REFERENCE_JPEG_BYTES
            expected_dimensions = (CAPTURE_WIDTH_PIXELS, CAPTURE_HEIGHT_PIXELS)
        if not MIN_JPEG_BYTES <= len(data) <= maximum_jpeg_bytes:
            raise CaptureVerificationError(
                f"{directory.name}/{filename} size {len(data)} is outside "
                f"{MIN_JPEG_BYTES}..{maximum_jpeg_bytes}"
            )
        dimensions = jpeg_dimensions(data)
        if dimensions != expected_dimensions:
            raise CaptureVerificationError(
                f"{directory.name}/{filename} dimensions mismatch: "
                f"expected {expected_dimensions}, observed {dimensions}"
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
        if expected_variant == "deliberately-bad-control":
            expected_capture_width_points = PINNED_CONTROL_WIDTH_PIXELS
            expected_capture_height_points = PINNED_CONTROL_HEIGHT_PIXELS
        else:
            expected_capture_width_points = REFERENCE_CAPTURE_WIDTH_POINTS
            expected_capture_height_points = REFERENCE_CAPTURE_HEIGHT_POINTS
        geometry_contract = {
            "windowFrameWidthPoints": WINDOW_WIDTH_POINTS,
            "windowFrameHeightPoints": WINDOW_HEIGHT_POINTS,
            "captureBoundsXPoints": 0,
            "captureBoundsYPoints": 0,
            "captureBoundsWidthPoints": expected_capture_width_points,
            "captureBoundsHeightPoints": expected_capture_height_points,
        }
        for key, expected_value in geometry_contract.items():
            if record.get(key) != expected_value:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} {key} mismatch: "
                    f"expected {expected_value}, observed {record.get(key)!r}"
                )
        if record.get("fixtureState") != expected_state:
            raise CaptureVerificationError(f"{directory.name}/{filename} fixtureState mismatch")
        if record.get("appearance") != expected_appearance:
            raise CaptureVerificationError(f"{directory.name}/{filename} appearance mismatch")
        if record.get("variant") != expected_variant:
            raise CaptureVerificationError(f"{directory.name}/{filename} variant mismatch")
        if expected_variant == "deliberately-bad-control":
            if record.get("focusState") != PINNED_RESOURCE_FOCUS_STATE:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} pinned resource focusState mismatch"
                )
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
            if "layoutDiagnostics" in record:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} pinned control claims layout diagnostics"
                )
            if "settleAttempts" in record or "firstComparisonMatched" in record:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} pinned control claims a render settle path"
                )
        else:
            if record.get("focusState") != RENDERED_FOCUS_STATE:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} rendered focusState is not "
                    f"{RENDERED_FOCUS_STATE}"
                )
            if record.get("captureProvenance") != "rendered-current-run":
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} reference provenance declaration mismatch"
                )
            if "pinnedControlSha256" in record:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} reference capture claims a pinned control hash"
                )
            rendering_environment_contract = {
                "layoutDisplayScale": CAPTURE_SCALE,
                "hostLayerContentsScale": CAPTURE_SCALE,
                "bitmapPixelsPerPoint": CAPTURE_SCALE,
            }
            for key, expected_value in rendering_environment_contract.items():
                if record.get(key) != expected_value:
                    raise CaptureVerificationError(
                        f"{directory.name}/{filename} {key} mismatch: "
                        f"expected {expected_value}, observed {record.get(key)!r}"
                    )
            if record.get("windowBackingScaleFactor") != window_backing_scale_factor:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} windowBackingScaleFactor mismatch: "
                    f"expected manifest-observed {window_backing_scale_factor!r}, "
                    f"observed {record.get('windowBackingScaleFactor')!r}"
                )
            layout_diagnostics = record.get("layoutDiagnostics")
            if not isinstance(layout_diagnostics, dict):
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} layoutDiagnostics is missing or not an object"
                )
            if layout_diagnostics.get("collectionStage") != (
                "after-two-identical-frames-before-jpeg-encoding"
            ):
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} layoutDiagnostics collectionStage mismatch"
                )
            root_layout = layout_diagnostics.get("root")
            if not isinstance(root_layout, dict) or root_layout.get("coordinateSystem") != (
                "appkit-hosting-view-content-points"
            ):
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} root layout facts are missing or malformed"
                )
            expected_root_rects = {
                "windowFramePoints": {
                    "x": 0,
                    "y": 0,
                    "width": WINDOW_WIDTH_POINTS,
                    "height": WINDOW_HEIGHT_POINTS,
                },
                "windowContentLayoutRectPoints": {
                    "x": 0,
                    "y": 0,
                    "width": REFERENCE_CAPTURE_WIDTH_POINTS,
                    "height": REFERENCE_CAPTURE_HEIGHT_POINTS,
                },
                "captureViewFramePoints": {
                    "x": 0,
                    "y": 0,
                    "width": REFERENCE_CAPTURE_WIDTH_POINTS,
                    "height": REFERENCE_CAPTURE_HEIGHT_POINTS,
                },
                "captureViewBoundsPoints": {
                    "x": 0,
                    "y": 0,
                    "width": REFERENCE_CAPTURE_WIDTH_POINTS,
                    "height": REFERENCE_CAPTURE_HEIGHT_POINTS,
                },
                "hostingViewFrameInCapturePoints": {
                    "x": 0,
                    "y": 0,
                    "width": REFERENCE_CAPTURE_WIDTH_POINTS,
                    "height": REFERENCE_CAPTURE_HEIGHT_POINTS,
                },
                "hostingViewBoundsPoints": {
                    "x": 0,
                    "y": 0,
                    "width": REFERENCE_CAPTURE_WIDTH_POINTS,
                    "height": REFERENCE_CAPTURE_HEIGHT_POINTS,
                },
            }
            for key, expected_rect in expected_root_rects.items():
                if root_layout.get(key) != expected_rect:
                    raise CaptureVerificationError(
                        f"{directory.name}/{filename} root {key} mismatch: "
                        f"expected {expected_rect!r}, observed {root_layout.get(key)!r}"
                    )
            if root_layout.get("captureViewIsFlipped") is not True:
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} captureViewIsFlipped is not true"
                )
            native_controls = layout_diagnostics.get("nativeControls")
            if not isinstance(native_controls, list):
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} native control layout facts are not a list"
                )
            hierarchy_paths = [control.get("hierarchyPath") for control in native_controls]
            if (
                any(not isinstance(path, str) or not path for path in hierarchy_paths)
                or len(hierarchy_paths) != len(set(hierarchy_paths))
            ):
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} native control hierarchy paths are invalid"
                )
            semantic_keys = [control.get("semanticKey") for control in native_controls]
            if any(not isinstance(key, str) or not key for key in semantic_keys):
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} native control semantic keys are invalid"
                )
            line_heights_by_font: dict[tuple[str, float], float] = {}
            for control in native_controls:
                font_metrics = control.get("fontMetrics")
                if font_metrics is None:
                    continue
                if not isinstance(font_metrics, dict):
                    raise CaptureVerificationError(
                        f"{directory.name}/{filename} native control font metrics are malformed"
                    )
                font_name = font_metrics.get("fontName")
                point_size = font_metrics.get("pointSize")
                line_height = font_metrics.get("textKitDefaultLineHeightPoints")
                if (
                    not isinstance(font_name, str)
                    or not font_name
                    or isinstance(point_size, bool)
                    or not isinstance(point_size, (int, float))
                    or not math.isfinite(point_size)
                    or point_size <= 0
                    or isinstance(line_height, bool)
                    or not isinstance(line_height, (int, float))
                    or not math.isfinite(line_height)
                    or line_height <= 0
                ):
                    raise CaptureVerificationError(
                        f"{directory.name}/{filename} native control resolved line height "
                        f"is missing or malformed: observed {font_metrics!r}"
                    )
                font_key = (font_name, float(point_size))
                previous_line_height = line_heights_by_font.setdefault(
                    font_key,
                    float(line_height),
                )
                if previous_line_height != float(line_height):
                    raise CaptureVerificationError(
                        f"{directory.name}/{filename} distinct font {font_key!r} resolved "
                        f"inconsistent line heights: {previous_line_height} and {line_height}"
                    )
            settle_attempts = record.get("settleAttempts")
            if (
                isinstance(settle_attempts, bool)
                or not isinstance(settle_attempts, int)
                or not 1 <= settle_attempts <= 40
            ):
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} settleAttempts is outside 1...40: "
                    f"observed {settle_attempts!r}"
                )
            first_comparison_matched = record.get("firstComparisonMatched")
            if (
                not isinstance(first_comparison_matched, bool)
                or first_comparison_matched != (settle_attempts == 1)
            ):
                raise CaptureVerificationError(
                    f"{directory.name}/{filename} firstComparisonMatched contradicts "
                    f"settleAttempts={settle_attempts}: "
                    f"observed {first_comparison_matched!r}"
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
    assert_states_match_swift_enum()
    try:
        verify_capture_root(args.root)
    except CaptureVerificationError as error:
        raise SystemExit(f"DESIGN CAPTURE FAIL {error}") from error
    # Counted, never spelled out. This line was a hardcoded "standard=38 jpeg=38" while the tool
    # verified a different number of files, so the evidence line asserted something the run had not
    # measured — the failure mode a stale comment causes, applied to the artifact's own summary.
    current_run_references = len(STATES) * len(APPEARANCES)
    pinned_controls = len(expected_standard_files()) - current_run_references
    verified_files = current_run_references + pinned_controls
    print(
        f"DESIGN CAPTURE PASS standard={verified_files} jpeg={verified_files} "
        f"current-run-references={current_run_references} pinned-controls={pinned_controls} "
        f"reference-pixels={CAPTURE_WIDTH_PIXELS}x{CAPTURE_HEIGHT_PIXELS} "
        f"pinned-control-pixels={PINNED_CONTROL_WIDTH_PIXELS}x{PINNED_CONTROL_HEIGHT_PIXELS} "
        f"frame-points={WINDOW_WIDTH_POINTS}x{WINDOW_HEIGHT_POINTS} "
        "reference-capture-surface=ns-hosting-view-content-only "
        f"capture-bounds-points=0,0,{REFERENCE_CAPTURE_WIDTH_POINTS}x"
        f"{REFERENCE_CAPTURE_HEIGHT_POINTS} "
        "control-active-state=key "
        "focus-state=no-focused-control focus-assertion=every-bitmap-draw "
        "decoded-pixels-pairwise-distinct=true "
        "filename-manifest-embedded-labels-consistent=true dynamic-type-claim=absent "
        "pinned-control-bytes-match-reviewed-sha256=true "
        "capture-provenance-declarations-consistent=true"
    )


if __name__ == "__main__":
    main()
