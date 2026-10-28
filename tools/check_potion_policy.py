"""Narrow policy checks; --native-root also replays the candidate's actual native actions."""
import argparse
from concurrent.futures import Future
from pathlib import Path
import sys
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--native-root', type=Path)
parser.add_argument('--java-receipt', type=Path)
args = parser.parse_args()
root = args.native_root or Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from auto_policy import recommend, smoke_opening, smoke_probe_keeps

game = dict(current_hp=80, max_hp=80, act=1, screen_type='NONE',
            combat_state={'turn': 1, 'monsters': []},
            solver_auto_context={'smoke_opening': True})
assert smoke_opening(game)
assert not smoke_opening({**game, 'solver_auto_context': {}})
assert not smoke_opening({**game, 'combat_state': {'turn': 2}})
assert not smoke_opening({**game, 'solver_auto_context': {'smoke_opening': False}})
for loss, keep in [(14, True), (15, False), (16, False)]:
    assert smoke_probe_keeps(dict(outcome=1, hp=80-loss, post_hp=80), game) is keep
assert not smoke_probe_keeps(dict(outcome=2, hp=0), game)
assert not smoke_probe_keeps(dict(outcome=0, hp=80), game)
assert not smoke_probe_keeps(dict(outcome=1, hp=80, used_fairy=1), game)
assert not smoke_probe_keeps(dict(outcome=1, hp=80, used_tail=True), game)
assert smoke_probe_keeps(dict(outcome=1, hp=64), {**game, 'solver_auto_context': {'smoke_loss_threshold': 17}})

baseline = dict(id='no-potion', available=True, won=True, loss=8, potion_uses=[], empty_potion_slots=0)
bottle = dict(id='potion-0', available=True, won=True, loss=0,
              potion_uses=[{'slot': 0}], empty_potion_slots=1)
supply_game = {**game, 'potions': [{'id': 'Strength Potion'}, {'id': 'FairyPotion'}],
               'solver_auto_context': {'potion_drop_probability': .4}}
assert recommend(supply_game, [baseline, bottle])['branch'] == 'no-potion'
statue = {**supply_game, 'relics': [{'id': 'White Beast Statue'}]}
assert recommend(statue, [baseline, bottle])['branch'] == 'potion-0'
assert recommend(statue, [baseline, {**bottle, 'empty_potion_slots': 0}])['branch'] == 'no-potion'
assert recommend({**statue, 'relics': statue['relics']+[{'id': 'Sozu'}]}, [baseline, bottle])['branch'] == 'no-potion'
assert recommend({**statue, 'solver_auto_context': {'potion_reward_blocked': True}}, [baseline, bottle])['branch'] == 'no-potion'
assert recommend(statue, [baseline, {**bottle, 'won': False}])['branch'] == 'no-potion'
escape = dict(id='potion-1', available=True, won=False, escaped=True, smoke_stop_loss=True, loss=0, potion_uses=[{'slot': 1}])
assert recommend(game, [baseline, escape])['branch'] == 'no-potion'  # manual comparison is unchanged
assert recommend({**game, 'solver_auto_context': {'run_auto': True}}, [baseline, escape])['branch'] == 'potion-1'
assert recommend({**game, 'solver_auto_context': {'run_auto': True}},
                 [{**baseline, 'loss': 16, 'growth_value': 99999},
                  {**escape, 'escaped': False, 'won': True, 'potion_uses': []}])['branch'] == 'potion-1'
print('PASS: actual Python policy entry, 14/15/16 raw loss, death/incomplete/revival, manual/auto, WBS/Sozu/reward/full-slot boundaries')
curse_branch = dict(baseline, loss=30, new_permanent_curse=True)
clean_branch = dict(bottle, loss=79, new_permanent_curse=False)
assert recommend(supply_game, [curse_branch, clean_branch])['branch'] == clean_branch['id']
assert recommend(supply_game, [curse_branch])['branch'] == curse_branch['id']
assert recommend(supply_game, [dict(curse_branch, new_permanent_curse=False), clean_branch])['branch'] == curse_branch['id']
assert not smoke_probe_keeps(dict(outcome=1, hp=80, new_permanent_curse=True), game)
from recovery import health
for new in (False, True):
    assert health(dict(hp=80, post_hp=80, max_hp=80, curse_hp=30,
                       new_permanent_curse=new), game, True)['new_permanent_curse'] is new
