package sts1solver;
import com.google.gson.*;

/** Picker previews can change while a backend reply is in flight. */
public class StateKeyCheck {
    public static void main(String[] args) {
        JsonParser parser = new JsonParser();
        JsonObject card = parser.parse("{\"id\":\"Strike_R\",\"uuid\":\"card-1\",\"cost\":1,\"base_damage\":6,\"damage\":6,\"block\":0,\"magic_number\":0}").getAsJsonObject();
        for (String location : new String[]{"pile", "picker", "pending"}) {
            JsonObject state = parser.parse("{\"game_state\":{\"combat_state\":{}},\"available_commands\":[\"choose\"]}").getAsJsonObject();
            JsonObject game = state.getAsJsonObject("game_state");
            JsonArray cards = new JsonArray(); cards.add(card);
            if (location.equals("pile")) game.getAsJsonObject("combat_state").add("discard_pile",cards);
            if (location.equals("picker")) { JsonObject screen=new JsonObject();screen.add("cards",cards);game.add("screen_state",screen); }
            if (location.equals("pending")) { JsonObject action=new JsonObject();action.add("targetCard",card);game.add("solver_selection",action); }
            JsonObject before = SolverMod.key(state);
            card.addProperty("damage",99);card.addProperty("block",20);card.addProperty("magic_number",4);
            if (!before.equals(SolverMod.key(state))) throw new AssertionError(location+" hover cancels route");
            if (card.get("damage").getAsInt()!=99) throw new AssertionError("snapshot mutated");
            for (String field : new String[]{"cost","base_damage","uuid"}) {
                JsonElement old=card.get(field); card.addProperty(field,"changed");
                if (before.equals(SolverMod.key(state))) throw new AssertionError(location+" lost rule field "+field);
                card.add(field,old);
            }
        }
        JsonObject state = parser.parse("{\"game_state\":{\"combat_state\":{},\"solver_auto_context\":{\"run_auto\":true,\"high_speed\":false}},\"available_commands\":[]}").getAsJsonObject();
        JsonObject context = state.getAsJsonObject("game_state").getAsJsonObject("solver_auto_context");
        JsonObject before = SolverMod.key(state);
        context.addProperty("run_auto", false);
        if (!before.equals(SolverMod.key(state))) throw new AssertionError("F9 / automation toggle discards the found route");
        context.addProperty("high_speed", true);
        if (before.equals(SolverMod.key(state))) throw new AssertionError("search settings must stay in the key");
        JsonObject opening = parser.parse("{\"game_state\":{\"screen_type\":\"CARD_REWARD\",\"combat_state\":{\"monsters\":"
            + "[{\"move_id\":2,\"intent\":\"DEBUG\",\"move_adjusted_damage\":-1}]}},\"available_commands\":[\"choose\"]}").getAsJsonObject();
        JsonObject monster = opening.getAsJsonObject("game_state").getAsJsonObject("combat_state")
            .getAsJsonArray("monsters").get(0).getAsJsonObject();
        before = SolverMod.key(opening);
        monster.addProperty("intent", "ATTACK"); monster.addProperty("move_adjusted_damage", 12);
        if (!before.equals(SolverMod.key(opening))) throw new AssertionError("Toolbox: intent shown during the picker cancels the route");
        if (!monster.get("intent").getAsString().equals("ATTACK")) throw new AssertionError("snapshot mutated");
        monster.addProperty("move_id", 3);
        if (before.equals(SolverMod.key(opening))) throw new AssertionError("Toolbox: monster move must stay in the key");
        opening.getAsJsonObject("game_state").addProperty("screen_type", "NONE");
        monster.addProperty("move_id", 2);
        before = SolverMod.key(opening);
        monster.addProperty("intent", "DEFEND");
        if (before.equals(SolverMod.key(opening))) throw new AssertionError("intent must stay in the key outside pickers");
        // Headbutt kills a minion then opens GRID: the game freezes room.update,
        // so waiting for that monster's death animation would deadlock the picker.
        for (com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen screen
                : com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.values()) {
            boolean interactive = screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.NONE
                || screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.HAND_SELECT
                || screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.GRID
                || screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.CARD_REWARD;
            if(CombatReadiness.actionPausedByScreen(screen)==interactive)
                throw new AssertionError("Action timeout must pause on overlay screen " + screen);
            boolean paused = screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.GRID
                || screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.CARD_REWARD;
            if (CombatReadiness.holdSolveForPicker(screen, false, false) != (interactive && screen != com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.NONE)
                    || CombatReadiness.holdSolveForPicker(screen, true, false)
                    || CombatReadiness.holdSolveForPicker(screen, false, true))
                throw new AssertionError("manual re-solve must wait only on mid-turn picker " + screen);
            if (CombatReadiness.waitForMonsterExit(screen,true,false,false,false,false) == paused)
                throw new AssertionError("death gate on " + screen);
            if (CombatReadiness.waitForMonsterExit(screen,false,false,false,true,false) == paused)
                throw new AssertionError("escape gate on " + screen);
            if (CombatReadiness.waitForMonsterExit(screen,true,true,false,true,true)
                    || CombatReadiness.waitForMonsterExit(screen,true,false,true,false,false))
                throw new AssertionError("finished/half-dead monster blocks " + screen);
        }
        System.out.println("PASS: picker/card previews, run_auto and picker-time intents ignored; card identity, costs, base rules and search settings retained");
    }
}
