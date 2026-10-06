package sts1solver;

import com.google.gson.*;
import java.util.*;

/** Reward-only policy and acknowledgement checks; no game mutation here. */
final class PotionRewards {
    static String string(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : "";
    }
    static boolean flag(JsonObject value, String name) {
        return value.has(name) && value.get(name).getAsBoolean();
    }
    static boolean hasRelic(JsonObject game, String id) {
        if(game.has("relics"))for(JsonElement item:game.getAsJsonArray("relics"))
            if(id.equals(string(item.getAsJsonObject(),"id")))return true;
        return false;
    }
    static boolean screen(JsonObject game) {
        return "COMBAT_REWARD".equals(string(game,"screen_type"))
            && !"COMBAT".equals(string(game,"room_phase"));
    }
    static int value(String id, JsonObject game) {
        int hits=2;
        boolean burst=false;
        if(game.has("deck"))for(JsonElement item:game.getAsJsonArray("deck")) {
            String card=string(item.getAsJsonObject(),"id");
            if(Arrays.asList("Fiend Fire","Whirlwind","Pummel","Sword Boomerang").contains(card))hits=5;
            if(Arrays.asList("Fiend Fire","Immolate","Impervious","Demon Form","Limit Break").contains(card))burst=true;
        }
        // ponytail: hand-set relative keep-values, not HP or win probabilities.
        // Replace with encounter rollouts after future-battle import is validated.
        switch(id) {
            case "Fire Potion": return game.has("act") && game.get("act").getAsInt()>1?14:20;
            case "Explosive Potion": return 18;
            case "Block Potion": return 16;
            case "SteroidPotion": return 5*hits;
            case "SpeedPotion": return hasRelic(game,"OrangePellets")?28:12;
            case "Strength Potion": return 18+2*hits;
            case "Dexterity Potion": return 24;
            case "Energy Potion": return 20;
            case "Swift Potion": return 34;
            case "Gambler's Brew": return 34;
            case "DistilledChaos": return 36;
            case "FearPotion": return 24;
            case "Weak Potion": return 22;
            case "Ancient Potion": return game.has("act") && game.get("act").getAsInt()==2?26:12;
            case "AttackPotion": return 19;
            case "SkillPotion": return 23;
            case "PowerPotion": return 28;
            case "ColorlessPotion": return 23;
            case "DuplicationPotion": return burst?40:32;
            case "LiquidMemories": return 30;
            case "LiquidBronze": return 16;
            case "EssenceOfSteel": return 12;
            case "Regen Potion": return hasRelic(game,"Mark of the Bloom")?0:28;
            case "BloodPotion": return hasRelic(game,"Mark of the Bloom")?0:32;
            case "Fruit Juice": return 60;
            case "FairyPotion": return hasRelic(game,"Mark of the Bloom")?0:70;
            case "CultistPotion": return 34;
            case "EntropicBrew": return 40;
            case "SneckoOil": return 22;
            case "GhostInAJar": return 60;
            case "HeartOfIron": return 36;
            case "ElixirPotion": return 18;
            case "SmokeBomb": return 12;
            default: return -1; // Unknown/modded bottles need manual comparison.
        }
    }
    static final class Advice {
        final int reward, slot;
        final String incoming, outgoing, message;
        Advice(int reward, int slot, String incoming, String outgoing, String message) {
            this.reward=reward;this.slot=slot;this.incoming=incoming;this.outgoing=outgoing;this.message=message;
        }
        boolean actionable(){return reward>=0 && slot>=0;}
    }
    static boolean hasCeiling(JsonObject game) {
        Set<String> cards=new HashSet<>();
        if(game.has("deck"))for(JsonElement item:game.getAsJsonArray("deck")) {
            JsonObject card=item.getAsJsonObject();
            String id=string(card,"id").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
            cards.add(id);
            if(id.equals("searingblow") && card.has("upgrades") && card.get("upgrades").getAsInt()>=4)return true;
        }
        return cards.contains("demonform") || cards.contains("spotweakness")
            || (cards.contains("limitbreak") && (cards.contains("inflame")
                || hasRelic(game,"Vajra")))
            || (cards.contains("barricade") && cards.contains("entrench") && cards.contains("bodyslam"))
            || (cards.contains("corruption") && (cards.contains("darkembrace") || hasRelic(game,"Dead Branch")));
    }
    static int comboValue(List<String> ids, JsonObject game) {
        if(hasCeiling(game))return 0;
        int draw=0, options=0;
        for(String id:ids) {
            if(Arrays.asList("Swift Potion","Gambler's Brew").contains(id))draw++;
            if(Arrays.asList("PowerPotion","SkillPotion","AttackPotion","ColorlessPotion",
                    "EntropicBrew","SneckoOil","CultistPotion","Strength Potion").contains(id))options++;
        }
        // Relative inventory points, never HP: reward one complementary pair.
        // Multiple emergency hand-access bottles remain valuable on their own.
        return draw>0 && options>0?12:0;
    }
    static int inventoryValue(List<String> ids, JsonObject game) {
        int total=comboValue(ids,game);
        for(String id:ids)total+=Math.max(0,value(id,game));
        return total;
    }
    static Advice advise(JsonObject game) {
        Advice none=new Advice(-1,-1,"","",I18n.t("药水：保留现有库存"));
        if(!screen(game) || !game.has("potions") || !game.has("screen_state"))return none;
        if(hasRelic(game,"Sozu"))return new Advice(-1,-1,"","",I18n.t("添水：无法领取药水，保留库存"));
        JsonArray potions=game.getAsJsonArray("potions");
        JsonArray rewards=game.getAsJsonObject("screen_state").getAsJsonArray("rewards");
        if(rewards==null)return none;
        List<String> inventory=new ArrayList<>();
        int emptySlot=-1;
        for(int i=0;i<potions.size();i++) {
            JsonObject p=potions.get(i).getAsJsonObject();
            String id=string(p,"id");inventory.add(id);
            if(id.equals("Potion Slot") && emptySlot<0)emptySlot=i;
        }
        int before=inventoryValue(inventory,game);
        Advice best=none;
        int gain=2;
        for(int i=0;i<rewards.size();i++) {
            JsonObject r=rewards.get(i).getAsJsonObject();
            if(!"POTION".equals(string(r,"reward_type")) || !r.has("potion"))continue;
            JsonObject p=r.getAsJsonObject("potion");
            int score=value(string(p,"id"),game);
            if(emptySlot<0 && score<0){if(!best.actionable())best=new Advice(-1,-1,"","",I18n.t("未知药水：请手动比较"));continue;}
            for(int slot=0;slot<potions.size();slot++) {
                JsonObject old=potions.get(slot).getAsJsonObject();
                boolean empty=slot==emptySlot;
                if(emptySlot>=0 && !empty)continue;
                if(!empty && (!flag(old,"can_discard") || value(string(old,"id"),game)<0))continue;
                List<String> candidate=new ArrayList<>(inventory);
                candidate.set(slot,string(p,"id"));
                int after=inventoryValue(candidate,game);
                int improvement=empty?100+Math.max(0,score):after-before;
                if(improvement<=gain)continue;
                gain=improvement;
                String text=empty?I18n.t("领取 ")+string(p,"name"):I18n.t("换药：")+string(old,"name")+" → "+string(p,"name");
                if(!empty)text+=comboValue(candidate,game)>comboValue(inventory,game)
                    ?(comboValue(candidate,game)>0?I18n.t("（缺上限，保留抽牌＋变量）"):I18n.t("（缺上限，减少重复抽牌）"))
                    :I18n.t("（组合分 ")+before+" → "+after+"）";
                best=new Advice(i,slot,string(p,"id"),string(old,"id"),text);
            }
        }
        return best;
    }
    static String identity(JsonObject game) {
        return string(game,"seed")+":"+string(game,"floor");
    }
    static final class Pending {
        final String identity;
        final JsonArray before;
        final Advice advice;
        final long started;
        Pending(JsonObject game, Advice advice, long started) {
            this.identity=identity(game);this.before=new JsonParser().parse(game.get("potions").toString()).getAsJsonArray();
            this.advice=advice;this.started=started;
        }
        // 1 = acknowledged, 0 = still pending, -1 = divergent screen/inventory.
        int check(JsonObject game) {
            if(!screen(game) || !identity.equals(identity(game)) || !game.has("potions"))return -1;
            JsonArray actual=game.getAsJsonArray("potions");
            if(actual.size()!=before.size())return -1;
            for(int i=0;i<actual.size();i++) {
                String id=string(actual.get(i).getAsJsonObject(),"id");
                if(i==advice.slot) {
                    if(!id.equals(advice.incoming) && !id.equals("Potion Slot"))return -1;
                } else if(!id.equals(string(before.get(i).getAsJsonObject(),"id")))return -1;
            }
            return advice.incoming.equals(string(actual.get(advice.slot).getAsJsonObject(),"id"))?1:0;
        }
    }
}
