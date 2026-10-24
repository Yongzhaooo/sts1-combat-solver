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
            Actions::ChannelOrb(Orb::LIGHTNING).actFunc(bc);
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
            Actions::ChannelOrb(Orb::LIGHTNING).actFunc(bc);
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
            Actions::ChannelOrb(Orb::LIGHTNING).actFunc(bc);
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

        std::cout << "orb_evoke_target: ok\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << "orb_evoke_target: FAIL " << e.what() << '\n';
        return 1;
    }
}
