#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include <iostream>
#include <stdexcept>

using namespace sts;

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

namespace {
    // A louse keeps Curl Up from its pre-battle action, which is where the
    // original rolls 3-7 (9-12 at Ascension 17). The engine models that already;
    // what these cases pin down is when the power is spent.
    // Curl Up, Malleable, Flight and the other "unique" monster powers share one
    // storage slot in the engine, so a case that needs two of them asks for none.
    BattleContext freshBattle(int curlUp = 6) {
        GameContext game(CharacterClass::DEFECT, 123, 0);
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        if (curlUp > 0) battle.monsters.arr[0].buff<MS::CURL_UP>(curlUp);
        return battle;
    }

    void drain(BattleContext &battle) {
        battle.setState(InputState::EXECUTING_ACTIONS);
        for (int i = 0; i < 32 && !battle.actionQueue.isEmpty(); ++i) {
            battle.actionQueue.popFront()(battle);
        }
    }
}

int main() {
    try {
    {
        // CurlUpPower.onAttacked only reacts to NORMAL damage that does not kill:
        // the louse rolls up, gains Block equal to the power and loses it.
        BattleContext bc = freshBattle();
        Actions::AttackEnemy(0, 5).actFunc(bc);
        drain(bc);
        check(!bc.monsters.arr[0].hasStatus<MS::CURL_UP>(),
              "a normal hit should spend Curl Up");
        check(bc.monsters.arr[0].block == 6,
              "Curl Up should grant Block equal to its amount");
        check(bc.monsters.arr[0].curHp == bc.monsters.arr[0].maxHp - 5,
              "Curl Up must not absorb the hit");
    }

    {
        // Orb damage is typed THORNS in the game (LightningOrb and Dark build
        // their DamageInfo with DamageType.THORNS), and CurlUpPower skips anything
        // that is not NORMAL. A live Defect battle diverged here: the simulator
        // spent the power on the orb hit and the game kept it.
        BattleContext bc = freshBattle();
        Actions::AttackEnemy(0, 5, DamageType::THORNS).actFunc(bc);
        drain(bc);
        check(bc.monsters.arr[0].hasStatus<MS::CURL_UP>(),
              "THORNS-typed damage must not spend Curl Up");
        check(bc.monsters.arr[0].block == 0, "Curl Up must not grant Block here");
    }

    {
        // A killing blow returns before the power is spent.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        Actions::AttackEnemy(0, hp).actFunc(bc);
        drain(bc);
        check(bc.monsters.arr[0].isDeadOrEscaped(), "the louse should be dead");
        check(bc.monsters.arr[0].block == 0, "a killing blow grants no Block");
    }

    {
        // Fully blocked damage reaches the callback as zero.
        BattleContext bc = freshBattle();
        bc.monsters.arr[0].block = 20;
        Actions::AttackEnemy(0, 5).actFunc(bc);
        drain(bc);
        check(bc.monsters.arr[0].hasStatus<MS::CURL_UP>(),
              "a fully blocked hit must not spend Curl Up");
    }

    {
        // The whole orb path, not just the flag: the end-of-turn Lightning passive
        // hits a curled-up louse and leaves the power alone.
        BattleContext bc = freshBattle();
        bc.player.orbs.clear();
        bc.player.channelOrb(Orb::LIGHTNING);
        bc.monsters.arr[0].buff<MS::CURL_UP>(4);
        const int hp = bc.monsters.arr[0].curHp;
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
        drain(bc);
        check(bc.monsters.arr[0].curHp < hp, "the Lightning passive should hit");
        check(bc.monsters.arr[0].hasStatus<MS::CURL_UP>(),
              "orb damage must leave Curl Up alone");
        check(bc.monsters.arr[0].block == 0, "orb damage must not grant Block");
    }

    {
        // Dark's evoke is the other THORNS-typed attack.
        BattleContext bc = freshBattle();
        bc.player.orbs.clear();
        bc.player.channelOrb(Orb::DARK, 9);
        Actions::EvokeOrbAction(0, 1).actFunc(bc);
        drain(bc);
        check(bc.monsters.arr[0].hasStatus<MS::CURL_UP>(),
              "Dark evoke damage must leave Curl Up alone");
    }

    {
        // MalleablePower requires NORMAL, ReactivePower skips THORNS: orb damage
        // must not inflate Malleable or arm Reactive either.
        BattleContext bc = freshBattle(0);
        bc.monsters.arr[0].buff<MS::MALLEABLE>(3);
        bc.monsters.arr[0].buff<MS::REACTIVE>(2);
        Actions::AttackEnemy(0, 5, DamageType::THORNS).actFunc(bc);
        drain(bc);
        check(bc.monsters.arr[0].getStatus<MS::MALLEABLE>() == 3,
              "THORNS damage must not raise Malleable");
        check(bc.monsters.arr[0].getStatus<MS::REACTIVE>() == 2,
              "THORNS damage must not change Reactive");

        BattleContext normal = freshBattle(0);
        normal.monsters.arr[0].buff<MS::MALLEABLE>(3);
        Actions::AttackEnemy(0, 5).actFunc(normal);
        drain(normal);
        check(normal.monsters.arr[0].getStatus<MS::MALLEABLE>() == 4,
              "a normal hit should still raise Malleable");
        check(normal.monsters.arr[0].block == 3,
              "a normal hit should still grant Malleable Block");
    }

    {
        // DamageAllEnemy (The Bomb, Mercury Hourglass, and the Explosive Potion,
        // whose owner is null) never reacts to Curl Up; explosive_potion_test
        // covers the potion itself.
        BattleContext bomb = freshBattle();
        Actions::DamageAllEnemy(10).actFunc(bomb);
        drain(bomb);
        check(bomb.monsters.arr[0].hasStatus<MS::CURL_UP>(),
              "the bomb's THORNS damage must leave Curl Up alone");
        check(bomb.monsters.arr[0].curHp == bomb.monsters.arr[0].maxHp - 10,
              "the bomb should deal its full damage");
    }

    {
        // Four more hooks the game gates on the DamageInfo type, so the THORNS damage an
        // orb deals must not set them off: AngryPower.onAttacked and ThornsPower.onAttacked
        // skip THORNS, PlatedArmorPower.wasHPLost skips THORNS and HP_LOSS, and
        // EnvenomPower.onAttack requires NORMAL. Each case asks for one power only: the
        // engine stores the "unique" monster powers in a single slot.
        BattleContext angry = freshBattle(0);
        angry.monsters.arr[0].buff<MS::ANGRY>(1);
        Actions::AttackEnemy(0, 5, DamageType::THORNS).actFunc(angry);
        drain(angry);
        check(!angry.monsters.arr[0].hasStatus<MS::STRENGTH>(),
              "THORNS damage must not raise Angry's Strength");
        BattleContext angryNormal = freshBattle(0);
        angryNormal.monsters.arr[0].buff<MS::ANGRY>(1);
        Actions::AttackEnemy(0, 5, DamageType::NORMAL).actFunc(angryNormal);
        drain(angryNormal);
        check(angryNormal.monsters.arr[0].getStatus<MS::STRENGTH>() == 1,
              "a normal hit should still raise Angry's Strength");

        BattleContext thorns = freshBattle(0);
        thorns.monsters.arr[0].buff<MS::THORNS>(3);
        const int hpBefore = thorns.player.curHp;
        Actions::AttackEnemy(0, 5, DamageType::THORNS).actFunc(thorns);
        drain(thorns);
        check(thorns.player.curHp == hpBefore,
              "THORNS damage must not set off the monster's own Thorns");

        BattleContext plated = freshBattle(0);
        plated.monsters.arr[0].buff<MS::PLATED_ARMOR>(4);
        Actions::AttackEnemy(0, 5, DamageType::THORNS).actFunc(plated);
        drain(plated);
        check(plated.monsters.arr[0].getStatus<MS::PLATED_ARMOR>() == 4,
              "THORNS damage must not spend Plated Armor");

        BattleContext envenom = freshBattle(0);
        envenom.player.buff<PS::ENVENOM>(2);
        Actions::AttackEnemy(0, 5, DamageType::THORNS).actFunc(envenom);
        drain(envenom);
        check(!envenom.monsters.arr[0].hasStatus<MS::POISON>(),
              "THORNS damage must not apply Envenom's poison");
    }

    std::cout << "Orb damage type holds\n";
    } catch (const std::exception &error) {
        std::cout << error.what() << "\n";
        return 1;
    }
    return 0;
}