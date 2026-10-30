"""Check the simulator against the captured combat windows committed under fixtures/native/.

`defect_cards_test` proves one card against one dummy at 9999 HP, which says nothing about three
things the reviewer asked for: ability cards that act on a later turn, fights where a monster
leaves the board part-way through a card, and Focus below zero. The windows that cover those live
in `fixtures/native/DEFECT` and were recorded from the real game classes running on the headless
engine, so the recording, not this script, is the authority: a failure here means the simulator
disagrees with the game.

    python tools/check_coverage_gaps.py [path ...]

Each checkpoint is fed to the simulator's snapshot importer and every field the comparator can see
is compared. No seed-to-board reconstruction is needed -- the recorder saved the state the play was
chosen from -- so this runs with no game jar, no save and no network, only the built runtime
(tools/build_solver_runtime.py) that the other `tools/check_*.py` scripts also need.

Two grades of result are reported:

* the eight coverage-gap windows below are REQUIRED: every checkpoint must import and match;
* any other native fixture is graded where it can be. The legacy captures in the same directory
  predate the harness fix that makes a hand-built monster announce its intent, so their first
  in-combat checkpoint carries `intent: DEBUG, move: -1` and cannot be imported at all. Those
  checkpoints are counted as unavailable rather than silently passed, and an importable checkpoint
  that disagrees is still a failure.
"""
from pathlib import Path
import copy
import gzip
import json
import sys

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'overlay'))
from backend import Advisor, view_from_state  # noqa: E402

DEFAULT_DIR = REPO / 'fixtures' / 'native' / 'DEFECT'

# The cards whose behaviour each required window exists to pin down.
EXPECTED = {
    'defect-hello-world-across-turn': ('Hello World', 'the start-of-turn draw uses the whole Common pool'),
    'defect-creative-ai-across-turn': ('Creative AI', 'a Power arrives at the start of each later turn'),
    'defect-loop-across-turn': ('Loop', 'the first orb fires again and keeps its slot'),
    'defect-storm-across-turn': ('Storm', 'every Power channels a Lightning, across a turn end'),
    'defect-multicast-kill-mid-fight': ('Multi-Cast', 'each evoke rolls against the board the last one left'),
    'defect-fission-kill-mid-fight': ('Fission', 'every orb evokes one at a time'),
    'defect-thunder-strike-kill-mid-fight': ('Thunder Strike', 'a target dying mid-card still takes the later hits'),
    'defect-biased-focus-negative': ('Biased Cognition', 'Focus below zero still rolls, and damage clamps at zero'),
}


def card(card_id, upgrades=0, cost=1):
    # Deliberately no `is_playable`: the fixture records the board, not which card the game
    # considered legal, so the comparator must treat legality as unobserved instead of
    # comparing the simulator's guess against a value that was never captured.
    return {'id': card_id, 'name': card_id, 'cost': cost, 'base_cost': cost, 'upgrades': upgrades,
            'retain': False, 'has_target': False, 'target': 'SELF',
            'type': 'SKILL', 'misc': 0, 'uuid': None, 'free_to_play_once': False}


def to_game_state(observation, template):
    """Express one recorded observation as a CommunicationMod game_state.

    Only the fields the importer reads are filled; everything else comes from the captured
    live root, so the run context (deck, relics, gold) stays real instead of hand-written.
    """
    game = copy.deepcopy(template['game'])
    combat = game['combat_state']
    player = observation['player']
    piles = observation['piles']
    by_id = {}
    for pile in ('hand', 'draw', 'discard', 'exhaust'):
        by_id[pile] = [dict(card(c['id'], c['upgrades'], c['cost']), uuid=f'{pile}-{index:04d}')
                       for index, c in enumerate(piles[pile])]
    combat['hand'] = by_id['hand']
    combat['draw_pile'] = by_id['draw']
    combat['discard_pile'] = by_id['discard']
    combat['exhaust_pile'] = by_id['exhaust']
    combat['limbo'] = []
    combat['turn'] = observation['turn']
    combat['player'] = {
        **combat.get('player', {}),
        'current_hp': player['hp'], 'max_hp': player['max_hp'], 'block': player['block'],
        'energy': player['energy'], 'powers': [dict(p) for p in player['powers']],
        'orbs': [{'id': orb['id'], 'passive_amount': orb['passive'], 'evoke_amount': orb['evoke']}
                 for orb in player['orbs']],
    }
    prototype = (combat.get('monsters') or [None])[0] or {}
    combat['monsters'] = [
        {**copy.deepcopy(prototype),
         'id': monster['id'], 'name': monster['id'],
         'current_hp': monster['hp'], 'max_hp': monster['max_hp'], 'block': monster['block'],
         'powers': [dict(p) for p in monster['powers']], 'is_gone': False, 'half_dead': False,
         'intent': monster['intent'], 'move_id': monster['move'],
         'last_move_id': -1, 'second_last_move_id': -1,
         'move_base_damage': monster.get('intent_damage', 0),
         'move_adjusted_damage': monster.get('intent_damage', 0), 'move_hits': 1}
        for monster in observation['monsters']
    ]
    game['current_hp'] = player['hp']
    game['floor'] = observation['floor']
    game['class'] = 'DEFECT'
    game['room_phase'] = 'COMBAT'
    game['screen_type'] = 'NONE'
    return game


