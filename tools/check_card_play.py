"""Replay the card play in every recorded window and hold the simulator to the board that follows.

`tools/check_coverage_gaps.py` grades a captured window by diffing every field the comparator can
see, but it keeps one deliberate blind spot: paths under `/legacy/` are filtered out, because the
legacy power and intent helper re-reads move data the recording never captured. That is the right
call for a bulk smoke check and the wrong call for a card: Weak *is* a monster power, so a window
that shows the wrong Weak stack still passes the bulk grade.

The bulk grade cannot close that gap on its own, and no change to its filter can, because it never
plays a card: it imports the recorded board between two checkpoints and diffs them, so the powers
it reports are the ones the recording already carries. Only a script that takes the opening board,
replays the play, and diffs the result against the next recorded board sees what the card applied.

This script does that for every window it finds, whatever card the window plays:

* every checkpoint must still be a zero-difference match under the bulk grade, so this cannot pass
  while `check_coverage_gaps.py` fails;
* the play the recording took is replayed in the simulator and the board it lands on is held
  against the *unfiltered* comparator, `/legacy/` rows included, so the powers the card applies
  cannot hide behind them;
* the intent the board announces must be a real one, never the DEBUG placeholder a hand-built
  capture carries;
* the opening checkpoint must be the recorded board, not the simulator's guess at it.

    python tools/check_card_play.py [path ...]

With no arguments every native directory is scanned and each window that states its purpose is
graded, so nothing here has to be told where the fixtures live or which card is under test. A
window that states no purpose is reported at the end of the run rather than dropped: silence about
a window is how a grade turns into a no-op, and a recording declares itself by writing down what it
exists to show.
"""
import importlib.util
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'overlay'))
sys.path.insert(0, str(REPO / 'tools'))

import card_play_windows

# The placeholder a hand-built capture announces; no real game ever does.
DEBUG_INTENT = 'DEBUG'
# The one `/legacy/` row this script forgives: it is the recorded intent restated, not a power.
ATTACK_COUNT = '/legacy/monster_attack_damage_and_count'


