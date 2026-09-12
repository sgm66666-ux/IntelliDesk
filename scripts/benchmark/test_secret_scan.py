import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import secret_scan


class SecretScanTest(unittest.TestCase):

    def test_secret_candidate_fails_closed_without_echoing_value(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            scan_root = Path(temp_dir)
            secret_value = "Bearer " + ("T" * 32)
            (scan_root / "candidate.json").write_text(
                '{"Authorization":"' + secret_value + '"}',
                encoding="utf-8",
            )

            output = io.StringIO()
            with patch.object(secret_scan, "SCAN_ROOTS", [scan_root]):
                with contextlib.redirect_stdout(output):
                    exit_code = secret_scan.main()

            rendered = output.getvalue()
            self.assertEqual(1, exit_code)
            self.assertIn("potential secrets found", rendered)
            self.assertNotIn(secret_value, rendered)
            self.assertIn("[REDACTED_SECRET_CANDIDATE]", rendered)

    def test_runtime_credential_identity_without_value_passes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            scan_root = Path(temp_dir)
            (scan_root / "context.json").write_text(
                '{"credential_source":"BENCH_ACCESS_TOKEN"}',
                encoding="utf-8",
            )

            output = io.StringIO()
            with patch.object(secret_scan, "SCAN_ROOTS", [scan_root]):
                with contextlib.redirect_stdout(output):
                    exit_code = secret_scan.main()

            self.assertEqual(0, exit_code)
            self.assertIn("no persisted secrets found", output.getvalue())


if __name__ == "__main__":
    unittest.main()
