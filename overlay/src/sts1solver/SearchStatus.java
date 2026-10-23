package sts1solver;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Shared deadline contract and stage labels; counts are work units, never a percentage. The search bar is reassurance only. */
final class SearchStatus {
    private static final JsonObject CONTRACT;
    static {
        try (InputStreamReader reader = new InputStreamReader(
                SearchStatus.class.getResourceAsStream("/search_contract.json"), StandardCharsets.UTF_8)) {
            CONTRACT = new JsonParser().parse(reader).getAsJsonObject();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    static long timeoutMillis(int budget) {
        return (CONTRACT.get(budget >= 128000 ? "deep_timeout_seconds" : "standard_timeout_seconds").getAsLong()
            + CONTRACT.get("client_grace_seconds").getAsLong()) * 1000;
    }
    static long acknowledgedTimeoutMillis(long seconds) {
        if (seconds <= 0 || seconds > 3600) throw new IllegalArgumentException("Invalid backend deadline");
        return (seconds + CONTRACT.get("client_grace_seconds").getAsLong()) * 1000;
    }
    static boolean expired(long now, long sentAt, long timeoutMillis) { return now - sentAt > timeoutMillis; }
    /** Search bar fill from elapsed time alone: half at 8 s, never past 95 %. It shows the search is alive, not how far it is. */
    static float waitingFill(long elapsedMillis) {
        float seconds = Math.max(0, elapsedMillis) / 1000f;
        return Math.min(.95f, seconds / (seconds + 8));
    }
    static String phase(JsonObject row) {
        String phase = row.has("phase") ? row.get("phase").getAsString() : "mcts";
        switch (phase) {
            case "ready": return I18n.t("路线可执行");
            case "finalizing": return I18n.t("验证与精简");
            case "cycle": return I18n.t("循环启动搜索");
            case "beam": return I18n.t("循环补充搜索");
            case "smoke_probe": return I18n.t("无药止损预检");
            case "smoke_opening": return I18n.t("开局逃跑候选");
            default: return I18n.t(row.has("budget") && row.get("budget").getAsInt() >= 128000
                ? "追加深搜" : "基础搜索");
        }
    }
}
