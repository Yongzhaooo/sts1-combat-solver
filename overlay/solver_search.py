"""Shared bounded combat search; callers own import, settings and execution.

Accepts an imported native root and explicit game context. It has no Mod
transport, diagnostic output, outside-combat policy or process lifecycle.
"""
import itertools
import time

from recovery import health

DEEP_BUDGET = 128000
# Bottle sets are compared only when no single bottle wins within this effective loss.
MULTI_POTION_LOSS = 6
# A winning bottle line that saves under this much HP over the winning potion-free line gets the potion-free line
# searched again at twice the deep budget before they are compared (user, 2026-11-06).
POTION_MARGIN_HP = 3
FOCUS_TARGET = 25  # High Focus mode (user, 2026-11-05): look for a win ending with at least this much Focus


def focus_target(game):
    return FOCUS_TARGET if game.get('solver_auto_context', {}).get('focus_mode') is True else 0


def focus_short(game, plan):
    target = focus_target(game)
    return bool(target) and plan.get('focus', 0) < target


def better_plan(candidate, best):
    return (candidate['value'] > best['value']
            or (candidate['value'] == best['value'] and len(candidate['actions']) < len(best['actions'])))


def potion_margin_close(game, branches):
    """True when the best winning bottle line saves under POTION_MARGIN_HP over the winning potion-free line."""
    base = next((b for b in branches if b['id'] == 'no-potion' and b.get('available') and b.get('won')), None)
    if base is None:
        return False
    saved = [base['loss'] - b['loss'] for b in branches
             if b['id'] != 'no-potion' and b.get('available') and b.get('won') and b.get('potion_uses')
             and focus_short(game, b) == focus_short(game, base)]
    return bool(saved) and 0 < max(saved) < POTION_MARGIN_HP


def clean_no_potion(game, plan, loss, quiet_loss, toolbox):
    """A won no-potion line with no loss beyond quiet_loss, no tail/fairy rescue and no missed Focus target; loss is a callable."""
    return (plan['outcome'] == 1 and loss() <= quiet_loss
            and not plan.get('used_tail') and not plan.get('used_fairy')
            and not toolbox and not focus_short(game, plan))


def first_stage(slots, smoke_slots, strength_slots, fast_boss, clean_regular, brew_order):
    """Opening stage list: no-potion alone after a clean win (unless a brew must be ordered) or in a fast boss, else singles too; Smoke Bomb pairs with Strength/Flex."""
    stage = [()] if fast_boss or (clean_regular and not brew_order) else [()] + [(slot,) for slot in slots]
    if smoke_slots and strength_slots:
        stage.extend((smoke, strength) for smoke in smoke_slots for strength in strength_slots)
        if len(strength_slots) > 1:
            stage.extend((smoke, *strength_slots) for smoke in smoke_slots)
    return [stage]


def stop_after_no_potion(game, plan, loss, quiet_loss, toolbox, smoke_slots, brew_order, budget, brew_slots):
    return (not smoke_slots and clean_no_potion(game, plan, loss, quiet_loss, toolbox)
            and not (brew_order or (budget >= DEEP_BUDGET and brew_slots)))


def add_follow_ups(stages, stage, game, branch_info, quiet_loss, fast_boss, slots, smoke_slots, brew_slots, brew_order,
                    interrupt):
    """Append the next stage after `stage` finished: fast bosses get singles after the no-potion stage; bottle pairs and the full set only without a good line (or when a brew is involved)."""
    good = any(b['available'] and b['won'] and b['loss'] <= max(MULTI_POTION_LOSS, quiet_loss)
               and not focus_short(game, b) for b in branch_info)
    if fast_boss and stage is stages[0] and slots:
        stages.append([(slot,) for slot in slots])
    singles_done = (not fast_boss and stage is stages[0]) or (fast_boss and len(stages) > 1 and stage is stages[1])
    if singles_done and not interrupt.is_set() and (not good or brew_order) and len(slots) > 1:
        stages.append([c for n in range(2, len(slots) + 1)
                       for c in itertools.combinations([slot for slot in slots if slot not in smoke_slots], n)
                       if (n == 2 or n == len(slots)) and (not good or bool(brew_slots.intersection(c)))])


def research_gate(game, plan, interrupt):
    return not interrupt.is_set() and not plan.get('boss_fast_finish') and not (
        game.get('solver_auto_context', {}).get('high_speed') is True and not focus_target(game))


def research_line(base, budget, search, interrupt):
    """After potion_margin_close: search the potion-free line at twice the deep budget with search(budget) and return it when it beats `base`, else None."""
    try:
        line = dict(search(2 * max(budget, DEEP_BUDGET)))
    except RuntimeError:
        if not interrupt.is_set():
            raise
        return None
    return line if better_plan(line, base) else None


def boss_fast_plan(game, plan, slots):
    return (game.get('solver_auto_context', {}).get('boss_fast_finish') is True
            and not slots and plan['outcome'] == 1 and plan['hp'] > 0
            and plan['turns'] <= 20
            and not plan.get('used_tail') and not plan.get('used_fairy')
            and plan.get('relics_held') is True
            and not plan.get('potion_uses')
            and not any((int(bits) & 0xffffffff) >> 29 == 1 for bits in plan['actions'])
            and 2 * max(0, game['current_hp'] - plan['hp']) <= game['max_hp']
            and not focus_short(game, plan))


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
    # A search that reached the memory budget is over, like an interrupted one: no further stage starts.
    if initial_budget != budget and not interrupt.is_set() and not best.get('memory_capped'):
        candidate = dict(native.plan(root, budget, slots, credit, index, kill_thieves, generated, fast_boss))
        if better_plan(candidate, best):
            best = candidate
        if boss_fast_plan(game, best, slots):
            best['boss_fast_finish'] = True
            return finalize_plan(root, best)
    # Standard search gets one bounded refinement when its route still loses HP or misses the Focus target.
    if budget == 8000 and not interrupt.is_set() and not best.get('memory_capped') and (focus_short(game, best) or (
            best['outcome'] != 1
            or health(best, game, True)['loss'] > 0
            or best.get('used_tail') or best.get('used_fairy')) and not (
            deep_gate is not None and deep_gate(best, slots, seconds))):
        try:
            refined = dict(native.plan(root, DEEP_BUDGET, slots, credit, index, kill_thieves, generated, fast_boss))
        except RuntimeError:
            if not interrupt.is_set():
                raise
            return finalize_plan(root, best)
        if better_plan(refined, best):
            best = refined
    if boss_fast_plan(game, best, slots):
        best['boss_fast_finish'] = True
    return finalize_plan(root, best)
