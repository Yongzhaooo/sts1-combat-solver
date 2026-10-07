"""Check real backend spawn, state rejection, cancellation, diagnostics and EOF without a game."""
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import zipfile
import argparse


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
                reply = send({'op': 'export_debug', 'metadata': {'test': 'native-protocol'}})
                assert reply.get('status') == 'debug_export', reply
                with zipfile.ZipFile(data / 'bug-reports' / reply['file']) as archive:
                    assert json.loads(archive.read('debug.json'))['events']
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
        print('PASS: native worker initialized, invalid-state rejection, idle cancel, debug export, EOF')


if __name__ == '__main__':
    main()
