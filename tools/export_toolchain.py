#!/usr/bin/env python3
from pathlib import Path
import tomllib

versions = tomllib.loads(Path("gradle/libs.versions.toml").read_text())["versions"]
for output, key in (
    ("jdk", "jdk"),
    ("xcode", "xcode"),
    ("compile_sdk", "android-compile-sdk"),
):
    print(f"{output}={versions[key]}")
