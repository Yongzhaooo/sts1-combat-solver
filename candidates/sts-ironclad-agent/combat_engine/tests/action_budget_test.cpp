#include <cassert>
#include <iostream>
#include <stdexcept>
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/BattleScumSearcher2.h"

using namespace sts;
#include "../../../../overlay/growth_gate.h"
#include "../../../../overlay/loop_beam.h"

static int repeats = 0;
static void repeat(BattleContext &battle) {
    ++repeats;
    battle.addToBot(Action{repeat});
}

static BattleContext fresh() {
    GameContext game(CharacterClass::IRONCLAD, 123, 0);
    BattleContext battle;
    battle.init(game, MonsterEncounter::CULTIST);
    return battle;
}

int main() {
    auto battle = fresh();
    battle.loopCount = BattleContext::ACTION_EXECUTION_LIMIT;
    battle.inputState = InputState::PLAYER_NORMAL;
    const auto hp = battle.player.curHp;
    battle.executeActions();
    assert(battle.player.curHp == hp && battle.outcome == Outcome::UNDECIDED);
    search::BattleScumSearcher2 searcher(battle);
    assert(searcher.isTerminalState(battle));
    loop_beam::stats = {};
    loop_beam::Best best;
    loop_beam::pass(battle, 8, 128, [](const BattleContext &) { return 0.0; }, [] { return false; }, best, true);
    assert(!best.found && loop_beam::stats.transitions == 0);

    auto invalid = fresh();
    invalid.monsters.monstersAlive = -1;
    bool refused = false;
    try { invalid.executeActions(); } catch (const std::runtime_error &) { refused = true; }
    assert(refused);

    auto spin = fresh();
    spin.loopCount = BattleContext::ACTION_EXECUTION_LIMIT;
    spin.inputState = InputState::EXECUTING_ACTIONS;
    spin.addToBot(Action{repeat});
    refused = false;
    try { spin.executeActions(); } catch (const std::runtime_error &) { refused = true; }
    assert(refused && repeats == BattleContext::ACTION_EXECUTION_LIMIT);
    std::cout << "PASS: cumulative search exhaustion, no fabricated outcome, invalid state and live queue guard\n";
}
