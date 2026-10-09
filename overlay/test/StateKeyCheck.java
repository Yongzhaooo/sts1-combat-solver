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
        // Headbutt kills a minion then opens GRID: the game freezes room.update,
        // so waiting for that monster's death animation would deadlock the picker.
        for (com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen screen
                : com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.values()) {
            boolean interactive = screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.NONE
                || screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.HAND_SELECT
                || screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.GRID
                || screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.CARD_REWARD;
            if(SolverMod.actionPausedByScreen(screen)==interactive)
                throw new AssertionError("Action timeout must pause on overlay screen " + screen);
            boolean paused = screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.GRID
                || screen == com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.CARD_REWARD;
            if (SolverMod.waitForMonsterExit(screen,true,false,false,false,false) == paused)
                throw new AssertionError("death gate on " + screen);
            if (SolverMod.waitForMonsterExit(screen,false,false,false,true,false) == paused)
                throw new AssertionError("escape gate on " + screen);
            if (SolverMod.waitForMonsterExit(screen,true,true,false,true,true)
                    || SolverMod.waitForMonsterExit(screen,true,false,true,false,false))
                throw new AssertionError("finished/half-dead monster blocks " + screen);
        }
        System.out.println("PASS: picker/card previews ignored; card identity, costs and base rules retained");
    }
}
