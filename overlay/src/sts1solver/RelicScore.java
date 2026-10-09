package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/** Hand-written relic values (0-10) for boss picks and the Sapphire Key trade. Unlisted relics are neutral.
 * Thresholds are the author's draft for an Ironclad autopilot: tune here, nowhere else. */
final class RelicScore {
    static final int NEUTRAL = 5;
    /** The Sapphire Key is worth more than any relic scored below this; it costs the linked relic. */
    static final int SAPPHIRE_KEY = 6;
    /** Below this a boss relic is worse than none. */
    static final int SKIP_BELOW = 2;

    private static final Map<String, Integer> SCORES = new HashMap<>();

    private static void put(int score, String... ids) {
        for (String id : ids) SCORES.put(id, score);
    }

    static {
        // Boss relics, straight from the community ranking of all 30 (rank 1 = 10 points, rank 30 = 1), with one fact
        // check: Runic Dome only hides the intent icons, the data is still exported, so the solver sees everything.
        put(10, "Pandora's Box");
        put(9, "Runic Dome", "Runic Pyramid", "Astrolabe");
        put(8, "Empty Cage");
        put(7, "Snecko Eye", "Coffee Dripper");
        put(6, "Calling Bell", "Black Star");
        put(5, "SacredBark", "Cursed Key", "Philosopher's Stone");
        put(4, "Tiny House", "SlaversCollar", "Fusion Hammer");
        put(3, "Black Blood", "Velvet Choker", "Runic Cube");
        put(2, "Mark of Pain");
        put(1, "Sozu", "Busted Crown", "Ectoplasm");
        // Common, uncommon, rare, shop and event relics (strong ones for a big Ironclad deck score high).
        put(9, "Lizard Tail", "TungstenRod", "Dead Branch", "Pen Nib", "Girya");
        put(8, "Torii", "Calipers", "Ginger", "Turnip", "Mango", "Peace Pipe", "Meat on the Bone", "Orichalcum", "Gambling Chip",
            "Charon's Ashes", "Shuriken", "Brimstone", "FossilizedHelix", "Thread and Needle");
        put(7, "Vajra", "Anchor", "Lantern", "Centennial Puzzle", "Bronze Scales", "Red Skull", "Paper Frog", "Pantograph",
            "Singing Bowl", "Question Card", "Molten Egg 2", "Self Forming Clay", "Prayer Wheel", "Incense Burner", "CaptainsWheel",
            "StrikeDummy", "Bag of Marbles", "Ancient Tea Set", "Unceasing Top");
        put(6, "Strawberry", "Pear", "Nunchaku", "Oddly Smooth Stone", "Matryoshka", "White Beast Statue", "Eternal Feather",
            "Magic Flower", "Bottled Tornado", "Bottled Flame", "Bottled Lightning", "Mercury Hourglass", "HornCleat",
            "Gremlin Horn", "Dream Catcher", "Regal Pillow", "Shovel", "Old Coin", "Ice Cream", "StoneCalendar", "OrangePellets",
            "ClockworkSouvenir", "Bird Faced Urn", "Lee's Waffle", "Toy Ornithopter");
        put(5, "Blood Vial", "Bag of Preparation", "Happy Flower", "Whetstone", "War Paint", "Letter Opener", "InkBottle",
            "Orrery", "Membership Card", "Cauldron", "PreservedInsect", "Boot", "Du-Vu Doll", "Mummified Hand",
            "Frozen Egg 2", "Toxic Egg 2", "Ornamental Fan", "Sundial", "Champion Belt");
        put(4, "Omamori", "Juzu Bracelet", "MawBank", "Smiling Mask", "The Courier", "Chemical X", "Toolbox", "DollysMirror",
            "PrismaticShard", "Kunai", "Pocketwatch", "Blue Candle", "Unceasing Top", "Art of War");
        put(3, "Potion Belt", "WingedGreaves", "MealTicket", "CeramicFish", "Darkstone Periapt", "Medical Kit", "Frozen Eye",
            "HandDrill", "Strange Spoon", "Sling", "TheAbacus", "Tiny Chest");
        put(2, "TwistedFunnel", "Spirit Poop", "Symbiotic Virus", "TeardropLocket");
    }

    static int score(String id) {
        Integer value = SCORES.get(id);
        return value == null ? NEUTRAL : value;
    }

    /** Strike Dummy affects every original-game STRIKE-tagged card, not just starter Strikes. */
    static int score(String id, JsonArray deck) {
        if(!id.equals("StrikeDummy"))return score(id);
        int strikes=0;
        for(JsonElement item:deck) {
            String cardId=item.getAsJsonObject().get("id").getAsString();
            if(cardId.equals("Strike_R") || cardId.equals("Strike_G") || cardId.equals("Strike_B")
                    || cardId.equals("Strike_P") || cardId.equals("Pommel Strike") || cardId.equals("Twin Strike")
                    || cardId.equals("Wild Strike") || cardId.equals("Perfected Strike") || cardId.equals("Swift Strike"))strikes++;
        }
        return strikes==0?0:strikes==1?3:strikes==2?5:7;
    }

    /** Boss-only adjustments use the current deck, never assumed future synergies. */
    static int bossScore(String id, JsonArray deck) {
        if(!id.equals("Runic Cube") && !id.equals("Empty Cage"))return score(id);
        int junk=0;
        boolean selfDamage=false;
        for(JsonElement item:deck) {
            JsonObject card=item.getAsJsonObject();
            String cardId=card.get("id").getAsString();
            String type=card.has("type")?card.get("type").getAsString():"";
            if((type.equals("CURSE") && !EventRules.unremovable(cardId))
                    || cardId.equals("Strike_R") || cardId.equals("Defend_R") || cardId.equals("Bash"))junk++;
            selfDamage |= cardId.equals("Bloodletting") || cardId.equals("Offering")
                || cardId.equals("Hemokinesis") || cardId.equals("Brutality")
                || cardId.equals("Combust") || cardId.equals("Pain");
        }
        // ponytail: presence is a coarse synergy threshold; use trigger frequency if this proves too generous.
        if(id.equals("Runic Cube"))return selfDamage?8:3;
        return junk>=2?8:junk==1?6:2;
    }

    /** Index of the best boss relic, ties broken by {@code tiebreak}; -1 when every offer is worth skipping. */
    static int bestBoss(String[] ids, float[] tiebreak) {
        int best = -1;
        for (int i = 0; i < ids.length; i++) {
            if (score(ids[i]) < SKIP_BELOW) continue;
            if (best < 0 || score(ids[i]) > score(ids[best])
                    || (score(ids[i]) == score(ids[best]) && tiebreak[i] > tiebreak[best])) best = i;
        }
        return best;
    }

    /** True when the Sapphire Key is worth more than the relic it replaces. */
    static boolean takeSapphireKey(String linkedRelic) {
        return SAPPHIRE_KEY > score(linkedRelic);
    }
}
