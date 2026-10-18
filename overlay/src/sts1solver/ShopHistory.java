package sts1solver;

import com.google.gson.*;

/** Bounded snapshots of actual shop results, including a visit still in progress. */
final class ShopHistory {
    static final int MAX_VISITS = 64, MAX_CHANGES = 128;
    private String seed;
    private JsonArray completed = new JsonArray();
    private JsonObject current;
    private int dropped;

    private static JsonObject copy(JsonObject value) {
        return new JsonParser().parse(value.toString()).getAsJsonObject();
    }

    void reset(String value) { seed = value; completed = new JsonArray(); current = null; dropped = 0; }

    void observe(String value, String visit, JsonObject state) {
        if (!value.equals(seed)) reset(value);
        if (current != null && !current.get("visit").getAsString().equals(visit)) close();
        if (current == null) {
            current = new JsonObject();
            current.addProperty("visit", visit);
            current.addProperty("entered_at", System.currentTimeMillis());
            current.add("before", copy(state));
            current.add("changes", new JsonArray());
        }
        if (state.equals(current.get("after"))) return;
        current.add("after", copy(state));
        JsonArray changes = current.getAsJsonArray("changes");
        if (changes.size() < MAX_CHANGES) {
            JsonObject change = copy(state);
            change.addProperty("time", System.currentTimeMillis());
            changes.add(change);
        } else current.addProperty("changes_truncated", true);
    }

    void close() {
        if (current == null) return;
        current.addProperty("left_at", System.currentTimeMillis());
        completed.add(current);
        if (completed.size() > MAX_VISITS) { completed.remove(0); dropped++; }
        current = null;
    }

    JsonObject snapshot(String value) {
        JsonObject out = new JsonObject();
        out.addProperty("format", "sts1-shops-v1");
        out.addProperty("seed", value);
        out.addProperty("scope", "observed shop rooms; before is the first observed state, after is the latest settled observation");
        out.add("completed", value.equals(seed) ? new JsonParser().parse(completed.toString()) : new JsonArray());
        if (value.equals(seed)) {
            if (current != null) out.add("current", copy(current));
            if (dropped > 0) out.addProperty("dropped_visits", dropped);
        }
        return out;
    }

    void restore(String value, JsonObject saved) {
        reset(value);
        if (saved == null || !saved.has("seed") || !value.equals(saved.get("seed").getAsString())
                || !saved.has("format") || !"sts1-shops-v1".equals(saved.get("format").getAsString())) return;
        for (JsonElement entry : saved.getAsJsonArray("completed")) {
            if (completed.size() >= MAX_VISITS) { dropped++; continue; }
            completed.add(copy(entry.getAsJsonObject()));
        }
        if (saved.has("current")) current = copy(saved.getAsJsonObject("current"));
        if (saved.has("dropped_visits")) dropped += saved.get("dropped_visits").getAsInt();
    }
}
