#!/usr/bin/env python3

"""Retain and measure exact-byte differences between two design-capture trees."""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from typing import Any


IMAGE_SUFFIXES = {".bmp", ".jpeg", ".jpg", ".png", ".tif", ".tiff"}


class ForensicsError(RuntimeError):
    pass


@dataclass(frozen=True)
class DecodedImage:
    width: int
    height: int
    rgb: bytes


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_bmp(path: Path) -> DecodedImage:
    data = path.read_bytes()
    if len(data) < 54 or data[:2] != b"BM":
        raise ForensicsError(f"decoded bitmap is malformed: {path.name}")

    pixel_offset = int.from_bytes(data[10:14], "little")
    dib_size = int.from_bytes(data[14:18], "little")
    width = int.from_bytes(data[18:22], "little", signed=True)
    stored_height = int.from_bytes(data[22:26], "little", signed=True)
    planes = int.from_bytes(data[26:28], "little")
    bits_per_pixel = int.from_bytes(data[28:30], "little")
    compression = int.from_bytes(data[30:34], "little")
    if (
        dib_size < 40
        or width <= 0
        or stored_height == 0
        or planes != 1
        or bits_per_pixel != 24
        or compression != 0
    ):
        raise ForensicsError(f"decoded bitmap format is unsupported: {path.name}")

    height = abs(stored_height)
    row_bytes = width * 3
    stride = (row_bytes + 3) & ~3
    pixel_end = pixel_offset + stride * height
    if pixel_offset < 14 + dib_size or pixel_end > len(data):
        raise ForensicsError(f"decoded bitmap is truncated: {path.name}")

    rgb = bytearray(width * height * 3)
    for y in range(height):
        stored_y = height - 1 - y if stored_height > 0 else y
        source_offset = pixel_offset + stored_y * stride
        target_offset = y * row_bytes
        for x in range(width):
            blue, green, red = data[source_offset + x * 3:source_offset + x * 3 + 3]
            rgb[target_offset + x * 3:target_offset + x * 3 + 3] = (
                red,
                green,
                blue,
            )
    return DecodedImage(width=width, height=height, rgb=bytes(rgb))


def decode_image(path: Path) -> DecodedImage:
    if path.suffix.lower() == ".bmp":
        return decode_bmp(path)

    sips = Path("/usr/bin/sips")
    if not sips.is_file():
        raise ForensicsError(
            f"cannot decode {path.suffix.lower()} image because /usr/bin/sips is unavailable"
        )
    with tempfile.TemporaryDirectory(prefix="dulcet-capture-forensics-") as temporary:
        bitmap_path = Path(temporary) / "decoded.bmp"
        result = subprocess.run(
            [str(sips), "-s", "format", "bmp", str(path), "--out", str(bitmap_path)],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        if result.returncode != 0 or not bitmap_path.is_file():
            diagnostic = result.stderr.strip() or f"sips exited {result.returncode}"
            raise ForensicsError(f"could not decode {path.name}: {diagnostic}")
        return decode_bmp(bitmap_path)


def histogram(counter: Counter[int]) -> list[dict[str, int]]:
    return [
        {"delta": delta, "pixels": counter[delta]}
        for delta in sorted(counter)
    ]


def measure_delta(run_a: DecodedImage, run_b: DecodedImage) -> dict[str, Any]:
    if (run_a.width, run_a.height) != (run_b.width, run_b.height):
        raise ForensicsError(
            "decoded dimensions differ: "
            f"run-a={run_a.width}x{run_a.height} "
            f"run-b={run_b.width}x{run_b.height}"
        )

    channel_names = ("red", "green", "blue")
    signed = {channel: Counter() for channel in channel_names}
    absolute = {channel: Counter() for channel in channel_names}
    differing_pixels = 0
    minimum_x = run_a.width
    minimum_y = run_a.height
    maximum_x = -1
    maximum_y = -1
    pixel_count = run_a.width * run_a.height

    for pixel_index in range(pixel_count):
        offset = pixel_index * 3
        deltas = tuple(
            run_b.rgb[offset + channel] - run_a.rgb[offset + channel]
            for channel in range(3)
        )
        if deltas == (0, 0, 0):
            continue

        differing_pixels += 1
        x = pixel_index % run_a.width
        y = pixel_index // run_a.width
        minimum_x = min(minimum_x, x)
        minimum_y = min(minimum_y, y)
        maximum_x = max(maximum_x, x)
        maximum_y = max(maximum_y, y)
        for channel, delta in zip(channel_names, deltas):
            signed[channel][delta] += 1
            absolute[channel][abs(delta)] += 1

    bounding_box = None
    if differing_pixels:
        bounding_box = {
            "minX": minimum_x,
            "minY": minimum_y,
            "maxX": maximum_x,
            "maxY": maximum_y,
            "width": maximum_x - minimum_x + 1,
            "height": maximum_y - minimum_y + 1,
        }

    return {
        "widthPixels": run_a.width,
        "heightPixels": run_a.height,
        "totalPixels": pixel_count,
        "differingPixels": differing_pixels,
        "differingPixelPercentage": round(differing_pixels * 100 / pixel_count, 10),
        "boundingBoxInclusive": bounding_box,
        "signedChannelDeltaDistribution": {
            channel: histogram(signed[channel]) for channel in channel_names
        },
        "absoluteChannelDeltaDistribution": {
            channel: histogram(absolute[channel]) for channel in channel_names
        },
    }


def file_map(root: Path) -> dict[Path, Path]:
    if not root.is_dir():
        raise ForensicsError(f"capture root is not a directory: {root}")
    return {
        path.relative_to(root): path
        for path in root.rglob("*")
        if path.is_file()
    }


def retain(path: Path, relative: Path, output: Path, run: str) -> str:
    destination = output / "retained" / run / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, destination)
    return destination.relative_to(output).as_posix()


