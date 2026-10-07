"""Check the exported backend's real imports and engine identity in a fresh process."""
from pathlib import Path
import sys
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
root = parser.parse_args().root.resolve()
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor

advisor = Advisor()
assert advisor.search.runtime == (root / 'candidates/sts-ironclad-agent/runtime').resolve()
assert Path(advisor.native_search.__file__).resolve().is_relative_to(root / 'overlay/build/native')
print('PASS: exported advisor imports its own engine and native extension')

# Exercise a real native plan and replay, not just shared-library imports.
# This is a simulator smoke check, not evidence of parity with a live game.
sts = advisor.search.sts
battle = sts.BattleContext()
battle.init_encounter(sts.GameContext(sts.CharacterClass.IRONCLAD, 123, 0), sts.MonsterEncounter.CULTIST)
before = advisor.search.comparator.clone_fingerprint(battle)
advisor.native_search.resume()
plan = advisor.native_search.plan(battle, 256, [], 0, 0)
assert plan['outcome'] == 1 and plan['actions'] and plan['hp'] > 0, plan
assert before == advisor.search.comparator.clone_fingerprint(battle), 'search mutated its input'
for bits in plan['actions']:
    advisor.native_search.execute(battle, int(bits) & 0xffffffff)
assert battle.outcome == sts.Outcome.PLAYER_VICTORY and battle.player.cur_hp == plan['hp']
print('PASS: native combat search and winning-action replay')
