"""Heart simulation: a first-turn Heart battle built from an outside-combat run state.

The game's own Heart fight is started by the native engine (BattleContext.init_encounter), which handles the battle-start
relics, the Heart's ascension numbers and the opening shuffle. This module only turns the CommunicationMod `game_state`
the panel sends (deck, relics, potions, HP, keys) into the GameContext that call needs, and refuses what the engine cannot
play. The real run and its save are never touched.

Callers: `unsupported(game_state)` first (the backend does this inside `build_heart_battle`), then
`build_heart_battle(game_state, seed)`. The engine modules are the ones the backend already loaded (`slaythespire` and
`steam_mcts` in sys.modules), never a second copy.

Relic counters. The engine keeps each relic's `data` in the game's own counter convention (BattleContext::initRelics reads
it as: Happy Flower and Incense Burner `-1` or the count, Ink Bottle / Nunchaku / Pen Nib / Sundial the count, Girya its
lifts, Lizard Tail `-1` unused, Neow's Lament charges left, Ancient Tea Set `-2` armed), so the game's counter is passed
through unchanged and a relic without one is `-1`. One exception, as in steam_mcts.build_snapshot: the engine reads
Omamori's `data` as the charges left, and a relic with 0 charges is dropped (the engine's combat bit means "a curse can
still be blocked", not ownership). Any other Omamori counter is refused, not guessed.
"""
from __future__ import annotations

import sys

# Bottled relic id -> engine card type name the bottled card must have.
BOTTLE_RELICS = {'Bottled Flame': 'ATTACK', 'Bottled Lightning': 'SKILL', 'Bottled Tornado': 'POWER'}
FLOOR = 56          # the Act 4 Heart room, as in a real run
HEART_ACT = 4
UINT64 = 1 << 64
OMAMORI_SPENT = (0, -2)     # counters of a used-up Omamori (0 charges; -2 is the game's "spent" sentinel)


class HeartUnsupported(ValueError):
    """The run holds items the engine cannot play, or the panel's request cannot be simulated; `items` names them."""

    def __init__(self, items):
        super().__init__('无法模拟：' + '、'.join(items))
        self.items = list(items)


def parse_seed(value):
    """The panel's seed: an unsigned decimal string (or int) in 0..2^64-1."""
    if type(value) is int:
        number = value
    elif isinstance(value, str) and value.isascii() and value.isdigit():
        number = int(value)
    else:
        raise ValueError('无效的种子：需要 0 到 2^64-1 的十进制数')
    if not 0 <= number < UINT64:
        raise ValueError('无效的种子：需要 0 到 2^64-1 的十进制数')
    return number


def _engine(engine):
    if engine is not None:
        return engine
    sts, mcts = sys.modules.get('slaythespire'), sys.modules.get('steam_mcts')
    if sts is None or mcts is None:
        raise RuntimeError('战斗引擎尚未加载')
    return sts, mcts


def _label(identifier, name=None):
    name = name or identifier
    return identifier if name == identifier else '%s（%s）' % (name, identifier)


def card_id(sts, mcts, name):
    return sts.CardId(sts.card_id_from_name(mcts.CARD_ALIASES.get(name, mcts.enum_key(name))))


def potion_id(sts, mcts, name):
    return int(sts.potion_id_from_name(mcts.POTION_ALIASES.get(name, name)))


def _bottle_entries(game):
    """{relic id: [uuid, ...]} from the frontend's `solver_heart_bottles` ([{relic, uuid}, ...])."""
    found = {}
    raw = game.get('solver_heart_bottles')
    for entry in raw if isinstance(raw, list) else ():
        if isinstance(entry, dict) and isinstance(entry.get('relic'), str) and isinstance(entry.get('uuid'), str):
            found.setdefault(entry['relic'], []).append(entry['uuid'])
    return found


def bottle_indices(game):
    """{deck index: relic id} for every bottle relic held, or the list of relic labels whose bottled card cannot be found.

    CommunicationMod does not say which card is bottled, so the panel sends it (`solver_heart_bottles`, the bottle relic's
    own `card` field, as a uuid). A held bottle relic needs exactly one entry naming exactly one deck card, and no card may
    be bottled twice. Anything else is refused: the card is never guessed.
    """
    entries = _bottle_entries(game)
    deck = game.get('deck') or []
    chosen, bad = {}, []
    for relic in game.get('relics') or []:
        rid = relic.get('id')
        if rid not in BOTTLE_RELICS:
            continue
        uuids = entries.get(rid, [])
        positions = [i for i, card in enumerate(deck) if len(uuids) == 1 and card.get('uuid') == uuids[0]]
        if len(uuids) != 1 or len(positions) != 1 or positions[0] in chosen:
            bad.append(_label(rid, relic.get('name')))
        else:
            chosen[positions[0]] = rid
    return chosen, bad


def _default_gaps():
    """The backend's own coverage_gaps (the module that is running, or a fresh import when used on its own)."""
    for name in ('backend', '__main__', '__mp_main__'):
        gaps = getattr(sys.modules.get(name), 'coverage_gaps', None)
        if gaps is not None:
            return gaps
    from backend import coverage_gaps
    return coverage_gaps


