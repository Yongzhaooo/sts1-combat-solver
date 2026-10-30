"""Reconstruct observed choices without inferring missing continuation inputs."""


def headbutt_selection(comparator, view):
    game, live = view['game'], view['live_run']
    screen, combat = game['screen_state'], game['combat_state']
    if (screen.get('selected_cards') or screen.get('confirm_up') or screen.get('num_cards') != 1
            or any(screen.get(k) for k in ('for_upgrade', 'for_transform', 'for_purge', 'any_number'))):
        raise ValueError('Headbutt requires an untouched single-card discard picker')
    options = [c['uuid'] for c in screen['cards']]
    discard = [c['uuid'] for c in combat['discard_pile']]
    if len(options) < 2 or len(set(options)) != len(options) or set(options) != set(discard):
        raise ValueError('Headbutt picker differs from the original discard pile')
    return dict(task='HEADBUTT', queue=[resolving_card(comparator, view, 'Headbutt')])


def resolving_card(comparator, view, name):
    """The UseCardAction still queued behind a picker its own card opened.

    The card left the hand when it was played but reaches its pile only when
    UseCardAction resolves after the pick, so the import must finish it too.
    """
    live, combat = view['live_run'], view['game']['combat_state']
    actions = queued_actions(view)
    effects = [a for a in actions if a['class'] not in ('WaitAction', 'HandCheckAction')]
    if len(effects) != 1 or effects[0].get('class_full') != 'com.megacrit.cardcrawl.actions.utility.UseCardAction':
        raise ValueError(f'unsupported continuation after {name} selection')
    finish = effects[0]
    card = finish.get('targetCard', {})
    if card.get('id') != name or card.get('uuid') != combat.get('card_in_play', {}).get('uuid'):
        raise ValueError(f'{name} selection needs the resolving card export; restart with the updated mod')
    queued = live.get('queued_cards')
    if queued not in ([], [card['uuid']]):
        raise ValueError(f'unsupported queued card replay after {name}')
    if card.get('return_to_hand') or finish.get('returnToHand') or finish.get('reboundCard'):
        raise ValueError(f'unsupported redirected {name} continuation')
    if any(c['uuid'] == card['uuid'] for pile in ('hand','draw_pile','discard_pile','exhaust_pile') for c in combat[pile]):
        raise ValueError(f'resolving {name} already belongs to a pile')
    return dict(kind='finish_card', card=comparator.sts.CardId(comparator.bridge.card_snapshot(card)['id']),
        upgrades=card['upgrades'], cost=card['cost'], base_cost=card['base_cost'], exhaust=finish['exhaustCard'],
        purge=card['purge_on_use'], trigger=not card['dont_trigger_on_use'])


def queued_actions(view):
    actions = view['live_run'].get('pending_actions')
    if actions is not None:
        # Older captures named anonymous callbacks with an empty simple name;
        # their independent audit still records the fully qualified class.
        raw = view.get('parity', {}).get('raw_state', {}).get('actions', [])
        result = []
        for index, action in enumerate(actions):
            row = dict(action)
            if 'class_full' not in row and index < len(raw):
                full = raw[index]['class']
                if full.rsplit('.', 1)[-1] == row['class'] or not row['class']:
                    row['class_full'] = full
            result.append(row)
        return result
    result = []
    for raw in view['parity']['raw_state']['actions']:
        row = {key.rsplit('.', 1)[-1]: value for key, value in raw['fields'].items()}
        row['class'] = raw['class'].rsplit('.', 1)[-1]
        row['class_full'] = raw['class']
        result.append(row)
    return result


def queued_power(comparator, view, action):
    power, target = action.get('powerToApply'), action.get('target')
    if not isinstance(power, dict) or not isinstance(target, dict):
        raise ValueError('queued ApplyPowerAction lacks original power/target export')
    if power.get('owner') != target:
        raise ValueError('queued power owner differs from its original target')
    owner = target.get('kind')
    amount = action['amount']
    if owner == 'player':
        index = -1
    elif owner == 'monster':
        monsters = view['game']['combat_state']['monsters']
        original_index = target['index']
        if not 0 <= original_index < len(monsters) or monsters[original_index]['id'] != target['id']:
            raise ValueError('queued power target identity differs from original slot')
        _, targets = comparator.bridge.canonical_monsters(monsters)
        if targets.count(original_index) != 1:
            raise ValueError('queued power target has no unique native slot')
        index = targets.index(original_index)
    else:
        raise ValueError('unsupported queued power target: ' + str(owner))
    # Startup relic powers have explicit equivalents. Refuse unknown powers;
    # mapping their visible amount alone would omit callbacks and artifact.
    player = {'Buffer', 'Intangible', 'Strength', 'Dexterity', 'Artifact', 'LoseStrength', 'Weak', 'Vulnerable', 'Frail', 'No Draw',
              'Pen Nib'}
    monster = {'Strength', 'Weak', 'Vulnerable', 'Poison', 'Artifact'}
    identifier = {'Weakened':'Weak', 'IntangiblePlayer':'Intangible'}.get(power.get('ID'), power.get('ID'))
    if identifier not in (player if owner == 'player' else monster):
        raise ValueError('unsupported queued original power: ' + str(power.get('ID')))
    return dict(kind='power', owner=owner, target=index, power=identifier, amount=amount,
                source_monster=action.get('source', {}).get('kind') == 'monster')


MID_TURN_TASKS = {
    # A Defect card opens these pickers while it is still resolving: its own
    # UseCardAction waits behind the pick, so the import finishes it like Headbutt.
    'Hologram': 'HOLOGRAM',
    'Recycle': 'RECYCLE',
    'Seek': 'SEEK',
}


