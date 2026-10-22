// The printed numbers of every Defect card, base and upgraded.
//
// The expected values come from the card classes in the game jar: the damage,
// block and magic numbers are the literals their constructors assign to
// baseDamage, baseBlock and baseMagicNumber, and each upgrade() either adds an
// increment (upgradeDamage, upgradeBlock, upgradeMagicNumber) or sets an
// absolute cost (upgradeBaseCost). Cards whose upgrade changes no number still
// have to keep their base numbers, so both forms are checked for every card.
//
// Every case gets its own battle, so orbs, focus and statuses never leak
// between cards.
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"

#include <iostream>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

using namespace sts;

namespace {

    void check(bool value, const std::string &why) {
        if (!value) {
            throw std::runtime_error(why);
        }
    }

    std::string name(CardId id) {
        return cardEnumStrings[static_cast<int>(id)];
    }

    std::string tagged(CardId id, int upgrades) {
        return name(id) + (upgrades ? "+" : "");
    }

    // How many entries the power order holds for one status. An applied power
    // has exactly one, which is what makes its effect run.
    int powerCount(const Player &player, PlayerStatus which) {
        int count = 0;
        for (const auto &ref : player.powerOrder) {
            if (ref.status == which) {
                ++count;
            }
        }
        return count;
    }

    // A fresh player turn against a single Cultist: empty hand and piles, the
    // Cracked Core's Lightning orb, three energy.
    BattleContext freshBattle(MonsterEncounter encounter = MonsterEncounter::CULTIST) {
        GameContext game(CharacterClass::DEFECT, 123, 0);
        game.regainControlAction = [](GameContext &) {};
        BattleContext battle;
        battle.init(game, encounter);
        battle.cards = CardManager{};
        battle.setState(InputState::PLAYER_NORMAL);
        return battle;
    }

    // A battle with no orbs and no focus, so a card's own numbers are the only
    // thing that moves.
    BattleContext emptyBattle() {
        BattleContext battle = freshBattle();
        battle.player.orbs.clear();
        return battle;
    }

    void settle(BattleContext &battle) {
        for (int i = 0; i < 64 && !battle.actionQueue.isEmpty(); ++i) {
            battle.actionQueue.popFront()(battle);
        }
    }

    void addDrawPile(BattleContext &battle, int count) {
        for (int i = 0; i < count; ++i) {
            battle.cards.createTempCardInDrawPile(i, CardInstance(CardId::STRIKE_BLUE));
        }
    }

    void addDiscardPile(BattleContext &battle, int count) {
        for (int i = 0; i < count; ++i) {
            battle.cards.createTempCardInDiscard(CardInstance(CardId::STRIKE_BLUE));
        }
    }

    // Play a card from hand with a given amount of energy in the pool, then let
    // the queued actions finish. Energy is set first so that a test can watch
    // both what a card costs and what it gains.
    void playWith(BattleContext &battle, CardId id, int upgrades, int energy, int target = 0) {
        battle.cards.createTempCardInHand(CardInstance(id, upgrades > 0));
        const int idx = battle.cards.cardsInHand - 1;
        battle.player.energy = energy;
        search::Action action(search::ActionType::CARD, idx, target);
        if (!action.isValidAction(battle)) {
            throw std::runtime_error("card should be playable: " + tagged(id, upgrades)
                                     + " with " + std::to_string(battle.player.energy) + " energy in the pool");
        }
        action.execute(battle);
        settle(battle);
    }

    // The usual call: enough energy that no card is blocked by its cost.
    void play(BattleContext &battle, CardId id, int upgrades = 0, int target = 0) {
        playWith(battle, id, upgrades, 10, target);
    }

    int damageDealt(const BattleContext &battle) {
        return battle.monsters.arr[0].maxHp - battle.monsters.arr[0].curHp;
    }

    // One card's printed numbers as the game's classes report them. Zero means
    // the card prints no such number.
    struct Printed {
        CardId id;
        int cost, costUp;
        int damage, damageUp;
        int block, blockUp;
        int magic, magicUp;
    };

