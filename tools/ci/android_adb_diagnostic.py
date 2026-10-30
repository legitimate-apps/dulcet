"""Finite diagnostic metadata; never persist ADB arguments, output, or environment."""
import datetime
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def operation(argv):
    top = argv[0] if argv else ''
    if top == 'shell':
        try:
            words = shlex.split(argv[1]) if len(argv) > 1 else []
        except ValueError:
            words = []
        word = words[0] if words else ''
        if word == 'am' and len(words) > 1 and words[1] == 'instrument':
            return 'shell.instrument'
        return 'shell.' + (word if word in {'am', 'pm', 'settings', 'cmd', 'pidof', 'cat', 'run-as', 'getprop'} else 'other')
    if top == 'logcat':
        return 'logcat.clear' if '-c' in argv else 'logcat.read'
    return top if top in {'pull', 'install', 'reverse', 'get-state'} else 'other'


def stderr_class(value, timeout=False):
    if timeout:
        return 'timeout'
    text = (value.decode(errors='replace') if isinstance(value, bytes) else value or '').lower()
    # Classification only: no matched text, paths, URLs, command lines or raw logcat survives.
    for label, phrases in (
        ('device-offline', ('device offline',)),
        ('device-not-found', ('no devices/emulators found', 'device not found')),
        ('connection-refused', ('connection refused',)),
        ('connection-reset', ('connection reset',)),
        ('broken-pipe', ('broken pipe',)),
        ('transport-error', ('protocol fault', 'transport error', 'closed', 'failed to read', 'failed to write')),
        ('missing-file', ('no such file or directory', 'does not exist')),
        ('permission-denied', ('permission denied',)),
        ('device-unauthorized', ('device unauthorized', 'unauthorized')),
    ):
        if any(phrase in text for phrase in phrases):
            return label
    if 'device' in text and 'not found' in text:
        return 'device-not-found'
    return 'other-stderr' if text else 'empty-stderr'


def snapshot(proc=Path('/proc')):
    """Read only process accounting and owner IDs for three known loopback listeners."""
    result = {'time': utc(), 'monotonic_ns': time.monotonic_ns(), 'qemu': [], 'listeners': []}
    listeners = {}
    for version in ('tcp', 'tcp6'):
        try:
            lines = (proc / 'net' / version).read_text().splitlines()[1:]
            for line in lines:
                fields = line.split()
                address, port = fields[1].split(':')
                port = int(port, 16)
                loopback = address in {'0100007F', '00000000000000000000000001000000', '0000000000000000FFFF00000100007F'}
                wildcard = set(address) == {'0'}
                if port in {5037, 5554, 5555} and fields[3] == '0A' and (loopback or wildcard):
                    entry = {'port': port, 'family': version, 'bind': 'loopback' if loopback else 'wildcard', 'inode': int(fields[9]), 'owners': []}
                    listeners.setdefault(fields[9], []).append(entry)
                    result['listeners'].append(entry)
        except (OSError, ValueError, IndexError):
            result.setdefault('unavailable', []).append(version)
    for directory in proc.iterdir():
        if not directory.name.isdigit():
            continue
        try:
            comm = (directory / 'comm').read_text().strip()
            if comm.startswith('qemu-system'):
                raw = (directory / 'stat').read_text()
                fields = raw[raw.rfind(')') + 2:].split()
                try:
                    wchan = (directory / 'wchan').read_text().strip()
                except OSError:
                    wchan = 'unavailable'
                result['qemu'].append({'pid': int(directory.name), 'state': fields[0], 'ppid': int(fields[1]),
                    'utime_ticks': int(fields[11]), 'stime_ticks': int(fields[12]), 'threads': int(fields[17]),
                    'starttime_ticks': int(fields[19]), 'rss_pages': int(fields[21]),
                    'page_size': os.sysconf('SC_PAGE_SIZE'),
                    'wchan': wchan if re.fullmatch(r'[a-zA-Z0-9_]{1,128}', wchan) else 'unavailable'})
            # File-descriptor targets are compared internally and never copied to output.
            for fd in (directory / 'fd').iterdir():
                try:
                    target = fd.readlink().as_posix()
                    match = re.fullmatch(r'socket:\[(\d+)\]', target)
                    if match and match[1] in listeners:
                        for entry in listeners[match[1]]:
                            owner = {'pid': int(directory.name), 'kind': 'qemu' if comm.startswith('qemu-system') else 'adb' if comm == 'adb' else 'other'}
                            if owner not in entry['owners']:
                                entry['owners'].append(owner)
                except OSError:
                    pass
        except (OSError, ValueError, IndexError):
            pass
    return result


class Recorder:
    def __init__(self, output, snapshotter=snapshot):
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.ordinal = 0
        self.failures = set()
        self.snapshotter = snapshotter

    def run(self, executable, serial, argv, *, cwd, check=True, **kwargs):
        self.ordinal += 1
        row = {'ordinal': self.ordinal, 'operation': operation(argv), 'start': utc(), 'start_monotonic_ns': time.monotonic_ns()}
        error = None
        result = None
        try:
            result = subprocess.run([executable, '-s', serial, *argv], cwd=cwd, check=False, **kwargs)
            row.update(returncode=result.returncode, timeout=False, stderr_class=stderr_class(result.stderr))
        except (OSError, subprocess.TimeoutExpired) as caught:
            error = caught
            row.update(returncode=None, timeout=isinstance(caught, subprocess.TimeoutExpired),
                       stderr_class=stderr_class(getattr(caught, 'stderr', None), isinstance(caught, subprocess.TimeoutExpired)),
                       exception_type=type(caught).__name__)
        row.update(end=utc(), end_monotonic_ns=time.monotonic_ns())
        raw_stderr = getattr(error, 'stderr', None) if error else result.stderr
        row['stderr_bytes'] = len(raw_stderr.encode() if isinstance(raw_stderr, str) else raw_stderr or b'')
        failed = error is not None or result.returncode != 0
        # These check=False absence controls are expected; transport failures are never exempt.
        category = row['stderr_class']
        expected_absence = not check and error is None and (
            row['operation'] == 'shell.pidof' and category == 'empty-stderr' or
            row['operation'] == 'shell.cat' and category == 'missing-file')
        row['expected_absence'] = expected_absence and failed
        if failed and not expected_absence and category not in self.failures:
            self.failures.add(category)
            name = 'first-adb-failure.json' if len(self.failures) == 1 else 'first-adb-' + category + '.json'
            try:
                evidence = self.snapshotter()
            except (OSError, ValueError) as caught:
                evidence = {'time': utc(), 'snapshot_error_type': type(caught).__name__}
            evidence['trigger'] = dict(row)
            (self.output / name).write_text(json.dumps(evidence, indent=2) + '\n')
            row['snapshot'] = name
        with (self.output / 'adb-commands.jsonl').open('a') as stream:
            stream.write(json.dumps(row) + '\n')
        if error is not None:
            raise error
        if check:
            result.check_returncode()
        return result
