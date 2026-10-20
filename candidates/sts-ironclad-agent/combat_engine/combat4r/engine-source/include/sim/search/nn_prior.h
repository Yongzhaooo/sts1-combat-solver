// Learned move ordering for BattleScumSearcher2 tree expansion (research only).
// The prior never changes the legal edge set, rollouts or terminal scoring: it orders a
// freshly expanded node's edges, limits how many of them UCT may visit (progressive
// widening) and biases the first leaf action. Disabled by default; then search is unchanged.
#pragma once

#include "combat/BattleContext.h"
#include "sim/search/Action.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#ifdef _WIN32
#include <filesystem>
#endif
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace nn_prior {

constexpr int DENSE = 40;
constexpr int CARD_VOCAB = 512;
constexpr int POTION_VOCAB = 64;

struct Model {
    int cardEmb = 0, potionEmb = 0, h1 = 0, h2 = 0;
    std::vector<float> card, potion, w1, b1, w2, b2, w3;
    float b3 = 0;
    bool loaded = false;
    int inputs() const { return DENSE + cardEmb + potionEmb; }
};

struct Config {
    bool enabled = false;
    double widenC = 2.0;      // allowed children = max(minWidth, ceil(widenC * n^widenAlpha))
    double widenAlpha = 0.5;
    int minWidth = 2;
    double priorWeight = 0.0; // PUCT-style rank bonus weight
    double rankTau = 2.0;     // rank prior exp(-rank/tau) for the bonus and the leaf sample
    double leafGreedy = 0.75; // probability the first leaf action is rank-sampled, else uniform
};

inline Model &model() { static Model m; return m; }
inline Config &config() { static Config c; return c; }

inline void load(const std::string &path) {
#ifdef _WIN32  // a narrow std::string opens through the ANSI code page and fails on CJK paths
    std::ifstream in(std::filesystem::u8path(path), std::ios::binary);
#else  // POSIX paths are UTF-8 bytes; std::filesystem needs macOS 10.15+
    std::ifstream in(path, std::ios::binary);
#endif
    if (!in) throw std::runtime_error("nn_prior: cannot open " + path);
    std::int32_t header[6];
    in.read(reinterpret_cast<char *>(header), sizeof(header));
    if (!in || header[0] != 0x4e4e5031 || header[1] != DENSE)
        throw std::runtime_error("nn_prior: bad header in " + path);
    Model m;
    m.cardEmb = header[2]; m.potionEmb = header[3]; m.h1 = header[4]; m.h2 = header[5];
    auto take = [&](std::vector<float> &v, std::size_t n) {
        v.resize(n);
        in.read(reinterpret_cast<char *>(v.data()), static_cast<std::streamsize>(n * sizeof(float)));
        if (!in) throw std::runtime_error("nn_prior: truncated weights in " + path);
    };
    take(m.card, static_cast<std::size_t>(CARD_VOCAB) * m.cardEmb);
    take(m.potion, static_cast<std::size_t>(POTION_VOCAB) * m.potionEmb);
    take(m.w1, static_cast<std::size_t>(m.h1) * m.inputs());
    take(m.b1, m.h1);
    take(m.w2, static_cast<std::size_t>(m.h2) * m.h1);
    take(m.b2, m.h2);
    take(m.w3, m.h2);
    std::vector<float> last;
    take(last, 1);
    m.b3 = last[0];
    char extra;
    if (in.read(&extra, 1)) throw std::runtime_error("nn_prior: trailing bytes in " + path);
    m.loaded = true;
    model() = std::move(m);
}

struct Encoded {
    float dense[DENSE];
    int card = 0;
    int potion = 0;
};

// Shared state part; the candidate part overwrites indices 16 onwards.
inline void encodeState(const sts::BattleContext &bc, float *x) {
    const auto &p = bc.player;
    int alive = 0, hp = 0, incoming = 0;
    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
        const auto &m = bc.monsters.arr[i];
        if (!m.isAlive()) continue;
        ++alive; hp += m.curHp;
        if (m.isAttacking()) {
            const auto d = m.getMoveBaseDamage(bc);
            incoming += d.damage * d.attackCount;
        }
    }
    int potions = 0;
    for (int i = 0; i < bc.potionCapacity; ++i)
        potions += bc.potions[i] != sts::Potion::EMPTY_POTION_SLOT;
    const float maxHp = static_cast<float>(std::max(1, p.maxHp));
    x[0] = p.curHp / maxHp;
    x[1] = p.curHp / 100.f;
    x[2] = p.block / 50.f;
    x[3] = p.energy / 5.f;
    x[4] = p.strength / 10.f;
    x[5] = p.dexterity / 10.f;
    x[6] = bc.turn / 20.f;
    x[7] = bc.cards.cardsInHand / 10.f;
    x[8] = bc.cards.drawPile.size() / 40.f;
    x[9] = bc.cards.discardPile.size() / 40.f;
    x[10] = bc.cards.exhaustPile.size() / 40.f;
    x[11] = alive / 5.f;
    x[12] = hp / 300.f;
    x[13] = incoming / 50.f;
    x[14] = std::max(0, incoming - p.block) / 50.f;
    x[15] = potions / 5.f;
}

