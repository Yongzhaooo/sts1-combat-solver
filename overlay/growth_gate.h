#pragma once
namespace growth_gate {
// shortcut: audit other character cards before extending this Ironclad pilot.
inline bool ordinary(CardId id) {
    if (id == CardId::INVALID) return false;
    if (getCardColor(id) == CardColor::RED) return id != CardId::INFERNAL_BLADE;
    if (cardTypes[static_cast<int>(id)] == CardType::CURSE || cardTypes[static_cast<int>(id)] == CardType::STATUS) return true;
    switch (id) {
    case CardId::APOTHEOSIS: case CardId::APPARITION: case CardId::BANDAGE_UP: case CardId::BITE:
    case CardId::BLIND: case CardId::DARK_SHACKLES: case CardId::DEEP_BREATH:
    case CardId::DRAMATIC_ENTRANCE: case CardId::ENLIGHTENMENT: case CardId::FINESSE:
    case CardId::FLASH_OF_STEEL: case CardId::GOOD_INSTINCTS: case CardId::IMPATIENCE:
    case CardId::MADNESS: case CardId::MASTER_OF_STRATEGY: case CardId::MAYHEM:
    case CardId::MIND_BLAST: case CardId::PANACEA: case CardId::PANACHE:
    case CardId::PURITY: case CardId::SADISTIC_NATURE: case CardId::SECRET_TECHNIQUE:
    case CardId::SECRET_WEAPON: case CardId::SWIFT_STRIKE: case CardId::THE_BOMB:
    case CardId::THINKING_AHEAD: case CardId::TRIP: return true;
    default: return false;
    }
}

// -1 means unknown; otherwise this is an upper bound on remaining combat max-HP gain.
inline int remaining(const BattleContext &root, unsigned mask, bool generatedPotions) {
    if (root.inputState != InputState::PLAYER_NORMAL || generatedPotions
        || root.player.hasRelic<RelicId::DEAD_BRANCH>() || root.player.hasRelic<RelicId::NILRYS_CODEX>()
        || root.player.hasStatus<PS::MAGNETISM>() || root.player.hasStatus<PS::CREATIVE_AI>()
        || root.player.hasStatus<PS::HELLO_WORLD>()) return -1;
    int feeds = 0;
    bool restore = false, repeat = false, unknown = false;
    auto scan = [&](const CardInstance &c) {
        feeds += c.id == CardId::FEED;
        restore |= c.id == CardId::EXHUME;
        repeat |= c.id == CardId::DUAL_WIELD || c.id == CardId::DOUBLE_TAP;
        unknown |= !ordinary(c.id);
    };
    for (int i=0; i<root.cards.cardsInHand; ++i) scan(root.cards.hand[i]);
    for (const auto &c:root.cards.drawPile) scan(c);
    for (const auto &c:root.cards.discardPile) scan(c);
    // Irretrievable exhausted cards cannot create a future opportunity.
    if (restore) for (const auto &c:root.cards.exhaustPile) scan(c);
    if (unknown) return -1;
    for (int i=0; i<root.potionCapacity; ++i) if (mask & (1u<<i)) switch(root.potions[i]) {
    case Potion::ATTACK_POTION: case Potion::SKILL_POTION: case Potion::POWER_POTION:
    case Potion::COLORLESS_POTION: case Potion::ENTROPIC_BREW: case Potion::FRUIT_JUICE: return -1;
    case Potion::DUPLICATION_POTION: repeat = true; break;
    default: break;
    }
    if (feeds && (restore || repeat || root.player.hasRelic<RelicId::STRANGE_SPOON>()
        || root.player.hasRelic<RelicId::NECRONOMICON>() || root.player.hasStatus<PS::DOUBLE_TAP>()
        || root.player.hasStatus<PS::DUPLICATION>() || root.player.hasStatus<PS::ECHO_FORM>())) return -1;
    return 4*feeds;  // Allow in-combat upgrades even if every Feed starts unupgraded.
}

inline bool stop(int target, bool plateau128, bool plateau512, const BattleContext *winner) {
    return target >= 0 && plateau128 && plateau512 && winner
        && winner->outcome == Outcome::PLAYER_VICTORY && winner->player.curHp > 0
        && winner->player.maxHp >= target;
}
}
