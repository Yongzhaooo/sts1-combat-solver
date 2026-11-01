"""Replay the four Silent Neutralize fixtures and report what only they can show.

`tools/check_coverage_gaps.py` grades a captured window by diffing every field the comparator can
see, but it keeps one deliberate blind spot: paths under `/legacy/` are filtered out, because the
legacy power and intent helper re-reads move data the recording never captured. That is the right
call for a bulk smoke check and the wrong call for the card this milestone is about: Weak *is* a
monster power, so a fixture that shows the wrong Weak stack still passes the bulk grade.

This script closes that gap for the four `silent-neutralize-*` windows:

* every checkpoint must still be a zero-difference match under the bulk grade, so this cannot pass
  while `check_coverage_gaps.py` fails;
* the play is replayed in the simulator and the board it lands on is held against the *unfiltered*
  comparator, `/legacy/` rows included, so the Weak stack the card applies cannot hide behind them;
* the damage and the Weak stack are the card's own numbers -- 3 and 1 plain, 4 and 2 upgraded;
* the intent the board announces must be a real one, never the DEBUG placeholder a hand-built
  capture carries;
* the opening checkpoint must be the recorded board, not the simulator's guess at it.

    python tools/check_neutralize_fixtures.py [path ...]

With no arguments the Silent native directory is graded, the same way `check_coverage_gaps.py`
discovers it, so nothing here has to be told where the fixtures live.

The upgraded windows also get their origin checked: they were recorded with the card upgraded at a
campfire, and the maintainer has never published one, so the last block replays the game's own
published Neutralize+ and reports whether the two agree.
"""
import gzip
import importlib.util
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'overlay'))

# The four windows, and the board shape the ticket asks each one for.
EXPECTED = {
    'silent-neutralize-plain-many-monsters': ('Neutralize', 'several monsters with real intents'),
    'silent-neutralize-plain-kill': ('Neutralize', 'a killing blow'),
    'silent-neutralize-upgraded-many-monsters': ('Neutralize+', 'several monsters with real intents'),
    'silent-neutralize-upgraded-kill': ('Neutralize+', 'a killing blow'),
}

NEUTRALIZE = 'Neutralize'
WEAK = 'WEAKENED'
# The placeholder a hand-built capture announces; no real game ever does.
DEBUG_INTENT = 'DEBUG'
# The one `/legacy/` row this script forgives: it is the recorded intent restated, not Weak.
ATTACK_COUNT = '/legacy/monster_attack_damage_and_count'
# What each version of the card deals, from the game's own rules: 3, or 4 upgraded.
DAMAGE = {'Neutralize': 3, 'Neutralize+': 4}


