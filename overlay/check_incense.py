"""Check the Incense Burner carry rule without a game."""
from recovery import carry_weight, incense_targets


def game(act=1, boss='The Guardian', last=None, room='MonsterRoom', ahead=True, heart=False):
    return {'act': act, 'act_boss': boss, 'solver_last_elite': last, 'room_type': room,
            'solver_elite_ahead': ahead,
            'combat_state': {'monsters': [{'id': 'CorruptHeart' if heart else 'JawWorm'}]}}


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
