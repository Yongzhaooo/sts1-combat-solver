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
    // Fresh player-turn battle vs a single Cultist, empty hand, 3 energy.
    BattleContext freshBattle() {
        GameContext game(CharacterClass::DEFECT, 123, 0);
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        return battle;
    }

    // Creates the card in the empty hand and plays it at index 0.
    void playCard(BattleContext &battle, CardId id) {
        battle.cards.createTempCardInHand(CardInstance(id));
        search::Action play(search::ActionType::CARD, 0, 0);
        check(play.isValidAction(battle), "card should be playable");
        play.execute(battle);
    }

    bool discardContains(const BattleContext &battle, CardId id) {
        for (const auto &card : battle.cards.discardPile) {
            if (card.getId() == id) {
                return true;
            }
        }
        return false;
    }
}

// M3b Defect cards: common attacks/skills that only use existing mechanics.
// Values cross-checked against the calibration fixtures and the game wiki.
int main() {
    try {
    {
        // Cold Snap: 6 damage, then channel one Frost orb.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::COLD_SNAP);
        check(hp - bc.monsters.arr[0].curHp == 6, "Cold Snap should deal 6");
        check(bc.player.orbs.size() == 2 && bc.player.orbs.back().type == Orb::FROST,
              "Cold Snap should channel a Frost orb");
    }
    {
        // Beam Cell: 3 damage, then 1 Vulnerable.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::BEAM_CELL);
        check(hp - bc.monsters.arr[0].curHp == 3, "Beam Cell should deal 3");
        check(bc.monsters.arr[0].hasStatus<MS::VULNERABLE>(), "Beam Cell should apply Vulnerable");
    }
    {
        // Compile Driver: 7 damage, draw one card per distinct orb type.
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::FROST).actFunc(bc); // bar: lightning, frost
        bc.cards.createTempCardInDrawPile(0, CardInstance(CardId::STRIKE_BLUE));
        bc.cards.createTempCardInDrawPile(1, CardInstance(CardId::STRIKE_BLUE));
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::COMPILE_DRIVER);
        check(hp - bc.monsters.arr[0].curHp == 7, "Compile Driver should deal 7");
        check(bc.cards.cardsInHand == 2, "Compile Driver should draw 2 for two orb types");
    }
    {
        // Barrage: 4 damage per orb in the bar.
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::FROST).actFunc(bc);
        Actions::ChannelOrb(Orb::FROST).actFunc(bc); // bar: lightning, frost, frost
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::BARRAGE);
        check(hp - bc.monsters.arr[0].curHp == 12, "Barrage should deal 4 per orb (12)");
    }
    {
        // Sweeping Beam: 6 damage to all enemies, then draw one card.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInDrawPile(0, CardInstance(CardId::STRIKE_BLUE));
        bc.cards.createTempCardInDrawPile(1, CardInstance(CardId::STRIKE_BLUE));
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::SWEEPING_BEAM);
        check(hp - bc.monsters.arr[0].curHp == 6, "Sweeping Beam should deal 6");
        check(bc.cards.cardsInHand == 1, "Sweeping Beam should draw one card");
    }
    {
        // Coolheaded: channel one Frost orb, then draw one card.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInDrawPile(0, CardInstance(CardId::STRIKE_BLUE));
        bc.cards.createTempCardInDrawPile(1, CardInstance(CardId::STRIKE_BLUE));
        playCard(bc, CardId::COOLHEADED);
        check(bc.player.orbs.size() == 2 && bc.player.orbs.back().type == Orb::FROST,
              "Coolheaded should channel a Frost orb");
        check(bc.cards.cardsInHand == 1, "Coolheaded should draw one card");
    }
    {
        // Leap: 9 block.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::LEAP);
        check(bc.player.block == 9, "Leap should grant 9 block");
    }
    {
        // Stack: block equal to the discard pile size.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInDiscard(CardInstance(CardId::STRIKE_BLUE));
        bc.cards.createTempCardInDiscard(CardInstance(CardId::STRIKE_BLUE));
        bc.cards.createTempCardInDiscard(CardInstance(CardId::STRIKE_BLUE));
        playCard(bc, CardId::STACK);
        check(bc.player.block == 3, "Stack should grant block equal to the discard pile");
    }
    {
        // Turbo: gain 2 energy, then add a Void to the discard pile.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::TURBO);
        check(bc.player.energy == 5, "Turbo should gain 2 energy");
        check(discardContains(bc, CardId::VOID), "Turbo should add a Void to the discard pile");
    }
    {
        // Recursion: evoke the oldest orb, then rechannel its type.
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::FROST).actFunc(bc); // bar: lightning, frost
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::RECURSION);
        check(hp - bc.monsters.arr[0].curHp == 8, "Recursion should evoke the oldest lightning for 8");
        check(bc.player.orbs.size() == 2 && bc.player.orbs[0].type == Orb::FROST
              && bc.player.orbs[1].type == Orb::LIGHTNING,
              "Recursion should rechannel the evoked type at the open end");
    }
    {
        // Glacier: 7 block, then channel two Frost orbs.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::GLACIER);
        check(bc.player.block == 7, "Glacier should grant 7 block");
        check(bc.player.orbs.size() == 3 && bc.player.orbs[1].type == Orb::FROST
              && bc.player.orbs[2].type == Orb::FROST,
              "Glacier should channel two Frost orbs");
    }
    {
        // Chill: one Frost orb per alive enemy; the card exhausts.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::CHILL);
        check(bc.player.orbs.size() == 2 && bc.player.orbs.back().type == Orb::FROST,
              "Chill should channel one Frost orb for the single enemy");
        check(bc.cards.exhaustPile.size() == 1, "Chill should exhaust");
    }

    std::cout << "M3b Defect card behaviors hold\n";
    return 0;
    } catch (const std::exception &e) {
        std::cout << "FAILED: " << e.what() << std::endl;
        return 1;
    }
}
