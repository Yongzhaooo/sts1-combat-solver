package sts1solver;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Phase boundaries, actual replacement advice and disabled resurrection. */
public final class PotionRewardsCheck {
    static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    static JsonObject bottle(String id) {
        JsonObject p=new JsonObject();p.addProperty("id",id);p.addProperty("name",id);
        p.addProperty("can_discard",true);return p;
    }
    public static void main(String[] args) throws Exception {
        JsonObject game=new JsonObject();game.addProperty("act",3);game.addProperty("floor",42);
        JsonArray deck=new JsonArray();deck.add(bottle("Demon Form"));game.add("deck",deck);
        String[] priority={"FairyPotion","Swift Potion","Gambler's Brew","DistilledChaos","PowerPotion",
            "LiquidMemories","CultistPotion","DuplicationPotion","GhostInAJar"};
        int[] early=new int[priority.length];
        for(int i=0;i<priority.length;i++)early[i]=PotionRewards.value(priority[i],game);
        game.addProperty("floor",43);
        for(int i=0;i<priority.length;i++)check(PotionRewards.value(priority[i],game)>early[i],priority[i]+" late boost");
        check(PotionRewards.value("FairyPotion",game)>PotionRewards.value("Fruit Juice",game),"resurrection priority");
        check(PotionRewards.value("Fire Potion",game)==14,"damage potion unchanged");
        check(PotionRewards.value("UnknownPotion",game)==-1,"unknown remains manual");
        game.addProperty("act",2);game.addProperty("floor",50);
        for(int i=0;i<priority.length;i++)check(PotionRewards.value(priority[i],game)==early[i],"Act 2 unchanged");
        game.addProperty("act",4);game.addProperty("floor",54);
        check(PotionRewards.value("PowerPotion",game)==52,"Act 4 shop power potion boost");
        game.addProperty("floor",55);
        check(PotionRewards.value("Swift Potion",game)==52,"spear/shield preparation boost");
        game.addProperty("floor",56);
        check(PotionRewards.value("Swift Potion",game)==34,"pre-spear scope boundary");
        game.addProperty("act",3);game.addProperty("floor",43);
        game.addProperty("screen_type","COMBAT_REWARD");game.addProperty("room_phase","COMPLETE");
        JsonArray inventory=new JsonArray();inventory.add(bottle("CultistPotion"));inventory.add(bottle("FairyPotion"));
        game.add("potions",inventory);
        JsonObject screen=new JsonObject(),reward=new JsonObject();
        reward.addProperty("reward_type","POTION");reward.add("potion",bottle("PowerPotion"));
        JsonArray rewards=new JsonArray();rewards.add(reward);screen.add("rewards",rewards);game.add("screen_state",screen);
        check(!PotionRewards.advise(game).actionable(),"do not replace saved scaling with power potion at equal value");
        inventory.set(0,bottle("Dexterity Potion"));
        check(PotionRewards.advise(game).actionable() && PotionRewards.advise(game).slot==0,"late power replaces generic stat bottle");
        reward.add("potion",bottle("Swift Potion"));
        check(PotionRewards.advise(game).actionable() && PotionRewards.advise(game).slot==0,"late draw replaces generic stat bottle");
        game.addProperty("floor",42);
        inventory.set(0,bottle("CultistPotion"));
        check(!PotionRewards.advise(game).actionable(),"early draw does not replace scaling at equal value");
        game.addProperty("floor",43);
        JsonArray relics=new JsonArray();relics.add(bottle("Mark of the Bloom"));game.add("relics",relics);
        check(PotionRewards.value("FairyPotion",game)==0,"Bloom disables late resurrection bonus");
        check(PotionRewards.value("BloodPotion",game)==0,"Bloom healing unchanged");
        relics.add(bottle("Sozu"));
        check(!PotionRewards.advise(game).actionable(),"Sozu still blocks pickup");
        JsonObject fixture=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject shopGame=fixture.getAsJsonObject("state").getAsJsonObject("game_state");
        JsonArray shop=shopGame.getAsJsonObject("screen_state").getAsJsonArray("potions");
        shop.get(0).getAsJsonObject().addProperty("id","PowerPotion");
        shop.get(1).getAsJsonObject().addProperty("id","Swift Potion");
        shop.get(2).getAsJsonObject().addProperty("id","Fire Potion");
        shopGame.addProperty("act",3);
        for(int floor:new int[]{42,43}) {
            shopGame.addProperty("floor",floor);
            OutsidePacket packet=OutsidePacket.build(Distill2.load(),fixture.getAsJsonObject("state"),fixture.getAsJsonObject("visible"));
            Distill2.Result base=packet.score(), adjusted=packet.policyScore();
            int seen=0;
            for(int i=0;i<packet.choices.size();i++) {
                long bits=packet.choices.get(i).bits;
                if((bits>>27)!=3)continue;
                int index=(int)(bits&((1L<<27)-1));
                float expected=floor==42?0:index==0?2.4f:index==1?1.8f:0;
                check(Math.abs(adjusted.scores[i]-base.scores[i]-expected)<0.0001f,"actual shop priority bonus");
                seen++;
            }
            check(seen==3,"all three affordable shop potions tested");
        }
        System.out.println("PASS: late potion values, phase boundaries, replacement advice, unknown/Sozu/Bloom");
    }
}
