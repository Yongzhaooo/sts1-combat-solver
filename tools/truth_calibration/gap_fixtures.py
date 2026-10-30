"""Turn recorded multi-action harness states into `sts1-calibration/1` native fixtures.

The card-by-card sweep (`coverage_fixtures.py`) records one play against a single dummy at 9999 HP,
so it cannot show anything that needs a later turn, a second monster leaving the board, or Focus
below zero. This exporter keeps every action of a recorded window instead, together with one
checkpoint per action, so a replay has to reproduce the whole chain.

The states come from the real game classes (desktop-1.0.jar bytecode) running on the headless
truth engine through `CoverageGapHarness`; only the projection into the fixture schema uses
repository code.

    python tools/truth_calibration/gap_fixtures.py <rawDir> <outDir> [repoRoot]

`<rawDir>` holds one `*.json` per scenario, each `{seed, before, steps[], scenario}` as written by
the harness. The scenario name selects the description recorded in `source.scenario`; an unknown
name is still exported, described by its own actions.
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

SCENARIOS = {
    'hello-world': ('defect-hello-world-across-turn',
                    'Hello World in play, then three end-of-turn cycles: the start-of-turn draw comes '
                    'from the whole Common pool, not just the Skills'),
    'creative-ai': ('defect-creative-ai-across-turn',
                    'Creative AI in play, then three end-of-turn cycles: a Power is added at the '
                    'start of each later turn'),
    'loop': ('defect-loop-across-turn',
              'Two Lightning orbs and Loop played, then three end-of-turn cycles: the first orb\'s '
              'passive fires again without changing slots'),
    'storm': ('defect-storm-across-turn',
               'Storm in play, then Defragment channels a second Lightning and raises Focus to 1, '
               'then two end-of-turn cycles: the orb count carries into the next turn'),
    'multicast-kill': ('defect-multicast-kill-mid-fight',
                        'Three Lightning orbs and three monsters at 9/1/9 HP, Multi-Cast for 3 '
                        'energy: the second evoke rolls against a board the first one already emptied'),
    'fission-kill': ('defect-fission-kill-mid-fight',
                      'Three Lightning orbs and three monsters at 9/1/9 HP, Fission+ played: every orb '
                      'evokes one at a time, so each rolls against the survivors so far'),
    'thunder-strike': ('defect-thunder-strike-kill-mid-fight',
                        'Three Lightning orbs and three monsters at 9/1/9 HP, Thunder Strike on the '
                        'first: its hits re-roll against the board after a target dies'),
    'biased-focus': ('defect-biased-focus-negative',
                      'Biased Cognition with two Lightning orbs, then eight end-of-turn cycles: Focus '
                      'falls through zero to -4, the passive clamps to zero damage and the evoke '
                      'clamps, so a zero-damage passive still has to roll its target'),
}


def fixture(raw, name, path):
    before = project(raw['before'])
    actions = []
    checkpoints = [{'after': 0, 'observation': before}]
    for index, step in enumerate(raw['steps'], start=1):
        actions.append(dict(step['action']))
        # The run's own Neow, map and proceed steps only exist to reach the fight; Neow's choices
        # depend on the unlock state, so they are replayed but never asserted.
        if 'after' in step:
            checkpoints.append({'after': index, 'observation': project(step['after'])})
    case_id, scenario = SCENARIOS.get(
        name, (f'defect-{name}', 'recorded window from the coverage-gap harness'))
    return {
        'schema': SCHEMA,
        'id': case_id,
        'game_jar_sha256': GAME_JAR_SHA256,
        'character': 'DEFECT',
        'ascension': 0,
        'seed': str(raw['seed']),
        'actions': actions,
        'checkpoints': checkpoints,
        'source': {
            'truth': TRUTH,
            'truth_identity': TRUTH_IDENTITY,
            'exporter': 'sts1-combat-solver tools/truth_calibration/gap_fixtures.py',
            'calibration': 'fixtures/calibration/ 24/24 published cases',
            'scenario': scenario,
        },
    }


def main():
    raw_dir = Path(sys.argv[1] if len(sys.argv) > 1 else 'gaps')
    out_dir = Path(sys.argv[2] if len(sys.argv) > 2 else 'gaps-out')
    out_dir.mkdir(parents=True, exist_ok=True)
    written = skipped = 0
    for path in sorted(raw_dir.glob('*.json')):
        raw = json.loads(path.read_text(encoding='utf-8'))
        if 'error' in raw or not raw.get('steps'):
            print(f'skip {path.stem}: {raw.get("error") or "no steps"}')
            skipped += 1
            continue
        case = fixture(raw, path.stem, path)
        with gzip.open(out_dir / f'{case["id"]}.json.gz', 'wt', encoding='utf-8',
                       compresslevel=9) as f:
            json.dump(case, f, sort_keys=False, separators=(',', ':'))
        written += 1
    print(f'wrote {written}, skipped {skipped} -> {out_dir}')


if __name__ == '__main__':
    main()
