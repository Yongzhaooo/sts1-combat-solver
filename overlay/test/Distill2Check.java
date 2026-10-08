package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** PyTorch reference packet scored using only the JDK and bundled model. */
public final class Distill2Check {
    private static void fill(float[] target, JsonArray sparse) {
        for (int i = 0; i < sparse.size(); i++) {
            JsonArray entry = sparse.get(i).getAsJsonArray();
            target[entry.get(0).getAsInt()] = entry.get(1).getAsFloat();
        }
    }

    public static void main(String[] args) throws Exception {
        JsonObject fixture;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(args[0]), StandardCharsets.UTF_8)) {
            fixture = new JsonParser().parse(reader).getAsJsonObject();
        }
        float[] observation = new float[6843], extra = new float[20];
        fill(observation, fixture.getAsJsonArray("observation"));
        JsonArray extras = fixture.getAsJsonArray("extra");
        for (int i = 0; i < extra.length; i++) extra[i] = extras.get(i).getAsFloat();
        JsonArray descriptors = fixture.getAsJsonArray("descriptors");
        float[][] desc = new float[descriptors.size()][807], routes = new float[descriptors.size()][26];
        for (int i = 0; i < desc.length; i++) {
            fill(desc[i], descriptors.get(i).getAsJsonArray());
            fill(routes[i], fixture.getAsJsonArray("routes").get(i).getAsJsonArray());
        }
        Distill2 model = Distill2.load();
        if (model.id("cards", "Strike_R") != model.id("cards", "STRIKE_RED")
                || model.id("cards", "Bash") != 25
                || model.id("potions", "Potion Slot") != 1)
            throw new AssertionError("Simulator ID mapping drift");
        JsonObject original;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(
                new java.io.File(new java.io.File(args[0]).getParentFile(), "original-game-ids.json")),
                StandardCharsets.UTF_8)) {
            original = new JsonParser().parse(reader).getAsJsonObject();
        }
        for (String kind : new String[]{"cards", "relics", "potions", "events"})
            for (com.google.gson.JsonElement id : original.getAsJsonArray(kind))
                model.id(kind, id.getAsString()); // an unmapped original-game ID would stop the pilot mid-run
        long start = System.nanoTime();
        Distill2.Result result = model.score(observation, extra, desc, routes);
        long elapsed = System.nanoTime() - start;
        if (result.best != fixture.get("best").getAsInt()) throw new AssertionError("Student choice drift");
        JsonArray expected = fixture.getAsJsonArray("scores");
        for (int i = 0; i < result.scores.length; i++)
            if (Math.abs(result.scores[i] - expected.get(i).getAsFloat()) > .002f)
                throw new AssertionError("Student score drift at " + i + ": " + result.scores[i]
                    + " vs " + expected.get(i));
        System.out.printf("PASS: portable distill2 scores, every original-game ID mapped; %d candidates in %.1f ms%n",
            desc.length, elapsed / 1_000_000.0);
    }
}
