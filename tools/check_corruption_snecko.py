"""Replay an original-game end turn with Corruption plus Snecko Eye.

Snecko randomizes a drawn skill's base cost; Corruption only zeroes its turn
cost. The end-of-turn reset restores the base cost, and the first exhaust or
discard afterwards makes every remaining hand skill permanently free.
"""
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import ActionMapper

before, after = (view_from_state(json.loads((root / 'overlay/test' / ('corruption-snecko-' + name + '.json'))
                 .read_text(encoding='utf-8'))) for name in ('before', 'after'))
advisor = Advisor()
s = advisor.search
battle = s.comparator.import_battle({**before, 'game': {**before['game'], 'seed': int(before['game']['seed']) % (1 << 64)}},
                                    require_search_state=True)
assert not s.comparator.compare_battle(before, battle)['differences']
advisor.native_search.execute(battle, int(ActionMapper(s.comparator, battle, before).action('end', before, battle).bits))
differences = s.comparator.compare_battle(after, battle)['differences']
assert not differences, differences[:3]
print('PASS: Corruption + Snecko end-turn skill costs match the original game')
