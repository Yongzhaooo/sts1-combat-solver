package sts1solver;

import static sts1solver.CombatReadiness.combat;
import static sts1solver.CombatReadiness.ready;
import static sts1solver.CombatReadiness.openingSelection;
import static sts1solver.CombatReadiness.actionPausedByScreen;
import static sts1solver.PanelCanvas.*;

import basemod.BaseMod;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import basemod.interfaces.PostInitializeSubscriber;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.google.gson.*;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.helpers.input.InputAction;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import communicationmod.CommandExecutor;
import communicationmod.GameStateConverter;
import communicationmod.GameStateListener;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@SpireInitializer
public class SolverMod implements PostUpdateSubscriber, PostRenderSubscriber, PostInitializeSubscriber {
    public static SolverMod instance;
    private final BackendClient backend = new BackendClient();
    private final PanelCanvas canvas = new PanelCanvas();
    private final OutsidePilot pilot = new OutsidePilot();
    private final AgentBridge agent = new AgentBridge();
    private final DeckWatch deckWatch = new DeckWatch();
    private final RunExports exports = new RunExports();
    private volatile String rngWarning = "";
    // SuperFastMode multiplies Gdx delta; the solver no longer depends on frame time.
    // x10 is the speed long runs were played at (the author's own setting).
    static final float SUPER_FAST_TESTED = 10f;
    private long serial, activeId, sentAt;
    private long searchTimeoutMillis = SearchStatus.timeoutMillis(8000);
    private JsonObject rootKey, lastKey, result;
    private boolean decisionDirty=true;
    private boolean busy, awaitingAction, auto, turnOnly, gamblingAuto, paused, collapsed;
    private boolean runAuto;
    private boolean heldThisFight; // Step, Execute turn or F10 took this fight over; the next fight runs automatically again
    private boolean runAfterBranch; // F10 pressed while a route choice was pending: run the fight once chosen
    private boolean autoPotionRewards = true;
    private boolean runAutoEnabled = true;
    private boolean bossFastFinishEnabled; // Settings: stop searching a Boss once a potion-free win is found
    private int smokeProbeBudget=12000, smokeLossThreshold=15; // Mod config: raw combat HP loss, strict less-than threshold
    private int searchWorkers; // 0: auto; applied only by the Mod backend.
    private boolean feedModeEnabled; // Mod config: max HP gained by Feed counts twice in the plan value
    private boolean routeMarks = true; // Mod config: draw the planned route on the map
    private AbstractPlayer runAutoPlayer;
    private String autoReason = "";
    private PotionRewards.Advice rewardPotionAdvice;
    private PotionRewards.Pending rewardPotionPending;
    private int scroll, startingTurn;
    private int executedTurn;
    private String executedCommand = "";
    // Every request asks for the standard budget; the backend tiers it per act and room (low-performance mode: 1/20,
    // streak mode: no acts 1-3 tiers).
    private static final int BUDGET = 8000;
    private boolean highSpeed, streakMode;
    private long highSpeedWarnedAt;
    private boolean agentBridge; // Agent play (AGENT_PLAY.md), see AgentBridge
    private String status = I18n.t("等待进入战斗"), error = "";
    // Compact combat panel: summary card left, five route rows right, then actions and toggles.
    private static final float ROUTE_LEFT = 248, BODY_TOP = 76, BODY_ROW = 30, NOTE_TOP = 234;
    private static final int BODY_ROWS = 5;
    private static final float ACTIONS_TOP = 262, TOGGLES_TOP = 306;
    private static final float SETTINGS_TOP = 78, SETTINGS_PITCH = 34;
    private static final float ACTION_SPACING = (PANEL_WIDTH - 24) / 6;
    private boolean settingsOpen;
    private TransformPreview transformPreview;
    private int previewRngCounter;
    private Object previewSource;
    private AbstractDungeon.CurrentScreen lastFoldScreen;
    private boolean wasCombat;
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
    // Classes the combat engine imports exactly; keep in sync with backend.SUPPORTED_CLASSES.
    // Out-of-combat foresight works for every class.
    static final EnumSet<AbstractPlayer.PlayerClass> SUPPORTED = EnumSet.of(AbstractPlayer.PlayerClass.IRONCLAD);
    static final String SUPPORTED_NAMES = I18n.t("战士");

    static boolean supported() {
        return AbstractDungeon.player != null && SUPPORTED.contains(AbstractDungeon.player.chosenClass);
    }

