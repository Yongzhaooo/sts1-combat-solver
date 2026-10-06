#include "combat/Actions.h"
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"
#include <iostream>
#include <stdexcept>

using namespace sts;

int main() {
    try {
        GameContext game(CharacterClass::IRONCLAD, 123, 20);
        game.curHp = game.maxHp = 80;
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager();
        battle.monsters.arr[0].curHp = battle.monsters.arr[0].maxHp = 300;
        battle.player.setHasRelic<R::KUNAI>(true);
        battle.player.attacksPlayedThisTurn = 1;
        battle.player.buff<PS::DOUBLE_TAP>(1);

        for (CardId id : {CardId::DEFEND_RED, CardId::RAMPAGE, CardId::SHOCKWAVE}) {
            CardInstance card(id);
            card.uniqueId = battle.cards.nextUniqueCardId++;
            battle.cards.notifyAddCardToCombat(card);
            battle.cards.notifyAddToDrawPile(card);
            battle.cards.drawPile.push_back(card);
        }

        battle.potions[0] = Potion::DISTILLED_CHAOS;
        battle.potionCount = 1;
        search::Action potion(search::ActionType::POTION, 0, 0);
        if (!potion.isValidAction(battle)) throw std::runtime_error("Distilled Chaos rejected");
        potion.execute(battle);
        if (battle.player.attacksPlayedThisTurn != 3 ||
            battle.player.getStatus<PS::DEXTERITY>() != 1 || battle.player.block != 6) {
            throw std::runtime_error("Double Tap copy did not trigger Kunai before Defend");
        }
        std::cout << "Distilled Chaos / Double Tap / Kunai order matches the original game.\n";
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
