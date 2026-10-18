"""Replay combat-exam witnesses; --search separately checks one 8000-simulation warm stage."""
import argparse
import json
from pathlib import Path
import sys
import time


def frame(battle):
    return [battle.player.cur_hp, battle.player.energy, battle.turn + 1]


def resources(battle, native, live):
    projected = dict(live.search_state(battle))
    return dict(outcome=int(battle.outcome), raw_hp=battle.player.cur_hp,
                post_hp=projected['victory_hp'], max_hp=projected['victory_max_hp'],
                gold=battle.player.gold, potions=list(battle.potions),
                revival=list(native.revival_inventory(battle)))


def execute(battle, bits, sts, native):
    action = sts.SearchAction.from_bits(int(bits) & 0xffffffff)
    assert action.is_valid(battle), 'illegal exam action: ' + action.desc(battle)
    native.execute(battle, int(bits) & 0xffffffff)


def replay_witness(root, witness, sts, native, live):
    battle = root.clone()
    for index, step in enumerate(witness['steps']):
        assert frame(battle) == step['before'], ('before', index, frame(battle), step['before'])
        execute(battle, step['bits'], sts, native)
        actual = frame(battle) + [int(battle.outcome)]
        assert actual == step['after'], ('after', index, actual, step['after'])
    actual = resources(battle, native, live)
    assert actual == witness['expected'], ('terminal resources', actual, witness['expected'])
    return actual


def check_quality(actual, target, initial):
    assert actual['outcome'] == 1, 'warm-stage exam did not win'
    for field in ('raw_hp', 'post_hp', 'max_hp'):
        assert actual[field] >= target['min_' + field], (field, actual[field], target['min_' + field])
    for field in ('gold', 'potions', 'revival'):
        assert actual[field] == initial[field], ('resource changed', field, actual[field], initial[field])


def search_exam(root, target, sts, native, live):
    # This path receives no witness actions or frames.
    native.set_max_hp_gain_weight(target['max_hp_gain_weight'])
    native.set_incense_targets(target['incense_targets'])
    initial = resources(root, native, live)
    started = time.monotonic()
    plan = dict(native.plan(root, 8000, [], target['recovery_credit'], 0, False, False, False))
    elapsed = time.monotonic() - started
    battle = root.clone()
    for bits in plan['actions']:
        execute(battle, bits, sts, native)
    actual = resources(battle, native, live)
    assert actual['raw_hp'] == plan['hp'] and actual['outcome'] == plan['outcome']
    assert actual['post_hp'] == plan['post_hp'] and actual['max_hp'] == plan['max_hp']
    check_quality(actual, target, initial)
    return dict(resources=actual, simulation_budget=8000, elapsed_seconds=elapsed,
                native_measure=plan.get('prefix_measure'))


def main():
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-root', type=Path, default=repo)
    parser.add_argument('--fixtures', type=Path, default=repo / 'fixtures/combat-exams')
    parser.add_argument('--search', action='store_true', help='check one warm-stage search, not the witness')
    args = parser.parse_args()
    sys.path.insert(0, str(args.runtime_root.resolve() / 'overlay'))
    from backend import Advisor
    advisor = Advisor()
    sts, live, native = advisor.search.sts, advisor.search.native, advisor.native_search
    paths = [args.fixtures] if args.fixtures.is_file() else sorted(args.fixtures.glob('*.json'))
    assert paths, 'no combat-exam fixtures found'
    for path in paths:
        case = json.loads(path.read_text(encoding='utf-8'))
        assert case['format'] == 'sts1-combat-exam-v1', 'unsupported combat-exam format'
        initial = case['initial']
        root = sts.BattleContext.from_snapshot(initial['snapshot'], int(initial['snapshot']['seed']))
        root.potion_bark_flip = initial['potion_bark_flip']
        live.import_search_state(root, initial['search_state'])
        native.resume()
        if args.search:
            result = search_exam(root, case['quality'], sts, native, live)
            mode = 'warm-stage-search-8000'
        else:
            result = replay_witness(root, case['witness'], sts, native, live)
            mode = 'witness-replay'
        print(json.dumps(dict(status='PASS', exam=case['id'], mode=mode, result=result), ensure_ascii=False))


if __name__ == '__main__':
    main()
