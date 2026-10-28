"""Export Slay the Spire run history (.run files) into the route-history bundle.

    python tools/route_history/export_runs.py --runs-dir "<game>/runs/2_IRONCLAD" \
        --out tools/route_history/data/ironclad-a20.jsonl

One output line per finished run. Format: docs/route-tuning.md. The exporter keeps only what a
route-preference review needs: the per-act room sequence, the per-floor HP/max HP/gold that the
planner's `low_hp_elites` and shop terms read, the campfire choices, and the run outcome. It drops
the identifiers the game save carries for account and machine tracking (`play_id`, `timestamp`,
the full `local_time` clock) and keeps the seed, which is what makes a run reproducible.

The room sequence comes from `path_per_floor`: one entry per floor, `null` between acts. `M`
monster, `E` elite, `R` campfire, `?` unknown, `$` shop, `T` treasure, `B` boss.
`path_taken` is the same walk compressed, but it keeps `?` for rooms that `path_per_floor` and the
recorded campfire/event floors resolve, so it is only cross-checked here, never used for the bundle.
"""
import argparse
import json
from pathlib import Path
import sys

SCHEMA = 'sts1-route-history/1'
ENCODINGS = ('utf-8-sig', 'utf-8', 'gbk', 'cp1252')
ROOMS = frozenset('MER?$TB')
# Acts 1-3 are 16-17 rows (boss included) on every standard map; below this one the run stopped early.
SHORT_ACT_NODES = 12


def read_run(path):
    """Parse one .run file. The game writes these as JSON in the platform charset, not UTF-8."""
    raw = path.read_bytes()
    for encoding in ENCODINGS:
        try:
            return json.loads(raw.decode(encoding))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
    raise ValueError(f'{path}: not readable as ' + '/'.join(ENCODINGS))


def act_floors(path_per_floor):
    """Split `path_per_floor` on its null separators into [(act, [(floor, room), ...]), ...]."""
    acts, current = [], []
    for index, room in enumerate(path_per_floor):
        if room is None:
            if current:
                acts.append(current)
            current = []
            continue
        current.append((index + 1, room))
    if current:
        acts.append(current)
    return list(enumerate(acts, start=1))


def slice_or_none(values, floors):
    """The per-floor companion arrays stop one floor short of `path_per_floor`; pad with None."""
    if not isinstance(values, list):
        return [None] * len(floors)
    return [values[floor - 1] if floor - 1 < len(values) else None for floor, _ in floors]


def campfires(raw):
    out = []
    for choice in raw.get('campfire_choices') or []:
        floor, key = choice.get('floor'), choice.get('key')
        if isinstance(floor, (int, float)) and isinstance(key, str):
            out.append({'floor': int(floor), 'key': key})
    return sorted(out, key=lambda choice: choice['floor'])


def path_taken_mismatches(raw, path_per_floor):
    """Count floors where the compressed walk disagrees with the resolved one (`M`/`$`/`T` vs `?`)."""
    compressed = [room for room in path_per_floor if room is not None]
    taken = [('B' if room == 'BOSS' else room) for room in raw.get('path_taken') or []]
    if len(taken) != len(compressed):
        return {'count': None, 'length_differs': True}
    return {'count': sum(1 for a, b in zip(compressed, taken) if a != b), 'length_differs': False}


