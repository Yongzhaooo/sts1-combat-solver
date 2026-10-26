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

    // Equal results retain the shortest sampled route, including cached MCTS terminals.
    const search::Action play(search::ActionType::CARD, 0, 0);
    searcher.updateFromEvaluation({}, {play, play, play}, 100, hp);
    searcher.updateFromEvaluation({}, {play}, 100, hp);
    assert(searcher.bestActionSequence.size() == 1);
    searcher.updateFromEvaluation({}, {play, play}, 100, hp);
    searcher.updateFromEvaluation({}, {}, 99, hp);
    assert(searcher.bestActionSequence.size() == 1 && searcher.bestActionValue == 100);

    auto candidate = fresh();
    candidate.outcome = Outcome::PLAYER_VICTORY;
    auto end = std::make_shared<BattleContext>(candidate);
    loop_beam::Best seed{true, 100, end, {1, 2, 3}};
    auto path = std::make_shared<loop_beam::Path>();
    const auto sameResources = [](const BattleContext &x, const BattleContext &y) {
        return x.player.curHp == y.player.curHp && x.player.maxHp == y.player.maxHp && x.potions == y.potions;
    };
    assert(loop_beam::better(seed, 100, path, candidate, -1));
    assert(!loop_beam::better(seed, 99, path, candidate, -1));
    ++candidate.turn;
    assert(loop_beam::better(seed, 99, path, candidate, -1, sameResources));
    path->length = 4;
    assert(!loop_beam::better(seed, 101, path, candidate, -1, sameResources));
    path->length = 0;
    --candidate.player.curHp;
    assert(!loop_beam::better(seed, 99, path, candidate, -1, sameResources));
    candidate = *end;
    --candidate.player.maxHp;
    assert(!loop_beam::better(seed, 99, path, candidate, -1, sameResources));
    candidate = *end;
    candidate.potions[0] = Potion::FIRE_POTION;
    assert(!loop_beam::better(seed, 99, path, candidate, -1, sameResources));

    auto phase = fresh();
    assert(!loop_beam::retainTurnExits(phase));
    phase.monsters.arr[0].id = MonsterId::NEMESIS;
    assert(loop_beam::retainTurnExits(phase)); // Before its first Intangible turn too.
    phase.monsters.arr[0].id = MonsterId::AWAKENED_ONE;
    assert(loop_beam::retainTurnExits(phase));
    phase.monsters.arr[0].curHp = 0;
    phase.monsters.arr[0].halfDead = true;
    phase.loopCount = 0;
    phase.inputState = InputState::PLAYER_NORMAL;
    phase.cards.cardsInHand = 1;
    phase.cards.hand[0] = CardInstance(CardId::IMMOLATE);
    phase.cards.hand[0].uniqueId = 7;
    phase.player.energy = 3;
    const auto blast = search::Action(search::ActionType::CARD, 0, 0);
    assert(blast.isValidAction(phase)); // Normal search still permits resource preparation during revival.
    loop_beam::Node node{std::make_shared<BattleContext>(phase), nullptr, 0};
    auto motif = loop_beam::append(node, blast);
    loop_beam::stats = {};
    auto repeated = loop_beam::repeatCycle(node, {motif.get()}, 100, [] { return false; }, [](const loop_beam::Node &) {});
    assert(loop_beam::stats.macroActions == 0 && repeated.state->player.energy == 3);

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
