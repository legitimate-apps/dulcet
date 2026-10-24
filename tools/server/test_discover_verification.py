import json
import unittest
from discover_verification import summarize, capture
import subprocess


class DiscoverVerificationTest(unittest.TestCase):
    def test_stale_and_failure_attribution_without_payload_or_log_secrets(self):
        canary = "private-query-password-user-canary"
        response = {"subsonic-response": {"dulcetDiscover": {"stale": True, "item": [{"title": canary}]}}}
        logs = [f"unrelated password={canary}", f"INFO discover_upstream origin=musicbrainz event=http outcome=http_status status=5xx duration_ms=231 requests=1 status_5xx=1 backoff_applied=1 query={canary}", "INFO discover_upstream origin=musicbrainz event=stale_serve outcome=http_status status=none duration_ms=0 stale_serves=1"]
        result = summarize(response, logs)
        self.assertTrue(result["stale"])
        self.assertEqual(2, len(result["upstream_outcomes"]))
        self.assertEqual("http_status", result["upstream_outcomes"][-1]["outcome"])
        self.assertEqual(1, result["upstream_outcomes"][-1]["stale_serves"])
        self.assertNotIn(canary, json.dumps(result))

    def test_fresh_missing_and_untrusted_fields_are_distinct(self):
        self.assertIs(False, summarize({"stale": False}, [])["stale"])
        self.assertIsNone(summarize({"items": []}, [])["stale"])
        self.assertIsNone(summarize({"stale": "false"}, [])["stale"])
        self.assertEqual([], summarize({}, ["discover_upstream origin=private.example event=http outcome=timeout status=none duration_ms=1"])["upstream_outcomes"])

    def test_log_collection_failure_preserves_stale_and_drops_exception_details(self):
        def failed_reader():
            raise subprocess.CalledProcessError(1, "private-command-canary", output="private-output-canary")
        result = capture({"stale": True}, failed_reader)
        self.assertIs(True, result["stale"])
        self.assertIs(False, result["diagnostics_available"])
        self.assertEqual("CalledProcessError", result["diagnostics_error_type"])
        self.assertEqual([], result["upstream_outcomes"])
        self.assertNotIn("canary", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
