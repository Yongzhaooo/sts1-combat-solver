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
        BattleHistory pending = new BattleHistory();
        pending.record("3", event("battle_start", 31), null);
        JsonObject root = new JsonObject();
        root.addProperty("ready_for_command", false);
        pending.captureSolveRoot("3", root, 10);
        if (pending.snapshot("3").getAsJsonObject("current").has("root"))
            throw new AssertionError("Unready battle start must not become a replay root");
        root.addProperty("ready_for_command", true);
        root.addProperty("marker", "first-stable-root");
        pending.captureSolveRoot("3", root, 20);
        root.addProperty("marker", "later-solve");
        pending.captureSolveRoot("3", root, 30);
        JsonObject exported = pending.snapshot("3").getAsJsonObject("current");
        if (!exported.getAsJsonObject("root").get("marker").getAsString().equals("first-stable-root")
                || exported.getAsJsonArray("events").size() != 1
                || exported.get("root_after_events").getAsInt() != 1
                || exported.get("root_time").getAsLong() != 20
                || !exported.get("root_source").getAsString().equals("solve"))
            throw new AssertionError("Export before any action must retain the first stable solve root without fake actions");
        BattleHistory failed = new BattleHistory();
        JsonObject error = event("selection", 31);
        error.addProperty("root_error", "original capture failure");
        failed.record("3", event("battle_start", 31), null);
        failed.record("3", error, null);
        failed.captureSolveRoot("3", root, 40);
        JsonObject failure = failed.snapshot("3").getAsJsonObject("current");
        if (failure.has("root") || !failure.get("root_error").getAsString().equals("original capture failure"))
            throw new AssertionError("Later solve must not conceal an earlier root capture failure");
        BattleHistory malformed = new BattleHistory();
        malformed.record("3", event("battle_start", 31), null);
        JsonObject invalidRoot = new JsonObject();
        invalidRoot.add("ready_for_command", new JsonObject());
        malformed.captureSolveRoot("3", invalidRoot, 50);
        malformed.captureSolveRoot("3", root, 60);
        JsonObject rejected = malformed.snapshot("3").getAsJsonObject("current");
        if (rejected.has("root") || !rejected.has("root_error") || rejected.get("root_time").getAsLong() != 50)
            throw new AssertionError("Capture errors must survive export and must not stop the solve");
        System.out.println("PASS: five battles, manual actions, roots, ownership, current battle, truncation, seed reset, pending solve export, capture failure");
    }
}
