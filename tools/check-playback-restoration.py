#!/usr/bin/env python3
"""Run real Apple controller restoration regressions with a built DulcetCore framework."""
import argparse
import shutil
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
# SwiftPM's native engine places the module in Modules and the target's objects in
# DulcetKit.build beside it; the default swiftbuild engine places the module in Products
# and the objects in Intermediates. Fall back to the static archive when neither holds objects.
modules = build / "Modules" if (build / "Modules").is_dir() else build
objects = sorted((build / "DulcetKit.build").glob("*.o"))
if not objects:
    objects = sorted((build.parents[1] / "Intermediates.noindex").glob(
        "DulcetKit.build/*/DulcetKit-t.build/Objects-normal/arm64/*.o"
    ))
if not objects and (build / "libDulcetKit.a").is_file():
    objects = [build / "libDulcetKit.a"]
if not (modules / "DulcetKit.swiftmodule").exists() or not objects:
    raise SystemExit("restoration check could not locate the built DulcetKit module and objects")
with tempfile.TemporaryDirectory(prefix="restoration-check-", dir=package / ".build") as temp:
    temp = Path(temp)
    resources = build / "DulcetKit_DulcetKit.bundle"
    if resources.is_dir():
        # swiftbuild's accessor locates resources beside a standalone executable.
        (temp / resources.name).symlink_to(resources)
    subprocess.run(["clang", "-mmacosx-version-min=14.0", "-c",
                    str(root / "apple/DulcetAppleShared/SQLiteLoadExtensionUnsupported.c"),
                    "-o", str(temp / "sqlite.o")], check=True)
    subprocess.run([
        "swiftc", "-parse-as-library", "-target", "arm64-apple-macos14.0",
        "-I", str(modules), "-F", str(args.framework_dir.resolve()),
        "-framework", "DulcetCore", "-lsqlite3",
        str(root / "apple/DulcetAppleShared/DulcetCorePlayback.swift"),
        str(root / "apple/DulcetAppleShared/DulcetCorePlaybackController.swift"),
        str(root / "tools/playback-restoration-check.swift"), str(temp / "sqlite.o"),
        *map(str, objects),
        "-o", str(temp / "check"),
    ], check=True)
    # An Xcode-backed SwiftPM resource accessor locates its bundle beside the executable.
    for resource in build.glob("DulcetKit_DulcetKit.*"):
        if resource.is_dir():
            shutil.copytree(resource, temp / resource.name)
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
