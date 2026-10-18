#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include <iostream>
#include <stdexcept>

using namespace sts;

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

namespace {
    // Player turn vs a single Cultist. Cracked Core's battle-start orb is cleared
    // per case so the bar only holds what the case channels.
    BattleContext freshBattle() {
        GameContext game(CharacterClass::DEFECT, 123, 0);
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

    void drink(BattleContext &battle, Potion potion) {
        battle.potions[0] = potion;
        battle.potionCount = 1;
        battle.drinkPotion(0, 0);
        drain(battle);
    }
}

// Defect potions: the three orb potions and the Sacred Bark doubling.
int main() {
    try {
    {
        // Essence of Darkness: one Dark per orb slot, each starting at 6.
        BattleContext bc = freshBattle();
        bc.player.orbs.clear();
        drink(bc, Potion::ESSENCE_OF_DARKNESS);
        check(bc.player.orbs.size() == 3, "Essence of Darkness should fill every slot");
        bool allDark = true;
        for (auto &orb : bc.player.orbs) {
            allDark = allDark && orb.type == Orb::DARK && orb.darkCounter == 6;
        }
        check(allDark, "every channelled orb should be a fresh Dark orb");
    }
    {
        // Sacred Bark doubles it: six Darks into three slots, so the overflow
        // evokes the three oldest (6 damage each, the counter never grew).
        BattleContext bc = freshBattle();
        bc.player.setHasRelic<R::SACRED_BARK>(true);
        bc.player.orbs.clear();
        const int hp = bc.monsters.arr[0].curHp;
        drink(bc, Potion::ESSENCE_OF_DARKNESS);
        check(bc.player.orbs.size() == 3, "the bar cannot exceed its slots");
        check(hp - bc.monsters.arr[0].curHp == 18,
              "the three overflow Darks should evoke for 6 each");
    }
    {
        // Focus Potion: 2 Focus, 4 with Sacred Bark.
        BattleContext bc = freshBattle();
        drink(bc, Potion::FOCUS_POTION);
        // Focus lives in Player::focus, not in the status amount map.
        check(bc.player.focus == 2, "Focus Potion should grant 2 Focus");

        BattleContext bark = freshBattle();
        bark.player.setHasRelic<R::SACRED_BARK>(true);
        drink(bark, Potion::FOCUS_POTION);
        check(bark.player.focus == 4,
              "Sacred Bark should double the Focus Potion");
    }
    {
        // Potion of Capacity: 2 orb slots, 4 with Sacred Bark.
        BattleContext bc = freshBattle();
        drink(bc, Potion::POTION_OF_CAPACITY);
        check(bc.player.orbSlots == 5, "Potion of Capacity should grant 2 slots");

        BattleContext bark = freshBattle();
        bark.player.setHasRelic<R::SACRED_BARK>(true);
        drink(bark, Potion::POTION_OF_CAPACITY);
        check(bark.player.orbSlots == 7, "Sacred Bark should double the slot potion");
    }

    std::cout << "Defect potion behaviors hold\n";
    return 0;
    } catch (const std::exception &e) {
        std::cout << "FAILED: " << e.what() << std::endl;
        return 1;
    }
}
