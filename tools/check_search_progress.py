"""Observe actual native beam work and require bounded cancellation and truthful rows."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'overlay'))
from backend import Advisor
from search_status import search_timeout

assert search_timeout(8000) == 150 and search_timeout(128000) == 600
a = Advisor()
S, N = a.search.sts, a.native_search
b = S.BattleContext.from_snapshot(dict(seed=42, floor=1, turn=1, ascension=20,
    player=dict(current_hp=80, max_hp=80, block=0, energy=3),
    hand=[dict(id=S.card_id_from_name(c), upgrades=1) for c in ('POMMEL_STRIKE', 'POMMEL_STRIKE', 'BODY_SLAM')],
    draw_pile=[], discard_pile=[], exhaust_pile=[], relics=[], potions=[],
    monsters=[dict(id=S.monster_id_from_name('CULTIST'), current_hp=100000, max_hp=100000,
                   block=0, move=S.monster_move_id_from_name('CULTIST_INCANTATION'))]), 42)
a.searching = [(0, 'no-potion', '不用药')]
N.resume()
seen = set()
transitions = []
with ThreadPoolExecutor(max_workers=1) as pool:
    future = pool.submit(N.plan, b, 128000, [], 0, 0)
    deadline = time.monotonic() + 8
    try:
        while not future.done() and time.monotonic() < deadline:
            row = a.progress()[0]
            seen.add(row['phase'])
            transitions.append(row['transitions'])
            assert not row['ready']
            if row['phase'] in ('cycle', 'beam') and row['transitions'] >= 256:
                break
            time.sleep(.001)
    finally:
        stopped = time.monotonic()
        N.cancel()
    try:
        future.result(timeout=10)
    except RuntimeError as error:
        assert '中断' in str(error) or 'interrupted' in str(error), str(error)
    assert time.monotonic()-stopped < 10
assert seen & {'cycle', 'beam'} and max(transitions, default=0) >= 256, (seen, transitions[-5:])
a.branch_info = [dict(id='no-potion', available=True)]
assert a.progress()[0]['phase'] == 'ready'
assert a.progress()[0]['used_fairy'] == 0 and not a.progress()[0]['used_tail']
a.branch_info = [dict(id='no-potion', available=True, used_fairy=1, used_tail=False)]
assert a.progress()[0]['used_fairy'] == 1, 'a finished route must report its Fairy revival'

# The native running maximum can come from a route that spent a revival.
a.root = S.BattleContext.from_snapshot(dict(seed=42, floor=1, turn=1, ascension=0,
    player=dict(current_hp=1, max_hp=74, block=0, energy=3),
    hand=[dict(id=S.card_id_from_name('DEFEND_RED'))],
    draw_pile=[dict(id=S.card_id_from_name('STRIKE_RED'))], discard_pile=[], exhaust_pile=[],
    relics=[dict(id=S.relic_id_from_name('LIZARD_TAIL'))], potions=[],
    monsters=[dict(id=S.monster_id_from_name('JAW_WORM'), current_hp=5, max_hp=5,
                   block=0, move=S.monster_move_id_from_name('JAW_WORM_CHOMP'))]), 42)
a.branch_info = []
N.resume()
revived = N.plan(a.root, 1000, [], 0, 0)
assert revived['used_tail'] and revived['hp'] == 37, revived
running = a.progress()[0]
assert running['best_hp'] == 37 and running['hp_provisional'] and not running['ready'], running

# A finalized row must pair the accepted plan's HP with its own revival cost.
a.branch_info = [dict(id='no-potion', available=True, raw_hp=19, used_tail=False, used_fairy=0)]
finished = a.progress()[0]
assert finished['best_hp'] == 19 and finished['ready'] and not finished['hp_provisional'], finished
assert not finished['used_tail'] and finished['used_fairy'] == 0, finished
print('PASS: live search stages and changing beam transitions, accepted readiness and cancellation', sorted(seen))
