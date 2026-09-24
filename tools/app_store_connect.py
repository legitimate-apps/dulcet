#!/usr/bin/env python3
"""The App Store Connect half of release.yml: build numbering, upload, and proof of arrival.

Credentials come only from the environment (DULCET_ASC_KEY_ID, DULCET_ASC_ISSUER_ID,
DULCET_ASC_KEY_P8_BASE64). The private key never reaches argv, a log or the workspace: it is
written only into a private temporary directory for the one child process that needs it, and that
directory is removed before this program returns. It has no third-party dependencies (the JWT is
signed by the openssl binary) so it runs on a stock hosted runner.

Subcommands
  next-build  --bundle-id B --family F [--require-record]
      Print app_record, app_id, latest_build and build_number as step outputs. The next build is
      one above the highest build App Store Connect holds across the family's records (F and
      F.dev), so numbering is monotonic across both channels and every platform, whether a build
      was cut by hand or by release.yml.
  validate    --package P          altool --validate-app (server-side checks, uploads nothing)
  upload      --package P          altool --upload-package
  await-build --app-id A --build-number N [--timeout S]
      Poll until App Store Connect reports the build VALID. An upload that exits 0 is not arrival.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


API_ROOT = "https://api.appstoreconnect.apple.com"
# App Store Connect accepts tokens of at most 20 minutes; await-build alone can run 30. So the
# client never holds one token for a run: it re-mints within REFRESH_MARGIN of expiry, and once
# more on a 401, which is what an expired token returns.
TOKEN_LIFETIME = 900
REFRESH_MARGIN = 60


class AscError(Exception):
    pass


# ---------------------------------------------------------------------------------------------
# Pure logic (exercised directly by tools/test-release-channel)
# ---------------------------------------------------------------------------------------------

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def der_ecdsa_to_raw(der: bytes, size: int = 32) -> bytes:
    """Convert an ASN.1 DER ECDSA signature (what openssl emits) to JOSE's fixed r||s form."""
    def read_length(data: bytes, index: int) -> tuple[int, int]:
        first = data[index]
        if first < 0x80:
            return first, index + 1
        count = first & 0x7F
        return int.from_bytes(data[index + 1:index + 1 + count], "big"), index + 1 + count

    if not der or der[0] != 0x30:
        raise AscError("signature is not a DER SEQUENCE")
    length, index = read_length(der, 1)
    if index + length != len(der):
        raise AscError("signature DER length mismatch")
    parts = []
    for _ in range(2):
        if der[index] != 0x02:
            raise AscError("signature DER component is not an INTEGER")
        part_length, index = read_length(der, index + 1)
        value = der[index:index + part_length].lstrip(b"\x00")
        if len(value) > size:
            raise AscError("signature component is too large")
        parts.append(value.rjust(size, b"\x00"))
        index += part_length
    if index != len(der):
        raise AscError("trailing bytes after signature")
    return parts[0] + parts[1]


def build_versions(builds: list[dict]) -> list[int]:
    versions = []
    for build in builds:
        version = build.get("attributes", {}).get("version", "")
        # Every Dulcet build number is a plain integer. Anything else means a build this tool
        # did not number; refuse rather than guess an ordering for it.
        if not re.fullmatch(r"\d+", version or ""):
            raise AscError(f"App Store Connect holds a non-integer build number {version!r}")
        versions.append(int(version))
    return versions


def next_build_number(versions: list[int]) -> int:
    return max(versions, default=0) + 1


def family_identifiers(family: str) -> list[str]:
    return [family, f"{family}.dev"]


def matching_app(apps: list[dict], bundle_id: str) -> dict | None:
    # filter[bundleId] is a prefix-tolerant filter in practice; require the exact identifier so
    # a DEV lookup can never resolve to the PROD record or the reverse.
    exact = [app for app in apps if app.get("attributes", {}).get("bundleId") == bundle_id]
    if len(exact) > 1:
        raise AscError(f"more than one App Store Connect record claims {bundle_id}")
    return exact[0] if exact else None


def redact(text: str, values: list[str]) -> str:
    for value in values:
        if value:
            text = text.replace(value, "[REDACTED]")
    return text


# ---------------------------------------------------------------------------------------------
# Credentials and transport
# ---------------------------------------------------------------------------------------------

