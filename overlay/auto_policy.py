"""Explainable potion choice over replay-validated routes, never executable commands."""
from collections import deque


def key(value):
    return ''.join(c for c in value.lower() if c.isalnum())


def assess_deck(game):
    """Conservative weakness screen; these scores are not simulated win probabilities."""
    context = game.get('solver_auto_context', {})
    deck = context.get('deck', game.get('deck', []))
    ids = {key(c['id']) for c in deck}
    n = max(1, len(deck))
    # ponytail: static three-energy/draw-five profile misses combos and relic engines;
    # replace with sampled encounter rollouts once future-battle import is validated.
    damage = block = 0.0
    for c in deck:
        cost = max(1, c.get('cost', 1))
        hits = {'twinstrike': 2, 'swordboomerang': 3 + bool(c.get('upgrades')),
                'pummel': 4 + bool(c.get('upgrades'))}.get(key(c['id']), 1)
        damage += max(0, c.get('base_damage', 0)) * hits / cost
        block += max(0, c.get('base_block', 0)) / cost
    damage = round(damage * min(1, 5 / n), 1)
    block = round(block * min(1, 5 / n), 1)
    scaling = bool(ids & {'demonform', 'inflame', 'spotweakness', 'limitbreak'})
    scaling |= 'barricade' in ids and bool(ids & {'entrench', 'bodyslam'})
    scaling |= 'searingblow' in ids and any(key(c['id']) == 'searingblow' and c.get('upgrades', 0) >= 4 for c in deck)
    aoe = bool(ids & {'immolate', 'whirlwind', 'cleave', 'combust', 'reaper'})
    defense = block >= 6 or bool(ids & {'impervious', 'disarm', 'powerthrough', 'flamebarrier'})
    defense |= 'feelnopain' in ids and bool(ids & {'corruption', 'secondwind', 'fiendfire'})
    act = min(4, max(1, game.get('act', 1)))
    nodes = {(v['x'], v['y']): v for v in game.get('map', [])}
    start = (context.get('x'), context.get('y'))
    queue, seen, distance = deque([(start, 0)]), set(), None
    while queue:
        pos, steps = queue.popleft()
        if pos in seen:
            continue
        seen.add(pos)
        node = nodes.get(pos, {})
        if steps and node.get('symbol') == 'E':
            distance = steps
            break
        queue.extend(((c['x'], c['y']), steps + 1) for c in node.get('children', []))
    boss_distance = max(0, 15 - context['y']) if isinstance(context.get('y'), int) else None
    # At the boss there is no reason to reserve a bottle for that same fight.
    room = context.get('room', '')
    near_elite = distance is not None and distance <= 3
    near_boss = boss_distance is not None and 0 < boss_distance <= 4 and 'Boss' not in room
    weaknesses = []
    if damage < (9 if act == 1 else 13): weaknesses.append('爆发不足')
    if act >= 2 and not defense: weaknesses.append('防御不足')
    if not aoe: weaknesses.append('群攻不足')
    if not scaling: weaknesses.append('成长不足')
    threatened = (near_elite and bool(set(weaknesses) - {'成长不足'})) or (near_boss and bool(weaknesses))
    unknown = not context or not deck
    return dict(damage_per_draw=damage, block_per_draw=block, scaling=scaling, aoe=aoe,
                defense=defense, weaknesses=weaknesses, elite_distance=distance,
                boss_distance=boss_distance, boss=context.get('boss', ''),
                reserve=bool(threatened or unknown), unknown=unknown,
                summary=('牌组资料不足，保守留药' if unknown else
                         ('近期精英/BOSS：' if near_elite or near_boss else '牌组评估：')
                         + ('、'.join(weaknesses) or '未发现明显短板')) + '（启发式）')


def recommend(game, branches):
    profile = assess_deck(game)
    context = game.get('solver_auto_context', {})
    relics = {r['id'] for r in game.get('relics', [])}
    probability = context.get('potion_drop_probability')
    if 'Sozu' in relics: probability = 0
    if 'White Beast Statue' in relics and 'Sozu' not in relics: probability = 1
    if 'Boss' in context.get('room', ''): probability = 0
    potions = game.get('potions', [])
    full = bool(potions) and all(p['id'] != 'Potion Slot' for p in potions)
    supply = full and isinstance(probability, (int, float)) and probability >= .6
    winners = [b for b in branches if b.get('available') and b.get('won')]
    if not winners:
        return dict(branch=None, reason='未找到可验证的获胜路线，停止自动', assessment=profile)
    if any(m['id'] == 'CorruptHeart' for m in game.get('combat_state', {}).get('monsters', [])):
        return dict(branch=winners[0]['id'], reason='心脏：全部资源可用，找到存活胜线即执行', assessment=profile)
    baseline = min((b for b in winners if not b.get('potion_uses')), key=lambda b: b['loss'], default=None)
    strong = {'GhostInAJar', 'DuplicationPotion', 'CultistPotion',
              'Swift Potion', "Gambler's Brew", 'DistilledChaos',
              'Dexterity Potion', 'Strength Potion', 'PowerPotion',
              'SkillPotion', 'AttackPotion', 'ColorlessPotion', 'EntropicBrew', 'SneckoOil'}

    def price(branch):
        total = 0
        for use in branch.get('potion_uses', []):
            potion = potions[use['slot']]['id']
            valuable = potion in strong or (potion == 'Ancient Potion' and game.get('act') == 2)
            covers_gap = ((potion in {'Fire Potion', 'FlexPotion', 'Energy Potion'} and '爆发不足' in profile['weaknesses'])
                          or (potion == 'Explosive Potion' and '群攻不足' in profile['weaknesses'])
                          or (potion in {'Block Potion', 'SpeedPotion'} and '防御不足' in profile['weaknesses']))
            reserve = profile['reserve'] and (valuable or covers_gap)
            # Full inventory discounts strong bottles too; otherwise it hoards
            # Strength/Dexterity even when the next drop is likely to be wasted.
            total += 20 if reserve else (5 if valuable else 3) if supply else 10 if valuable else 5
        return total

    def score(b):
        # A winning line consuming a revival is not equivalent to preserving it.
        return b['loss'] + price(b) + (game.get('max_hp', 80) + 20) * (bool(b.get('used_tail')) + b.get('used_fairy', 0))

    chosen = min(winners, key=lambda b: (score(b), b['loss'], len(b.get('potion_uses', []))))
    saved = round(baseline['loss'] - chosen['loss'], 1) if baseline else None
    if chosen.get('potion_uses'):
        reason = '无药未找到胜线，优先保命' if baseline is None else f'省 {saved:g} 血；用药门槛 {price(chosen)}'
        if supply: reason += '；满栏且掉药概率高'
    else:
        reason = '保留药水；收益未超过用药/保留价值'
    if chosen.get('used_tail') or chosen.get('used_fairy'):
        reason = '保命路线：消耗' + ('尾巴 ' if chosen.get('used_tail') else '') + (
            f"小精灵×{chosen['used_fairy']}" if chosen.get('used_fairy') else '')
    return dict(branch=chosen['id'], reason=reason, saved_hp=saved,
                drop_probability=probability, assessment=profile)
