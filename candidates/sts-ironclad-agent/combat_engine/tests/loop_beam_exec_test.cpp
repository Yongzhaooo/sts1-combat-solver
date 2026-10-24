// Self-check for the rescue beam's execution shortcuts (overlay/loop_beam.h): state recycling must be indistinguishable from
// a fresh copy, the shared score features must equal the one-shot score, and a pass must be repeatable and replayable.
#include <cassert>
#include <iostream>
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/BattleScumSearcher2.h"

using namespace sts;
#include "../../../../overlay/growth_gate.h"
#include "../../../../overlay/loop_beam.h"

static BattleContext fresh(MonsterEncounter encounter) {
    GameContext game(CharacterClass::IRONCLAD, 123, 0);
    BattleContext battle;
    battle.init(game, encounter);
    return battle;
}

// Plays the first legal action `steps` times so the copy has piles, statuses and a non-trivial queue.
static BattleContext advanced(MonsterEncounter encounter, int steps) {
    auto battle = fresh(encounter);
    battle.executeActions();
    search::BattleScumSearcher2 enumerator(battle);
    search::RolloutNode legal;
    for (int i = 0; i < steps && battle.outcome == Outcome::UNDECIDED && battle.inputState == InputState::PLAYER_NORMAL; ++i) {
        enumerator.enumerateActionsForNode(legal, battle);
        if (legal.edges.empty()) break;
        legal.edges[std::size_t(i) % legal.edges.size()].action.execute(battle);
    }
    return battle;
}

int main() {
    const auto a = advanced(MonsterEncounter::CULTIST, 5), b = advanced(MonsterEncounter::JAW_WORM, 9);
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
    std::cout << "PASS: state recycling, shared score features, repeatable replayable pass\n";
}
