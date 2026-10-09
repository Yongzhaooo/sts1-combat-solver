package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Exercise the public-state map adapter with a recorded original-game map and deck. */
public final class OutsidePacketCheck {
    public static void main(String[] args) throws Exception {
        JsonObject state;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(args[0]), StandardCharsets.UTF_8)) {
            state = new JsonParser().parse(reader).getAsJsonObject();
        }
        JsonObject game = state.getAsJsonObject("game_state");
        state.addProperty("ready_for_command", true);
        game.addProperty("screen_type", "MAP");
        game.addProperty("room_type", "EventRoom");
        JsonArray nodes = new JsonArray();
        for (JsonElement item : game.getAsJsonArray("map")) {
            JsonObject node = item.getAsJsonObject();
            if (node.get("y").getAsInt() == 0) nodes.add(node);
        }
        JsonObject screen = new JsonObject();
        screen.add("next_nodes", nodes);
        screen.addProperty("boss_available", false);
        game.add("screen_state", screen);
        JsonObject visible = new JsonObject();
        visible.addProperty("screen_type", "MAP");
        visible.addProperty("x", -1); visible.addProperty("y", -1);
        visible.addProperty("purge_base_cost", 75);
        visible.add("burning_elites", new JsonArray());
        visible.add("path_taken", new JsonArray());
        visible.add("bottled_deck_indices", new JsonArray());
        OutsidePacket packet = OutsidePacket.build(Distill2.load(), state, visible);
        if (packet.choices.size() != nodes.size() || packet.choices.size() < 2)
            throw new AssertionError("Map candidates drifted");
        Distill2.Result result = packet.score();
        Distill2.Result policy = packet.policyScore();
        List<OutsidePacket.Step> recommended=OutsidePacket.plannedRoute(Distill2.load(),state,visible,-1);
        if(packet.mapTargetX(policy.best)!=recommended.get(0).x)
            throw new AssertionError("Executed map choice differs from drawn plan");
        if (result.best < 0 || result.best >= packet.choices.size())
            throw new AssertionError("Invalid student selection");
        for (int i = 0; i < packet.choices.size(); i++)
            if (!packet.choices.get(i).command.equals("choose " + i))
                throw new AssertionError("Map command order drifted");
        int firstX=nodes.get(result.best).getAsJsonObject().get("x").getAsInt();
        long started=System.nanoTime();
        List<OutsidePacket.Step> route=OutsidePacket.plannedRoute(Distill2.load(),state,visible,firstX);
        double planMs=(System.nanoTime()-started)/1e6;
        if(route.size()!=15 || route.get(0).x!=firstX || route.get(0).y!=0)
            throw new AssertionError("Route must start at the chosen node and reach the boss row");
        for(int i=1;i<route.size();i++) {
            OutsidePacket.Step previous=route.get(i-1), next=route.get(i);
            if(next.y!=previous.y+1)throw new AssertionError("Route skipped a floor");
            if(!(next.share>0 && next.share<=1))throw new AssertionError("Route share out of range");
            boolean linked=false;
            for(JsonElement item:game.getAsJsonArray("map")) {
                JsonObject node=item.getAsJsonObject();
                if(node.get("x").getAsInt()!=previous.x || node.get("y").getAsInt()!=previous.y)continue;
                for(JsonElement child:node.getAsJsonArray("children")) {
                    JsonObject target=child.getAsJsonObject();
                    if(target.get("x").getAsInt()==next.x && target.get("y").getAsInt()==next.y)linked=true;
                }
            }
            if(!linked)throw new AssertionError("Route used an absent edge");
        }
        // Standing on the planned node, replan a legal suffix rather than lock the opening.
        OutsidePacket.Step second=route.get(0);
        visible.addProperty("x",second.x); visible.addProperty("y",second.y);
        int floor=game.get("floor").getAsInt();
        game.addProperty("floor",floor+1);
        for(JsonElement item:game.getAsJsonArray("map")) {
            JsonObject node=item.getAsJsonObject();
            if(node.get("x").getAsInt()==second.x && node.get("y").getAsInt()==second.y)
            {
                visible.getAsJsonArray("path_taken").add(node.get("symbol").getAsString());
                screen.add("next_nodes",node.getAsJsonArray("children"));
            }
        }
        List<OutsidePacket.Step> replanned=OutsidePacket.plannedRoute(Distill2.load(),state,visible,-1);
        if(replanned.size()!=14 || replanned.get(0).y!=1)
            throw new AssertionError("Replan must cover the remaining connected map");
        boolean reachable=false;
        for(JsonElement e:screen.getAsJsonArray("next_nodes"))
            if(e.getAsJsonObject().get("x").getAsInt()==replanned.get(0).x)reachable=true;
        if(!reachable)throw new AssertionError("Replan first node is unreachable");
        visible.addProperty("x",-1); visible.addProperty("y",-1);
        visible.add("path_taken",new JsonArray()); game.addProperty("floor",floor);
        JsonObject neow = new JsonObject();
        neow.addProperty("event_id", "Neow Event");
        game.add("screen_state", neow);
        game.addProperty("room_type", "NeowRoom");
        visible.addProperty("screen_type", "EVENT");
        JsonArray talk = new JsonArray();
        talk.add("Talk");
        visible.add("choices", talk);
        OutsidePacket introduction = OutsidePacket.build(Distill2.load(), state, visible);
        if(!java.util.Arrays.equals(introduction.score().scores,introduction.policyScore().scores))
            throw new AssertionError("Non-map policy changed");
        if (introduction.choices.size() != 1 || !"choose 0".equals(introduction.choices.get(0).command))
            throw new AssertionError("Neow introduction must advance before the reward options");
        visible.addProperty("neow_screen", 0);
        JsonArray rewards = new JsonArray();
        for (String bonus : new String[]{"THREE_CARDS", "HUNDRED_GOLD", "REMOVE_CARD", "BOSS_RELIC"}) {
            JsonObject reward = new JsonObject();
            reward.addProperty("bonus", bonus); reward.addProperty("drawback", "NONE");
            rewards.add(reward);
        }
        visible.add("neow_options", rewards);
        JsonArray four = new JsonArray();
        for (int i = 0; i < 4; i++) four.add("Blessing " + i);
        visible.add("choices", four);
        visible.addProperty("neow_screen", 3);
        if (OutsidePacket.build(Distill2.load(), state, visible).choices.size() != 4)
            throw new AssertionError("Neow blessing stage must offer all four rewards");
        JsonArray leave = new JsonArray();
        leave.add("Leave");
        visible.add("choices", leave);
        visible.addProperty("neow_screen", 99); // rewards stay populated after the pick.
        OutsidePacket leaving = OutsidePacket.build(Distill2.load(), state, visible);
        if (leaving.choices.size() != 1 || !"choose 0".equals(leaving.choices.get(0).command))
            throw new AssertionError("Post-blessing Neow must press Leave");
        visible.add("choices", new JsonArray());
        try {
            OutsidePacket.build(Distill2.load(), state, visible);
            throw new AssertionError("Neow without buttons must wait");
        } catch (OutsidePacket.NotReady expected) { }
        visible.add("choices", talk);
        visible.remove("neow_screen"); visible.remove("neow_options");
        neow.addProperty("body_text", "animated dialogue");
        if (!introduction.sameDecision(OutsidePacket.build(Distill2.load(), state, visible)))
            throw new AssertionError("Irrelevant event text must not reset the auto timer");
        game.addProperty("gold", game.get("gold").getAsInt()+1);
        if (introduction.sameDecision(OutsidePacket.build(Distill2.load(), state, visible)))
            throw new AssertionError("Changed decision input must reset the auto timer");
        // Single-button follow-up pages (Colosseum, the Heart door) click through without the network,
        // and a new page must not look like the unchanged decision that was just submitted.
        JsonObject colosseum = new JsonObject();
        colosseum.addProperty("event_id", "Colosseum");
        colosseum.addProperty("body_text", "You wake up in an arena.");
        game.add("screen_state", colosseum);
        game.addProperty("room_type", "EventRoom");
        JsonArray cont = new JsonArray();
        cont.add("Continue");
        visible.add("choices", cont);
        OutsidePacket firstPage = OutsidePacket.build(Distill2.load(), state, visible);
        if (firstPage.choices.size() != 1 || !"choose 0".equals(firstPage.choices.get(0).command))
            throw new AssertionError("A single-button event page must be clicked through");
        colosseum.addProperty("body_text", "Let the fight begin.");
        if (firstPage.sameDecision(OutsidePacket.build(Distill2.load(), state, visible)))
            throw new AssertionError("A new event page must not look like the submitted one");
        colosseum.addProperty("event_id", "Spire Heart");
        if (OutsidePacket.build(Distill2.load(), state, visible).choices.size() != 1)
            throw new AssertionError("An event the network has no ID for must still advance");
        JsonObject light = new JsonObject();
        light.addProperty("event_id", "Shining Light");
        game.add("screen_state", light);
        if (OutsidePacket.build(Distill2.load(), state, visible).choices.size() != 1)
            throw new AssertionError("Shining Light result page has no HP cost and must still advance");
        JsonObject dealer = new JsonObject();
        dealer.addProperty("event_id", "Drug Dealer");
        game.add("screen_state", dealer);
        JsonArray dealerButtons = new JsonArray();
        dealerButtons.add("Try J.A.X."); dealerButtons.add("Transform"); dealerButtons.add("Mutagens");
        visible.add("choices", dealerButtons);
        OutsidePacket dealerPacket = OutsidePacket.build(Distill2.load(), state, visible);
        if (dealerPacket.policyScore().best != 1)
            throw new AssertionError("Drug Dealer must transform, never take J.A.X.");
        JsonArray savedPotions = game.getAsJsonArray("potions");
        JsonArray carried = new JsonArray();
        for(String id : new String[]{"Strength Potion", "Dexterity Potion"}) {
            JsonObject potion = new JsonObject();
            potion.addProperty("id", id); potion.addProperty("name", id);
            potion.addProperty("can_use", false); potion.addProperty("can_discard", true);
            potion.addProperty("requires_target", false); carried.add(potion);
        }
        game.add("potions", carried);
        // Event rules must count real buttons, excluding the two appended potion actions.
        OutsidePacket withPotions = OutsidePacket.build(Distill2.load(), state, visible);
        if(withPotions.choices.size()!=5 || withPotions.policyScore().best!=1 || withPotions.ruleNote==null)
            throw new AssertionError("Drug Dealer rule bypassed while carrying potions");
        JsonObject addict = new JsonObject(); addict.addProperty("event_id", "Addict");
        game.add("screen_state", addict);
        int savedGold = game.get("gold").getAsInt();
        for(int gold : new int[]{84,85}) {
            game.addProperty("gold", gold);
            JsonArray buttons = new JsonArray();
            if(gold>=85)buttons.add("Buy relic"); buttons.add("Steal (Shame)"); buttons.add("Leave");
            visible.add("choices", buttons);
            OutsidePacket noShame = OutsidePacket.build(Distill2.load(), state, visible);
            int expected = gold>=85?0:1;
            if(noShame.policyScore().best!=expected || noShame.ruleNote==null)
                throw new AssertionError("Addict must never steal with potion actions present");
        }
        game.addProperty("gold", savedGold);
        // Reproduce the reported Cube/Bark/Cage boss offer, with no self-damage.
        JsonArray savedDeck = game.getAsJsonArray("deck");
        JsonArray plainDeck = new JsonArray();
        for(String id : new String[]{"Strike_R", "Defend_R"}) {
            JsonObject card = new JsonParser().parse(savedDeck.get(0).toString()).getAsJsonObject();
            card.addProperty("id", id); card.addProperty("type", id.equals("Strike_R")?"ATTACK":"SKILL");
            plainDeck.add(card);
        }
        game.add("deck", plainDeck);
        JsonObject bossScreen = new JsonObject(); JsonArray bossRelics = new JsonArray();
        for(String id : new String[]{"Runic Cube", "SacredBark", "Empty Cage"}) {
            JsonObject relic = new JsonObject(); relic.addProperty("id",id); relic.addProperty("name",id);
            bossRelics.add(relic);
        }
        bossScreen.add("relics",bossRelics); game.add("screen_state",bossScreen);
        visible.addProperty("screen_type","BOSS_REWARD");
        OutsidePacket boss = OutsidePacket.build(Distill2.load(),state,visible);
        if(boss.policyScore().best!=2)throw new AssertionError("Choose Cage over an unsupported Cube");
        if(RelicScore.bossScore("Runic Cube",plainDeck)!=3 || RelicScore.bossScore("Empty Cage",plainDeck)!=8)
            throw new AssertionError("Deck-aware Cube/Cage scores");
        JsonObject blood = new JsonParser().parse(plainDeck.get(0).toString()).getAsJsonObject();
        blood.addProperty("id","Bloodletting"); blood.addProperty("type","SKILL");
        JsonArray cleanDeck = new JsonArray(); cleanDeck.add(blood);
        if(RelicScore.bossScore("Runic Cube",cleanDeck)!=8 || RelicScore.bossScore("Empty Cage",cleanDeck)!=2)
            throw new AssertionError("Existing self-damage earns Cube value; a clean deck lowers Cage value");
        if(RelicScore.score("StrikeDummy",cleanDeck)!=0 || RelicScore.score("StrikeDummy",plainDeck)!=3)
            throw new AssertionError("Strike Dummy must drop in value with zero/one strike");
        JsonObject pommel = new JsonParser().parse(blood.toString()).getAsJsonObject();
        pommel.addProperty("id","Pommel Strike"); cleanDeck.add(pommel);
        if(RelicScore.score("StrikeDummy",cleanDeck)!=3)
            throw new AssertionError("Pommel Strike still benefits from Strike Dummy");
        JsonObject keyState = new JsonParser().parse(state.toString()).getAsJsonObject();
        JsonObject keyVisible = new JsonParser().parse(visible.toString()).getAsJsonObject();
        JsonObject keyGame = keyState.getAsJsonObject("game_state");
        keyGame.addProperty("act",3); keyGame.addProperty("solver_final_act",true);
        keyGame.addProperty("room_type","TreasureRoom");
        JsonObject keys = new JsonObject(); keys.addProperty("sapphire",false); keyGame.add("keys",keys);
        keyVisible.addProperty("x",0); keyVisible.addProperty("y",8);
        JsonArray chestMap = new JsonArray();
        JsonObject currentChest = new JsonObject(); currentChest.addProperty("x",0); currentChest.addProperty("y",8);
        currentChest.addProperty("symbol","T"); currentChest.add("children",new JsonArray()); chestMap.add(currentChest);
        keyGame.add("map",chestMap);
        JsonObject keyScreen = new JsonObject(); JsonArray keyRewards = new JsonArray();
        JsonObject powerful = new JsonObject(); powerful.addProperty("id","Pandora's Box"); powerful.addProperty("name","Pandora's Box");
        JsonObject offered = new JsonObject(); offered.addProperty("reward_type","RELIC"); offered.add("relic",powerful); keyRewards.add(offered);
        JsonObject blue = new JsonObject(); blue.addProperty("reward_type","SAPPHIRE_KEY"); blue.add("link",powerful); keyRewards.add(blue);
        keyScreen.add("rewards",keyRewards); keyGame.add("screen_state",keyScreen);
        keyVisible.addProperty("screen_type","COMBAT_REWARD");
        OutsidePacket lastChest = OutsidePacket.build(Distill2.load(),keyState,keyVisible);
        if(lastChest.policyScore().best!=1 || lastChest.ruleNote==null)
            throw new AssertionError("Missing blue key at final Act 3 chest must beat even a 10-point relic and potion actions");
        keys.addProperty("sapphire",true);
        if(OutsidePacket.build(Distill2.load(),keyState,keyVisible).policyScore().best!=0)
            throw new AssertionError("Already-owned blue key must not force the key");
        keys.addProperty("sapphire",false); keyGame.addProperty("act",2);
        if(OutsidePacket.build(Distill2.load(),keyState,keyVisible).policyScore().best!=0)
            throw new AssertionError("Earlier-act chest keeps the value comparison");
        keyGame.addProperty("act",3);
        JsonObject laterChest = new JsonObject(); laterChest.addProperty("x",0); laterChest.addProperty("y",9);
        laterChest.addProperty("symbol","T"); laterChest.add("children",new JsonArray()); chestMap.add(laterChest);
        JsonObject edge = new JsonObject(); edge.addProperty("x",0); edge.addProperty("y",9); currentChest.getAsJsonArray("children").add(edge);
        if(OutsidePacket.build(Distill2.load(),keyState,keyVisible).policyScore().best!=0)
            throw new AssertionError("A reachable later chest keeps the value comparison");
        currentChest.add("children",new JsonArray()); // Unreachable chest is not another opportunity.
        keyVisible.addProperty("screen_type","CHEST"); keyVisible.addProperty("chest_size",0);
        JsonArray open = new JsonArray(); open.add("Open"); open.add("Leave"); keyVisible.add("choices",open);
        OutsidePacket unopened = OutsidePacket.build(Distill2.load(),keyState,keyVisible);
        if(unopened.policyScore().best!=0 || unopened.ruleNote==null)
            throw new AssertionError("Missing blue key forces opening the final chest");
        game.add("deck",savedDeck); game.add("potions",savedPotions);
        JsonObject rewardScreen = new JsonObject();
        JsonArray rewardList = new JsonArray();
        rewardScreen.add("rewards", rewardList);
        game.add("screen_state", rewardScreen);
        game.addProperty("room_type", "MonsterRoom");
        visible.addProperty("screen_type", "COMBAT_REWARD");
        visible.add("choices", new JsonArray());
        JsonArray proceed = new JsonArray();
        proceed.add("proceed");
        state.add("available_commands", proceed);
        if (!"proceed".equals(OutsidePacket.build(Distill2.load(), state, visible).choices.get(0).command))
            throw new AssertionError("Emptied combat rewards must press proceed");
        JsonObject cardReward = new JsonObject();
        cardReward.addProperty("reward_type", "CARD");
        rewardList.add(cardReward);
        if (!"choose 0".equals(OutsidePacket.build(Distill2.load(), state, visible).choices.get(0).command))
            throw new AssertionError("An unseen card reward must be opened");
        // The reward list may put cards/gold before an egg; equip the relic first.
        for(String egg : new String[]{"Molten Egg 2", "Toxic Egg 2", "Frozen Egg 2"}) {
            JsonObject goldReward = new JsonObject();
            goldReward.addProperty("reward_type", "GOLD"); goldReward.addProperty("gold", 30);
            rewardList.add(goldReward);
            JsonObject relicReward = new JsonObject(), relic = new JsonObject();
            relicReward.addProperty("reward_type", "RELIC");
            relic.addProperty("id", egg); relic.addProperty("name", egg);
            relicReward.add("relic", relic); rewardList.add(relicReward);
            OutsidePacket eggs = OutsidePacket.build(Distill2.load(), state, visible);
            if(eggs.choices.size()!=1 || !"choose 2".equals(eggs.choices.get(0).command))
                throw new AssertionError("Equip " + egg + " before card/gold rewards");
            JsonObject keyReward = new JsonObject();
            keyReward.addProperty("reward_type", "SAPPHIRE_KEY"); keyReward.add("link", relic);
            rewardList.add(keyReward);
            OutsidePacket linked = OutsidePacket.build(Distill2.load(), state, visible);
            if(linked.choices.size()!=2 || !"choose 3".equals(linked.choices.get(1).command))
                throw new AssertionError("Relic priority must preserve the linked key alternative");
            rewardList.remove(3); rewardList.remove(2); rewardList.remove(1);
            if(!"choose 0".equals(OutsidePacket.build(Distill2.load(), state, visible).choices.get(0).command))
                throw new AssertionError("Open card reward after equipping the relic");
        }
        visible.addProperty("skipped_card_rewards", 1);
        if (!"proceed".equals(OutsidePacket.build(Distill2.load(), state, visible).choices.get(0).command))
            throw new AssertionError("A skipped card reward must not be reopened");
        visible.addProperty("screen_type", "SHOP_ROOM");
        JsonArray merchant = new JsonArray();
        merchant.add("shop");
        visible.add("choices", merchant);
        if (!"choose 0".equals(OutsidePacket.build(Distill2.load(), state, visible).choices.get(0).command))
            throw new AssertionError("A fresh merchant room must open the shop");
        visible.addProperty("shop_left", true);
        if (!"proceed".equals(OutsidePacket.build(Distill2.load(), state, visible).choices.get(0).command))
            throw new AssertionError("After leaving the shop the pilot must proceed, not reopen it");
        JsonObject purgeScreen = new JsonObject();
        JsonArray purgeCards = new JsonArray();
        for (String[] spec : new String[][]{{"Demon Form", "POWER"}, {"Bloodletting", "SKILL"},
                {"Strike_R", "ATTACK"}, {"Bash", "ATTACK"}}) {
            JsonObject card = game.getAsJsonArray("deck").get(0).getAsJsonObject();
            JsonObject copy = new JsonParser().parse(card.toString()).getAsJsonObject();
            copy.addProperty("id", spec[0]); copy.addProperty("name", spec[0]); copy.addProperty("type", spec[1]);
            purgeCards.add(copy);
        }
        purgeScreen.add("cards", purgeCards);
        purgeScreen.addProperty("for_purge", true);
        purgeScreen.addProperty("num_cards", 1);
        game.add("screen_state", purgeScreen);
        game.addProperty("room_type", "EventRoom");
        visible.addProperty("screen_type", "GRID");
        visible.add("choices", new JsonArray());
        state.add("available_commands", new JsonArray());
        OutsidePacket purge = OutsidePacket.build(Distill2.load(), state, visible);
        if (purge.choices.size() != 2 || !purge.choices.get(0).command.equals("choose 2")
                || !purge.choices.get(1).command.equals("choose 3"))
            throw new AssertionError("Event removal must offer only starter cards and curses");
        System.out.printf("PASS: map packet, reviewed rule route to boss (%.0f ms) and Neow stages%n", planMs);
    }
}
