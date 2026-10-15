"""Run calibration cases against a truth engine through the stdio adapter protocol.

    python tools/truth_calibration/run.py --adapter "<launch command>" fixtures/calibration
    python tools/truth_calibration/run.py --check fixtures/calibration     # format only, no truth engine

Format and protocol: docs/truth-calibration.md. The comparison is the strict, symmetric one in
sim_patch/parity/core.py: an extra field, a missing field, a type change or a different list
order is a difference, and there is no tolerance. Exit status is 0 only if every case passes.
"""
import argparse
import gzip
import json
from pathlib import Path
import queue
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'candidates/sts-ironclad-agent'))
from sim_patch.parity.core import differences  # noqa: E402

SCHEMA = 'sts1-calibration/1'
CHARACTERS = {'IRONCLAD', 'THE_SILENT', 'DEFECT', 'WATCHER'}
# kind -> (required fields, optional fields). "card" is a Java card ID; every other field is an integer.
ACTIONS = {
    'neow': (('index',), ()), 'event': (('index',), ()), 'match': (('index',), ()),
    'map': (('x', 'y'), ()),
    'play': (('hand', 'card'), ('target',)), 'end': ((), ()),
    'potion': (('slot',), ('target',)), 'discard_potion': (('slot',), ()),
    'reward': (('index',), ()), 'open_card': (('index',), ()), 'pick': (('index', 'card'), ()),
    'skip': ((), ()), 'bowl': ((), ()), 'proceed': ((), ()),
    'grid': (('index',), ()), 'grid_confirm': ((), ()), 'hand_select': (('index',), ()), 'hand_confirm': ((), ()),
    'rest': (('index',), ()), 'chest': ((), ()), 'boss_relic': (('index',), ()), 'skip_boss_relic': ((), ()),
    'shop': ((), ()), 'buy_card': (('index',), ()), 'buy_relic': (('index',), ()), 'buy_potion': (('index',), ()),
    'purge': (('index',), ()), 'leave': ((), ()),
}
ACTION_KINDS = set(ACTIONS)

# The observation, field by field (docs/truth-calibration.md). None in a tuple allows JSON null.
CARD = {'id': str, 'upgrades': int, 'cost': int, 'retain': bool}
POWER = {'id': str, 'amount': int}
RNG_STREAMS = ('ai', 'card', 'card_random', 'event', 'map', 'merchant', 'misc', 'monster', 'monster_hp',
               'potion', 'relic', 'shuffle', 'treasure')
OBSERVATION = {
    'phase': str, 'floor': int, 'turn': int,
    'player': {'hp': int, 'max_hp': int, 'block': int, 'energy': int, 'gold': int, 'powers': [POWER],
               'stance': str, 'mantra': int, 'orbs': [{'id': (str, None), 'passive': int, 'evoke': int}],
               'orb_slots': int, 'focus': int},
    'deck': [CARD],
    'piles': {'hand': [CARD], 'draw': [CARD], 'discard': [CARD], 'exhaust': [CARD]},
    'monsters': [{'id': str, 'hp': int, 'max_hp': int, 'block': int, 'powers': [POWER], 'intent': str,
                  'move': int}],
    'relics': [{'id': str, 'counter': int}],
    'potions': [str],
    'rng': {name: int for name in RNG_STREAMS},
}


def shape_problems(value, template=OBSERVATION, path=''):
    """Paths where value does not have exactly the template's keys and types."""
    if isinstance(template, dict):
        if not isinstance(value, dict):
            return [f'{path or "/"} must be an object']
        problems = [f'{path}/{key} is not a documented field' for key in sorted(value.keys() - template.keys())]
        problems += [f'{path}/{key} is missing' for key in sorted(template.keys() - value.keys())]
        return problems + [p for key in sorted(template.keys() & value.keys())
                           for p in shape_problems(value[key], template[key], f'{path}/{key}')]
    if isinstance(template, list):
        if not isinstance(value, list):
            return [f'{path} must be a list']
        return [p for i, item in enumerate(value) for p in shape_problems(item, template[0], f'{path}/{i}')]
    allowed = tuple(type(None) if t is None else t for t in (template if isinstance(template, tuple) else (template,)))
    return [] if type(value) in allowed else [f'{path} must be {"/".join(t.__name__ for t in allowed)}']


def validate_case(case):
    """Return a list of schema problems; empty means the case is well formed."""
    if not isinstance(case, dict):
        return ['case is not an object']
    problems = []

    def need(condition, message):
        if not condition:
            problems.append(message)

    need(case.get('schema') == SCHEMA, f'schema must be {SCHEMA}')
    for key in ('id', 'game_jar_sha256', 'seed'):
        need(isinstance(case.get(key), str) and case[key], f'{key} must be a non-empty string')
    need(case.get('character') in CHARACTERS, f'character must be one of {sorted(CHARACTERS)}')
    need(type(case.get('ascension')) is int and 0 <= case['ascension'] <= 20, 'ascension must be an integer 0..20')
    actions = case.get('actions')
    need(isinstance(actions, list), 'actions must be a list')
    actions = actions if isinstance(actions, list) else []
    for index, action in enumerate(actions):
        kind = action.get('kind') if isinstance(action, dict) else None
        need(kind in ACTION_KINDS, f'actions[{index}].kind must be one of {sorted(ACTION_KINDS)}')
        if kind in ACTION_KINDS:
            required, optional = ACTIONS[kind]
            fields = action.keys() - {'kind'}
            need(set(required) <= fields <= set(required + optional)
                 and all(type(action[f]) is (str if f == 'card' else int) for f in fields),
                 f'actions[{index}] {kind} needs {list(required)} (optional {list(optional)}); '
                 'card is a string, the rest integers')
    checkpoints = case.get('checkpoints')
    need(isinstance(checkpoints, list) and checkpoints, 'checkpoints must be a non-empty list')
    last = -1
    for index, checkpoint in enumerate(checkpoints if isinstance(checkpoints, list) else []):
        checkpoint = checkpoint if isinstance(checkpoint, dict) else {}
        after = checkpoint.get('after')
        # "after" counts applied actions; 0 is the observation that "new" returns.
        need(type(after) is int and last < after <= len(actions),
             f'checkpoints[{index}].after must be an increasing integer within 0..len(actions)')
        need(isinstance(checkpoint.get('observation'), dict), f'checkpoints[{index}].observation must be an object')
        last = after if type(after) is int else last
    need(isinstance(case.get('source'), dict), 'source must be an object')
    return problems


