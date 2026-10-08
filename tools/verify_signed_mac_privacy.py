#!/usr/bin/env python3
"""Fail closed before publishing signed-host JUnit or auditing downloaded job evidence.

The private token registry is derived by the local launcher and never committed.
Diagnostics contain only verdicts and counts, never matched values or source lines.
"""
import argparse
import html
import json
from pathlib import Path
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET

CASE = 'testLiveConnectSavesDeviceOnlyCredentialAndRendersConnectedUI'

def clean(paths, tokens):
    if not tokens or any(not isinstance(t, str) or len(t) < 2 for t in tokens):
        raise ValueError('invalid private registry')
    for path in paths:
        text = Path(path).read_bytes().decode('utf-8', errors='strict').casefold()
        for token in tokens:
            variants = [token, html.escape(token), urllib.parse.quote(token),
                        json.dumps(token, ensure_ascii=False)[1:-1]]
            if any(v.casefold() in text for v in variants):
                return False
    return True

def junit(path):
    suite = ET.parse(path).getroot()
    assert suite.tag == 'testsuite'
    assert set(suite.attrib) <= {'name', 'tests', 'failures', 'errors', 'skipped', 'time'}
    assert suite.attrib['tests'] == '1'
    assert all(suite.attrib.get(k, '0') == '0' for k in ('failures', 'errors', 'skipped'))
    cases = list(suite)
    assert len(cases) == 1 and cases[0].tag == 'testcase'
    case = cases[0]
    assert set(case.attrib) <= {'classname', 'name', 'time'}
    assert case.attrib['classname'] == 'DulcetSignedMacAccountConnectTests'
    assert case.attrib['name'] == CASE
    assert not list(case) and not (case.text or '').strip()
    return 1

def self_test():
    with tempfile.TemporaryDirectory() as directory:
        p = Path(directory)/'probe'
        tokens = ['private-fixture-token', 'private fixture path']
        p.write_text('neutral passing evidence')
        assert clean([p], tokens)
        controls = 0
        for t in tokens:
            for v in (t, t.upper(), html.escape(t), urllib.parse.quote(t), json.dumps(t)[1:-1]):
                p.write_text(v)
                assert not clean([p], tokens)
                controls += 1
        p.write_text('<testsuite tests="1"><testcase classname="DulcetSignedMacAccountConnectTests" name="'+CASE+'"/></testsuite>')
        assert junit(p) == 1
        for bad in ('tests="0"', 'tests="2"', 'tests="1" failures="1"'):
            p.write_text('<testsuite '+bad+'><testcase classname="DulcetSignedMacAccountConnectTests" name="'+CASE+'"/></testsuite>')
            try: junit(p)
            except (AssertionError, KeyError): controls += 1
            else: raise AssertionError('invalid evidence accepted')
    print('Privacy controls PASS count='+str(controls))

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--tokens', type=Path)
    p.add_argument('--file', action='append', type=Path, default=[])
    p.add_argument('--junit', type=Path)
    p.add_argument('--self-test', action='store_true')
    args = p.parse_args()
    try:
        if args.self_test: self_test()
        if args.file or args.junit:
            if not args.tokens: raise ValueError('missing registry')
            tokens = json.loads(args.tokens.read_text())
            paths = [*args.file, *([args.junit] if args.junit else [])]
            if not clean(paths, tokens): raise ValueError('private token found')
            count = junit(args.junit) if args.junit else 0
            print('Privacy scrub PASS files='+str(len(paths))+' tests='+str(count))
        elif not args.self_test: raise ValueError('missing evidence')
    except Exception:
        print('Privacy scrub FAIL; evidence withheld')
        return 1
    return 0

if __name__ == '__main__': raise SystemExit(main())
