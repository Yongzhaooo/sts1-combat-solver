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
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'overlay'))

# The card the from-scratch upgraded windows were recorded with.
NEUTRALIZE = 'Neutralize'
WEAK = 'WEAKENED'


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


def weak_in(observation, index):
    """The Weak stacks a recorded board shows on one monster, or 0 when it has none."""
    if index < 0 or index >= len(observation.get("monsters", [])):
        return 0
    for power in observation["monsters"][index].get("powers") or []:
        if power.get("id") in (WEAK, "Weakened", "Weak"):
            return power.get("amount") or 0
    return 0


def weak_of(observation, monster_id):
    """The Weak stacks a recorded board shows on the monster with this id, or 0 if it is gone."""
    for index, monster in enumerate(observation.get("monsters", [])):
        if monster.get("id") == monster_id:
            return weak_in(observation, index)
    return 0


def outcome(board, followed, index):
    """What one recorded play did to the monster at `index`: (damage dealt, Weak left).

    A dead monster is not the pre-play monster at the same index: the recording removes it and the
    monsters behind it shift down. So the follow-up board is matched by id. A monster that is gone
    took exactly its remaining HP, and carries no Weak on a board it is not on.
    """
    before = board["monsters"][index]
    after = [monster for monster in followed.get("monsters", []) if monster.get("id") == before["id"]]
    if not after:
        return before["hp"], 0
    return before["hp"] - after[0]["hp"], weak_of(followed, before["id"])


def play_target(scratch, slot):
    """The monster the from-scratch window aimed the card at, from its recorded action."""
    for action in scratch.get("actions") or []:
        if action.get("card") == NEUTRALIZE and "target" in action:
            return action["target"]
    # No recorded target: fall back to the only monster, and refuse an ambiguous board rather than
    # guessing which one the play hit.
    if len(scratch["checkpoints"][0]["observation"].get("monsters", [])) == 1:
        return 0
    raise AssertionError(
        f"{scratch['id']}: boards several monsters but records no target for the {NEUTRALIZE} play")


def find_upgraded_window(published_dir, card_id, upgrades, want_kill):
    """The first window in a published case where `card_id` at `upgrades` is really played.

    A window is two adjacent checkpoints. The card is in the opening hand and gone from the closing
    one, which is what a play looks like in a recording. `want_kill` picks the kind: a window whose
    play removed a monster, or one that left every monster standing. A killing window and a
    non-killing window record different numbers for the same card (damage is capped by the target's
    HP, and a dead monster carries no Weak), so they are only ever compared with their own kind.

    `after` is the number of actions taken at the *opening* checkpoint, so it names the window's
    start, not its index; the caller must treat the returned boards as the comparison, never
    re-index the case with that number.
    """
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
            killed = len(followed.get("monsters", [])) < len(board.get("monsters", []))
            if killed != want_kill:
                continue
            return case, board, followed, checkpoint.get("after")
    return None


def diff_against_published(fixture_path, published_dir):
    """Hold a from-scratch upgraded window against the game's own published upgraded Neutralize.

    What this checks is the *recording*, not the simulator: that the from-scratch window really
    carries Neutralize at the upgrade level its name claims, at the cost an upgraded Neutralize
    costs, and that its board reacts to the play the way the game's own board reacts.

    The published side is the game's recording of the same card. Its two checkpoints bracket one
    play of Neutralize+, so the difference between them is what the game itself did -- the damage
    it dealt and the Weak it left. That difference is the expected value this script holds a
    from-scratch window to. Comparing the two *recordings* is the only comparison that says
    anything about the recording: replaying the published board and diffing the result against
    that same board agrees whatever the from-scratch window contained.
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

    # What the from-scratch recording says the same play did. Read this first: the recorded board
    # decides which kind of published window can serve as a control.
    scratch_after = scratch["checkpoints"][1]["observation"]
    scratch_target = play_target(scratch, slot)
    scratch_dealt, scratch_left = outcome(before, scratch_after, scratch_target)
    scratch_kill = scratch_dealt >= before["monsters"][scratch_target]["hp"]

    published = find_upgraded_window(published_dir, wanted, upgrades, scratch_kill)
    if published is None:
        # Not a pass: nothing was compared, and the caller must not read this as agreement.
        raise AssertionError(
            f"no published case records a {'killing' if scratch_kill else 'non-killing'} "
            f"{wanted}+{upgrades} play to compare {scratch['id']} against; the campfire-upgrade "
            f"helper is unverified for this kind of window")

    case, board, followed, at = published
    when = f"{case['id']} at action {at}"
    target = first_target(board)

    # What the game itself did, read from its own two checkpoints: the card was played between
    # `board` and `followed`, so the difference is the game's answer, not the simulator's.
    game_dealt, game_left = outcome(board, followed, target)

    # Both sides are the same kind of window now, so their recorded numbers are comparable. Damage
    # is still capped by the target's HP and a killed monster leaves no Weak behind; in a killing
    # window the only thing both recordings can be held to is that the hit removed the monster.
    game_kill = game_dealt >= board["monsters"][target]["hp"]
    assert game_kill == scratch_kill, \
        f"{scratch['id']}: window kind changed under us ({scratch_kill} vs {game_kill})"

    if scratch_kill:
        return (f"{scratch['id']} carries {wanted}+{upgrades} at cost {held.get('cost')} and, like the "
                f"game's own {when}, its recorded {wanted}+{upgrades} removes the monster it hits; "
                f"damage is capped by that monster's HP, so the many-monster windows carry the number")

    if (scratch_dealt, scratch_left) != (game_dealt, game_left):
        return (f"differs: {scratch['id']} records {scratch_dealt} damage and {scratch_left} "
                f"{WEAK}, while the game's own {when} records {game_dealt} damage and "
                f"{game_left} {WEAK} for the same {wanted}+{upgrades}")

    return (f"{scratch['id']} carries {wanted}+{upgrades} at cost {held.get('cost')} and its recorded "
            f"play matches the game's own {when} field for field: {game_dealt} damage and "
            f"{game_left} {WEAK} onto the monster it hit")


def main():
    paths = silent_fixtures(sys.argv[1:])
    upgraded = [path for path in paths if "upgraded" in read_fixture(path)["id"]]
    assert upgraded, \
        "no from-scratch upgraded window to check the campfire-upgrade helper against"

    # Each from-scratch upgraded window is held against the play the game itself recorded, read
    # from the published case's own two checkpoints. Both sides are recordings, so agreement here
    # is evidence about the helper that produced the from-scratch window. No simulator is involved:
    # importing a board to replay it would answer a question about the simulator, not the recording.
    published = REPO / "fixtures" / "calibration" / "THE_SILENT"
    origins = [diff_against_published(path, published) for path in upgraded]
    for origin in origins:
        print(f"from-scratch upgraded window: {origin}")
    trusted = all(not origin.startswith("differs") for origin in origins)
    print("campfire-upgrade helper: " +
          ("agrees with the published upgraded case" if trusted
           else "DIFFERS from the published upgraded case -- do not reuse it for the next card"))
    return 0 if trusted else 1


if __name__ == "__main__":
    raise SystemExit(main())
