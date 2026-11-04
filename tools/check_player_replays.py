"""Replay recorded player fights action by action and require the game's monster HP after every action.

Each fixture under fixtures/player-replays is cut from a player's state_divergence export: the
combat root the solver imported, the commands the mod executed, and the monster HP the game
reported before the next request. A fixture records a fight that once diverged, so a rule that
regresses shows up here as the same divergence.

  python tools/check_player_replays.py            # check every fixture
  python tools/check_player_replays.py --cut EXPORT.json NAME   # write a fixture from an export
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'fixtures/player-replays'


def cut(export_path, name):
    export = json.loads(Path(export_path).read_text(encoding='utf-8'))
    events = export['events']
    start = max(i for i, e in enumerate(events) if e['k'] == 'combat_open')
    steps, command = [], None
    for event in events[start:]:
        if event['k'] == 'diag' and event.get('event') == 'execute':
            command = event['message']
        elif event['k'] == 'request' and command is not None and 'snap' in event:
            steps.append({'command': command, 'monster_hp': [m[1] for m in event['snap']['monsters']]})
            command = None
        elif event['k'] == 'fault':
            break
    # The fight that faulted may already be in completed; a potion picked up on the reward screen
    # opens a partial record whose root is that screen, so only a combat root counts.
    floor = events[start].get('f')
    battles = [b for b in [*export['recent_battles'].get('completed', []), export['recent_battles'].get('current')]
               if b and b.get('root') and b['root']['game_state'].get('floor') == floor
               and b['root']['game_state'].get('room_phase') == 'COMBAT']
    assert battles, 'no recorded root for floor %s' % floor
    fixture = {'source': Path(export_path).name, 'root': battles[-1]['root'], 'steps': steps}
    target = FIXTURES / (name + '.json')
    target.write_text(json.dumps(fixture, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    print('wrote %s: %d steps' % (target, len(steps)))


def check():
    sys.path.insert(0, str(ROOT / 'overlay'))
    from backend import Advisor, view_from_state
    session = Advisor().search
    S = session.sts
    paths = sorted(FIXTURES.glob('*.json'))
    assert paths, 'no player replay fixtures'
    for path in paths:
        fixture = json.loads(path.read_text(encoding='utf-8'))
        view = view_from_state(fixture['root'])
        view = {**view, 'game': {**view['game'], 'seed': int(view['game']['seed']) % (1 << 64)}}
        battle = session.comparator.import_battle(view, require_search_state=True)
        for number, step in enumerate(fixture['steps'], 1):
            words = step['command'].split()
            if words[0] == 'end':
                action = S.SearchAction(S.SearchActionType.END_TURN, 0, 0)
            elif words[0] == 'play':
                action = S.SearchAction(S.SearchActionType.CARD, int(words[1]) - 1, int(words[2]) if len(words) > 2 else 0)
            else:
                raise ValueError('%s step %d: unsupported command %r' % (path.name, number, step['command']))
            assert action.is_valid(battle), (path.name, number, step['command'])
            action.execute(battle)
            got = [int(m.cur_hp) for m in battle.monsters]
            assert got == step['monster_hp'], '%s step %d (%s): game %s, simulator %s' % (
                path.name, number, step['command'], step['monster_hp'], got)
    print('PASS: %d player replays match the game after every action' % len(paths))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--cut', nargs=2, metavar=('EXPORT', 'NAME'))
    args = parser.parse_args()
    cut(*args.cut) if args.cut else check()
