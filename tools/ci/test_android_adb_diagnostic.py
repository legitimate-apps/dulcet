"""Fake ADB controls for the isolated diagnostic, without an emulator or real fixture."""
import importlib.machinery
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from android_adb_diagnostic import Recorder, snapshot

Runner = importlib.machinery.SourceFileLoader('discover_runner', str(Path(__file__).with_name('run-discover-wishlist-android'))).load_module().Runner
CANARY = 'diagnostic-private-canary'


class DiagnosticTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / 'evidence'
        self.marker = self.root / 'cleanup'
        self.adb = self.root / 'adb'
        self.adb.write_text('''#!/usr/bin/env python3
import pathlib,sys,time
operation=sys.argv[3]
if operation=='shell':
    command=sys.argv[4].split()[0]
    if command=='pidof':sys.exit(1)
    if command=='cat':
        print('cat: /private/diagnostic-private-canary: No such file or directory',file=sys.stderr);sys.exit(1)
    if command=='cmd':pathlib.Path(__file__).with_name('cleanup').touch();sys.exit(0)
if operation=='install':time.sleep(2)
print('adb: device offline diagnostic-private-canary https://private.invalid/?auth=diagnostic-private-canary',file=sys.stderr)
sys.exit(17)
''')
        self.adb.chmod(0o755)
        self.proc = self.root / 'proc'
        (self.proc / 'net').mkdir(parents=True)
        (self.proc / 'net/tcp').write_text('header\n 0: 0100007F:15B2 00000000:0000 0A 0:0 0:0 0 0 0 77\n')
        (self.proc / 'net/tcp6').write_text('header\n')
        process = self.proc / '4377'
        (process / 'fd').mkdir(parents=True)
        (process / 'comm').write_text('qemu-system-x86\n')
        fields = ['0'] * 22
        for index, value in {0: 'S', 1: '1', 11: '3', 12: '4', 17: '2', 19: '100', 21: '200'}.items():
            fields[index] = value
        (process / 'stat').write_text('4377 (qemu-system-x86) ' + ' '.join(fields))
        (process / 'wchan').write_text('futex_wait_queue\n')
        (process / 'fd/5').symlink_to('socket:[77]')
        (process / 'fd/6').symlink_to('/private/' + CANARY)
        self.snapshots = []

        def immediate():
            self.assertFalse(self.marker.exists(), 'snapshot must precede Runner.close cleanup')
            self.snapshots.append(True)
            return snapshot(self.proc)

        self.runner = Runner.__new__(Runner)
        self.runner.adb = str(self.adb)
        self.runner.serial = 'emulator-5554'
        self.runner.adb_diagnostic = Recorder(self.output, immediate)
        self.runner.reversed = False
        self.runner.old_night = '0'

    def call(self, *args, **kwargs):
        return self.runner.adb_run(*args, capture_output=True, text=True, **kwargs)

    def events(self):
        return [json.loads(line) for line in (self.output / 'adb-commands.jsonl').read_text().splitlines()]

    def test_expected_absence_does_not_mask_immediate_pull_failure(self):
        self.assertEqual(self.call('shell', 'pidof private-app', check=False).returncode, 1)
        self.assertEqual(self.call('shell', 'cat /private/' + CANARY, check=False).returncode, 1)
        self.assertFalse(self.snapshots)
        self.assertFalse(list(self.output.glob('first-*.json')))
        result = self.call('pull', '/private/' + CANARY, 'https://private.invalid/?auth=' + CANARY, check=False)
        self.assertEqual(result.returncode, 17)
        evidence = json.loads((self.output / 'first-adb-failure.json').read_text())
        self.assertEqual(evidence['trigger']['ordinal'], 3)
        self.assertEqual(evidence['trigger']['operation'], 'pull')
        self.assertEqual(evidence['trigger']['stderr_class'], 'device-offline')
        self.assertGreater(evidence['trigger']['stderr_bytes'], 0)
        self.assertEqual(evidence['qemu'][0]['state'], 'S')
        self.assertEqual(evidence['qemu'][0]['wchan'], 'futex_wait_queue')
        self.assertEqual(evidence['qemu'][0]['threads'], 2)
        self.assertEqual(evidence['qemu'][0]['utime_ticks'], 3)
        self.assertEqual(evidence['listeners'][0]['port'], 5554)
        self.assertEqual(evidence['listeners'][0]['owners'], [{'pid': 4377, 'kind': 'qemu'}])
        self.runner.close()
        self.assertTrue(self.marker.exists())
        self.assertEqual(len(self.snapshots), 1)
        records = self.events()
        self.assertEqual([r['ordinal'] for r in records], list(range(1, len(records) + 1)))
        self.assertTrue(all(r['end_monotonic_ns'] >= r['start_monotonic_ns'] for r in records))
        all_metadata = ''.join(p.read_text() for p in self.output.iterdir())
        for forbidden in (CANARY, 'https://', 'auth=', '/private/', 'private-app'):
            self.assertNotIn(forbidden, all_metadata)

    def test_checked_failure_preserves_real_exception_and_status(self):
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            self.call('get-state')
        self.assertEqual(caught.exception.returncode, 17)
        self.assertEqual(self.events()[0]['returncode'], 17)
        self.assertEqual(len(self.snapshots), 1)

    def test_timeout_preserves_timeout_and_takes_snapshot(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            self.call('install', CANARY, timeout=0.1)
        row = self.events()[0]
        self.assertTrue(row['timeout'])
        self.assertIsNone(row['returncode'])
        self.assertEqual(row['stderr_class'], 'timeout')
        self.assertEqual(len(self.snapshots), 1)


if __name__ == '__main__':
    unittest.main()