def format_distribution(distribution: dict[str, list[dict[str, int]]]) -> str:
    labels = {"red": "R", "green": "G", "blue": "B"}
    channels = []
    for channel in ("red", "green", "blue"):
        entries = ",".join(
            f'{entry["delta"]}:{entry["pixels"]}'
            for entry in distribution[channel]
        )
        channels.append(f"{labels[channel]}[{entries}]")
    return " ".join(channels)


def format_bounding_box(box: dict[str, int] | None) -> str:
    if box is None:
        return "none"
    return (
        f'x={box["minX"]}..{box["maxX"]},'
        f'y={box["minY"]}..{box["maxY"]},'
        f'width={box["width"]},height={box["height"]}'
    )


def build_report(run_a_root: Path, run_b_root: Path, output: Path) -> dict[str, Any]:
    run_a_files = file_map(run_a_root)
    run_b_files = file_map(run_b_root)
    run_a_names = set(run_a_files)
    run_b_names = set(run_b_files)
    missing_from_run_a = sorted(run_b_names - run_a_names)
    missing_from_run_b = sorted(run_a_names - run_b_names)
    common = run_a_names & run_b_names
    byte_different = sorted(
        relative
        for relative in common
        if sha256(run_a_files[relative]) != sha256(run_b_files[relative])
    )

    differing_files: list[dict[str, Any]] = []
    differing_images: list[dict[str, Any]] = []
    measurement_errors = 0

    for relative in missing_from_run_a:
        path_b = run_b_files[relative]
        differing_files.append({
            "path": relative.as_posix(),
            "difference": "missing-from-run-a",
            "runB": {
                "sha256": sha256(path_b),
                "retainedPath": retain(path_b, relative, output, "run-b"),
            },
        })
        if relative.suffix.lower() in IMAGE_SUFFIXES:
            differing_images.append({
                "path": relative.as_posix(),
                "measurementError": "image is missing from run-a",
            })
            measurement_errors += 1

    for relative in missing_from_run_b:
        path_a = run_a_files[relative]
        differing_files.append({
            "path": relative.as_posix(),
            "difference": "missing-from-run-b",
            "runA": {
                "sha256": sha256(path_a),
                "retainedPath": retain(path_a, relative, output, "run-a"),
            },
        })
        if relative.suffix.lower() in IMAGE_SUFFIXES:
            differing_images.append({
                "path": relative.as_posix(),
                "measurementError": "image is missing from run-b",
            })
            measurement_errors += 1

    for relative in byte_different:
        path_a = run_a_files[relative]
        path_b = run_b_files[relative]
        entry: dict[str, Any] = {
            "path": relative.as_posix(),
            "difference": "bytes-differ",
            "runA": {
                "sha256": sha256(path_a),
                "bytes": path_a.stat().st_size,
                "retainedPath": retain(path_a, relative, output, "run-a"),
            },
            "runB": {
                "sha256": sha256(path_b),
                "bytes": path_b.stat().st_size,
                "retainedPath": retain(path_b, relative, output, "run-b"),
            },
        }
        differing_files.append(entry)
        if relative.suffix.lower() not in IMAGE_SUFFIXES:
            continue

        image_entry: dict[str, Any] = {
            "path": relative.as_posix(),
            "runA": entry["runA"],
            "runB": entry["runB"],
        }
        try:
            image_entry["decodedPixelDelta"] = measure_delta(
                decode_image(path_a),
                decode_image(path_b),
            )
        except ForensicsError as error:
            image_entry["measurementError"] = str(error)
            measurement_errors += 1
        differing_images.append(image_entry)

    differing_files.sort(key=lambda item: item["path"])
    differing_images.sort(key=lambda item: item["path"])
    return {
        "schemaVersion": 1,
        "comparisonPolicy": "exact-recursive-file-bytes",
        "gateDisposition": "fail-on-any-file-difference",
        "pixelCoordinateOrigin": "top-left-zero-based",
        "channelDeltaDefinition": "run-b-minus-run-a-on-differing-decoded-pixels",
        "missingFromRunA": [path.as_posix() for path in missing_from_run_a],
        "missingFromRunB": [path.as_posix() for path in missing_from_run_b],
        "differingFileCount": len(differing_files),
        "differingImageCount": len(differing_images),
        "measurementErrorCount": measurement_errors,
        "differingFiles": differing_files,
        "differingImages": differing_images,
    }


