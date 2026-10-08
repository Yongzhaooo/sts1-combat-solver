"""Game-only combat advisor. JSON lines in/out; native search lives in a killable child."""
from __future__ import annotations

import copy
import os
import importlib.machinery
import itertools
from concurrent.futures import ThreadPoolExecutor
import json
import multiprocessing as mp
from pathlib import Path
import queue
import sys
import threading
import time
import traceback
from recovery import health, future_recovery
from diagnostics import AsyncReports, export_debug
from auto_policy import recommend

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT / 'candidates/sts-ironclad-agent'
sys.path.insert(0, str(REPO))
# Classes the combat engine imports exactly. Adding one also needs its card/relic
# coverage in the engine and the same entry in SolverMod.SUPPORTED.
SUPPORTED_CLASSES = {'IRONCLAD': '战士'}
# Bottle sets are compared only when no single bottle wins within this effective loss.
MULTI_POTION_LOSS = 6
DEEP_BUDGET = 128000


def heart_fight(game):
    return any(m['id'] == 'CorruptHeart' for m in game['combat_state']['monsters'])


def boss_fast_plan(game, plan, slots):
    return (game.get('solver_auto_context', {}).get('boss_fast_finish') is True
            and not slots and plan['outcome'] == 1 and plan['hp'] > 0
            and plan['turns'] <= 20
            and not plan.get('used_tail') and not plan.get('used_fairy')
            and not plan.get('potion_uses')
            and not any((int(bits) & 0xffffffff) >> 29 == 1 for bits in plan['actions'])
            and 2 * max(0, game['current_hp'] - plan['hp']) <= game['max_hp'])


def toolbox_selection(game):
    return (game.get('screen_type') == 'CARD_REWARD'
            and game.get('solver_selection', {}).get('current_action', {}).get('class') == 'ChooseOneColorless')


def start_selection(comparator, view):
    from steam.selection_import import queued_actions, start_selection as original_selection
    # These RelicStats callbacks only measure hand size before/after a draw.
    # Preserve the actual draw actions and reject any other unknown mod action.
    stats_only = {'relicstats.actions.PreCardDrawAction',
                  'relicstats.actions.CardDrawFollowupAction'}
    actions = [a for a in queued_actions(view) if a.get('class_full') not in stats_only]
    for action in actions:
        # Undo the Spire replaces RedSkull$1 with this equivalent named callback.
        # Match its full identity; unrelated mod actions must still be rejected.
        if action.get('class_full') == 'undobutton.patches.TemporaryActionsPatches$RedSkullAction':
            if not any(r['id'] == 'Red Skull' for r in view['game']['relics']):
                raise ValueError('queued RedSkullAction requires Red Skull relic')
            action.update({'class': '', 'class_full': 'com.megacrit.cardcrawl.relics.RedSkull$1',
                           'this$0': 'Red Skull'})
    adapted = {**view, 'live_run': {**view['live_run'], 'pending_actions': actions}}
    return original_selection(comparator, adapted)


def view_from_state(state):
    from steam.rng_contract import validate, COMBAT_STREAMS
    game = state.get('game_state', {})
    if (not state.get('in_game') or game.get('class') not in SUPPORTED_CLASSES
            or game.get('room_phase') != 'COMBAT'
            or game.get('screen_type') not in ('NONE', 'HAND_SELECT', 'GRID', 'CARD_REWARD')):
        raise ValueError('仅支持' + '、'.join(SUPPORTED_CLASSES.values())
                         + '的战斗阶段；选奖励牌、路线和商店由你操作。')
    if not state.get('ready_for_command'):
        raise ValueError('游戏动画尚未结算，请稍候。')
    # CommunicationMod's canUse(player, null) flag does not guarantee a target.
    # A half-dead Awakened One is still in combat but cannot be attacked.
    combat = game.get('combat_state', {})
    monsters = combat.get('monsters', [])
    if monsters and not any(m['current_hp'] > 0 and not m['is_gone']
                            and not m['half_dead'] for m in monsters):
        game = copy.deepcopy(game)
        for card in game['combat_state']['hand']:
            if card.get('has_target') and 'is_playable' in card:
                card['is_playable'] = False
    view = {'game': game, 'available_commands': state['available_commands']}
    if 'solver_selection' in game:
        view['live_run'] = game['solver_selection']
    errors = validate(view)  # Production export; no invented independent oracle evidence.
    if errors:
        raise ValueError('随机状态导出不完整：' + repr(errors[:2]))
    streams = game['full_rng_state']['streams']
    view['rng'] = {name: {k: int(streams[name][k]) for k in ('seed0', 'seed1', 'counter')}
                   for name in COMBAT_STREAMS.values()}
    return view


