//
// Created by gamerpuppy on 7/4/2021.
//

#include "combat/Actions.h"
#include "combat/BattleContext.h"
#include "game/Game.h"

using namespace sts;

Action Actions::SetState(InputState state) {
    return {[=] (BattleContext &bc) {
        bc.setState(state);
    }};
}

Action Actions::AttackEnemy(int idx, int damage, DamageType type) {
    return {[=] (BattleContext &bc) {
        if (bc.monsters.arr[idx].isDeadOrEscaped()) {
            return;
        }

        bc.monsters.arr[idx].attacked(bc, damage, type);
        bc.checkCombat();
    }};
}

Action Actions::RipAndTearAction(int damage, int times) {
    return {[=] (BattleContext &bc) {
        // NewRipAndTearAction: each hit rolls its own random target when it resolves.
        for (int i = 0; i < times; ++i) {
            bc.addToBot({[=] (BattleContext &bc) {
                const int target = bc.monsters.getRandomMonsterIdx(bc.cardRandomRng);
                if (target >= 0) {
                    bc.addToBot(Actions::AttackEnemy(target, damage));
                }
            }});
        }
    }};
}

Action Actions::AllForOneAction() {
    return {[] (BattleContext &bc) {
        // AllForOneAction: every 0-cost card in the discard pile returns to hand as
        // the same instance (no new unique id). Walking backwards keeps indices valid.
        for (int i = static_cast<int>(bc.cards.discardPile.size()) - 1; i >= 0; --i) {
            const auto &dc = bc.cards.discardPile[i];
            if (dc.costForTurn != 0 && dc.cost != 0) {
                continue;
            }
            if (bc.cards.cardsInHand >= CardManager::MAX_HAND_SIZE) {
                break;
            }
            bc.cards.hand[bc.cards.cardsInHand++] = dc;
            bc.cards.discardPile.erase(bc.cards.discardPile.begin() + i);
        }
    }};
}

Action Actions::CreateRandomCardInHandAction(CardType type, bool commonOnly, bool zeroCost) {
    return {[=] (BattleContext &bc) {
        // Hello World and Creative AI add the card at its printed cost: neither
        // HelloPower.atStartOfTurn nor CreativeAIPower.atStartOfTurn touches the cost,
        // and MakeTempCardInHandAction never zeroes one. Only WhiteNoise.java calls
        // setCostForTurn(0) on what it adds, hence zeroCost. commonOnly restricts the
        // pool to Common cards, which is what separates Hello World from the others.
        const auto cid = commonOnly
                ? getRandomClassCardOfTypeAndRarity(
                        bc.cardRandomRng, bc.player.cc, type, CardRarity::COMMON)
                : getTrulyRandomCardInCombat(bc.cardRandomRng, bc.player.cc, type);
        CardInstance generated = bc.createGeneratedCard(cid);
        if (zeroCost) generated.setCostForTurn(0);
        bc.addToTop(Actions::MakeTempCardInHand(generated));
    }};
}

Action Actions::CreateRandomRarityCardInHandAction(CardRarity rarity, bool zeroCost) {
    return {[=] (BattleContext &bc) {
        // HelloPower.atStartOfTurn: AbstractDungeon.getCard(COMMON, cardRandomRng), which
        // rolls over the whole rarity pool - attacks included - so it is not the
        // type-filtered pool CreateRandomCardInHandAction draws from.
        const auto cid = getRandomClassCardOfRarity(bc.cardRandomRng, bc.player.cc, rarity);
        CardInstance generated = bc.createGeneratedCard(cid);
        if (zeroCost) generated.setCostForTurn(0);
        bc.addToTop(Actions::MakeTempCardInHand(generated));
    }};
}

Action Actions::SunderAction(int idx, int damage) {
    return {[=] (BattleContext &bc) {
        if (bc.monsters.arr[idx].isDeadOrEscaped()) {
            return;
        }
        const bool aliveBefore = bc.monsters.arr[idx].curHp > 0;
        bc.monsters.arr[idx].attacked(bc, damage);
        // The kill check must run inside the damage action. The game checks
        // target.isDying in AttackAction; a follow-up queued action may never
        // resolve once checkCombat changes the input state.
        if (aliveBefore && bc.monsters.arr[idx].curHp <= 0) {
            bc.player.gainEnergy(3);
        }
        bc.checkCombat();
    }};
}

Action Actions::AttackAllEnemy(DamageMatrix damageMatrix) {
    return {[=] (BattleContext &bc) {
        for (int i = 0; i < bc.monsters.monsterCount; ++i) {
            if (!bc.monsters.arr[i].isDeadOrEscaped()) {
                bc.monsters.arr[i].attacked(bc, damageMatrix[i]);
            }
        }

        bc.checkCombat();
    }};
}

Action Actions::DamageEnemy(int idx, int damage) {
    return {[=] (BattleContext &bc) {
        if (bc.monsters.arr[idx].isDeadOrEscaped()) {
            return;
        }

        bc.monsters.arr[idx].damage(bc, damage);
        bc.checkCombat();
    }};
}

Action Actions::DamageAllEnemy(int damage) { // todo this is probably broken
    return {[=] (BattleContext &bc) {
        for (int idx = 0; idx < bc.monsters.monsterCount; idx++) {
            if (!bc.monsters.arr[idx].isDeadOrEscaped()) {
                // ExplosivePotion (null owner, javap), The Bomb and Mercury Hourglass all
                // skip the onAttacked chain: Curl Up, Thorns, Angry and friends need an owner.
                bc.monsters.arr[idx].damage(bc, damage); // possible should addToBot here todo
            }
        }
        bc.checkCombat();
    }};
}

Action Actions::AttackPlayer(int idx, int damage) {
    return {[=] (BattleContext &bc) {
        // DamageAction cancels remaining hits when retaliation kills its owner.
        if (!bc.monsters.arr[idx].isAlive()) return;
        bc.player.attacked(bc, idx, damage);
    }, false};
}

Action Actions::DamagePlayer(int damage, bool selfDamage) {
    return {[=] (BattleContext &bc) {
        bc.player.damage(bc, damage, selfDamage);
    }, false};
}

Action Actions::VampireAttack(int damage) {
    return {[=] (BattleContext &bc) {
        const auto mIdx = 0;
        auto &m = bc.monsters.arr[mIdx]; // only used by shelled parasite so idx is 0
        bc.player.attacked(bc, mIdx, damage);
        if (m.isAlive()) {
            m.heal(std::min(damage, static_cast<int>(bc.player.lastAttackUnblockedDamage)));
        }
    }};
}

Action Actions::PlayerLoseHp(int hp, bool selfDamage) { // TODO this doesn't take into account intangible or relics
    return {[=] (BattleContext &bc) {
        bc.player.loseHp(bc, hp, selfDamage);
    }, false};
}

Action Actions::HealPlayer(int amount) {
    return {[=] (BattleContext &bc) {
        bc.player.heal(bc, amount);
    }, false};
}

Action Actions::MonsterGainBlock(int idx, int amount) {
    return {[=] (BattleContext &bc) {
        bc.monsters.arr[idx].block += amount;
    }};
}

Action Actions::SetMove(int monsterIdx, MonsterMoveId move) {
    return {[=](BattleContext &bc) { bc.monsters.arr[monsterIdx].moveHistory[0] = move; }};
}

Action Actions::RollMove(int monsterIdx) {
    return {[=] (BattleContext &bc) {
        Monster &m = bc.monsters.arr[monsterIdx];
        m.rollMove(bc);
    }};
}

Action Actions::ReactiveRollMove() {
    return {[=] (BattleContext &bc) {
        // writhing mass is always monster 0
        Monster &m = bc.monsters.arr[0];

        for (int i = 0 ; i < m.getStatus<MS::REACTIVE>(); ++i) {
            m.rollMove(bc);
        }
        m.setStatus<MS::REACTIVE>(0);
    }};
}

Action Actions::NoOpRollMove() {
    return {[=] (BattleContext &bc) {
        bc.noOpRollMove();
    }};
}

Action Actions::ChangeStance(Stance stance) {
    return sts::Action();
}