    public static void initialize() {
        communicationmod.CommunicationMod.initializeEmbedded();
        instance = new SolverMod();
    }
    public SolverMod() {
        BaseMod.subscribe(this);
        EventRules.live = true; // hidden-information event rules read the running game
        try {
            Properties defaults = new Properties();
            defaults.setProperty("language", "zh");
            defaults.setProperty("uiScale", "1");
            defaults.setProperty("autoKey", Integer.toString(Input.Keys.F10));
            defaults.setProperty("brightEyeMode", "false"); // beyond-SL display hints are opt-in; no policy reads them
            defaults.setProperty("bossFastFinish", "false"); // new key: every player starts with the standard Boss search
            defaults.setProperty("feedMode", "false");
            defaults.setProperty("highSpeed", "false");
            defaults.setProperty("streakMode", "false");
            defaults.setProperty("agentBridge", "false");
            defaults.setProperty("smokeProbeBudget", "12000");
            defaults.setProperty("smokeLossThreshold", "15");
            defaults.setProperty("searchWorkers", "0");
            defaults.setProperty("testMode", "false");
            defaults.setProperty("routeMarks", "true");
            defaults.setProperty("autoPotionRewards", "true");
            defaults.setProperty("runAutoEnabled", "true");
            defaults.setProperty("runLog", "true");
            config = new SpireConfig("STS1CombatSolver", "config", defaults);
            I18n.setLanguage(config.getString("language"));
            canvas.uiScale = PanelSize.preference(config.getString("uiScale"));
            status = I18n.t("等待进入战斗");
            backendPhase = I18n.t("等待后台接收");
            autoKey = config.getInt("autoKey");
            Foresight.brightEyeMode = config.getBool("brightEyeMode");
            bossFastFinishEnabled = config.getBool("bossFastFinish");
            feedModeEnabled = config.getBool("feedMode");
            highSpeed = config.getBool("highSpeed");
            streakMode = config.getBool("streakMode") && !highSpeed;
            agentBridge = config.getBool("agentBridge");
            smokeProbeBudget = Math.max(1,Math.min(128000,config.getInt("smokeProbeBudget")));
            smokeLossThreshold = Math.max(0,config.getInt("smokeLossThreshold"));
            try { searchWorkers = Math.max(0, Math.min(32, config.getInt("searchWorkers"))); }
            catch (NumberFormatException invalid) { searchWorkers = 0; }
            config.setInt("searchWorkers", searchWorkers);
            config.save();
            TestRecorder.enabled = config.getBool("testMode");
            routeMarks = config.getBool("routeMarks");
            autoPotionRewards = config.getBool("autoPotionRewards");
            runAutoEnabled = config.getBool("runAutoEnabled");
            backend.setRunLog(config.getBool("runLog"));
            if (!validAutoKey(autoKey)) autoKey = Input.Keys.F10;
        } catch (IOException failure) { error = I18n.t("快捷键配置读取失败：") + failure.getMessage(); }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            exports.shutdown();
            backend.close();
        }, "sts1-solver-shutdown"));
    }

    @Override public void receivePostInitialize() {
        canvas.font();
        registerConfigPanel();
        TestRecorder.subscribe();
        ShopRecorder.subscribe();
        Thread warmup = new Thread(() -> {
            List<String> rngMods = RngMods.loaded();
            if (!rngMods.isEmpty()) {
                rngWarning = I18n.t("这些 Mod 会影响随机数（RNG），求解器计算可能不准确，建议关闭：") + String.join(" / ", rngMods);
                System.err.println("[STS1Solver] RNG-affecting mods: " + rngMods);
                if (exports.message.isEmpty()) { exports.message = rngWarning; exports.messageError = true; }
            }
            String fast = superFastNote();
            if (superFastSpeed() > SUPER_FAST_TESTED && exports.message.isEmpty()) { exports.message = fast; exports.messageError = true; }
            try { backend.start(); } catch (IOException failure) { System.err.println("[STS1Solver] " + failure); }
        }, "sts1-solver-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    private void autoFold() {
        AbstractRoom room = CommandExecutor.isInDungeon() ? AbstractDungeon.getCurrRoom() : null;
        AbstractDungeon.CurrentScreen screen = room == null ? null : AbstractDungeon.screen;
        boolean event = room != null && room.phase == AbstractRoom.RoomPhase.EVENT && room.event != null;
        boolean fold = !expandOnScreen(screen, room != null && combat(), event)
            || (pilot.mode == 2 && !combat()); // the pilot's status fits the folded header.
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

    // The export button stays in the header in every panel state (F6 does the same).
    private float exportLeft(float w) { return collapsed ? w-108 : w-196; }
    private float exportWidth() { return collapsed ? 60 : 72; }

    private float panelWidth() { return pilot.cardShown() ? OutsidePilot.PILOT_WIDTH : collapsed ? 300 : PANEL_WIDTH; }
    private float panelHeight() { return pilot.cardShown() ? pilot.cardHeight() : collapsed ? 48 : PANEL_HEIGHT; }

    private boolean validAutoKey(int key) {
        return key >= Input.Keys.F1 && key <= Input.Keys.F12 && key != Input.Keys.F6 && key != Input.Keys.F7 && key != Input.Keys.F8 && key != Input.Keys.F9;
    }

    private void toggleAuto() {
        decisionDirty=true;
        if (!combat() || !supported()) return;
        if (auto || turnOnly) { heldThisFight = true; auto = turnOnly = false; status = I18n.t("自动已关闭 · 保留建议"); return; }
        heldThisFight = false;
        auto = true;
        turnOnly = false;
        paused = false;
        error = "";
        if (!busy && result == null && !awaitingAction) lastKey = null;
        status = I18n.t("自动执行已开启 · 仅本场战斗");
        if (result != null && routeChoice(result, !fightAuto()) && !branchConfirmed) { runAfterBranch = true; requestBranches(); }
    }

    private void requestBranches() {
        if (fightAuto() && !paused && result != null && !busy) {
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
            auto=fightAuto() || (autoAfterBranch(manual) && (RunMode.combatAutomatic(mode()) || runAfterBranch)); // Manual steps unless F10 asked for the run
            runAfterBranch=false;
            if(manual)thiefPreference=branch;
            paused=false;
            send("choose",state);
        } catch(Exception failure){fail(failure.toString());}
    }

    static boolean routeConfirmed(JsonObject result, boolean branchConfirmed) {
        return result != null && (!result.has("manual_choice")
            || !result.get("manual_choice").getAsBoolean() || branchConfirmed);
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
        com.megacrit.cardcrawl.map.MapRoomNode currNode=AbstractDungeon.getCurrMapNode();
        out.addProperty("x",currNode==null?0:currNode.x);
        out.addProperty("y",currNode==null?-1:currNode.y);
        out.addProperty("boss",AbstractDungeon.bossKey==null?"":AbstractDungeon.bossKey);
        com.megacrit.cardcrawl.rooms.AbstractRoom room=AbstractDungeon.getCurrRoom();
        out.addProperty("room",room==null?"":room.getClass().getSimpleName());
        out.addProperty("boss_candidates_remaining",AbstractDungeon.bossList==null?0:AbstractDungeon.bossList.size());
        boolean bossHeal=bossFastFinish(room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss,
            AbstractDungeon.actNum,AbstractDungeon.ascensionLevel,
            AbstractDungeon.bossList==null?0:AbstractDungeon.bossList.size(),
            AbstractDungeon.player!=null && AbstractDungeon.player.hasRelic("Mark of the Bloom"));
        // The next act heals after this Boss (75% of missing HP from A5); auto_policy discounts HP loss for it.
        out.addProperty("boss_heal",bossHeal);
        out.addProperty("boss_fast_finish",bossFastFinishEnabled && bossHeal);
        out.addProperty("feed_mode",feedModeEnabled);
        out.addProperty("high_speed",highSpeed);
        out.addProperty("streak_mode",streakMode);
        out.addProperty("run_auto",fightAuto());
        out.addProperty("smoke_probe_budget",smokeProbeBudget);
        out.addProperty("smoke_loss_threshold",smokeLossThreshold);
        out.addProperty("smoke_opening",smokeOpening(GameActionManager.turn,
            AbstractDungeon.actionManager.cardsPlayedThisCombat.size(),
            GameActionManager.damageReceivedThisCombat,GameActionManager.hpLossThisCombat));
        float chance=Math.max(0,Math.min(1,(40+AbstractRoom.blizzardPotionMod)/100f));
        if(AbstractDungeon.player.hasRelic("White Beast Statue"))chance=1;
        boolean rewardBlocked=room==null || room.rewards.size()>=4 || room.smoked
            || (room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss
                && (AbstractDungeon.actNum>=3) && !Settings.isEndless);
        if(AbstractDungeon.player.hasRelic("Sozu") || rewardBlocked)chance=0;
        out.addProperty("potion_reward_blocked",rewardBlocked);
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

    /** Solver policy only; Java itself also permits later escapes. Never erase real damage or card history. */
    static boolean smokeOpening(int turn,int played,int received,int hpLoss) {
        return turn==1 && played==0 && received==0 && hpLoss==0;
    }

    /** The Settings switch gates the rule; off means a Boss gets the standard budget and the full search like any fight. */
    static boolean bossFastFinish(boolean enabled,boolean bossRoom,int act,int ascension,int remainingBosses,boolean noHealing) {
        return enabled && bossFastFinish(bossRoom,act,ascension,remainingBosses,noHealing);
    }

    static boolean bossFastFinish(boolean bossRoom,int act,int ascension,int remainingBosses,boolean noHealing) {
        return bossRoom && act>=1 && act<=3 && !noHealing
            // Beyond starts with three candidates, removing the current one on
            // entry: two remain at boss #1, one unused candidate at boss #2.
            && !(act==3 && ascension>=20 && remainingBosses>1);
    }

    private RunMode mode() { return RunMode.of(pilot.mode,runAuto); }

    /** Combat automation drives this fight: on for the run and not taken over for this fight. */
    private boolean fightAuto() { return runAuto && !heldThisFight; }

    /** The player picks a route first: the backend asks, or Manual has more than one usable route. */
    static boolean routeChoice(JsonObject result, boolean manual) {
        if (result.get("needs_confirmation").getAsBoolean()) return true;
        if (!manual || !result.has("branches")) return false;
        int usable = 0;
        for (JsonElement branch : result.getAsJsonArray("branches"))
            if (branch.getAsJsonObject().get("available").getAsBoolean()) usable++;
        return usable > 1;
    }

    static boolean inSupportedRun() {
        return CommandExecutor.isInDungeon() && AbstractDungeon.player!=null && supported();
    }

    private void toggleCombatMode() {
        RunMode before=mode();
        if(RunMode.combatButton(before,auto)==RunMode.MANUAL) {
            if(runAuto){runAutoEnabled=false;saveAutomation();}
            cancel(true); // also ends the automatic run of this fight alone
            if(before==RunMode.COMBAT_AUTO && inSupportedRun())chooseOutsideMode(1);
            return;
        }
        if(pilot.mode!=0)chooseOutsideMode(0);
        runAuto=false; // Switching from the pilot or step mode starts combat-only automation.
        toggleRunAuto();
    }

    private void togglePilotMode() {
        if(RunMode.pilotButton(mode())==RunMode.MANUAL)stopAll();
        else chooseOutsideMode(2);
    }

    private void toggleRunAuto() {
        if(runAuto){runAutoEnabled=false;saveAutomation();cancel(true);return;}
        if(!CommandExecutor.isInDungeon() || AbstractDungeon.player==null || !supported())return;
        runAutoEnabled=true;saveAutomation();
        runAuto=true;runAutoPlayer=AbstractDungeon.player;heldThisFight=false;
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

    /** F9 / Stop: back to Manual, where the solver and the network suggest and every step waits for the player. */
    private void stopAll() {
        pilot.stop(inSupportedRun());
        autoPotionRewards=false;
        cancel(true);
    }

    private void chooseOutsideMode(int mode) {
        pilot.select(mode);
        if(mode==0)return;
        if(mode==1) { runAuto=false; auto=turnOnly=false; decisionDirty=true; return; } // fights wait for the player too
        runAuto=true; runAutoPlayer=AbstractDungeon.player; heldThisFight=false;
        auto=combat(); paused=false; decisionDirty=true;
    }

    /** Step mode for outside choices, which the agent confirms, plus automatic fights. F9 hands both back to the player. */
    private void startAgentMode() {
        chooseOutsideMode(1);
        runAuto=true; runAutoPlayer=AbstractDungeon.player; heldThisFight=false;
        auto=combat(); paused=false; decisionDirty=true;
        pilot.notice=I18n.t("Agent 模式：局外由 agent 决定，战斗自动执行 · F9 接管");
    }

    private void syncAgent() {
        try {
            if(agent.sync(pilot,runAuto,rngWarning,this::startAgentMode) && error.startsWith(I18n.t("Agent 通道读写失败：")))error="";
        } catch(IOException failure) {
            // Retried next frame: on Windows the replace fails while the agent holds the file open.
            error=I18n.t("Agent 通道读写失败：")+failure.getMessage();
        }
    }

    /** What the outside pilot needs from the combat automation. */
    private final OutsidePilot.Host pilotHost = new OutsidePilot.Host() {
        @Override public JsonObject capture() { return SolverMod.this.capture(); }
        @Override public void record(String message) {
            try { backend.start(); diagnostic("outside",message,null); }
            catch(IOException ignored) { }
        }
        @Override public void runStarted() { if(agentBridge)startAgentMode(); }
        @Override public void failed() { runAuto=false; auto=false; collapsed=false; }
    };

    private void chooseAutomaticBranch() {
        if(!ready())return;
        if(smokeOpening(GameActionManager.turn,AbstractDungeon.actionManager.cardsPlayedThisCombat.size(),
                GameActionManager.damageReceivedThisCombat,GameActionManager.hpLossThisCombat)
                && (!result.has("smoke_policy") || !result.get("smoke_policy").getAsBoolean())) {
            for(com.megacrit.cardcrawl.potions.AbstractPotion potion:AbstractDungeon.player.potions)
                if("SmokeBomb".equals(potion.ID) && potion.canUse()) {recalculate();return;}
        }
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
            // Toolbox and Gambling Chip pick before monsters show intents (DEBUG); the intent can appear while the
            // picker is still open. The move id and powers stay in the key, so the route is still checked.
            if (game.has("screen_type") && !"NONE".equals(game.get("screen_type").getAsString())
                    && c.has("monsters"))
                for (JsonElement monster : c.getAsJsonArray("monsters")) {
                    monster.getAsJsonObject().remove("intent");
                    monster.getAsJsonObject().remove("move_adjusted_damage");
                }
        }
        // F9 and the automation buttons flip run_auto mid-fight; the route found so far stays valid.
        JsonObject context = game.getAsJsonObject("solver_auto_context");
        if (context != null) context.remove("run_auto");
        // Pickers and pending UseCardAction export the same cards again. Hover
        // previews must not invalidate a route through one of those copies.
        stripCardPreviews(game);
        JsonObject key = new JsonObject();
        key.add("game", game);
        key.add("commands", state.get("available_commands"));
        return key;
    }

    private static void stripCardPreviews(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("uuid") && object.has("id") && object.has("cost")) {
                object.remove("damage"); object.remove("block"); object.remove("magic_number");
            }
            for (Map.Entry<String,JsonElement> entry : object.entrySet()) stripCardPreviews(entry.getValue());
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) stripCardPreviews(item);
        }
    }

    private void send(String op, JsonObject state) throws IOException {
        backend.start();
        JsonObject request = new JsonObject();
        request.addProperty("id", ++serial);
        request.addProperty("op", op);
        request.addProperty("budget", BUDGET);
        request.addProperty("search_workers", searchWorkers);
        if (!op.equals("cancel")) searchTimeoutMillis = SearchStatus.timeoutMillis(BUDGET);
        if (op.equals("choose")) request.addProperty("branch", chosenBranch);
        if (op.equals("solve")) request.addProperty("progress", true);
        if (!op.equals("cancel")) {
            progressRows = null;
            backendPhase = I18n.t("等待后台接收");
            phaseAt = lastProgressAt = System.currentTimeMillis();
        }
        if (state != null) request.add("state", state);
        backend.write(request);
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
        if(!backend.started())return;
        try {
            if(state==null && combat())state=capture();
            JsonObject record=new JsonObject();
            record.addProperty("op","diagnostic");
            record.addProperty("id",activeId);
            record.addProperty("event",event);
            if(AbstractDungeon.player!=null){record.addProperty("seed",Long.toString(Settings.seed));record.addProperty("floor",AbstractDungeon.floorNum);}
            record.addProperty("message",message);
            record.addProperty("auto",auto);
            record.addProperty("turn_only",turnOnly);
            record.addProperty("awaiting_action",awaitingAction);
            record.addProperty("executed_command",executedCommand);
            if(state!=null)record.add("state",state);
            if(rootKey!=null)record.add("expected_key",rootKey);
            if(result!=null)record.add("previous_result",result);
            backend.write(record);
        } catch(Exception failure) {
            System.err.println(I18n.t("[STS1Solver] 诊断写入失败：")+failure);
        }
    }

    private void cancel(boolean pause) {
        if(pause)runAuto=false;
        rewardPotionPending=null;
        boolean pendingSearch = pause && busy && !awaitingAction;
        boolean keepResult = pause && result != null && !awaitingAction;
        try { if (backend.alive() && (busy || awaitingAction)) send("cancel", null); }
        catch (IOException failure) { error = failure.getMessage(); }
        if (!pendingSearch) { ++serial; activeId = serial; }
        if (!pendingSearch) clickedBranch = null;
        busy = pendingSearch;
        awaitingAction = auto = turnOnly = gamblingAuto = runAfterBranch = false;
        if (!keepResult) { branchPicker = branchConfirmed = false; branchInput = ""; result = null; }
        decisionDirty = true;
        paused = pause;
        status = pendingSearch ? I18n.t("正在停止搜索 · 保留已找到路线")
            : pause ? (keepResult ? I18n.t("已停止 · 点击路线打完本场，回车走一步") : I18n.t("已停止 · 点击重新计算恢复")) : I18n.t("等待稳定局面");
        if (keepResult && result.has("branches") && result.getAsJsonArray("branches").size()>1) {
            branchPicker=true;
            status=I18n.t("已停止 · 点击路线或输入编号后回车");
        }
    }

    private void fail(String message) {
        if(pilot.mode!=0)pilot.pause(message);
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

    private void toggleRunLog() {
        boolean runLog = !backend.runLog();
        try { if (config != null) { config.setBool("runLog", runLog); config.save(); } }
        catch (IOException failure) { exports.message = I18n.t("设置保存失败：") + failure.getMessage(); }
        backend.setRunLog(runLog);
        exports.message = I18n.t(runLog ? "持续记录已开启" : "持续记录已关闭");
    }

    /** A bug in the solver must never close the game: record the stack and keep the panel alive. */
    private void guard(String where, Throwable failure) {
        String message = exports.crash(where, failure);
        if (message != null) error = message;
    }

    @Override public void receivePostUpdate() {
        try { update(); } catch (Throwable failure) { guard("update", failure); }
    }

    private void update() {
        layout();
        if (exports.research) { String failure = exports.observeInventory(); if (failure != null) error = failure; }
        exports.exportReplies(backend);
        if (Gdx.input.isKeyJustPressed(Input.Keys.F6)) {
            if (exports.research && !Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) && !Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) {
                String failure = exports.toggleVoiceNote();
                if (failure != null) error = failure;
            } else exports.exportRun(backend);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F7)) exports.exportMap(pilot.routePlan);
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
            runAuto=runAutoEnabled || pilot.mode==2; // the pilot keeps combat automation across acts.
            autoPotionRewards=config==null || config.getBool("autoPotionRewards");
        }
        if(AbstractDungeon.player!=null && AbstractDungeon.player.isDead)runAuto=false;
        if (Gdx.input.isKeyJustPressed(Input.Keys.F9)) stopAll();
        boolean inCombat = combat();
        if(!wasCombat && inCombat) heldThisFight=false;
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
        if(!inCombat && pilot.mode!=0)updatePotionRewards();
        pilot.update(pilotHost, rewardPotionPending!=null);
        if(agentBridge)syncAgent();
        pilot.updateRoute(pilotHost);
        deckWatch.update(pilot.pilotRun);
        if (!inCombat) {
            status = pilot.prompt || pilot.mode!=0 || !pilot.notice.isEmpty() ? pilot.notice
                : runAuto ? I18n.t("战斗全自动已开 · 等待下场；其他选择由你决定") : I18n.t("选牌与路线由你决定");
            if(pilot.mode==0)updatePotionRewards();
            return;
        }
        finishGamblingSelection(AbstractDungeon.screen);
        if (!supported()) {
            if (busy || auto || result != null) cancel(true);
            status = I18n.t("战斗引擎目前支持") + I18n.t(SUPPORTED_NAMES) + I18n.t("；预测仍可用");
            return;
        }
        JsonObject reply;
        while ((reply = backend.reply()) != null) {
            if (reply.get("id").getAsLong() != activeId) continue;
            String kind = reply.get("status").getAsString();
            if (kind.equals("progress")) {
                if (reply.has("timeout_seconds"))
                    searchTimeoutMillis = SearchStatus.acknowledgedTimeoutMillis(reply.get("timeout_seconds").getAsLong());
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
                    ? I18n.t("已停止 · 点击路线打完本场，回车走一步") : I18n.t("路线已就绪 · 等待你的操作");
                if(reply.has("boss_fast_finish") && reply.get("boss_fast_finish").getAsBoolean())
                    status=I18n.t("Boss 已有合格无药胜线 · 固定路线，结束搜索");
                if(fightAuto() && !paused && !branchConfirmed) {
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
                } else if (routeChoice(reply, !fightAuto()) && !branchConfirmed) requestBranches();
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
        if (busy && (!backend.alive()
                || SearchStatus.expired(now, sentAt, searchTimeoutMillis))) {
            fail(I18n.t("后台求解器未响应，请重新计算。详情见数据目录中的 backend.log。"));
            return;
        }
        // Opening the map/settings/piles suspends game actions. Resume the wait
        // after closing the screen instead of charging its open time as a timeout.
        if(awaitingAction && actionPausedByScreen(AbstractDungeon.screen)) {
            sentAt=now;
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
                if(fightAuto() && !branchConfirmed){chooseAutomaticBranch();return;}
                if (routeChoice(result, !fightAuto()) && !branchConfirmed) {requestBranches();return;}
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
            JsonArray offers=game.getAsJsonObject("screen_state").getAsJsonArray("rewards");
            // Let the pilot resolve relics/linked keys before automatic potion pickup.
            if(pilot.mode!=0 && offers!=null)for(JsonElement offer:offers) {
                String type=PotionRewards.string(offer.getAsJsonObject(),"reward_type");
                if(type.equals("RELIC") || type.equals("SAPPHIRE_KEY") || type.equals("EMERALD_KEY"))return;
            }
            if(useFruitJuice())return;
            boolean hasPotion=false;
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
        com.megacrit.cardcrawl.helpers.FontHelper.renderFontLeftTopAligned(sb,canvas.font(),label,
            Settings.WIDTH*.20f,Settings.HEIGHT*.90f,ACCENT);
    }

    private void changeScale(float value) {
        canvas.uiScale = PanelSize.preference(Float.toString(value));
        layout();
        try { if (config != null) { config.setString("uiScale", Float.toString(canvas.uiScale)); config.save(); } }
        catch (IOException failure) { error = I18n.t("界面缩放保存失败：") + failure.getMessage(); }
    }
    private void layout() { canvas.layout(panelWidth(), panelHeight()); }

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
        // Manual outside combat: Enter runs the suggested step.
        if(pilot.mode==1 && !combat() && pilot.packet!=null && pilot.scores!=null && Gdx.input.isKeyJustPressed(Input.Keys.ENTER))
            pilot.confirm=true;
        boolean held=Gdx.input.isKeyPressed(Input.Keys.ENTER);
        for(int n=0;n<=9;n++) held |= Gdx.input.isKeyPressed(Input.Keys.NUM_0+n)
                || Gdx.input.isKeyPressed(Input.Keys.NUMPAD_0+n);
        // Keep native card hotkeys suppressed until the selecting key is released.
        captureBranchKeys=branchPicker || (captureBranchKeys && held);
        // Enter runs one step in Manual, otherwise resumes this combat's automation. The key is
        // withheld from the game until released; potion choices keep their own Enter.
        if(!branchPicker && !auto && !turnOnly && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)
                && supported() && ready()) {
            if(RunMode.combatAutomatic(mode())) toggleAuto();
            else executeStep(); // one step per Enter; F10 or Execute turn runs more
            captureBranchKeys=true;
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
        float s=canvas.scale(), mx=InputHelper.mX, my=InputHelper.mY;
        boolean over=PanelCanvas.inside(mx,my,canvas.x,canvas.y-panelHeight()*s,panelWidth()*s,panelHeight()*s);
        boolean click=InputHelper.justClickedLeft;
        if(pilot.cardShown()) {
            canvas.drag(mx,my); layout();
            if(over) {
                pilotInput((mx-canvas.x)/s,(canvas.y-my)/s,click,mx,my);
                InputHelper.justClickedLeft=false; InputHelper.justReleasedClickLeft=false;
                InputHelper.justClickedRight=false; InputHelper.justReleasedClickRight=false;
                InputHelper.scrolledDown=InputHelper.scrolledUp=false;
            }
            canvas.hovered=over || canvas.dragging; return;
        }
        canvas.drag(mx,my); layout();
        if (over && click) {
            float lx=(mx-canvas.x)/s, ly=(canvas.y-my)/s, w=panelWidth();
            boolean body=!collapsed && !settingsOpen, list=body && lx>=ROUTE_LEFT && ly>=BODY_TOP && ly<BODY_TOP+BODY_ROWS*BODY_ROW;
            int row=(int)((ly-BODY_TOP)/BODY_ROW);
            if (ly < 42) {
                if (lx >= exportLeft(w) && lx < exportLeft(w)+exportWidth()) exports.exportRun(backend);
                else if (!collapsed && lx >= w-118 && lx < w-54) settingsOpen=!settingsOpen;
                else if (lx > w-47) { collapsed=!collapsed; autoFolded=false; }
                else canvas.startDrag(mx,my);
            } else if (!collapsed && settingsOpen && ly>=SETTINGS_TOP && ly<SETTINGS_TOP+4*SETTINGS_PITCH && (ly-SETTINGS_TOP)%SETTINGS_PITCH<30) {
                float cw=(w-40)/2, off=lx-(24+cw);
                int setting=(int)((ly-SETTINGS_TOP)/SETTINGS_PITCH);
                if (setting==0 && off<0) toggleBrightEye();
                else if (setting==0) changeScale(off<cw/3 ? canvas.uiScale-.1f : off>=cw*2/3 ? canvas.uiScale+.1f : 1);
                else if (setting==1) { if (off<0) toggleBossFinish(); else toggleLanguage(); }
                else if (setting==2) { if (off<0) toggleRunLog(); else exports.exportRun(backend); }
                else if (off<0) bindingKey=true;
            } else if (body && previewCount > 0 && lx >= ROUTE_LEFT && ly >= 42 && ly < 72) {
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
            } else if (list && transformPreview != null) {
                transformPreview.pick(scroll + row);
                scroll = 0;
            } else if (list && branchPicker && result!=null) {
                int index=scroll+row;
                JsonArray branches=result.getAsJsonArray("branches");
                if(index<branches.size()) {
                    JsonObject branch=branches.get(index).getAsJsonObject();
                    if(branch.get("available").getAsBoolean())chooseBranch(branch.get("id").getAsString());
                }
            } else if (list && busy && progressRows!=null) {
                if(row<Math.min(progressRows.size(),BODY_ROWS)) {
                    JsonObject progress=progressRows.get(row).getAsJsonObject();
                    if(progress.has("ready") && progress.get("ready").getAsBoolean() && progress.has("id")) {
                        boolean keepAuto=runAuto; // picking a finished route is not a Stop
                        clickedBranch=progress.get("id").getAsString();
                        cancel(true);
                        runAuto=keepAuto;
                    }
                }
            } else if (list && result!=null) {
                if (!auto && !turnOnly) toggleAuto(); // the whole route; Enter is one step in Manual
            } else if (!collapsed && lx >= 16 && lx < w-16 && ly >= ACTIONS_TOP && ly <= ACTIONS_TOP+36
                    && (lx-16)%ACTION_SPACING < ACTION_SPACING-8) {
                int action=(int)((lx-16)/ACTION_SPACING);
                if (action==0) recalculate();
                else if (action==1) { heldThisFight=true; auto=turnOnly=false; executeStep(); }
                else if (action==2) {
                    if (result != null && ready()) { startingTurn=AbstractDungeon.actionManager.turn; turnOnly=true; heldThisFight=true; auto=false; }
                } else if (action==3) toggleCombatMode();
                else if (action==4) togglePilotMode();
                else stopAll();
            } else if (!collapsed && lx >= 16 && lx < w-16 && ly >= TOGGLES_TOP && ly <= TOGGLES_TOP+28) {
                float spacing=(w-24)/(canCreditRest()?3:2);
                if ((lx-16)%spacing < spacing-8) {
                    int toggle=(int)((lx-16)/spacing);
                    if (toggle==0) {
                        boolean fast=highSpeed;   // standard -> high speed -> streak -> standard
                        highSpeed=!highSpeed && !streakMode; streakMode=fast;
                        saveSetting("highSpeed", highSpeed); saveSetting("streakMode", streakMode); recalculate();
                        highSpeedWarnedAt=highSpeed || streakMode?System.currentTimeMillis():0;
                    }
                    else if (toggle==1) togglePotionRewards();
                    else { nextRest=!nextRest; recalculate(); }
                }
            }
        }
        canvas.hovered = over || canvas.dragging;
        if (over || canvas.dragging) {
            float lx=(mx-canvas.x)/s, ly=(canvas.y-my)/s;
            if (!collapsed && !settingsOpen && lx >= ROUTE_LEFT && ly >= BODY_TOP && ly < BODY_TOP+BODY_ROWS*BODY_ROW) {
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

    private void toggleBrightEye() {
        Foresight.brightEyeMode = !Foresight.brightEyeMode;
        scroll = 0;
        try { if(config!=null) { config.setBool("brightEyeMode",Foresight.brightEyeMode); config.save(); } }
        catch(IOException failure) { error=I18n.t("模式保存失败：")+failure.getMessage(); }
    }

    static boolean solverDriving() { return instance != null && (instance.auto || instance.turnOnly || instance.runAuto); }
    static Path desktop() { return instance != null ? RunExports.desktopDirectory() : Paths.get(System.getProperty("user.home"), "Desktop"); }

    private void saveSetting(String key, boolean value) {
        try { if (config != null) { config.setBool(key, value); config.save(); } }
        catch (IOException failure) { error = I18n.t("模式保存失败：") + failure.getMessage(); }
    }

    /** Mods menu > Config: the switches that change what the solver plans, records or draws. */
    private void registerConfigPanel() {
        basemod.ModPanel panel = new basemod.ModPanel();
        float x = 380f, y = 700f, step = 70f;
        Object[][] rows = {
            {I18n.t("Boss 快速收尾：找到不交药的胜利路线就停止搜索"), bossFastFinishEnabled, (java.util.function.Consumer<Boolean>) v -> { bossFastFinishEnabled = v; saveSetting("bossFastFinish", v); }},
            {I18n.t("狂宴模式：狂宴得到的生命上限按两倍计价"), feedModeEnabled, (java.util.function.Consumer<Boolean>) v -> { feedModeEnabled = v; saveSetting("feedMode", v); }},
            {I18n.t("测试模式：把本局每张出牌写到桌面 sts1-test-种子.jsonl（只保留最近一局）"), TestRecorder.enabled, (java.util.function.Consumer<Boolean>) v -> { TestRecorder.enabled = v; saveSetting("testMode", v); }},
            {I18n.t("路线标注：在地图上画出规划路线"), routeMarks, (java.util.function.Consumer<Boolean>) v -> { routeMarks = v; saveSetting("routeMarks", v); }},
        };
        for (int i = 0; i < rows.length; i++) {
            @SuppressWarnings("unchecked") java.util.function.Consumer<Boolean> set = (java.util.function.Consumer<Boolean>) rows[i][2];
            panel.addUIElement(new basemod.ModLabeledToggleButton((String) rows[i][0], x, y - i * step, Settings.CREAM_COLOR,
                font(), (Boolean) rows[i][1], panel, label -> { }, button -> set.accept(button.enabled)));
        }
        BaseMod.registerModBadge(ImageMaster.loadImage("sts1solver/badge.png"), "STS1 Combat Solver", "CYZ",
            I18n.t("战斗求解器设置"), panel);
    }

    private void toggleBossFinish() {
        bossFastFinishEnabled = !bossFastFinishEnabled;
        try { if(config!=null) { config.setBool("bossFastFinish",bossFastFinishEnabled); config.save(); } }
        catch(IOException failure) { error=I18n.t("模式保存失败：")+failure.getMessage(); }
    }

    private void toggleLanguage() {
        I18n.setLanguage("en".equals(I18n.language()) ? "zh" : "en");
        scroll = 0;
        status = I18n.t(busy ? "正在搜索整场战斗…" : result != null ? "路线已就绪 · 等待你的操作" : "等待稳定局面");
        try {
            if (config != null) { config.setString("language", I18n.language()); config.save(); }
        } catch (IOException failure) { error = "Language save failed: " + failure.getMessage(); }
    }

    BitmapFont font() { return canvas.font(); }
    private static String count(long n) { return n>=10000 ? String.format(I18n.t("%.1f 万"), n/10000.0) : Long.toString(n); }
    private String progressSummary() {
        long total=0, transitions=0; int turn=0, best=-1;
        for (JsonElement e : progressRows) {
            JsonObject row=e.getAsJsonObject();
            total+=row.get("simulations").getAsLong();
            if(row.has("transitions"))transitions+=row.get("transitions").getAsLong();
            turn=Math.max(turn,row.get("turn").getAsInt());
            best=Math.max(best,row.get("best_hp").getAsInt());
        }
        return I18n.t("本轮模拟 ")+count(total)+I18n.t("次 · 循环展开 ")+count(transitions)+I18n.t("步");
    }
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
            if (p[0].equals("choose")) {
                int index = Integer.parseInt(p[1]);
                if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID)
                    return I18n.t("选择：") + communicationmod.ChoiceScreenUtils.getGridScreenCards().get(index).name;
                return I18n.t("战斗选牌：第 ")+(index+1)+I18n.t(" 张");
            }
            if (p[0].equals("confirm")) return I18n.t("确认战斗选牌");
            if (p[0].equals("skip")) return I18n.t("跳过战斗选牌");
        } catch (RuntimeException ignored) { return I18n.t("局面已变化，等待重新计算"); }
        return command;
    }

    @Override public void receivePostRender(SpriteBatch sb) {
        try { renderPanel(sb); } catch (Throwable failure) { guard("render", failure); }
    }

    private void renderPanel(SpriteBatch sb) {
        Foresight.renderRubyReminder(sb);
        renderPotionRewardAdvice(sb);
        if(routeMarks)pilot.renderRoute(sb,canvas);
        deckWatch.render(sb,canvas);
        layout();
        if(pilot.cardShown()) { pilot.renderCard(sb,canvas); canvas.cursorOnTop(sb); return; }
        float w=panelWidth();
        canvas.box(sb,0,0,w,panelHeight(),BORDER);
        canvas.box(sb,1,1,w-2,panelHeight()-2,BACKGROUND);
        canvas.label(sb,I18n.t("战斗求解器"),16,collapsed?7:10,exportLeft(w)-24,collapsed?.95f:1.1f,Color.WHITE);
        canvas.label(sb,collapsed?"＋":"－",w-36,collapsed?14:10,26,1,ACCENT);
        canvas.button(sb,exportLeft(w),collapsed?6:8,exportWidth(),collapsed?22:28,SURFACE);
        canvas.label(sb,I18n.t("导出"),exportLeft(w)+8,collapsed?8:12,exportWidth()-12,collapsed?.72f:.85f,ACCENT);
        if (collapsed) {
            canvas.label(sb,error.isEmpty()?status:I18n.backend(error),20,29,240,.65f,error.isEmpty()?MUTED:DANGER);
            sb.setColor(Color.WHITE); canvas.cursorOnTop(sb); return;
        }
        canvas.button(sb,w-118,8,64,28,settingsOpen?ACCENT:SURFACE);
        canvas.label(sb,I18n.t(settingsOpen?"返回":"设置"),w-108,12,48,.85f,settingsOpen?BACKGROUND:ACCENT);
        String revivalWarning = result == null ? "" : revivalWarning(result);
        if (revivalWarning.isEmpty() && (highSpeed || streakMode) && System.currentTimeMillis()-highSpeedWarnedAt < 8000)
            revivalWarning = I18n.t(highSpeed?"高速模式：搜索量 1/20，胜率和省血下降":"连胜模式：前三幕不降预算，搜索更慢");
        canvas.label(sb,revivalWarning.isEmpty()?status:revivalWarning,16,48,ROUTE_LEFT-28,.85f,
            revivalWarning.isEmpty() && error.isEmpty()?MUTED:DANGER);
        String note=exports.note();
        if (settingsOpen) renderSettings(sb,w,note);
        else renderCombatBody(sb,w,note);
        String[] labels={I18n.t("重新计算"),I18n.t("执行一步"),turnOnly?I18n.t("正在执行本回合"):I18n.t("执行本回合"),
            I18n.t(pilot.mode!=2 && (runAuto || auto)?"战斗全自动：开":"战斗全自动"),
            I18n.t(pilot.mode==2?"AI全自动：开":"AI全自动"),I18n.t("停止")};
        for(int i=0;i<labels.length;i++) {
            canvas.button(sb,16+i*ACTION_SPACING,ACTIONS_TOP,ACTION_SPACING-8,36,i==0?ACCENT:SURFACE);
            canvas.label(sb,labels[i],26+i*ACTION_SPACING,ACTIONS_TOP+8,ACTION_SPACING-28,.8f,
                i==0?BACKGROUND:i==5?DANGER:i==4&&pilot.mode==2?ACCENT
                :i==3&&pilot.mode!=2&&(runAuto||auto)?ACCENT:i==2&&result==null?MUTED:Color.WHITE);
        }
        List<String> toggles=new ArrayList<>(Arrays.asList(
            I18n.t("搜索：")+I18n.t(highSpeed?"高速":streakMode?"连胜":"标准"),
            I18n.t("自动换药：")+I18n.t(autoPotionRewards?"开":"关")));
        if(canCreditRest())toggles.add(I18n.t("羽毛回血：")+I18n.t(nextRest?"是":"仅必经火堆"));
        float spacing=(w-24)/toggles.size();
        for(int i=0;i<toggles.size();i++) {
            canvas.button(sb,16+i*spacing,TOGGLES_TOP,spacing-8,28,SURFACE);
            canvas.label(sb,toggles.get(i),24+i*spacing,TOGGLES_TOP+5,spacing-24,.8f,
                i==0?(highSpeed?DANGER:streakMode?ACCENT:MUTED):i==1?(autoPotionRewards?ACCENT:MUTED):ACCENT);
        }
        sb.setColor(Color.WHITE);
        canvas.cursorOnTop(sb);
    }

    private void renderSettings(SpriteBatch sb, float w, String note) {
        float cw=(w-40)/2, right=24+cw;
        for(int row=0;row<4;row++) for(int col=0;col<2;col++) {
            if(row==3 && col==1) canvas.box(sb,16+col*(cw+8),SETTINGS_TOP+row*SETTINGS_PITCH,cw,30,SURFACE); // SuperFastMode: display only
            else canvas.button(sb,16+col*(cw+8),SETTINGS_TOP+row*SETTINGS_PITCH,cw,30,SURFACE);
        }
        float t=SETTINGS_TOP+6, p=SETTINGS_PITCH;
        canvas.label(sb,I18n.t("辉眼模式：")+I18n.t(Foresight.brightEyeMode?"开":"关"),26,t,cw-20,.85f,ACCENT);
        canvas.label(sb,"−",right+12,t-1,24,1,ACCENT);
        canvas.label(sb,Math.round(canvas.uiScale*100)+"%",right+cw/2-22,t,60,.85f,Color.WHITE);
        canvas.label(sb,"＋",right+cw-30,t-1,24,1,ACCENT);
        canvas.label(sb,I18n.t("Boss 快速收尾：")+I18n.t(bossFastFinishEnabled?"开":"关"),26,t+p,cw-20,.85f,bossFastFinishEnabled?ACCENT:MUTED);
        canvas.label(sb,I18n.t("语言：")+("en".equals(I18n.language())?"English":"简体中文"),right+10,t+p,cw-20,.85f,ACCENT);
        canvas.label(sb,I18n.t("持续记录：")+" "+I18n.t(backend.runLog()?"开":"关"),26,t+2*p,cw-20,.85f,backend.runLog()?ACCENT:MUTED);
        canvas.label(sb,I18n.t("导出到桌面 · F6"),right+10,t+2*p,cw-20,.85f,ACCENT);
        canvas.label(sb,I18n.t("自动战斗快捷键：")+Input.Keys.toString(autoKey),26,t+3*p,cw-20,.85f,ACCENT);
        canvas.label(sb,"SuperFastMode: "+(Loader.isModLoaded("superfastmode")?"x"+superFastShown():I18n.t("未安装")),right+10,t+3*p,cw-20,.85f,
            superFastSpeed()>SUPER_FAST_TESTED?DANGER:MUTED);
        canvas.label(sb,bindingKey?I18n.t("按 F1-F12 绑定；F6/F7/F8/F9 保留；Esc 取消")
                :I18n.t("Boss 快速收尾：找到无药胜线就停止搜索，省时间但可能多掉血；关闭时与普通战斗相同"),
            16,SETTINGS_TOP+4*p-2,w-32,.7f,bindingKey?ACCENT:MUTED);
        boolean incompatibleMod = Loader.isModLoaded("SaveStateMod") || Loader.isModLoaded("undothespire") || Loader.isModLoaded("undobutton");
        String fast = superFastNote();
        boolean warn = incompatibleMod || !rngWarning.isEmpty() || superFastSpeed() > SUPER_FAST_TESTED;
        String defaultNote = incompatibleMod
            ? I18n.t("检测到不兼容 Mod（SaveStateMod / Undo）：会破坏战斗选牌与状态同步，建议禁用")
            : !rngWarning.isEmpty() ? rngWarning : !fast.isEmpty() ? fast : I18n.t("F6 导出记录到桌面，发这一个文件即可");
        Color noteColor = note.isEmpty()
            ? (warn ? DANGER : MUTED)
            : (exports.messageError ? DANGER : ACCENT);
        canvas.label(sb,note.isEmpty()?defaultNote:note,16,NOTE_TOP,w-32,.75f,noteColor);
    }

    /** 1 when SuperFastMode is absent or its multiplier is off. */
    static float superFastSpeed() {
        if (!Loader.isModLoaded("superfastmode")) return 1f;
        try {
            Class<?> mod = Class.forName("skrelpoid.superfastmode.SuperFastMode");
            return mod.getField("isDeltaMultiplied").getBoolean(null) ? mod.getField("deltaMultiplier").getFloat(null) : 1f;
        } catch (ReflectiveOperationException | LinkageError failure) { return 1f; }
    }

    static String superFastShown() {
        float speed = superFastSpeed();
        return speed == Math.rint(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
    }

    static String superFastNote() {
        if (!Loader.isModLoaded("superfastmode")) return "";
        float speed = superFastSpeed();
        String shown = superFastShown();
        return speed > SUPER_FAST_TESTED
            ? I18n.t("SuperFastMode 速度 ×") + shown + I18n.t(" 超出已测试的 ×10，如自动执行异常请调低")
            : I18n.t("已兼容 SuperFastMode（当前 ×") + shown + I18n.t("，建议不超过 ×10）");
    }

    private void renderCombatBody(SpriteBatch sb, float w, String note) {
        float inner=ROUTE_LEFT-48;
        canvas.box(sb,16,BODY_TOP,ROUTE_LEFT-28,BODY_ROWS*BODY_ROW-4,SURFACE);
        if (busy) {
            long seconds=(System.currentTimeMillis()-sentAt)/1000;
            long now = System.currentTimeMillis();
            canvas.label(sb,I18n.backend(backendPhase),26,86,inner,.9f,ACCENT);
            canvas.label(sb,I18n.t("总等待 ")+seconds+I18n.t(" 秒 · 本阶段 ")+((now-phaseAt)/1000)+I18n.t(" 秒"),26,116,inner,.8f,MUTED);
            canvas.label(sb,now-lastProgressAt>5000
                ? I18n.t("已 ")+((now-lastProgressAt)/1000)+I18n.t(" 秒无进度 · F9 停止")
                :progressRows==null?I18n.t("请求 #")+activeId+I18n.t(" · F9 停止"):progressSummary(),26,142,inner,.8f,MUTED);
        } else if (result != null) {
            boolean won=result.get("won").getAsBoolean();
            canvas.label(sb,won?I18n.t("预测胜利"):I18n.t("当前路线未找到胜利"),26,84,inner,.9f,won?ACCENT:DANGER);
            canvas.label(sb,I18n.t("战后 HP"),26,108,90,.75f,MUTED);
            canvas.label(sb,I18n.t("等效战损"),126,108,90,.75f,MUTED);
            canvas.label(sb,result.get("hp").getAsString(),26,124,90,1.5f,Color.WHITE);
            canvas.label(sb,result.get("loss").getAsString(),126,124,90,1.5f,ACCENT);
            canvas.line(sb,26,164,inner);
            canvas.label(sb,I18n.t("下一步  ")+commandLabel(),26,172,inner,.85f,Color.WHITE);
            String healing=I18n.t("战后恢复：")+result.get("recovery").getAsInt();
            if(result.getAsJsonArray("recovery_notes").size()>0)
                healing+=" · "+I18n.backend(result.getAsJsonArray("recovery_notes").get(0).getAsString());
            canvas.label(sb,healing,26,198,inner,.75f,MUTED);
        } else {
            canvas.label(sb,error.isEmpty()?I18n.t("自己构筑请开【战斗全自动】；点击【AI全自动】随时完全托管"):I18n.backend(error),26,90,inner,.85f,error.isEmpty()?ACCENT:DANGER);
            canvas.label(sb,error.isEmpty()?I18n.t("仅查看建议，点击按钮后才出牌"):I18n.t("详细原因已写入后台日志"),26,124,inner,.8f,MUTED);
        }
        List<String> foresight=transformPreview!=null?transformPreview.lines():combat()?Collections.<String>emptyList():Foresight.lines();
        String heading=transformPreview!=null?(transformPreview.upgrade?I18n.t("星盘"):I18n.t("变化"))+I18n.t("试选 ") + transformPreview.selected.size() + "/"+transformPreview.count+I18n.t(" · 点击此处关闭")
            :Foresight.previewCount()>0?I18n.t("变化预览 · 点击此处打开试选列表")
            :branchPicker?I18n.t("点击路线，或输入 0～10 后回车：[")+branchInput+"]"
            :busy&&progressRows!=null?I18n.t("搜索过程 · 已完成路线可点击执行")
            :!foresight.isEmpty()?I18n.t("随机结果预测 · 滚轮翻页")
            :result!=null && result.has("manual_choice") && result.get("manual_choice").getAsBoolean()
                ?I18n.t("抢劫怪路线 · 可手动或自动执行")
            :(fightAuto() || auto) && result!=null && result.has("branch") && !result.get("branch").getAsString().equals("no-potion")
                ?I18n.t("自动战斗已采纳用药路线 · 点击或 F9 可接管")
            :result!=null && (auto || turnOnly)?I18n.t("推荐行动顺序 · 自动执行中，F9 停止")
            :I18n.t("推荐行动顺序 · 点击执行整条路线");
        canvas.label(sb,heading,ROUTE_LEFT,48,w-ROUTE_LEFT-16,.85f,Color.WHITE);
        float rowWidth=w-ROUTE_LEFT-16, textWidth=rowWidth-16;
        String hint="";
        Color hintColor=MUTED;
        if (transformPreview != null) {
            scroll=Math.max(0,Math.min(scroll,Math.max(0,foresight.size()-BODY_ROWS)));
            for (int i=scroll;i<Math.min(foresight.size(),scroll+BODY_ROWS);i++) {
                canvas.button(sb,ROUTE_LEFT,BODY_TOP+(i-scroll)*BODY_ROW,rowWidth,BODY_ROW-4,SURFACE);
                canvas.label(sb,foresight.get(i),ROUTE_LEFT+8,BODY_TOP+5+(i-scroll)*BODY_ROW,textWidth,.85f,i<transformPreview.selected.size()?ACCENT:MUTED);
            }
            hint=I18n.t("仅试选 · 滚轮翻页 · 点击已选项撤回"); hintColor=ACCENT;
        } else if (branchPicker && result!=null) {
            JsonArray branches=result.getAsJsonArray("branches");
            scroll=Math.max(0,Math.min(scroll,Math.max(0,branches.size()-BODY_ROWS)));
            for(int i=scroll;i<Math.min(branches.size(),scroll+BODY_ROWS);i++) {
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
                if(available) canvas.button(sb,ROUTE_LEFT,BODY_TOP+(i-scroll)*BODY_ROW,rowWidth,BODY_ROW-4,SURFACE);
                else canvas.box(sb,ROUTE_LEFT,BODY_TOP+(i-scroll)*BODY_ROW,rowWidth,BODY_ROW-4,SURFACE);
                canvas.label(sb,i+" "+summary+" · "+I18n.backend(branch.get("name").getAsString()),ROUTE_LEFT+8,BODY_TOP+5+(i-scroll)*BODY_ROW,
                    textWidth,.85f,!revival.isEmpty()?DANGER:available?ACCENT:MUTED);
            }
            hint=result.has("manual_choice") && result.get("manual_choice").getAsBoolean()
                ?I18n.t("空回车：选推荐路线；选后可开自动")
                :branches.size()>0 && branches.get(0).getAsJsonObject().get("id").getAsString().equals("no-potion")
                ?I18n.t("0 / 空回车：不交药；无输入则等待"):I18n.t("0 / 空回车：选 0 号路线；无输入则等待");
        } else if (result != null) {
            JsonArray rows=result.getAsJsonArray("route");
            scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-BODY_ROWS)));
            for (int i=scroll;i<Math.min(rows.size(),scroll+BODY_ROWS);i++) {
                JsonObject row=rows.get(i).getAsJsonObject();
                String label=row.get("text").getAsString();
                if (row.has("label")) label=row.get("label").getAsString();
                float top=BODY_TOP+(i-scroll)*BODY_ROW;
                if(!auto && !turnOnly) canvas.button(sb,ROUTE_LEFT,top,rowWidth,BODY_ROW-4,SURFACE);
                else canvas.box(sb,ROUTE_LEFT,top,rowWidth,BODY_ROW-4,SURFACE);
                canvas.label(sb,String.format("%02d",i+1),ROUTE_LEFT+8,top+5,28,.8f,i==0?ACCENT:MUTED);
                canvas.label(sb,I18n.backend(label),ROUTE_LEFT+40,top+4,rowWidth-88,.9f,i==0?Color.WHITE:MUTED);
                canvas.label(sb,"T"+row.get("turn").getAsInt(),w-56,top+5,36,.75f,MUTED);
            }
        } else if (busy && progressRows != null) {
            for (int i=0;i<Math.min(progressRows.size(),BODY_ROWS);i++) {
                JsonObject row=progressRows.get(i).getAsJsonObject();
                int best=row.get("best_hp").getAsInt();
                String line=(best>0?"HP "+best:I18n.t("未胜"))+" · T"+row.get("turn").getAsInt()+" · "
                    +SearchStatus.phase(row)+" · "+(row.has("transitions") && (row.get("phase").getAsString().equals("beam") || row.get("phase").getAsString().equals("cycle"))
                        ? count(row.get("transitions").getAsLong())+I18n.t("步") : count(row.get("simulations").getAsLong())+I18n.t("次"))
                    +" · "+I18n.backend(row.get("name").getAsString());
                boolean ready=row.has("ready") && row.get("ready").getAsBoolean();
                String revival=ready?revivalWarning(row):"";
                if(!revival.isEmpty()) line=revival+" · "+line;
                if(ready) canvas.button(sb,ROUTE_LEFT,BODY_TOP+i*BODY_ROW,rowWidth,BODY_ROW-4,SURFACE); // finished routes can be clicked
                canvas.label(sb,line,ROUTE_LEFT+8,BODY_TOP+5+i*BODY_ROW,textWidth,.85f,!revival.isEmpty()?DANGER:ready?ACCENT:MUTED);
            }
        } else if (!foresight.isEmpty()) {
            scroll=Math.max(0,Math.min(scroll,Math.max(0,foresight.size()-BODY_ROWS)));
            for (int i=scroll;i<Math.min(foresight.size(),scroll+BODY_ROWS);i++) {
                String line=foresight.get(i);
                canvas.label(sb,line,ROUTE_LEFT+8,BODY_TOP+5+(i-scroll)*BODY_ROW,textWidth,.85f,line.startsWith(" ")?MUTED:ACCENT);
            }
        } else {
            canvas.label(sb,I18n.t("发牌和动画结算后自动生成建议"),ROUTE_LEFT+8,BODY_TOP+10,textWidth,.85f,MUTED);
            canvas.label(sb,I18n.t("奖励牌 / 地图 / 商店始终由你操作"),ROUTE_LEFT+8,BODY_TOP+40,textWidth,.85f,MUTED);
        }
        if(hint.isEmpty() && result!=null && !busy && !auto && !turnOnly && !branchPicker && transformPreview==null)
            hint=I18n.t(RunMode.combatAutomatic(mode())?"点击列表或回车：继续自动打完本场":"点击列表打完本场 · 回车走一步");
        if(fightAuto() && hint.isEmpty() && result!=null && result.has("auto_recommendation")) {
            JsonObject advice=result.getAsJsonObject("auto_recommendation");
            hint=I18n.backend(autoReason)+" · "+I18n.backend(advice.getAsJsonObject("assessment").get("summary").getAsString());
            hintColor=ACCENT;
        }
        if(!note.isEmpty())canvas.label(sb,note,16,NOTE_TOP,ROUTE_LEFT-28,.75f,exports.messageError?DANGER:ACCENT);
        if(!hint.isEmpty())canvas.label(sb,hint,ROUTE_LEFT,NOTE_TOP,w-ROUTE_LEFT-16,.75f,hintColor);
    }
    /** Clicks on the compact pilot card, in card units. */
    private void pilotInput(float lx, float ly, boolean click, float mx, float my) {
        if(pilot.listInput(ly,click) || !click)return;
        if(ly<40) {
            if(lx>=OutsidePilot.EXPORT_LEFT && lx<OutsidePilot.EXPORT_LEFT+OutsidePilot.EXPORT_WIDTH) exports.exportRun(backend);
            else if(lx>=OutsidePilot.PILOT_WIDTH-40 && !pilot.prompt) { // hide the card; the full panel returns, without suggestions.
                if(pilot.mode==2)stopAll();
                pilot.close();
            } else canvas.startDrag(mx,my);
            return;
        }
        int action=pilot.buttonAt(lx,ly);
        switch(action) {
            case OutsidePilot.NO_BUTTON: break;
            case 3: if(pilot.packet!=null)pilot.confirm=true; break;
            case 0: stopAll(); break;
            case -1: chooseOutsideMode(1); break;
            case -2:
                chooseOutsideMode(0);
                if (!runAuto) toggleRunAuto();
                break;
            default: chooseOutsideMode(action);
        }
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
