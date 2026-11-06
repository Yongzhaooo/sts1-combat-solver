// Issue #21: leaving Calm pays 2 energy, leaving Neutral or Wrath pays nothing, and Divinity ends
// with its own turn so the x3 multiplier cannot cross into the next one.
// CalmStance.onExitStance adds GainEnergyAction(2); DivinityStance.atDamageGive triples normal
// attack damage and DivinityStance.atStartOfTurn queues ChangeStanceAction("Neutral").
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
        WatcherBattle b;   // Neutral, 10 energy
        check(b.hit(CardId::VIGILANCE) == 0, "Vigilance deals no damage");
        check(b.battle.player.stance == Stance::CALM, "Vigilance must enter Calm");
        check(b.battle.player.energy == 8, "Vigilance costs 2");
        check(b.hit(CardId::ERUPTION) == 9, "Eruption in Calm keeps its printed 9");
        check(b.battle.player.stance == Stance::WRATH, "Eruption must leave Calm for Wrath");
        // 8 - 2 for Eruption + 2 from CalmStance.onExitStance.
        check(b.battle.player.energy == 8, "leaving Calm must grant exactly 2 energy");
    }

    {
        // The other two exits are silent.
        WatcherBattle b;
        check(b.hit(CardId::ERUPTION) == 9, "Eruption must hit for 9 from Neutral");
        check(b.battle.player.energy == 8, "leaving Neutral must grant no energy");
        check(b.hit(CardId::VIGILANCE) == 0, "Vigilance deals no damage");
        check(b.battle.player.stance == Stance::CALM, "Vigilance must enter Calm from Wrath");
        check(b.battle.player.energy == 6, "leaving Wrath must grant no energy");
    }

    {
        // Divinity ends when its turn ends, not when the next turn's cards are played.
        WatcherBattle b;
        b.battle.player.buff<PS::MANTRA>(9);
        b.battle.addToBot(Actions::GainMantra(1));
        b.drain();
        check(b.battle.player.stance == Stance::DIVINITY, "ten mantra must enter Divinity");
        search::Action(search::ActionType::END_TURN).execute(b.battle);
        check(b.battle.player.stance == Stance::NEUTRAL, "Divinity must return to Neutral for the next turn");
        check(b.battle.player.energy == 3, "the new turn starts with three energy");
        check(b.hit(CardId::ERUPTION) == 9, "the Divinity multiplier must not cross the turn");
        check(b.battle.player.energy == 1, "Eruption from Neutral costs 2");
    }

    std::cout << "Calm exit paid 2 energy, Neutral and Wrath exits paid none, "
                 "Divinity returned to Neutral at the next turn\n";
}
