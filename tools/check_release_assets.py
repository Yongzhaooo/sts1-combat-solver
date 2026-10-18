import hashlib
import json
from pathlib import Path
import tempfile
import zipfile
from package_release_assets import package

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    content = root / 'content'
    content.mkdir()
    names = ('STS1CombatSolver.jar', 'sts1-solver-runtime/python/python.exe',
             'sts1-solver-macos-arm64.zip', 'sts1-solver-macos-x86_64.zip',
             'WINDOWS_INSTALL.md', 'MACOS_INSTALL.md', 'LICENSE')
    for name in names:
        path = content / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(name.encode())
    files = {n: hashlib.sha256((content / n).read_bytes()).hexdigest() for n in names}
    (content / 'bundle-manifest.json').write_text(json.dumps(dict(files=files, source_commit='same-source')))
    result = package(content, root / 'out', '0.8.0-test')
    assert len(result['archives']) == 2
    for asset in result['archives']:
        with zipfile.ZipFile(root / 'out' / asset['zip']) as bundle:
            manifest = json.loads(bundle.read('bundle-manifest.json'))
            assert manifest['source_commit'] == 'same-source'
            for name, digest in manifest['files'].items():
                assert hashlib.sha256(bundle.read(name)).hexdigest() == digest
            assert 'STS1CombatSolver.jar' in bundle.namelist()
            windows = '-windows-' in asset['zip']
            assert ('sts1-solver-runtime/python/python.exe' in bundle.namelist()) == windows
            assert ('sts1-solver-macos-arm64.zip' in bundle.namelist()) != windows
            assert ('sts1-solver-macos-x86_64.zip' in bundle.namelist()) != windows
    (content / 'STS1CombatSolver.jar').write_bytes(b'tampered')
    try:
        package(content, root / 'bad', '0.8.0-test')
        raise AssertionError('Must refuse stale content')
    except ValueError:
        pass
print('PASS: platform separation, matching manifests, SHA256, corrupt-content refusal')
