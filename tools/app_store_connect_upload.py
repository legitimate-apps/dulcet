#!/usr/bin/env python3
"""Upload one signed app package through App Store Connect's build-upload API.

Authentication is intentionally environment-only. The key identifier, issuer identifier, and
private key are never accepted as command-line arguments, and signed upload URLs are never logged.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from typing import Any
import urllib.error
import urllib.parse
import urllib.request


API_ROOT = "https://api.appstoreconnect.apple.com"
API_AUDIENCE = "appstoreconnect-v1"
TOKEN_LIFETIME_SECONDS = 15 * 60
REQUEST_TIMEOUT_SECONDS = 120
PROCESSING_TIMEOUT_SECONDS = 45 * 60
POLL_INTERVAL_SECONDS = 15
REQUIRED_ENVIRONMENT = (
    "DULCET_ASC_KEY_ID",
    "DULCET_ASC_ISSUER_ID",
    "DULCET_ASC_KEY_P8_BASE64",
)


class UploadFailure(RuntimeError):
    """A safe-to-print upload failure with secrets and signed URLs removed."""


def fail(message: str) -> None:
    raise UploadFailure(message)


def base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def redact(text: str) -> str:
    """Remove URL query strings and bearer-shaped values from a diagnostic."""
    text = re.sub(r"https?://[^\s\"'<>]+", "[REDACTED_URL]", text)
    text = re.sub(r"\bBearer\s+[A-Za-z0-9._~-]+", "Bearer [REDACTED]", text, flags=re.I)
    return text[:1000]


def der_ecdsa_to_raw(signature: bytes, component_size: int = 32) -> bytes:
    """Convert OpenSSL's ASN.1 ECDSA signature into the raw JWS r||s form."""

    def read_length(data: bytes, offset: int) -> tuple[int, int]:
        if offset >= len(data):
            fail("JWT signing returned a truncated DER length")
        first = data[offset]
        if first < 0x80:
            return first, offset + 1
        count = first & 0x7F
        if count == 0 or count > 4 or offset + 1 + count > len(data):
            fail("JWT signing returned an invalid DER length")
        return int.from_bytes(data[offset + 1 : offset + 1 + count], "big"), offset + 1 + count

    if not signature or signature[0] != 0x30:
        fail("JWT signing returned a non-sequence DER signature")
    sequence_length, cursor = read_length(signature, 1)
    if cursor + sequence_length != len(signature):
        fail("JWT signing returned a malformed DER sequence")

    components: list[bytes] = []
    for _ in range(2):
        if cursor >= len(signature) or signature[cursor] != 0x02:
            fail("JWT signing returned a malformed DER integer")
        integer_length, integer_start = read_length(signature, cursor + 1)
        integer_end = integer_start + integer_length
        if integer_end > len(signature):
            fail("JWT signing returned a truncated DER integer")
        integer = signature[integer_start:integer_end].lstrip(b"\x00") or b"\x00"
        if len(integer) > component_size:
            fail("JWT signing returned an oversized ECDSA component")
        components.append(integer.rjust(component_size, b"\x00"))
        cursor = integer_end
    if cursor != len(signature):
        fail("JWT signing returned trailing DER data")
    return b"".join(components)


class AppStoreConnectClient:
    def __init__(self, key_id: str, issuer_id: str, private_key_path: Path) -> None:
        self.key_id = key_id
        self.issuer_id = issuer_id
        self.private_key_path = private_key_path

    def token(self) -> str:
        now = int(time.time())
        header = base64url(
            json.dumps(
                {"alg": "ES256", "kid": self.key_id, "typ": "JWT"},
                separators=(",", ":"),
                sort_keys=True,
            ).encode()
        )
        payload = base64url(
            json.dumps(
                {
                    "aud": API_AUDIENCE,
                    "exp": now + TOKEN_LIFETIME_SECONDS,
                    "iat": now,
                    "iss": self.issuer_id,
                },
                separators=(",", ":"),
                sort_keys=True,
            ).encode()
        )
        signing_input = f"{header}.{payload}".encode("ascii")
        result = subprocess.run(
            [
                "/usr/bin/openssl",
                "dgst",
                "-sha256",
                "-sign",
                str(self.private_key_path),
            ],
            input=signing_input,
            capture_output=True,
            env={"LC_ALL": "C", "PATH": "/usr/bin:/bin"},
            check=False,
        )
        if result.returncode != 0:
            fail("App Store Connect JWT signing failed")
        return f"{header}.{payload}.{base64url(der_ecdsa_to_raw(result.stdout))}"

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        if not path.startswith("/") or path.startswith("//"):
            fail("refusing an App Store Connect request outside the fixed API origin")
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token()}",
        }
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            f"{API_ROOT}{path}",
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                response_body = response.read()
        except urllib.error.HTTPError as error:
            response_body = error.read()
            detail = self.error_detail(response_body)
            fail(f"App Store Connect request failed: status={error.code} detail={detail}")
        except (OSError, urllib.error.URLError) as error:
            fail(f"App Store Connect request failed before a response: {redact(str(error))}")
        if not response_body:
            return {}
        try:
            decoded = json.loads(response_body)
        except (UnicodeDecodeError, json.JSONDecodeError):
            fail("App Store Connect returned a non-JSON response")
        if not isinstance(decoded, dict):
            fail("App Store Connect returned an unexpected JSON document")
        return decoded

    @staticmethod
    def error_detail(body: bytes) -> str:
        try:
            decoded = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError):
            return "response body withheld"
        safe: list[str] = []
        for item in decoded.get("errors", []) if isinstance(decoded, dict) else []:
            if not isinstance(item, dict):
                continue
            fields = [item.get(name) for name in ("status", "code", "title", "detail")]
            safe.append(" | ".join(redact(str(value)) for value in fields if value))
        return "; ".join(safe)[:1000] or "no structured error detail"


