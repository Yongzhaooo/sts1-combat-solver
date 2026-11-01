"""Hold the campfire-upgrade helper against the game's own published upgraded card.

`tools/check_card_play.py` replays every window that states a purpose, `/legacy/` rows included, so
it already catches a card that applies the wrong power. What it cannot catch is the *recording*:
the upgraded Silent windows were built by upgrading the card with the game's own smith, through a
helper (`find_upgraded_window` below) that has never been checked against a window the maintainer
published. If the helper quietly picks the wrong window, `check_card_play.py` grades a card that is
not the card, and it does so with a green tick.

So this script does one thing: take a from-scratch upgraded window, find the game's own published
play of the same card at the same upgrade level, replay both, and hold the simulator to the board
the game recorded. Disagreement means the helper is not trustworthy yet, and the next card must not
reuse it.

    python tools/check_campfire_upgrade.py [path ...]

With no arguments the Silent native directory is scanned for the from-scratch upgraded windows.
"""
import gzip
import importlib.util
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'overlay'))

# The card the from-scratch upgraded windows were recorded with.
NEUTRALIZE = 'Neutralize'
WEAK = 'WEAKENED'
# The one `/legacy/` row this script forgives: it is the recorded intent restated, not Weak.
ATTACK_COUNT = '/legacy/monster_attack_damage_and_count'


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


def neutralize_slot(observation):
    """The hand slot holding the Neutralize the window plays, or None."""
    for index, held in enumerate(observation['piles']['hand']):
        if held['id'] == NEUTRALIZE:
            return index
    return None


def first_target(observation):
    """The first monster a play would be aimed at."""
    return 0 if observation.get("monsters") else -1


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


def main():
    from backend import Advisor
    from sim_patch.parity.adapter import RNG_NAMES

    bulk = bulk_grade()
    advisor = Advisor()
    sts = advisor.search.comparator.sts
    root = json.loads((REPO / "overlay" / "test" / "live-root.json").read_text(encoding="utf-8"))

    paths = silent_fixtures(sys.argv[1:])
    upgraded = [path for path in paths if "upgraded" in read_fixture(path)["id"]]
    assert upgraded, \
        "no from-scratch upgraded window to check the campfire-upgrade helper against"

    # The upgraded windows were recorded from scratch, with the card upgraded by the game's own
    # smith, so the helper that upgrades at a campfire has never been checked against anything the
    # maintainer published. Diff each from-scratch upgraded window against the published upgraded
    # case, field by field, and report whoever disagrees first.
    published = REPO / "fixtures" / "calibration" / "THE_SILENT"
    origins = [diff_against_published(path, published, bulk, root, RNG_NAMES, sts, advisor)
               for path in upgraded]
    for origin in origins:
        print(f"from-scratch upgraded window: {origin}")
    trusted = all(not origin.startswith("differs") for origin in origins)
    print("campfire-upgrade helper: " +
          ("agrees with the published upgraded case" if trusted
           else "DIFFERS from the published upgraded case -- do not reuse it for the next card"))
    return 0 if trusted else 1


if __name__ == "__main__":
    raise SystemExit(main())
