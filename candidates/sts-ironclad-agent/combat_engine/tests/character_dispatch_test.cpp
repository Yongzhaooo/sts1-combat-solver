#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/Action.h"
#include <iostream>
#include <stdexcept>
#include <string>

using namespace sts;
static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

// Two things must hold at the per-character dispatch. A card a character has not implemented yet
// must be declined and reported by the shared switch, never skipped silently. A card that is
// implemented must run without that report. The Defect list is implemented in full now, so its
// entries moved to the second group, keeping attack, skill and power covered for both groups.
int main() {
    struct Case { CharacterClass cc; CardId card; };
    const Case unimplemented[] = {
        {CharacterClass::SILENT, CardId::NEUTRALIZE},   // attack
        {CharacterClass::SILENT, CardId::SURVIVOR},     // skill
        {CharacterClass::SILENT, CardId::ACCURACY},     // power
        {CharacterClass::WATCHER, CardId::ERUPTION},    // attack
        {CharacterClass::WATCHER, CardId::VIGILANCE},   // skill
        {CharacterClass::WATCHER, CardId::DEVA_FORM},   // power
    };
    const Case implemented[] = {
        {CharacterClass::DEFECT, CardId::BALL_LIGHTNING},   // attack
        {CharacterClass::DEFECT, CardId::ZAP},              // skill
        {CharacterClass::DEFECT, CardId::DEFRAGMENT},       // power
    };
    for (const auto &c : unimplemented) {
        GameContext game(c.cc, 123, 20);
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.player.energy = 100;
        battle.cards.createTempCardInHand(CardInstance(c.card));
        search::Action action(search::ActionType::CARD, 0, 0);
        check(action.isValidAction(battle), "card is not playable");
        bool reported = false;
        try {
            action.execute(battle);
        } catch (const std::runtime_error &error) {
            reported = std::string(error.what()).find("unimplemented card") != std::string::npos;
        }
        check(reported, "an unimplemented card did not report an error");
    }
    for (const auto &c : implemented) {
        GameContext game(c.cc, 123, 20);
        game.regainControlAction = [](GameContext&) {};
        BattleContext battle;
        battle.init(game, MonsterEncounter::CULTIST);
        battle.cards = CardManager{};
        battle.player.energy = 100;
        battle.cards.createTempCardInHand(CardInstance(c.card));
        search::Action action(search::ActionType::CARD, 0, 0);
        check(action.isValidAction(battle), "card is not playable");
        bool reported = false;
        try {
            action.execute(battle);
        } catch (const std::runtime_error &error) {
            reported = std::string(error.what()).find("unimplemented card") != std::string::npos;
        }
        check(!reported, "an implemented card was reported as unimplemented");
    }
    std::cout << "unimplemented cards of 2 characters and 3 card types are still reported;"
                 " implemented Defect cards run\n";
}
