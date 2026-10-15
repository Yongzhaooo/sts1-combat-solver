"""SYNTHETIC headless CLI for testing headless_adapter.py. It models no game; IDs and numbers are invented.

Answers new / actions / step <i> / state / quit like a cli.sh-style CLI, each followed by the "> " prompt.
`state` carries the calibration engine extension. Playing a card moves it from hand to discard.
"""
import json
import sys

state = None


def card(card_id):
    return {'id': card_id, 'upgrades': 0, 'cost': 1, 'retain': False}


def new():
    return {'phase': 'PLAYER_TURN', 'floor': 1, 'name': 'text the projection must drop',
            'player': {'hp': 10, 'maxHp': 10, 'block': 0, 'energy': 3, 'gold': 0, 'powers': [{'id': 'SYN_FOCUS', 'amount': 1}],
                       'orbs': [{'passiveAmount': 0, 'evokeAmount': 0}], 'relics': [{'id': 'SYN_RELIC', 'counter': -1}],
                       'potions': [{'id': 'SYN_SLOT'}], 'hand': [{'id': 'SYN_A'}, {'id': 'SYN_B'}]},
            'monsters': [{'id': 'SYN_M', 'hp': 5, 'maxHp': 5, 'block': 0, 'powers': []}],
            'calibration': {'turn': 1, 'stance': 'SYN_STANCE', 'deck': [card('SYN_A'), card('SYN_B')],
                            'hand': [card('SYN_A'), card('SYN_B')], 'draw': [], 'discard': [], 'exhaust': [],
                            'monsters': [{'intent': 'SYN_INTENT', 'move': 1}],
                            'rng': {name: 0 for name in ('aiRng', 'cardRng', 'cardRandomRng', 'eventRng', 'mapRng',
                                                         'merchantRng', 'miscRng', 'monsterRng', 'monsterHpRng',
                                                         'potionRng', 'relicRng', 'shuffleRng', 'treasureRng')}}}


def actions():
    return [f'play {i + 1} 0 # synthetic' for i in range(len(state['player']['hand']))] + ['end']


sys.stdout.write('synthetic CLI\n> ')
sys.stdout.flush()
for line in sys.stdin:
    words = line.split()
    if words[0] == 'quit':
        break
    if words[0] == 'new':
        state, text = new(), 'phase=PLAYER_TURN'
    elif words[0] == 'actions':
        text = '\n'.join(['phase=PLAYER_TURN'] + [f'  {i}: {a}' for i, a in enumerate(actions())])
    elif words[0] == 'step':
        command = actions()[int(words[1])]
        if command.startswith('play'):
            played = int(command.split()[1]) - 1
            state['player']['hand'].pop(played)
            ext = state['calibration']
            ext['discard'].append(ext['hand'].pop(played))
            ext['rng']['cardRandomRng'] += 1
        else:
            state['calibration']['turn'] += 1
        text = 'exec: ' + command
    elif words[0] == 'state':
        text = json.dumps(state, indent=2)
    else:
        text = 'unknown command: ' + words[0]
    sys.stdout.write(text + '\n> ')
    sys.stdout.flush()
