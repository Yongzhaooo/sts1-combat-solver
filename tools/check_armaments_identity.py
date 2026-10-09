"""Recorded Armaments picker plus an explicitly reconstructed pre-play prefix."""
from collections import deque
import copy
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import ActionMapper, CoverageGap, IdentityDifference

recorded = json.loads((root / 'overlay/test/armaments-picker.json').read_text(encoding='utf-8'))
picker = view_from_state(recorded)
prefix = copy.deepcopy(recorded)
game = prefix['game_state']
combat = game['combat_state']
# Export records the post-play fault frame, not every full pre-play frame.
# The preceding request records [Dazed, Defend, Defend, Armaments, Dazed, Strike].
# Reconstruct the hidden Dazed instances explicitly; do not claim a full run replay.
dazed = next(c for c in combat['draw_pile'] if c['id'] == 'Dazed')
hidden = [dict(dazed, uuid='armaments-hidden-dazed-' + str(i)) for i in range(2)]
defend1, defend2, strike = combat['hand']
armaments = combat.pop('card_in_play')
combat['hand'] = [hidden[0], defend1, defend2, armaments, hidden[1], strike]
combat['player']['energy'] += 1
combat['player']['block'] -= 5
game['screen_type'] = 'NONE'
game['is_screen_up'] = False
game['screen_state'] = {}
game.pop('solver_selection', None)
prefix['available_commands'] = ['play', 'end', 'potion']
before = view_from_state(prefix)
advisor = Advisor()
s = advisor.search
battle = s.comparator.import_battle(before, require_search_state=True)
mapper = ActionMapper(s.comparator, battle, before)
identities = dict(mapper.uuids)
play = mapper.action('play 4', before, battle)
assert play.is_valid(battle)
s.battle, s.mapper = battle, mapper
next_pick = s.sts.SearchAction(s.sts.SearchActionType.SINGLE_CARD_SELECT, 1)
s.actions = deque([int(play.bits), int(next_pick.bits)])
advisor.pending = play
advisor.advance(picker, 256)  # Exercise the exact production call that faulted.
assert s.native.selection_info(battle)['task'] == 'ARMAMENTS'
assert len(battle.hand) == 5 and len(picker['game']['combat_state']['hand']) == 3
# This raised IdentityDifference before the fix: original hand index 1 is now
# the second Defend, but native index 1 remains the first Defend.
mapper.refresh(battle, picker)
assert mapper.uuids == identities, 'existing identities must never be rebound'
pick = mapper.action('choose 0', picker, battle)
assert pick.is_valid(battle) and int(pick.select_idx) == 1
s.battle, s.mapper = battle, mapper
s.actions = deque([int(pick.bits)])
assert s.next_action(picker)[1] == 'choose 0'
reordered = copy.deepcopy(picker)
reordered['game']['screen_state']['hand'].reverse()
assert s.next_action(reordered)[1] == 'choose 2', 'picker transport must use UUID'
for kind in ('wrong_card', 'unknown_duplicate', 'duplicate_uuid'):
    invalid = copy.deepcopy(picker)
    hand = invalid['game']['combat_state']['hand']
    if kind == 'wrong_card':
        hand[0]['upgrades'] = 1
    elif kind == 'unknown_duplicate':
        hand[0]['uuid'] = 'unobserved-defend'
    else:
        hand[1]['uuid'] = hand[0]['uuid']
    try:
        mapper.refresh(battle, invalid)
    except CoverageGap:
        pass
    else:
        raise AssertionError('unsafe selection identity accepted: ' + kind)
    assert mapper.uuids == identities
advisor.native_search.execute(battle, int(pick.bits))
assert battle.hand[2].upgrade_count == 1, 'selected Defend instance upgraded'
# Re-establish a complete normal-hand observation from the known UUID relation.
# Only this expected continuation is synthetic; the filtered picker above is real.
after = copy.deepcopy(picker)
after['game']['screen_type'] = 'NONE'
after['game']['screen_state'] = {}
originals = {c['uuid']: c for pile in ('hand','draw_pile','discard_pile','exhaust_pile')
             for c in before['game']['combat_state'][pile]}
inverse = {unique: uuid for uuid, unique in identities.items()}
actual_hand = []
for card in battle.hand:
    raw = copy.deepcopy(originals[inverse[card.unique_id]])
    raw['upgrades'] = int(card.upgrade_count)
    actual_hand.append(raw)
after['game']['combat_state']['hand'] = actual_hand
mapper.refresh(battle, after)
invalid = copy.deepcopy(after)
invalid['game']['combat_state']['hand'][-2], invalid['game']['combat_state']['hand'][-1] = (
    invalid['game']['combat_state']['hand'][-1], invalid['game']['combat_state']['hand'][-2])
try:
    mapper.refresh(battle, invalid)
except IdentityDifference:
    pass
else:
    raise AssertionError('normal-hand identity mismatch was suppressed')
print('PASS: recorded Armaments filtered picker, stable duplicate UUIDs, selection transport and strict rejection')