inline void encodeAction(const sts::BattleContext &bc, sts::search::Action a, Encoded &e) {
    using sts::search::ActionType;
    float *x = e.dense;
    std::fill(x + 16, x + DENSE, 0.f);
    e.card = 0; e.potion = 0;
    const auto type = a.getActionType();
    x[16 + std::min(4, static_cast<int>(type))] = 1.f;
    int target = -1;
    if (type == ActionType::CARD) {
        const auto &c = bc.cards.hand[a.getSourceIdx()];
        e.card = std::min(CARD_VOCAB - 1, static_cast<int>(c.getId()));
        x[21] = c.isUpgraded();
        x[22] = c.costForTurn / 3.f;
        x[23 + std::min(5, static_cast<int>(c.getType()))] = 1.f;  // 23..28
        x[29] = c.requiresTarget();
        if (c.requiresTarget()) target = a.getTargetIdx();
    } else if (type == ActionType::POTION) {
        e.potion = std::min(POTION_VOCAB - 1, static_cast<int>(bc.potions[a.getSourceIdx()]));
        target = a.getTargetIdx();
    } else if (type == ActionType::SINGLE_CARD_SELECT) {
        x[30] = a.getSelectIdx() / 10.f;
    }
    if (target >= 0 && target < bc.monsters.monsterCount) {
        const auto &m = bc.monsters.arr[target];
        int minHp = 1 << 30;
        for (int i = 0; i < bc.monsters.monsterCount; ++i)
            if (bc.monsters.arr[i].isTargetable()) minHp = std::min(minHp, bc.monsters.arr[i].curHp);
        x[31] = m.curHp / 100.f;
        x[32] = m.curHp / static_cast<float>(std::max(1, m.maxHp));
        x[33] = m.block / 50.f;
        x[34] = m.isAttacking();
        if (m.isAttacking()) {
            const auto d = m.getMoveBaseDamage(bc);
            x[35] = d.damage * d.attackCount / 50.f;
        }
        x[36] = m.vulnerable > 0;
        x[37] = m.weak > 0;
        x[38] = m.strength / 10.f;
        x[39] = m.curHp == minHp;
    }
}

inline float score(const Model &m, const Encoded &e, std::vector<float> &buf) {
    const int n = m.inputs();
    buf.resize(static_cast<std::size_t>(n + m.h1 + m.h2));
    float *in = buf.data(), *a1 = in + n, *a2 = a1 + m.h1;
    std::copy(e.dense, e.dense + DENSE, in);
    std::copy_n(m.card.data() + static_cast<std::size_t>(e.card) * m.cardEmb, m.cardEmb, in + DENSE);
    std::copy_n(m.potion.data() + static_cast<std::size_t>(e.potion) * m.potionEmb, m.potionEmb, in + DENSE + m.cardEmb);
    for (int j = 0; j < m.h1; ++j) {
        const float *w = m.w1.data() + static_cast<std::size_t>(j) * n;
        float s = m.b1[j];
        for (int i = 0; i < n; ++i) s += w[i] * in[i];
        a1[j] = s > 0 ? s : 0;
    }
    for (int j = 0; j < m.h2; ++j) {
        const float *w = m.w2.data() + static_cast<std::size_t>(j) * m.h1;
        float s = m.b2[j];
        for (int i = 0; i < m.h1; ++i) s += w[i] * a1[i];
        a2[j] = s > 0 ? s : 0;
    }
    float s = m.b3;
    for (int i = 0; i < m.h2; ++i) s += m.w3[i] * a2[i];
    return s;
}

// Logits for a list of candidate actions in one state.
inline std::vector<float> logits(const sts::BattleContext &bc, const std::vector<sts::search::Action> &actions) {
    const auto &m = model();
    std::vector<float> out(actions.size());
    if (!m.loaded) throw std::runtime_error("nn_prior: no model loaded");
    thread_local std::vector<float> buf;
    Encoded e;
    encodeState(bc, e.dense);
    for (std::size_t i = 0; i < actions.size(); ++i) {
        encodeAction(bc, actions[i], e);
        out[i] = score(m, e, buf);
    }
    return out;
}

inline double rankWeight(int rank) { return std::exp(-rank / config().rankTau); }

inline int allowedChildren(std::int64_t visits, int size) {
    const auto &c = config();
    const int widened = static_cast<int>(std::ceil(c.widenC * std::pow(static_cast<double>(visits + 1), c.widenAlpha)));
    return std::min(size, std::max(c.minWidth, widened));
}

}  // namespace nn_prior
