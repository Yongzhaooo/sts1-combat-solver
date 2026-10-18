"""Split verified Workshop content into Windows and macOS downloads."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def package(content, output, version):
    content, output = Path(content), Path(output)
    if not version or any(c not in '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-' for c in version):
        raise ValueError('Invalid version')
    manifest = json.loads((content / 'bundle-manifest.json').read_text(encoding='utf-8'))
    if manifest.get('version', version) != version:
        raise ValueError('Version differs from content manifest')
    files = manifest['files']
    actual = {p.relative_to(content).as_posix() for p in content.rglob('*') if p.is_file()} - {'bundle-manifest.json'}
    if actual != set(files):
        raise ValueError('Content differs from manifest')
    for name, digest in files.items():
        if '\\' in name or PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts:
            raise ValueError('Unsafe manifest path')
        if hashlib.sha256((content / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Manifest hash mismatch: ' + name)
    mac = {'sts1-solver-macos-arm64.zip', 'sts1-solver-macos-x86_64.zip'}
    if not mac <= set(files) or 'STS1CombatSolver.jar' not in files or not any(n.startswith('sts1-solver-runtime/') for n in files):
        raise ValueError('Missing platform runtime or Mod JAR')
    output.mkdir(parents=True, exist_ok=True)
    names = {p: 'STS1CombatSolver-%s-v%s.zip' % (p, version) for p in ('windows', 'macos')}
    if any((output / n).exists() for n in (*names.values(), 'SHA256SUMS.txt')):
        raise FileExistsError('Use fresh asset output paths')
    assets = []
    for platform, name in names.items():
        selected = {n: h for n, h in files.items()
                    if (n not in mac if platform == 'windows' else not n.startswith('sts1-solver-runtime/'))}
        selected.pop('MACOS_INSTALL.md' if platform == 'windows' else 'WINDOWS_INSTALL.md', None)
        platform_manifest = dict(manifest, platform='windows-x64' if platform == 'windows' else 'macos-arm64-x86_64',
                                 files=selected)
        if platform == 'macos':
            platform_manifest.pop('python_archive_sha256', None)
            platform_manifest.pop('python', None)  # Each macOS runtime carries its own Python metadata.
        archive = output / name
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as bundle:
            for member in sorted(selected):
                bundle.write(content / member, member)
            bundle.writestr('bundle-manifest.json', json.dumps(platform_manifest, indent=2))
        with zipfile.ZipFile(archive) as bundle:
            if bundle.testzip() is not None:
                raise ValueError('Invalid ZIP: ' + name)
            if set(bundle.namelist()) != set(selected) | {'bundle-manifest.json'}:
                raise ValueError('Archive members differ from manifest')
        assets.append(dict(zip=name, sha256=hashlib.sha256(archive.read_bytes()).hexdigest(), bytes=archive.stat().st_size))
    (output / 'SHA256SUMS.txt').write_text(''.join('%s  %s\n' % (a['sha256'], a['zip']) for a in assets), encoding='utf-8')
    return {'archives': assets}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--content', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    print(json.dumps(package(args.content, args.output, args.version)))
