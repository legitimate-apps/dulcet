"""Discover uses the shared hosted startup gate and retains pressure on failed proofs."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parent


class HostedAdmissionTests(unittest.TestCase):
    def test_template_matches_selected_sdk_and_requested_device(self):
        from discover_hosted_admission import hosted_simulator_template
        runtime = 'com.apple.CoreSimulator.SimRuntime.iOS-27-0'
        phone = {'name': 'iPhone 18 Pro', 'udid': 'PHONE', 'isAvailable': True,
                 'deviceTypeIdentifier': 'com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro'}
        inventory = {'devices': {runtime: [phone],
            'com.apple.CoreSimulator.SimRuntime.iOS-27-1': [{**phone, 'udid': 'NEWER'}]}}
        for version in ('27.0', runtime):
            self.assertEqual((runtime, phone), hosted_simulator_template(inventory, 'iPhone 18 Pro', version))
        self.assertEqual('NEWER', hosted_simulator_template(inventory, 'iPhone 18 Pro', '27.1')[1]['udid'])
        for version, name in [('27.2', 'iPhone 18 Pro'), ('27.0', 'iPad Pro')]:
            with self.assertRaises(RuntimeError):
                hosted_simulator_template(inventory, name, version)
        for unusable in ({**phone, 'isAvailable': False}, {**phone, 'deviceTypeIdentifier': None}):
            with self.assertRaises(RuntimeError):
                hosted_simulator_template({'devices': {runtime: [unusable]}}, 'iPhone 18 Pro', '27.0')

    def admission(self, owner, output):
        path = HERE / 'discover_hosted_admission.py'
        self.assertTrue(path.exists(), 'Discover hosted proofs have no pressure admission or diagnostics')
        spec = importlib.util.spec_from_file_location('discover_hosted_admission', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.HostedAppleAdmission(owner, HERE, output, 'ipad')

    def test_sampler_is_owned_and_its_raw_record_is_in_the_proof_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            owner = Mock()
            gate = self.admission(owner, Path(directory))
            gate.start()
            command = owner.spawn.call_args.args[0]
            self.assertEqual(str(HERE / 'host-pressure'), command[1])
            self.assertEqual(['sample', '--output', str(Path(directory) / 'host-pressure.jsonl'), '--interval', '5'], command[2:])
            self.assertEqual('mark', owner.run.call_args.args[0][2])

    def test_shared_gate_warms_the_built_shell_on_the_exact_proof_device(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            app = output / 'Dulcet DEV.app'
            app.mkdir()
            owner = Mock()
            gate = self.admission(owner, output)
            gate.admit('PROOF-UDID', app)
            command = owner.run.call_args.args[0]
            self.assertEqual(str(HERE / 'isolate-simulator'), command[1])
            self.assertEqual(['--phase', 'discover-ipad-first-launch', '--keep', 'PROOF-UDID',
                              '--require-isolation', '--settle', '--settle-pressure',
                              '--warmup-app', str(app)], command[2:])
            self.assertEqual(str(output / 'host-pressure.jsonl'), owner.run.call_args.kwargs['env']['DULCET_HOST_PRESSURE_LOG'])
            self.assertTrue(owner.run.call_args.kwargs['check'])

    def test_missing_built_shell_cannot_silently_skip_warmup(self):
        with tempfile.TemporaryDirectory() as directory:
            owner = Mock()
            gate = self.admission(owner, Path(directory))
            with self.assertRaises(FileNotFoundError):
                gate.admit('PROOF-UDID', Path(directory) / 'missing.app')
            owner.run.assert_not_called()

    def test_failed_pressure_admission_is_fatal(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / 'built.app'
            app.mkdir()
            owner = Mock()
            owner.run.side_effect = subprocess.CalledProcessError(1, 'isolate-simulator')
            gate = self.admission(owner, Path(directory))
            with self.assertRaises(subprocess.CalledProcessError):
                gate.admit('PROOF-UDID', app)

    def test_cleanup_stops_stack_workers_and_preserves_printed_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            owner = Mock()
            gate = self.admission(owner, Path(directory))
            with patch('subprocess.run') as run:
                gate.close()
            self.assertEqual(['stop', 'summarize'], [call.args[0][2] for call in run.call_args_list])
            self.assertTrue(all(call.kwargs['timeout'] <= 60 for call in run.call_args_list))


if __name__ == '__main__':
    unittest.main()
