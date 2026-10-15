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


def command(*extra):
    argv = [sys.executable, str(SELFTEST / 'fake_adapter.py'), *extra]
    return subprocess.list2cmdline(argv) if sys.platform == 'win32' else shlex.join(argv)


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
    print('PASS: calibration runner accepts the synthetic case and reports every injected fault')


if __name__ == '__main__':
    main()
