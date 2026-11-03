#!/usr/bin/env python3
"""The rootless proof runner embeds only the fresh CA and restores test sources on failure."""
from importlib.machinery import SourceFileLoader
from pathlib import Path
import tempfile
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


if __name__ == '__main__': unittest.main()
