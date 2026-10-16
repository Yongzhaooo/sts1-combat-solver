#include "combat/CharacterCards.h"

namespace sts {

    // Generated cards (Shiv, Miracle, ...) have no character color, so try each owner in turn.
    bool useCharacterCard(BattleContext &bc) {
        return useSilentCard(bc) || useDefectCard(bc) || useWatcherCard(bc);
    }

}
