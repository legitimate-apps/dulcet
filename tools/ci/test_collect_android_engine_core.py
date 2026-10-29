"""No policy changes or emulators: isolated controls for the diagnostic-only collector."""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

loader = importlib.machinery.SourceFileLoader('engine_core', str(Path(__file__).with_name('collect-android-engine-core')))
spec = importlib.util.spec_from_loader(loader.name, loader)
core = importlib.util.module_from_spec(spec)
loader.exec_module(core)


class EngineCoreControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.sdk = self.root / 'sdk'
        self.proc = self.root / 'proc'
        self.private = self.root / 'engine-private-cores'
        self.output = self.root / 'engine-diagnostic/core-text'
        self.output.mkdir(parents=True)
        self.args = SimpleNamespace(private=self.private, output=self.output, sdk=self.sdk)

    def tearDown(self):
        self.temp.cleanup()

    def process(self, pid=37, start='123', flags=0x400000, exit_code=0):
        executable = self.sdk / 'emulator/qemu-system-x86_64-headless'
        executable.parent.mkdir(parents=True, exist_ok=True)
        executable.touch()
        path = self.proc / str(pid)
        path.mkdir(parents=True, exist_ok=True)
        (path / 'exe').symlink_to(executable)
        fields = ['0'] * 50
        fields[0], fields[6], fields[19], fields[49] = 'S', str(flags), start, str(exit_code)
        (path / 'stat').write_text(f'{pid} (qemu name) ' + ' '.join(fields))
        return core.identity(pid, self.sdk, self.proc)

    def policy(self):
        pattern = {'value': '|original-collector'}
        original_read = Path.read_text
        def read(path, *args, **kwargs):
            return pattern['value'] + '\n' if str(path) == '/proc/sys/kernel/core_pattern' else original_read(path, *args, **kwargs)
        return pattern, patch.object(core, 'hosted'), patch.object(core.shutil, 'which', return_value='/usr/bin/gdb'), patch.object(core.shutil, 'disk_usage', return_value=SimpleNamespace(free=12 * 1024**3)), patch.object(Path, 'read_text', read), patch.object(core, 'set_pattern', side_effect=lambda value: pattern.update(value=value))

    def test_policy_rejects_non_ephemeral_host_before_mutation(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(core, 'set_pattern') as mutation:
            with self.assertRaises(RuntimeError): core.prepare(self.args)
            mutation.assert_not_called()

    def test_pid_identity_and_fatal_fields_are_exact(self):
        healthy = self.process()
        self.assertEqual(healthy['starttime'], '123')
        self.assertFalse(core.fatal(healthy))
        fatal = dict(healthy, flags=0x400408, exitCode=11)
        self.assertTrue(core.fatal(fatal))
        self.assertFalse(core.same_process(healthy, dict(healthy, starttime='124')))

    def test_non_sdk_process_is_rejected(self):
        process = self.process()
        (self.proc / '37/exe').unlink()
        outside = self.root / 'qemu-system-other'; outside.touch()
        (self.proc / '37/exe').symlink_to(outside)
        self.assertIsNone(core.identity(process['pid'], self.sdk, self.proc))

    def test_prlimit_never_mutates_reused_pid(self):
        process = self.process()
        with patch.object(core, 'identity', return_value=dict(process, starttime='124')), patch.object(core.resource, 'prlimit', create=True) as mutation:
            with self.assertRaises(RuntimeError): core.enable_core(process, self.sdk)
            mutation.assert_not_called()

    def test_prlimit_records_before_after_and_identity(self):
        process = self.process()
        with patch.object(core, 'identity', return_value=process), patch.object(core.resource, 'prlimit', create=True, side_effect=[(0, core.resource.RLIM_INFINITY), None, (core.CORE_BYTES, core.resource.RLIM_INFINITY)]) as limits:
            receipt = core.enable_core(process, self.sdk)
            self.assertEqual(receipt['before'], [0, core.resource.RLIM_INFINITY]); self.assertEqual(receipt['after'], [core.CORE_BYTES, core.resource.RLIM_INFINITY])
            self.assertEqual(limits.call_args_list[1].args, (37, core.resource.RLIMIT_CORE, (core.CORE_BYTES, core.resource.RLIM_INFINITY)))

    def test_insufficient_hard_limit_fails_without_mutation(self):
        process = self.process()
        with patch.object(core, 'identity', return_value=process), patch.object(core.resource, 'prlimit', create=True, return_value=(0, 0)) as limits:
            with self.assertRaises(RuntimeError): core.enable_core(process, self.sdk)
            self.assertEqual(limits.call_count, 1)

    def test_healthy_watch_never_collects_or_debugs(self):
        self.private.mkdir()
        process = self.process()
        original_glob = Path.glob
        def glob(path, pattern):
            return [SimpleNamespace(name='37')] if path == Path('/proc') else original_glob(path, pattern)
        with patch.object(core, 'hosted'), patch.object(Path, 'glob', glob), patch.object(core, 'identity', return_value=process), patch.object(core, 'enable_core', return_value={'engine': process}), patch.object(core.time, 'sleep', side_effect=lambda _: (self.private / 'stop').touch()), patch.object(core, 'collect') as collect, patch.object(core.subprocess, 'run') as run:
            self.assertEqual(core.watch(self.args), 0)
            collect.assert_not_called(); run.assert_not_called()

    def trigger_watch(self, fatal_state, proof_failed):
        self.private.mkdir()
        process = self.process(flags=0x400408 if fatal_state else 0x400000, exit_code=11 if fatal_state else 0)
        if proof_failed: (self.output / 'proof-failed').touch()
        original_glob = Path.glob
        def glob(path, pattern):
            return [SimpleNamespace(name='37')] if path == Path('/proc') else original_glob(path, pattern)
        with patch.object(core, 'hosted'), patch.object(Path, 'glob', glob), patch.object(core, 'identity', return_value=process), patch.object(core, 'enable_core', return_value={'engine': process}), patch.object(core, 'collect') as collect:
            self.assertEqual(core.watch(self.args), 0)
            collect.assert_called_once_with(process, self.args)
        return json.loads((self.output / 'core-trigger.json').read_text())['reason']

    def test_fatal_state_triggers_once_without_waiting_for_proof_error(self):
        self.assertEqual(self.trigger_watch(True, False), 'fatal-state')

    def test_established_failure_marker_triggers_readonly_snapshot(self):
        self.assertEqual(self.trigger_watch(False, True), 'established-proof-failure')

    def test_ready_rejects_fatal_engine_before_proofs(self):
        process = self.process(flags=0x400408, exit_code=11)
        core.save(self.output / 'core-ready.json', {'engine': process})
        with patch.object(core, 'identity', return_value=process), patch.object(core.resource, 'prlimit', create=True) as limits:
            with self.assertRaises(RuntimeError): core.ready(self.args)
            limits.assert_not_called()

    def test_preparation_restores_pattern_if_output_fails(self):
        pattern, host, gdb, disk, read, set_pattern = self.policy()
        original_save = core.save
        def save(path, value):
            if path.name == 'core-policy.json': raise OSError('synthetic output failure')
            original_save(path, value)
        with host, gdb, disk, read, set_pattern, patch.object(core, 'save', side_effect=save):
            with self.assertRaises(OSError): core.prepare(self.args)
        self.assertEqual(pattern['value'], '|original-collector')
        self.assertFalse(self.private.exists())

    def test_finish_restores_policy_and_deletes_private_core(self):
        pattern, host, gdb, disk, read, set_pattern = self.policy()
        with host, gdb, disk, read, set_pattern:
            self.assertEqual(core.prepare(self.args), 0)
            self.assertNotEqual(pattern['value'], '|original-collector')
            (self.private / 'core.37').write_bytes(b'PRIVATE CORE CANARY')
            self.assertEqual(core.finish(self.args), 0)
        self.assertEqual(pattern['value'], '|original-collector')
        self.assertFalse(self.private.exists())
        receipt = json.loads((self.output / 'core-cleanup.json').read_text())
        self.assertTrue(receipt['restored'] and receipt['privateCoresDeleted'])
        self.assertFalse(any(b'PRIVATE CORE CANARY' in p.read_bytes() for p in self.output.rglob('*') if p.is_file()))

    def test_finish_still_deletes_core_if_restore_fails(self):
        pattern, host, gdb, disk, read, set_pattern = self.policy()
        with host, gdb, disk, read, set_pattern: core.prepare(self.args)
        with patch.object(core, 'hosted'), patch.object(core, 'set_pattern', side_effect=RuntimeError('restore denied')):
            with self.assertRaises(RuntimeError): core.finish(self.args)
        self.assertFalse(self.private.exists())
        self.assertFalse(json.loads((self.output / 'core-cleanup.json').read_text())['restored'])

    def test_artifact_overlap_is_rejected(self):
        self.args.private = self.output / 'private'
        with patch.object(core, 'hosted'):
            with self.assertRaises(RuntimeError): core.prepare(self.args)

    def test_cleanup_rejects_another_jobs_state(self):
        self.private.mkdir()
        core.save(self.private / 'state.json', {'private': '/other-job', 'output': str(self.output), 'uid': os.getuid()})
        with patch.object(core, 'hosted'), patch.object(core, 'set_pattern') as mutation:
            with self.assertRaises(RuntimeError): core.finish(self.args)
            mutation.assert_not_called()
        self.assertTrue(self.private.exists())

    def test_backtrace_disables_automatic_code_and_arguments(self):
        command = core.backtrace_command('/sdk/emulator/qemu', '/private/core.37')
        self.assertIn('-nx', command); self.assertIn('-nh', command)
        for setting in ('set auto-load off', 'set debuginfod enabled off', 'set print frame-arguments none', 'set print entry-values no'):
            self.assertIn(setting, command)
        self.assertIn('thread apply all bt 32', command)
        self.assertFalse(any(value in command for value in ('bt full', 'info locals', 'show environment', 'info sharedlibrary')))

    def test_only_fault_pc_mapping_survives_private_map_filter(self):
        result = core.fault_mapping_from_text('fault-pc: 0x2100\n', '1000-2000 r--p other-module\n2000-3000 rwxp anonymous-jit\n')
        self.assertEqual(result, {'faultPc': '0x2100', 'mapping': '2000-3000 rwxp anonymous-jit'})
        self.assertNotIn('other-module', json.dumps(result))

    def test_offline_backtrace_is_hard_byte_bounded(self):
        self.private.mkdir(); (self.private / 'core.37').touch()
        process = self.process()
        with patch.object(core, 'snapshot'), patch.object(core, 'identity', return_value=None), patch.object(core, 'backtrace_command', return_value=[sys.executable, '-c', 'import os; os.write(1,b"x"*(3*1024**2))']):
            core.collect(process, self.args)
        self.assertEqual((self.output / 'engine-backtrace.txt').stat().st_size, core.TEXT_BYTES)

    def test_backtrace_timeout_is_explicit_and_preserves_private_separation(self):
        self.private.mkdir(); (self.private / 'core.37').touch()
        process = self.process()
        with patch.object(core, 'snapshot'), patch.object(core, 'identity', return_value=None), patch.object(core.subprocess, 'run', side_effect=subprocess.TimeoutExpired('gdb', 30)):
            core.collect(process, self.args)
        self.assertIn('exceeded30seconds', json.loads((self.output / 'core-extraction.json').read_text())['unavailable'])
        self.assertFalse((self.output / 'core.37').exists())

    def test_no_completed_core_never_runs_gdb(self):
        self.private.mkdir(); process = self.process()
        with patch.object(core, 'snapshot'), patch.object(core, 'identity', return_value=None), patch.object(core.subprocess, 'run') as run:
            core.collect(process, self.args)
            run.assert_not_called()
        self.assertIn('no completed', json.loads((self.output / 'core-extraction.json').read_text())['unavailable'])


if __name__ == '__main__':
    unittest.main()
