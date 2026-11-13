"""Check the Incense Burner carry rule without a game."""
from recovery import carry_weight, incense_targets, incense_values


def game(act=1, boss='The Guardian', last=None, room='MonsterRoom', ahead=True, heart=False, nxt=None, max_hp=80):
    state = {'act': act, 'act_boss': boss, 'solver_last_elite': last, 'room_type': room, 'max_hp': max_hp,
            'solver_elite_ahead': ahead,
            'combat_state': {'monsters': [{'id': 'CorruptHeart' if heart else 'JawWorm'}]}}
    if nxt is not None:
        state['solver_next_fights'] = nxt
    return state


assert incense_targets(game()) == [3]
assert incense_targets(game(last='Lagavulin')) == [3]
assert incense_targets(game(last='Gremlin Nob')) == [3, 4]
assert incense_targets(game(last='Gremlin Nob', boss='Slime Boss')) == [3]
assert incense_targets(game(room='MonsterRoomBoss', boss='Slime Boss')) == [4]
assert incense_targets(game(act=2, boss='Champ')) == [5]
assert incense_targets(game(act=2, last='Gremlin Nob')) == [5]
assert incense_targets(game(act=2, last='Slavers')) == [4]
assert incense_targets(game(act=2, boss='Automaton')) == [5]
assert incense_targets(game(act=2, boss='Automaton', ahead=False)) == [0]
assert incense_targets(game(act=2, boss='Automaton', room='MonsterRoomBoss')) == [4]
assert incense_targets(game(act=3, last='Nemesis')) == [4]
assert incense_targets(game(act=4)) == [4]
assert incense_targets(game(act=4, heart=True)) == []
# Act 1 looks at the next fight (user rule, 2026-11-13).
# Floor 6 report (seed -6026793163311779219): hallway fight next, so the counter is worthless.
assert incense_values(game(boss='Slime Boss', nxt=['monster'])) == {}
assert incense_values(game(nxt=['monster', 'elite'])) == {}
assert incense_values(game(nxt=['monster', 'boss'], boss='Hexaghost')) == {}
assert incense_values(game(nxt=['elite'])) == {3: 30}
assert incense_values(game(nxt=['elite'], last='Gremlin Nob')) == {3: 30, 4: 30}
assert incense_values(game(nxt=['elite'], boss='Hexaghost')) == {3: 30}
assert incense_values(game(nxt=['boss'], boss='Hexaghost', max_hp=64)) == {4: 30}
assert incense_values(game(nxt=['boss'], boss='Hexaghost', max_hp=75)) == {4: 36}
assert incense_values(game(nxt=['boss'], boss='Hexaghost', max_hp=11)) == {4: 0}
assert incense_values(game(nxt=['boss'], boss='The Guardian')) == {4: 30}
assert incense_values(game(nxt=['boss'], boss='Slime Boss')) == {3: 30}
assert incense_values(game(nxt=['elite', 'boss'], boss='Hexaghost', max_hp=75)) == {3: 30, 4: 36}
# Without map data, in the Boss room and after act 1 the targets keep 30 HP each.
assert incense_values(game()) == {3: 30}
assert incense_values(game(nxt=[])) == {3: 30}
assert incense_values(game(room='MonsterRoomBoss', boss='Hexaghost', nxt=['monster'])) == {4: 30}
# Act 2 general case: 4 or 5, 12 HP each (user rule, 2026-11-13); the other act 2 cases keep 30.
assert incense_values(game(act=2, boss='Champ', nxt=['monster'])) == {4: 12, 5: 12}
assert incense_values(game(act=2, boss='Champ')) == {4: 12, 5: 12}
assert incense_values(game(act=2, boss='Automaton')) == {4: 12, 5: 12}
assert incense_values(game(act=2, last='Slavers')) == {4: 30}
assert incense_values(game(act=2, boss='Automaton', ahead=False)) == {0: 30}
assert incense_values(game(act=2, room='MonsterRoomBoss')) == {4: 30}
assert incense_values(game(act=4, heart=True, nxt=['boss'])) == {}
# Act 3 hallway fight next holds 5 (user rule, 2026-11-13); elite or Boss next keeps 4.
assert incense_values(game(act=3, nxt=['monster'])) == {5: 5}
assert incense_values(game(act=3, nxt=['elite'])) == {4: 30}
assert incense_values(game(act=3, nxt=['boss'])) == {4: 30}
assert incense_values(game(act=3, nxt=['monster', 'elite'])) == {4: 30, 5: 5}
assert incense_values(game(act=3)) == {4: 30}
assert incense_values(game(act=3, room='MonsterRoomBoss', nxt=['monster'])) == {4: 30}
assert carry_weight(game()) == 1
assert carry_weight(dict(game(), solver_next_fights=['monster', 'elite'])) == 2
assert carry_weight(dict(game(), solver_next_fights=['monster', 'boss'])) == 3
assert carry_weight(game(act=2, room='MonsterRoomBoss')) == 1
boss1 = dict(game(act=3, room='MonsterRoomBoss'), ascension_level=20, solver_auto_context={'boss_candidates_remaining': 2})
assert carry_weight(boss1) == 3
boss2 = dict(boss1, solver_auto_context={'boss_candidates_remaining': 1})
assert carry_weight(boss2) == 1
assert carry_weight(dict(boss2, keys={'ruby': True, 'emerald': True, 'sapphire': True})) == 2
assert carry_weight(game(act=4, room='MonsterRoomElite')) == 3
assert carry_weight(game(act=4, heart=True)) == 1
print('PASS: incense targets, carry weight')

# The native search scores the counter with the value Python sends (needs the built runtime).
import sys
if '--native' in sys.argv:
    from backend import Advisor
    advisor = Advisor()
    S, N = advisor.search.sts, advisor.native_search

    def burner(counter):
        return S.BattleContext.from_snapshot(dict(seed=1, floor=1, turn=1, ascension=20,
            player=dict(current_hp=72, max_hp=80, block=0, energy=3),
            hand=[dict(id=S.card_id_from_name('STRIKE_RED'), upgrades=0)], draw_pile=[], discard_pile=[],
            exhaust_pile=[], relics=[dict(id=int(S.RelicId.INCENSE_BURNER), counter=counter)], potions=[],
            monsters=[dict(id=S.monster_id_from_name('CULTIST'), current_hp=1, max_hp=48, block=0,
                           move=S.monster_move_id_from_name('CULTIST_INCANTATION'))]), 1)

    N.set_incense_values({4: 36.0, 5: 5.0})
    assert N.carried_relic_value(burner(4)) == 36 and N.carried_relic_value(burner(5)) == 5
    assert N.carried_relic_value(burner(3)) == 0
    N.set_incense_values({})
    assert N.carried_relic_value(burner(4)) == 0
    N.set_incense_targets([3])  # combat-exam fixtures: each listed target is one Intangible
    assert N.carried_relic_value(burner(3)) == 30 and N.carried_relic_value(burner(4)) == 0
    for bad in ({6: 30.0}, {-1: 30.0}, {4: -1.0}):
        try:
            N.set_incense_values(bad)
        except (ValueError, TypeError):
            pass
        else:
            raise AssertionError(('accepted', bad))
    N.set_incense_values({})
    print('PASS: native Incense Burner value follows set_incense_values')