class Advisor:
    def __init__(self):
        from steam.live_search import LiveSearch
        self.search = LiveSearch(REPO / 'runtime', REPO, simulations=8000, boss_multiplier=4.)
        self.pending = None
        self.plan = None
        sys.path.insert(0, str(Path(__file__).parent / 'build/native'))
        import overlay_search
        self.native_search = overlay_search
        self.branches = {}
        self.branch_info = []
        self.selected_branch = 'no-potion'
        self.root = None
        self.root_game = None
        self.searching = []
        self.interrupt = threading.Event()
        self.manual_choice = False

    def phase(self, name):
        if hasattr(self, 'on_phase'):
            self.on_phase(name)

    def search_plan(self, root, budget, slots, credit, index, kill_thieves, generated):
        fast_boss = not slots and self.root_game.get('solver_auto_context', {}).get('boss_fast_finish') is True
        if fast_boss:
            generated = False
        initial_budget = 2000 if fast_boss else budget
        args = (root, initial_budget, slots, credit, index, kill_thieves, generated)
        best = dict(self.native_search.plan(*args, True) if fast_boss else self.native_search.plan(*args))
        if boss_fast_plan(self.root_game, best, slots):
            best['boss_fast_finish'] = True
            return best
        if initial_budget != budget and not self.interrupt.is_set():
            candidate = dict(self.native_search.plan(root, budget, slots, credit, index, kill_thieves, generated, fast_boss))
            if candidate['value'] > best['value']:
                best = candidate
            if boss_fast_plan(self.root_game, best, slots):
                best['boss_fast_finish'] = True
                return best
        # Standard search gets one bounded refinement when its route still loses HP.
        if budget == 8000 and not self.interrupt.is_set() and (
                best['outcome'] != int(self.search.sts.Outcome.PLAYER_VICTORY)
                or health(best, self.root_game, True)['loss'] > 0
                or best.get('used_tail') or best.get('used_fairy')):
            try:
                refined = dict(self.native_search.plan(root, DEEP_BUDGET, slots, credit, index, kill_thieves, generated, fast_boss))
            except RuntimeError:
                if not self.interrupt.is_set():
                    raise
                return best
            if refined['value'] > best['value']:
                best = refined
        if boss_fast_plan(self.root_game, best, slots):
            best['boss_fast_finish'] = True
        return best

    def solve(self, view, budget):
        self.phase('导入战斗状态')
        from sim_patch.parity.adapter import ActionMapper
        s = self.search
        pools={name:[s.sts.CardId(s.sts.card_id_from_name(s.comparator.bridge.CARD_ALIASES.get(card,s.comparator.bridge.enum_key(card))))
                     for card in cards] for name,cards in view['game'].get('solver_card_pools',{}).items()}
        self.native_search.set_card_pools(pools)
        reroll=view['game'].get('solver_discovery_reroll',True)
        if type(reroll) is not bool:
            raise ValueError('无效的 Discovery 随机规则')
        self.native_search.set_discovery_reroll(reroll)
        self.native_search.resume()
        if self.interrupt.is_set():
            self.native_search.cancel()
        s.actions.clear()
        s.pending_multi = None
        self.plan = None
        self.branches = {}
        self.branch_info = []
        self.manual_choice = False
        # Validate the original signed Java seed first. Only the native importer
        # needs uint64 representation; keep the authoritative RNG envelope intact.
        import_view = {**view, 'game': {**view['game'],
                       'seed': int(view['game']['seed']) % (1 << 64)}}
        s.battle = s.comparator.import_battle(import_view, require_search_state=True)
        if view['game']['screen_type'] != 'NONE':
            if 'live_run' not in view:
                raise ValueError('缺少战斗选牌动作导出，请重启游戏加载新版模组。')
            s.native.import_start_selection(s.battle, start_selection(s.comparator, view))
        self.phase('核对游戏与模拟状态')
        comparison = s.comparator.compare_battle(view, s.battle)
        if comparison['differences']:
            raise ValueError('模拟器无法精确导入当前状态：' + repr(comparison['differences'][:2]))
        before = s.comparator.clone_fingerprint(s.battle)
        self.phase('搜索战斗路线')
        started = time.monotonic()
        self.root_game = copy.deepcopy(view['game'])
        credit, _ = future_recovery(self.root_game)
        self.root = s.battle.clone()
        potions = [(slot, potion.get('name', potion['id']))
                   for slot, potion in enumerate(view['game'].get('potions', []))
                   if potion.get('can_use') and potion['id'] not in ('Potion Slot', 'FairyPotion')]
        names = dict(potions)
        if heart_fight(self.root_game):
            self.searching = [(0, 'heart-all', '心脏：全部资源求胜')]
            try:
                self.plan = dict(self.native_search.plan(self.root, budget,
                    list(names), 0, 0, False, True))
                if self.plan['outcome'] != 1 and budget < DEEP_BUDGET and not self.interrupt.is_set():
                    refined = dict(self.native_search.plan(self.root, DEEP_BUDGET,
                        list(names), 0, 0, False, True))
                    if refined['value'] > self.plan['value']:
                        self.plan = refined
                replay = self.root.clone()
                for bits in self.plan['actions']:
                    self.native_search.execute(replay, bits & 0xffffffff)
                if int(replay.outcome) != self.plan['outcome'] or replay.player.cur_hp != self.plan['hp']:
                    raise RuntimeError('心脏路线回放与搜索结果不一致')
                if before != s.comparator.clone_fingerprint(s.battle):
                    raise RuntimeError('搜索修改了输入快照')
                self.plan['seconds'] = round(time.monotonic() - started, 2)
                self.selected_branch = 'heart-all'
                self.branches = {'heart-all': self.plan}
                self.branch_info = [self.branch_summary('heart-all', '心脏：全部资源求胜', self.plan, view)]
                s.actions.extend(self.plan['actions'])
                s.mapper = ActionMapper(s.comparator, s.battle, view)
            finally:
                self.native_search.cancel()
                self.searching = []
            return
        if any(m['id'] in ('Looter', 'Mugger') and not m.get('is_gone')
               for m in view['game']['combat_state']['monsters']):
            self.solve_thief(view, budget, credit, started, before, potions)
            return
        # Every branch searches its own copy of the root on its own thread (the
        # native call releases the GIL). Single-bottle lines are searched
        # speculatively and only kept if the no-potion line cannot win without net
        # HP loss. Bottle sets are searched only if no line so far wins within
        # MULTI_POTION_LOSS effective HP loss.
        fast_boss = view['game'].get('solver_auto_context', {}).get('boss_fast_finish') is True
        stages = [[()]] if fast_boss else [[()] + [(slot,) for slot, _ in potions]]
        self.searching = []
        pool = ThreadPoolExecutor(max_workers=max(1, 2 ** len(potions)))
        try:
            for stage in stages:
                futures = {}
                for slots in stage:
                    index = len(self.searching)
                    branch_id = 'no-potion' if not slots else 'potion-' + '+'.join(map(str, slots))
                    self.searching.append((index, branch_id, '+'.join(names[i] for i in slots) or '不用药'))
                    futures[slots] = pool.submit(self.search_plan, self.root, budget,
                                                 list(slots), credit, index, False,
                                                 budget >= DEEP_BUDGET)
                if () in futures:
                    self.plan = dict(futures[()].result())
                    self.plan['seconds'] = round(time.monotonic() - started, 2)
                    if before != s.comparator.clone_fingerprint(s.battle):
                        raise RuntimeError('搜索修改了输入快照')
                    self.selected_branch = 'no-potion'
                    self.branches = {'no-potion': self.plan}
                    self.branch_info = [self.branch_summary('no-potion', '不主动用药', self.plan, view)]
                    if self.plan.get('boss_fast_finish'):
                        break
                    if (self.plan['outcome'] == int(s.sts.Outcome.PLAYER_VICTORY)
                            and self.branch_info[0]['loss'] == 0
                            and not self.plan.get('used_tail') and not self.plan.get('used_fairy')
                            and not toolbox_selection(view['game'])
                            and not (budget >= DEEP_BUDGET and any(
                                view['game']['potions'][slot]['id'] == 'EntropicBrew'
                                for slot, _ in potions))):
                        break
                for slots, future in futures.items():
                    if slots:
                        self.potion_branch(slots, names, future, started, view, budget)
                if self.interrupt.is_set():
                    break
                good = any(b['available'] and b['won'] and b['loss'] <= MULTI_POTION_LOSS for b in self.branch_info)
                if fast_boss and stage is stages[0] and potions:
                    stages.append([(slot,) for slot, _ in potions])
                singles_done = (not fast_boss and stage is stages[0]) or (
                    fast_boss and len(stages) > 1 and stage is stages[1])
                if singles_done and not self.interrupt.is_set() and not good and len(potions) > 1:
                    combos = [c for n in range(2, len(potions) + 1)
                              for c in itertools.combinations([slot for slot, _ in potions], n)
                              if n == 2 or n == len(potions)]
                    stages.append(combos)
        finally:
            # Unneeded or abandoned branches stop at their next round; do not wait for them.
            self.native_search.cancel()
            pool.shutdown(wait=False)
            self.searching = []
        # Keep no-potion and single bottles in slot order; best bottle sets first after them.
        head, sets = self.branch_info[:1 + len(potions)], self.branch_info[1 + len(potions):]
        sets.sort(key=lambda b: (not b['available'], not b.get('won'), b.get('loss', 0)))
        self.branch_info = head + sets
        if self.plan.get('used_tail') or self.plan.get('used_fairy'):
            available = [(key, plan) for key, plan in self.branches.items()
                         if plan['outcome'] == int(s.sts.Outcome.PLAYER_VICTORY)]
            if available:
                key, plan = max(available, key=lambda pair: pair[1]['value'])
                if plan['value'] > self.plan['value']:
                    self.selected_branch, self.plan = key, plan
                    self.branch_info.sort(key=lambda branch: branch['id'] != key)
        s.actions.extend(self.plan['actions'])
        s.mapper = ActionMapper(s.comparator, s.battle, view)

    def solve_thief(self, view, budget, credit, started, before, potions):
        """Show forced-kill and HP-preserving lines for the player to choose from."""
        from sim_patch.parity.adapter import ActionMapper

        s = self.search
        self.manual_choice = True
        specs = [('kill-thief', '不交药击杀 · 追回金币', True),
                 ('kill-thief-potion', '用药击杀 · 追回金币', True),
                 ('min-loss', '战损最少（可逃跑）', False)]
        slots = [slot for slot, _ in potions]
        choices = [()] + [combination for count in range(1, len(slots)+1)
                          for combination in itertools.combinations(slots, count)]
        jobs = [(branch_id, name, forced, option)
                for branch_id, name, forced in specs for option in choices
                if (branch_id != 'kill-thief' or not option)
                and (branch_id != 'kill-thief-potion' or option)]
        searching = [(index, branch_id, name + (' / 用药' if option else ' / 不用药'))
                     for index, (branch_id, name, _, option) in enumerate(jobs)]
        self.searching = searching[:1]
        pool = ThreadPoolExecutor(max_workers=min(4, len(jobs)))
        try:
            self.phase('优先深搜：不交药击杀盗贼')
            first = pool.submit(self.search_plan, self.root, max(budget, DEEP_BUDGET),
                                [], credit, 0, True, False)
            best = {}
            futures = [first]
            for index, (branch_id, name, forced, option) in enumerate(jobs):
                if index == 1:
                    self.phase('比较用药击杀与最低战损路线')
                    self.searching = searching
                    futures.extend(pool.submit(self.search_plan, self.root, budget,
                                               list(other_option), credit, other_index, other_forced,
                                               budget >= DEEP_BUDGET and bool(other_option))
                                   for other_index, (_, _, other_forced, other_option) in enumerate(jobs[1:], 1))
                future = futures[index]
                try:
                    plan = dict(future.result())
                    if plan['outcome'] != int(s.sts.Outcome.PLAYER_VICTORY):
                        continue
                    if forced and plan['escaped_thieves']:
                        continue
                    plan['seconds'] = round(time.monotonic() - started, 2)
                    plan['potion_slots'] = option
                    used = any(s.sts.SearchAction.from_bits(bits & 0xffffffff).action_type
                               == s.sts.SearchActionType.POTION for bits in plan['actions'])
                    if branch_id == 'kill-thief' and used:
                        raise ValueError('不交药路线包含用药动作')
                    if branch_id == 'kill-thief-potion' and not used:
                        continue
                    old = best.get(branch_id)
                    if old is None or (plan['value'], -len(option)) > (old['value'], -len(old['potion_slots'])):
                        best[branch_id] = plan
                        self.branches[branch_id] = plan
                        label = name + (' · 用药' if used and branch_id == 'min-loss' else '')
                        summary = self.branch_summary(branch_id, label, plan, view)
                        self.branch_info = [b for b in self.branch_info if b['id'] != branch_id] + [summary]
                except (ValueError, RuntimeError):
                    pass
            self.branch_info = []
            for branch_id, name, forced in specs:
                if branch_id == 'kill-thief-potion' and not slots:
                    continue
                plan = best.get(branch_id)
                if plan is not None:
                    self.branches[branch_id] = plan
                    used = any(s.sts.SearchAction.from_bits(bits & 0xffffffff).action_type
                               == s.sts.SearchActionType.POTION for bits in plan['actions'])
                    label = name + (' · 用药' if used and branch_id == 'min-loss' else '')
                    self.branch_info.append(self.branch_summary(branch_id, label, plan, view))
                else:
                    self.branch_info.append({'id': branch_id, 'name': name, 'available': False,
                                             'message': '本轮未找到完整路线'})
        finally:
            self.native_search.cancel()
            pool.shutdown(wait=False)
            self.searching = []
        if before != s.comparator.clone_fingerprint(s.battle):
            raise RuntimeError('搜索修改了输入快照')
        by_id = {branch['id']: branch for branch in self.branch_info}
        kill = by_id['kill-thief']
        minimum = by_id['min-loss']
        if kill['available']:
            selected = 'kill-thief'
        elif by_id.get('kill-thief-potion', {}).get('available'):
            selected = 'kill-thief-potion'
        elif minimum['available']:
            selected = 'min-loss'
        else:
            raise ValueError('本轮尚无可用路线，请增加搜索预算')
        self.branch_info.sort(key=lambda branch: branch['id'] != selected)
        self.selected_branch = selected
        self.plan = self.branches[selected]
        s.actions.extend(self.plan['actions'])
        s.mapper = ActionMapper(s.comparator, s.battle, view)

    def potion_branch(self, slots, names, future, started, view, budget):
        """Validate initial bottles; deep plans may also use generated bottles."""
        s = self.search
        branch_id = 'potion-' + '+'.join(map(str, slots))
        name = ' + '.join(names[i] for i in slots)
        try:
            plan = dict(future.result())
            plan['seconds'] = round(time.monotonic() - started, 2)
            replay = self.root.clone()
            uses = []
            generated_uses = []
            initial_slots_used = set()
            for step, bits in enumerate(plan['actions']):
                action = s.sts.SearchAction.from_bits(bits & 0xffffffff)
                if action.action_type == s.sts.SearchActionType.POTION:
                    slot = int(action.source_idx)
                    generated = budget >= DEEP_BUDGET and (
                        view['game']['potions'][slot]['id'] == 'Potion Slot'
                        or replay.potions[slot] != self.root.potions[slot]
                        or slot in initial_slots_used)
                    if generated:
                        generated_uses.append({'slot': slot, 'turn': int(replay.turn)+1, 'step': step+1})
                    else:
                        if slot not in slots or slot in initial_slots_used:
                            raise ValueError('路线使用了未授权的药水')
                        initial_slots_used.add(slot)
                        uses.append({'slot': slot, 'turn': int(replay.turn)+1, 'step': step+1})
                self.native_search.execute(replay, int(action.bits))
            if not uses:
                self.branch_info.append({'id': branch_id, 'name': name, 'available': False,
                                         'message': '本轮搜索未找到使用路线'})
                return
            plan['potion_use'] = uses[0]
            plan['potion_uses'] = uses
            plan['generated_potion_uses'] = generated_uses
            plan['potion_gain'] = sum(
                replay.potions[slot] != self.root.potions[slot]
                for slot, potion in enumerate(view['game']['potions'])
                if potion['id'] == 'Potion Slot')
            self.branches[branch_id] = plan
            # Compact for the panel row: bottles used on the same turn share one "Tn".
            turns = {}
            for u in uses:
                turns.setdefault(u['turn'], []).append(names[u['slot']])
            label = ' '.join(f"T{turn} " + '+'.join(bottles) for turn, bottles in turns.items())
            self.branch_info.append(self.branch_summary(branch_id, label, plan, view))
        except (ValueError, RuntimeError) as error:
            self.branch_info.append({'id': branch_id, 'name': name, 'available': False,
                                     'message': str(error)})

    def progress(self):
        """Live per-branch search counters while solve() runs; empty otherwise."""
        rows = []
        accepted = {branch['id'] for branch in list(self.branch_info) if branch['available']}
        for index, branch_id, name in list(self.searching):
            p = self.native_search.progress(index)
            rows.append({'id': branch_id, 'name': name, 'simulations': p['simulations'], 'turn': p['turn'],
                         'best_hp': p['best_hp'], 'done': p['done'], 'ready': branch_id in accepted})
        return rows

    def branch_summary(self, branch_id, name, plan, view):
        won = plan['outcome'] == int(self.search.sts.Outcome.PLAYER_VICTORY)
        if 'potion_uses' not in plan:
            # Thief searches also consume bottles; never infer use from branch names.
            used = set()
            for bits in plan['actions']:
                action = self.search.sts.SearchAction.from_bits(bits & 0xffffffff)
                if action.action_type == self.search.sts.SearchActionType.POTION:
                    slot = int(action.source_idx)
                    if view['game']['potions'][slot]['id'] != 'Potion Slot':
                        used.add(slot)
            plan['potion_uses'] = [{'slot': slot} for slot in sorted(used)]
        return {'id': branch_id, 'name': name, 'available': True,
                'won': won, 'potion_gain': plan.get('potion_gain', 0),
                'potion_uses': plan.get('potion_uses', []),
                **health(plan, view['game'], won)}

    def choose(self, view, branch_id):
        from sim_patch.parity.adapter import ActionMapper
        if branch_id not in self.branches:
            raise ValueError('所选药水路线不可用')
        comparison = self.search.comparator.compare_battle(view, self.root)
        if comparison['differences']:
            raise ValueError('局面已经变化，请重新计算药水路线。')
        self.selected_branch = branch_id
        self.plan = self.branches[branch_id]
        s = self.search
        s.battle = self.root.clone()
        s.actions.clear()
        s.actions.extend(self.plan['actions'])
        s.pending_multi = None
        s.mapper = ActionMapper(s.comparator, s.battle, view)

    def advance(self, view, budget):
        # Same acceptance logic as LiveSearch, with production RNG validation in
        # view_from_state instead of the independent test-oracle requirement.
        s, action = self.search, self.pending
        if not s.actions:
            raise ValueError('没有可继续执行的路线')
        if action is None:
            if s.pending_multi is not None:
                if view['game']['screen_type'] != 'NONE':
                    return
                if s.pending_multi['remaining']:
                    raise ValueError('战斗选牌提前关闭')
                action = s.pending_multi['action']
            else:
                comparison = s._compare(view)
                if comparison['differences']:
                    raise ValueError('确认选牌后状态偏离预测：' + repr(comparison['differences'][:4]))
                return
        if (int(action.bits) & 0xffffffff) != (s.actions[0] & 0xffffffff):
            raise ValueError('执行动作与路线不一致')
        self.native_search.execute(s.battle,int(action.bits))
        s.actions.popleft()
        s.pending_multi = None
        comparison = s._compare(view)
        if comparison['differences']:
            raise ValueError('实际状态偏离预测；已停止执行。' + repr(comparison['differences'][:2]))
        if s.battle.input_state == s.sts.InputState.CARD_SELECT:
            # Toolbox can generate a card and immediately open Gambling Chip,
            # with no normal-play frame in which LiveSearch refreshes identities.
            # Only bind observed cards after the complete state comparison passes.
            s.mapper.refresh(s.battle, view)

    def result(self, view):
        s = self.search
        if not s.actions:
            return {'status': 'complete', 'message': '当前路线已执行完毕。'}
        # next_action prepares the first multi-select click and mutates its
        # transport queue. That preparation has not yet executed in the game.
        branch_current = len(s.actions) == len(self.plan['actions']) and s.pending_multi is None
        self.pending, command = s.next_action(view)
        if command.split()[0] not in ('play', 'end', 'potion', 'choose', 'confirm', 'skip'):
            raise ValueError('拒绝非战斗命令')
        replay = s.battle.clone()
        route = []
        game = view['game']
        cards = list(game.get('deck', []))
        for pile in ('hand', 'draw_pile', 'discard_pile', 'exhaust_pile'):
            cards.extend(game['combat_state'][pile])
        names = {s.comparator.bridge.CARD_ALIASES.get(c['id'], s.comparator.bridge.enum_key(c['id'])):
                 c.get('name', c['id']) for c in cards}
        monsters = {int(s.comparator.bridge.monster_snapshot(m)['id']): m.get('name', m['id'])
                    for m in game['combat_state']['monsters'] if not m.get('is_gone')}
        for bits in s.actions:
            a = s.sts.SearchAction.from_bits(bits & 0xffffffff)
            if not a.is_valid(replay):
                raise ValueError('路线回放出现非法动作')
            row = {'turn': int(replay.turn) + 1, 'text': a.desc(replay)}
            if a.action_type == s.sts.SearchActionType.CARD:
                card = replay.hand[int(a.source_idx)]
                row.update(kind='card', card=card.id.name, upgrades=int(card.upgrade_count))
                row['label'] = names.get(card.id.name, card.id.name)
                if card.upgrade_count and not row['label'].endswith('+'):
                    row['label'] += '+'
                if card.requires_target:
                    target = int(replay.monsters[int(a.target_idx)].id)
                    row['label'] += ' → ' + monsters.get(target, '敌人 ' + str(int(a.target_idx)+1))
            elif a.action_type == s.sts.SearchActionType.END_TURN:
                row.update(kind='end', text='结束回合')
            elif a.action_type == s.sts.SearchActionType.POTION:
                row.update(kind='potion', text='药水：' + a.desc(replay))
                slot = int(a.source_idx)
                original = game['potions'][slot]
                row['label'] = '使用 ' + (original.get('name', '药水')
                    if replay.potions[slot] == self.root.potions[slot] and original['id'] != 'Potion Slot'
                    else a.desc(replay))
            else:
                row.update(kind='select', text='选牌：' + a.desc(replay))
                if (a.action_type == s.sts.SearchActionType.SINGLE_CARD_SELECT
                        and s.native.selection_info(replay)['task'] == 'HEADBUTT'):
                    card = replay.discard_pile[int(a.select_idx)]
                    row['label'] = '弃牌置顶：' + names.get(card.id.name, card.id.name)
            before_gold = int(replay.player.gold)
            before_max_hp = int(replay.player.max_hp)
            before_revives = self.native_search.revival_inventory(replay)
            self.native_search.execute(replay,int(a.bits))
            after_revives = self.native_search.revival_inventory(replay)
            spent_revives = []
            if after_revives[0] < before_revives[0]:
                spent_revives.append('消耗蜥蜴尾巴')
            if after_revives[1] < before_revives[1]:
                spent_revives.append(f'消耗小精灵×{before_revives[1]-after_revives[1]}')
            if spent_revives:
                row['label'] = '【' + '；'.join(spent_revives) + '】' + row.get('label', row['text'])
            benefit = None
            if row.get('card') == 'HAND_OF_GREED' and int(replay.player.gold) > before_gold:
                benefit = f"打钱 +{int(replay.player.gold) - before_gold}金"
            elif row.get('card') == 'FEED' and int(replay.player.max_hp) > before_max_hp:
                benefit = f"狂宴 +{int(replay.player.max_hp) - before_max_hp}生命上限"
            if benefit:
                row['finisher'] = benefit
                row['label'] = f"【{benefit}】" + row['label']
            route.append(row)
            if len(route) >= 160:
                break  # ponytail: cap displayed rows; full executable plan is retained.
        won = int(self.plan['outcome']) == int(s.sts.Outcome.PLAYER_VICTORY)
        healing = health(self.plan, self.root_game, won)
        return {'status': 'ready', 'command': command, 'route': route,
                **healing,
                'outcome': int(self.plan['outcome']),
                'won': won,
                'boss_fast_finish': self.plan.get('boss_fast_finish', False),
                'seconds': self.plan['seconds'], 'simulations': self.plan['simulations'],
                'remaining': len(s.actions), 'branch': self.selected_branch,
                'branches': self.branch_info,
                'auto_recommendation': recommend(self.root_game, self.branch_info),
                'branch_current': branch_current,
                'manual_choice': self.manual_choice,
                'needs_confirmation': self.manual_choice or (self.selected_branch == 'no-potion'
                    and any(b['id'] != 'no-potion' and b['available'] for b in self.branch_info)
                    and (toolbox_selection(view['game'])
                         or self.plan['outcome'] != int(s.sts.Outcome.PLAYER_VICTORY)
                         or healing['loss'] > 0
                         or any(b['available'] and b.get('potion_gain', 0) > 0
                                for b in self.branch_info)))}

    def handle(self, request):
        view = view_from_state(request['state'])
        budget = request.get('budget', 8000)
        if type(budget) is not int or budget not in (2000, 8000, DEEP_BUDGET):
            raise ValueError('无效搜索预算')
        if request['op'] == 'solve':
            self.solve(view, budget)
        elif request['op'] == 'advance':
            self.advance(view, budget)
        elif request['op'] == 'choose':
            self.choose(view, request.get('branch'))
        else:
            raise ValueError('未知操作')
        return self.result(view)


