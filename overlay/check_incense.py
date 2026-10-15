"""Check the Incense Burner carry rule without a game."""
from recovery import incense_targets


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
print('PASS: incense targets')
