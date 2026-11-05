"""High Focus mode trades a later kill for the Focus target, and plan reduction keeps it.

The root is the recorded Steam Barrier state with a 6 HP Cultist, Focus 24, no orbs, 1 energy and a
hand of Defragment and Strike. Off, the Strike kills now. On, the plan plays Defragment
first to end at Focus 25 and kills a turn later; the reduced plan still ends at 25. Combat
auto then prefers a bottle line that reaches the target over a cheaper one that does not.
"""
import json
from pathlib import Path
import sys
import uuid

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
import backend
from auto_policy import recommend
from backend import Advisor, view_from_state
from solver_search import FOCUS_TARGET, finalize_plan, focus_short, focus_target

state = json.loads((root / 'overlay/test/recorded/DEFECT/steam-barrier-root.json').read_text(encoding='utf-8'))
combat = state['game_state']['combat_state']
strike = next(c for c in combat['hand'] if c['id'] == 'Strike_B')
defragment = dict(strike, id='Defragment', name='Defragment', type='POWER', rarity='UNCOMMON', target='SELF',
                  has_target=False, damage=-1, base_damage=-1, magic_number=1, base_magic_number=1)
combat['hand'] = [dict(defragment, uuid=str(uuid.uuid4())), dict(strike, uuid=str(uuid.uuid4()))]
combat['player']['energy'] = 1
combat['player']['powers'] = [{'id': 'Focus', 'name': 'Focus', 'amount': FOCUS_TARGET - 1}]
combat['monsters'][0]['current_hp'] = 6
empty = combat['player']['orbs'][1]
combat['player']['orbs'] = [dict(empty) for _ in combat['player']['orbs']]  # an orb's values would have to include Focus
saved = backend.SUPPORTED_CLASSES
backend.SUPPORTED_CLASSES = {**saved, 'DEFECT': 'test only'}
try:
    view = view_from_state(state)
finally:
    backend.SUPPORTED_CLASSES = saved
view = {**view, 'game': {**view['game'], 'seed': int(view['game']['seed']) % (1 << 64)}}
advisor = Advisor()
s = advisor.search


def solve(target):
    advisor.native_search.set_focus_target(target)
    try:
        battle = s.comparator.import_battle(view, require_search_state=True)
        plan = dict(advisor.native_search.plan(battle, 2000, [], 0, 0))
        reduced = finalize_plan(s.comparator.import_battle(view, require_search_state=True), plan)
    finally:
        advisor.native_search.set_focus_target(0)
    end = s.comparator.import_battle(view, require_search_state=True)
    for bits in reduced['actions']:
        advisor.native_search.execute(end, bits & 0xffffffff)
    return plan, end


off, off_end = solve(0)
assert off['outcome'] == 1 and off['turns'] == 0 and off['focus'] == FOCUS_TARGET - 1, off
on, on_end = solve(FOCUS_TARGET)
assert on['outcome'] == 1 and on['turns'] >= 1 and on['focus'] == FOCUS_TARGET, on
assert int(on_end.outcome) == 1 and on_end.player.focus == FOCUS_TARGET, 'plan reduction dropped Defragment'
assert on['value'] - off['value'] > 100.0 * 100, (on['value'], off['value'])

game = {'solver_auto_context': {'focus_mode': True}}
assert focus_target(game) == FOCUS_TARGET and focus_target({'solver_auto_context': {}}) == 0
assert focus_short(game, {'focus': FOCUS_TARGET - 1}) and not focus_short(game, {'focus': FOCUS_TARGET})
assert not focus_short({}, {'focus': -3})
branches = [dict(id='no-potion', name='no-potion', available=True, won=True, loss=0, focus=FOCUS_TARGET - 1),
            dict(id='potion-0', name='potion-0', available=True, won=True, loss=0, focus=FOCUS_TARGET,
                 potion_uses=[{'slot': 0}])]
auto_game = view['game']
focus_game = {**auto_game, 'solver_auto_context': {**auto_game.get('solver_auto_context', {}), 'focus_mode': True}}
assert recommend(focus_game, branches)['branch'] == 'potion-0'
assert recommend(auto_game, branches)['branch'] == 'no-potion'
print('PASS: High Focus mode delays the kill to reach Focus', FOCUS_TARGET, 'and plan reduction keeps it')
