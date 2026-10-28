"""Per-seed run log: append, reopen, export one json, signatures, retention."""
import json
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import diagnostics
from diagnostics import AsyncReports, RunLog, export_run, fault_signature, reply_summary

TEST = Path(__file__).parent / 'test'


def main():
    state = json.loads((TEST / 'headbutt-before.json').read_text(encoding='utf-8'))
    state['game_state']['seed'] = 777
    state['game_state']['potions'] = [{'id': 'Weak Potion', 'can_use': True}, {'id': 'FairyPotion', 'can_use': False}]
    state['game_state']['relics'].append({'id': 'Sundial', 'counter': 2})

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
        branches = [{'id': 'potion-0', 'name': '虚弱药', 'available': False,
                     'message': '药水不改变结果，已并入不用药路线', 'route': ['omit detail']}]
        ready = reply_summary({'id': 1, 'status': 'ready', 'command': 'play 1 0', 'branches': branches})
        assert 'route' not in ready['branches'][0], 'branch summary stays compact'
        reports.run('reply', ready, state)
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
        assert rows[2]['snap']['potions'] == state['game_state']['potions']
        assert rows[3]['branches'][0]['message'] == branches[0]['message']
        assert rows[3]['branches'][0]['available'] is False
        assert rows[2]['snap']['counters'] == {'Sundial': 2}, 'snapshot keeps cross-fight relic counters'
        assert rows[1]['summary']['relic_counters'] == {'Sundial': 2} and 'last_elite' in rows[1]['summary']
        assert 'snap' not in rows[3], 'reply must not duplicate snapshot from request'

        out = export_run(runs, '777', Path(tmp) / 'Desktop', {'mod_version': '0.4.2', 'username': 'private'})
        assert out.name.startswith('sts1-unimplemented_card-Halt-777-F'), out.name
        assert out.name.endswith('.json') and len(out.name) < 100 and ' ' not in out.name
        doc = json.loads(out.read_text(encoding='utf-8'))
        assert doc['seed'] == '777' and len(doc['events']) == 7
        assert 'username' not in doc['metadata']
        assert doc['events'][2]['snap']['potions'][0] == {'id': 'Weak Potion', 'can_use': True}
        assert doc['events'][3]['branches'][0]['available'] is False
        assert doc['events'][3]['branches'][0]['message'] == branches[0]['message']
        # Exporting an unanswered solve must retain the bottle's actual usability.
        pending = RunLog(Path(tmp) / 'pending')
        pending.record('request', {'id': 10, 'op': 'solve'}, state)
        pending_doc = json.loads(export_run(pending.directory, '777', Path(tmp) / 'PendingExport').read_text(encoding='utf-8'))
        assert pending_doc['events'][-1]['k'] == 'request'
        assert pending_doc['events'][-1]['snap']['potions'][1]['can_use'] is False

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