Action Actions::GainEnergy(int amount) {
    return {[=] (BattleContext &bc) {
        bc.player.gainEnergy(amount);
    }};
}

Action Actions::GainBlock(int amount) {
    return {[=] (BattleContext &bc) {
        bc.player.gainBlock(bc, amount);
    }, false};
}
//
//Action Actions::GainBlockFromCard(int amount) {
//    return {[=] (BattleContext &bc) {
//
//        // TODO
//        bc.player.block += amount;
//    };
//}

Action Actions::DrawCards(int amount) {
    return {[=] (BattleContext &bc) {
        bc.drawCards(amount);
    }};
}

Action Actions::EmptyDeckShuffle() {
    return {[=] (BattleContext &bc) {
        java::Collections::shuffle(
                bc.cards.discardPile.begin(),
                bc.cards.discardPile.end(),
                java::Random(bc.shuffleRng.randomLong())
        );

        bc.cards.moveDiscardPileIntoToDrawPile();
    }};
}

Action Actions::ShuffleDrawPile() {
    return {[=] (BattleContext &bc) {
        java::Collections::shuffle(
                bc.cards.drawPile.begin(),
                bc.cards.drawPile.end(),
                java::Random(bc.shuffleRng.randomLong())
        );
    }};
}

Action Actions::ShuffleTempCardIntoDrawPile(CardId id, int count) {
    return {[=] (BattleContext &bc) {
        CardInstance c(id);
        for (int i = 0; i < count; ++i) {
            const int idx = bc.cards.drawPile.empty() ? 0 : bc.cardRandomRng.random(static_cast<int>(bc.cards.drawPile.size()-1));
            bc.cards.createTempCardInDrawPile(idx, c);
        }
    }};
}

Action Actions::PlayTopCard(int monsterTargetIdx, bool exhausts) {
    return {[=](BattleContext &bc) {
        bc.playTopCardInDrawPile(monsterTargetIdx, exhausts);
    }};
}


// todo fix the arguments are used wrongly all over ? what did this mean
// todo check for master reality
Action Actions::MakeTempCardInHand(CardId card, bool upgraded, int amount) {
    return {[=](BattleContext &bc) {
        Actions::MakeTempCardInHand(bc.createGeneratedCard(card, upgraded), amount).actFunc(bc);
    }};
}

Action Actions::MakeTempCardInHand(CardInstance card, int amount) {
    // todo master reality when the action is created
    return {[=](BattleContext &bc) {
        for (int i = 0; i < amount; ++i) {
            CardInstance c(card);
            c.uniqueId = bc.cards.nextUniqueCardId++;
            bc.cards.notifyAddCardToCombat(c);
            if (bc.cards.cardsInHand == 10) {
                // ShowCardAndAddToDiscardEffect creates the card without the
                // cost reset used when an existing hand card is discarded.
                bc.cards.notifyAddToDiscardPile(c);
                bc.cards.discardPile.push_back(c);
            } else {
                // ShowCardAndAddToHandEffect adds the card, then runs
                // onCardDrawOrDiscard, so Corruption makes a generated skill
                // permanently free before its setCostForTurn(-9).
                bc.cards.moveToHand(c);
                bc.onCardDrawOrDiscard();
            }
        }
    }};
}

Action Actions::MakeTempCardInDrawPile(const CardInstance &c, int amount, bool shuffleInto) {
    // the random calculation is done in an effect so it be wrong to do it here?
    return {[=](BattleContext &bc) {
        for (int i = 0; i < amount; ++i) {
            if (shuffleInto) {
                const int idx = bc.cards.drawPile.empty() ? 0 : bc.cardRandomRng.random(static_cast<int>(bc.cards.drawPile.size()-1));
                bc.cards.createTempCardInDrawPile(idx, c);
            } else {
                bc.cards.createTempCardInDrawPile(bc.cards.drawPile.size(), c);
            }
        }
    }};
}

Action Actions::MakeTempCardInDiscard(const CardInstance &c, int amount) {
    CardInstance c_copy(c);
    // This action animates the inserted object; its Soul clears temporary
    // costs. Hand-overflow and Dual Wield insert a distinct source object
    // while animating a copy, so their CardManager paths must not reset it.
    c_copy.costForTurn = (c_copy.cost == -1) ? static_cast<int8_t>(-1) : c_copy.cost;
    return {[=](BattleContext &bc) { // todo maybe store by reference instead of copy if CardInstance is too big to fit directly in the lambda. Make a seperate space where the card will be stored
        for (int i = 0; i < amount; ++i) {
            bc.cards.createTempCardInDiscard(c_copy);
        }
    }};
}

Action Actions::MakeTempCardsInHand(std::vector<CardInstance> cards) {
    return {[=](BattleContext &bc) {
        for (auto c : cards) {
            Actions::MakeTempCardInHand(c).actFunc(bc);
        }
    }};
}


Action Actions::DiscardNoTriggerCard() {
    return {[](BattleContext &bc) {
        const auto &c = bc.curCardQueueItem.card;
        bc.cards.notifyRemoveFromHand(c);
        // UseCardAction without use triggers still exhausts on card.exhaust (a Burn the Medical Kit marked earlier),
        // after Strange Spoon's roll.
        if (c.doesExhaust() && !(bc.player.hasRelic<R::STRANGE_SPOON>() && bc.cardRandomRng.randomBoolean())) {
            bc.triggerAndMoveToExhaustPile(c);
            return;
        }
        bc.cards.moveToDiscardPile(c);
        bc.onCardDrawOrDiscard(); // UseCardAction -> CardGroup.moveToDiscardPile: Corruption frees the other hand skills
    }};
}

Action Actions::ClearCardQueue() {
    return {[](BattleContext &bc) {
        bc.cardQueue.clear();
    }};
}

Action Actions::DiscardAtEndOfTurn() {
    return {[] (BattleContext &bc) {
        bc.discardAtEndOfTurn();
    }};
}

Action Actions::DiscardAtEndOfTurnHelper() {
    return {[] (BattleContext &bc) {
        bc.discardAtEndOfTurnHelper();
    }};
}

Action Actions::RestoreRetainedCards(int count) {
    return {[=] (BattleContext &bc) {
        bc.restoreRetainedCards(count);
    }};
}

Action Actions::UnnamedEndOfTurnAction() {
    // EndTurnAction does this:
    //        AbstractDungeon.player.resetControllerValues();
    //        this.turnHasEnded = true;
    //        playerHpLastTurn = AbstractDungeon.player.currentHealth;

    return {[=] (BattleContext &bc) {
        bc.turnHasEnded = true;
        if (!bc.skipMonsterTurn) {
            bc.addToBot(Actions::MonsterStartTurnAction());
            bc.monsterTurnIdx = 0; // monstergroup preincrements this
        }
    }};
}

Action Actions::MonsterStartTurnAction() {
    return {[] (BattleContext &bc) {
        bc.monsters.applyPreTurnLogic(bc);
    }};
}

namespace {
    // LockOnPower: orb damage against a locked target is increased by 50%. The
    // game applies this per target, so multi-target orb hits resolve one enemy at
    // a time instead of through a single DamageAllEnemy action.
    int lockOnDamage(const BattleContext &bc, int targetIdx, int damage) {
        if (targetIdx >= 0 && targetIdx < bc.monsters.monsterCount
                && bc.monsters.arr[targetIdx].hasStatus<MS::LOCK_ON>()) {
            return static_cast<int>(static_cast<float>(damage) * 1.5f);
        }
        return damage;
    }

    // DarkOrb.onEndOfTurn: the accumulated counter grows by max(0, 6 + focus).
    // Negative focus never reduces the counter (Dark.applyFocus clamps only the
    // growth rate, and never touches the accumulated value itself).
    void growDarkOrb(BattleContext &bc, Player::OrbState &orb) {
        const int growth = 6 + bc.player.focus;
        if (growth > 0) {
            orb.darkCounter = static_cast<std::int16_t>(orb.darkCounter + growth);
        }
    }

