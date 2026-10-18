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
# focus-applied passive and evoke amounts. The importer derives the card-granted
# evoke bonus from the evoke amount and keeps Dark's accumulated counter.
lightning = {'id': 'Lightning', 'passive': 3, 'evoke': 8}
frost = {'id': 'Frost', 'passive': 2, 'evoke': 5}
dark = {'id': 'Dark', 'passive': 6, 'evoke': 12}
plasma = {'id': 'Plasma', 'passive': 1, 'evoke': 2}
empty = {'id': None, 'passive': 0, 'evoke': 0}
assert load(lambda v: v['player'].update(orbs=[lightning], orb_slots=3)).orbs == [
    {'id': 'LIGHTNING', 'evoke_bonus': 0, 'dark_counter': 0}]
assert load(lambda v: v['player'].update(orbs=[{'id': 'Lightning', 'passive': 3, 'evoke': 11}], orb_slots=3)).orbs == [
    {'id': 'LIGHTNING', 'evoke_bonus': 3, 'dark_counter': 0}], 'Zap+ adds 3 to the evoke amount'
assert load(lambda v: v['player'].update(orbs=[frost, dark, plasma, empty], orb_slots=4)).orbs == [
    {'id': 'FROST', 'evoke_bonus': 0, 'dark_counter': 0},
    {'id': 'DARK', 'evoke_bonus': 0, 'dark_counter': 12},
    {'id': 'FUSION', 'evoke_bonus': 0, 'dark_counter': 0}]
assert load(lambda v: v['player'].update(orbs=[empty, empty], orb_slots=2)).orbs == []
assert load(lambda v: (v['player']['powers'].append({'id': int(sts.PlayerStatus.FOCUS), 'amount': 2}),
                       v['player'].update(orbs=[{'id': 'Lightning', 'passive': 5, 'evoke': 13}], orb_slots=3))).orbs == [
    {'id': 'LIGHTNING', 'evoke_bonus': 3, 'dark_counter': 0}], 'focus is part of the exported evoke amount'
assert refused(lambda v: v['player'].update(orbs=[{'id': 'Lightning', 'passive': 3, 'evoke': 7}], orb_slots=3)), \
    'an evoke amount below base plus focus must not import'
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
    {'id': 'LIGHTNING', 'evoke_bonus': 0, 'dark_counter': 0},
    {'id': 'DARK', 'evoke_bonus': 0, 'dark_counter': 18}]

print('PASS: snapshot character, stance, orb slots, orbs, mantra and retain import; '
      'unknown or unsupported values are refused')
