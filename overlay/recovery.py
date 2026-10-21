"""Future recovery credit; victory healing is projected by the native engine."""


def future_recovery(game):
    relics = {r['id'] for r in game.get('relics', [])}
    if 'Mark of the Bloom' in relics:
        return 0, []
    amount, notes = 0, []
    if 'Blood Vial' in relics:
        value = 3 if 'Magic Flower' in relics else 2
        amount += value
        notes.append(f'下场小血瓶 +{value}')
    rooms = game.get('solver_next_rooms', [])
    rest = bool(rooms) and all(room == 'R' for room in rooms)
    rest |= 'R' in rooms and game.get('solver_next_rest', False)
    if 'Eternal Feather' in relics and rest:
        value = len(game.get('deck', [])) // 5 * 3
        amount += value
        notes.append(f'下层火堆羽毛 +{value}')
    return amount, notes


def health(plan, game, won):
    future, notes = future_recovery(game) if won else (0, [])
    raw = int(plan['hp'])
    post = int(plan['post_hp'])
    effective = min(int(plan['max_hp']), post + future) if won else raw
    # A permanent curse (Writhing Mass Implant) counts as HP lost; the panel shows notes[0].
    curse = int(plan.get('curse_hp', 0)) if won else 0
    if curse:
        notes = [f'被塞诅咒 计 {curse} 血'] + notes
    lost_gold = int(plan.get('lost_gold', 0)) if won else 0
    if lost_gold:
        notes = [f'盗贼逃跑，损失约 {lost_gold} 金'] + notes
    revivals = (['消耗蜥蜴尾巴'] if plan.get('used_tail') else [])
    if plan.get('used_fairy'):
        revivals.append(f"消耗小精灵×{int(plan['used_fairy'])}")
    if revivals:
        notes = ['；'.join(revivals)] + notes
    return dict(hp=post, raw_hp=raw, effective_hp=effective,
                new_permanent_curse=bool(won and plan.get('new_permanent_curse')),
                loss=max(0, int(game['current_hp']) - effective) + curse,
                raw_loss=max(0, int(game['current_hp']) - raw),
                recovery=post-raw, recovery_notes=notes, lost_gold=lost_gold,
                used_tail=bool(plan.get('used_tail')), used_fairy=int(plan.get('used_fairy', 0)))


def incense_targets(game):
    """Incense Burner counters worth ending this fight with (user rule, 2026-10-15).

    Counter c gives Intangible on turn 6-c of the next fight (5 = turn 1, 0 = turn 6).
    The game never rolls the same elite twice in a row (populateMonsterList), so an
    elite is possible next exactly when it was not the last elite of the act.
    """
    if any(m['id'] == 'CorruptHeart' for m in game['combat_state']['monsters']):
        return []
    act, boss, last = game.get('act', 1), game.get('act_boss'), game.get('solver_last_elite')
    if game.get('room_type') == 'MonsterRoomBoss' or act >= 3:
        return [4]  # next act, act 3 elites, Shield/Spear into the Heart
    if act == 1:
        return [3] if boss == 'Slime Boss' or last != 'Gremlin Nob' else [3, 4]
    if boss == 'Automaton' and not game.get('solver_elite_ahead', True):
        return [0]
    return [5] if last != 'Slavers' else [4]


def carry_weight(game):
    """Weight of the cross-fight counters other than Incense Burner (user rule, 2026-10-21).

    x2 when the next fight can be an elite, x3 when it is a Boss. Without map data (headless) x1.
    """
    if any(m['id'] == 'CorruptHeart' for m in game['combat_state']['monsters']):
        return 1.0
    act, room = game.get('act', 1), game.get('room_type')
    if act >= 4:
        return 3.0 if room == 'MonsterRoomElite' else 1.0  # Shield and Spear, then the Heart
    if room == 'MonsterRoomBoss':
        if act < 3:
            return 1.0  # the next act opens with hallway fights
        # A20 Beyond: two candidates remain during Boss #1, so Boss #2 is next (SolverMod.bossFastFinish).
        context = game.get('solver_auto_context') or {}
        if game.get('ascension_level', 0) >= 20 and context.get('boss_candidates_remaining', 0) > 1:
            return 3.0
        keys = game.get('keys') or {}
        return 2.0 if all(keys.get(k) for k in ('ruby', 'emerald', 'sapphire')) else 1.0
    kinds = game.get('solver_next_fights', [])
    return 3.0 if 'boss' in kinds else 2.0 if 'elite' in kinds else 1.0
