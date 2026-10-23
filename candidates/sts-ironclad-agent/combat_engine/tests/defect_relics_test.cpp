#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include <iostream>
#include <stdexcept>

using namespace sts;

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

namespace {
    // Player turn vs a single Cultist. Cracked Core's battle-start orb is cleared
    // per case so the bar only holds what the case channels.
    BattleContext freshBattle() {
        GameContext game(CharacterClass::DEFECT, 123, 0);
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        return battle;
    }

    void drain(BattleContext &battle) {
        battle.setState(InputState::EXECUTING_ACTIONS);
        for (int i = 0; i < 32 && !battle.actionQueue.isEmpty(); ++i) {
            battle.actionQueue.popFront()(battle);
        }
    }

    // Same fight, but the relic is in the GameContext before init, so the
    // battle-start and pre-battle triggers fire the way they do in a real run.
    BattleContext freshBattleWithRelic(RelicId relic) {
        GameContext game(CharacterClass::DEFECT, 123, 0);
        game.regainControlAction = [](GameContext&) {};
        game.relics.add(RelicInstance{relic, 0});
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        return battle;
    }
}

int main() {
    try {
    {
        // GoldPlatedCables: TriggerEndOfTurnOrbsAction runs the whole list, then
        // the relic triggers orbs.get(0) once more. Index 0 is the orb evokeOrb
        // removes first, so the oldest Lightning passive settles twice.
        BattleContext bc = freshBattle();
        bc.player.setHasRelic<R::GOLD_PLATED_CABLES>(true);
        bc.player.orbs.clear();
        bc.player.channelOrb(Orb::LIGHTNING);
        bc.player.channelOrb(Orb::FROST);
        const int hp = bc.monsters.arr[0].curHp;
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
        drain(bc);
        check(hp - bc.monsters.arr[0].curHp == 6,
              "Cables should fire the oldest lightning passive a second time");
        check(bc.player.block == 2, "the frost passive should still fire once");
    }
    {
        // EmotionChip: wasHPLost arms the relic and atTurnStart spends the pulse.
        BattleContext bc = freshBattle();
        bc.player.setHasRelic<R::EMOTION_CHIP>(true);
        bc.player.orbs.clear();
        bc.player.channelOrb(Orb::LIGHTNING);
        const int hp = bc.monsters.arr[0].curHp;

        bc.player.applyStartOfTurnRelics(bc);
        drain(bc);
        check(bc.monsters.arr[0].curHp == hp,
              "Emotion Chip should not fire before any hp loss");

        bc.player.attacked(bc, 0, 5);
        drain(bc);
        bc.player.applyStartOfTurnRelics(bc);
        drain(bc);
        check(hp - bc.monsters.arr[0].curHp == 3,
              "Emotion Chip should trigger the lightning passive once");

        bc.player.applyStartOfTurnRelics(bc);
        drain(bc);
        check(hp - bc.monsters.arr[0].curHp == 3,
              "Emotion Chip should pulse only once per hp loss");
    }
    {
        // ImpulseAction honours Cables too: a Plasma at index 0 pays its start
        // passive twice (its own trigger plus the relic's extra trigger).
        BattleContext bc = freshBattle();
        bc.player.setHasRelic<R::EMOTION_CHIP>(true);
        bc.player.setHasRelic<R::GOLD_PLATED_CABLES>(true);
        bc.player.orbs.clear();
        bc.player.channelOrb(Orb::FUSION);
        bc.player.channelOrb(Orb::LIGHTNING);
        bc.player.attacked(bc, 0, 5);
        drain(bc);
        const int energy = bc.player.energy;
        const int hp = bc.monsters.arr[0].curHp;
        bc.player.applyStartOfTurnRelics(bc);
        drain(bc);
        check(bc.player.energy == energy + 2,
              "Cables should repeat the plasma start passive");
        check(hp - bc.monsters.arr[0].curHp == 3,
              "the lightning orb should still fire its passive once");
    }

    {
        // Cables must trigger orbs.get(0) in a *separate* deferred action, so the
        // first pass's damage resolves first and a monster it kills is already
        // out of the random-target pool when the relic rolls.
        //
        // Floor 39 (seed -1208793710291554246) is the live case: the relic rolled
        // inside the original action, monstersAlive was still 4 instead of 3, and
        // the draw landed on the corpse's neighbour, so the engine predicted
        // Exploder1 21 / Spiker 38 against the game's 24 / 35.
        //
        // The observable contract is the queue: the end-of-turn trigger leaves the
        // Cables hit behind as its own action instead of having already rolled.
        BattleContext bc = freshBattle();
        bc.player.setHasRelic<R::GOLD_PLATED_CABLES>(true);
        bc.player.orbs.clear();
        bc.player.channelOrb(Orb::LIGHTNING);

        const int hp = bc.monsters.arr[0].curHp;
        const int queuedBefore = bc.actionQueue.size;
        const int rollsBefore = bc.cardRandomRng.counter;
        Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
        // One queued lightning hit for the main pass plus one for the relic.
        check(bc.actionQueue.size - queuedBefore == 2,
              "Cables must defer its extra passive as its own queued action");
        // Only the main pass may roll here; the relic's target comes later, when
        // the queue reaches its own action and the first hit has resolved.
        check(bc.cardRandomRng.counter - rollsBefore == 1,
              "the end-of-turn trigger must not roll the Cables target up front");
        check(bc.monsters.arr[0].curHp == hp,
              "nothing should resolve while the end-of-turn trigger is queued");

        drain(bc);
        check(hp - bc.monsters.arr[0].curHp == 6,
              "both lightning passives should land once the queue drains");
    }
    {
        // CrackedCore.atBattleStart channels one Lightning. It is the starter relic,
        // so it fires in every fresh battle above; assert it rather than assume it.
        BattleContext bc = freshBattle();
        check(bc.player.hasRelic<R::CRACKED_CORE>(), "the starter relic should be Cracked Core");
        check(bc.player.orbs.size() == 1, "Cracked Core should channel one orb");
        check(bc.player.orbs[0].type == Orb::LIGHTNING,
              "Cracked Core should channel a Lightning orb");
    }
    {
        // DataDisk.atBattleStart applies one Focus. Battle init is the point where
        // the relic fires, so the fresh battle carries it before any card.
        BattleContext bc = freshBattleWithRelic(R::DATA_DISK);
        check(bc.player.focus == 1, "Data Disk should start the fight with 1 Focus");
    }
    {
        // NuclearBattery.atPreBattle channels one Plasma; SymbioticVirus.atPreBattle
        // channels one Dark in its place. Cracked Core adds a Lightning to both.
        BattleContext battery = freshBattleWithRelic(R::NUCLEAR_BATTERY);
        check(battery.player.orbs.size() == 2, "Nuclear Battery should add one orb");
        check(battery.player.orbs[1].type == Orb::FUSION,
              "Nuclear Battery should channel a Plasma orb");

        BattleContext virus = freshBattleWithRelic(R::SYMBIOTIC_VIRUS);
        check(virus.player.orbs.size() == 2, "Symbiotic Virus should add one orb");
        check(virus.player.orbs[1].type == Orb::DARK,
              "Symbiotic Virus should channel a Dark orb");
    }
    {
        // RunicCapacitor.atTurnStart raises the slots by 3 on the first player turn
        // only. The engine applies them at battle start, which is the same fight.
        BattleContext bc = freshBattleWithRelic(R::RUNIC_CAPACITOR);
        check(bc.player.orbSlots == 6, "Runic Capacitor should raise slots from 3 to 6");
    }
    {
        // Inserter.atTurnStart counts turns and adds a slot when the counter reaches
        // 2, so it fires on the second player turn and every second turn after.
        BattleContext bc = freshBattleWithRelic(R::INSERTER);
        const int startSlots = bc.player.orbSlots;
        bc.afterMonsterTurns();
        drain(bc);
        check(bc.player.orbSlots == startSlots,
              "Inserter must not fire on the first player turn");
        bc.afterMonsterTurns();
        drain(bc);
        check(bc.player.orbSlots == startSlots + 1,
              "Inserter should add one slot on the second player turn");
        bc.afterMonsterTurns();
        drain(bc);
        check(bc.player.orbSlots == startSlots + 1,
              "Inserter must not fire twice in a row");
        bc.afterMonsterTurns();
        drain(bc);
        check(bc.player.orbSlots == startSlots + 2,
              "Inserter should fire again on the fourth player turn");
    }
    {
        // FrozenCore.onPlayerEndTurn channels a Frost orb when a slot is free, and
        // does nothing when the bar is full.
        BattleContext empty = freshBattleWithRelic(R::FROZEN_CORE);
        empty.player.orbs.clear();
        empty.callEndOfTurnActions();
        check(empty.player.orbs.size() == 1 && empty.player.orbs[0].type == Orb::FROST,
              "Frozen Core should channel one Frost into an empty bar");

        BattleContext full = freshBattleWithRelic(R::FROZEN_CORE);
        full.player.orbs.clear();
        for (int i = 0; i < full.player.orbSlots; ++i) full.player.channelOrb(Orb::LIGHTNING);
        const int before = full.player.orbs.size();
        full.callEndOfTurnActions();
        check(full.player.orbs.size() == before,
              "Frozen Core must stay quiet when every slot is taken");
    }
    {
        // EmotionChip.atTurnStart runs one extra start-of-turn and end-of-turn pass
        // over every orb, but only in the turn after the player lost hp.
        BattleContext bc = freshBattleWithRelic(R::EMOTION_CHIP);
        bc.player.orbs.clear();
        bc.player.channelOrb(Orb::LIGHTNING);
        const int hp = bc.monsters.arr[0].curHp;

        bc.afterMonsterTurns();
        drain(bc);
        check(bc.monsters.arr[0].curHp == hp,
              "Emotion Chip must not pulse on a turn with no hp loss");

        bc.player.hpWasLost(bc, 1);
        bc.afterMonsterTurns();
        drain(bc);
        check(hp - bc.monsters.arr[0].curHp >= 1,
              "Emotion Chip should pulse after hp was lost");

        const int afterPulse = bc.monsters.arr[0].curHp;
        bc.afterMonsterTurns();
        drain(bc);
        check(bc.monsters.arr[0].curHp == afterPulse,
              "the pulse is spent, so the next turn runs the passives once only");
    }

    std::cout << "Defect relic behaviors hold\n";
    return 0;
    } catch (const std::exception &e) {
        std::cout << "FAILED: " << e.what() << std::endl;
        return 1;
    }
}
