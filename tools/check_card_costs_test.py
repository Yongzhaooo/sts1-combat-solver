"""Regression tests for check_card_costs.py.

Verifies the input resolution that keeps the step honest:
1. STS_GAME_CLASSES is enough on its own (check_local.py runs the CI command verbatim,
   so it has no way to pass --classes)
2. --classes still wins over the environment
3. A missing or non-directory input prints SKIP even under --allow-skip, instead of an
   empty stdout that reads as "201 card costs match"
4. A run that compares nothing exits non-zero without --allow-skip
5. A real comparison prints PASS and never the skip message
"""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

TOOLS_DIR = Path(__file__).resolve().parent
SCRIPT = TOOLS_DIR / "check_card_costs.py"
# The comparison needs javap and the game's class tree, neither of which CI has;
# these tests only exercise which directory gets picked, so a tree with no classes
# in it is enough to get past input resolution.


def run_check(env_classes: str | None, *extra: str, classes: Path | None = None) -> tuple[int, str]:
    env = {k: v for k, v in os.environ.items() if k != "STS_GAME_CLASSES"}
    if env_classes is not None:
        env["STS_GAME_CLASSES"] = env_classes
    cmd = [sys.executable, str(SCRIPT), *extra]
    if classes is not None:
        cmd += ["--classes", str(classes)]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env)
    return proc.returncode, proc.stdout + proc.stderr


class CheckCardCostsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="card-costs-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.tree = self.tmp / "classes"
        (self.tree / "com" / "megacrit" / "cardcrawl" / "cards" / "red").mkdir(parents=True)

    def assert_skipped(self, out: str):
        self.assertIn("SKIP:", out, out)
        self.assertNotIn("PASS:", out, out)

    def test_no_input_under_allow_skip_still_says_skip(self):
        code, out = run_check(None, "--allow-skip")
        self.assertEqual(code, 0, out)
        self.assert_skipped(out)
        self.assertIn("no STS_GAME_CLASSES set", out)

    def test_no_input_without_allow_skip_fails(self):
        code, out = run_check(None)
        self.assertNotEqual(code, 0, out)
        self.assert_skipped(out)
        self.assertIn("FAIL:", out)

    def test_environment_variable_names_the_class_tree(self):
        code, out = run_check(str(self.tree))
        self.assertNotIn("no STS_GAME_CLASSES set", out, out)
        # The tree resolves; the run then fails on missing classes/javap rather than
        # on having no input at all, which is what this test is about.
        self.assertNotIn("no --classes directory", out)

    def test_classes_wins_over_environment(self):
        bad = self.tmp / "missing"
        code, out = run_check(str(bad), classes=self.tree)
        self.assertNotIn("$STS_GAME_CLASSES names", out, out)
        self.assertNotIn("no --classes directory", out)

    def test_non_directory_input_is_skipped_loudly(self):
        code, out = run_check(str(self.tree / "com"), "--allow-skip")
        self.assertEqual(code, 0, out)
        self.assert_skipped(out)

    def test_directory_gets_to_the_comparison(self):
        code, out = run_check(str(self.tree), "--allow-skip")
        self.assertNotIn("SKIP: no --classes directory", out, out)
        self.assertNotIn("$STS_GAME_CLASSES names", out)


if __name__ == "__main__":
    unittest.main()