def report_progress(advisor, connection, request, stop, send_lock):
    """Stream search counters to the panel while a solve runs (opt-in per request)."""
    while not stop.wait(.25):
        rows = advisor.progress()
        if rows:
            with send_lock:
                connection.send({'id': request['id'], 'status': 'progress', 'branches': rows})


def worker(connection, interrupt):
    try:
        init_started = time.monotonic()
        advisor = Advisor()
        init_seconds = time.monotonic() - init_started
        send_lock = threading.Lock()
        while True:
            request = connection.recv()
            request_started = time.monotonic()
            timings = []
            phase_started = request_started
            phase_name = None
            def phase(name):
                nonlocal phase_started, phase_name
                now = time.monotonic()
                if phase_name is not None:
                    timings.append({'phase': phase_name, 'seconds': round(now-phase_started, 3)})
                phase_name, phase_started = name, now
                if request.get('progress'):
                    with send_lock:
                        connection.send({'id': request['id'], 'status': 'progress',
                                         'phase': name, 'worker_init_seconds': round(init_seconds, 3)})
            advisor.on_phase = phase
            phase('校验请求与随机状态')
            interrupt.clear()
            advisor.interrupt = interrupt
            stop = threading.Event()
            reporter = None
            def watch_interrupt():
                if interrupt.wait() and not stop.is_set():
                    advisor.native_search.cancel()
            watcher = threading.Thread(target=watch_interrupt, daemon=True)
            watcher.start()
            if request.get('progress') and request.get('op') == 'solve':
                reporter = threading.Thread(target=report_progress, daemon=True,
                                            args=(advisor, connection, request, stop, send_lock))
                reporter.start()
            try:
                result = advisor.handle(request)
                if request['op'] in ('solve', 'choose'):
                    result['_plan'] = advisor.plan
            except Exception as error:
                traceback.print_exc(file=sys.stderr)
                result = {'status': 'error', 'message': str(error), 'traceback': traceback.format_exc()}
            finally:
                was_interrupted = interrupt.is_set()
                stop.set()
                interrupt.set()
                watcher.join()
                if reporter:
                    reporter.join()  # No progress message may follow the final reply.
            result['interrupted'] = result.get('status') == 'ready' and was_interrupted
            timings.append({'phase': phase_name, 'seconds': round(time.monotonic()-phase_started, 3)})
            result['timings'] = timings
            result['worker_init_seconds'] = round(init_seconds, 3)
            result['worker_seconds'] = round(time.monotonic()-request_started, 3)
            connection.send({'id': request['id'], **result})
    except EOFError:
        pass
    except Exception:
        traceback.print_exc(file=sys.stderr)


