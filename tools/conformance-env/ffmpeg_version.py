#!/usr/bin/env python3

"""Parse and exactly verify the version token in an ffmpeg banner."""


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
