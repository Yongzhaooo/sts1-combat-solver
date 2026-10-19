#pragma once
// Generic small-loop rescue search. Kernel (three scoring lanes, diversity buckets, beam over decision points) is taken from
// sts1-combat-solver reports/experiments/time-eater-loop-20261016/generic_search.h (Codex). Production changes only:
// budget in state transitions (deterministic, no wall clock), cooperative cancellation, best line chosen by the planner's own
// end-state value, no witness / human actions. Potion permissions come from the overlay's shared enumerator.
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <memory>
#include <numeric>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

namespace loop_beam {
struct Path {
    std::shared_ptr<const Path> parent;
    unsigned bits;
    int card = -1, turn = -1;
    CardId id = CardId::INVALID;
};
struct Node {
    std::shared_ptr<BattleContext> state;
    std::shared_ptr<const Path> path;
    double powers = 0;
    std::array<double, 3> scores{};
    std::string key;
};
struct Stats {
    long long transitions = 0, macroActions = 0;
    int layers = 0, passes = 0, macroAttempts = 0, macroCycles = 0;
};
thread_local Stats stats;

inline std::shared_ptr<const Path> append(const Node &node, search::Action action) {
    auto path = std::make_shared<Path>(Path{node.path, action.bits});
    if (action.getActionType() == search::ActionType::CARD) {
        const auto &card = node.state->cards.hand[action.getSourceIdx()];
        path->card = card.uniqueId;
        path->id = card.id;
        path->turn = node.state->turn;
    }
    return path;
}

inline bool cyclePotential(const BattleContext &b) {
    // shortcut: a cheap Ironclad/colorless draw gate; add other characters with their simulator coverage.
    auto draws = [](const CardInstance &c) {
        switch (c.id) {
        case CardId::POMMEL_STRIKE: case CardId::DROPKICK: case CardId::SHRUG_IT_OFF:
        case CardId::FINESSE: case CardId::FLASH_OF_STEEL: case CardId::DARK_EMBRACE:
            return true;
        default: return false;
        }
    };
    if (b.player.hasStatus<PS::DARK_EMBRACE>()) return true;
    for (int i = 0; i < b.cards.cardsInHand; ++i) if (draws(b.cards.hand[i])) return true;
    for (auto &c : b.cards.drawPile) if (draws(c)) return true;
    for (auto &c : b.cards.discardPile) if (draws(c)) return true;
    return false;
}

inline std::vector<const Path *> cycle(const Node &node) {
    std::vector<const Path *> motif;
    const auto *last = node.path.get();
    if (!last || last->card < 0 || last->turn != node.state->turn) return motif;
    for (auto p = last; p && motif.size() < 12; p = p->parent.get()) {
        if (p->card < 0 || p->turn != last->turn) break;
        if (p != last && p->card == last->card && p->id == last->id
                && search::Action(p->bits).getTargetIdx() == search::Action(last->bits).getTargetIdx()) {
            std::reverse(motif.begin(), motif.end());
            return motif;
        }
        motif.push_back(p);
    }
    return {};
}

template <class Cancel, class Exit>
Node repeatCycle(const Node &start, const std::vector<const Path *> &motif, long long cap, Cancel cancelled, Exit checkpoint) {
    Node out{std::make_shared<BattleContext>(*start.state), start.path, start.powers};
    ++stats.macroAttempts;
    int played = 0;
    while (played < 256) {
        const int hp = out.state->player.curHp, energy = out.state->player.energy;
        const auto beforeShuffle = out.state->shuffleRng.counter;
        for (auto p : motif) {
            auto &b = *out.state;
            if (stats.transitions >= cap || cancelled() || played >= 256
                    || b.loopCount >= BattleContext::ACTION_EXECUTION_LIMIT
                    || b.outcome != Outcome::UNDECIDED || b.inputState != InputState::PLAYER_NORMAL
                    || b.turn != start.state->turn || b.player.curHp < hp
                    || b.player.hasStatus<PS::NO_DRAW>()) return out;
            int source = -1;
            for (int i = 0; i < b.cards.cardsInHand; ++i)
                if (b.cards.hand[i].uniqueId == p->card && b.cards.hand[i].id == p->id) {
                    if (source >= 0) return out; // Ambiguous identities must go through normal search.
                    source = i;
                }
            if (source < 0) return out;
            search::Action action(search::ActionType::CARD, source, search::Action(p->bits).getTargetIdx());
            if (!action.isValidAction(b)) return out;
            auto path = append(out, action);
            action.execute(b);
            out.path = std::move(path);
            ++stats.transitions; ++stats.macroActions; ++played;
            for (int i = 0; i < b.monsters.monsterCount; ++i)
                if (b.monsters.arr[i].miscInfo != start.state->monsters.arr[i].miscInfo) return out;
        }
        ++stats.macroCycles;
        // Replaying one circuit is evidence of a candidate, not proof of an infinite loop.
        if (out.state->player.curHp < hp || out.state->shuffleRng.counter == beforeShuffle) return out;
        // Finite cycles may spend energy; preserve each exit before trying another legal circuit.
        if (out.state->player.energy < energy)
            checkpoint(Node{std::make_shared<BattleContext>(*out.state), out.path, out.powers});
    }
    return out;
}

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
    std::string s;
    s.reserve(128 + 20 * (b.cards.cardsInHand + b.cards.drawPile.size() + b.cards.discardPile.size()));
    const auto scalar = [&](auto value) { s.append(reinterpret_cast<const char *>(&value), sizeof(value)); };
    scalar(b.turn); scalar(b.player.curHp); scalar(b.player.block); scalar(b.player.energy);
    scalar(b.player.statusBits0); scalar(b.player.statusBits1); scalar(b.player.strength);
    scalar(int(b.player.sundialCounter)); scalar(b.player.cardsPlayedThisTurn);
    scalar(b.player.powerOrder.size());
    for (auto p : b.player.powerOrder) {
        scalar(int(p.status));
        scalar(b.player.statusMap.contains(p.status) ? b.player.statusMap.at(p.status) : 0);
    }
    scalar(b.monsters.monsterCount);
    for (int i = 0; i < b.monsters.monsterCount; ++i) {
        const auto &m = b.monsters.arr[i];
        scalar(m.curHp); scalar(m.block); scalar(int(m.moveHistory[0]));
        scalar(m.uniquePower0); scalar(m.uniquePower1); scalar(m.strength); scalar(m.vulnerable);
    }
    using CardKey = std::array<int,5>;
    const auto card = [](const CardInstance &c) -> CardKey {
        return {int(c.getId()), int(c.isUpgraded()), int(c.cost), int(c.costForTurn), int(c.specialData)};
    };
    const auto write = [&](const CardKey &c) {
        s.append(reinterpret_cast<const char *>(c.data()), 5 * sizeof(int));
    };
    std::array<CardKey,10> hand;
    for (int i = 0; i < b.cards.cardsInHand; ++i) hand[i] = card(b.cards.hand[i]);
    std::sort(hand.begin(), hand.begin() + b.cards.cardsInHand);
    scalar(b.cards.cardsInHand);
    for (int i = 0; i < b.cards.cardsInHand; ++i) write(hand[i]);
    scalar(b.cards.drawPile.size());
    for (const auto &c : b.cards.drawPile) write(card(c));
    std::vector<CardKey> discard;
    discard.reserve(b.cards.discardPile.size());
    for (const auto &c : b.cards.discardPile) discard.push_back(card(c));
    std::sort(discard.begin(), discard.end());
    scalar(discard.size());
    for (const auto &c : discard) write(c);
    return s;
}