def unsupported(game, gaps=None, engine=None):
    """Names of everything in the run the simulation cannot play (empty when it can).

    `gaps(game)` is the backend's coverage_gaps (the coverage manifest's missing cards and class relics; the default is the
    backend's own function); the deck is shown to it as a combat's draw pile. Names the engine cannot map at all, a non-Ironclad class, an unreadable Omamori counter,
    a bottle whose card is unknown and a potion belt larger than the engine's are listed as well.
    """
    if not isinstance(game, dict):
        return ['局外状态缺失']
    if game.get('class') != 'IRONCLAD':
        return ['暂只支持战士']
    sts, mcts = _engine(engine)
    deck, relics, potions = game.get('deck') or [], game.get('relics') or [], game.get('potions') or []
    items = []
    seen = set()

    def add(label):
        if label not in seen:
            seen.add(label)
            items.append(label)

    gapped = set()
    for identifier, name in (gaps or _default_gaps())({'combat_state': {'draw_pile': deck}, 'relics': relics}):
        gapped.add(identifier)
        add(_label(identifier, name))

    def mapped(identifier, name, convert, *args):
        if identifier in gapped:
            return
        try:
            convert(*args)
        except (ValueError, KeyError, TypeError):
            add(_label(str(identifier), name))

    for card in deck:
        mapped(card.get('id'), card.get('name'), card_id, sts, mcts, card.get('id'))
    for relic in relics:
        mapped(relic.get('id'), relic.get('name'), sts.relic_id_from_name, relic.get('id'))
        if relic.get('id') == 'Omamori' and relic.get('counter') not in (1, 2, *OMAMORI_SPENT):
            add(_label('Omamori', relic.get('name')) + ' 次数无法识别：' + repr(relic.get('counter')))
    for potion in potions:
        mapped(potion.get('id'), potion.get('name'), potion_id, sts, mcts, potion.get('id'))
    if len(potions) > _POTION_SLOTS:
        add('药水栏位 %d 个' % len(potions))
    if not deck:
        add('卡组为空')
    for label in bottle_indices(game)[1]:
        add('瓶装牌：' + label)
    return items


# The engine holds this many potion slots (GameContext::potions); set_potions_raw refuses more.
_POTION_SLOTS = 5


def build_game(game, seed, engine=None):
    """The outside-combat GameContext at the Act 4 Heart room for this inventory; the RNG streams follow the real room entry."""
    sts, mcts = _engine(engine)
    seed = parse_seed(seed)
    ascension = int(game['ascension_level'])
    gc = sts.GameContext(sts.CharacterClass.IRONCLAD, seed, ascension)
    while len(gc.deck):
        gc.remove_card(len(gc.deck) - 1)
    for entry in game['deck']:
        card = sts.Card(card_id(sts, mcts, entry['id']))
        for _ in range(int(entry.get('upgrades') or 0)):
            card.upgrade()
        card.misc = int(entry.get('misc') or 0)
        gc.obtain_card(card)
    types = {name: getattr(sts.CardType, name) for name in set(BOTTLE_RELICS.values())}
    for index, relic in bottle_indices(game)[0].items():
        if gc.deck[index].type != types[BOTTLE_RELICS[relic]]:
            raise HeartUnsupported(['瓶装牌：' + relic + ' 的牌类型与卡组不符'])
        gc.bottle_card(index, types[BOTTLE_RELICS[relic]])
    raw = []
    for relic in game['relics']:
        counter = relic.get('counter')
        if relic['id'] == 'Omamori' and counter in OMAMORI_SPENT:
            continue
        raw.append((int(sts.relic_id_from_name(relic['id'])), -1 if counter is None else int(counter)))
    gc.set_relics_raw(raw)
    gc.set_potions_raw([potion_id(sts, mcts, p['id']) for p in game['potions']])
    gc.max_hp = int(game['max_hp'])
    gc.cur_hp = int(game['current_hp'])
    gc.gold = int(game.get('gold') or 0)
    keys = game.get('keys') or {}
    gc.red_key, gc.green_key, gc.blue_key = bool(keys.get('ruby')), bool(keys.get('emerald')), bool(keys.get('sapphire'))
    gc.act = HEART_ACT
    gc.floor_num = FLOOR
    gc.cur_room = sts.Room.BOSS
    gc.cur_map_node_x, gc.cur_map_node_y = 0, 99   # never the Act 1 burning elite node
    # Room entry reseeds misc with Random(seed + floor), as it does monsterHp/ai/shuffle/cardRandom (which init_encounter
    # reseeds itself). A fresh GameContext's cardRandomRng is Random(its seed) at counter 0 (heart-sim W1, pilot.md).
    room = sts.GameContext(sts.CharacterClass.IRONCLAD, (seed + FLOOR) % UINT64, ascension)
    gc.set_rng_states({'miscRng': room.rng_states['cardRandomRng']})
    return gc


def build_heart_battle(game, seed, gaps=None, engine=None):
    """The Heart fight's first player turn, ready for the search. Raises HeartUnsupported (message 无法模拟：...) for what
    cannot be played; `gaps` is the coverage check of `unsupported`."""
    items = unsupported(game, gaps, engine)
    if items:
        raise HeartUnsupported(items)
    sts, _ = _engine(engine)
    battle = sts.BattleContext()
    battle.init_encounter(build_game(game, seed, engine), sts.MonsterEncounter.THE_HEART)
    return battle
