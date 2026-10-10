#!/usr/bin/env python3
"""Audit downloaded device-run output and emit a receipt to private local storage.

No receipt is written into the repository. A detected privacy violation deletes
the public run, including its logs and artifacts; diagnostics expose no values.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import urllib.parse
import zipfile

from verify_signed_iphone_privacy import TEST, clean, junit, registry

REPO = 'legitimate-apps/dulcet'
SCRATCH = 'legitimate-apps/dulcet-signed-iphone-privacy-check'
WORKFLOW = 'signed-iphone-account-connect'
JOB = 'signed-iphone-account-connect'
NEUTRAL = 'dulcet-signed-iphone-host'
SOURCES = (
    'apple/DulcetSignedIPhoneUITests/DulcetSignedIPhoneAccountConnectUITests.swift',
    'apple/DulcetSignedIPhoneHost/DulcetSignedIPhoneAccountProbe.swift',
    'apple/DulcetSignedIPhoneHost/DulcetSignedIPhoneApp.swift',
    'apple/signed-iphone.yml',
    'tools/ci/run-signed-iphone-proof',
    'tools/verify_signed_iphone_privacy.py',
)


from audit_signed_ipad_receipt import GitHub, PrivacyIncident
import audit_signed_ipad_receipt as shared
import verify_signed_iphone_privacy as privacy

TITLE = 'iPhone'
ARTIFACT_PREFIX = 'dulcet-signed-iphone-junit'
AUDIT_TOOL = 'tools/audit_signed_iphone_receipt.py'
# Shared scanner and auditor inputs are part of the audited source envelope too.
SOURCES += ('tools/audit_signed_ipad_receipt.py', 'tools/verify_signed_ipad_privacy.py',
            'tools/ci/signed-iphone-job', 'tools/ci/signed-ipad-job',
            '.github/workflows/signed-iphone-account-connect.yml',
            'apple/DulcetAppleShared/DulcetAppleProduction.swift',
            'apple/DulcetKit/Sources/DulcetKit/AccountConnectionPresentation.swift',
            'apple/DulcetKit/Sources/DulcetKit/DulcetCredentialStore.swift')


def audit(repo, run_id, tokens, output, github):
    return shared.audit(repo, run_id, tokens, output, github, lane=globals())


def self_test():
    shared.self_test(lane=globals())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id')
    parser.add_argument('--tokens', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--repo', choices=(REPO, SCRATCH), default=REPO)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
        if args.run_id:
            receipt = audit(args.repo, args.run_id, registry(args.tokens), args.output, GitHub())
            print('iPhone receipt audit PASS files='+str(receipt['audit']['files_scanned'])
                  +' tokens='+str(receipt['audit']['tokens_checked'])+' tests=1')
        elif not args.self_test:
            raise ValueError('missing run')
    except PrivacyIncident as incident:
        print('iPhone privacy audit FAIL; public run deleted' if incident.deleted
              else 'iPhone privacy audit FAIL; receipt withheld; run removal requires attention')
        return 1
    except Exception:
        print('iPhone receipt audit FAIL; receipt withheld')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
