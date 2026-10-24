"""Scenario: a smoke bomb the engine resolves before the game does.

Replays a recorded combat frame with a Smoke Bomb in the belt. The engine leaves the
battle the instant the potion lands; the game keeps reporting UNDECIDED while the haze
plays and the reward screen opens. Driving the production `Advisor.advance` path must
drop the rest of the plan instead of calling that a state divergence, and a frame that
really drifted must still stop the pilot.
"""
import argparse
from collections import deque
import json
from pathlib import Path
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--native-root', type=Path)
args = parser.parse_args()
root = args.native_root or Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, TERMINAL_PENDING, unexplained_differences, view_from_state
from sim_patch.parity.adapter import ActionMapper

recorded = json.loads((Path(__file__).resolve().parents[1] / 'overlay/test/live-root.json')
                      .read_text(encoding='utf-8'))
game = recorded['game_state']
# The recorded frame predates the potion: its slots are empty. Put the Smoke Bomb in
# the first slot so the frame is the one the player is holding it in.
game['potions'] = [dict(id='SmokeBomb', can_use=True, can_discard=True)] + list(game['potions'][1:])
view = view_from_state(recorded)

advisor = Advisor()
session = advisor.search


def escaped_advisor():
    """A fresh imported battle plus the queued potion action, as a real request would have."""
    battle = session.comparator.import_battle(view, require_search_state=True)
    mapper = ActionMapper(session.comparator, battle, view)
    action = mapper.action('potion use 0', view, battle)
    assert action.is_valid(battle)
    session.battle, session.mapper = battle, mapper
    session.actions = deque([int(action.bits)])
    advisor.pending = action
    return battle


battle = escaped_advisor()
advisor.advance(view, 256)
assert int(battle.outcome) == int(session.sts.Outcome.PLAYER_ESCAPE), int(battle.outcome)
assert not session.actions, 'the rest of a plan that cannot happen must be dropped'
assert advisor.result(view)['status'] == 'complete'

comparison = session.comparator.compare_battle(view, battle)
assert [d.get('path') for d in comparison['differences']] == ['/outcome'], comparison['differences']
assert any(g.get('kind') == TERMINAL_PENDING for g in comparison['gaps']), comparison['gaps']
assert unexplained_differences(comparison) == []
# Without the gap this very frame is a divergence, so the test cannot pass by accident.
assert len(unexplained_differences({**comparison, 'gaps': []})) == 1
# And the tolerance stays narrow: one real difference alongside it still stops the pilot.
extra = {'path': '/player/hp', 'kind': 'value', 'original': 72, 'simulator': 67}
assert len(unexplained_differences({**comparison,
                                    'differences': comparison['differences'] + [extra]})) == 2

print('PASS: a smoke bomb the engine resolves before the game does ends the plan '
      'without a divergence, while real drift still stops the pilot')
