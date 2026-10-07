"""Fetch the pinned redistributable CPython used for both macOS builds and bundles."""
import argparse
import hashlib
from pathlib import Path
import tarfile
import urllib.request

RELEASE = '20261003'
ARCHIVES = {
    'arm64': ('aarch64', 'd15291f940cfecd2e54010d5e37d2e03aa192f076a65d26ab741372fff2dabfe'),
    'x86_64': ('x86_64', '4b25eec9616cf363ad7181b2e8098f76fc453705fce22322da2502c9e16f44a9'),
}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arch', choices=ARCHIVES, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    arch, digest = ARCHIVES[args.arch]
    name = f'cpython-3.14.8+{RELEASE}-{arch}-apple-darwin-install_only.tar.gz'
    archive = args.output / name
    urllib.request.urlretrieve(f'https://github.com/astral-sh/python-build-standalone/releases/download/{RELEASE}/{name}', archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
        raise ValueError('CPython archive checksum mismatch')
    with tarfile.open(archive) as source:
        source.extractall(args.output, filter='data')
    print(args.output / 'python/bin/python3')
