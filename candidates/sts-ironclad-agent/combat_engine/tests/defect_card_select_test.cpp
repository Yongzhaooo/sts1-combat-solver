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

// Recycle enumerates one action per hand card in hand order, so the action index
// equals the hand index of the card it picks. Look the card up by id: playing
// Recycle itself reshuffles the hand, so the position is not known in advance.
static int pickIdx(const BattleContext &battle, CardId id) {
    for (int i = 0; i < battle.cards.cardsInHand; ++i) {
        if (battle.cards.hand[i].getId() == id) {
            return i;
        }
    }
    throw std::runtime_error("card not in hand while enumerating the select");
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
        // Seek's action moves the whole pile straight to hand when the pile holds
        // no more cards than it asks for, so a one-card pile never opens a grid.
        BattleContext bc = fresh();
        bc.player.energy = 5;
        bc.cards.drawPile.push_back(CardInstance(CardId::CLASH));
        const int handBefore = bc.cards.cardsInHand;
        playCard(bc, CardId::SEEK);
        check(bc.cardSelectInfo.cardSelectTask != CardSelectTask::SEEK,
              "Seek should not open a grid when the pile fits in one hand's worth");
        check(bc.inputState == InputState::PLAYER_NORMAL, "the straight-to-hand path should not open a picker");
        check(bc.cards.cardsInHand == handBefore + 1, "the whole pile should join the hand");
        check(bc.cards.drawPile.empty(), "the draw pile should be emptied");
    }

    {
        // With more cards than it asks for, Seek opens the grid instead.
        BattleContext bc = fresh();
        bc.player.energy = 5;
        bc.cards.drawPile.push_back(CardInstance(CardId::CLASH));
        bc.cards.drawPile.push_back(CardInstance(CardId::BASH));
        playCard(bc, CardId::SEEK);
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::SEEK,
              "playing Seek should open its select when the pile is larger than the ask");
        const auto actions = enumerate(bc);
        check(!actions.empty(), "Seek must enumerate draw pile options");
        actions.front().execute(bc);
        check(bc.cards.cardsInHand >= 1, "the sought card should join the hand");
        check(bc.cards.drawPile.size() == 1, "only the sought card should leave the pile");
    }

    {
        // The live F55 run diverged here. Tempest is an X-cost card (cost -1),
        // but Confusion had randomised its costForTurn to 0. RecycleAction reads
        // the *number*: a 0 cost grants nothing, while the -1 sentinel grants all
        // the energy available. The engine used to answer "all of it" here, so
        // the solver predicted energy it never got on the next play.
        BattleContext bc = fresh();
        bc.cards.createTempCardInHand(CardInstance(CardId::TEMPEST));
        bc.cards.createTempCardInHand(CardInstance(CardId::STRIKE_BLUE));
        // Confusion randomises the this-turn cost of every card it shuffles in,
        // X-cost ones included; that is the only way a Tempest ends up at 0.
        bc.cards.hand[0].costForTurn = 0;
        check(bc.cards.hand[0].costForTurn == 0,
              "Confusion should leave a 0 this-turn cost on the X card");
        bc.player.energy = 3;
        playCard(bc, CardId::RECYCLE); // spends its own 1 energy
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::RECYCLE,
              "playing Recycle should open its select");
        const auto actions = enumerate(bc);
        check(actions.size() == 2, "Recycle should enumerate both hand cards");
        const int energyBefore = bc.player.energy;
        const int tempestIdx = pickIdx(bc, CardId::TEMPEST);
        actions[tempestIdx].execute(bc);
        check(bc.player.energy == energyBefore,
              "Recycling a 0-cost X card must grant its printed 0, not the whole pool");
        check(bc.cards.cardsInHand == 1, "Recycle should exhaust the picked card");
    }

    {
        // Without Confusion the X card keeps its -1 sentinel, so recycling it
        // grants everything currently available (RecycleAction's X branch).
        BattleContext bc = fresh();
        bc.cards.createTempCardInHand(CardInstance(CardId::TEMPEST));
        bc.cards.createTempCardInHand(CardInstance(CardId::STRIKE_BLUE));
        bc.player.energy = 3;
        playCard(bc, CardId::RECYCLE); // spends its own 1 energy
        const auto actions = enumerate(bc);
        check(actions.size() == 2, "Recycle should enumerate both hand cards");
        const int energyBefore = bc.player.energy;
        const int tempestIdx = pickIdx(bc, CardId::TEMPEST);
        actions[tempestIdx].execute(bc);
        check(bc.player.energy == energyBefore * 2,
              "Recycling an X card with no confusion should grant the whole pool");
        check(bc.cards.cardsInHand == 1, "Recycle should exhaust the picked card");
    }

    {
        // A picker a card opened mid-turn is restored by the bridge instead of
        // by replaying a queue: the game shows the pile and the simulator has to
        // agree on it. Two picks means the executor reopens the screen, which is
        // the upgraded Seek case.
        BattleContext bc = fresh();
        bc.cards.drawPile.push_back(CardInstance(CardId::CLASH));
        bc.cards.drawPile.push_back(CardInstance(CardId::BASH));
        bc.openSimpleCardSelectScreen(CardSelectTask::SEEK, 2);
        const int handBefore = bc.cards.cardsInHand;
        auto actions = enumerate(bc);
        check(actions.size() == 2, "an opened SEEK screen should list the whole draw pile");
        actions.front().execute(bc);
        // The executor leaves the screen open while picks remain; the caller
        // resolves what it queued before asking for the next decision.
        check(bc.inputState == InputState::CARD_SELECT,
              "a two-pick SEEK should keep its screen open for the second pick");
        check(bc.cards.cardsInHand == handBefore,
              "a pick that is still on screen must not join the hand yet");
        check(bc.cardSelectInfo.pickCount == 1, "the reopened screen should ask for the one remaining pick");
        actions = enumerate(bc);
        check(actions.size() == 1, "a card already picked must not be offered a second time");
        check(actions.front().getSelectIdx() != 0,
              "the remaining pick should be the card still on the grid");
        actions.front().execute(bc);
        check(bc.inputState == InputState::PLAYER_NORMAL, "the last pick should close the screen");
        check(bc.cards.cardsInHand == handBefore + 2, "both sought cards should join the hand");
        // A closed picker must not leave a task behind: a copy taken here used
        // to enumerate nothing, which aborted the whole search process.
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::INVALID,
              "the closed SEEK screen should clear its task");
        check(bc.cardSelectInfo.pickCount == 0, "the closed SEEK screen should clear its count");
    }

    {
        // The same shape at the end of the pile: the second pick is moot once
        // the draw pile ran out, and the screen still has to close cleanly.
        BattleContext bc = fresh();
        bc.cards.drawPile.push_back(CardInstance(CardId::CLASH));
        bc.openSimpleCardSelectScreen(CardSelectTask::SEEK, 2);
        auto actions = enumerate(bc);
        check(actions.size() == 1, "a one-card pile should offer the single card");
        actions.front().execute(bc);
        check(bc.inputState == InputState::PLAYER_NORMAL,
              "an emptied draw pile should close the screen instead of reopening it");
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::INVALID,
              "the emptied SEEK screen should clear its task");
    }

    {
        // Only Seek reopens its own screen. Every other picker closes on its
        // pick, and chooseDiscoveryCard is one of the helpers that never touches
        // inputState, so the reset after execute() is the only thing that closes
        // it. The live floor-18 run ran out of memory because a rollout kept
        // picking Discovery forever: the screen stayed open, the discard pile
        // grew past two thousand cards and the turn never ended.
        BattleContext bc = fresh();
        bc.openDiscoveryScreen(
            std::array<CardId, 3>{CardId::FLAME_BARRIER, CardId::IMPERVIOUS, CardId::BATTLE_TRANCE}, 1);
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::DISCOVERY,
              "the opened Discovery screen should carry its task");
        auto actions = enumerate(bc);
        check(actions.size() == 3, "a Discovery screen should list its three cards");
        const int handBefore = bc.cards.cardsInHand;
        actions.front().execute(bc);
        check(bc.inputState == InputState::PLAYER_NORMAL,
              "a one-pick Discovery should close its screen on the pick");
        check(bc.cards.cardsInHand == handBefore + 1, "the discovered card should join the hand");
    }

    std::cout << "Defect card select enumeration holds\n";
    } catch (const std::exception &error) {
        std::cout << error.what() << "\n";
        return 1;
    }
    return 0;
}