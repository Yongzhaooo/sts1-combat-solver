package sts1solver;

import com.google.gson.*;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import communicationmod.CommandExecutor;
import communicationmod.GameStateConverter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * What the player sends back to the authors: the run export (F6), the map picture (F7), crash reports and,
 * in research builds, decision notes and voice notes. {@link #message} is the status line these leave on the settings page.
 */
final class RunExports {
    final boolean research;  // research builds record decision and voice notes; F6 starts a voice note there
    String message = "";
    boolean messageError;
    private final Gson gson = new Gson();
    private final VoiceNotes voiceNotes = new VoiceNotes();
    private final DecisionNotes decisionNotes = new DecisionNotes();
    private String inventorySignature = "";
    private long noteRetryAfter;
    private AbstractPlayer notePlayer;
    private long lastGuardAt;

    RunExports() {
        boolean recording = false;
        defectTestBuild = false;
        try (InputStream stream = getClass().getResourceAsStream("/solver.properties")) {
            Properties build = new Properties();
            if (stream != null) build.load(stream);
            recording = Boolean.parseBoolean(build.getProperty("research_recording", "false"));
            defectTestBuild = Boolean.parseBoolean(build.getProperty("defect_test", "false"));
        } catch (IOException failure) { System.err.println("[STS1Solver] " + failure); }
        research = recording;
    }

    /**
     * True only in builds produced by {@code overlay/build.py --defect-test}, which also point
     * {@code backend=} at {@code overlay/backend_defect_test.py}. The Defect is still work in
     * progress, so it stays out of ordinary releases; {@code solver.properties} is a build
     * constant, so whichever instance reads it last still leaves the right answer here.
     */
    static boolean defectTestBuild;

    static Path dataDirectory(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = RunExports.class.getResourceAsStream("/solver.properties")) {
            if (stream == null) throw new IOException("solver.properties missing");
            properties.load(stream);
        }
        return BackendRuntime.dataDirectory(properties).resolve(name);
    }

    static Path desktopDirectory() {
        try {
            java.io.File home = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
            if (home != null && home.isDirectory()) return home.toPath();
        } catch (Throwable ignored) { }
        return Paths.get(System.getProperty("user.home"), "Desktop");
    }

    String note() { return research ? voiceNotes.status() : message; }

    void shutdown() { voiceNotes.shutdown(); }

    /** Records each new outside decision state; returns an error for the panel, or null. */
    String observeInventory() {
        if (!CommandExecutor.isInDungeon() || AbstractDungeon.player == null) {
            decisionNotes.reset(); inventorySignature = ""; notePlayer = null; return null;
        }
        if (Foresight.capturing) return null;
        if (notePlayer != AbstractDungeon.player) {
            decisionNotes.reset(); inventorySignature = ""; notePlayer = AbstractDungeon.player;
        }
        // Combat execution already has its own trace. Record outside decisions and selection screens.
        if (AbstractDungeon.getCurrRoom() == null ||
                (AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT
                && !AbstractDungeon.isScreenUp)) return null;
        if (System.currentTimeMillis() < noteRetryAfter) return null;
        try {
            JsonObject inventory = new JsonObject();
            inventory.addProperty("seed", Long.toString(Settings.seed));
            inventory.addProperty("floor", AbstractDungeon.floorNum);
            inventory.addProperty("screen", String.valueOf(AbstractDungeon.screen));
            inventory.addProperty("gold", AbstractDungeon.player.gold);
            JsonArray deck = new JsonArray();
            for (AbstractCard card : AbstractDungeon.player.masterDeck.group) {
                JsonObject item = new JsonObject();
                item.addProperty("uuid", card.uuid.toString());
                item.addProperty("id", card.cardID);
                item.addProperty("name", card.name);
                item.addProperty("upgrades", card.timesUpgraded);
                DecisionContext.cardDetails(item, card);
                deck.add(item);
            }
            inventory.add("deck", deck);
            JsonArray potions = new JsonArray();
            for (com.megacrit.cardcrawl.potions.AbstractPotion potion : AbstractDungeon.player.potions)
                if (!(potion instanceof com.megacrit.cardcrawl.potions.PotionSlot)) potions.add(new JsonPrimitive(potion.ID));
            inventory.add("potions", potions);
            inventory.add("public_context", DecisionContext.visible());
            inventory.add("metadata", DecisionContext.metadata());
            String signature = inventory.toString();
            if (signature.equals(inventorySignature)) return null;
            JsonElement state = new JsonParser().parse(GameStateConverter.getCommunicationState());
            decisionNotes.append(dataDirectory("decision-notes"), inventory, state, DecisionContext.recovery());
            inventorySignature = signature;
            return null;
        } catch (Exception failure) {
            noteRetryAfter = System.currentTimeMillis() + 10000;
            System.err.println("[STS1DecisionNotes] " + failure);
            return I18n.t("决策记录失败：") + failure.getMessage();
        }
    }

    /** Starts or stops a voice note; returns an error for the panel, or null. */
    String toggleVoiceNote() {
        if (voiceNotes.active()) { voiceNotes.stop(); return null; }
        try {
            JsonObject context = new JsonObject();
            context.addProperty("schema_version", 1);
            context.addProperty("started_at", java.time.Instant.now().toString());
            context.addProperty("context_scope", "recording_start_only");
            context.addProperty("decision_session", decisionNotes.session());
            context.addProperty("decision_sequence", decisionNotes.sequence());
            context.addProperty("bright_eye_mode", Foresight.brightEyeMode);
            context.addProperty("seed", Long.toString(Settings.seed));
            context.addProperty("floor", AbstractDungeon.floorNum);
            context.addProperty("screen", String.valueOf(AbstractDungeon.screen));
            try {
                context.add("state", new JsonParser().parse(GameStateConverter.getCommunicationState()));
            } catch (RuntimeException failure) {
                context.addProperty("state_error", failure.toString());
            }
            voiceNotes.start(dataDirectory("voice-notes"), gson.toJson(context));
            return null;
        } catch (Exception failure) {
            System.err.println("[STS1VoiceNotes] " + failure);
            return I18n.t("录音启动失败：") + failure.getMessage();
        }
    }

    /** One json for the current seed, written to the desktop so the user can send it as is. */
    void exportRun(BackendClient backend) {
        try {
            backend.start();
            JsonObject request = new JsonObject();
            request.addProperty("op", "export_run");
            request.addProperty("directory", desktopDirectory().toString());
            if (AbstractDungeon.player != null) request.addProperty("seed", Long.toString(Settings.seed));
            request.add("metadata", DecisionContext.metadata());
            request.add("recent_battles", TestRecorder.recentBattles());
            request.add("shop_history", ShopRecorder.snapshot());
            backend.write(request);
            messageError = false;
            message = I18n.t("正在导出记录…");
        } catch (Exception failure) {
            messageError = true;
            message = I18n.t("导出失败：") + failure.getMessage();
        }
    }

    /** The backend's answers to exportRun. */
    void exportReplies(BackendClient backend) {
        JsonObject reply;
        while ((reply = backend.debugReply()) != null) {
            if (reply.get("status").getAsString().equals("debug_run_export")) {
                messageError = false;
                message = I18n.t("已导出到桌面：") + reply.get("file").getAsString();
                showDirectory(Paths.get(reply.get("directory").getAsString()));
            } else {
                messageError = true;
                message = I18n.t("导出失败：") + reply.get("message").getAsString();
            }
        }
    }

    /** F7: this act's map with the planned route, as a PNG on the desktop. */
    void exportMap(List<OutsidePacket.Step> routePlan) {
        try {
            if (!CommandExecutor.isInDungeon() || AbstractDungeon.player == null) {
                messageError = false;
                message = I18n.t("当前没有地图");
                return;
            }
            Path file = MapExport.write(desktopDirectory(), routePlan);
            messageError = false;
            message = I18n.t("地图已导出到桌面：") + file.getFileName();
            showDirectory(file.getParent());
        } catch (Exception failure) {
            messageError = true;
            message = I18n.t("导出失败：") + failure.getMessage();
        }
    }

    private static void showDirectory(Path directory) {
        try { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(directory.toFile()); }
        catch (Exception failure) { System.err.println("[STS1Export] " + failure); }
    }

    /**
     * A bug in the solver must never close the game: record the stack and keep the panel alive.
     * Returns the error for the panel, or null when a report was written in the last five seconds.
     */
    String crash(String where, Throwable failure) {
        long now = System.currentTimeMillis();
        if (now - lastGuardAt < 5000) return null;
        lastGuardAt = now;
        System.err.println("[STS1Solver] " + where + " failed: " + failure);
        failure.printStackTrace();
        try {
            Path directory = dataDirectory("crash-reports");
            Files.createDirectories(directory);
            java.io.StringWriter text = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(text));
            Files.write(directory.resolve("mod-error-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt"),
                (where + "\n" + text).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
        return I18n.t("求解器内部错误，已记录：") + failure;
    }
}