print('PASS: cross-potion recommendation prioritizes clean wins, preserves cursed rescue/sunk curses, and Smoke probe rejects a new curse')

boss = {**supply_game, 'ascension_level': 20, 'solver_auto_context': {'room': 'MonsterRoomBoss', 'boss_heal': True,
        'deck': [{'id': 'Strike_R', 'cost': 1, 'base_damage': 6}]}}
fifty = dict(baseline, loss=50)
block_bottle = {**bottle, 'id': 'potion-1', 'potion_uses': [{'slot': 1}]}
boss_potions = {**boss, 'potions': [{'id': 'Strength Potion'}, {'id': 'Block Potion'}]}
assert recommend({**supply_game, 'solver_auto_context': {'room': 'MonsterRoomBoss'}}, [fifty, bottle])['branch'] == 'potion-0'
assert recommend(boss, [fifty, dict(bottle, loss=20)])['branch'] == 'no-potion'  # saving 30 counts 7.5, under a strong bottle's 10
assert recommend(boss, [fifty, bottle])['branch'] == 'potion-0'  # saving 50 counts 12.5
assert recommend(boss_potions, [fifty, block_bottle])['branch'] == 'potion-1'  # an ordinary bottle still buys 50 HP
assert recommend(boss_potions, [dict(baseline, loss=16), block_bottle])['branch'] == 'no-potion'
assert recommend({**boss, 'ascension_level': 4}, [dict(baseline, loss=79), bottle])['branch'] == 'no-potion'
assert recommend(boss, [bottle])['branch'] == 'potion-0'  # no potion-free win: survival first
assert '1/4' in recommend(boss, [fifty, dict(bottle, loss=20)])['reason']
print('PASS: healing Boss counts a quarter of HP loss (none below A5)')

deck_game = {**supply_game, 'solver_auto_context': {'potion_drop_probability': .4,
             'deck': [{'id': 'Strike_R', 'cost': 1, 'base_damage': 6}]}}
for top in ('GamblersBrew', 'Swift Potion', 'DistilledChaos', 'HeartOfIron', 'CultistPotion'):
    held = {**deck_game, 'potions': [{'id': top}, {'id': 'Strength Potion'}]}
    assert recommend(held, [dict(baseline, loss=12), bottle])['branch'] == 'no-potion', top  # premium price 15
    assert recommend(held, [dict(baseline, loss=16), bottle])['branch'] == 'potion-0', top
strength = {**deck_game, 'potions': [{'id': 'Strength Potion'}]}
assert recommend(strength, [dict(baseline, loss=12), bottle])['branch'] == 'potion-0'  # ordinary strong price 10
print('PASS: hand-access and long-fight bottles (Gambler Brew by its game id) cost 15 HP, other strong bottles 10')

flex = {**deck_game, 'potions': [{'id': 'SteroidPotion'}],
        'map': [{'x': 0, 'y': 10, 'children': [{'x': 0, 'y': 11}]},
                {'x': 0, 'y': 11, 'symbol': 'E', 'children': []}],
        'solver_auto_context': {**deck_game['solver_auto_context'], 'x': 0, 'y': 10}}
comparison = [dict(baseline, loss=12), bottle]
assert recommend(flex, comparison)['assessment']['reserve']
assert recommend(flex, comparison)['branch'] == 'no-potion'  # keep the real Flex id for a nearby damage check
assert recommend(flex, [bottle])['branch'] == 'potion-0'  # preserving it cannot override the only winning line
assert recommend({**flex, 'map': [], 'solver_auto_context': {
    **flex['solver_auto_context'], 'y': 0}}, comparison)['branch'] == 'potion-0'
