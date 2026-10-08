package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public original-game state mapped to the frozen student's first decision types. */
final class OutsidePacket {
    static final class Choice {
        final long bits;
        final String command, label;
        final float[] descriptor = new float[807], route = new float[26];
        Choice(long bits, String command, String label) {
            this.bits = bits; this.command = command; this.label = label;
        }
    }

    /** A UI transition: wait for the next frame instead of stopping the pilot. */
    static final class NotReady extends IllegalStateException {
        NotReady(String message) { super(message); }
    }

    final float[] observation = new float[6843], extra = new float[20];
    final List<Choice> choices = new ArrayList<>();
    private final Distill2 model;
    private final JsonObject game, visible;
    private final JsonArray availableCommands;
    private final Map<Integer, JsonObject> nodes = new HashMap<>();
    private int flameX = -1, flameY = -1;

    private OutsidePacket(Distill2 model, JsonObject game, JsonObject visible, JsonArray availableCommands) {
        this.model = model; this.game = game; this.visible = visible;
        this.availableCommands = availableCommands;
        for (JsonElement item : array(game, "map")) {
            JsonObject node = item.getAsJsonObject();
            nodes.put(key(integer(node, "x"), integer(node, "y")), node);
        }
        JsonArray burning = array(visible, "burning_elites");
        if (burning.size() > 0) {
            flameX = integer(burning.get(0).getAsJsonObject(), "x");
            flameY = integer(burning.get(0).getAsJsonObject(), "y");
        }
    }

    static OutsidePacket build(Distill2 model, JsonObject state, JsonObject visible) {
        if (!state.get("in_game").getAsBoolean() || !state.get("ready_for_command").getAsBoolean())
            throw new NotReady("Outside decision is not ready");
        JsonObject game = state.getAsJsonObject("game_state");
        if (!"IRONCLAD".equals(string(game, "class")))
            throw new IllegalArgumentException("Student only supports Ironclad");
        OutsidePacket packet = new OutsidePacket(model, game, visible, array(state, "available_commands"));
        packet.observation();
        packet.extra();
        String screen = string(visible, "screen_type");
        if (screen.equals("MAP")) packet.mapChoices();
        else if (screen.equals("CARD_REWARD")) packet.cardChoices();
        else if (screen.equals("EVENT") && optionalInt(visible, "neow_screen", -1) == 3
                && array(visible, "neow_options").size() > 0)
            packet.neowChoices();
        else if (screen.equals("EVENT") && (visible.has("neow_screen")
                || string(object(game, "screen_state"), "event_id").equals("Neow Event")))
            packet.neowAdvance();
        else if (screen.equals("EVENT")) packet.eventChoices();
        else if (screen.equals("BOSS_REWARD")) packet.bossChoices();
        else if (screen.equals("REST")) packet.restChoices();
        else if (screen.equals("SHOP_SCREEN")) packet.shopChoices();
        else if (screen.equals("SHOP_ROOM") && bool(visible, "shop_left") && packet.hasCommand("proceed"))
            packet.directChoice("proceed", "Leave shop room");
        else if (screen.equals("SHOP_ROOM")) packet.directChoice("choose 0", "Enter shop");
        else if (screen.equals("COMBAT_REWARD")) packet.combatRewardChoices();
        else if (screen.equals("CHEST")) packet.chestChoices();
        else if (screen.equals("COMPLETE")) packet.directChoice("confirm", "Proceed");
        else if (screen.equals("GRID") && bool(object(game, "screen_state"), "confirm_up"))
            packet.directChoice("confirm", "Confirm");
        else if (screen.equals("GRID")) packet.selectChoices();
        else throw new IllegalArgumentException("Student screen not mapped yet: " + screen);
        packet.potionChoices();
        if (packet.choices.isEmpty()) throw new IllegalArgumentException("No legal student choices");
        return packet;
    }

    Distill2.Result score() {
        float[][] descriptions = new float[choices.size()][], routes = new float[choices.size()][];
        for (int i = 0; i < choices.size(); i++) {
            descriptions[i] = choices.get(i).descriptor;
            routes[i] = choices.get(i).route;
        }
        return model.score(observation, extra, descriptions, routes);
    }

    boolean sameDecision(OutsidePacket other) {
        if (other == null || !Arrays.equals(observation, other.observation)
                || !Arrays.equals(extra, other.extra) || choices.size() != other.choices.size()) return false;
        for (int i = 0; i < choices.size(); i++) {
            Choice a = choices.get(i), b = other.choices.get(i);
            if (a.bits != b.bits || !a.command.equals(b.command)
                    || !Arrays.equals(a.descriptor, b.descriptor)
                    || !Arrays.equals(a.route, b.route)) return false;
        }
        return true;
    }

    /** Map column of a node choice, or -1 for potion, boss and non-map choices. */
    int mapTargetX(int index) {
        JsonObject screen = object(game, "screen_state");
        if (!string(visible, "screen_type").equals("MAP") || index < 0 || index >= choices.size()
                || bool(screen, "boss_available") || !choices.get(index).command.startsWith("choose ")) return -1;
        JsonArray next = array(screen, "next_nodes");
        int node = Integer.parseInt(choices.get(index).command.substring(7));
        return node < next.size() ? integer(next.get(node).getAsJsonObject(), "x") : -1;
    }

