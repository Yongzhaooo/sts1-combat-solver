"""Build only the combat advisor runtime; no teacher weights or training bundle."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import importlib.machinery
import pybind11

root = Path(__file__).resolve().parents[1]
repo = root / 'candidates/sts-ironclad-agent'
runtime = repo / 'runtime'
build = repo / 'build/engine'
subprocess.run(['cmake', '-S', str(repo / 'combat_engine'), '-B', str(build),
                '-DCMAKE_BUILD_TYPE=Release', '-Dpybind11_DIR=' + pybind11.get_cmake_dir(),
                '-DPython_EXECUTABLE=' + sys.executable,
                '-DPYTHON_EXECUTABLE=' + sys.executable,
                '-DSTS_RUNTIME_DIR=' + str(runtime)], check=True)
subprocess.run(['cmake', '--build', str(build), '--config', 'Release', '-j', '4'], check=True)
subprocess.run(['ctest', '--test-dir', str(build), '-C', 'Release', '--output-on-failure'], check=True)
modules = {}
for name in ('slaythespire', 'fightsim', 'live_combat_search'):
    paths = [runtime / 'engine' / (name + suffix) for suffix in importlib.machinery.EXTENSION_SUFFIXES
             if (runtime / 'engine' / (name + suffix)).exists()]
    if len(paths) != 1 or paths[0].stat().st_size < 1000:
        raise RuntimeError('Expected one native extension for ' + name)
    modules[paths[0].name] = hashlib.sha256(paths[0].read_bytes()).hexdigest()
(runtime / 'live-manifest.json').write_text(json.dumps({'engine_files': modules}, indent=2), encoding='utf-8')
subprocess.run([sys.executable, str(root / 'overlay/build_native.py')], check=True)
subprocess.run([sys.executable, str(root / 'tools/check_solver_runtime.py')], check=True)
print('Combat runtime built; no policy network required.')