assert recommend({**flex, 'solver_auto_context': {**flex['solver_auto_context'],
    'deck': [{'id': 'Immolate', 'cost': 2, 'base_damage': 21}]}}, comparison)['branch'] == 'potion-0'
print('PASS: Flex uses its actual game id, retains the burst reserve, and preserves safe-use and survival choices')

if not args.native_root:
    raise SystemExit(0)
from backend import Advisor, TERMINAL_PENDING, unexplained_differences
advisor = Advisor()
engine = advisor.search.sts
native = advisor.native_search
potion = engine.potion_id_from_name
card = engine.card_id_from_name

# The recorded F48 smoke-bomb fault: the engine escapes the instant the potion lands and
# the game still reports UNDECIDED while the haze plays. That is a gap, not a divergence,
# so the rest of the plan is dropped instead of raising 实际状态偏离预测.
escape = {'differences': [{'path': '/outcome', 'kind': 'value',
                           'original': 'UNDECIDED', 'simulator': 'PLAYER_ESCAPE'}],
          'gaps': [{'kind': TERMINAL_PENDING}], 'observed_match': False}
assert unexplained_differences(escape) == []
assert len(unexplained_differences({**escape, 'gaps': []})) == 1  # the same loss without the gap still stops
# The tolerance is the escape only: the same gap on a predicted victory is a real divergence.
assert len(unexplained_differences({**escape, 'differences': [dict(escape['differences'][0], simulator='PLAYER_VICTORY')]})) == 1
assert unexplained_differences({'differences': [{'path': '/rng/card_random/0'}], 'gaps': []}) == []
assert unexplained_differences({'differences': [], 'gaps': [{'kind': TERMINAL_PENDING}]}) == []
print('PASS: a predicted terminal outcome the original battle is still playing is a gap, not a divergence')

def snapshot(hand=(), *, turn=1, played=0, damaged=0, hp=20, enemy_hp=50,
             potions=('SMOKE_BOMB', 'FAIRY_POTION', 'EMPTY_POTION_SLOT'), powers=(), enemy_powers=(),
             encounter='CULTIST', monsters=None, draw=()):
    return dict(seed=42, floor=1, ascension=0, turn=turn, encounter=int(getattr(engine.MonsterEncounter, encounter)),
                cards_played_this_turn=played, times_damaged=damaged,
                player=dict(current_hp=hp, max_hp=80, block=0, energy=6, powers=list(powers)),
                hand=[{'id': card(c)} for c in hand], draw_pile=[{'id': card(c)} for c in draw],
                discard_pile=[], exhaust_pile=[], relics=[], potions=[potion(p) for p in potions],
                monsters=monsters or [dict(id=engine.monster_id_from_name('JAW_WORM'), current_hp=enemy_hp,
                    max_hp=enemy_hp, block=0, move=engine.monster_move_id_from_name('JAW_WORM_CHOMP'), powers=list(enemy_powers))])

def battle(snap):
    return engine.BattleContext.from_snapshot(snap, snap['seed'])

def opening(snap, slots=(0,)):
    state = battle(snap)
    fingerprint = advisor.search.comparator.clone_fingerprint(state)
    native.resume()
    plan = native.plan(state, 1, list(slots), 0, 0, False, False, False, True)
    assert fingerprint == advisor.search.comparator.clone_fingerprint(state), 'planner mutated input'
    replay = state.clone()
    labels = []
    for bits in plan['actions']:
        action = engine.SearchAction.from_bits(bits & 0xffffffff)
        labels.append(('potion', int(action.source_idx)) if action.action_type == engine.SearchActionType.POTION
                      else ('card', replay.hand[action.source_idx].id.name))
        native.execute(replay, bits & 0xffffffff)
    assert int(replay.outcome) == plan['outcome'] and replay.player.cur_hp == plan['hp']
    return plan, replay, labels

plan, replay, labels = opening(snapshot())
assert labels == [('potion', 0)] and plan['hp'] == 20 and plan['outcome'] == int(engine.Outcome.PLAYER_ESCAPE)
assert replay.potions[1] == potion('FAIRY_POTION')
for bad in [snapshot(turn=2), snapshot(played=1), snapshot(damaged=1)]:
    try: opening(bad)
    except ValueError: pass
    else: raise AssertionError('late/damaged request accepted as opening')