    /** One planned map node; share is the student's softmax weight among that floor's options. */
    static final class Step {
        final int x, y;
        final float share;
        Step(int x, int y, float share) { this.x = x; this.y = y; this.share = share; }
    }

    /**
     * Student-greedy route to the boss row: at each later floor the frozen student picks among
     * the children as if it were standing there. HP, gold, deck and relics stay as they are now,
     * so the route is re-planned after every floor. firstX forces the next node (-1: let it pick).
     */
    static List<Step> plannedRoute(Distill2 model, JsonObject state, JsonObject visible, int firstX) {
        JsonObject game = state.getAsJsonObject("game_state");
        if (game == null || !"IRONCLAD".equals(string(game, "class"))) return Collections.emptyList();
        OutsidePacket map = new OutsidePacket(model, game, visible, new JsonArray());
        int x = optionalInt(visible, "x", -1), y = optionalInt(visible, "y", -1);
        if (y < -1 || y >= 14) return Collections.emptyList();
        List<Step> path = new ArrayList<>();
        List<String> taken = new ArrayList<>();
        for (JsonElement item : array(visible, "path_taken")) taken.add(item.getAsString());
        int floor = integer(game, "floor");
        while (y < 14) {
            List<Integer> next = new ArrayList<>();
            if (y < 0) { for (int column = 0; column < 7; column++) if (map.hasChildren(column, 0)) next.add(column); }
            else next.addAll(map.children(x, y));
            if (next.isEmpty()) break;
            OutsidePacket at = map.standingAt(x, y, next, floor, taken);
            Distill2.Result result = at.score();
            int pick = result.best;
            if (path.isEmpty() && firstX >= 0) pick = Math.max(0, next.indexOf(firstX));
            float top = Float.NEGATIVE_INFINITY, total = 0;
            for (float score : result.scores) top = Math.max(top, score);
            for (float score : result.scores) total += (float)Math.exp(score - top);
            x = next.get(pick); y++; floor++;
            path.add(new Step(x, y, (float)Math.exp(result.scores[pick] - top) / total));
            String symbol = string(map.nodes.get(key(x, y)), "symbol");
            taken.add(symbol);
        }
        return path;
    }

    /** The MAP decision the game would show after finishing the room at (x, y). */
    private OutsidePacket standingAt(int x, int y, List<Integer> next, int floor, List<String> taken) {
        JsonObject game = copy(this.game), visible = copy(this.visible), screen = new JsonObject();
        JsonArray nodes = new JsonArray();
        for (int column : next) nodes.add(this.nodes.get(key(column, y+1)));
        screen.add("next_nodes", nodes);
        screen.addProperty("boss_available", false);
        game.add("screen_state", screen);
        game.addProperty("screen_type", "MAP");
        game.addProperty("floor", floor);
        if (y >= 0) game.addProperty("room_type", roomClass(string(this.nodes.get(key(x, y)), "symbol")));
        visible.addProperty("screen_type", "MAP");
        visible.addProperty("x", x); visible.addProperty("y", y);
        JsonArray path = new JsonArray();
        for (String symbol : taken) path.add(symbol);
        visible.add("path_taken", path);
        OutsidePacket packet = new OutsidePacket(model, game, visible, new JsonArray());
        packet.observation();
        packet.extra();
        packet.mapChoices();
        return packet;
    }

    private static String roomClass(String symbol) {
        switch (symbol) {
            case "M": return "MonsterRoom";
            case "E": return "MonsterRoomElite";
            case "R": return "RestRoom";
            case "$": return "ShopRoom";
            case "T": return "TreasureRoom";
            default: return "EventRoom"; // "?" is unresolved until entered.
        }
    }

