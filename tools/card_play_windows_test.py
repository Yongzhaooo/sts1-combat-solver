"""The card-play grade must pick its windows from the fixtures, not from a table in the script.

Ticket #8, part (ii). The seam under test is `card_play_windows.discover`: give it a directory of
recordings and it returns the windows a card play can be graded from, with the card and hand slot
each one's own actions name. Nothing here imports the engine or plays a card, so this holds the
selection rule alone.

Expected values come from the ticket: any card is graded, a window with no play is not, a window
states its own purpose, and a window that states nothing is reported rather than silently dropped.
"""
import gzip
import importlib.util
import json
import tempfile
from pathlib import Path
import unittest


TOOLS_DIR = Path(__file__).resolve().parent


def discover_module():
    spec = importlib.util.spec_from_file_location(
        "card_play_windows", TOOLS_DIR / "card_play_windows.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def recording(case_id, card, hand, purpose="a killing blow", play=True):
    return {
        "schema": "sts1-calibration/1",
        "id": case_id,
        "character": "THE_SILENT",
        "purpose": purpose,
        "actions": ([{"kind": "play", "hand": hand, "card": card, "target": 0}, {"kind": "end"}]
                    if play else [{"kind": "end"}]),
        "checkpoints": [{"observation": {}}, {"observation": {}}],
    }


class DiscoverTest(unittest.TestCase):
    def setUp(self):
        self.module = discover_module()
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = Path(self.dir.name)

    def write(self, case):
        with gzip.open(self.root / f"{case['id']}.json.gz", "wt", encoding="utf-8") as handle:
            json.dump(case, handle)

    def windows(self, targets=None):
        return self.module.discover(targets or [self.root])[0]

    def test_a_play_of_any_card_is_discovered(self):
        # Bane, not Neutralize: the script must not need to know the card's name.
        self.write(recording("silent-bane-plain-kill", "Bane", 1))
        self.assertEqual([w.card for w in self.windows()], ["Bane"])

    def test_the_hand_slot_comes_from_the_recording(self):
        # The recorded action says which slot it played; the script must not search the hand for
        # a card it was told to look for.
        self.write(recording("silent-bane-plain-kill", "Bane", 3))
        self.assertEqual([w.slot for w in self.windows()], [3])

    def test_the_purpose_comes_from_the_recording(self):
        self.write(recording("silent-bane-plain-kill", "Bane", 0,
                             purpose="several monsters with real intents"))
        self.assertEqual([w.purpose for w in self.windows()],
                         ["several monsters with real intents"])

    def test_a_window_with_no_play_is_left_out(self):
        self.write(recording("silent-bane-plain-kill", "Bane", 0, play=False))
        self.assertEqual(self.windows(), [])

    def test_a_window_that_states_no_purpose_is_reported_not_dropped(self):
        # The entry ticket: a card play nobody accounted for must come back as a finding, because
        # a window silently left out of the grade is how the grade turns into a no-op.
        self.write(recording("defect-a0-00-b0", "Strike", 0, purpose=""))
        windows, undeclared = self.module.discover([self.root])
        self.assertEqual(windows, [])
        self.assertEqual(undeclared, ["defect-a0-00-b0"])

    def test_a_window_with_no_play_is_not_reported_as_undeclared(self):
        # It is not missing a purpose; there is nothing to grade. Reporting it would be noise.
        self.write(recording("silent-bane-plain-kill", "Bane", 0, purpose="", play=False))
        self.assertEqual(self.module.discover([self.root]), ([], []))

    def test_windows_come_back_in_a_stable_order(self):
        # Two recordings in one directory must grade in a fixed order, or the run is not repeatable.
        self.write(recording("silent-bane-plain-kill", "Bane", 0))
        self.write(recording("silent-bane-plain", "Bane", 0))
        self.assertEqual([w.case["id"] for w in self.windows()],
                         ["silent-bane-plain", "silent-bane-plain-kill"])

    def test_the_path_travels_with_the_window(self):
        # The failure message names the file that failed, so the path has to survive discovery.
        self.write(recording("silent-bane-plain-kill", "Bane", 0))
        self.assertEqual(self.windows()[0].path, self.root / "silent-bane-plain-kill.json.gz")

    def test_a_declared_window_is_not_also_reported_as_undeclared(self):
        # The two lists must partition the card plays, not overlap.
        self.write(recording("silent-bane-plain-kill", "Bane", 0))
        windows, undeclared = self.module.discover([self.root])
        self.assertEqual((len(windows), undeclared), (1, []))


if __name__ == "__main__":
    unittest.main()
