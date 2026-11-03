"""Replay issue #96: Ethereal exhaust order at the end of an Equilibrium turn.

EquilibriumPower.atEndOfTurn marks every non-Ethereal hand card retain, and
DiscardAtEndOfTurnAction moves retained cards to limbo before BaseMod's seeded
shuffle (seed + floor * 55 + GameActionManager.turn). The shuffle therefore sees
only the Ethereal cards, and their exhaust order follows from that shorter list.

Both cases come from one reported run (seed 9080560135178017388). Each starts
from the real frame the game reported after the end turn and restores the hand
and exhaust pile the player held when pressing End Turn; the expected exhaust
order is what the game produced.
"""
import copy
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
import backend
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import ActionMapper

frames = json.loads((root / 'overlay/test/equilibrium-ethereal-faults.json').read_text(encoding='utf-8'))['frames']


def end_turn_frame(after, turn, hand_uuids, exhaust_kept):
    """The frame at End Turn: hand rebuilt from the after-frame, Equilibrium active."""
    frame = copy.deepcopy(after)
    combat = frame['game_state']['combat_state']
    cards = {card['uuid'][:8]: card for pile in ('hand', 'exhaust_pile', 'draw_pile', 'discard_pile')
             for card in combat[pile]}
    combat['hand'] = [cards[uuid] for uuid in hand_uuids]
    combat['exhaust_pile'] = combat['exhaust_pile'][:exhaust_kept]
    taken = set(hand_uuids)
    for pile in ('draw_pile', 'discard_pile'):
        combat[pile] = [card for card in combat[pile] if card['uuid'][:8] not in taken]
    combat['turn'] = turn
    combat['player']['energy'] = 0
    combat['player']['powers'] = combat['player']['powers'] + [{'id': 'Equilibrium', 'name': 'Equilibrium', 'amount': 1}]
    return frame


cases = (
    # F47 Transient, turn 2: hand [Ascender's Bane, Void, Defend]; the game exhausted Bane, then Void.
    ('F47-transient', frames['959'], 2, ['bcaf5efe', 'e69fb48e', 'dfc24406'], 1, ['bcaf5efe', 'e69fb48e']),
    # F51 Donu and Deca, turn 4: three Dazed among seven cards; the game exhausted them as 0c01, fccf, 0d06.
    ('F51-donu-deca', frames['1288'], 4,
     ['60e1af80', 'fe7e6976', '0d06d955', '3dac9243', 'b2e64bbf', 'fccf355d', '0c01316d'], 4,
     ['0c01316d', 'fccf355d', '0d06d955']),
)

advisor = Advisor()
s = advisor.search
for name, after, turn, hand_uuids, exhaust_kept, expected in cases:
    saved = backend.SUPPORTED_CLASSES
    backend.SUPPORTED_CLASSES = {**saved, 'DEFECT': 'test only'}
    try:
        before = view_from_state(end_turn_frame(after, turn, hand_uuids, exhaust_kept))
    finally:
        backend.SUPPORTED_CLASSES = saved
    battle = s.comparator.import_battle({**before, 'game': {**before['game'], 'seed': int(before['game']['seed']) % (1 << 64)}},
                                        require_search_state=True)
    owner = {card.unique_id: uuid for card, uuid in zip(battle.hand, hand_uuids)}
    advisor.native_search.execute(battle, int(ActionMapper(s.comparator, battle, before).action('end', before, battle).bits))
    exhausted = [owner[card.unique_id] for card in list(battle.exhaust_pile)[exhaust_kept:]]
    assert exhausted == expected, (name, exhausted, expected)
print('PASS: Ethereal cards exhaust in the original game order at the end of an Equilibrium turn')