def worker_version():
    """Detect a replaced build without hashing it on every frame or losing warm workers."""
    here = Path(__file__).resolve().parent
    paths = [Path(__file__).resolve(), here / 'recovery.py', here / 'diagnostics.py', here / 'auto_policy.py']
    paths.extend(here / 'build/native' / ('overlay_search'+suffix)
                 for suffix in importlib.machinery.EXTENSION_SUFFIXES
                 if (here / 'build/native' / ('overlay_search'+suffix)).exists())
    return tuple((str(path), path.stat().st_ino, path.stat().st_mtime_ns,
                  path.stat().st_size) for path in paths)


def start_worker(process):
    if sys.platform != 'win32':
        process.start()
        return
    # CPython bpo-34780: a child inheriting stdin while another thread reads
    # that pipe can block before Python initialization. Workers use Pipe, not
    # stdin. Change only the Win32 startup handle; our sys.stdin keeps its fd.
    import ctypes
    import msvcrt
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.GetStdHandle.argtypes = [ctypes.c_ulong]
    kernel.GetStdHandle.restype = ctypes.c_void_p
    kernel.SetStdHandle.argtypes = [ctypes.c_ulong, ctypes.c_void_p]
    kernel.SetStdHandle.restype = ctypes.c_int
    previous = kernel.GetStdHandle(-10)  # STD_INPUT_HANDLE
    with open(os.devnull, 'rb') as null:
        if not kernel.SetStdHandle(-10, msvcrt.get_osfhandle(null.fileno())):
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            process.start()
        finally:
            if not kernel.SetStdHandle(-10, previous):
                raise ctypes.WinError(ctypes.get_last_error())


