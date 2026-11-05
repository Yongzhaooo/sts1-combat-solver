"""Red/green guard: a card play must never go to an open hand picker.

The F55 export (sts1-run-752440786197460925-F55-v0.9.8-1104-2051.json) ends with
`Invalid command: play. Possible commands: [choose, potion, key, click, wait, state]`:
the game was on a hand picker while the plan still expected a normal turn, so the
solver handed over the next card play and the game refused it.

`steam.live_search.check_selection_screen` is the invariant that catches this before
the command leaves the solver.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "candidates" / "sts-ironclad-agent"))

from steam import live_search


class FakeInputState:
    PLAYER_NORMAL = "PLAYER_NORMAL"
    CARD_SELECT = "CARD_SELECT"


class FakeSts:
    InputState = FakeInputState


class FakeBattle:
    def __init__(self, input_state):
        self.input_state = input_state


def view(screen):
    return {"game": {"screen_type": screen}}


def run():
    failures = []
    guard = getattr(live_search, "check_selection_screen", None)
    if guard is None:
        failures.append("live_search.check_selection_screen is missing")
        return failures

    # The F55 case: the game waits for a card pick, the plan expects a normal turn.
    try:
        guard(view("HAND_SELECT"), FakeBattle("PLAYER_NORMAL"), FakeSts)
    except ValueError as error:
        if "选牌" not in str(error):
            failures.append("picker mismatch raised an unrelated message: %s" % error)
    else:
        failures.append("a hand picker with a card-play plan was accepted")

    # The same mismatch on the grid picker.
    try:
        guard(view("GRID"), FakeBattle("PLAYER_NORMAL"), FakeSts)
    except ValueError:
        pass
    else:
        failures.append("a grid picker with a card-play plan was accepted")

    # Normal turn, no picker: the plan may play cards.
    try:
        guard(view("NONE"), FakeBattle("PLAYER_NORMAL"), FakeSts)
    except ValueError as error:
        failures.append("a normal turn was rejected: %s" % error)

    # Picker open and the plan knows it: the plan must pick, and the guard stays quiet.
    try:
        guard(view("HAND_SELECT"), FakeBattle("CARD_SELECT"), FakeSts)
    except ValueError as error:
        failures.append("a planned card pick was rejected: %s" % error)

    return failures


def main():
    failures = run()
    if failures:
        for line in failures:
            print("FAIL:", line)
        return 1
    print("PASS: the solver refuses to play a card while a picker is open")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