    // One orb's end-of-turn passive: Lightning and Frost settle, Dark grows and
    // Plasma has none (Plasma overrides onStartOfTurn only). Shared by the
    // end-of-turn trigger, Loop's extra trigger and Emotion Chip's ImpulseAction.
    void triggerOrbEndOfTurnPassive(BattleContext &bc, Player::OrbState &orb) {
        switch (orb.type) {
            case Orb::LIGHTNING: {
                // LightningOrb.onEndOfTurn: deal 3 (+focus) to a random enemy.
                // With Electrodynamics (ElectroPower) it hits all enemies and
                // consumes no target roll (Lightning checks hasPower("Electro")).
                const int damage = 3 + bc.player.focus;
                if (bc.player.hasStatus<PS::ELECTRO>()) {
                    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
                        if (!bc.monsters.arr[i].isDeadOrEscaped()) {
                            bc.addToBot(Actions::AttackEnemy(i, lockOnDamage(bc, i, damage), DamageType::THORNS));
                        }
                    }
                } else {
                    // LightningOrb.onEndOfTurn queues LightningOrbPassiveAction, whose
                    // triggerPassiveEffect calls AbstractDungeon.getRandomMonster() as it
                    // runs - after every passive this same pass already queued, and after
                    // the block/settle actions in between, has resolved. Queue the roll
                    // through addToBottom so it keeps that position in the queue: rolling
                    // here would read a candidate list that still holds monsters an
                    // earlier passive just killed (F30, floor 30: the roll silently ran
                    // before the Frost passive's settle action, leaving the counter one
                    // step behind and sending Coolheaded's evoke onto the wrong enemy).
                    // The roll is unconditional: the action picks its target before it
                    // looks at the damage, so Focus <= -3 still advances cardRandomRng.
                    bc.addToBot({[damage] (BattleContext &bc) {
                        const int target = bc.monsters.getRandomMonsterIdx(bc.cardRandomRng);
                        if (target >= 0 && damage > 0) {
                            bc.addToBot(Actions::AttackEnemy(target, lockOnDamage(bc, target, damage), DamageType::THORNS));
                        }
                    }});
                }
                break;
            }
            case Orb::FROST: {
                // FrostOrb.onEndOfTurn: gain 2 (+focus) block.
                const int block = 2 + bc.player.focus;
                if (block > 0) {
                    bc.addToBot(Actions::GainBlock(block));
                }
                break;
            }
            case Orb::DARK:
                // DarkOrb.onEndOfTurn: the counter grows; no damage, the orb stays.
                growDarkOrb(bc, orb);
                break;
            default:
                // Plasma has no end-of-turn passive.
                break;
        }
    }
}

Action Actions::TriggerOrbPassiveAction(int orbIdx) {
    return {[=] (BattleContext &bc) {
        // LoopPower: for each stack, trigger the oldest orb's passive by calling
        // both onStartOfTurn and onEndOfTurn (only Plasma overrides the former).
        if (orbIdx < 0 || orbIdx >= bc.player.orbs.size()) {
            return;
        }
        auto &orb = bc.player.orbs[orbIdx];
        if (orb.type == Orb::FUSION) {
            bc.addToBot(Actions::GainEnergy(1));
        }
        triggerOrbEndOfTurnPassive(bc, orb);
    }};
}

Action Actions::TriggerStartOfTurnOrbsAction() {
    return {[] (BattleContext &bc) {
        // Plasma is the only orb with a start-of-turn passive: Plasma.onStartOfTurn
        // gains passiveAmount (1) energy per orb, queued at the bottom. Focus never
        // changes plasma values (AbstractOrb.applyFocus skips Plasma).
        for (int i = 0; i < bc.player.orbs.size(); ++i) {
            if (bc.player.orbs[i].type == Orb::FUSION) {
                bc.addToBot(Actions::GainEnergy(1));
            }
        }
        // AbstractPlayer.applyStartOfTurnOrbs: with Cables, orbs.get(0).onStartOfTurn() runs again.
        if (bc.player.hasRelic<R::GOLD_PLATED_CABLES>() && bc.player.orbs.size() > 0
                && bc.player.orbs[0].type == Orb::FUSION) {
            bc.addToBot(Actions::GainEnergy(1));
        }
    }};
}

Action Actions::TriggerEndOfTurnOrbsAction() {
    return {[] (BattleContext &bc) {
        // End-of-turn passives trigger left to right and never consume the orb.
        for (int i = 0; i < bc.player.orbs.size(); ++i) {
            triggerOrbEndOfTurnPassive(bc, bc.player.orbs[i]);
        }
        // GoldPlatedCables lives inside TriggerEndOfTurnOrbsAction in the game:
        // after the full pass it triggers orbs.get(0) - the orb evokeOrb removes
        // first, index 0 here - one more time. The game queues that second
        // TriggerEndOfTurnOrbsAction to the bottom, so the first pass's damage
        // resolves and can kill a monster before the second pass picks its
        // target. Rolling the target here instead would see a corpse as alive
        // and draw from the wrong range.
        if (bc.player.hasRelic<R::GOLD_PLATED_CABLES>() && bc.player.orbs.size() > 0) {
            bc.addToBot({[] (BattleContext &bc) {
                if (bc.player.orbs.size() > 0) {
                    triggerOrbEndOfTurnPassive(bc, bc.player.orbs[0]);
                }
            }});
        }
    }};
}

Action Actions::ImpulseAction() {
    return {[] (BattleContext &bc) {
        // EmotionChip.atTurnStart queues ImpulseAction: every orb runs its
        // onStartOfTurn and then its onEndOfTurn passive once. GoldPlatedCables
        // adds one more full trigger to orbs.get(0).
        for (int i = 0; i < bc.player.orbs.size(); ++i) {
            auto &orb = bc.player.orbs[i];
            if (orb.type == Orb::FUSION) {
                bc.addToBot(Actions::GainEnergy(1));
            }
            triggerOrbEndOfTurnPassive(bc, orb);
        }
        if (bc.player.hasRelic<R::GOLD_PLATED_CABLES>() && bc.player.orbs.size() > 0) {
            auto &orb = bc.player.orbs[0];
            if (orb.type == Orb::FUSION) {
                bc.addToBot(Actions::GainEnergy(1));
            }
            triggerOrbEndOfTurnPassive(bc, orb);
        }
    }};
}

namespace {
    // Fires one orb's evoke effects without consuming it. Shared by EvokeOrbAction
    // and the full-bar push-out in ChannelOrb. The target roll for lightning
    // happens here, when the effects are queued. The game queues evoke effects
    // with addToTop (Lightning/Frost/Dark onEvoke), so effects must resolve
    // before anything else pending in the queue.
    void fireEvokeEffects(BattleContext &bc, const Player::OrbState &orb) {
        switch (orb.type) {
            case Orb::LIGHTNING: {
                // LightningOrb.onEvoke: deal 8 (+focus +bonus) to a random enemy, or
                // to all enemies under Electrodynamics (no target roll in that case).
                // LightningOrbEvokeAction.update() calls getRandomMonster() before it
                // looks at the damage, so the roll happens even at zero damage - Focus
                // <= -8 must not shift cardRandomRng by one for the rest of the fight.
                const int damage = 8 + bc.player.focus;
                if (bc.player.hasStatus<PS::ELECTRO>()) {
                    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
                        if (!bc.monsters.arr[i].isDeadOrEscaped()) {
                            bc.addToTop(Actions::AttackEnemy(i, lockOnDamage(bc, i, damage), DamageType::THORNS));
                        }
                    }
                } else {
                    const int target = bc.monsters.getRandomMonsterIdx(bc.cardRandomRng);
                    if (target >= 0 && damage > 0) {
                        bc.addToTop(Actions::AttackEnemy(target, lockOnDamage(bc, target, damage), DamageType::THORNS));
                    }
                }
                break;
            }
            case Orb::FROST: {
                // FrostOrb.onEvoke: gain 5 (+focus) block.
                const int block = 5 + bc.player.focus;
                if (block > 0) {
                    bc.addToTop(Actions::GainBlock(block));
                }
                break;
            }
            case Orb::FUSION:
                // Plasma.onEvoke: gain evokeAmount (2, never scaled by focus) energy.
                bc.addToTop(Actions::GainEnergy(2));
                break;
            case Orb::DARK: {
                // DarkOrb.onEvoke via DarkOrbEvokeAction: deal the accumulated
                // counter to the lowest-HP enemy, chosen when the effect queues.
                if (orb.darkCounter > 0) {
                    int target = -1;
                    int lowestHp = 0;
                    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
                        const auto &m = bc.monsters.arr[i];
                        if (m.isDeadOrEscaped() || m.curHp <= 0) {
                            continue;
                        }
                        if (target < 0 || m.curHp < lowestHp) {
                            target = i;
                            lowestHp = m.curHp;
                        }
                    }
                    if (target >= 0) {
                        // DarkOrbEvokeAction runs AbstractOrb.applyLockOn on its target.
                        bc.addToTop(Actions::AttackEnemy(target, lockOnDamage(bc, target, orb.darkCounter), DamageType::THORNS));
                    }
                }
                break;
            }
            default:
                // TODO(defect M3): plasma evoke settlement stays unimplemented.
                break;
        }
    }
}