class AdapterError(Exception):
    pass


class Adapter:
    """One adapter process speaking JSON lines; a failure kills it and the next request restarts it."""

    def __init__(self, command, timeout):
        self.command, self.timeout, self.process, self.lines = command, timeout, None, None

    def start(self):
        # shell=True: the command is a line as typed in a terminal, so quoting follows the platform.
        process = self.process = subprocess.Popen(self.command, shell=True, stdin=subprocess.PIPE,
                                                  stdout=subprocess.PIPE, text=True, encoding='utf-8')
        lines = self.lines = queue.Queue()

        def read():
            for line in process.stdout:
                lines.put(line)
            lines.put(None)

        threading.Thread(target=read, daemon=True).start()

    def request(self, message):
        if self.process is None:
            self.start()
        try:
            self.process.stdin.write(json.dumps(message, ensure_ascii=False) + '\n')
            self.process.stdin.flush()
            line = self.lines.get(timeout=self.timeout)
        except queue.Empty:
            self.close()
            raise AdapterError(f'adapter gave no reply within {self.timeout} s')
        except OSError:
            line = None
        if line is None:
            self.close()
            raise AdapterError('adapter exited')
        try:
            reply = json.loads(line)
        except ValueError:
            self.close()
            raise AdapterError('adapter reply is not JSON: ' + line[:200])
        if not isinstance(reply, dict) or type(reply.get('ok')) is not bool:
            self.close()
            raise AdapterError('adapter reply needs a boolean "ok"')
        return reply

    def close(self):
        process, self.process = self.process, None
        if process is None:
            return
        try:
            process.stdin.write(json.dumps({'op': 'quit'}) + '\n')
            process.stdin.close()
            process.wait(timeout=2)
        except (OSError, subprocess.TimeoutExpired):
            process.kill()
            process.wait()


def run_case(adapter, case):
    """('pass', None) or ('fail', reason); a difference names its checkpoint and the first differing path."""
    problems = validate_case(case)
    if problems:
        return 'fail', 'invalid case: ' + '; '.join(problems)
    expected = {c['after']: c['observation'] for c in case['checkpoints']}
    try:
        reply = adapter.request({'op': 'new', 'seed': case['seed'], 'character': case['character'],
                                 'ascension': case['ascension']})
        for applied in range(len(case['actions']) + 1):
            if applied:
                reply = adapter.request({'op': 'act', 'action': case['actions'][applied - 1]})
            if not reply['ok']:
                step = f'action {applied}' if applied else 'new'
                return 'fail', f'adapter rejected {step}: {reply.get("error")}'
            if applied in expected:
                found = differences(expected[applied], reply.get('observation'))
                if found:
                    return 'fail', (f'after {applied} actions: {len(found)} differences; first: '
                                    + json.dumps(found[0], ensure_ascii=False))
    except AdapterError as error:
        return 'fail', str(error)
    return 'pass', None


def read_case(file):
    data = file.read_bytes()
    return json.loads(gzip.decompress(data) if file.suffix == '.gz' else data)


def check_case(case):
    """Format problems of a committed case, observations included; needs no truth engine."""
    problems = validate_case(case)
    for index, checkpoint in enumerate(case.get('checkpoints') or [] if isinstance(case, dict) else []):
        if isinstance(checkpoint, dict) and isinstance(checkpoint.get('observation'), dict):
            problems += [f'checkpoints[{index}].observation{p}' for p in shape_problems(checkpoint['observation'])]
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--adapter', help='command that starts the adapter')
    mode.add_argument('--check', action='store_true', help='only check the case format, observations included')
    parser.add_argument('--timeout', type=float, default=60, help='seconds to wait for each adapter reply')
    parser.add_argument('paths', nargs='+', help='case files, or directories searched for *.json and *.json.gz')
    args = parser.parse_args(argv)
    files = []
    for path in map(Path, args.paths):
        files += sorted([*path.rglob('*.json'), *path.rglob('*.json.gz')]) if path.is_dir() else [path]
    if not files:
        sys.exit('FAIL: no calibration cases found')
    adapter, failed = None if args.check else Adapter(args.adapter, args.timeout), 0
    try:
        for file in files:
            try:
                case = read_case(file)
            except (OSError, ValueError) as error:
                status, reason = 'fail', f'cannot read case: {error}'
            else:
                if args.check:
                    problems = check_case(case)
                    status, reason = ('fail', '; '.join(problems[:5])) if problems else ('pass', None)
                else:
                    status, reason = run_case(adapter, case)
            failed += status != 'pass'
            print(f'{status.upper()}: {file}' + (f'\n  {reason}' if reason else ''))
    finally:
        if adapter:
            adapter.close()
    print(f'{len(files) - failed}/{len(files)} cases passed')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
