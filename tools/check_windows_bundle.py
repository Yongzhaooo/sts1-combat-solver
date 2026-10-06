"""Exercise the bundled interpreter with the same cwd as the Java launcher and a minimal PATH."""
import argparse
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time
import zipfile
import hashlib


def check(bundle, state_file):
    runtime = bundle.resolve() / 'sts1-solver-runtime'
    state = json.loads(state_file.read_text(encoding='utf-8'))
    with tempfile.TemporaryDirectory(prefix='sts-native-', dir=bundle.resolve().parent) as outside:
        env = dict(os.environ, PATH=os.environ['SystemRoot'] + '/System32',
                   STS_SOLVER_DATA=outside, PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
        env.pop('PYTHONHOME', None)
        env.pop('PYTHONPATH', None)
        process = subprocess.Popen([str(runtime/'python/python.exe'), '-X', 'utf8', '-B', '-u', str(runtime/'overlay/backend.py')],
                                   cwd=runtime, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, encoding='utf-8')
        replies, errors = queue.Queue(), []
        def read():
            for line in process.stdout:
                replies.put(json.loads(line))
        def stderr():
            for line in process.stderr:
                errors.append(line)
                print(line.rstrip(), flush=True)
        reader = threading.Thread(target=read, daemon=True)
        error_reader = threading.Thread(target=stderr, daemon=True)
        reader.start(); error_reader.start()
        def send(value):
            process.stdin.write(json.dumps(value)+'\n'); process.stdin.flush()
        try:
            send({'id':1,'op':'solve','budget':2000,'state':state})
            result = replies.get(timeout=20)
            assert result.get('status') == 'ready' and result.get('command'), result
            # Reload while the input reader is blocked waiting for another line.
            # On Windows this reproduces inherited-stdin startup deadlocks too.
            backend = runtime/'overlay/backend.py'
            stamp = backend.stat()
            os.utime(backend, ns=(stamp.st_atime_ns, stamp.st_mtime_ns + 1_000_000_000))
            send({'id':2,'op':'solve','budget':2000,'state':state})
            result = replies.get(timeout=20)
            assert result.get('id') == 2 and result.get('status') == 'ready', result
            send({'id':3,'op':'solve','budget':128000,'state':state})
            started = time.monotonic()
            send({'id':4,'op':'cancel'})
            result = replies.get(timeout=20)
            while result.get('id') != 4:
                result = replies.get(timeout=20)
            assert result.get('status') == 'ready' and result.get('interrupted') and result.get('command'), result
            assert time.monotonic()-started < 5, 'Cancellation too slow'
            divergent = json.loads(json.dumps(state))
            divergent['game_state']['current_hp'] -= 7
            divergent['game_state']['combat_state']['player']['current_hp'] -= 7
            send({'id':5,'op':'advance','budget':2000,'state':divergent})
            result = replies.get(timeout=20)
            assert result.get('id') == 5 and result.get('status') == 'error', result
            send({'op':'export_debug','metadata':{'test':'bundled-windows'}})
            exported = replies.get(timeout=20)
            assert exported['status'] == 'debug_export', exported
            with zipfile.ZipFile(Path(outside)/'bug-reports'/exported['file']) as archive:
                assert json.loads(archive.read('debug.json'))['events']
            process.stdin.close()
            assert process.wait(timeout=15) == 0
            print('PASS: cold start, worker reload, cancellation, divergent-state refusal, debug export and clean exit')
        finally:
            if process.poll() is None:
                process.kill(); process.wait()
            error_reader.join(timeout=2)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',type=Path,required=True)
    parser.add_argument('--state',type=Path,required=True)
    parser.add_argument('--repeats',type=int,default=3)
    args=parser.parse_args()
    manifest=json.loads((args.bundle/'bundle-manifest.json').read_text(encoding='utf-8'))
    for name,digest in manifest['files'].items():
        assert hashlib.sha256((args.bundle/name).read_bytes()).hexdigest()==digest, name
    # Exercise a relocated installation with spaces/Unicode, without changing
    # the release files or depending on the current source checkout.
    with tempfile.TemporaryDirectory(prefix='工坊 clean install ',dir=args.bundle.resolve().parent) as folder:
        relocated=Path(folder)/'content'
        shutil.copytree(args.bundle,relocated)
        for attempt in range(args.repeats):
            check(relocated,args.state)
        print(f'PASS: {args.repeats} runs, verified manifest, relocated Unicode path and minimal PATH')
