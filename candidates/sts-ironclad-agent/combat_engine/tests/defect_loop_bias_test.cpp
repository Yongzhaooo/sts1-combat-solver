// F22 (Defect, Ascension 20, Shelled Parasite with Plated Armor 14): the game and the
// engine disagreed on the monster's block by two - game 2, engine 4.
//
// The game's LoopPower.atStartOfTurn triggers the oldest orb's passive right away, and
// the orb deals its stored passiveAmount. BiasPower.atStartOfTurn only *queues* its
// Focus decrement, so the trigger still fires with the Focus the turn ended with. The
// engine recomputed "3 + focus" when the queued trigger finally ran, i.e. after the
// decrement, and every Loop stack lost one damage.
//
// Frame: Focus 3, Bias 1, Loop 2, orbs Lightning/Frost/Lightning, monster block 14 and
// Plated Armor 14. Loop fires the Lightning twice for 6 each, so the block has to end at
// 14 - 12 = 2, with the monster's HP untouched.
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"
#include <iostream>
#include <stdexcept>

using namespace sts;

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

namespace {
    struct Result {
        int block;
        int hp;
        int focus;
    };

    // loopFirst mirrors the power acquisition order: the game's power list only gets a
    // stable sort by priority, so Loop and Bias keep the order they were applied in and
    // both orders have to agree.
    Result runBoard(bool loopFirst) {
        GameContext game(CharacterClass::DEFECT, 123, 20);
        game.regainControlAction = [](GameContext &) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::SHELL_PARASITE);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        battle.player.focus = 3;
        battle.player.channelOrb(Orb::FROST);
        battle.player.channelOrb(Orb::LIGHTNING);
        if (loopFirst) {
            battle.player.buff<PS::LOOP>(2);
        }
        battle.player.buff<PS::BIAS>(1);
        if (!loopFirst) {
            battle.player.buff<PS::LOOP>(2);
        }

        auto &monster = battle.monsters.arr[0];
        const int hpBefore = monster.curHp;

        search::Action end(search::ActionType::END_TURN, 0, 0);
        check(end.isValidAction(battle), "end turn should be legal");
        end.execute(battle);

        return {monster.block, monster.curHp - hpBefore, battle.player.focus};
    }
}

int main() {
    // Loop before Bias: the order the fight had, and the one the frame was recorded with.
    const Result frame = runBoard(true);
    std::cout << "loop first: block=" << frame.block << " hp_delta=" << frame.hp
              << " focus=" << frame.focus << std::endl;
    check(frame.block == 2, "Loop must fire with the Focus the turn ended with (block 2, not 4)");
    check(frame.hp == 0, "the Loop damage stays inside the Plated Armor block");
    check(frame.focus == 2, "Bias still drops Focus by one");

    // The other acquisition order must reach the same numbers.
    const Result reversed = runBoard(false);
    std::cout << "bias first: block=" << reversed.block << " hp_delta=" << reversed.hp
              << " focus=" << reversed.focus << std::endl;
    check(reversed.block == frame.block, "the buff order must not move the block");
    check(reversed.hp == frame.hp, "the buff order must not move the monster's HP");
    check(reversed.focus == 2, "Bias still drops Focus by one");

    std::cout << "defect_loop_bias ok" << std::endl;
    return 0;
}
