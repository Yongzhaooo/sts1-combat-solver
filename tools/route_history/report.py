"""Summarise a route-history bundle: what the reference runs did, act by act.

    python tools/route_history/report.py
    python tools/route_history/report.py --data tools/route_history/data/ironclad-a20.jsonl --act 1
    python tools/route_history/report.py --weights overlay/resources/route-preferences.json

Nothing here fits weights. The bundle records the rooms a run walked through, not the map it was
offered (the map is rebuildable from the seed, but this script does not do that), so the sibling
rooms that would make a comparison possible are missing. What the numbers do
give is the house style of the reference runs: how many elites, campfires and shops an act actually
held, and with what HP and gold the next act started. Read `docs/route-tuning.md` before changing a
weight: it says which terms this bundle can and cannot speak to.
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA = ROOT / 'tools/route_history/data/ironclad-a20.jsonl'
DEFAULT_WEIGHTS = ROOT / 'overlay/resources/route-preferences.json'
SCHEMA = 'sts1-route-history/1'
ROOMS = ('M', 'E', 'R', '?', '$', 'T', 'B')
NAMES = {'M': 'monster', 'E': 'elite', 'R': 'campfire', '?': 'unknown', '$': 'shop', 'T': 'treasure',
         'B': 'boss'}


def load_bundle(path):
    if not path.is_file():
        raise SystemExit(f'error: {path} is not a file; run export_runs.py first')
    records = []
    with path.open(encoding='utf-8') as handle:
        for number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise SystemExit(f'error: {path}:{number}: {error}') from None
            if record.get('schema') != SCHEMA:
                raise SystemExit(f'error: {path}:{number}: schema {record.get("schema")!r} != {SCHEMA!r}')
            records.append(record)
    if not records:
        raise SystemExit(f'error: {path} holds no runs')
    return dedupe(records)


def dedupe(records):
    """Drop a run recorded twice (same seed and same rooms in every act, e.g. a reloaded save)."""
    seen, kept = set(), []
    for record in records:
        key = (record['seed'], tuple(act['rooms'] for act in record['acts']))
        if key not in seen:
            seen.add(key)
            kept.append(record)
    if len(kept) < len(records):
        print(f'note: dropped {len(records) - len(kept)} duplicate run(s) (same seed and rooms)', file=sys.stderr)
    return kept


def act_entries(records, act):
    """One act of every run, skipping acts the export flagged short_act (e.g. a secret portal)."""
    return [entry for record in records for entry in record['acts'] if entry['act'] == act
            and not any(i['type'] == 'short_act' and i.get('act') == act for i in record.get('issues', []))]


def act_floor_ranges(record):
    """Floor numbers of each act, rebuilt from the room counts and the separators between acts."""
    ranges, floor = [], 1
    for index, act in enumerate(record['acts']):
        if index:
            floor += 1  # the separator row between two acts
        count = len(act['rooms'])
        ranges.append((act['act'], range(floor, floor + count)))
        floor += count
    return ranges


def mean(values):
    values = [value for value in values if value is not None]
    return sum(values) / len(values) if values else float('nan')


def percentile(values, fraction):
    values = sorted(value for value in values if value is not None)
    if not values:
        return float('nan')
    index = min(len(values) - 1, max(0, int(round(fraction * (len(values) - 1)))))
    return values[index]


def histogram(values, bins):
    """Counts in `bins`, where the last bin is open-ended ("bins[-1]+")."""
    labels = []
    for index in range(len(bins) - 1):
        low, high = bins[index], bins[index + 1] - 1
        labels.append((low, high, f'{low}-{high}' if high > low else f'{low}'))
    counts = {label: 0 for _, _, label in labels}
    counts[f'{bins[-1]}+'] = 0
    for value in values:
        for low, high, label in labels:
            if low <= value <= high:
                counts[label] += 1
                break
        else:
            counts[f'{bins[-1]}+'] += 1
    return counts


def table(header, rows, indent=0):
    widths = [max(len(str(row[column])) for row in [header] + rows) for column in range(len(header))]
    pad = ' ' * indent
    print(pad + '  '.join(str(cell).ljust(widths[i]) for i, cell in enumerate(header)))
    print(pad + '  '.join('-' * widths[i] for i in range(len(header))))
    for row in rows:
        print(pad + '  '.join(str(cell).ljust(widths[i]) for i, cell in enumerate(row)))


def print_coverage(records, path):
    acts = [len(record['acts']) for record in records]
    room_totals = [len(act['rooms']) for record in records for act in record['acts']]
    lengths = {}
    for record in records:
        key = tuple(len(act['rooms']) for act in record['acts'])
        lengths[key] = lengths.get(key, 0) + 1
    dates = sorted(record['date'] for record in records if record.get('date'))
    print(f'bundle: {path}')
    print(f'schema {SCHEMA}; characters {sorted({r["character"] for r in records})}; '
          f'ascension {sorted({r["ascension"] for r in records})}')
    print(f'runs {len(records)}: {sum(1 for r in records if r["victory"])} won, '
          f'{sum(1 for r in records if not r["victory"])} lost; floors '
          f'{min(r["floor_reached"] for r in records)}-{max(r["floor_reached"] for r in records)}'
          + (f'; {dates[0]} to {dates[-1]}' if dates else ''))
    print(f'acts per run: ' + ', '.join(f'{count} acts x{acts.count(count)}' for count in sorted(set(acts))))
    for key, count in sorted(lengths.items(), key=lambda item: -item[1]):
        print(f'  room counts per act {key}: {count} runs')


def print_room_mix(records, acts_wanted):
    print()
    print('## rooms per act (mean per run; share of the act excluding its boss rows; short_act acts left out)')
    rows = []
    for act in acts_wanted:
        entries = act_entries(records, act)
        if not entries:
            continue
        rooms = [entry['rooms'] for entry in entries]
        row = [act, len(entries), f'{mean(len(r) for r in rooms):.1f}']
        for symbol in ROOMS:
            row.append(f'{mean(r.count(symbol) for r in rooms):.2f}')
        nonboss = sum(r.count(symbol) for r in rooms for symbol in ROOMS if symbol != 'B')
        share = []
        for symbol in ROOMS:
            if symbol == 'B':
                share.append('')
                continue
            total = sum(r.count(symbol) for r in rooms)
            share.append(f'{100 * total / nonboss:.0f}%' if nonboss else '')
        rows.append(row + [' '.join(share)])
    header = ['act', 'runs', 'rooms'] + list(ROOMS) + ['share M E R ? $ T']
    table(header, rows)
    print('campfire rows do not all heal: see the campfire choices below for what they were spent on.')


def print_spread(records, acts_wanted):
    print()
    print('## how a single run distributes its rooms')
    for act in acts_wanted:
        entries = act_entries(records, act)
        if not entries:
            continue
        cells = []
        for symbol, bins in (('E', (0, 1, 2, 3, 4)), ('R', (0, 1, 2, 3, 4)), ('$', (0, 1, 2, 3)),
                             ('?', (0, 1, 2, 3, 4, 5))):
            counts = [entry['rooms'].count(symbol) for entry in entries]
            spread = histogram(counts, bins)
            cells.append(f'{symbol} ' + ' '.join(f'{key}:{value}' for key, value in spread.items()))
        print(f'act {act}: ' + '   '.join(cells))


def print_entry_state(records):
    print()
    print('## state entering each act (the row after the previous act\'s boss)')
    rows = []
    for act in (2, 3, 4):
        states = [record['acts'][act - 2]['entry'] for record in records
                  if len(record['acts']) >= act and record['acts'][act - 2].get('entry')]
        hp = [state['hp'] for state in states]
        max_hp = [state['max_hp'] for state in states]
        gold = [state['gold'] for state in states]
        pairs = [(h, m) for h, m in zip(hp, max_hp) if h is not None and m]
        share = [100 * h / m for h, m in pairs]
        if not pairs:
            continue
        rows.append([act, len(pairs), f'{mean(share):.0f}%', f'{percentile(share, 0.25):.0f}%',
                     f'{percentile(share, 0.50):.0f}%', f'{percentile(share, 0.75):.0f}%',
                     f'{mean(gold):.0f}'])
    table(['act', 'runs', 'hp', 'p25', 'p50', 'p75', 'gold'], rows)


def print_campfires(records, acts_wanted):
    print()
    print('## campfire choices (from campfire_choices; floor mapped back to its act)')
    rows = []
    for act in acts_wanted:
        keys = {}
        for record in records:
            floors = dict((floor, act_no) for act_no, span in act_floor_ranges(record) for floor in span)
            for choice in record['campfires']:
                if floors.get(choice['floor']) == act:
                    keys[choice['key']] = keys.get(choice['key'], 0) + 1
        if keys:
            rows.append([act, sum(keys.values())] + [f'{key}:{value}' for key, value in sorted(keys.items())])
    header = ['act', 'fires'] + ['choices']
    table(header, [[r[0], r[1], '  '.join(r[2:])] for r in rows])


def print_weights(path, records, acts_wanted):
    weights = json.loads(path.read_text(encoding='utf-8'))
    print()
    print(f'## weights in {path.name} ({weights.get("version", "?")}) next to what these runs walked')
    print('   the observed column is what the reference strategy did, not an optimum; the bundle keeps')
    print('   no map, so the rooms it was offered are unknown and nothing here can be fitted.')
    observed = {'elite': 'E', 'fire': 'R', 'event': '?', 'monster': 'M', 'shop': '$'}
    for act in acts_wanted:
        row = weights.get('weights', {}).get(f'{act}:balanced')
        if row is None:
            continue
        entries = act_entries(records, act)
        if not entries:
            continue
        print(f'\nact {act} ("{act}:balanced")')
        lines = []
        for term, value in row.items():
            symbol = observed.get(term)
            seen = f'{mean(entry["rooms"].count(symbol) for entry in entries):.2f} /run' if symbol else '-'
            lines.append([term, f'{value:g}', seen])
        table(['term', 'weight', 'observed'], lines, indent=2)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--data', type=Path, default=DEFAULT_DATA, help=f'bundle to read (default {DEFAULT_DATA})')
    parser.add_argument('--weights', type=Path, default=None,
                        help='compare these route preferences with the runs (default: none)')
    parser.add_argument('--act', type=int, action='append', dest='acts',
                        help='restrict to this act (repeatable; default: every act in the bundle)')
    args = parser.parse_args(argv)

    records = load_bundle(args.data)
    acts_wanted = args.acts or list(range(1, max(len(record['acts']) for record in records) + 1))

    print_coverage(records, args.data)
    print_room_mix(records, acts_wanted)
    print_spread(records, acts_wanted)
    print_entry_state(records)
    print_campfires(records, acts_wanted)
    if args.weights:
        if not args.weights.is_file():
            raise SystemExit(f'error: {args.weights} is not a file')
        print_weights(args.weights, records, acts_wanted)
    return 0


if __name__ == '__main__':
    sys.exit(main())
