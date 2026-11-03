"""Replay a recorded Defect combat against the current build, one fault at a time.

A jump reports a state divergence: a JSON file whose `fault` events each carry the
game frame the search stopped on. This walks those frames through the same path the
backend uses — `view_from_state` -> `import_battle(require_search_state=True)` ->
`compare_battle` — and prints, for each one, whether it imports, what the comparison
found, and the game's own value next to the simulator's for the fields that matter.

Three builds can be compared by pointing `--engine` at a different runtime:

  * this branch           (what ships next)
  * a release tag         (what the jump is running)
  * both, to say which record reproduces where

The frames are read-only evidence: nothing is written back, and a frame that raises
is reported as a raise rather than skipped, because "the import refused" is itself
the finding.

Usage:
  python tools/replay_defect_capture.py <capture.json> [...]
  python tools/replay_defect_capture.py <capture.json> --engine runtime
  python tools/replay_defect_capture.py <capture.json> --only 393 --only 406
  python tools/replay_defect_capture.py <capture.json> --compare
  python tools/replay_defect_capture.py <capture.json> --json > out.json

`--compare` also prints the game value beside the simulator value for block, orbs
and every monster's HP, so a record that no longer reproduces is visible as two
equal numbers rather than a silent pass.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RUNTIME = ROOT / 'candidates/sts-ironclad-agent/runtime'


def load_backend(runtime: Path):
    """Import overlay.backend against one runtime directory.

    The runtime has to be on sys.path and named in the environment before the
    engine is imported: the adapter refuses a second simulator in one process, so a
    single run can only ever look at one build.
    """
    engine = (runtime / 'engine').resolve()
    if not engine.is_dir():
        raise SystemExit(f'no engine at {engine}')
    os.environ['STS_LIGHTSPEED_BUILD'] = str(engine)
    sys.path.insert(0, str(engine))
    sys.path.insert(0, str(ROOT / 'overlay'))
    sys.path.insert(0, str(ROOT / 'candidates/sts-ironclad-agent'))
    import backend
    # A capture of a Defect combat has to pass the class gate the backend applies to
    # a live run; that gate is about what the overlay offers, not about this replay.
    backend.SUPPORTED_CLASSES.setdefault('DEFECT', '机器人')
    return backend


def fault_frames(paths, only):
    """Every fault event with a frame, in file order."""
    for path in paths:
        data = json.loads(Path(path).read_text(encoding='utf-8'))
        for event in data.get('events') or []:
            if event.get('k') != 'fault':
                continue
            request = event.get('id')
            if only and request not in only:
                continue
            yield Path(path), event


def recorded_paths(message):
    """The paths a fault's message says diverged, read out of the recorded text."""
    import re
    return sorted(set(re.findall(r"'path': '([^']+)'", message or '')))


def game_values(frame):
    """The game's own numbers for the fields the records argue about."""
    combat = (frame.get('game_state') or {}).get('combat_state') or {}
    player = combat.get('player') or {}
    return {
        'block': player.get('block'),
        'energy': player.get('energy'),
        'orbs': [(orb or {}).get('id') for orb in player.get('orbs') or []],
        'monster_hp': [monster.get('current_hp') for monster in combat.get('monsters') or []],
    }


def simulator_values(battle):
    """The simulator's own numbers, in the shape game_values() returns."""
    # player.orbs holds the filled orbs as snapshot dicts; empty slots are only
    # counted, matching how the importer keeps the bar.
    orbs = [orb.get('id') if isinstance(orb, dict) else str(orb)
            for orb in battle.player.orbs]
    return {
        'block': int(battle.player.block),
        'energy': int(battle.player.energy),
        'orbs': orbs,
        'orb_slots': int(battle.player.orb_slots),
        'monster_hp': [int(monster.cur_hp) for monster in battle.monsters],
    }


def replay(backend, comparison, frame):
    """Import a recorded frame and compare it, returning what happened."""
    view = backend.view_from_state(frame)
    try:
        battle = comparison.import_battle(view, require_search_state=True)
    except Exception as error:  # noqa: BLE001 - the raise is the finding
        return {'status': 'raised', 'stage': 'import',
                'error': f'{type(error).__name__}: {error}'}
    try:
        result = comparison.compare_battle(view, battle)
    except Exception as error:  # noqa: BLE001
        return {'status': 'raised', 'stage': 'compare',
                'error': f'{type(error).__name__}: {error}'}
    return {'status': 'compared',
            'differences': result['differences'],
            'simulator': simulator_values(battle)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('captures', nargs='+', help='capture JSON file or a directory of them')
    parser.add_argument('--engine', default=str(DEFAULT_RUNTIME),
                        help='runtime directory to replay against (default: the built runtime)')
    parser.add_argument('--only', type=int, action='append',
                        help='replay only these request ids')
    parser.add_argument('--compare', action='store_true',
                        help='print the game value beside the simulator value')
    parser.add_argument('--json', action='store_true', help='emit one JSON document')
    options = parser.parse_args(argv)

    paths = []
    for given in options.captures:
        found = glob.glob(str(Path(given) / '*.json')) if Path(given).is_dir() else [given]
        paths.extend(str(path) for path in sorted(found))
    if not paths:
        raise SystemExit('no capture files found')

    backend = load_backend(Path(options.engine))
    comparison = backend.Advisor().search.comparator

    rows = []
    for path, event in fault_frames(paths, set(options.only or ())):
        frame = event.get('frame')
        row = {'file': Path(path).name, 'fault': event.get('f'), 'request': event.get('id'),
               'recorded': recorded_paths(event.get('message'))}
        if not frame or not frame.get('game_state'):
            row.update(status='no-frame')
        else:
            row.update(replay(backend, comparison, frame))
            row['game'] = game_values(frame)
        rows.append(row)

    if options.json:
        print(json.dumps(rows, ensure_ascii=False, indent=1))
    else:
        for row in rows:
            head = f"f{row['fault']} req {row['request']}  {row['file']}"
            if row['status'] == 'no-frame':
                print(f'{head}\n  no frame recorded')
            elif row['status'] == 'raised':
                print(f"{head}\n  {row['stage'].upper()} RAISED  {row['error']}")
            elif row['differences']:
                print(f'{head}\n  DIVERGED  {json.dumps(row["differences"], ensure_ascii=False)}')
            else:
                print(f'{head}\n  clean')
            if options.compare and 'game' in row:
                game, sim = row['game'], row.get('simulator') or {}
                print(f"    block   game {game['block']!s:>5}   sim {sim.get('block')!s:>5}")
                print(f"    orbs    game {game['orbs']}")
                print(f"            sim  {sim.get('orbs')} ({sim.get('orb_slots')} slots)")
                print(f"    hp      game {game['monster_hp']}")
                print(f"            sim  {sim.get('monster_hp')}")

    raised = sum(1 for row in rows if row['status'] == 'raised')
    diverged = sum(1 for row in rows if row.get('differences'))
    clean = sum(1 for row in rows if row['status'] == 'compared' and not row['differences'])
    print(f'\n{len(rows)} faults: {clean} clean, {diverged} diverged, {raised} raised'
          f'  (engine {Path(options.engine).resolve()})')
    return 0 if not raised and not diverged else 1


if __name__ == '__main__':
    raise SystemExit(main())
