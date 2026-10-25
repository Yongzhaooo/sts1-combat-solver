package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Hand-written event and removal rules, checked on synthetic states (no game needed). */
public final class EventRulesCheck {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static EventRules.Context c(String event, int shown, int hp, int maxHp, int gold) {
        EventRules.Context c = new EventRules.Context();
        c.event = event; c.shown = shown; c.hp = hp; c.maxHp = maxHp; c.gold = gold; c.removable = 3;
        return c;
    }

    static int index(EventRules.Context c) {
        EventRules.Decision d = EventRules.decide(c);
        return d == null ? -99 : d.index;
    }

    public static void main(String[] args) throws Exception {
        // Knowing Skull: gold while healthy and the price is still low, else leave; never potion/card.
        check(index(c("Knowing Skull", 4, 90, 98, 120)) == 1, "skull healthy takes gold");
        check(index(c("Knowing Skull", 4, 60, 98, 120)) == 3, "skull below 70% leaves");
        EventRules.Context skull = c("Knowing Skull", 4, 90, 98, 120);
        skull.skullGoldCost = 8;
        check(index(skull) == 3, "skull stops after two gold picks");
        check(index(c("Knowing Skull", 4, 74, 98, 120)) == 3, "skull: 70% threshold applies to HP after paying");
        skull.skullGoldCost = 7; skull.hp = 76;
        check(index(skull) == 1, "skull: second gold at its shown cost 7");
        check(index(c("Knowing Skull", 1, 90, 98, 120)) == -99, "skull intro page is not ruled");

        check(index(c("Colosseum", 2, 50, 100, 0)) == 1 && index(c("Colosseum", 2, 49, 100, 0)) == 0, "colosseum 50%");
        check(index(c("Golden Idol", 2, 80, 80, 0)) == 0 && index(c("Golden Idol", 3, 80, 80, 0)) == 2, "idol hides");

        // Cleric: never pays for healing; with 40 gold the heal is the only paid option and we leave.
        check(index(c("The Cleric", 2, 10, 80, 40)) == 1, "cleric heal-only gold: leave");
        check(index(c("The Cleric", 3, 10, 80, 120)) == 1, "cleric purifies when rich (heal, purify, leave)");
        check(index(c("The Cleric", 1, 10, 80, 10)) == 0, "cleric poor leaves");
        // Emergency heal: under 15 HP, a known next step without a rest site, and 35 gold.
        EventRules.Context sick = c("The Cleric", 2, 14, 80, 40);
        sick.nextRoomsKnown = true; sick.nextHasRest = false;
        check(index(sick) == 0, "cleric heals under 15 HP with no rest site next");
        sick.nextHasRest = true;
        check(index(sick) == 1, "cleric does not heal when a rest site is next");
        sick.nextRoomsKnown = false; sick.nextHasRest = false;
        check(index(sick) == 1, "cleric does not heal when the next rooms are unknown");
        sick.nextRoomsKnown = true; sick.hp = 15;
        check(index(sick) == 1, "cleric does not heal at 15 HP");
        EventRules.Context sickRich = c("The Cleric", 3, 14, 80, 120);
        sickRich.nextRoomsKnown = true;
        check(index(sickRich) == 0, "emergency heal comes before purifying");
        EventRules.Context bare = c("The Cleric", 3, 70, 80, 120);
        bare.removable = 0;
        check(index(bare) == 2, "cleric without targets leaves");
        check(index(c("Beggar", 2, 70, 80, 75)) == 0 && index(c("Beggar", 1, 70, 80, 74)) == 0, "beggar");

        EventRules.Decision ghosts = EventRules.decide(c("Ghosts", 2, 80, 80, 0));
        check(ghosts.index == 1 && ghosts.note.startsWith(EventRules.MANUAL), "ghosts: manual refusal");

        // Scrap Ooze reads hidden information: relic within 25 HP, with a floor on remaining HP.
        EventRules.Context ooze = c("Scrap Ooze", 2, 60, 80, 0);
        ooze.oozeLoss = 12;
        check(index(ooze) == 0, "ooze reaches for a cheap relic");
        ooze.oozeLoss = 26;
        check(index(ooze) == 1, "ooze too expensive");
        ooze.oozeLoss = 25; ooze.hp = 60;
        check(index(ooze) == 0, "ooze exactly 25 is allowed");
        ooze.hp = 35;
        check(index(ooze) == 0, "ooze may end on 10 HP");
        ooze.hp = 34;
        check(index(ooze) == 1, "ooze would leave under 10 HP");
        ooze.oozeLoss = EventRules.UNKNOWN;
        check(index(ooze) == -99, "ooze unknown falls back to the network");

        // Dead Adventurer: search while the next search is safe; fight only non-Lagavulin elites at good health.
        EventRules.Context adv = c("Dead Adventurer", 2, 70, 80, 0);
        adv.adventurerNext = EventRules.SAFE_SEARCH; adv.adventurerEnemy = 2;
        check(index(adv) == 0, "adventurer: safe search even with Lagavulin around");
        adv.adventurerNext = EventRules.ELITE_SEARCH;
        check(index(adv) == 1, "adventurer: Lagavulin next, leave");
        adv.adventurerEnemy = 1;
        adv.hp = 60;
        check(index(adv) == 0, "adventurer: Nob at 75% HP fights");
        adv.hp = 59;
        check(index(adv) == 1, "adventurer: Nob under 75% leaves");
        adv.adventurerEnemy = 0; adv.hp = 48;
        check(index(adv) == 0, "adventurer: sentries at 60% fight");
        adv.hp = 47;
        check(index(adv) == 1, "adventurer: sentries under 60% leave");
        adv.adventurerNext = EventRules.UNKNOWN;
        check(index(adv) == -99, "adventurer unknown falls back");

        // Drug Dealer: never J.A.X.; transform by default; Mutagenic Strength with a built-in Artifact.
        EventRules.Context dealer = c("Drug Dealer", 3, 70, 80, 0);
        check(index(dealer) == 1, "dealer transforms");
        dealer.artifactRelic = true;
        check(index(dealer) == 2, "dealer takes Mutagenic Strength with Artifact");
        check(index(c("Drug Dealer", 2, 70, 80, 0)) == 1, "dealer without transform: mutagen, not J.A.X.");

        // Removal order: removable curse, Strike, Bash, Defend, other attack; the rest is not ruled.
        check(EventRules.removalTier("Injury", "CURSE") == 0, "curse first");
        check(EventRules.removalTier("AscendersBane", "CURSE") == 9, "unremovable curse");
        check(EventRules.removalTier("Strike_R", "ATTACK") == 1, "strike");
        check(EventRules.removalTier("Bash", "ATTACK") == 2, "bash");
        check(EventRules.removalTier("Defend_R", "SKILL") == 3, "defend after starters and curses, before other cards");
        check(EventRules.removalTier("Hemokinesis", "ATTACK") == 4, "other attack");
        check(index(c("Living Wall", 3, 70, 80, 0)) == 0, "wall forgets");
        EventRules.Context wall = c("Living Wall", 3, 70, 80, 0);
        wall.removable = 0;
        check(index(wall) == 2, "wall grows without targets");

        // Curse events: never the curse option.
        check(index(c("Liars Game", 2, 70, 80, 0)) == 1, "serpent: disagree");
        check(index(c("The Mausoleum", 2, 70, 80, 0)) == 1, "mausoleum: unread (live off) leaves");
        EventRules.Context tomb0 = c("The Mausoleum", 2, 70, 80, 0);
        tomb0.mausoleumCurse = 0;
        check(index(tomb0) == 0, "mausoleum: preread no curse opens");
        tomb0.mausoleumCurse = 1;
        check(index(tomb0) == 1, "mausoleum: preread curse leaves");
        tomb0.mausoleumCurse = 0; tomb0.ascension = 15;
        check(index(tomb0) == 1, "mausoleum: A15 always curses, leave");
        check(index(c("The Mausoleum", 1, 70, 80, 0)) == -99, "mausoleum result page is not ruled");
        for (String event : new String[]{"The Mausoleum", "Cursed Tome"}) {
            EventRules.Context offline = c(event, 2, 70, 80, 0);
            EventRules.readLive(offline); EventRules.selfCheck();
            check(offline.preread == null && offline.mausoleumCurse == EventRules.UNKNOWN && offline.tomeBook == null
                && index(offline) == 1, event + ": live off reads nothing and keeps the old rule");
        }
        check(index(c("Mushrooms", 2, 5, 80, 0)) == 0, "mushrooms: fight even when hurt");
        check(index(c("Accursed Blacksmith", 3, 70, 80, 0)) == 0 && index(c("Accursed Blacksmith", 2, 70, 80, 0)) == 1, "blacksmith");
        check(index(c("Forgotten Altar", 2, 70, 80, 0)) == 0 && index(c("Forgotten Altar", 3, 70, 80, 0)) == 0, "altar");
        check(index(c("Forgotten Altar", 2, 20, 80, 0)) == 1, "altar: lethal sacrifice falls back to desecrate");
        check(index(c("MindBloom", 3, 70, 80, 0)) == 0, "mind bloom: the Act 1 boss");

        // Neow: lose HP and remove two cards; otherwise never the curse drawback.
        EventRules.Context neow = c("Neow Event", 4, 80, 80, 99);
        neow.neowBonus = new String[]{"THREE_CARDS", "REMOVE_TWO", "ONE_RARE_RELIC", "TWENTY_PERCENT_HP_BONUS"};
        neow.neowDrawback = new String[]{"NONE", "PERCENT_DAMAGE", "CURSE", "NONE"};
        check(index(neow) == 1 && EventRules.decide(neow).boost > 0, "neow: lose HP, remove two is favoured, not forced");
        neow.neowDrawback = new String[]{"NONE", "TEN_PERCENT_HP_LOSS", "NONE", "NONE"};
        check(index(neow) == 1 && EventRules.decide(neow).boost > 0, "neow: ten percent HP loss also counts");
        neow.neowDrawback = new String[]{"NONE", "CURSE", "NO_GOLD", "NONE"};
        EventRules.Decision noCurse = EventRules.decide(neow);
        check(noCurse.index == -1 && noCurse.avoid.length == 1 && noCurse.avoid[0] == 1, "neow: avoid the curse offer");
        neow.neowDrawback = new String[]{"NONE", "NO_GOLD", "NONE", "NONE"};
        check(EventRules.decide(neow) == null, "neow: remove-two without HP loss is left to the network");
        neow.neowBonus = null;
        check(EventRules.decide(neow) == null, "neow: unknown offers fall back"); 
        EventRules.Context blue = c("The Woman in Blue", 4, 70, 80, 100);
        blue.freePotionSlots = 3;
        check(index(blue) == 2, "blue: fill three free slots");
        blue.freePotionSlots = 1;
        check(index(blue) == 0, "blue: only one free slot");
        blue.freePotionSlots = 3; blue.gold = 35;
        check(index(blue) == 1, "blue: gold limits the count");
        blue.gold = 19;
        check(index(blue) == 3, "blue: cannot afford any");
        blue.gold = 100; blue.freePotionSlots = 0;
        check(index(blue) == 3, "blue: no free slot");
        blue.freePotionSlots = 3; blue.sozu = true;
        check(index(blue) == 3, "blue: Sozu blocks potions");

        // Ruby Key: smith first; recall at the second-to-last campfire, or the last one.
        String[] fire = {"rest", "smith", "recall"};
        EventRules.Decision early = EventRules.rest(fire, true, 3);
        check(early.index == -1 && early.avoid[0] == 2, "ruby: keep smithing while campfires remain");
        check(EventRules.rest(fire, true, 1).index == 2 && EventRules.rest(fire, true, 0).index == 2, "ruby: recall at the second-to-last and last fire");
        check(EventRules.rest(fire, false, 0) == null && EventRules.rest(new String[]{"rest", "smith"}, true, 0) == null, "ruby: nothing to decide");

        // Relic scores: Sapphire Key beats a weak relic, loses to a strong one; boss picks the best, skips junk.
        check(RelicScore.takeSapphireKey("Anchor") == false && RelicScore.takeSapphireKey("Strawberry") == false, "key vs strong relic");
        check(RelicScore.takeSapphireKey("Omamori") && RelicScore.takeSapphireKey("Unknown Relic") == true, "key vs weak or neutral relic");
        check(RelicScore.bestBoss(new String[]{"Fusion Hammer", "Black Star", "Sozu"}, new float[3]) == 1, "boss: best score");
        check(RelicScore.bestBoss(new String[]{"Ectoplasm"}, new float[1]) == -1, "boss: skip when all are junk");
        check(RelicScore.bestBoss(new String[]{"Ectoplasm", "Runic Dome", "Sozu"}, new float[3]) == 1, "boss: the dome only hides icons, the solver still sees intents");

        // Rules from the community guides.
        check(index(c("Nest", 2, 70, 80, 0)) == 0 && index(c("Nest", 1, 70, 80, 0)) == -99, "nest: take the gold");
        EventRules.Context basics = c("Back to Basics", 2, 70, 80, 0);
        check(index(basics) == 0, "back to basics: remove");
        basics.removable = 0;
        check(index(basics) == 1, "back to basics: upgrade when nothing to remove");
        check(index(c("Cursed Tome", 2, 70, 80, 0)) == 1, "tome: unread (live off) leaves");
        EventRules.Context tome = c("Cursed Tome", 2, 17, 80, 0);
        tome.tomeBook = "Enchiridion"; tome.tomeCost = 16; // intro below A15: 1+2+3 pages + 10
        check(index(tome) == 0, "tome: a non-Necronomicon book within HP reads");
        tome.hp = 16;
        check(index(tome) == 1, "tome: a cost equal to HP is lethal, don't read");
        tome.hp = 70; tome.tomeBook = "Necronomicon";
        check(index(tome) == 1, "tome: Necronomicon (Necronomicurse) is refused");
        tome.tomeBook = "Circlet"; tome.tomeCost = 15; tome.hp = 16; // last page at A15: only the 15 HP book page is left
        check(index(tome) == 0, "tome: last page takes a safe book");
        tome.tomeCost = EventRules.UNKNOWN;
        check(index(tome) == 1, "tome: unknown cost falls back to don't read");

        // Addict: pay only when the shop removal stays affordable, or nothing is left to remove; never steal.
        EventRules.Context addict = c("Addict", 3, 70, 80, 160);
        check(index(addict) == 0, "addict: gold-85 == purge cost pays");
        addict.gold = 159;
        check(index(addict) == 2, "addict: one gold short of the purge leaves");
        addict.purgeCost = 100; addict.gold = 185;
        check(index(addict) == 0, "addict: follows the shop's current purge price");
        addict.gold = 184;
        check(index(addict) == 2, "addict: below the raised purge price leaves");
        addict.gold = 85; addict.removable = 0;
        check(index(addict) == 0, "addict: nothing removable pays");
        check(index(c("Addict", 2, 70, 80, 84)) == 1, "addict: cannot pay, leave (never steal)");
        check(index(c("Vampires", 3, 70, 80, 0)) == 2 && index(c("Vampires", 2, 70, 80, 0)) == 1, "vampires: refuse");
        check(index(c("Masked Bandits", 2, 60, 80, 99)) == 1 && index(c("Masked Bandits", 2, 5, 80, 99)) == 1, "bandits: never pay");
        check(index(c("SecretPortal", 2, 70, 80, 0)) == 1, "portal: leave");
        EventRules.Context tomb = c("Tomb of Lord Red Mask", 2, 70, 80, 300);
        check(index(tomb) == 1, "tomb without the mask leaves");
        tomb.redMask = true;
        check(index(tomb) == 0, "tomb with the mask takes the gold");
        check(index(c("SensoryStone", 3, 70, 80, 0)) == 2 && index(c("SensoryStone", 3, 30, 80, 0)) == 0, "sensory stone");
        check(index(c("FaceTrader", 3, 70, 80, 0)) == 0 && index(c("FaceTrader", 3, 20, 80, 0)) == 2, "face trader");
        EventRules.Context face = c("FaceTrader", 3, 70, 80, 0);
        face.goodFace = 1;
        check(index(face) == 1, "face trader: read a good face, trade");
        face.goodFace = 0; face.hp = 20;
        check(index(face) == 2, "face trader: bad face and hurt, leave");
        EventRules.Context joust = c("The Joust", 2, 70, 80, 200);
        check(index(joust) == -99, "joust: unread falls back");
        joust.ownerWins = 1;
        check(index(joust) == 1, "joust: owner wins");
        joust.ownerWins = 0;
        check(index(joust) == 0, "joust: murderer wins");
        check(index(c("The Library", 2, 33, 80, 0)) == 0 && index(c("The Library", 2, 30, 80, 0)) == 1, "library reads from 40%");
        check(index(c("The Moai Head", 3, 70, 80, 0)) == 1 && index(c("The Moai Head", 2, 70, 80, 0)) == 1
            && index(c("The Moai Head", 2, 20, 80, 0)) == 0, "moai head");
        check(index(c("Mysterious Sphere", 2, 70, 80, 0)) == 0 && index(c("Mysterious Sphere", 2, 30, 80, 0)) == 1, "sphere");
        // Designer: adjust, clean up, full service, punch; unaffordable ones are not listed.
        check(index(c("Designer", 4, 70, 80, 120)) == 2 && index(c("Designer", 3, 70, 80, 120)) == 1, "designer full service");
        check(index(c("Designer", 3, 70, 80, 70)) == 1 && index(c("Designer", 2, 70, 80, 70)) == 0, "designer clean up");
        check(index(c("Designer", 2, 70, 80, 45)) == 0 && index(c("Designer", 1, 70, 80, 10)) == -99, "designer adjust or nothing");

        packetChecks(args[0]);
        System.out.println("PASS: event rules and removal order");
    }

