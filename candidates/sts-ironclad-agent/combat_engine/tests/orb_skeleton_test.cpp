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

// Defect orb skeleton: starting slots, channel rules (including the full-bar
// evoke of the oldest orb), slot clamping, Zap, Dualcast, and lightning/frost
// settlement. Dark and plasma orbs stay unimplemented on purpose.
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

    // Channel actions append after the oldest orb (index 0 holds the oldest).
    Actions::ChannelOrb(Orb::FROST).actFunc(battle);
    Actions::ChannelOrb(Orb::FROST).actFunc(battle);
    check(battle.player.orbs.size() == 3, "three channels should fill the bar");
    check(battle.player.orbs[0].type == Orb::LIGHTNING && battle.player.orbs[1].type == Orb::FROST,
          "channeled orbs append after the oldest");

    // A full bar evokes the oldest orb (its effects fire) before the new orb lands.
    const int cultistHp0 = battle.monsters.arr[0].curHp;
    Actions::ChannelOrb(Orb::LIGHTNING).actFunc(battle);
    battle.setState(InputState::EXECUTING_ACTIONS);
    for (int i = 0; i < 8 && !battle.actionQueue.isEmpty(); ++i) {
        battle.actionQueue.popFront()(battle);
    }
    check(battle.player.orbs.size() == 3, "a full bar keeps the slot count");
    check(battle.player.orbs[0].type == Orb::FROST, "the evoked oldest orb should leave the bar");
    check(cultistHp0 - battle.monsters.arr[0].curHp == 8,
          "the pushed-out lightning should evoke for 8");

    // Frost evoke grants 5 block and consumes the orb.
    battle.setState(InputState::EXECUTING_ACTIONS);
    Actions::EvokeOrbAction(0).actFunc(battle);
    for (int i = 0; i < 8 && !battle.actionQueue.isEmpty(); ++i) {
        battle.actionQueue.popFront()(battle);
    }
    check(battle.player.block == 5, "frost evoke should grant 5 block");
    check(battle.player.orbs.size() == 2, "evoking should consume the orb");

    // End-of-turn passives settle left to right and never consume orbs.
    const int cultistHp1 = battle.monsters.arr[0].curHp;
    Actions::TriggerEndOfTurnOrbsAction().actFunc(battle);
    for (int i = 0; i < 16 && !battle.actionQueue.isEmpty(); ++i) {
        battle.actionQueue.popFront()(battle);
    }
    check(battle.player.block == 7, "frost passive should grant 2 block");
    check(cultistHp1 - battle.monsters.arr[0].curHp == 3, "lightning passive should deal 3");
    check(battle.player.orbs.size() == 2, "end-of-turn passives should not consume orbs");

    // Zap channels a plain lightning orb into the open slot.
    battle.setState(InputState::PLAYER_NORMAL);
    battle.cards.createTempCardInHand(CardInstance(CardId::ZAP));
    search::Action zap(search::ActionType::CARD, 0, 0);
    check(zap.isValidAction(battle), "Zap should be playable");
    zap.execute(battle);
    check(battle.player.orbs.size() == 3 && battle.player.orbs.back().type == Orb::LIGHTNING
          && battle.player.orbs.back().darkCounter == 0, "Zap should channel a plain lightning orb");

    // Dualcast evokes the oldest orb twice and consumes it once. Here the oldest
    // is a Frost orb: two evokes grant 10 block and deal no damage.
    battle.setState(InputState::PLAYER_NORMAL);
    const int cultistHp2 = battle.monsters.arr[0].curHp;
    battle.cards.createTempCardInHand(CardInstance(CardId::DUALCAST));
    search::Action dual(search::ActionType::CARD, 0, 0);
    check(dual.isValidAction(battle), "Dualcast should be playable");
    dual.execute(battle);
    check(battle.player.orbs.size() == 2, "Dualcast should consume one orb for two evokes");
    check(battle.player.orbs[0].type == Orb::LIGHTNING, "Dualcast should evoke the oldest orb");
    check(battle.player.block == 17, "two frost evokes should grant 10 more block");
    check(cultistHp2 == battle.monsters.arr[0].curHp, "frost evokes should deal no damage");

    // A second Dualcast on a lightning orb reproduces the fixture pattern 8+8=16.
    battle.setState(InputState::PLAYER_NORMAL);
    const int cultistHpDc2 = battle.monsters.arr[0].curHp;
    battle.cards.createTempCardInHand(CardInstance(CardId::DUALCAST));
    search::Action dual2(search::ActionType::CARD, 0, 0);
    check(dual2.isValidAction(battle), "second Dualcast should be playable");
    dual2.execute(battle);
    check(battle.player.orbs.size() == 1, "the second Dualcast should consume its orb");
    check(cultistHpDc2 - battle.monsters.arr[0].curHp == 16, "Dualcast lightning evokes should deal 16");

    // Vanilla clamps orb slots at 10 and never below 0.
    battle.player.increaseOrbSlots(999);
    check(battle.player.orbSlots == 10, "orb slots should clamp at the vanilla cap of 10");
    battle.player.increaseOrbSlots(-999);
    check(battle.player.orbSlots == 0, "orb slots should never go below zero");

    // With no slots a channel does nothing and never triggers the orb's evoke.
    // The bar still holds one lightning orb from the second Dualcast.
    const int cultistHp3 = battle.monsters.arr[0].curHp;
    Actions::ChannelOrb(Orb::LIGHTNING).actFunc(battle);
    check(battle.player.orbs.size() == 1, "channeling without slots should do nothing");

    // With no slots there is no push-out evoke either: no damage was rolled.
    check(battle.monsters.arr[0].curHp == cultistHp3,
         "channeling without slots should not evoke anything");

    std::cout << "orb slots, channel rules, Zap, Dualcast and lightning/frost settlement hold\n";
    return 0;
    } catch (const std::exception &e) {
        std::cout << "FAILED: " << e.what() << std::endl;
        return 1;
    }
}
