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
                loss=max(0, int(game['current_hp']) - effective) + curse,
                raw_loss=max(0, int(game['current_hp']) - raw),
                recovery=post-raw, recovery_notes=notes, lost_gold=lost_gold,
                used_tail=bool(plan.get('used_tail')), used_fairy=int(plan.get('used_fairy', 0)))
