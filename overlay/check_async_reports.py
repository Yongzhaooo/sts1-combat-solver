"""A stalled run-log write cannot block solve enqueue; close drains in order."""
import json
import sys
import tempfile
import threading
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
from diagnostics import RunLog, AsyncReports

entered, release = threading.Event(), threading.Event()
class SlowRunLog(RunLog):
    def record(self, *args):
        entered.set()
        assert release.wait(5), 'test failed to release storage'
        super().record(*args)

with tempfile.TemporaryDirectory() as temp:
    runs = Path(temp)/'runs'
    with patch('diagnostics.RunLog', SlowRunLog):
        reports = AsyncReports(runs)
        try:
            state = {'game_state': {'seed': 42, 'floor': 1}}
            for i in range(1, 5):
                reports.run('request', {'op': 'solve', 'id': i}, state)
            assert entered.wait(2)
            reports.run('reply', {'id': 4, 'status': 'ready'}, state)
            assert not release.is_set(), 'writes did not remain stalled'
        finally:
            release.set()
            reports.close()
    rows = [json.loads(line) for line in (runs/'42.jsonl').read_text(encoding='utf-8').splitlines()]
    assert [(r['k'], r['id']) for r in rows if 'id' in r] == [
        ('request', 1), ('request', 2), ('request', 3), ('request', 4), ('reply', 4)], rows
print('PASS: stalled run log does not block enqueue; ordered drain')
