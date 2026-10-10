#!/usr/bin/env python3
"""Withhold device evidence unless its runtime token scan and exact JUnit both pass.

The local registry includes both paired devices' identifying values in addition to
the account, signing and canary tokens. Diagnostics expose only verdicts and counts.
"""
import argparse
import html
import json
from pathlib import Path
import re
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET

CLASS = 'DulcetSignedIPhoneAccountConnectUITests'
CASE = 'testSignedIPhoneConnectKeychainRelaunchAndTouchReconnect'
TEST = CLASS + '/' + CASE


from verify_signed_ipad_privacy import clean, registry


def junit(path):
    raw = Path(path).read_bytes()
    # No declarations, entity expansion, comments or processing instructions carry
    # additional upload data. A plain XML declaration is the only allowed prefix.
    checked = re.sub(rb'^\s*<\?xml\s+[^?]*\?>', b'', raw, count=1)
    if b'<!' in checked or b'<?' in checked:
        raise ValueError('invalid evidence')
    suite = ET.fromstring(raw)
    if (suite.tag != 'testsuite'
            or set(suite.attrib) - {'name', 'tests', 'failures', 'errors', 'skipped', 'time'}
            or suite.attrib.get('name', CLASS) != CLASS
            or suite.attrib.get('tests') != '1'
            or any(suite.attrib.get(k, '0') != '0' for k in ('failures', 'errors', 'skipped'))
            or (suite.text or '').strip() or (suite.tail or '').strip()):
        raise ValueError('invalid evidence')
    cases = list(suite)
    if len(cases) != 1 or cases[0].tag != 'testcase':
        raise ValueError('invalid evidence')
    case = cases[0]
    if (set(case.attrib) - {'classname', 'name', 'time'}
            or case.attrib.get('classname') != CLASS or case.attrib.get('name') != CASE
            or list(case) or (case.text or '').strip() or (case.tail or '').strip()):
        raise ValueError('invalid evidence')
    for element in (suite, case):
        if 'time' in element.attrib and not re.fullmatch(r'\d+(?:\.\d+)?', element.attrib['time']):
            raise ValueError('invalid evidence')
    return 1


def self_test():
    controls = 0
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'probe'
        tokens = ['fixture-device-identity', 'fixture path & value', 'fixture-\u00e9']
        path.write_text('neutral passing evidence')
        assert clean([path], tokens)
        for token in tokens:
            for value in (token, token.upper(), html.escape(token), urllib.parse.quote(token, safe=''),
                          urllib.parse.quote_plus(token, safe=''), json.dumps(token)[1:-1]):
                path.write_text(value)
                assert not clean([path], tokens)
                controls += 1
        for bad in ([], {}, ['x'], ['fixture\nvalue'], [17]):
            try:
                clean([path], bad)
            except ValueError:
                controls += 1
            else:
                raise AssertionError('invalid registry accepted')
        xml = '<testsuite name="'+CLASS+'" tests="1"><testcase classname="'+CLASS+'" name="'+CASE+'"/></testsuite>'
        path.write_text(xml)
        assert junit(path) == 1
        for bad in (xml.replace('tests="1"', 'tests="0"'),
                    xml.replace('tests="1"', 'tests="2"'),
                    xml.replace('tests="1"', 'tests="1" failures="1"'),
                    xml.replace('tests="1"', 'tests="1" skipped="1"'),
                    xml.replace(CASE, 'wrongCase'), xml.replace(CLASS, 'WrongClass'),
                    xml.replace('/>', '><system-out>payload</system-out></testcase>'),
                    xml.replace('</testsuite>', 'payload</testsuite>'),
                    xml.replace('<testcase', '<!--payload--><testcase'),
                    xml.replace('tests="1"', 'tests="1" time="payload"'),
                    '<!DOCTYPE testsuite>'+xml,
                    xml.replace('tests="1"', 'tests="1" hostname="payload"')):
            path.write_text(bad)
            try:
                junit(path)
            except (ValueError, ET.ParseError):
                controls += 1
            else:
                raise AssertionError('invalid evidence accepted')
    print('iPhone privacy controls PASS count='+str(controls))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tokens', type=Path)
    parser.add_argument('--file', action='append', type=Path, default=[])
    parser.add_argument('--junit', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
        if args.file or args.junit:
            tokens = registry(args.tokens)
            paths = [*args.file, *([args.junit] if args.junit else [])]
            if not clean(paths, tokens):
                raise ValueError('private token found')
            count = junit(args.junit) if args.junit else 0
            print('iPhone privacy scrub PASS files='+str(len(paths))+' tests='+str(count))
        elif not args.self_test:
            raise ValueError('missing evidence')
    except Exception:
        print('iPhone privacy scrub FAIL; evidence withheld')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