try:
    native.plan(battle(snapshot()), 1, [0], 0, 0)
except ValueError: pass
else: raise AssertionError('missing Java opening proof accepted')
for encounter in ['SLIME_BOSS', 'SHIELD_AND_SPEAR']:
    snap = snapshot(encounter=encounter)
    if encounter == 'SHIELD_AND_SPEAR': snap['monsters'] *= 2
    try: opening(snap)
    except ValueError: pass
    else: raise AssertionError('unescapable encounter accepted')
plan, replay, labels = opening(snapshot(['REAPER']))
assert labels == [('card', 'REAPER'), ('potion', 0)] and plan['hp'] > 20
plain_hp = plan['hp']
plan, replay, labels = opening(snapshot(['FLEX', 'INFLAME', 'REAPER'], hp=79))
assert labels == [('card', 'REAPER'), ('potion', 0)] and plan['hp'] == 80
plan, replay, labels = opening(snapshot(['REAPER'], hp=79,
    potions=('SMOKE_BOMB', 'STRENGTH_POTION', 'FAIRY_POTION')), slots=(0,1))
assert labels == [('card', 'REAPER'), ('potion', 0)] and replay.potions[1] == potion('STRENGTH_POTION')
plan, replay, labels = opening(snapshot(['JAX', 'REAPER']))
assert labels == [('card', 'REAPER'), ('potion', 0)] and plan['hp'] == plain_hp
plan, replay, labels = opening(snapshot(['JAX', 'LIMIT_BREAK', 'REAPER']))
assert labels[:2] == [('card', 'JAX'), ('card', 'LIMIT_BREAK')] and plan['hp'] == 25
plan, replay, labels = opening(snapshot(['LIMIT_BREAK', 'REAPER']))
assert labels == [('card', 'REAPER'), ('potion', 0)], 'zero Strength doubling is not an immediate gain'
no_attack = snapshot(['SPOT_WEAKNESS', 'REAPER'])
no_attack['monsters'][0]['move'] = engine.monster_move_id_from_name('JAW_WORM_BELLOW')
plan, replay, labels = opening(no_attack)
assert labels == [('card', 'REAPER'), ('potion', 0)], 'inactive Spot Weakness is not an immediate gain'
plan, replay, labels = opening(snapshot(['FLEX', 'INFLAME', 'SPOT_WEAKNESS', 'LIMIT_BREAK', 'REAPER']))
assert labels[-2:] == [('card', 'REAPER'), ('potion', 0)] and plan['hp'] > plain_hp
assert all(name in {'FLEX', 'INFLAME', 'SPOT_WEAKNESS', 'LIMIT_BREAK', 'REAPER'} for kind, name in labels if kind == 'card')
plan, replay, labels = opening(snapshot(['DEMON_FORM', 'RUPTURE', 'REAPER']))
assert labels == [('card', 'REAPER'), ('potion', 0)]
plan, replay, labels = opening(snapshot(['BATTLE_TRANCE'], draw=['REAPER']))
assert labels == [('potion', 0)], labels
plan, replay, labels = opening(snapshot(['REAPER'], enemy_hp=3))
assert plan['outcome'] == 1 and labels == [('card', 'REAPER')] and replay.potions[0] == potion('SMOKE_BOMB')
thorns = [{'id': engine.monster_status_id_from_name('THORNS'), 'amount': 10}]
plan, replay, labels = opening(snapshot(['REAPER'], hp=1, enemy_powers=thorns))
assert labels == [('potion', 0)] and plan['hp'] == 1, 'dead Reaper must not escape or fake healing'
plan, replay, labels = opening(snapshot(['REAPER'], potions=('SMOKE_BOMB', 'STRENGTH_POTION', 'FAIRY_POTION')))
assert ('potion', 1) not in labels and replay.potions[1] == potion('STRENGTH_POTION')
plan, replay, labels = opening(snapshot(['REAPER'], potions=('SMOKE_BOMB', 'STRENGTH_POTION', 'FAIRY_POTION')), slots=(0,1))
assert labels[0] == ('potion', 1) and labels[-1] == ('potion', 0) and replay.potions[2] == potion('FAIRY_POTION')
# A later real Smoke remains replayable. The overlay policy must not narrow engine action legality.
late = battle(snapshot(turn=2))
native.execute(late, 1 << 29)
assert late.outcome == engine.Outcome.PLAYER_ESCAPE

