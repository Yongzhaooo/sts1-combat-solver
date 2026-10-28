#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include <cstdio>
#include <stdexcept>

using namespace sts;

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

namespace {
    BattleContext freshBattle() {
        GameContext game(CharacterClass::IRONCLAD, 123, 0);
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        return battle;
    }

    void drain(BattleContext &battle) {
        battle.setState(InputState::EXECUTING_ACTIONS);
        for (int i = 0; i < 32 && !battle.actionQueue.isEmpty(); ++i) {
            battle.actionQueue.popFront()(battle);
        }
    }

    void drinkExplosive(BattleContext &battle) {
        battle.potions[0] = Potion::EXPLOSIVE_POTION;
        battle.potionCount = 1;
        battle.drinkPotion(0, 0);
        drain(battle);
    }
}

// ExplosivePotion.use builds DamageAllEnemiesAction with a null owner (javap on
// desktop-1.0.jar), and the monster powers below all require info.owner != null,
// so the potion's blast must not set any of them off.
int main() {
    try {
    {
        BattleContext bc = freshBattle();
        bc.monsters.arr[0].buff<MS::CURL_UP>(6);
        const int hp = bc.monsters.arr[0].curHp;
        drinkExplosive(bc);
        check(bc.monsters.arr[0].curHp == hp - 10, "the blast should deal 10");
        check(bc.monsters.arr[0].hasStatus<MS::CURL_UP>(), "the blast must not spend Curl Up");
        check(bc.monsters.arr[0].block == 0, "the blast must not grant Curl Up Block");
    }
    {
        BattleContext bc = freshBattle();
        bc.monsters.arr[0].buff<MS::THORNS>(3);
        const int hp = bc.player.curHp;
        drinkExplosive(bc);
        check(bc.player.curHp == hp, "the blast must not set off monster Thorns");
    }
    {
        BattleContext bc = freshBattle();
        bc.monsters.arr[0].buff<MS::ANGRY>(1);
        drinkExplosive(bc);
        check(!bc.monsters.arr[0].hasStatus<MS::STRENGTH>(), "the blast must not raise Angry");
    }
    {
        // CurlUpPower.onAttacked: damage > 0 and damage < currentHealth. A killing hit
        // and a fully blocked hit leave the power alone; a surviving hit spends it.
        BattleContext bc = freshBattle();
        bc.monsters.arr[0].buff<MS::CURL_UP>(6);
        bc.monsters.arr[0].curHp = 5;
        Actions::AttackEnemy(0, 5).actFunc(bc);
        drain(bc);
        check(bc.monsters.arr[0].hasStatus<MS::CURL_UP>(), "a killing hit must not spend Curl Up");

        BattleContext blocked = freshBattle();
        blocked.monsters.arr[0].buff<MS::CURL_UP>(6);
        blocked.monsters.arr[0].block = 20;
        Actions::AttackEnemy(0, 5).actFunc(blocked);
        drain(blocked);
        check(blocked.monsters.arr[0].hasStatus<MS::CURL_UP>(), "a fully blocked hit must not spend Curl Up");

        BattleContext alive = freshBattle();
        alive.monsters.arr[0].buff<MS::CURL_UP>(6);
        alive.monsters.arr[0].curHp = 6;
        Actions::AttackEnemy(0, 5).actFunc(alive);
        drain(alive);
        check(!alive.monsters.arr[0].hasStatus<MS::CURL_UP>(), "a surviving hit should spend Curl Up");
    }
    } catch (const std::exception &e) {
        std::fputs(e.what(), stderr);
        std::fputc('\n', stderr);
        return 1;
    }
    return 0;
}
