package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Heart simulation: when the button can be pressed, how replies are sorted, the request ids and seeds, and the cancel on entering a fight. */
public final class HeartSimCheck {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static JsonObject json(String text) { return new JsonParser().parse(text).getAsJsonObject(); }

    static final AbstractPlayer.PlayerClass IRONCLAD = AbstractPlayer.PlayerClass.IRONCLAD;

    static HeartSim fresh() {
        AtomicLong next = new AtomicLong(1000);
        return new HeartSim(null, () -> next.getAndIncrement());
    }

    static HeartSim running() {
        HeartSim heart = fresh();
        heart.begin(json("{\"game_state\":{}}"), 0);
        return heart;
    }

    public static void main(String[] args) {
        I18n.setLanguage("zh");
        button();
        replyRouting();
        classification();
        requests();
        enteringCombat();
        progressAndDeadline();
        leavingAndClosing();
        faults();
        rows();
        englishText();
        System.out.println("PASS: heart simulation button rules, reply routing and classes, ids and seeds, search workers, cancel on entering a fight, deadline, faults, rows, English text");
    }

    /** Every reason the button is unusable has a case that fails alone; the one good case passes. */
    static void button() {
        require(HeartSim.canStart(true, true, false, IRONCLAD, false), "switch on, in the dungeon, out of combat, Ironclad, nothing running: usable");
        require(HeartSim.block(true, true, false, IRONCLAD, false) == HeartSim.Block.NONE, "usable means no block");
        require(!HeartSim.canStart(false, true, false, IRONCLAD, false), "switch off: not usable");
        require(HeartSim.block(false, true, false, IRONCLAD, false) == HeartSim.Block.DISABLED, "switch off is named");
        require(!HeartSim.canStart(true, false, false, IRONCLAD, false), "outside a run: not usable");
        require(HeartSim.block(true, false, false, IRONCLAD, false) == HeartSim.Block.NOT_IN_DUNGEON, "outside a run is named");
        require(!HeartSim.canStart(true, true, true, IRONCLAD, false), "in a fight: not usable");
        require(HeartSim.block(true, true, true, IRONCLAD, false) == HeartSim.Block.IN_COMBAT, "in a fight is named");
        for (AbstractPlayer.PlayerClass other : AbstractPlayer.PlayerClass.values()) {
            if (other == IRONCLAD) continue;
            require(!HeartSim.canStart(true, true, false, other, false), other + ": not usable");
            require(HeartSim.block(true, true, false, other, false) == HeartSim.Block.UNSUPPORTED_CLASS, other + " is named");
        }
        require(!HeartSim.canStart(true, true, false, null, false), "no player: not usable");
        require(!HeartSim.canStart(true, true, false, IRONCLAD, true), "one already running: not usable");
        require(HeartSim.block(true, true, false, IRONCLAD, true) == HeartSim.Block.RUNNING, "already running is named");
        // The reason shown is the first one in the order a player would fix them.
        require(HeartSim.block(false, false, true, null, true) == HeartSim.Block.DISABLED, "switch first");
        require(HeartSim.block(true, true, true, AbstractPlayer.PlayerClass.DEFECT, true) == HeartSim.Block.IN_COMBAT, "combat before class");
        require(HeartSim.tip(HeartSim.Block.UNSUPPORTED_CLASS).equals("暂只支持战士"), "non-Ironclad hint");
        Set<String> tips = new HashSet<>();
        for (HeartSim.Block block : HeartSim.Block.values()) tips.add(HeartSim.tip(block));
        require(tips.size() >= 5, "each block has its own hint");

        // The live state drives the button through the same function: a running simulation greys it.
        HeartSim heart = fresh();
        require(!heart.running(), "starts idle");
        heart.begin(json("{}"), 0);
        require(heart.running() && !HeartSim.canStart(true, true, false, IRONCLAD, heart.running()), "a running simulation blocks a second press");
    }

