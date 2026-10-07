package sts1solver;

import basemod.BaseMod;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import basemod.interfaces.PostInitializeSubscriber;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.google.gson.*;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.helpers.input.InputAction;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import communicationmod.CommandExecutor;
import communicationmod.GameStateConverter;
import communicationmod.GameStateListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@SpireInitializer
public class SolverMod implements PostUpdateSubscriber, PostRenderSubscriber, PostInitializeSubscriber {
    public static SolverMod instance;
    private final Gson gson = new Gson();
    private final VoiceNotes voiceNotes = new VoiceNotes();
    private final ExperienceNotes experience = new ExperienceNotes();
    private final ConcurrentLinkedQueue<JsonObject> debugReplies = new ConcurrentLinkedQueue<>();
    private boolean researchRecording;
    private String debugMessage = "";
    private long nextExperienceObservation;
    private AbstractPlayer experiencePlayer;
    private int experienceRun;
    private final DecisionNotes decisionNotes = new DecisionNotes();
    private String inventorySignature = "";
    private long noteRetryAfter;
    private AbstractPlayer notePlayer;

    private Path notesDirectory(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/solver.properties")) {
            if (stream == null) throw new IOException("solver.properties missing");
            properties.load(stream);
        }
        return BackendRuntime.dataDirectory(properties).resolve(name);
    }

    private void observeInventory() {
        if (!CommandExecutor.isInDungeon() || AbstractDungeon.player == null) {
            decisionNotes.reset(); inventorySignature = ""; notePlayer = null; return;
        }
        if (Foresight.capturing) return;
        if (notePlayer != AbstractDungeon.player) {
            decisionNotes.reset(); inventorySignature = ""; notePlayer = AbstractDungeon.player;
        }
        // Combat execution already has its own trace. Record outside decisions and selection screens.
        if (AbstractDungeon.getCurrRoom() == null ||
                (AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT
                && !AbstractDungeon.isScreenUp)) return;
        if (System.currentTimeMillis() < noteRetryAfter) return;
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
            if (signature.equals(inventorySignature)) return;
            JsonElement state = new JsonParser().parse(GameStateConverter.getCommunicationState());
            decisionNotes.append(notesDirectory("decision-notes"), inventory, state, DecisionContext.recovery());
            inventorySignature = signature;
        } catch (Exception failure) {
            noteRetryAfter = System.currentTimeMillis() + 10000;
            error = I18n.t("决策记录失败：") + failure.getMessage();
            System.err.println("[STS1DecisionNotes] " + failure);
        }
    }
    private final ConcurrentLinkedQueue<JsonObject> replies = new ConcurrentLinkedQueue<>();
    private Process backend;
    private BufferedWriter writer;
    private long serial, activeId, sentAt;
    private JsonObject rootKey, lastKey, result;
    private boolean decisionDirty=true;
    private boolean busy, awaitingAction, auto, turnOnly, gamblingAuto, paused, collapsed, dragging;
    private boolean runAuto;
    private boolean autoPotionRewards = true;
    private boolean runAutoEnabled = true;
    private AbstractPlayer runAutoPlayer;
    private String autoReason = "";
    private PotionRewards.Advice rewardPotionAdvice;
    private PotionRewards.Pending rewardPotionPending;
    private int budgetIndex = 1, scroll, startingTurn;
    private int executedTurn;
    private String executedCommand = "";
    private final int[] budgets = {2000, 8000, 128000};
    private final String[] budgetNames = {"快速", "标准", "深入"};
    private String status = I18n.t("等待进入战斗"), error = "";
    private static final float PANEL_WIDTH = 880;
    private static final float PANEL_HEIGHT = 386;
    private static final float ROUTE_LEFT = 368;
    private static final float ACTION_SPACING = (PANEL_WIDTH - 24) / 4;
    private float x = -1, y = -1, dx, dy;
    private TransformPreview transformPreview;
    private int previewRngCounter;
    private Object previewSource;
    private AbstractDungeon.CurrentScreen lastFoldScreen;
    private final Color background = new Color(.055f,.070f,.105f,.96f);
    private final Color surface = new Color(.10f,.13f,.19f,1);
    static final Color ACCENT = new Color(.38f,.83f,.72f,1);
    private final Color accent = ACCENT;
    private final Color muted = new Color(.62f,.69f,.78f,1);
    private final Color danger = new Color(1,.48f,.44f,1);
    private boolean wasCombat;
    private boolean hovered;  // pointer over the panel, which is drawn after the game cursor
    private BitmapFont uiFont;
    private FreeTypeFontGenerator fontGenerator;
    private SpireConfig config;
    private int autoKey = Input.Keys.F10;
    private boolean bindingKey;
    private boolean branchPicker, branchConfirmed;
    private boolean captureBranchKeys;
    private boolean nextRest;
    private String branchInput = "";
    private String chosenBranch = "no-potion";
    private JsonArray progressRows;
    private String backendPhase = I18n.t("等待后台接收");
    private long phaseAt, lastProgressAt;
    private String clickedBranch;
    private String thiefPreference;
    // Shops and events fold the panel (results sit beside the options); it reopens on leaving
    // unless the player toggled it meanwhile.
    private boolean autoFolded, inFoldRoom;
    private final GlyphLayout measure = new GlyphLayout();
    // Classes the combat engine imports exactly; keep in sync with backend.SUPPORTED_CLASSES.
    // Out-of-combat foresight works for every class.
    static final EnumSet<AbstractPlayer.PlayerClass> SUPPORTED = EnumSet.of(AbstractPlayer.PlayerClass.IRONCLAD);
    static final String SUPPORTED_NAMES = I18n.t("战士");

    private static boolean supported() {
        return AbstractDungeon.player != null && SUPPORTED.contains(AbstractDungeon.player.chosenClass);
    }

    public static void initialize() {
        communicationmod.CommunicationMod.initializeEmbedded();
        instance = new SolverMod();
    }
    public SolverMod() {
        BaseMod.subscribe(this);
        try (InputStream stream = getClass().getResourceAsStream("/solver.properties")) {
            Properties build = new Properties();
            if (stream != null) build.load(stream);
            researchRecording = Boolean.parseBoolean(build.getProperty("research_recording", "false"));
        } catch (IOException failure) { System.err.println("[STS1Solver] " + failure); }
        try {
            Properties defaults = new Properties();
            defaults.setProperty("language", "zh");
            defaults.setProperty("autoKey", Integer.toString(Input.Keys.F10));
            defaults.setProperty("brightEyeMode", "true");
            defaults.setProperty("autoPotionRewards", "true");
            defaults.setProperty("runAutoEnabled", "true");
            config = new SpireConfig("STS1CombatSolver", "config", defaults);
            I18n.setLanguage(config.getString("language"));
            status = I18n.t("等待进入战斗");
            backendPhase = I18n.t("等待后台接收");
            autoKey = config.getInt("autoKey");
            Foresight.brightEyeMode = config.getBool("brightEyeMode");
            autoPotionRewards = config.getBool("autoPotionRewards");
            runAutoEnabled = config.getBool("runAutoEnabled");
            if (!validAutoKey(autoKey)) autoKey = Input.Keys.F10;
        } catch (IOException failure) { error = I18n.t("快捷键配置读取失败：") + failure.getMessage(); }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            voiceNotes.shutdown();
            try { experience.stop(); } catch (IOException failure) { System.err.println("[STS1Experience] " + failure); }
            try { if (writer != null) writer.close(); } catch (IOException ignored) { }
            // EOF lets the supervisor terminate and reap its native worker.
        }, "sts1-solver-shutdown"));
    }

    @Override public void receivePostInitialize() {
        initFont();
        Thread warmup = new Thread(() -> {
            try { startBackend(); } catch (IOException failure) { System.err.println("[STS1Solver] " + failure); }
        }, "sts1-solver-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    private void autoFold() {
        AbstractRoom room = CommandExecutor.isInDungeon() ? AbstractDungeon.getCurrRoom() : null;
        AbstractDungeon.CurrentScreen screen = room == null ? null : AbstractDungeon.screen;
        boolean event = room != null && room.phase == AbstractRoom.RoomPhase.EVENT && room.event != null;
        boolean fold = !expandOnScreen(screen, room != null && combat(), event);
        if (fold && (!inFoldRoom || screen != lastFoldScreen) && !collapsed) { collapsed = true; autoFolded = true; }
        if (!fold && inFoldRoom && autoFolded) { collapsed = false; autoFolded = false; }
        inFoldRoom = fold;
        lastFoldScreen = screen;
    }

    static boolean expandOnScreen(AbstractDungeon.CurrentScreen screen, boolean combat, boolean event) {
        return screen == AbstractDungeon.CurrentScreen.NONE && (combat || event);
    }

    static String revivalWarning(JsonObject plan) {
        String warning = plan.has("used_tail") && plan.get("used_tail").getAsBoolean() ? I18n.t("消耗尾巴") : "";
        if (plan.has("used_fairy") && plan.get("used_fairy").getAsInt() > 0)
            warning += (warning.isEmpty()?"":" + ") + I18n.t("小精灵×") + plan.get("used_fairy").getAsInt();
        return warning;
    }

    private float panelWidth() { return collapsed ? 300 : PANEL_WIDTH; }

    private boolean validAutoKey(int key) {
        return key >= Input.Keys.F1 && key <= Input.Keys.F12 && key != Input.Keys.F6 && key != Input.Keys.F8 && key != Input.Keys.F9;
    }

    private void toggleAuto() {
        decisionDirty=true;
        if (!combat() || !supported()) return;
        runAuto=false;
        if (auto || turnOnly) { auto = turnOnly = false; status = I18n.t("自动已关闭 · 保留建议"); return; }
        auto = true;
        turnOnly = false;
        paused = false;
        error = "";
        if (!busy && result == null && !awaitingAction) lastKey = null;
        status = I18n.t("自动执行已开启 · 仅本场战斗");
        if (result != null && result.get("needs_confirmation").getAsBoolean() && !branchConfirmed) requestBranches();
    }

    private void requestBranches() {
        if (runAuto && !paused && result != null && !busy) {
            chooseAutomaticBranch();
            return;
        }
        auto=turnOnly=false;
        branchPicker=true;
        collapsed=false;
        branchInput="";
        scroll=0;
        status=result!=null && result.has("manual_choice") && result.get("manual_choice").getAsBoolean()
            ? I18n.t("抢劫怪：先选路线，再决定是否自动执行") : I18n.t("请确认战斗与用药路线 · 等待数字选择");
        if(result!=null && !result.get("branch_current").getAsBoolean() && ready()) {
            try { send("solve",capture()); } catch(IOException failure){fail(failure.toString());}
        }
    }

    private void chooseBranch(String branch) {
        if(result==null || busy || !ready())return;
        try {
            JsonObject state=capture();
            if(!key(state).equals(rootKey)){recalculate();return;}
            chosenBranch=branch;
            branchPicker=false;
            branchConfirmed=true;
            boolean manual=result.has("manual_choice") && result.get("manual_choice").getAsBoolean();
            auto=runAuto || autoAfterBranch(manual);
            if(manual)thiefPreference=branch;
            paused=false;
            send("choose",state);
        } catch(Exception failure){fail(failure.toString());}
    }

    private boolean combat() {
        return CommandExecutor.isInDungeon() && AbstractDungeon.player != null
            && AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT
            && !AbstractDungeon.getCurrRoom().isBattleOver && !AbstractDungeon.player.isDead;
    }

    private boolean ready() {
        if (!combat() || !GameStateListener.isWaitingForCommand()) return false;
        // Piles already contain cards while Soul is still moving them. Completion
        // calls clearPowers/resetAttributes, including temporary cost reset.
        if (com.megacrit.cardcrawl.cards.SoulGroup.isActive()) return false;
        // AbstractMonster.updateDeathAnimation clears powers when isDead becomes
        // true. Action queues can already be idle before this final state change.
        for (AbstractMonster monster: AbstractDungeon.getMonsters().monsters)
            if (pendingMonsterDeath(monster.isDying, monster.isDead, monster.halfDead)
                    || (monster.isEscaping && !monster.escaped)) return false;
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.CARD_REWARD
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.HAND_SELECT
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) return false;
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.NONE) {
            for(com.megacrit.cardcrawl.vfx.AbstractGameEffect effect:AbstractDungeon.effectList)
                if(pendingCardEffect(effect))return false;
            for(com.megacrit.cardcrawl.vfx.AbstractGameEffect effect:AbstractDungeon.effectsQueue)
                if(pendingCardEffect(effect))return false;
            for(com.megacrit.cardcrawl.vfx.AbstractGameEffect effect:AbstractDungeon.topLevelEffects)
                if(pendingCardEffect(effect))return false;
            for (AbstractMonster monster: AbstractDungeon.getMonsters().monsters)
                if (!monster.isDeadOrEscaped() && monster.intent == AbstractMonster.Intent.DEBUG) return false;
            return AbstractDungeon.actionManager.actions.isEmpty()
                && AbstractDungeon.actionManager.phase == GameActionManager.Phase.WAITING_ON_USER
                && AbstractDungeon.actionManager.currentAction == null
                && !AbstractDungeon.actionManager.hasControl
                && AbstractDungeon.actionManager.cardQueue.isEmpty()
                && AbstractDungeon.actionManager.preTurnActions.isEmpty()
                && !AbstractDungeon.player.endTurnQueued;
        }
        return true;
    }
    static boolean pendingMonsterDeath(boolean dying, boolean dead, boolean halfDead) {
        return dying && !dead && !halfDead;
    }
    // The startup action identifies Gambling Chip more precisely than the relic alone:
    // other hand selections can occur while the player owns the same relic.
    static boolean gamblingSelection(JsonObject state) {
        if (state == null || !state.has("game_state")) return false;
        JsonObject game = state.getAsJsonObject("game_state");
        if (!game.has("screen_type") || !"HAND_SELECT".equals(game.get("screen_type").getAsString())
                || !game.has("screen_state") || !game.has("solver_selection")) return false;
        JsonObject screen = game.getAsJsonObject("screen_state");
        JsonObject action = game.getAsJsonObject("solver_selection").getAsJsonObject("current_action");
        return screen != null && screen.has("max_cards") && screen.get("max_cards").getAsInt() == 99
            && action != null && action.has("class")
            && "GamblingChipAction".equals(action.get("class").getAsString());
    }
    static boolean routeConfirmed(JsonObject result, boolean branchConfirmed) {
        return result != null && (!result.has("manual_choice")
            || !result.get("manual_choice").getAsBoolean() || branchConfirmed);
    }
    static boolean toolboxSelection(JsonObject state) {
        if (state == null || !state.has("game_state")) return false;
        JsonObject game = state.getAsJsonObject("game_state");
        if (!game.has("screen_type") || !"CARD_REWARD".equals(game.get("screen_type").getAsString())
                || !game.has("solver_selection")) return false;
        JsonObject action = game.getAsJsonObject("solver_selection").getAsJsonObject("current_action");
        return action != null && action.has("class") && "ChooseOneColorless".equals(action.get("class").getAsString());
    }
    static boolean openingSelection(JsonObject state) {
        return gamblingSelection(state) || toolboxSelection(state);
    }
    static boolean autoAfterBranch(boolean manual) {
        return !manual;
    }
    private void finishGamblingSelection(AbstractDungeon.CurrentScreen screen) {
        if(gamblingAuto && screen==AbstractDungeon.CurrentScreen.NONE) {
            gamblingAuto=false;
            // Continue the already confirmed combat route; never overwrite a
            // user's intervening Stop or disable-auto choice.
            decisionDirty=true;
        }
    }
    private boolean pendingCardEffect(com.megacrit.cardcrawl.vfx.AbstractGameEffect effect) {
        String name=effect.getClass().getSimpleName();
        return !effect.isDone && (name.equals("ShowCardAndAddToHandEffect")
            || name.equals("ShowCardAndAddToDiscardEffect") || name.equals("ShowCardAndAddToDrawPileEffect")
            || name.equals("ExhaustCardEffect")); // Completion resets temporary card attributes.
    }
    @SpirePatch(clz=GameStateListener.class, method="checkForDungeonStateChange")
    public static class DecisionEvent {
        @SpirePostfixPatch public static boolean after(boolean __result) {
            if(__result && instance!=null)instance.decisionDirty=true;
            return __result;
        }
    }

    private JsonObject capture() {
        JsonObject state=new JsonParser().parse(GameStateConverter.getCommunicationState()).getAsJsonObject();
        if(state.has("game_state")) {
            JsonObject game=state.getAsJsonObject("game_state");
            game.addProperty("solver_next_rest",nextRest);
            game.add("solver_auto_context",autoContext());
        }
        return state;
    }

    private JsonObject autoContext() {
        JsonObject out=new JsonObject();
        out.addProperty("x",AbstractDungeon.getCurrMapNode().x);
        out.addProperty("y",AbstractDungeon.getCurrMapNode().y);
        out.addProperty("boss",AbstractDungeon.bossKey);
        out.addProperty("room",AbstractDungeon.getCurrRoom().getClass().getSimpleName());
        out.addProperty("boss_candidates_remaining",AbstractDungeon.bossList.size());
        out.addProperty("boss_fast_finish",bossFastFinish(
            AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss,
            AbstractDungeon.actNum,AbstractDungeon.ascensionLevel,AbstractDungeon.bossList.size(),
            AbstractDungeon.player.hasRelic("Mark of the Bloom")));
        float chance=Math.max(0,Math.min(1,(40+AbstractRoom.blizzardPotionMod)/100f));
        if(AbstractDungeon.player.hasRelic("White Beast Statue"))chance=1;
        if(AbstractDungeon.player.hasRelic("Sozu") || AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss)chance=0;
        out.addProperty("potion_drop_probability",chance);
        JsonArray deck=new JsonArray();
        for(AbstractCard card:AbstractDungeon.player.masterDeck.group) {
            JsonObject c=new JsonObject();
            c.addProperty("id",card.cardID);c.addProperty("cost",card.cost);
            c.addProperty("base_damage",card.baseDamage);c.addProperty("base_block",card.baseBlock);
            c.addProperty("upgrades",card.timesUpgraded);deck.add(c);
        }
        out.add("deck",deck);
        return out;
    }

    static boolean bossFastFinish(boolean bossRoom,int act,int ascension,int remainingBosses,boolean noHealing) {
        return bossRoom && act>=1 && act<=3 && !noHealing
            // Beyond starts with three candidates, removing the current one on
            // entry: two remain at boss #1, one unused candidate at boss #2.
            && !(act==3 && ascension>=20 && remainingBosses>1);
    }

    private void toggleRunAuto() {
        if(runAuto){runAutoEnabled=false;saveAutomation();cancel(true);return;}
        if(!CommandExecutor.isInDungeon() || AbstractDungeon.player==null || !supported())return;
        runAutoEnabled=true;saveAutomation();
        runAuto=true;runAutoPlayer=AbstractDungeon.player;
        auto=combat();turnOnly=false;paused=false;branchConfirmed=false;
        branchPicker=false;captureBranchKeys=false;branchInput="";decisionDirty=true;
        error="";autoReason=I18n.t("自动用药＋跨场战斗已开启");
        if(result!=null && !busy && ready())chooseAutomaticBranch();
    }

    private void saveAutomation() {
        try {
            if(config==null)return;
            config.setBool("autoPotionRewards",autoPotionRewards);
            config.setBool("runAutoEnabled",runAutoEnabled);
            config.save();
        } catch(IOException failure){error=I18n.t("自动开关保存失败：")+failure.getMessage();}
    }

    private void togglePotionRewards() {
        autoPotionRewards=!autoPotionRewards;
        saveAutomation();
    }

    private void stopAll() {
        autoPotionRewards=false;
        cancel(true);
    }

    private void chooseAutomaticBranch() {
        if(!ready())return;
        JsonObject advice=result.getAsJsonObject("auto_recommendation");
        if(advice==null || !advice.has("branch") || advice.get("branch").isJsonNull()) {
            fail(I18n.t("自动模式未找到获胜路线，请手动检查"));return;
        }
        autoReason=advice.get("reason").getAsString();
        String branch=advice.get("branch").getAsString();
        if(!result.get("branch_current").getAsBoolean()) {recalculate();return;}
        if(branch.equals(result.get("branch").getAsString())) {
            branchConfirmed=true;branchPicker=false;auto=true;decisionDirty=true;
        } else chooseBranch(branch);
    }

    // Keep gameplay and six combat RNG streams; visual timing and unrelated
    // global animation RNG must not invalidate every frame of an unchanged turn.
    static JsonObject key(JsonObject state) {
        JsonObject game = new JsonParser().parse(state.get("game_state").toString()).getAsJsonObject();
        game.remove("full_rng_state");
        game.remove("map");
        game.remove("current_action");
        game.remove("action_phase");
        JsonObject c = game.getAsJsonObject("combat_state");
        if (c != null) {
            c.remove("frame_delta_seconds");
            // Card hover recalculates displayed damage; retain base rule values,
            // powers, target identities, costs and RNG instead of hover previews.
            for (String pile : new String[]{"hand","draw_pile","discard_pile","exhaust_pile","limbo"})
                if (c.has(pile)) for (JsonElement value : c.getAsJsonArray(pile)) {
                    JsonObject card = value.getAsJsonObject();
                    card.remove("damage"); card.remove("block"); card.remove("magic_number");
                }
        }
        JsonObject key = new JsonObject();
        key.add("game", game);
        key.add("commands", state.get("available_commands"));
        return key;
    }

    private synchronized void startBackend() throws IOException {
        if (backend != null && backend.isAlive()) return;
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/solver.properties")) {
            if (stream == null) throw new IOException(I18n.t("缺少求解器路径配置，请重新安装。"));
            properties.load(stream);
        }
        Path log = BackendRuntime.dataDirectory(properties).resolve("backend.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder pb = BackendRuntime.process(properties);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        backend = pb.start();
        final Process child = backend;
        writer = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    JsonObject reply = new JsonParser().parse(line).getAsJsonObject();
                    if (reply.has("status") && reply.get("status").getAsString().startsWith("debug_")) debugReplies.add(reply);
                    else replies.add(reply);
                }
            } catch (Exception failure) {
                JsonObject reply = new JsonObject();
                reply.addProperty("id", -1);
                reply.addProperty("status", "transport_error");
                reply.addProperty("message", I18n.t("后台连接中断：") + failure.getMessage());
                replies.add(reply);
            }
        }, "sts1-solver-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void send(String op, JsonObject state) throws IOException {
        startBackend();
        JsonObject request = new JsonObject();
        request.addProperty("id", ++serial);
        request.addProperty("op", op);
        request.addProperty("budget", budgets[budgetIndex]);
        if (op.equals("choose")) request.addProperty("branch", chosenBranch);
        if (op.equals("solve")) request.addProperty("progress", true);
        if (!op.equals("cancel")) {
            progressRows = null;
            backendPhase = I18n.t("等待后台接收");
            phaseAt = lastProgressAt = System.currentTimeMillis();
        }
        if (state != null) request.add("state", state);
        writer.write(gson.toJson(request));
        writer.newLine();
        writer.flush();
        activeId = serial;
        if (!op.equals("cancel")) {
            rootKey = key(state);
            lastKey = rootKey;
            sentAt = System.currentTimeMillis();
            busy = true;
            result = null;
            status = op.equals("advance") ? I18n.t("核对执行结果…") : I18n.t("正在搜索整场战斗…");
        }
    }

    private void diagnostic(String event,String message,JsonObject state) {
        if(writer==null)return;
        try {
            if(state==null && combat())state=capture();
            JsonObject record=new JsonObject();
            record.addProperty("op","diagnostic");
            record.addProperty("id",activeId);
            record.addProperty("event",event);
            record.addProperty("message",message);
            record.addProperty("auto",auto);
            record.addProperty("turn_only",turnOnly);
            record.addProperty("awaiting_action",awaitingAction);
            record.addProperty("executed_command",executedCommand);
            if(state!=null)record.add("state",state);
            if(rootKey!=null)record.add("expected_key",rootKey);
            if(result!=null)record.add("previous_result",result);
            writer.write(gson.toJson(record));writer.newLine();writer.flush();
        } catch(Exception failure) {
            System.err.println(I18n.t("[STS1Solver] 诊断写入失败：")+failure);
        }
    }

    private void cancel(boolean pause) {
        if(pause)runAuto=false;
        rewardPotionPending=null;
        boolean pendingSearch = pause && busy && !awaitingAction;
        boolean keepResult = pause && result != null && !awaitingAction;
        try { if (backend != null && backend.isAlive() && (busy || awaitingAction)) send("cancel", null); }
        catch (IOException failure) { error = failure.getMessage(); }
        if (!pendingSearch) { ++serial; activeId = serial; }
        if (!pendingSearch) clickedBranch = null;
        busy = pendingSearch;
        awaitingAction = auto = turnOnly = gamblingAuto = false;
        if (!keepResult) { branchPicker = branchConfirmed = false; branchInput = ""; result = null; }
        decisionDirty = true;
        paused = pause;
        status = pendingSearch ? I18n.t("正在停止搜索 · 保留已找到路线")
            : pause ? (keepResult ? I18n.t("已停止 · 回车继续当前路线") : I18n.t("已停止 · 点击重新计算恢复")) : I18n.t("等待稳定局面");
        if (keepResult && result.has("branches") && result.getAsJsonArray("branches").size()>1) {
            branchPicker=true;
            status=I18n.t("已停止 · 点击路线或输入编号后回车");
        }
    }

    private void fail(String message) {
        runAuto=false;
        autoPotionRewards=false;
        diagnostic("error",message,null);
        cancel(false);
        paused = true;
        error = message;
        status = I18n.t("已停止 · 需要重新计算");
        System.err.println("[STS1Solver] " + message);
    }

    private void recalculate() {
        diagnostic("recalculate",I18n.t("重新计算"),null);
        boolean resumeAuto=auto, resumeTurn=turnOnly;
        cancel(false);
        auto=resumeAuto;
        turnOnly=resumeTurn;
        error = "";
        lastKey = null;
        scroll = 0;
        decisionDirty = true;
    }

    private void stateChanged(JsonObject state) {
        String difference=firstDifference(rootKey,key(state),"");
        diagnostic("state_changed",difference,state);
        if(paused) {
            cancel(false);
            paused=true;
            status=I18n.t("局面已变化 · 请重新计算");
        } else if(auto || turnOnly)fail(I18n.t("局面发生未预期变化，已停止自动执行：")+difference);
        else recalculate();
    }

    private void showDirectory(Path directory) {
        try { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(directory.toFile()); }
        catch (Exception failure) { System.err.println("[STS1Export] " + failure); }
    }

    private void exportDebug() {
        try {
            startBackend();
            JsonObject request = new JsonObject();
            request.addProperty("op", "export_debug");
            request.add("metadata", DecisionContext.metadata());
            writer.write(gson.toJson(request)); writer.newLine(); writer.flush();
            debugMessage = I18n.t("正在导出错误报告…");
        } catch (Exception failure) { debugMessage = I18n.t("导出失败：") + failure.getMessage(); }
    }

    private void toggleExperience() {
        try {
            if (experience.enabled()) experience.stop();
            else experience.start(notesDirectory("experience"), DecisionContext.metadata());
            debugMessage = experience.enabled()?I18n.t("已录制经验 · Shift+F6 导出"):I18n.t("经验记录已关闭");
        } catch (Exception failure) { experienceFailure(failure); }
    }

    private void exportExperience() {
        try {
            Path directory = experience.finish();
            if (directory == null || experience.count() == 0) { debugMessage = I18n.t("请先开启经验记录"); return; }
            debugMessage = I18n.t("经验已导出 · 请检查后分享");
            showDirectory(directory);
        } catch (Exception failure) { experienceFailure(failure); }
    }

    private void experienceFailure(Exception failure) {
        try { experience.stop(); } catch (IOException ignored) { }
        debugMessage = I18n.t("经验记录已停止：") + failure.getMessage();
        System.err.println("[STS1Experience] " + failure);
    }

    private void recordExperience(String kind, JsonObject state, String action, JsonObject plan) {
        if (!experience.enabled()) return;
        try {
            JsonObject snapshot = new JsonObject();
            if (experiencePlayer != AbstractDungeon.player) { experiencePlayer=AbstractDungeon.player; experienceRun++; }
            snapshot.addProperty("run_sequence", experienceRun);
            snapshot.add("raw_state", state);
            snapshot.add("decision_context", DecisionContext.visible());
            snapshot.add("privileged_recovery", DecisionContext.recovery());
            experience.record(kind, snapshot, action, plan);
        } catch (Exception failure) { experienceFailure(failure); }
    }

    private void observeExperience() {
        if (!experience.enabled() || !CommandExecutor.isInDungeon() || AbstractDungeon.player == null
                || System.currentTimeMillis() < nextExperienceObservation) return;
        nextExperienceObservation = System.currentTimeMillis() + 200;
        try {
            JsonObject state = capture();
            if (state.has("ready_for_command") && state.get("ready_for_command").getAsBoolean())
                recordExperience("observation", state, null, null);
        } catch (Exception failure) { experienceFailure(failure); }
    }

    private void startVoiceNote() {
        try {
            Properties properties = new Properties();
            try (InputStream stream = getClass().getResourceAsStream("/solver.properties")) {
                if (stream == null) throw new IOException("solver.properties missing");
                properties.load(stream);
            }
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
            voiceNotes.start(BackendRuntime.dataDirectory(properties).resolve("voice-notes"), gson.toJson(context));
        } catch (Exception failure) {
            error = I18n.t("录音启动失败：") + failure.getMessage();
            System.err.println("[STS1VoiceNotes] " + failure);
        }
    }

    @Override public void receivePostUpdate() {
        layout();
        if (researchRecording) observeInventory();
        observeExperience();
        JsonObject debugReply;
        while ((debugReply = debugReplies.poll()) != null) {
            if (debugReply.get("status").getAsString().equals("debug_export")) {
                debugMessage = I18n.t("报告已导出 · F6");
                try { showDirectory(notesDirectory("bug-reports")); } catch (IOException failure) { debugMessage = failure.getMessage(); }
            } else debugMessage = I18n.t("导出失败：") + debugReply.get("message").getAsString();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F6)) {
            if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) exportExperience();
            else if (researchRecording) { if (voiceNotes.active()) voiceNotes.stop(); else startVoiceNote(); }
            else exportDebug();
        }
        if (bindingKey) {
            for (int key = Input.Keys.F1; key <= Input.Keys.F12; key++) {
                if (validAutoKey(key) && Gdx.input.isKeyJustPressed(key)) {
                    autoKey=key; bindingKey=false;
                    try { if(config!=null){config.setInt("autoKey",key);config.save();} }
                    catch(IOException failure){error=I18n.t("快捷键保存失败：")+failure.getMessage();}
                    return;
                }
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) bindingKey=false;
        } else if (Gdx.input.isKeyJustPressed(autoKey)) toggleAuto();
        if (Gdx.input.isKeyJustPressed(Input.Keys.F8)) { collapsed = !collapsed; autoFolded = false; }
        autoFold();
        if(!CommandExecutor.isInDungeon() || AbstractDungeon.player==null) {
            runAuto=false;runAutoPlayer=null;
        } else if(supported() && AbstractDungeon.player!=runAutoPlayer) {
            runAutoPlayer=AbstractDungeon.player;
            runAuto=runAutoEnabled;
            autoPotionRewards=config==null || config.getBool("autoPotionRewards");
        }
        if(AbstractDungeon.player!=null && AbstractDungeon.player.isDead)runAuto=false;
        if (Gdx.input.isKeyJustPressed(Input.Keys.F9)) stopAll();
        boolean inCombat = combat();
        if(!wasCombat && inCombat && runAuto) {
            auto=true;paused=false;branchConfirmed=false;decisionDirty=true;lastKey=null;
        }
        if (wasCombat && !inCombat) {
            diagnostic("combat_end",I18n.t("离开战斗"),null);
            cancel(false);
            nextRest=false;
            lastKey = null;
            thiefPreference=null;
            error = "";
        }
        wasCombat = inCombat;
        if (!inCombat) {
            status = runAuto ? I18n.t("跨场自动已开 · 等待下场；其他选择由你决定") : I18n.t("选牌与路线由你决定");
            updatePotionRewards();
            return;
        }
        finishGamblingSelection(AbstractDungeon.screen);
        if (!supported()) {
            if (busy || auto || result != null) cancel(true);
            status = I18n.t("战斗引擎目前支持") + I18n.t(SUPPORTED_NAMES) + I18n.t("；预测仍可用");
            return;
        }
        JsonObject reply;
        while ((reply = replies.poll()) != null) {
            if (reply.get("id").getAsLong() != activeId) continue;
            String kind = reply.get("status").getAsString();
            if (kind.equals("progress")) {
                lastProgressAt = System.currentTimeMillis();
                if (reply.has("phase")) {
                    backendPhase = reply.get("phase").getAsString();
                    phaseAt = lastProgressAt;
                }
                if (reply.has("branches")) progressRows = reply.getAsJsonArray("branches");
                continue;
            }
            busy = false;
            if (kind.equals("ready")) {
                result = reply;
                status = reply.has("manual_choice") && reply.get("manual_choice").getAsBoolean()
                    ? I18n.t("抢劫怪：选定路线后可开启自动战斗")
                    : reply.has("interrupted") && reply.get("interrupted").getAsBoolean()
                    ? I18n.t("已停止 · 回车或选编号继续当前路线") : I18n.t("路线已就绪 · 等待你的操作");
                if(reply.has("boss_fast_finish") && reply.get("boss_fast_finish").getAsBoolean())
                    status=I18n.t("Boss 已有合格无药胜线 · 固定路线，结束搜索");
                if(runAuto && !paused && !branchConfirmed) {
                    chooseAutomaticBranch();
                } else if (clickedBranch != null) {
                    String selected = clickedBranch;
                    clickedBranch = null;
                    boolean available = false;
                    for (JsonElement branch : reply.getAsJsonArray("branches")) {
                        JsonObject row = branch.getAsJsonObject();
                        if (row.get("id").getAsString().equals(selected) && row.get("available").getAsBoolean())
                            available = true;
                    }
                    if (available) chooseBranch(selected);
                    else status = I18n.t("该路线尚无完整结果 · 请重新选择");
                } else if (reply.has("manual_choice") && reply.get("manual_choice").getAsBoolean()
                        && thiefPreference != null && !branchConfirmed && !paused) {
                    boolean available=false;
                    for(JsonElement branch:reply.getAsJsonArray("branches")) {
                        JsonObject row=branch.getAsJsonObject();
                        if(row.get("id").getAsString().equals(thiefPreference)
                                && row.get("available").getAsBoolean())available=true;
                    }
                    if(available)chooseBranch(thiefPreference);
                    else {thiefPreference=null;requestBranches();}
                } else if (reply.has("interrupted") && reply.get("interrupted").getAsBoolean()
                        && reply.getAsJsonArray("branches").size()>1) {
                    branchPicker=true;
                    branchInput="";
                    status=I18n.t("已停止 · 点击路线或输入编号后回车");
                } else if (reply.get("needs_confirmation").getAsBoolean() && !branchConfirmed) requestBranches();
                decisionDirty=true;
            } else if (kind.equals("error")) fail(reply.get("message").getAsString());
            else if (kind.equals("cancelled")) { busy=false; clickedBranch=null; status=I18n.t("已停止 · 尚无可执行路线"); }
            else if (kind.equals("complete")) {
                auto = turnOnly = false;
                paused = true;
                status = I18n.t("路线已结束 · 可手动操作或重新计算");
            }
        }
        long now = System.currentTimeMillis();
        if (busy && (backend == null || !backend.isAlive()
                || now - sentAt > (budgets[budgetIndex] >= 128000 ? 600000 : 100000))) {
            fail(I18n.t("后台求解器未响应，请重新计算。详情见数据目录中的 backend.log。"));
            return;
        }
        if(awaitingAction && now-sentAt>15000){fail(I18n.t("动作未产生可执行的完成状态，请手动检查。"));return;}
        if (!ready() || !decisionDirty) return;
        try {
            JsonObject state = capture(), current = key(state);
            if (!paused && !gamblingAuto && !auto && !turnOnly && !awaitingAction
                    && openingSelection(state)) {
                gamblingAuto = auto = branchConfirmed = true;
                branchPicker=false;captureBranchKeys=false;branchInput="";
                status = I18n.t("开场选牌：规划并自动选择（不用药路线）");
            }
            if (awaitingAction) {
                if (executedCommand.equals("end") && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.NONE
                        && GameActionManager.turn <= executedTurn) return;
                if (current.equals(rootKey)) {
                    if (now - sentAt > 8000) fail(I18n.t("动作未完成，已停止；请手动操作或重新计算。"));
                    return;
                }
                awaitingAction = false;
                decisionDirty=false;
                send("advance", state);
                return;
            }
            if ((busy || result != null) && !current.equals(rootKey)) {
                stateChanged(state);
                if(paused)return;
            }
            if (!busy && result == null && !paused && !current.equals(lastKey)) {
                decisionDirty=false;
                send("solve", state);
                return;
            }
            if (result != null && (auto || turnOnly)) {
                if(runAuto && !branchConfirmed){chooseAutomaticBranch();return;}
                if (result.get("needs_confirmation").getAsBoolean() && !branchConfirmed) {requestBranches();return;}
                int turn = state.getAsJsonObject("game_state").getAsJsonObject("combat_state").get("turn").getAsInt();
                if (turnOnly && turn != startingTurn) { turnOnly = false; return; }
                executeStep();
            }
            if(!awaitingAction && !captureBranchKeys)decisionDirty=false;
        } catch (Exception failure) { fail(failure.toString()); }
    }

    private void executeStep() {
        if (result == null || !ready() || busy || awaitingAction) return;
        if (!routeConfirmed(result,branchConfirmed) || branchPicker || captureBranchKeys) return;
        try {
            JsonObject state = capture();
            if (!key(state).equals(rootKey)) { stateChanged(state); return; }
            String command = result.get("command").getAsString();
            String verb = command.split(" ")[0];
            if (!Arrays.asList("play", "end", "potion", "choose", "confirm", "skip").contains(verb))
                throw new IllegalStateException(I18n.t("拒绝局外动作"));
            executedTurn = GameActionManager.turn;
            executedCommand = verb;
            diagnostic("execute",command,state);
            decisionDirty = true;
            // Direct game-thread call: no queued command can survive Stop or a screen change.
            if (!CommandExecutor.executeCommand(command)) throw new IllegalStateException(I18n.t("动作未提交"));
            recordExperience("solver_action_submitted", state, command, result);
            GameStateListener.registerCommandExecution();
            result = null;
            awaitingAction = true;
            sentAt = System.currentTimeMillis();
            status = I18n.t("执行中 · 等待动画结算");
        } catch (Exception failure) { fail(failure.toString()); }
    }

    private boolean useFruitJuice() throws Exception {
        if(!autoPotionRewards || !GameStateListener.isWaitingForCommand())return false;
        for(int slot=0;slot<AbstractDungeon.player.potions.size();slot++) {
            com.megacrit.cardcrawl.potions.AbstractPotion potion=AbstractDungeon.player.potions.get(slot);
            if(!"Fruit Juice".equals(potion.ID) || !potion.canUse())continue;
            if(!CommandExecutor.executeCommand("potion use "+slot))
                throw new IllegalStateException(I18n.t("果汁未能提交使用"));
            GameStateListener.registerCommandExecution();
            status=I18n.t("果汁已使用 · 等待结算");
            return true;
        }
        return false;
    }

    private void updatePotionRewards() {
        rewardPotionAdvice=null;
        if(!CommandExecutor.isInDungeon() || AbstractDungeon.player==null || !supported())return;
        try {
            if(AbstractDungeon.screen!=AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
                if(rewardPotionPending!=null)fail(I18n.t("领取药水期间离开奖励界面，已停止自动"));
                else useFruitJuice();
                return;
            }
            JsonObject state=capture(), game=state.getAsJsonObject("game_state");
            if(rewardPotionPending!=null) {
                int checked=rewardPotionPending.check(game);
                if(checked<0) {fail(I18n.t("领取药水时库存发生变化，已停止自动"));return;}
                if(checked==1) {
                    diagnostic("potion_reward_complete",rewardPotionPending.advice.message,state);
                    status=I18n.t("药水已领取");rewardPotionPending=null;return;
                }
                if(System.currentTimeMillis()-rewardPotionPending.started>5000)
                    fail(I18n.t("药水尚未领取成功，已停止自动；请检查奖励"));
                return;
            }
            if(useFruitJuice())return;
            boolean hasPotion=false;
            JsonArray offers=game.getAsJsonObject("screen_state").getAsJsonArray("rewards");
            if(offers!=null)for(JsonElement offer:offers)
                hasPotion|="POTION".equals(PotionRewards.string(offer.getAsJsonObject(),"reward_type"));
            if(!hasPotion)return;
            rewardPotionAdvice=PotionRewards.advise(game);
            if(!autoPotionRewards || !rewardPotionAdvice.actionable()
                    || !GameStateListener.isWaitingForCommand())return;
            PotionRewards.Advice advice=rewardPotionAdvice;
            // Both commands run on the game thread against this exact reward object.
            // No delayed discard may survive a menu change or Stop.
            if(advice.reward>=AbstractDungeon.combatRewardScreen.rewards.size())return;
            com.megacrit.cardcrawl.rewards.RewardItem reward=AbstractDungeon.combatRewardScreen.rewards.get(advice.reward);
            if(reward.type!=com.megacrit.cardcrawl.rewards.RewardItem.RewardType.POTION
                    || reward.isDone || reward.ignoreReward || reward.potion==null
                    || !reward.potion.ID.equals(advice.incoming) || AbstractDungeon.player.hasRelic("Sozu"))return;
            com.megacrit.cardcrawl.potions.AbstractPotion old=AbstractDungeon.player.potions.get(advice.slot);
            if(!old.ID.equals(advice.outgoing))return;
            boolean discard=!"Potion Slot".equals(advice.outgoing);
            if(discard && !old.canDiscard())return;
            rewardPotionPending=new PotionRewards.Pending(game,advice,System.currentTimeMillis());
            diagnostic("potion_reward_execute",advice.message,state);
            if(discard && !CommandExecutor.executeCommand("potion discard "+advice.slot))
                throw new IllegalStateException(I18n.t("换药未能提交弃药"));
            if(!(AbstractDungeon.player.potions.get(advice.slot) instanceof com.megacrit.cardcrawl.potions.PotionSlot))
                throw new IllegalStateException(I18n.t("换药未出现空栏，停止领取"));
            if(!CommandExecutor.executeCommand("choose "+advice.reward))
                throw new IllegalStateException(I18n.t("药水奖励未能提交领取"));
            GameStateListener.registerCommandExecution();
            status=advice.message+I18n.t(" · 等待领取确认");
        } catch(Exception failure){fail(I18n.t("自动换药失败：")+failure.getMessage());}
    }

    private void renderPotionRewardAdvice(SpriteBatch sb) {
        if(rewardPotionAdvice==null || AbstractDungeon.screen!=AbstractDungeon.CurrentScreen.COMBAT_REWARD)return;
        String label=(autoPotionRewards?I18n.t("自动 · "):I18n.t("建议 · "))+rewardPotionAdvice.message;
        com.megacrit.cardcrawl.helpers.FontHelper.renderFontLeftTopAligned(sb,font(),label,
            Settings.WIDTH*.20f,Settings.HEIGHT*.90f,accent);
    }

    private float scale() { return Settings.scale; }
    private void layout() {
        float s = scale();
        if (x < 0) { x = 20*s; y = Settings.HEIGHT - 180*s; }
        x = Math.max(0, Math.min(x, Settings.WIDTH - PANEL_WIDTH*s));
        y = Math.max((collapsed ? 48 : PANEL_HEIGHT)*s, Math.min(y, Settings.HEIGHT));
    }
    private boolean inside(float mx, float my, float rx, float ry, float w, float h) {
        return mx >= rx && mx <= rx+w && my >= ry && my <= ry+h;
    }

    // Runs after InputHelper polls, before dungeon/card/monster handlers see clicks.
    @SpirePatch(clz=InputHelper.class, method="updateFirst")
    public static class InputPatch {
        @SpirePostfixPatch public static void after() { if (instance != null) instance.input(); }
    }
    @SpirePatch(clz=InputAction.class, method="isJustPressed")
    public static class BranchJustPressedPatch {
        @SpirePrefixPatch public static SpireReturn<Boolean> before() {
            return instance!=null && instance.captureBranchKeys ? SpireReturn.Return(false) : SpireReturn.Continue();
        }
    }
    @SpirePatch(clz=InputAction.class, method="isPressed")
    public static class BranchPressedPatch {
        @SpirePrefixPatch public static SpireReturn<Boolean> before() {
            return instance!=null && instance.captureBranchKeys ? SpireReturn.Return(false) : SpireReturn.Continue();
        }
    }
    static int branchNumber(String input, int count) {
        try {
            int n=input.isEmpty()?0:Integer.parseInt(input);
            return n>=0 && n<=10 && n<count ? n : -1;
        } catch(NumberFormatException invalid) { return -1; }
    }
    private void branchKeyboard() {
        boolean held=Gdx.input.isKeyPressed(Input.Keys.ENTER);
        for(int n=0;n<=9;n++) held |= Gdx.input.isKeyPressed(Input.Keys.NUM_0+n)
                || Gdx.input.isKeyPressed(Input.Keys.NUMPAD_0+n);
        // Keep native card hotkeys suppressed until the selecting key is released.
        captureBranchKeys=branchPicker || (captureBranchKeys && held);
        // Enter starts this combat's auto execution (same as the auto key). The key is
        // withheld from the game until released; potion choices keep their own Enter.
        if(!branchPicker && !auto && !turnOnly && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)
                && supported() && ready()) {
            captureBranchKeys=true;
            toggleAuto();
            return;
        }
        if(!branchPicker || busy || result==null)return;
        for(int n=0;n<=9;n++) {
            if(Gdx.input.isKeyJustPressed(Input.Keys.NUM_0+n) || Gdx.input.isKeyJustPressed(Input.Keys.NUMPAD_0+n)) {
                if(branchInput.length()>=2)branchInput="";
                branchInput+=n;
            }
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE) && !branchInput.isEmpty())
            branchInput=branchInput.substring(0,branchInput.length()-1);
        if(Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            JsonArray branches=result.getAsJsonArray("branches");
            int n=branchNumber(branchInput,branches.size());
            if(n>=0 && branches.get(n).getAsJsonObject().get("available").getAsBoolean())
                chooseBranch(branches.get(n).getAsJsonObject().get("id").getAsString());
            else status=I18n.t("编号不可用 · 请修改数字后回车");
        }
    }
    private void input() {
        layout();
        int previewCount = Foresight.previewCount();
        Object source = AbstractDungeon.currMapNode;
        if (previewCount == 0 || (transformPreview != null && (previewRngCounter != AbstractDungeon.miscRng.counter || previewSource != source)))
            transformPreview = null;
        branchKeyboard();
        float s=scale(), mx=InputHelper.mX, my=InputHelper.mY;
        boolean over=inside(mx,my,x,y-(collapsed?48:PANEL_HEIGHT)*s,panelWidth()*s,(collapsed?48:PANEL_HEIGHT)*s);
        boolean click=InputHelper.justClickedLeft;
        if (dragging) {
            if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)) { x=mx-dx; y=my-dy; layout(); }
            else dragging=false;
        }
        if (over && click) {
            float lx=(mx-x)/s, ly=(y-my)/s;
            if (ly < 46) {
                if (!collapsed && lx >= 688 && lx < PANEL_WIDTH-47) {
                    toggleExperience();
                } else if (!collapsed && lx >= ROUTE_LEFT && lx < 688) {
                    Foresight.brightEyeMode = !Foresight.brightEyeMode;
                    scroll = 0;
                    try { if(config!=null) { config.setBool("brightEyeMode",Foresight.brightEyeMode); config.save(); } }
                    catch(IOException failure) { error=I18n.t("模式保存失败：")+failure.getMessage(); }
                } else if (lx > panelWidth()-47) { collapsed=!collapsed; autoFolded=false; }
                else { dragging=true; dx=mx-x; dy=my-y; }
            } else if (!collapsed && previewCount > 0 && lx >= ROUTE_LEFT && ly >= 50 && ly < 79) {
                if (transformPreview != null) transformPreview = null;
                else {
                    boolean astrolabe = Foresight.astrolabeOffered();
                    transformPreview = new TransformPreview(astrolabe ? AbstractDungeon.player.masterDeck.getPurgeableCards().group
                        : EventForesight.transformCards(AbstractDungeon.getCurrRoom().event).group,
                        AbstractDungeon.miscRng, previewCount, astrolabe);
                    previewRngCounter = AbstractDungeon.miscRng.counter;
                    previewSource = source;
                }
                scroll = 0;
            } else if (!collapsed && transformPreview != null && lx >= ROUTE_LEFT && ly >= 82 && ly < 244) {
                transformPreview.pick(scroll + (int)((ly-82)/27));
                scroll = 0;
            } else if (!collapsed && ly >= 278 && ly <= 310) {
                if (lx < 12+ACTION_SPACING) recalculate();
                else if (lx < 12+2*ACTION_SPACING) { runAuto=auto=turnOnly=false; executeStep(); }
                else if (lx < 12+3*ACTION_SPACING) {
                    toggleAuto();
                } else stopAll();
            } else if (!collapsed && ly >= 316 && ly <= 344) {
                if(lx>=ROUTE_LEFT)togglePotionRewards();else bindingKey=true;
            } else if (!collapsed && lx<ROUTE_LEFT && ly>=350 && ly<=378) {
                toggleLanguage();
            } else if (!collapsed && lx>=ROUTE_LEFT && ly>=350 && ly<=378) {
                toggleRunAuto();
            } else if (!collapsed && lx >= ROUTE_LEFT && ly>=240 && ly<260 && canCreditRest()) {
                nextRest=!nextRest;recalculate();
            } else if (!collapsed && lx >= ROUTE_LEFT && branchPicker && result!=null && ly>=82 && ly<222) {
                int index=scroll+(int)((ly-82)/28);
                JsonArray branches=result.getAsJsonArray("branches");
                if(index<branches.size()) {
                    JsonObject branch=branches.get(index).getAsJsonObject();
                    if(branch.get("available").getAsBoolean())chooseBranch(branch.get("id").getAsString());
                }
            } else if (!collapsed && lx >= ROUTE_LEFT && busy && progressRows!=null && ly>=82 && ly<244) {
                int index=(int)((ly-82)/27);
                if(index<Math.min(progressRows.size(),6)) {
                    JsonObject row=progressRows.get(index).getAsJsonObject();
                    if(row.has("ready") && row.get("ready").getAsBoolean() && row.has("id")) {
                        clickedBranch=row.get("id").getAsString();
                        cancel(true);
                    }
                }
            } else if (!collapsed && lx >= ROUTE_LEFT && result!=null && ly>=82 && ly<244) {
                if (!auto && !turnOnly) toggleAuto();
            } else if (!collapsed && lx >= 16 && lx < ROUTE_LEFT-16 && ly >= 198 && ly <= 272) {
                if (ly <= 230) { budgetIndex=(budgetIndex+1)%3; recalculate(); }
                else if (ly < 240) { /* Gap between buttons. */ }
                else if (result != null && ready()) {
                    startingTurn=AbstractDungeon.actionManager.turn;
                    turnOnly=true; runAuto=auto=false;
                }
            }
        }
        hovered = over || dragging;
        if (over || dragging) {
            if (!collapsed) {
                if (InputHelper.scrolledDown) scroll++;
                if (InputHelper.scrolledUp) scroll=Math.max(0,scroll-1);
            }
            InputHelper.justClickedLeft=false;
            InputHelper.justReleasedClickLeft=false;
            InputHelper.justClickedRight=false;
            InputHelper.justReleasedClickRight=false;
            InputHelper.scrolledDown=InputHelper.scrolledUp=false;
        }
    }

    private void toggleLanguage() {
        I18n.setLanguage("en".equals(I18n.language()) ? "zh" : "en");
        scroll = 0;
        status = I18n.t(busy ? "正在搜索整场战斗…" : "路线已就绪 · 等待你的操作");
        try {
            if (config != null) { config.setString("language", I18n.language()); config.save(); }
        } catch (IOException failure) { error = "Language save failed: " + failure.getMessage(); }
    }

    private void box(SpriteBatch sb, float left, float top, float width, float height, Color color) {
        sb.setColor(color);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x+left*scale(), y-(top+height)*scale(), width*scale(), height*scale());
    }
    private void initFont() {
        if (uiFont == null) {
            // Use the game's bundled CJK font even when the game language is English.
            fontGenerator = new FreeTypeFontGenerator(Gdx.files.internal("font/zhs/NotoSansMonoCJKsc-Regular.otf"));
            FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
            parameter.size = Math.max(12, Math.round(19*scale()));
            parameter.incremental = true;
            uiFont = fontGenerator.generateFont(parameter);
        }
    }
    BitmapFont font() { initFont(); return uiFont; }
    private void text(SpriteBatch sb, String text, float left, float top, Color color) {
        text = I18n.t(text);
        initFont();
        float right = top >= 50 && top < 244 && left < ROUTE_LEFT ? ROUTE_LEFT-16 : PANEL_WIDTH-14;
        float available=(right-left)*scale();
        measure.setText(uiFont,text);
        while(text.length()>1 && measure.width>available) {
            text=text.substring(0,text.length()-2)+"…";
            measure.setText(uiFont,text);
        }
        FontHelper.renderFontLeftTopAligned(sb, uiFont, text, x+left*scale(), y-top*scale(), color);
    }
    private static String count(long n) { return n>=10000 ? String.format(I18n.t("%.1f 万"), n/10000.0) : Long.toString(n); }
    private String progressSummary() {
        long total=0; int turn=0, best=-1;
        for (JsonElement e : progressRows) {
            JsonObject row=e.getAsJsonObject();
            total+=row.get("simulations").getAsLong();
            turn=Math.max(turn,row.get("turn").getAsInt());
            best=Math.max(best,row.get("best_hp").getAsInt());
        }
        return I18n.t("已模拟 ")+count(total)+I18n.t("次 · 规划至 T")+turn+(best>0?I18n.t(" · 最佳 HP ")+best:"");
    }
    private String shortText(String value, int max) { return I18n.t(value); }
    private boolean canCreditRest() {
        if(!combat() || !AbstractDungeon.player.hasRelic("Eternal Feather"))return false;
        for(com.megacrit.cardcrawl.map.MapRoomNode node:communicationmod.ChoiceScreenUtils.getMapScreenNodeChoices())
            if(node.getRoomSymbol(true).equals("R"))return true;
        return false;
    }
    private String commandLabel() {
        if (result == null) return "";
        String command=result.get("command").getAsString();
        String[] p=command.split(" ");
        try {
            if (p[0].equals("play")) {
                AbstractCard c=AbstractDungeon.player.hand.group.get(Integer.parseInt(p[1])-1);
                String target=p.length>2 ? " → "+AbstractDungeon.getMonsters().monsters.get(Integer.parseInt(p[2])).name : "";
                return c.name+target;
            }
            if (p[0].equals("potion")) return I18n.t("药水：")+AbstractDungeon.player.potions.get(Integer.parseInt(p[2])).name;
            if (p[0].equals("end")) return I18n.t("结束回合");
            if (p[0].equals("choose")) return I18n.t("战斗选牌：第 ")+(Integer.parseInt(p[1])+1)+I18n.t(" 张");
            if (p[0].equals("confirm")) return I18n.t("确认战斗选牌");
            if (p[0].equals("skip")) return I18n.t("跳过战斗选牌");
        } catch (RuntimeException ignored) { return I18n.t("局面已变化，等待重新计算"); }
        return command;
    }

    @Override public void receivePostRender(SpriteBatch sb) {
        Foresight.renderRubyReminder(sb);
        renderPotionRewardAdvice(sb);
        layout();
        box(sb,0,0,panelWidth(),collapsed?48:PANEL_HEIGHT,background);
        box(sb,0,0,4,collapsed?48:PANEL_HEIGHT,accent);
        text(sb,researchRecording?voiceNotes.status():debugMessage.isEmpty()?I18n.t("F6 导出错误报告"):debugMessage,18,12,Color.WHITE);
        text(sb,collapsed?"＋":"－",panelWidth()-35,12,accent);
        if (collapsed) { sb.setColor(Color.WHITE); cursorOnTop(sb); return; }
        text(sb,I18n.t("辉眼模式：")+(Foresight.brightEyeMode?I18n.t("开"):I18n.t("关"))+I18n.t(" [点击切换]"),ROUTE_LEFT+8,12,accent);
        text(sb,I18n.t("经验：") + I18n.t(experience.enabled()?"开":"关"),696,12,experience.enabled()?accent:muted);
        String revivalWarning = result == null ? "" : revivalWarning(result);
        text(sb,revivalWarning.isEmpty()?shortText(status,30):revivalWarning,18,55,
            revivalWarning.isEmpty() && error.isEmpty()?muted:danger);
        box(sb,16,89,ROUTE_LEFT-32,98,surface);
        if (busy) {
            long seconds=(System.currentTimeMillis()-sentAt)/1000;
            long now = System.currentTimeMillis();
            text(sb,shortText(I18n.backend(backendPhase),28),30,99,accent);
            text(sb,I18n.t("总等待 ")+seconds+I18n.t(" 秒 · 本阶段 ")+((now-phaseAt)/1000)+I18n.t(" 秒"),30,126,muted);
            text(sb,now-lastProgressAt>5000
                ? I18n.t("已 ")+((now-lastProgressAt)/1000)+I18n.t(" 秒无进度 · F9 停止")
                :progressRows==null?I18n.t("请求 #")+activeId+I18n.t(" · F9 停止"):progressSummary(),30,153,muted);
        } else if (result != null) {
            boolean won=result.get("won").getAsBoolean();
            text(sb,won?I18n.t("预测胜利   战后 HP ")+result.get("hp").getAsInt():I18n.t("当前路线未找到胜利"),30,103,won?accent:danger);
            String healing=I18n.t("等效战损 ")+result.get("loss").getAsInt()+I18n.t(" · 战后回 ")+result.get("recovery").getAsInt();
            if(result.getAsJsonArray("recovery_notes").size()>0)
                healing+=" · "+I18n.backend(result.getAsJsonArray("recovery_notes").get(0).getAsString());
            text(sb,healing,30,134,muted);
            text(sb,I18n.t("下一步  ")+shortText(commandLabel(),25),30,160,Color.WHITE);
        } else {
            text(sb,error.isEmpty()?I18n.t("由你构筑，由它求解战斗"):shortText(I18n.backend(error),27),30,105,error.isEmpty()?accent:danger);
            text(sb,error.isEmpty()?I18n.t("仅查看建议，点击按钮后才出牌"):I18n.t("详细原因已写入后台日志"),30,143,muted);
        }
        List<String> foresight=transformPreview!=null?transformPreview.lines():combat()?Collections.<String>emptyList():Foresight.lines();
        String heading=transformPreview!=null?(transformPreview.upgrade?I18n.t("星盘"):I18n.t("变化"))+I18n.t("试选 ") + transformPreview.selected.size() + "/"+transformPreview.count+I18n.t(" · 点击此处关闭")
            :Foresight.previewCount()>0?I18n.t("变化预览 · 点击此处打开试选列表")
            :branchPicker?I18n.t("输入 0～10 后回车：[")+branchInput+"]"
            :busy&&progressRows!=null?I18n.t("搜索过程 · 已完成路线可点击执行")
            :!foresight.isEmpty()?I18n.t("随机结果预测 · 滚轮翻页")
            :result!=null && result.has("manual_choice") && result.get("manual_choice").getAsBoolean()
                ?I18n.t("抢劫怪路线 · 可手动或自动执行"):I18n.t("推荐行动顺序 · 点击执行整条路线");
        text(sb,heading,ROUTE_LEFT+2,55,Color.WHITE);
        if (transformPreview != null) {
            scroll=Math.max(0,Math.min(scroll,Math.max(0,foresight.size()-6)));
            for (int i=scroll;i<Math.min(foresight.size(),scroll+6);i++) {
                box(sb,ROUTE_LEFT,82+(i-scroll)*27,PANEL_WIDTH-ROUTE_LEFT-16,25,surface);
                text(sb,foresight.get(i),ROUTE_LEFT+8,88+(i-scroll)*27,i<transformPreview.selected.size()?accent:muted);
            }
            text(sb,I18n.t("仅试选 · 滚轮翻页 · 点击已选项撤回"),ROUTE_LEFT+8,240,accent);
        } else if (branchPicker && result!=null) {
            JsonArray branches=result.getAsJsonArray("branches");
            scroll=Math.max(0,Math.min(scroll,Math.max(0,branches.size()-5)));
            for(int i=scroll;i<Math.min(branches.size(),scroll+5);i++) {
                JsonObject branch=branches.get(i).getAsJsonObject();
                boolean available=branch.get("available").getAsBoolean();
                // Result first so a long bottle list can only truncate the route name.
                String summary=available?(branch.get("won").getAsBoolean()?I18n.t("损 ")+branch.get("loss").getAsInt():I18n.t("未胜")):I18n.t("不可用");
                String revival = revivalWarning(branch);
                if (!revival.isEmpty()) summary = revival + " · " + summary;
                if(available && branch.has("lost_gold") && branch.get("lost_gold").getAsInt()>0)
                    summary+=I18n.t(" / 金 -")+branch.get("lost_gold").getAsInt();
                if(available && branch.has("potion_gain") && branch.get("potion_gain").getAsInt()>0)
                    summary+=I18n.t(" / 补 ")+branch.get("potion_gain").getAsInt()+I18n.t(" 药");
                box(sb,ROUTE_LEFT,82+(i-scroll)*28,PANEL_WIDTH-ROUTE_LEFT-16,26,surface);
                text(sb,i+" "+summary+" · "+I18n.backend(branch.get("name").getAsString()),ROUTE_LEFT+8,86+(i-scroll)*28,
                    !revival.isEmpty()?danger:available?accent:muted);
            }
            text(sb,result.has("manual_choice") && result.get("manual_choice").getAsBoolean()
                ?I18n.t("空回车：选推荐路线；选后可开自动"):I18n.t("0 / 空回车：不交药；无输入则等待"),ROUTE_LEFT+8,222,muted);
        } else if (result != null) {
            JsonArray rows=result.getAsJsonArray("route");
            int visibleRows=runAuto?5:6;
            scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-visibleRows)));
            for (int i=scroll;i<Math.min(rows.size(),scroll+visibleRows);i++) {
                JsonObject row=rows.get(i).getAsJsonObject();
                String label=row.get("text").getAsString();
                if (row.has("label")) label=row.get("label").getAsString();
                text(sb,"T"+row.get("turn").getAsInt()+"  "+shortText(I18n.backend(label),33),ROUTE_LEFT+8,88+(i-scroll)*27,i==0?accent:muted);
            }
        } else if (busy && progressRows != null) {
            for (int i=0;i<Math.min(progressRows.size(),6);i++) {
                JsonObject row=progressRows.get(i).getAsJsonObject();
                int best=row.get("best_hp").getAsInt();
                String line=(best>0?"HP "+best:I18n.t("未胜"))+" · T"+row.get("turn").getAsInt()+" · "
                    +count(row.get("simulations").getAsLong())+" · "+I18n.backend(row.get("name").getAsString());
                text(sb,shortText(line,34),ROUTE_LEFT+8,88+i*27,row.has("ready") && row.get("ready").getAsBoolean()?accent:muted);
            }
        } else if (!foresight.isEmpty()) {
            scroll=Math.max(0,Math.min(scroll,Math.max(0,foresight.size()-6)));
            for (int i=scroll;i<Math.min(foresight.size(),scroll+6);i++) {
                String line=foresight.get(i);
                text(sb,shortText(line,34),ROUTE_LEFT+8,88+(i-scroll)*27,line.startsWith(" ")?muted:accent);
            }
        } else {
            text(sb,I18n.t("发牌和动画结算后自动生成建议"),ROUTE_LEFT+8,96,muted);
            text(sb,I18n.t("奖励牌 / 地图 / 商店始终由你操作"),ROUTE_LEFT+8,132,muted);
            text(sb,I18n.t("拖动标题栏移动 · F8 收起 / 展开"),ROUTE_LEFT+8,168,muted);
        }
        if(canCreditRest())text(sb,I18n.t("下层走火堆计羽毛回血：")+(nextRest?I18n.t("是"):I18n.t("仅必经火堆"))+I18n.t(" [点击]"),ROUTE_LEFT+8,240,accent);
        box(sb,16,198,ROUTE_LEFT-32,32,surface);
        box(sb,16,240,ROUTE_LEFT-32,32,surface);
        text(sb,I18n.t("搜索预算：")+I18n.t(budgetNames[budgetIndex]),28,204,muted);
        text(sb,turnOnly?I18n.t("正在执行本回合"):I18n.t("执行本回合"),28,246,result!=null?Color.WHITE:muted);
        String[] labels={I18n.t("重新计算"),I18n.t("执行一步"),auto?I18n.t("自动：开"):I18n.t("自动战斗"),I18n.t("停止")};
        for(int i=0;i<4;i++) {
            box(sb,16+i*ACTION_SPACING,278,ACTION_SPACING-8,32,surface);
            text(sb,labels[i],24+i*ACTION_SPACING,284,i==3?danger:(i==2&&auto?accent:Color.WHITE));
        }
        box(sb,16,316,ROUTE_LEFT-24,28,surface);
        text(sb,bindingKey?I18n.t("按 F1-F12 绑定；F6/F8/F9 保留；Esc 取消")
            :I18n.t("本场：回车 / ")+Input.Keys.toString(autoKey)+I18n.t(" [改绑] · F9 停止"),24,321,accent);
        box(sb,ROUTE_LEFT,316,PANEL_WIDTH-ROUTE_LEFT-16,28,surface);
        text(sb,I18n.t("自动换药：")+(autoPotionRewards?I18n.t("开"):I18n.t("关"))+I18n.t(" [点击切换]"),ROUTE_LEFT+8,321,accent);
        box(sb,16,350,ROUTE_LEFT-24,28,surface);
        text(sb,"Language: " + ("en".equals(I18n.language()) ? "English" : "中文") + " [click]",24,355,accent);
        box(sb,ROUTE_LEFT,350,PANEL_WIDTH-ROUTE_LEFT-16,28,surface);
        text(sb,runAuto?I18n.t("自动用药＋跨场战斗：开 [点击停止]"):I18n.t("自动用药＋跨场战斗：关 [点击开启]"),ROUTE_LEFT+8,355,accent);
        if(runAuto && result!=null && result.has("auto_recommendation")) {
            JsonObject advice=result.getAsJsonObject("auto_recommendation");
            text(sb,shortText(I18n.backend(autoReason),34),ROUTE_LEFT+8,218,accent);
            text(sb,shortText(I18n.backend(advice.getAsJsonObject("assessment").get("summary").getAsString()),34),ROUTE_LEFT+8,260,muted);
        }
        sb.setColor(Color.WHITE);
        cursorOnTop(sb);
    }
    // The game draws its cursor before post-render overlays; repeat it above the panel.
    private void cursorOnTop(SpriteBatch sb) {
        if (hovered && !com.megacrit.cardcrawl.core.GameCursor.hidden) com.megacrit.cardcrawl.core.CardCrawlGame.cursor.render(sb);
    }

    static String firstDifference(JsonElement a, JsonElement b, String path) {
        if (Objects.equals(a,b)) return "";
        if(a!=null && b!=null && a.isJsonObject() && b.isJsonObject()) {
            Set<String> keys=new TreeSet<>();
            for(Map.Entry<String,JsonElement> e:a.getAsJsonObject().entrySet())keys.add(e.getKey());
            for(Map.Entry<String,JsonElement> e:b.getAsJsonObject().entrySet())keys.add(e.getKey());
            for(String key:keys) {
                String diff=firstDifference(a.getAsJsonObject().get(key),b.getAsJsonObject().get(key),path+"/"+key);
                if(!diff.isEmpty())return diff;
            }
        }
        if(a!=null && b!=null && a.isJsonArray() && b.isJsonArray()) {
            JsonArray x=a.getAsJsonArray(),y=b.getAsJsonArray();
            if(x.size()!=y.size())return path+"/length: "+x.size()+" -> "+y.size();
            for(int i=0;i<x.size();i++) {
                String diff=firstDifference(x.get(i),y.get(i),path+"/"+i);
                if(!diff.isEmpty())return diff;
            }
        }
        return path+": "+String.valueOf(a)+" -> "+String.valueOf(b);
    }
}
