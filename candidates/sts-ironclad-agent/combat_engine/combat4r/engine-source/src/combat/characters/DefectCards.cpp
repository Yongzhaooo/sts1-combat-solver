#include "combat/CharacterCards.h"
#include "combat/BattleContext.h"

namespace sts {

    // Defect cards. To implement one, add its case here, queue its actions exactly as the original
    // game does, and end the case with "return true;". tools/coverage_manifest.py then counts it
    // as implemented. Do not approximate: leave a card out until it can match the original.
    bool useDefectCard(BattleContext &bc) {
        switch (bc.curCardQueueItem.card.getId()) {
            // case CardId::EXAMPLE:
            //     bc.addToBot(...);
            //     return true;

            default:
                return false;
        }
    }

}
