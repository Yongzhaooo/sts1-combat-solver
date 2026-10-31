#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"
#include <iostream>
#include <stdexcept>
#include <string>

using namespace sts;
static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

namespace {
    // Fresh player-turn battle for the Silent. Empty hand, so only the card the
    // test adds can be played; the starter deck never enters the picture.
    BattleContext freshBattle(MonsterEncounter encounter = MonsterEncounter::CULTIST) {
        GameContext game(CharacterClass::SILENT, 123, 20);
        game.relics = RelicContainer{};
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, encounter);
        battle.cards = CardManager{};
        battle.player.energy = 3;
        battle.setState(InputState::PLAYER_NORMAL);
        return battle;
    }

    // Creates Neutralize in the hand and plays it at the given target.
    void playNeutralize(BattleContext &battle, bool upgraded, int target = 0) {
        battle.cards.createTempCardInHand(CardInstance(CardId::NEUTRALIZE, upgraded));
        const int idx = battle.cards.cardsInHand - 1;
        search::Action play(search::ActionType::CARD, idx, target);
        check(play.isValidAction(battle), "Neutralize should be playable");
        play.execute(battle);
    }

    int weakOf(const BattleContext &bc, int idx) {
        return bc.monsters.arr[idx].getStatus<MS::WEAK>();
    }
}

// Silent M1a: Neutralize and Neutralize+. Values and ordering come from
// com/megacrit/cardcrawl/cards/green/Neutralize.java: DamageAction first, then
// ApplyPowerAction(new WeakPower(m, magicNumber, false)). The false is what makes
// this different from Go for the Eyes, and it is the part that is easy to copy wrong.
int main() {
    // Criterion 1, plus printed values (criteria 2 and 3): the Silent switch owns
    // the card, so the shared fallback must not report it as unimplemented, and the
    // damage and Weak stacks must match the card class in both upgrade states.
    for (bool upgraded : {false, true}) {
        BattleContext bc = freshBattle();
        bc.monsters.arr[0].curHp = bc.monsters.arr[0].maxHp = 3000;
        const int damage = upgraded ? 4 : 3;
        const int weak = upgraded ? 2 : 1;
        bool reported = false;
        try {
            playNeutralize(bc, upgraded);
        } catch (const std::runtime_error &error) {
            reported = std::string(error.what()).find("unimplemented card") != std::string::npos;
            if (!reported) throw;
        }
        check(!reported, "Neutralize must be dispatched by the Silent card switch");
        check(3000 - bc.monsters.arr[0].curHp == damage, "Neutralize should deal its printed damage");
        check(weakOf(bc, 0) == weak, "Neutralize should apply its printed Weak stacks");
        // Player-sourced: WeakPower(..., false) leaves justApplied clear, so the
        // end of the round really does take a stack off (checked below).
        check(!bc.monsters.arr[0].wasJustApplied<MS::WEAK>(),
              "Neutralize's Weak must not be marked justApplied");
    }

    // Criterion 4: the Weak is reduced by one at the end of the round it lands in.
    for (bool upgraded : {false, true}) {
        BattleContext bc = freshBattle(MonsterEncounter::JAW_WORM);
        check(bc.monsters.arr[0].isAttacking(), "Jaw Worm should open by attacking");
        playNeutralize(bc, upgraded);
        check(weakOf(bc, 0) == (upgraded ? 2 : 1), "Neutralize should apply its printed Weak stacks");
        check(!bc.monsters.arr[0].wasJustApplied<MS::WEAK>(),
              "Neutralize's Weak must not be marked justApplied");
        // afterMonsterTurns() is the path the game takes to run the monsters'
        // atEndOfRound powers, so the test exercises that wiring rather than
        // calling applyEndOfRoundPowers() itself.
        bc.afterMonsterTurns();
        check(weakOf(bc, 0) == (upgraded ? 1 : 0),
              "Neutralize's Weak should lose one stack at the end of the round it was applied in");
    }

    // Criterion 5: no isAttacking() gate. Go for the Eyes has one; Neutralize does
    // not, and a Cultist opens with Incantation rather than an attack.
    for (bool upgraded : {false, true}) {
        BattleContext bc = freshBattle();
        check(!bc.monsters.arr[0].isAttacking(), "Cultist should not open by attacking");
        playNeutralize(bc, upgraded);
        check(weakOf(bc, 0) == (upgraded ? 2 : 1),
              "Neutralize should apply Weak even when the target is not attacking");
    }

    // Criterion 6: the Weak follows the target, not the caster, and the other
    // monster takes no damage either.
    for (bool upgraded : {false, true}) {
        BattleContext bc = freshBattle(MonsterEncounter::CHOSEN_AND_BYRDS);
        check(bc.monsters.monsterCount == 2, "Chosen and Byrds should field two monsters");
        const int otherHp = bc.monsters.arr[0].curHp;
        playNeutralize(bc, upgraded, 1);
        check(weakOf(bc, 1) == (upgraded ? 2 : 1),
              "Neutralize should apply Weak to the monster it targeted");
        check(weakOf(bc, 0) == 0, "Neutralize must leave the other monster's Weak alone");
        check(bc.monsters.arr[0].curHp == otherHp,
              "Neutralize must not damage the monster it did not target");
    }

    // Neutralize.java is a 0-cost card, so the printed cost must be 0 and the
    // card must be playable with no energy at all.
    for (bool upgraded : {false, true}) {
        const CardInstance card(CardId::NEUTRALIZE, upgraded);
        check(card.cost == 0 && card.costForTurn == 0,
              "Neutralize should be a 0-cost card");
        BattleContext bc = freshBattle();
        bc.player.energy = 0;
        playNeutralize(bc, upgraded);
        check(bc.player.energy == 0, "Neutralize should not spend energy");
        check(weakOf(bc, 0) == (upgraded ? 2 : 1),
              "Neutralize should still apply Weak when played for free");
    }

    std::cout << "10 Neutralize cases (5 scenarios x 2 upgrade states) passed\n";
}
