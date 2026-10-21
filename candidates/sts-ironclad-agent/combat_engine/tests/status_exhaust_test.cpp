#include <cassert>
#include "combat/BattleContext.h"
#include "combat/Actions.h"
#include "game/GameContext.h"
using namespace sts;

// End-of-turn Burn (UseCardAction without use triggers): Java exhausts it when card.exhaust was set earlier by
// Medical Kit, after Strange Spoon's cardRandom roll; an unmarked Burn is discarded with no roll (train-r5 2658411229969493029).
static void endOfTurnBurn(bool marked, bool spoon) {
    GameContext game(CharacterClass::IRONCLAD, 123, 0);
    BattleContext battle;
    battle.init(game, MonsterEncounter::CULTIST);
    battle.actionQueue.clear();
    battle.cards.discardPile.clear();
    battle.cards.exhaustPile.clear();
    battle.player.setHasRelic<RelicId::STRANGE_SPOON>(spoon);
    CardInstance burn(CardId::BURN);
    burn.exhaust = marked;
    battle.curCardQueueItem.card = burn;
    const auto rolls = battle.cardRandomRng.counter;
    Actions::DiscardNoTriggerCard().actFunc(battle);
    assert(battle.cardRandomRng.counter == rolls + (marked && spoon ? 1 : 0));
    assert(battle.cards.discardPile.size() + battle.cards.exhaustPile.size() == 1);
    assert(marked || battle.cards.discardPile.size() == 1);
    assert(!marked || spoon || battle.cards.exhaustPile.size() == 1);
}

int main() {
    endOfTurnBurn(false, false);
    endOfTurnBurn(false, true);
    endOfTurnBurn(true, false);
    endOfTurnBurn(true, true);
}
