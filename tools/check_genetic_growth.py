"""Genetic Algorithm's permanent growth is valued, so the search plays it (issue #102).

The root is the recorded Steam Barrier state with hand slot 0 swapped for Defend_B or
Genetic Algorithm (misc 1) and 3 energy. Both lines kill the Cultist next turn without
losing HP, so the only difference is Genetic Algorithm's deck growth: 2 (upgraded 3)
points priced at Feed's permanent max-HP premium, 3 HP-equivalents each by default.
"""
import copy
import json
from pathlib import Path
import sys
import uuid

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
import backend
from backend import Advisor, view_from_state

state = json.loads((root / 'overlay/test/recorded/DEFECT/steam-barrier-root.json').read_text(encoding='utf-8'))
defend = state['game_state']['combat_state']['hand'][1]
assert defend['id'] == 'Defend_B'
advisor = Advisor()
s = advisor.search


def solve(slot0):
    probe = copy.deepcopy(state)
    combat = probe['game_state']['combat_state']
    combat['hand'][0] = dict(defend, uuid=str(uuid.uuid4()), **slot0)
    combat['player']['energy'] = 3
    saved = backend.SUPPORTED_CLASSES
    backend.SUPPORTED_CLASSES = {**saved, 'DEFECT': 'test only'}
    try:
        view = view_from_state(probe)
    finally:
        backend.SUPPORTED_CLASSES = saved
    view = {**view, 'game': {**view['game'], 'seed': int(view['game']['seed']) % (1 << 64)}}
    plan = dict(advisor.native_search.plan(s.comparator.import_battle(view, require_search_state=True), 2000, [], 0, 0))
    battle = s.comparator.import_battle(view, require_search_state=True)
    for bits in plan['actions']:
        advisor.native_search.execute(battle, bits & 0xffffffff)
    misc = [c.special_data for c in [*battle.hand, *battle.draw_pile, *battle.discard_pile, *battle.exhaust_pile]
            if c.id.name == 'GENETIC_ALGORITHM']
    return plan, misc


genetic = dict(id='Genetic Algorithm', name='Genetic Algorithm', rarity='COMMON', exhausts=True,
               base_block=1, block=1, base_magic_number=2, magic_number=2, misc=1)
base, _ = solve({})
assert base['outcome'] == 1 and base['hp'] == 47, base
for upgrades, growth in ((0, 2), (1, 3)):
    plan, misc = solve(dict(genetic, upgrades=upgrades))
    assert plan['outcome'] == 1 and plan['hp'] == base['hp'] and plan['turns'] == base['turns'], plan
    assert misc == [1 + growth], ('Genetic Algorithm was not played', misc)
    assert abs(plan['value'] - base['value'] - 300.0 * growth) < 1e-6, (plan['value'], base['value'])
print('PASS: Genetic Algorithm growth is valued at Feed\'s max-HP premium and the search plays it')
