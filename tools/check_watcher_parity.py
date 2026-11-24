"""Import a Watcher battle and prove the stance, mantra and Retain fields survive the boundary.

The Watcher calibration captures already record `player.stance`, `player.mantra` and per-card
`retain` (1052 checkpoints), but nothing here graded them: the simulator could drop any of the
three and the comparison would still read as agreement. This builds a Watcher battle from the
captured live root, checks all four stance spellings through import -> compare, and then makes
the observation lie about each of the three fields to prove the comparison notices.

A note on the shape: `compare_battle(view, battle)` reads the expectation from `view` and the
value from the already imported `battle`, which is how the replay path uses it (import one
checkpoint, act, compare against the next). Comparing a view with a battle imported from that
same untouched view can only prove the fields round-trip; moving the view afterwards is what
proves the comparison reads them.

The four stance spellings are not spelled out here: they come from `sts.STANCE_NAMES`, which the
binding builds from the engine's own table in `constants/PlayerStatusEffects.h`. The mantra and
Retain values below are synthetic inputs that prove the comparison reads the fields; they are not
claims about game behaviour, so no expected number in this file comes from our own implementation.

Needs the built runtime, not the game.
"""
import copy
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "overlay"))
sys.path.insert(0, str(REPO / "tools"))
from backend import Advisor, view_from_state  # noqa: E402
import check_coverage_gaps as gaps  # noqa: E402
import parity_differences  # noqa: E402

# Only cards the engine already implements: Strike_P and Defend_P are the rest of the
# character, and a hand that carries them cannot import at all yet.
HAND = (("Eruption", 1), ("Vigilance", 2))
TARGETED = {"Eruption"}


def watcher_hand(retain):
    """The captured board's hand, holding the two Watcher cards the engine implements."""
    hand = []
    for index, (name, cost) in enumerate(HAND):
        card = gaps.card(name, cost=cost, retain=retain)
        card.update(uuid="00000000-0000-0000-0000-%012d" % (index + 1), name=name,
                    has_target=name in TARGETED,
                    target="MONSTER" if name in TARGETED else "SELF",
                    type="ATTACK" if name in TARGETED else "SKILL")
        hand.append(card)
    return hand


def watcher_view(root, stance, mantra=0, retain=False):
    """A turn-one Watcher board whose run context (relics, gold, rng) is the captured one."""
    view = view_from_state(copy.deepcopy(root))
    game = view["game"]
    game["class"] = "WATCHER"
    combat = game["combat_state"]
    combat["hand"] = watcher_hand(retain)
    for pile in ("draw_pile", "discard_pile", "exhaust_pile"):
        combat[pile] = []
    combat["limbo"] = []
    player = combat["player"]
    player.pop("orbs", None)
    player.pop("orb_slots", None)
    player.update(current_hp=68, max_hp=68, block=0, energy=3, stance=stance, mantra=mantra)
    combat.update(turn=1, cards_played_this_turn=0, attacks_played_this_turn=0,
                  skills_played_this_turn=0, cards_drawn=5, energy_wasted=0,
                  cards_discarded_this_turn=0, times_damaged=0)
    game["current_hp"] = 68
    return view


def compare(search, view, battle, *, expect_difference=False, what=""):
    result = search.comparator.compare_battle(view, battle)
    found = [d for d in result["differences"] if parity_differences.kept(d)]
    if expect_difference:
        assert found, f"{what}: expected a difference, got none"
    else:
        assert not found, f"{what}: unexpected differences {found[:3]}"
    return found


def import_view(search, view):
    return search.comparator.import_battle(view, require_search_state=True)


def main(search):
    sts = search.sts
    mantra_status = sts.PlayerStatus.MANTRA
    root = json.loads((REPO / "overlay" / "test" / "live-root.json").read_text(encoding="utf-8"))

    # The four spellings are the engine's own table, so this file unpacks them instead of
    # restating them; `check_snapshot_import.py` does the same.
    assert len(sts.STANCE_NAMES) == 4, sts.STANCE_NAMES
    neutral, calm, wrath, divinity = sts.STANCE_NAMES

    # Every stance imports and compares against its own capture: this proves the round trip.
    # What proves the comparison reads the field is moving the observation further down.
    for stance in sts.STANCE_NAMES:
        view = watcher_view(root, stance)
        battle = import_view(search, view)
        assert battle.player.stance == stance, (stance, battle.player.stance)
        compare(search, view, battle, what=f"{stance} imports and compares")

    # A capture that carries the three fields puts them in the snapshot unchanged, in the
    # game's own spelling; a capture without them gets none of them, which the import test
    # in check_snapshot_import.py covers.
    snapshot = search.comparator.bridge.build_snapshot(
        watcher_view(root, calm, mantra=6, retain=True)["game"])
    assert (snapshot["player"]["stance"], snapshot["player"]["mantra"]) == (calm, 6), snapshot["player"]
    assert all(card["retain"] for card in snapshot["hand"]), snapshot["hand"]

    # The observation is what moves: the battle below stays Calm and praying while the
    # recorded capture claims Wrath and no prayer, which is exactly the mistake this
    # boundary has to catch.
    baseline = watcher_view(root, calm, mantra=6, retain=True)
    battle = import_view(search, baseline)
    assert battle.player.stance == calm and battle.player.get_status(mantra_status) == 6
    assert [card.retain for card in battle.hand] == [True, True], [c.retain for c in battle.hand]
    compare(search, baseline, battle, what="the Calm battle against its own capture")

    state = search.comparator.simulator_state(battle)
    assert (state["player"]["stance"], state["player"]["mantra"]) == (calm, 6), state["player"]
    assert [card["retain"] for card in state["piles"]["hand"]] == [True, True], state["piles"]["hand"]

    wrong_stance = copy.deepcopy(baseline)
    wrong_stance["game"]["combat_state"]["player"]["stance"] = wrath
    found = compare(search, wrong_stance, battle, expect_difference=True,
                    what="a capture that says Wrath")
    assert any(d["path"] == "/player/stance" for d in found), found

    wrong_mantra = copy.deepcopy(baseline)
    wrong_mantra["game"]["combat_state"]["player"]["mantra"] = 2
    found = compare(search, wrong_mantra, battle, expect_difference=True,
                    what="a capture that says 2 mantra")
    assert any(d["path"] == "/player/mantra" for d in found), found

    lost_retain = copy.deepcopy(baseline)
    for card in lost_retain["game"]["combat_state"]["hand"]:
        card["retain"] = False
    found = compare(search, lost_retain, battle, expect_difference=True,
                    what="a capture whose cards do not retain")
    assert any(d["path"].startswith("/piles/hand/") and d["path"].endswith("/retain")
               for d in found), found

    # The engine writes the spellings, so only those spellings import.
    try:
        import_view(search, watcher_view(root, calm.lower()))
    except ValueError as error:
        assert "stance" in str(error), error
    else:
        raise AssertionError("a lower-case stance must not import")

    print("PASS: stance, mantra and Retain cross the import boundary, and a capture "
          "that misstates any of them is reported")
    return 0


if __name__ == "__main__":
    sys.exit(main(Advisor().search))
