#!/usr/bin/env python3
"""The rootless proof runner embeds only the fresh CA and restores test sources on failure."""
from importlib.machinery import SourceFileLoader
from pathlib import Path
import tempfile
import json
import subprocess
import runpy
import os
import sys
from unittest.mock import patch
import unittest
import xml.etree.ElementTree as ET

RUNNER = SourceFileLoader('connection_android_runner', str(Path(__file__).with_name('run-connection-android-proofs'))).load_module()


class ConnectionAndroidTrustTest(unittest.TestCase):
    def check_cleanup(self, fail):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            resources = root/'core/src/androidDeviceTest/res'
            config = resources/'xml/connection_fixture_network_security.xml'
            config.parent.mkdir(parents=True)
            original = b'<network-security-config><base-config><trust-anchors><certificates src="system"/><certificates src="user"/></trust-anchors></base-config></network-security-config>'
            config.write_bytes(original)
            ca = root/'fresh-fixture.pem'; ca.write_bytes(b'fresh fixture certificate')
            previous = RUNNER.REPO
            RUNNER.REPO = root
            try:
                try:
                    with RUNNER.fixture_apk_trust(ca):
                        sources = [node.get('src') for node in ET.fromstring(config.read_bytes()).iter('certificates')]
                        self.assertEqual(['system', '@raw/connection_fixture_ca'], sources)
                        self.assertEqual(ca.read_bytes(), (resources/'raw/connection_fixture_ca.pem').read_bytes())
                        if fail: raise RuntimeError('instrumentation failure')
                except RuntimeError:
                    if not fail: raise
                self.assertEqual(original, config.read_bytes())
                self.assertFalse((resources/'raw/connection_fixture_ca.pem').exists())
            finally: RUNNER.REPO = previous

    def test_exact_fresh_test_apk_root_and_success_cleanup(self): self.check_cleanup(False)
    def test_failed_instrumentation_restores_test_sources(self): self.check_cleanup(True)


class ConnectionApplicationTrustTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.enterContext(patch.object(RUNNER, 'REPO', self.root))
        self.ca = self.root/'fresh.pem'; self.ca.write_bytes(b'exact fresh fixture CA bytes')

    def test_exact_root_is_localhost_debug_only_and_cleanup_survives_failure_and_cancellation(self):
        for module in ('app', 'tv'):
            for failure in (None, RuntimeError('native failure'), SystemExit(143)):
                with self.subTest(module=module, failure=type(failure).__name__):
                    main = self.root/'android'/module/'src/main/AndroidManifest.xml'
                    main.parent.mkdir(parents=True, exist_ok=True)
                    main.write_bytes(b'unchanged production manifest')
                    debug = main.parent.parent/'debug'
                    try:
                        with RUNNER.fixture_app_trust(self.ca, module):
                            config = ET.fromstring((debug/'res/xml/connection_controls_network_security.xml').read_bytes())
                            self.assertEqual([node.get('src') for node in config.findall('./base-config/trust-anchors/certificates')], ['system'])
                            self.assertEqual(config.find('./domain-config/domain').text, 'localhost')
                            self.assertEqual(config.find('./domain-config/domain').get('includeSubdomains'), 'false')
                            self.assertEqual([node.get('src') for node in config.findall('./domain-config/trust-anchors/certificates')],
                                             ['system', '@raw/connection_controls_ca'])
                            self.assertEqual((debug/'res/raw/connection_controls_ca.pem').read_bytes(), self.ca.read_bytes())
                            self.assertNotIn(b'user', (debug/'res/xml/connection_controls_network_security.xml').read_bytes())
                            self.assertEqual(main.read_bytes(), b'unchanged production manifest')
                            if failure: raise failure
                    except BaseException as observed:
                        self.assertIs(observed, failure)
                    self.assertFalse(debug.exists())
                    self.assertEqual(main.read_bytes(), b'unchanged production manifest')

    def test_existing_generated_owner_is_refused_before_any_other_source_changes(self):
        for relative in ('AndroidManifest.xml', 'res/xml/connection_controls_network_security.xml', 'res/raw/connection_controls_ca.pem'):
            with self.subTest(relative=relative):
                debug = self.root/'android/app/src/debug'
                owned = debug/relative; owned.parent.mkdir(parents=True, exist_ok=True)
                owned.write_bytes(b'existing owner')
                before = {path.relative_to(debug): path.read_bytes() for path in debug.rglob('*') if path.is_file()}
                with self.assertRaisesRegex(RuntimeError, 'existing owner'):
                    with RUNNER.fixture_app_trust(self.ca, 'app'): self.fail('collision admitted')
                after = {path.relative_to(debug): path.read_bytes() for path in debug.rglob('*') if path.is_file()}
                self.assertEqual(after, before)
                owned.unlink()

    def test_only_the_two_application_modules_can_receive_debug_trust(self):
        with self.assertRaises(ValueError):
            with RUNNER.fixture_app_trust(self.ca, '../release'): self.fail('arbitrary path admitted')

    def test_exact_results_reject_zero_missing_duplicate_failed_skipped_and_nonzero_exit(self):
        results = self.root/'results'; results.mkdir()
        path = results/'TEST-controls.xml'
        cls = 'FixtureControls'
        methods = RUNNER.CONTROLS_METHODS
        def write(names, terminal=''):
            path.write_text('<testsuite>'+''.join(f'<testcase classname="{cls}" name="{name}">{terminal}</testcase>' for name in names)+'</testsuite>')
        write(sorted(methods))
        self.assertEqual(RUNNER.require_results(results, cls, methods, 0), 2)
        for names, terminal, code in [([], '', 0), ([sorted(methods)[0]], '', 0),
            ([*sorted(methods), sorted(methods)[0]], '', 0), (sorted(methods), '<failure/>', 0),
            (sorted(methods), '<skipped/>', 0), (sorted(methods), '', 1)]:
            write(names, terminal)
            with self.assertRaises(RuntimeError): RUNNER.require_results(results, cls, methods, code)

    def test_transport_failure_still_executes_controls_and_each_suite_keeps_the_gate_red(self):
        for native_failed, controls_failed in [(True, False), (False, True), (True, True), (False, False)]:
            with self.subTest(native_failed=native_failed, controls_failed=controls_failed), \
                 patch.object(RUNNER, 'require_results', side_effect=RuntimeError('transport') if native_failed else None, return_value=6), \
                 patch.object(RUNNER, 'execute_controls', side_effect=RuntimeError('controls') if controls_failed else None) as controls:
                def finish():
                    RUNNER.finish_native_proofs(True, ['adb'], 'emulator-owned', {}, self.ca, self.root,
                                               RUNNER.PHASE2_METHODS, 1 if native_failed else 0)
                if native_failed or controls_failed:
                    with self.assertRaises(RuntimeError): finish()
                else: finish()
                controls.assert_called_once()

    def test_phone_and_tv_select_exact_native_methods_and_preserve_the_validated_manifest(self):
        for tv in (False, True):
            with self.subTest(tv=tv):
                module = 'tv' if tv else 'app'
                class_name = ('com.legitimateapps.dulcet.tv.AndroidTvEmulatorAccountConnectProofTest' if tv else
                              'com.legitimateapps.dulcet.AndroidEmulatorAccountConnectProofTest')
                evidence = self.root/('evidence-tv' if tv else 'evidence-phone'); evidence.mkdir()
                manifest = {'standard': 'dulcet-multipath-fixture', 'nonce': '11111111-2222-4333-8444-555555555555'}
                def command(argv, **kwargs):
                    self.assertEqual(Path(argv[0]).name, 'run-gradle-exclusive')
                    self.assertIn('--max-workers=2', argv)
                    self.assertIn(f':android:{module}:connected'+('Debug' if tv else 'DevDebug')+'AndroidTest', argv)
                    selected = next(value for value in argv if value.startswith('-Pandroid.testInstrumentationRunnerArguments.class='))
                    self.assertEqual(set(selected.split('=', 1)[1].split(',')), {class_name+'#'+name for name in RUNNER.CONTROLS_METHODS})
                    passed_manifest = next(value for value in argv if value.startswith('-Pandroid.testInstrumentationRunnerArguments.DULCET_MULTIPATH_MANIFEST_JSON='))
                    self.assertEqual(json.loads(passed_manifest.split('=', 1)[1]), manifest)
                    results = self.root/'android'/module/'build/outputs/androidTest-results/connected'
                    results.mkdir(parents=True)
                    (results/'TEST-controls.xml').write_text('<testsuite>'+''.join(
                        f'<testcase classname="{class_name}" name="{name}"/>' for name in RUNNER.CONTROLS_METHODS)+'</testsuite>')
                    return subprocess.CompletedProcess(argv, 0)
                features = 'feature:android.software.leanback\n' if tv else 'feature:android.hardware.touchscreen\n'
                with patch.object(RUNNER.subprocess, 'check_output', return_value=features), \
                     patch.object(RUNNER.subprocess, 'run', side_effect=command), \
                     patch.object(RUNNER, 'export_controls_captures', return_value=7):
                    RUNNER.execute_controls(['adb', '-s', 'emulator-owned'], 'emulator-owned', manifest, self.ca, evidence)
                self.assertTrue((evidence/'results/controls/TEST-controls.xml').is_file())

    def test_concrete_debug_app_and_instrumentation_apks_build_without_starting_runtime(self):
        for tv in (False, True):
            for failed in (False, True):
                with self.subTest(tv=tv, failed=failed):
                    features = 'feature:android.software.leanback\n' if tv else ''
                    failure = subprocess.CalledProcessError(1, 'mock APK build')
                    with patch.object(RUNNER.subprocess, 'check_output', return_value=features), \
                         patch.object(RUNNER, 'run', side_effect=failure if failed else None) as command:
                        if failed:
                            with self.assertRaises(subprocess.CalledProcessError): RUNNER.compile_controls(['adb'], 'emulator-owned', self.ca)
                        else: RUNNER.compile_controls(['adb'], 'emulator-owned', self.ca)
                    argv = command.call_args.args[0]
                    module, variant = ('tv', 'Debug') if tv else ('app', 'DevDebug')
                    self.assertEqual([arg for arg in argv if arg.startswith(':')],
                        [f':android:{module}:assemble{variant}', f':android:{module}:assemble{variant}AndroidTest'])
                    self.assertFalse((self.root/'android'/module/'src/debug').exists())

    def test_application_trust_is_cleaned_and_captures_are_attempted_on_failed_native_command(self):
        evidence = self.root/'evidence'; evidence.mkdir()
        with patch.object(RUNNER.subprocess, 'check_output', return_value=''), \
             patch.object(RUNNER.subprocess, 'run', side_effect=RuntimeError('native command failed')), \
             patch.object(RUNNER, 'export_controls_captures', return_value=0) as capture:
            with self.assertRaisesRegex(RuntimeError, 'native command failed'):
                RUNNER.execute_controls(['adb'], 'emulator-owned', {'nonce': '11111111-2222-4333-8444-555555555555'}, self.ca, evidence)
        capture.assert_called_once()
        self.assertFalse((self.root/'android/app/src/debug').exists())

    def test_ordinary_rest_runner_excludes_exact_fixture_methods_without_skipping_the_class(self):
        captures = self.root/'listen-now-fallback-shots'; captures.mkdir()
        for number in range(3): (captures/f'listen-now-{number}.jpg').write_bytes(b'fixture photo marker')
        script = Path(__file__).with_name('run-android-rest-proofs')
        for surface in ('phone', 'tv'):
            with patch.dict(os.environ, RUNNER_TEMP=str(self.root)), \
                 patch.object(sys, 'argv', [str(script), ':fixture:connectedTest', surface]), \
                 patch.object(subprocess, 'run') as command:
                runpy.run_path(str(script), run_name='__main__')
            argv = command.call_args_list[0].args[0]
            exclusions = next(item for item in argv if item.startswith('-Pandroid.testInstrumentationRunnerArguments.notClass='))
            excluded = set(exclusions.split('=', 1)[1].split(','))
            class_name = ('com.legitimateapps.dulcet.tv.AndroidTvEmulatorAccountConnectProofTest' if surface == 'tv' else
                          'com.legitimateapps.dulcet.AndroidEmulatorAccountConnectProofTest')
            self.assertTrue({class_name+'#'+method for method in RUNNER.CONTROLS_METHODS}.issubset(excluded))
            self.assertNotIn(class_name, excluded)

    def test_capture_names_cannot_escape_the_owned_evidence_directory(self):
        response = subprocess.CompletedProcess(['adb'], 0, stdout=b'../escape.jpg\n')
        with patch.object(RUNNER.subprocess, 'run', return_value=response):
            with self.assertRaisesRegex(RuntimeError, 'unexpected controls capture name'):
                RUNNER.export_controls_captures(['adb'], 'fixture.package', self.root, '11111111-2222-4333-8444-555555555555')


if __name__ == '__main__': unittest.main()
