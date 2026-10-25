"""Turn CoverageHarness raw states into `sts1-calibration/1` native fixtures.

One record per (Defect card, upgrade state): the battle-start observation, the single play action,
and the observation after it. The states come from the real game bytecode (GameKernel), so the
numbers are the game's own; only the projection uses repository code.

    python tools/truth_calibration/coverage_fixtures.py <rawDir> <outDir>

This script is a local aid for producing fixtures, not part of the public CI: `fixtures/native/`
is what CI replays, and the harness that produced the raw states lives with the truth engine.
"""
import gzip
import json
import sys
from pathlib import Path

ROOT = Path(sys.argv[3] if len(sys.argv) > 3 else '.').resolve()
sys.path.insert(0, str(ROOT / 'tools/truth_calibration'))
from headless_projection import project  # noqa: E402

SCHEMA = 'sts1-calibration/1'
GAME_JAR_SHA256 = 'cfad868ac8d65a88e71a0bf096fb09f78811e553effe0787c5309a655e081673'
TRUTH = 'calibrated headless engine (SlayTheSpireHeadlessEngine)'
TRUTH_IDENTITY = '1d6344debc290632f55d5ac25abf36ca284a8cdc'


def fixture(raw, path):
    before = project(raw['before'])
    after = project(raw['after'])
    card = raw['cardId']
    action = {'kind': 'play', 'hand': 0, 'card': card}
    if raw['target'] in ('ENEMY', 'SELF_AND_ENEMY'):
        action['target'] = 0
    name = Path(path).stem
    return {
        'schema': SCHEMA,
        'id': f'defect-{name}',
        'game_jar_sha256': GAME_JAR_SHA256,
        'character': 'DEFECT',
        'ascension': 0,
        'seed': str(raw['seed']),
        'actions': [action],
        'checkpoints': [{'after': 0, 'observation': before},
                        {'after': 1, 'observation': after}],
        'source': {
            'truth': TRUTH,
            'truth_identity': TRUTH_IDENTITY,
            'exporter': 'sts1-combat-solver tools/truth_calibration/coverage_fixtures.py',
            'calibration': 'fixtures/calibration/ 24/24 published cases',
            'scenario': 'single card in hand at battle start, dummy monster at 9999 HP',
        },
    }


def main():
    raw_dir = Path(sys.argv[1] if len(sys.argv) > 1 else 'cov')
    out_dir = Path(sys.argv[2] if len(sys.argv) > 2 else 'out')
    out_dir.mkdir(parents=True, exist_ok=True)
    written = skipped = 0
    for path in sorted(raw_dir.glob('*.json')):
        raw = json.loads(path.read_text(encoding='utf-8'))
        if 'error' in raw or raw.get('phase') not in ('PLAYER_TURN', 'MONSTER_TURN'):
            print(f'skip {path.stem}: {raw.get("error") or raw.get("phase")}')
            skipped += 1
            continue
        case = fixture(raw, path)
        with gzip.open(out_dir / f'{case["id"]}.json.gz', 'wt', encoding='utf-8', compresslevel=9) as f:
            json.dump(case, f, sort_keys=False, separators=(',', ':'))
        written += 1
    print(f'wrote {written}, skipped {skipped} -> {out_dir}')


if __name__ == '__main__':
    main()
