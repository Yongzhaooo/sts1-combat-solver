"""Regression tests for parity_differences.py, the rule that decides which difference fails a grade.

Ticket #8: the bulk grade used to drop every path under `/legacy/`, and a monster's WEAK lives in
one of those rows, so a wrong power stack on a monster passed. The rule now opens that row and only
that row. The expected verdicts below come from the ticket, not from the implementation: it names
`monster_powers_and_intents` as the side to open, and `monster_attack_damage_and_count` and
`player_powers` as the sides to leave filtered.
"""
import importlib.util
from pathlib import Path
import unittest

TOOLS_DIR = Path(__file__).resolve().parent


def load_rule():
    spec = importlib.util.spec_from_file_location("parity_differences", TOOLS_DIR / "parity_differences.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def row(path):
    return {"path": path, "kind": "value", "original": 1, "simulator": 2}


class KeptTest(unittest.TestCase):
    def setUp(self):
        self.rule = load_rule()

    def test_monster_powers_are_graded(self):
        # The fix itself: this row must reach the grade, whatever the shape of its path.
        self.assertTrue(self.rule.kept(row("/legacy/monster_powers_and_intents")))

    def test_attack_damage_and_count_stays_filtered(self):
        # The row is rebuilt from the recorded intent, which is the CURRENT intent: a monster that
        # already acted still carries the intent it acted on, so the row restates the recording.
        self.assertFalse(self.rule.kept(row("/legacy/monster_attack_damage_and_count")))
        self.assertFalse(self.rule.kept(row("/legacy/monster_attack_damage_and_count/0")))

    def test_player_powers_stay_filtered(self):
        self.assertFalse(self.rule.kept(row("/legacy/player_powers")))

    def test_every_other_legacy_row_stays_filtered(self):
        # Anything the legacy helper adds later is filtered until someone opens it on purpose.
        self.assertFalse(self.rule.kept(row("/legacy/relic_combat_state/pen_nib")))

    def test_real_rows_are_always_graded(self):
        self.assertTrue(self.rule.kept(row("/player/hp")))
        self.assertTrue(self.rule.kept(row("/monsters/0/current_hp")))


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS_DIR / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ChecksUseTheRuleTest(unittest.TestCase):
    """Both grades must ask the shared rule, not carry their own copy of it.

    A second copy inside a check drifts the moment the rule changes: the test above would keep
    passing while the grade kept filtering the row the ticket opened.
    """

    def test_bulk_grade_asks_the_rule(self):
        source = (TOOLS_DIR / "check_coverage_gaps.py").read_text(encoding="utf-8")
        self.assertIn("parity_differences", source, "the bulk grade carries its own legacy filter")
        self.assertNotIn("startswith('/legacy/')", source, "the bulk grade still filters inline")

    def test_defect_parity_asks_the_rule(self):
        source = (TOOLS_DIR / "check_defect_parity.py").read_text(encoding="utf-8")
        self.assertIn("parity_differences", source, "the Defect check carries its own legacy filter")
        self.assertNotIn('startswith("/legacy/")', source, "the Defect check still filters inline")


if __name__ == "__main__":
    unittest.main()