Action Actions::ChannelOrb(Orb orb, std::int16_t darkCounter) {
    return {[=] (BattleContext &bc) {
        // ChannelAction(orb) defaults autoEvoke to true. Its update() only calls
        // AbstractPlayer.channelOrb when autoEvoke is set, or when the bar still
        // holds an EmptyOrbSlot. A channel requested with a full bar therefore
        // does nothing here: the game's channelOrb would instead queue an evoke
        // of slot 0 and a nested ChannelAction, so the orb is not swallowed, it
        // is simply pushed back behind the evoke.
        if (bc.player.orbSlots <= 0) {
            return;
        }
        if (bc.player.hasEmptyOrb()) {
            bc.player.channelOrb(orb, darkCounter);
            return;
        }
        // AbstractPlayer.channelOrb with no empty slot: addToTop a ChannelAction
        // whose own channelOrb now finds that empty slot, and before it an
        // EvokeOrbAction(1). Both go to the top, evoke first, so the oldest orb
        // fires before the new one lands at the end.
        if (!bc.player.orbs.empty()) {
            const auto pushed = bc.player.orbs[0];
            bc.addToTop(Actions::ChannelOrb(orb, darkCounter));
            fireEvokeEffects(bc, pushed);
            bc.player.orbs.remove(0);
        }
    }};
}

Action Actions::DarkImpulseAction() {
    return {[] (BattleContext &bc) {
        // DarkImpulseAction (upgraded Darkness): every Dark orb's counter grows
        // once right now. GoldPlatedCables then repeats the trigger for orbs.get(0)
        // when that orb is Dark, like the other orb-trigger actions.
        for (auto &orb : bc.player.orbs) {
            if (orb.type == Orb::DARK) {
                growDarkOrb(bc, orb);
            }
        }
        if (bc.player.hasRelic<R::GOLD_PLATED_CABLES>() && bc.player.orbs.size() > 0
                && bc.player.orbs[0].type == Orb::DARK) {
            growDarkOrb(bc, bc.player.orbs[0]);
        }
    }};
}

Action Actions::DoubleEnergy() {
    return {[] (BattleContext &bc) {
        // DoubleEnergyAction: gainEnergy(player.energy) doubles the current energy.
        bc.player.gainEnergy(bc.player.energy);
    }};
}

Action Actions::EmptyEnergy(bool freeToPlay) {
    return {[=] (BattleContext &bc) {
        // TempestAction/MulticastAction/ReinforcedBodyAction.update(): the energy
        // pool is emptied at the very end of the action, and only when the card
        // was not played for free (bytecode guard `freeToPlayOnce != 0 -> skip`).
        // AbstractCard.useCard() pays nothing for an X card, so this is the whole
        // energy cost of the card.
        if (freeToPlay) {
            return;
        }
        bc.player.useEnergy(bc.player.energy);
    }};
}

Action Actions::MultiCastAction(int times) {
    return {[=] (BattleContext &bc) {
        // MulticastAction: evoke the oldest orb N times. Every trigger except
        // the last one leaves the orb in the bar (EvokeWithoutRemovingOrbAction);
        // the final EvokeOrbAction(1) consumes it. Each trigger is its own action,
        // so a trigger's target roll sees the monsters the previous trigger killed
        // (AbstractDungeon.getRandomMonster filters halfDead/isDying/isEscaping);
        // rolling all N targets here would pick from a stale candidate list.
        for (int i = 0; i < times; ++i) {
            const bool last = i == times - 1;
            bc.addToBot({[=] (BattleContext &bc) {
                if (bc.player.orbs.empty()) {
                    return;
                }
                fireEvokeEffects(bc, bc.player.orbs[0]);
                if (last) {
                    bc.player.orbs.remove(0);
                }
            }});
        }
    }};
}

Action Actions::FissionAction(bool upgraded) {
    return {[=] (BattleContext &bc) {
        // FissionAction: the orb count is read when the action resolves, then all
        // orbs are removed (evoked when upgraded), and that many energy/cards are
        // granted. Queued bottom-up so removal resolves before the rewards.
        const int count = static_cast<int>(bc.player.orbs.size());
        if (upgraded) {
            // EvokeAllOrbsAction addToTop's one EvokeOrbAction per orb, so each
            // evoke's target roll sees the monsters the previous evoke killed.
            // One evoke per orb, queued in order, reproduces that.
            for (int i = 0; i < count; ++i) {
                bc.addToBot({[] (BattleContext &bc) {
                    if (bc.player.orbs.empty()) {
                        return;
                    }
                    fireEvokeEffects(bc, bc.player.orbs[0]);
                    bc.player.orbs.remove(0);
                }});
            }
        } else {
            bc.addToBot({[] (BattleContext &bc) {
                bc.player.orbs.clear();
            }});
        }
        bc.addToBot(Actions::GainEnergy(count));
        bc.addToBot(Actions::DrawCards(count));
    }};
}

Action Actions::EvokeOrbAction(int orbIdx, int times) {
    return {[=] (BattleContext &bc) {
        if (orbIdx < 0 || orbIdx >= bc.player.orbs.size()) {
            return;
        }
        if (times <= 1) {
            fireEvokeEffects(bc, bc.player.orbs[orbIdx]);
            // Evoking consumes the orb, after all triggered instances resolve.
            bc.player.orbs.remove(orbIdx);
            return;
        }
        // EvokeOrbAction.update() evokes `times` orbs in one update, and every
        // LightningOrbEvokeAction it queues is addToTop'd, so that action resolves
        // before the loop continues. Each evoke therefore observes the monsters
        // the previous one killed: AbstractDungeon.getRandomMonster() filters
        // halfDead/isDying/isEscaping, so the candidate list can shrink between
        // the rolls. Queue one evoke per slot to reproduce that ordering; rolling
        // all `times` targets up front would pick from a stale candidate list.
        for (int i = 0; i < times; ++i) {
            const bool last = i == times - 1;
            bc.addToBot({[=] (BattleContext &bc) {
                if (orbIdx < 0 || orbIdx >= bc.player.orbs.size()) {
                    return;
                }
                fireEvokeEffects(bc, bc.player.orbs[orbIdx]);
                if (last) {
                    bc.player.orbs.remove(orbIdx);
                }
            }});
        }
    }};
}

Action Actions::ExhaustTopCardInHand() {
    return {[=] (BattleContext &bc) {
        bc.exhaustTopCardInHand();
    }};
}

Action Actions::ExhaustSpecificCardInHand(int idx, std::int16_t uniqueId) {
    return {[=] (BattleContext &bc) {
        bc.exhaustSpecificCardInHand(idx, uniqueId);
    }};
}

Action Actions::DamageRandomEnemy(int damage) {
    return {[=] (BattleContext &bc) {
        const int idx = bc.monsters.getRandomMonsterIdx(bc.cardRandomRng, true);
        if (idx == -1) {
            return;
        }
        bc.monsters.arr[idx].damage(bc, damage);
        bc.checkCombat();
    }};
}

