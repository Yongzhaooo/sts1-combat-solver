"""A debug bundle is bounded, readable and excludes unrelated files and obvious identifiers."""
import json
import sqlite3
import tempfile
import zipfile
from pathlib import Path
from diagnostics import AsyncReports, export_debug
from contextlib import closing

build = Path(__file__).parent / 'build'
build.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(dir=build) as temp:
    root = Path(temp)
    database = root / 'reports.sqlite3'
    reports = AsyncReports(database)
    for i in range(305):
        reports.record('request', {'id': i, 'player_name': 'private-name',
                       'path': 'C:\\Users\\private-name\\file.py', 'message': 'token=secret',
                       'state': {'seed': 123, 'cards': [{'id': 'Strike_R', 'uuid': 'card-identity'}]}},
                       {'game_state': {'seed': 123, 'floor': 1}})
    reports.flush()
    (root / 'voice.wav').write_bytes(b'private audio')
    target = export_debug(database, root/'exports', {'mods': [{'id': 'sts1solver'}]})
    reports.close()
    with closing(sqlite3.connect(database)) as db:
        assert db.execute('SELECT count(*) FROM events').fetchone()[0] == 300
    with zipfile.ZipFile(target) as archive:
        assert set(archive.namelist()) == {'debug.json', 'README.txt'}
        data = archive.read('debug.json').decode('utf-8')
        assert 'private-name' not in data and 'token=secret' not in data
        parsed = json.loads(data)
        assert len(parsed['events']) == 100
        assert parsed['events'][0]['payload']['id'] == 205
        assert parsed['events'][-1]['payload']['state']['cards'][0]['uuid'] == 'card-identity'
        assert 'no training permission' in parsed['purpose']
    assert target.stat().st_size < 1024*1024
print('PASS: bounded retention/export, redaction, game identity preserved, no unrelated attachments')
