"""Check the per-search memory budget: a heavy search stops at the budget and still returns a valid winning route.

The accounted bytes (MCTS edge arena, beam state pool) are deterministic, so the stop point is checked exactly
against the budget plus the stated overshoot margin, never against process RSS.
"""
import argparse
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--runtime-root', type=Path, default=root)
args = parser.parse_args()
sys.path.insert(0, str(args.runtime_root.resolve() / 'overlay'))
from backend import Advisor

# The MCTS checks the arena every 1024 simulations, so it may overshoot by that many expansions.
MARGIN = 32 << 20
SMALL = 256 << 10
advisor = Advisor()
S, N = advisor.search.sts, advisor.native_search
card = lambda name, up=0: dict(id=S.card_id_from_name(name), upgrades=up)


def fight():
    return S.BattleContext.from_snapshot(dict(seed=7, floor=1, turn=1, ascension=0,
        player=dict(current_hp=80, max_hp=80, block=0, energy=3),
        hand=[card('STRIKE_RED')] * 3 + [card('DEFEND_RED')] * 2,
        draw_pile=[card('STRIKE_RED')] * 6 + [card('DEFEND_RED')] * 4 + [card('BASH')], discard_pile=[], exhaust_pile=[],
        relics=[], potions=[],
        monsters=[dict(id=S.monster_id_from_name('CULTIST'), current_hp=48, max_hp=48, block=0,
                       move=S.monster_move_id_from_name('CULTIST_INCANTATION'))]), 7)


def plan(budget):
    N.resume()
    N.set_memory_budget(budget)
    return N.plan(fight(), 128000, [], 0, 0)


default = plan(3 << 30)
assert not default['memory_capped'] and 0 < default['memory_peak'] < (3 << 30), default['memory_peak']
capped = plan(SMALL)
assert capped['memory_capped'], 'a budget below one search tick must stop the search'
assert capped['memory_peak'] <= SMALL + MARGIN, capped['memory_peak']
assert capped['memory_peak'] < default['memory_peak'], 'the capped search must stop early'
assert capped['outcome'] == int(S.Outcome.PLAYER_VICTORY) and capped['actions'], 'the stop returns the best route so far'
replay = fight()
for bits in capped['actions']:
    N.execute(replay, bits & 0xffffffff)
assert int(replay.outcome) == capped['outcome'] and replay.player.cur_hp == capped['hp'], 'the route must replay to its result'
N.set_beam_only(40)    # the beam alone: its budget is the pooled BattleContext copies
try:
    beam = plan(SMALL)
    beam_free = plan(3 << 30)
finally:
    N.set_beam_only(0)
assert beam['memory_capped'] and not beam_free['memory_capped']
# The beam checks before every transition, so it overshoots by at most one pooled state (about 28 KB).
assert beam['memory_beam'] <= SMALL + (1 << 20) and beam['memory_beam'] < beam_free['memory_beam'], beam['memory_beam']
assert beam['outcome'] == int(S.Outcome.PLAYER_VICTORY) and beam['actions']
N.set_memory_budget(3 << 30)
print('PASS: memory budget stops a search at', capped['memory_peak'], 'of', SMALL, 'bytes; uncapped peak', default['memory_peak'])
