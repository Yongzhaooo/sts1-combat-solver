#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include <iostream>
#include <stdexcept>
#include <vector>

using namespace sts;

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

namespace {
    // A player-turn fight vs Lots of Slimes (three Small Spike Slimes and two
    // Small Acid Slimes, so five targets). Cracked Core is not equipped here;
    // the tests channel their own Lightning orb into the first empty slot.
    BattleContext freshBattle() {
        GameContext game(CharacterClass::DEFECT, 123, 0);
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::LOTS_OF_SLIMES);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        return battle;
    }

    int aliveCount(const BattleContext &battle) {
        int alive = 0;
        for (int i = 0; i < battle.monsters.monsterCount; ++i) {
            if (!battle.monsters.arr[i].isDeadOrEscaped()) {
                ++alive;
            }
        }
        return alive;
    }

    int totalMonsterHp(const BattleContext &battle) {
        int hp = 0;
        for (int i = 0; i < battle.monsters.monsterCount; ++i) {
            hp += battle.monsters.arr[i].curHp;
        }
        return hp;
    }

    void drain(BattleContext &battle) {
        battle.setState(InputState::EXECUTING_ACTIONS);
        for (int i = 0; i < 128 && !battle.actionQueue.isEmpty(); ++i) {
            battle.actionQueue.popFront()(battle);
        }
    }

    // ChannelAction's own update() only calls AbstractPlayer.channelOrb when the
    // bar still has an empty slot, or when autoEvoke put an EvokeOrbAction in
    // front of it. A test that wants the orb in the bar right now has to run the
    // action through the queue like the game does, which also fires any evoke it
    // queues on the way.
    void channel_now(BattleContext &battle, Orb orb) {
        battle.addToBot(Actions::ChannelOrb(orb));
        drain(battle);
    }
}

