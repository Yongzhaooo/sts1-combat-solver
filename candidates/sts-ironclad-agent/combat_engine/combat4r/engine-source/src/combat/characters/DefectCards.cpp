#include "combat/CharacterCards.h"
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
                // Zap.java: channel one Lightning orb; the upgraded card adds +3 to
                // the channeled orb's evoke value.
                bc.addToBot(Actions::ChannelOrb(Orb::LIGHTNING, up ? 3 : 0));
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
                // Tempest.java: channel X (+1 upgraded) Lightning orbs. X resolves like
                // other X-cost cards (Whirlwind pattern).
                auto &item = bc.curCardQueueItem;
                if (!item.ignoreEnergyTotal && bc.player.energy < item.energyOnUse) {
                    item.energyOnUse = bc.player.energy;
                }
                const int count = item.energyOnUse + (up ? 1 : 0);
                for (int i = 0; i < count; ++i) {
                    bc.addToBot(Actions::ChannelOrb(Orb::LIGHTNING));
                }
                return true;
            }

            default:
                return false;
        }
    }

}
