"""Package an already built Windows backend with an official embedded Python ZIP."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import zipfile


def package(source, python_zip, vc_runtime, pybind_license, output):
    source, output = source.resolve(), output.resolve()
    if output.exists():
        raise FileExistsError('Use a fresh output directory')
    # Official CPython 3.14.8 x64 embed ZIP; reviewed release-page SHA-256.
    expected = 'a93abe456ab01bd96d7a085b3cdb6566b3063f4241360d114142fbdb07f0a310'
    if hashlib.sha256(python_zip.read_bytes()).hexdigest() != expected:
        raise ValueError('Unexpected embedded Python archive; review version and checksum first')
    runtime = output / 'sts1-solver-runtime'

    def copy(relative, destination=None):
        path = source / relative
        target = destination or runtime / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)

    for name in ('STS1CombatSolver',):
        copy('overlay/build/' + name + '.jar', output / (name + '.jar'))
    with zipfile.ZipFile(output / 'STS1CombatSolver.jar') as jar:
        if b'runtime=windows-bundled' not in jar.read('solver.properties'):
            raise ValueError('Build the JAR with -BundledWindows first')
        metadata = json.loads(jar.read('ModTheSpire.json'))
        if metadata['dependencies'] != ['basemod']:
            raise ValueError('Expected the integrated single-mod build')
    for name in ('backend.py', 'auto_policy.py', 'recovery.py', 'diagnostics.py'):
        copy('overlay/' + name)
    repo = Path('candidates/sts-ironclad-agent')
    for name in ('live_search.py', 'selection_import.py', 'rng_contract.py', 'steam_mcts.py'):
        copy(repo / 'steam' / name)
    for name in ('adapter.py', 'core.py', '__init__.py'):
        copy(repo / 'sim_patch/parity' / name)
    for name in ('compare_cards.py', 'compare_powers.py'):
        copy(repo / 'sim_patch/alignment/tests' / name)
    copy(repo / 'runtime/live-manifest.json')
    manifest = json.loads((source / repo / 'runtime/live-manifest.json').read_text(encoding='utf-8'))
    for name, digest in manifest['engine_files'].items():
        if not name.endswith('.cp314-win_amd64.pyd') or Path(name).name != name:
            raise ValueError('Expected Windows native modules')
        path = repo / 'runtime/engine' / name
        if hashlib.sha256((source / path).read_bytes()).hexdigest() != digest:
            raise ValueError('Native manifest mismatch: ' + name)
        copy(path)
    modules = list((source / 'overlay/build/native').glob('overlay_search.cp314-win_amd64.pyd'))
    if len(modules) != 1:
        raise ValueError('Expected one overlay search module')
    copy(modules[0].relative_to(source))
    python = runtime / 'python'
    python.mkdir(parents=True)
    with zipfile.ZipFile(python_zip) as archive:
        for name in archive.namelist():
            if Path(name).is_absolute() or '..' in Path(name).parts:
                raise ValueError('Unexpected Python archive path')
        archive.extractall(python)
    (python / 'python314._pth').write_text(
        'python314.zip\n.\n../overlay\n../candidates/sts-ironclad-agent\n', encoding='utf-8')
    # Python provides vcruntime; MSVC's C++ library is distributed app-locally.
    for name in ('msvcp140.dll', 'msvcp140_1.dll', 'msvcp140_2.dll', 'msvcp140_atomic_wait.dll',
                 'msvcp140_codecvt_ids.dll', 'concrt140.dll'):
        shutil.copyfile(vc_runtime / name, python / name)
    copy('LICENSE', output / 'LICENSE')
    copy('THIRD_PARTY_NOTICES.md', output / 'THIRD_PARTY_NOTICES.md')
    copy(repo / 'LICENSE', output / 'licenses/sts-ironclad-agent.txt')
    copy('references/CommunicationMod/LICENSE', output / 'licenses/CommunicationMod.txt')
    header = (source / repo / 'combat_engine/third_party/nlohmann/json.hpp').read_text(encoding='utf-8')
    (output / 'licenses/nlohmann-json.txt').write_text(header.split('*/', 1)[0].removeprefix('/*'), encoding='utf-8')
    shutil.copyfile(pybind_license, output / 'licenses/pybind11.txt')
    with (output / 'THIRD_PARTY_NOTICES.md').open('a', encoding='utf-8') as notices:
        notices.write('\nWindows binary package additions: CPython 3.14.8 (license in sts1-solver-runtime/python/LICENSE.txt), '
                      'pybind11 3.1.0 (licenses/pybind11.txt), and Microsoft VC142 app-local C++ runtime. '
                      'Microsoft components remain under Microsoft redistribution terms: '
                      'https://learn.microsoft.com/en-us/visualstudio/releases/2019/redistribution\n')
    for name in ('PRIVACY.md', 'PRIVACY.zh-CN.md', 'WORKSHOP_DESCRIPTION.md', 'WORKSHOP_DESCRIPTION.zh-CN.md', 'WINDOWS_INSTALL.md'):
        copy(name, output / name)
    files = {p.relative_to(output).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(output.rglob('*')) if p.is_file()}
    (output / 'bundle-manifest.json').write_text(json.dumps({
        'platform': 'windows-x64', 'python': '3.14.8', 'python_archive_sha256': expected,
        'files': files}, indent=2), encoding='utf-8')
    print(f'Packaged {len(files)} files: {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'python-zip', 'vc-runtime', 'pybind-license', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    package(args.source, args.python_zip, args.vc_runtime, args.pybind_license, args.output)
