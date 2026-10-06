"""A blocked retention write cannot block solve enqueue; close drains in order."""
import json
import sqlite3
import tempfile
import threading
from pathlib import Path
from unittest.mock import patch
from diagnostics import Reports, AsyncReports

entered, release = threading.Event(), threading.Event()
class SlowReports(Reports):
    def record(self, *args):
        entered.set()
        assert release.wait(5), 'test failed to release storage'
        super().record(*args)

with tempfile.TemporaryDirectory() as temp:
    path = Path(temp)/'reports.sqlite3'
    with patch('diagnostics.Reports', SlowReports):
        reports = AsyncReports(path)
        try:
            for seed in range(1,5):
                state = {'game_state': {'seed':seed, 'floor':1}}
                reports.record('request', {'op':'solve','id':seed}, state)
            assert entered.wait(2)
            reports.record('reply', {'id':4, 'status':'ready'})
            assert not release.is_set(), 'writes did not remain stalled'
        finally:
            release.set()
            reports.close()
    with sqlite3.connect(path) as db:
        assert db.execute('SELECT count(*) FROM runs').fetchone()[0] == 3
        rows = db.execute('SELECT kind,payload FROM events ORDER BY id').fetchall()
        assert [(k,json.loads(v)['id']) for k,v in rows] == [('request',2),('request',3),('request',4),('reply',4)]
print('PASS: stalled storage does not block enqueue; ordered drain and three-run retention')
