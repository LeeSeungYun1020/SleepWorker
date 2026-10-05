"""Offline harness checks; none of these are provider measurements."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from phase0 import capture, command, parse_output, save


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def run_case(self, code, **kwargs):
        return capture(self.root / "case", [sys.executable, "-c", code], self.root, **kwargs)

    def test_exit_stdin_and_large_dual_streams(self):
        result = self.run_case("import sys; s=sys.stdin.read(); "
                              "sys.stdout.write(s + 'x'*200000); "
                              "sys.stderr.write('y'*200000); sys.exit(7)", stdin="hello")
        self.assertEqual(result["exitCode"], 7)
        self.assertTrue(result["outputComplete"])
        self.assertEqual((self.root / "case/stdout.log").read_bytes(), b"hello" + b"x"*200000)
        self.assertEqual((self.root / "case/stderr.log").stat().st_size, 200000)

    def test_timeout_records_signal_not_fake_exit(self):
        result = self.run_case("import time; time.sleep(30)", timeout=0.1)
        self.assertEqual(result["termination"], "timeout")
        self.assertLess(result["exitCode"], 0)
        self.assertLess(result["durationSec"], 4)

    def test_descendant_holding_pipe_is_bounded(self):
        result = self.run_case("import subprocess,sys; "
                              "subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'])",
                              timeout=0.2)
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["termination"], "timeout")
        self.assertTrue(result["outputComplete"])

    def test_late_output_preserved(self):
        result = self.run_case("import os; os.write(1,b'final\\n')")
        self.assertTrue(result["outputComplete"])
        self.assertEqual((self.root / "case/stdout.log").read_bytes(), b"final\n")

    def test_existing_evidence_is_not_overwritten(self):
        self.run_case("print('original')")
        with self.assertRaises(FileExistsError):
            self.run_case("print('replacement')")

    def test_missing_binary_has_no_fabricated_exit_code(self):
        result = capture(self.root / "case", [str(self.root / "missing")], self.root)
        self.assertIsNone(result["exitCode"])
        self.assertIn("collectorError", result)

    def test_cleanup_permission_error_still_preserves_evidence(self):
        with patch("phase0.os.killpg", side_effect=PermissionError("denied")):
            result = self.run_case("import time; time.sleep(0.2)", timeout=0.05)
        self.assertEqual(result["exitCode"], 0)
        self.assertIn("collectorError", result)
        self.assertTrue(result["cleanupErrors"])
        saved = json.loads((self.root / "case/result.json").read_text())
        self.assertEqual(saved["exitCode"], 0)

    def test_codex_finalization(self):
        folder = self.root / "case"
        folder.mkdir()
        save(folder / "result.json", dict(exitCode=0, outputComplete=True, termination=None))
        good = [{"type": "thread.started", "thread_id": "session"},
                {"type": "item.completed", "item": {"type": "agent_message", "text": "OK"}},
                {"type": "turn.completed"}]
        cases = [(good, True), (good + [{"type": "turn.failed"}], False),
                 (good[1:], False), (good[:-1], False)]
        for events, expected in cases:
            (folder / "stdout.log").write_text("\n".join(map(json.dumps, events)))
            self.assertEqual(parse_output("codex", folder)["success"], expected)
        (folder / "stdout.log").write_text('{"type":')
        self.assertFalse(parse_output("codex", folder)["success"])

    def test_resume_global_flags_precede_subcommand(self):
        argv = command("codex", "/bin/codex", Path("/a b"), "model", "id")
        self.assertLess(argv.index("-C"), argv.index("resume"))
        self.assertEqual(argv[-3:], ["resume", "id", "-"])

    def test_agy_requires_explicit_success(self):
        folder = self.root / "case"
        folder.mkdir()
        save(folder / "result.json", dict(exitCode=0, outputComplete=True, termination=None))
        for status in ("SUCCESS", "ERROR", None):
            save(folder / "stdout.log", dict(conversation_id="session", response="text", status=status))
            self.assertEqual(parse_output("agy", folder)["success"], status == "SUCCESS")


if __name__ == "__main__":
    unittest.main()
