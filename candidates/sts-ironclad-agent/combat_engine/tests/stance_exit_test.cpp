// Issue #21: leaving Calm pays 2 energy, leaving Neutral or Wrath pays nothing, and Divinity ends
// with its own turn so the x3 multiplier cannot cross into the next one.
// CalmStance.onExitStance adds GainEnergyAction(2); DivinityStance.atDamageGive triples normal
// attack damage and DivinityStance.atStartOfTurn queues ChangeStanceAction("Neutral").
#include "combat/Actions.h"
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
    // A Watcher in a quiet first player turn: empty deck, no relics, a monster that cannot die.
    // Every number below is read back off the monster's HP, the block and the energy.
    struct WatcherBattle {
        GameContext game;
        BattleContext battle;

        WatcherBattle() : game(CharacterClass::WATCHER, 123, 20) {
            game.relics = RelicContainer{};
            game.regainControlAction = [](GameContext &) {};
            battle.init(game, MonsterEncounter::CULTIST);
            battle.cards = CardManager{};
            battle.monsters.arr[0].curHp = battle.monsters.arr[0].maxHp = 3000;
            drain();
            battle.player.energy = 10;
        }

        // executeActions returns immediately unless the context is executing, and it always ends
        // in PLAYER_NORMAL, so every drain has to re-open the queue.
        void drain() {
            battle.inputState = InputState::EXECUTING_ACTIONS;
            battle.executeActions();
        }

        // Plays the only card in hand at monster 0 and returns the damage it dealt.
        int hit(CardId card, bool upgraded = false) {
            battle.cards.createTempCardInHand(CardInstance(card, upgraded));
            search::Action action(search::ActionType::CARD, 0, 0);
            check(action.isValidAction(battle), "card is not playable");
            const int hp = battle.monsters.arr[0].curHp;
            action.execute(battle);
            return hp - battle.monsters.arr[0].curHp;
        }
    };
}

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
