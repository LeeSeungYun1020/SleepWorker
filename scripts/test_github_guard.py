from pathlib import Path
import runpy
import unittest

guard = runpy.run_path(str(Path(__file__).with_name("verify-github.py")))["publication_guard"]


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


if __name__ == "__main__":
    unittest.main()
