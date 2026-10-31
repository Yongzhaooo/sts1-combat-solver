#include "combat/CharacterCards.h"
#include "combat/BattleContext.h"

namespace sts {

    // Silent cards. To implement one, add its case here, queue its actions exactly as the original
    // game does, and end the case with "return true;". tools/coverage_manifest.py then counts it
    // as implemented. Do not approximate: leave a card out until it can match the original.
    bool useSilentCard(BattleContext &bc) {
        const auto &c = bc.curCardQueueItem.card;
        const auto t = bc.curCardQueueItem.target;
        const bool up = c.isUpgraded();
        switch (c.getId()) {
            case CardId::NEUTRALIZE:
                // Neutralize.java: damage, then WeakPower(m, magicNumber, false).
                // The false keeps the Weak player-sourced: it decays this round.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 4 : 3)));
                bc.addToBot(Actions::DebuffEnemy<MS::WEAK>(t, up ? 2 : 1, false));
                return true;

            default:
                return false;
        }
    }

}
