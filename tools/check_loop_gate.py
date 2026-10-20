"""Check the native loop-family table (overlay/loop_gate.h).

Always runs built-in cases. With --recognizer and --fights it also compares every recorded fight against the
reference Python table (sts1-combat-pilot/tools/loop_recognizer.py) and requires identical matched families.
"""
import argparse
import gzip
import importlib.util
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--runtime-root', type=Path, default=root)
parser.add_argument('--recognizer', type=Path)
parser.add_argument('--fights', type=Path)
args = parser.parse_args()
sys.path.insert(0, str(args.runtime_root.resolve() / 'overlay'))
from backend import Advisor

advisor = Advisor()
sts, bridge, native = advisor.search.sts, advisor.search.comparator.bridge, advisor.native_search
card_ids, relic_ids = {}, {}


def card(java_id):
    if java_id not in card_ids:
        try:
            card_ids[java_id] = sts.CardId(sts.card_id_from_name(bridge.CARD_ALIASES.get(java_id, bridge.enum_key(java_id))))
        except Exception:
            card_ids[java_id] = sts.CardId.INVALID  # not in any family
    return card_ids[java_id]


def relic(java_id):
    if java_id not in relic_ids:
        try:
            relic_ids[java_id] = sts.RelicId(sts.relic_id_from_name(java_id))
        except Exception:
            relic_ids[java_id] = None
    return relic_ids[java_id]


def families(cards, relics=()):
    return set(native.loop_families([(card(c), bool(u)) for c, u in cards], [r for r in map(relic, relics) if r is not None]))


base = [('Strike_R', False)] * 5 + [('Defend_R', False)] * 4 + [('Bash', False)]
assert not families(base)
assert not families(base + [('Pommel Strike', False)]), 'one cheap draw card is not a loop'
assert 'sundial-pommel-shrug' in families(base + [('Pommel Strike', True), ('Shrug It Off', False)], ['Sundial'])
assert 'sundial-pommel-shrug' not in families(base + [('Pommel Strike', False), ('Shrug It Off', False)], ['Sundial'])
assert 'double-dropkick' in families(base + [('Dropkick', False)] * 2), 'Bash supplies Vulnerable'
assert 'double-dropkick' not in families([('Dropkick', False)] * 2)
assert 'rage-double-pommel-body-slam' in families([('Pommel Strike', True), ('Pommel Strike', False), ('Rage', False), ('Body Slam', False)])
assert 'sundial-pommel-pommel' not in families([('Pommel Strike', True)], ['Sundial']), 'one card fills one slot'
assert 'necronomicurse' in families([], ['Necronomicon', 'Blue Candle', 'TungstenRod'])
heart = [('Body Slam', True), ('Shrug It Off', True), ('Deep Breath', True), ('Bloodletting', True), ('Pommel Strike', True)]
assert {'body-slam-draw', 'deep-breath-shrug-body-slam', 'bleed-pommel-shrug'} <= families(heart)
print('PASS: loop-family table built-in cases')

if args.recognizer and args.fights:
    spec = importlib.util.spec_from_file_location('loop_recognizer', args.recognizer)
    reference = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reference)
    with gzip.open(args.fights, 'rt', encoding='utf-8') as f:
        f.readline()
        rows = [json.loads(line) for line in f]
    native_ids = [f['id'] for f in reference.FAMILIES]
    differ = []
    for r in rows:
        want = {m['id'] for m in reference.recognize(r['cards'], r['relics'])['matched']}
        got = families(r['cards'], r['relics'])
        if want != got:
            differ.append(dict(floor=r['floor'], want=sorted(want), got=sorted(got)))
    print(json.dumps(dict(fights=len(rows), gated=sum(bool(families(r['cards'], r['relics'])) for r in rows),
                          differ=len(differ), examples=differ[:5])))
    assert not differ, 'native loop table differs from the reference recognizer'
    print('PASS: native loop table matches the reference recognizer on every recorded fight')
