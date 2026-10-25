"""Check the importer reads the character and the optional character fields from a snapshot.

Needs the built runtime (tools/build_solver_runtime.py), not the game.
"""
import copy
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import RNG_NAMES

s = Advisor().search
sts = s.sts
view = view_from_state(json.loads((root / 'overlay/test/live-root.json').read_text(encoding='utf-8')))
game = copy.deepcopy(view['game'])
game['combat_state']['rngs'] = {k: view['rng'][name] for k, name in RNG_NAMES.items()}
snapshot = s.comparator.bridge.build_snapshot(game)
seed = int(game['seed']) % (1 << 64)


def load(edit=lambda value: None):
    value = copy.deepcopy(snapshot)
    edit(value)
    return sts.BattleContext.from_snapshot(value, seed).player


def refused(edit):
    try:
        load(edit)
    except ValueError:
        return True
    return False


# The producer writes the game's class; an Ironclad snapshot still imports as Ironclad,
# with or without the field (snapshots from before it existed).
assert snapshot['character'] == 'IRONCLAD' and load().character == 'IRONCLAD'
assert load(lambda v: v.pop('character')).character == 'IRONCLAD'
for name, engine_name in (('IRONCLAD', 'IRONCLAD'), ('THE_SILENT', 'SILENT'), ('DEFECT', 'DEFECT'), ('WATCHER', 'WATCHER')):
    assert load(lambda v: v.update(character=name)).character == engine_name, name
assert refused(lambda v: v.update(character='SILENT')), 'an unknown character must not import as Ironclad'

# Absent optional fields mean none.
player = load()
assert (player.stance, player.orb_slots, player.has_status(sts.PlayerStatus.MANTRA)) == ('NEUTRAL', 0, False)

for stance in ('NEUTRAL', 'CALM', 'WRATH', 'DIVINITY'):
    assert load(lambda v: v['player'].update(stance=stance)).stance == stance, stance
assert refused(lambda v: v['player'].update(stance='wrath'))
assert load(lambda v: v['player'].update(orb_slots=3)).orb_slots == 3
assert load(lambda v: v['player'].update(orbs=[])).orb_slots == 0

# Orbs: CommunicationMod exports every slot, a null id for an empty one, with the
# focus-applied passive and evoke amounts. No card raises an orb's evoke amount, so
# the importer insists on evoke == max(0, base + focus) and keeps only Dark's
# accumulated counter.
lightning = {'id': 'Lightning', 'passive': 3, 'evoke': 8}
frost = {'id': 'Frost', 'passive': 2, 'evoke': 5}
dark = {'id': 'Dark', 'passive': 6, 'evoke': 12}
plasma = {'id': 'Plasma', 'passive': 1, 'evoke': 2}
empty = {'id': None, 'passive': 0, 'evoke': 0}
assert load(lambda v: v['player'].update(orbs=[lightning], orb_slots=3)).orbs == [
    {'id': 'LIGHTNING', 'dark_counter': 0}]
assert refused(lambda v: v['player'].update(orbs=[{'id': 'Lightning', 'passive': 3, 'evoke': 11}], orb_slots=3)), \
    'no card adds an evoke bonus, so 11 must not import over base 8'
assert load(lambda v: v['player'].update(orbs=[frost, dark, plasma, empty], orb_slots=4)).orbs == [
    {'id': 'FROST', 'dark_counter': 0},
    {'id': 'DARK', 'dark_counter': 12},
    {'id': 'FUSION', 'dark_counter': 0}]
assert load(lambda v: v['player'].update(orbs=[empty, empty], orb_slots=2)).orbs == []
assert load(lambda v: (v['player']['powers'].append({'id': int(sts.PlayerStatus.FOCUS), 'amount': 2}),
                       v['player'].update(orbs=[{'id': 'Lightning', 'passive': 5, 'evoke': 10}], orb_slots=3))).orbs == [
    {'id': 'LIGHTNING', 'dark_counter': 0}], 'focus is part of the exported evoke amount'
assert refused(lambda v: v['player'].update(orbs=[{'id': 'Lightning', 'passive': 3, 'evoke': 7}], orb_slots=3)), \
    'an evoke amount below base plus focus must not import'
