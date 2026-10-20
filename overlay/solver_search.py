"""Shared bounded combat search; callers own import, settings and execution.

Accepts an imported native root and explicit game context. It has no Mod
transport, diagnostic output, outside-combat policy or process lifecycle.
"""
import time

from recovery import health

DEEP_BUDGET = 128000


def boss_fast_plan(game, plan, slots):
    return (game.get('solver_auto_context', {}).get('boss_fast_finish') is True
            and not slots and plan['outcome'] == 1 and plan['hp'] > 0
            and plan['turns'] <= 20
            and not plan.get('used_tail') and not plan.get('used_fairy')
            and plan.get('relics_held') is True
            and not plan.get('potion_uses')
            and not any((int(bits) & 0xffffffff) >> 29 == 1 for bits in plan['actions'])
            and 2 * max(0, game['current_hp'] - plan['hp']) <= game['max_hp'])


def finalize_plan(root, plan):
    from plan_reduction import postprocess, replay
    reduced = postprocess(root, plan['actions'])
    end = replay(root, reduced['actions'])[0]
    result = dict(plan, actions=reduced['actions'], turns=end.turn-root.turn)
    # Preserve search ranking; reduction only changes execution of an accepted result.
    result['action_reduction'] = dict(before=len(plan['actions']), after=len(reduced['actions']),
                                      seconds=reduced['seconds'])
    return result

def search_plan(native, root, budget, slots, credit, index, kill_thieves, generated,
                *, game, interrupt, initial_plan=None, deep_gate=None):
    fast_boss = not slots and game.get('solver_auto_context', {}).get('boss_fast_finish') is True
    if fast_boss:
        generated = False
    initial_budget = 2000 if fast_boss else budget
    args = (root, initial_budget, slots, credit, index, kill_thieves, generated)
    began = time.monotonic()
    best = dict(initial_plan) if initial_plan is not None else dict(
        native.plan(*args, True) if fast_boss else native.plan(*args))
    seconds = None if initial_plan is not None else time.monotonic() - began
    if boss_fast_plan(game, best, slots):
        best['boss_fast_finish'] = True
        return finalize_plan(root, best)
    if initial_budget != budget and not interrupt.is_set():
        candidate = dict(native.plan(root, budget, slots, credit, index, kill_thieves, generated, fast_boss))
        if candidate['value'] > best['value']:
            best = candidate
        if boss_fast_plan(game, best, slots):
            best['boss_fast_finish'] = True
            return finalize_plan(root, best)
    # Standard search gets one bounded refinement when its route still loses HP.
    if budget == 8000 and not interrupt.is_set() and (
            best['outcome'] != 1
            or health(best, game, True)['loss'] > 0
            or best.get('used_tail') or best.get('used_fairy')) and not (
            deep_gate is not None and deep_gate(best, slots, seconds)):
        try:
            refined = dict(native.plan(root, DEEP_BUDGET, slots, credit, index, kill_thieves, generated, fast_boss))
        except RuntimeError:
            if not interrupt.is_set():
                raise
            return finalize_plan(root, best)
        if refined['value'] > best['value']:
            best = refined
    if boss_fast_plan(game, best, slots):
        best['boss_fast_finish'] = True
    return finalize_plan(root, best)
