package sts1solver;

import com.google.gson.*;

public class BattleHistoryCheck {
    static JsonObject event(String kind, int floor) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", kind);
        out.addProperty("floor", floor);
        out.addProperty("hp", 57);
        return out;
    }

    public static void main(String[] args) {
        BattleHistory history = new BattleHistory();
        for (int floor = 1; floor <= 7; floor++) {
            history.record("1", event("battle_start", floor), null);
            JsonObject card = event("card_play", floor);
            card.addProperty("solver_driving", false);
            card.addProperty("card_uuid", "original-card");
            history.record("1", card, event("root", floor));
            card.addProperty("card_uuid", "changed-after-recording");
            history.record("1", event("battle_end", floor), null);
        }
        JsonObject snapshot = history.snapshot("1");
        JsonArray battles = snapshot.getAsJsonArray("completed");
        if (battles.size() != 5 || battles.get(0).getAsJsonObject().getAsJsonObject("start").get("floor").getAsInt() != 3)
            throw new AssertionError("Must retain the last five finished battles");
        JsonObject first = battles.get(0).getAsJsonObject();
        if (!first.getAsJsonArray("events").get(1).getAsJsonObject().get("card_uuid").getAsString().equals("original-card"))
            throw new AssertionError("Recorded actions must not alias caller state");
        first.remove("root");
        if (!history.snapshot("1").getAsJsonArray("completed").get(0).getAsJsonObject().has("root"))
            throw new AssertionError("Export must not mutate the buffer");
        history.record("1", event("card_play", 8), event("root", 8));
        if (!history.snapshot("1").getAsJsonObject("current").get("partial_start").getAsBoolean())
            throw new AssertionError("Missing battle start must be explicit");
        for (int i = 0; i < BattleHistory.MAX_EVENTS + 10; i++) history.record("1", event("card_play", 8), null);
        history.record("1", event("battle_end", 8), null);
        JsonObject last = history.snapshot("1").getAsJsonArray("completed").get(4).getAsJsonObject();
        if (!last.has("end") || !last.get("truncated").getAsBoolean() || last.getAsJsonArray("events").size() != BattleHistory.MAX_EVENTS)
            throw new AssertionError("Long fights must remain bounded and disclose truncation");
        if (history.snapshot("2").getAsJsonArray("completed").size() != 0) throw new AssertionError("Cross-seed leak");
        history.record("2", event("battle_start", 1), null);
        if (history.snapshot("2").getAsJsonArray("completed").size() != 0) throw new AssertionError("New seed must reset history");
        System.out.println("PASS: five battles, manual actions, roots, ownership, current battle, truncation, seed reset");
    }
}