# Exact same generated stream, actual received slots differ. No forced passive consumption.
snap = snapshot(potions=('ENTROPIC_BREW', 'STRENGTH_POTION', 'FAIRY_POTION'))
a, b = battle(snap), battle(snap)
native.execute(a, (1 << 29) | 1)
native.execute(a, 1 << 29)
native.execute(b, 1 << 29)
assert a.rng_states['potion'] == b.rng_states['potion'], 'brew RNG must use capacity, not empty-slot count'
assert a.potions[0] == b.potions[0] and a.potions[1] != potion('EMPTY_POTION_SLOT')
assert b.potions[1] == potion('STRENGTH_POTION')
assert a.potions[2] == b.potions[2] == potion('FAIRY_POTION')
print('BREW_RNG', dict(a.rng_states['potion']), 'received', a.potions, b.potions)
if args.java_receipt:
    import json
    truth = json.loads(args.java_receipt.read_text(encoding='utf-8'))
    ids = {'Regen Potion': 'REGEN_POTION', 'SmokeBomb': 'SMOKE_BOMB', 'FairyPotion': 'FAIRY_POTION',
           'Strength Potion': 'STRENGTH_POTION', 'Potion Slot': 'EMPTY_POTION_SLOT'}
    for state, name in [(a, 'strength_then_brew'), (b, 'brew_first')]:
        row = truth[name]
        assert state.potions == [potion(ids[p]) for p in row['potions']]
        assert dict(state.rng_states['potion']) == {k: int(row[k]) for k in ('seed0', 'seed1', 'counter')}
    sozu = snapshot(potions=('ENTROPIC_BREW', 'STRENGTH_POTION', 'FAIRY_POTION'))
    sozu['relics'] = [{'id': engine.relic_id_from_name('SOZU')}]
    state = battle(sozu)
    native.execute(state, (1 << 29) | 1); native.execute(state, 1 << 29)
    assert state.potions == [potion(ids[p]) for p in truth['sozu']['potions']]
    assert dict(state.rng_states['potion']) == {k: int(truth['sozu'][k]) for k in ('seed0', 'seed1', 'counter')}
    print('PASS: exact native/Java Entropic potion identities, both RNG words and counter, both orders and Sozu')
try: native.execute(a, (1 << 29) | 2)
except ValueError: pass
else: raise AssertionError('passive Fairy actively consumed')
ordered = snapshot(['BLUDGEON', 'ANGER', 'ANGER'], enemy_hp=50,
                   potions=('ENTROPIC_BREW', 'STRENGTH_POTION', 'FAIRY_POTION'))
ordered['player']['energy'] = 3
native.resume()
ordered_plan = native.plan(battle(ordered), 128, [0, 1], 0, 0)
uses = [int(engine.SearchAction.from_bits(bits & 0xffffffff).source_idx)
        for bits in ordered_plan['actions'] if (int(bits) & 0xffffffff) >> 29 == 1]
