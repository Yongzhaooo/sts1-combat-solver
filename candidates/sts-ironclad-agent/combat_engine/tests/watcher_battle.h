// Shared by the Issue #21 stance tests: a Watcher in a quiet first player turn, with an empty
// deck, no relics, and a monster that cannot die. Every number is read back off the monster's HP,
// the block and the energy.
#pragma once
#include "combat/Actions.h"
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"
#include <stdexcept>

using namespace sts;

inline void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

// A Watcher in a quiet first player turn: empty deck, no relics, a monster that cannot die.
struct WatcherBattle {
    GameContext game;
    BattleContext battle;

    WatcherBattle() : game(CharacterClass::WATCHER, 123, 20) {
        game.relics = RelicContainer{};
        game.regainControlAction = [](GameContext &) {};
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.monsters.arr[0].curHp = battle.monsters.arr[0].maxHp = 3000;
        drain();
        battle.player.energy = 10;
    }

    // executeActions returns immediately unless the context is executing, and it always ends
    // in PLAYER_NORMAL, so every drain has to re-open the queue.
    void drain() {
        battle.inputState = InputState::EXECUTING_ACTIONS;
        battle.executeActions();
    }

    // Plays the only card in hand at monster 0 and returns the damage it dealt.
    int hit(CardId card, bool upgraded = false) {
        battle.cards.createTempCardInHand(CardInstance(card, upgraded));
        search::Action action(search::ActionType::CARD, 0, 0);
        check(action.isValidAction(battle), "card is not playable");
        const int hp = battle.monsters.arr[0].curHp;
        action.execute(battle);
        return hp - battle.monsters.arr[0].curHp;
    }
};
