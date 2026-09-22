#!/usr/bin/env python3
"""Run real Apple controller restoration regressions with a built DulcetCore framework."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import uuid

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
    prefix = f"dulcet-restoration-check-{uuid.uuid4()}"
    assert prefix.startswith("dulcet-restoration-check-"), prefix
    # NativeSqliteDriver's macOS database directory. Everything this run creates begins with the
    # run's own fresh UUID prefix, so deriving the cleanup from that prefix cannot reach the app's
    # dulcet.db and cannot stop matching when the Swift renames its databases.
    #
    # It did stop matching. This loop used to delete a hardcoded `{prefix}-{count}.db` for counts
    # (0, 2, 3); when the control gained a coverage dimension the Swift began writing
    # `{prefix}-{count}-{coverage}.db` and every file leaked. MEASURED 2026-09-11 before this
    # change: 264 files across 54 run prefixes, and ZERO in the old naming -- which is the
    # positive control that the cleanup worked until the rename and has deleted nothing since.
    database_dir = Path.home() / "Library/Application Support/databases"
    failure = None
    try:
        subprocess.run([str(temp / "check"), prefix], check=True)
    except subprocess.CalledProcessError as error:
        failure = error
    finally:
        for path in sorted(database_dir.glob(f"{prefix}*")):
            path.unlink(missing_ok=True)
    if failure is not None:
        raise failure
    # A cleanup that silently stops matching is what produced those 264 files, so the run now
    # proves it removed its own databases rather than assuming the pattern still fits.
    leaked = sorted(path.name for path in database_dir.glob(f"{prefix}*"))
    if leaked:
        raise SystemExit(
            "restoration check left its synthetic databases behind, so its cleanup no longer "
            f"matches what the Swift writes: {leaked}"
        )
