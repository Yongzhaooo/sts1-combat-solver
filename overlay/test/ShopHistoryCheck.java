package sts1solver;

import com.google.gson.*;

public final class ShopHistoryCheck {
    static JsonObject state(int floor, int gold, String... cards) {
        JsonObject state = new JsonObject();
        state.addProperty("floor", floor); state.addProperty("gold", gold);
        JsonArray deck = new JsonArray(); for (String card : cards) deck.add(card);
        state.add("deck", deck); return state;
    }
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        ShopHistory h = new ShopHistory();
        JsonObject before = state(18, 300, "strike-a", "strike-b");
        h.observe("1", "1:18", before);
        h.observe("1", "1:18", before);
        require(h.snapshot("1").getAsJsonObject("current").getAsJsonArray("changes").size() == 1);
        before.addProperty("gold", 0);
        h.observe("1", "1:18", state(18, 225, "strike-b"));
        JsonObject saved = h.snapshot("1");
        require(saved.getAsJsonObject("current").getAsJsonObject("before").get("gold").getAsInt() == 300);
        ShopHistory restored = new ShopHistory(); restored.restore("1", saved);
        restored.close();
        JsonObject visit = restored.snapshot("1").getAsJsonArray("completed").get(0).getAsJsonObject();
        require(visit.getAsJsonObject("after").get("gold").getAsInt() == 225);
        require(visit.getAsJsonObject("after").getAsJsonArray("deck").size() == 1);
        require(visit.has("left_at"));
        for (int i = 0; i < 150; i++) restored.observe("1", "2:25", state(25, i));
        JsonObject current = restored.snapshot("1").getAsJsonObject("current");
        require(current.get("changes_truncated").getAsBoolean());
        require(current.getAsJsonObject("after").get("gold").getAsInt() == 149);
        require(restored.snapshot("2").getAsJsonArray("completed").size() == 0);
        restored.restore("2", saved);
        require(!restored.snapshot("2").has("current"));
        System.out.println("PASS: shop before/after, no-op dedup, save/load, ownership, truncation, seed isolation");
    }
}