    /** Heart ids go to the heart queue; the solver's ids, the transport error's -1 and malformed ids stay with the solver. */
    static void replyRouting() {
        require(BackendClient.isHeartReply(json("{\"id\":-1000,\"status\":\"progress\"}")), "-1000 is the first heart id");
        require(BackendClient.isHeartReply(json("{\"id\":-1001,\"status\":\"ready\"}")), "-1001 is a heart id");
        require(BackendClient.isHeartReply(json("{\"id\":-123456789012,\"status\":\"ready\"}")), "far below -1000 is a heart id");
        require(!BackendClient.isHeartReply(json("{\"id\":-999,\"status\":\"ready\"}")), "-999 is not");
        require(!BackendClient.isHeartReply(json("{\"id\":-1,\"status\":\"transport_error\"}")), "the transport error's -1 stays with the solver");
        require(!BackendClient.isHeartReply(json("{\"id\":0,\"status\":\"ready\"}")), "0 is not");
        require(!BackendClient.isHeartReply(json("{\"id\":7,\"status\":\"ready\"}")), "a solver id is not");
        require(!BackendClient.isHeartReply(json("{\"status\":\"ready\"}")), "no id is not");
        require(!BackendClient.isHeartReply(json("{\"id\":\"-2000\",\"status\":\"ready\"}")), "a text id is not");
        require(!BackendClient.isHeartReply(json("{\"id\":null,\"status\":\"ready\"}")), "a null id is not");
        require(!BackendClient.isHeartReply(json("{\"id\":[-2000]}")), "an array id is not");
        require(!BackendClient.isHeartReply(null), "nothing is not");
    }

