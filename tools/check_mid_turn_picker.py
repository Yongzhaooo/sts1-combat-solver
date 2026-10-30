"""A picker Seek opened mid-turn is searched and finishes Seek's own UseCardAction.

The root is the recorded Machine Learning state turned into the moment after Seek
was played: Seek is the card in play, its UseCardAction waits behind the grid, and
the draw pile is Defend or Sweeping Beam. Both lice have 5 HP and 1 energy is left,
so only Sweeping Beam wins this turn; the first legal choice is Defend.
"""
import copy
import json
from pathlib import Path
import sys
import uuid

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
import backend
from backend import Advisor, view_from_state, start_selection
from sim_patch.parity.adapter import ActionMapper

state = json.loads((root / 'overlay/test/recorded/DEFECT/machine-learning-root.json').read_text(encoding='utf-8'))
game = state['game_state']
combat = game['combat_state']
strike, defend = combat['hand'][2], combat['draw_pile'][0]
assert strike['id'] == 'Strike_B' and defend['id'] == 'Defend_B'
seek = dict(strike, id='Seek', name='Seek', type='SKILL', rarity='RARE', cost=0, base_cost=0, damage=-1,
            base_damage=-1, magic_number=1, base_magic_number=1, has_target=False, target='NONE',
            uuid=str(uuid.uuid4()), purge_on_use=False, dont_trigger_on_use=False, return_to_hand=False)
beam = dict(strike, id='Sweeping Beam', name='Sweeping Beam', rarity='COMMON', has_target=False,
            target='ALL_ENEMY', magic_number=1, base_magic_number=1, uuid=str(uuid.uuid4()))
combat['draw_pile'] = [defend, beam]
combat['card_in_play'] = seek
for monster in combat['monsters']:
    monster['current_hp'] = 5
game['screen_type'] = 'GRID'
# Seek's grid sorts the draw pile, so its order need not be the pile's.
game['screen_state'] = dict(cards=[beam, defend], selected_cards=[], num_cards=1, any_number=False,
                            for_upgrade=False, for_transform=False, for_purge=False, confirm_up=False)
game['solver_selection'] = dict(queued_cards=[], current_action=dict(
    amount=1, **{'class': 'BetterDrawPileToHandAction',
                 'class_full': 'com.megacrit.cardcrawl.actions.common.BetterDrawPileToHandAction'}),
    pending_actions=[dict(amount=1, exhaustCard=False, returnToHand=False, reboundCard=False, targetCard=seek,
                          **{'class': 'UseCardAction',
                             'class_full': 'com.megacrit.cardcrawl.actions.utility.UseCardAction'})])
state['available_commands'] = ['choose', 'key', 'click', 'wait', 'state']

saved = backend.SUPPORTED_CLASSES
backend.SUPPORTED_CLASSES = {**saved, 'DEFECT': 'test only'}
try:
    picker = view_from_state(copy.deepcopy(state))
finally:
    backend.SUPPORTED_CLASSES = saved
imported = {**picker, 'game': {**picker['game'], 'seed': int(picker['game']['seed']) % (1 << 64)}}

advisor = Advisor()
s = advisor.search
spec = start_selection(s.comparator, picker)
assert spec['task'] == 'MID_TURN' and spec['select_task'] == 'SEEK', spec
assert [a['kind'] for a in spec['queue']] == ['finish_card'], spec

# The pick resolves Seek's UseCardAction: Seek reaches the discard pile, as in the game.
battle = s.comparator.import_battle(imported, require_search_state=True)
s.native.import_start_selection(battle, spec)
assert not s.comparator.compare_battle(picker, battle)['differences']
mapper = ActionMapper(s.comparator, battle, picker)
pick = mapper.action('choose 0', picker, battle)
assert pick.is_valid(battle)
pick.execute(battle)
assert [c.id.name for c in battle.discard_pile].count('SEEK') == 1, 'Seek vanished instead of reaching the discard pile'
assert 'SWEEPING_BEAM' in [c.id.name for c in battle.hand]

# The picker is searched: the reply picks Sweeping Beam (grid position 0), not the first legal choice.
advisor.solve(picker, 2000)
assert not advisor.plan.get('mid_turn_pick') and advisor.plan['simulations'] > 0, advisor.plan
assert advisor.plan['outcome'] == 1 and advisor.plan['turns'] == 0, advisor.plan
reply = advisor.result(picker)
assert reply['status'] == 'ready' and reply['command'] == 'choose 0', reply

for change in ('missing_use', 'extra_queue', 'wrong_card'):
    invalid = copy.deepcopy(picker)
    if change == 'missing_use':
        invalid['live_run']['pending_actions'] = []
    elif change == 'extra_queue':
        invalid['live_run']['pending_actions'].append({'class': 'UnmappedAction'})
    else:
        invalid['live_run']['pending_actions'][0]['targetCard'] = dict(seek, uuid='not-in-play')
    try:
        start_selection(s.comparator, invalid)
    except ValueError:
        pass
    else:
        raise AssertionError('unsafe mid-turn picker accepted: ' + change)
print('PASS: mid-turn Seek picker finishes Seek, is searched, picks the winning card and rejects unsafe queues')
