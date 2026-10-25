#include "combat/CharacterCards.h"
#include <iostream>
#include "combat/BattleContext.h"

namespace sts {

    namespace {
        // Recursion.java (RedoAction): evoke the next (leftmost) orb, then channel
        // the very orb object just evoked. Channeling the same object preserves
        // its per-orb state - notably a Dark orb keeps its accumulated counter.
        // The type must be read when the action resolves, not when the card is
        // queued: earlier queued actions can change the bar.
        Action evokeNextAndRechannel() {
            return {[] (BattleContext &bc) {
                if (bc.player.orbs.empty()) {
                    return;
                }
                const auto evoked = bc.player.orbs[0];
                Actions::EvokeOrbAction(0).actFunc(bc);
                if (evoked.type != Orb::EMPTY) {
                    bc.player.channelOrb(evoked.type, evoked.evokeBonus, evoked.darkCounter);
                }
            }};
        }
    }

    // Defect cards. To implement one, add its case here, queue its actions exactly as the original
    // game does, and end the case with "return true;". tools/coverage_manifest.py then counts it
    // as implemented. Do not approximate: leave a card out until it can match the original.
    bool useDefectCard(BattleContext &bc) {
        const auto &c = bc.curCardQueueItem.card;
        const auto t = bc.curCardQueueItem.target;
        const bool up = c.isUpgraded();
        switch (c.getId()) {
            case CardId::ZAP:
                // Zap.java: channel one Lightning orb. The upgrade only calls
                // upgradeBaseCost(0); the orb keeps its plain 8 evoke.
                bc.addToBot(Actions::ChannelOrb(Orb::LIGHTNING));
                return true;

            case CardId::DUALCAST:
                // Dualcast.java: evoke the next (leftmost) orb twice.
                bc.addToBot(Actions::EvokeOrbAction(0, 2));
                return true;

            case CardId::COLD_SNAP:
                // ColdSnap.java: attack, then channel one Frost orb.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 9 : 6)));
                bc.addToBot(Actions::ChannelOrb(Orb::FROST));
                return true;

            case CardId::BEAM_CELL:
                // BeamCell.java: attack, then apply Vulnerable.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 4 : 3)));
                bc.addToBot(Actions::DebuffEnemy<MS::VULNERABLE>(t, up ? 2 : 1, false));
                return true;

            case CardId::COMPILE_DRIVER: {
                // CompileDriver.java: attack, then draw one card per distinct orb
                // type. The game computes the count in applyPowers at play time.
                bool seen[5] = {};
                int distinct = 0;
                for (const auto &orb : bc.player.orbs) {
                    if (orb.type != Orb::EMPTY && !seen[static_cast<int>(orb.type)]) {
                        seen[static_cast<int>(orb.type)] = true;
                        ++distinct;
                    }
                }
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 10 : 7)));
                bc.addToBot(Actions::DrawCards(distinct));
                return true;
            }

            case CardId::BARRAGE:
                // Barrage.java: attack once per orb in the bar.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(
                    c, t, (up ? 6 : 4) * static_cast<int>(bc.player.orbs.size()))));
                return true;

            case CardId::SWEEPING_BEAM:
                // SweepingBeam.java: attack all enemies, then draw one card.
                bc.addToBot(Actions::AttackAllEnemy(bc.calculateCardDamageMatrix(c, up ? 9 : 6)));
                bc.addToBot(Actions::DrawCards(1));
                return true;

            case CardId::COOLHEADED:
                // Coolheaded.java: channel one Frost orb, then draw.
                bc.addToBot(Actions::ChannelOrb(Orb::FROST));
                bc.addToBot(Actions::DrawCards(up ? 2 : 1));
                return true;

            case CardId::LEAP:
                // Leap.java: gain block.
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 12 : 9)));
                return true;

            case CardId::STACK:
                // Stack.java: block equal to the discard pile size; upgraded adds 3.
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(
                    static_cast<int>(bc.cards.discardPile.size()) + (up ? 3 : 0))));
                return true;

            case CardId::TURBO:
                // Turbo.java: gain energy, then add a Void to the discard pile.
                bc.addToBot(Actions::GainEnergy(up ? 3 : 2));
                bc.addToBot(Actions::MakeTempCardInDiscard(CardInstance(CardId::VOID)));
                return true;

            case CardId::RECURSION:
                // Recursion.java: evoke the next (leftmost) orb, then rechannel it.
                bc.addToBot(evokeNextAndRechannel());
                return true;

            case CardId::GLACIER:
                // Glacier.java: gain block, then channel two Frost orbs.
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 10 : 7)));
                bc.addToBot(Actions::ChannelOrb(Orb::FROST));
                bc.addToBot(Actions::ChannelOrb(Orb::FROST));
                return true;

            case CardId::CHILL: {
                // Chill.java: channel one Frost orb per alive enemy.
                const int alive = bc.monsters.getAliveCount();
                for (int i = 0; i < alive; ++i) {
                    bc.addToBot(Actions::ChannelOrb(Orb::FROST));
                }
                return true;
            }

            case CardId::DEFRAGMENT:
                // Defragment.java: gain Focus.
                bc.addToBot(Actions::BuffPlayer<PS::FOCUS>(up ? 2 : 1));
                return true;

            case CardId::CONSUME:
                // Consume.java: gain Focus, then lose one orb slot. Slot loss
                // drops the newest orb without evoking it.
                bc.addToBot(Actions::BuffPlayer<PS::FOCUS>(up ? 3 : 2));
                bc.addToBot(Actions::IncreaseOrbSlots(-1));
                return true;

            case CardId::REPROGRAM:
                // Reprogram.java: lose Focus, gain Strength and Dexterity.
                bc.addToBot(Actions::DecrementStatus<PS::FOCUS>(up ? 2 : 1));
                bc.addToBot(Actions::BuffPlayer<PS::STRENGTH>(up ? 2 : 1));
                bc.addToBot(Actions::BuffPlayer<PS::DEXTERITY>(up ? 2 : 1));
                return true;

            case CardId::HYPERBEAM:
                // Hyperbeam.java: attack all enemies, then lose 3 Focus.
                bc.addToBot(Actions::AttackAllEnemy(bc.calculateCardDamageMatrix(c, up ? 34 : 26)));
                bc.addToBot(Actions::DecrementStatus<PS::FOCUS>(3));
                return true;

            case CardId::BIASED_COGNITION:
                // BiasedCognition.java: gain Focus, then apply BIAS. The turn-start
                // BIAS hook in Player::applyStartOfTurnPowers drains Focus per turn.
                bc.addToBot(Actions::BuffPlayer<PS::FOCUS>(up ? 5 : 4));
                bc.addToBot(Actions::BuffPlayer<PS::BIAS>(1));
                return true;

            case CardId::DARKNESS:
                // Darkness.java: channel one Dark orb; upgraded also grows every
                // Dark orb's counter right now (DarkImpulseAction).
                bc.addToBot(Actions::ChannelOrb(Orb::DARK));
                if (up) {
                    bc.addToBot(Actions::DarkImpulseAction());
                }
                return true;

            case CardId::DOOM_AND_GLOOM:
                // DoomAndGloom.java: attack all enemies, then channel one Dark orb.
                bc.addToBot(Actions::AttackAllEnemy(bc.calculateCardDamageMatrix(c, up ? 14 : 10)));
                bc.addToBot(Actions::ChannelOrb(Orb::DARK));
                return true;

            case CardId::RAINBOW:
                // Rainbow.java: channel Lightning, Frost and Dark. Exhaust is card
                // metadata (base only; upgraded does not exhaust).
                bc.addToBot(Actions::ChannelOrb(Orb::LIGHTNING));
                bc.addToBot(Actions::ChannelOrb(Orb::FROST));
                bc.addToBot(Actions::ChannelOrb(Orb::DARK));
                return true;

            case CardId::FUSION:
                // Fusion.java: channel one Plasma orb.
                bc.addToBot(Actions::ChannelOrb(Orb::FUSION));
                return true;

            case CardId::BALL_LIGHTNING:
                // BallLightning.java: attack, then channel magicNumber (1) Lightning orbs.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 10 : 7)));
                for (int i = 0; i < 1; ++i) {
                    bc.addToBot(Actions::ChannelOrb(Orb::LIGHTNING));
                }
                return true;

            case CardId::CHAOS: {
                // Chaos.java: channel one random orb, two when upgraded. The vanilla
                // pool excludes Plasma: getRandomOrb(true) rolls cardRandomRng over
                // [Dark, Frost, Lightning]. Both rolls happen at play time.
                const int rolls = up ? 2 : 1;
                for (int i = 0; i < rolls; ++i) {
                    const int roll = bc.cardRandomRng.random(3);
                    const Orb orb = roll == 0 ? Orb::DARK : roll == 1 ? Orb::FROST : Orb::LIGHTNING;
                    bc.addToBot(Actions::ChannelOrb(orb));
                }
                return true;
            }

            case CardId::TEMPEST: {
                // Tempest.java: channel X (+1 upgraded) Lightning orbs, then the
                // card itself empties the energy pool (an X card pays nothing at
                // the cost check). Chemical X adds 2 to X.
                auto &item = bc.curCardQueueItem;
                if (!item.ignoreEnergyTotal && bc.player.energy < item.energyOnUse) {
                    item.energyOnUse = bc.player.energy;
                }
                const int count = item.energyOnUse + (up ? 1 : 0)
                    + (bc.player.hasRelic<R::CHEMICAL_X>() ? 2 : 0);
                for (int i = 0; i < count; ++i) {
                    bc.addToBot(Actions::ChannelOrb(Orb::LIGHTNING));
                }
                bc.addToBot(Actions::EmptyEnergy(bc.curCardQueueItem.freeToPlay || c.freeToPlayOnce));
                return true;
            }

            case CardId::CAPACITOR:
                // Capacitor.java: gain 2 (3 upgraded) orb slots.
                bc.addToBot(Actions::IncreaseOrbSlots(up ? 3 : 2));
                return true;

            case CardId::ELECTRODYNAMICS:
                // Electro.java: lightning orbs hit all enemies, then channel 2 (3).
                bc.addToBot(Actions::BuffPlayer<PS::ELECTRO>(1));
                for (int i = 0; i < (up ? 3 : 2); ++i) {
                    bc.addToBot(Actions::ChannelOrb(Orb::LIGHTNING));
                }
                return true;

            case CardId::LOOP:
                // Loop.java applies magicNumber stacks, and upgradeMagicNumber(1)
                // makes Loop+ apply two; Player::applyStartOfTurnPowers triggers the
                // oldest orb's passive once per stack at turn start.
                bc.addToBot(Actions::BuffPlayer<PS::LOOP>(up ? 2 : 1));
                return true;

            case CardId::STORM:
                // Storm.java: whenever a Power is played, channel one Lightning orb.
                bc.addToBot(Actions::BuffPlayer<PS::STORM>(1));
                return true;

            case CardId::HEATSINKS:
                // Heatsinks.java: whenever a Power is played, draw 1 (2 upgraded).
                bc.addToBot(Actions::BuffPlayer<PS::HEATSINKS>(up ? 2 : 1));
                return true;

            case CardId::MACHINE_LEARNING:
                // MachineLearning.java applies the game's DrawPower (+1 hand size per
                // turn). The engine tracks this through cardDrawPerTurn, which persists
                // across turns (Snecko and Draw Reduction use the same field).
                bc.player.cardDrawPerTurn += 1;
                return true;

            case CardId::GO_FOR_THE_EYES:
                // GoForTheEyes.java: damage first, then ForTheEyesAction adds Weak only
                // when the target intends to attack (getIntentBaseDmg() >= 0, which the
                // engine exposes as Monster::isAttacking()). That action constructs
                // `new WeakPower(m, amount, false)`, so the game never marks this Weak
                // as justApplied and it does decrement at the end of the round it was
                // applied in. isSourceMonster=true (the DebuffEnemy default) would skip
                // that first decrement and leave the Weak one round too long.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 4 : 3)));
                bc.addToBot({[=] (BattleContext &bc) {
                    if (t >= 0 && t < bc.monsters.monsterCount
                        && !bc.monsters.arr[t].isDeadOrEscaped()
                        && bc.monsters.arr[t].isAttacking()) {
                        bc.addToBot(Actions::DebuffEnemy<MS::WEAK>(t, up ? 2 : 1, false));
                    }
                }});
                return true;

            case CardId::AUTO_SHIELDS:
                // AutoShields.java: gains block only when the player currently has none.
                bc.addToBot({[=] (BattleContext &bc) {
                    if (bc.player.block == 0) {
                        bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 15 : 11)));
                    }
                }});
                return true;

            case CardId::BOOT_SEQUENCE:
                // BootSequence.java: 10 (13) block; innate and exhaust come from the
                // shared card metadata (isCardInnate / doesCardExhaust).
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 13 : 10)));
                return true;

            case CardId::CHARGE_BATTERY:
                // ConserveBattery.java: block, then EnergizedBluePower(1) for next turn.
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 10 : 7)));
                bc.addToBot(Actions::BuffPlayer<PS::ENERGIZED>(1));
                return true;

            case CardId::DOUBLE_ENERGY:
                // DoubleEnergy.java: DoubleEnergyAction doubles the current energy.
                bc.addToBot(Actions::DoubleEnergy());
                return true;

            case CardId::MULTI_CAST: {
                // MultiCast.java -> MulticastAction: evoke the oldest orb X times,
                // consuming it once. X = energyOnUse + Chemical X (2) + upgraded (1).
                // The evokes resolve first, then the card empties the energy pool.
                auto &item = bc.curCardQueueItem;
                if (!item.ignoreEnergyTotal && bc.player.energy < item.energyOnUse) {
                    item.energyOnUse = bc.player.energy;
                }
                const int times = item.energyOnUse + (up ? 1 : 0)
                    + (bc.player.hasRelic<R::CHEMICAL_X>() ? 2 : 0);
                bc.addToBot(Actions::MultiCastAction(times));
                bc.addToBot(Actions::EmptyEnergy(bc.curCardQueueItem.freeToPlay || c.freeToPlayOnce));
                return true;
            }

            case CardId::REINFORCED_BODY: {
                // ReinforcedBody.java -> ReinforcedBodyAction: gain block X times,
                // X = energyOnUse + Chemical X (2). Upgraded raises block 7 -> 9.
                // The card empties the energy pool when it is used.
                auto &item = bc.curCardQueueItem;
                if (!item.ignoreEnergyTotal && bc.player.energy < item.energyOnUse) {
                    item.energyOnUse = bc.player.energy;
                }
                const int times = item.energyOnUse
                    + (bc.player.hasRelic<R::CHEMICAL_X>() ? 2 : 0);
                const int block = bc.calculateCardBlock(up ? 9 : 7);
                for (int i = 0; i < times; ++i) {
                    bc.addToBot(Actions::GainBlock(block));
                }
                bc.addToBot(Actions::EmptyEnergy(bc.curCardQueueItem.freeToPlay || c.freeToPlayOnce));
                return true;
            }

            case CardId::METEOR_STRIKE:
                // MeteorStrike.java: 24 (30) damage, then channel three Plasma orbs.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 30 : 24)));
                for (int i = 0; i < 3; ++i) {
                    bc.addToBot(Actions::ChannelOrb(Orb::FUSION));
                }
                return true;

            case CardId::CORE_SURGE:
                // CoreSurge.java: damage then one Artifact; exhaust is card metadata.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 15 : 11)));
                bc.addToBot(Actions::BuffPlayer<PS::ARTIFACT>(1));
                return true;

            case CardId::MELTER:
                // Melter.java: RemoveAllBlockAction resolves before the damage.
                bc.addToBot({[=] (BattleContext &bc) {
                    if (t >= 0 && t < bc.monsters.monsterCount) {
                        bc.monsters.arr[t].block = 0;
                    }
                }});
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 14 : 10)));
                return true;

            case CardId::SUNDER:
                // Sunder.java: damage; if this card kills the enemy, gain 3 energy.
                bc.addToBot(Actions::SunderAction(t, bc.calculateCardDamage(c, t, up ? 32 : 24)));
                return true;

            case CardId::AGGREGATE:
                // AggregateEnergyAction: gainEnergy(drawPile.size() / divideAmount),
                // where divideAmount is 4 (3 upgraded).
                bc.addToBot({[=] (BattleContext &bc) {
                    const int per = up ? 3 : 4;
                    bc.player.gainEnergy(static_cast<int>(bc.cards.drawPile.size()) / per);
                }});
                return true;

            case CardId::SKIM:
                // Skim.java: draw 3 (4).
                bc.addToBot(Actions::DrawCards(up ? 4 : 3));
                return true;

            case CardId::OVERCLOCK:
                // Overclock.java: draw 2 (3), then a Burn enters the discard pile.
                bc.addToBot(Actions::DrawCards(up ? 3 : 2));
                bc.addToBot(Actions::MakeTempCardInDiscard(CardInstance(CardId::BURN), 1));
                return true;

            case CardId::RIP_AND_TEAR:
                // RipAndTear.java: two hits, each picking its own random target.
                bc.addToBot(Actions::RipAndTearAction(bc.calculateCardDamage(c, t, up ? 9 : 7), 2));
                return true;

            case CardId::FISSION:
                // FissionAction: remove (evoke when upgraded) every orb, then gain
                // one energy and draw one card per orb removed.
                bc.addToBot(Actions::FissionAction(up));
                return true;

            case CardId::FTL:
                // FTL.java: damage, then draw 1 when fewer than magicNumber cards
                // were played this turn. FTLAction.update() reads
                // cardsPlayedThisTurn.size() *after* this card was appended, so the
                // game compares size()-1 < cardPlayCount, and cardPlayCount is the
                // magicNumber the constructor sets: 3 base, 4 upgraded. The engine
                // also counts this card before dispatch, so the equivalent is
                // cardsPlayedThisTurn - 1 < (up ? 4 : 3).
                //
                // FTLAction queues its DrawCardAction with addToTop, so the draw
                // resolves before UseCardAction has moved FTL itself into the
                // discard pile. That matters when the draw pile is empty: the
                // reshuffle then cannot include FTL, which is where it would be if
                // the draw waited for the queue to drain.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 6 : 5)));
                bc.addToBot({[=] (BattleContext &bc) {
                    if (bc.player.cardsPlayedThisTurn - 1 < (up ? 4 : 3)) {
                        bc.addToTop(Actions::DrawCards(1));
                    }
                }});
                return true;

            case CardId::BUFFER:
                // Buffer.java: prevent the next 1 (2) HP loss; Player::damage
                // consumes a stack.
                bc.addToBot(Actions::BuffPlayer<PS::BUFFER>(up ? 2 : 1));
                return true;

            case CardId::ECHO_FORM:
                // EchoForm.java: the first card each turn is played twice. The
                // copy itself lives in BattleContext::onAfterCardUsed; Player's
                // start-of-turn hook resets echoFormCardsDoubled. Ethereal is
                // card metadata.
                bc.addToBot(Actions::BuffPlayer<PS::ECHO_FORM>(1));
                return true;

            case CardId::REBOUND:
                // Rebound.java: damage, then the next card played goes on top of
                // the draw pile (handled in BattleContext's card-purge path).
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 12 : 9)));
                // ReboundPower ships with justEvoked = true, so the Rebound card
                // itself is not topped. The engine consumes one stack per played
                // card, so seeding two stacks encodes that skip plus the real one.
                bc.addToBot(Actions::BuffPlayer<PS::REBOUND>(2));
                return true;

            case CardId::RUPTURE:
                // Rupture.java: gain Strength whenever a card costs HP.
                bc.addToBot(Actions::BuffPlayer<PS::RUPTURE>(up ? 2 : 1));
                return true;

            case CardId::WHITE_NOISE:
                // WhiteNoise.java: add a random Power card, free to play this turn
                // (it is the one generator that calls setCostForTurn(0)).
                bc.addToBot(Actions::CreateRandomCardInHandAction(CardType::POWER, false, true));
                return true;

            case CardId::CREATIVE_AI:
                // CreativeAi.java: at the start of each turn add a random Power
                // card; the Player start-of-turn hook does the work.
                bc.addToBot(Actions::BuffPlayer<PS::CREATIVE_AI>(1));
                return true;

            case CardId::THUNDER_STRIKE: {
                // ThunderStrike.java: one random-target hit per Lightning orb
                // channeled this combat, 7 (9) damage each.
                const int hits = bc.player.lightningChanneledThisCombat;
                for (int i = 0; i < hits; ++i) {
                    bc.addToBot({[=] (BattleContext &bc) {
                        const int target = bc.monsters.getRandomMonsterIdx(bc.cardRandomRng);
                        if (target >= 0) {
                            bc.addToBot(Actions::AttackEnemy(target, up ? 9 : 7));
                        }
                    }});
                }
                return true;
            }

            case CardId::BLIZZARD: {
                // Blizzard.java: hit all enemies for 2 (3) per Frost orb channeled
                // this combat.
                const int count = bc.player.frostChanneledThisCombat;
                if (count > 0) {
                    bc.addToBot(Actions::AttackAllEnemy(
                        bc.calculateCardDamageMatrix(c, (up ? 3 : 2) * count)));
                }
                return true;
            }

            case CardId::EQUILIBRIUM:
                // Equilibrium.java: block, then retain this turn's hand. The
                // end-of-turn discard path skips while PS::EQUILIBRIUM is set and
                // consumes one stack (Player::endTurn).
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 16 : 13)));
                bc.addToBot(Actions::BuffPlayer<PS::EQUILIBRIUM>(1));
                return true;

            case CardId::CLAW:
                // Claw.java: damage, then every Claw card gains 2 damage for the
                // rest of combat. calculateCardDamage reads the shared bonus before
                // this action resolves, so the current hit is not boosted.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 5 : 3)));
                bc.addToBot({[] (BattleContext &bc) { bc.player.clawBonus += 2; }});
                return true;

            case CardId::STREAMLINE:
                // Streamline.java: damage; onAfterUseCard lowers its own cost by 1
                // for the rest of combat.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 20 : 15)));
                return true;

            case CardId::STEAM_BARRIER: {
                // SteamBarrier.java: block drops by 1 for each earlier play this
                // combat; specialData carries that count on the card instance and
                // onAfterUseCard raises it after each play.
                const int block = std::max(0, (up ? 8 : 6) - static_cast<int>(c.specialData));
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(block)));
                return true;
            }

            case CardId::STATIC_DISCHARGE:
                // StaticDischargePower: channel 1 (2) Lightning whenever attack
                // damage lands. The hook lives in Player::attacked.
                bc.addToBot(Actions::BuffPlayer<PS::STATIC_DISCHARGE>(up ? 2 : 1));
                return true;

            case CardId::ALL_FOR_ONE:
                // AllForOne.java: damage, then every 0-cost card in the discard
                // pile returns to hand.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 14 : 10)));
                bc.addToBot(Actions::AllForOneAction());
                return true;

            case CardId::AMPLIFY:
                // Amplify.java: the next 1 (2) Power cards this turn are played
                // twice. The copy happens in onAfterUseCard next to Duplication.
                bc.addToBot(Actions::BuffPlayer<PS::AMPLIFY>(up ? 2 : 1));
                return true;

            case CardId::FORCE_FIELD:
                // ForceField.java: 12 (16) block; its cost drops 1 per Power played
                // this combat (applied in onAfterUseCard when a Power resolves).
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 16 : 12)));
                return true;

            case CardId::HELLO_WORLD:
                // HelloWorld.java: at the start of each turn add a random Common
                // Skill, free to play. The Player start-of-turn hook does the work.
                bc.addToBot(Actions::BuffPlayer<PS::HELLO_WORLD>(1));
                return true;

            case CardId::BULLSEYE:
                // LockOn.java: 8 damage (11 upgraded), then 2 Lock-On (3 upgraded).
                // LockOnPower makes orb damage against that target hit 50% harder.
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 11 : 8)));
                bc.addToBot(Actions::DebuffEnemy<MS::LOCK_ON>(t, up ? 3 : 2));
                return true;

            case CardId::REBOOT: {
                // Reboot.java: addToBot(new ShuffleAllAction()); addToBot(new DrawCardAction(p, 4|6));
                //
                // ShuffleAllAction.update() on its first tick does exactly two things:
                //   1. addToTop(new PutOnDeckAction(p, p, 99, true))
                //   2. player.discardPile.shuffle(AbstractDungeon.shuffleRng)
                // and it never moves the discard pile into the draw pile: the discard
                // cards only come back when the draw pile runs out during the draw
                // below (DrawCardAction -> EmptyDeckShuffleAction).
                //
                // PutOnDeckAction with isRandom=true runs, hand.size() times:
                //   card = hand.getRandomCard(AbstractDungeon.cardRandomRng)  // random(hand.size()-1)
                //   hand.moveToDeck(card, isRandom=true)
                // so the hand leaves in a *random* order, not in hand order, and each
                // pick consumes one cardRandomRng draw. moveToDeck ends in
                // Soul.onToDeck(card, isRandom, isRandomSpot=false) ->
                // drawPile.addToRandomSpot(card) -> group.add(random(size-1), card),
                // i.e. a second cardRandomRng draw places the card at a random spot in
                // the draw pile (or appends it when the pile is empty). Java's
                // Random.random(n) is inclusive of n, hence the -1 bounds below.
                //
                // Reboot itself is still in hand when ShuffleAllAction is constructed
                // (AbstractPlayer.useCard calls card.use() before hand.removeCard), so it
                // is part of that transfer like any other hand card. This engine removes
                // the played card from hand before the queued actions run, so we put it
                // back into the hand array first.
                const int draw = up ? 6 : 4;
                const CardInstance played = c;
                bc.addToBot({[played] (BattleContext &bc) {
                    if (bc.cards.cardsInHand < CardManager::MAX_HAND_SIZE) {
                        bc.cards.hand[bc.cards.cardsInHand++] = played;
                    }
                    while (bc.cards.cardsInHand > 0) {
                        // PutOnDeckAction: hand.getRandomCard(cardRandomRng)
                        const int pick = bc.cardRandomRng.random(bc.cards.cardsInHand - 1);
                        const CardInstance card = bc.cards.hand[pick];
                        for (int i = pick; i + 1 < bc.cards.cardsInHand; ++i) {
                            bc.cards.hand[i] = bc.cards.hand[i + 1];
                        }
                        --bc.cards.cardsInHand;
                        // Soul.onToDeck -> drawPile.addToRandomSpot(card)
                        if (bc.cards.drawPile.empty()) {
                            bc.cards.moveToDrawPileTop(card);
                        } else {
                            const int spot = bc.cardRandomRng.random(
                                    static_cast<int>(bc.cards.drawPile.size()) - 1);
                            bc.cards.drawPile.insert(bc.cards.drawPile.begin() + spot, card);
                        }
                    }
                    // player.discardPile.shuffle(AbstractDungeon.shuffleRng)
                    java::Collections::shuffle(
                            bc.cards.discardPile.begin(),
                            bc.cards.discardPile.end(),
                            java::Random(bc.shuffleRng.randomLong()));
                }});
                bc.addToBot(Actions::DrawCards(draw));
                return true;
            }

            case CardId::SELF_REPAIR:
                // SelfRepairPower: heal 7 (10) when the combat is won. The heal
                // itself runs in BattleContext::updateRelicsOnExit.
                bc.addToBot(Actions::BuffPlayer<PS::SELF_REPAIR>(up ? 10 : 7));
                return true;

            case CardId::GENETIC_ALGORITHM:
                // GeneticAlgorithm.java: baseBlock equals misc, so the card grants
                // its accumulated block and then grows by 2 (3). The growth runs in
                // onAfterUseCard and cardOnExit writes it back to the deck.
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(c.specialData)));
                return true;

            case CardId::SCRAPE: {
                // Scrape.java: damage, then DrawCardAction(4|5) with a
                // ScrapeFollowUpAction behind it. The follow-up walks
                // DrawCardAction.drawnCards in draw order once the draw is over
                // and discards every card whose turn cost is not 0, so the drawn
                // cards sit in hand while the rest of the draw happens and only
                // then leave, in the order they were drawn.
                const int drawCount = up ? 5 : 4;
                // The hand can change between queueing and resolving (the card
                // itself leaves, earlier actions draw), so the mark is taken by
                // an action that runs immediately before the draw.
                auto handMark = std::make_shared<int>(0);
                bc.addToBot(Actions::AttackEnemy(t, bc.calculateCardDamage(c, t, up ? 10 : 7)));
                bc.addToBot({[handMark] (BattleContext &bc) {
                    *handMark = bc.cards.cardsInHand;
                }});
                bc.addToBot(Actions::DrawCards(drawCount));
                bc.addToBot({[handMark] (BattleContext &bc) {
                    const int handBefore = *handMark;
                    std::vector<CardInstance> toDiscard;
                    for (int i = handBefore; i < bc.cards.cardsInHand; ++i) {
                        const auto &card = bc.cards.hand[i];
                        if (card.costForTurn == 0 || card.freeToPlayOnce) {
                            continue;
                        }
                        toDiscard.push_back(card);
                    }
                    for (int i = bc.cards.cardsInHand - 1; i >= handBefore; --i) {
                        const auto &card = bc.cards.hand[i];
                        if (card.costForTurn == 0 || card.freeToPlayOnce) {
                            continue;
                        }
                        bc.cards.removeFromHandAtIdx(i);
                    }
                    for (const auto &card : toDiscard) {
                        bc.cards.moveToDiscardPile(card);
                        ++bc.player.cardsDiscardedThisTurn;
                        bc.onManualDiscard(card);
                    }
                }});
                return true;
            }

            case CardId::HOLOGRAM:
                // Hologram.java: 3 (5) block, then return one card from the discard
                // pile to hand. Only the base version exhausts.
                bc.addToBot(Actions::GainBlock(bc.calculateCardBlock(up ? 5 : 3)));
                bc.addToBot({[=] (BattleContext &bc) {
                    if (bc.cards.discardPile.empty()) {
                        return;
                    }
                    if (bc.cards.discardPile.size() == 1) {
                        bc.chooseDiscardToHandCard(0, false);
                    } else {
                        bc.openSimpleCardSelectScreen(CardSelectTask::HOLOGRAM, 1);
                    }
                }});
                return true;

            case CardId::RECYCLE:
                // Recycle.java: exhaust a card from hand, gaining its cost in
                // energy. chooseRecycleCard does the work once a card is picked.
                bc.addToBot({[=] (BattleContext &bc) {
                    if (bc.cards.cardsInHand == 0) {
                        return;
                    }
                    if (bc.cards.cardsInHand == 1) {
                        bc.chooseRecycleCard(0);
                    } else {
                        bc.openSimpleCardSelectScreen(CardSelectTask::RECYCLE, 1);
                    }
                }});
                return true;

            case CardId::SEEK:
                // Seek.java: BetterDrawPileToHandAction(1), upgraded 2. That action
                // moves the whole pile straight to hand when the pile holds no more
                // cards than it asks for, and only opens the grid otherwise.
                bc.addToBot({[=] (BattleContext &bc) {
                    const int want = up ? 2 : 1;
                    if (bc.cards.drawPile.empty()) {
                        return;
                    }
                    if (static_cast<int>(bc.cards.drawPile.size()) <= want) {
                        const int count = static_cast<int>(bc.cards.drawPile.size());
                        std::vector<CardInstance> taken(bc.cards.drawPile.begin(),
                                                        bc.cards.drawPile.begin() + count);
                        for (int i = count - 1; i >= 0; --i) bc.cards.removeFromDrawPileAtIdx(i);
                        for (const auto &card : taken) bc.moveToHandHelper(card);
                        return;
                    }
                    bc.openSimpleCardSelectScreen(CardSelectTask::SEEK, want);
                }});
                return true;

            default:
                return false;
        }
    }

}
