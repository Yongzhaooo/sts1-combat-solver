"""Recorded Armaments picker plus an explicitly reconstructed pre-play prefix."""
from collections import deque
import argparse
import copy
import json
from pathlib import Path
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
root = parser.parse_args().root
fixtures = Path(__file__).resolve().parents[1] / 'overlay/test'
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor, view_from_state
from sim_patch.parity.adapter import ActionMapper, CoverageGap, IdentityDifference

recorded = json.loads((fixtures / 'armaments-picker.json').read_text(encoding='utf-8'))
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

# A full, unfiltered hand can bind Stasis's new Java object by exact pile order.
returned = copy.deepcopy(after)
returned['game']['screen_type'] = 'HAND_SELECT'
returned['game']['screen_state'] = dict(hand=returned['game']['combat_state']['hand'], for_upgrade=False)
card = returned['game']['combat_state']['hand'][0]
previous = mapper.uuids[card['uuid']]
card['uuid'] = 'returned-stasis-instance'
mapper.refresh(battle, returned)
assert mapper.uuids[card['uuid']] == previous
print('PASS: unfiltered hand picker binds a returned instance without reassigning known cards')

# The capture is the real post-click frame. Only the preceding play and the
# following second picker are reconstructed; this is not a JVM replay.
fault = json.loads((fixtures / 'burning-pact-confirmation.json').read_text(encoding='utf-8'))
before = copy.deepcopy(fault)
g = before['game']; combat = g['combat_state']
combat['hand'].insert(2, g['screen_state']['selected'][0])
combat['hand'].append(combat.pop('card_in_play'))
combat['player']['energy'] = 4
combat['cards_played_this_turn'] = combat['skills_played_this_turn'] = 0
next(p for p in combat['player']['powers'] if p['id'] == 'DuplicationPower')['amount'] = 1
next(r for r in g['relics'] if r['id'] == 'InkBottle')['counter'] = 0
g['seed'] %= 1 << 64  # Same signed-Java-seed conversion as Advisor.solve.
g['screen_type'], g['screen_state'] = 'NONE', {}
g.pop('solver_selection')
advisor = Advisor(); s = advisor.search
s.battle = s.comparator.import_battle(before, require_search_state=True)
s.mapper = ActionMapper(s.comparator, s.battle, before)
play = s.sts.SearchAction(s.sts.SearchActionType.CARD, 4, 0)
pick = s.sts.SearchAction(s.sts.SearchActionType.SINGLE_CARD_SELECT, 2)
assert play.is_valid(s.battle)
advisor.native_search.execute(s.battle, int(play.bits))
first = copy.deepcopy(fault)
first['game']['screen_state']['selected'] = []
first['game']['screen_state']['hand'] = copy.deepcopy(combat['hand'][:-1])
first['game']['combat_state']['hand'] = first['game']['screen_state']['hand']
s.actions = deque([int(pick.bits)])
assert s.next_action(first)[1] == 'choose 2' and pick.is_valid(s.battle)
pending = s.pending_single if hasattr(s, 'pending_single') else None
advisor.pending = pick
# Native resolves the first exhaust/draw and enters the copied card's picker.
advisor.advance(fault, 8000)
assert s.battle.input_state == s.sts.InputState.CARD_SELECT
assert s.native.selection_info(s.battle)['task'] == 'EXHAUST_ONE'
choices = [a for a in s.sts.get_legal_actions(s.battle)
           if a.action_type == s.sts.SearchActionType.SINGLE_CARD_SELECT]
second = choices[-1]
s.actions = deque([int(second.bits)])
for kind in ('uuid', 'card', 'remaining', 'turn', 'action', 'resolving'):
    invalid = copy.deepcopy(fault)
    game = invalid['game']
    if kind == 'uuid': game['screen_state']['selected'][0]['uuid'] = 'wrong-selected-instance'
    elif kind == 'card': game['screen_state']['selected'][0]['id'] = 'Dazed'
    elif kind == 'remaining': game['screen_state']['hand'][0]['uuid'] = 'wrong-remaining-instance'
    elif kind == 'turn': game['combat_state']['turn'] += 1
    elif kind == 'action': game['solver_selection']['current_action']['class'] = 'WrongAction'
    else: game['combat_state']['card_in_play']['uuid'] = 'wrong-resolving-instance'
    try:
        s.next_action(invalid)
    except ValueError:
        pass
    else:
        raise AssertionError('unsafe old picker accepted: ' + kind)
