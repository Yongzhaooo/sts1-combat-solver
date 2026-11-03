"""Check an empty orb slot stays empty however the game spells it.

CommunicationMod should export a null id for an empty orb slot, and usually does.
In a captured Defect run it also reported the literal string "Empty" for one slot
and null for the next inside the same combat, so both spellings have to mean "no
orb here" everywhere:

  * the producer, which turns the game's orb list into a snapshot, and
  * the comparison, which reads that orb list back out of a capture.

Missing either one ends the search: the producer spelling reaches the importer as
an unknown orb id and raises, and the comparison spelling collects an empty slot as
a filled orb, so every Defect run diverges on `/player/orbs` the moment a slot is
free. A Defect starts with empty slots in its default three, so this is not an edge
case - it is the first combat.

Needs the built runtime (tools/build_solver_runtime.py), not the game.
"""
import copy
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import EMPTY_ORB_IDS, ORB_IDS, RNG_NAMES

s = Advisor().search
sts = s.sts
view = view_from_state(json.loads((root / 'overlay/test/live-root.json').read_text(encoding='utf-8')))
game = copy.deepcopy(view['game'])
game['combat_state']['rngs'] = {k: view['rng'][name] for k, name in RNG_NAMES.items()}
SUPPORTED_CLASSES = ('IRONCLAD', 'DEFECT')


def slot(identifier, passive=0, evoke=0, name='充能球栏位'):
    """One entry of the game's orb list, in CommunicationMod's shape."""
    entry = {'name': name, 'passive_amount': passive, 'evoke_amount': evoke}
    if identifier is not None:
        entry['id'] = identifier
    return entry


def snapshot_of(orbs):
    state = copy.deepcopy(game)
    state['combat_state']['player']['orbs'] = orbs
    return s.comparator.bridge.build_snapshot(state)


def imported(orbs):
    return sts.BattleContext.from_snapshot(snapshot_of(orbs), int(game['seed']) % (1 << 64)).player


# The producer must fold every spelling of "empty" to the null the importer accepts.
# "Empty" is the one that reached the game and crashed it: a non-empty string, so it
# was neither skipped as an empty slot nor recognised as an orb.
for spelling in EMPTY_ORB_IDS:
    empty = slot(spelling if spelling != '' else '')
    snap = snapshot_of([empty, empty, empty])
    assert snap['player']['orb_slots'] == 3, spelling
    assert [orb['id'] for orb in snap['player']['orbs']] == [None, None, None], \
        (spelling, snap['player']['orbs'])
    assert imported([empty, empty, empty]).orbs == [], spelling

# A filled slot keeps its id and its position, so a mixed list must not shift: the
# importer addresses orbs by list position, and a dropped placeholder would move
# every later orb one slot along.
mixed = [slot('Lightning', 3, 8, '闪电'), slot('Empty'), slot('Dark', 6, 18, '黑暗')]
mixed_snap = snapshot_of(mixed)
assert mixed_snap['player']['orb_slots'] == 3, mixed_snap['player']['orb_slots']
assert [orb['id'] for orb in mixed_snap['player']['orbs']] == ['Lightning', None, 'Dark'], \
    mixed_snap['player']['orbs']
assert imported(mixed).orbs == [{'id': 'LIGHTNING', 'dark_counter': 0},
                                {'id': 'DARK', 'dark_counter': 18}], imported(mixed).orbs

# Null and "Empty" must produce the same snapshot, or a capture taken with one
# spelling could not be replayed against a run that used the other.
assert snapshot_of([slot('Lightning', 3, 8), slot(None), slot(None)]) == \
    snapshot_of([slot('Lightning', 3, 8), slot('Empty'), slot('Empty')])

# The comparison reads orbs straight out of a capture, without going through the
# producer, so it needs the same rule. A capture records "Empty" as it was sent, and
# the historical captures will never be re-exported.
captured = [slot('Empty'), slot('Empty'), slot('Empty')]
kept = [raw for raw in captured if raw.get('id') not in EMPTY_ORB_IDS]
assert kept == [], kept
# The rule has to be stated once and shared, not re-derived per call site.
assert set(EMPTY_ORB_IDS) == {None, '', 'Empty'}, EMPTY_ORB_IDS
# A real orb id must not be swept up by it, however the game spells it.
for game_id in ORB_IDS:
    assert game_id not in EMPTY_ORB_IDS, game_id

print('PASS: an empty orb slot is empty whether the game calls it null or "Empty"; '
      'the producer folds both to null, a mixed list keeps every orb in its slot, '
      'and the comparison counts neither spelling as a filled orb.')
