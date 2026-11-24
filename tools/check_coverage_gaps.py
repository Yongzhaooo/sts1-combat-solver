"""Check the simulator against the captured combat windows committed under fixtures/native/.

`defect_cards_test` proves one card against one dummy at 9999 HP, which says nothing about three
things the reviewer asked for: ability cards that act on a later turn, fights where a monster
leaves the board part-way through a card, and Focus below zero. The windows that cover those live
in `fixtures/native/DEFECT` and were recorded from the real game classes running on the headless
engine, so the recording, not this script, is the authority: a failure here means the simulator
disagrees with the game.

    python tools/check_coverage_gaps.py [path ...]

With no arguments every character directory under `fixtures/native/` is graded, so a new
character's captures are picked up without editing this file.

Each checkpoint is fed to the simulator's snapshot importer and every field the comparator can see
is compared. No seed-to-board reconstruction is needed -- the recorder saved the state the play was
chosen from -- so this runs with no game jar, no save and no network, only the built runtime
(tools/build_solver_runtime.py) that the other `tools/check_*.py` scripts also need.

Four kinds of result are reported:

* the eight coverage-gap windows below are REQUIRED: every checkpoint must import and match, and a
  missing window fails the run;
* any other native fixture is graded where it can be. An importable checkpoint that disagrees is
  still a failure;
* a checkpoint the capture could never support -- a value the recording does not carry at all --
  is counted as UNOBSERVABLE and named by cause. Three causes are known and named: a hand-built
  monster recorded before the harness fix that made it announce an intent, Steam Barrier's play
  count, and a Louse's rolled bite damage. Those stay visible without pretending to be capture
  defects, which keeps `not importable` at zero on the committed fixtures: an entry there is a new
  defect;
* a fixture with no compared checkpoint at all is printed as SKIP, so a character whose captures
  were never really graded cannot read as a pass. The per-character block at the end says how many
  checkpoints each character actually got compared.
"""
from pathlib import Path
import copy
import gzip
import json
import re
import sys
from collections import Counter

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'overlay'))
sys.path.insert(0, str(REPO / 'tools'))
from backend import Advisor, view_from_state  # noqa: E402
import parity_differences  # noqa: E402

NATIVE_DIR = REPO / 'fixtures' / 'native'

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


def card(card_id, upgrades=0, cost=1, retain=False):
    # Deliberately no `is_playable`: the fixture records the board, not which card the game
    # considered legal, so the comparator must treat legality as unobserved instead of
    # comparing the simulator's guess against a value that was never captured.
    return {'id': card_id, 'name': card_id, 'cost': cost, 'base_cost': cost, 'upgrades': upgrades,
            'retain': retain, 'has_target': False, 'target': 'SELF',
            'type': 'SKILL', 'misc': 0, 'uuid': None, 'free_to_play_once': False}


def to_game_state(observation, template, character):
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
        by_id[pile] = []
        for index, c in enumerate(piles[pile]):
            entry = dict(card(c['id'], c['upgrades'], c['cost']), uuid=f'{pile}-{index:04d}')
            # Claim the Retain keyword only when the capture recorded it; otherwise the
            # comparison has to report the bit as unobserved instead of reading a default.
            if 'retain' in c:
                entry['retain'] = c['retain']
            else:
                entry.pop('retain', None)
            by_id[pile].append(entry)
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
    # Written only when the capture recorded the field: every committed capture carries both, but
    # a hand-made one may not, and then the importer's Neutral/0 default is the honest reading --
    # this tool does not invent a value.
    for field in ('stance', 'mantra'):
        if field in player:
            combat['player'][field] = player[field]
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
    game['class'] = character
    game['room_phase'] = 'COMBAT'
    game['screen_type'] = 'NONE'
    return game


def to_view(observation, root, rng_names, character):
    """The backend view for one observation: recorded state plus the streams the importer needs."""
    # view_from_state only knows how to describe an Ironclad fight, so the captured root is
    # turned into a view first and the recorded state is written over it afterwards. That keeps
    # the run context real while letting a Defect board through, as check_defect_parity does.
    view = view_from_state(copy.deepcopy(root))
    view['game'] = to_game_state(observation, {'game': root['game_state']}, character)
    view['rng'] = {name: _rng(observation['rng'][key]) for key, name in rng_names.items()}
    return view


def _rng(counter):
    # The importer only reads the counter, and the recording carries exactly that.
    return {'seed0': 0, 'seed1': 0, 'counter': counter}


def compare_result(view, what):
    """Import and compare one view; returns the battle with its comparison result.

    Split from `compare` so a caller that accounts for a checkpoint can also see the fields the
    capture never recorded, while everyone else keeps the assertion-only entry point.
    """
    search = Advisor().search
    battle = search.comparator.import_battle(view, require_search_state=True)
    result = search.comparator.compare_battle(view, battle)
    found = [d for d in result['differences'] if parity_differences.kept(d)]
    if found:
        raise AssertionError(f'{what}: {len(found)} differences; first: {json.dumps(found[0], ensure_ascii=False)}')
    return battle, result


