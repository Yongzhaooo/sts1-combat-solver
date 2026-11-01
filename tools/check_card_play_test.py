"""The card-play grade must run every recorded play, not only the one card it was written for.

Ticket #8, part (ii). The seam under test is `check_card_play.main`, run as a script: give it a
directory of recordings and it grades each window that carries a play, whatever card that is.

These tests use recordings built from a real one, with the card swapped, so nothing here depends
on the engine having a particular card implemented: a swap the simulator cannot play must be
refused by name rather than silently skipped. The card that really is implemented, Neutralize, is
graded end to end against the real engine by the fixtures in `fixtures/native/THE_SILENT`.
"""
import gzip
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
TOOLS_DIR = REPO / "tools"
SOURCE = REPO / "fixtures" / "native" / "THE_SILENT" / "silent-neutralize-plain.json.gz"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def copy_window(root, case_id, card, purpose="a killing blow"):
    """One of the real recordings, renamed and re-carded, so it is a card play of something else."""
    with gzip.open(SOURCE, "rt", encoding="utf-8") as handle:
        case = json.load(handle)
    case["id"] = case_id
    case["purpose"] = purpose
    case["actions"][0]["card"] = card
    with gzip.open(Path(root) / f"{case_id}.json.gz", "wt", encoding="utf-8") as handle:
        json.dump(case, handle)


class GradesEveryCardTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = Path(self.dir.name)

    def test_a_card_the_simulator_cannot_play_is_refused_by_name(self):
        # Not skipped: a window that names a card the engine does not know is a finding, and the
        # message has to name the window and the card so the reader knows which one to fix.
        copy_window(self.root, "silent-bane-plain-kill", "Bane")
        result = subprocess.run(
            [sys.executable, str(TOOLS_DIR / "check_card_play.py"), str(self.root)],
            capture_output=True, text=True, encoding="utf-8")
        self.assertNotEqual(result.returncode, 0, "a card the engine cannot play must fail the grade")
        self.assertIn("silent-bane-plain-kill", result.stdout + result.stderr)
        self.assertIn("Bane", result.stdout + result.stderr)

    def test_the_real_card_still_grades(self):
        # The regression guard for part (i): the windows Neutralize already had must keep passing.
        result = subprocess.run(
            [sys.executable, str(TOOLS_DIR / "check_card_play.py"),
             str(REPO / "fixtures" / "native" / "THE_SILENT")],
            capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("silent-neutralize-plain-kill", result.stdout)

    def test_a_directory_with_no_card_play_is_refused(self):
        # Silence about an empty run is how a grade turns into a no-op.
        result = subprocess.run(
            [sys.executable, str(TOOLS_DIR / "check_card_play.py"), str(self.root)],
            capture_output=True, text=True, encoding="utf-8")
        self.assertNotEqual(result.returncode, 0, "grading nothing must not pass")


class NoCardNameTest(unittest.TestCase):
    def test_the_script_does_not_name_a_card(self):
        # Part (ii) in one assertion: the grade is not allowed to know which card it is grading.
        source = (TOOLS_DIR / "check_card_play.py").read_text(encoding="utf-8")
        self.assertNotIn("'Neutralize'", source)
        self.assertNotIn('"Neutralize"', source)


if __name__ == "__main__":
    unittest.main()
