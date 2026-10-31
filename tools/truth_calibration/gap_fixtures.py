"""Turn recorded multi-action harness states into `sts1-calibration/1` native fixtures.

The card-by-card sweep (`coverage_fixtures.py`) records one play against a single dummy at 9999 HP,
so it cannot show anything that needs a later turn, a second monster leaving the board, or Focus
below zero. This exporter keeps every action of a recorded window instead, together with one
checkpoint per action, so a replay has to reproduce the whole chain.

The states come from the real game classes (desktop-1.0.jar bytecode) running on the headless
truth engine through `CoverageGapHarness`; only the projection into the fixture schema uses
repository code.

    python tools/truth_calibration/gap_fixtures.py <rawDir> <outDir> [repoRoot] [--character NAME]

`<rawDir>` holds one `*.json` per scenario, each `{seed, before, steps[], scenario}` as written by
the harness. The scenario name selects the description recorded in `source.scenario`; an unknown
name is still exported, described by its own actions.

`--character` is the game's `PlayerClass` name the window was recorded with: `DEFECT` by default,
`THE_SILENT`, `IRONCLAD` or `WATCHER`. It is written into `character`, which is what tells the
importer which deck, relics and orbs the observation belongs to: a window recorded with a Silent
deck and exported as DEFECT imports as the wrong character. The default keeps every published
Defect capture exporting to the same content (the gzip header carries the write time, so the bytes
differ between runs), and an unknown name is a hard failure rather than a silently mislabelled
capture.
"""
import gzip
import json
import sys
from pathlib import Path

CHARACTERS = {'IRONCLAD', 'THE_SILENT', 'DEFECT', 'WATCHER'}
DEFAULT_CHARACTER = 'DEFECT'


def arguments(argv):
    """Positional `<rawDir> <outDir> [repoRoot]` plus an optional `--character NAME`."""
    positional = []
    character = DEFAULT_CHARACTER
    rest = list(argv)
    while rest:
        arg = rest.pop(0)
        if arg == '--character':
            if not rest:
                raise SystemExit('FAIL: --character needs a value')
            character = rest.pop(0).upper()
        elif arg.startswith('--character='):
            character = arg.split('=', 1)[1].upper()
        else:
            positional.append(arg)
    if character not in CHARACTERS:
        raise SystemExit('FAIL: unknown character ' + character + '; expected one of '
                         + ', '.join(sorted(CHARACTERS)))
    raw_dir = Path(positional[0] if positional else 'gaps')
    out_dir = Path(positional[1] if len(positional) > 1 else 'gaps-out')
    root = Path(positional[2] if len(positional) > 2 else '.')
    return raw_dir, out_dir, root, character


RAW_DIR, OUT_DIR, ROOT, CHARACTER = arguments(sys.argv[1:])
sys.path.insert(0, str(ROOT.resolve() / 'tools/truth_calibration'))
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
    'silent-neutralize': ('silent-neutralize-plain',
                          'Silent starter deck against the first fight of the run: Neutralize is '
                          'played unupgraded, so the Weak it applies has to be one stack'),
    'silent-neutralize-plus': ('silent-neutralize-upgraded',
                                'Silent starter deck after a campfire smith upgraded Neutralize: the '
                                'upgraded card is played in the next fight, so the Weak it applies '
                                'has to be two stacks'),
    'biased-focus': ('defect-biased-focus-negative',
                      'Biased Cognition with two Lightning orbs, then eight end-of-turn cycles: Focus '
                      'falls through zero to -4, the passive clamps to zero damage and the evoke '
                      'clamps, so a zero-damage passive still has to roll its target'),
}


def prefix(character):
    """The fixture-id prefix for a PlayerClass name: `THE_SILENT` -> `silent`."""
    return character.replace('THE_', '').lower()


def fixture(raw, name, path, character=DEFAULT_CHARACTER):
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
        name, (f'{prefix(character)}-{name}', 'recorded window from the coverage-gap harness'))
    return {
        'schema': SCHEMA,
        'id': case_id,
        'game_jar_sha256': GAME_JAR_SHA256,
        'character': character,
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
    out_dir = OUT_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    written = skipped = 0
    for path in sorted(RAW_DIR.glob('*.json')):
        raw = json.loads(path.read_text(encoding='utf-8'))
        if 'error' in raw or not raw.get('steps'):
            print(f'skip {path.stem}: {raw.get("error") or "no steps"}')
            skipped += 1
            continue
        case = fixture(raw, path.stem, path, CHARACTER)
        with gzip.open(out_dir / f'{case["id"]}.json.gz', 'wt', encoding='utf-8',
                       compresslevel=9) as f:
            json.dump(case, f, sort_keys=False, separators=(',', ':'))
        written += 1
    print(f'wrote {written}, skipped {skipped} -> {out_dir}')


if __name__ == '__main__':
    main()
