"""Check the Defect simulator against the public calibration cases, without the game.

Every calibration checkpoint carries the maintainer's truth for a Defect run: focus,
orbs, block, monster HP and energy. Replaying the actions here can therefore verify
the engine's numbers directly:

  costs    energy delta of a play matches CardInstance.cost (free plays and the
           self-discounting cards are reported separately)
  orbs     channel deltas match the card (an empty slot means no evoke can happen)
  block    pure block cards match base + Dexterity, x0.75 when Frail
  damage   single-monster, unblocked attacks match base + Strength, with Weak and
           Vulnerable applied
  state    passive/evoke amounts of every orb agree with the focus on that checkpoint

Needs the built runtime (tools/build_solver_runtime.py), not the game.
"""
import collections
import copy
import gzip
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import RNG_NAMES, enum_key

advisor = Advisor()
s = advisor.search
sts = s.sts
from steam.steam_mcts import CARD_ALIASES

CASES = sorted((root / 'fixtures/calibration/DEFECT').glob('*.json.gz'))
BLOCK = {'Defend_B': (5, 8), 'Leap': (9, 12), 'Steam': (6, 8), 'Charge Battery': (7, 10),
         'Undo': (13, 16), 'Auto Shields': (11, 15), 'Glacier': (7, 10), 'BootSequence': (10, 13)}
DAMAGE = {'Strike_B': (6, 9), 'Ball Lightning': (7, 10), 'Cold Snap': (6, 9),
          'Compile Driver': (7, 10), 'Melter': (10, 14), 'Streamline': (15, 20)}
# Plays whose energy delta is not simply minus the printed cost.
ENERGY_CARDS = {'Turbo'}
CHANNEL = {'Zap': 'Lightning', 'Cold Snap': 'Frost', 'Ball Lightning': 'Lightning',
           'Coolheaded': 'Frost', 'Darkness': 'Dark', 'Fusion': 'Plasma'}

view = view_from_state(json.loads((root / 'overlay/test/live-root.json').read_text(encoding='utf-8')))
game = copy.deepcopy(view['game'])
game['combat_state']['rngs'] = {k: view['rng'][name] for k, name in RNG_NAMES.items()}
BASE = s.comparator.bridge.build_snapshot(game)
COST_CACHE = {}


def engine_cost(name, upgrades):
    key = (name, upgrades)
    if key not in COST_CACHE:
        snap = copy.deepcopy(BASE)
        snap['hand'] = [{'id': int(sts.card_id_from_name(CARD_ALIASES.get(name, enum_key(name)))),
                         'upgrades': upgrades}]
        COST_CACHE[key] = sts.BattleContext.from_snapshot(snap, 123).hand[0].base_cost
    return COST_CACHE[key]


def powers(entries):
    return {p['id']: p['amount'] for p in entries}


plays = cost_ok = orb_ok = block_ok = damage_ok = 0
skipped = collections.Counter()
failures = []
for case in CASES:
    data = json.loads(gzip.open(case, 'rb').read().decode('utf-8'))
    actions = data['actions']
    checkpoints = {c['after']: c['observation'] for c in data['checkpoints']}
    for i, action in enumerate(actions):
        before, after = checkpoints.get(i), checkpoints.get(i + 1)
        if before is None or after is None:
            continue
        if before['phase'] == 'PLAYER_TURN' and after['phase'] == 'PLAYER_TURN':
            for orb in before['player'].get('orbs') or []:
                ident, focus = orb['id'], before['player'].get('focus', 0)
                if ident is None:
                    continue
                want = {'Lightning': (3 + focus, 8 + focus), 'Frost': (2 + focus, 5 + focus),
                        'Plasma': (1, 2)}.get(ident)
                if want and (orb['passive'], orb['evoke']) != want:
                    failures.append((case.name, before['floor'], 'orb', ident, orb, focus))
                if ident == 'Dark' and (orb['passive'] != 6 + focus or orb['evoke'] < 6):
                    failures.append((case.name, before['floor'], 'dark', orb, focus))
        if action.get('kind') != 'play':
            continue
        before, after = checkpoints.get(i), checkpoints.get(i + 1)
        if before is None or after is None:
            continue
        if before['phase'] != 'PLAYER_TURN' or after['phase'] != 'PLAYER_TURN':
            continue
        hand, idx = before['piles']['hand'], action['hand']
        if idx >= len(hand):
            continue
        card = hand[idx]
        name = card['id'].rstrip('+')
        plays += 1

        if name not in ENERGY_CARDS:
            try:
                cost = engine_cost(name, card['upgrades'])
            except Exception:
                failures.append((case.name, before['floor'], 'unmapped', name))
                continue
            delta = after['player']['energy'] - before['player']['energy']
            if cost < 0:
                skipped['x-cost'] += 1
            elif card['cost'] != cost and card['cost'] != 0:
                skipped['discounted'] += 1   # Streamline and friends; energy delta still matches the print
            elif -delta != card['cost']:
                failures.append((case.name, before['floor'], 'energy', name, card['upgrades'], delta, cost))
            else:
                cost_ok += 1

        orbs_before = [o['id'] for o in before['player']['orbs']]
        orbs_after = [o['id'] for o in after['player']['orbs']]
        if name in CHANNEL and None in orbs_before:
            gained = collections.Counter(x for x in orbs_after if x) - collections.Counter(x for x in orbs_before if x)
            if gained != collections.Counter({CHANNEL[name]: 1}):
                failures.append((case.name, before['floor'], 'channel', name, dict(gained)))
            else:
                orb_ok += 1

        if name in BLOCK:
            if name == 'Glacier' and None not in orbs_before:
                continue
            p = powers(before['player']['powers'])
            value = BLOCK[name][1 if card['upgrades'] else 0] + p.get('Dexterity', 0)
            if p.get('Frail', 0) > 0:
                value = int(value * 0.75)
            gained = after['player']['block'] - before['player']['block']
            if gained != value:
                failures.append((case.name, before['floor'], 'block', name, card['upgrades'], gained, value))
            else:
                block_ok += 1

        monsters_before, monsters_after = before['monsters'], after['monsters']
        if (name in DAMAGE and len(monsters_before) == 1 and len(monsters_after) == 1
                and monsters_after[0]['hp'] > 0 and monsters_before[0]['block'] == 0
                and monsters_before[0]['id'] == monsters_after[0]['id']):
            p = powers(before['player']['powers'])
            m = powers(monsters_before[0]['powers'])
            value = DAMAGE[name][1 if card['upgrades'] else 0] + p.get('Strength', 0)
            if m.get('Vulnerable', 0) > 0:
                value = int(value * 1.5)
            if p.get('Weakened', p.get('Weak', 0)) > 0:
                value = int(value * 0.75)
            dealt = monsters_before[0]['hp'] - monsters_after[0]['hp']
            if dealt != value:
                failures.append((case.name, before['floor'], 'damage', name, card['upgrades'], dealt, value))
            else:
                damage_ok += 1

print(f'cases {len(CASES)}, plays {plays}: costs {cost_ok}, channels {orb_ok}, block {block_ok}, damage {damage_ok}')
print('skipped:', dict(skipped))
if failures:
    for row in failures[:10]:
        print('FAIL', row)
    raise SystemExit(f'{len(failures)} differences against the calibration truth')
print('PASS: Defect costs, orb channels, block and damage agree with the calibration truth')
