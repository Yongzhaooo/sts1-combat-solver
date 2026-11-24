"""Account for every checkpoint of a character's calibration captures.

The Watcher captures in `fixtures/calibration/WATCHER` hold 1052 checkpoints, and the work on the
character is staged: the stance state machine landed first, its cards come later. So the question
this answers is not "does everything pass" but "where did every one of the 1052 go" -- how many
were compared against the simulator, how many the capture never recorded enough to compare, and
how many the simulator cannot import yet. The three numbers must add up to the checkpoint count;
nothing may be dropped between them.

The third number is broken down by cause, and the causes are not interchangeable. A card the
simulator does not implement yet, a checkpoint taken outside a fight and one taken after the fight
ended are all "not importable", but only the first is work this repository has already planned.
Anything the importer refuses for a reason no cause names is printed under "unexplained" instead
of being absorbed: that is a capture defect or an import gap until someone shows otherwise.

Nothing here grades the simulator beyond a difference: a compared checkpoint that disagrees is a
failure, an import that never happened is not. A set where nothing could be compared prints SKIP
rather than PASS, because its import boundary has not been exercised and passing would claim a
check that did not happen.

Field-level gaps are listed on top of the three numbers: a compared checkpoint can still skip a
field the capture never recorded, and those are named so nothing looks checked that was not.

Run it against any character directory:
    python tools/check_calibration_parity.py fixtures/calibration/WATCHER

`--expect-checkpoints=N` pins the denominator. CI passes the count it was written against, so a
capture set that lost checkpoints fails instead of quietly shrinking its own denominator.
"""
import json
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'overlay'))
sys.path.insert(0, str(REPO / 'tools'))
import check_coverage_gaps as gaps  # noqa: E402

COMPARED, UNOBSERVABLE, UNIMPORTABLE, FAILED = 'compared', 'unobservable', 'not importable', 'failed'
UNEXPLAINED = 'unexplained'
UNEXPLAINED_CAUSE = 'an import the report cannot name'


def account(path, root, rng_names):
    """Bucket every checkpoint of one fixture.

    Reuses `check_coverage_gaps.check_case` for the import and comparison, so the two tools cannot
    drift apart on what a bucket means. Returns the counts, the causes behind them, the fields a
    compared checkpoint could not see, the imports nothing explains, and the disagreements.
    """
    case = gaps.read_case(path)
    result = gaps.check_case(path, root, rng_names)
    counts = Counter()
    causes = Counter()
    unrecorded_fields = Counter()
    unexplained = []
    for record in result['records']:
        status = record['status']
        if status == 'compared':
            counts[COMPARED] += 1
            unrecorded_fields.update(record['unrecorded'])
        elif status == 'unobserved':
            counts[UNOBSERVABLE] += 1
            causes[(UNOBSERVABLE, record['cause'])] += 1
        elif status == 'failed':
            counts[FAILED] += 1
            causes[(FAILED, 'the comparison disagrees')] += 1
        else:
            counts[UNIMPORTABLE] += 1
            if record.get('card'):
                # A card the simulator does not implement: the checkpoint waits for that card.
                causes[(UNIMPORTABLE, 'unimplemented card ' + record['card'])] += 1
            elif status in ('no_board', 'terminal'):
                # The capture was not asking for a board: named by phase or by the fight's end.
                causes[(UNIMPORTABLE, record['cause'])] += 1
            else:
                causes[(UNIMPORTABLE, UNEXPLAINED_CAUSE)] += 1
                unexplained.append(f'{record["what"]}: {record["cause"]}')
    expected = len(case['checkpoints'])
    assert sum(counts.values()) == expected, f'{case["id"]}: {counts} does not add up to {expected}'
    return case['id'], expected, counts, causes, unrecorded_fields, unexplained, result['failures']


def expected_checkpoints(argv):
    """The denominator the caller pins, so a capture set that shrank cannot pass quietly."""
    for arg in argv:
        if arg.startswith('--expect-checkpoints='):
            return int(arg.split('=', 1)[1])
    return None


def main():
    from sim_patch.parity.adapter import RNG_NAMES

    expected = expected_checkpoints(sys.argv[1:])
    targets = [Path(arg) for arg in sys.argv[1:] if not arg.startswith('--')] or sorted(
        REPO.glob('fixtures/calibration/*'))
    paths = []
    for target in targets:
        if target.is_dir():
            paths.extend(sorted(target.rglob('*.json.gz')))
            paths.extend(sorted(target.rglob('*.json')))
        else:
            paths.append(target)
    if not paths:
        print(f'no fixtures found under {", ".join(str(t) for t in targets)}')
        return 1

    root = json.loads((REPO / 'overlay' / 'test' / 'live-root.json').read_text(encoding='utf-8'))
    totals = Counter()
    causes = Counter()
    unrecorded_fields = Counter()
    unexplained = []
    failures = []
    for path in paths:
        case_id, checkpoints, counts, case_causes, fields, case_unexplained, case_failures = account(
            path, root, RNG_NAMES)
        assert sum(counts.values()) == checkpoints
        totals.update(counts)
        causes.update(case_causes)
        unrecorded_fields.update(fields)
        unexplained.extend(case_unexplained)
        failures.extend(case_failures)
        print(f'{case_id:24s} {checkpoints:5d} checkpoints: '
              f'{counts[COMPARED]:5d} compared, {counts[UNOBSERVABLE]:5d} unobservable, '
              f'{counts[UNIMPORTABLE]:5d} not importable, {counts[FAILED]:5d} disagree')

    checkpoints = sum(totals.values())
    if expected is not None and checkpoints != expected:
        print(f'\n{checkpoints} checkpoints were read but {expected} were expected: a capture set '
              f'that shrank cannot be accounted for by a smaller denominator')
        return 1
    summary = (f'{totals[COMPARED]} compared, {totals[UNOBSERVABLE]} unobservable, '
               f'{totals[UNIMPORTABLE]} not importable')
    if totals[FAILED]:
        summary += f', {totals[FAILED]} disagree'
    print(f'\n{len(paths)} fixtures, {checkpoints} checkpoints: {summary}')

    print('\nnot importable, by cause:')
    for (bucket, cause), count in sorted(causes.items(), key=lambda item: (-item[1], item[0][1])):
        if bucket == UNIMPORTABLE:
            print(f'  {count:5d}  {cause}')
    for bucket in (UNOBSERVABLE, FAILED):
        named = [(cause, count) for (b, cause), count in causes.items() if b == bucket]
        if named:
            print(f'\n{bucket}, by cause:')
            for cause, count in sorted(named, key=lambda item: (-item[1], item[0])):
                print(f'  {count:5d}  {cause}')

    if unrecorded_fields:
        print('\nfields inside compared checkpoints that the capture never recorded '
              '(these stay in "compared"; listed so nothing looks checked that was not):')
        for name, count in unrecorded_fields.most_common():
            print(f'  {count:5d}  {name}')

    if unexplained:
        print(f'\n{len(unexplained)} checkpoint(s) the importer refused for a reason no cause above '
              f'names. They are counted in "not importable"; listed here rather than absorbed:')
        for line in unexplained[:10]:
            print('  ' + line)
        if len(unexplained) > 10:
            print(f'  ... and {len(unexplained) - 10} more')

    if not totals[COMPARED]:
        print('\nSKIP: no checkpoint could be compared, so this set does not exercise the import '
              'boundary yet. The numbers above say where every checkpoint went; they do not say '
              'the boundary works.')

    if failures:
        print(f'\n{len(failures)} checkpoint(s) disagree with the simulator:')
        for failure in failures:
            print('  ' + failure)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())