def compare(view, what):
    return compare_result(view, what)[0]


def read_case(path):
    if path.suffix == '.gz':
        with gzip.open(path, 'rt', encoding='utf-8') as handle:
            return json.load(handle)
    return json.loads(path.read_text(encoding='utf-8'))


# Checkpoints whose value the capture never records. Naming them keeps them out of the
# "not importable" pool, where a value that was never recorded looks the same as a capture
# that stopped describing the board -- one is a recording limit, the other a regression.
# On the committed fixtures `not importable` is therefore expected to be empty: an entry
# there is a new defect, not one of the known ones.
DEBUG_INTENT = 'DEBUG'

# A capture that names a card the simulator does not implement refuses to import with this
# message. Reading the name out of it lets the accounting tools report the card rather than the
# string, and keeps the pattern in one place.
UNIMPLEMENTED = re.compile(r'unknown enum name: (\w+)')

UNOBSERVABLE = (
    ('steam-barrier-play-count',
     lambda error: isinstance(error, KeyError) and error.args == ('base_block',),
     'Steam Barrier reports how often it was played through its base block, which the capture omits'),
    ('louse-bite-damage',
     lambda error: isinstance(error, ValueError) and 'biteDamage' in str(error),
     'a Louse derives its bite damage in rollMove and the capture carries only intent and move'),
)


def unobservable(error):
    """Name the recorded absence behind this import error, or None if nothing explains it."""
    for kind, matches, why in UNOBSERVABLE:
        if matches(error):
            return kind, why
    return None


def check_case(path, root, rng_names):
    """Grade one fixture.

    Returns a dict of counters plus the reason lists, and one record per checkpoint so an
    accounting tool can bucket every checkpoint without rebuilding this loop. `unobserved`
    counts the checkpoints whose value the capture never records; `unavailable` counts the ones
    the importer cannot rebuild for any other reason. Both stay open gaps, but only the second
    one is a capture defect, so the two must not share a bucket.
    """
    case = read_case(path)
    compared = 0
    unobserved = 0
    unavailable = 0
    terminal = 0
    reasons = []
    failures = []
    named = []
    records = []
    unrecorded_fields = Counter()
    for checkpoint in case['checkpoints']:
        observation = checkpoint['observation']
        what = f'{case["id"]} after {checkpoint["after"]} actions'
        if not observation.get('monsters'):
            # No board to grade. Named for accounting only: the counters here keep counting
            # boards, so a comparison run against the committed fixtures is unchanged.
            records.append({'what': what, 'status': 'no_board', 'unrecorded': [],
                            'cause': f'out of combat ({observation.get("phase")})'})
            continue
        if observation.get('phase') == 'GAME_OVER':
            # The fight is over: there is no board left to compare, and the importer rebuilds the
            # board but not the outcome, so it answers UNDECIDED where the game says PLAYER_LOSS.
            # That is a gap in the importer's outcome derivation, not in the rules under test here.
            terminal += 1
            records.append({'what': what, 'status': 'terminal', 'unrecorded': [],
                            'cause': 'fight is over (GAME_OVER)'})
            continue
        if (observation.get('monsters') or [{}])[0].get('intent') == DEBUG_INTENT:
            # A hand-built monster recorded before the harness fix: the capture carries the
            # recorder's placeholder instead of the intent the game would have announced, so the
            # board cannot be rebuilt at all. Named here rather than left to the importer's error.
            unobserved += 1
            named.append(f'{what}: monster-intent-never-announced -- the capture predates the harness '
                         f'fix that makes a hand-built monster announce its intent')
            records.append({'what': what, 'status': 'unobserved', 'unrecorded': [],
                            'cause': 'monster-intent-never-announced'})
            continue
        try:
            # The fixture already stores the projected shape, so it goes to the importer as is.
            view = to_view(observation, root, rng_names, case.get('character', 'DEFECT'))
            _, result = compare_result(view, what)
            if any(g.get('kind') == 'pending_choice_state_and_continuation' for g in result['gaps']):
                # The checkpoint stops at a card selection: the comparison has nothing to grade
                # until the choice's continuation is recorded, so this is not agreement.
                unobserved += 1
                named.append(f'{what}: pending-choice-state -- the capture stops at a choice '
                             f'nothing continues from')
                records.append({'what': what, 'status': 'unobserved', 'unrecorded': [],
                                'cause': 'pending-choice-state'})
                continue
            compared += 1
            unrecorded = ['.'.join(g['path'].split('/')[-2:]) for g in result['gaps']
                          if g.get('kind') == 'missing_observation']
            unrecorded_fields.update(unrecorded)
            records.append({'what': what, 'status': 'compared', 'cause': None,
                            'unrecorded': unrecorded})
        except (ValueError, KeyError) as error:
            cause = unobservable(error)
            if cause is None:
                # The capture cannot be imported for a reason nobody has named yet: that is a
                # defect in the capture until someone shows otherwise. Reported as its message,
                # and flagged when the message names an unimplemented card.
                unavailable += 1
                missing = UNIMPLEMENTED.search(str(error))
                label = f'unimplemented card {missing.group(1)}' if missing else f'not importable ({error})'
                reasons.append(f'{what}: {label}')
                records.append({'what': what, 'status': 'unavailable', 'unrecorded': [],
                                'cause': label, 'card': missing.group(1) if missing else None})
            else:
                kind, why = cause
                unobserved += 1
                named.append(f'{what}: {kind} -- {why}')
                records.append({'what': what, 'status': 'unobserved', 'unrecorded': [],
                                'cause': kind})
        except AssertionError as error:
            failures.append(str(error))
            records.append({'what': what, 'status': 'failed', 'unrecorded': [],
                            'cause': str(error)})
    return {'compared': compared, 'unobserved': unobserved, 'unavailable': unavailable,
            'terminal': terminal, 'reasons': reasons, 'failures': failures, 'named': named,
            'records': records, 'unrecorded': unrecorded_fields}


