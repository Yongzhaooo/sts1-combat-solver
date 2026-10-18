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
        // Play the card just added; Echo Form copies can leave earlier slots occupied.
        const int idx = battle.cards.cardsInHand - 1;
        search::Action play(search::ActionType::CARD, idx, 0);
        if (!play.isValidAction(battle)) {
            throw std::runtime_error(std::string("card should be playable: ")
                + cardEnumStrings[static_cast<int>(id)]);
        }
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
        check(bc.player.energy == 0, "Tempest should spend all energy (X-cost)");
        check(bc.player.orbs.size() == 3, "Tempest with 3 energy should channel three orbs");
        for (int i = 0; i < bc.player.orbs.size(); ++i) {
            check(bc.player.orbs[i].type == Orb::LIGHTNING, "Tempest should channel Lightning orbs");
        }
        check(CardInstance(CardId::TEMPEST).cost == -1, "Tempest should be an X-cost card");
        check(CardInstance(CardId::TEMPEST).doesExhaust(), "Tempest should exhaust");
    }

    {
        // Capacitor: gain 2 orb slots.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::CAPACITOR);
        check(bc.player.orbSlots == 5, "Capacitor should gain 2 orb slots");
    }
    {
        // Loop: at turn start, trigger the oldest orb's passive once per stack.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::LOOP);
        check(bc.player.hasStatus<PS::LOOP>(), "Loop should apply the Loop power");
        const int hp = bc.monsters.arr[0].curHp;
        bc.player.applyStartOfTurnPowers(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(hp - bc.monsters.arr[0].curHp == 3, "Loop should trigger the lightning passive for 3");
        check(bc.player.orbs.size() == 1, "Loop should not consume the orb");
    }
    {
        // Electrodynamics: lightning hits all enemies (passive and evoke), no target roll.
        BattleContext bc = freshBattle(MonsterEncounter::TWO_LOUSE);
        playCard(bc, CardId::ELECTRODYNAMICS); // power + 2 lightning, bar full (3)
        check(bc.player.hasStatus<PS::ELECTRO>(), "Electrodynamics should apply the Electro power");
        check(bc.player.orbs.size() == 3, "Electrodynamics should channel two Lightning orbs");
        int hp0 = bc.monsters.arr[0].curHp;
        int hp1 = bc.monsters.arr[1].curHp;
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
        for (int i = 0; i < 16 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(hp0 - bc.monsters.arr[0].curHp == 9 && hp1 - bc.monsters.arr[1].curHp == 9,
              "three lightning passives should hit both enemies for 9 each under Electro");
        // The passives nearly kill the lice (13 HP), so give them headroom and
        // clear any curl-up block before measuring the evoke damage.
        for (int i = 0; i < bc.monsters.monsterCount; ++i) {
            bc.monsters.arr[i].curHp = 100;
            bc.monsters.arr[i].block = 0;
        }
        hp0 = bc.monsters.arr[0].curHp;
        hp1 = bc.monsters.arr[1].curHp;
        Actions::EvokeOrbAction(0).actFunc(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(hp0 - bc.monsters.arr[0].curHp == 8 && hp1 - bc.monsters.arr[1].curHp == 8,
              "a lightning evoke should hit both enemies for 8 under Electro");
    }
    {
        // Storm: playing a Power channels a Lightning orb (the Storm card itself does not).
        BattleContext bc = freshBattle();
        playCard(bc, CardId::STORM);
        check(bc.player.orbs.size() == 1, "playing Storm itself should not trigger it");
        playCard(bc, CardId::CAPACITOR);
        check(bc.player.orbs.size() == 2 && bc.player.orbs.back().type == Orb::LIGHTNING,
              "Storm should channel a Lightning orb when a Power is played");
    }
    {
        // Heatsinks: playing a Power draws a card.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInDrawPile(0, CardInstance(CardId::STRIKE_BLUE));
        bc.cards.createTempCardInDrawPile(1, CardInstance(CardId::STRIKE_BLUE));
        playCard(bc, CardId::HEATSINKS);
        playCard(bc, CardId::CAPACITOR);
        check(bc.cards.cardsInHand == 1, "Heatsinks should draw one card when a Power is played");
    }
    {
        // Machine Learning: +1 card drawn per turn (persistent draw count).
        BattleContext bc = freshBattle();
        check(bc.player.cardDrawPerTurn == 5, "base draw should start at 5");
        playCard(bc, CardId::MACHINE_LEARNING);
        check(bc.player.cardDrawPerTurn == 6, "Machine Learning should add one card draw per turn");
    }

    {
        // Go for the Eyes: 0-cost 3 damage; Weak only when the target intends to attack.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        const bool attacking = bc.monsters.arr[0].isAttacking();
        playCard(bc, CardId::GO_FOR_THE_EYES);
        check(hp - bc.monsters.arr[0].curHp == 3, "Go for the Eyes should deal 3");
        check(bc.monsters.arr[0].getStatus<MS::WEAK>() == (attacking ? 1 : 0),
              "Go for the Eyes should apply Weak only to an attacking enemy");
    }
    {
        // Auto Shields: block only when the player has none.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::AUTO_SHIELDS);
        check(bc.player.block == 11, "Auto Shields should grant 11 block at zero block");
        BattleContext bc2 = freshBattle();
        Actions::GainBlock(5).actFunc(bc2);
        playCard(bc2, CardId::AUTO_SHIELDS);
        check(bc2.player.block == 5, "Auto Shields should do nothing when block is held");
    }
    {
        // Boot Sequence: 10 block for free; innate metadata lives in Cards.h.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::BOOT_SEQUENCE);
        check(bc.player.block == 10, "Boot Sequence should grant 10 block");
        check(CardInstance(CardId::BOOT_SEQUENCE).cost == 0, "Boot Sequence should cost 0");
    }
    {
        // Charge Battery: block now, +1 energy next turn through ENERGIZED.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::CHARGE_BATTERY);
        check(bc.player.block == 7, "Charge Battery should grant 7 block");
        check(bc.player.getStatus<PS::ENERGIZED>() == 1, "Charge Battery should energize next turn");
    }
    {
        // Double Energy: doubles the current energy after paying the 1 cost.
        BattleContext bc = freshBattle();
        bc.player.energy = 3;
        playCard(bc, CardId::DOUBLE_ENERGY);
        check(bc.player.energy == 4, "Double Energy should double the 2 energy left after cost");
        check(CardInstance(CardId::DOUBLE_ENERGY, true).cost == 0, "upgraded Double Energy is free");
    }
    {
        // Multi-Cast: evoke the oldest orb X times, consume it once, spend all energy.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        bc.player.energy = 3;
        playCard(bc, CardId::MULTI_CAST);
        check(bc.player.energy == 0, "Multi-Cast should spend all energy");
        check(bc.player.orbs.empty(), "Multi-Cast should consume the evoked orb once");
        check(hp - bc.monsters.arr[0].curHp == 24, "Multi-Cast should evoke lightning three times for 8 each");
    }
    {
        // Reinforced Body: X blocks of 7 for 3 energy, then all energy is spent.
        BattleContext bc = freshBattle();
        bc.player.energy = 3;
        playCard(bc, CardId::REINFORCED_BODY);
        check(bc.player.energy == 0, "Reinforced Body should spend all energy");
        check(bc.player.block == 21, "Reinforced Body should grant 7 block three times");
    }
    {
        // Meteor Strike: 5 energy, 24 damage, then three Plasma orbs.
        BattleContext bc = freshBattle();
        Actions::EvokeOrbAction(0).actFunc(bc); // clear Cracked Core's lightning
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        bc.player.energy = 5;
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::METEOR_STRIKE);
        check(hp - bc.monsters.arr[0].curHp == 24, "Meteor Strike should deal 24");
        check(bc.player.orbs.size() == 3, "Meteor Strike should channel three orbs");
        for (int i = 0; i < 3; ++i) {
            check(bc.player.orbs[i].type == Orb::FUSION, "Meteor Strike should channel Plasma");
        }
        check(CardInstance(CardId::METEOR_STRIKE).cost == 5, "Meteor Strike should cost 5");
    }
    {
        // Core Surge: 11 damage and one Artifact.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::CORE_SURGE);
        check(hp - bc.monsters.arr[0].curHp == 11, "Core Surge should deal 11");
        check(bc.player.getStatus<PS::ARTIFACT>() == 1, "Core Surge should grant 1 Artifact");
    }
    {
        // Melter: strips all block before the 10 damage lands.
        BattleContext bc = freshBattle();
        bc.monsters.arr[0].block = 10;
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::MELTER);
        check(bc.monsters.arr[0].block == 0, "Melter should remove all block");
        check(hp - bc.monsters.arr[0].curHp == 10, "Melter should deal 10 through the removed block");
    }
    {
        // Sunder: 3 energy, gains 3 energy back only when the hit kills.
        BattleContext bc = freshBattle();
        bc.monsters.arr[0].curHp = 10;
        bc.player.energy = 3;
        playCard(bc, CardId::SUNDER);
        check(bc.player.energy == 3, "Sunder should refund 3 energy on a kill");
        BattleContext bc2 = freshBattle();
        bc2.monsters.arr[0].curHp = 200;
        bc2.player.energy = 3;
        playCard(bc2, CardId::SUNDER);
        check(bc2.player.energy == 0, "Sunder should not refund energy without a kill");
    }
    {
        // Aggregate: 1 energy per 4 cards in the draw pile (3 upgraded).
        BattleContext bc = freshBattle();
        for (int i = 0; i < 8; ++i) {
            bc.cards.createTempCardInDrawPile(i, CardInstance(CardId::STRIKE_BLUE));
        }
        bc.player.energy = 1; // enough to pay the 1 cost
        playCard(bc, CardId::AGGREGATE);
        check(bc.player.energy == 2, "Aggregate should gain 1 energy per 4 draw pile cards");
    }

    {
        // Skim: draw 3.
        BattleContext bc = freshBattle();
        for (int i = 0; i < 5; ++i) {
            bc.cards.createTempCardInDrawPile(i, CardInstance(CardId::STRIKE_BLUE));
        }
        playCard(bc, CardId::SKIM);
        check(bc.cards.cardsInHand == 3, "Skim should draw 3 cards");
    }
    {
        // Overclock: draw 2, then a Burn enters the discard pile.
        BattleContext bc = freshBattle();
        for (int i = 0; i < 4; ++i) {
            bc.cards.createTempCardInDrawPile(i, CardInstance(CardId::STRIKE_BLUE));
        }
        playCard(bc, CardId::OVERCLOCK);
        check(bc.cards.cardsInHand == 2, "Overclock should draw 2 cards");
        check(discardContains(bc, CardId::BURN), "Overclock should add a Burn to the discard pile");
    }
    {
        // Rip and Tear: two 7-damage hits on a lone enemy.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::RIP_AND_TEAR);
        check(hp - bc.monsters.arr[0].curHp == 14, "Rip and Tear should hit twice for 7");
    }
    {
        // Fission: remove every orb, gaining 1 energy and 1 card per orb.
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::FROST).actFunc(bc);
        Actions::ChannelOrb(Orb::FROST).actFunc(bc); // bar: lightning, frost, frost
        for (int i = 0; i < 6; ++i) {
            bc.cards.createTempCardInDrawPile(i, CardInstance(CardId::STRIKE_BLUE));
        }
        bc.player.energy = 0;
        playCard(bc, CardId::FISSION);
        check(bc.player.orbs.empty(), "Fission should remove every orb");
        check(bc.player.energy == 3, "Fission should gain 1 energy per orb removed");
        check(bc.cards.cardsInHand == 3, "Fission should draw 1 card per orb removed");
    }
    {
        // FTL: damage plus a draw while fewer than 3 cards were played this turn.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInDrawPile(0, CardInstance(CardId::STRIKE_BLUE));
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::FTL);
        check(hp - bc.monsters.arr[0].curHp == 5, "FTL should deal 5");
        check(bc.cards.cardsInHand == 1, "FTL should draw a card on an early turn play");
        BattleContext bc2 = freshBattle();
        bc2.player.cardsPlayedThisTurn = 5;
        bc2.cards.createTempCardInDrawPile(0, CardInstance(CardId::STRIKE_BLUE));
        playCard(bc2, CardId::FTL);
        check(bc2.cards.cardsInHand == 0, "FTL should not draw after 4 or more cards");
    }
    {
        // Buffer: one stack absorbs the next HP loss.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::BUFFER);
        check(bc.player.getStatus<PS::BUFFER>() == 1, "Buffer should grant one stack");
        const int hp = bc.player.curHp;
        bc.player.damage(bc, 5);
        check(bc.player.curHp == hp && bc.player.getStatus<PS::BUFFER>() == 0,
              "Buffer should absorb one hit and be consumed");
    }
    {
        // Echo Form applies its power; the doubling itself runs through the
        // engine's existing cardQueue copy path (BattleContext::queuePurgeCard),
        // which is exercised by live play rather than this per-card harness.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::ECHO_FORM);
        check(bc.player.getStatus<PS::ECHO_FORM>() == 1, "Echo Form should apply its power");
    }
    {
        // Rebound: damage plus the REBOUND power that tops the next card.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::REBOUND);
        check(hp - bc.monsters.arr[0].curHp == 9, "Rebound should deal 9");
        check(bc.player.hasStatus<PS::REBOUND>(), "Rebound should apply its power");
    }
    {
        // Rupture: gains Strength whenever a card costs HP.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::RUPTURE);
        check(bc.player.getStatus<PS::RUPTURE>() == 1, "Rupture should apply its power");
    }
    {
        // White Noise: adds a random Power card that is free this turn.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::WHITE_NOISE);
        check(bc.cards.cardsInHand == 1, "White Noise should add one card");
        check(bc.cards.hand[0].getType() == CardType::POWER, "White Noise should add a Power card");
        check(bc.cards.hand[0].costForTurn == 0, "White Noise should make the card free this turn");
    }
    {
        // Creative AI: at turn start, add one random Power card.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::CREATIVE_AI);
        check(bc.player.hasStatus<PS::CREATIVE_AI>(), "Creative AI should apply its power");
        bc.player.applyStartOfTurnPowers(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(bc.cards.cardsInHand == 1 && bc.cards.hand[0].getType() == CardType::POWER,
              "Creative AI should add a Power card at turn start");
    }
    {
        // Thunder Strike: one random hit per Lightning orb channeled this combat.
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::LIGHTNING).actFunc(bc); // Cracked Core + this = 2 total
        check(bc.player.lightningChanneledThisCombat == 2, "lightning channel counter should track channels");
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::THUNDER_STRIKE);
        check(hp - bc.monsters.arr[0].curHp == 14, "Thunder Strike should hit twice for 7 on a lone enemy");
    }
    {
        // Blizzard: hits all enemies for 2 per Frost orb channeled this combat.
        BattleContext bc = freshBattle();
        Actions::ChannelOrb(Orb::FROST).actFunc(bc);
        Actions::ChannelOrb(Orb::FROST).actFunc(bc);
        check(bc.player.frostChanneledThisCombat == 2, "frost channel counter should track channels");
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::BLIZZARD);
        check(hp - bc.monsters.arr[0].curHp == 4, "Blizzard should deal 2 per Frost channel");
    }

    {
        // Equilibrium: 13 block and the retain power.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::EQUILIBRIUM);
        check(bc.player.block == 13, "Equilibrium should grant 13 block");
        check(bc.player.hasStatus<PS::EQUILIBRIUM>(), "Equilibrium should apply its power");
    }
    {
        // Claw: damage, then every Claw card gains 2 for the rest of combat.
        BattleContext bc = freshBattle();
        const int hp = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::CLAW);
        check(hp - bc.monsters.arr[0].curHp == 3, "Claw should deal 3 the first time");
        check(bc.player.clawBonus == 2, "Claw should raise the shared Claw bonus by 2");
        const int hp2 = bc.monsters.arr[0].curHp;
        playCard(bc, CardId::CLAW);
        check(hp2 - bc.monsters.arr[0].curHp == 5, "the second Claw should deal 3+2");
    }
    {
        // Streamline: its own cost drops by 1 for the rest of combat.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::STREAMLINE);
        bool found = false;
        for (const auto &dc : bc.cards.discardPile) {
            if (dc.getId() == CardId::STREAMLINE) {
                check(dc.cost == 1, "Streamline should cost 1 after one play");
                found = true;
            }
        }
        check(found, "Streamline should be in the discard pile");
    }
    {
        // Steam Barrier: 6 block, and the instance remembers one play.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::STEAM_BARRIER);
        check(bc.player.block == 6, "Steam Barrier should grant 6 block");
        bool found = false;
        for (const auto &dc : bc.cards.discardPile) {
            if (dc.getId() == CardId::STEAM_BARRIER) {
                check(dc.specialData == 1, "Steam Barrier should count its play");
                found = true;
            }
        }
        check(found, "Steam Barrier should be in the discard pile");
    }
    {
        // Static Discharge: attack damage channels a Lightning orb, but a hit
        // that block absorbs completely does not (AbstractPlayer.damage runs
        // decrementBlock before the onAttacked loop; StaticDischargePower
        // returns when damageAmount <= 0).
        BattleContext bc = freshBattle();
        playCard(bc, CardId::STATIC_DISCHARGE);
        const int before = bc.player.lightningChanneledThisCombat;
        bc.player.block = 20;
        bc.player.attacked(bc, 0, 5);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(bc.player.lightningChanneledThisCombat == before,
              "a fully blocked attack should not channel from Static Discharge");

        bc.player.block = 0;
        bc.player.attacked(bc, 0, 5);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(bc.player.lightningChanneledThisCombat == before + 1,
              "Static Discharge should channel one Lightning orb when hit");
    }
    {
        // All for One: 0-cost cards in the discard pile return to hand.
        BattleContext bc = freshBattle();
        bc.cards.discardPile.push_back(CardInstance(CardId::CLAW));
        bc.cards.discardPile.push_back(CardInstance(CardId::STEAM_BARRIER));
        bc.cards.discardPile.push_back(CardInstance(CardId::EQUILIBRIUM));
        playCard(bc, CardId::ALL_FOR_ONE);
        check(bc.cards.cardsInHand == 2, "All for One should return the two 0-cost cards");
        check(bc.cards.discardPile.size() == 2,
              "All for One should leave the 2-cost cards behind (Equilibrium and itself)");
    }

    {
        // Amplify: stores stacks for the next Power card to be doubled.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::AMPLIFY);
        check(bc.player.getStatus<PS::AMPLIFY>() == 1, "Amplify should apply one stack");
    }
    {
        // Force Field: 12 block, and each Power played lowers its cost by 1.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInHand(CardInstance(CardId::FORCE_FIELD));
        check(bc.cards.hand[0].cost == 4, "Force Field should start at 4 energy");
        playCard(bc, CardId::CAPACITOR);
        check(bc.cards.hand[0].cost == 3, "a Power play should lower Force Field cost by 1");
    }
    {
        // Hello World: at turn start add a random Common Skill, free this turn.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::HELLO_WORLD);
        check(bc.player.hasStatus<PS::HELLO_WORLD>(), "Hello World should apply its power");
        bc.player.applyStartOfTurnPowers(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(bc.cards.cardsInHand == 1, "Hello World should add one card");
        check(bc.cards.hand[0].getType() == CardType::SKILL, "Hello World should add a Skill");
        check(bc.cards.hand[0].costForTurn == 0, "Hello World should make the card free");
    }

    {
        // Bullseye: Lock-On raises orb damage on that target by 50% (8 -> 12).
        BattleContext bc = freshBattle();
        playCard(bc, CardId::BULLSEYE);
        check(bc.monsters.arr[0].hasStatus<MS::LOCK_ON>(), "Bullseye should apply Lock-On");
        const int hp = bc.monsters.arr[0].curHp;
        Actions::EvokeOrbAction(0).actFunc(bc);
        for (int i = 0; i < 8 && !bc.actionQueue.isEmpty(); ++i) {
            bc.actionQueue.popFront()(bc);
        }
        check(hp - bc.monsters.arr[0].curHp == 12,
              "a locked lightning evoke should deal 8 * 1.5 = 12");
    }
    {
        // Reboot: hand and discard pile shuffle back, then draw 4.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInHand(CardInstance(CardId::STRIKE_BLUE));
        bc.cards.createTempCardInHand(CardInstance(CardId::STRIKE_BLUE));
        for (int i = 0; i < 3; ++i) {
            bc.cards.discardPile.push_back(CardInstance(CardId::STRIKE_BLUE));
        }
        playCard(bc, CardId::REBOOT);
        check(bc.cards.cardsInHand == 4, "Reboot should draw 4 cards from the reshuffled pile");
    }

    {
        // Self Repair: stores the combat-end heal amount.
        BattleContext bc = freshBattle();
        playCard(bc, CardId::SELF_REPAIR);
        check(bc.player.getStatus<PS::SELF_REPAIR>() == 7,
              "Self Repair should store a 7 HP combat-end heal");
    }

    {
        // Genetic Algorithm: grants its accumulated block, then grows by 2.
        BattleContext bc = freshBattle();
        check(CardInstance(CardId::GENETIC_ALGORITHM).specialData == 1,
              "Genetic Algorithm should start with 1 block");
        playCard(bc, CardId::GENETIC_ALGORITHM);
        check(bc.player.block == 1, "the first Genetic Algorithm should grant 1 block");
        bool grew = false;
        for (const auto &dc : bc.cards.exhaustPile) { // the card exhausts
            if (dc.getId() == CardId::GENETIC_ALGORITHM) {
                check(dc.specialData == 3, "Genetic Algorithm should grow by 2 after a play");
                grew = true;
            }
        }
        check(grew, "Genetic Algorithm should be in the exhaust pile");
    }

    {
        // Scrape: draws 4 and discards every drawn card costing 2 or more.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInDrawPile(0, CardInstance(CardId::EQUILIBRIUM)); // costs 2
        bc.cards.createTempCardInDrawPile(1, CardInstance(CardId::STRIKE_BLUE)); // costs 1
        playCard(bc, CardId::SCRAPE);
        check(bc.cards.cardsInHand == 1, "Scrape should keep only the 1-cost card");
        check(bc.cards.hand[0].getId() == CardId::STRIKE_BLUE, "the kept card should be the 1-cost one");
    }
    {
        // Hologram: 3 block and a discard-pile pick; the base card exhausts.
        BattleContext bc = freshBattle();
        bc.cards.discardPile.push_back(CardInstance(CardId::STRIKE_BLUE));
        bc.cards.discardPile.push_back(CardInstance(CardId::STRIKE_BLUE));
        playCard(bc, CardId::HOLOGRAM);
        check(bc.player.block == 3, "Hologram should grant 3 block");
        check(bc.inputState == InputState::CARD_SELECT, "Hologram should open the discard select screen");
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::HOLOGRAM,
              "Hologram should use the HOLOGRAM select task");
    }
    {
        // Recycle: with a single card in hand it auto-exhausts and gains its cost.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInHand(CardInstance(CardId::EQUILIBRIUM)); // costs 2
        playCard(bc, CardId::RECYCLE);
        check(bc.player.energy == 4, "Recycle should gain the exhausted card's 2 energy");
        check(bc.cards.cardsInHand == 0, "Recycle should leave the hand empty");
    }

    {
        // Seek: opens the draw-pile select screen for one card.
        BattleContext bc = freshBattle();
        bc.cards.createTempCardInDrawPile(0, CardInstance(CardId::STRIKE_BLUE));
        bc.cards.createTempCardInDrawPile(1, CardInstance(CardId::STRIKE_BLUE));
        playCard(bc, CardId::SEEK);
        check(bc.inputState == InputState::CARD_SELECT, "Seek should open the draw pile select screen");
        check(bc.cardSelectInfo.cardSelectTask == CardSelectTask::SEEK,
              "Seek should use the SEEK select task");
    }

    {
        // Echo Form N doubles exactly the first N cards played each turn. The
        // engine counts the queued duplicate in cardsPlayedThisTurn, so this
        // catches an off-by-one in the cardsPlayedThisTurn - doubled comparison.
        for (int amount = 1; amount <= 2; ++amount) {
            BattleContext bc = freshBattle();
            bc.player.buff<PS::ECHO_FORM>(amount);
            bc.player.energy = 20;
            const int hp = bc.monsters.arr[0].curHp;
            playCard(bc, CardId::STRIKE_BLUE);
            playCard(bc, CardId::STRIKE_BLUE);
            playCard(bc, CardId::STRIKE_BLUE);
            check(hp - bc.monsters.arr[0].curHp == 18 + 6 * amount,
                  "Echo Form should double exactly the first amount cards");
        }
    }

    std::cout << "Defect card behaviors hold\n";
    return 0;
    } catch (const std::exception &e) {
        std::cout << "FAILED: " << e.what() << std::endl;
        return 1;
    }
}
