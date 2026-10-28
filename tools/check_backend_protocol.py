"""Check real backend spawn, state rejection, cancellation, diagnostics and EOF without a game."""
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import argparse
import time


# Entry point whose solve sleeps first; the spawned worker re-imports this file, so the patch reaches it.
STALLED = '''import sys, time
sys.path.insert(0, "{overlay}")
import backend
_solve = backend.Advisor.solve
def solve(self, *args):
    time.sleep(60)
    return _solve(self, *args)
backend.Advisor.solve = solve
if __name__ == "__main__":
    backend.serve()
'''


def check_deadline(backend, timeout, requests):
    """Run one backend with shortened search deadlines; `requests` gets a send(value, wait) callable."""
    with tempfile.TemporaryDirectory(prefix='solver deadline check ') as folder:
        env = dict(os.environ, STS_SOLVER_DATA=folder, PYTHONUTF8='1', PYTHONIOENCODING='utf-8',
                   STS_SEARCH_TIMEOUT_SECONDS=str(timeout))
        env.pop('PYTHONHOME', None)
        env.pop('PYTHONPATH', None)
        with (Path(folder) / 'backend.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([sys.executable, '-X', 'utf8', '-B', '-u', str(backend)],
                cwd=folder, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=log, text=True, encoding='utf-8')
            replies = queue.Queue()
            threading.Thread(target=lambda: [replies.put(json.loads(line)) for line in process.stdout], daemon=True).start()

            def send(value, wait=30):
                process.stdin.write(json.dumps(value) + '\n')
                process.stdin.flush()
                return replies.get(timeout=wait)

            try:
                requests(send)
            finally:
                process.kill()
                process.wait()
                process.stdout.close()
                process.stdin.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    backend = parser.parse_args().root.resolve() / 'overlay/backend.py'
    with tempfile.TemporaryDirectory(prefix='solver 协议 check ') as folder:
        data = Path(folder)
        env = dict(os.environ, STS_SOLVER_DATA=folder, PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
        env.pop('PYTHONHOME', None)
        env.pop('PYTHONPATH', None)
        with (data / 'backend.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([sys.executable, '-X', 'utf8', '-B', '-u', str(backend)],
                cwd=data, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=log, text=True, encoding='utf-8')
            replies = queue.Queue()

            def read():
                for line in process.stdout:
                    replies.put(json.loads(line))

            reader = threading.Thread(target=read, daemon=True)
            reader.start()

            def send(value):
                process.stdin.write(json.dumps(value) + '\n')
                process.stdin.flush()
                return replies.get(timeout=30)

            try:
                # This specific validation is reached only after the worker has imported all native modules.
                reply = send({'id': 1, 'op': 'solve',
                              'state': {'game_state': {'seed': 123, 'floor': 1}}, 'budget': 256})
                assert reply.get('id') == 1 and reply.get('status') == 'error', reply
                assert '仅支持战士' in reply.get('message', '') and 'worker_init_seconds' in reply, reply
                reply = send({'id': 2, 'op': 'cancel'})
                assert reply.get('id') == 2 and reply.get('status') == 'cancelled', reply
                state = json.loads((Path(__file__).resolve().parents[1] / 'overlay/test/live-root.json').read_text(encoding='utf-8'))
                reply = send({'id': 3, 'op': 'solve', 'state': state, 'budget': 2000, 'progress': True})
                assert reply['status'] == 'progress' and reply['timeout_seconds'] == 150, reply
                while reply.get('status') == 'progress':
                    reply = replies.get(timeout=30)
                assert reply.get('id') == 3 and reply.get('status') == 'ready' and reply.get('command'), reply
                assert reply.get('search_workers', 0) >= 1 and reply.get('search_priority') in ('below_normal', 'unchanged'), reply
                first_plan = reply
                for workers in (1, 4):
                    limited = send({'id': 'workers-' + str(workers), 'op': 'solve', 'state': state,
                                    'budget': 2000, 'search_workers': workers})
                    assert limited['status'] == 'ready' and 1 <= limited['search_workers'] <= workers, limited
                    for key in ('outcome', 'hp', 'loss', 'command', 'route', 'remaining'):
                        assert limited.get(key) == first_plan.get(key), (workers, key)
                divergent = json.loads(json.dumps(state))
                divergent['game_state']['current_hp'] -= 7
                divergent['game_state']['combat_state']['player']['current_hp'] -= 7
                reply = send({'id': 4, 'op': 'advance', 'state': divergent, 'budget': 2000})
                assert reply.get('id') == 4 and reply.get('status') == 'error', reply
                # A card or class relic the coverage manifest lists as missing is refused before search.
                for field, item in (('hand', 'Neutralize'), ('relics', 'Tingsha')):
                    gapped = json.loads(json.dumps(state))
                    target = gapped['game_state']['combat_state']['hand'] if field == 'hand' else gapped['game_state']['relics']
                    target[0]['id'] = item
                    reply = send({'id': 'gap-' + item, 'op': 'solve', 'state': gapped, 'budget': 2000})
                    assert reply.get('status') == 'error' and reply.get('limitation') == [item], reply
                    assert '尚未实现' in reply['message'] and item in reply['message'], reply
                process.stdin.write(json.dumps({'id': 5, 'op': 'solve', 'state': state, 'budget': 128000}) + '\n')
                started = time.monotonic()
                reply = send({'id': 6, 'op': 'cancel'})
                while reply.get('id') != 6:
                    reply = replies.get(timeout=30)
                assert reply.get('status') == 'ready' and reply.get('interrupted') and reply.get('command'), reply
                assert time.monotonic() - started < 10, 'active cancellation timed out'
                reply = send({'op': 'export_run', 'directory': str(data / 'export'),
                              'metadata': {'test': 'native-protocol'}})
                assert reply.get('status') == 'debug_run_export', reply
                document = json.loads((Path(reply['directory']) / reply['file']).read_text(encoding='utf-8'))
                assert document['format'] == 'sts1-run-v1' and document['events'], reply
                process.stdin.close()
                assert process.wait(timeout=15) == 0
                reader.join(timeout=5)
                assert not reader.is_alive()
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
                process.stdout.close()
                if not process.stdin.closed:
                    process.stdin.close()
        # The fixture comes from the checkout: a packaged runtime under test ships no test files.
        state = json.loads((Path(__file__).resolve().parents[1] / 'overlay/test/live-root.json').read_text(encoding='utf-8'))
        for i, potion in enumerate(('Block Potion', 'Strength Potion', 'Fire Potion')):
            state['game_state']['potions'][i] = {'requires_target': potion == 'Fire Potion', 'can_use': True,
                                                 'can_discard': True, 'name': potion, 'id': potion}
        state['game_state']['combat_state']['monsters'][0]['current_hp'] = 300   # unwinnable: the deep search runs long
        state['game_state']['combat_state']['monsters'][0]['max_hp'] = 300

        def soft(send):
            assert send({'id': 1, 'op': 'solve', 'state': state, 'budget': 2000}, 120).get('status') == 'ready'  # warm worker
            started = time.monotonic()
            reply = send({'id': 2, 'op': 'solve', 'state': state, 'budget': 128000})
            assert reply['status'] == 'ready' and reply['interrupted'] and reply['deadline'] and reply['command'], reply
            assert time.monotonic() - started < 4, 'soft deadline reply came after the hard deadline'

        def hard(send):
            # The worker ignores the interrupt (stalled before it searches), so no plan exists at the hard deadline.
            reply = send({'id': 1, 'op': 'solve', 'state': state, 'budget': 128000}, 30)
            assert reply['status'] == 'error' and '搜索超时' in reply['message'], str(reply)[:300]

        check_deadline(backend, 4, soft)
        with tempfile.TemporaryDirectory(prefix='solver stall ') as folder:
            stalled = Path(folder) / 'stalled_backend.py'
            stalled.write_text(STALLED.format(overlay=backend.parent.as_posix()), encoding='utf-8')
            check_deadline(stalled, 1, hard)
        print('PASS: native worker, real solve, divergent-state refusal, unimplemented-item refusal, idle/active cancel, soft-deadline plan, hard-deadline timeout, debug export, EOF')


if __name__ == '__main__':
    main()