Action Actions::ExhaustRandomCardInHand(int count, bool anyNumber) {
    return {[=] (BattleContext &bc) {
        // ExhaustAction skips random selection when every remaining card must be
        // exhausted and anyNumber is false. True Grit uses that form; Fiend Fire
        // uses anyNumber=true and still consumes cardRandomRng for a one-card hand.
        if (!anyNumber && bc.cards.cardsInHand <= count) {
            while (bc.cards.cardsInHand > 0) {
                const int idx = bc.cards.cardsInHand - 1;
                auto c = bc.cards.hand[idx];
                bc.cards.removeFromHandAtIdx(idx);
                bc.triggerAndMoveToExhaustPile(c);
            }
            return;
        }

        for (int i = 0; i < count; ++i) {
            if (bc.cards.cardsInHand <= 0) {
                return;
            }
            const auto idx = bc.cards.getRandomCardIdxInHand(bc.cardRandomRng);
            auto c = bc.cards.hand[idx];
            bc.cards.removeFromHandAtIdx(idx);
            bc.triggerAndMoveToExhaustPile(c);
        }
    }};
}

Action Actions::MadnessAction() {
    return {[=] (BattleContext &bc) {

        bool haveNonZeroCost = false;
        bool haveNonZeroTurnCost = false;
        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            const auto &c = bc.cards.hand[i];

            if (c.costForTurn > 0) {
                haveNonZeroTurnCost = true;
                break;
            }

            if (c.cost > 0) {
                haveNonZeroCost = true;
            }
        }

        const auto haveValidCard = haveNonZeroCost || haveNonZeroTurnCost;
        if (!haveValidCard) {
            return;
        }

        // always have 1 or more cards in hand here
//#pragma clang diagnostic push
//#pragma ide diagnostic ignored "EndlessLoop"
        while (true) {
            const auto randomIdx = bc.cardRandomRng.random(bc.cards.cardsInHand-1);
            auto &c = bc.cards.hand[randomIdx];

            if (haveNonZeroTurnCost) {
                if (c.costForTurn > 0) {
                    c.cost = 0;
                    c.costForTurn = 0;
                    break;
                } else {
                    continue;
                }

            } else {
                if (c.cost > 0) {
                    c.cost = 0;
                    c.costForTurn = 0;
                    break;

                } else {
                    continue;
                }
            }
        }
//#pragma clang diagnostic pop

    }};
}

Action Actions::RandomizeHandCost() {
    return {[=] (BattleContext &bc) {
        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            auto &c = bc.cards.hand[i];
            if (c.cost >= 0) {
                int newCost = bc.cardRandomRng.random(3);
                // Java preserves a temporary turn override when the roll equals printed cost.
                if (c.cost != newCost) {
                    c.cost = newCost;
                    c.costForTurn = newCost;
                }
            }
        }
    }};
}

Action Actions::GainBlockRandomEnemy(int sourceMonster, int amount) {
    return {[=] (BattleContext &bc) {
        int validIdxs[5];
        int validCount = 0;

        for (int i = 0; i < bc.monsters.monsterCount; ++i) {
            const auto &m = bc.monsters.arr[i];
            if (i != sourceMonster && !m.isDying()) {
                validIdxs[validCount++] = i;
            }
        }

        int targetIdx;
        if (validCount > 0) {
            targetIdx = validIdxs[bc.aiRng.random(validCount - 1)];
        } else {
            targetIdx = sourceMonster;
        }

        bc.monsters.arr[targetIdx].addBlock(amount);
    }};
}

Action Actions::SummonGremlins() {
    // gremlin leader searches in the order 1, 2, 0 for open space

    return {[=] (BattleContext &bc) {
        int openIdxCount = 0;
        int newGremlinIdxs[2];
        if (bc.monsters.arr[1].isDying()) {
            newGremlinIdxs[openIdxCount++] = 1;
        }
        if (bc.monsters.arr[2].isDying()) {
            newGremlinIdxs[openIdxCount++] = 2;
        }
        if (openIdxCount < 2 && bc.monsters.arr[0].isDying()) {
            newGremlinIdxs[openIdxCount++] = 0;
        }
#ifdef sts_asserts
        assert(openIdxCount == 2);
#endif

        auto &gremlin0 = bc.monsters.arr[newGremlinIdxs[0]];
        auto &gremlin1 = bc.monsters.arr[newGremlinIdxs[1]];

        gremlin0 = Monster();
        gremlin1 = Monster();

        gremlin0.construct(bc, MonsterGroup::getGremlin(bc.aiRng), newGremlinIdxs[0]);
        gremlin1.construct(bc, MonsterGroup::getGremlin(bc.aiRng), newGremlinIdxs[1]);
        bc.monsters.monstersAlive += 2;

        if (bc.player.hasRelic<R::PHILOSOPHERS_STONE>()) {
            gremlin0.buff<MS::STRENGTH>(1);
            gremlin1.buff<MS::STRENGTH>(1);
        }
        gremlin0.buff<MS::MINION>();
        gremlin1.buff<MS::MINION>();

        gremlin0.rollMove(bc);
        gremlin1.rollMove(bc);
        gremlin0.preBattleAction(bc);
        gremlin1.preBattleAction(bc);
    }};
}

Action Actions::SpawnTorchHeads() {
    return {[=] (BattleContext &bc) {
        const auto spawnCount = 3-bc.monsters.monstersAlive;
#ifdef sts_asserts
        assert(spawnCount > 0);
#endif
        const int spawnIdxs[2] {(bc.monsters.arr[1].isDying() ? 1 : 0), 0};

        for (int i = 0; i < spawnCount; ++i) {
            const auto idx = spawnIdxs[i];
            auto &torchHead = bc.monsters.arr[idx];
            torchHead = Monster();
            torchHead.construct(bc, MonsterId::TORCH_HEAD, idx);
            torchHead.initHp(bc.monsterHpRng, bc.ascension); // bug somewhere in game
            torchHead.setMove(MMID::TORCH_HEAD_TACKLE);
            torchHead.buff<MS::MINION>();

            if (bc.player.hasRelic<R::PHILOSOPHERS_STONE>()) {
                torchHead.buff<MS::STRENGTH>(1);
            }
            ++bc.monsters.monstersAlive;
        }

        for (int i = 0; i < spawnCount; ++i) {
            bc.noOpRollMove();
        }
    }};
}

Action Actions::SpireShieldDebuff() {
    return {[] (BattleContext &bc) {
        if (bc.aiRng.randomBoolean()) {
            bc.player.debuff<PS::FOCUS>(-1);
        } else {
            bc.player.debuff<PS::STRENGTH>(-1);
        }
    }};
}


Action Actions::OnAfterCardUsed() {
    return {[] (BattleContext &bc) {
        bc.onAfterUseCard();
    }, false};
}

Action Actions::PutRandomCardsInDrawPile(CardType type, int count) {
    return {[=] (BattleContext &bc) {
        CardId ids[5];
        for (int i = 0; i < count; ++i) {
            ids[i] = getTrulyRandomCardInCombat(bc.cardRandomRng, bc.player.cc, type);
        }

        for (int i = 0; i < count; ++i) {
            CardInstance card(ids[i], false);
            card.cost = 0;
            card.costForTurn = 0;

            const int idx = bc.cards.drawPile.empty() ? 0 : bc.cardRandomRng.random(static_cast<int>(bc.cards.drawPile.size()-1));
            bc.cards.createTempCardInDrawPile(idx, card);
        }
    }};
}

Action Actions::DiscoveryAction(CardType type, int amount) {
    return {[=] (BattleContext &bc) {
        bc.haveUsedDiscoveryAction = true;
        bc.openDiscoveryScreen(sts::generateDiscoveryCards(bc.cardRandomRng, bc.player.cc, type), amount);
        bc.cardSelectInfo.discoveryFrameRng = true;
        bc.cardSelectInfo.discoveryType = type;
    }};
}

Action Actions::InfernalBladeAction() {
    return {[=] (BattleContext &bc) {
        const auto cid = getTrulyRandomCardInCombat(bc.cardRandomRng, bc.player.cc, CardType::ATTACK);
        CardInstance c = bc.createGeneratedCard(cid);
        c.setCostForTurn(0);
        bc.addToTop( Actions::MakeTempCardInHand(c) );
    }};
}

