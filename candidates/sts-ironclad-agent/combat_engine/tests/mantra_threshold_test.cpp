// Issue #21: the mantra threshold is MantraPower.stackPower, not a check after every gain.
// The original stackPower calls super.stackPower (which adds the amount) and then, only when the
// power was already stacked and the total reached 10, enters Divinity, subtracts exactly ten and
// drops the power. ApplyPowerAction adds a fresh MantraPower without stackPower at all, which is
// why the first ten mantra do not change stance but the next application does.
// DevotionPower.atStartOfTurnPostDraw instead changes stance directly when ten devotion arrive with
// no MantraPower present, so that path never creates one.
#include "combat/Actions.h"
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"
#include "watcher_battle.h"
#include <iostream>
#include <stdexcept>

using namespace sts;

int main() {
    {
        WatcherBattle b;
        b.battle.player.buff<PS::MANTRA>(9);
        b.battle.addToBot(Actions::GainMantra(1));
        b.drain();
        check(b.battle.player.stance == Stance::DIVINITY, "the tenth mantra must enter Divinity");
        check(!b.battle.player.hasStatus<PS::MANTRA>(), "ten mantra must spend the whole power");
        check(b.battle.player.getStatus<PS::MANTRA>() == 0, "no mantra may be left");
        check(b.battle.player.energy == 13, "entering Divinity grants 3 energy");
    }

    {
        WatcherBattle b;
        b.battle.player.buff<PS::MANTRA>(8);
        b.battle.addToBot(Actions::GainMantra(5));
        b.drain();
        check(b.battle.player.stance == Stance::DIVINITY, "thirteen mantra must enter Divinity");
        check(b.battle.player.getStatus<PS::MANTRA>() == 3, "only ten mantra are spent");
        check(b.battle.player.energy == 13, "Divinity is entered once, not twice");
    }

    {
        // stackPower itself checks "amount >= 10" once, so a single huge gain crosses once.
        WatcherBattle b;
        b.battle.player.buff<PS::MANTRA>(5);
        b.battle.addToBot(Actions::GainMantra(20));
        b.drain();
        check(b.battle.player.getStatus<PS::MANTRA>() == 15,
              "one application subtracts ten mantra, not twenty");
        check(b.battle.player.stance == Stance::DIVINITY, "the threshold was crossed");
        check(b.battle.player.energy == 13, "Divinity is entered once");
    }

    {
        // A fresh MantraPower is not a stackPower call, so the first ten do not enter Divinity.
        WatcherBattle b;
        check(!b.battle.player.hasStatus<PS::MANTRA>(), "a fresh battle has no mantra power");
        b.battle.addToBot(Actions::GainMantra(10));
        b.drain();
        check(b.battle.player.stance == Stance::NEUTRAL, "a fresh power of ten must not enter Divinity");
        check(b.battle.player.getStatus<PS::MANTRA>() == 10, "a fresh power keeps its whole amount");
        check(b.battle.player.energy == 10, "no stance change means no energy");
        b.battle.addToBot(Actions::GainMantra(1));
        b.drain();
        check(b.battle.player.stance == Stance::DIVINITY, "stacking past ten must enter Divinity");
        check(b.battle.player.getStatus<PS::MANTRA>() == 1, "ten of the eleven mantra are spent");
    }

    {
        // DevotionPower.atStartOfTurnPostDraw with no MantraPower: straight into Divinity.
        WatcherBattle b;
        b.battle.player.buff<PS::DEVOTION>(10);
        search::Action(search::ActionType::END_TURN).execute(b.battle);
        check(b.battle.player.stance == Stance::DIVINITY, "ten Devotion with no mantra must enter Divinity");
        check(!b.battle.player.hasStatus<PS::MANTRA>(), "that path never creates a MantraPower");
    }

    {
        // Below the threshold and without a Mantra power the original applies nothing at all:
        // DevotionPower only applies mantra when the player already has the power.
        WatcherBattle b;
        b.battle.player.buff<PS::DEVOTION>(3);
        search::Action(search::ActionType::END_TURN).execute(b.battle);
        check(b.battle.player.stance == Stance::NEUTRAL, "three Devotion must not enter Divinity");
        check(!b.battle.player.hasStatus<PS::MANTRA>(), "three Devotion without a Mantra power adds none");
    }

    {
        // With a Mantra power already present, Devotion stacks onto it through the threshold check.
        WatcherBattle b;
        b.battle.player.buff<PS::MANTRA>(9);
        b.battle.player.buff<PS::DEVOTION>(1);
        search::Action(search::ActionType::END_TURN).execute(b.battle);
        check(b.battle.player.stance == Stance::DIVINITY, "Devotion must stack past the threshold");
        check(b.battle.player.getStatus<PS::MANTRA>() == 0, "ten mantra are spent");
    }

    std::cout << "mantra threshold entered Divinity once, spent exactly ten, "
                 "and Devotion 10/3 behaved as the game does\n";
}
