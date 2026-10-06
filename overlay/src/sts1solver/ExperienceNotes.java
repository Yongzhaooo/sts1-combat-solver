package sts1solver;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Opt-in, losslessly compressed observations. No network, microphone or save-file reads. */
final class ExperienceNotes {
    private static final int CHUNK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EVENT_BYTES = 8 * 1024 * 1024;
    private static final Set<String> PRIVATE = new HashSet<>(Arrays.asList(
        "username", "user_name", "player_name", "steam_id", "steamid", "email",
        "password", "token", "access_token", "authorization", "hostname", "home"));
    private final int chunkLimit;
    private boolean enabled;
    private Path directory, pending;
    private ZipOutputStream archive;
    private JsonObject metadata;
    private long sequence, started;
    private int bytes, part;
    private String previous = "";

    ExperienceNotes() { this(CHUNK_BYTES); }
    ExperienceNotes(int chunkLimit) { this.chunkLimit = chunkLimit; }
    boolean enabled() { return enabled; }
    long count() { return sequence; }
    Path directory() { return directory; }

    void start(Path parent, JsonObject versions) throws IOException {
        if (enabled) return;
        if (directory == null) {
            directory = parent.resolve("experience-" + UUID.randomUUID().toString().substring(0, 12));
            Files.createDirectories(directory);
            metadata = clean(versions).getAsJsonObject();
            started = System.nanoTime();
        }
        enabled = true;
        previous = "";
    }
    void stop() throws IOException { enabled = false; finish(); }

    static JsonElement clean(JsonElement value) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> item : value.getAsJsonObject().entrySet())
                if (!PRIVATE.contains(item.getKey().toLowerCase(Locale.ROOT))) out.add(item.getKey(), clean(item.getValue()));
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) out.add(clean(item));
            return out;
        }
        if (value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString().replaceAll("(?:[A-Za-z]:[\\\\/]|/(?:mnt|home|Users|tmp)/)[^\\s\\\"<>]+", "<local-path>");
            return new JsonPrimitive(text);
        }
        return value;
    }

    void record(String kind, JsonObject snapshot, String action, JsonObject plan) throws IOException {
        if (!enabled) return;
        JsonElement state = clean(snapshot);
        String signature = state.toString();
        if (kind.equals("observation") && signature.equals(previous)) return;
        JsonObject event = new JsonObject();
        event.addProperty("sequence", sequence + 1);
        event.addProperty("elapsed_ms", (System.nanoTime() - started) / 1000000);
        event.addProperty("kind", kind);
        event.addProperty("action_source", action == null ? "unattributed_observation" : "solver");
        event.addProperty("visibility", "privileged_raw_state; do not use as visible-only policy input");
        event.add("state", state);
        if (action != null) event.addProperty("action", action);
        if (plan != null) event.add("solver_plan", clean(plan));
        byte[] line = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > MAX_EVENT_BYTES) {
            stop();
            throw new IOException("Experience event exceeds 8 MiB; recording stopped without truncating it");
        }
        if (archive != null && bytes + line.length > chunkLimit) finish();
        if (archive == null) openPart();
        archive.write(line);
        bytes += line.length;
        sequence++;
        previous = signature;
    }

    private void openPart() throws IOException {
        pending = directory.resolve(String.format(Locale.ROOT, "part-%04d.zip.part", ++part));
        archive = new ZipOutputStream(Files.newOutputStream(pending, StandardOpenOption.CREATE_NEW));
        archive.putNextEntry(new ZipEntry("README.txt"));
        archive.write(("STS1 experience / 对局经验\nLossless structured game observations and solver actions, split into parts.\n完整结构化局面与求解器动作，无损压缩并分卷。\nRaw states contain hidden RNG/pools and must not be treated as visible-only inputs.\n原始局面含隐藏随机状态与牌池，不可直接作为可见信息策略输入。\nHuman clicks are not confirmed action labels. Full replay is not guaranteed.\n人类点击未作为已确认动作标签；不保证完整回放。\nReview before voluntarily contributing for AI training. No automatic upload.\n检查后再自愿贡献给 AI 训练；不会自动上传。\n").getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
        archive.putNextEntry(new ZipEntry("experience.jsonl"));
        JsonObject header = new JsonObject();
        header.addProperty("format", "sts1-experience-v1");
        header.addProperty("part", part);
        header.add("metadata", metadata);
        archive.write((header.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        bytes = 0;
    }

    Path finish() throws IOException {
        if (archive != null) {
            ZipOutputStream current = archive;
            archive = null;
            current.closeEntry();
            current.close();
            Files.move(pending, pending.resolveSibling(pending.getFileName().toString().replace(".zip.part", ".zip")));
        }
        return directory;
    }
}