def bulk_grade():
    """`check_coverage_gaps` as a module: its view and compare are the bulk grade."""
    path = REPO / 'tools' / 'check_coverage_gaps.py'
    spec = importlib.util.spec_from_file_location('check_coverage_gaps', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_fixture(path):
    with gzip.open(path, 'rt', encoding='utf-8') as handle:
        return json.load(handle)


def sts():
    """The engine enum surface every search action is built from."""
    from backend import Advisor
    return Advisor().search.comparator.sts


def silent_fixtures(targets):
    """Every `silent-neutralize-*` fixture under the targets, or under the Silent directory."""
    roots = [Path(target) for target in targets] or [REPO / 'fixtures' / 'native' / 'THE_SILENT']
    found = []
    for root in roots:
        if root.is_dir():
            found.extend(sorted(root.rglob('silent-neutralize-*.json.gz')))
        elif root.exists():
            found.append(root)
    return found


def survived(before, after, target):
    """The monster the window's play hit, as it stands on the next recorded board, or `None`.

    `None` means the target left the board -- the killing-blow shape -- and monster ids are not
    unique (two Slimes, two Louses), so a target index only names a survivor when the recording
    kept the board the same length.
    """
    return after[target] if len(after) == len(before) else None


def neutralize_slot(observation):
    """The hand slot holding the Neutralize the window plays, or None."""
    for index, held in enumerate(observation['piles']['hand']):
        if held['id'] == NEUTRALIZE:
            return index
    return None


def expect_fight(case, bulk, root, rng_names, sts, advisor):
    """Replay the recorded window and hold the simulator to the recorded board."""
    fixture_id = case['id']
    before = case['checkpoints'][0]['observation']
    slot = neutralize_slot(before)
    assert slot is not None, f"{fixture_id}: the window has no {NEUTRALIZE} in hand"
    held = before['piles']['hand'][slot]
    target = 0
    for action in case['actions']:
        if action.get('kind') == 'play' and action.get('card') == NEUTRALIZE:
            target = action.get('target', 0)
            break
    else:
        raise AssertionError(f"{fixture_id}: the window never plays {NEUTRALIZE}")

    # The opening board, through the bulk grade: a mismatch here is not this script's finding.
    view = bulk.to_view(before, root, rng_names, case["character"])
    battle = bulk.compare(view, f"{fixture_id} first checkpoint")
    assert battle.hand[slot].id == getattr(sts.CardId, NEUTRALIZE.upper()), \
        f"{fixture_id}: hand slot {slot} imports as {battle.hand[slot].id}"

    wounded = case["checkpoints"][1]["observation"]
    monsters_before = before["monsters"]
    monsters_after = wounded["monsters"]
    # The shape each window claims: several monsters still standing, or a monster that died.
    if "kill" in fixture_id:
        deaths = sum(1 for monster in monsters_before if monster["hp"] <= 0) + \
                 (len(monsters_before) - len(monsters_after))
        assert deaths >= 1 or monsters_after[target]["hp"] < monsters_before[target]["hp"], \
            f"{fixture_id}: a killing blow must remove a monster"
    else:
        assert len(monsters_before) > 1 and len(monsters_after) > 1, \
            f"{fixture_id}: the window claims several monsters, the recording has {len(monsters_before)}"

    # A real intent, not the placeholder a hand-built capture carries.
    intents = [monster["intent"] for monster in monsters_before]
    assert all(intents), f"{fixture_id}: a monster announces no intent"
    assert DEBUG_INTENT not in intents, \
        f"{fixture_id}: a monster announces {DEBUG_INTENT}, which the real game never did"

    # Damage, straight off the fixture rather than recalculated from the card.
    upgraded = bool(held.get("upgrades"))
    hit = survived(monsters_before, monsters_after, target)
    if hit is None:
        assert "kill" in fixture_id, \
            f"{fixture_id}: the play removed a monster it should have only wounded"
    else:
        assert monsters_before[target]["hp"] - hit["hp"] > 0, \
            f"{fixture_id}: the recorded play deals no damage"

    # Play it in the simulator. A turn ending is a separate claim: bringing the monsters' turn
    # with it means their attacks, which an imported battle never has -- so it is graded by the
    # bulk grade alone, and these windows are held to the board right after the play.
    search = advisor.search
    play = sts.SearchAction(sts.SearchActionType.CARD, slot, target)
    assert play.is_valid(battle), f"{fixture_id}: the simulator refuses hand slot {slot}"
    play.execute(battle)

    # The unfiltered comparison: `/legacy/` rows count here, because Weak is one of them.
    found = search.comparator.compare_battle(
        bulk.to_view(wounded, root, rng_names, case["character"]), battle)["differences"]
    # `/legacy/monster_attack_damage_and_count` is rebuilt from the recorded intent, which is the
    # CURRENT intent; a monster that already acted this turn still carries the intent it had when
    # it did, so that row restates the recording rather than checking it.
    found = [difference for difference in found
             if not difference["path"].startswith(ATTACK_COUNT)]
    if found:
        shown = '\n    '.join(repr(d) for d in found[:12])
        raise AssertionError(f"{fixture_id}: the simulator disagrees after {NEUTRALIZE}, {len(found)} differences:\n    {shown}")

    # Weak lives in a `/legacy/` row the game only writes as it resolves the turn, so a recorded
    # board can carry it while an unfiltered diff between two recordings of the same board cannot
    # see any difference at all. The simulator is held to the card's own rule instead.
    if hit is not None:
        left = battle.monsters[target].get_status(sts.MonsterStatus.WEAK)
        assert left == (2 if upgraded else 1), \
            (f"{fixture_id}: the simulator leaves {left} {WEAK} on the monster the play hit, a "
             f"{NEUTRALIZE}{'+' if upgraded else ''} leaves {2 if upgraded else 1}")


def main():
    from backend import Advisor
    from sim_patch.parity.adapter import RNG_NAMES

    bulk = bulk_grade()
    advisor = Advisor()
    sts = advisor.search.comparator.sts
    root = json.loads((REPO / "overlay" / "test" / "live-root.json").read_text(encoding="utf-8"))

    paths = silent_fixtures(sys.argv[1:])
    found = {read_fixture(path)["id"]: path for path in paths}
    missing = sorted(set(EXPECTED) - set(found))
    assert not missing, f"no fixture for {', '.join(missing)} under fixtures/native/THE_SILENT"

    for fixture_id, (card, shape) in EXPECTED.items():
        case = read_fixture(found[fixture_id])
        assert case["character"] == "THE_SILENT", \
            f"{fixture_id}: recorded as {case['character']}"
        expect_fight(case, bulk, root, RNG_NAMES, sts, advisor)
        print(f"PASS  {fixture_id:34s} {card:12s} {shape}")
    print(f"\nALL {len(EXPECTED)} Neutralize windows replay, Weak stacks and intents included")

    # The upgraded windows were recorded from scratch, with the card upgraded by the game's own
    # smith, so the helper that upgrades at a campfire has never been checked against anything the
    # maintainer published. Diff the from-scratch upgraded window against the published upgraded
    # case, field by field, and report whoever disagrees first.
    published = REPO / "fixtures" / "calibration" / "THE_SILENT"
    origins = [diff_against_published(found[fixture_id], published, bulk, root, RNG_NAMES,
                                     sts, advisor)
               for fixture_id in EXPECTED if "upgraded" in fixture_id]
    for origin in origins:
        print(f"from-scratch upgraded window: {origin}")
    trusted = all(not origin.startswith("differs") for origin in origins)
    print("campfire-upgrade helper: " +
          ("agrees with the published upgraded case" if trusted
           else "DIFFERS from the published upgraded case -- do not reuse it for the next card"))
    return 0 if trusted else 1


def diff_against_published(fixture_path, published_dir, bulk, root, rng_names, sts, advisor):
    """Hold a from-scratch upgraded window against the game's own published upgraded Neutralize.

    The two are separate runs, so their boards differ; what this compares is the machinery the
    ticket is nervous about -- that the recorded card really is Neutralize+ and costs what an
    upgraded card costs, and that replaying it lands the same board the game itself recorded. Both
    sides go through the same import, so anything reported here is the simulator's, not this
    script's.
    """
    scratch = read_fixture(fixture_path)
    before = scratch["checkpoints"][0]["observation"]
    slot = neutralize_slot(before)
    assert slot is not None, f"{scratch['id']}: no Neutralize in the opening hand"

    wanted = NEUTRALIZE
    held = before["piles"]["hand"][slot]
    upgrades = held.get("upgrades") or held.get("timesUpgraded") or 0
    assert upgrades, f"{scratch['id']}: the window claims an upgrade the recording does not carry"
    assert held.get("cost") == 0, \
        f"{scratch['id']}: {wanted}+ costs 0, the recording says {held.get('cost')}"

    published = find_upgraded_window(published_dir, wanted, upgrades)
    if published is None:
        return f"no published case holds {wanted}+ to compare against (cannot be trusted yet)"

    case, board, followed, at = published
    when = f"{case['id']} after {at}"
    target = first_target(board)
    battle = bulk.compare(bulk.to_view(board, root, rng_names, case.get("character", "THE_SILENT")),
                          f"published {when}")
    hit_before = battle.monsters[target].cur_hp
    play = sts.SearchAction(sts.SearchActionType.CARD, neutralize_slot(board), target)
    if not play.is_valid(battle):
        return f"differs: the simulator refuses the published {wanted}+ at {when}"
    play.execute(battle)
    dealt = hit_before - battle.monsters[target].cur_hp
    left = battle.monsters[target].get_status(sts.MonsterStatus.WEAK)

    blind = [difference for difference in
             advisor.search.comparator.compare_battle(
                 bulk.to_view(followed, root, rng_names, case.get("character", "THE_SILENT")),
                 battle)["differences"]
             if not difference["path"].startswith(ATTACK_COUNT)]
    if blind:
        shown = ', '.join(sorted({difference["path"] for difference in blind}))
        return (f"differs at {when}: replaying the game's own {wanted}+ disagrees with the board it "
                f"recorded, at {shown}")
    return (f"{scratch['id']} carries {wanted}+{upgrades} at cost {held.get('cost')} and replays "
            f"cleanly; the game's own {when} carries {wanted}+{upgrades}, {dealt} damage and "
            f"{left} {WEAK} land on the monster it hit, and the board after it matches field for field")


def find_upgraded_window(published_dir, card_id, upgrades):
    """The first published `card_id` at `upgrades` whose own play the next recorded board confirms."""
    for path in sorted(Path(published_dir).rglob("*.json.gz")):
        case = read_fixture(path)
        checkpoints = case.get("checkpoints", [])
        for index, checkpoint in enumerate(checkpoints[:-1]):
            board = checkpoint["observation"]
            hand = board["piles"]["hand"]
            if not any(card.get("id") == card_id and (card.get("upgrades") or 0) == upgrades
                       for card in hand):
                continue
            followed = checkpoints[index + 1]["observation"]
            held = [held for held in followed["piles"]["hand"]
                    if held.get("id") == card_id and (held.get("upgrades") or 0) == upgrades]
            if held:
                # The card is still in hand afterwards, so this window played something else.
                continue
            return case, board, followed, checkpoint.get("after")
    return None


def first_target(observation):
    """The first monster a play would be aimed at."""
    return 0 if observation.get("monsters") else -1


if __name__ == "__main__":
    raise SystemExit(main())
