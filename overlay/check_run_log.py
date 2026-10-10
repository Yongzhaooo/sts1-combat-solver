"""Per-seed run log: append, reopen, export one json, signatures, retention."""
import json
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import diagnostics
from diagnostics import AsyncReports, RunLog, export_run, fault_signature

TEST = Path(__file__).parent / 'test'


def main():
    state = json.loads((TEST / 'headbutt-before.json').read_text(encoding='utf-8'))
    state['game_state']['seed'] = 777

    assert fault_signature('attempted to use unimplemented card: Outmaneuver',
                           'Assertion failed: false, BattleContext.cpp:1545') == 'unimplemented_card-Outmaneuver'
    assert fault_signature('x', 'Assertion failed: false, BattleContext.cpp:1545') == 'assert-BattleContext.cpp-1545'
    assert fault_signature('IdentityDifference: stable original identity') == 'IdentityDifference'
    assert fault_signature('实际状态偏离预测；已停止执行。[{...}]') == 'state_divergence'
    assert fault_signature('boom') == 'error'

    with tempfile.TemporaryDirectory() as tmp:
        runs = Path(tmp) / 'runs'
        reports = AsyncReports(runs)
        reports.run('request', {'id': 1, 'op': 'solve', 'budget': 2000}, state)
        reports.run('reply', {'id': 1, 'status': 'ready', 'command': 'play 1 0'}, state)
        reports.run('request', {'id': 2, 'op': 'advance', 'budget': 2000}, state)  # same battle: no second combat_open
        reports.run('diag', {'event': 'outside', 'message': 'choose 0'}, None, 777)  # seed from the argument
        reports.run('fault', {'id': 2, 'message': 'attempted to use unimplemented card: Halt',
                              'stderr': 'Assertion failed: false, BattleContext.cpp:1545',
                              'sig': fault_signature('attempted to use unimplemented card: Halt'),
                              'frame': state}, state)
        reports.flush()
        assert reports.runs_seed() == '777'
        runs = Path(tmp) / 'runs'
        rows = [json.loads(line) for line in (runs / '777.jsonl').read_text(encoding='utf-8').splitlines()]
        kinds = [r['k'] for r in rows]
        assert kinds == ['session', 'combat_open', 'request', 'reply', 'request', 'diag', 'fault'], kinds
        assert 'summary' in rows[1] and 'state' not in rows[1], 'combat_open uses summary instead of raw state'
        assert rows[1]['summary']['deck'] and 'keys' in rows[1]['summary'], 'summary has deck and keys'
        assert rows[2]['snap']['hand'], 'request keeps a small snapshot for divergence checks'
        assert 'snap' not in rows[3], 'reply must not duplicate snapshot from request'

        out = export_run(runs, '777', Path(tmp) / 'Desktop', {'mod_version': '0.4.2', 'username': 'private'})
        assert out.name.startswith('sts1-unimplemented_card-Halt-777-F'), out.name
        assert out.name.endswith('.json') and len(out.name) < 100 and ' ' not in out.name
        doc = json.loads(out.read_text(encoding='utf-8'))
        assert doc['seed'] == '777' and len(doc['events']) == 7
        assert 'username' not in doc['metadata']

        # A crash can leave a torn last line; the export must still work.
        with (runs / '777.jsonl').open('a', encoding='utf-8') as stream:
            stream.write('{"k":"torn')
        assert export_run(runs, '777', Path(tmp) / 'Desktop2').exists()

        # A reopened backend (new session) appends to the same seed's file.
        again = RunLog(runs)
        again.record('request', {'id': 9}, state)
        tail = (runs / '777.jsonl').read_text(encoding='utf-8').splitlines()[-4:]
        assert json.loads(tail[-1])['id'] == 9 and any('"session"' in l for l in tail), tail

        # Retention keeps the newest seeds and never the one being written.
        for i in range(30):
            (runs / ('old%02d.jsonl' % i)).write_text('{}\n')
            os.utime(runs / ('old%02d.jsonl' % i), (1000 + i, 1000 + i))
        RunLog(runs).prune(keep='777')
        left = sorted(f.name for f in runs.glob('*.jsonl'))
        assert '777.jsonl' in left and len(left) == diagnostics.RUN_KEEP_SEEDS, left
        reports.close()
    print('run log ok')


if __name__ == '__main__':
    main()