    // Every card useDefectCard implements, plus the two starter cards the shared
    // switch provides. A card added to the dispatch needs an entry here.
    const std::vector<Printed> printedNumbers {
        //  card                          cost      damage     block      magic
        { CardId::STRIKE_BLUE,            1, 1,     6, 9,      0, 0,      0, 0 },
        { CardId::DEFEND_BLUE,            1, 1,     0, 0,      5, 8,      0, 0 },
        { CardId::AGGREGATE,              1, 1,     0, 0,      0, 0,      4, 3 },
        { CardId::ALL_FOR_ONE,            2, 2,    10, 14,     0, 0,      0, 0 },
        { CardId::AMPLIFY,                1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::AUTO_SHIELDS,           1, 1,     0, 0,     11, 15,     0, 0 },
        { CardId::BALL_LIGHTNING,         1, 1,     7, 10,     0, 0,      1, 1 },
        { CardId::BARRAGE,                1, 1,     4, 6,      0, 0,      0, 0 },
        { CardId::BEAM_CELL,              0, 0,     3, 4,      0, 0,      1, 2 },
        { CardId::BIASED_COGNITION,       1, 1,     0, 0,      0, 0,      4, 5 },
        { CardId::BLIZZARD,               1, 1,     0, 0,      0, 0,      2, 3 },
        { CardId::BOOT_SEQUENCE,          0, 0,     0, 0,     10, 13,     0, 0 },
        { CardId::BUFFER,                 2, 2,     0, 0,      0, 0,      1, 2 },
        { CardId::BULLSEYE,               1, 1,     8, 11,     0, 0,      2, 3 },
        { CardId::CAPACITOR,              1, 1,     0, 0,      0, 0,      2, 3 },
        { CardId::CHAOS,                  1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::CHARGE_BATTERY,         1, 1,     0, 0,      7, 10,     0, 0 },
        { CardId::CHILL,                  0, 0,     0, 0,      0, 0,      1, 1 },
        { CardId::CLAW,                   0, 0,     3, 5,      0, 0,      2, 2 },
        { CardId::COLD_SNAP,              1, 1,     6, 9,      0, 0,      1, 1 },
        { CardId::COMPILE_DRIVER,         1, 1,     7, 10,     0, 0,      1, 1 },
        { CardId::CONSUME,                2, 2,     0, 0,      0, 0,      2, 3 },
        { CardId::COOLHEADED,             1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::CORE_SURGE,             1, 1,    11, 15,     0, 0,      1, 1 },
        { CardId::CREATIVE_AI,            3, 2,     0, 0,      0, 0,      1, 1 },
        { CardId::DARKNESS,               1, 1,     0, 0,      0, 0,      1, 1 },
        { CardId::DEFRAGMENT,             1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::DOOM_AND_GLOOM,         2, 2,    10, 14,     0, 0,      1, 1 },
        { CardId::DOUBLE_ENERGY,          1, 0,     0, 0,      0, 0,      0, 0 },
        { CardId::DUALCAST,               1, 0,     0, 0,      0, 0,      0, 0 },
        { CardId::ECHO_FORM,              3, 3,     0, 0,      0, 0,      0, 0 },
        { CardId::ELECTRODYNAMICS,        2, 2,     0, 0,      0, 0,      2, 3 },
        { CardId::EQUILIBRIUM,            2, 2,     0, 0,     13, 16,     1, 1 },
        { CardId::FISSION,                0, 0,     0, 0,      0, 0,      1, 1 },
        { CardId::FORCE_FIELD,            4, 4,     0, 0,     12, 16,     0, 0 },
        { CardId::FTL,                    0, 0,     5, 6,      0, 0,      3, 4 },
        { CardId::FUSION,                 2, 1,     0, 0,      0, 0,      1, 1 },
        { CardId::GENETIC_ALGORITHM,      1, 1,     0, 0,      1, 1,      2, 3 },
        { CardId::GLACIER,                2, 2,     0, 0,      7, 10,     2, 2 },
        { CardId::GO_FOR_THE_EYES,        0, 0,     3, 4,      0, 0,      1, 2 },
        { CardId::HEATSINKS,              1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::HELLO_WORLD,            1, 1,     0, 0,      0, 0,      0, 0 },
        { CardId::HOLOGRAM,               1, 1,     0, 0,      3, 5,      0, 0 },
        { CardId::HYPERBEAM,              2, 2,    26, 34,     0, 0,      3, 3 },
        { CardId::LEAP,                   1, 1,     0, 0,      9, 12,     0, 0 },
        { CardId::LOOP,                   1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::MACHINE_LEARNING,       1, 1,     0, 0,      0, 0,      1, 1 },
        { CardId::MELTER,                 1, 1,    10, 14,     0, 0,      0, 0 },
        { CardId::METEOR_STRIKE,          5, 5,    24, 30,     0, 0,      3, 3 },
        { CardId::MULTI_CAST,            -1, -1,    0, 0,      0, 0,      0, 0 },
        { CardId::OVERCLOCK,              0, 0,     0, 0,      0, 0,      2, 3 },
        { CardId::RAINBOW,                2, 2,     0, 0,      0, 0,      0, 0 },
        { CardId::REBOOT,                 0, 0,     0, 0,      0, 0,      4, 6 },
        { CardId::REBOUND,                1, 1,     9, 12,     0, 0,      0, 0 },
        { CardId::RECURSION,              1, 0,     0, 0,      0, 0,      0, 0 },
        { CardId::RECYCLE,                1, 0,     0, 0,      0, 0,      0, 0 },
        { CardId::REINFORCED_BODY,       -1, -1,    0, 0,      7, 9,      0, 0 },
        { CardId::REPROGRAM,              1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::RIP_AND_TEAR,           1, 1,     7, 9,      0, 0,      2, 2 },
        { CardId::RUPTURE,                1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::SCRAPE,                 1, 1,     7, 10,     0, 0,      4, 5 },
        { CardId::SEEK,                   0, 0,     0, 0,      0, 0,      1, 2 },
        { CardId::SELF_REPAIR,            1, 1,     0, 0,      0, 0,      7, 10 },
        { CardId::SKIM,                   1, 1,     0, 0,      0, 0,      3, 4 },
        { CardId::STACK,                  1, 1,     0, 0,      0, 3,      0, 0 },
        { CardId::STATIC_DISCHARGE,       1, 1,     0, 0,      0, 0,      1, 2 },
        { CardId::STEAM_BARRIER,          0, 0,     0, 0,      6, 8,      0, 0 },
        { CardId::STORM,                  1, 1,     0, 0,      0, 0,      1, 1 },
        { CardId::STREAMLINE,             2, 2,    15, 20,     0, 0,      1, 1 },
        { CardId::SUNDER,                 3, 3,    24, 32,     0, 0,      0, 0 },
        { CardId::SWEEPING_BEAM,          1, 1,     6, 9,      0, 0,      1, 1 },
        { CardId::TEMPEST,               -1, -1,    0, 0,      0, 0,      0, 0 },
        { CardId::THUNDER_STRIKE,         3, 3,     7, 9,      0, 0,      0, 0 },
        { CardId::TURBO,                  0, 0,     0, 0,      0, 0,      2, 3 },
        { CardId::WHITE_NOISE,            1, 0,     0, 0,      0, 0,      0, 0 },
        { CardId::ZAP,                    1, 0,     0, 0,      0, 0,      1, 1 },
    };

