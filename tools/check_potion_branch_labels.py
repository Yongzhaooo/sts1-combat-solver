"""Issue #95: an unavailable bottle row says why, and a bottle-set row never reads like a single-bottle row."""
import argparse
from concurrent.futures import Future
from pathlib import Path
import sys
import time

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
root = parser.parse_args().root.resolve()
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor

advisor = Advisor()
S = advisor.search.sts
advisor.root = S.BattleContext.from_snapshot(dict(seed=1, floor=1, turn=1, ascension=20,
    player=dict(current_hp=72, max_hp=80, block=0, energy=3),
    hand=[dict(id=S.card_id_from_name('STRIKE_RED'), upgrades=0)],
    draw_pile=[], discard_pile=[], exhaust_pile=[], relics=[],
    potions=[S.potion_id_from_name('SPEED_POTION'), S.potion_id_from_name('FIRE_POTION')],
    monsters=[dict(id=S.monster_id_from_name('CULTIST'), current_hp=1, max_hp=48,
                   block=0, move=S.monster_move_id_from_name('CULTIST_INCANTATION'))]), 1)
game = {'current_hp': 72, 'max_hp': 80, 'act': 1, 'ascension_level': 20,
        'potions': [{'id': 'SpeedPotion', 'can_use': True}, {'id': 'Fire Potion', 'can_use': True}],
        'combat_state': {'turn': 1, 'monsters': []}, 'solver_auto_context': {}}
names = {0: '速度药水', 1: '火焰药水'}
strike = int(S.SearchAction(S.SearchActionType.CARD, 0, 0).bits)
fire = int(S.SearchAction(S.SearchActionType.POTION, 1, 0).bits)
win = dict(outcome=int(S.Outcome.PLAYER_VICTORY), hp=72, post_hp=78, max_hp=80, value=10000, empty_potion_slots=0)


def branch(slots, plan):
    advisor.branch_info = []
    future = Future()
    future.set_result(plan)
    advisor.potion_branch(slots, names, future, time.monotonic(), {'game': game}, 8000)
    return advisor.branch_info[-1]


# 1. A finished search whose best win keeps the bottle is not "no route found"; a cut search or a loss still is.
kept = branch((1,), dict(win, actions=[strike]))
assert not kept['available'] and kept['message'] == '搜索完成，最佳胜线保留药水', kept
assert branch((1,), dict(win, actions=[strike], memory_capped=True))['message'] == '搜索提前结束，未找到使用路线'
advisor.interrupt.set()
assert branch((1,), dict(win, actions=[strike]))['message'] == '搜索提前结束，未找到使用路线'
advisor.interrupt.clear()
assert branch((1,), dict(win, actions=[], outcome=int(S.Outcome.UNDECIDED)))['message'] == '本轮搜索未找到使用路线'
print('PASS: an unused bottle row tells a kept bottle from a cut or losing search')

# 2. Bottles 0+1 whose plan drinks only bottle 1 names the undrunk bottle, so it differs from the bottle-1 row.
single = branch((1,), dict(win, actions=[fire], empty_potion_slots=1))
pair = branch((0, 1), dict(win, actions=[fire], empty_potion_slots=1))
assert single['available'] and pair['available'] and single['id'] == 'potion-1' and pair['id'] == 'potion-0+1'
assert single['name'] == 'T1 火焰药水', single['name']
assert pair['name'] == 'T1 火焰药水 · 速度药水 未用', pair['name']
print('PASS: a bottle-set row that leaves a bottle undrunk is labelled apart from the single-bottle row')
