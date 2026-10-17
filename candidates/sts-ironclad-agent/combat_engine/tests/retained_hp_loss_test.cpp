#include <cassert>
#include "combat/BattleContext.h"
#include "combat/Actions.h"
#include "game/GameContext.h"
using namespace sts;
int main() {
    for (auto relic : {RelicId::RUNIC_CUBE, RelicId::CENTENNIAL_PUZZLE}) {
        GameContext game(CharacterClass::IRONCLAD, 123, 0);
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.actionQueue.clear();
        if (relic == RelicId::RUNIC_CUBE) battle.player.setHasRelic<RelicId::RUNIC_CUBE>(true);
        else battle.player.setHasRelic<RelicId::CENTENNIAL_PUZZLE>(true);
        battle.outcome = Outcome::PLAYER_VICTORY;
        battle.player.hpWasLost(battle, 1, false);
        assert(battle.actionQueue.isEmpty());
        assert(relic != RelicId::CENTENNIAL_PUZZLE || battle.player.hasRelic<RelicId::CENTENNIAL_PUZZLE>());
        battle.outcome = Outcome::UNDECIDED;
        battle.player.hpWasLost(battle, 1, false);
        assert(!battle.actionQueue.isEmpty());
    }
    GameContext game(CharacterClass::IRONCLAD, 123, 0);
    BattleContext battle;
    battle.init(game, MonsterEncounter::CULTIST);
    battle.cards.drawPile.clear();
    battle.cards.discardPile = {CardInstance(CardId::STRIKE_RED)};
    battle.outcome = Outcome::PLAYER_VICTORY;
    Actions::HeadbuttAction().actFunc(battle);
    assert(battle.cards.discardPile.size() == 1 && battle.cards.drawPile.empty());
    battle.outcome = Outcome::UNDECIDED;
    Actions::HeadbuttAction().actFunc(battle);
    assert(battle.cards.discardPile.empty() && battle.cards.drawPile.size() == 1);
}
