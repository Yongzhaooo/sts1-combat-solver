#pragma once
// Generic small-loop rescue search. Kernel (three scoring lanes, diversity buckets, beam over decision points) is taken from
// sts1-combat-solver reports/experiments/time-eater-loop-20261016/generic_search.h (Codex). Production changes only:
// budget in state transitions (deterministic, no wall clock), cooperative cancellation, best line chosen by the planner's own
// end-state value, no witness / human actions. Potion permissions come from the overlay's shared enumerator.
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <map>
#include <memory>
#include <numeric>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_set>
#include <vector>
#include "loop_gate.h"

namespace loop_beam {
struct Path {
    std::shared_ptr<const Path> parent;
    unsigned bits;
    int card = -1, turn = -1;
    CardId id = CardId::INVALID;
    int length = 1;  // actions from the root, this one included
    Path(std::shared_ptr<const Path> parent, unsigned bits) : parent(std::move(parent)), bits(bits) {}
    Path(const Path &) = delete;
    Path &operator=(const Path &) = delete;
    // Release a sole-owned parent chain in a loop: recursive destruction of a long rescue path overflowed
    // the ~3 MB stack of a Windows search thread (streak-mode Darkling root, 2026-10-28 export).
    ~Path() {
        auto next = std::move(parent);
        while (next && next.use_count() == 1)
            next = std::move(const_cast<Path &>(*next).parent);  // every Path is created non-const by makePath
    }
};
// The Path fields of an action, kept until a node survives (Node::deferred) so that dropped candidates never allocate one.
struct Step {
    unsigned bits = 0;
    int card = -1, turn = -1;
    CardId id = CardId::INVALID;
};
struct Node {
    std::shared_ptr<BattleContext> state;
    std::shared_ptr<const Path> path;
    double powers = 0;
    std::array<double, 3> scores{};
    std::string key;
    std::size_t keyHash = 0;
    std::shared_ptr<const Path> base;  // deferred: parent path of `step`; `path` is null until materialize()
    Step step;
    bool deferred = false;
};
struct Stats {
    long long transitions = 0, macroActions = 0;
    int layers = 0, passes = 0, macroAttempts = 0, macroCycles = 0;
};
thread_local Stats stats;
// Rescue portfolio limits (set_rescue): widest pass, and stop after a pass of width >= 128 that improved nothing.
inline int rescueMaxWidth = 2048;
inline bool rescuePlateau = false;

inline Step stepOf(const Node &node, search::Action action) {
    Step step;
    step.bits = action.bits;
    if (action.getActionType() == search::ActionType::CARD) {
        const auto &card = node.state->cards.hand[action.getSourceIdx()];
        step.card = card.uniqueId;
        step.id = card.id;
        step.turn = node.state->turn;
    }
    return step;
}
inline std::shared_ptr<const Path> makePath(std::shared_ptr<const Path> parent, const Step &step) {
    auto path = std::make_shared<Path>(std::move(parent), step.bits);
    if (path->parent) path->length = path->parent->length + 1;
    path->card = step.card;
    path->id = step.id;
    path->turn = step.turn;
    return path;
}
inline std::shared_ptr<const Path> append(const Node &node, search::Action action) { return makePath(node.path, stepOf(node, action)); }
inline void materialize(Node &n) {
    if (!n.deferred) return;
    n.path = makePath(std::move(n.base), n.step);
    n.base.reset();
    n.deferred = false;
}

// BattleContext objects owned by this thread's pass pool (live plus recycled): the dominant beam memory, read by the search memory budget.
inline thread_local std::size_t poolStates = 0;

// Recycles the BattleContext copies of one pass: a dropped state returns to a free list and the next copy assigns into it
// (vector capacities survive) instead of paying a malloc/free pair per edge. Single-threaded: a pass owns its pool, and every
// state cloned from it dies before the pass returns (a stored win is copied out), so the deleters may hold a raw pointer.
class StatePool {
public:
    explicit StatePool(std::size_t limit) : limit_(limit) {}
    StatePool(const StatePool &) = delete;
    StatePool &operator=(const StatePool &) = delete;
    ~StatePool() { poolStates -= free_.size(); for (auto *p : free_) delete p; }
    std::shared_ptr<BattleContext> clone(const BattleContext &src) {
        if (free_.empty()) { ++poolStates; return std::shared_ptr<BattleContext>(new BattleContext(src), Release{this}); }
        BattleContext *p = free_.back();
        free_.pop_back();
        std::shared_ptr<BattleContext> out(p, Release{this});  // back to the pool should the assignment throw
        *p = src;
        return out;
    }
private:
    struct Release {
        StatePool *pool;
        void operator()(BattleContext *p) const {
            if (pool->free_.size() < pool->limit_) pool->free_.push_back(p);
            else { --poolStates; delete p; }
        }
    };
    std::vector<BattleContext *> free_;
    std::size_t limit_;
};

// Root gate (layer 1): a known loop family is present (loop_gate.h). Off means no quick loop pass and no macros.
inline bool cyclePotential(const BattleContext &b) { return loop_gate::matches(b) != 0; }

inline bool hasTarget(const BattleContext &b) {
    for (int i = 0; i < b.monsters.monsterCount; ++i)
        if (b.monsters.arr[i].isTargetable()) return true;
    return false;
}

inline bool retainTurnExits(const BattleContext &b) {
    for (int i = 0; i < b.monsters.monsterCount; ++i) {
        const auto &m = b.monsters.arr[i];
        if (m.hasStatus<MS::INVINCIBLE>() || m.id == MonsterId::NEMESIS || m.id == MonsterId::AWAKENED_ONE) return true;
    }
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
Node repeatCycle(const Node &start, const std::vector<const Path *> &motif, long long cap, Cancel cancelled, Exit checkpoint,
                 bool payHp = false) {
    Node out{std::make_shared<BattleContext>(*start.state), start.path, start.powers};
    ++stats.macroAttempts;
    int played = 0;
    // Pseudo-infinite families (Bloodletting, Offering) trade HP for energy every circuit. They may keep going above a
    // safety floor while each circuit still lowers the enemies' total HP; every other loop stops at its first HP loss.
    const int floor = start.state->player.maxHp / 4;
    auto enemyHp = [&]() { int t = 0; for (int i = 0; i < out.state->monsters.monsterCount; ++i) t += std::max(0, out.state->monsters.arr[i].curHp); return t; };
    while (played < 256) {
        const int hp = out.state->player.curHp, energy = out.state->player.energy, enemy = enemyHp();
        const auto beforeShuffle = out.state->shuffleRng.counter;
        for (auto p : motif) {
            auto &b = *out.state;
            if (stats.transitions >= cap || cancelled() || played >= 256
                    || b.loopCount >= BattleContext::ACTION_EXECUTION_LIMIT
                    || b.outcome != Outcome::UNDECIDED || b.inputState != InputState::PLAYER_NORMAL
                    || b.turn != start.state->turn || (payHp ? b.player.curHp <= floor : b.player.curHp < hp)
                    || !hasTarget(b)
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
        if ((payHp ? out.state->player.curHp <= floor || (out.state->player.curHp < hp && enemyHp() >= enemy)
                   : out.state->player.curHp < hp)
                || out.state->shuffleRng.counter == beforeShuffle) return out;
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

// The lane-independent part of score(): total enemy HP and incoming damage, computed once per node.
struct Features { int enemy = 0, incoming = 0; };
inline Features features(const BattleContext &b) {
    Features f;
    for (int i = 0; i < b.monsters.monsterCount; ++i) {
        const auto &m = b.monsters.arr[i];
        f.enemy += std::max(0, m.curHp);
        // Phase 1 revives at full HP: count phase 2 too, so killing phase 1 scores as progress rather than a 300+ HP regress.
        if (m.id == MonsterId::AWAKENED_ONE && !m.miscInfo) f.enemy += m.maxHp;
        if (m.curHp > 0) {
            auto d = m.getMoveBaseDamage(b);
            f.incoming += d.attackCount * m.calculateDamageToPlayer(b, d.damage);
        }
    }
    return f;
}

inline double score(const Node &n, int lane, const Features &f) {
    const auto &b = *n.state;
    const int enemy = f.enemy, incoming = f.incoming;
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

inline double score(const Node &n, int lane) { return score(n, lane, features(*n.state)); }

inline std::string bucket(const BattleContext &b) {
    // Written into a reused buffer and copied out once; the bound covers every field below (at most 8 bytes each).
    // One thread_local per function: MSVC v142 emits a duplicate guard for a second one in an inline function (C2086).
    static thread_local struct { std::vector<char> buffer; std::vector<std::uint64_t> discard; } scratch;
    std::vector<char> &buffer = scratch.buffer;
    const std::size_t bound = 256 + 16 * b.player.powerOrder.size() + 80 * std::size_t(b.monsters.monsterCount)
        + 20 * (b.cards.cardsInHand + b.cards.drawPile.size() + b.cards.discardPile.size());
    if (buffer.size() < bound) buffer.resize(bound);
    char *out = buffer.data();
    const auto scalar = [&](auto value) { std::memcpy(out, &value, sizeof(value)); out += sizeof(value); };
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
    // A card is the five ints (id, upgraded, cost, costForTurn, specialData). Packed most significant first with the signed fields
    // offset, the unsigned order of the pack is the lexicographic order of the five ints, so sorting packs sorts the keys.
    const auto pack = [](const CardInstance &c) -> std::uint64_t {
        return std::uint64_t(std::uint16_t(c.getId())) << 33 | std::uint64_t(c.isUpgraded()) << 32
            | std::uint64_t(std::int32_t(c.cost) + 128) << 24 | std::uint64_t(std::int32_t(c.costForTurn) + 128) << 16
            | std::uint64_t(std::int32_t(c.specialData) + 32768);
    };
    const auto write = [&](std::uint64_t k) {
        const int key[5] = {int(k >> 33 & 0xFFFF), int(k >> 32 & 1), int(k >> 24 & 0xFF) - 128, int(k >> 16 & 0xFF) - 128, int(k & 0xFFFF) - 32768};
        std::memcpy(out, key, sizeof(key));
        out += sizeof(key);
    };
    std::array<std::uint64_t,10> hand;
    for (int i = 0; i < b.cards.cardsInHand; ++i) hand[i] = pack(b.cards.hand[i]);
    std::sort(hand.begin(), hand.begin() + b.cards.cardsInHand);
    scalar(b.cards.cardsInHand);
    for (int i = 0; i < b.cards.cardsInHand; ++i) write(hand[i]);
    scalar(b.cards.drawPile.size());
    for (const auto &c : b.cards.drawPile) write(pack(c));
    std::vector<std::uint64_t> &discard = scratch.discard;
    discard.clear();
    for (const auto &c : b.cards.discardPile) discard.push_back(pack(c));
    std::sort(discard.begin(), discard.end());
    scalar(discard.size());
    for (const auto &c : discard) write(c);
    return std::string(buffer.data(), out - buffer.data());
}

struct Best {
    bool found = false;
    double value = 0;
    std::shared_ptr<BattleContext> state;
    std::vector<int> actions;
};

// Equal results, fewest actions. A shortening guard can recognize identical carried resources despite a turn penalty.
// With a floor (shorten() for the Heart, where the run ends on victory) any win keeping that much HP counts as equal.
using SameVictory = bool (*)(const BattleContext &, const BattleContext &);
inline bool better(const Best &best, double v, const std::shared_ptr<const Path> &path,
                   const BattleContext &end, int floor, SameVictory sameVictory = nullptr) {
    const bool shorter = path && (!best.found || path->length < int(best.actions.size()));
    if (floor >= 0) return end.player.curHp >= floor && shorter;
    if (best.found && sameVictory && sameVictory(end, *best.state)) return shorter;
    return !best.found || v > best.value || (v == best.value && shorter);
}

inline const std::string &bucketKey(Node &n) {
    if (n.key.empty()) { n.key = bucket(*n.state); n.keyHash = std::hash<std::string_view>{}(n.key); }
    return n.key;
}
// A bucket key in a set: the hash is computed once per node, strings are compared only on equal hashes.
struct KeyRef {
    const std::string *key;
    std::size_t hash;
};
struct KeyHash { std::size_t operator()(const KeyRef &r) const { return r.hash; } };
struct KeyEq { bool operator()(const KeyRef &a, const KeyRef &b) const { return a.hash == b.hash && *a.key == *b.key; } };
using KeySet = std::unordered_set<KeyRef, KeyHash, KeyEq>;
inline KeyRef keyRef(Node &n) { bucketKey(n); return {&n.key, n.keyHash}; }

// One beam pass of the given width; stops at `cap` total transitions (stats.transitions) or when cancelled().
template <class Value, class Cancel>
void pass(const BattleContext &root, int width, long long cap, Value value, Cancel cancelled, Best &best, bool macros = false,
          double playWeight = 0, int winFloor = -1, SameVictory sameVictory = nullptr) {
    if (root.loopCount >= BattleContext::ACTION_EXECUTION_LIMIT) return;
    StatePool pool(16 * std::size_t(width) + 1024);  // declared first: it outlives every state cloned from it
    search::BattleScumSearcher2 enumerator(root);
    enumerator.preservePotions = true;
    search::RolloutNode legalActions;
    auto legal = [&](const BattleContext &b) -> const std::vector<search::RolloutNode::Edge> & {
        enumerator.enumerateActionsForNode(legalActions, b);
        return legalActions.edges;
    };
    auto stop = [&]() { return stats.transitions >= cap || cancelled(); };
    // A terminal-run HP floor retains the per-turn heuristic; other shortening charges the whole path.
    auto rank = [&](Node &n) {
        const int length = n.deferred ? (n.base ? n.base->length : 0) + 1 : (n.path ? n.path->length : 0);
        const double charge = playWeight * (winFloor >= 0 ? n.state->player.cardsPlayedThisTurn : length);
        const auto f = features(*n.state);
        n.scores = {score(n, 0, f) - charge, score(n, 1, f) - charge, score(n, 2, f) - charge};
    };
    std::vector<Node> frontier{{std::make_shared<BattleContext>(root), nullptr, 0}};
    // Buffers reused by every layer and every compaction.
    std::vector<Node> candidates, survivors, next;
    std::vector<std::size_t> order;
    std::vector<char> keep;
    struct Ranked { double score; std::uint32_t pos, index; };
    std::vector<Ranked> keyed;
    KeySet seen;
    // Damage caps, Intangible and revival phases need an exit from the current turn even while loop lanes score well.
    const bool payHp = loop_gate::pseudo(loop_gate::matches(root));
    const bool turnExits = retainTurnExits(root);
    // The transition cap bounds a pass; 256 actions did not reach the end of long loop fights (Awakened One, 320+300 HP).
    for (int depth = 0; depth < 4096 && !frontier.empty() && !stop(); ++depth) {
        candidates.clear();
        // One layer can hold ~10^5 full states (3.1 GB: an OOM kill on a held-out boss). Compact past 12x width: lanes are ranked
        // in turn on one index order (as below), and lane L only ever picks among its first (L+1)*width distinct buckets, so
        // keeping those, in arrival order, leaves the final selection unchanged.
        const std::size_t compactAt = 12 * std::size_t(width) + 64;
        // Lane scores compare states of different turns: one that just ended its turn has zero block against the next
        // attack and always ranks below another loop in the old turn. With an unbounded loop past the Heart's damage cap
        // every kept state then stays in that turn until its HP runs out. So under a damage cap each later turn also keeps
        // its own leaders, ranked on lane 0 against states of the same turn only.
        // Stable sort of an index list by one lane's score, on packed (score, index) pairs: the same permutation as sorting the indices.
        // The position in the incoming order breaks score ties, which is what the stable sort did; std::sort needs no temporary buffer.
        auto sortByLane = [&](std::vector<std::size_t> &idx, int lane) {
            keyed.resize(idx.size());
            for (std::size_t i = 0; i < idx.size(); ++i) keyed[i] = {candidates[idx[i]].scores[lane], std::uint32_t(i), std::uint32_t(idx[i])};
            std::sort(keyed.begin(), keyed.end(), [](const Ranked &x, const Ranked &y) { return x.score > y.score || (x.score == y.score && x.pos < y.pos); });
            for (std::size_t i = 0; i < idx.size(); ++i) idx[i] = keyed[i].index;
        };
        auto byTurn = [&]() {
            std::map<int, std::vector<std::size_t>> groups;
            for (std::size_t i = 0; i < candidates.size(); ++i) groups[candidates[i].state->turn].push_back(i);
            for (auto &group : groups) sortByLane(group.second, 0);
            return groups;
        };
        auto compact = [&]() {
            order.resize(candidates.size());
            std::iota(order.begin(), order.end(), 0);
            keep.assign(candidates.size(), 0);
            for (int lane = 0; lane < 3; ++lane) {
                sortByLane(order, lane);
                seen.clear();
                for (std::size_t k = 0; k < order.size() && seen.size() < std::size_t(lane + 1) * width; ++k)
                    if (seen.insert(keyRef(candidates[order[k]])).second) keep[order[k]] = 1;
            }
            // Each turn's leaders for the per-turn quota below. Later candidates only add turns, so the final quota is no
            // larger; keeping more would let stalling lines over many turns refill the layer past compactAt.
            auto groups = turnExits ? byTurn() : decltype(byTurn()){};
            const std::size_t quota = std::max<std::size_t>(1, width / std::max<std::size_t>(1, groups.size() - 1));
            for (auto &group : groups) {
                seen.clear();
                for (std::size_t k = 0; k < group.second.size() && seen.size() < quota; ++k)
                    if (seen.insert(keyRef(candidates[group.second[k]])).second) keep[group.second[k]] = 1;
            }
            survivors.clear();
            for (std::size_t i = 0; i < candidates.size(); ++i) if (keep[i]) survivors.push_back(std::move(candidates[i]));
            candidates.swap(survivors);
            survivors.clear();
        };
        for (auto &parent : frontier) {
            if (macros && !stop() && !parent.state->player.hasStatus<PS::NO_DRAW>()) {
                auto motif = cycle(parent);
                // Layer 2: repeat only from states whose loop cards fit the hand or can still be thinned.
                if (!motif.empty() && loop_gate::runnable(*parent.state)) {
                    auto checkpoint = [&](Node child) {
                        auto &b = *child.state;
                        if (b.outcome == Outcome::UNDECIDED && b.player.curHp > 0
                                && b.turn < 100 && b.inputState == InputState::PLAYER_NORMAL
                                && b.loopCount < BattleContext::ACTION_EXECUTION_LIMIT) {
                            rank(child);
                            candidates.push_back(std::move(child));
                            if (candidates.size() > compactAt) compact();
                        }
                    };
                    auto child = repeatCycle(parent, motif, cap, cancelled, checkpoint, payHp);
                    auto &b = *child.state;
                    if (b.outcome == Outcome::PLAYER_VICTORY && b.player.curHp > 0) {
                        double v = value(b);
                        if (better(best, v, child.path, b, winFloor, sameVictory)) { best = {true, v, child.state, actions(child.path)}; }
                        if (best.state->player.curHp >= root.player.curHp) return;
                    } else if (child.path != parent.path && b.outcome == Outcome::UNDECIDED
                            && b.player.curHp > 0 && b.turn < 100 && b.inputState == InputState::PLAYER_NORMAL
                            && b.loopCount < BattleContext::ACTION_EXECUTION_LIMIT) {
                        rank(child);
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
                    Node child{pool.clone(*current.state), nullptr, current.powers};
                    child.base = current.path; child.step = stepOf(current, a); child.deferred = true;
                    if (a.getActionType() == search::ActionType::CARD &&
                        current.state->cards.hand[a.getSourceIdx()].getType() == CardType::POWER) child.powers += 1;
                    a.execute(*child.state); ++stats.transitions;
                    auto &b = *child.state;
                    if (b.outcome == Outcome::PLAYER_VICTORY && b.player.curHp > 0) {
                        materialize(child);
                        double v = value(b);
                        if (better(best, v, child.path, b, winFloor, sameVictory)) { best.found = true; best.value = v; best.state = std::make_shared<BattleContext>(*child.state); best.actions = actions(child.path); }
                    } else if (b.outcome == Outcome::UNDECIDED && b.player.curHp > 0 && b.turn < 100
                            && b.loopCount < BattleContext::ACTION_EXECUTION_LIMIT) {
                        // turn < 100: stalling lines (end turn behind block forever) otherwise reach the engine's 500-turn limit, which throws
                        if (b.inputState == InputState::PLAYER_NORMAL) {
                            rank(child);
                            candidates.push_back(std::move(child));
                            if (candidates.size() > compactAt) compact();
                        }
                        else { materialize(child); pending.push_back(std::move(child)); }
                    }
                }
            }
        }
        order.resize(candidates.size());
        std::iota(order.begin(), order.end(), 0);
        seen.clear();
        next.clear();
        // Only survivors get a Path; the next layer needs neither their scores nor their keys.
        const auto advance = [&](Node &n) { materialize(n); next.push_back(Node{n.state, n.path, n.powers}); };
        for (int lane = 0; lane < 3; ++lane) {
            sortByLane(order, lane);
            int kept = 0;
            for (std::size_t k = 0; k < order.size() && kept < width; ++k) {
                auto &n = candidates[order[k]];
                if (seen.insert(keyRef(n)).second) { advance(n); ++kept; }
            }
        }
        if (turnExits && !candidates.empty()) {
            auto groups = byTurn();
            groups.erase(groups.begin());  // the earliest turn is the one the lanes above already favour
            const int quota = groups.empty() ? 0 : std::max(1, width / int(groups.size()));
            for (auto &group : groups)
                for (std::size_t k = 0, kept = 0; k < group.second.size() && kept < std::size_t(quota); ++k) {
                    auto &n = candidates[group.second[k]];
                    if (seen.insert(keyRef(n)).second) { advance(n); ++kept; }
                }
        }
        frontier.swap(next);
        next.clear();
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
        if (stats.transitions >= cap || cancelled() || width > rescueMaxWidth) break;
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
        if (const char *log = std::getenv("LOOP_BEAM_LOG")) if (FILE *f = std::fopen(log, "a")) {   // diagnostic only
            std::fprintf(f, "{\"kind\":\"pass\",\"tid\":%zu,\"width\":%d,\"transitions\":%lld,\"cap\":%lld,\"hp0\":%d,\"found\":%d,\"hp\":%d,\"value\":%.17g}\n",
                std::hash<std::thread::id>{}(std::this_thread::get_id()), width, stats.transitions, cap, root.player.curHp,
                int(best.found), best.found ? best.state->player.curHp : -1, best.found ? best.value : 0.0);
            std::fclose(f);
        }
        const auto *after = available();
        const bool plateau = after && value(*after) <= beforeValue;
        if (width == 128) plateau128 = plateau;
        if (width == 512) plateau512 = plateau;
        if (rescuePlateau && width >= 128 && (!after || (before && value(*after) <= beforeValue))) break;
        ++stats.passes;
        if (best.found && best.state->player.curHp >= root.player.curHp) break;
    }
    return best;
}
// A long win often keeps replaying a loop that no longer changes the result (past the Heart's damage cap, extra circuits
// only reshuffle). One extra pass penalizes plays, without loop macros, seeded with that win; better() keeps
// the seed unless a line wins with a higher value or fewer actions at equal value or identical carried resources;
// with winFloor >= 0 any shorter win keeping at least winFloor HP replaces it.
template <class Value, class Cancel>
Best shorten(const BattleContext &root, Best best, long long budget, Value value, Cancel cancelled, int winFloor = -1,
             SameVictory sameVictory = nullptr) {
    pass(root, 128, stats.transitions + budget, value, cancelled, best, false, 1.0, winFloor, sameVictory);
    return best;
}
}  // namespace loop_beam
