package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Frozen public-state scorer. It needs only the JDK at player runtime. */
final class Distill2 {
    private static final String SCHEMA_ID = "31c15b49f3131c57cf59a427a798e1274f558c52a22fce68618676459bf05f34";
    private final float[][] first;
    private final float[] firstBias, last;
    private final float lastBias;
    private final float[][] cardTypes;
    private final float[] nonstarter;
    private final JsonObject ids;
    private final JsonObject offsets;
    private final int obsDim, baseObsDim, descDim, cardCap, deckOffset, actionWidth,
        cardOffset, cardWidth, cardUpgradeOffset, baseDim, contextDim;
    private final float specialScale;
    private final int[] scalarIndices;

    private Distill2(JsonObject schema, DataInputStream input) throws IOException {
        if (!SCHEMA_ID.equals(schema.get("schema_id").getAsString()))
            throw new IOException("Unsupported distill2 feature schema");
        obsDim = number(schema, "OBS_DIM"); baseObsDim = number(schema, "BASE_OBS_DIM");
        descDim = number(schema, "DESC_DIM"); cardCap = number(schema, "CARD_CAP");
        deckOffset = number(schema, "deck_offset"); actionWidth = number(schema, "W_ACTION");
        cardOffset = number(schema, "OFF_CARD"); cardWidth = number(schema, "W_CARD");
        cardUpgradeOffset = number(schema, "OFF_CARD_UPGRADE");
        baseDim = number(schema, "base_dim"); contextDim = number(schema, "context_dim");
        specialScale = schema.get("special_scale").getAsFloat();
        int width = number(schema, "width");
        scalarIndices = ints(schema.getAsJsonArray("scalar_indices"));
        cardTypes = matrix(schema.getAsJsonArray("card_types"));
        nonstarter = floats(schema.getAsJsonArray("nonstarter_attacks"));
        ids = schema.getAsJsonObject("ids");
        offsets = schema.getAsJsonObject("offsets");
        if (obsDim != 6843 || baseObsDim != 6820 || descDim != 807 || cardCap != 371
                || deckOffset != 3980 || cardWidth != cardCap || actionWidth != 24
                || contextDim != 2 * descDim || baseDim != 5529 || width != 384
                || scalarIndices.length != 12 || cardTypes.length != cardCap
                || nonstarter.length != cardCap)
            throw new IOException("Unexpected distill2 dimensions");
        byte[] magic = new byte[8];
        input.readFully(magic);
        if (!Arrays.equals(magic, "D2MLP001".getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Invalid distill2 weight header");
        int featureDim = baseDim + deckOffset + obsDim - baseObsDim + 20 + 3 + contextDim + 26;
        if (featureDim != 11195) throw new IOException("Unexpected distill2 input width");
        first = new float[width][featureDim];
        for (float[] row : first) for (int i = 0; i < row.length; i++) row[i] = input.readFloat();
        firstBias = new float[width];
        for (int i = 0; i < width; i++) firstBias[i] = input.readFloat();
        last = new float[width];
        for (int i = 0; i < width; i++) last[i] = input.readFloat();
        lastBias = input.readFloat();
        if (input.read() != -1) throw new IOException("Trailing distill2 weights");
    }

    static Distill2 load() throws IOException {
        try (InputStream metadata = Distill2.class.getResourceAsStream("/sts1solver/distill2-schema.json");
             InputStream raw = Distill2.class.getResourceAsStream("/sts1solver/distill2-weights.bin")) {
            if (metadata == null || raw == null) throw new IOException("Bundled distill2 model missing");
            JsonObject schema = new JsonParser().parse(
                new InputStreamReader(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(raw))) {
                return new Distill2(schema, input);
            }
        }
    }

    static final class Result {
        final float[] scores;
        final int best;
        Result(float[] scores, int best) { this.scores = scores; this.best = best; }
    }

    /** Original-game IDs whose simulator names differ; every card, relic, potion and event ID was checked. */
    private static final java.util.Map<String, String> ALIASES = new java.util.HashMap<>();
    static {
        String[][] pairs = {
            {"cards", "Strike_R", "STRIKE_RED"}, {"cards", "Defend_R", "DEFEND_RED"},
            {"cards", "Ghostly", "APPARITION"}, {"cards", "J.A.X.", "JAX"},
            {"potions", "Potion Slot", "EMPTY_POTION_SLOT"}, {"potions", "SteroidPotion", "FLEX_POTION"},
            {"potions", "Elixir", "ELIXIR_POTION"}, {"potions", "GhostInAJar", "GHOST_IN_A_JAR"},
            {"relics", "Boot", "THE_BOOT"}, {"relics", "CultistMask", "CULTIST_HEADPIECE"},
            {"relics", "Yang", "DUALITY"}, {"relics", "Frozen Egg 2", "FROZEN_EGG"},
            {"relics", "Molten Egg 2", "MOLTEN_EGG"}, {"relics", "Toxic Egg 2", "TOXIC_EGG"},
            {"relics", "Cables", "GOLD_PLATED_CABLES"}, {"relics", "GremlinMask", "GREMLIN_VISAGE"},
            {"relics", "NeowsBlessing", "NEOWS_LAMENT"}, {"relics", "NlothsMask", "NLOTHS_HUNGRY_FACE"},
            {"relics", "Paper Crane", "PAPER_KRANE"}, {"relics", "Paper Frog", "PAPER_PHROG"},
            {"relics", "Sling", "SLING_OF_COURAGE"}, {"relics", "Snake Skull", "SNECKO_SKULL"},
            {"relics", "WingedGreaves", "WING_BOOTS"},
            {"events", "MindBloom", "MINDBLOOM"}, {"events", "Addict", "PLEADING_VAGRANT"},
            {"events", "Back to Basics", "ANCIENT_WRITING"}, {"events", "Beggar", "OLD_BEGGAR"},
            {"events", "Drug Dealer", "AUGMENTER"}, {"events", "Nest", "THE_NEST"},
            {"events", "Golden Wing", "WING_STATUE"}, {"events", "Mushrooms", "HYPNOTIZING_COLORED_MUSHROOMS"},
            {"events", "Liars Game", "THE_SSSSSERPENT"}, {"events", "Accursed Blacksmith", "OMINOUS_FORGE"},
            {"events", "Bonfire Elementals", "BONFIRE_SPIRITS"}, {"events", "Designer", "DESIGNER_IN_SPIRE"},
            {"events", "Fountain of Cleansing", "THE_DIVINE_FOUNTAIN"}};
        for (String[] pair : pairs) ALIASES.put(pair[0] + ':' + pair[1], pair[2]);
    }

    int id(String kind, String original) {
        String key = ALIASES.get(kind + ':' + original);
        if (key == null) key = original.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .replace("-", " ").replace("'", "")
            .replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "")
            .toUpperCase(java.util.Locale.ROOT);
        JsonObject table = ids.getAsJsonObject(kind);
        if (table == null || !table.has(key))
            throw new IllegalArgumentException("Unsupported student " + kind + " ID: " + original);
        return table.get(key).getAsInt();
    }

    int offset(String name) {
        if (!offsets.has(name)) throw new IllegalArgumentException("Unknown student offset: " + name);
        return offsets.get(name).getAsInt();
    }

    Result score(float[] observation, float[] extra, float[][] descriptors, float[][] routes) {
        int count = descriptors.length;
        if (observation.length != obsDim || extra.length != 20 || count == 0
                || routes.length != count) throw new IllegalArgumentException("Invalid distill2 packet");
        finite(observation); finite(extra);
        float[] context = new float[contextDim];
        Arrays.fill(context, descDim, context.length, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < count; i++) {
            float[] descriptor = descriptors[i];
            if (descriptor.length != descDim || routes[i].length != 26)
                throw new IllegalArgumentException("Invalid distill2 candidate dimensions");
            finite(descriptor); finite(routes[i]);
            for (int j = 0; j < descDim; j++) {
                context[j] += descriptor[j];
                context[descDim+j] = Math.max(context[descDim+j], descriptor[j]);
            }
        }
        for (int j = 0; j < descDim; j++) context[j] /= count;
        float[] scores = new float[count];
        int best = 0;
        for (int i = 0; i < count; i++) {
            float[] features = features(observation, extra, descriptors[i], routes[i], context, i, count);
            float score = lastBias;
            for (int h = 0; h < first.length; h++) {
                float value = firstBias[h];
                float[] weights = first[h];
                for (int j = 0; j < weights.length; j++) value += weights[j] * features[j];
                score += last[h] * Math.max(0f, value);
            }
            if (!Float.isFinite(score)) throw new IllegalArgumentException("Non-finite distill2 score");
            scores[i] = score;
            if (score > scores[best]) best = i;
        }
        return new Result(scores, best);
    }

    private float[] features(float[] obs, float[] extra, float[] desc, float[] route,
                             float[] context, int index, int count) {
        float[] out = new float[first[0].length];
        int at = 0;
        System.arraycopy(desc, 0, out, at, descDim); at += descDim;
        for (int kind = 0; kind < actionWidth; kind++)
            for (int scalar : scalarIndices) out[at++] = desc[kind] * obs[scalar];
        int act = Math.max(1, Math.min(4, (int)(obs[4] * 4))) - 1;
        for (int kind = 0; kind < actionWidth; kind++)
            for (int a = 0; a < 4; a++) out[at++] = desc[kind] * (a == act ? 1f : 0f);
        for (int card = 0; card < cardWidth; card++)
            for (int a = 0; a < 4; a++) out[at++] = desc[cardOffset+card] * (a == act ? 1f : 0f);
        System.arraycopy(obs, deckOffset, out, at, baseObsDim - deckOffset);
        at += baseObsDim - deckOffset;
        float selectedCount = 0, selectedUpgrade = 0, nonstarterCount = 0;
        float[] typeSelected = new float[5], typeCounts = new float[5];
        for (int card = 0; card < cardCap; card++) {
            float face = obs[deckOffset+2*card] * 20;
            float upgrades = obs[deckOffset+2*card+1] * 20;
            float amount = face + upgrades;
            float selection = desc[cardOffset+card];
            selectedCount += selection * amount;
            selectedUpgrade += selection * upgrades;
            for (int type = 0; type < 5; type++) {
                typeSelected[type] += selection * cardTypes[card][type];
                typeCounts[type] += amount * cardTypes[card][type];
            }
            nonstarterCount += amount * nonstarter[card];
        }
        out[at++] = selectedCount; out[at++] = selectedUpgrade;
        for (float value : typeSelected) out[at++] = value;
        for (float value : typeCounts) out[at++] = value / 10;
        out[at++] = nonstarterCount / 10;
        out[at++] = desc[cardUpgradeOffset] * specialScale;
        if (at != baseDim) throw new IllegalStateException("Distill2 parent feature drift");
        System.arraycopy(obs, 0, out, at, deckOffset); at += deckOffset;
        System.arraycopy(obs, baseObsDim, out, at, obsDim-baseObsDim); at += obsDim-baseObsDim;
        System.arraycopy(extra, 0, out, at, extra.length); at += extra.length;
        out[at++] = index / 32f;
        out[at++] = (float)index / Math.max(1, count-1);
        out[at++] = count / 32f;
        System.arraycopy(context, 0, out, at, context.length); at += context.length;
        System.arraycopy(route, 0, out, at, route.length); at += route.length;
        if (at != out.length) throw new IllegalStateException("Distill2 feature drift");
        return out;
    }

    private static int number(JsonObject object, String key) { return object.get(key).getAsInt(); }
    private static int[] ints(JsonArray array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsInt();
        return out;
    }
    private static float[] floats(JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsFloat();
        return out;
    }
    private static float[][] matrix(JsonArray array) {
        float[][] out = new float[array.size()][];
        for (int i = 0; i < out.length; i++) out[i] = floats(array.get(i).getAsJsonArray());
        return out;
    }
    private static void finite(float[] values) {
        for (float value : values) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Non-finite distill2 input");
    }
}
