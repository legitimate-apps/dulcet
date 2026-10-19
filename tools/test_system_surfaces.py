#!/usr/bin/env python3
"""Controls for managed CarPlay activation without Apple account or profile data."""
from pathlib import Path
import plistlib
import runpy
import unittest

ROOT = Path(__file__).resolve().parent.parent
select = runpy.run_path(str(ROOT / "tools/release/carplay-entitlements"))["select"]

class CarPlayProvisioningTests(unittest.TestCase):
    def profile(self, channel="dev", audio=True):
        return {"Name": "Host profile", "Entitlements": {
            "application-identifier": "3LTL47SJ8C.com.legitimateapps.dulcet" + (".dev" if channel == "dev" else ""),
            "com.apple.developer.carplay-audio": audio,
        }}
    def test_pregrant_device_archive_omits_audio(self):
        path = select("Host profile", "dev", "ios", [self.profile(audio=False)])
        self.assertNotIn("com.apple.developer.carplay-audio", plistlib.loads((ROOT / "apple" / path).read_bytes()))
    def test_refreshing_selected_profile_activates_next_archive(self):
        for channel in ("dev", "prod"):
            path = select("Host profile", channel, "ios", [self.profile(channel)])
            entitlements = plistlib.loads((ROOT / "apple" / path).read_bytes())
            self.assertTrue(entitlements["com.apple.developer.carplay-audio"])
            self.assertEqual(entitlements["com.apple.security.application-groups"], ["group.com.legitimateapps.dulcet" + (".dev" if channel == "dev" else "")])
    def test_unrelated_profile_and_other_platforms_cannot_enable_audio(self):
        for profile, channel, platform in ((self.profile(), "prod", "ios"), (self.profile(), "dev", "macos"), (self.profile(), "dev", "tvos")):
            self.assertTrue(select("Host profile", channel, platform, [profile]).endswith("-ios.entitlements"))
        self.assertTrue(select("Another profile", "dev", "ios", [self.profile()]).endswith("-ios.entitlements"))
    def test_unknown_channel_is_rejected(self):
        with self.assertRaises(ValueError): select("Host profile", "unknown", "ios", [])

if __name__ == "__main__": unittest.main()
