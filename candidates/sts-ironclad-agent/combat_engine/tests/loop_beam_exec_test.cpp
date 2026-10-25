// Self-check for the rescue beam's execution shortcuts (overlay/loop_beam.h): bucket keys must keep their bytes, state recycling
// must be indistinguishable from a fresh copy, the shared score features must equal the one-shot score, and a pass must be
// repeatable and replayable.
#include <cassert>
#include <iostream>
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/BattleScumSearcher2.h"

using namespace sts;
#include "../../../../overlay/growth_gate.h"
#include "../../../../overlay/loop_beam.h"

// bucket() as released in v0.9.4 (5b672b4): the byte layout the faster version must reproduce.
static std::string referenceBucket(const BattleContext &b) {
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
    const auto write = [&](const CardKey &c) { s.append(reinterpret_cast<const char *>(c.data()), 5 * sizeof(int)); };
    std::array<CardKey,10> hand;
    for (int i = 0; i < b.cards.cardsInHand; ++i) hand[i] = card(b.cards.hand[i]);
    std::sort(hand.begin(), hand.begin() + b.cards.cardsInHand);
    scalar(b.cards.cardsInHand);
    for (int i = 0; i < b.cards.cardsInHand; ++i) write(hand[i]);
    scalar(b.cards.drawPile.size());
    for (const auto &c : b.cards.drawPile) write(card(c));
    std::vector<CardKey> discard;
    for (const auto &c : b.cards.discardPile) discard.push_back(card(c));
    std::sort(discard.begin(), discard.end());
    scalar(discard.size());
    for (const auto &c : discard) write(c);
    return s;
}

static BattleContext fresh(MonsterEncounter encounter) {
    GameContext game(CharacterClass::IRONCLAD, 123, 0);
    BattleContext battle;
    battle.init(game, encounter);
    return battle;
}

// Plays legal actions (rotating through the edges) `steps` times; every state on the way goes into `trail`.
static BattleContext advanced(MonsterEncounter encounter, int steps, std::vector<BattleContext> &trail) {
    auto battle = fresh(encounter);
    battle.executeActions();
    search::BattleScumSearcher2 enumerator(battle);
    search::RolloutNode legal;
    for (int i = 0; i < steps && battle.outcome == Outcome::UNDECIDED && battle.inputState == InputState::PLAYER_NORMAL; ++i) {
        enumerator.enumerateActionsForNode(legal, battle);
        if (legal.edges.empty()) break;
        legal.edges[std::size_t(i) % legal.edges.size()].action.execute(battle);
        trail.push_back(battle);
    }
    return battle;
}

int main() {
    std::vector<BattleContext> trail;
    const auto a = advanced(MonsterEncounter::CULTIST, 5, trail), b = advanced(MonsterEncounter::CULTIST, 9, trail);
    for (const auto &state : trail) assert(loop_beam::bucket(state) == referenceBucket(state));
    loop_beam::StatePool pool(8);
    // A recycled object (dirtied by a different state first) equals a plain copy.
    for (int round = 0; round < 3; ++round) {
        auto dirty = pool.clone(a);
        dirty->turn += 7;
        dirty->player.curHp -= 3;
        dirty->cards.drawPile.clear();
        dirty.reset();
        auto x = pool.clone(b), y = std::make_shared<BattleContext>(b);
        assert(loop_beam::bucket(*x) == loop_beam::bucket(*y));
        assert(x->turn == y->turn && x->player.curHp == y->player.curHp && x->cards.drawPile.size() == y->cards.drawPile.size());
        loop_beam::Node nx{x, nullptr, 1}, ny{y, nullptr, 1};
        for (int lane = 0; lane < 3; ++lane) assert(loop_beam::score(nx, lane) == loop_beam::score(ny, lane, loop_beam::features(*y)));
    }
    // A pass is repeatable and its win replays on a fresh copy to the same outcome.
    const auto value = [](const BattleContext &s) { return double(s.player.curHp); };
    const auto never = [] { return false; };
    loop_beam::Best first, second;
    loop_beam::stats = {};
    loop_beam::pass(a, 32, 20000, value, never, first);
    const auto transitions = loop_beam::stats.transitions;
    loop_beam::stats = {};
    loop_beam::pass(a, 32, 20000, value, never, second);
    assert(transitions == loop_beam::stats.transitions && transitions > 0);
    assert(first.found == second.found && first.actions == second.actions);
    if (first.found) {
        BattleContext replay(a);
        for (int bits : first.actions) search::Action(static_cast<std::uint32_t>(bits)).execute(replay);
        assert(replay.outcome == Outcome::PLAYER_VICTORY && replay.player.curHp == first.state->player.curHp);
    }
    std::cout << "PASS: bucket bytes, state recycling, shared score features, repeatable replayable pass (" << trail.size() << " states)\n";
}