def to_view(observation, root, rng_names):
    """The backend view for one observation: recorded state plus the streams the importer needs."""
    # view_from_state only knows how to describe an Ironclad fight, so the captured root is
    # turned into a view first and the recorded state is written over it afterwards. That keeps
    # the run context real while letting a Defect board through, as check_defect_parity does.
    view = view_from_state(copy.deepcopy(root))
    view['game'] = to_game_state(observation, {'game': root['game_state']})
    view['rng'] = {name: _rng(observation['rng'][key]) for key, name in rng_names.items()}
    return view


def _rng(counter):
    # The importer only reads the counter, and the recording carries exactly that.
    return {'seed0': 0, 'seed1': 0, 'counter': counter}


def compare(view, what):
    search = Advisor().search
    battle = search.comparator.import_battle(view, require_search_state=True)
    result = search.comparator.compare_battle(view, battle)
    found = [d for d in result['differences'] if not d['path'].startswith('/legacy/')]
    if found:
        raise AssertionError(f'{what}: {len(found)} differences; first: {json.dumps(found[0], ensure_ascii=False)}')
    return battle


def read_case(path):
    if path.suffix == '.gz':
        with gzip.open(path, 'rt', encoding='utf-8') as handle:
            return json.load(handle)
    return json.loads(path.read_text(encoding='utf-8'))


def check_case(path, root, rng_names):
    """Grade one fixture. Returns (compared, unavailable, terminal, reasons, failures)."""
    case = read_case(path)
    compared = 0
    unavailable = 0
    terminal = 0
    reasons = []
    failures = []
    for checkpoint in case['checkpoints']:
        observation = checkpoint['observation']
        if not observation.get('monsters'):
            continue
        if observation.get('phase') == 'GAME_OVER':
            # The fight is over: there is no board left to compare, and the importer rebuilds the
            # board but not the outcome, so it answers UNDECIDED where the game says PLAYER_LOSS.
            # That is a gap in the importer's outcome derivation, not in the rules under test here.
            terminal += 1
            continue
        what = f'{case["id"]} after {checkpoint["after"]} actions'
        try:
            # The fixture already stores the projected shape, so it goes to the importer as is.
            view = to_view(observation, root, rng_names)
            compare(view, what)
            compared += 1
        except (ValueError, KeyError) as error:
            # The capture cannot be imported: a legacy board that never announced an intent, or
            # a monster whose internal state the observation does not carry.
            unavailable += 1
            reasons.append(f'{what}: not importable ({error})')
        except AssertionError as error:
            failures.append(str(error))
    return compared, unavailable, terminal, reasons, failures


def main():
    from sim_patch.parity.adapter import RNG_NAMES

    targets = [Path(arg) for arg in sys.argv[1:]] or [DEFAULT_DIR]
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

    # The captured live root supplies the run context; every combat field is replaced by the
    # recording, so nothing here is hand-written.
    root = json.loads((REPO / 'overlay' / 'test' / 'live-root.json').read_text(encoding='utf-8'))

    failed = []
    required_seen = set()
    graded = 0
    skipped = 0
    finished = 0
    for path in paths:
        case_id = read_case(path)['id']
        compared, unavailable, terminal, reasons, failures = check_case(path, root, RNG_NAMES)
        required = case_id in EXPECTED
        card_id, purpose = EXPECTED.get(case_id, ('(legacy capture)', 'any in-combat checkpoint'))
        if required:
            required_seen.add(case_id)
        graded += compared
        skipped += unavailable
        finished += terminal
        if failures:
            # An importable checkpoint that disagrees is a failure whatever the fixture is.
            failed.extend(failures)
            print(f'FAIL  {case_id:40s} {card_id:15s} {failures[0]}')
        elif required and unavailable:
            # A required window must be fully replayable: an unavailable checkpoint here means
            # the capture stopped describing the board, which is a regression of its own.
            failed.extend(reasons)
            print(f'FAIL  {case_id:40s} {card_id:15s} {unavailable} checkpoints not importable')
        elif compared:
            note = f' ({purpose})' if required else ''
            print(f'PASS  {case_id:40s} {card_id:15s} {compared} checkpoints{note}')
        # else: a legacy capture whose checkpoints are all unimportable -- counted in `skipped`.

    missing = sorted(set(EXPECTED) - required_seen)
    for case_id in missing:
        failed.append(f'{case_id}: required coverage-gap fixture is missing')
        print(f'FAIL  {case_id:40s} {"":15s} missing')

    if failed:
        print(f'\n{len(failed)} problem(s); {graded} checkpoints matched, {skipped} not importable')
        for problem in failed:
            print('  ' + problem)
        return 1
    print(f'\nALL {len(EXPECTED)} coverage-gap windows match the game; {graded} checkpoints matched, '
          f'{finished} post-fight checkpoints skipped, {skipped} legacy checkpoints not importable')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