    const Printed &printed(CardId id) {
        for (const auto &row : printedNumbers) {
            if (row.id == id) {
                return row;
            }
        }
        throw std::runtime_error("no printed numbers recorded for " + name(id));
    }

    void expectCost(CardId id, int upgrades) {
        const Printed &row = printed(id);
        const int expected = upgrades ? row.costUp : row.cost;
        const CardInstance card(id, upgrades > 0);
        check(card.cost == expected,
              tagged(id, upgrades) + " should cost " + std::to_string(expected)
                  + ", costs " + std::to_string(static_cast<int>(card.cost)));
        check(card.costForTurn == expected,
              tagged(id, upgrades) + " should start the turn at its printed cost");
    }

    // Attack damage against a lone Cultist. No orbs, no strength, so the number
    // is the card's own.
    void expectDamage(CardId id, int upgrades) {
        const int expected = upgrades ? printed(id).damageUp : printed(id).damage;
        check(expected > 0, name(id) + " prints no damage, so it needs no damage check");
        BattleContext battle = emptyBattle();
        play(battle, id, upgrades);
        const int actual = damageDealt(battle);
        check(actual == expected,
              tagged(id, upgrades) + " should deal " + std::to_string(expected)
                  + ", dealt " + std::to_string(actual));
    }

    void expectBlock(CardId id, int upgrades, int discardSize = 0, int energy = 10) {
        const int expected = upgrades ? printed(id).blockUp : printed(id).block;
        check(expected > 0, name(id) + " prints no block, so it needs no block check");
        BattleContext battle = emptyBattle();
        addDiscardPile(battle, discardSize);
        playWith(battle, id, upgrades, energy);
        const int actual = battle.player.block;
        check(actual == expected,
              tagged(id, upgrades) + " should grant " + std::to_string(expected)
                  + " block, granted " + std::to_string(actual));
    }

    // Statuses a magic number might be stored in. Focus, Strength, Dexterity
    // and Artifact live in their own player fields; every other power keeps its
    // amount in statusMap, which only has an entry once the power is applied.
    void expectPlayerStatus(CardId id, int upgrades, PlayerStatus which) {
        const int expected = upgrades ? printed(id).magicUp : printed(id).magic;
        check(expected > 0, name(id) + " prints no magic number");
        BattleContext battle = emptyBattle();
        play(battle, id, upgrades);
        check(battle.player.hasStatusRuntime(which),
              tagged(id, upgrades) + " should apply its printed power, applied nothing");
        check(powerCount(battle.player, which) == 1,
              tagged(id, upgrades) + " should raise exactly one power in the power order");

        int actual = 0;
        try {
            actual = battle.player.getStatusRuntime(which);
        } catch (const std::out_of_range &) {
            throw std::runtime_error(tagged(id, upgrades)
                                     + ": the power is in the power order but not in statusMap");
        }
        check(actual == expected,
              tagged(id, upgrades) + " should store " + std::to_string(expected)
                  + ", stored " + std::to_string(actual));
    }

    void expectTableIsClean();

