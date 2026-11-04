#include "combat/Actions.h"
#include "combat/BattleContext.h"
#include "constants/CardPools.h"
#include "game/GameContext.h"
#include <iostream>
#include <stdexcept>

using namespace sts;

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

static bool inClassPool(CardId id, CharacterClass cc) {
    const CardId *pool = TrulyRandomCardPool::getPoolForClass(cc);
    const int size = TrulyRandomCardPool::getPoolSizeForClass(cc);
    for (int i = 0; i < size; ++i) {
        if (pool[i] == id) return true;
    }
    return false;
}

static BattleContext freshBattle(CharacterClass cc) {
    GameContext game(cc, 123, 0);
    game.regainControlAction = [](GameContext&) {};
    BattleContext battle;
    battle.init(game, MonsterEncounter::CULTIST);
    battle.cards = CardManager{};
    battle.setState(InputState::PLAYER_NORMAL);
    return battle;
}

int main() {
    try {
        // Nilry's Codex offers three cards from the player's class pool, the same
        // way every other discovery screen does. The action used to pass
        // CharacterClass::IRONCLAD outright, so a Defect run was offered Ironclad
        // cards -- the solver then predicted a card the game never showed it.
        for (CharacterClass cc : {CharacterClass::IRONCLAD, CharacterClass::DEFECT}) {
            BattleContext battle = freshBattle(cc);
            Actions::CodexAction().actFunc(battle);
            check(battle.inputState == InputState::CARD_SELECT, "Codex opens a card select");
            check(battle.cardSelectInfo.cardSelectTask == CardSelectTask::CODEX,
                  "the card select is the Codex task");
            for (auto id : battle.cardSelectInfo.cards) {
                check(inClassPool(id, cc), "Codex offers a card from the player's own class pool");
            }
        }
        std::cout << "codex card pool ok\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << "FAIL: " << e.what() << "\n";
        return 1;
    }
}
