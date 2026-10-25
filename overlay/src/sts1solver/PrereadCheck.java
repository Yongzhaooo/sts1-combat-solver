package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime self-check of the event prereads: the expected outcome waits for a later outside state on the same floor that
 * shows the real one, and OutsidePacket.policyScore logs one line ("preread ok ..." / "preread MISMATCH ...") with that
 * decision. Cards are compared as id+upgrades, ignoring order (the Library grid lists its cards in reverse).
 */
final class PrereadCheck {
    /** Null while the real outcome is not visible yet. */
    interface Outcome { String check(JsonObject game, JsonObject visible); }

    private static Outcome pending;
    private static String event;
    private static int floor;

    private PrereadCheck() { }

    static void expect(String event, int floor, Outcome outcome) {
        PrereadCheck.event = event; PrereadCheck.floor = floor; pending = outcome;
    }

    /** The line to log for this outside state, or null; the map screen or another floor ends the wait. */
    static String observe(JsonObject game, JsonObject visible) {
        if (pending == null) return null;
        String line = game.has("floor") && game.get("floor").getAsInt() == floor ? pending.check(game, visible) : null;
        if (line == null && (!game.has("floor") || game.get("floor").getAsInt() != floor || "MAP".equals(screen(visible))))
            line = "preread unchecked: " + event + " outcome not shown on floor " + floor;
        if (line != null) pending = null;
        return line;
    }

    /** SensoryStone: the card reward screen shows one of the preread groups. */
    static Outcome rewardGroups(String event, List<List<String>> groups) {
        return (game, visible) -> {
            if (!"CARD_REWARD".equals(screen(visible))) return null;
            List<String> shown = sorted(faces(game.getAsJsonObject("screen_state").getAsJsonArray("cards")));
            for (int k = 0; k < groups.size(); k++)
                if (sorted(groups.get(k)).equals(shown)) return "preread ok: " + event + " group " + (k + 1) + " " + shown;
            return "preread MISMATCH: " + event + " shown " + shown + " preread " + groups;
        };
    }

    /** The Library: the read grid holds exactly the preread cards. */
    static Outcome grid(String event, List<String> cards) {
        return (game, visible) -> {
            if (!"GRID".equals(screen(visible))) return null;
            List<String> shown = sorted(faces(game.getAsJsonObject("screen_state").getAsJsonArray("cards")));
            return sorted(cards).equals(shown) ? "preread ok: " + event + " " + shown.size() + " cards"
                : "preread MISMATCH: " + event + " shown " + shown + " preread " + sorted(cards);
        };
    }

    /** Transforms: back on the map, the targets have left the deck and the results joined it (before = deck faces then). */
    static Outcome transform(String event, Set<String> targetUuids, List<String> results, List<String> before) {
        return (game, visible) -> {
            if (!"MAP".equals(screen(visible))) return null;
            JsonArray deck = game.getAsJsonArray("deck");
            int left = 0;
            for (JsonElement card : deck)
                if (targetUuids.contains(string(card.getAsJsonObject(), "uuid"))) left++;
            if (left == targetUuids.size()) return "preread unchecked: " + event + " transform not taken";
            Map<String, Integer> gained = counts(faces(deck));
            for (Map.Entry<String, Integer> was : counts(before).entrySet()) gained.merge(was.getKey(), -was.getValue(), Integer::sum);
            boolean ok = left == 0;
            for (Map.Entry<String, Integer> want : counts(results).entrySet())
                ok &= gained.getOrDefault(want.getKey(), 0) >= want.getValue();
            return (ok ? "preread ok: " : "preread MISMATCH: ") + event + " results " + results
                + (left == 0 ? "" : ", " + left + " picked card(s) still in the deck");
        };
    }

    static List<String> faces(JsonArray cards) {
        List<String> out = new ArrayList<>();
        for (JsonElement item : cards) {
            JsonObject card = item.getAsJsonObject();
            out.add(string(card, "id") + "+" + (card.has("upgrades") ? card.get("upgrades").getAsInt() : 0));
        }
        return out;
    }

    private static Map<String, Integer> counts(List<String> faces) {
        Map<String, Integer> out = new HashMap<>();
        for (String face : faces) out.merge(face, 1, Integer::sum);
        return out;
    }

    private static List<String> sorted(List<String> faces) {
        List<String> out = new ArrayList<>(faces);
        Collections.sort(out);
        return out;
    }

    private static String screen(JsonObject visible) { return string(visible, "screen_type"); }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
