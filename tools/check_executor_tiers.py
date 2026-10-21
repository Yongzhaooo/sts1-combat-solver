"""Check the shipped pi_base v2.8 executor tiers and the desktop high-speed and streak modes.

A recording stand-in for overlay_search receives the calls the backend tier wrapper makes for each act, room
and mode; the file must load without environment overrides, as it does in the mod.
"""
import json
import os
from pathlib import Path
import sys
import types

root = Path(__file__).resolve().parents[1]
for key in [k for k in os.environ if k.startswith(('STS_ACT13', 'STS_NN_PRIOR', 'STS_RESCUE', 'STS_BEAM_ONLY', 'STS_ELITE'))]:
    del os.environ[key]
sys.path.insert(0, str(root / 'overlay'))
import backend

tiers = json.loads((root / 'overlay/headless_tiers.json').read_text(encoding='utf-8'))
assert tiers['STS_ACT13_BUDGET_DIVISOR'] == '10' and tiers['STS_NN_PRIOR_ROOMS'] == 'MonsterRoom,EventRoom'
assert json.loads(tiers['STS_ACT13_ROOM_DIVISORS']) == {'MonsterRoomElite': 2, 'MonsterRoomBoss': 4}
assert backend._ELITE_DEEP_GATE is not None and Path(backend._NN_WEIGHTS).is_file()

calls = {}
native = types.SimpleNamespace(
    plan=lambda root, simulations, *args: simulations if root is None else dict(root, simulations=simulations),
    nn_load=lambda path: calls.__setitem__('loaded', path),
    set_rescue=lambda *args: calls.__setitem__('rescue', args),
    nn_config=lambda enabled, *args: calls.__setitem__('prior', enabled))
sys.modules['overlay_search'] = native
solve = backend._tier_wrap(lambda self, view, budget: native.plan(None, budget))

full, fast = (160, 160, 2048, False), (40, 40, 2048, False)
cases = [  # act, room, mode (True: high speed, 'streak') -> simulations of 8000, rescue beam, prior
    (1, 'MonsterRoom', False, 800, full, True),
    (2, 'EventRoom', False, 800, full, True),
    (3, 'MonsterRoomElite', False, 4000, full, False),
    (3, 'MonsterRoomBoss', False, 2000, full, False),
    (4, 'MonsterRoomBoss', False, 8000, full, False),
    (1, 'MonsterRoomElite', True, 400, fast, True),
    (4, 'MonsterRoomBoss', True, 400, fast, True),
    (4, 'MonsterRoomBoss', False, 8000, full, False),  # leaving the mode restores the beam and the prior
    (1, 'MonsterRoom', 'streak', 8000, full, False),
    (3, 'MonsterRoomBoss', 'streak', 8000, full, False),
    (2, 'EventRoom', False, 800, full, True),  # leaving streak mode restores the tiers
]
for act, room, mode, simulations, rescue, prior in cases:
    view = {'game': {'act': act, 'solver_auto_context': {'room': room, 'high_speed': mode is True,
                                                          'streak_mode': mode == 'streak'}}}
    got = (solve(None, view, 8000), calls['rescue'], calls['prior'])
    assert got == (simulations, rescue, prior), (act, room, mode, got)
assert calls['loaded'] == backend._NN_WEIGHTS

# Feed escalation: a non-Boss win that misses Feed's max HP is searched again at 2x the divided budget.
feed_solve = backend._tier_wrap(lambda self, view, budget, result: native.plan(result, budget)['simulations'])
feed = lambda room, fast=False, card='Feed': {'game': {'act': 1, 'solver_auto_context': {'room': room, 'high_speed': fast},
    'combat_state': {'player': {'max_hp': 80}, 'hand': [], 'draw_pile': [{'id': card}], 'discard_pile': []}}}
missed, gained = {'outcome': 1, 'max_hp': 80}, {'outcome': 1, 'max_hp': 83}
assert feed_solve(None, feed('MonsterRoom'), 8000, missed) == 1600
assert feed_solve(None, feed('MonsterRoom'), 8000, gained) == 800
assert feed_solve(None, feed('MonsterRoom'), 8000, {'outcome': 0, 'max_hp': 80}) == 800
assert feed_solve(None, feed('MonsterRoom', card='Strike_R'), 8000, missed) == 800
assert feed_solve(None, feed('MonsterRoomElite'), 8000, missed) == 8000
assert feed_solve(None, feed('MonsterRoomBoss'), 8000, missed) == 2000
assert feed_solve(None, feed('MonsterRoom', fast=True), 8000, missed) == 800
streak = {'game': dict(feed('MonsterRoom')['game'], solver_auto_context={'room': 'MonsterRoom', 'streak_mode': True})}
assert feed_solve(None, streak, 8000, missed) == 8000   # streak mode already searches the full budget
print('PASS: v2.8 tiers per act and room; high-speed 1/20, prior everywhere, rescue 40; streak untiered; mode reset; Feed escalation x2 outside Boss fights')
