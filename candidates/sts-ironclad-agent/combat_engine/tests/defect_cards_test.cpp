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
    BattleContext freshBattle(MonsterEncounter encounter = MonsterEncounter::CULTIST) {
        GameContext game(CharacterClass::DEFECT, 123, 0);
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, encounter);
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
    {
        // Card costs corrected against the wiki and calibration fixtures.
        check(CardInstance(CardId::BEAM_CELL).cost == 0, "Beam Cell should cost 0");
        check(CardInstance(CardId::CHILL).cost == 0, "Chill should cost 0");
        check(CardInstance(CardId::TURBO).cost == 0, "Turbo should cost 0");
        check(CardInstance(CardId::GLACIER).cost == 2, "Glacier should cost 2");
        check(CardInstance(CardId::RECURSION).cost == 1, "Recursion should cost 1");
        check(CardInstance(CardId::RECURSION, true).cost == 0, "upgraded Recursion should cost 0");
    }
    {
        // Defragment: gain Focus; focus raises orb settlement values.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::DEFRAGMENT);
        check(bc.player.focus == 1, "Defragment should gain 1 focus");
        const int hpBefore = bc.monsters.arr[0].curHp;
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(hpBefore - bc.monsters.arr[0].curHp == 4,
              "lightning passive should deal 3+1 focus damage");
    }
    {
        // Consume: gain Focus, then lose one orb slot; the newest orb drops
        // without evoking (no block gained from a dropped Frost).
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::FROST).actFunc(bc);
        Actions::ChannelOrb(Orb::FROST).actFunc(bc); // bar: lightning, frost, frost (full)
        playCard(bc, CardId::CONSUME);
        check(bc.player.orbSlots == 2, "Consume should lose one orb slot");
        check(bc.player.orbs.size() == 2 && bc.player.orbs[0].type == Orb::LIGHTNING
              && bc.player.orbs[1].type == Orb::FROST,
              "Consume should drop the newest orb without evoking");
        check(bc.player.block == 0, "dropping an orb on slot loss should not evoke");
        check(bc.player.focus == 2, "Consume should gain 2 focus");
    }
    {
        // Reprogram: lose Focus, gain Strength and Dexterity.
        BattleContext bc = freshBattle();
        Actions::BuffPlayer<PS::FOCUS>(1).actFunc(bc);
        playCard(bc, CardId::REPROGRAM);
        check(bc.player.focus == 0, "Reprogram should lose 1 focus (1 -> 0)");
        check(bc.player.getStatus<PS::STRENGTH>() == 1, "Reprogram should gain 1 strength");
        check(bc.player.getStatus<PS::DEXTERITY>() == 1, "Reprogram should gain 1 dexterity");
    }
    {
        // Hyperbeam: 26 damage to all enemies, then lose 3 Focus. Vanilla
        // FocusPower has canGoNegative=true, so focus can drop below zero.
        BattleContext bc = freshBattle();
        Actions::BuffPlayer<PS::FOCUS>(1).actFunc(bc);
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::HYPERBEAM);
        check(hp - bc.monsters.arr[0].curHp == 26, "Hyperbeam should deal 26");
        check(bc.player.focus == -2, "Hyperbeam should lose 3 focus below zero (1 -> -2)");
    }
    {
        // Biased Cognition: gain Focus, apply BIAS; turn start drains Focus.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::BIASED_COGNITION);
        check(bc.player.focus == 4, "Biased Cognition should gain 4 focus");
        check(bc.player.hasStatus<PS::BIAS>(), "Biased Cognition should apply BIAS");
        bc.player.applyStartOfTurnPowers(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(bc.player.focus == 3, "BIAS should drain 1 focus at turn start");
        check(bc.player.hasStatus<PS::BIAS>(), "BIAS should persist across turns");
    }

    {
        // Dark orb: channels with counter 6, grows by max(0, 6 + focus) per end
        // of turn, and focus never resets the accumulated counter.
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::DARK).actFunc(bc);
        check(bc.player.orbs.back().darkCounter == 6, "Dark orb should start with counter 6");
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
        check(bc.player.orbs.back().darkCounter == 12, "Dark counter should grow by 6 at focus 0");
        Actions::BuffPlayer<PS::FOCUS>(2).actFunc(bc);
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
        check(bc.player.orbs.back().darkCounter == 20, "Dark counter should grow by 6+2 focus");
        check(bc.player.orbs.size() == 2, "Dark passive should not consume the orb");
    }
    {
        // Dark evoke hits the lowest-HP enemy (TWO_LOUSE for two targets).
        BattleContext bc = freshBattle(MonsterEncounter::TWO_LOUSE);
        Actions::ChannelOrb(Orb::DARK).actFunc(bc);
        int lowest = -1, other = -1, lowestHp = 0;
        for (int i = 0; i < bc.monsters.monsterCount; ++i) {
            const auto &m = bc.monsters.arr[i];
            if (m.isDeadOrEscaped()) continue;
            if (lowest < 0 || m.curHp < lowestHp) {
                other = lowest;
                lowest = i;
                lowestHp = m.curHp;
            } else {
                other = i;
            }
        }
        check(lowest >= 0 && other >= 0, "TWO_LOUSE should provide two alive enemies");
        const int lowestBefore = bc.monsters.arr[lowest].curHp;
        const int otherBefore = bc.monsters.arr[other].curHp;
        // The bar holds Cracked Core's lightning at index 0; evoke the Dark at the end.
        Actions::EvokeOrbAction(static_cast<int>(bc.player.orbs.size()) - 1).actFunc(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(lowestBefore - bc.monsters.arr[lowest].curHp == 6, "Dark evoke should hit the lowest-HP enemy for 6");
        check(bc.monsters.arr[other].curHp == otherBefore, "Dark evoke should not touch the other enemy");
        check(bc.player.orbs.size() == 1, "evoking should consume the Dark orb");
    }
    {
        // Recursion preserves the Dark counter (the same orb object rechannels).
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::DARK).actFunc(bc);          // bar: lightning, dark
        Actions::EvokeOrbAction(0).actFunc(bc);              // clear Cracked Core's lightning
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc); // counter 6 -> 12
        playCard(bc, CardId::RECURSION);
        check(bc.player.orbs.size() == 1 && bc.player.orbs[0].darkCounter == 12,
              "Recursion should rechannel the Dark orb with its counter");
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
        check(bc.player.orbs[0].darkCounter == 18, "the rechanneled Dark orb keeps growing");
    }
    {
        // Darkness: channel a Dark orb; upgraded also grows it immediately.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::DARKNESS);
        check(bc.player.orbs.size() == 2 && bc.player.orbs.back().darkCounter == 6,
              "Darkness should channel a Dark orb with counter 6");
        BattleContext up2 = freshBattle();
        up2.cards.createTempCardInHand(CardInstance(CardId::DARKNESS, true));
        search::Action play(search::ActionType::CARD, 0, 0);
        check(play.isValidAction(up2), "upgraded Darkness should be playable");
        play.execute(up2);
        check(up2.player.orbs.back().darkCounter == 12,
              "upgraded Darkness should grow the new Dark orb immediately");
    }
    {
        // Doom and Gloom: 10 damage to all enemies, then a Dark orb.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::DOOM_AND_GLOOM);
        check(hp - bc.monsters.arr[0].curHp == 10, "Doom and Gloom should deal 10");
        check(bc.player.orbs.back().type == Orb::DARK, "Doom and Gloom should channel a Dark orb");
    }
    {
        // Rainbow: one orb of each of the three types; base exhausts. With only
        // three slots the third channel fills the bar and the fourth evokes the
        // oldest lightning (8 damage to the lone Cultist).
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::RAINBOW);
        check(bc.player.orbs.size() == 3, "the bar stays capped at the slot count");
        check(bc.player.orbs[0].type == Orb::LIGHTNING && bc.player.orbs[1].type == Orb::FROST
              && bc.player.orbs[2].type == Orb::DARK,
              "Rainbow should channel Lightning, Frost and Dark, evoking the oldest");
        check(hp - bc.monsters.arr[0].curHp == 8, "the pushed-out lightning should evoke for 8");
        check(bc.cards.exhaustPile.size() == 1, "base Rainbow should exhaust");
        check(CardInstance(CardId::RAINBOW, true).doesExhaust() == false,
              "upgraded Rainbow should not exhaust");
    }

    {
        // Plasma orb: +1 energy per orb at turn start, evoke +2, focus never scales it.
        BattleContext bc = freshBattle();
        Actions::BuffPlayer<PS::FOCUS>(3).actFunc(bc);
        Actions::ChannelOrb(Orb::FUSION).actFunc(bc); // bar: lightning, plasma
        bc.player.energy = 0;
        Actions::TriggerStartOfTurnOrbsAction().actFunc(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(bc.player.energy == 1, "Plasma should gain 1 energy at turn start, focus-immune");
        Actions::EvokeOrbAction(1).actFunc(bc); // the plasma at the open end
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(bc.player.energy == 3, "Plasma evoke should gain 2 energy");
        check(bc.player.orbs.size() == 1, "evoking should consume the Plasma orb");
    }
    {
        // Fusion: channel one Plasma orb; 2 energy (1 upgraded).
        BattleContext bc = freshBattle();
        playCard(bc, CardId::FUSION);
        check(bc.player.orbs.back().type == Orb::FUSION, "Fusion should channel a Plasma orb");
        check(CardInstance(CardId::FUSION).cost == 2, "Fusion should cost 2");
        check(CardInstance(CardId::FUSION, true).cost == 1, "upgraded Fusion should cost 1");
    }
    {
        // Ball Lightning: 7 damage and one Lightning orb (not a random orb).
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::BALL_LIGHTNING);
        check(hp - bc.monsters.arr[0].curHp == 7, "Ball Lightning should deal 7");
        check(bc.player.orbs.back().type == Orb::LIGHTNING, "Ball Lightning should channel a Lightning orb");
    }
    {
        // Chaos: one random orb from [Dark, Frost, Lightning]; vanilla excludes Plasma.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::CHAOS);
        const Orb type = bc.player.orbs.back().type;
        check(type == Orb::DARK || type == Orb::FROST || type == Orb::LIGHTNING,
              "Chaos should never channel Plasma");
        BattleContext up2 = freshBattle();
        up2.cards.createTempCardInHand(CardInstance(CardId::CHAOS, true));
        search::Action play(search::ActionType::CARD, 0, 0);
        check(play.isValidAction(up2), "upgraded Chaos should be playable");
        play.execute(up2);
        check(up2.player.orbs.size() == 3, "upgraded Chaos should channel two orbs");
        for (int i = 0; i < up2.player.orbs.size(); ++i) {
            check(up2.player.orbs[i].type != Orb::FUSION, "upgraded Chaos never channels Plasma");
        }
    }
    {
        // Tempest: X-cost, channels X Lightning orbs and exhausts.
        BattleContext bc = freshBattle();
        Actions::EvokeOrbAction(0).actFunc(bc); // clear Cracked Core's lightning
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        playCard(bc, CardId::TEMPEST);
        // X resolves to the energy spent at play time (Whirlwind pattern). The engine
        // does not deduct energy for X-cost cards anywhere yet, so only the orb count
        // is asserted here.
        check(bc.player.orbs.size() == 3, "Tempest with 3 energy should channel three orbs");
        for (int i = 0; i < bc.player.orbs.size(); ++i) {
            check(bc.player.orbs[i].type == Orb::LIGHTNING, "Tempest should channel Lightning orbs");
        }
        check(CardInstance(CardId::TEMPEST).cost == -1, "Tempest should be an X-cost card");
        check(CardInstance(CardId::TEMPEST).doesExhaust(), "Tempest should exhaust");
    }

    std::cout << "M3b Defect card behaviors hold\n";
    return 0;
    } catch (const std::exception &e) {
        std::cout << "FAILED: " << e.what() << std::endl;
        return 1;
    }
}