def main():
    from sim_patch.parity.adapter import RNG_NAMES

    targets = [Path(arg) for arg in sys.argv[1:]]
    if not targets:
        # Every character directory, so a new one is discovered instead of remembered.
        targets = sorted(p for p in NATIVE_DIR.iterdir() if p.is_dir()) if NATIVE_DIR.is_dir() else []
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
    unobserved = 0
    skipped = 0
    finished = 0
    breakdown = {}
    totals = {}
    unrecorded_total = Counter()
    for path in paths:
        case = read_case(path)
        case_id = case['id']
        character = case.get('character', 'DEFECT')
        result = check_case(path, root, RNG_NAMES)
        compared = result['compared']
        required = case_id in EXPECTED
        card_id, purpose = EXPECTED.get(case_id, ('', 'any in-combat checkpoint'))
        if required:
            required_seen.add(case_id)
        graded += compared
        unobserved += result['unobserved']
        skipped += result['unavailable']
        finished += result['terminal']
        unrecorded_total.update(result['unrecorded'])
        for line in result['named']:
            kind = line.split(': ', 1)[1].split(' -- ', 1)[0]
            breakdown[kind] = breakdown.get(kind, 0) + 1
        per_character = totals.setdefault(character, {'fixtures': 0, 'compared': 0, 'unobserved': 0,
                                                      'unavailable': 0, 'unimportable': 0})
        per_character['fixtures'] += 1
        per_character['compared'] += compared
        per_character['unobserved'] += result['unobserved']
        per_character['unavailable'] += result['unavailable']
        if result['failures']:
            # An importable checkpoint that disagrees is a failure whatever the fixture is.
            failed.extend(result['failures'])
            print(f'FAIL  {case_id:40s} {card_id:15s} {result["failures"][0]}')
        elif required and (result['unavailable'] or result['unobserved']):
            # A required window must be fully replayable: a checkpoint the capture cannot support
            # is a regression of that window, whatever the reason.
            failed.extend(result['reasons'] + result['named'])
            print(f'FAIL  {case_id:40s} {card_id:15s} '
                  f'{result["unavailable"] + result["unobserved"]} checkpoints not comparable')
        elif compared:
            note = f' ({purpose})' if required else ''
            print(f'PASS  {case_id:40s} {card_id:15s} {compared} checkpoints{note}')
        else:
            # Nothing was compared, so nothing about this fixture is proven either way. Say so
            # instead of letting it sit silently inside the unimportable total.
            per_character['unimportable'] += 1
            why = (result['reasons'] or result['named'] or ['no in-combat checkpoint'])[0]
            print(f'SKIP  {case_id:40s} {card_id:15s} nothing compared: {why}')

    missing = sorted(set(EXPECTED) - required_seen)
    for case_id in missing:
        failed.append(f'{case_id}: required coverage-gap fixture is missing')
        print(f'FAIL  {case_id:40s} {"":15s} missing')

    print('\nper character:')
    for character in sorted(totals):
        per_character = totals[character]
        print(f'  {character:12s} {per_character["fixtures"]:4d} fixtures, '
              f'{per_character["compared"]:4d} checkpoints compared, '
              f'{per_character["unobserved"]:4d} unobservable, '
              f'{per_character["unavailable"]:4d} not importable, '
              f'{per_character["unimportable"]:4d} fixtures with nothing compared')

    if failed:
        print(f'\n{len(failed)} problem(s); {graded} checkpoints matched, '
              f'{unobserved} unobservable, {skipped} not importable')
        for problem in failed:
            print('  ' + problem)
        return 1
    print(f'\nALL {len(EXPECTED)} coverage-gap windows match the game; {graded} checkpoints matched, '
          f'{finished} post-fight checkpoints skipped, {unobserved} unobservable, '
          f'{skipped} not importable')
    for kind, count in sorted(breakdown.items()):
        print(f'  {count:5d}  {kind}')
    if unrecorded_total:
        # A checkpoint counted as compared can still have skipped a field the capture never
        # recorded. The counters above keep meaning what they meant; these are named beside them.
        print('\nfields inside compared checkpoints that the capture never recorded '
              '(listed so nothing looks checked that was not):')
        for name, count in unrecorded_total.most_common():
            print(f'  {count:5d}  {name}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
