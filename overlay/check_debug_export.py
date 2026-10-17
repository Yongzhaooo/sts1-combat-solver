"""The F6 export is one redacted json and excludes unrelated files and obvious identifiers."""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from diagnostics import AsyncReports, export_run

with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    reports = AsyncReports(root/'runs')
    for i in range(5):
        reports.run('request', {'id': i, 'player_name': 'private-name',
                    'path': r'C:\Users\private-name\file.py', 'message': 'token=secret',
                    'cards': [{'id': 'Strike_R', 'uuid': 'card-identity'}]},
                    {'game_state': {'seed': 123, 'floor': 1}})
    reports.close()
    (root/'runs'/'voice.wav').write_bytes(b'private audio')
    target = export_run(root/'runs', '123', root/'Desktop', {'mods': [{'id': 'sts1solver', 'version': '0.6.0'}],
                                                             'username': 'private-name'})
    assert [p.name for p in (root/'Desktop').iterdir()] == [target.name] and target.suffix == '.json'
    data = target.read_text(encoding='utf-8')
    assert 'private-name' not in data and 'token=secret' not in data and 'private audio' not in data
    parsed = json.loads(data)
    assert parsed['format'] == 'sts1-run-v1' and 'no training permission' in parsed['purpose']
    requests = [e for e in parsed['events'] if e['k'] == 'request']
    assert [e['id'] for e in requests] == list(range(5))
    assert requests[-1]['cards'][0]['uuid'] == 'card-identity', 'game identity is preserved'
    assert '-v0.6.0-' in target.name, target.name
    history = {'format': 'sts1-recent-battles-v1', 'completed': [
        {'start': {'floor': 38}, 'root': {'username': 'private-name', 'rng': {'counter': 12}},
         'events': [{'kind': 'card_play', 'solver_driving': False, 'card_uuid': 'manual-card'}],
         'end': {'hp': 57}}]}
    # Human play is available even with no backend run log and test mode off.
    manual = export_run(root/'missing-runs', '456', root/'Manual', recent_battles=history)
    exported = json.loads(manual.read_text(encoding='utf-8'))
    assert '-F38-' in manual.name and exported['events'] == []
    battle = exported['recent_battles']['completed'][0]
    assert battle['events'][0]['card_uuid'] == 'manual-card' and battle['end']['hp'] == 57
    assert battle['root'] == {'rng': {'counter': 12}}, 'history uses the same redaction boundary'
print('PASS: single-json export, redaction, game identity preserved, no unrelated attachments')