    private static JsonObject copy(JsonObject source) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) out.add(entry.getKey(), entry.getValue());
        return out;
    }

    private void observation() {
        int hp = integer(game, "current_hp"), maxHp = integer(game, "max_hp");
        int gold = integer(game, "gold"), act = integer(game, "act");
        JsonArray deck = array(game, "deck"), relics = array(game, "relics"), potions = array(game, "potions");
        int potionCount = 0;
        for (JsonElement p : potions) if (!emptyPotion(string(p.getAsJsonObject(), "id"))) potionCount++;
        int x = optionalInt(visible, "x", -1), y = optionalInt(visible, "y", -1);
        float[] o = observation;
        o[0] = hp / 200f; o[1] = maxHp / 200f; o[2] = gold / 1800f;
        o[3] = integer(game, "floor") / 60f; o[4] = act / 4f;
        o[5] = integer(game, "ascension_level") / 20f;
        o[6] = (x+1) / 7f; o[7] = (y+1) / 15f;
        o[8] = deck.size() / 100f; o[9] = relics.size() / 181f;
        o[10] = potionCount / 5f; o[11] = potions.size() / 5f;
        int purge = optionalInt(visible, "purge_base_cost", 75);
        o[12] = Math.max(0, (purge - 75) / 25) / 100f;
        JsonObject keys = object(game, "keys");
        o[32] = bool(keys, "emerald") ? 1 : 0;
        o[33] = bool(keys, "ruby") ? 1 : 0;
        o[34] = bool(keys, "sapphire") ? 1 : 0;
        int room = roomType(string(game, "room_type"));
        if (room >= 0) o[35+room] = 1;
        JsonArray path = array(visible, "path_taken");
        if (path.size() > 0) {
            int last = symbol(path.get(path.size()-1).getAsString());
            if (last >= 0) o[45+last] = 1;
        } else o[54] = 1; // Before the first node, the simulator's last room is INVALID.
        int screen = screenType(string(visible, "screen_type"));
        if (screen >= 0) o[55+screen] = 1;
        if (screen == 1 && (array(visible, "neow_options").size() > 0
                || string(object(game, "screen_state"), "event_id").equals("Neow Event"))) o[75+5] = 1;
        else if (screen == 1) {
            String event=string(object(game,"screen_state"),"event_id");
            o[75+model.id("events",event)] = 1;
            if(event.equals("Shining Light")) {
                int loss=optionalInt(visible,"event_hp_loss",-1);
                if(loss<0)throw new IllegalArgumentException("Shining Light HP cost is not visible");
                o[22]=loss/200f;
            }
        }
        int boss = bossType(string(game, "act_boss"));
        if (boss >= 0) o[65+boss] = 1;
        mapObservation();
        Set<Integer> bottled = new HashSet<>();
        for (JsonElement value : array(visible, "bottled_deck_indices")) bottled.add(value.getAsInt());
        List<Integer> searing = new ArrayList<>(), ritual = new ArrayList<>();
        for (int i = 0; i < deck.size(); i++) {
            JsonObject card = deck.get(i).getAsJsonObject();
            String name = string(card, "id");
            int id = model.id("cards", name), upgrades = optionalInt(card, "upgrades", 0);
            int face = id * 2 + (upgrades > 0 ? 1 : 0);
            o[3980+face] += 1/20f;
            o[4722+id] += upgrades / 1000f;
            int misc = optionalInt(card, "misc", 0);
            o[5093+id] += misc / 1000f;
            if (bottled.contains(i)) o[5496+face] += 1;
            if (name.equals("Searing Blow")) searing.add(upgrades);
            if (name.equals("Ritual Dagger")) ritual.add(misc);
        }
        Collections.sort(searing); Collections.sort(ritual);
        for (int i = 0; i < Math.min(16, searing.size()); i++) o[5464+i] = searing.get(i)/1000f;
        for (int i = 0; i < Math.min(16, ritual.size()); i++) o[5480+i] = ritual.get(i)/1000f;
        for (JsonElement value : relics) {
            JsonObject relic = value.getAsJsonObject();
            int id = model.id("relics", string(relic, "id"));
            o[6238+id] = 1;
            o[6419+id] = optionalInt(relic, "counter", 0) / 1000f;
        }
        for (int i = 0; i < Math.min(5, potions.size()); i++) {
            int id = model.id("potions", string(potions.get(i).getAsJsonObject(), "id"));
            o[6600+i*44+id] = 1;
        }
        for (int i = potions.size(); i < 5; i++) o[6600+i*44+1] = 1;
        if (flameX >= 0 && flameY >= 0) {
            o[6820+flameX] = 1; o[6827+flameY] = 1; o[6842] = 1;
        }
    }

    private void extra() {
        JsonArray deck = array(game, "deck"), potions = array(game, "potions");
        int hp = integer(game, "current_hp"), maxHp = integer(game, "max_hp");
        int strikes = 0, defends = 0, upgrades = 0, attacks = 0, skills = 0, powers = 0, curses = 0;
        for (JsonElement item : deck) {
            JsonObject card = item.getAsJsonObject();
            String id = string(card, "id"), type = string(card, "type");
            if (id.equals("Strike_R") || id.equals("STRIKE_RED")) strikes++;
            if (id.equals("Defend_R") || id.equals("DEFEND_RED")) defends++;
            if (optionalInt(card, "upgrades", 0) > 0) upgrades++;
            if (type.equals("ATTACK")) attacks++;
            if (type.equals("SKILL")) skills++;
            if (type.equals("POWER")) powers++;
            if (type.equals("CURSE")) curses++;
        }
        int potionCount = 0;
        for (JsonElement p : potions) if (!emptyPotion(string(p.getAsJsonObject(), "id"))) potionCount++;
        float[] v = extra;
        v[0] = (float)hp / Math.max(1, maxHp); v[1] = (maxHp-hp)/100f;
        v[2] = deck.size()/40f; v[3] = integer(game, "gold")/300f;
        v[4] = optionalInt(visible, "y", -1)/15f;
        v[5] = integer(game, "act")/4f;
        v[6] = optionalInt(object(game,"screen_state"),"num_cards",0)/5f;
        v[7] = potionCount/5f; v[8] = potions.size()/5f;
        v[9] = strikes/10f; v[10] = defends/10f; v[11] = upgrades/40f;
        v[12] = attacks/40f; v[13] = skills/40f; v[14] = powers/40f; v[15] = curses/40f;
        v[16] = string(visible, "screen_type").equals("SHOP_SCREEN")
            ? optionalInt(visible, "purge_actual_cost", 0)/300f : 0;
        JsonObject keys = object(game, "keys");
        v[17] = bool(keys, "ruby") ? 1 : 0;
        v[18] = bool(keys, "emerald") ? 1 : 0;
        v[19] = bool(keys, "sapphire") ? 1 : 0;
    }

    private void mapObservation() {
        int offset = 3175;
        for (int x = 0; x < 7; x++) if (hasChildren(x, 0)) observation[offset+x] = 1;
        offset += 7;
        for (int y = 0; y < 14; y++) for (int x = 0; x < 7; x++) {
            for (int child : children(x, y)) observation[offset+(child < x ? 0 : child == x ? 1 : 2)] = 1;
            offset += 3;
        }
        for (int y = 1; y < 14; y++) {
            if (y == 8) continue;
            for (int x = 0; x < 7; x++) {
                JsonObject node = nodes.get(key(x, y));
                if (node != null) {
                    int room = symbol(string(node, "symbol"));
                    if (room >= 0 && room < 6) observation[offset+room] = 1;
                }
                offset += 6;
            }
        }
        if (offset != 3980) throw new IllegalStateException("Map observation width drift");
    }

    private void mapChoices() {
        JsonObject screen = object(game, "screen_state");
        if (bool(screen, "boss_available")) {
            Choice choice = choice(0, "choose 0", "Boss", 5, 0, 0, 0);
            choice.descriptor[model.offset("OFF_MROOM")+6] = 1;
            route(choice, 0, 15);
            return;
        }
        int targetY = optionalInt(visible, "y", -1)+1;
        JsonArray next = array(screen, "next_nodes");
        for (int i = 0; i < next.size(); i++) {
            JsonObject node = next.get(i).getAsJsonObject();
            int targetX = integer(node, "x");
            Choice choice = choice(targetX, "choose " + i, "x=" + targetX + " " + string(node, "symbol"),
                5, 0, targetX, 0);
            int room = featureRoom(string(node, "symbol"));
            if (room >= 0 && room < 7) choice.descriptor[model.offset("OFF_MROOM")+room] = 1;
            choice.descriptor[model.offset("OFF_BURNING_ROOM")] =
                flameX == targetX && flameY == targetY ? 1 : 0;
            Set<Integer> reachable = reachable(targetX, targetY);
            choice.descriptor[model.offset("OFF_BURNING_REACHABLE")] =
                reachable.contains(key(flameX, flameY)) ? 1 : 0;
            for (int child : children(targetX, targetY)) {
                int index = featureRoomAt(child, targetY+1);
                if (index >= 0 && index < 7) choice.descriptor[model.offset("OFF_MLA1")+index]++;
                for (int grandchild : children(child, targetY+1)) {
                    index = featureRoomAt(grandchild, targetY+2);
                    if (index >= 0 && index < 7) choice.descriptor[model.offset("OFF_MLA2")+index]++;
                }
            }
            route(choice, targetX, targetY);
        }
    }

    private void cardChoices() {
        JsonObject screen = object(game, "screen_state");
        JsonArray cards = array(screen, "cards");
        for (int i = 0; i < cards.size(); i++) {
            JsonObject card = cards.get(i).getAsJsonObject();
            Choice choice = choice(i << 8, "choose " + i, string(card, "name"), 2, 3, 0, i);
            encodeCard(choice, card);
        }
        if (bool(screen, "bowl_available")) {
            Choice choice = choice(5 << 8, "choose " + cards.size(), "Singing Bowl", 2, 4, 0, 5);
            choice.descriptor[model.offset("OFF_PASS")] = 1;
        }
        if (bool(screen, "skip_available")) {
            Choice choice = choice(6L << 27, "skip", "Skip", 2, 10, 0, 0);
            choice.descriptor[model.offset("OFF_PASS")] = 1;
        }
    }

    private void neowChoices() {
        JsonArray options = array(visible, "neow_options"), shown = array(visible, "choices");
        if (shown.size() != options.size()) throw new NotReady("Neow option UI is not ready");
        for (int i = 0; i < options.size(); i++) {
            JsonObject option = options.get(i).getAsJsonObject();
            int bonus = neowBonus(string(option, "bonus"));
            int drawback = neowDrawback(string(option, "drawback"));
            if (bonus == 18) drawback = 6; // Boss swap removes the starter relic.
            Choice choice = choice(i, "choose " + i, shown.get(i).getAsString(), 1, 2, i, 0);
            choice.descriptor[model.offset("OFF_EVENT")+5] = 1;
            choice.descriptor[model.offset("OFF_EVENT_OPTION")+i] = 1;
            choice.descriptor[model.offset("OFF_NEOW_BONUS")+bonus] = 1;
            choice.descriptor[model.offset("OFF_NEOW_DRAWBACK")+drawback] = 1;
        }
    }

    /** Talk, mini-blessing dialogue and the post-blessing Leave are single-button Neow stages. */
    private void neowAdvance() {
        JsonArray shown = array(visible, "choices");
        if (shown.size() == 0) throw new NotReady("Neow dialogue is still animating");
        if (shown.size() != 1) throw new IllegalArgumentException("Unexpected Neow stage "
            + optionalInt(visible, "neow_screen", -1) + " with " + shown.size() + " buttons");
        directChoice("choose 0", shown.get(0).getAsString());
    }

    private void eventChoices() {
        JsonArray shown=array(visible,"choices");
        int event=model.id("events",string(object(game,"screen_state"),"event_id"));
        for(int i=0;i<shown.size();i++) {
            Choice choice=choice(i,"choose "+i,shown.get(i).getAsString(),1,2,i,0);
            choice.descriptor[model.offset("OFF_EVENT")+event]=1;
            choice.descriptor[model.offset("OFF_EVENT_OPTION")+i]=1;
        }
    }

    private void combatRewardChoices() {
        JsonArray rewards=array(object(game,"screen_state"),"rewards");
        int firstCard=-1, skipped=optionalInt(visible,"skipped_card_rewards",0);
        for(int i=0;i<rewards.size();i++) {
            JsonObject reward=rewards.get(i).getAsJsonObject();
            String type=string(reward,"reward_type");
            if(type.equals("GOLD") || type.equals("STOLEN_GOLD")) {
                Choice choice=choice(i,"choose "+i,"Gold",2,5,i,0);
                choice.descriptor[model.offset("OFF_AMOUNT")]=optionalInt(reward,"gold",0)/1000f;
                return; // Taking gold is free and cannot exclude another reward.
            }
            if(type.equals("CARD") && firstCard<0 && skipped--<=0)firstCard=i;
        }
        if(firstCard>=0) {
            choice(firstCard,"choose "+firstCard,"View card reward",2,3,firstCard,0);
            return; // Original game reveals the cards only after this click.
        }
        for(int i=0;i<rewards.size();i++) {
            JsonObject reward=rewards.get(i).getAsJsonObject();
            String type=string(reward,"reward_type");
            if(type.equals("RELIC")) {
                JsonObject relic=object(reward,"relic");
                Choice choice=choice(i,"choose "+i,string(relic,"name"),2,9,i,0);
                choice.descriptor[model.offset("OFF_RELIC")+model.id("relics",string(relic,"id"))]=1;
            } else if(type.equals("SAPPHIRE_KEY") || type.equals("EMERALD_KEY")) {
                Choice choice=choice(i,"choose "+i,type,2,type.equals("SAPPHIRE_KEY")?7:6,i,0);
                choice.descriptor[model.offset("OFF_KEY")+(type.equals("SAPPHIRE_KEY")?2:0)]=1;
                JsonObject linked=object(reward,"link");
                if(type.equals("SAPPHIRE_KEY") && linked.has("id"))
                    choice.descriptor[model.offset("OFF_RELIC")+model.id("relics",string(linked,"id"))]=1;
            }
        }
        if(choices.isEmpty() && (hasCommand("proceed") || hasCommand("skip"))) {
            // The reward screen's own button is "proceed"; skipped cards and full-slot potions may remain.
            Choice choice=choice(6L<<27,hasCommand("proceed")?"proceed":"skip","Leave rewards",2,10,0,0);
            choice.descriptor[model.offset("OFF_PASS")]=1;
        }
    }

    private void chestChoices() {
        int size=optionalInt(visible,"chest_size",0);
        if(size<0 || size>=4)throw new IllegalArgumentException("Unknown chest size");
        if(array(visible,"choices").size()>0) {
            Choice open=choice(0,"choose 0","Open chest",6,20,0,0);
            open.descriptor[model.offset("OFF_CHEST")+size]=1;
            Choice leave=choice(1,"confirm","Leave chest",6,21,1,0);
            leave.descriptor[model.offset("OFF_CHEST")+size]=1;
            leave.descriptor[model.offset("OFF_PASS")]=1;
        } else directChoice("confirm","Proceed");
    }

    private void bossChoices() {
        JsonArray relics = array(object(game, "screen_state"), "relics");
        for (int i = 0; i < relics.size(); i++) {
            JsonObject relic = relics.get(i).getAsJsonObject();
            Choice choice = choice(i, "choose " + i, string(relic, "name"), 3, 16, i, 0);
            choice.descriptor[model.offset("OFF_RELIC")+model.id("relics", string(relic, "id"))] = 1;
        }
        if (hasCommand("skip") || hasCommand("cancel")) {
            Choice choice = choice(3, "skip", "Skip", 3, 17, 3, 0);
            choice.descriptor[model.offset("OFF_PASS")] = 1;
        }
    }

    private void restChoices() {
        JsonArray shown = array(visible, "choices");
        if(shown.size()==0 && hasCommand("proceed")) {directChoice("confirm","Proceed");return;}
        for (int i = 0; i < shown.size(); i++) {
            String label = shown.get(i).getAsString();
            int option;
            switch (label) {
                case "rest": option = 0; break;
                case "smith": option = 1; break;
                case "recall": option = 2; break;
                case "lift": option = 3; break;
                case "toke": option = 4; break;
                case "dig": option = 5; break;
                default: throw new IllegalArgumentException("Unknown campfire option " + label);
            }
            Choice choice = choice(option, "choose " + i, label, 7, 1, option, 0);
            choice.descriptor[model.offset("OFF_REST")+option] = 1;
            if (option == 2) choice.descriptor[model.offset("OFF_KEY")+1] = 1;
        }
    }

    private void shopChoices() {
        JsonObject screen = object(game, "screen_state");
        JsonArray shown = array(visible, "choices");
        int gold = integer(game, "gold");
        boolean purge=bool(screen, "purge_available") && optionalInt(screen, "purge_cost", 0) <= gold;
        int base=purge?1:0; // CommunicationMod displays purge, cards, relics, then potions.
        JsonArray cards = array(screen, "cards");
        int affordableCards=0;
        for (int i = 0; i < cards.size(); i++) {
            JsonObject card = cards.get(i).getAsJsonObject();
            int price = optionalInt(card, "price", Integer.MAX_VALUE);
            if (price > gold) continue;
            Choice choice = choice(i, "choose " + (base+affordableCards++), string(card, "name"), 8, 11, i, 0);
            encodeCard(choice, card);
            choice.descriptor[model.offset("OFF_PRICE")] = price/1000f;
        }
        JsonArray relics = array(screen, "relics");
        int affordableRelics=0;
        for(JsonElement item:relics)if(optionalInt(item.getAsJsonObject(),"price",Integer.MAX_VALUE)<=gold)
            affordableRelics++;
        JsonArray potions = array(screen, "potions");
        boolean sozu = hasRelic("Sozu");
        boolean full = !hasEmptyPotion();
        int affordablePotions=0;
        for (int i = 0; i < potions.size(); i++) {
            JsonObject potion = potions.get(i).getAsJsonObject();
            int price = optionalInt(potion, "price", Integer.MAX_VALUE);
            if (price > gold) continue;
            if (!sozu && !full) {
                Choice choice = choice((3L << 27) | i,
                    "choose " + (base+affordableCards+affordableRelics+affordablePotions),
                    string(potion, "name"), 8, 13, i, 0);
                choice.descriptor[model.offset("OFF_POTION")+model.id("potions", string(potion, "id"))] = 1;
                choice.descriptor[model.offset("OFF_PRICE")] = price/1000f;
            }
            affordablePotions++;
        }
        int relicClick=0;
        for (int i = 0; i < relics.size(); i++) {
            JsonObject relic = relics.get(i).getAsJsonObject();
            int price = optionalInt(relic, "price", Integer.MAX_VALUE);
            if (price > gold) continue;
            Choice choice = choice((4L << 27) | i,
                "choose " + (base+affordableCards+relicClick++),string(relic,"name"),8,12,i,0);
            choice.descriptor[model.offset("OFF_RELIC")+model.id("relics", string(relic, "id"))] = 1;
            choice.descriptor[model.offset("OFF_PRICE")] = price/1000f;
        }
        if(purge && removableCardExists()) {
            Choice choice=choice(5L<<27,"choose 0","Remove a card",8,14,0,0);
            choice.descriptor[model.offset("OFF_PRICE")]=optionalInt(screen,"purge_cost",0)/1000f;
        }
        if (base+affordableCards+affordableRelics+affordablePotions != shown.size())
            throw new IllegalArgumentException("Shop UI ordering drift");
        Choice leave = choice(6L << 27, "leave", "Leave shop", 8, 15, 0, 0);
        leave.descriptor[model.offset("OFF_PASS")] = 1;
    }

    private void selectChoices() {
        JsonObject screen = object(game, "screen_state");
        JsonArray cards = array(screen, "cards");
        int type = bool(screen, "for_purge") ? 4 : bool(screen, "for_upgrade") ? 3
            : bool(screen, "for_transform") ? 1 : 6;
        // The student ranks removal/transform targets mostly by grid position, so a rare card listed
        // first would be lost. Every removal or transform is limited to curses and starter cards;
        // events fall back to the full grid when none is left (the shop never offers that case).
        boolean restricted = type == 4 || type == 1;
        if (restricted && !bool(visible, "grid_from_shop")) {
            boolean any = false;
            for (JsonElement item : cards)
                any |= allowedRemoval(string(item.getAsJsonObject(), "id"), string(item.getAsJsonObject(), "type"));
            restricted = any;
        }
        for (int i = 0; i < cards.size(); i++) {
            JsonObject card = cards.get(i).getAsJsonObject();
            if (restricted && !allowedRemoval(string(card, "id"), string(card, "type"))) continue;
            Choice choice = choice(i, "choose " + i, string(card, "name"), 4, 18, i, 0);
            choice.descriptor[model.offset("OFF_SELECTION_TYPE")+type] = 1;
            encodeCard(choice, card);
        }
        if (hasCommand("cancel")) {
            Choice choice = choice(6L << 27, "cancel", "Cancel", 4, 19, 0, 0);
            choice.descriptor[model.offset("OFF_PASS")] = 1;
        }
    }

    private void directChoice(String command, String label) {
        Choice choice = choice(0, command, label, Math.max(0,screenType(string(visible, "screen_type"))), 19, 0, 0);
        choice.descriptor[model.offset("OFF_PASS")] = 1;
    }

    private void potionChoices() {
        JsonArray potions=array(game,"potions");
        int screen=Math.max(0,screenType(string(visible,"screen_type")));
        for(int slot=0;slot<potions.size();slot++) {
            JsonObject potion=potions.get(slot).getAsJsonObject();
            String id=string(potion,"id");
            if(emptyPotion(id))continue;
            if(bool(potion,"can_use") && !bool(potion,"requires_target"))
                potionChoice((23L<<27)|slot,"potion use "+slot,
                    "Use "+string(potion,"name"),screen,22,slot,id);
            if(bool(potion,"can_discard"))
                potionChoice((24L<<27)|slot,"potion discard "+slot,
                    "Discard "+string(potion,"name"),screen,23,slot,id);
        }
    }
    private void potionChoice(long bits,String command,String label,int screen,int kind,int slot,String id) {
        Choice choice=choice(bits,command,label,screen,kind,slot,0);
        choice.descriptor[model.offset("OFF_POTION")+model.id("potions",id)]=1;
        choice.descriptor[model.offset("OFF_POTION_SLOT")+slot]=1;
    }

    private boolean removableCardExists() {
        for (JsonElement item : array(game, "deck")) {
            JsonObject card = item.getAsJsonObject();
            if (allowedRemoval(string(card, "id"), string(card, "type"))) return true;
        }
        return false;
    }
    private static boolean allowedRemoval(String id, String type) {
        return type.equals("CURSE") || id.equals("Strike_R") || id.equals("STRIKE_RED")
            || id.equals("Defend_R") || id.equals("DEFEND_RED") || id.equals("Bash")
            || id.equals("BASH");
    }
    private boolean hasRelic(String id) {
        for (JsonElement item : array(game, "relics"))
            if (string(item.getAsJsonObject(), "id").equalsIgnoreCase(id)) return true;
        return false;
    }
    private boolean hasEmptyPotion() {
        for (JsonElement item : array(game, "potions"))
            if (emptyPotion(string(item.getAsJsonObject(), "id"))) return true;
        return false;
    }
    private boolean hasCommand(String command) {
        for (JsonElement item : availableCommands)
            if (item.getAsString().equalsIgnoreCase(command)) return true;
        return false;
    }

    private static int neowBonus(String value) {
        String[] names = {"THREE_CARDS", "ONE_RANDOM_RARE_CARD", "REMOVE_CARD", "UPGRADE_CARD",
            "TRANSFORM_CARD", "RANDOM_COLORLESS", "THREE_SMALL_POTIONS", "RANDOM_COMMON_RELIC",
            "TEN_PERCENT_HP_BONUS", "THREE_ENEMY_KILL", "HUNDRED_GOLD", "RANDOM_COLORLESS_2",
            "REMOVE_TWO", "ONE_RARE_RELIC", "THREE_RARE_CARDS", "TWO_FIFTY_GOLD",
            "TRANSFORM_TWO_CARDS", "TWENTY_PERCENT_HP_BONUS", "BOSS_RELIC"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(value)) return i;
        throw new IllegalArgumentException("Unknown Neow bonus " + value);
    }
    private static int neowDrawback(String value) {
        String[] names = {"INVALID", "NONE", "TEN_PERCENT_HP_LOSS", "NO_GOLD",
            "CURSE", "PERCENT_DAMAGE", "LOSE_STARTER_RELIC"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(value)) return i;
        throw new IllegalArgumentException("Unknown Neow drawback " + value);
    }

    private Choice choice(long bits, String command, String label, int screen,
                          int kind, int idx1, int idx2) {
        Choice result = new Choice(bits, command, label);
        result.descriptor[kind] = 1;
        result.descriptor[model.offset("OFF_SCREEN")+screen] = 1;
        result.descriptor[model.offset("OFF_RAW_INDEX")] = idx1/32f;
        result.descriptor[model.offset("OFF_RAW_INDEX")+1] = idx2/32f;
        choices.add(result);
        return result;
    }

    private void encodeCard(Choice choice, JsonObject card) {
        int id = model.id("cards", string(card, "id"));
        choice.descriptor[model.offset("OFF_CARD")+id] = 1;
        choice.descriptor[model.offset("OFF_CARD_UPGRADE")] = optionalInt(card, "upgrades", 0)/1000f;
        choice.descriptor[model.offset("OFF_CARD_MISC")] = optionalInt(card, "misc", 0)/1000f;
        String uuid=string(card,"uuid");
        if(!uuid.isEmpty())for(JsonElement item:array(visible,"bottled_deck_indices")) {
            int index=item.getAsInt();
            JsonArray deck=array(game,"deck");
            if(index>=0 && index<deck.size()
                    && uuid.equals(string(deck.get(index).getAsJsonObject(),"uuid"))) {
                choice.descriptor[model.offset("OFF_CARD_BOTTLED")]=1;
                break;
            }
        }
    }

    private void route(Choice choice, int x, int y) {
        float[] out = choice.route;
        out[0] = 1;
        Set<Integer> reached = reachable(x, y);
        boolean flame = reached.contains(key(flameX, flameY));
        out[1] = flameX == x && flameY == y ? 1 : 0;
        out[2] = flame ? 1 : 0;
        out[3] = flame ? (flameY-y)/15f : 16/15f;
        out[4] = reached.size()/105f;
        for (int room = 0; room < 7; room++) {
            int distance = 16;
            for (int position : reached) if (featureRoomAt(position%7, position/7) == room)
                distance = Math.min(distance, position/7-y);
            out[5+room] = distance/15f;
        }
        int[][] range = pathRange(x, y, new HashMap<Integer,int[][]>());
        for (int room = 0; room < 7; room++) {
            out[12+room] = range[0][room]/15f;
            out[19+room] = range[1][room]/15f;
        }
    }

    private int[][] pathRange(int x, int y, Map<Integer,int[][]> memo) {
        int position = key(x, y);
        if (memo.containsKey(position)) return memo.get(position);
        int[] own = new int[7];
        int room = featureRoomAt(x, y);
        if (room >= 0 && room < 7) own[room] = 1;
        int[] low = own.clone(), high = own.clone();
        List<Integer> children = y >= 15 ? Collections.<Integer>emptyList() : children(x, y);
        if (!children.isEmpty()) for (int type = 0; type < 7; type++) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int child : children) {
                int[][] below = pathRange(child, y+1, memo);
                min = Math.min(min, below[0][type]); max = Math.max(max, below[1][type]);
            }
            low[type] += min; high[type] += max;
        }
        int[][] result = {low, high};
        memo.put(position, result);
        return result;
    }

    private Set<Integer> reachable(int x, int y) {
        Set<Integer> reached = new HashSet<>();
        reachable(x, y, reached);
        return reached;
    }
    private void reachable(int x, int y, Set<Integer> reached) {
        if (!reached.add(key(x, y)) || y >= 15) return;
        for (int child : children(x, y)) reachable(child, y+1, reached);
    }
    private List<Integer> children(int x, int y) {
        JsonObject node = nodes.get(key(x, y));
        if (node == null) return Collections.emptyList();
        List<Integer> result = new ArrayList<>();
        for (JsonElement child : array(node, "children")) {
            JsonObject next = child.getAsJsonObject();
            if (integer(next, "y") == y+1) result.add(integer(next, "x"));
        }
        return result;
    }
    private boolean hasChildren(int x, int y) { return !children(x, y).isEmpty(); }
    private int symbolAt(int x, int y) {
        if (y >= 15) return 6;
        JsonObject node = nodes.get(key(x, y));
        return node == null ? -1 : symbol(string(node, "symbol"));
    }
    private int featureRoomAt(int x, int y) {
        if (y >= 15) return 6;
        JsonObject node = nodes.get(key(x, y));
        return node == null ? -1 : featureRoom(string(node, "symbol"));
    }
    private static int featureRoom(String value) {
        switch (value) {
            case "M": return 0;
            case "E": return 1;
            case "R": return 2;
            case "$": return 3;
            case "?": return 4;
            case "T": return 5;
            case "B": return 6;
            default: return -1;
        }
    }
    private static int symbol(String value) {
        switch (value) {
            case "$": return 0;
            case "R": return 1;
            case "?": return 2;
            case "E": return 3;
            case "M": return 4;
            case "T": return 5;
            case "B": return 6;
            default: return -1;
        }
    }
    private static int roomType(String value) {
        if (value.equals("INVALID")) return 9;
        if (value.equals("NONE")) return 8;
        if (value.contains("Neow")) return 9;
        if (value.contains("Shop")) return 0;
        if (value.contains("Rest")) return 1;
        if (value.contains("Event")) return 2;
        if (value.contains("Elite")) return 3;
        if (value.contains("Monster")) return 4;
        if (value.contains("Treasure")) return 5;
        if (value.contains("Boss")) return 6;
        return 8;
    }
    private static int screenType(String value) {
        switch (value) {
            case "EVENT": return 1;
            case "CARD_REWARD": case "COMBAT_REWARD": return 2;
            case "BOSS_REWARD": return 3;
            case "GRID": return 4;
            case "MAP": return 5;
            case "CHEST": return 6;
            case "REST": return 7;
            case "SHOP_SCREEN": return 8;
            case "SHOP_ROOM": return 8;
            default: return -1;
        }
    }
    private static int bossType(String name) {
        switch (name.replaceAll("[^A-Za-z]", "").toLowerCase(java.util.Locale.ROOT)) {
            case "slimeboss": return 0;
            case "hexaghost": return 1;
            case "theguardian": return 2;
            case "champ": return 3;
            case "automaton": case "bronzeautomaton": return 4;
            case "collector": case "thecollector": return 5;
            case "timeeater": return 6;
            case "donuanddeca": return 7;
            case "awakenedone": return 8;
            case "corruptheart": return 9;
            default: return -1;
        }
    }
    private static int key(int x, int y) { return y*7+x; }
    private static boolean emptyPotion(String id) {
        return id.equals("Potion Slot") || id.equals("EMPTY_POTION_SLOT");
    }
    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
            ? object.getAsJsonArray(key) : new JsonArray();
    }
    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
            ? parent.getAsJsonObject(key) : new JsonObject();
    }
    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
            ? object.get(key).getAsString() : "";
    }
    private static int integer(JsonObject object, String key) {
        if (object == null || !object.has(key)) throw new IllegalArgumentException("Missing public field " + key);
        return object.get(key).getAsInt();
    }
    private static int optionalInt(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
            ? object.get(key).getAsInt() : fallback;
    }
    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
            && object.get(key).getAsBoolean();
    }
}
