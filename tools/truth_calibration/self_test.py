"""Self-test for run.py using a SYNTHETIC fake adapter and a synthetic case (no game, no real observations)."""
import copy
import json
from pathlib import Path
import shlex
import subprocess
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import run  # noqa: E402

SELFTEST = HERE / 'selftest'
CASE = json.loads((SELFTEST / 'synthetic-0001.json').read_text(encoding='utf-8'))


def line(argv):
    return subprocess.list2cmdline(argv) if sys.platform == 'win32' else shlex.join(argv)


def command(*extra):
    return line([sys.executable, str(SELFTEST / 'fake_adapter.py'), *extra])


def check_headless_adapter():
    """Reference adapter and projection against the SYNTHETIC CLI in selftest/fake_cli.py."""
    from headless_projection import MissingField, project
    try:
        project({'phase': 'MAP', 'floor': 0, 'player': {'powers': []}})
        raise AssertionError('a state without the engine extension must be rejected')
    except MissingField as error:
        assert 'calibration' in str(error), error
    cli = line([sys.executable, str(SELFTEST / 'fake_cli.py'), '{saves}'])
    adapter = run.Adapter(line([sys.executable, str(HERE / 'headless_adapter.py'), '--cli', cli]), 20)
    try:
        reply = adapter.request({'op': 'new', 'seed': '1', 'character': 'IRONCLAD', 'ascension': 0})
        assert reply['ok'] and run.shape_problems(reply['observation']) == [], reply
        play = {'kind': 'play', 'hand': 0, 'card': 'SYN_B', 'target': 0}
        reply = adapter.request({'op': 'act', 'action': play})
        assert not reply['ok'] and 'does not hold SYN_B' in reply['error'], reply
        reply = adapter.request({'op': 'act', 'action': {'kind': 'map', 'x': 0, 'y': 0}})
        assert not reply['ok'] and 'does not offer' in reply['error'], reply
        reply = adapter.request({'op': 'act', 'action': dict(play, hand=1)})
        observation = reply['observation']
        assert [c['id'] for c in observation['piles']['discard']] == ['SYN_B'], reply
        assert observation['rng']['card_random'] == 1 and observation['player']['focus'] == 0, observation
        assert observation['player']['orbs'] == [{'id': None, 'passive': 0, 'evoke': 0}], observation
    finally:
        adapter.close()


def result(case, *extra):
    adapter = run.Adapter(command(*extra), 20)
    try:
        return run.run_case(adapter, case)
    finally:
        adapter.close()


def main():
    assert run.validate_case(CASE) == [], run.validate_case(CASE)
    for edit, text in [(lambda c: c.update(schema='x'), 'schema'),
                       (lambda c: c.update(character='SILENT'), 'character'),
                       (lambda c: c['checkpoints'][1].update(after=9), 'after'),
                       (lambda c: c['actions'][0].update(kind='dance'), 'kind'),
                       (lambda c: c.pop('source'), 'source')]:
        broken = copy.deepcopy(CASE)
        edit(broken)
        assert any(text in p for p in run.validate_case(broken)), (text, run.validate_case(broken))

    assert result(CASE) == ('pass', None), result(CASE)
    # Every deliberate fault must be reported, with the path of the first difference.
    expected = {'extra-field': '/extra', 'missing-field': '/synthetic', 'wrong-value': '/step',
                'wrong-type': '/step', 'list-order': '/log/0'}
    for fault, path in expected.items():
        status, reason = result(CASE, '--fault', fault)
        assert status == 'fail' and 'after 2 actions' in reason and f'"path": "{path}"' in reason, (fault, reason)
    status, reason = result(CASE, '--fault', 'exit')
    assert status == 'fail' and 'adapter exited' in reason, reason
    illegal = copy.deepcopy(CASE)
    illegal['actions'][1]['hand'] = 9
    status, reason = result(illegal)
    assert status == 'fail' and 'rejected action 2' in reason and 'hand index 9' in reason, reason

    # Command line: exit status follows the verdict.
    for extra, code in (([], 0), (['--fault', 'wrong-value'], 1)):
        done = subprocess.run([sys.executable, str(HERE / 'run.py'), '--adapter', command(*extra), str(SELFTEST)],
                              capture_output=True, text=True, encoding='utf-8')
        assert done.returncode == code, (extra, done.stdout, done.stderr)
    check_headless_adapter()
    print('PASS: calibration runner accepts the synthetic case and reports every injected fault; '
          'the reference adapter drives the synthetic CLI')


if __name__ == '__main__':
    main()
