"""Verify the real Java extractor and a relocated macOS bundle without developer tools on PATH."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('archive', type=Path)
args = parser.parse_args()
with zipfile.ZipFile(args.archive) as archive:
    for name in ('LICENSE', 'LICENSE.cpython.txt', 'LICENSE.openssl-3.txt', 'LICENSE.libffi.txt', 'LICENSE.bzip2.txt'):
        assert len(archive.read('licenses/python-build-standalone/' + name)) > 100, name
with tempfile.TemporaryDirectory(prefix='工坊 clean install ') as folder:
    temp = Path(folder)
    for name, body in {
        'Loader': 'public static ModInfo[] MODINFOS = new ModInfo[0];',
        'ModInfo': 'public String ID; public java.net.URL jarURL;',
    }.items():
        (temp / (name + '.java')).write_text(
            'package com.evacipated.cardcrawl.modthespire; public class ' + name + ' {' + body + '}', encoding='utf-8')
    subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-d', str(temp),
        str(temp / 'Loader.java'), str(temp / 'ModInfo.java'),
        str(root / 'overlay/src/sts1solver/BackendRuntime.java'),
        str(root / 'overlay/test/UnpackRuntime.java')], check=True)
    runtime = Path(subprocess.check_output(['java', '-Dfile.encoding=UTF-8', '-cp', str(temp),
        'sts1solver.UnpackRuntime', str(args.archive.resolve()), str(temp / 'data')], text=True, encoding='utf-8').strip())
    env = dict(os.environ, PATH='/usr/bin:/bin', PYTHONUTF8='1', PYTHONIOENCODING='utf-8',
               STS_SOLVER_DATA=str(temp / 'reports'))
    env.pop('PYTHONHOME', None)
    env.pop('PYTHONPATH', None)
    python = runtime / 'python/bin/python3'
    for script in ('check_solver_runtime.py', 'check_backend_protocol.py'):
        subprocess.run([str(python), '-B', str(root / 'tools' / script), '--root', str(runtime)],
                       cwd=temp, env=env, check=True, timeout=120)
    print('PASS: production extraction, relocated Unicode path, bundled Python, native search/replay, worker protocol')