struct Best {
    bool found = false;
    double value = 0;
    std::shared_ptr<BattleContext> state;
    std::vector<int> actions;
};

inline const std::string &bucketKey(Node &n) {
    if (n.key.empty()) n.key = bucket(*n.state);
    return n.key;
}

// One beam pass of the given width; stops at `cap` total transitions (stats.transitions) or when cancelled().
template <class Value, class Cancel>
void pass(const BattleContext &root, int width, long long cap, Value value, Cancel cancelled, Best &best, bool macros = false) {
    if (root.loopCount >= BattleContext::ACTION_EXECUTION_LIMIT) return;
    search::BattleScumSearcher2 enumerator(root);
    enumerator.preservePotions = true;
    search::RolloutNode legalActions;
    auto legal = [&](const BattleContext &b) -> const std::vector<search::RolloutNode::Edge> & {
        enumerator.enumerateActionsForNode(legalActions, b);
        return legalActions.edges;
    };
    auto stop = [&]() { return stats.transitions >= cap || cancelled(); };
    std::vector<Node> frontier{{std::make_shared<BattleContext>(root), nullptr, 0}};
    // Only a per-turn damage cap (Invincible: the Heart) makes a loop worthless before the turn ends; elsewhere the
    // per-turn quota below stays off and the search is unchanged.
    bool damageCap = false;
    for (int i = 0; i < root.monsters.monsterCount; ++i) damageCap |= root.monsters.arr[i].hasStatus<MS::INVINCIBLE>();
    // The transition cap bounds a pass; 256 actions did not reach the end of long loop fights (Awakened One, 320+300 HP).
    for (int depth = 0; depth < 4096 && !frontier.empty() && !stop(); ++depth) {
        std::vector<Node> candidates;
        // One layer can hold ~10^5 full states (3.1 GB: an OOM kill on a held-out boss). Compact past 12x width: lanes are ranked
        // in turn on one index order (as below), and lane L only ever picks among its first (L+1)*width distinct buckets, so
        // keeping those, in arrival order, leaves the final selection unchanged.
        const std::size_t compactAt = 12 * std::size_t(width) + 64;
        // Lane scores compare states of different turns: one that just ended its turn has zero block against the next
        // attack and always ranks below another loop in the old turn. With an unbounded loop past the Heart's damage cap
        // every kept state then stays in that turn until its HP runs out. So under a damage cap each later turn also keeps
        // its own leaders, ranked on lane 0 against states of the same turn only.
        auto byTurn = [&]() {
            std::map<int, std::vector<std::size_t>> groups;
            for (std::size_t i = 0; i < candidates.size(); ++i) groups[candidates[i].state->turn].push_back(i);
            for (auto &group : groups)
                std::stable_sort(group.second.begin(), group.second.end(),
                                 [&](auto x, auto y) { return candidates[x].scores[0] > candidates[y].scores[0]; });
            return groups;
        };
        auto compact = [&]() {
            std::vector<std::size_t> order(candidates.size());
            std::iota(order.begin(), order.end(), 0);
            std::vector<char> keep(candidates.size(), 0);
            for (int lane = 0; lane < 3; ++lane) {
                std::stable_sort(order.begin(), order.end(), [&](auto x, auto y) { return candidates[x].scores[lane] > candidates[y].scores[lane]; });
                std::unordered_set<std::string> seen;
                for (std::size_t k = 0; k < order.size() && seen.size() < std::size_t(lane + 1) * width; ++k)
                    if (seen.insert(bucketKey(candidates[order[k]])).second) keep[order[k]] = 1;
            }
            // Each turn's leaders for the per-turn quota below. Later candidates only add turns, so the final quota is no
            // larger; keeping more would let stalling lines over many turns refill the layer past compactAt.
            auto groups = damageCap ? byTurn() : decltype(byTurn()){};
            const std::size_t quota = std::max<std::size_t>(1, width / std::max<std::size_t>(1, groups.size() - 1));
            for (auto &group : groups) {
                std::unordered_set<std::string> seen;
                for (std::size_t k = 0; k < group.second.size() && seen.size() < quota; ++k)
                    if (seen.insert(bucketKey(candidates[group.second[k]])).second) keep[group.second[k]] = 1;
            }
            std::vector<Node> kept;
            for (std::size_t i = 0; i < candidates.size(); ++i) if (keep[i]) kept.push_back(std::move(candidates[i]));
            candidates.swap(kept);
        };
        for (auto &parent : frontier) {
            if (macros && !stop() && !parent.state->player.hasStatus<PS::NO_DRAW>()) {
                auto motif = cycle(parent);
                if (!motif.empty()) {
                    auto checkpoint = [&](Node child) {
                        auto &b = *child.state;
                        if (b.outcome == Outcome::UNDECIDED && b.player.curHp > 0
                                && b.turn < 100 && b.inputState == InputState::PLAYER_NORMAL
                                && b.loopCount < BattleContext::ACTION_EXECUTION_LIMIT) {
                            child.scores = {score(child, 0), score(child, 1), score(child, 2)};
                            candidates.push_back(std::move(child));
                            if (candidates.size() > compactAt) compact();
                        }
                    };
                    auto child = repeatCycle(parent, motif, cap, cancelled, checkpoint);
                    auto &b = *child.state;
                    if (b.outcome == Outcome::PLAYER_VICTORY && b.player.curHp > 0) {
                        double v = value(b);
                        if (!best.found || v > best.value) { best = {true, v, child.state, actions(child.path)}; }
                        if (best.state->player.curHp >= root.player.curHp) return;
                    } else if (child.path != parent.path && b.outcome == Outcome::UNDECIDED
                            && b.player.curHp > 0 && b.turn < 100 && b.inputState == InputState::PLAYER_NORMAL
                            && b.loopCount < BattleContext::ACTION_EXECUTION_LIMIT) {
                        child.scores = {score(child, 0), score(child, 1), score(child, 2)};
                        candidates.push_back(std::move(child));
                        if (candidates.size() > compactAt) compact();
                    }
                }
            }
            std::vector<Node> pending{parent};
            while (!pending.empty() && !stop()) {
                auto current = std::move(pending.back()); pending.pop_back();
                for (auto edge : legal(*current.state)) {
                    if (stop()) break;
                    const auto a = edge.action;
                    Node child{std::make_shared<BattleContext>(*current.state),
                               append(current, a), current.powers};
                    if (a.getActionType() == search::ActionType::CARD &&
                        current.state->cards.hand[a.getSourceIdx()].getType() == CardType::POWER) child.powers += 1;
                    a.execute(*child.state); ++stats.transitions;
                    auto &b = *child.state;
                    if (b.outcome == Outcome::PLAYER_VICTORY && b.player.curHp > 0) {
                        double v = value(b);
                        if (!best.found || v > best.value) { best.found = true; best.value = v; best.state = child.state; best.actions = actions(child.path); }
                    } else if (b.outcome == Outcome::UNDECIDED && b.player.curHp > 0 && b.turn < 100
                            && b.loopCount < BattleContext::ACTION_EXECUTION_LIMIT) {
                        // turn < 100: stalling lines (end turn behind block forever) otherwise reach the engine's 500-turn limit, which throws
                        if (b.inputState == InputState::PLAYER_NORMAL) {
                            child.scores = {score(child, 0), score(child, 1), score(child, 2)};
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
        std::unordered_set<std::string> buckets;
        std::vector<Node> next;
        for (int lane = 0; lane < 3; ++lane) {
            std::stable_sort(order.begin(), order.end(), [&](auto x, auto y) { return candidates[x].scores[lane] > candidates[y].scores[lane]; });
            int kept = 0;
            for (std::size_t k = 0; k < order.size() && kept < width; ++k) {
                auto &n = candidates[order[k]];
                if (buckets.insert(bucketKey(n)).second) { next.push_back(n); ++kept; }
            }
        }
        if (damageCap && !candidates.empty()) {
            auto groups = byTurn();
            groups.erase(groups.begin());  // the earliest turn is the one the lanes above already favour
            const int quota = groups.empty() ? 0 : std::max(1, width / int(groups.size()));
            for (auto &group : groups)
                for (std::size_t k = 0, kept = 0; k < group.second.size() && kept < std::size_t(quota); ++k) {
                    auto &n = candidates[group.second[k]];
                    if (buckets.insert(bucketKey(n)).second) { next.push_back(n); ++kept; }
                }
        }
        frontier = std::move(next);
        stats.layers = std::max(stats.layers, depth + 1);
    }
}

// Widths 32, 128, 512, 2048 in turn (Codex v2 schedule plus one wider pass) until the transition cap is spent; returns the best victory
// found. Stops early once a pass has found a win that loses no combat HP (no later pass can do better on HP).
template <class Value, class Cancel>
Best portfolio(const BattleContext &root, long long cap, Value value, Cancel cancelled, Best best = {}, Stats prior = {}, const BattleContext *mctsEnd = nullptr, int growthTarget = -1) {
    stats = prior;
    if (best.found && best.state->player.curHp >= root.player.curHp) return best;
    const bool macros = cyclePotential(root);
    auto available = [&]() -> const BattleContext * {
        if (best.found && (!mctsEnd || mctsEnd->outcome != Outcome::PLAYER_VICTORY || best.value > value(*mctsEnd))) return best.state.get();
        return mctsEnd;
    };
    bool plateau128 = false, plateau512 = false;
    for (int width : {32, 128, 512, 2048}) {
        if (stats.transitions >= cap || cancelled()) break;
        const auto *before = available();
        const double beforeValue = before ? value(*before) : -1e300;
        if (width == 2048 && mctsEnd) {
            const bool skip = growth_gate::stop(growthTarget, plateau128, plateau512, before);
            if (const char *log = std::getenv("LOOP_BEAM_LOG")) if (FILE *f=std::fopen(log,"a")) {
                std::fprintf(f,"{\"kind\":\"growth_gate\",\"target\":%d,\"hp\":%d,\"max_hp\":%d,\"value\":%.17g,\"plateau128\":%d,\"plateau512\":%d,\"skip\":%d}\n",
                    growthTarget,before?before->player.curHp:-1,before?before->player.maxHp:-1,beforeValue,int(plateau128),int(plateau512),int(skip));
                std::fclose(f);
            }
            if (skip) break;
        }
        pass(root, width, cap, value, cancelled, best, macros);
        const auto *after = available();
        const bool plateau = after && value(*after) <= beforeValue;
        if (width == 128) plateau128 = plateau;
        if (width == 512) plateau512 = plateau;
        ++stats.passes;
        if (best.found && best.state->player.curHp >= root.player.curHp) break;
    }
    return best;
}
}  // namespace loop_beam