assert uses[:2] == [1, 0] and ordered_plan['outcome'] == 1, (uses, ordered_plan)
assert ordered_plan['used_fairy'] == 0 and ordered_plan['empty_potion_slots'] == 0
print('PASS: actual native authorized search finds Strength before Brew, refills real slots and keeps Fairy')
# A 1-simulation no-potion probe is one bounded tree call and never calls beam/replanning.
native.resume()
probe = native.plan(battle(snapshot(['STRIKE_RED'])), 1, [], 0, 0, False, False, False, False, True)
assert probe['simulations'] <= 1 and not probe['loop_beam_ran']
assert not any((int(bits) & 0xffffffff) >> 29 == 1 for bits in probe['actions'])
# A high-growth route can outscore raw HP. The probe must retain any qualifying
# witness through the actual terminal hook, rather than judging only its main-score winner.
competing = snapshot(['BLUDGEON', 'ANGER', 'ANGER', 'FEED'], hp=40, enemy_hp=44)
competing['player']['energy'] = 3
native.set_max_hp_gain_weight(100.0)
native.resume()
main_score = native.plan(battle(competing), 2000, [], 0, 0, False, False, False, False, True, 0)
native.resume()
qualified = native.plan(battle(competing), 2000, [], 0, 0, False, False, False, False, True, 1)
native.set_max_hp_gain_weight(1.0)
assert main_score['max_hp'] > 80 and main_score['hp'] < 40, main_score
assert qualified['fast_ready'] and qualified['outcome'] == 1 and qualified['hp'] >= 40, qualified
assert qualified['simulations'] < 2000, 'qualifying complete witness should stop the bounded probe'
print('PASS: actual terminal hook preserves any raw-low-loss witness despite a competing high-growth/high-loss main-score winner')
# Panel row consumes actual actions and displays the actual escape loss/uses.
advisor.root = battle(snapshot(['REAPER']))
view = {'game': {**game, 'current_hp': 20, 'potions': [{'id': 'SmokeBomb'}, {'id': 'FairyPotion'}, {'id': 'Potion Slot'}]}}
native.resume()
plan = native.plan(advisor.root, 1, [0], 0, 0, False, False, False, True)
f = Future(); f.set_result(plan)
advisor.potion_branch((0,), {0: '烟雾弹'}, f, time.monotonic(), view, 2000)
row = advisor.branch_info[-1]
assert row['available'] and row['escaped'] and row['name'] == 'T1 收割后烟雾弹逃跑'
assert row['potion_uses'] == [{'slot': 0, 'turn': 1, 'step': 2}] and row['raw_hp'] == plan['hp']
print('PASS: actual native opening/direct/Reaper/strength/death/late/boss, replay immutability, budget, panel actions, Entropic slots/RNG, passive Fairy')

# Drive the real automatic orchestration, recording all actual native calls.
class Calls:
    def __init__(self): self.calls = []
    def __getattr__(self, name): return getattr(native, name)
    def plan(self, *values, **kwargs):
        self.calls.append((values, kwargs))
        return native.plan(*values, **kwargs)

for snap, expected, calls in [(snapshot(), 'potion-0', 2),
                               (snapshot(['REAPER'], enemy_hp=3), 'no-potion', 1)]:
    advisor.root = battle(snap)
    advisor.search.battle = advisor.root
    advisor.search.actions.clear()
    advisor.root_game = {**game, 'current_hp': 20,
        'solver_auto_context': {'run_auto': True, 'smoke_opening': True, 'smoke_probe_budget': 128},
        'potions': [{'id': 'SmokeBomb'}, {'id': 'FairyPotion'}, {'id': 'Potion Slot'}]}
    advisor.native_search = recorded = Calls()
    native.resume()
    before = advisor.search.comparator.clone_fingerprint(advisor.root)
    advisor.solve_smoke_opening({'game': advisor.root_game}, 0, 0, time.monotonic(), before)
    assert advisor.selected_branch == expected and len(recorded.calls) == calls
    assert recorded.calls[0][0][1:3] == (128, []) and recorded.calls[0][0][-2] is True and recorded.calls[0][0][-1] == 15
    if calls == 2: assert recorded.calls[1][0][2] == [0]
    assert advisor.search.actions, 'automatic policy returned no executable action'
    assert recommend(advisor.root_game, advisor.branch_info)['branch'] == expected