class Credentials:
    def __init__(self) -> None:
        try:
            self.key_id = os.environ["DULCET_ASC_KEY_ID"].strip()
            self.issuer_id = os.environ["DULCET_ASC_ISSUER_ID"].strip()
            encoded = os.environ["DULCET_ASC_KEY_P8_BASE64"].strip()
        except KeyError as error:
            raise AscError(f"missing credential environment variable {error.args[0]}") from None
        if not re.fullmatch(r"[A-Z0-9]{10}", self.key_id):
            raise AscError("DULCET_ASC_KEY_ID is not a 10-character key id")
        self.private_key = base64.b64decode(encoded, validate=True)
        if b"PRIVATE KEY" not in self.private_key:
            raise AscError("DULCET_ASC_KEY_P8_BASE64 does not decode to a PEM private key")
        self.encoded = encoded

    def secrets(self) -> list[str]:
        values = [self.encoded, self.private_key.decode("ascii", "replace")]
        values += self.private_key.decode("ascii", "replace").splitlines()
        return [value for value in values if len(value) >= 8]


class PrivateKeyDirectory:
    """A 0700 directory holding AuthKey_<id>.p8 (0600) for exactly one child process."""

    def __init__(self, credentials: Credentials) -> None:
        self.credentials = credentials

    def __enter__(self) -> Path:
        os.umask(0o077)
        root = os.environ.get("RUNNER_TEMP") or None
        self.directory = tempfile.TemporaryDirectory(prefix="dulcet-asc-", dir=root)
        path = Path(self.directory.name)
        os.chmod(path, 0o700)
        self.key_path = path / f"AuthKey_{self.credentials.key_id}.p8"
        descriptor = os.open(self.key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(self.credentials.private_key)
        return path

    def __exit__(self, *exc: object) -> None:
        self.directory.cleanup()
        if self.key_path.exists():
            raise AscError("the temporary private key survived cleanup")


def make_jwt(credentials: Credentials, now: int | None = None) -> str:
    now = int(time.time()) if now is None else now
    header = {"alg": "ES256", "kid": credentials.key_id, "typ": "JWT"}
    payload = {"iss": credentials.issuer_id, "iat": now - 5, "exp": now + TOKEN_LIFETIME,
               "aud": "appstoreconnect-v1"}
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(payload).encode())}"
    with PrivateKeyDirectory(credentials) as directory:
        result = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(directory / f"AuthKey_{credentials.key_id}.p8")],
            input=signing_input.encode(), capture_output=True, check=False,
        )
    if result.returncode != 0:
        raise AscError("openssl could not sign the App Store Connect token")
    return f"{signing_input}.{b64url(der_ecdsa_to_raw(result.stdout))}"


class Client:
    def __init__(self, credentials: Credentials, clock=time.time) -> None:
        self.credentials = credentials
        self.clock = clock
        self.token: str | None = None
        self.expires = 0
        self.minted = 0

    def bearer(self, force: bool = False) -> str:
        now = int(self.clock())
        if force or self.token is None or now >= self.expires - REFRESH_MARGIN:
            self.token = make_jwt(self.credentials, now)
            self.expires = now + TOKEN_LIFETIME
            self.minted += 1
        return self.token

    def get(self, path_or_url: str) -> dict:
        url = path_or_url if path_or_url.startswith("https://") else API_ROOT + path_or_url
        refreshed = False
        attempt = 0
        while True:
            request = urllib.request.Request(url, headers={"Authorization": f"Bearer {self.bearer()}"})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                body = redact(error.read()[:500].decode("utf-8", "replace"), [self.token or ""])
                if error.code == 401 and not refreshed:
                    refreshed = True
                    self.bearer(force=True)
                    continue
                attempt += 1
                if error.code >= 500 and attempt < 3:
                    time.sleep(5 * (attempt + 1))
                    continue
                raise AscError(f"App Store Connect GET {urllib.parse.urlsplit(url).path} "
                               f"returned HTTP {error.code}: {body}") from None
            except urllib.error.URLError as error:
                attempt += 1
                if attempt < 3:
                    time.sleep(5 * attempt)
                    continue
                raise AscError(f"App Store Connect unreachable: {error.reason}") from None

    def all_pages(self, path: str, params: dict[str, str | int]) -> list[dict]:
        url: str | None = f"{API_ROOT}{path}?{urllib.parse.urlencode(params)}"
        rows: list[dict] = []
        while url:
            document = self.get(url)
            rows.extend(document.get("data", []))
            url = document.get("links", {}).get("next")
        return rows

    def app_for(self, bundle_id: str) -> dict | None:
        apps = self.all_pages("/v1/apps", {"filter[bundleId]": bundle_id,
                                           "fields[apps]": "bundleId,name,sku", "limit": 200})
        return matching_app(apps, bundle_id)

    def builds(self, app_id: str, version: str | None = None) -> list[dict]:
        params: dict[str, str | int] = {
            "filter[app]": app_id, "limit": 200,
            "fields[builds]": "version,processingState,uploadedDate,expired",
        }
        if version is not None:
            params["filter[version]"] = version
        return self.all_pages("/v1/builds", params)


