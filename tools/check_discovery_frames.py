"""Discovery draws one post-pick generation at any frame time (SuperFastMode multiplies it)."""
import copy
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import ActionMapper

advisor = Advisor()
s = advisor.search
sts = s.sts
base = view_from_state(json.loads((root / 'overlay/test/headbutt-before.json').read_text(encoding='utf-8')))
base['game']['potions'][0] = {'id': 'SkillPotion', 'name': 'Skill Potion', 'can_use': True,
                              'can_discard': True, 'requires_target': False}
draws = {}
for dt in (1 / 144, 1 / 60, 0.1, 1 / 6, 2.0):  # 2.0: x10 SuperFastMode during a 5 fps stall
    view = copy.deepcopy(base)
    view['game']['combat_state']['frame_delta_seconds'] = dt
    battle = s.comparator.import_battle(view, require_search_state=True)
    # Live execution and plans run through overlay_search, the build with the Discovery patch.
    use = ActionMapper(s.comparator, battle, view).action('potion use 0', view, battle)
    advisor.native_search.execute(battle, int(use.bits))
    before = battle.rng_states['card_random']['counter']
    advisor.native_search.execute(battle, int(sts.SearchAction(sts.SearchActionType.SINGLE_CARD_SELECT, 0).bits))
    draws[dt] = battle.rng_states['card_random']['counter'] - before
assert len(set(draws.values())) == 1 and 0 < draws[1 / 60] < 10, draws
print('PASS: Discovery post-pick RNG independent of frame time / SuperFastMode')