# Acts 1-3 tiers divide native.plan by 10; the probe still gets its own setting (F35 hallway escaped on a 1/10 probe).
advisor.root = advisor.search.battle = battle(snapshot())
advisor.search.actions.clear()
advisor.native_search = recorded = Calls()
recorded._act13_original_plan, recorded.plan = recorded.plan, lambda root, sims, *a: recorded._act13_original_plan(root, max(1, sims // 10), *a)
native.resume()
advisor.solve_smoke_opening({'game': advisor.root_game}, 0, 0, time.monotonic(), advisor.search.comparator.clone_fingerprint(advisor.root))
assert recorded.calls[0][0][1] == 128, recorded.calls[0][0][1]
advisor.native_search = native
print('PASS: actual backend automatic bounded probe keeps low-loss victory or immediately chooses Smoke without deep/other-potion calls')

# Implant is a permanent-deck choice, including the tempting Darkstone/Feed reward.
def parasite_plan(*, darkstone=True, omamori=False, implanted=False, rescue=False, feed=False):
    snap = snapshot(['FEED' if feed else 'STRIKE_RED'] if not rescue else [], hp=1,
                    enemy_hp=1, potions=(), encounter='WRITHING_MASS', draw=['FEED'])
    snap['player']['max_hp'] = 80
    snap['player']['energy'] = 0 if rescue else 1
    snap['monsters'][0].update(id=engine.monster_id_from_name('WRITHING_MASS'),
        move=engine.monster_move_id_from_name('WRITHING_MASS_WITHER' if implanted else 'WRITHING_MASS_IMPLANT'), misc_info=int(implanted))
    snap['relics'] = [dict(id=engine.relic_id_from_name(name), data=2) for name in
                      (['DARKSTONE_PERIAPT'] if darkstone else []) + (['OMAMORI'] if omamori else [])]
    state = battle(snap)
    native.resume()
    result = native.plan(state, 512, [], 0, 0)
    end = state.clone()
    for bits in result['actions']:
        native.execute(end, bits & 0xffffffff)
    assert result['outcome'] == 1 and end.player.cur_hp > 0
    return result

clean = parasite_plan()
assert clean['curse_hp'] == 0 and clean['max_hp'] == 80 and clean['post_hp'] == 1, clean
assert clean['new_permanent_curse'] is False
rescue = parasite_plan(rescue=True)
assert rescue['curse_hp'] == 30 and rescue['max_hp'] == 89 and 1 < rescue['value'] < 2, rescue
assert rescue['new_permanent_curse'] is True
assert clean['value'] > rescue['value'], (clean, rescue)
assert 2 < clean['value'] < 3
dead_snap = snapshot([], hp=1, enemy_hp=100, potions=(), encounter='WRITHING_MASS')
dead_snap['player']['energy'] = 0
dead_snap['monsters'][0].update(id=engine.monster_id_from_name('WRITHING_MASS'),
    move=engine.monster_move_id_from_name('WRITHING_MASS_STRONG_STRIKE'))
dead = native.plan(battle(dead_snap), 1, [], 0, 0)
assert dead['outcome'] == 2 and rescue['value'] > dead['value'], (rescue, dead)
without_relic = parasite_plan(darkstone=False, rescue=True)
assert without_relic['curse_hp'] == 30 and without_relic['max_hp'] == 83, without_relic
blocked = parasite_plan(omamori=True, rescue=True)
assert blocked['curse_hp'] == 15 and blocked['max_hp'] == 83, blocked
assert blocked['new_permanent_curse'] is False
sunk = parasite_plan(implanted=True, feed=True)
ordinary = parasite_plan(darkstone=False, implanted=True, feed=True)
assert sunk['max_hp'] == ordinary['max_hp'] == 83, (sunk, ordinary)
assert abs(sunk['value']-ordinary['value']) < 1e-6, (sunk, ordinary)
assert sunk['value'] > 0
assert sunk['new_permanent_curse'] is False
feed_win = parasite_plan(feed=True)
assert feed_win['curse_hp'] == 0 and feed_win['max_hp'] == 83
assert feed_win['value'] > clean['value'], (feed_win, clean)
assert abs(sunk['value']-parasite_plan(implanted=True)['value']-1200) < 1e-6
print('PASS: 1-HP clean victory outranks Darkstone/Feed curse rewards, cursed rescue survives, Omamori blocks permanent curse, sunk Implant preserves later Feed')
