from pathlib import Path
import runpy
import unittest

guard = runpy.run_path(str(Path(__file__).with_name("verify-github.py")))["publication_guard"]


cleanup_helpers = runpy.run_path(str(Path(__file__).with_name("cleanup-github-test.py")))
is_valid_test_branch = cleanup_helpers["is_valid_test_branch"]
is_valid_test_pr_title = cleanup_helpers["is_valid_test_pr_title"]


class PublicationGuardTests(unittest.TestCase):
    def test_only_exact_clean_approved_commit_passes(self):
        self.assertTrue(guard("APPROVED\nhead\nbase\nreason", ["head", "base"], "head", "base", ""))
        for review, target, head, base, dirty in [
            ("NOT_APPROVED\nhead\nbase", ["head", "base"], "head", "base", ""),
            ("APPROVED\nhead\nbase", ["head", "base"], "newhead", "base", ""),
            ("APPROVED\nhead\nbase", ["head", "base"], "head", "newbase", ""),
            ("APPROVED\nhead\nbase", ["head", "base"], "head", "base", "?? untracked"),
            ("APPROVED\nhead", ["head", "base"], "head", "base", ""),
            ("APPROVED\nstale\nbase", ["head", "base"], "head", "base", ""),
        ]:
            with self.subTest(review=review, head=head, base=base, dirty=dirty):
                self.assertFalse(guard(review, target, head, base, dirty))

    def test_cleanup_branch_and_title_support_sleepworker_and_legacy_aiflow(self):
        self.assertTrue(is_valid_test_branch("ai/sleepworker-phase0-1234abcd"))
        self.assertTrue(is_valid_test_branch("ai/aiflow-phase0-1234abcd"))
        self.assertFalse(is_valid_test_branch("main"))
        self.assertFalse(is_valid_test_branch("feature/unrelated"))
        self.assertFalse(is_valid_test_branch("ai/production-worktree"))

        self.assertTrue(is_valid_test_pr_title("[TEST][DO NOT MERGE] SleepWorker Phase 0 GitHub contract"))
        self.assertTrue(is_valid_test_pr_title("[TEST][DO NOT MERGE] aiflow Phase 0 GitHub contract"))
        self.assertFalse(is_valid_test_pr_title("feat: implement Phase 5"))
        self.assertFalse(is_valid_test_pr_title("[TEST] Other PR"))


if __name__ == "__main__":
    unittest.main()