unavailable = copy.deepcopy(fault)
unavailable['available_commands'].remove('confirm')
try:
    s.next_action(unavailable)
except ValueError:
    pass
else:
    raise AssertionError('missing original confirm was accepted')
assert s.next_action(fault) == (None, 'confirm')
try:
    s.next_action(fault)
except ValueError:
    pass
else:
    raise AssertionError('unsettled confirm reused the old picker')

# Bind the synthetic post-confirm observation by existing exact native fields
# and UUIDs. The newly drawn cards already have UUIDs in the captured draw pile.
second_view = copy.deepcopy(fault)
originals = {s.mapper.uuids[c['uuid']]: c for pile in ('hand','draw_pile','discard_pile','exhaust_pile')
             for c in before['game']['combat_state'][pile]}
second_view['game']['screen_state']['selected'] = []
continued = second_view['game']['combat_state']
for pile in ('hand', 'draw_pile', 'discard_pile', 'exhaust_pile'):
    continued[pile] = [copy.deepcopy(originals[c.unique_id]) for c in getattr(s.battle, pile)]
continued['player']['energy'] = int(s.battle.player.energy)
continued['player']['powers'] = [p for p in continued['player']['powers'] if p['id'] != 'DuplicationPower']
continued.update({k: int(v) for k, v in s.native.search_state(s.battle).items() if k in continued})
continued['rngs'] = copy.deepcopy(dict(s.battle.rng_states))
continued['end_turn_shuffle'] = dict(s.battle.end_turn_shuffle)
second_view['rng'] = {name: continued['rngs'][k] for k, name in
    [('ai','aiRng'),('card_random','cardRandomRng'),('misc','miscRng'),
     ('monster_hp','monsterHpRng'),('potion','potionRng'),('shuffle','shuffleRng')]}
second_view['game']['screen_state']['hand'] = continued['hand']
# This exercises the actual confirm response consumer before requesting the
# next command. Pending-choice comparisons retain their existing explicit gaps.
advisor.pending = None
advisor.advance(second_view, 8000)
assert s.next_action(second_view)[1] == 'choose 5'
assert second.is_valid(s.battle)
second_view['game']['screen_state']['hand'].reverse()
assert s.next_action(second_view)[1] == 'choose 0', 'second picker must still use UUID'
invalid = copy.deepcopy(second_view)
invalid['game']['screen_state']['hand'][0]['uuid'] = 'unmapped-next-instance'
try:
    s.next_action(invalid)
except ValueError:
    pass
else:
    raise AssertionError('unknown second-picker identity was accepted')
# Fast hand confirmation can open the next picker without an explicit confirm.
s.pending_single = pending
assert s.next_action(second_view)[1] == 'choose 0'
# A genuinely new, untouched picker across a turn must not inherit old confirm.
s.pending_single = pending
later = copy.deepcopy(second_view)
later['game']['combat_state']['turn'] += 1
assert s.next_action(later)[1] == 'choose 0'
assert second.is_valid(s.battle)
advisor.native_search.execute(s.battle, int(second.bits))
assert s.battle.input_state == s.sts.InputState.PLAYER_NORMAL
s.pending_single = None
assert s.confirmation(fault), 'ordinary resolved hand picker still needs confirmation'
grid = copy.deepcopy(fault)
grid['game']['screen_type'] = 'GRID'
assert s.confirmation(grid), 'ordinary resolved grid picker still needs confirmation'
print('PASS: duplicated Burning Pact confirm boundary, next picker UUIDs, nine rejection boundaries and legacy confirms')
