from worker_policy import worker_limit

for cpus, expected in ((1, 1), (2, 1), (4, 2), (8, 4), (16, 8), (32, 8)):
    assert worker_limit(0, cpus) == expected
assert worker_limit(12, 32) == 12
assert worker_limit(32, 8) == 6
assert worker_limit(1, 32) == 1
for bad in (-1, 33, True, '8', None):
    try:
        worker_limit(bad, 16)
        raise AssertionError('invalid setting accepted')
    except ValueError:
        pass
print('PASS: automatic CPU limits, explicit override, reserved CPUs, invalid-setting refusal')

if __name__ == '__main__':
    import sys
    if '--native' in sys.argv:
        from concurrent.futures import ThreadPoolExecutor
        from backend import Advisor
        advisor = Advisor()
        S, native = advisor.search.sts, advisor.native_search
        root = S.BattleContext.from_snapshot(dict(seed=42, floor=1, turn=1, ascension=0,
            player=dict(current_hp=40, max_hp=80, block=0, energy=3),
            hand=[dict(id=S.card_id_from_name(c)) for c in ('STRIKE_RED', 'DEFEND_RED', 'BASH')],
            draw_pile=[], discard_pile=[], exhaust_pile=[], relics=[],
            potions=[S.potion_id_from_name(p) for p in ('STRENGTH_POTION', 'BLOCK_POTION')],
            monsters=[dict(id=S.monster_id_from_name('JAW_WORM'), current_hp=35, max_hp=35,
                block=0, move=S.monster_move_id_from_name('JAW_WORM_CHOMP'))]), 42)
        fingerprint = advisor.search.comparator.clone_fingerprint(root)
        baseline = None
        for workers in (1, 4):
            native.resume()
            with ThreadPoolExecutor(max_workers=workers) as pool:
                futures = [pool.submit(native.plan, root, 2000, slots, 0, index)
                           for index, slots in enumerate(([], [0], [1], [0, 1]))]
                plans = [dict(f.result(timeout=30)) for f in futures]
            result = [{k: p[k] for k in ('outcome', 'hp', 'max_hp', 'value', 'actions')} for p in plans]
            if baseline is None:
                baseline = result
            else:
                assert result == baseline, 'branch quality changed with scheduling'
            assert advisor.search.comparator.clone_fingerprint(root) == fingerprint
        print('PASS: four native potion branches match at 1 and 4 workers; input unchanged')
