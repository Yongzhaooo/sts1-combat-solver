#include <iostream>
#include <stdexcept>
#include "combat/Actions.h"
#include "combat/BattleContext.h"
#include "game/GameContext.h"

using namespace sts;

// Release builds define NDEBUG, so use an exception instead of assert.
static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

// Java AbstractCreature.addBlock caps block at 999; monster block gains must too.
int main() {
    GameContext game(CharacterClass::IRONCLAD, 123, 20);
    BattleContext battle;
    battle.init(game, MonsterEncounter::SPHERIC_GUARDIAN);
    battle.actionQueue.clear();
    auto &guardian = battle.monsters.arr[0];
    guardian.block = 989;
    guardian.moveHistory[0] = MMID::SPHERIC_GUARDIAN_HARDEN;
    guardian.takeTurn(battle);
    check(!battle.actionQueue.isEmpty(), "Spheric Guardian queued no block");
    battle.actionQueue.popFront()(battle);
    check(guardian.block == 999, "queued monster block must stop at 999");
    Actions::MonsterGainBlock(0, 15).actFunc(battle);
    check(guardian.block == 999, "MonsterGainBlock must stop at 999");

    Monster monster;
    monster.block = 984;
    monster.addBlock(20);
    check(monster.block == 999, "addBlock must stop at 999");
    monster.addBlock(20);
    check(monster.block == 999, "addBlock at the cap must stay at 999");
    monster.block = 10;
    monster.addBlock(20);
    check(monster.block == 30, "addBlock below the cap adds normally");
    monster.addBlock(0);
    check(monster.block == 30, "addBlock(0) changes nothing");
    std::cout << "monster block cap: queued, direct and below-cap gains passed\n";
}
