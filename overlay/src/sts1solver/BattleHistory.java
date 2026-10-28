package sts1solver;

import com.google.gson.*;
import java.util.ArrayDeque;

/** Local, bounded battle history; no disk writes during play. */
final class BattleHistory {
    static final int KEEP = 5, MAX_EVENTS = 4096;
    private final ArrayDeque<JsonObject> completed = new ArrayDeque<>();
    private String seed;
    private JsonObject current;

    private static JsonObject copy(JsonObject value) {
        return new JsonParser().parse(value.toString()).getAsJsonObject();
    }

    private void selectSeed(String value) {
        if (!value.equals(seed)) {
            seed = value;
            completed.clear();
            current = null;
        }
    }

    void start(String seed, JsonObject event, boolean partial) {
        selectSeed(seed);
        current = new JsonObject();
        current.addProperty("seed", seed);
        current.addProperty("partial_start", partial);
        current.add("start", copy(event));
        current.add("events", new JsonArray());
    }

    boolean needsRoot(String seed) {
        return !seed.equals(this.seed) || current == null || (!current.has("root") && !current.has("root_error"));
    }

    void captureSolveRoot(String seed, JsonObject root, long time) {
        selectSeed(seed);
        if (current == null || !needsRoot(seed) || root == null) return;
        try {
            if (!root.has("ready_for_command") || !root.get("ready_for_command").getAsBoolean()) return;
            current.add("root", copy(root));
        } catch (RuntimeException failure) {
            current.addProperty("root_error", failure.toString());
        }
        current.addProperty("root_source", "solve");
        current.addProperty("root_time", time);
        current.addProperty("root_after_events", current.getAsJsonArray("events").size());
    }

    void record(String seed, JsonObject event, JsonObject root) {
        selectSeed(seed);
        String kind = event.get("kind").getAsString();
        if (kind.equals("battle_start")) start(seed, event, false);
        if (current == null) start(seed, event, true);
        if (root != null && !current.has("root")) current.add("root", copy(root));
        if (event.has("root_error")) current.addProperty("root_error", event.get("root_error").getAsString());
        JsonArray events = current.getAsJsonArray("events");
        if (events.size() < MAX_EVENTS) events.add(copy(event));
        else {
            current.addProperty("truncated", true);
            current.addProperty("dropped_events", current.has("dropped_events")
                    ? current.get("dropped_events").getAsInt() + 1 : 1);
        }
        if (kind.equals("battle_end")) {
            current.add("end", copy(event));
            completed.addLast(current);
            while (completed.size() > KEEP) completed.removeFirst();
            current = null;
        }
    }

    JsonObject snapshot(String seed) {
        JsonObject out = new JsonObject();
        out.addProperty("format", "sts1-recent-battles-v1");
        out.addProperty("scope", "actual actions; solver_driving is control mode, not proof of human input");
        JsonArray battles = new JsonArray();
        if (seed.equals(this.seed)) {
            for (JsonObject battle : completed) battles.add(copy(battle));
            if (current != null) out.add("current", copy(current));
        }
        out.add("completed", battles);
        return out;
    }
}