    void checkNumbers() {
        // Costs: every card, both forms, straight off the card instance.
        for (const auto &row : printedNumbers) {
            expectCost(row.id, 0);
            expectCost(row.id, 1);
        }

        // One card per line: the attack numbers.
        const std::vector<CardId> attacks {
            CardId::STRIKE_BLUE, CardId::ALL_FOR_ONE, CardId::BALL_LIGHTNING,
            CardId::BEAM_CELL, CardId::BULLSEYE, CardId::CLAW, CardId::COLD_SNAP,
            CardId::COMPILE_DRIVER, CardId::CORE_SURGE, CardId::DOOM_AND_GLOOM,
            CardId::FTL, CardId::GO_FOR_THE_EYES, CardId::HYPERBEAM, CardId::MELTER,
            CardId::METEOR_STRIKE, CardId::REBOUND,
            CardId::SCRAPE, CardId::STREAMLINE, CardId::SUNDER, CardId::SWEEPING_BEAM,
            CardId::THUNDER_STRIKE,
        };
        for (const CardId id : attacks) {
            expectDamage(id, 0);
            expectDamage(id, 1);
        }

        // Rip and Tear hits twice for its printed number, so the total is
        // double and the upgrade shows on both hits.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::RIP_AND_TEAR).damageUp
                                          : printed(CardId::RIP_AND_TEAR).damage;
            BattleContext battle = emptyBattle();
            play(battle, CardId::RIP_AND_TEAR, upgrades);
            check(damageDealt(battle) == expected * 2,
                  tagged(CardId::RIP_AND_TEAR, upgrades) + " should hit twice for its printed damage");
        }

