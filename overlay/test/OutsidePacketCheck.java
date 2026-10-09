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
