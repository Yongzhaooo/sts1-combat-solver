"""Potion-branch stage decisions in solver_search, driven by stub plans: no native module or Advisor needed."""
from pathlib import Path
import sys
import threading

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'overlay'))
from solver_search import (DEEP_BUDGET, add_follow_ups, clean_no_potion, first_stage, potion_margin_close,
                           research_gate, research_line, stop_after_no_potion)

game = {'current_hp': 70, 'max_hp': 80, 'solver_auto_context': {}}
win = dict(outcome=1, hp=70, actions=[1])
idle = threading.Event()


def stages_after(first, fast_boss=False, slots=(0, 1), smoke=(), brew=(), brew_order=False, loss=0, good=False, interrupt=idle):
    info = [dict(id='no-potion', available=True, won=True, loss=0 if good else 99, focus=99)]
    stages = [list(first[0])]
    add_follow_ups(stages, stages[0], game, info, loss, fast_boss, list(slots), list(smoke), set(brew), brew_order, interrupt)
    if fast_boss and len(stages) > 1:
        add_follow_ups(stages, stages[1], game, info, loss, fast_boss, list(slots), list(smoke), set(brew), brew_order, interrupt)
    return stages[1:]


# A clean no-potion win searches no bottle; a brew order or a fast boss changes only what the stage holds.
clean = lambda plan, loss=0, toolbox=False, g=game: clean_no_potion(g, plan, lambda: loss, 0, toolbox)
assert clean(win) and not clean(dict(win, outcome=2)) and not clean(win, loss=1)
assert not clean(dict(win, used_tail=True)) and not clean(dict(win, used_fairy=1)) and not clean(win, toolbox=True)
assert clean(win, loss=50, g=game) is False and clean_no_potion(game, win, lambda: 50, 50, False)
focus_game = {**game, 'solver_auto_context': {'focus_mode': True}}
assert not clean_no_potion(focus_game, dict(win, focus=24), lambda: 0, 0, False)
assert clean_no_potion(focus_game, dict(win, focus=25), lambda: 0, 0, False)
assert first_stage([0, 1], [], [], False, True, False) == [[()]]
assert first_stage([0, 1], [], [], False, False, False) == [[(), (0,), (1,)]]
assert first_stage([0, 1], [], [], False, True, True) == [[(), (0,), (1,)]]
assert first_stage([0, 1], [], [], True, False, False) == [[()]]
assert first_stage([0, 1, 2], [2], [0, 1], False, False, False) == [[(), (0,), (1,), (2,), (2, 0), (2, 1), (2, 0, 1)]]
assert first_stage([0, 1], [1], [0], True, False, False) == [[(), (1, 0)]]
stop = lambda **k: stop_after_no_potion(game, win, lambda: 0, 0, False, **{'smoke_slots': [], 'brew_order': False, 'budget': 8000, 'brew_slots': set(), **k})
assert stop() and not stop(smoke_slots=[1]) and not stop(brew_order=True)
assert stop(budget=DEEP_BUDGET) and not stop(budget=DEEP_BUDGET, brew_slots={0}) and stop(brew_slots={0})
print('PASS: a clean no-potion win stops the search before bottles; brew order, smoke, tails and Focus keep it going')

# Follow-up stages.
assert stages_after([[(), (0,), (1,)]], good=True) == []
assert stages_after([[(), (0,), (1,)]]) == [[(0, 1)]]
assert stages_after([[(), (0,), (1,), (2,)]], slots=(0, 1, 2)) == [[(0, 1), (0, 2), (1, 2), (0, 1, 2)]]
assert stages_after([[(), (0,), (1,), (2,)]], slots=(0, 1, 2), good=True, brew=(2,), brew_order=True) == [[(0, 2), (1, 2), (0, 1, 2)]]
assert stages_after([[(), (0,)]], slots=(0,)) == []
# A Smoke Bomb slot never joins bottle sets.
assert stages_after([[(), (0,), (1,), (2,)]], slots=(0, 1, 2), smoke=(2,)) == [[(0, 1)]]
interrupted = threading.Event()
interrupted.set()
assert stages_after([[(), (0,), (1,)]], interrupt=interrupted) == []
print('PASS: bottle pairs and the full set are added only without a good line, or when a brew is among them')

# Fast boss: no-potion alone, then singles, then combos.
assert stages_after([[()]], fast_boss=True) == [[(0,), (1,)], [(0, 1)]]
assert stages_after([[()]], fast_boss=True, good=True) == [[(0,), (1,)]]
assert stages_after([[()]], fast_boss=True, slots=()) == []
print('PASS: a fast boss searches no-potion, then single bottles, then sets')

# Research trigger.
def branch(id, loss, uses=(), **k):
    return dict(id=id, available=True, won=True, loss=loss, potion_uses=[{'slot': s} for s in uses], **k)


assert potion_margin_close(game, [branch('no-potion', 4), branch('potion-1', 2, (1,))])
for rows in ([branch('no-potion', 5), branch('potion-1', 2, (1,))], [branch('no-potion', 2), branch('potion-1', 2, (1,))],
             [branch('no-potion', 2), branch('potion-1', 4, (1,))], [branch('no-potion', 4)]):
    assert not potion_margin_close(game, rows), rows
quick = {**game, 'solver_auto_context': {'high_speed': True}}
assert research_gate(game, win, idle) and not research_gate(game, win, interrupted)
assert not research_gate(game, dict(win, boss_fast_finish=True), idle) and not research_gate(quick, win, idle)
assert research_gate({**quick, 'solver_auto_context': {'high_speed': True, 'focus_mode': True}}, win, idle)
assert research_gate({**game, 'solver_auto_context': {'high_speed': False}}, win, idle)

seen = []
def search(budget):
    seen.append(budget)
    return deeper
deeper = dict(win, value=2, actions=[1])
base = dict(win, value=1, actions=[1, 2])
assert research_line(base, 8000, search, idle) == deeper and seen == [2 * DEEP_BUDGET]
assert research_line(base, 300000, search, idle) == deeper and seen[-1] == 600000
deeper = dict(win, value=1, actions=[1, 2])
assert research_line(base, 8000, search, idle) is None
deeper = dict(win, value=1, actions=[1])
assert research_line(base, 8000, search, idle) is not None  # equal value, fewer actions wins


def fail(budget):
    raise RuntimeError('cancelled')


assert research_line(base, 8000, fail, interrupted) is None
try:
    research_line(base, 8000, fail, idle)
except RuntimeError:
    pass
else:
    raise AssertionError('a search failure without an interrupt must propagate')
print('PASS: a bottle saving under 3 HP re-searches the potion-free line deeper unless high speed, fast finish or interrupt')