def build_record(raw, source):
    path_per_floor = raw.get('path_per_floor') or []
    if not all(room is None or room in ROOMS for room in path_per_floor):
        unknown = sorted({room for room in path_per_floor if room is not None and room not in ROOMS})
        raise ValueError(f'{source}: unknown room symbol(s) {unknown}')
    local_time = str(raw.get('local_time') or '')
    acts, issues = [], []
    for act, floors in act_floors(path_per_floor):
        rooms = ''.join(room for _, room in floors)
        if act <= 3 and len(floors) < SHORT_ACT_NODES:
            issues.append({'type': 'short_act', 'act': act, 'nodes': len(floors)})
        if not rooms.endswith('B') or rooms.count('B') > (2 if act == 3 else 1):
            # Ascension 20 fights the act 3 double boss; any other shape means the walk was resumed
            # or interrupted and never resolved one boss room.
            issues.append({'type': 'odd_boss', 'act': act, 'rooms': rooms})
        acts.append({
            'act': act,
            'rooms': rooms,
            'hp': slice_or_none(raw.get('current_hp_per_floor'), floors),
            'max_hp': slice_or_none(raw.get('max_hp_per_floor'), floors),
            'gold': slice_or_none(raw.get('gold_per_floor'), floors),
            'entry': entry_state(raw, floors),
        })
    mismatch = path_taken_mismatches(raw, path_per_floor)
    if mismatch['count']:
        issues.append({'type': 'path_taken_differs', **mismatch})
    return {
        'schema': SCHEMA,
        'character': raw.get('character_chosen'),
        'ascension': raw.get('ascension_level'),
        'victory': bool(raw.get('victory')),
        'floor_reached': raw.get('floor_reached'),
        'seed': str(raw.get('seed_played')),
        'date': f'{local_time[0:4]}-{local_time[4:6]}-{local_time[6:8]}' if len(local_time) >= 8 else None,
        'build': raw.get('build_version'),
        'score': raw.get('score'),
        'playtime_seconds': raw.get('playtime'),
        'campfires': campfires(raw),
        'acts': acts,
        'issues': issues,
    }


def entry_state(raw, floors):
    """The separator row after an act is the act transition: its HP/gold are the next act's start."""
    index = floors[-1][0] if floors else 0
    state = {}
    for key, field in (('hp', 'current_hp_per_floor'), ('max_hp', 'max_hp_per_floor'), ('gold', 'gold_per_floor')):
        values = raw.get(field)
        state[key] = values[index] if isinstance(values, list) and index < len(values) else None
    return state


def record_problems(record, path_per_floor):
    """Structural invariants; a violation means the export is wrong, not that the run is unusual.

    `floor_reached` counts every entry of `path_per_floor`, the act separators included, so the room
    total plus those separators has to match it.
    """
    problems = []
    nodes = sum(len(act['rooms']) for act in record['acts'])
    separators = sum(1 for room in path_per_floor if room is None)
    if len(path_per_floor) != record['floor_reached']:
        problems.append(f'path_per_floor {len(path_per_floor)} != floor_reached {record["floor_reached"]}')
    if nodes + separators != len(path_per_floor):
        problems.append(f'rooms {nodes} + separators {separators} != path_per_floor {len(path_per_floor)}')
    for act in record['acts']:
        for key in ('hp', 'max_hp', 'gold'):
            if len(act[key]) != len(act['rooms']):
                problems.append(f'act {act["act"]} {key} length {len(act[key])} != {len(act["rooms"])}')
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--runs-dir', required=True, type=Path,
                        help='directory of .run files, e.g. "<game>/runs/2_IRONCLAD"')
    parser.add_argument('--out', required=True, type=Path, help='bundle to write (one run per line)')
    parser.add_argument('--character', help='keep only this character_chosen, e.g. IRONCLAD')
    args = parser.parse_args(argv)

    if not args.runs_dir.is_dir():
        print(f'error: {args.runs_dir} is not a directory', file=sys.stderr)
        return 2
    paths = sorted(args.runs_dir.glob('*.run'))
    if not paths:
        print(f'error: no .run files under {args.runs_dir}', file=sys.stderr)
        return 2

    records, rejected, skipped = [], [], 0
    for path in paths:
        try:
            raw = read_run(path)
            record = build_record(raw, path.name)
        except ValueError as error:
            rejected.append(str(error))
            continue
        problems = record_problems(record, raw.get('path_per_floor') or [])
        if problems:
            rejected.append(f'{path.name}: ' + '; '.join(problems))
            continue
        if args.character and record['character'] != args.character:
            skipped += 1
            continue
        records.append(record)

    records.sort(key=lambda record: (record['date'] or '', record['seed']))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open('w', encoding='utf-8', newline='\n') as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + '\n')

    winners = sum(1 for record in records if record['victory'])
    print(f'wrote {len(records)} runs to {args.out} (won {winners}, lost {len(records) - winners})')
    if skipped:
        print(f'skipped {skipped} runs of another character')
    for error in rejected:
        print(f'rejected: {error}')
    return 0 if records else 1


if __name__ == '__main__':
    sys.exit(main())
