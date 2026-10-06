package sts1solver;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.UUID;

/** Observed inventory transitions, without guessing the cause of a removal. */
final class DecisionNotes {
    private final String session = UUID.randomUUID().toString();
    private JsonObject previous;
    private long sequence;
    private long segment;

    long sequence() { return sequence; }
    String session() { return session; }

    void append(Path directory, JsonObject current, JsonElement state) throws IOException {
        append(directory, current, state, new JsonObject());
    }

    void append(Path directory, JsonObject current, JsonElement state, JsonObject recovery) throws IOException {
        if (current.equals(previous)) return;
        boolean sameRun = previous != null && previous.get("seed").equals(current.get("seed"))
                && current.get("floor").getAsInt() >= previous.get("floor").getAsInt();
        long nextSegment = previous != null && !sameRun ? segment + 1 : segment;
        JsonObject row = new JsonObject();
        row.addProperty("schema", 2);
        row.addProperty("kind", "state_observation");
        row.addProperty("action_observed", false);
        row.addProperty("run_segment", nextSegment);
        row.addProperty("continuity", sameRun ? "observed_transition" : "segment_start");
        row.addProperty("session", session);
        row.addProperty("sequence", sequence + 1);
        row.addProperty("observed_at", java.time.Instant.now().toString());
        row.add("before", sameRun ? previous : null);
        row.add("after", current);
        row.add("state", state);
        row.addProperty("state_information", "raw_privileged_not_policy_input");
        row.add("privileged_recovery", recovery);
        if (sameRun) {
            row.add("cards_added", cards(current.getAsJsonArray("deck"), previous.getAsJsonArray("deck"), false));
            row.add("cards_removed", cards(previous.getAsJsonArray("deck"), current.getAsJsonArray("deck"), false));
            row.add("cards_changed", cards(current.getAsJsonArray("deck"), previous.getAsJsonArray("deck"), true));
            row.add("potions_added", difference(current.getAsJsonArray("potions"), previous.getAsJsonArray("potions")));
            row.add("potions_removed", difference(previous.getAsJsonArray("potions"), current.getAsJsonArray("potions")));
        }
        Files.createDirectories(directory);
        Files.write(directory.resolve(session + ".jsonl"), (row.toString()+"\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        previous = new JsonParser().parse(current.toString()).getAsJsonObject();
        segment = nextSegment;
        sequence++;
    }

    void reset() { if (previous != null) segment++; previous = null; }

    static JsonArray cards(JsonArray after, JsonArray before, boolean changed) {
        java.util.Map<String, JsonElement> old = new java.util.HashMap<>();
        for (JsonElement value : before) old.put(value.getAsJsonObject().get("uuid").getAsString(), value);
        JsonArray result = new JsonArray();
        for (JsonElement value : after) {
            JsonElement prior = old.get(value.getAsJsonObject().get("uuid").getAsString());
            if (changed ? prior != null && !prior.equals(value) : prior == null) result.add(value);
        }
        return result;
    }

    static JsonArray difference(JsonArray after, JsonArray before) {
        java.util.List<JsonElement> remaining = new java.util.ArrayList<>();
        for (JsonElement value : before) remaining.add(value);
        JsonArray added = new JsonArray();
        for (JsonElement value : after) if (!remaining.remove(value)) added.add(value);
        return added;
    }
}