# At negative Focus the game clamps an orb's evoke amount at 0, so 0 is the only
# value base + focus can produce there; it must import as 0, never as a bonus.
assert load(lambda v: (v['player']['powers'].append({'id': int(sts.PlayerStatus.FOCUS), 'amount': -9}),
                       v['player'].update(orbs=[{'id': 'Lightning', 'passive': 0, 'evoke': 0}], orb_slots=3))).orbs == [
    {'id': 'LIGHTNING', 'dark_counter': 0}], 'a clamped evoke at negative focus imports as it is'
assert refused(lambda v: (v['player']['powers'].append({'id': int(sts.PlayerStatus.FOCUS), 'amount': -9}),
                          v['player'].update(orbs=[{'id': 'Lightning', 'passive': 0, 'evoke': 5}], orb_slots=3))), \
    'an evoke above the clamped base + focus must not import as a bonus'
assert refused(lambda v: v['player'].update(orbs=[{'id': 'Nope', 'passive': 1, 'evoke': 1}], orb_slots=3))
assert refused(lambda v: v['player'].update(orbs=[lightning, frost], orb_slots=1))
assert load(lambda v: (v['player'].update(orbs=[lightning, frost]), v['player'].pop('orb_slots'))).orb_slots == 2

mantra = sts.PlayerStatus.MANTRA
assert load(lambda v: v['player'].update(mantra=4)).get_status(mantra) == 4
assert refused(lambda v: v['player'].update(mantra=-1))
with_power = lambda amount: (lambda v: v['player']['powers'].append({'id': int(mantra), 'amount': amount}))
assert load(lambda v: (with_power(3)(v), v['player'].update(mantra=3))).get_status(mantra) == 3
assert refused(lambda v: (with_power(3)(v), v['player'].update(mantra=4))), 'mantra must agree with its power'

battle = sts.BattleContext.from_snapshot(snapshot, seed)
assert not any(card.retain for card in battle.hand)
retained = copy.deepcopy(snapshot)
retained['hand'][0]['retain'] = True
assert sts.BattleContext.from_snapshot(retained, seed).hand[0].retain
# End to end: CommunicationMod's orb list survives the Python producer and the importer.
with_orbs = copy.deepcopy(game)
with_orbs['combat_state']['player']['orbs'] = [
    {'id': 'Lightning', 'passive_amount': 3, 'evoke_amount': 8},
    {'id': None, 'passive_amount': 0, 'evoke_amount': 0},
    {'id': 'Dark', 'passive_amount': 6, 'evoke_amount': 18},
]
orbed = s.comparator.bridge.build_snapshot(with_orbs)
assert orbed['player']['orb_slots'] == 3
assert [orb['id'] for orb in orbed['player']['orbs']] == ['Lightning', None, 'Dark']
assert sts.BattleContext.from_snapshot(orbed, seed).player.orbs == [
    {'id': 'LIGHTNING', 'dark_counter': 0},
    {'id': 'DARK', 'dark_counter': 18}]

# Defect powers that the game exports under a different id, or folds into another
# field. Machine Learning applies the game's DrawPower, whose id is "Draw"; the
# engine keeps that in card_draw_per_turn (CommunicationMod already reports the
# raised value), so it must not be imported as a status. Heatsinks and Self Repair
# are statuses whose game ids are "Heatsink" and "Repair".
draw_game = copy.deepcopy(game)
draw_game['combat_state']['player']['powers'] = [{'id': 'Draw', 'amount': 1}]
draw_game['combat_state']['card_draw_per_turn'] = 6
draw_snap = s.comparator.bridge.build_snapshot(draw_game)
assert draw_snap['player']['powers'] == [], draw_snap['player']['powers']
assert draw_snap['player']['card_draw_per_turn'] == 6, draw_snap['player']['card_draw_per_turn']
assert sts.BattleContext.from_snapshot(draw_snap, seed).player.card_draw_per_turn == 6

for game_id, engine_name in (('Heatsink', 'HEATSINKS'), ('Repair', 'SELF_REPAIR')):
    powered = copy.deepcopy(game)
    powered['combat_state']['player']['powers'] = [{'id': game_id, 'amount': 2}]
    powered_snap = s.comparator.bridge.build_snapshot(powered)
    assert powered_snap['player']['powers'] == [
        {'id': int(sts.player_status_id_from_name(engine_name)), 'amount': 2, 'just_applied': False}], game_id

# Only those known ids are skipped: an unrecognised power still has to be refused.
unknown_power = copy.deepcopy(game)
unknown_power['combat_state']['player']['powers'] = [{'id': 'NotAPower', 'amount': 1}]
try:
    s.comparator.bridge.build_snapshot(unknown_power)
