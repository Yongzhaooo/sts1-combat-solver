"""Replay original-game Headbutt captures, including a fresh solve inside its picker."""
from collections import deque
import copy
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state, start_selection
from sim_patch.parity.adapter import ActionMapper

states = [json.loads((root / 'overlay/test' / ('headbutt-' + name + '.json')).read_text(encoding='utf-8'))
          for name in ('before', 'picker', 'after')]
before, picker, after = map(view_from_state, states)
advisor = Advisor()
s = advisor.search

# Continue a plan that played Headbutt normally, then choose the second of two
# identical Strikes. The final comparison includes HP, energy, piles and RNG.
battle = s.comparator.import_battle(before, require_search_state=True)
mapper = ActionMapper(s.comparator, battle, before)
play = mapper.action('play 1 0', before, battle)
assert play.is_valid(battle)
play.execute(battle)
assert s.native.selection_info(battle)['task'] == 'HEADBUTT'
pick = mapper.action('choose 1', picker, battle)
assert pick.is_valid(battle)
pick.execute(battle)
assert not s.comparator.compare_battle(after, battle)['differences']

# Fresh import while Java still has UseCardAction queued: do not replay damage
# or forget the Headbutt that must subsequently move to the discard pile.
spec = start_selection(s.comparator, picker)
assert spec['task'] == 'HEADBUTT'
battle = s.comparator.import_battle(picker, require_search_state=True)
s.native.import_start_selection(battle, spec)
mapper = ActionMapper(s.comparator, battle, picker)
pick = mapper.action('choose 1', picker, battle)
s.battle, s.mapper = battle, mapper
s.actions = deque([int(pick.bits), int(s.sts.SearchAction(s.sts.SearchActionType.END_TURN).bits)])
assert s.next_action(picker)[1] == 'choose 1'
reordered = copy.deepcopy(picker)
cards = reordered['game']['screen_state']['cards']
cards[0], cards[1] = cards[1], cards[0]
assert s.next_action(reordered)[1] == 'choose 0', 'UUID identity lost among duplicate Strikes'
pick.execute(battle)
comparison = s.comparator.compare_battle(after, battle)
assert not comparison['differences'], comparison['differences']

# Search itself must accept this root, produce a real choice, then validate the
# chosen result against a game capture when that is the captured choice.
advisor.solve(picker, 2000)
reply = advisor.result(picker)
assert reply['status'] == 'ready' and reply['command'].startswith('choose '), reply
assert reply['route'][0]['label'].startswith('弃牌置顶：'), reply['route'][0]
later = copy.deepcopy(picker)
later['game']['combat_state']['turn'] = 4
assert start_selection(s.comparator, later)['task'] == 'HEADBUTT'

for change in ('missing_card', 'extra_queue', 'selected', 'wrong_uuid', 'queued_replay'):
    invalid = copy.deepcopy(picker)
    if change == 'missing_card':
        invalid['live_run']['pending_actions'][0].pop('targetCard')
    elif change == 'extra_queue':
        invalid['live_run']['pending_actions'].append({'class': 'UnmappedAction'})
    elif change == 'selected':
        invalid['game']['screen_state']['selected_cards'] = [invalid['game']['screen_state']['cards'][0]]
    elif change == 'wrong_uuid':
        invalid['game']['screen_state']['cards'][0]['uuid'] = 'not-in-discard'
    else:
        invalid['live_run']['queued_cards'] = ['a-different-card']
    try:
        start_selection(s.comparator, invalid)
    except ValueError:
        pass
    else:
        raise AssertionError('unsafe selection accepted: ' + change)
print('PASS: Headbutt play/select replay, fresh picker search, continuation parity, duplicate UUIDs and invalid queues')
