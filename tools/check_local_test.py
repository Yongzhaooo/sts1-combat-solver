"""Regression tests for check_local.py's skip detection.

A step that exits 0 and compares nothing used to count as a pass, which made
"all green" and "one check was asleep" look identical. `skipped_work()` is what
tells them apart, so it is pinned here: a real comparison is not a skip, no
comparison is, a failing run is, and no other step can be mistaken for one.
"""
import importlib.util
from pathlib import Path
import unittest

TOOLS_DIR = Path(__file__).resolve().parent


def load_check_local():
    spec = importlib.util.spec_from_file_location("check_local", TOOLS_DIR / "check_local.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CARD_COSTS = "python tools/check_card_costs.py --allow-skip"


class SkippedWorkTest(unittest.TestCase):
    def setUp(self):
        self.check_local = load_check_local()

    def test_a_comparison_is_not_a_skip(self):
        output = ("SKIP: no class directory for com.megacrit.cardcrawl.cards.curse\n"
                  "PASS: 201 implemented card costs match the game bytecode (blue, red)")
        self.assertFalse(self.check_local.skipped_work(CARD_COSTS, output))

    def test_a_run_that_compared_nothing_is_a_skip(self):
        output = ("SKIP: no --classes directory and no STS_GAME_CLASSES set, so no card "
                  "cost was compared against the game")
        self.assertTrue(self.check_local.skipped_work(CARD_COSTS, output))

    def test_a_failing_run_is_a_skip_too(self):
        # Non-zero already fails the step; marking it a skip keeps the summary from
        # calling it a pass, and keeps it off the failure list twice.
        output = "SKIP: no --classes\nFAIL: no card cost was compared against the game"
        self.assertTrue(self.check_local.skipped_work(CARD_COSTS, output))

    def test_another_step_is_never_a_skip(self):
        self.assertFalse(self.check_local.skipped_work("python tools/check_line_endings.py", "SKIP: x"))
        self.assertFalse(self.check_local.skipped_work("python tools/check_solver_runtime.py", ""))


if __name__ == "__main__":
    unittest.main()
