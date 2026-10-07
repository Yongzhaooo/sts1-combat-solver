"""Build the integrated Java mod on Windows, macOS or Linux (JDK 9+ required)."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def property_value(value):
    # Properties.load(InputStream) is Latin-1, even when javac uses UTF-8.
    return json.dumps(str(value), ensure_ascii=True)[1:-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game', required=True, type=Path, help='Steam SlayTheSpire directory')
    parser.add_argument('--modthespire', type=Path, help='Override ModTheSpire.jar location')
    parser.add_argument('--basemod', type=Path, help='Override BaseMod.jar location')
    parser.add_argument('--bundled-windows', action='store_true', help='Use the existing Windows release runtime')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    here = root / 'overlay'
    game = args.game.expanduser().resolve()
    game_jar = game / 'desktop-1.0.jar'
    if not game_jar.is_file():
        game_jar = game / 'SlayTheSpire.app/Contents/Resources/desktop-1.0.jar'
    workshop = game.parent.parent / 'workshop/content/646570'
    mts = (args.modthespire or workshop / '1605060445/ModTheSpire.jar').resolve()
    base = (args.basemod or workshop / '1605833019/BaseMod.jar').resolve()
    for path in (game_jar, mts, base):
        if not path.is_file():
            parser.error('Missing JAR: ' + str(path))
    if not args.bundled_windows:
        subprocess.run([sys.executable, str(root / 'tools/check_solver_runtime.py')], check=True)
    out = here / 'build'
    out.mkdir(exist_ok=True)
    classpath = os.pathsep.join(map(str, (game_jar, mts, base)))
    source_roots = [here / 'src', root / 'references/CommunicationMod/src/main/java',
                    root / 'candidates/sts-ironclad-agent/steam/state_export_mod/src']
    sources = sorted(path for source in source_roots for path in source.rglob('*.java'))
    with tempfile.TemporaryDirectory(prefix='mod-', dir=out) as folder:
        staging = Path(folder)
        classes = staging / 'classes'
        classes.mkdir()
        listing = staging / 'sources.txt'
        listing.write_text('\n'.join('"' + path.as_posix() + '"' for path in sources), encoding='utf-8')
        subprocess.run(['javac', '-J-Dfile.encoding=UTF-8', '-g', '-proc:none', '-encoding', 'UTF-8',
                        '--release', '8', '-cp', classpath, '-d', str(classes), '@' + str(listing)], check=True)
        shutil.copy2(here / 'ModTheSpire.json', classes)
        shutil.copytree(here / 'resources', classes, dirs_exist_ok=True)
        licenses = classes / 'licenses'
        licenses.mkdir()
        shutil.copy2(root / 'references/CommunicationMod/LICENSE', licenses / 'CommunicationMod.txt')
        shutil.copy2(root / 'candidates/sts-ironclad-agent/LICENSE', licenses / 'sts-ironclad-agent.txt')
        properties = {'runtime': 'windows-bundled' if args.bundled_windows else 'native',
                      'research_recording': 'false', 'recorder_build': datetime.now(timezone.utc).isoformat()}
        if not args.bundled_windows:
            # Do not resolve the venv interpreter symlink: that would lose its environment.
            properties.update(python=Path(sys.executable).absolute().as_posix(),
                              backend=(here / 'backend.py').as_posix())
        (classes / 'solver.properties').write_text(''.join(
            key + '=' + property_value(value) + '\n' for key, value in properties.items()), encoding='ascii')
        jar = staging / 'STS1CombatSolver.jar'
        subprocess.run(['jar', 'cf', str(jar), '-C', str(classes), '.'], check=True)
        subprocess.run([sys.executable, str(root / 'tools/check_single_mod.py'), str(jar)], check=True)
        checks = staging / 'checks'
        subprocess.run(['javac', '--release', '8', '-proc:none', '-encoding', 'UTF-8', '-cp',
                        os.pathsep.join((str(classes), classpath)), '-d', str(checks),
                        str(here / 'test/BackendRuntimeCheck.java'), str(here / 'test/LanguageCheck.java')], check=True)
        check_cp = os.pathsep.join((str(checks), str(classes), classpath))
        subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', check_cp,
                        'sts1solver.BackendRuntimeCheck', str(Path(sys.executable).absolute())], check=True)
        subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', check_cp, 'sts1solver.LanguageCheck'], check=True)
        jar.replace(out / jar.name)
    print(out / 'STS1CombatSolver.jar')
    if not args.bundled_windows:
        print('Local native build: keep this checkout and Python environment at their current paths.')


if __name__ == '__main__':
    main()
