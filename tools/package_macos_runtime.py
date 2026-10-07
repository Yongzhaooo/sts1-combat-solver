"""Package the built macOS backend and its unmodified standalone Python installation."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import sys
import sysconfig
import subprocess
import zipfile


def package(python_root, output):
    root = Path(__file__).resolve().parents[1]
    repo = Path('candidates/sts-ironclad-agent')
    if sys.platform != 'darwin' or sys.version_info[:2] != (3, 14):
        raise RuntimeError('Build and package with the pinned macOS Python 3.14 interpreter')
    files = [Path('overlay') / name for name in ('backend.py', 'auto_policy.py', 'recovery.py', 'diagnostics.py')]
    files += [repo / 'steam' / name for name in ('live_search.py', 'selection_import.py', 'rng_contract.py', 'steam_mcts.py')]
    files += [repo / 'sim_patch/parity' / name for name in ('adapter.py', 'core.py', '__init__.py')]
    files += [repo / 'sim_patch/alignment/tests' / name for name in ('compare_cards.py', 'compare_powers.py')]
    manifest = json.loads((root / repo / 'runtime/live-manifest.json').read_text(encoding='utf-8'))
    files.append(repo / 'runtime/live-manifest.json')
    for name, digest in manifest['engine_files'].items():
        if Path(name).name != name or not name.endswith(sysconfig.get_config_var('EXT_SUFFIX')):
            raise ValueError('Unexpected native module: ' + name)
        module = repo / 'runtime/engine' / name
        if hashlib.sha256((root / module).read_bytes()).hexdigest() != digest:
            raise ValueError('Native manifest mismatch: ' + name)
        files.append(module)
    files.append(Path('overlay/build/native/overlay_search' + sysconfig.get_config_var('EXT_SUFFIX')))
    files += [Path('LICENSE'), Path('THIRD_PARTY_NOTICES.md'), repo / 'LICENSE']
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for relative in files:
            archive.write(root / relative, relative.as_posix())
        # Dereference symlinks: Java's ZIP extraction does not restore symlinks or Unix modes.
        # Preserve the complete upstream Python tree, including all dependency licenses.
        for path in sorted(python_root.rglob('*')):
            if path.is_file():
                archive.write(path, 'python/' + path.relative_to(python_root).as_posix())
        dist = importlib.metadata.distribution('pybind11')
        license_path = next(p for p in dist.files if p.name == 'LICENSE')
        archive.write(dist.locate_file(license_path), 'licenses/pybind11.txt')
        header = (root / repo / 'combat_engine/third_party/nlohmann/json.hpp').read_text(encoding='utf-8')
        archive.writestr('licenses/nlohmann-json.txt', header.split('*/', 1)[0].removeprefix('/*'))
        # install_only omits the native dependency notices from the full distribution.
        for license_path in sorted((root / 'tools/python-licenses').iterdir()):
            archive.write(license_path, 'licenses/python-build-standalone/' + license_path.name)
        archive.writestr('bundle.json', json.dumps({'platform': 'macos-' + platform.machine(),
            'python': platform.python_version(), 'source_commit': subprocess.check_output(
                ['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()}))
    print(output, hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--python-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package(args.python_root.resolve(), args.output.resolve())
