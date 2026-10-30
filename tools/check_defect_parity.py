"""Import a Defect battle and compare it against the game field by field.

The unit tests prove each card, orb and relic behaves; this proves the live
snapshot path agrees with the game for a whole Defect state: card ids, orb
slots, the derived orb amounts and every counter the comparator can reach.
The state is built from the captured live root, so the run context (rng,
relics, gold, deck) is real rather than hand-written.
"""
import copy
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "overlay"))
from backend import Advisor, view_from_state  # noqa: E402

ROOT = json.loads((REPO / "overlay" / "test" / "live-root.json").read_text(encoding="utf-8"))


def defect_view(hand, piles, orbs, focus):
    view = view_from_state(copy.deepcopy(ROOT))
    game = view["game"]
    game["class"] = "DEFECT"
    combat = game["combat_state"]
    template = combat["hand"][0]
    for pile, ids in [("hand", hand), *piles.items()]:
        combat[pile] = [defect_card(template, name, cost) for name, cost in ids]
    combat["player"]["powers"] = [{"id": "Focus", "name": "Focus", "amount": focus}] if focus else []
    combat["player"]["orbs"] = [dict(id=name, evoke_amount=evoke, passive_amount=passive)
                                for name, passive, evoke in orbs]
    combat["player"].pop("orb_slots", None)
    # The captured root was taken mid-turn with two cards already spent; put the
    # combat back to the start of turn one so playability has a real answer.
    combat["player"].update(current_hp=75, max_hp=75, block=0, energy=3)
    combat.update(turn=1, cards_played_this_turn=0, attacks_played_this_turn=0,
                  skills_played_this_turn=0, cards_drawn=5, energy_wasted=0,
                  cards_discarded_this_turn=0, times_damaged=0)
    game["current_hp"] = 75
    return view


# Cards that need a monster to aim at. The captured root was recorded with a
# pending selection, so playability has to be restored for a normal turn.
TARGETED = {"Zap", "Dualcast", "Strike_B", "Ball Lightning", "Sunder", "Meteor Strike"}
_UUID = iter(f"00000000-0000-0000-0000-{index:012d}" for index in range(1, 1000))


def defect_card(template, name, cost):
    # Every card needs its own identity: the action mapper keys hand cards by
    # uuid, so a copied one would look like a card changing places.
    card = dict(template, id=name, cost=cost, base_cost=cost, upgrades=0, free_to_play_once=False,
                is_playable=True, uuid=next(_UUID))
    card["has_target"] = name in TARGETED
    card["target"] = "MONSTER" if card["has_target"] else "SELF"
    card["type"] = "ATTACK" if card["has_target"] else "SKILL"
    card.pop("misc", None)
    return card


def orb(name, passive, evoke):
    return name, passive, evoke


def check(view, *, expect_difference=False, what=""):
    search = Advisor().search
    battle = search.comparator.import_battle(view, require_search_state=True)
    result = search.comparator.compare_battle(view, battle)
    found = [d for d in result["differences"] if not d["path"].startswith("/legacy/")]
    if expect_difference:
        assert found, f"{what}: expected a difference, got none"
    else:
        assert not found, f"{what}: unexpected differences {found[:3]}"
    return found, battle


LIGHTNING = orb("Lightning", 3, 8)
FROST = orb("Frost", 2, 5)
DARK = orb("Dark", 6, 30)
PLASMA = orb("Plasma", 1, 2)
EMPTY = (None, 0, 0)


