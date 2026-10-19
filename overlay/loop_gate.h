#pragma once
// Rule table of known Ironclad loop families, ported from sts1-combat-pilot/tools/loop_recognizer.py (FAMILIES, fill,
// recognize; rules and sources in sts1-combat-pilot/docs/LOOP_KNOWLEDGE.md). A family matches when distinct cards fill
// every slot and its relics are held. Matching says the pieces exist, not that the loop starts: the search decides that.
// Keep this table and the Python one identical; tools/check_loop_gate.py compares them on recorded fights.
#include <algorithm>
#include <cstdint>
#include <utility>
#include <vector>

namespace loop_gate {
enum class Kind { INFINITE, PSEUDO, SMALL };
struct Slot { std::vector<CardId> ids; int n = 1; bool up = false; };
struct Family { const char *id; Kind kind; std::vector<RelicId> relics; std::vector<Slot> cards; };
using Card = std::pair<CardId, bool>;  // id, upgraded

inline const std::vector<Family> &families() {
    using C = CardId;
    using R = RelicId;
    const std::vector<C> zeroDraw{C::FLASH_OF_STEEL, C::FINESSE, C::DEEP_BREATH};
    const std::vector<C> vulnerable{C::BASH, C::SHOCKWAVE, C::UPPERCUT, C::THUNDERCLAP, C::TRIP};
    static const std::vector<Family> table{
        // true infinites
        {"double-dropkick", Kind::INFINITE, {}, {{{C::DROPKICK}, 2}, {vulnerable}}},
        {"dropkick-zero-draw", Kind::INFINITE, {}, {{{C::DROPKICK}}, {zeroDraw}, {vulnerable}}},
        {"sundial-pommel-shrug", Kind::INFINITE, {R::SUNDIAL}, {{{C::POMMEL_STRIKE}, 1, true}, {{C::SHRUG_IT_OFF}}}},
        {"sundial-pommel-pommel", Kind::INFINITE, {R::SUNDIAL}, {{{C::POMMEL_STRIKE}, 1, true}, {{C::POMMEL_STRIKE}}}},
        {"sundial-pommel-zero", Kind::INFINITE, {R::SUNDIAL}, {{{C::POMMEL_STRIKE}}, {{C::FINESSE, C::FLASH_OF_STEEL}}}},
        {"sundial-shrug-zero", Kind::INFINITE, {R::SUNDIAL}, {{{C::SHRUG_IT_OFF}}, {{C::FINESSE, C::FLASH_OF_STEEL}}}},
        {"sundial-pact", Kind::INFINITE, {R::SUNDIAL}, {{{C::BURNING_PACT}}, {{C::POMMEL_STRIKE, C::SHRUG_IT_OFF}}}},
        {"zero-cost-draw", Kind::INFINITE, {}, {{{C::FLASH_OF_STEEL, C::FINESSE}, 2}}},
        {"double-embrace-havoc-reckless", Kind::INFINITE, {},
         {{{C::DARK_EMBRACE}, 2}, {{C::HAVOC}}, {{C::RECKLESS_CHARGE}}}},
        {"medkit-reckless-evolve", Kind::INFINITE, {R::MEDICAL_KIT},
         {{{C::RECKLESS_CHARGE}}, {{C::DARK_EMBRACE}}, {{C::EVOLVE}}}},
        {"medkit-reckless-double-embrace", Kind::INFINITE, {R::MEDICAL_KIT}, {{{C::RECKLESS_CHARGE}}, {{C::DARK_EMBRACE}, 2}}},
        {"top-zero-cost", Kind::INFINITE, {R::UNCEASING_TOP}, {{{C::ANGER}}}},
        {"necronomicurse", Kind::INFINITE, {R::NECRONOMICON, R::BLUE_CANDLE, R::TUNGSTEN_ROD}, {}},
        {"reaper-lifesteal", Kind::INFINITE, {},
         {{{C::DARK_EMBRACE}}, {{C::BLOODLETTING}}, {{C::DUAL_WIELD}}, {{C::REAPER}}}},
        // pseudo-infinites (pay HP or hand space)
        {"bleed-pommel-shrug", Kind::PSEUDO, {}, {{{C::POMMEL_STRIKE}}, {{C::SHRUG_IT_OFF}}, {{C::BLOODLETTING}}}},
        {"evolve-reckless-zero-draw", Kind::PSEUDO, {}, {{{C::EVOLVE}}, {{C::RECKLESS_CHARGE}}, {zeroDraw}}},
        {"evolve-reckless-pommel-rage", Kind::PSEUDO, {},
         {{{C::EVOLVE}}, {{C::RECKLESS_CHARGE}}, {{C::POMMEL_STRIKE}}, {{C::RAGE}}}},
        // small loops (numeric)
        {"power-through-second-wind-body-slam", Kind::SMALL, {},
         {{{C::POWER_THROUGH}}, {{C::SECOND_WIND}}, {{C::BODY_SLAM}}, {{C::FEEL_NO_PAIN, C::POWER_THROUGH, C::DARK_EMBRACE}}}},
        {"rage-double-pommel-body-slam", Kind::SMALL, {}, {{{C::RAGE}}, {{C::POMMEL_STRIKE}, 2}, {{C::BODY_SLAM}}}},
        {"pact-pommel-rage-body-slam", Kind::SMALL, {},
         {{{C::BURNING_PACT}}, {{C::POMMEL_STRIKE}}, {{C::RAGE}}, {{C::BODY_SLAM}}}},
        {"sundial-power-through-pact", Kind::SMALL, {R::SUNDIAL}, {{{C::POWER_THROUGH}}, {{C::BURNING_PACT}, 2}}},
        {"rampage-draw", Kind::SMALL, {}, {{{C::RAMPAGE}}, {{C::POMMEL_STRIKE, C::BURNING_PACT}}}},
        {"blood-for-blood-draw", Kind::SMALL, {},
         {{{C::BLOOD_FOR_BLOOD}}, {{C::POMMEL_STRIKE, C::SHRUG_IT_OFF, C::BURNING_PACT}, 2}}},
        {"deep-breath-shrug-body-slam", Kind::SMALL, {},
         {{{C::DEEP_BREATH}, 1, true}, {{C::SHRUG_IT_OFF}}, {{C::BODY_SLAM}, 1, true}}},
        // mined from recorded games
        {"embrace-havoc", Kind::PSEUDO, {}, {{{C::DARK_EMBRACE}}, {{C::HAVOC}, 1, true}}},
        {"body-slam-draw", Kind::SMALL, {},
         {{{C::BODY_SLAM}, 1, true},
          {{C::POMMEL_STRIKE, C::SHRUG_IT_OFF, C::BURNING_PACT, C::BATTLE_TRANCE, C::OFFERING, C::DEEP_BREATH,
            C::FLASH_OF_STEEL, C::FINESSE, C::WARCRY, C::MASTER_OF_STRATEGY}, 2}}},
    };
    return table;
}

// Distinct cards fill every slot, most specific slot first (same greedy order as the Python fill()).
inline bool fills(const std::vector<Slot> &slots, std::vector<Card> pool) {
    std::vector<const Slot *> order;
    for (auto &s : slots) order.push_back(&s);
    std::stable_sort(order.begin(), order.end(), [](auto a, auto b) {
        return std::make_pair(a->ids.size(), !a->up) < std::make_pair(b->ids.size(), !b->up);
    });
    // Unupgraded copies are spent first, so an upgraded copy stays free for a slot that needs it.
    std::stable_sort(pool.begin(), pool.end(), [](const Card &a, const Card &b) { return a.second < b.second; });
    std::vector<char> used(pool.size(), 0);
    for (auto *s : order) {
        int need = s->n;
        for (std::size_t i = 0; i < pool.size() && need; ++i)
            if (!used[i] && (pool[i].second || !s->up) && std::find(s->ids.begin(), s->ids.end(), pool[i].first) != s->ids.end()) {
                used[i] = 1;
                --need;
            }
        if (need) return false;
    }
    return true;
}

// Bit i set: families()[i] matches.
inline std::uint32_t matches(const std::vector<Card> &cards, const std::vector<RelicId> &relics) {
    std::uint32_t mask = 0;
    const auto &table = families();
    for (std::size_t i = 0; i < table.size(); ++i) {
        bool held = true;
        for (auto r : table[i].relics) held &= std::find(relics.begin(), relics.end(), r) != relics.end();
        if (held && fills(table[i].cards, cards)) mask |= 1u << i;
    }
    return mask;
}

inline bool pseudo(std::uint32_t mask) {
    const auto &table = families();
    for (std::size_t i = 0; i < table.size(); ++i)
        if ((mask >> i & 1u) && table[i].kind == Kind::PSEUDO) return true;
    return false;
}

// Cards the loop can still use (hand, draw, discard); an active Dark Embrace power counts as one more Dark Embrace.
inline std::vector<Card> cards(const BattleContext &b) {
    std::vector<Card> out;
    out.reserve(b.cards.cardsInHand + b.cards.drawPile.size() + b.cards.discardPile.size() + 1);
    for (int i = 0; i < b.cards.cardsInHand; ++i) out.emplace_back(b.cards.hand[i].getId(), b.cards.hand[i].isUpgraded());
    for (auto &c : b.cards.drawPile) out.emplace_back(c.getId(), c.isUpgraded());
    for (auto &c : b.cards.discardPile) out.emplace_back(c.getId(), c.isUpgraded());
    if (b.player.hasStatus<PS::DARK_EMBRACE>()) out.emplace_back(CardId::DARK_EMBRACE, true);
    return out;
}

inline std::vector<RelicId> relics(const BattleContext &b) {
    std::vector<RelicId> out;
    if (b.player.hasRelic<RelicId::SUNDIAL>()) out.push_back(RelicId::SUNDIAL);
    if (b.player.hasRelic<RelicId::MEDICAL_KIT>()) out.push_back(RelicId::MEDICAL_KIT);
    if (b.player.hasRelic<RelicId::UNCEASING_TOP>()) out.push_back(RelicId::UNCEASING_TOP);
    if (b.player.hasRelic<RelicId::NECRONOMICON>()) out.push_back(RelicId::NECRONOMICON);
    if (b.player.hasRelic<RelicId::BLUE_CANDLE>()) out.push_back(RelicId::BLUE_CANDLE);
    if (b.player.hasRelic<RelicId::TUNGSTEN_ROD>()) out.push_back(RelicId::TUNGSTEN_ROD);
    return out;
}

inline std::uint32_t matches(const BattleContext &b) { return matches(cards(b), relics(b)); }

// Layer 2: a loop can only run once its cards fit the 10-card hand, or the deck can still be thinned.
inline bool runnable(const BattleContext &b) {
    const auto pool = cards(b);
    if (!matches(pool, relics(b))) return false;
    if (pool.size() <= 10) return true;
    for (auto &c : pool)
        switch (c.first) {
        case CardId::TRUE_GRIT: case CardId::BURNING_PACT: case CardId::SECOND_WIND: case CardId::FIEND_FIRE:
        case CardId::SEVER_SOUL: case CardId::CORRUPTION: case CardId::HAVOC:
            return true;
        default: break;
        }
    return b.player.hasStatus<PS::CORRUPTION>();
}
}  // namespace loop_gate