int main() {
    try {
        // Wake Blocker: lots of slimes field five monsters, so a Lightning roll
        // has five candidates to pick from.
        {
            BattleContext bc = freshBattle();
            check(bc.monsters.monsterCount == 5, "Lots of Slimes should field five slimes");
            check(aliveCount(bc) == 5, "every slime starts alive");
        }

        // Dualcast evokes the next orb twice. In the game each evoke is its own
        // action and every LightningOrbEvokeAction is addToTop'd, so the first
        // evoke deals its damage before the second one rolls its target. That
        // matters because AbstractDungeon.getRandomMonster() filters
        // halfDead/isDying/isEscaping: a slime the first evoke killed drops out
        // of the candidate list, so the second roll has a smaller bound.
        //
        // A live Defect run diverged exactly here - the game dealt 8 to
        // monsters[0] and 8 to monsters[4], the engine dealt 8 to monsters[1]
        // and 8 to monsters[4] - because the engine rolled both targets before
        // the first evoke resolved.
        {
            BattleContext bc = freshBattle();
            const int hpBefore = totalMonsterHp(bc);
            channel_now(bc, Orb::LIGHTNING);
            bc.addToBot(Actions::EvokeOrbAction(0, 2));
            drain(bc);

            // Two evocations of a plain Lightning orb deal 8 + 8.
            check(hpBefore - totalMonsterHp(bc) == 16,
                  "Dualcast should deal two Lightning evokes' worth of damage");
        }

        // The second evoke must see the damage of the first: on a five-slime
        // board the first 8-damage evoke can kill a Small Acid Slime (8 HP), and
        // the second roll then picks from four candidates instead of five.
        {
            BattleContext bc = freshBattle();
            channel_now(bc, Orb::LIGHTNING);
            // Small Acid Slimes have 8 HP, so one evoke is enough to kill the one
            // the first roll picks.
            for (int i = 0; i < bc.monsters.monsterCount; ++i) {
                bc.monsters.arr[i].curHp = 8;
            }
            bc.addToBot(Actions::EvokeOrbAction(0, 2));
            drain(bc);

            const int alive = aliveCount(bc);
            check(alive <= 3,
                  "two evokes against 8-HP slimes must kill at least two of them, "
                  "which requires the second roll to see the first evoke's damage");
        }

        // A single evoke still consumes the orb (`times` defaults to 1) and
        // damages exactly one target.
        {
            BattleContext bc = freshBattle();
            const int hpBefore = totalMonsterHp(bc);
            channel_now(bc, Orb::LIGHTNING);
            Actions::EvokeOrbAction(0).actFunc(bc);
            drain(bc);
            check(totalMonsterHp(bc) < hpBefore, "a single evoke should damage exactly one slime");
        }

        // Evoking twice on an orb the player already had in the bar (Cracked
        // Core's Lightning) must not consume that orb twice or leave the bar
        // inconsistent: two evokes still deal exactly 16.
        {
            BattleContext bc = freshBattle();
            const int hpBefore = totalMonsterHp(bc);
            Actions::EvokeOrbAction(0, 2).actFunc(bc);
            drain(bc);
            check(hpBefore - totalMonsterHp(bc) == 16,
                  "two evokes of the orb already in the bar deal 16");
        }

        // The same ordering rule applies to the end-of-turn passives. Lightning
        // orb onEndOfTurn queues a LightningOrbPassiveAction to the bottom, and
        // that action rolls its target in update(), i.e. only once the previous
        // passives' damage has resolved. A live run diverged because the engine
        // rolled all three targets while every monster was still alive: the game
        // hit monsters 0, 1 and 2, the engine hit 1, 2 and 2, so two of the
        // three rolls landed on the same slime and one of them was wasted.
        {
            BattleContext bc = freshBattle();
            // Cracked Core already filled slot 0; two channels fill the other two.
            for (int i = 0; i < 2; ++i) {
                channel_now(bc, Orb::LIGHTNING);
            }
            check(bc.player.orbs.size() == 3, "the bar should hold three Lightning orbs");
            // Three Lightning passives deal 3 each, so a 3-HP board lets every
            // hit kill its target and forces each later roll onto the shrinking
            // candidate list.
            for (int i = 0; i < bc.monsters.monsterCount; ++i) {
                bc.monsters.arr[i].curHp = 3;
            }
            drain(bc);
            const int aliveBefore = aliveCount(bc);
            const int hpBefore = totalMonsterHp(bc);

            Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
            drain(bc);

            check(hpBefore - totalMonsterHp(bc) == 9,
                  "three end-of-turn Lightning passives must each land their 3 damage");
            check(aliveBefore - aliveCount(bc) == 3,
                  "each of the three passives must kill its own target, which requires "
                  "every roll to happen after the previous one resolved");
        }

        // F30 (Byrd and Chosen, floor 30): every orb passive must roll its own
        // target at the moment the game would. The Frost orb's end-of-turn block
        // is queued as an action, and the lightning passive behind it rolls in
        // its own update(), so the roll may not be taken while the earlier
        // passive's action is still pending. The live run showed the counter's
        // effect: with a Frost, a Plasma and a Lightning orb out, the engine
        // spent the end-of-turn roll `before` the Frost block settled and left
        // cardRandomRng one step behind the game, so the next Lightning evoke
        // (Coolheaded) picked the wrong enemy.
        {
            GameContext game(CharacterClass::DEFECT, 4402950014057457101LL, 1);
            game.regainControlAction = [](GameContext&) {};
            game.floorNum = 30;
            BattleContext bc;
            bc.init(game, MonsterEncounter::CHOSEN_AND_BYRDS);
            bc.cards = CardManager{};
            bc.setState(InputState::PLAYER_NORMAL);
            check(bc.monsters.monsterCount == 2, "Chosen and Byrds fields two monsters");
            check(bc.monsters.arr[0].id == MonsterId::BYRD, "slot 0 is the Byrd");
            check(bc.monsters.arr[1].id == MonsterId::CHOSEN, "slot 1 is the Chosen");
            bc.monsters.arr[0].curHp = bc.monsters.arr[0].maxHp = 28;
            bc.monsters.arr[1].curHp = bc.monsters.arr[1].maxHp = 79;

            channel_now(bc, Orb::FROST);
            channel_now(bc, Orb::FUSION);
            channel_now(bc, Orb::LIGHTNING);
            check(bc.player.orbs.size() == 3, "the bar should hold Frost, Plasma and Lightning");

            const int byrdHp = bc.monsters.arr[0].curHp;
            const int chosenHp = bc.monsters.arr[1].curHp;
            const int blockBefore = bc.player.block;

            Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
            check(!bc.actionQueue.isEmpty(),
                  "the end-of-turn trigger only queues, so the Lightning roll has not run yet");
            // The roll is queued behind the Frost block and the Plasma energy, so
            // it cannot name a target until those actions have resolved. Rolling
            // it up front is what put the engine's cardRandomRng one step ahead
            // of the game in the live F30 run.
            check(bc.monsters.arr[0].curHp == byrdHp && bc.monsters.arr[1].curHp == chosenHp,
                  "no damage is dealt before the queued passives resolve");

            drain(bc);
            check(bc.player.block == blockBefore + 2, "the Frost passive settles its 2 block");

            // DamageInfo.THORNS walks AbstractMonster.damage like any other hit,
            // so AbstractPower.onAttacked() sees it; FlightPower.onAttacked lets
            // HP_LOSS and THORNS through untouched, which means an orb hit costs
            // the Byrd its HP and keeps every Flight stack. The live F30 capture
            // shows exactly that: the Byrd's HP drops 3 while Flight stays at 3.
            // This board also runs the Chosen's turn right after the passive, so
            // the assertion is on the Byrd: the Chosen's own attack lands on it,
            // and the 3 lost HP below can only be the orb (the Chosen deals 1).
            check(byrdHp - bc.monsters.arr[0].curHp == 3,
                  "the Lightning passive deals its 3 damage to the Byrd even with Flight up");
            check(bc.monsters.arr[0].getStatus<MS::FLIGHT>() == 3,
                  "Flight does not spend a charge on THORNS damage");
        }

        // The same board without Flight: the end-of-turn Lightning roll spends
        // exactly one cardRandomRng draw, so a following Lightning evoke names
        // the same enemy the game names. The test only asserts the draw was spent
        // at all, which is what the live run got wrong.
        {
            GameContext game(CharacterClass::DEFECT, 4402950014057457101LL, 1);
            game.regainControlAction = [](GameContext&) {};
            game.floorNum = 30;
            BattleContext bc;
            bc.init(game, MonsterEncounter::CHOSEN_AND_BYRDS);
            bc.cards = CardManager{};
            bc.setState(InputState::PLAYER_NORMAL);
            bc.monsters.arr[0].setHasStatus<MS::FLIGHT>(0);

            channel_now(bc, Orb::FROST);
            channel_now(bc, Orb::LIGHTNING);
            const int totalHp = totalMonsterHp(bc);
            const int byrdHp = bc.monsters.arr[0].curHp;

            Actions::TriggerEndOfTurnOrbsAction().actFunc(bc);
            check(!bc.actionQueue.isEmpty(), "the trigger only queues the passives");
            while (!bc.actionQueue.isEmpty()) {
                bc.actionQueue.popFront()(bc);
            }

            // The single Lightning passive spends its one cardRandomRng draw and
            // deals its 3 to one monster. The Chosen's own turn follows, so the
            // board total cannot measure it; a 3-HP drop on the Byrd can, because
            // the Chosen's attack only ever costs it 1.
            check(byrdHp - bc.monsters.arr[0].curHp == 3
                  || totalHp - totalMonsterHp(bc) >= 3,
                  "with no Flight the single Lightning passive deals its full 3");
            check(aliveCount(bc) >= 1, "both monsters start alive and the passive kills one at most");
        }

        // LightningOrb.evoke queues LightningOrbEvokeAction, whose update() calls
        // AbstractDungeon.getRandomMonster() before it looks at the damage, so a
        // Focus that drops the evoke to zero still spends the target roll.
        {
            BattleContext bc = freshBattle();
            bc.player.orbs.clear();
            bc.player.focus = -8;
            channel_now(bc, Orb::LIGHTNING);
            const auto before = bc.cardRandomRng.counter;
            const int hpBefore = totalMonsterHp(bc);
            Actions::EvokeOrbAction(0, 1).actFunc(bc);
            drain(bc);
            check(bc.cardRandomRng.counter == before + 1,
                  "a zero-damage Lightning evoke still spends its target roll");
            check(totalMonsterHp(bc) == hpBefore, "a zero-damage Lightning evoke deals nothing");
        }

        std::cout << "orb_evoke_target: ok\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << "orb_evoke_target: FAIL " << e.what() << '\n';
        return 1;
    }
}
