// Issue #21: a stance change really happens, and the damage that follows is the game's.
// Eruption.java queues its hit before ChangeStanceAction("Wrath"), Vigilance.java grants block
// before ChangeStanceAction("Calm"). WrathStance.atDamageGive doubles and DivinityStance.atDamageGive
// triples normal attack damage; DivinityStance.onEnterStance grants 3 energy. ChangeStanceAction.update
// returns without any effect when the requested stance is the current one, so a repeated Eruption or
// Vigilance must not run the exit and enter effects a second time.
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
        check(b.battle.player.stance == Stance::NEUTRAL, "a Watcher starts in Neutral");
        // The hit is still calculated in Neutral, then the card enters Wrath.
        check(b.hit(CardId::ERUPTION) == 9, "Eruption must hit for 9 before its stance change");
        check(b.battle.player.stance == Stance::WRATH, "Eruption must enter Wrath");
        check(b.battle.player.energy == 8, "Eruption costs 2 and entering Wrath grants no energy");
        // Already in Wrath: the same card now deals double, and the same-stance request is a no-op.
        check(b.hit(CardId::ERUPTION) == 18, "Wrath must double attack damage");
        check(b.battle.player.stance == Stance::WRATH, "a repeated Eruption stays in Wrath");
        check(b.battle.player.energy == 6, "a repeated stance change must not grant energy");
    }

    {
        WatcherBattle b;
        check(b.hit(CardId::VIGILANCE) == 0, "Vigilance deals no damage");
        check(b.battle.player.block == 8, "Vigilance must grant 8 block");
        check(b.battle.player.stance == Stance::CALM, "Vigilance must enter Calm");
        check(b.battle.player.energy == 8, "Vigilance costs 2 and entering Calm grants no energy");
        check(b.hit(CardId::VIGILANCE, true) == 0, "upgraded Vigilance deals no damage");
        check(b.battle.player.block == 20, "upgraded Vigilance must add its 12 block");
        check(b.battle.player.stance == Stance::CALM, "a repeated Vigilance stays in Calm");
        // Staying in Calm must not pay CalmStance.onExitStance's 2 energy.
        check(b.battle.player.energy == 6, "a repeated Calm must not pay the exit bonus");
    }

    {
        // The only way into Divinity today is MantraPower.stackPower at the threshold.
        WatcherBattle b;
        b.battle.player.buff<PS::MANTRA>(9);
        b.battle.addToBot(Actions::GainMantra(1));
        b.drain();
        check(b.battle.player.stance == Stance::DIVINITY, "ten mantra must enter Divinity");
        check(b.battle.player.energy == 13, "entering Divinity grants 3 energy");
        check(b.hit(CardId::ERUPTION) == 27, "Divinity must triple attack damage");
        // Eruption itself then leaves Divinity for Wrath; DivinityStance.onExitStance pays nothing.
        check(b.battle.player.stance == Stance::WRATH, "Eruption after Divinity must enter Wrath");
        check(b.battle.player.energy == 11, "Eruption costs 2 and leaving Divinity grants nothing");
    }

    std::cout << "Eruption 9/18/27 across Neutral, Wrath and Divinity, Vigilance 8/12 block, "
                 "repeated stance requests ignored\n";
}