    static JsonObject copy(JsonObject value) {
        return new JsonParser().parse(value.toString()).getAsJsonObject();
    }

    static void packetChecks(String fixturePath) throws Exception {
        JsonObject fixture;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(fixturePath), StandardCharsets.UTF_8)) {
            fixture = new JsonParser().parse(reader).getAsJsonObject();
        }
        Distill2 model = Distill2.load();
        for (int[] row : new int[][]{{94, 98, 1}, {30, 98, 3}}) {
            OutsidePacket packet = event(model, fixture, "Knowing Skull", row[0], row[1], 4);
            Distill2.Result result = packet.policyScore();
            check(result.best == row[2], "skull via the packet at hp " + row[0] + ": " + result.best);
            check(packet.ruleNote != null, "rule note is kept for the panel");
        }
        // The gold button's shown cost (public) drives the rule: 8 HP after two takes stops it even when healthy.
        for (int[] row : new int[][]{{7, 1}, {8, 3}}) {
            JsonArray options = new JsonArray();
            for (String text : new String[]{"[A Pick Me Up?] Get a Potion. Lose 6 HP.",
                    "[Riches?] Gain 90 Gold. Lose " + row[0] + " HP.", "[Success?] Get a Colorless Card. Lose 6 HP.", "[How do I leave?] Lose 6 HP."}) {
                JsonObject option = new JsonObject(); option.addProperty("text", text); options.add(option);
            }
            OutsidePacket shown = event(model, fixture, "Knowing Skull", 94, 98, 4, options);
            check(shown.policyScore().best == row[1], "skull reads the gold button cost " + row[0]);
        }
        OutsidePacket ghosts = event(model, fixture, "Ghosts", 70, 80, 2);
        check(ghosts.policyScore().best == 1 && ghosts.ruleNote.startsWith(EventRules.MANUAL), "ghosts via the packet");
        OutsidePacket bloom = event(model, fixture, "MindBloom", 70, 80, 3);
        check(bloom.policyScore().best == 0 && bloom.ruleNote != null, "mind bloom via the packet");
        OutsidePacket unruled = event(model, fixture, "The Joust", 70, 80, 3);
        check(unruled.policyScore().best == unruled.score().best && unruled.ruleNote == null, "unruled event uses the network");

        // Removal grid: curse before Strike before Bash before Defend before other non-rare attacks, whatever the network prefers.
        String[][][] sets = {
            {{"Defend_R", "SKILL"}, {"Bash", "ATTACK"}, {"Strike_R", "ATTACK"}, {"Injury", "CURSE"}},
            {{"Defend_R", "SKILL"}, {"Hemokinesis", "ATTACK"}, {"Bash", "ATTACK"}, {"Strike_R", "ATTACK"}},
            {{"Defend_R", "SKILL"}, {"Hemokinesis", "ATTACK"}, {"Bash", "ATTACK"}},
            {{"Defend_R", "SKILL"}, {"Hemokinesis", "ATTACK"}}};
        int[] expected = {3, 3, 2, 0};
        for (int set = 0; set < sets.length; set++) {
            OutsidePacket grid = grid(model, fixture, sets[set]);
            Distill2.Result result = grid.policyScore();
            check(result.best == expected[set], "removal order set " + set + ": " + result.best);
        }
        OutsidePacket defendsOnly = grid(model, fixture, new String[][]{{"Defend_R", "SKILL"}, {"Defend_R", "SKILL"}});
        check(defendsOnly.policyScore().best == defendsOnly.score().best && defendsOnly.ruleNote != null,
            "only Defends left: a Defend is removed by rule (the network picks which)");
        // Bonfire Spirits: no hand order; the network scores every legal card, so a key card is never forced out by a rule.
        String[][] bonfireCards = {{"Strike_R", "ATTACK", "BASIC"}, {"Demon Form", "POWER", "RARE"}, {"Corruption", "POWER", "RARE"},
            {"Shrug It Off", "SKILL", "COMMON"}, {"Flame Barrier", "SKILL", "UNCOMMON"}};
        for (int hp : new int[]{30, 70}) {
            OutsidePacket fire = grid(model, fixture, bonfireCards, "Bonfire", hp, 80);
            Distill2.Result ruled = fire.policyScore();
            check(fire.choices.size() == bonfireCards.length, "bonfire lists every legal card at hp " + hp);
            check(ruled.best == fire.score().best && fire.ruleNote == null, "bonfire offering is the network's at hp " + hp);
        }
    }

    static OutsidePacket event(Distill2 model, JsonObject fixture, String id, int hp, int maxHp, int options) {
        return event(model, fixture, id, hp, maxHp, options, null);
    }

    static OutsidePacket event(Distill2 model, JsonObject fixture, String id, int hp, int maxHp, int options, JsonArray buttons) {
        JsonObject state = copy(fixture.getAsJsonObject("state")), visible = copy(fixture.getAsJsonObject("visible"));
        JsonObject game = state.getAsJsonObject("game_state");
        game.addProperty("current_hp", hp); game.addProperty("max_hp", maxHp); game.addProperty("gold", 100);
        game.addProperty("screen_type", "EVENT");
        JsonObject screen = new JsonObject();
        screen.addProperty("event_id", id);
        if (buttons != null) screen.add("options", buttons);
        game.add("screen_state", screen);
        JsonArray choices = new JsonArray();
        for (int i = 0; i < options; i++) choices.add("option " + i);
        visible.add("choices", choices);
        visible.addProperty("screen_type", "EVENT");
        visible.addProperty("event_hp_loss", 0);
        return OutsidePacket.build(model, state, visible);
    }

    static OutsidePacket grid(Distill2 model, JsonObject fixture, String[][] cards) {
        return grid(model, fixture, cards, null, 0, 0);
    }

    static OutsidePacket grid(Distill2 model, JsonObject fixture, String[][] cards, String eventClass, int hp, int maxHp) {
        JsonObject state = copy(fixture.getAsJsonObject("state")), visible = copy(fixture.getAsJsonObject("visible"));
        JsonObject game = state.getAsJsonObject("game_state");
        if (eventClass != null) {
            game.addProperty("solver_event_class", eventClass);
            game.addProperty("current_hp", hp); game.addProperty("max_hp", maxHp);
        }
        game.addProperty("screen_type", "GRID");
        JsonObject screen = new JsonObject();
        screen.addProperty("for_purge", true);
        screen.addProperty("confirm_up", false);
        JsonArray list = new JsonArray();
        for (String[] card : cards) {
            JsonObject item = new JsonObject();
            item.addProperty("id", card[0]); item.addProperty("name", card[0]); item.addProperty("type", card[1]);
            if (card.length > 2) item.addProperty("rarity", card[2]);
            item.addProperty("cost", 1); item.addProperty("upgrades", 0); item.addProperty("exhausts", false);
            list.add(item);
        }
        screen.add("cards", list);
        game.add("screen_state", screen);
        visible.addProperty("screen_type", "GRID");
        visible.remove("choices");
        return OutsidePacket.build(model, state, visible);
    }
}
