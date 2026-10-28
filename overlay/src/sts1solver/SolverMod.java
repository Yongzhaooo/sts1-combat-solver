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
    private volatile String rngMods = "";  // names only: the warning is translated when shown, after language switches
    // SuperFastMode multiplies Gdx delta; the solver no longer depends on frame time.
    // x10 is the speed long runs were played at (the author's own setting).
    static final float SUPER_FAST_TESTED = 10f;
    private long serial, activeId, sentAt;
    private long searchTimeoutMillis = SearchStatus.timeoutMillis(8000);
    private JsonObject rootKey, lastKey, result;
    // The step being executed, kept for the panel only: the backend's answer to "advance" replaces `result`, and a
    // body that fell back to the search view between steps made the panel blink.
    private JsonObject shownPlan;
    private boolean decisionDirty=true;
    private boolean busy, awaitingAction, auto, gamblingAuto, paused, collapsed;
    private boolean runAuto;
    // Standby mode: a fight is searched only after the player asks (Solve, F10 or Recalculate); armed marks that request.
    private boolean armed;
    private int warningRows; // rows of mod warnings drawn under the panel; layout keeps them on screen
    private boolean heldThisFight; // Step or Execute fight took this fight over; the next fight runs automatically again
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
    private int scroll;
    private int executedTurn;
    private String executedCommand = "";
    // Every request asks for the standard budget; the backend tiers it per act and room (low-performance mode: 1/20,
    // streak mode: no acts 1-3 tiers).
    private static final int BUDGET = 8000;
    private boolean highSpeed, streakMode;
    private long highSpeedWarnedAt;
    private boolean agentBridge; // Agent play (AGENT_PLAY.md), see AgentBridge
    private String status = I18n.t("等待进入战斗"), error = "";
    // Compact panel (product spec "Combat panel"): 420 units wide and as tall as its content, measured each frame.
    private static final float WIDTH = 420, PAD = 14, FOLDED = 40;
    private static final int LIST_ROWS = 6; // rows a list shows before the wheel scrolls it
    private float contentHeight = 200;
    private boolean settingsOpen;
    private boolean playOrderOpen, progressOpen; // disclosure rows stay as the player left them
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
            List<String> mods = RngMods.loaded();
            if (!mods.isEmpty()) {
                rngMods = String.join(" / ", mods);
                System.err.println("[STS1Solver] RNG-affecting mods: " + mods);
            }
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
        if (!fold && inFoldRoom && autoFolded && !standby()) { collapsed = false; autoFolded = false; }
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

    private float panelWidth() { return pilot.cardShown() ? OutsidePilot.PILOT_WIDTH : WIDTH; }

    /** Standby mode: fights wait for the player (not Combat auto, the pilot or agent play). */
    private boolean standby() { return !runAuto; }
    private boolean standbyWaiting() { return standby() && !armed; }

    /** The folded panel's one action: search this fight, stop the search, play the plan, pause it. Empty when there is nothing to do. */
    private String foldedAction() {
        if (!combat() || !supported() || branchPicker) return "";
        if (auto) return "暂停";
        if (busy) return "停止";
        if (result != null) return "执行";
        return standbyWaiting() || !error.isEmpty() ? "计算" : "";
    }

    private void foldedClick() {
        if (auto || result != null && !busy) toggleAuto();
        else if (busy) stopSearch();
        else solveAgain();
    }

    /** Solve, Recalculate and the error box: search this fight now. */
    private void solveAgain() { armed = true; recalculate("player"); }

    /** Stop search keeps the mode; in Combat auto this fight is then the player's. */
    private void stopSearch() {
        boolean keep = runAuto;
        cancel(true);
        runAuto = keep;
        if (keep) heldThisFight = true;
    }

    private String rngWarning() {
        return rngMods.isEmpty() ? "" : I18n.t("这些 Mod 会影响随机数（RNG），求解器计算可能不准确，建议关闭：") + rngMods;
    }

    /** Red warnings about other mods, shown whole in their own box under the panel. */
    private List<String> modWarnings() {
        List<String> warnings = new ArrayList<>();
        if (Loader.isModLoaded("SaveStateMod") || Loader.isModLoaded("undothespire") || Loader.isModLoaded("undobutton"))
            warnings.add(I18n.t("检测到不兼容 Mod（SaveStateMod / Undo）：会破坏战斗选牌与状态同步，建议禁用"));
        if (!rngMods.isEmpty()) warnings.add(rngWarning());
        if (superFastSpeed() > SUPER_FAST_TESTED) warnings.add(superFastNote());
        return warnings;
    }

    private void renderModWarnings(SpriteBatch sb) {
        float w = panelWidth(), top = panelHeight()+4;
        List<String> rows = new ArrayList<>();
        for (String warning : modWarnings()) rows.addAll(canvas.wrap(warning, w-24, .75f));
        warningRows = rows.size();
        if (rows.isEmpty()) return;
        canvas.box(sb,0,top,w,warningHeight(),DANGER);
        canvas.box(sb,1,top+1,w-2,warningHeight()-2,BACKGROUND);
        for (int i = 0; i < rows.size(); i++) canvas.label(sb,rows.get(i),12,top+6+i*20,w-24,.75f,DANGER);
        sb.setColor(Color.WHITE);
    }
    private float warningHeight() { return warningRows == 0 ? 0 : 12+warningRows*20; }
    private float panelHeight() { return pilot.cardShown() ? pilot.cardHeight() : collapsed ? FOLDED : contentHeight; }

    private boolean validAutoKey(int key) {
        return key >= Input.Keys.F1 && key <= Input.Keys.F12 && key != Input.Keys.F6 && key != Input.Keys.F7 && key != Input.Keys.F8 && key != Input.Keys.F9;
    }

    private void toggleAuto() {
        decisionDirty=true;
        if (!combat() || !supported()) return;
        if (auto) { heldThisFight = true; auto = false; status = I18n.t("自动已关闭 · 保留建议"); return; }
        heldThisFight = false;
        armed = true;
        auto = true;
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
        auto=false;
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
            if(!key(state).equals(rootKey)){recalculate("branch_state_changed");return;}
            chosenBranch=branch;
            branchPicker=false;
            branchConfirmed=true;
            boolean manual=result.has("manual_choice") && result.get("manual_choice").getAsBoolean();
            auto=fightAuto() || (autoAfterBranch(manual) && (RunMode.combatAutomatic(mode()) || runAfterBranch)); // Standby waits for Play unless it was pressed
            runAfterBranch=false;
            if(standby()) { collapsed=true; autoFolded=false; }
            if(manual)thiefPreference=branch;
            paused=false;
            send("choose",state);
        } catch(Exception failure){fail(failure.toString());}
    }

    static boolean routeConfirmed(JsonObject result, boolean branchConfirmed) {
        return result != null && (!result.has("manual_choice")
            || !result.get("manual_choice").getAsBoolean() || branchConfirmed);
    }
    /** A plan may run: the backend's route question is answered, and in Standby a plan is picked whenever several are usable. */
    static boolean approved(JsonObject result, boolean branchConfirmed, boolean standby) {
        return routeConfirmed(result, branchConfirmed) && (branchConfirmed || !routeChoice(result, standby));
    }
    /** Only the automatic modes start an opening selection by themselves; Standby waits for the player's approval. */
    static boolean startsOpeningAuto(boolean opening, boolean fightAuto) {
        return opening && fightAuto;
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

    /** The player picks a route first: the backend asks, or Standby has more than one usable route. */
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

    /** The panel's three-way mode switch and the new-run question. Switching to Standby keeps every setting. */
    private void setMode(RunMode target) {
        RunMode current=mode();
        if(target==current)return;
        if(target==RunMode.PILOT) { chooseOutsideMode(2); return; }
        if(pilot.mode!=0)pilot.select(0);
        if(target==RunMode.COMBAT_AUTO) { runAuto=false; toggleRunAuto(); return; } // from the pilot: combat-only automation
        runAutoEnabled=false; saveAutomation();
        cancel(true); // also ends the automatic run of this fight
    }

    private void toggleRunAuto() {
        if(runAuto){runAutoEnabled=false;saveAutomation();cancel(true);return;}
        if(!CommandExecutor.isInDungeon() || AbstractDungeon.player==null || !supported())return;
        runAutoEnabled=true;saveAutomation();
        runAuto=true;runAutoPlayer=AbstractDungeon.player;heldThisFight=false;
        auto=combat();paused=false;branchConfirmed=false;
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

    /** F9: back to Standby, where every fight waits for Solve and outside decisions are the player's. */
    private void stopAll() {
        pilot.stop();
        autoPotionRewards=false;
        cancel(true);
    }

    private void chooseOutsideMode(int mode) {
        pilot.select(mode);
        if(mode==0)return;
        if(mode==1) { runAuto=false; auto=false; decisionDirty=true; return; } // fights wait for the player too
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
            if(agent.sync(pilot,runAuto,rngWarning(),this::startAgentMode) && error.startsWith(I18n.t("Agent 通道读写失败：")))error="";
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
                if("SmokeBomb".equals(potion.ID) && potion.canUse()) {recalculate("smoke_opening");return;}
        }
        JsonObject advice=result.getAsJsonObject("auto_recommendation");
        if(advice==null || !advice.has("branch") || advice.get("branch").isJsonNull()) {
            fail(I18n.t("自动模式未找到获胜路线，请手动检查"));return;
        }
        autoReason=advice.get("reason").getAsString();
        String branch=advice.get("branch").getAsString();
        if(!result.get("branch_current").getAsBoolean()) {recalculate("auto_branch_stale");return;}
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
        if (op.equals("solve")) TestRecorder.captureSolveRoot(state);
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
            if (!op.equals("advance")) shownPlan = null;
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
        awaitingAction = auto = gamblingAuto = runAfterBranch = false;
        shownPlan = null;
        if (!keepResult) { branchPicker = branchConfirmed = false; branchInput = ""; result = null; }
        decisionDirty = true;
        paused = pause;
        status = pendingSearch ? I18n.t("正在停止搜索 · 保留已找到路线")
            : pause ? (keepResult ? I18n.t("已停止 · 点【执行整场】继续") : I18n.t("已停止 · 点击重新计算恢复")) : I18n.t("等待稳定局面");
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

    /** Drops the plan and searches again; the reason goes to the run record so an unexpected re-search can be traced. */
    private void recalculate(String reason) {
        diagnostic("recalculate",reason,null);
        boolean resumeAuto=auto;
        cancel(false);
        auto=resumeAuto;
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
        } else if(auto)fail(I18n.t("局面发生未预期变化，已停止自动执行：")+difference);
        else recalculate("state_changed");
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
        if(!wasCombat && inCombat) {
            heldThisFight=false;
            if(standby()) { collapsed=true; autoFolded=false; status=I18n.t("待命 · 点【计算】开始搜索"); }
        }
        if(!wasCombat && inCombat && runAuto) {
            auto=true;paused=false;branchConfirmed=false;decisionDirty=true;lastKey=null;
        }
        if (wasCombat && !inCombat) {
            diagnostic("combat_end",I18n.t("离开战斗"),null);
            cancel(false);
            nextRest=false;
            lastKey = null;
            thiefPreference=null;
            armed=false;
            if(standby()) { collapsed=true; autoFolded=false; }
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
            shownPlan = null;
            if (kind.equals("ready")) {
                result = reply;
                status = reply.has("manual_choice") && reply.get("manual_choice").getAsBoolean()
                    ? I18n.t("抢劫怪：选定路线后可开启自动战斗")
                    : reply.has("deadline") && reply.get("deadline").getAsBoolean()
                    ? I18n.t("已到时限 · 当前最佳路线（搜索未完成）")
                    : reply.has("interrupted") && reply.get("interrupted").getAsBoolean()
                    ? I18n.t("已停止 · 点【执行整场】继续") : I18n.t("路线已就绪 · 等待你的操作");
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
                    if (!reply.has("deadline")) status=I18n.t("已停止 · 点击路线或输入编号后回车");
                } else if (routeChoice(reply, !fightAuto()) && !branchConfirmed) requestBranches();
                decisionDirty=true;
            } else if (kind.equals("error")) fail(reply.get("message").getAsString());
            else if (kind.equals("cancelled")) { busy=false; clickedBranch=null; status=I18n.t("已停止 · 尚无可执行路线"); }
            else if (kind.equals("complete")) {
                auto = false;
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
            if (!paused && !gamblingAuto && !auto && !awaitingAction && !standbyWaiting()
                    && startsOpeningAuto(openingSelection(state), fightAuto())) {
                gamblingAuto = auto = true; // the branch is still chosen below, like any fight
                branchPicker=false;captureBranchKeys=false;branchInput="";
                status = I18n.t("开场选牌：规划并自动选择");
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
            if (CombatReadiness.holdSolveForPicker(AbstractDungeon.screen, auto, openingSelection(state))) return; // decisionDirty stays set: re-solve after it closes
            if ((busy || result != null) && !current.equals(rootKey)) {
                stateChanged(state);
                if(paused)return;
            }
            if (!busy && result == null && !paused && !standbyWaiting() && !current.equals(lastKey)) {
                decisionDirty=false;
                send("solve", state);
                return;
            }
            if (result != null && auto) {
                if(fightAuto() && !branchConfirmed){chooseAutomaticBranch();return;}
                if (routeChoice(result, !fightAuto()) && !branchConfirmed) {requestBranches();return;}
                executeStep();
            }
            if(!awaitingAction && !captureBranchKeys)decisionDirty=false;
        } catch (Exception failure) { fail(failure.toString()); }
    }

    private void executeStep() {
        if (result == null || !ready() || busy || awaitingAction) return;
        if (branchPicker || captureBranchKeys) return;
        if (!approved(result,branchConfirmed,!fightAuto())) { requestBranches(); return; } // never an unpicked plan
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
            shownPlan = result;
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
    private void layout() { canvas.layout(panelWidth(), panelHeight()+(warningRows==0?0:4+warningHeight())); }

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
        // Agent play outside combat: Enter confirms the listed step.
        if(pilot.mode==1 && !combat() && pilot.packet!=null && pilot.scores!=null && Gdx.input.isKeyJustPressed(Input.Keys.ENTER))
            pilot.confirm=true;
        boolean held=Gdx.input.isKeyPressed(Input.Keys.ENTER);
        for(int n=0;n<=9;n++) held |= Gdx.input.isKeyPressed(Input.Keys.NUM_0+n)
                || Gdx.input.isKeyPressed(Input.Keys.NUMPAD_0+n);
        // Keep native card hotkeys suppressed until the selecting key is released.
        captureBranchKeys=branchPicker || (captureBranchKeys && held);
        // In Combat auto and the pilot, Enter resumes this fight's automation; in Standby it stays the game's. The key
        // is withheld from the game until released; potion choices keep their own Enter.
        if(!branchPicker && !auto && RunMode.combatAutomatic(mode()) && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)
                && supported() && ready()) {
            toggleAuto();
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
        if (over && click && !canvas.click((mx-canvas.x)/s,(canvas.y-my)/s) && (canvas.y-my)/s < 40) canvas.startDrag(mx,my);
        canvas.hovered = over || canvas.dragging;
        if (over || canvas.dragging) {
            if (!collapsed && InputHelper.scrolledDown) scroll++; // the one list on screen; drawing clamps it
            if (!collapsed && InputHelper.scrolledUp) scroll=Math.max(0,scroll-1);
            InputHelper.justClickedLeft=false;
            InputHelper.justReleasedClickLeft=false;
            InputHelper.justClickedRight=false;
            InputHelper.justReleasedClickRight=false;
            InputHelper.scrolledDown=InputHelper.scrolledUp=false;
        }
    }

    private void toggleTransformPreview() {
        if (transformPreview != null) transformPreview = null;
        else {
            boolean astrolabe = Foresight.astrolabeOffered();
            transformPreview = new TransformPreview(astrolabe ? AbstractDungeon.player.masterDeck.getPurgeableCards().group
                : EventForesight.transformCards(AbstractDungeon.getCurrRoom().event).group,
                AbstractDungeon.miscRng, Foresight.previewCount(), astrolabe);
            previewRngCounter = AbstractDungeon.miscRng.counter;
            previewSource = AbstractDungeon.currMapNode;
        }
        scroll = 0;
    }

    /** A finished route clicked during the search: stop and take it (Standby then waits for Play). */
    private void pickProgress(JsonObject progress) {
        boolean keepAuto=runAuto; // picking a finished route is not a Stop
        clickedBranch=progress.get("id").getAsString();
        cancel(true);
        runAuto=keepAuto;
    }

    private void setSearchTier(int tier) {
        highSpeed = tier == 1; streakMode = tier == 2;
        saveSetting("highSpeed", highSpeed); saveSetting("streakMode", streakMode); recalculate("search_tier");
        highSpeedWarnedAt = highSpeed || streakMode ? System.currentTimeMillis() : 0;
    }

    private void toggleBrightEye() {
        Foresight.brightEyeMode = !Foresight.brightEyeMode;
        scroll = 0;
        try { if(config!=null) { config.setBool("brightEyeMode",Foresight.brightEyeMode); config.save(); } }
        catch(IOException failure) { error=I18n.t("模式保存失败：")+failure.getMessage(); }
    }

    static boolean solverDriving() { return instance != null && (instance.auto || instance.runAuto); }
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
        return I18n.t("本轮模拟 ")+count(total)+I18n.t("次 · 循环展开 ")+count(transitions)+" "+I18n.t("步");
    }
    private boolean canCreditRest() {
        if(!combat() || !AbstractDungeon.player.hasRelic("Eternal Feather"))return false;
        for(com.megacrit.cardcrawl.map.MapRoomNode node:communicationmod.ChoiceScreenUtils.getMapScreenNodeChoices())
            if(node.getRoomSymbol(true).equals("R"))return true;
        return false;
    }
    /** The step the plan runs next; while it is being executed, the route row the backend labelled for it. */
    private String commandLabel(JsonObject plan, boolean executing) {
        if (plan == null) return "";
        if (executing) {
            String step = routeLabel(plan);
            if (!step.isEmpty()) return step;
        }
        String command=plan.get("command").getAsString();
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

    /** The first route row's label: what the plan's next step is, without reading the live hand. */
    static String routeLabel(JsonObject plan) {
        if (plan == null || !plan.has("route") || !plan.get("route").isJsonArray()
                || plan.getAsJsonArray("route").size() == 0) return "";
        JsonObject first = plan.getAsJsonArray("route").get(0).getAsJsonObject();
        if (!first.has("label") && !first.has("text")) return "";
        return I18n.backend(first.has("label") ? first.get("label").getAsString() : first.get("text").getAsString());
    }

    @Override public void receivePostRender(SpriteBatch sb) {
        try { renderPanel(sb); renderModWarnings(sb); } catch (Throwable failure) { guard("render", failure); }
    }

    private void renderPanel(SpriteBatch sb) {
        Foresight.renderRubyReminder(sb);
        renderPotionRewardAdvice(sb);
        if(routeMarks)pilot.renderRoute(sb,canvas);
        deckWatch.render(sb,canvas);
        layout();
        canvas.clearHits();
        if(pilot.cardShown()) { pilot.renderCard(sb,canvas); canvas.cursorOnTop(sb); return; }
        if(collapsed) renderFolded(sb);
        else {
            canvas.dry=true; // measure first so the card is drawn at this frame's height
            try { contentHeight=renderBody(sb); } finally { canvas.dry=false; }
            canvas.box(sb,0,0,WIDTH,contentHeight,BORDER);
            canvas.box(sb,1,1,WIDTH-2,contentHeight-2,BACKGROUND);
            renderBody(sb);
        }
        sb.setColor(Color.WHITE);
        canvas.cursorOnTop(sb);
    }

    /** Which body the unfolded panel draws: exactly one, most specific first. */
    enum Body { OUTSIDE, UNSUPPORTED, ERROR, PICKER, PLAN, SEARCH, IDLE }

    /**
     * The body for the current flags. A plan being executed keeps the plan body ({@code hasShownPlan}), so the step
     * the panel just ran stays in place while the backend confirms it, instead of blinking to the search body.
     */
    static Body bodyView(boolean combat, boolean supported, boolean hasError, boolean picker,
                         boolean busy, boolean hasResult, boolean hasShownPlan) {
        if (!combat) return Body.OUTSIDE;
        if (!supported) return Body.UNSUPPORTED;
        if (hasError) return Body.ERROR;
        if (picker && hasResult) return Body.PICKER;
        if (hasResult || hasShownPlan) return Body.PLAN;
        if (busy) return Body.SEARCH;
        return Body.IDLE;
    }

    private void renderFolded(SpriteBatch sb) {
        canvas.box(sb,0,0,WIDTH,FOLDED,BORDER);
        canvas.box(sb,1,1,WIDTH-2,FOLDED-2,BACKGROUND);
        canvas.label(sb,I18n.t("战斗求解器"),PAD,9,150,.9f,Color.WHITE);
        float left=PAD+canvas.textWidth("战斗求解器",.9f)+10, right=WIDTH-PAD-24;
        canvas.button(sb,right,7,24,26,null,"＋",1,MUTED,() -> { collapsed=false; autoFolded=false; });
        String action=foldedAction();
        if(!action.isEmpty()) {
            float w=canvas.textWidth(action,.74f)+26;
            right-=w+6;
            boolean danger=action.equals("停止"), soft=action.equals("暂停");
            canvas.button(sb,right,7,w,26,danger?DANGER_SOFT:soft?SECONDARY:ACCENT,action,.74f,danger?DANGER:soft?Color.WHITE:BACKGROUND,this::foldedClick);
        }
        if(busy && combat() && supported()) canvas.bar(sb,1,FOLDED-4,(WIDTH-2)*SearchStatus.waitingFill(System.currentTimeMillis()-sentAt),3,ACCENT);
        Color[] tone={SECONDARY,MUTED};
        String badge=foldedBadge(tone);
        if(!badge.isEmpty()) canvas.badge(sb,badge,left,10,right-8-left,tone[0],tone[1]);
    }

    /** The folded panel's status tag; {@code tone} receives its fill and text colours. */
    private String foldedBadge(Color[] tone) {
        if(!combat() || !supported()) {
            if(runAuto) { tone[0]=ACCENT_SOFT; tone[1]=ACCENT; }
            return I18n.t(pilot.mode==2?"AI全自动":runAuto?"战斗全自动":"待命");
        }
        if(!error.isEmpty()) { tone[0]=DANGER_SOFT; tone[1]=DANGER; return status; }
        if(auto) { tone[0]=ACCENT_SOFT; tone[1]=ACCENT; return I18n.t("自动执行中"); }
        if(shownPlan != null) return status;
        if(busy) return I18n.t("搜索中")+" "+(System.currentTimeMillis()-sentAt)/1000+I18n.t("秒");
        if(branchPicker) { tone[0]=ACCENT_SOFT; tone[1]=ACCENT; return I18n.t("选择用药路线"); }
        if(result!=null) {
            boolean won=result.get("won").getAsBoolean();
            String revival=revivalWarning(result);
            boolean good=won && revival.isEmpty();
            tone[0]=good?ACCENT_SOFT:DANGER_SOFT; tone[1]=good?ACCENT:DANGER;
            // A win is told by the green badge; the words would not fit beside Play in English.
            return (won?"":I18n.t("未胜")+" · ")+"HP "+result.get("hp").getAsString()
                +" · "+I18n.t("战损")+" "+result.get("loss").getAsString()+(revival.isEmpty()?"":" · "+revival);
        }
        return standbyWaiting() ? I18n.t("待命") : status;
    }

    /** The unfolded panel from the top; returns its height. Runs twice a frame: measured (canvas.dry), then drawn. */
    private float renderBody(SpriteBatch sb) {
        float right=WIDTH-PAD-24;
        canvas.label(sb,I18n.t(settingsOpen?"设置":"战斗求解器"),PAD,11,200,.95f,Color.WHITE);
        canvas.button(sb,right,8,24,26,null,"－",1,MUTED,() -> { collapsed=true; autoFolded=false; });
        right=ghost(sb,right,settingsOpen?"返回":"设置",() -> settingsOpen=!settingsOpen);
        if(!settingsOpen) ghost(sb,right,"导出日志",() -> exports.exportRun(backend));
        float top=44;
        if(settingsOpen) top=renderSettings(sb,top);
        else {
            if(inSupportedRun()) top=modeSwitch(sb,top);
            if((highSpeed || streakMode) && System.currentTimeMillis()-highSpeedWarnedAt < 8000)
                top=lines(sb,I18n.t(highSpeed?"高速模式：搜索量 1/20，胜率和省血下降":"连胜模式：前三幕不降预算，搜索更慢"),top,.76f,DANGER);
            switch(bodyView(combat(),supported(),!error.isEmpty(),branchPicker,busy,result!=null,shownPlan!=null)) {
                case OUTSIDE: top=renderOutside(sb,top); break;
                case UNSUPPORTED: top=lines(sb,status,top,.85f,MUTED); break;
                case ERROR: top=renderError(sb,top); break;
                case PICKER: top=renderPicker(sb,top); break;
                case PLAN: top=renderPlan(sb,top,result!=null?result:shownPlan,result==null); break;
                case SEARCH: top=renderSearch(sb,top); break;
                default: top=renderIdle(sb,top); break;
            }
        }
        String note=exports.note();
        if(!note.isEmpty()) top=lines(sb,note,top,.74f,exports.messageError?DANGER:ACCENT);
        return top+PAD-6;
    }

    /** A ghost header button ending at {@code right}; returns its left edge. */
    private float ghost(SpriteBatch sb, float right, String label, Runnable action) {
        float w=canvas.textWidth(label,.76f)+18;
        canvas.button(sb,right-w-4,8,w,26,null,label,.76f,MUTED,action);
        return right-w-4;
    }

    /** Wrapped text, whole; returns the top of the next row. */
    private float lines(SpriteBatch sb, String text, float top, float size, Color color) {
        for(String line:canvas.wrap(text,WIDTH-2*PAD,size)) { canvas.label(sb,line,PAD,top,WIDTH-2*PAD,size,color); top+=size*24; }
        return top+6;
    }

    private float modeSwitch(SpriteBatch sb, float top) {
        String[] labels={"待命","战斗全自动","AI全自动"};
        RunMode[] modes={RunMode.STANDBY,RunMode.COMBAT_AUTO,RunMode.PILOT};
        RunMode current=mode();
        float w=(WIDTH-2*PAD-4)/3;
        canvas.box(sb,PAD,top,WIDTH-2*PAD,28,SECONDARY);
        for(int i=0;i<3;i++) {
            RunMode target=modes[i];
            boolean on=current==target;
            canvas.button(sb,PAD+2+i*w,top+2,w,24,on?ACCENT_SOFT:null,labels[i],.74f,on?ACCENT:MUTED,on?null:() -> setMode(target));
        }
        return top+38;
    }

    /** Up to three buttons in one row; kinds: 0 primary, 1 secondary, 2 ghost, 3 danger. */
    private float actions(SpriteBatch sb, float top, String[] labels, int[] kinds, Runnable[] runs) {
        float avail=WIDTH-2*PAD-8*(labels.length-1), left=PAD;
        for(int i=0;i<labels.length;i++) {
            float w=labels.length==3 ? (i==0?avail*.38f:i==1?avail*.31f:avail*.31f) : avail/labels.length;
            Color fill=kinds[i]==0?ACCENT:kinds[i]==1?SECONDARY:kinds[i]==3?DANGER_SOFT:null;
            Color text=kinds[i]==0?BACKGROUND:kinds[i]==1?Color.WHITE:kinds[i]==3?DANGER:MUTED;
            canvas.button(sb,left,top,w,32,fill,labels[i],.8f,text,runs[i]);
            left+=w+8;
        }
        return top+32+PAD;
    }

    /** A "▶ title" row that opens and closes the list under it. */
    private float disclosure(SpriteBatch sb, float top, String title, boolean open, Runnable toggle, String tail) {
        canvas.line(sb,PAD,top,WIDTH-2*PAD);
        top+=6;
        canvas.button(sb,PAD-6,top,WIDTH-2*PAD+12,26,null,null,.76f,MUTED,toggle);
        float tw=tail.isEmpty()?0:canvas.textWidth(tail,.72f);
        canvas.label(sb,(open?"▼ ":"▶ ")+title,PAD,top+5,WIDTH-2*PAD-tw-12,.76f,MUTED);
        if(tw>0) canvas.label(sb,tail,WIDTH-PAD-tw,top+6,tw+2,.72f,MUTED);
        return top+32;
    }

    /** Rows of a list, at most LIST_ROWS from {@code scroll}; {@code click} null leaves the rows display-only. */
    private float listRows(SpriteBatch sb, float top, List<String> rows, List<Color> colors, java.util.function.IntConsumer click) {
        scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-LIST_ROWS)));
        for(int i=scroll;i<Math.min(rows.size(),scroll+LIST_ROWS);i++) {
            int index=i;
            if(click==null) canvas.box(sb,PAD,top,WIDTH-2*PAD,24,SURFACE);
            else canvas.button(sb,PAD,top,WIDTH-2*PAD,24,SURFACE,null,.8f,MUTED,() -> click.accept(index));
            canvas.label(sb,rows.get(i),PAD+10,top+4,WIDTH-2*PAD-20,.8f,colors.get(i));
            top+=28;
        }
        return top+4;
    }

    private float headline(SpriteBatch sb, String text, float top, Color color) {
        canvas.label(sb,text,PAD,top,WIDTH-2*PAD,1.05f,color);
        return top+30;
    }

    private float renderIdle(SpriteBatch sb, float top) {
        if(standbyWaiting()) {
            top=headline(sb,I18n.t("待命"),top,Color.WHITE);
            top=lines(sb,I18n.t("点【计算】开始搜索本场"),top,.8f,MUTED);
            return actions(sb,top+4,new String[]{"计算"},new int[]{0},new Runnable[]{this::solveAgain});
        }
        top=lines(sb,status,top,.85f,MUTED);
        return actions(sb,top+4,new String[]{"重新计算"},new int[]{2},new Runnable[]{this::solveAgain});
    }

    private float renderError(SpriteBatch sb, float top) {
        List<String> rows=canvas.wrap(I18n.backend(error),WIDTH-2*PAD-24,.78f);
        float h=58+rows.size()*20;
        canvas.box(sb,PAD,top,WIDTH-2*PAD,h,DANGER);
        canvas.box(sb,PAD+1,top+1,WIDTH-2*PAD-2,h-2,DANGER_SOFT);
        canvas.label(sb,status,PAD+12,top+9,WIDTH-2*PAD-24,.85f,DANGER);
        for(int i=0;i<rows.size();i++) canvas.label(sb,rows.get(i),PAD+12,top+34+i*20,WIDTH-2*PAD-24,.78f,Color.WHITE);
        canvas.label(sb,I18n.t("详细原因已写入后台日志"),PAD+12,top+36+rows.size()*20,WIDTH-2*PAD-24,.72f,MUTED);
        return actions(sb,top+h+10,new String[]{"重新计算"},new int[]{0},new Runnable[]{this::solveAgain});
    }

    private float renderSearch(SpriteBatch sb, float top) {
        long now=System.currentTimeMillis();
        top=headline(sb,I18n.t("搜索中")+" …",top,Color.WHITE);
        canvas.bar(sb,PAD,top-2,WIDTH-2*PAD,6,SECONDARY);
        canvas.bar(sb,PAD,top-2,(WIDTH-2*PAD)*SearchStatus.waitingFill(now-sentAt),6,ACCENT);
        top+=12;
        top=lines(sb,I18n.backend(backendPhase),top,.8f,MUTED);
        top=lines(sb,I18n.t("总等待 ")+((now-sentAt)/1000)+I18n.t(" 秒 · 本阶段 ")+((now-phaseAt)/1000)+I18n.t(" 秒"),top-4,.76f,MUTED);
        top=lines(sb,now-lastProgressAt>5000 ? I18n.t("已 ")+((now-lastProgressAt)/1000)+I18n.t(" 秒无进度 · F9 停止")
            : progressRows==null ? I18n.t("请求 #")+activeId+I18n.t(" · F9 停止") : progressSummary(),top-4,.76f,MUTED);
        if(progressRows!=null) {
            List<String> rows=new ArrayList<>(); List<Color> colors=new ArrayList<>(); int done=0;
            for(JsonElement e:progressRows) {
                JsonObject row=e.getAsJsonObject();
                int best=row.get("best_hp").getAsInt();
                boolean ready=row.has("ready") && row.get("ready").getAsBoolean() && row.has("id");
                String revival=ready?revivalWarning(row):"";
                String phase=row.get("phase").getAsString();
                rows.add((revival.isEmpty()?"":revival+" · ")+(best>0?"HP "+best:I18n.t("未胜"))+" · T"+row.get("turn").getAsInt()+" · "
                    +SearchStatus.phase(row)+" · "+(row.has("transitions") && (phase.equals("beam") || phase.equals("cycle"))
                        ? count(row.get("transitions").getAsLong())+I18n.t("步") : count(row.get("simulations").getAsLong())+I18n.t("次"))
                    +" · "+I18n.backend(row.get("name").getAsString()));
                colors.add(!revival.isEmpty()?DANGER:ready?ACCENT:MUTED);
                if(ready)done++;
            }
            top=disclosure(sb,top,I18n.t("搜索过程")+" · "+done+" "+I18n.t("条已完成"),progressOpen,() -> { progressOpen=!progressOpen; scroll=0; },"");
            if(progressOpen) top=listRows(sb,top,rows,colors,index -> {
                JsonObject row=progressRows.get(index).getAsJsonObject();
                if(row.has("ready") && row.get("ready").getAsBoolean() && row.has("id")) pickProgress(row);
            });
        }
        return actions(sb,top+4,new String[]{"停止搜索"},new int[]{3},new Runnable[]{this::stopSearch});
    }

    private float renderPlan(SpriteBatch sb, float top, JsonObject plan, boolean executing) {
        boolean won=plan.get("won").getAsBoolean();
        String head=I18n.t(won?"预测胜利":"当前路线未找到胜利");
        String metrics=I18n.t("战后 HP")+" "+plan.get("hp").getAsString()+" · "+I18n.t("等效战损")+" "+plan.get("loss").getAsString();
        canvas.label(sb,head,PAD,top,WIDTH-2*PAD,1.05f,won?ACCENT:DANGER);
        float x=PAD+canvas.textWidth(head,1.05f)+12;
        if(x+canvas.textWidth(metrics,.8f)<=WIDTH-PAD) canvas.label(sb,metrics,x,top+4,WIDTH-PAD-x,.8f,MUTED);
        else { top+=26; canvas.label(sb,metrics,PAD,top,WIDTH-2*PAD,.8f,MUTED); }
        top+=30;
        String revival=revivalWarning(plan);
        if(!revival.isEmpty()) top=lines(sb,revival,top-4,.78f,DANGER);
        if((fightAuto() || auto) && plan.has("branch") && !plan.get("branch").getAsString().equals("no-potion")) {
            String why=plan.has("auto_recommendation") && !autoReason.isEmpty() ? I18n.backend(autoReason) : I18n.t("自动战斗已采纳用药路线");
            if(plan.has("auto_recommendation"))
                why+=" · "+I18n.backend(plan.getAsJsonObject("auto_recommendation").getAsJsonObject("assessment").get("summary").getAsString());
            top=lines(sb,why,top-4,.74f,MUTED);
        }
        String lead=I18n.t(executing?"执行中":auto?"自动执行中":"下一步");
        canvas.label(sb,lead,PAD,top,140,.82f,MUTED);
        float lx=PAD+canvas.textWidth(lead,.82f)+8;
        canvas.label(sb,commandLabel(plan,executing),lx,top,WIDTH-PAD-lx,.82f,Color.WHITE);
        top+=30;
        JsonArray route=plan.getAsJsonArray("route");
        String healing=I18n.t("战后恢复：")+plan.get("recovery").getAsInt();
        top=disclosure(sb,top,I18n.t("出牌顺序")+" · "+route.size()+" "+I18n.t("步"),playOrderOpen,() -> { playOrderOpen=!playOrderOpen; scroll=0; },healing);
        if(playOrderOpen) {
            List<String> rows=new ArrayList<>(); List<Color> colors=new ArrayList<>();
            for(int i=0;i<route.size();i++) {
                JsonObject row=route.get(i).getAsJsonObject();
                String label=row.has("label")?row.get("label").getAsString():row.get("text").getAsString();
                rows.add(String.format("%02d  ",i+1)+I18n.backend(label)+"  · T"+row.get("turn").getAsInt());
                colors.add(i==0?Color.WHITE:MUTED);
            }
            top=listRows(sb,top,rows,colors,null); // reading only; Play and Step are the ways in
            if(plan.getAsJsonArray("recovery_notes").size()>0)
                top=lines(sb,I18n.backend(plan.getAsJsonArray("recovery_notes").get(0).getAsString()),top,.72f,MUTED);
        }
        if(auto) return actions(sb,top+4,new String[]{"暂停自动"},new int[]{1},new Runnable[]{this::toggleAuto});
        // One button too, so the row keeps its height while the step is confirmed.
        if(executing) return actions(sb,top+4,new String[]{"重新计算"},new int[]{2},new Runnable[]{this::solveAgain});
        return actions(sb,top+4,new String[]{"执行整场","执行一步","重新计算"},new int[]{0,1,2},new Runnable[]{
            this::toggleAuto, () -> { heldThisFight=true; auto=false; executeStep(); }, this::solveAgain});
    }

    static String unavailableRouteReason(JsonObject branch) {
        if (branch.has("message") && !branch.get("message").isJsonNull()
                && !branch.get("message").getAsString().trim().isEmpty())
            return I18n.backend(branch.get("message").getAsString());
        return I18n.t("本轮未提供原因，请重新计算或导出日志");
    }

    private float renderPicker(SpriteBatch sb, float top) {
        boolean thief=result.has("manual_choice") && result.get("manual_choice").getAsBoolean();
        top=headline(sb,I18n.t(thief?"抢劫怪路线 · 可手动或自动执行":"选择用药路线"),top,Color.WHITE);
        top=lines(sb,status+(branchInput.isEmpty()?"":"  ["+branchInput+"]"),top-4,.78f,MUTED);
        JsonArray branches=result.getAsJsonArray("branches");
        List<String> rows=new ArrayList<>(); List<Color> colors=new ArrayList<>();
        List<Integer> rowBranches=new ArrayList<>();
        for(int i=0;i<branches.size();i++) {
            JsonObject branch=branches.get(i).getAsJsonObject();
            boolean available=branch.get("available").getAsBoolean();
            // Result first so a long bottle list can only truncate the route name.
            String summary=available?(branch.get("won").getAsBoolean()?I18n.t("损 ")+branch.get("loss").getAsInt():I18n.t("未胜")):I18n.t("本轮无可选路线");
            String revival=revivalWarning(branch);
            if(!revival.isEmpty()) summary=revival+" · "+summary;
            if(available && branch.has("lost_gold") && branch.get("lost_gold").getAsInt()>0)
                summary+=I18n.t(" / 金 -")+branch.get("lost_gold").getAsInt();
            if(available && branch.has("potion_gain") && branch.get("potion_gain").getAsInt()>0)
                summary+=I18n.t(" / 补 ")+branch.get("potion_gain").getAsInt()+I18n.t(" 药");
            rows.add(i+"  "+summary+" · "+I18n.backend(branch.get("name").getAsString()));
            colors.add(!revival.isEmpty()?DANGER:available?ACCENT:MUTED);
            rowBranches.add(i);
            if(!available) {
                String prefix=i+"  ";
                for(String line:canvas.wrap(unavailableRouteReason(branch),WIDTH-2*PAD-20-canvas.textWidth(prefix,.8f),.8f)) {
                    rows.add(prefix+line);
                    colors.add(MUTED);
                    rowBranches.add(i);
                }
            }
        }
        top=listRows(sb,top,rows,colors,index -> {
            JsonObject branch=branches.get(rowBranches.get(index)).getAsJsonObject();
            if(branch.get("available").getAsBoolean()) chooseBranch(branch.get("id").getAsString());
        });
        String hint=thief ? I18n.t("空回车：选推荐路线；选后可开自动")
            : branches.size()>0 && branches.get(0).getAsJsonObject().get("id").getAsString().equals("no-potion")
            ? I18n.t("0 / 空回车：不交药；无输入则等待") : I18n.t("0 / 空回车：选 0 号路线；无输入则等待");
        return lines(sb,hint+I18n.t(" · 滚轮查看完整路线与原因"),top,.72f,MUTED);
    }

    /** Outside combat: the run status, and the event foresight or the transform preview when there is one. */
    private float renderOutside(SpriteBatch sb, float top) {
        top=lines(sb,status,top,.8f,MUTED);
        List<String> foresight=transformPreview!=null?transformPreview.lines():Foresight.lines();
        if(transformPreview!=null || Foresight.previewCount()>0) {
            String title=transformPreview!=null
                ? (transformPreview.upgrade?I18n.t("星盘"):I18n.t("变化"))+I18n.t("试选 ")+transformPreview.selected.size()+"/"+transformPreview.count
                : I18n.t("变化预览 · 点击此处打开试选列表");
            top=disclosure(sb,top,title,transformPreview!=null,this::toggleTransformPreview,"");
        } else if(!foresight.isEmpty()) {
            canvas.line(sb,PAD,top,WIDTH-2*PAD);
            top=lines(sb,I18n.t("随机结果预测 · 滚轮翻页"),top+6,.76f,MUTED);
        }
        if(foresight.isEmpty()) return top;
        List<Color> colors=new ArrayList<>();
        for(int i=0;i<foresight.size();i++)
            colors.add(transformPreview!=null ? (i<transformPreview.selected.size()?ACCENT:MUTED) : foresight.get(i).startsWith(" ")?MUTED:ACCENT);
        top=listRows(sb,top,foresight,colors,transformPreview==null?null:index -> { transformPreview.pick(index); scroll=0; });
        return transformPreview==null ? top : lines(sb,I18n.t("仅试选 · 滚轮翻页 · 点击已选项撤回"),top,.72f,ACCENT);
    }

    /** One settings row: the label on the left; the control is drawn by the caller on the right. */
    private void settingLabel(SpriteBatch sb, String label, float top) {
        canvas.label(sb,I18n.t(label),PAD,top+5,170,.8f,Color.WHITE);
    }

    /** A segmented control ending at the panel's right padding. */
    private void segmented(SpriteBatch sb, float top, String[] options, int active, java.util.function.IntConsumer pick) {
        float[] widths=new float[options.length]; float total=4;
        for(int i=0;i<options.length;i++) { widths[i]=canvas.textWidth(options[i],.72f)+18; total+=widths[i]; }
        float left=WIDTH-PAD-total;
        canvas.box(sb,left,top,total,26,SECONDARY);
        left+=2;
        for(int i=0;i<options.length;i++) {
            int index=i;
            canvas.button(sb,left,top+2,widths[i],22,i==active?ACCENT_SOFT:null,options[i],.72f,i==active?ACCENT:MUTED,
                i==active?null:() -> pick.accept(index));
            left+=widths[i];
        }
    }

    /** An on/off switch at the panel's right padding. */
    private void onOff(SpriteBatch sb, float top, boolean on, Runnable toggle) {
        float left=WIDTH-PAD-38;
        canvas.button(sb,left,top+3,38,20,on?ACCENT:SECONDARY,null,.7f,MUTED,toggle);
        canvas.box(sb,left+(on?20:2),top+5,16,16,on?BACKGROUND:Color.WHITE);
    }

    private float renderSettings(SpriteBatch sb, float top) {
        float p=32;
        settingLabel(sb,"搜索",top);
        segmented(sb,top,new String[]{"标准","高速","连胜"},highSpeed?1:streakMode?2:0,this::setSearchTier); top+=p;
        settingLabel(sb,"自动换药",top); onOff(sb,top,autoPotionRewards,this::togglePotionRewards); top+=p;
        if(canCreditRest()) {
            settingLabel(sb,"羽毛回血",top);
            segmented(sb,top,new String[]{"是","仅必经火堆"},nextRest?0:1,index -> { nextRest=index==0; recalculate("rest_setting"); }); top+=p;
        }
        settingLabel(sb,"Boss 快速收尾",top); onOff(sb,top,bossFastFinishEnabled,this::toggleBossFinish); top+=p;
        settingLabel(sb,"辉眼模式",top); onOff(sb,top,Foresight.brightEyeMode,this::toggleBrightEye); top+=p;
        settingLabel(sb,"持续记录",top); onOff(sb,top,backend.runLog(),this::toggleRunLog); top+=p;
        settingLabel(sb,"语言",top);
        boolean english="en".equals(I18n.language());
        segmented(sb,top,new String[]{"简体中文","English"},english?1:0,index -> toggleLanguage()); top+=p;
        settingLabel(sb,"界面缩放",top);
        float right=WIDTH-PAD;
        canvas.button(sb,right-28,top,28,26,SECONDARY,"＋",.8f,Color.WHITE,() -> changeScale(canvas.uiScale+.1f));
        canvas.button(sb,right-90,top,58,26,null,Math.round(canvas.uiScale*100)+"%",.78f,Color.WHITE,() -> changeScale(1));
        canvas.button(sb,right-122,top,28,26,SECONDARY,"−",.8f,Color.WHITE,() -> changeScale(canvas.uiScale-.1f)); top+=p;
        settingLabel(sb,"自动战斗快捷键",top);
        canvas.button(sb,right-64,top,64,26,SECONDARY,bindingKey?"…":Input.Keys.toString(autoKey),.76f,ACCENT,() -> bindingKey=true); top+=p;
        if(bindingKey) top=lines(sb,I18n.t("按 F1-F12 绑定；F6/F7/F8/F9 保留；Esc 取消"),top-4,.72f,ACCENT);
        settingLabel(sb,"SuperFastMode",top);
        String fast=Loader.isModLoaded("superfastmode")?"×"+superFastShown():I18n.t("未安装");
        float fw=canvas.textWidth(fast,.78f);
        canvas.label(sb,fast,right-fw,top+5,fw+2,.78f,superFastSpeed()>SUPER_FAST_TESTED?DANGER:MUTED); top+=p;
        canvas.line(sb,PAD,top,WIDTH-2*PAD);
        return lines(sb,I18n.t("F6 导出日志 · F8 收起 · F9 停止")+" · "+Input.Keys.toString(autoKey)+" "+I18n.t("执行整场"),top+8,.72f,MUTED);
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
            case -1: pilot.select(0); setMode(RunMode.STANDBY); break;
            case -2: pilot.select(0); setMode(RunMode.COMBAT_AUTO); break;
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
