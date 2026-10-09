"""Build and check both local test mods; never launch or install the game."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile


def revision(path):
    def git(*args):
        return subprocess.check_output(
            ['git', '-c', 'core.fsmonitor=false', '-C', str(path), *args],
            encoding='utf-8').strip()
    return {'commit': git('rev-parse', 'HEAD'),
            'dirty': bool(git('status', '--porcelain'))}


def inspect_jar(path, expected):
    with zipfile.ZipFile(path) as jar:
        metadata = json.loads(jar.read('ModTheSpire.json'))
        if metadata['modid'] != expected:
            raise RuntimeError(f'Wrong mod in {path}: {metadata}')
        forbidden = [name for name in jar.namelist()
                     if name.endswith('.jar') or 'NativeProbe' in name
                     or 'NativeHistoryProbe' in name or name.endswith('Check.class')]
        if forbidden:
            raise RuntimeError(f'Test drivers/dependencies in shipping JAR: {forbidden}')
    return {'id': expected, 'version': metadata['version'],
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game', type=Path, required=True)
    parser.add_argument('--state-mod', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True,
                        help='New evidence directory; existing directories are refused')
    parser.add_argument('--rebuild-runtime', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    state = args.state_mod.resolve()
    game = args.game.resolve()
    for path in (state / 'build.ps1', state / 'ModTheSpire.json', game / 'desktop-1.0.jar'):
        if not path.is_file():
            parser.error(f'Missing input: {path}')
    shell = shutil.which('pwsh') or shutil.which('powershell')
    if not shell:
        parser.error('PowerShell is required for the State Notes build')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    environment = dict(os.environ, PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
    checks = []

    def run(label, command):
        print(f'Running {label}', flush=True)
        with (out / (label + '.log')).open('wb') as log:
            process = subprocess.run(command, cwd=root, env=environment,
                                     stdout=log, stderr=subprocess.STDOUT)
        if process.returncode:
            raise RuntimeError(f'{label} failed ({process.returncode}); see {out / (label + ".log")}')
        checks.append(label)

    if args.rebuild_runtime:
        run('native-runtime', [sys.executable, str(root / 'tools/build_solver_runtime.py')])
    run('solver', [sys.executable, str(root / 'overlay/build.py'), '--game', str(game)])
    run('state-notes', [shell, '-NoProfile', '-File', str(state / 'build.ps1'),
                        '-Game', str(game), '-Check'])
    run('headbutt', [sys.executable, str(root / 'tools/check_headbutt.py')])
    run('armaments-identity', [sys.executable, str(root / 'tools/check_armaments_identity.py')])
    run('run-log', [sys.executable, str(root / 'overlay/check_run_log.py')])
    mods = {}
    for source, mod_id in ((root / 'overlay/build/STS1CombatSolver.jar', 'sts1solver'),
                           (state / 'build/STS1StateNotes.jar', 'sts1-state-notes')):
        mods[source.name] = inspect_jar(source, mod_id)
        shutil.copy2(source, out / source.name)
    manifest = {'built_at': datetime.now(timezone.utc).isoformat(),
                'solver_source': revision(root), 'state_source': revision(state),
                'mods': mods, 'checks_passed': checks,
                'runtime': 'native-checkout-bound',
                'joint_game_test': 'pending_manual', 'installed': False}
    (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    (out / 'README.txt').write_text(
        '双 mod 本机测试包\n'
        '退出游戏后安装两个 JAR；在 MTS 中同时勾选求解器和 State Notes。\n'
        '求解器使用构建机器的 Python 和源码绝对路径；不能分发给其他机器。\n'
        '需要保留构建时的求解器目录和 Python 环境。游戏联合实测尚未执行。\n'
        '测试：遗物先于卡牌、头槌、事件选项、空鸟笼、最后蓝钥匙，\n'
        '以及全自动期间打开/关闭构筑记录界面。\n'
        '当前自动化来源可能为 unknown；不得把这些测试记录当真人训练样本。\n'
        '附 manifest.json 对应这一对 JAR；不包含游戏、依赖或玩家存档。\n', encoding='utf-8')
    archive = out / 'STS1-mod-pair-test.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
        for name in (*mods, 'manifest.json', 'README.txt'):
            package.write(out / name, name)
    # Check the actual deliverable, including byte identity, not just build exit codes.
    with zipfile.ZipFile(archive) as package:
        for name, metadata in mods.items():
            if hashlib.sha256(package.read(name)).hexdigest() != metadata['sha256']:
                raise RuntimeError(f'Package byte mismatch: {name}')
        if set(package.namelist()) != set(mods) | {'manifest.json', 'README.txt'}:
            raise RuntimeError('Unexpected files in pair package')
    print(f'PASS: both mods built and checked; manual joint game test pending\n{archive}')


if __name__ == '__main__':
    main()
