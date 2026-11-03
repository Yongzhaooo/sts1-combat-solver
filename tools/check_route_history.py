"""Check the route-history exporter and report (tools/route_history).

`acts[k].entry` must be the state entering act k: the separator row before the act, so Act 4 has one and
Act 1 does not. Also checks the committed bundle against that and runs report.py on it and on missing values.
"""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'tools/route_history'))
import export_runs
import report

NULL = {'gold': None, 'hp': None, 'max_hp': None}


def synthetic_run(acts):
    """A .run with the given act room strings; each floor's HP is 100 + floor, separators 50 + floor; gold = floor."""
    path, hp = [], []
    for index, rooms in enumerate(acts):
        if index:
            path.append(None)
            hp.append(50 + len(path))
        for room in rooms:
            path.append(room)
            hp.append(100 + len(path))
    return {'path_per_floor': path, 'floor_reached': len(path), 'current_hp_per_floor': hp,
            'max_hp_per_floor': [200] * len(hp), 'gold_per_floor': list(range(1, len(hp) + 1)),
            'campfire_choices': [], 'seed_played': 1, 'local_time': '20261103120000',
            'character_chosen': 'IRONCLAD', 'ascension_level': 20, 'victory': True}


# A won run: the save records no row after the last act, but every act after the first starts on one.
raw = synthetic_run(['MRB', 'MEB', 'MBB', 'R$EB'])
del raw['current_hp_per_floor'][-1], raw['max_hp_per_floor'][-1], raw['gold_per_floor'][-1]
record = export_runs.build_record(raw, 'won.run')
assert not export_runs.record_problems(record, raw['path_per_floor'])
assert record['acts'][0]['entry'] == NULL, record['acts'][0]['entry']
# Separators sit on floors 4, 8 and 12.
assert [act['entry']['hp'] for act in record['acts'][1:]] == [54, 58, 62], record['acts']
assert record['acts'][3]['entry'] == {'gold': 12, 'hp': 62, 'max_hp': 200}, record['acts'][3]['entry']

# A run that died inside act 2 still knows what act 2 started with.
record = export_runs.build_record(synthetic_run(['MRB', 'ME']), 'lost.run')
assert record['acts'][1]['entry']['hp'] == 54, record['acts'][1]

# The committed bundle follows the same layout.
bundle = report.load_bundle(report.DEFAULT_DATA)
for run in bundle:
    assert run['schema'] == export_runs.SCHEMA, run['schema']
    assert run['acts'][0]['entry'] == NULL, (run['seed'], run['acts'][0]['entry'])
    for act in run['acts'][1:]:
        assert None not in act['entry'].values(), (run['seed'], act['act'], act['entry'])
assert sum(1 for run in bundle if len(run['acts']) == 4) == 108


def report_output(path):
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
        assert report.main(['--data', str(path)]) == 0
    section = out.getvalue().split('## state entering each act')[1]
    return section.split('\n## ')[0].splitlines()


rows = {line.split()[0]: line.split() for line in report_output(report.DEFAULT_DATA) if line.strip()
        and line.split()[0].isdigit()}
assert sorted(rows) == ['2', '3', '4'], rows
assert rows['4'][1] == '108', rows['4']

# Missing values (an older save without the separator row) drop out of the table instead of crashing it.
with tempfile.TemporaryDirectory() as tmp:
    lines = report.DEFAULT_DATA.read_text(encoding='utf-8').splitlines()
    runs = [json.loads(line) for line in lines]
    for run in runs:
        for act in run['acts']:
            if act['act'] == 4:
                act['entry'] = dict(NULL)
    path = Path(tmp) / 'missing.jsonl'
    path.write_text(''.join(json.dumps(run) + '\n' for run in runs), encoding='utf-8')
    text = '\n'.join(report_output(path))
    assert '\n4 ' not in text and '\n3 ' in text, text

print('route history: ok')