def serve(report_path=None):
    inbox = queue.Queue()
    data_dir = Path(os.environ.get('STS_SOLVER_DATA', Path(__file__).parent/'runtime'))
    report_path = Path(report_path or data_dir/'combat-reports.sqlite3')
    reports = AsyncReports(report_path)
    error_log = data_dir/'backend.log'

    def stderr_since(offset):
        if not error_log.exists():
            return ''
        with error_log.open('rb') as stream:
            stream.seek(offset)
            return stream.read().decode('utf-8', errors='replace')

    def read():
        try:
            for line in sys.stdin:
                inbox.put(json.loads(line))
        finally:
            inbox.put(None)

    threading.Thread(target=read, daemon=True).start()
    process = connection = active = interrupt = None
    last_solve_ready = None
    loaded_version = None
    ctx = mp.get_context('spawn')

    def stop():
        nonlocal process, connection, active, interrupt
        if process:
            if process.is_alive():
                process.terminate()
            process.join(2)
            if process.is_alive():
                process.kill()
                process.join()
        if connection:
            connection.close()
        process = connection = active = interrupt = None

    def emit(value, state=None):
        if value.get('status') != 'progress':
            reports.record('reply', value, state)
        elif 'phase' in value:
            reports.record('phase', value, state)
        value = {k: v for k, v in value.items() if k != '_plan'}
        print(json.dumps(value, ensure_ascii=False), flush=True)

    def start():
        nonlocal process, connection, loaded_version, interrupt
        if process is None:
            loaded_version = worker_version()
            connection, child = ctx.Pipe()
            interrupt = ctx.Event()
            process = ctx.Process(target=worker, args=(child, interrupt), daemon=True)
            start_worker(process)
            child.close()

    try:
        # Warm worker: engine import happens before the first fight, not during it.
        start()
        while True:
            try:
                request = inbox.get(timeout=.03)
            except queue.Empty:
                request = 'poll'
            if request is None:
                break
            if request != 'poll':
                if request.get('op') == 'export_debug':
                    try:
                        reports.flush()
                        target = export_debug(report_path, report_path.parent/'bug-reports', request.get('metadata'))
                        answer = {'status': 'debug_export', 'file': target.name}
                    except Exception as failure:
                        answer = {'status': 'debug_error', 'message': str(failure)}
                    print(json.dumps(answer, ensure_ascii=False), flush=True)
                    continue
                reports.record('request', request, request.get('state'))
                if request.get('op') == 'diagnostic':
                    continue  # UI event, no worker mutation or reply/active-ID change.
                if request.get('op') == 'cancel':
                    if active and active[4] == 'solve':
                        interrupt.set()
                        active = (*active[:4], active[4], request['id'])
                    else:
                        # Executing or idle work has no partial search to retain.
                        if active:
                            stop()
                            start()
                        if not active and last_solve_ready is not None:
                            emit({**last_solve_ready, 'id': request['id'], 'interrupted': True})
                        else:
                            emit({'id': request['id'], 'status': 'cancelled'})
                    continue
                last_solve_ready = None
                received_at = time.monotonic()
                if request.get('progress'):
                    emit({'id': request['id'], 'status': 'progress', 'phase': '后台已接收，准备工作进程'}, request.get('state'))
                if active:
                    stop()
                if request.get('op') == 'solve' and worker_version() != loaded_version:
                    stop()  # A plan starts against one build; never switch midway through it.
                start()
                if request.get('progress'):
                    emit({'id': request['id'], 'status': 'progress', 'phase': '等待工作进程（启动或加载引擎）'}, request.get('state'))
                log_offset = error_log.stat().st_size if error_log.exists() else 0
                connection.send(request)
                active = (request['id'], received_at, request.get('state'), log_offset, request.get('op'), None)
            while active and connection.poll():
                completed_active = active
                active_state = active[2]
                log_offset = active[3]
                try:
                    result = connection.recv()
                except EOFError:
                    result = {'id': active[0], 'status': 'error', 'message': '求解进程意外退出；请重新计算。',
                              'exitcode': process.exitcode}
                    stop()
                if result.get('status') != 'progress':
                    result['backend_seconds'] = round(time.monotonic()-completed_active[1], 3)
                    if completed_active[5] is not None:
                        result['id'] = completed_active[5]
                        if result.get('status') == 'ready':
                            result['interrupted'] = True
                    if completed_active[4] == 'solve' and result.get('status') == 'ready':
                        last_solve_ready = result
                    active = None
                if result.get('status') == 'error':
                    result['stderr'] = stderr_since(log_offset)
                emit(result, active_state)
            if active and (not process.is_alive() or time.monotonic() - active[1] > 90):
                request_id = active[0]
                active_state = active[2]
                exitcode = process.exitcode
                log_offset = active[3]
                stop()
                emit({'id': request_id, 'status': 'error', 'message': '搜索超时或引擎退出；请降低预算后重算。',
                      'exitcode': exitcode, 'stderr': stderr_since(log_offset)}, active_state)
    finally:
        stop()
        reports.close()


if __name__ == '__main__':
    serve()