Action Actions::JackOfAllTradesAction(bool upgraded) {
    return {[=] (BattleContext &bc) {
        const auto c1 = sts::getTrulyRandomColorlessCardInCombat(bc.cardRandomRng);
        bc.addToTop( Actions::MakeTempCardInHand(c1) );
        if (upgraded) {
            auto c2 = sts::getTrulyRandomColorlessCardInCombat(bc.cardRandomRng);
            bc.addToTop( Actions::MakeTempCardInHand(c2) );
        }
    }};
}

Action Actions::TransmutationAction(bool upgraded, int energy, bool freeToPlay) {
    return {[=] (BattleContext &bc) {
        const auto effectAmount = energy + (bc.player.hasRelic<R::CHEMICAL_X>() ? 2 : 0);

        if (effectAmount == 0) {
            return;
        }

        std::vector<CardInstance> cards;
       for (int i = 0; i < effectAmount; ++i) {
           const auto cid = sts::getTrulyRandomColorlessCardInCombat(bc.cardRandomRng);
           CardInstance c(cid, upgraded);
           c.setCostForTurn(0);
           cards.push_back(c);
       }
       bc.addToBot( Actions::MakeTempCardsInHand(cards) );

       // TransmutationAction.update() empties the pool after queueing the cards,
       // and only when the card was not played for free.
       if (!freeToPlay) {
           bc.player.useEnergy(bc.player.energy);
       }

    }};
}

Action Actions::ViolenceAction(int count) { // todo a faster algorithm for inserting into the attack list
    return {[=] (BattleContext &bc) {
        std::vector<int> attackIdxList;
        for (int i = 0; i < bc.cards.drawPile.size(); ++i) {
            const auto &c = bc.cards.drawPile[i];
            if (c.getType() == CardType::ATTACK) {

                if (attackIdxList.empty()) {
                    attackIdxList.push_back(i);
                } else {
                    const auto randomIdx = bc.cardRandomRng.random(static_cast<int>(attackIdxList.size()) - 1);
                    attackIdxList.insert(attackIdxList.begin()+randomIdx, i);
                }
            }
        }

        if (attackIdxList.empty()) {
            return;
        }

        int removeIdxs[4];
        // hack to do this faster: the attackList is just pushed forward by i so we skip removing from bottom
        int i = 0;
        for (; i < count; ++i) {
            if (attackIdxList.size()-i <= 0) {
                break;
            }

            java::Collections::shuffle(attackIdxList.begin()+i, attackIdxList.end(), java::Random(bc.shuffleRng.randomLong()));
            const auto removeIdx = attackIdxList[i];
            removeIdxs[i] = removeIdx;

            const auto &c = bc.cards.drawPile[removeIdx];
            if (bc.cards.cardsInHand == 10) {
                bc.cards.moveToDiscardPile(c);
            } else {
                bc.cards.moveToHand(c);
            }
        }

        std::sort(removeIdxs, removeIdxs+i);
        for (int x = i-1; x >= 0; --x) {
            const auto drawPileRemoveIdx = removeIdxs[x];
            bc.cards.removeFromDrawPileAtIdx(drawPileRemoveIdx);
        }

    }};
}

Action Actions::BetterDiscardPileToHandAction(int amount, CardSelectTask task) {
    return {[=] (BattleContext &bc) {
        if (bc.cards.discardPile.empty() || amount <= 0) {
            return;
        }
        if (bc.cards.discardPile.size() <= amount) {
            const int count = bc.cards.discardPile.size();
            for (int i = 0; i < count && bc.cards.cardsInHand < 10; ++i) {
                bc.chooseDiscardToHandCard(0, task == CardSelectTask::LIQUID_MEMORIES_POTION);
            }
        } else {
            bc.openSimpleCardSelectScreen(task, amount);
        }
    }};
}

Action Actions::ArmamentsAction() {
    return {[=] (BattleContext &bc) {
        int canUpgradeCount = 0;
        int lastUpgradeIdx = 0;
        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            if (bc.cards.hand[i].canUpgrade()) {
                ++canUpgradeCount;
                lastUpgradeIdx = i;
            }
        }

        if (canUpgradeCount == 0) {
            // do nothing

        } else if (canUpgradeCount == 1) {
            bc.cards.hand[lastUpgradeIdx].upgrade();

        } else {
            bc.openSimpleCardSelectScreen(CardSelectTask::ARMAMENTS, 1);
        }
    }};
}

Action Actions::DualWieldAction(int copyCount) {
    return {[=] (BattleContext &bc) {

//        fixed_list<CardInstance, 10> validCards;
//        fixed_list<CardInstance, 10> invalidCards;
//
//        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
//            const bool valid = bc.cards.hand[i].getType() == CardType::ATTACK || bc.cards.hand[i].getType() == CardType::POWER;
//            if (valid) {
//                validCards.push_back(bc.cards.hand[i]);
//
//            } else {
//                invalidCards.push_back(bc.cards.hand[i]);
//            }
//        }

        int validCount = 0;
        int lastValidIdx = 0;

        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            const bool valid = bc.cards.hand[i].getType() == CardType::ATTACK || bc.cards.hand[i].getType() == CardType::POWER;
            if (valid) {
                ++validCount;
                lastValidIdx = i;
            }
        }

        if (validCount == 0) {
            return;
        }

        if (validCount == 1) {
            for (int i = 0; i < copyCount; ++i) {
                auto c = bc.cards.hand[lastValidIdx];
                if (bc.cards.cardsInHand + 1 <= CardManager::MAX_HAND_SIZE) {
                    bc.cards.createTempCardInHand(c);

                } else {
                    bc.cards.createTempCardInDiscard(c);

                }
            }

        } else {
            bc.inputState = InputState::CARD_SELECT;
            bc.cardSelectInfo.cardSelectTask = CardSelectTask::DUAL_WIELD;
            bc.cardSelectInfo.dualWield_CopyCount() = copyCount;

        }


    }};
}

Action Actions::ExhumeAction() {
    return {[=] (BattleContext &bc) {
        if (bc.cards.exhaustPile.empty() || bc.cards.cardsInHand == 10) {
            return;
        }
        // The original automatic return depends on the whole pile size,
        // before temporarily excluding other Exhumes from the selection.
        if (bc.cards.exhaustPile.size() == 1) {
            if (bc.cards.exhaustPile[0].id != CardId::EXHUME) {
                bc.chooseExhumeCard(0);
            }
            return;
        }
        std::vector<CardInstance> excluded;
        for (int i = 0; i < bc.cards.exhaustPile.size();) {
            if (bc.cards.exhaustPile[i].id == CardId::EXHUME) {
                excluded.push_back(bc.cards.exhaustPile[i]);
                bc.cards.removeFromExhaustPile(i);
            } else {
                ++i;
            }
        }
        if (bc.cards.exhaustPile.empty()) {
            for (const auto &card : excluded) bc.cards.exhaustPile.push_back(card);
            return;
        }
        if (!excluded.empty()) {
            // Each copied action queue owns a copy of these temporarily held
            // cards. Restore them before the played card's exhaust callback.
            bc.addToTop(Action{[excluded](BattleContext &state) {
                for (const auto &card : excluded) state.cards.exhaustPile.push_back(card);
            }});
        }
        bc.cardSelectInfo.cardSelectTask = CardSelectTask::EXHUME;
        bc.inputState = InputState::CARD_SELECT;
    }};
}

Action Actions::ForethoughtAction(bool upgraded) {
    return {[=] (BattleContext &bc) {
        if (bc.cards.cardsInHand == 0) {
            return;
        }

        if (bc.cards.cardsInHand == 1 && !upgraded) {
            bc.chooseForethoughtCard(0);
        } else {
            bc.openSimpleCardSelectScreen(CardSelectTask::FORETHOUGHT, upgraded ? 10 : 1);
            bc.cardSelectInfo.canPickAnyNumber = upgraded;
            bc.cardSelectInfo.canPickZero = upgraded;
        }
    }};
}

Action Actions::HeadbuttAction() {
    return {[=] (BattleContext &bc) {
        if (bc.outcome != Outcome::UNDECIDED || bc.monsters.areMonstersBasicallyDead() || bc.cards.discardPile.empty()) {
            return;

        } else if (bc.cards.discardPile.size() == 1) {
            bc.chooseHeadbuttCard(0);
        } else {
            bc.openSimpleCardSelectScreen(CardSelectTask::HEADBUTT, 1);
        }
    }};
}

