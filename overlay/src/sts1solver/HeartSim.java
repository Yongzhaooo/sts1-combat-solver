package sts1solver;

import static sts1solver.PanelCanvas.*;

import basemod.BaseMod;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.TipHelper;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.relics.BottledFlame;
import com.megacrit.cardcrawl.relics.BottledLightning;
import com.megacrit.cardcrawl.relics.BottledTornado;
import communicationmod.CommandExecutor;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Heart simulation (opt-in, off by default): a heart button in the top bar asks the backend to play the current deck against
 * the Heart and reports only win / no win line found / ran out of time. This class owns the setting's effect, the button, the
 * request and its replies, and the small panel under the button. The decisions are static functions ({@link #block},
 * {@link #parse}, {@link BackendClient#isHeartReply}) and the request life cycle ({@link #begin}, {@link #accept}, {@link #cancel},
 * {@link #tick}) uses no game classes, so HeartSimCheck can run them without a game.
 *
 * Request ids are at or below {@link #FIRST_ID} and count down, apart from the solver's positive ids and the -1 of the
 * backend client's synthesized transport_error. BackendClient routes such replies to its own queue.
 */
final class HeartSim {
    static final long FIRST_ID = BackendClient.HEART_FIRST_ID;
    private static final String ITEM_ID = "sts1solver:heart_sim";
    private static final String HEART_IMAGE = "images/ui/map/boss/heart.png";
    private static final float WIDTH = 380, PAD = 14, TOP_GAP = 70;
    private static final int MAX_ROWS = 12;

    /** Why the button cannot be pressed; NONE when it can. */
    enum Block { NONE, DISABLED, NOT_IN_DUNGEON, IN_COMBAT, UNSUPPORTED_CLASS, RUNNING }
    enum Phase { IDLE, RUNNING, DONE }
    enum Outcome { WIN, NO_WIN, TIMEOUT, ERROR, CANCELLED, NO_RESPONSE }

    /** A finished simulation as the panel shows it. {@code message} is the backend's text, or a Chinese UI string when {@code local}. */
    static final class Result {
        final Outcome outcome;
        final String message;
        final boolean local;
        final double seconds;      // NaN when the reply did not say
        final long simulations;    // -1 when the reply did not say
        Result(Outcome outcome, String message, boolean local, double seconds, long simulations) {
            this.outcome = outcome; this.message = message == null ? "" : message; this.local = local;
            this.seconds = seconds; this.simulations = simulations;
        }
        static Result local(Outcome outcome, String message) { return new Result(outcome, message, true, Double.NaN, -1); }
    }

    // ---- Pure decisions ----

    /** The first reason the button is not usable, in the order the player would fix them. */
    static Block block(boolean enabled, boolean inDungeon, boolean inCombat, AbstractPlayer.PlayerClass playerClass, boolean running) {
        if (!enabled) return Block.DISABLED;
        if (!inDungeon) return Block.NOT_IN_DUNGEON;
        if (inCombat) return Block.IN_COMBAT;
        if (playerClass != AbstractPlayer.PlayerClass.IRONCLAD) return Block.UNSUPPORTED_CLASS;
        if (running) return Block.RUNNING;
        return Block.NONE;
    }

    static boolean canStart(boolean enabled, boolean inDungeon, boolean inCombat, AbstractPlayer.PlayerClass playerClass, boolean running) {
        return block(enabled, inDungeon, inCombat, playerClass, running) == Block.NONE;
    }

    /**
     * A terminal reply as a Result, or null for a progress reply. "ready" without a known heart_result is an error: the
     * panel must never report a win it was not told about.
     */
    static Result parse(JsonObject reply) {
        String status = text(reply, "status");
        if (status.equals("progress")) return null;
        double seconds = reply.has("seconds") && reply.get("seconds").isJsonPrimitive()
            && reply.get("seconds").getAsJsonPrimitive().isNumber() ? reply.get("seconds").getAsDouble() : Double.NaN;
        long simulations = reply.has("simulations") && reply.get("simulations").isJsonPrimitive()
            && reply.get("simulations").getAsJsonPrimitive().isNumber() ? reply.get("simulations").getAsLong() : -1;
        switch (status) {
            case "ready": {
                String verdict = text(reply, "heart_result");
                Outcome outcome = verdict.equals("win") ? Outcome.WIN : verdict.equals("no_win") ? Outcome.NO_WIN
                    : verdict.equals("timeout") ? Outcome.TIMEOUT : null;
                if (outcome == null)
                    return Result.local(Outcome.ERROR, verdict.isEmpty() ? "后台回复缺少心脏模拟结果" : "后台回复了无法识别的心脏模拟结果");
                return new Result(outcome, "", true, seconds, simulations);
            }
            case "error": {
                String message = text(reply, "message");
                return message.isEmpty() ? Result.local(Outcome.ERROR, "后台报告了错误，没有说明原因")
                    : new Result(Outcome.ERROR, message, false, seconds, simulations);
            }
            case "cancelled":
                return Result.local(Outcome.CANCELLED, "已取消");
            default:
                return Result.local(Outcome.ERROR, "后台回复了无法识别的状态");
        }
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    /** The one-line headline for a finished simulation. */
    static String headline(Result result) {
        switch (result.outcome) {
            case WIN: return I18n.t("胜");
            case NO_WIN: return I18n.t("未找到胜线");
            case TIMEOUT: return I18n.t("超时未算完");
            case CANCELLED: return I18n.t(result.message);
            case NO_RESPONSE: return I18n.t("后台未响应");
            default: return I18n.t("模拟失败");
        }
    }

    /** One progress row: the branch's stage and simulation count (transitions for the beam and cycle stages), then its name. */
    static String rowText(JsonObject row) {
        String stage = text(row, "phase");
        boolean steps = (stage.equals("beam") || stage.equals("cycle")) && row.has("transitions");
        long amount = steps ? row.get("transitions").getAsLong() : row.has("simulations") ? row.get("simulations").getAsLong() : 0;
        String name = text(row, "name");
        return SearchStatus.phase(row) + " · " + SolverMod.count(amount) + I18n.t(steps ? "步" : "次")
            + (name.isEmpty() ? "" : " · " + I18n.backend(name));
    }

    static String seedText(long bits) { return Long.toUnsignedString(bits); }

    // ---- Request life cycle (no game classes) ----

    private final BackendClient backend;
    private final Supplier<JsonObject> capture;   // the solver's own state capture (with solver_auto_context); null in checks
    private final IntSupplier workers;            // the solver's search_workers setting
    private final LongSupplier seeds;
    private long nextId = FIRST_ID, activeId;
    private Phase phase = Phase.IDLE;
    private Result result;
    private long startedAt, finishedAt, phaseAt;
    private long timeoutMillis = SearchStatus.timeoutMillis(128000);
    private String stage = "";
    private JsonArray rows;
    private String seed = "";
    private boolean enabled, open;

    HeartSim(BackendClient backend, Supplier<JsonObject> capture, IntSupplier workers) {
        this(backend, capture, workers, new SecureRandom()::nextLong);
    }

    HeartSim(BackendClient backend, Supplier<JsonObject> capture, IntSupplier workers, LongSupplier seeds) {
        this.backend = backend;
        this.capture = capture;
        this.workers = workers;
        this.seeds = seeds;
    }

    HeartSim(BackendClient backend) {
        this(backend, null, () -> 0);
    }

    HeartSim(BackendClient backend, LongSupplier seeds) {
        this(backend, null, () -> 0, seeds);
    }

    boolean running() { return phase == Phase.RUNNING; }
    Phase phase() { return phase; }
    Result result() { return result; }
    long activeId() { return activeId; }
    String seed() { return seed; }
    String stage() { return stage; }
    long timeoutMillis() { return timeoutMillis; }
    boolean enabled() { return enabled; }
    boolean open() { return open; }

    /** Starts a simulation and returns the request to send; every call draws a fresh seed and the next lower id. */
    JsonObject begin(JsonObject state, long now) {
        activeId = nextId--;
        seed = seedText(seeds.getAsLong());
        phase = Phase.RUNNING;
        result = null;
        rows = null;
        stage = "";
        startedAt = phaseAt = now;
        timeoutMillis = SearchStatus.timeoutMillis(128000);
        open = true;
        JsonObject request = new JsonObject();
        request.addProperty("id", activeId);
        request.addProperty("op", "heart_sim");
        request.addProperty("seed", seed);
        request.addProperty("progress", true);
        request.addProperty("search_workers", workers.getAsInt());
        request.add("state", state);
        return request;
    }

    /** Takes one reply. Only the running request's replies count: after it ended (any way) every later reply is ignored. */
    void accept(JsonObject reply, long now) {
        if (phase != Phase.RUNNING || !BackendClient.isHeartReply(reply) || reply.get("id").getAsLong() != activeId) return;
        Result parsed = parse(reply);
        if (parsed == null) {
            if (reply.has("timeout_seconds") && reply.get("timeout_seconds").isJsonPrimitive()) {
                try { timeoutMillis = SearchStatus.acknowledgedTimeoutMillis(reply.get("timeout_seconds").getAsLong()); }
                catch (RuntimeException invalid) { /* keep the deep default */ }
            }
            if (reply.has("phase") && reply.get("phase").isJsonPrimitive()) {
                stage = reply.get("phase").getAsString();
                phaseAt = now;
            }
            if (reply.has("branches") && reply.get("branches").isJsonArray()) rows = reply.getAsJsonArray("branches");
            return;
        }
        finish(parsed, now);
    }

    /** Ends a running simulation by the player's or the game's decision; returns the cancel request to send, or null if none runs. */
    JsonObject cancel(String message, long now) {
        if (phase != Phase.RUNNING) return null;
        finish(Result.local(Outcome.CANCELLED, message), now);
        return cancelRequest();
    }

    /** Entering a fight: the solver is about to search it, so the simulation stops first. */
    JsonObject enterCombat(long now) { return cancel("已取消：进入战斗", now); }

    /** Leaving the run (to the main menu) ends the simulation and clears a finished panel. */
    JsonObject leaveRun(long now) {
        JsonObject request = cancel("已取消：离开本局", now);
        if (phase == Phase.DONE) close();
        return request;
    }

    /** No final reply before the deadline, or the process died: "backend not responding". Returns a best-effort cancel, or null. */
    JsonObject tick(long now, boolean backendAlive) {
        if (phase != Phase.RUNNING) return null;
        if (!backendAlive) { finish(Result.local(Outcome.NO_RESPONSE, "后台未响应"), now); return null; }
        if (SearchStatus.expired(now, startedAt, timeoutMillis)) {
            finish(Result.local(Outcome.NO_RESPONSE, "后台未响应"), now);
            return cancelRequest();
        }
        return null;
    }

    /** Hides a finished panel. A running simulation is cancelled instead of closed. */
    void close() {
        if (phase == Phase.DONE) { phase = Phase.IDLE; result = null; rows = null; open = false; }
    }

    private void finish(Result outcome, long now) {
        phase = Phase.DONE;
        result = outcome;
        finishedAt = now;
    }

    private JsonObject cancelRequest() {
        JsonObject request = new JsonObject();
        request.addProperty("id", nextId--);
        request.addProperty("op", "cancel");
        return request;
    }

    // ---- The game side: setting, button, requests on the wire, panel ----

    private static final Color READY = new Color(.85f, .85f, .85f, 1), BLOCKED = new Color(.35f, .35f, .35f, .75f);
    private Button button;
    private boolean added;
    private float anchorRight = -1; // right edge of the button as last drawn
    private PanelCanvas panel;
    private float panelHeight = 120;

    /** Applies the ModConfig switch: the button exists in the top bar only while it is on. */
    void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) {
            if (button == null) button = new Button(this, loadHeart());
            if (!added) { BaseMod.addTopPanelItem(button); added = true; }
        } else {
            if (added) { BaseMod.removeTopPanelItem(button); added = false; }
            // Turning the switch off ends a running simulation and hides the panel at once, so no reason is shown.
            send(cancel("已取消", System.currentTimeMillis()));
            close();
        }
    }

    private static Texture loadHeart() {
        try { return ImageMaster.loadImage(HEART_IMAGE); }
        catch (RuntimeException missing) { System.err.println("[STS1Solver] " + missing); return null; }
    }

    Block liveBlock() {
        return block(enabled, CommandExecutor.isInDungeon(), CombatReadiness.combat(),
            AbstractDungeon.player == null ? null : AbstractDungeon.player.chosenClass, running());
    }

    /** The button was pressed: one fresh request with the whole current game state. */
    void press() {
        if (liveBlock() != Block.NONE) return;
        long now = System.currentTimeMillis();
        try {
            JsonObject state = capture.get();
            if (state.has("game_state")) state.getAsJsonObject("game_state").add("solver_heart_bottles", bottles(AbstractDungeon.player));
            backend.start();
            JsonObject request = begin(state, now);
            try { backend.write(request); }
            catch (IOException failure) { finish(new Result(Outcome.ERROR, reason(failure), false, Double.NaN, -1), now); }
        } catch (Exception failure) {
            open = true;
            finish(new Result(Outcome.ERROR, reason(failure), false, Double.NaN, -1), now);
            System.err.println("[STS1Solver] " + failure);
        }
    }

    /** What went wrong, in words: the message, or the exception's name when it has none. */
    static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * Which deck card each Bottled Flame / Lightning / Tornado holds, as [{relic, uuid}]: CommunicationMod does not say, and
     * the backend must not guess. A bottle relic with no card has no entry, which the backend refuses by name.
     */
    static JsonArray bottles(AbstractPlayer player) {
        JsonArray out = new JsonArray();
        if (player == null) return out;
        for (AbstractRelic relic : player.relics) {
            AbstractCard card = relic instanceof BottledFlame ? ((BottledFlame) relic).card
                : relic instanceof BottledLightning ? ((BottledLightning) relic).card
                : relic instanceof BottledTornado ? ((BottledTornado) relic).card : null;
            if (card == null || card.uuid == null) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("relic", relic.relicId);
            entry.addProperty("uuid", card.uuid.toString());
            out.add(entry);
        }
        return out;
    }

    /**
     * A failure inside this feature's own update, input or drawing: shown on the heart panel and kept away from the solver.
     * A running simulation is cancelled, since nothing will watch its reply any more.
     */
    void fault(String where, Throwable failure) {
        try {
            long now = System.currentTimeMillis();
            String text = where + ": " + reason(failure);
            boolean repeat = phase == Phase.DONE && result != null && result.outcome == Outcome.ERROR && result.message.equals(text);
            if (phase == Phase.RUNNING) send(cancel(text, now));
            if (!repeat) {
                open = true;
                finish(new Result(Outcome.ERROR, text, false, Double.NaN, -1), now);
                System.err.println("[STS1Solver] heart simulation " + where + " failed: " + failure);
            }
        } catch (Throwable ignored) { /* the panel must never take the game down either */ }
    }

    /** The panel's cancel button. */
    private void cancelByPlayer() { send(cancel("已取消", System.currentTimeMillis())); }

    /** Every frame, before SolverMod's fight handling: drains this feature's replies and watches its deadline. */
    void update() {
        long now = System.currentTimeMillis();
        JsonObject reply;
        while ((reply = backend.heartReply()) != null) accept(reply, now);
        if (!CommandExecutor.isInDungeon()) send(leaveRun(now));
        send(tick(now, backend.alive()));
        if (button != null) button.setClickable(liveBlock() == Block.NONE);
    }

    /** The first moment of a fight, before the solver sends its first request. */
    void onCombatStart() { send(enterCombat(System.currentTimeMillis())); }

    private void send(JsonObject request) {
        if (request == null || backend == null || !backend.started()) return;
        try { backend.write(request); }
        catch (IOException failure) { System.err.println("[STS1Solver] " + failure); }
    }

    /** The heart in the top bar, drawn at the game's top-bar size from the game's own 512-pixel image. */
    static final class Button extends basemod.TopPanelItem {
        private final HeartSim owner;

        Button(HeartSim owner, Texture image) {
            super(image, ITEM_ID);
            this.owner = owner;
        }

        @Override protected void onClick() { owner.press(); }

        @Override public void render(SpriteBatch sb, Color color) {
            owner.anchorRight = hitbox.x + hitbox.width;
            Block block = owner.liveBlock();
            float size = 64f * Settings.scale;
            Color tint = block == Block.NONE ? (hitbox.hovered ? Color.WHITE : READY)
                : block == Block.RUNNING ? ACCENT : BLOCKED;
            sb.setColor(tint);
            if (image != null) sb.draw(image, x, y, size, size);
            else sb.draw(ImageMaster.WHITE_SQUARE_IMG, x + size * .2f, y + size * .2f, size * .6f, size * .6f);
            sb.setColor(Color.WHITE);
            if (hitbox.hovered)
                TipHelper.renderGenericTip(Math.min(hitbox.x, Settings.WIDTH - 340f * Settings.scale),
                    Settings.HEIGHT - 80f * Settings.scale, I18n.t("心脏模拟"), tip(block));
        }
    }

    static String tip(Block block) {
        switch (block) {
            case NOT_IN_DUNGEON: return I18n.t("只能在局内使用");
            case IN_COMBAT: return I18n.t("战斗中不可用");
            case UNSUPPORTED_CLASS: return I18n.t("暂只支持战士");
            case RUNNING: return I18n.t("正在模拟中");
            default: return I18n.t("用当前卡组、遗物、药水和生命值模拟一场心脏战，只报胜负");
        }
    }

    // ---- Panel ----

    /** Draws the panel under the button, in the solver panel's style. {@code main} lends its font and scale. */
    void render(SpriteBatch sb, PanelCanvas main) {
        if (!panelVisible()) return;
        PanelCanvas c = place(main);
        c.clearHits();
        c.dry = true;
        try { panelHeight = body(sb, c); } finally { c.dry = false; }
        c.box(sb, 0, 0, WIDTH, panelHeight, BORDER);
        c.box(sb, 1, 1, WIDTH - 2, panelHeight - 2, BACKGROUND);
        body(sb, c);
        sb.setColor(Color.WHITE);
        c.cursorOnTop(sb);
    }

    /** Takes clicks on the panel and keeps them from reaching the game under it. */
    void input(PanelCanvas main) {
        if (!panelVisible()) { if (panel != null) panel.hovered = false; return; }
        PanelCanvas c = place(main);
        float s = c.scale(), mx = InputHelper.mX, my = InputHelper.mY;
        boolean over = PanelCanvas.inside(mx, my, c.x, c.y - panelHeight * s, WIDTH * s, panelHeight * s);
        if (over && InputHelper.justClickedLeft) c.click((mx - c.x) / s, (c.y - my) / s);
        c.hovered = over;
        if (over) {
            InputHelper.justClickedLeft = false; InputHelper.justReleasedClickLeft = false;
            InputHelper.justClickedRight = false; InputHelper.justReleasedClickRight = false;
            InputHelper.scrolledDown = InputHelper.scrolledUp = false;
        }
    }

    private boolean panelVisible() { return enabled && open && phase != Phase.IDLE && CommandExecutor.isInDungeon(); }

    private PanelCanvas place(PanelCanvas main) {
        if (panel == null) { panel = new PanelCanvas(); panel.shareFont(main); }
        panel.uiScale = main.uiScale;
        float right = anchorRight > 0 ? anchorRight : Settings.WIDTH - 8f * Settings.scale;
        panel.x = right - WIDTH * panel.scale();
        panel.y = Settings.HEIGHT - TOP_GAP * Settings.scale;
        panel.layout(WIDTH, panelHeight);
        return panel;
    }

    private float body(SpriteBatch sb, PanelCanvas c) {
        long now = System.currentTimeMillis();
        c.label(sb, I18n.t("心脏模拟"), PAD, 11, 200, .95f, Color.WHITE);
        float top = 44;
        if (phase == Phase.RUNNING) {
            top = headline(sb, c, I18n.t("模拟中") + " …", top, Color.WHITE);
            c.bar(sb, PAD, top - 2, WIDTH - 2 * PAD, 6, SECONDARY);
            c.bar(sb, PAD, top - 2, (WIDTH - 2 * PAD) * SearchStatus.waitingFill(now - startedAt), 6, ACCENT);
            top += 12;
            top = lines(sb, c, I18n.backend(stage.isEmpty() ? I18n.t("等待后台接收") : stage), top, .8f, MUTED);
            top = lines(sb, c, I18n.t("已等待 ") + (now - startedAt) / 1000 + I18n.t(" 秒"), top - 4, .76f, MUTED);
        } else if (result != null) {
            boolean bad = result.outcome == Outcome.ERROR || result.outcome == Outcome.NO_RESPONSE;
            Color tone = result.outcome == Outcome.WIN ? ACCENT : bad ? DANGER : Color.WHITE;
            top = headline(sb, c, headline(result), top, tone);
            if (result.outcome == Outcome.ERROR)
                top = lines(sb, c, result.local ? I18n.t(result.message) : I18n.backend(result.message), top - 4, .78f, Color.WHITE);
            if (result.outcome == Outcome.WIN || result.outcome == Outcome.NO_WIN || result.outcome == Outcome.TIMEOUT) {
                double seconds = Double.isNaN(result.seconds) ? (finishedAt - startedAt) / 1000.0 : result.seconds;
                String used = I18n.t("用时 ") + String.format(Locale.ROOT, "%.1f", seconds) + I18n.t(" 秒")
                    + (result.simulations >= 0 ? " · " + SolverMod.count(result.simulations) + I18n.t("次") : "");
                top = lines(sb, c, used, top - 4, .78f, MUTED);
            }
        }
        if (rows != null && rows.size() > 0) top = branchRows(sb, c, top);
        if (phase == Phase.RUNNING)
            top = action(sb, c, top + 4, I18n.t("取消"), DANGER_SOFT, DANGER, this::cancelByPlayer);
        else top = action(sb, c, top + 4, I18n.t("关闭"), SECONDARY, Color.WHITE, this::close);
        return top + PAD - 6;
    }

    /** One row per branch: stage and simulation count, like the solver's search list. */
    private float branchRows(SpriteBatch sb, PanelCanvas c, float top) {
        int shown = Math.min(rows.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            JsonElement element = rows.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            boolean ready = row.has("ready") && row.get("ready").getAsBoolean();
            c.box(sb, PAD, top, WIDTH - 2 * PAD, 24, SURFACE);
            c.label(sb, rowText(row), PAD + 10, top + 4, WIDTH - 2 * PAD - 20, .76f, ready ? ACCENT : MUTED);
            top += 28;
        }
        if (rows.size() > shown)
            top = lines(sb, c, I18n.t("另有 ") + (rows.size() - shown) + I18n.t(" 条分支"), top, .72f, MUTED);
        return top + 4;
    }

    private float headline(SpriteBatch sb, PanelCanvas c, String text, float top, Color color) {
        c.label(sb, text, PAD, top, WIDTH - 2 * PAD, 1.05f, color);
        return top + 30;
    }

    private float lines(SpriteBatch sb, PanelCanvas c, String text, float top, float size, Color color) {
        for (String line : c.wrap(text, WIDTH - 2 * PAD, size)) { c.label(sb, line, PAD, top, WIDTH - 2 * PAD, size, color); top += size * 24; }
        return top + 6;
    }

    private float action(SpriteBatch sb, PanelCanvas c, float top, String label, Color fill, Color text, Runnable run) {
        c.button(sb, PAD, top, WIDTH - 2 * PAD, 32, fill, label, .8f, text, run);
        return top + 32;
    }
}