def summary_lines(report: dict[str, Any]) -> list[str]:
    # Report the gate from the measurement rather than hardcoding FAIL. This tool is
    # normally reached only after a failed recursive diff, which is why the verdict used
    # to be a literal — but it is also run directly to confirm a fix, and a line reading
    # "exact-byte-gate=FAIL differing-files=0 differing-images=0" contradicts itself.
    # A verdict that cannot say PASS carries no information, and it costs a reader real
    # time at exactly the moment they are debugging a capture mismatch under pressure.
    gate = (
        "FAIL"
        if report["differingFileCount"] or report["measurementErrorCount"]
        else "PASS"
    )
    lines = [
        f"DESIGN CAPTURE FORENSICS exact-byte-gate={gate} "
        f'differing-files={report["differingFileCount"]} '
        f'differing-images={report["differingImageCount"]} '
        f'measurement-errors={report["measurementErrorCount"]}'
    ]
    for image in report["differingImages"]:
        if "measurementError" in image:
            lines.append(
                f'DESIGN CAPTURE DELTA file={image["path"]} '
                f'measurement-error={image["measurementError"]}'
            )
            continue
        delta = image["decodedPixelDelta"]
        lines.append(
            f'DESIGN CAPTURE DELTA file={image["path"]} '
            f'differing-pixels={delta["differingPixels"]}/{delta["totalPixels"]} '
            f'percentage={delta["differingPixelPercentage"]:.10f}% '
            f'bbox={format_bounding_box(delta["boundingBoxInclusive"])}'
        )
        lines.append(
            "DESIGN CAPTURE SIGNED CHANNEL DELTAS "
            f'file={image["path"]} '
            f'{format_distribution(delta["signedChannelDeltaDistribution"])}'
        )
        lines.append(
            "DESIGN CAPTURE ABSOLUTE CHANNEL DELTAS "
            f'file={image["path"]} '
            f'{format_distribution(delta["absoluteChannelDeltaDistribution"])}'
        )
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_a", type=Path)
    parser.add_argument("run_b", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    try:
        args.output.mkdir(parents=True, exist_ok=False)
        report = build_report(args.run_a, args.run_b, args.output)
        report_path = args.output / "report.json"
        report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        lines = summary_lines(report)
        (args.output / "summary.txt").write_text("\n".join(lines) + "\n")
        print("\n".join(lines))
    except (ForensicsError, OSError) as error:
        print(f"DESIGN CAPTURE FORENSICS ERROR {error}")
        return 2

    if report["measurementErrorCount"]:
        return 2
    return 1 if report["differingFileCount"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
