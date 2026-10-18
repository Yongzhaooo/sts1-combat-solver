#include "combat/CharacterCards.h"
#include "combat/BattleContext.h"

namespace sts {

    // Defect cards. To implement one, add its case here, queue its actions exactly as the original
    // game does, and end the case with "return true;". tools/coverage_manifest.py then counts it
    // as implemented. Do not approximate: leave a card out until it can match the original.
    bool useDefectCard(BattleContext &bc) {
        switch (bc.curCardQueueItem.card.getId()) {
            case CardId::ZAP:
                // Zap.java: channel one Lightning orb; the upgraded card adds +3 to
                // the channeled orb's evoke value.
                bc.addToBot(Actions::ChannelOrb(Orb::LIGHTNING,
                    bc.curCardQueueItem.card.isUpgraded() ? 3 : 0));
                return true;

            case CardId::DUALCAST:
                // Dualcast.java: evoke the next (leftmost) orb twice.
                bc.addToBot(Actions::EvokeOrbAction(0, 2));
                return true;

            default:
                return false;
        }
    }

}