Action Actions::ChooseExhaustOne() {
    return {[=] (BattleContext &bc) {
        if (bc.cards.cardsInHand == 0) {
            return;

        } else if (bc.cards.cardsInHand == 1) {
            bc.chooseExhaustOneCard(0);

        } else {
            bc.openSimpleCardSelectScreen(CardSelectTask::EXHAUST_ONE, 1);

        }
    }};
}

Action Actions::DrawToHandAction(CardSelectTask task, CardType cardType) {
    return {[=] (BattleContext &bc) {
        int count = 0;
        int idx = 0;

        for (int i = 0; i < bc.cards.drawPile.size(); ++i) {
            const auto &c = bc.cards.drawPile[i];
            if (c.getType() == cardType) {
                if (count > 0) {
                    // for keeping rng consistent with game
                    // the game creates a temporary list with the skills
                    bc.cardRandomRng.random(count - 1);
                }
                idx = i;
                ++count;
            }
        }

        if (count == 0) {
            return;
        }

        if (count == 1) {
            bc.chooseDrawToHandCards(&idx, 1);

        } else {
            bc.cardSelectInfo.cardSelectTask = task;
            bc.inputState = InputState::CARD_SELECT;
        }

    }};
}

Action Actions::WarcryAction() {
    return {[=] (BattleContext &bc) {
        // todo if the handSize equals or less than the cardsToChoose just choose them here
        if (bc.cards.cardsInHand == 0) {
            return;
        }

        if (bc.cards.cardsInHand == 1) {
            bc.cardRandomRng.random(1);
            bc.chooseWarcryCard(0);

        } else {
            bc.inputState = InputState::CARD_SELECT;
            bc.cardSelectInfo.cardSelectTask = CardSelectTask::WARCRY;
        }
    }};
}

Action Actions::TimeEaterPlayCardQueueItem(const CardQueueItem &x) {
    return {[=] (BattleContext &bc) {
        auto item = x;
        // Java UseCardAction: the cleared card's own exhaust / exhaustOnUseOnce, not the card whose use ended the turn.
        item.exhaustOnUse |= item.card.doesExhaust();
        item.triggerOnUse = false;
        bc.curCardQueueItem = item;
        bc.onAfterUseCard();

    }, false};
}

Action Actions::UpgradeAllCardsInHand() {
    return {[=] (BattleContext &bc) {
        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            bc.cards.hand[i].upgrade();
        }
    }};
}

Action Actions::EssenceOfDarkness(int darkOrbsPerSlot) {
    return {[=] (BattleContext &bc) {
        // EssenceOfDarknessAction.update: while the orb bar still holds an empty
        // slot, channel this.amount Dark orbs. It stops as soon as the bar is
        // full rather than pushing out the oldest orb.
        for (int i = 0; i < bc.player.orbSlots; ++i) {
            for (int j = 0; j < darkOrbsPerSlot; ++j) {
                if (!bc.player.hasEmptyOrb()) {
                    return;
                }
                bc.addToTop(Actions::ChannelOrb(Orb::DARK));
            }
        }
    }};
}

Action Actions::IncreaseOrbSlots(int count) {
    return {[=] (BattleContext &bc) {
        bc.player.increaseOrbSlots(count);
        // Losing a slot drops the newest orb without evoking it. In the vanilla
        // game only the full-bar channel push-out evokes; slot loss does not.
        while (static_cast<int>(bc.player.orbs.size()) > bc.player.orbSlots) {
            bc.player.orbs.remove(bc.player.orbs.size() - 1);
        }
    }};
}

Action Actions::SuicideAction(int monsterIdx, bool triggerRelics) {
    return {[=] (BattleContext &bc) {
        auto &m = bc.monsters.arr[monsterIdx];
        if (triggerRelics) {
            if (m.isAlive()) {
                m.damage(bc, m.curHp);
            }
        } else {
            bc.monsters.arr[monsterIdx].suicideAction(bc);
        }
    }};
}

Action Actions::PoisonLoseHpAction(int targetIdx, int amount) {
    return {[targetIdx, amount](BattleContext &bc) {
        if (bc.outcome != Outcome::UNDECIDED) {
            return;
        }
        auto &monster = bc.monsters.arr[targetIdx];
        if (!monster.isDeadOrEscaped() && monster.curHp > 0) {
            int hpLoss = std::max(0, amount);
            if (monster.hasStatus<MS::INTANGIBLE>()) {
                hpLoss = std::min(hpLoss, 1);
            }
            // Poison is HP loss: preserve block and avoid normal-attack triggers.
            // This helper applies Invincible and the existing HP-loss/death hooks.
            if (hpLoss > 0) monster.damageUnblockedHelper(bc, hpLoss);
        }
        // Death can remove powers (e.g. the Awakened One's first phase).
        if (monster.hasStatus<MS::POISON>()) {
            monster.decrementStatus<MS::POISON>();
        }
    }};
}

Action Actions::RemovePlayerDebuffs() {
    return {[] (BattleContext &bc) {
        bc.player.removeDebuffs();
    }};
}

Action Actions::UpgradeRandomCardAction() {
    return {[] (BattleContext &bc) {
        fixed_list<int,10> upgradeableHandIdxs;
        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            if (bc.cards.hand[i].canUpgrade()) {
                upgradeableHandIdxs.push_back(i);
            }
        }

        if (upgradeableHandIdxs.empty()) {
            return;
        }

        java::Collections::shuffle(
                upgradeableHandIdxs.begin(),
                upgradeableHandIdxs.end(),
                java::Random(bc.shuffleRng.randomLong())
        );

        const auto upgradeIdx = upgradeableHandIdxs[0];
        bc.cards.hand[upgradeIdx].upgrade();
    }};
}

Action Actions::CodexAction() {
    return {[] (BattleContext &bc) {
        bc.inputState = InputState::CARD_SELECT;
        bc.cardSelectInfo.cardSelectTask = CardSelectTask::CODEX;
        bc.cardSelectInfo.codexCards() =
                generateDiscoveryCards(bc.cardRandomRng, CharacterClass::IRONCLAD, CardType::INVALID);
    }};
}

Action Actions::ExhaustMany(int limit) {
    return {[=] (BattleContext &bc) {
        if (bc.cards.cardsInHand == 0) return;
        bc.inputState = InputState::CARD_SELECT;
        bc.cardSelectInfo.cardSelectTask = CardSelectTask::EXHAUST_MANY;
        bc.cardSelectInfo.pickCount = limit;
    }};
}

Action Actions::GambleAction() {
    return {[] (BattleContext &bc) {
        if (bc.cards.cardsInHand == 0) return;
        bc.inputState = InputState::CARD_SELECT;
        bc.cardSelectInfo.cardSelectTask = CardSelectTask::GAMBLE;
    }};
}

Action Actions::ToolboxAction() {
    return {[] (BattleContext &bc) {
        bc.inputState = InputState::CARD_SELECT;
        bc.cardSelectInfo.cardSelectTask = CardSelectTask::DISCOVERY;
        bc.cardSelectInfo.discovery_CopyCount() = 1;
        bc.cardSelectInfo.discoveryZeroCost = false;
        bc.cardSelectInfo.discoveryFrameRng = false;
        bc.cardSelectInfo.discovery_Cards() =
                generateDiscoveryCards(bc.cardRandomRng, bc.player.cc, CardType::STATUS); // status is mapped to colorless
    }};
}

Action Actions::DualityAction() {
    return {[](BattleContext &bc) {
        bc.player.buff<PS::DEXTERITY>(1);
        bc.player.debuff<PS::LOSE_DEXTERITY>(1);
    }};
}

Action Actions::ApotheosisAction() {
    return {[](BattleContext &bc) {

        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            auto &c = bc.cards.hand[i];
            if (c.canUpgrade()) {
                c.upgrade();
            }
        }

        for (auto &c : bc.cards.drawPile) {
            if (c.canUpgrade()) {
                c.upgrade();
            }
        }

        for (auto &c : bc.cards.discardPile) {
            if (c.canUpgrade()) {
                c.upgrade();
            }
        }

        for (auto &c : bc.cards.exhaustPile) {
            if (c.canUpgrade()) {
                c.upgrade();
            }
        }
    }};
}

