"""Model the pinned emulator action: each physical script line gets a fresh sh -c."""
import os
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/android-test-soak.yml'


def script_lines(source):
    match = re.search(r'^          script: \|\n((?:            .*\n)+)', source, re.M)
    if not match:
        raise AssertionError('expected exactly one literal emulator action script')
    return [line[12:] for line in match[1].splitlines() if line.strip()]


def execute_like_pinned_action(source, proof_exit):
    # ReactiveCircus/android-emulator-runner a421e438... main.ts executes every
    # parseScript line independently with exec.exec('sh', ['-c', script]).
    # Ubuntu24.04 /bin/sh is dash; use dash explicitly on other hosts too.
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        (root / 'tools/ci').mkdir(parents=True)
        (root / 'engine-diagnostic/core-text').mkdir(parents=True)
        fixture = root / 'tools/ci/with-discover-wishlist-fixture'
        fixture.write_text('#!/bin/sh\nexit ' + str(proof_exit) + '\n')
        fixture.chmod(0o755)
        environment = dict(os.environ, RUNNER_TEMP=temporary, ANDROID_HOME='/sdk', EMULATOR_API_LEVEL='34',
                           EMULATOR_TARGET='google_apis', EMULATOR_ARCH='x86_64', EMULATOR_PIN='{}', ENGINE_GPU_MODE='software')
        for line in script_lines(source):
            # Fixed metadata commands are outside this exit-status composition control.
            result = subprocess.run(['dash', '-c', 'python3() { return 0; }; ' + line], cwd=root,
                                    env=environment, capture_output=True, text=True)
            if result.returncode:
                break
        return result.returncode, (root / 'engine-diagnostic/core-text/proof-failed').exists(), result.stderr


class ActionShellCompositionTest(unittest.TestCase):
    def test_previous_multiline_status_control_reproduces_actual_exit_two(self):
        old = subprocess.check_output(['git', 'show', 'e5dedc1ff61fed0f643bb310cd7167f3814cb009:' + WORKFLOW], cwd=REPO, text=True)
        status, marker, stderr = execute_like_pinned_action(old, 0)
        self.assertEqual(status, 2)
        self.assertIn('Illegal number', stderr)
        self.assertFalse(marker)

    def test_success_stays_zero_across_real_per_line_shell_execution(self):
        status, marker, _ = execute_like_pinned_action((REPO / WORKFLOW).read_text(), 0)
        self.assertEqual(status, 0)
        self.assertFalse(marker)

    def test_failure_keeps_exact_status_and_failure_marker(self):
        status, marker, _ = execute_like_pinned_action((REPO / WORKFLOW).read_text(), 17)
        self.assertEqual(status, 17)
        self.assertTrue(marker)

    def renderer_receipt(self, surface_output):
        source = (REPO / WORKFLOW).read_text()
        line = next(line for line in script_lines(source) if 'SurfaceFlinger' in line)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'engine-diagnostic').mkdir()
            adb = root / 'adb'
            adb.write_text('#!/bin/sh\ncat <<\'RECEIPT\'\n' + surface_output + '\nRECEIPT\n')
            adb.chmod(0o755)
            environment = dict(os.environ, RUNNER_TEMP=temporary, ENGINE_GPU_MODE='software', PATH=temporary + os.pathsep + os.environ['PATH'])
            result = subprocess.run(['dash', '-c', line], env=environment, capture_output=True, text=True)
            receipt = root / 'engine-diagnostic/renderer.json'
            return result.returncode, json.loads(receipt.read_text()) if receipt.exists() else None

    def test_renderer_metadata_retains_actual_gles_without_unrelated_dump_lines(self):
        status, receipt = self.renderer_receipt('non-renderer canary\nGLES: actual driver and version\nother private canary')
        self.assertEqual(status, 0)
        self.assertEqual(receipt['requestedMode'], 'software')
        self.assertEqual(receipt['actualGles'], ['GLES: actual driver and version'])
        self.assertNotIn('canary', json.dumps(receipt))

    def test_missing_actual_renderer_receipt_blocks_before_proofs(self):
        status, receipt = self.renderer_receipt('no actual renderer field')
        self.assertNotEqual(status, 0)
        self.assertIsNone(receipt)


if __name__ == '__main__':
    unittest.main()
