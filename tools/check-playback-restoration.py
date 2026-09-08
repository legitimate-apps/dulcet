#!/usr/bin/env python3
"""Run real Apple controller restoration regressions with a built DulcetCore framework."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--framework-dir", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
package = root / "apple/DulcetKit"
subprocess.run(["swift", "build", "--package-path", str(package)], check=True)
build = Path(subprocess.check_output(
    ["swift", "build", "--package-path", str(package), "--show-bin-path"], text=True
).strip())
with tempfile.TemporaryDirectory(prefix="restoration-check-", dir=package / ".build") as temp:
    temp = Path(temp)
    subprocess.run(["clang", "-mmacosx-version-min=14.0", "-c",
                    str(root / "apple/DulcetAppleShared/SQLiteLoadExtensionUnsupported.c"),
                    "-o", str(temp / "sqlite.o")], check=True)
    subprocess.run([
        "swiftc", "-parse-as-library", "-target", "arm64-apple-macos14.0",
        "-I", str(build / "Modules"), "-F", str(args.framework_dir.resolve()),
        "-framework", "DulcetCore", "-lsqlite3",
        str(root / "apple/DulcetAppleShared/DulcetCorePlayback.swift"),
        str(root / "apple/DulcetAppleShared/DulcetCorePlaybackController.swift"),
        str(root / "tools/playback-restoration-check.swift"), str(temp / "sqlite.o"),
        *map(str, (build / "DulcetKit.build").glob("*.o")),
        "-o", str(temp / "check"),
    ], check=True)
    subprocess.run([str(temp / "check")], check=True)