def mid_turn_selection(comparator, view):
    """The spec for a picker a card opened in the middle of a turn.

    Unlike the battle-start pickers below the only queued work is the opening
    card's own UseCardAction; the screen already lists the cards the simulator
    keeps in that pile.
    """
    game = view['game']
    screen = game['screen_state']
    card = game['combat_state'].get('card_in_play')
    if not isinstance(card, dict) or card.get('id') not in MID_TURN_TASKS:
        raise ValueError('only battle-start queues are supported')  # the message every other picker already got
    task = MID_TURN_TASKS[card['id']]
    if not screen.get('cards'):
        raise ValueError('mid-turn picker has no candidates')
    cards = [comparator.sts.CardId(comparator.bridge.card_snapshot(c)['id']) for c in screen['cards']]
    if task == 'SEEK':
        # Seek's grid keeps the picked cards on screen and only moves them when the
        # last pick is confirmed, so a partly picked grid is a normal state: the
        # remaining picks are num_cards minus what is already selected. Its order
        # is the draw pile sorted by name and rarity, not the pile order, so the
        # already picked cards travel as draw pile indices instead of positions.
        picked = [c['uuid'] for c in screen.get('selected_cards') or []]
        pile = [c['uuid'] for c in game['combat_state']['draw_pile']]
        if len(set(picked)) != len(picked) or any(uuid not in pile for uuid in picked):
            raise ValueError('Seek picker selects a card the draw pile does not hold')
        if len(pile) != len(cards):
            raise ValueError('Seek picker does not show the whole draw pile')
        return dict(task='MID_TURN', select_task=task, pick_count=screen['num_cards'] - len(picked),
                    cards=cards, selected_indices=[pile.index(uuid) for uuid in picked],
                    queue=[resolving_card(comparator, view, card['id'])])
    if screen.get('selected') or screen.get('num_cards') != 1:
        raise ValueError('mid-turn picker is not an untouched single-card choice')
    return dict(task='MID_TURN', select_task=task, pick_count=1, cards=cards,
                queue=[resolving_card(comparator, view, card['id'])])


def start_selection(comparator, view):
    g, r = view['game'], view['live_run']
    screen = g['screen_type']
    current = r.get('current_action', {}).get('class')
    if screen == 'GRID' and current == 'DiscardPileToTopOfDeckAction':
        return headbutt_selection(comparator, view)
    card = g['combat_state'].get('card_in_play')
    # Hologram, Recycle and Seek open their picker on any turn, turn 1 included.
    if g['combat_state']['turn'] != 1 or (isinstance(card, dict) and card.get('id') in MID_TURN_TASKS):
        return mid_turn_selection(comparator, view)
    relics = {a['id'] for a in g['relics']}
    if screen == 'CARD_REWARD' and 'Toolbox' in relics:
        if current != 'ChooseOneColorless':
            raise ValueError('not an observed Toolbox selection: ' + str(current))
        spec = dict(task='TOOLBOX', cards=[comparator.sts.CardId(comparator.bridge.card_snapshot(c)['id'])
                                         for c in g['screen_state']['cards']])
    elif screen == 'HAND_SELECT' and 'Gambling Chip' in relics and g['screen_state']['max_cards'] == 99:
        if current != 'GamblingChipAction':
            raise ValueError('not an observed Gambling Chip selection: ' + str(current))
        if g['screen_state']['selected']:
            raise ValueError('partially selected start queue')
        spec = dict(task='GAMBLING_CHIP')
    else:
        raise ValueError('unsupported pending original selection')
    queue = []
    for a in queued_actions(view):
        kind, amount = a['class'], a.get('amount', 0)
        if kind in ('WaitAction', 'EnableEndTurnButtonAction', 'RelicAboveCreatureAction'):
            continue
        if kind == 'DrawCardAction':
            queue.append(dict(kind='draw', amount=amount))
        elif kind == 'GainBlockAction':
            # Monster block is a different operation.
            if a.get('target', {}).get('kind', 'player') != 'player':
                raise ValueError('unsupported queued monster GainBlockAction')
            queue.append(dict(kind='block', amount=amount))
        elif kind == 'GainEnergyAction':
            queue.append(dict(kind='energy', amount=a['energyGain']))
        elif kind == 'ApplyPowerAction':
            queue.append(queued_power(comparator, view, a))
        elif a.get('class_full') == 'com.megacrit.cardcrawl.relics.RedSkull$1' and a.get('this$0') == 'Red Skull':
            queue.append(dict(kind='red_skull'))
        elif kind == 'GamblingChipAction':
            queue.append(dict(kind='gamble'))
        elif kind == 'UpgradeRandomCardAction':
            # Warped Tongs (atTurnStartPostDraw), queued behind a battle-start Toolbox pick.
            queue.append(dict(kind='upgrade_random'))
        elif kind == 'MakeTempCardInDrawPileAction':
            c = a['cardToMake']
            queue.append(dict(kind='make_draw', card=comparator.sts.CardId(comparator.bridge.card_snapshot(c)['id']),
                              amount=amount, shuffle=a['randomSpot']))
        elif kind == 'DamageAllEnemiesAction':
            values = a['damage']
            if not values or len(set(values)) != 1 or a['damageType'] != 'THORNS':
                raise ValueError('unsupported queued DamageAllEnemiesAction')
            queue.append(dict(kind='damage_all', amount=values[0]))
        else:
            raise ValueError('unmapped original queued action: ' + kind)
    spec['queue'] = queue
    return spec
