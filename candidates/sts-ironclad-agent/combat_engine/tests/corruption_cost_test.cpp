#include <cassert>
#include "combat/BattleContext.h"
#include "combat/Actions.h"
#include "game/GameContext.h"
using namespace sts;

static BattleContext corrupted() {
    GameContext game(CharacterClass::IRONCLAD, 123, 0);
    BattleContext battle;
    battle.init(game, MonsterEncounter::CULTIST);
    battle.actionQueue.clear();
    while (battle.cards.cardsInHand > 0) battle.cards.removeFromHandAtIdx(0);
    battle.cards.drawPile.clear();
    battle.cards.discardPile.clear();
    battle.cards.exhaustPile.clear();
    battle.player.buff<PS::CORRUPTION>();
    return battle;
}

int main() {
    {   // Enlightenment+ on a Snecko-rolled Corruption skill: Java keeps its turn cost 0, so the
        // following discard hook leaves the lowered base cost at 1 (Java EnlightenmentAction).
        auto battle = corrupted();
        CardInstance defend(CardId::DEFEND_RED);
        defend.cost = 3;
        defend.costForTurn = 0;
        battle.cards.moveToHand(defend);
        battle.cards.moveToHand(CardInstance(CardId::UPPERCUT));
        Actions::EnlightenmentAction(true).actFunc(battle);
        assert(battle.cards.hand[0].cost == 1 && battle.cards.hand[0].costForTurn == 0);
        assert(battle.cards.hand[1].cost == 1 && battle.cards.hand[1].costForTurn == 1);
        battle.onCardDrawOrDiscard();
        assert(battle.cards.hand[0].cost == 1 && battle.cards.hand[0].costForTurn == 0);
    }
    {   // Secret Technique moves a skill without Corruption's turn-cost write; the exhaust hook then
        // makes it free for combat (Java SkillFromDeckToHandAction).
        auto battle = corrupted();
        battle.cards.drawPile = {CardInstance(CardId::SPOT_WEAKNESS)};
        const int idx = 0;
        battle.chooseDrawToHandCards(&idx, 1);
        assert(battle.cards.hand[0].cost == 1 && battle.cards.hand[0].costForTurn == 1);
        battle.onCardDrawOrDiscard();
        assert(battle.cards.hand[0].cost == 0 && battle.cards.hand[0].costForTurn == 0);
    }
    {   // ExhumeAction is the hand move that does apply Corruption.
        auto battle = corrupted();
        battle.cards.exhaustPile = {CardInstance(CardId::SHRUG_IT_OFF)};
        battle.chooseExhumeCard(0);
        assert(battle.cards.hand[0].cost == 1 && battle.cards.hand[0].costForTurn == 0);
    }
}
