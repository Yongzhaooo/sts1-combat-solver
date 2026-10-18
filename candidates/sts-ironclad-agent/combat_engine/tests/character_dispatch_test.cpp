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

// Until a character's cards are implemented, the per-character dispatch must decline them and
// the shared switch must still report them, never skip them silently. Cases cover all three
// type switches and every non-Ironclad character.
int main() {
    struct Case { CharacterClass cc; CardId card; };
    const Case cases[] = {
        {CharacterClass::SILENT, CardId::NEUTRALIZE},   // attack
        {CharacterClass::SILENT, CardId::SURVIVOR},     // skill
        {CharacterClass::SILENT, CardId::ACCURACY},     // power
        {CharacterClass::DEFECT, CardId::HOLOGRAM}, // skill; ZAP (m3a) and the M3b commons became implemented
        {CharacterClass::DEFECT, CardId::BALL_LIGHTNING},
        {CharacterClass::WATCHER, CardId::ERUPTION},
        {CharacterClass::WATCHER, CardId::VIGILANCE},
        {CharacterClass::WATCHER, CardId::DEVA_FORM},
    };
    for (const auto &c : cases) {
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
    std::cout << "unimplemented cards of 3 characters and 3 card types are still reported\n";
}
