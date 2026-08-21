#!/usr/bin/env python3

"""Parse and exactly verify the version token in an ffmpeg banner."""

import argparse


def parse_ffmpeg_version(line: str) -> str:
    fields = line.split()
    if len(fields) < 3 or fields[:2] != ["ffmpeg", "version"]:
        raise ValueError(f"unrecognized ffmpeg version line: {line!r}")
    return fields[2]


def require_ffmpeg_version(line: str, expected: str) -> str:
    observed = parse_ffmpeg_version(line)
    if observed != expected:
        raise ValueError(f"ffmpeg version mismatch: expected {expected}, observed {observed}")
    return observed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected", required=True)
    parser.add_argument("--banner", required=True)
    args = parser.parse_args()
    observed = require_ffmpeg_version(args.banner, args.expected)
    print(f"ffmpeg version gate PASS observed={observed}")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        raise SystemExit(str(error)) from error