Action Actions::DropkickAction(int targetIdx, int damage) {
    return {[=](BattleContext &bc) {
        if (bc.monsters.arr[targetIdx].isTargetable() && bc.monsters.arr[targetIdx].hasStatus<MS::VULNERABLE>()) {
            bc.addToTop(Actions::DrawCards(1));
            bc.addToTop(Actions::GainEnergy(1));
        }

        bc.addToTop(Actions::AttackEnemy(targetIdx, damage));
    }};
}

Action Actions::EnlightenmentAction(bool upgraded) {
    return {[=] (BattleContext &bc) {
        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            auto &c = bc.cards.hand[i];
            if (c.costForTurn > 1) {
                c.costForTurn = 1;
            }
            // Java EnlightenmentAction lowers only cost here; a turn cost of 0 (Corruption) stays 0.
            if (upgraded && c.cost > 1) {
                c.cost = 1;
            }
        }
    }};
}

Action Actions::EntrenchAction() {
    return {[=] (BattleContext &bc) {
        bc.player.gainBlock(bc, bc.player.block);
    }};
}

Action Actions::FeedAction(int idx, int damage, bool upgraded) {
    return {[=] (BattleContext &bc) {
        auto &m = bc.monsters.arr[idx];
        if (m.isDeadOrEscaped()) {
            return;
        }
        bc.monsters.arr[idx].attacked(bc, damage);

        const bool effectTriggered = !m.hasStatus<MS::MINION>()
                && !m.isAlive()
                && !m.isHalfDead()
                && !(m.hasStatus<MS::REGROW>() && bc.monsters.monstersAlive > 0);

        if (effectTriggered) {
            bc.player.increaseMaxHp(bc, upgraded ? 4 : 3);
        }

        bc.checkCombat();
    }};
}

Action Actions::FiendFireAction(int targetIdx, int calculatedDamage) {
    return {[=] (BattleContext &bc) {
        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            bc.addToTop(Actions::AttackEnemy(targetIdx, calculatedDamage));
        }

        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            bc.addToTop( Actions::ExhaustRandomCardInHand(1, true) );
        }
    }};
}

Action Actions::HandOfGreedAction(int idx, int damage, bool upgraded) {
    return {[=] (BattleContext &bc) {
        auto &m = bc.monsters.arr[idx];
        if (m.isDeadOrEscaped()) {
            return;
        }
        bc.monsters.arr[idx].attacked(bc, damage);

        const bool effectTriggered = !m.hasStatus<MS::MINION>()
                && !m.isAlive()
                && !m.isHalfDead()
                && !(m.hasStatus<MS::REGROW>() && bc.monsters.monstersAlive > 0);

        if (effectTriggered) {
            bc.player.gainGold(bc, upgraded ? 25 : 20);
        }

        bc.checkCombat();
    }};
}

Action Actions::LimitBreakAction() {
    return {[] (BattleContext &bc) {
        const int amount = bc.player.getStatus<PS::STRENGTH>();
        if (amount < 0) {
            bc.addToTop(Actions::DebuffPlayer<PS::STRENGTH>(amount, false));
        } else if (amount > 0) {
            bc.addToTop(Actions::BuffPlayer<PS::STRENGTH>(amount));
        }
    }};
}

Action Actions::ReaperAction(DamageMatrix damageMatrix) {
    return {[=] (BattleContext &bc) {

        int healAmount = 0;
        for (int i = 0; i < bc.monsters.monsterCount; ++i) {
            auto &m = bc.monsters.arr[i];
            if (m.isDeadOrEscaped()) {
                continue;
            }
            int preDamageHp = m.curHp;
            m.attacked(bc, damageMatrix[i]);
            healAmount += preDamageHp-m.curHp;
        }

        if (healAmount > 0) {
            bc.addToBot( Actions::HealPlayer(healAmount) );
        }

        // VampireDamageAllEnemiesAction clears post-combat actions after a
        // kill; HealPlayer is retained like Java HealAction.
        bc.checkCombat();
    }};
}

Action Actions::RitualDaggerAction(int idx, int damage) {
    return {[=] (BattleContext &bc) {
        auto &m = bc.monsters.arr[idx];
        if (m.isDeadOrEscaped()) {
            return;
        }
        bc.monsters.arr[idx].attacked(bc, damage);

        const bool shouldUpgrade = !m.hasStatus<MS::MINION>()
                                   && !m.isAlive()
                                   && !m.halfDead;
        if (shouldUpgrade) {
            auto &c = bc.curCardQueueItem.card;
            const auto upgradeAmt = c.isUpgraded() ? 5 : 3;

            if (bc.curCardQueueItem.purgeOnUse) {
                bc.cards.findAndUpgradeSpecialData(c.uniqueId, upgradeAmt);
            }
            c.specialData += upgradeAmt;
        }

        bc.checkCombat();
    }};
}

Action Actions::SecondWindAction(int blockPerCard) {
    return {[=] (BattleContext &bc) {
        int cardIdxsToExhaust[10];
        int toExhaustCount = 0;

        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            const auto &c = bc.cards.hand[i];
            if (c.getType() != CardType::ATTACK) {
                cardIdxsToExhaust[toExhaustCount++] = i;
                bc.addToTop( Actions::GainBlock(blockPerCard) );
            }
        }

        for (int i = 0; i < toExhaustCount; ++i) {
            const auto handIdx = cardIdxsToExhaust[i];
            const auto &c = bc.cards.hand[handIdx];

            bc.addToTop( Actions::ExhaustSpecificCardInHand(handIdx, c.uniqueId) );
        }
    }};
}

Action Actions::SeverSoulExhaustAction() {
    return {[=] (BattleContext &bc) {
        // Original ExhaustAllNonAttackAction pushes each selected card to the
        // top. Exhaust in reverse hand order before damage and UseCardAction,
        // so exhaust callbacks precede the Heart's onAfterUseCard damage.
        for (int i = 0; i < bc.cards.cardsInHand; ++i) {
            const auto &c = bc.cards.hand[i];
            if (c.getType() != CardType::ATTACK) {
                bc.addToTop( Actions::ExhaustSpecificCardInHand(i, c.getUniqueId()) );
            }
        }
    }};
}

Action Actions::SwordBoomerangAction(int baseDamage) { // pretty hacky until I can figure out a better solution
    return {[=](BattleContext &bc) {
        const static CardInstance swordBoomerang {CardId::SWORD_BOOMERANG};
        const auto idx = bc.monsters.getRandomMonsterIdx(bc.cardRandomRng, true);
        if (idx == -1) {
            return;
        }

        int damage = bc.calculateCardDamage(swordBoomerang, idx, baseDamage);
        bc.addToTop(Actions::AttackEnemy(idx, damage));
    }};
}

Action Actions::SpotWeaknessAction(int target, int strength) {
    return {[=](BattleContext &bc) {
        // Java SpotWeaknessAction: a null target (or one without an attack intent) gives nothing.
        if (target >= 0 && target < bc.monsters.monsterCount && bc.monsters.arr[target].isAttacking()) {
            bc.player.buff<PS::STRENGTH>(strength);
        }
    }};
}

Action Actions::WhirlwindAction(DamageMatrix damageMatrix, int energy, bool freeToPlay) {
    return {[=] (BattleContext &bc) {
        // WhirlwindAction.update(): the hits are queued first, then the pool is
        // emptied unless freeToPlayOnce was set (the bytecode guard at the end of
        // update() skips EnergyManager.use entirely when the card was free).
        if (!freeToPlay) {
            bc.player.useEnergy(bc.player.energy);
        }

        const auto effectAmount = energy + (bc.player.hasRelic<R::CHEMICAL_X>() ? 2 : 0);
        // Whirlwind appends its hits after already queued on-use effects.
        for (int i = 0; i < effectAmount; ++i) {
            bc.addToBot(Actions::AttackAllEnemy(damageMatrix));
        }
    }};
}
