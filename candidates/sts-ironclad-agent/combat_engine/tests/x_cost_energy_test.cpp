// X-cost cards pay their entire energy pool, unless the card was played for free.
//
// The five X actions in the game all end with the same bytecode guard
//
//     if (freeToPlayOnce == 0) { p.energy.use(EnergyPanel.totalCount); }
//
// (WhirlwindAction, TransmutationAction, TempestAction, MulticastAction,
// ReinforcedBodyAction — decompiled with E:\sts-devkit\decompiled\decompile.sh).
// AbstractCard.useCard() pays nothing for a card whose cost is -1, so that call
// is the card's whole cost.
//
// Upstream threaded this through a `useEnergy` parameter. The Defect rework
// replaced it with an unconditional EmptyEnergy() and deleted the drain from
// WhirlwindAction/TransmutationAction, which made both Ironclad X cards free.
// This test pins both halves of the rule: a paid X card empties the pool, a
// free-played one leaves it alone.
#include "combat/Actions.h"
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"

#include <iostream>
#include <stdexcept>
#include <string>

using namespace sts;

namespace {
    void check(bool value, const std::string &why) {
        if (!value) {
            throw std::runtime_error(why);
        }
    }

    std::string name(CardId id) {
        return cardEnumStrings[static_cast<int>(id)];
    }

    // A fresh player turn against a single Cultist, empty hand and piles.
    BattleContext freshBattle(CharacterClass character) {
        GameContext game(character, 123, 0);
        game.regainControlAction = [](GameContext &) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        return battle;
    }

    void settle(BattleContext &battle) {
        for (int i = 0; i < 64 && !battle.actionQueue.isEmpty(); ++i) {
            battle.actionQueue.popFront()(battle);
        }
    }

    // Plays the card from an empty hand with `energy` in the pool. `free` marks
    // the card the way Madness and Enlightenment do before it is played, which
    // is the flag the X actions check.
    void playCard(BattleContext &battle, CardId id, int energy, bool free) {
        CardInstance card(id, false);
        if (free) {
            card.freeToPlayOnce = true;
        }
        battle.cards.createTempCardInHand(card);
        const int idx = battle.cards.cardsInHand - 1;
        battle.player.energy = energy;

        search::Action action(search::ActionType::CARD, idx, 0);
        check(action.isValidAction(battle), "card should be playable: " + name(id));
        action.execute(battle);
        settle(battle);
    }

    int damageDealt(const BattleContext &battle) {
        return battle.monsters.arr[0].maxHp - battle.monsters.arr[0].curHp;
    }

    // Whirlwind: 5 damage per energy, and the pool is gone afterwards.
    void whirlwind(int energy) {
        BattleContext paid = freshBattle(CharacterClass::IRONCLAD);
        playCard(paid, CardId::WHIRLWIND, energy, false);
        check(damageDealt(paid) == 5 * energy,
              "Whirlwind should hit once per energy");
        check(paid.player.energy == 0,
              "Whirlwind should spend the whole pool");

        BattleContext free = freshBattle(CharacterClass::IRONCLAD);
        playCard(free, CardId::WHIRLWIND, energy, true);
        check(damageDealt(free) == 5 * energy,
              "free Whirlwind should still hit once per energy");
        check(free.player.energy == energy,
              "free Whirlwind should not spend the pool");
    }

    // Transmutation: one 0-cost colorless card per energy, pool gone afterwards.
    void transmutation(int energy) {
        BattleContext paid = freshBattle(CharacterClass::IRONCLAD);
        playCard(paid, CardId::TRANSMUTATION, energy, false);
        check(paid.cards.cardsInHand == energy,
              "Transmutation should add one card per energy");
        check(paid.player.energy == 0,
              "Transmutation should spend the whole pool");

        BattleContext free = freshBattle(CharacterClass::IRONCLAD);
        playCard(free, CardId::TRANSMUTATION, energy, true);
        check(free.cards.cardsInHand == energy,
              "free Transmutation should still add one card per energy");
        check(free.player.energy == energy,
              "free Transmutation should not spend the pool");
    }

    // The three Defect X cards drain through Actions::EmptyEnergy, which has to
    // carry the same guard.
    void defectXCost(CardId id, int energy) {
        BattleContext paid = freshBattle(CharacterClass::DEFECT);
        paid.player.orbSlots = 10;
        playCard(paid, id, energy, false);
        check(paid.player.energy == 0,
              name(id) + " should spend the whole pool");

        BattleContext free = freshBattle(CharacterClass::DEFECT);
        free.player.orbSlots = 10;
        playCard(free, id, energy, true);
        check(free.player.energy == energy,
              "free " + name(id) + " should not spend the pool");
    }
}

int main() {
    try {
        for (int energy = 1; energy <= 3; ++energy) {
            whirlwind(energy);
            transmutation(energy);
        }

        // A card that cannot be paid for is rejected, not played for free.
        {
            BattleContext battle = freshBattle(CharacterClass::IRONCLAD);
            battle.cards.createTempCardInHand(CardInstance(CardId::WHIRLWIND, false));
            battle.player.energy = 0;
            battle.cards.createTempCardInHand(CardInstance(CardId::STRIKE_RED, false));
            battle.player.energy = 0;
            const int idx = battle.cards.cardsInHand - 1;
            check(!search::Action(search::ActionType::CARD, idx, 0).isValidAction(battle),
                  "an unaffordable Whirlwind should not be playable");
        }

        for (int energy = 1; energy <= 3; ++energy) {
            defectXCost(CardId::TEMPEST, energy);
            defectXCost(CardId::MULTI_CAST, energy);
            defectXCost(CardId::REINFORCED_BODY, energy);
        }

        std::cout << "PASS: paid X cards empty the pool, free-played ones keep it"
                     " (Whirlwind, Transmutation, Tempest, Multi-Cast, Reinforced Body)\n";
    } catch (const std::exception &e) {
        std::cerr << "FAIL: " << e.what() << "\n";
        return 1;
    }
}
