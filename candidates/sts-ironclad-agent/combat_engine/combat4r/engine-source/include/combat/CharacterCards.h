#ifndef STS_COMBAT_CHARACTER_CARDS_H
#define STS_COMBAT_CHARACTER_CARDS_H

namespace sts {

    struct BattleContext;

    // Per-character card behavior, one file each in src/combat/characters/. Each function runs
    // the card in bc.curCardQueueItem and returns true, or returns false for a card it does not
    // implement. A false from all of them leaves the shared switch's "unimplemented card" error
    // in place, so a missing card is never silently skipped.
    bool useSilentCard(BattleContext &bc);
    bool useDefectCard(BattleContext &bc);
    bool useWatcherCard(BattleContext &bc);

    // Reached from the default branch of useAttackCard, useSkillCard and usePowerCard.
    bool useCharacterCard(BattleContext &bc);

}

#endif //STS_COMBAT_CHARACTER_CARDS_H
