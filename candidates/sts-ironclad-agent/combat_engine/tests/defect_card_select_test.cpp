#include <cassert>
#include <iostream>
#include <stdexcept>
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"

using namespace sts;

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

// The searcher died on a live Defect run: the card-select enumerators knew
// nothing about HOLOGRAM, RECYCLE or SEEK, and their default case asserted.
// The validity checker and the executors already support these tasks, so the
// enumerations are what was missing.
static BattleContext fresh() {
    GameContext game(CharacterClass::DEFECT, 123, 0);
    game.regainControlAction = [](GameContext&) {};
    BattleContext battle;
    battle.init(game, MonsterEncounter::CULTIST);
    battle.cards = CardManager{};
    battle.setState(InputState::PLAYER_NORMAL);
    return battle;
}

static void drain(BattleContext &battle) {
    battle.setState(InputState::EXECUTING_ACTIONS);
    for (int i = 0; i < 32 && !battle.actionQueue.isEmpty(); ++i) {
        battle.actionQueue.popFront()(battle);
    }
    battle.setState(InputState::PLAYER_NORMAL);
}

// Creates the card in the empty hand and plays it at index 0.
static void playCard(BattleContext &battle, CardId id) {
    battle.cards.createTempCardInHand(CardInstance(id));
    const int idx = battle.cards.cardsInHand - 1;
    search::Action play(search::ActionType::CARD, idx, 0);
    if (!play.isValidAction(battle)) {
        throw std::runtime_error(std::string("card should be playable: ")
            + cardEnumStrings[static_cast<int>(id)]);
    }
    play.execute(battle);
}

static std::vector<search::Action> enumerate(const BattleContext &battle) {
    return search::Action::enumerateCardSelectActions(battle);
}

int main() {
    try {
    {
        // Hologram moves one card from the discard pile into the hand.
        BattleContext bc = fresh();
        bc.player.energy = 5;
        // Two candidates: with one the implementation picks it without a screen.
        bc.cards.discardPile.push_back(CardInstance(CardId::CLASH));
        bc.cards.discardPile.push_back(CardInstance(CardId::BLUDGEON));
        playCard(bc, CardId::HOLOGRAM);
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::HOLOGRAM,
              "playing Hologram should open its select");
        const auto actions = enumerate(bc);
        check(actions.size() == 2, "Hologram should enumerate both discard cards");
        check(actions.front().isValidAction(bc), "the first enumerated pick must be valid");
        actions.front().execute(bc);
        check(bc.cards.cardsInHand == 1, "the picked card should join the hand");
        check(bc.cards.discardPile.size() == 1, "the discard pile should lose one card");
    }

    {
        // Recycle exhausts one hand card and gains energy equal to its cost.
        BattleContext bc = fresh();
        bc.player.energy = 5;
        // Two candidates besides Recycle itself.
        bc.cards.createTempCardInHand(CardInstance(CardId::DEFEND_BLUE));
        bc.cards.createTempCardInHand(CardInstance(CardId::STRIKE_BLUE));
        playCard(bc, CardId::RECYCLE);
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::RECYCLE,
              "playing Recycle should open its select");
        const auto actions = enumerate(bc);
        check(actions.size() == 2, "Recycle should enumerate both hand cards");
        const int energyBefore = bc.player.energy;
        actions.front().execute(bc);
        check(bc.cards.cardsInHand == 1, "Recycle should exhaust the picked card");
        check(bc.player.energy == energyBefore + 1, "Recycle should gain its cost");
    }

    {
        // Seek pulls one card from the draw pile; the executor reopens for Seek+.
        BattleContext bc = fresh();
        bc.player.energy = 5;
        bc.cards.drawPile.push_back(CardInstance(CardId::CLASH));
        playCard(bc, CardId::SEEK);
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::SEEK,
              "playing Seek should open its select");
        const auto actions = enumerate(bc);
        check(!actions.empty(), "Seek must enumerate draw pile options");
        actions.front().execute(bc);
        check(bc.cards.cardsInHand >= 1, "the sought card should join the hand");
        check(bc.cards.drawPile.empty(), "the draw pile should be emptied");
    }

    std::cout << "Defect card select enumeration holds\n";
    } catch (const std::exception &error) {
        std::cout << error.what() << "\n";
        return 1;
    }
    return 0;
}