def relationship_id(document: dict[str, Any], expected_type: str) -> str:
    data = document.get("data")
    if not isinstance(data, dict) or data.get("type") != expected_type:
        fail(f"App Store Connect response omitted {expected_type} data")
    identifier = data.get("id")
    if not isinstance(identifier, str) or not identifier:
        fail(f"App Store Connect response omitted the {expected_type} identifier")
    return identifier


def find_app(client: AppStoreConnectClient, bundle_id: str) -> str:
    query = urllib.parse.urlencode({"filter[bundleId]": bundle_id, "limit": "2"})
    document = client.request("GET", f"/v1/apps?{query}")
    candidates = document.get("data")
    if not isinstance(candidates, list):
        fail("App Store Connect app lookup returned an unexpected response")
    exact = [
        item
        for item in candidates
        if isinstance(item, dict)
        and item.get("type") == "apps"
        and item.get("attributes", {}).get("bundleId") == bundle_id
    ]
    if len(exact) != 1:
        fail(f"expected exactly one App Store Connect app for bundle identifier {bundle_id}")
    identifier = exact[0].get("id")
    if not isinstance(identifier, str) or not identifier:
        fail("App Store Connect app lookup omitted the app identifier")
    return identifier


def validate_operations(operations: Any, file_size: int) -> list[dict[str, Any]]:
    if not isinstance(operations, list) or not operations:
        fail("App Store Connect returned no package upload operations")
    ordered = sorted(operations, key=lambda item: item.get("offset", -1) if isinstance(item, dict) else -1)
    expected_offset = 0
    for operation in ordered:
        if not isinstance(operation, dict):
            fail("App Store Connect returned a malformed package upload operation")
        offset = operation.get("offset")
        length = operation.get("length")
        method = operation.get("method")
        url = operation.get("url")
        headers = operation.get("requestHeaders", [])
        if (
            not isinstance(offset, int)
            or not isinstance(length, int)
            or length < 1
            or method != "PUT"
            or not isinstance(url, str)
            or not url.startswith("https://")
            or not isinstance(headers, list)
        ):
            fail("App Store Connect returned an invalid package upload operation")
        if offset != expected_offset:
            fail("App Store Connect package upload operations contain a gap or overlap")
        expected_offset += length
    if expected_offset != file_size:
        fail("App Store Connect package upload operations do not cover the exported package")
    return ordered


def upload_operations(package: Path, operations: list[dict[str, Any]]) -> None:
    with package.open("rb") as handle:
        for index, operation in enumerate(operations, 1):
            offset = operation["offset"]
            length = operation["length"]
            handle.seek(offset)
            data = handle.read(length)
            if len(data) != length:
                fail("the exported package changed while it was being uploaded")
            headers: dict[str, str] = {}
            for header in operation.get("requestHeaders", []):
                if not isinstance(header, dict):
                    fail("App Store Connect returned a malformed upload header")
                name = header.get("name")
                value = header.get("value")
                if not isinstance(name, str) or not isinstance(value, str):
                    fail("App Store Connect returned a malformed upload header")
                headers[name] = value
            request = urllib.request.Request(
                operation["url"],
                data=data,
                headers=headers,
                method="PUT",
            )
            try:
                with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                    response.read()
            except urllib.error.HTTPError as error:
                error.read()
                fail(f"package upload part {index} failed: status={error.code}; response withheld")
            except (OSError, urllib.error.URLError):
                fail(f"package upload part {index} failed before a response; URL withheld")
            print(f"ASC BUILD UPLOAD part={index}/{len(operations)} bytes={length} complete")