def main():
    # A turn-one Defect hand: channel cards, both basics, and the game card ids
    # that differ from the engine enum names.
    base = defect_view([("Zap", 1), ("Dualcast", 1), ("Defend_B", 1)],
                       {"draw_pile": [("Strike_B", 1), ("Defend_B", 1), ("Ball Lightning", 1)],
                        "discard_pile": [("Strike_B", 1)], "exhaust_pile": []},
                       [LIGHTNING, EMPTY, EMPTY], 0)
    _, battle = check(base, what="opening hand")
    assert [o["id"] for o in battle.player.orbs] == ["LIGHTNING"], battle.player.orbs
    assert battle.player.orb_slots == 3, battle.player.orb_slots

    # Every orb type, both with and without Focus, plus an empty slot.
    check(defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                      [LIGHTNING, FROST, DARK], 0), what="four orb types, Focus 0")
    check(defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                      [orb("Lightning", 1, 6), orb("Frost", 0, 3), orb("Plasma", 1, 2),
                       orb("Dark", 4, 12)], -2), what="negative Focus")
    # The game floors both amounts at zero (AbstractOrb.applyFocus), so a Focus below the base
    # reads as 0 and not as a negative number the simulator would then report as a difference.
    check(defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                      [orb("Lightning", 0, 4), orb("Frost", 0, 1)], -4), what="Focus below the orb base")
    check(defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                      [orb("Lightning", 5, 10), orb("Frost", 4, 7), orb("Plasma", 1, 2)], 2),
          what="positive Focus")

    # No card raises an orb's evoke amount, so the importer insists on
    # evoke == max(0, base + Focus); a Zap+ shape must be refused, not stored.
    try:
        check(defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                          [orb("Lightning", 3, 11), EMPTY, EMPTY], 0), expect_difference=True,
              what="an evoke amount no card can produce")
        raise AssertionError("an invented evoke bonus must not import")
    except ValueError as error:
        assert "base + focus" in str(error), error

    # Negatives: each of these must be reported, never silently accepted.
    # The passive amount only depends on Focus, so a Focus the importer read
    # wrong shows up here even though the evoke bonus is derived from the same
    # total and would absorb any error.
    broken = defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                         [orb("Lightning", 3, 10), EMPTY, EMPTY], 0)
    broken["game"]["combat_state"]["player"]["powers"] = [{"id": "Focus", "name": "Focus", "amount": 2}]
    found, _ = check(broken, expect_difference=True, what="Focus not applied to the orb bar")
    assert "/player/orbs/0/passive" in [d["path"] for d in found], found

    # An evoke that disagrees with base + Focus in either direction is refused
    # outright rather than approximated.
    impossible = defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                             [orb("Lightning", 3, 8), EMPTY, EMPTY], 0)
    impossible["game"]["combat_state"]["player"]["powers"] = [{"id": "Focus", "name": "Focus", "amount": 2}]
    try:
        check(impossible, expect_difference=True, what="evoke disagreeing with base + Focus")
    except ValueError as error:
        assert "base + focus" in str(error), error

    # The orb type table is checked against the amounts, not against the id: a
    # Frost carrying Lightning's passive must be reported. Its evoke has to stay
    # self-consistent, because the importer refuses an evoke that base + focus
    # cannot produce (Frost base 5 + focus 2 = 7).
    mistyped = defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                           [orb("Frost", 5, 7)], 2)
    found, _ = check(mistyped, expect_difference=True, what="orb type disagrees with its amounts")
    assert any(d["path"].startswith("/player/orbs/0/") for d in found), found

    # The slot count and the orb list both come from the game, so the interesting
    # failures are the derived amounts, not the bar length.
    check(defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                      [LIGHTNING, EMPTY, EMPTY, EMPTY, EMPTY], 0), what="capacity relic bar")

    unknown = defect_view([("Zap", 1)], {"draw_pile": [], "discard_pile": [], "exhaust_pile": []},
                          [orb("Lightning", 3, 8), EMPTY, EMPTY], 0)
    unknown["game"]["combat_state"]["player"]["orbs"][1] = dict(id="Unknown", evoke_amount=1, passive_amount=1)
    try:
        check(unknown, expect_difference=True, what="unknown orb id")
    except ValueError as error:
        assert "unknown snapshot orb" in str(error), error

    print("PASS: a Defect snapshot imports and compares field by field, "
          "including orb slots and derived amounts")


if __name__ == "__main__":
    main()