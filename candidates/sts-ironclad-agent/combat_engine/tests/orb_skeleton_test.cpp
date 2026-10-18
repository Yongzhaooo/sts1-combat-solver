#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"
#include <iostream>
#include <stdexcept>
#include <string>

using namespace sts;
static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

// Defect orb skeleton (M3a): starting slots, channel rules, Zap, Dualcast, and
// lightning/frost settlement. Dark and plasma orbs stay unimplemented on purpose.
int main() {
    try {
    GameContext game(CharacterClass::DEFECT, 123, 0);
    game.regainControlAction = [](GameContext&) {};
    BattleContext battle;
    battle.init(game, MonsterEncounter::CULTIST);
    battle.cards = CardManager{};

    check(battle.player.orbSlots == 3, "Defect should start with 3 orb slots");
    // Cracked Core's reserved battle-start hook channels one lightning orb.
    check(battle.player.orbs.size() == 1 && battle.player.orbs[0].type == Orb::LIGHTNING,
          "Cracked Core should channel one lightning orb at battle start");

    // Channel fills empty slots left to right.
    battle.player.channelOrb(Orb::FROST);
    check(battle.player.orbs.size() == 2, "two channels should fill two slots");
    check(battle.player.orbs[0].type == Orb::LIGHTNING, "first channel should be leftmost");
    check(battle.player.orbs[1].type == Orb::FROST, "second channel should be right of it");

    // A full bar pushes out the leftmost orb.
    battle.player.channelOrb(Orb::LIGHTNING); // bar: lightning, frost, lightning
    battle.player.channelOrb(Orb::LIGHTNING); // pushes out the leftmost lightning
    check(battle.player.orbs.size() == 3, "bar is capped at slot count");
    check(battle.player.orbs[0].type == Orb::FROST, "full bar should push out the leftmost");

    // Zap channels a lightning orb at the right end; upgraded adds +3 evoke bonus.
    battle.cards.createTempCardInHand(CardInstance(CardId::ZAP));
    search::Action zap(search::ActionType::CARD, 0, 0);
    check(zap.isValidAction(battle), "Zap should be playable");
    zap.execute(battle);
    check(battle.player.orbs.size() == 3 && battle.player.orbs.back().type == Orb::LIGHTNING
          && battle.player.orbs.back().evokeBonus == 0, "Zap should channel a plain lightning orb");

    // Frost evoke grants 5 block; lightning evoke deals 5 to a random enemy.
    battle.player.channelOrb(Orb::FROST); // pushes out the leftmost frost
    Actions::EvokeOrbAction(battle.player.orbs.size() - 1).actFunc(battle);
    // Settle only what evoke queued; a full executeActions run would reach turn end.
    battle.setState(InputState::EXECUTING_ACTIONS);
    for (int i = 0; i < 8 && !battle.actionQueue.isEmpty(); ++i) {
        battle.actionQueue.popFront()(battle);
    }
    check(battle.player.block == 5, "frost evoke should grant 5 block");
    check(battle.player.orbs.size() == 2, "evoking should consume the orb");

    // End-of-turn passives settle from the reserved hook and never consume orbs.
    battle.player.channelOrb(Orb::LIGHTNING); // bar: lightning, lightning, lightning
    const int orbCount = battle.player.orbs.size();
    const int cultistHp = battle.monsters.arr[0].curHp;
    Actions::TriggerEndOfTurnOrbsAction().actFunc(battle);
    for (int i = 0; i < 16 && !battle.actionQueue.isEmpty(); ++i) {
        battle.actionQueue.popFront()(battle);
    }
    check(cultistHp - battle.monsters.arr[0].curHp == 3 * orbCount,
          "each lightning passive should deal 3");
    check(battle.player.orbs.size() == orbCount, "end-of-turn passives should not consume orbs");

    // Dualcast evokes the leftmost orb twice, consuming it once.
    battle.setState(InputState::PLAYER_NORMAL); // card actions require the player-turn state
    battle.player.channelOrb(Orb::LIGHTNING); // bar: lightning, lightning, lightning
    const int hpBefore = battle.monsters.arr[0].curHp;
    battle.cards.createTempCardInHand(CardInstance(CardId::DUALCAST));
    search::Action dual(search::ActionType::CARD, 0, 0);
    check(dual.isValidAction(battle), "Dualcast should be playable");
    dual.execute(battle);
    check(battle.player.orbs.size() == 2, "Dualcast should consume one orb for two evokes");
    check(hpBefore - battle.monsters.arr[0].curHp >= 5, "dualcast lightning should damage the enemy");

    std::cout << "orb slots, channel rules, Zap, Dualcast and lightning/frost settlement hold\n";
    return 0;
    } catch (const std::exception &e) {
        std::cout << "FAILED: " << e.what() << std::endl;
        return 1;
    }
}