        // Barrage prints a per-orb number; the Cracked Core's single Lightning
        // orb shows the first one.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = freshBattle();
            play(battle, CardId::BARRAGE, upgrades);
            const int expected = upgrades ? printed(CardId::BARRAGE).damageUp
                                          : printed(CardId::BARRAGE).damage;
            check(damageDealt(battle) == expected,
                  tagged(CardId::BARRAGE, upgrades) + " should deal its printed number per orb");
        }

        // Block numbers. Reinforced Body gains its block once per energy, so it
        // is given a single energy to show the printed number once.
        const std::vector<CardId> blockers {
            CardId::DEFEND_BLUE, CardId::AUTO_SHIELDS, CardId::BOOT_SEQUENCE,
            CardId::CHARGE_BATTERY, CardId::EQUILIBRIUM, CardId::FORCE_FIELD,
            CardId::GLACIER, CardId::HOLOGRAM, CardId::LEAP, CardId::STEAM_BARRIER,
        };
        for (const CardId id : blockers) {
            expectBlock(id, 0);
            expectBlock(id, 1);
        }
        expectBlock(CardId::REINFORCED_BODY, 0, 0, 1);
        expectBlock(CardId::REINFORCED_BODY, 1, 0, 1);

        // Stack prints no block of its own: it grants one per discard card, plus
        // 3 when upgraded.
        check(([&] { BattleContext b = emptyBattle(); addDiscardPile(b, 5);
                     play(b, CardId::STACK, 0); return b.player.block; })() == 5,
              "Stack should grant one block per card in the discard pile");
        check(([&] { BattleContext b = emptyBattle(); addDiscardPile(b, 5);
                     play(b, CardId::STACK, 1); return b.player.block; })() == 8,
              "Stack+ should add 3 to the discard pile count");

        // Genetic Algorithm grants its accumulated block, which starts at 1
        // (misc) and ignores upgrades.
        check(([&] { BattleContext b = emptyBattle();
                     play(b, CardId::GENETIC_ALGORITHM, 0); return b.player.block; })() == 1,
              "Genetic Algorithm should grant its starting 1 block");
        check(([&] { BattleContext b = emptyBattle();
                     play(b, CardId::GENETIC_ALGORITHM, 1); return b.player.block; })() == 1,
              "Genetic Algorithm+ should grant the same starting block");

        // Magic numbers that land on Focus.
        for (const CardId id : { CardId::DEFRAGMENT, CardId::CONSUME }) {
            expectPlayerStatus(id, 0, PS::FOCUS);
            expectPlayerStatus(id, 1, PS::FOCUS);
        }

        // Reprogram prints its magic number as both the Focus it takes and the
        // Strength and Dexterity it gives. Focus has to be there first for the
        // loss to show.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::REPROGRAM).magicUp
                                          : printed(CardId::REPROGRAM).magic;
            BattleContext battle = emptyBattle();
            battle.player.buff<PS::FOCUS>(expected);
            battle.player.energy = 10;
            play(battle, CardId::REPROGRAM, upgrades);
            check(battle.player.getStatus<PS::FOCUS>() == 0,
                  tagged(CardId::REPROGRAM, upgrades) + " should take away its printed Focus");
            check(battle.player.getStatus<PS::STRENGTH>() == expected,
                  tagged(CardId::REPROGRAM, upgrades) + " should gain its printed Strength");
            check(battle.player.getStatus<PS::DEXTERITY>() == expected,
                  tagged(CardId::REPROGRAM, upgrades) + " should gain its printed Dexterity");
        }

        // Powers that store their magic number as the stacks they apply.
        expectPlayerStatus(CardId::LOOP, 0, PS::LOOP);
        expectPlayerStatus(CardId::LOOP, 1, PS::LOOP);
        expectPlayerStatus(CardId::HEATSINKS, 0, PS::HEATSINKS);
        expectPlayerStatus(CardId::HEATSINKS, 1, PS::HEATSINKS);
        expectPlayerStatus(CardId::BUFFER, 0, PS::BUFFER);
        expectPlayerStatus(CardId::BUFFER, 1, PS::BUFFER);
        expectPlayerStatus(CardId::AMPLIFY, 0, PS::AMPLIFY);
        expectPlayerStatus(CardId::AMPLIFY, 1, PS::AMPLIFY);
        expectPlayerStatus(CardId::SELF_REPAIR, 0, PS::SELF_REPAIR);
        expectPlayerStatus(CardId::SELF_REPAIR, 1, PS::SELF_REPAIR);
        expectPlayerStatus(CardId::RUPTURE, 0, PS::RUPTURE);
        expectPlayerStatus(CardId::RUPTURE, 1, PS::RUPTURE);
        expectPlayerStatus(CardId::STATIC_DISCHARGE, 0, PS::STATIC_DISCHARGE);
        expectPlayerStatus(CardId::STATIC_DISCHARGE, 1, PS::STATIC_DISCHARGE);
        expectPlayerStatus(CardId::BIASED_COGNITION, 0, PS::FOCUS);
        expectPlayerStatus(CardId::BIASED_COGNITION, 1, PS::FOCUS);

        // Powers that only ever apply a single stack, printed or not.
        for (const CardId id : { CardId::STORM, CardId::ECHO_FORM, CardId::HELLO_WORLD,
                                 CardId::CORE_SURGE }) {
            const PlayerStatus which = id == CardId::CORE_SURGE ? PS::ARTIFACT
                                    : id == CardId::STORM ? PS::STORM
                                    : id == CardId::ECHO_FORM ? PS::ECHO_FORM
                                    : PS::HELLO_WORLD;
            for (int upgrades = 0; upgrades < 2; ++upgrades) {
                BattleContext battle = emptyBattle();
                play(battle, id, upgrades);
                check(battle.player.getStatusRuntime(which) == 1,
                      tagged(id, upgrades) + " should apply exactly one stack");
            }
        }

        // Electrodynamics prints one stack that is the same base and upgraded;
        // its magic number is the Lightning orbs it channels, checked below.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = emptyBattle();
            play(battle, CardId::ELECTRODYNAMICS, upgrades);
            check(battle.player.getStatusRuntime(PS::ELECTRO) == 1,
                  tagged(CardId::ELECTRODYNAMICS, upgrades)
                      + " should apply a single Electro stack");
        }

        // Machine Learning prints a single stack but raises the per-turn draw.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = emptyBattle();
            const int before = battle.player.cardDrawPerTurn;
            play(battle, CardId::MACHINE_LEARNING, upgrades);
            check(battle.player.cardDrawPerTurn == before + 1,
                  tagged(CardId::MACHINE_LEARNING, upgrades)
                      + " should raise the per-turn draw by 1");
        }

        // Debuffs that land on the enemy.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::BULLSEYE).magicUp
                                          : printed(CardId::BULLSEYE).magic;
            BattleContext battle = emptyBattle();
            play(battle, CardId::BULLSEYE, upgrades);
            check(battle.monsters.arr[0].getStatus<MS::LOCK_ON>() == expected,
                  tagged(CardId::BULLSEYE, upgrades) + " should apply its printed Lock-On");
        }
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::BEAM_CELL).magicUp
                                          : printed(CardId::BEAM_CELL).magic;
            BattleContext battle = emptyBattle();
            play(battle, CardId::BEAM_CELL, upgrades);
            check(battle.monsters.arr[0].getStatus<MS::VULNERABLE>() == expected,
                  tagged(CardId::BEAM_CELL, upgrades) + " should apply its printed Vulnerable");
        }
        // Go for the Eyes only applies its Weak while the enemy intends to
        // attack; a Cultist opens with Incantation, so its Dark Strike is
        // written into the move history first.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::GO_FOR_THE_EYES).magicUp
                                          : printed(CardId::GO_FOR_THE_EYES).magic;
            BattleContext battle = emptyBattle();
            battle.monsters.arr[0].moveHistory[0] = MMID::CULTIST_DARK_STRIKE;
            play(battle, CardId::GO_FOR_THE_EYES, upgrades);
            check(battle.monsters.arr[0].getStatus<MS::WEAK>() == expected,
                  tagged(CardId::GO_FOR_THE_EYES, upgrades)
                      + " should apply its printed Weak to an attacking enemy");
        }

        // Hyperbeam's printed number is the Focus it takes.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::HYPERBEAM).magicUp
                                          : printed(CardId::HYPERBEAM).magic;
            BattleContext battle = emptyBattle();
            battle.player.buff<PS::FOCUS>(expected);
            play(battle, CardId::HYPERBEAM, upgrades);
            check(battle.player.getStatus<PS::FOCUS>() == 0,
                  tagged(CardId::HYPERBEAM, upgrades) + " should lose its printed Focus");
        }

        // Cards that print no number still have a cost, which the upgrade may
        // remove; the free ones are covered by expectCost above. What remains is
        // the cards whose printed number is a count of something.
        auto drawnBy = [](CardId id, int upgrades, int drawPileSize) {
            BattleContext battle = emptyBattle();
            addDrawPile(battle, drawPileSize);
            play(battle, id, upgrades);
            return battle.cards.cardsInHand;
        };

        // Skim draws 3 (4).
        check(drawnBy(CardId::SKIM, 0, 6) == 3, "Skim should draw 3");
        check(drawnBy(CardId::SKIM, 1, 6) == 4, "Skim+ should draw 4");

        // Coolheaded draws 1 (2).
        check(drawnBy(CardId::COOLHEADED, 0, 6) == 1, "Coolheaded should draw 1");
        check(drawnBy(CardId::COOLHEADED, 1, 6) == 2, "Coolheaded+ should draw 2");

        // Overclock draws 2 (3).
        check(drawnBy(CardId::OVERCLOCK, 0, 6) == 2, "Overclock should draw 2");
        check(drawnBy(CardId::OVERCLOCK, 1, 6) == 3, "Overclock+ should draw 3");

        // Reboot draws 4 (6) after shuffling the discard pile back in.
        check(([&] { BattleContext b = emptyBattle(); addDiscardPile(b, 8);
                     play(b, CardId::REBOOT, 0); return b.cards.cardsInHand; })() == 4,
              "Reboot should draw 4");
        check(([&] { BattleContext b = emptyBattle(); addDiscardPile(b, 8);
                     play(b, CardId::REBOOT, 1); return b.cards.cardsInHand; })() == 6,
              "Reboot+ should draw 6");

        // Aggregate prints one energy per 4 (3) draw pile cards.
        // The pool starts at the card's cost so that is paid first, and the card
        // then gains one energy per 4 (3) draw pile cards on top of nothing else.
        check(([&] { BattleContext b = emptyBattle(); addDrawPile(b, 12);
                     playWith(b, CardId::AGGREGATE, 0, 1); return b.player.energy; })() == 3,
              "Aggregate should gain one energy per 4 draw pile cards");
        check(([&] { BattleContext b = emptyBattle(); addDrawPile(b, 12);
                     playWith(b, CardId::AGGREGATE, 1, 1); return b.player.energy; })() == 4,
              "Aggregate+ should gain one energy per 3 draw pile cards");

        // Turbo prints the energy it gains.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::TURBO).magicUp
                                          : printed(CardId::TURBO).magic;
            BattleContext battle = emptyBattle();
            playWith(battle, CardId::TURBO, upgrades, 0);
            check(battle.player.energy == expected,
                  tagged(CardId::TURBO, upgrades) + " should gain its printed energy");
        }

        // Capacitor prints the orb slots it adds, on top of the Defect's three.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::CAPACITOR).magicUp
                                          : printed(CardId::CAPACITOR).magic;
            BattleContext battle = emptyBattle();
            const int before = battle.player.orbSlots;
            play(battle, CardId::CAPACITOR, upgrades);
            check(battle.player.orbSlots == before + expected,
                  tagged(CardId::CAPACITOR, upgrades) + " should add its printed orb slots");
        }

        // Consume prints Focus and takes an orb slot.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = emptyBattle();
            const int before = battle.player.orbSlots;
            play(battle, CardId::CONSUME, upgrades);
            check(battle.player.orbSlots == before - 1,
                  tagged(CardId::CONSUME, upgrades) + " should lose one orb slot");
        }

        // Channel counts: Chaos channels 1 (2), Chill one per enemy,
        // Electrodynamics 2 (3) Lightning, Meteor Strike 3 Plasma.
        auto orbsChanneled = [](CardId id, int upgrades, MonsterEncounter encounter,
                                int slots) {
            BattleContext battle = freshBattle(encounter);
            battle.player.orbs.clear();
            battle.player.orbSlots = slots;
            play(battle, id, upgrades);
            return static_cast<int>(battle.player.orbs.size());
        };

        check(orbsChanneled(CardId::CHAOS, 0, MonsterEncounter::CULTIST, 10) == 1,
              "Chaos should channel 1 orb");
        check(orbsChanneled(CardId::CHAOS, 1, MonsterEncounter::CULTIST, 10) == 2,
              "Chaos+ should channel 2 orbs");
        check(orbsChanneled(CardId::CHILL, 0, MonsterEncounter::THREE_CULTIST, 10) == 3,
              "Chill should channel one Frost per enemy");
        check(orbsChanneled(CardId::CHILL, 1, MonsterEncounter::THREE_CULTIST, 10) == 3,
              "Chill+ should channel one Frost per enemy, unchanged");
        check(orbsChanneled(CardId::ELECTRODYNAMICS, 0, MonsterEncounter::CULTIST, 10) == 2,
              "Electrodynamics should channel 2 Lightning");
        check(orbsChanneled(CardId::ELECTRODYNAMICS, 1, MonsterEncounter::CULTIST, 10) == 3,
              "Electrodynamics+ should channel 3 Lightning");
        check(orbsChanneled(CardId::METEOR_STRIKE, 0, MonsterEncounter::CULTIST, 10) == 3,
              "Meteor Strike should channel 3 Plasma");
        check(orbsChanneled(CardId::METEOR_STRIKE, 1, MonsterEncounter::CULTIST, 10) == 3,
              "Meteor Strike+ should channel 3 Plasma, unchanged");

        // Single-orb cards: Zap and Ball Lightning channel Lightning, Cold Snap
        // and Coolheaded Frost, Glacier two Frost, Darkness a Dark orb.
        check(orbsChanneled(CardId::ZAP, 0, MonsterEncounter::CULTIST, 10) == 1,
              "Zap should channel 1 orb");
        check(orbsChanneled(CardId::ZAP, 1, MonsterEncounter::CULTIST, 10) == 1,
              "Zap+ should channel 1 orb, unchanged");
        check(orbsChanneled(CardId::COLD_SNAP, 0, MonsterEncounter::CULTIST, 10) == 1,
              "Cold Snap should channel 1 orb");
        check(orbsChanneled(CardId::GLACIER, 0, MonsterEncounter::CULTIST, 10) == 2,
              "Glacier should channel 2 orbs");
        check(orbsChanneled(CardId::DARKNESS, 0, MonsterEncounter::CULTIST, 10) == 1,
              "Darkness should channel 1 orb");
        check(orbsChanneled(CardId::FUSION, 0, MonsterEncounter::CULTIST, 10) == 1,
              "Fusion should channel 1 orb");
        check(orbsChanneled(CardId::RAINBOW, 0, MonsterEncounter::CULTIST, 10) == 3,
              "Rainbow should channel 3 orbs");

        // Seek picks 1 (2) cards.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int expected = upgrades ? printed(CardId::SEEK).magicUp
                                          : printed(CardId::SEEK).magic;
            BattleContext battle = emptyBattle();
            addDrawPile(battle, 4);
            play(battle, CardId::SEEK, upgrades);
            check(battle.inputState == InputState::CARD_SELECT,
                  tagged(CardId::SEEK, upgrades) + " should open the draw pile select screen");
            check(battle.cardSelectInfo.cardSelectTask == CardSelectTask::SEEK,
                  tagged(CardId::SEEK, upgrades) + " should use the SEEK select task");
            check(battle.cardSelectInfo.pickCount == expected,
                  tagged(CardId::SEEK, upgrades) + " should pick its printed count");
        }

        // Scrape draws 4 (5) and throws away the cards that cost 2 or more; the
        // strikes it draws cost 1, so they stay in hand.
        check(([&] { BattleContext b = emptyBattle(); addDrawPile(b, 6);
                     play(b, CardId::SCRAPE, 0); return b.cards.cardsInHand; })() == 4,
              "Scrape should draw 4");
        check(([&] { BattleContext b = emptyBattle(); addDrawPile(b, 6);
                     play(b, CardId::SCRAPE, 1); return b.cards.cardsInHand; })() == 5,
              "Scrape+ should draw 5");

        // X cards read the energy pool when they resolve, so the count is the
        // energy in the pool plus 1 when upgraded. Multi-Cast evokes the oldest
        // orb X times, Tempest channels X Lightning.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int energy = 3;
            const int times = energy + (upgrades ? 1 : 0);

            BattleContext multi = freshBattle();
            multi.player.orbs.clear();
            multi.player.orbSlots = 10;
            for (int i = 0; i < times + 1; ++i) {
                Actions::ChannelOrb(Orb::LIGHTNING).actFunc(multi);
            }
            const int orbsBefore = static_cast<int>(multi.player.orbs.size());
            playWith(multi, CardId::MULTI_CAST, upgrades, energy);
            check(static_cast<int>(multi.player.orbs.size()) == orbsBefore - 1,
                  tagged(CardId::MULTI_CAST, upgrades) + " should consume the evoked orb");
            check(multi.player.energy == 0,
                  tagged(CardId::MULTI_CAST, upgrades) + " should spend all of the energy");

            BattleContext tempest = freshBattle();
            tempest.player.orbs.clear();
            tempest.player.orbSlots = 10;
            playWith(tempest, CardId::TEMPEST, upgrades, energy);
            check(static_cast<int>(tempest.player.orbs.size()) == times,
                  tagged(CardId::TEMPEST, upgrades) + " should channel its X Lightning orbs");
            check(tempest.player.energy == 0,
                  tagged(CardId::TEMPEST, upgrades) + " should spend all of the energy");
        }

        // Reinforced Body gains its printed block per energy spent.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            const int perHit = upgrades ? printed(CardId::REINFORCED_BODY).blockUp
                                        : printed(CardId::REINFORCED_BODY).block;
            BattleContext battle = emptyBattle();
            playWith(battle, CardId::REINFORCED_BODY, upgrades, 3);
            check(battle.player.block == perHit * 3,
                  tagged(CardId::REINFORCED_BODY, upgrades) + " should gain its block per energy");
            check(battle.player.energy == 0,
                  tagged(CardId::REINFORCED_BODY, upgrades) + " should spend all of the energy");
        }

        // Double Energy doubles what is left after paying its own cost: a pool
        // of 5 leaves 4 for the base card and 10 for the free upgraded one.
        check(([&] { BattleContext b = emptyBattle();
                     playWith(b, CardId::DOUBLE_ENERGY, 0, 5); return b.player.energy; })() == 8,
              "Double Energy should spend 1 and double the remaining 4");
        check(([&] { BattleContext b = emptyBattle();
                     playWith(b, CardId::DOUBLE_ENERGY, 1, 5); return b.player.energy; })() == 10,
              "Double Energy+ is free, so it doubles the whole 5");

        // Dualcast evokes the leftmost orb twice, which the upgrade does not
        // change.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = freshBattle();
            battle.player.orbs.clear();
            play(battle, CardId::DUALCAST, upgrades);
            check(battle.player.orbs.empty(),
                  tagged(CardId::DUALCAST, upgrades) + " should evoke its orb");
        }

        // Fission removes every orb, then gives one energy and one card per orb
        // removed; upgraded it evokes them instead.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = freshBattle();
            battle.player.orbSlots = 4;
            battle.player.energy = 10;
            addDrawPile(battle, 3);
            check(static_cast<int>(battle.player.orbs.size()) == 1,
                  "the starting Defect battle should hold one orb");
            playWith(battle, CardId::FISSION, upgrades, 5);
            check(battle.player.orbs.empty(),
                  tagged(CardId::FISSION, upgrades) + " should clear the orb bar");
            check(battle.player.energy == 5 + 1,
                  tagged(CardId::FISSION, upgrades) + " should gain one energy per orb removed"
                      + " (energy " + std::to_string(battle.player.energy) + ")");
            check(battle.cards.cardsInHand == 1,
                  tagged(CardId::FISSION, upgrades) + " should draw one card per orb removed"
                      + " (hand " + std::to_string(battle.cards.cardsInHand) + ")");
        }

        // Charge Battery prints block and energises; both are checked above and
        // below.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = emptyBattle();
            play(battle, CardId::CHARGE_BATTERY, upgrades);
            check(battle.player.getStatus<PS::ENERGIZED>() == 1,
                  tagged(CardId::CHARGE_BATTERY, upgrades) + " should energise next turn");
        }

        // Biased Cognition prints Focus and applies one Bias, the upgrade only
        // raising the Focus.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = emptyBattle();
            play(battle, CardId::BIASED_COGNITION, upgrades);
            check(battle.player.getStatus<PS::BIAS>() == 1,
                  tagged(CardId::BIASED_COGNITION, upgrades) + " should apply 1 Bias");
        }

        // All For One prints damage and returns the 0-cost cards; the damage is
        // checked above, so only the return is left. An upgraded Zap is free and
        // comes back, while the Strike that costs 1 stays in the discard pile.
        for (int upgrades = 0; upgrades < 2; ++upgrades) {
            BattleContext battle = emptyBattle();
            battle.cards.createTempCardInDiscard(CardInstance(CardId::ZAP, true));
            battle.cards.createTempCardInDiscard(CardInstance(CardId::STRIKE_BLUE));
            play(battle, CardId::ALL_FOR_ONE, upgrades);
            check(battle.cards.cardsInHand == 1,
                  tagged(CardId::ALL_FOR_ONE, upgrades)
                      + " should return only the 0-cost card (hand "
                      + std::to_string(battle.cards.cardsInHand) + ")");
            // The played All For One itself also lands in the discard pile.
            check(battle.cards.discardPile.size() == 2,
                  tagged(CardId::ALL_FOR_ONE, upgrades)
                      + " should leave the costing card and itself in the discard pile"
                      + " (discard " + std::to_string(battle.cards.discardPile.size()) + ")");
        }

        expectTableIsClean();
    }

    void expectTableIsClean() {
        // The starter cards come from the shared switch rather than the Defect
        // dispatch, so they are the only two a case list does not carry.
        std::set<int> recorded;
        for (const auto &row : printedNumbers) {
            check(recorded.insert(static_cast<int>(row.id)).second,
                  name(row.id) + " is listed twice in the printed numbers");
        }
        check(recorded.count(static_cast<int>(CardId::STRIKE_BLUE)) == 1
                  && recorded.count(static_cast<int>(CardId::DEFEND_BLUE)) == 1,
              "the shared Defect starter cards need numbers too");
        // tools/coverage_manifest.py --check reads the dispatch itself and fails
        // when a card is implemented without a manifest entry, so this file only
        // has to keep the numbers complete and unique.
        check(printedNumbers.size() == 76,
              "the Defect pool holds 76 cards, the table has "
                  + std::to_string(printedNumbers.size()));
    }

}

int main() {
    try {
        checkNumbers();
        std::cout << "Defect card numbers hold\n";
    } catch (const std::out_of_range &e) {
        std::cout << "FAILED: out_of_range: " << e.what() << "\n";
        return 1;
    } catch (const std::exception &e) {
        std::cout << "FAILED: " << e.what() << "\n";
        return 1;
    }
    return 0;
}
