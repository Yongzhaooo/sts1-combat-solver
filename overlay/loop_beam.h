#pragma once
// Generic small-loop rescue search. Kernel (three scoring lanes, diversity buckets, beam over decision points) is taken from
// sts1-combat-solver reports/experiments/time-eater-loop-20261016/generic_search.h (Codex). Production changes only:
// budget in state transitions (deterministic, no wall clock), cooperative cancellation, best line chosen by the planner's own
// end-state value, no witness / human actions. Potions are never used by the beam.
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <numeric>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

namespace loop_beam {
struct Path { std::shared_ptr<const Path> parent; unsigned bits; };
struct Node {
    std::shared_ptr<BattleContext> state;
    std::shared_ptr<const Path> path;
    double powers = 0;
};
struct Stats { long long transitions = 0; int layers = 0; int passes = 0; };
thread_local Stats stats;

inline std::vector<int> actions(std::shared_ptr<const Path> path) {
    std::vector<int> out;
    for (; path; path = path->parent) out.push_back(static_cast<int>(path->bits));
    std::reverse(out.begin(), out.end());
    return out;
}

inline double score(const Node &n, int lane) {
    const auto &b = *n.state;
    int enemy = 0, incoming = 0;
    for (int i = 0; i < b.monsters.monsterCount; ++i) {
        const auto &m = b.monsters.arr[i];
        enemy += std::max(0, m.curHp);
        // Phase 1 revives at full HP: count phase 2 too, so killing phase 1 scores as progress rather than a 300+ HP regress.
        if (m.id == MonsterId::AWAKENED_ONE && !m.miscInfo) enemy += m.maxHp;
        if (m.curHp > 0) {
            auto d = m.getMoveBaseDamage(b);
            incoming += d.attackCount * m.calculateDamageToPlayer(b, d.damage);
        }
    }
    // Separate preferences retain setup, immediate progress, and survival alternatives.
    const double hpWeight[] = {4, 2, 8};
    const double setupWeight[] = {10, 3, 5};
    const double damageWeight[] = {0.7, 1.5, 0.35};
    const double compressionWeight[] = {1.5, 0, 0};
    return hpWeight[lane] * (b.player.curHp - 0.7 * std::max(0, incoming - b.player.block))
        - damageWeight[lane] * enemy + setupWeight[lane] * n.powers
        + 2 * std::min(8, b.player.energy) + 1.5 * b.cards.cardsInHand
        + compressionWeight[lane] * b.cards.exhaustPile.size()
        + 0.4 * std::min(incoming + 8, b.player.block) - 12 * b.turn;
}

inline std::string bucket(const BattleContext &b) {
    std::ostringstream s;
    s << b.turn << ':' << b.player.curHp << ':' << b.player.block << ':' << b.player.energy
      << ':' << b.player.statusBits0 << ':' << b.player.statusBits1 << ':' << b.player.strength
      << ':' << int(b.player.sundialCounter) << ':' << b.player.cardsPlayedThisTurn;
    for (auto p : b.player.powerOrder) s << ':' << int(p.status) << '=' << (b.player.statusMap.contains(p.status) ? b.player.statusMap.at(p.status) : 0);
    for (int i = 0; i < b.monsters.monsterCount; ++i) {
        const auto &m = b.monsters.arr[i];
        s << '/' << m.curHp << ':' << m.block << ':' << int(m.moveHistory[0])
          << ':' << m.uniquePower0 << ':' << m.uniquePower1 << ':' << m.strength << ':' << m.vulnerable;
    }
    auto card = [](const CardInstance &c) {
        return std::to_string(int(c.getId())) + "," + std::to_string(c.isUpgraded()) + "," +
            std::to_string(c.cost) + "," + std::to_string(c.costForTurn) + "," + std::to_string(c.specialData);
    };
    std::vector<std::string> hand, discard;
    for (int i = 0; i < b.cards.cardsInHand; ++i) hand.push_back(card(b.cards.hand[i]));
    for (auto &c : b.cards.discardPile) discard.push_back(card(c));
    std::sort(hand.begin(), hand.end()); std::sort(discard.begin(), discard.end());
    s << 'H'; for (auto &c : hand) s << c << ';';
    s << 'D'; for (auto &c : b.cards.drawPile) s << card(c) << ';';
    s << 'C'; for (auto &c : discard) s << c << ';';
    return s.str();
}

struct Best {
    bool found = false;
    double value = 0;
    std::shared_ptr<BattleContext> state;
    std::vector<int> actions;
};

// One beam pass of the given width; stops at `cap` total transitions (stats.transitions) or when cancelled().
template <class Value, class Cancel>
void pass(const BattleContext &root, int width, long long cap, Value value, Cancel cancelled, Best &best) {
    search::BattleScumSearcher2 enumerator(root);
    enumerator.preservePotions = true;
    auto legal = [&](const BattleContext &b) {
        search::BattleScumSearcher2::Node n;
        enumerator.enumerateActionsForNode(n, b);
        std::vector<search::Action> out;
        for (auto &e : n.edges) out.push_back(e.action);
        enumerator.edgeArena = {};
        return out;
    };
    auto stop = [&]() { return stats.transitions >= cap || cancelled(); };
    std::vector<Node> frontier{{std::make_shared<BattleContext>(root), nullptr, 0}};
    // The transition cap bounds a pass; 256 actions did not reach the end of long loop fights (Awakened One, 320+300 HP).
    for (int depth = 0; depth < 4096 && !frontier.empty() && !stop(); ++depth) {
        std::vector<Node> candidates;
        // One layer can hold ~10^5 full states (3.1 GB: an OOM kill on a held-out boss). Compact past 12x width: lanes are ranked
        // in turn on one index order (as below), and lane L only ever picks among its first (L+1)*width distinct buckets, so
        // keeping those, in arrival order, leaves the final selection unchanged.
        const std::size_t compactAt = 12 * std::size_t(width) + 64;
        auto compact = [&]() {
            std::vector<std::array<double, 3>> sc;
            std::vector<std::string> keys;
            sc.reserve(candidates.size()); keys.reserve(candidates.size());
            for (auto &n : candidates) { sc.push_back({score(n, 0), score(n, 1), score(n, 2)}); keys.push_back(bucket(*n.state)); }
            std::vector<std::size_t> order(candidates.size());
            std::iota(order.begin(), order.end(), 0);
            std::vector<char> keep(candidates.size(), 0);
            for (int lane = 0; lane < 3; ++lane) {
                std::stable_sort(order.begin(), order.end(), [&](auto x, auto y) { return sc[x][lane] > sc[y][lane]; });
                std::unordered_set<std::string> seen;
                for (std::size_t k = 0; k < order.size() && seen.size() < std::size_t(lane + 1) * width; ++k)
                    if (seen.insert(keys[order[k]]).second) keep[order[k]] = 1;
            }
            std::vector<Node> kept;
            for (std::size_t i = 0; i < candidates.size(); ++i) if (keep[i]) kept.push_back(std::move(candidates[i]));
            candidates.swap(kept);
        };
        for (auto &parent : frontier) {
            std::vector<Node> pending{parent};
            while (!pending.empty() && !stop()) {
                auto current = std::move(pending.back()); pending.pop_back();
                for (auto a : legal(*current.state)) {
                    if (stop()) break;
                    Node child{std::make_shared<BattleContext>(*current.state),
                               std::make_shared<Path>(Path{current.path, a.bits}), current.powers};
                    if (a.getActionType() == search::ActionType::CARD &&
                        current.state->cards.hand[a.getSourceIdx()].getType() == CardType::POWER) child.powers += 1;
                    a.execute(*child.state); ++stats.transitions;
                    auto &b = *child.state;
                    if (b.outcome == Outcome::PLAYER_VICTORY && b.player.curHp > 0) {
                        double v = value(b);
                        if (!best.found || v > best.value) { best.found = true; best.value = v; best.state = child.state; best.actions = actions(child.path); }
                    } else if (b.outcome == Outcome::UNDECIDED && b.player.curHp > 0 && b.turn < 100) {
                        // turn < 100: stalling lines (end turn behind block forever) otherwise reach the engine's 500-turn limit, which throws
                        if (b.inputState == InputState::PLAYER_NORMAL) {
                            candidates.push_back(std::move(child));
                            if (candidates.size() > compactAt) compact();
                        }
                        else pending.push_back(std::move(child));
                    }
                }
            }
        }
        std::vector<std::size_t> order(candidates.size());
        std::iota(order.begin(), order.end(), 0);
        std::vector<std::array<double, 3>> scores;
        scores.reserve(candidates.size());
        for (auto &n : candidates) scores.push_back({score(n, 0), score(n, 1), score(n, 2)});
        std::unordered_set<std::string> buckets;
        std::vector<Node> next;
        for (int lane = 0; lane < 3; ++lane) {
            std::stable_sort(order.begin(), order.end(), [&](auto x, auto y) { return scores[x][lane] > scores[y][lane]; });
            int kept = 0;
            for (std::size_t k = 0; k < order.size() && kept < width; ++k) {
                auto &n = candidates[order[k]];
                if (buckets.insert(bucket(*n.state)).second) { next.push_back(n); ++kept; }
            }
        }
        frontier = std::move(next);
        stats.layers = std::max(stats.layers, depth + 1);
    }
}

// Widths 32, 128, 512, 2048 in turn (Codex v2 schedule plus one wider pass) until the transition cap is spent; returns the best victory
// found. Stops early once a pass has found a win that loses no combat HP (no later pass can do better on HP).
template <class Value, class Cancel>
Best portfolio(const BattleContext &root, long long cap, Value value, Cancel cancelled) {
    stats = {};
    Best best;
    for (int width : {32, 128, 512, 2048}) {
        if (stats.transitions >= cap || cancelled()) break;
        pass(root, width, cap, value, cancelled, best);
        ++stats.passes;
        if (best.found && best.state->player.curHp >= root.player.curHp) break;
    }
    return best;
}
}  // namespace loop_beam
