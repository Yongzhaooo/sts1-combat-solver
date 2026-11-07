#include "combat/CharacterCards.h"
#include "combat/BattleContext.h"

namespace sts {

    // Watcher cards. To implement one, add its case here, queue its actions exactly as the original
    // game does, and end the case with "return true;". tools/coverage_manifest.py then counts it
    // as implemented. Do not approximate: leave a card out until it can match the original.
    bool useWatcherCard(BattleContext &bc) {
        const auto &c = bc.curCardQueueItem.card;
        const auto t = bc.curCardQueueItem.target;
        const bool up = c.isUpgraded();
        switch (c.getId()) {
            case CardId::ERUPTION:
                // Eruption.java: the attack is queued before the stance change, so its own damage is
                // still calculated in the stance the card is leaving. upgrade() only calls
                // upgradeBaseCost(1), so the damage is 9 at both levels and the cost drops to 1.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, 9)));
                bc.addToBot(Actions::ChangeStance(Stance::WRATH));
                return true;

            case CardId::VIGILANCE:
                // Vigilance.java: block first, then Calm. upgrade() calls upgradeBlock(4) -> 12;
                // the cost stays 2 at both levels.
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 12 : 8)));
                bc.addToBot(Actions::ChangeStance(Stance::CALM));
                return true;

            default:
                return false;
        }
    }

}
