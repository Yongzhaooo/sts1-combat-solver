"""Map a headless-engine `state` JSON to the calibration observation (docs/truth-calibration.md).

Only IDs and numbers are copied; names, descriptions and other game text in `state` are ignored.
The engine's plain `state` lacks some documented fields (pile contents, retain, move IDs, RNG
counters, stance ID); an engine must add them under `state["calibration"]`, described in the doc.
A missing field is an error that names it, never a silent default.
"""

# Java field name of each seeded stream in AbstractDungeon -> observation key.
RNG_FIELDS = {'aiRng': 'ai', 'cardRng': 'card', 'cardRandomRng': 'card_random', 'eventRng': 'event',
              'mapRng': 'map', 'merchantRng': 'merchant', 'miscRng': 'misc', 'monsterRng': 'monster',
              'monsterHpRng': 'monster_hp', 'potionRng': 'potion', 'relicRng': 'relic',
              'shuffleRng': 'shuffle', 'treasureRng': 'treasure'}
PILES = ('hand', 'draw', 'discard', 'exhaust')


class MissingField(ValueError):
    pass


def _get(obj, path):
    for key in path.split('.'):
        if not isinstance(obj, dict) or key not in obj:
            hint = ' (engine support: an engine extension, docs/truth-calibration.md, state["calibration"])' \
                if path.startswith('calibration') else ''
            raise MissingField(f'state has no {path}{hint}')
        obj = obj[key]
    return obj


def _card(card):
    return {'id': card['id'], 'upgrades': card['upgrades'], 'cost': card['cost'], 'retain': card['retain']}


def _powers(powers):
    return [{'id': p['id'], 'amount': p['amount']} for p in powers]


def _power(powers, power_id):
    return next((p['amount'] for p in powers if p['id'] == power_id), 0)


def project(state):
    """The observation for one decision point; raises MissingField if the engine lacks a field."""
    player = _get(state, 'player')
    powers = _get(state, 'player.powers')
    monsters = state.get('monsters', [])  # the engine omits the key outside combat
    extra = _get(state, 'calibration.monsters')
    if len(extra) != len(monsters):
        raise MissingField(f'calibration.monsters has {len(extra)} entries for {len(monsters)} monsters')
    rng = _get(state, 'calibration.rng')
    missing = sorted(RNG_FIELDS.keys() - rng.keys())
    if missing:
        raise MissingField(f'state has no calibration.rng.{missing[0]}')
    orbs = _get(state, 'player.orbs')
    return {
        'phase': _get(state, 'phase'),
        'floor': _get(state, 'floor'),
        'turn': _get(state, 'calibration.turn'),
        'player': {
            'hp': player['hp'], 'max_hp': player['maxHp'], 'block': player['block'],
            'energy': player['energy'], 'gold': player['gold'], 'powers': _powers(powers),
            'stance': _get(state, 'calibration.stance'), 'mantra': _power(powers, 'Mantra'),
            # An empty slot is an orb object whose Java ID is null; the engine's JSON omits null keys.
            'orbs': [{'id': o.get('id'), 'passive': o['passiveAmount'], 'evoke': o['evokeAmount']} for o in orbs],
            # Empty slots are in the list, so the slot count is its length.
            'orb_slots': len(orbs), 'focus': _power(powers, 'Focus'),
        },
        'deck': [_card(c) for c in _get(state, 'calibration.deck')],
        'piles': {pile: [_card(c) for c in _get(state, 'calibration.' + pile)] for pile in PILES},
        'monsters': [{'id': m['id'], 'hp': m['hp'], 'max_hp': m['maxHp'], 'block': m['block'],
                      'powers': _powers(m['powers']), 'intent': x['intent'], 'move': x['move']}
                     for m, x in zip(monsters, extra)],
        'relics': [{'id': r['id'], 'counter': r['counter']} for r in _get(state, 'player.relics')],
        'potions': [p['id'] for p in _get(state, 'player.potions')],
        'rng': {key: rng[field] for field, key in sorted(RNG_FIELDS.items(), key=lambda item: item[1])},
    }
