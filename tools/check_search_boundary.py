"""Check the shared search API, stage decisions and portable runtime file boundary."""
from pathlib import Path
import sys
import threading
from types import SimpleNamespace
from unittest.mock import patch

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
import solver_search as core
from runtime_files import PYTHON_FILES, SEARCH_FILES

assert not {'backend', 'auto_policy', 'diagnostics'} & sys.modules.keys()
assert len(PYTHON_FILES) == len(set(PYTHON_FILES))
assert all((root / name).is_file() for name in PYTHON_FILES)
assert {'solver_search.py', 'plan_reduction.py', 'recovery.py'} == set(SEARCH_FILES)

from backend import Advisor, worker_version
assert Advisor.finalize_plan is core.finalize_plan
watched = {Path(row[0]).name for row in worker_version()}
assert set(SEARCH_FILES) <= watched

game = dict(current_hp=80, max_hp=80, relics=[])
win = dict(outcome=1, hp=80, post_hp=80, max_hp=80, value=100,
           turns=1, relics_held=True, actions=[])
loss = dict(win, hp=70, post_hp=70, value=90)

def run(plans, *, budget=8000, initial=None, stopped=False, context=None):
    calls = []
    interrupt = threading.Event()
    if stopped:
        interrupt.set()
    def plan(*args):
        calls.append(args[1])
        value = plans[len(calls)-1]
        if isinstance(value, Exception):
            interrupt.set()
            raise value
        return value
    # Isolate stage decisions; check_plan_reduction exercises real native replay.
    with patch.object(core, 'finalize_plan', side_effect=lambda root, plan: plan) as final:
        result = core.search_plan(SimpleNamespace(plan=plan), object(), budget,
                                  [], 0, 0, False, False, game=context or game,
                                  interrupt=interrupt, initial_plan=initial)
        assert final.call_count == 1
    return result, calls

assert run([win]) == (win, [8000])
assert run([loss, win]) == (win, [8000, 128000])
assert run([loss, dict(loss, value=80)]) == (loss, [8000, 128000])
long_loss = dict(loss, actions=[1, 2, 3])
short_loss = dict(loss, actions=[1])
assert run([long_loss, short_loss]) == (short_loss, [8000, 128000])
assert run([short_loss, long_loss]) == (short_loss, [8000, 128000])
assert run([long_loss, dict(short_loss, value=89)]) == (long_loss, [8000, 128000])
fast_context = dict(game, solver_auto_context={'boss_fast_finish': True})
fast_long = dict(long_loss, relics_held=False)
fast_short = dict(short_loss, relics_held=False)
assert run([fast_long, fast_short, fast_long], context=fast_context) == (fast_short, [2000, 8000, 128000])
assert run([], initial=win) == (win, [])
assert run([loss], stopped=True) == (loss, [8000])
assert run([loss, RuntimeError('cancelled')]) == (loss, [8000, 128000])
assert run([loss], budget=2000) == (loss, [2000])
fast, calls = run([win], context=dict(game, solver_auto_context={'boss_fast_finish': True}))
assert fast['boss_fast_finish'] and calls == [2000]
print('PASS: search imports without Mod services, stage choices, cancellation, single finalization and runtime coverage')
