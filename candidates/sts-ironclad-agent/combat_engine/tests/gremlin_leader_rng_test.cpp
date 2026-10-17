#include <cassert>
#include <iostream>
#include "combat/BattleContext.h"
#include "game/GameContext.h"

using namespace sts;

static BattleContext importedOrder() {
    GameContext game(CharacterClass::IRONCLAD, 123, 20);
    BattleContext battle;
    battle.init(game, MonsterEncounter::GREMLIN_LEADER);
    assert(Monster::getAliveGremlinCount(battle) == 2);
    int leader = -1;
    for (int i = 0; i < battle.monsters.monsterCount; ++i)
        if (battle.monsters.arr[i].id == MonsterId::GREMLIN_LEADER) leader = i;
    assert(leader >= 2);
    battle.monsters.arr[2] = battle.monsters.arr[leader];
    battle.monsters.arr[2].idx = 2;
    if (leader != 2) battle.monsters.arr[leader] = Monster();
    battle.monsters.monsterCount = 3;
    battle.monsters.arr[0].curHp = 0;
    battle.monsters.monstersAlive = 2;
    return battle;
}

int main() {
    auto battle = importedOrder();
    assert(Monster::getAliveGremlinCount(battle) == 1);
    auto &leader = battle.monsters.arr[2];
    leader.moveHistory[0] = MMID::GREMLIN_LEADER_STAB;
    std::uint64_t seed = 1;
    for (; seed < 1000000; ++seed) {
        Random probe(seed);
        if (probe.random(0, 80) == 80 && probe.random(0, 80) == 80) break;
    }
    assert(seed < 1000000);
    battle.aiRng = Random(seed);
    Random truth = battle.aiRng;
    int roll;
    do { roll = truth.random(0, 80); } while (roll == 80);
    int data = 0;
    auto move = leader.getMoveForRoll(battle, data, 98);
    assert(move == (roll < 50 ? MMID::GREMLIN_LEADER_RALLY : MMID::GREMLIN_LEADER_ENCOURAGE));
    assert(battle.aiRng.counter == truth.counter && battle.aiRng.seed0 == truth.seed0 && battle.aiRng.seed1 == truth.seed1);

    auto buff = importedOrder();
    for (int i = 0; i < 3; ++i) buff.monsters.arr[i].block = 0;
    buff.monsters.arr[2].moveHistory[0] = MMID::GREMLIN_LEADER_ENCOURAGE;
    buff.monsters.arr[2].takeTurn(buff);
    assert(buff.monsters.arr[2].block == 0 && buff.monsters.arr[1].block == 10);
    std::cout << "PASS: imported minion roles, repeated roll 80 RNG and leader buff target\n";
}