except ValueError:
    pass
else:
    raise AssertionError('an unknown player power must not be silently dropped')

# States recorded from real games, not hand-written. The class gate guards the live
# product only, so it is widened while a Defect root becomes a view and put back after;
# each state is then imported and re-checked by the same comparison the live gate runs.
# overlay/test/recorded/ holds only the fixtures this contribution added; see its README.
import backend

RECORDED_DEFECT = root / 'overlay/test/recorded/DEFECT'


def recorded_defect(name):
    state = json.loads((RECORDED_DEFECT / name).read_text(encoding='utf-8'))
    saved = backend.SUPPORTED_CLASSES
    backend.SUPPORTED_CLASSES = {**saved, 'DEFECT': 'test only'}
    try:
        view = view_from_state(copy.deepcopy(state))
    finally:
        backend.SUPPORTED_CLASSES = saved
    # backend.py normalizes the signed Java seed for the native importer only.
    imported = {**view, 'game': {**view['game'], 'seed': int(view['game']['seed']) % (1 << 64)}}
    battle = s.comparator.import_battle(imported, require_search_state=True)
    # The live gate refuses any difference, legacy paths included, so these fixtures are
    # held to the same bar rather than to the looser one the older checks use.
    return view, battle, s.comparator.compare_battle(view, battle)['differences']


# Machine Learning, which the game reports as the "Draw" power. Before the skip above
# this state raised "unknown enum name: Draw", so it is that fix's recorded regression.
view, battle, diffs = recorded_defect('machine-learning-root.json')
assert view['game']['class'] == 'DEFECT'
assert [(p['id'], p['amount']) for p in view['game']['combat_state']['player']['powers']] == [('Draw', 1)]
assert s.comparator.bridge.build_snapshot(view['game'])['player']['powers'] == []
assert battle.player.card_draw_per_turn == 6, battle.player.card_draw_per_turn
assert len(battle.hand) == 4, [c.id for c in battle.hand]
assert not diffs, diffs[:3]

# Creative AI. The game reported a 2-cost Buffer in hand while the engine made every
# generated card free, which is the divergence this state recorded.
view, battle, diffs = recorded_defect('creative-ai-root.json')
assert [(p['id'], p['amount']) for p in view['game']['combat_state']['player']['powers']] == [('Creative AI', 1)]
game_first = view['game']['combat_state']['hand'][0]
assert (game_first['id'], game_first['cost']) == ('Buffer', 2), game_first
assert battle.hand[0].cost_for_turn == 2, battle.hand[0].cost_for_turn
assert not diffs, diffs[:3]

# Steam Barrier already played once. The game lowers the card's own baseBlock and leaves
# misc at 0; the engine carries that count in specialData, so the importer has to derive
# it from the block or the next play would grant 6 Block instead of 5.
view, battle, diffs = recorded_defect('steam-barrier-root.json')
steam = view['game']['combat_state']['discard_pile'][6]
assert (steam['id'], steam['base_block'], steam['misc']) == ('Steam', 5, 0), steam
steam_snap = s.comparator.bridge.build_snapshot(view['game'])['discard_pile'][6]
assert steam_snap['misc'] == 1, steam_snap
assert not diffs, diffs[:3]

# Defect cardIDs that differ from the engine enum name (the game's class names:
# Gash is Claw, Redo is Recursion, Steam is Steam Barrier, Undo is Equilibrium,
# Lockon is Bullseye, Steam Power is Overclock, Conserve Battery is Charge
# Battery, and the basics use the _B suffix).
from steam.steam_mcts import card_snapshot
for internal, enum in (('Strike_B', 'STRIKE_BLUE'), ('Defend_B', 'DEFEND_BLUE'),
                       ('Conserve Battery', 'CHARGE_BATTERY'), ('Gash', 'CLAW'),
                       ('Lockon', 'BULLSEYE'), ('Redo', 'RECURSION'),
                       ('Steam', 'STEAM_BARRIER'), ('Steam Power', 'OVERCLOCK'),
                       ('Undo', 'EQUILIBRIUM')):
    assert card_snapshot({'id': internal})['id'] == int(sts.card_id_from_name(enum)), internal

print('PASS: snapshot character, stance, orb slots, orbs, mantra and retain import; '
      'every recorded Defect state imports and passes the live comparison; '
      'unknown or unsupported values are refused')