def bulk_grade():
    """`check_coverage_gaps` as a module: its view and compare are the bulk grade."""
    path = REPO / 'tools' / 'check_coverage_gaps.py'
    spec = importlib.util.spec_from_file_location('check_coverage_gaps', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def survived(before, after, target):
    """The monster the window's play hit, as it stands on the next recorded board, or `None`.

    `None` means the target left the board -- the killing-blow shape -- and monster ids are not
    unique (two Slimes, two Louses), so a target index only names a survivor when the recording
    kept the board the same length.
    """
    return after[target] if len(after) == len(before) else None


def card_id_of(case, window):
    """The card the window's own action says it played, plus the upgrade count in that slot.

    Read from the action, not from the hand: a window whose action names one card while the hand
    holds another is a recording that disagrees with itself, and taking the hand's word for it
    would grade a play the recording never made.
    """
    held = window.case['checkpoints'][0]['observation']['piles']['hand'][window.slot]
    return window.card, (held.get('upgrades') or held.get('timesUpgraded') or 0)

def engine_card_id(sts, card_id, bridge):
    """The engine's enum member for a recorded card id, or `None` if the engine has no such card.

    The recording names a card the way the game prints it (`Neutralize`, `Strike_R`); the enum is
    the screaming-snake form of the same name, through the same alias table the rest of the
    pipeline uses, so a character-suffixed card resolves the same way it does everywhere else.
    """
    from sim_patch.parity.adapter import enum_key
    name = bridge.CARD_ALIASES.get(card_id, enum_key(card_id))
    return getattr(sts.CardId, name, None)


def expect_fight(window, bulk, root, rng_names, sts, advisor):
    """Replay the recorded window and hold the simulator to the recorded board."""
    case = window.case
    fixture_id = case['id']
    before = case['checkpoints'][0]['observation']
    slot = window.slot
    held = before['piles']['hand'][slot]
    card_id, upgrades = card_id_of(case, window)
    target = case['actions'][0].get('target', 0)

    # The opening board, through the bulk grade: a mismatch here is not this script's finding.
    view = bulk.to_view(before, root, rng_names, case["character"])
    battle = bulk.compare(view, f"{fixture_id} first checkpoint")
    wanted = engine_card_id(sts, card_id, advisor.search.comparator.bridge)
    assert wanted is not None, \
        f"{fixture_id}: the window plays {card_id}, which this engine has no card for"
    assert battle.hand[slot].id == wanted, \
        (f"{fixture_id}: the window plays {card_id} from hand slot {slot}, but the recorded board "
         f"holds {held['id']} there, which imports as {battle.hand[slot].id}")

    wounded = case["checkpoints"][1]["observation"]
    monsters_before = before["monsters"]
    monsters_after = wounded["monsters"]

    # A real intent, not the placeholder a hand-built capture carries.
    intents = [monster["intent"] for monster in monsters_before]
    assert all(intents), f"{fixture_id}: a monster announces no intent"
    assert DEBUG_INTENT not in intents, \
        f"{fixture_id}: a monster announces {DEBUG_INTENT}, which the real game never did"

    # The damage, straight off the fixture rather than recalculated from the card.
    hit = survived(monsters_before, monsters_after, target)
    if hit is not None:
        assert monsters_before[target]["hp"] - hit["hp"] > 0, \
            f"{fixture_id}: the recorded play deals no damage"

    # Play it in the simulator. A turn ending is a separate claim: bringing the monsters' turn
    # with it means their attacks, which an imported battle never has -- so it is graded by the
    # bulk grade alone, and these windows are held to the board right after the play.
    search = advisor.search
    play = sts.SearchAction(sts.SearchActionType.CARD, slot, target)
    assert play.is_valid(battle), f"{fixture_id}: the simulator refuses {held['id']} in hand slot {slot}"
    play.execute(battle)

    # The unfiltered comparison: `/legacy/` rows count here, because the powers a card applies are
    # among them, and that is the whole reason this script exists.
    found = search.comparator.compare_battle(
        bulk.to_view(wounded, root, rng_names, case["character"]), battle)["differences"]
    # `/legacy/monster_attack_damage_and_count` is rebuilt from the recorded intent, which is the
    # CURRENT intent; a monster that already acted this turn still carries the intent it had when
    # it did, so that row restates the recording rather than checking it.
    found = [difference for difference in found
             if not difference["path"].startswith(ATTACK_COUNT)]
    if found:
        shown = '\n    '.join(repr(d) for d in found[:12])
        raise AssertionError(
            f"{fixture_id}: the simulator disagrees after {held['id']}, {len(found)} differences:\n    {shown}")


def main():
    from backend import Advisor
    from sim_patch.parity.adapter import RNG_NAMES

    bulk = bulk_grade()
    advisor = Advisor()
    sts = advisor.search.comparator.sts
    root = json.loads((REPO / "overlay" / "test" / "live-root.json").read_text(encoding="utf-8"))

    targets = sys.argv[1:]
    windows, undeclared = card_play_windows.discover(
        targets, default=None if targets else REPO / 'fixtures' / 'native')
    assert windows, "no window states a purpose and carries a recorded card play -- grading nothing is not a pass"

    for window in windows:
        expect_fight(window, bulk, root, RNG_NAMES, sts, advisor)
        card_id, upgrades = card_id_of(window.case, window)
        print(f"PASS  {window.id:34s} {card_id}{'+' * upgrades:2s} {window.purpose}")
    # Coverage, not just success. A green run over 6 of 221 recorded plays is honest only when
    # the line says 6 of 221: "ALL card plays replay" would read as the repository being graded.
    total = len(windows) + len(undeclared)
    print(f"\nGRADED {len(windows)} of {total} recorded card plays replay, "
          f"the powers they apply included")

    # Reported, not dropped. A card play that states no purpose has not been accounted for, and
    # saying so is the difference between a grade that covers the repository and one that covers
    # the windows somebody happened to name. The count grows as recordings declare themselves.
    if undeclared:
        print(f"SKIPPED {len(undeclared)} have no purpose in their fixture, "
              f"so nothing here grades them (the first few: {', '.join(undeclared[:5])})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