    static void classification() {
        HeartSim.Result win = HeartSim.parse(json("{\"id\":-1000,\"status\":\"ready\",\"heart_result\":\"win\",\"seconds\":41.2,\"simulations\":1234567,\"seed\":\"9\",\"hp\":12}"));
        require(win.outcome == HeartSim.Outcome.WIN && win.seconds == 41.2 && win.simulations == 1234567, "win with time and simulations");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"ready\",\"heart_result\":\"no_win\"}")).outcome == HeartSim.Outcome.NO_WIN, "no_win");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"ready\",\"heart_result\":\"timeout\"}")).outcome == HeartSim.Outcome.TIMEOUT, "timeout");
        HeartSim.Result error = HeartSim.parse(json("{\"id\":-1000,\"status\":\"error\",\"message\":\"无法模拟：某某遗物\"}"));
        require(error.outcome == HeartSim.Outcome.ERROR && error.message.equals("无法模拟：某某遗物") && !error.local, "error keeps the backend's message");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"cancelled\"}")).outcome == HeartSim.Outcome.CANCELLED, "cancelled");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"progress\",\"phase\":\"x\"}")) == null, "progress is not a result");
        // A "ready" reply that does not say win / no win / timeout is never shown as one of them.
        HeartSim.Result missing = HeartSim.parse(json("{\"id\":-1000,\"status\":\"ready\"}"));
        require(missing.outcome == HeartSim.Outcome.ERROR && missing.local && !missing.message.isEmpty(), "ready without heart_result is an error");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"ready\",\"won\":true,\"hp\":30}")).outcome == HeartSim.Outcome.ERROR, "a solver-style win is not a heart win");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"ready\",\"heart_result\":\"WIN\"}")).outcome == HeartSim.Outcome.ERROR, "an unknown spelling is an error");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"ready\",\"heart_result\":\"\"}")).outcome == HeartSim.Outcome.ERROR, "an empty result is an error");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"error\"}")).outcome == HeartSim.Outcome.ERROR, "an error without a message is still an error");
        require(HeartSim.parse(json("{\"id\":-1000,\"status\":\"complete\"}")).outcome == HeartSim.Outcome.ERROR, "an unknown status is an error");
        require(HeartSim.parse(json("{\"id\":-1000}")).outcome == HeartSim.Outcome.ERROR, "no status is an error");

        // The state machine takes the same classification.
        for (String[] row : new String[][] {
                {"{\"status\":\"ready\",\"heart_result\":\"win\"}", "WIN"}, {"{\"status\":\"ready\",\"heart_result\":\"no_win\"}", "NO_WIN"},
                {"{\"status\":\"ready\",\"heart_result\":\"timeout\"}", "TIMEOUT"}, {"{\"status\":\"error\",\"message\":\"m\"}", "ERROR"},
                {"{\"status\":\"cancelled\"}", "CANCELLED"}, {"{\"status\":\"ready\"}", "ERROR"}}) {
            HeartSim heart = running();
            JsonObject reply = json(row[0]);
            reply.addProperty("id", heart.activeId());
            heart.accept(reply, 5000);
            require(heart.phase() == HeartSim.Phase.DONE && heart.result().outcome.name().equals(row[1]), row[0] + " -> " + row[1]);
            require(!heart.running(), "a finished simulation is not running: " + row[0]);
        }
        // Three result texts, and they differ.
        Set<String> texts = new HashSet<>();
        for (HeartSim.Outcome outcome : HeartSim.Outcome.values())
            texts.add(HeartSim.headline(HeartSim.Result.local(outcome, "已取消")));
        require(texts.size() == HeartSim.Outcome.values().length, "every outcome reads differently: " + texts);
        require(HeartSim.headline(HeartSim.Result.local(HeartSim.Outcome.WIN, "")).equals("胜"), "win reads 胜");
        require(HeartSim.headline(HeartSim.Result.local(HeartSim.Outcome.NO_WIN, "")).equals("未找到胜线"), "no_win reads 未找到胜线");
        require(HeartSim.headline(HeartSim.Result.local(HeartSim.Outcome.TIMEOUT, "")).equals("超时未算完"), "timeout reads 超时未算完");
        require(HeartSim.headline(HeartSim.Result.local(HeartSim.Outcome.NO_RESPONSE, "")).equals("后台未响应"), "no response reads 后台未响应");
    }

    /** Ids count down from -1000 and never repeat; every request gets its own seed. */
    static void requests() {
        JsonObject state = json("{\"game_state\":{\"floor\":3}}");
        HeartSim heart = fresh();
        JsonObject first = heart.begin(state, 100);
        require(first.get("id").getAsLong() == -1000, "the first id is -1000");
        require(first.get("op").getAsString().equals("heart_sim"), "op");
        require(first.get("progress").getAsBoolean(), "progress is requested");
        require(first.get("search_workers").getAsInt() == 0, "the default worker count is auto (0)");
        HeartSim threads = new HeartSim(null, null, () -> 3, () -> 77L);
        JsonObject threaded = threads.begin(state, 0);
        require(threaded.get("search_workers").getAsInt() == 3 && threaded.get("seed").getAsString().equals("77"), "the solver's worker setting travels with the request");
        threads.cancel("已取消", 1);
        require(first.get("state") == state, "the whole state travels");
        require(first.get("seed").getAsJsonPrimitive().isString() && first.get("seed").getAsString().matches("[0-9]+"), "the seed is a decimal string");
        require(heart.seed().equals(first.get("seed").getAsString()), "the seed is remembered for the panel and the log");
        heart.cancel("已取消", 200);
        JsonObject second = heart.begin(state, 300);
        require(second.get("id").getAsLong() == -1002, "the next request skips the cancel's id: " + second.get("id"));
        require(!second.get("seed").getAsString().equals(first.get("seed").getAsString()), "a new press draws a new seed");

        // An unsigned 64-bit seed: the all-ones pattern is 2^64-1, not -1.
        HeartSim wide = new HeartSim(null, () -> -1L);
        require(wide.begin(state, 0).get("seed").getAsString().equals("18446744073709551615"), "the seed is unsigned");
        // The real random source differs between presses.
        HeartSim real = new HeartSim(null);
        Set<String> seeds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            JsonObject request = real.begin(state, i);
            seeds.add(request.get("seed").getAsString());
            real.cancel("已取消", i);
        }
        require(seeds.size() == 20, "twenty presses, twenty seeds");

        // Every id handed out (requests and cancels) is <= -1000 and strictly decreasing.
        HeartSim ids = fresh();
        List<Long> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seen.add(ids.begin(state, 0).get("id").getAsLong());
            seen.add(ids.cancel("已取消", 0).get("id").getAsLong());
        }
        for (int i = 0; i < seen.size(); i++) {
            require(seen.get(i) <= -1000, "id " + seen.get(i) + " is -1000 or lower");
            if (i > 0) require(seen.get(i) < seen.get(i - 1), "ids keep falling: " + seen);
        }
        require(new HashSet<>(seen).size() == seen.size(), "no id repeats");
    }

    /** Entering a fight cancels a running simulation before the solver's first request, and everything after is ignored. */
    static void enteringCombat() {
        HeartSim heart = running();
        long original = heart.activeId();
        JsonObject cancel = heart.enterCombat(7000);
        require(cancel != null && cancel.get("op").getAsString().equals("cancel"), "a cancel request is made");
        require(cancel.get("id").getAsLong() <= -1000 && cancel.get("id").getAsLong() != original, "the cancel has its own heart id");
        require(!cancel.has("state"), "a cancel carries no state");
        require(heart.phase() == HeartSim.Phase.DONE && heart.result().outcome == HeartSim.Outcome.CANCELLED, "state is cancelled");
        require(HeartSim.headline(heart.result()).equals("已取消：进入战斗"), "the panel says why: " + HeartSim.headline(heart.result()));
        require(!heart.running(), "no longer running, so the button is usable again after the fight");
        // Any reply to the original request or to the cancel counts as already ended.
        heart.accept(json("{\"id\":" + original + ",\"status\":\"ready\",\"heart_result\":\"win\"}"), 7100);
        heart.accept(json("{\"id\":" + cancel.get("id").getAsLong() + ",\"status\":\"cancelled\"}"), 7200);
        heart.accept(json("{\"id\":" + original + ",\"status\":\"progress\",\"phase\":\"late\"}"), 7300);
        heart.accept(json("{\"id\":" + original + ",\"status\":\"error\",\"message\":\"late\"}"), 7400);
        require(heart.result().outcome == HeartSim.Outcome.CANCELLED && HeartSim.headline(heart.result()).equals("已取消：进入战斗"),
            "late replies do not change the cancelled panel");
        require(heart.enterCombat(7500) == null, "a second fight start sends nothing");

        // Counterexamples: nothing running, or already finished, sends nothing and changes nothing.
        HeartSim idle = fresh();
        require(idle.enterCombat(0) == null && idle.phase() == HeartSim.Phase.IDLE, "idle: no cancel");
        HeartSim done = running();
        done.accept(json("{\"id\":" + done.activeId() + ",\"status\":\"ready\",\"heart_result\":\"win\"}"), 10);
        require(done.enterCombat(20) == null && done.result().outcome == HeartSim.Outcome.WIN, "a finished win stays a win");

        // A new press after the fight starts a new request; the old request's late reply cannot end it.
        HeartSim again = running();
        long old = again.activeId();
        again.enterCombat(1);
        JsonObject next = again.begin(json("{}"), 2);
        again.accept(json("{\"id\":" + old + ",\"status\":\"ready\",\"heart_result\":\"win\"}"), 3);
        require(again.running() && again.activeId() == next.get("id").getAsLong(), "a reply to an older request is ignored");
        // Replies with the solver's ids never end it either.
        again.accept(json("{\"id\":" + again.activeId() + ",\"status\":\"ready\",\"heart_result\":\"no_win\"}"), 4);
        require(again.result().outcome == HeartSim.Outcome.NO_WIN, "its own reply does");

        // A player's cancel button is a cancel too, with its own words.
        HeartSim player = running();
        JsonObject byPlayer = player.cancel("已取消", 9);
        require(byPlayer != null && HeartSim.headline(player.result()).equals("已取消"), "the cancel button reads 已取消");
    }

    static void progressAndDeadline() {
        HeartSim heart = running();
        require(heart.timeoutMillis() == SearchStatus.timeoutMillis(128000) && heart.timeoutMillis() == 610000, "default is the deep tier: 600 s plus grace");
        heart.accept(json("{\"id\":" + heart.activeId() + ",\"status\":\"progress\",\"phase\":\"阶段名\",\"branches\":[{\"phase\":\"mcts\",\"simulations\":5}]}"), 1000);
        require(heart.stage().equals("阶段名"), "the stage name is kept");
        require(heart.timeoutMillis() == 610000, "no timeout_seconds keeps the deep default");
        heart.accept(json("{\"id\":" + heart.activeId() + ",\"status\":\"progress\",\"timeout_seconds\":150}"), 2000);
        require(heart.timeoutMillis() == SearchStatus.acknowledgedTimeoutMillis(150) && heart.timeoutMillis() == 160000, "an acknowledged timeout is used");
        heart.accept(json("{\"id\":" + heart.activeId() + ",\"status\":\"progress\",\"timeout_seconds\":0}"), 2500);
        require(heart.timeoutMillis() == 160000 && heart.running(), "a bad timeout is ignored, not fatal");

        require(heart.tick(160000, true) == null && heart.running(), "at the deadline exactly it still runs");
        JsonObject cancel = heart.tick(160001, true);
        require(heart.result().outcome == HeartSim.Outcome.NO_RESPONSE && cancel != null && cancel.get("op").getAsString().equals("cancel"),
            "past the deadline without a final reply: backend not responding, and a cancel is sent");
        require(cancel.get("id").getAsLong() <= -1000, "its id is a heart id");
        require(heart.tick(999999, true) == null, "nothing more once ended");

        HeartSim dead = running();
        require(dead.tick(5, true) == null && dead.running(), "a live backend within the deadline keeps running");
        require(dead.tick(5, false) == null && dead.result().outcome == HeartSim.Outcome.NO_RESPONSE, "a dead backend: not responding, nothing to write to");

        HeartSim deep = running();
        require(deep.tick(609999, true) == null && deep.running(), "deep default: still within 610 s");
        require(deep.tick(610001, true) != null && deep.result().outcome == HeartSim.Outcome.NO_RESPONSE, "deep default: past 610 s");

        // The bar is the solver's: elapsed time only, never full, no percentage.
        require(SearchStatus.waitingFill(0) == 0 && SearchStatus.waitingFill(3600000) <= .95f, "the waiting bar is time only");
    }

    static void leavingAndClosing() {
        HeartSim heart = running();
        JsonObject cancel = heart.leaveRun(1);
        require(cancel != null && heart.phase() == HeartSim.Phase.IDLE && !heart.open(), "leaving the run cancels and hides the panel");
        HeartSim done = running();
        done.accept(json("{\"id\":" + done.activeId() + ",\"status\":\"ready\",\"heart_result\":\"no_win\"}"), 2);
        require(done.leaveRun(3) == null && done.phase() == HeartSim.Phase.IDLE, "leaving the run clears a finished panel");

        HeartSim closing = running();
        closing.close();
        require(closing.running() && closing.open(), "close does not stop a running simulation (the panel offers cancel then)");
        closing.accept(json("{\"id\":" + closing.activeId() + ",\"status\":\"ready\",\"heart_result\":\"timeout\"}"), 4);
        require(closing.phase() == HeartSim.Phase.DONE && closing.open(), "a finished panel stays until closed");
        closing.close();
        require(closing.phase() == HeartSim.Phase.IDLE && !closing.open() && closing.result() == null, "close hides a finished panel");
        require(HeartSim.canStart(true, true, false, IRONCLAD, closing.running()), "usable again after closing");
    }

    /** A failure in the heart feature itself ends in an error on its own panel, never an exception for the caller. */
    static void faults() {
        HeartSim running = running();
        long id = running.activeId();
        running.fault("update", new IllegalStateException("boom"));
        require(running.phase() == HeartSim.Phase.DONE && running.result().outcome == HeartSim.Outcome.ERROR, "a fault while running ends in an error");
        require(running.result().message.equals("update: boom") && !running.result().local, "the error names where and why: " + running.result().message);
        require(running.open(), "the error is on a visible panel");
        require(!running.running(), "a faulted simulation no longer runs, so the button is usable again");
        running.accept(json("{\"id\":" + id + ",\"status\":\"ready\",\"heart_result\":\"win\"}"), 9);
        require(running.result().outcome == HeartSim.Outcome.ERROR, "a late reply does not undo the error");
        running.fault("update", new IllegalStateException("boom"));
        require(running.result().message.equals("update: boom"), "the same fault again changes nothing");

        // An exception without a message, and a null message anywhere, never turn into an exception while showing.
        HeartSim silent = fresh();
        silent.fault("render", new NullPointerException());
        require(silent.phase() == HeartSim.Phase.DONE && silent.open() && silent.result().message.equals("render: NullPointerException"),
            "no message: the exception's name is shown: " + silent.result().message);
        require(HeartSim.reason(new java.io.IOException()).equals("IOException"), "an IOException without a message is named");
        require(HeartSim.reason(new java.io.IOException("pipe closed")).equals("pipe closed"), "a message is kept");
        require(new HeartSim.Result(HeartSim.Outcome.ERROR, null, false, Double.NaN, -1).message.equals(""), "a null message becomes empty");
        require(!HeartSim.headline(new HeartSim.Result(HeartSim.Outcome.ERROR, null, false, Double.NaN, -1)).isEmpty(), "an error without a message still has a headline");
        silent.close();
        require(silent.phase() == HeartSim.Phase.IDLE, "a faulted panel closes like any finished one");
    }

    static void rows() {
        String basic = HeartSim.rowText(json("{\"phase\":\"mcts\",\"budget\":8000,\"simulations\":5000,\"name\":\"不用药\"}"));
        require(basic.equals("基础搜索 · 5000次 · 不用药"), basic);
        String deep = HeartSim.rowText(json("{\"phase\":\"mcts\",\"budget\":128000,\"simulations\":7}"));
        require(deep.equals("追加深搜 · 7次"), deep);
        String beam = HeartSim.rowText(json("{\"phase\":\"beam\",\"simulations\":1,\"transitions\":900,\"name\":\"x\"}"));
        require(beam.equals("循环补充搜索 · 900步 · x"), beam);
        String bare = HeartSim.rowText(json("{}"));
        require(bare.equals("基础搜索 · 0次"), "a bare row still reads: " + bare);
        JsonArray list = new JsonArray();
        list.add(json("{\"phase\":\"ready\",\"simulations\":3}"));
        require(HeartSim.rowText(list.get(0).getAsJsonObject()).startsWith(SearchStatus.phase(list.get(0).getAsJsonObject())), "a row starts with the solver's stage name");
    }

    static void englishText() {
        I18n.setLanguage("en");
        try {
            String[] keys = {"心脏模拟", "只能在局内使用", "战斗中不可用", "暂只支持战士", "正在模拟中", "模拟中", "已等待 ", "用时 ", "胜", "未找到胜线",
                "超时未算完", "已取消", "已取消：进入战斗", "已取消：离开本局", "后台未响应", "模拟失败", "后台回复缺少心脏模拟结果",
                "后台回复了无法识别的心脏模拟结果", "后台报告了错误，没有说明原因", "后台回复了无法识别的状态", "另有 ", " 条分支", "取消", "关闭",
                "用当前卡组、遗物、药水和生命值模拟一场心脏战，只报胜负",
                "心脏模拟：顶栏出现心脏按钮，战斗外按一下，用当前卡组模拟打一场心脏（只报胜负）"};
            for (String key : keys) require(!I18n.t(key).equals(key), "English text for: " + key);
            require(HeartSim.headline(HeartSim.Result.local(HeartSim.Outcome.NO_WIN, "")).equals("No winning line found"), "English no_win");
            // The switch's label must not be longer than the longest one already in the settings panel (the test mode's).
            String longest = I18n.t("测试模式：把本局每张出牌写到桌面 sts1-test-种子.jsonl（只保留最近一局）");
            String heart = I18n.t("心脏模拟：顶栏出现心脏按钮，战斗外按一下，用当前卡组模拟打一场心脏（只报胜负）");
            require(heart.length() <= longest.length(), "English switch label (" + heart.length() + ") is within the longest existing one (" + longest.length() + ")");
            require(!heart.equals(longest) && !heart.isEmpty(), "the two labels are different texts");
            // The backend's own wording for this feature is translated too.
            require(I18n.backend("无法模拟：暂只支持战士").equals("Cannot simulate: Ironclad only for now"), "refusal text: " + I18n.backend("无法模拟：暂只支持战士"));
            require(I18n.backend("无法模拟：Tingsha").equals("Cannot simulate: Tingsha"), "refusal prefix: " + I18n.backend("无法模拟：Tingsha"));
            require(I18n.backend("拼出心脏战局面").equals("Building the Heart fight") && I18n.backend("搜索心脏战").equals("Searching the Heart fight"), "stage names");
        } finally { I18n.setLanguage("zh"); }
    }
}
