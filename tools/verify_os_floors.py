#!/usr/bin/env python3
import argparse
from pathlib import Path
import re
import subprocess
import tomllib

parser = argparse.ArgumentParser()
parser.add_argument("--configuration-only", action="store_true")
parser.add_argument("--macos-framework")
parser.add_argument("--ios-framework")
parser.add_argument("--tvos-framework")
parser.add_argument("--macos-app")
parser.add_argument("--ios-app")
parser.add_argument("--tvos-app")
args = parser.parse_args()

versions = tomllib.loads(Path("gradle/libs.versions.toml").read_text())["versions"]
expected = {
    "macos": versions["macos-deployment"],
    "ios": versions["ios-deployment"],
    "tvos": versions["tvos-deployment"],
}

gradle_text = Path("core/build.gradle.kts").read_text()
for platform, floor in expected.items():
    needle = f"minVersion.{platform}=${{libs.versions.{platform}.deployment.get()}}"
    if needle not in gradle_text:
        raise SystemExit(f"missing Kotlin/Native deployment override: {needle}")

project_text = Path("apple/Dulcet.xcodeproj/project.pbxproj").read_text()
xcode_settings = {
    "macos": "MACOSX_DEPLOYMENT_TARGET",
    "ios": "IPHONEOS_DEPLOYMENT_TARGET",
    "tvos": "TVOS_DEPLOYMENT_TARGET",
}
for platform, setting in xcode_settings.items():
    observed = set(re.findall(rf"{setting} = ([0-9.]+);", project_text))
    if observed != {expected[platform]}:
        raise SystemExit(f"{setting}: expected only {expected[platform]}, observed {sorted(observed)}")

if args.configuration_only:
    print("OS-floor configuration agrees: Kotlin flags and Xcode targets are macOS 14.0, iOS/tvOS 17.0")
    raise SystemExit(0)

frameworks = {
    "macos": args.macos_framework,
    "ios": args.ios_framework,
    "tvos": args.tvos_framework,
}
for platform, framework in frameworks.items():
    if not framework:
        raise SystemExit(f"missing --{platform}-framework")
    output = subprocess.run(
        ["xcrun", "otool", "-l", framework],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout
    minos = set(re.findall(r"\bminos\s+([0-9.]+)", output))
    if not minos or max(tuple(map(int, value.split("."))) for value in minos) != tuple(
        map(int, expected[platform].split("."))
    ):
        raise SystemExit(
            f"{platform} static framework member floors: expected maximum {expected[platform]}, "
            f"observed {sorted(minos)}"
        )

apps = {
    "macos": args.macos_app,
    "ios": args.ios_app,
    "tvos": args.tvos_app,
}
for platform, app in apps.items():
    if not app:
        continue
    output = subprocess.run(
        ["xcrun", "otool", "-l", app],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout
    minos = set(re.findall(r"\bminos\s+([0-9.]+)", output))
    if minos != {expected[platform]}:
        raise SystemExit(f"{platform} app minos: expected {expected[platform]}, observed {sorted(minos)}")

print("OS-floor binaries agree: Kotlin archive members and linked apps enforce macOS 14.0, iOS/tvOS 17.0")