def upload_build(
    client: AppStoreConnectClient,
    package: Path,
    bundle_id: str,
    version: str,
    build_number: str,
) -> None:
    app_id = find_app(client, bundle_id)
    upload = client.request(
        "POST",
        "/v1/buildUploads",
        {
            "data": {
                "type": "buildUploads",
                "attributes": {
                    "cfBundleShortVersionString": version,
                    "cfBundleVersion": build_number,
                    "platform": "MAC_OS",
                },
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
            }
        },
    )
    upload_id = relationship_id(upload, "buildUploads")
    package_size = package.stat().st_size
    reservation = client.request(
        "POST",
        "/v1/buildUploadFiles",
        {
            "data": {
                "type": "buildUploadFiles",
                "attributes": {
                    "assetType": "ASSET",
                    "fileName": package.name,
                    "fileSize": package_size,
                    "uti": "com.apple.pkg",
                },
                "relationships": {
                    "buildUpload": {
                        "data": {"type": "buildUploads", "id": upload_id}
                    }
                },
            }
        },
    )
    file_id = relationship_id(reservation, "buildUploadFiles")
    attributes = reservation["data"].get("attributes", {})
    operations = validate_operations(attributes.get("uploadOperations"), package_size)
    print(
        "ASC BUILD UPLOAD reservation complete "
        f"bundle-id={bundle_id} version={version} build={build_number} parts={len(operations)}"
    )
    upload_operations(package, operations)

    sha256 = hashlib.sha256()
    with package.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            sha256.update(chunk)
    client.request(
        "PATCH",
        f"/v1/buildUploadFiles/{urllib.parse.quote(file_id, safe='')}",
        {
            "data": {
                "type": "buildUploadFiles",
                "id": file_id,
                "attributes": {
                    "uploaded": True,
                    "sourceFileChecksums": {
                        "file": {"algorithm": "SHA_256", "hash": sha256.hexdigest()}
                    },
                },
            }
        },
    )
    print("ASC BUILD UPLOAD package committed; waiting for App Store Connect processing")

    deadline = time.monotonic() + PROCESSING_TIMEOUT_SECONDS
    previous_state = ""
    while time.monotonic() < deadline:
        query = urllib.parse.urlencode({"fields[buildUploads]": "state"})
        status = client.request(
            "GET",
            f"/v1/buildUploads/{urllib.parse.quote(upload_id, safe='')}?{query}",
        )
        state_details = status.get("data", {}).get("attributes", {}).get("state", {})
        state = state_details.get("state") if isinstance(state_details, dict) else None
        if isinstance(state, str) and state != previous_state:
            print(f"ASC BUILD UPLOAD processing-state={state}")
            previous_state = state
        if state == "COMPLETE":
            print(
                "ASC BUILD UPLOAD COMPLETE "
                f"bundle-id={bundle_id} version={version} build={build_number}"
            )
            return
        if state == "FAILED":
            details = redact(json.dumps(state_details, separators=(",", ":")))
            fail(f"App Store Connect build processing failed: {details}")
        time.sleep(POLL_INTERVAL_SECONDS)
    fail("App Store Connect build processing did not finish within 45 minutes")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True, type=Path)
    parser.add_argument("--bundle-id", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build-number", required=True)
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    if not arguments.package.is_file() or arguments.package.suffix != ".pkg":
        fail("--package must identify one exported .pkg file")
    if not re.fullmatch(r"com\.legitimateapps\.dulcet(?:\.dev)?", arguments.bundle_id):
        fail("--bundle-id is not a Dulcet macOS release channel")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", arguments.version):
        fail("--version must contain two or three dot-separated integers")
    if not re.fullmatch(r"[1-9][0-9]*", arguments.build_number):
        fail("--build-number must be a positive integer")

    missing = [name for name in REQUIRED_ENVIRONMENT if not os.environ.get(name)]
    if missing:
        fail(f"required upload environment is empty: {', '.join(missing)}")
    try:
        private_key = base64.b64decode(
            os.environ["DULCET_ASC_KEY_P8_BASE64"],
            validate=True,
        )
    except (ValueError, binascii.Error):
        fail("DULCET_ASC_KEY_P8_BASE64 is not valid Base64")
    if b"-----BEGIN PRIVATE KEY-----" not in private_key:
        fail("DULCET_ASC_KEY_P8_BASE64 does not contain a PEM private key")

    old_umask = os.umask(0o077)
    try:
        with tempfile.TemporaryDirectory(prefix="dulcet-asc-") as temporary_directory:
            private_key_path = Path(temporary_directory) / "AuthKey.p8"
            private_key_path.write_bytes(private_key)
            client = AppStoreConnectClient(
                os.environ["DULCET_ASC_KEY_ID"],
                os.environ["DULCET_ASC_ISSUER_ID"],
                private_key_path,
            )
            upload_build(
                client,
                arguments.package,
                arguments.bundle_id,
                arguments.version,
                arguments.build_number,
            )
    finally:
        os.umask(old_umask)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except UploadFailure as error:
        raise SystemExit(f"dulcet-app-store-upload: {redact(str(error))}") from None
