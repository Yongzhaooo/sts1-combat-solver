"""Replay original-game Corruption cost rules on recorded transitions.

Java AbstractPlayer.onCardDrawOrDiscard makes every hand skill whose turn cost
is nonzero permanently free while Corruption is active:
- Snecko Eye: the end-of-turn reset restores randomized base costs, and the
  first exhaust or discard afterwards zeroes them.
- Dead Branch: ShowCardAndAddToHandEffect adds the generated card before that
  hook and only then calls setCostForTurn(-9).
"""
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import ActionMapper

advisor = Advisor()
s = advisor.search
for name, command in (('corruption-snecko', 'end'), ('corruption-deadbranch', 'play 1')):
    before, after = (view_from_state(json.loads((root / 'overlay/test' / (name + '-' + part + '.json'))
                     .read_text(encoding='utf-8'))) for part in ('before', 'after'))
    battle = s.comparator.import_battle({**before, 'game': {**before['game'], 'seed': int(before['game']['seed']) % (1 << 64)}},
                                        require_search_state=True)
    assert not s.comparator.compare_battle(before, battle)['differences'], name
    advisor.native_search.execute(battle, int(ActionMapper(s.comparator, battle, before).action(command, before, battle).bits))
    differences = s.comparator.compare_battle(after, battle)['differences']
    assert not differences, (name, differences[:3])
print('PASS: Corruption cost rules after Snecko end turn and Dead Branch generation match the original game')
