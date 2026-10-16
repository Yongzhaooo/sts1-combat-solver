"""SYNTHETIC truth engine for testing run.py. It models no game; its observations are invented.

Speaks the adapter protocol of docs/truth-calibration.md. --fault makes one deliberate mistake
so the self-test can confirm the runner reports it.
"""
import argparse
import json
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--fault', choices=['extra-field', 'missing-field', 'wrong-value', 'wrong-type',
                                        'list-order', 'exit'])
fault = parser.parse_args().fault
state = None


def observation():
    value = {'synthetic': True, 'step': state['step'], 'log': list(state['log'])}
    if fault == 'extra-field' and state['step'] >= 2:
        value['extra'] = 1
    if fault == 'missing-field' and state['step'] >= 2:
        del value['synthetic']
    if fault == 'wrong-value' and state['step'] >= 2:
        value['step'] += 1
    if fault == 'wrong-type' and state['step'] >= 2:
        value['step'] = str(value['step'])
    if fault == 'list-order' and state['step'] >= 2:
        value['log'].reverse()
    return value


for line in sys.stdin:
    request = json.loads(line)
    if request['op'] == 'quit':
        break
    if fault == 'exit' and request['op'] == 'act':
        sys.exit(3)
    if request['op'] == 'new':
        state = {'step': 0, 'log': []}
        reply = {'ok': True, 'observation': observation()}
    elif request['action']['kind'] == 'play' and not 0 <= request['action']['hand'] < 5:
        reply = {'ok': False, 'error': 'illegal action: hand index %d' % request['action']['hand']}
    else:
        state['step'] += 1
        state['log'].append(request['action']['kind'])
        reply = {'ok': True, 'observation': observation()}
    print(json.dumps(reply), flush=True)
