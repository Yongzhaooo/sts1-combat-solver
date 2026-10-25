package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.megacrit.cardcrawl.cards.AbstractCard;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "The model wants this card": a hypothetical offer is scored as the real CARD_REWARD screen (cards + skip, Singing Bowl
 * when owned) through the same OutsidePacket/Distill2 path, and wants = the best non-potion choice is a card. Grids are
 * scored the same way as the real GRID decision, hand rules included. The outside decision being ruled binds its model
 * and public state around EventRules.readLive (game thread); the hypothetical screen keeps that state and only replaces the
 * screen and appends the cards picked so far to the deck.
 */
final class CardWant {
    private static Distill2 model;
    private static JsonObject game, visible;

    private CardWant() { }

    static void bind(Distill2 m, JsonObject g, JsonObject v) { model = m; game = g; visible = v; }
    static void unbind() { model = null; game = null; visible = null; }

    /** Index into offer, or -1 for skip; -1 on any failure as well. */
    static int pick(List<AbstractCard> offer, List<AbstractCard> addedSoFar) {
        try {
            return choose(offer, addedSoFar);
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("[STS1Solver] card want: " + failure);
            return -1;
        }
    }

    /** Single-card offer + skip. */
    static boolean wants(AbstractCard card, List<AbstractCard> addedSoFar) {
        return pick(Collections.singletonList(card), addedSoFar) == 0;
    }

    /** Like pick, but a failure throws, so a caller can tell "skip" from "could not score" and keep its old rule. */
    static int choose(List<AbstractCard> offer, List<AbstractCard> addedSoFar) {
        if (model == null) throw new IllegalStateException("no outside decision bound");
        return pick(model, game, visible, json(offer), json(addedSoFar));
    }

    /** Index into grid of the card the real GRID decision selects next (numCards to pick, selected so far); throws on failure. */
    static int gridChoose(List<AbstractCard> grid, List<AbstractCard> selected, int numCards, boolean forTransform) {
        if (model == null) throw new IllegalStateException("no outside decision bound");
        return gridPick(model, game, visible, json(grid), json(selected), numCards, forTransform);
    }

    // ---- public-state level (offline checks call these with recorded states) ------------------

    static int pick(Distill2 model, JsonObject game, JsonObject visible, List<JsonObject> offer, List<JsonObject> added) {
        OutsidePacket packet = rewardPacket(model, game, visible, offer, added);
        int index = chosen(packet, packet.score().scores);
        return index < offer.size() ? index : -1; // "choose n" past the cards is the Singing Bowl
    }

    static int gridPick(Distill2 model, JsonObject game, JsonObject visible, List<JsonObject> grid, List<JsonObject> selected,
                        int numCards, boolean forTransform) {
        JsonObject screen = new JsonObject();
        screen.add("cards", array(grid));
        screen.add("selected_cards", array(selected));
        screen.addProperty("num_cards", numCards);
        screen.addProperty("any_number", false);
        screen.addProperty("for_upgrade", false);
        screen.addProperty("for_transform", forTransform);
        screen.addProperty("for_purge", false);
        screen.addProperty("confirm_up", false);
        OutsidePacket packet = packet(model, game, visible, "GRID", screen, Collections.<JsonObject>emptyList());
        int index = chosen(packet, packet.ruleScore().scores);
        if (index < 0 || index >= grid.size()) throw new IllegalStateException("grid choice is not a card");
        return index;
    }

    static OutsidePacket rewardPacket(Distill2 model, JsonObject game, JsonObject visible, List<JsonObject> offer, List<JsonObject> added) {
        boolean bowl = false;
        for (com.google.gson.JsonElement relic : game.getAsJsonArray("relics"))
            bowl |= "Singing Bowl".equals(relic.getAsJsonObject().get("id").getAsString());
        JsonObject screen = new JsonObject();
        screen.addProperty("bowl_available", bowl);
        screen.addProperty("skip_available", true);
        screen.add("cards", array(offer));
        return packet(model, game, visible, "CARD_REWARD", screen, added);
    }

    private static OutsidePacket packet(Distill2 model, JsonObject game, JsonObject visible, String screenType,
                                        JsonObject screen, List<JsonObject> added) {
        JsonObject g = copy(game), v = copy(visible);
        g.addProperty("screen_type", screenType);
        g.add("screen_state", screen);
        JsonArray deck = g.getAsJsonArray("deck");
        for (JsonObject card : added) deck.add(copy(card));
        // Event-page fields; the hypothetical screen is not an event page.
        for (String key : new String[]{"choices", "event_hp_loss", "neow_options", "neow_screen", "screen_state"}) v.remove(key);
        v.addProperty("screen_type", screenType);
        JsonObject state = new JsonObject();
        state.addProperty("in_game", true);
        state.addProperty("ready_for_command", true);
        JsonArray commands = new JsonArray();
        commands.add("choose");
        state.add("available_commands", commands);
        state.add("game_state", g);
        return OutsidePacket.build(model, state, v);
    }

    /** Out of combat no potion is used (OutsidePacket.potionRule): the best non-potion choice, as a "choose i" index or -1. */
    private static int chosen(OutsidePacket packet, float[] scores) {
        int best = -1;
        for (int i = 0; i < packet.choices.size(); i++)
            if (!packet.choices.get(i).command.startsWith("potion ") && (best < 0 || scores[i] > scores[best])) best = i;
        if (best < 0) throw new IllegalStateException("no card choice");
        String command = packet.choices.get(best).command;
        return command.startsWith("choose ") ? Integer.parseInt(command.substring(7)) : -1;
    }

    /** The fields OutsidePacket reads from a CommunicationMod card (convertCardToJson + CardExportPatch). */
    static JsonObject json(AbstractCard card) {
        JsonObject out = new JsonObject();
        out.addProperty("name", card.name);
        out.addProperty("uuid", card.uuid.toString());
        out.addProperty("misc", card.misc);
        out.addProperty("cost", card.cost);
        out.addProperty("upgrades", card.timesUpgraded);
        out.addProperty("id", card.cardID);
        out.addProperty("type", card.type.name());
        out.addProperty("rarity", card.rarity.name());
        return out;
    }

    private static List<JsonObject> json(List<AbstractCard> cards) {
        List<JsonObject> out = new ArrayList<>();
        for (AbstractCard card : cards) out.add(json(card));
        return out;
    }

    private static JsonArray array(List<JsonObject> cards) {
        JsonArray out = new JsonArray();
        for (JsonObject card : cards) out.add(copy(card));
        return out;
    }

    private static JsonObject copy(JsonObject value) { return new JsonParser().parse(value.toString()).getAsJsonObject(); }
}
