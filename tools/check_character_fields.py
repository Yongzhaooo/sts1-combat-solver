"""Check every character state field is exported, imported and verified, with one table.

steam_mcts.CHARACTER_FIELDS lists each field a character carries into the simulator. For every
row this proves, against the native module:

  (a) a recording of a real game export has the export key (the Mod's producer side),
  (b) build_snapshot carries a non-default value into the importer and it reads back unchanged,
  (c) a simulator that holds a different value makes the strict comparison report a difference.

A snapshot player key the importer reads that is neither a table row nor a shared combat key
fails too, so a new field cannot reach the simulator without a row. Stance and mantra are
imported but the Mod does not export them yet; they stay in PENDING_EXPORT until their row
(with a recording) replaces them here.

Needs the built runtime (tools/build_solver_runtime.py), not the game.
"""
import copy
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
import backend
from backend import Advisor, view_from_state

s = Advisor().search
sts = s.sts
bridge = s.comparator.bridge
FIELDS = bridge.CHARACTER_FIELDS
RECORDED = sorted((root / 'overlay/test/recorded').glob('*/*.json'))
REPLAYS = sorted((root / 'fixtures/player-replays').glob('defect-*.json'))
# Importer player keys that are shared combat state, verified with HP, block and piles.
SHARED = {'current_hp', 'max_hp', 'is_bloodied', 'block', 'energy', 'gold', 'necronomicon_used',
          'orange_pellets_mask', 'last_targeted_monster', 'energy_per_turn', 'card_draw_per_turn',
          'powers', 'centennial_puzzle_used', 'red_skull_active'}
PENDING_EXPORT = {'stance', 'mantra'}
BASE = root / 'overlay/test/recorded/DEFECT/creative-ai-root.json'


def states():
    for path in RECORDED:
        yield path, json.loads(path.read_text(encoding='utf-8'))['game_state']
    for path in REPLAYS:
        yield path, json.loads(path.read_text(encoding='utf-8'))['root']['game_state']


def resolve(node, path):
    """Walk 'a.b[id=X]'; True when the key is present."""
    for part in path.split('.'):
        match = re.fullmatch(r'(\w+)(?:\[(\w+)=(\w+)\])?', part)
        key, sel_key, sel_val = match.groups()
        if not isinstance(node, dict) or key not in node:
            return False
        node = node[key]
        if sel_key:
            node = next((x for x in node if x.get(sel_key) == sel_val), None)
            if node is None:
                return False
    return True


# (a) Producer evidence: a recorded export of each listed character holds the key.
all_states = list(states())
for field in FIELDS:
    for character in field['characters']:
        hits = [p.name for p, g in all_states if character in ('*', g.get('class')) and resolve(g, field['export'])]
        assert hits, f"{field['name']}: no recorded {character} game export contains {field['export']}; " \
                     'record one (see overlay/test/recorded/README.md) before adding the field'

# Probes: how to give each field a non-default value in an export, and what the importer shows.
FOCUS = {'id': 'Focus', 'name': 'Focus', 'amount': 1}


def probe_orbs(c):
    c['player']['orbs'][1] = {'id': 'Frost', 'passive_amount': 2, 'evoke_amount': 5, 'name': 'Frost'}


def probe_slots(c):
    c['player']['orbs'].append({'id': None, 'passive_amount': 0, 'evoke_amount': 0, 'name': 'Empty'})


def probe_focus(c):
    c['player']['powers'].append(dict(FOCUS))
    c['player']['orbs'][0].update(passive_amount=4, evoke_amount=9)


PROBES = {
    'character': (lambda c, g: g.update({'class': 'THE_SILENT'}), 'SILENT'),
    'orbs': (lambda c, g: probe_orbs(c), ['LIGHTNING', 'FROST']),
    'orb_slots': (lambda c, g: probe_slots(c), 4),
    'focus': (lambda c, g: probe_focus(c), 1),
    'lightning_channeled_this_combat': (lambda c, g: c.update(lightning_channeled_this_combat=3), 3),
    'frost_channeled_this_combat': (lambda c, g: c.update(frost_channeled_this_combat=2), 2),
}
assert set(PROBES) == {f['name'] for f in FIELDS}, \
    f"table rows without a probe, or probes without a row: {set(PROBES) ^ {f['name'] for f in FIELDS}}"


def recorded_view():
    saved = backend.SUPPORTED_CLASSES
    backend.SUPPORTED_CLASSES = {**saved, 'DEFECT': 'test only'}
    try:
        view = view_from_state(json.loads(BASE.read_text(encoding='utf-8')))
    finally:
        backend.SUPPORTED_CLASSES = saved
    return {**view, 'game': {**view['game'], 'seed': int(view['game']['seed']) % (1 << 64)}}


def imported(view):
    game = view['game']
    return sts.BattleContext.from_snapshot(bridge.build_snapshot(game), int(game['seed'])), bridge.build_snapshot(game)


base = recorded_view()
# This recording predates the channeled counts; the comparison reads them only when the original
# has them, so give the original the keys (as the recorded f12 export has) at their start value.
base['game']['combat_state'].update(lightning_channeled_this_combat=0, frost_channeled_this_combat=0)
base_battle = s.comparator.import_battle(base, require_search_state=True)
base_diff = s.comparator.compare_battle(base, base_battle)['differences']
assert not base_diff, base_diff[:3]  # an unmutated recording compares clean, so a difference below is the mutation's

for field in FIELDS:
    name = field['name']
    mutate, wanted = PROBES[name]
    game = copy.deepcopy(base['game'])
    mutate(game['combat_state'], game)
    view = {**base, 'game': game}
    # (b) the snapshot carries the value, the importer keeps it.
    battle, snapshot = imported(view)
    assert field['snap'](snapshot) == wanted, (name, field['snap'](snapshot), wanted)
    assert field['read'](battle.player) == wanted, (name, field['read'](battle.player), wanted)
    assert field['read'](base_battle.player) != wanted, (name, 'probe equals the recorded value')
    # (c) a simulator holding that value, compared against the unmutated game, must differ.
    sim = s.comparator.import_battle(view, require_search_state=True)
    paths = [d['path'] for d in s.comparator.compare_battle(base, sim)['differences']]
    assert any(p.startswith(prefix) for p in paths for prefix in field['compare']), (name, field['compare'], paths)
    print(f"  {name}: exported at {field['export']}, imported as {field['snapshot']}, compared at {field['compare']}")

# Importer player keys: every one is shared state, a table row, or pending export.
source = next((root / 'candidates').rglob('slaythespire.cpp')).read_text(encoding='utf-8')
keys = set(re.findall(r'\bplayer(?:\.contains\(|\[)"(\w+)"', source))
known = SHARED | PENDING_EXPORT | {f['snapshot'].split('.', 1)[1] for f in FIELDS if f['snapshot'].startswith('player.')}
unlisted = keys - known
assert not unlisted, f'the importer reads player key(s) {sorted(unlisted)} that are not in steam_mcts.CHARACTER_FIELDS; ' \
                     'add a row with its export key, then a recording and a probe here'
assert PENDING_EXPORT <= keys, 'a pending field left the importer; drop it from PENDING_EXPORT'

print(f'PASS: {len(FIELDS)} character fields are exported in a recording, round-trip through build_snapshot '
      'and the importer, and make the strict comparison fail when the simulator differs; '
      f'the importer reads no unlisted player key ({len(keys)} keys, {len(PENDING_EXPORT)} pending export)')