# ---------------------------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------------------------

def command_next_build(args: argparse.Namespace) -> None:
    family = family_identifiers(args.family)
    if args.bundle_id not in family:
        raise AscError(f"{args.bundle_id} is not one of the family identifiers {family}")
    client = Client(Credentials())
    versions: list[int] = []
    target = None
    for identifier in family:
        app = client.app_for(identifier)
        if app is None:
            continue
        versions += build_versions(client.builds(app["id"]))
        if identifier == args.bundle_id:
            target = app
    if target is None and args.require_record:
        raise AscError(
            f"no App Store Connect app record exists for {args.bundle_id}. Records cannot be "
            "created through the API; create it in the App Store Connect web UI, then re-run"
        )
    print(f"app_record={'present' if target else 'absent'}")
    print(f"app_id={target['id'] if target else ''}")
    print(f"latest_build={max(versions) if versions else ''}")
    print(f"build_number={next_build_number(versions)}")


def run_altool(credentials: Credentials, arguments: list[str]) -> int:
    with PrivateKeyDirectory(credentials) as directory:
        environment = dict(os.environ, API_PRIVATE_KEYS_DIR=str(directory))
        for name in ("DULCET_ASC_KEY_P8_BASE64",):
            environment.pop(name, None)
        result = subprocess.run(
            ["xcrun", "altool", *arguments,
             "--api-key", credentials.key_id, "--api-issuer", credentials.issuer_id],
            env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False,
        )
    print(redact(result.stdout.decode("utf-8", "replace"), credentials.secrets()))
    return result.returncode


def command_altool(args: argparse.Namespace, verb: str) -> None:
    package = Path(args.package)
    if not package.is_file() or package.suffix not in (".pkg", ".ipa"):
        raise AscError(f"{package} is not an exported .pkg or .ipa")
    status = run_altool(Credentials(), [verb, str(package)])
    if status != 0:
        raise AscError(f"altool {verb} failed with exit status {status}")


def command_await_build(args: argparse.Namespace) -> None:
    client = Client(Credentials())
    deadline = time.monotonic() + args.timeout
    polls = 0
    while True:
        polls += 1
        rows = client.builds(args.app_id, version=str(args.build_number))
        states = sorted({row["attributes"].get("processingState", "") for row in rows})
        print(f"poll {polls}: build {args.build_number} states={states or ['(not listed yet)']}", flush=True)
        if "VALID" in states:
            print(f"build {args.build_number} is VALID in App Store Connect after {polls} polls")
            return
        if {"INVALID", "FAILED"} & set(states):
            raise AscError(f"App Store Connect rejected build {args.build_number}: {states}")
        if time.monotonic() >= deadline:
            raise AscError(
                f"build {args.build_number} was uploaded but not VALID within {args.timeout}s "
                f"({polls} polls); check App Store Connect before re-dispatching"
            )
        time.sleep(args.interval)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    next_build = commands.add_parser("next-build")
    next_build.add_argument("--bundle-id", required=True)
    next_build.add_argument("--family", required=True)
    next_build.add_argument("--require-record", action="store_true")
    for name in ("validate", "upload"):
        command = commands.add_parser(name)
        command.add_argument("--package", required=True)
    await_build = commands.add_parser("await-build")
    await_build.add_argument("--app-id", required=True)
    await_build.add_argument("--build-number", required=True, type=int)
    await_build.add_argument("--timeout", type=int, default=1800)
    await_build.add_argument("--interval", type=int, default=30)
    args = parser.parse_args(argv)
    try:
        if args.command == "next-build":
            command_next_build(args)
        elif args.command == "validate":
            command_altool(args, "--validate-app")
        elif args.command == "upload":
            command_altool(args, "--upload-package")
        else:
            command_await_build(args)
    except AscError as error:
        print(f"APP STORE CONNECT: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
