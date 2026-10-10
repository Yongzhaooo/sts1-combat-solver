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
    private final ConcurrentLinkedQueue<JsonObject> debugReplies = new ConcurrentLinkedQueue<>();
    private boolean researchRecording;
    private String debugMessage = "";
    private boolean runLog = true;
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
    // Compact combat panel: summary card left, five route rows right, then actions and toggles.
    private static final float PANEL_WIDTH = 640;
    private static final float PANEL_HEIGHT = 346;
    private static final float ROUTE_LEFT = 248, BODY_TOP = 76, BODY_ROW = 30, NOTE_TOP = 234;
    private static final int BODY_ROWS = 5;
    private static final float ACTIONS_TOP = 262, TOGGLES_TOP = 306;
    private static final float PILOT_WIDTH = 440, PILOT_LIST_TOP = 72, PILOT_ROW = 30;
    private static final int PILOT_ROWS = 5;
    private static final float ACTION_SPACING = (PANEL_WIDTH - 24) / 6;
    private boolean settingsOpen;
    private float x = -1, y = -1, dx, dy;
    private float uiScale = 1, fontScale;
    private com.badlogic.gdx.graphics.g2d.NinePatch rounded;
    private com.badlogic.gdx.graphics.g2d.TextureRegion routePixel;
    private final Color border = new Color(.20f,.26f,.34f,1);
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
    private Distill2 outsideModel;
    private OutsidePacket outsidePacket;
    private OutsidePacket outsideStatePacket;
    private Distill2.Result outsideScores;
    private AbstractPlayer outsidePlayer;
    private String outsideNotice = "";
    private int outsideMode; // 0: off, 1: confirm each outside action, 2: auto pilot.
    private boolean outsidePrompt, outsideConfirm, outsideAwaiting;
    private boolean outsidePilotRun, outsideFailed; // the compact pilot card replaces the panel outside combat.
    private int outsideChosen, outsideScroll;
    private long outsideScoredAt, outsideSubmittedAt, outsideWaitSince;
    private int outsideRetries;
    private List<OutsidePacket.Step> routePlan = java.util.Collections.emptyList();
    private String routeKey = "";
    private int skippedCardFloor = -1, skippedCards;
    // Master-deck watch: uuid -> card and its upgrade count; changes are shown under the deck icon.
    private final Map<UUID,AbstractCard> deckCards = new HashMap<>();
    private final Map<UUID,Integer> deckUpgrades = new HashMap<>();
    private AbstractPlayer deckPlayer;
    private final List<AbstractCard> deckShown = new ArrayList<>();
    private final List<String> deckVerbs = new ArrayList<>();
    private long deckShownUntil;
    private boolean shopLeft;
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
        EventRules.live = true; // hidden-information event rules read the running game
        try (InputStream stream = getClass().getResourceAsStream("/solver.properties")) {
            Properties build = new Properties();
            if (stream != null) build.load(stream);
            researchRecording = Boolean.parseBoolean(build.getProperty("research_recording", "false"));
        } catch (IOException failure) { System.err.println("[STS1Solver] " + failure); }
        try {
            Properties defaults = new Properties();
            defaults.setProperty("language", "zh");
            defaults.setProperty("uiScale", "1");
            defaults.setProperty("autoKey", Integer.toString(Input.Keys.F10));
            defaults.setProperty("brightEyeMode", "true");
            defaults.setProperty("autoPotionRewards", "true");
            defaults.setProperty("runAutoEnabled", "true");
            defaults.setProperty("runLog", "true");
            config = new SpireConfig("STS1CombatSolver", "config", defaults);
            I18n.setLanguage(config.getString("language"));
            uiScale = PanelSize.preference(config.getString("uiScale"));
            status = I18n.t("等待进入战斗");
            backendPhase = I18n.t("等待后台接收");
            autoKey = config.getInt("autoKey");
            Foresight.brightEyeMode = config.getBool("brightEyeMode");
            autoPotionRewards = config.getBool("autoPotionRewards");
            runAutoEnabled = config.getBool("runAutoEnabled");
            runLog = config.getBool("runLog");
            if (!validAutoKey(autoKey)) autoKey = Input.Keys.F10;
        } catch (IOException failure) { error = I18n.t("快捷键配置读取失败：") + failure.getMessage(); }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            voiceNotes.shutdown();
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
        boolean fold = !expandOnScreen(screen, room != null && combat(), event)
            || (outsideMode == 2 && !combat()); // the pilot's status fits the folded header.
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

    private float panelWidth() { return pilotCard() ? PILOT_WIDTH : collapsed ? 300 : PANEL_WIDTH; }
    private float panelHeight() { return pilotCard() ? pilotHeight() : collapsed ? 48 : PANEL_HEIGHT; }

    private boolean validAutoKey(int key) {
        return key >= Input.Keys.F1 && key <= Input.Keys.F12 && key != Input.Keys.F6 && key != Input.Keys.F7 && key != Input.Keys.F8 && key != Input.Keys.F9;
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
            if (waitForMonsterExit(AbstractDungeon.screen, monster.isDying, monster.isDead,
                    monster.halfDead, monster.isEscaping, monster.escaped)) return false;
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
    static boolean actionPausedByScreen(AbstractDungeon.CurrentScreen screen) {
        return screen != AbstractDungeon.CurrentScreen.NONE
            && screen != AbstractDungeon.CurrentScreen.HAND_SELECT
            && screen != AbstractDungeon.CurrentScreen.GRID
            && screen != AbstractDungeon.CurrentScreen.CARD_REWARD;
    }
    static boolean waitForMonsterExit(AbstractDungeon.CurrentScreen screen, boolean dying,
                                      boolean dead, boolean halfDead, boolean escaping, boolean escaped) {
        // AbstractDungeon skips room.update on these screens unless peeking.
        // A killing Headbutt must choose a card before death animations can resume.
        if (screen == AbstractDungeon.CurrentScreen.GRID
                || screen == AbstractDungeon.CurrentScreen.CARD_REWARD) return false;
        return pendingMonsterDeath(dying, dead, halfDead) || (escaping && !escaped);
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

    private void toggleCombatMode() {
        if(outsideMode==0 && !runAuto && auto) { cancel(true); return; }
        if(outsideMode!=0) {
            chooseOutsideMode(0);
            runAuto=false; // Switching from the pilot starts combat-only automation.
        }
        toggleRunAuto();
    }

    private void togglePilotMode() {
        if(outsideMode==2)stopAll();
        else chooseOutsideMode(2);
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
        outsideMode=0; outsidePrompt=false; outsidePacket=null; outsideStatePacket=null; outsideScores=null;
        outsideConfirm=false; outsideAwaiting=false;
        outsideNotice=I18n.t("全自动已停止；现在由你接管");
        autoPotionRewards=false;
        cancel(true);
    }

    private void chooseOutsideMode(int mode) {
        outsidePrompt=false; outsideMode=mode; outsidePacket=null; outsideStatePacket=null; outsideScores=null;
        outsideConfirm=false; outsideAwaiting=false; outsideFailed=false;
        if(mode==0) { outsidePilotRun=false; outsideNotice=I18n.t("局外决策由你操作"); return; }
        outsidePilotRun=true;
        outsideNotice=mode==1?I18n.t("局外步进：每步等你确认；战斗自动执行")
            :I18n.t("全自动爬塔已开启 · F9 随时接管");
        runAuto=true; runAutoPlayer=AbstractDungeon.player;
        auto=combat(); paused=false; decisionDirty=true;
    }

    /**
     * The original game keeps a skipped card reward listed and lets the merchant be reopened;
     * remember both per floor so the pilot moves on instead of looping.
     */
    private JsonObject outsideVisible() throws Exception {
        if(skippedCardFloor!=AbstractDungeon.floorNum) {
            skippedCardFloor=AbstractDungeon.floorNum; skippedCards=0; shopLeft=false;
        }
        JsonObject visible=DecisionContext.visible();
        visible.addProperty("skipped_card_rewards",skippedCards);
        visible.addProperty("shop_left",shopLeft);
        return visible;
    }

    private void updateOutside() {
        if(!CommandExecutor.isInDungeon() || AbstractDungeon.player==null || !supported()) {
            // Act transitions leave the dungeon for a few frames; keep the chosen mode. A new run
            // (or a reload) brings a new player object, which asks again below.
            outsidePacket=null; outsideStatePacket=null; outsideScores=null; outsideAwaiting=false; return;
        }
        if(outsidePlayer!=AbstractDungeon.player) {
            outsidePlayer=AbstractDungeon.player; outsidePrompt=true; outsideMode=0;
            outsidePacket=null; outsideStatePacket=null; outsideScores=null; outsideAwaiting=false;
            outsidePilotRun=false; outsideFailed=false;
            outsideNotice=I18n.t("新一局：要开启自动爬塔吗？F9 随时接管");
        }
        if(outsidePrompt) return;
        if(outsideMode==0 || combat() || AbstractDungeon.player.isDead || rewardPotionPending!=null) return;
        if(!GameStateListener.isWaitingForCommand()) return;
        try {
            JsonObject state=capture();
            if(!state.get("ready_for_command").getAsBoolean())return;
            JsonObject visible=outsideVisible();
            if(outsideModel==null)outsideModel=Distill2.load();
            OutsidePacket latest=OutsidePacket.build(outsideModel,state,visible);
            outsideWaitSince=0;
            if(outsideAwaiting) {
                if(latest.sameDecision(outsideStatePacket)) {
                    long waited=System.currentTimeMillis()-outsideSubmittedAt;
                    // A click on a button that is still fading in is ignored by the game: try again.
                    if(outsideMode==2 && waited>3000 && outsideRetries<3) {
                        outsideRetries++; outsideAwaiting=false; return;
                    }
                    if(waited>10000)
                        throw new IllegalStateException("局外动作未完成；请手动接管");
                    return;
                }
                outsideAwaiting=false; outsideRetries=0;
            }
            if(outsidePacket==null || !latest.sameDecision(outsidePacket)) {
                outsidePacket=latest;
                outsideScores=outsidePacket.policyScore();
                outsideStatePacket=outsidePacket; outsideScoredAt=System.currentTimeMillis();
                outsideConfirm=false; outsideChosen=outsideScores.best; outsideScroll=0;
            }
            if((outsideMode==2 && System.currentTimeMillis()-outsideScoredAt>=600)
                    || (outsideMode==1 && outsideConfirm)) {
                OutsidePacket.Choice choice=outsidePacket.choices.get(outsideChosen);
                String command=choice.command, verb=command.split(" ")[0];
                if(!CommandExecutor.isCommandAvailable(verb))
                    throw new IllegalStateException("局外命令已不可用："+command);
                if(verb.equals("choose")) {
                    int index=Integer.parseInt(command.substring(7));
                    if(index<0 || index>=communicationmod.ChoiceScreenUtils.getCurrentChoiceList().size())
                        throw new IllegalStateException("局外候选已变化："+command);
                }
                JsonObject again=capture();
                if(!again.get("ready_for_command").getAsBoolean()
                        || !OutsidePacket.build(outsideModel,again,outsideVisible())
                            .sameDecision(outsidePacket)) {
                    outsidePacket=null; outsideScores=null; return;
                }
                if(!CommandExecutor.executeCommand(command))
                    throw new IllegalStateException("局外动作未提交："+command);
                GameStateListener.registerCommandExecution();
                String screenType=visible.get("screen_type").getAsString();
                if(verb.equals("skip") && screenType.equals("CARD_REWARD"))skippedCards++;
                if(verb.equals("leave") && screenType.equals("SHOP_SCREEN"))shopLeft=true;
                try { startBackend(); diagnostic("outside",screenType+" | "+choice.label+" | "+command
                    +" | "+outsidePacket.detail()+(outsidePacket.ruleNote==null?" | 网络":" | 规则："+outsidePacket.ruleNote),null); }
                catch(IOException ignored) { }
                outsideNotice=I18n.t("已执行：")+choice.label
                    +(outsidePacket.ruleNote==null?"":" · "+I18n.t(outsidePacket.ruleNote));
                outsidePacket=null; outsideScores=null; outsideConfirm=false;
                outsideAwaiting=true; outsideSubmittedAt=System.currentTimeMillis();
            }
        } catch(Exception failure) {
            long now=System.currentTimeMillis();
            if(failure instanceof OutsidePacket.NotReady) {
                if(outsideWaitSince==0)outsideWaitSince=now;
                if(now-outsideWaitSince<10000)return; // UI transition frames settle within a few hundred ms.
            }
            outsideWaitSince=0; outsideFailed=true;
            outsideMode=0; outsidePacket=null; outsideScores=null; outsideAwaiting=false;
            runAuto=false; auto=false;
            outsideNotice=I18n.t("全自动暂停，请手动接管：")+failure.getMessage();
            System.err.println("[STS1AutoPilot] "+failure);
            collapsed=false;
        }
    }

    /** Re-plan the drawn route whenever the map is open and the run state or chosen next node moved. */
    private void updateRoute() {
        if(!CommandExecutor.isInDungeon() || !supported() || AbstractDungeon.map==null
                || AbstractDungeon.screen!=AbstractDungeon.CurrentScreen.MAP
                || AbstractDungeon.getCurrMapNode()==null)return;
        int firstX=outsidePacket==null?-1:outsidePacket.mapTargetX(outsideChosen);
        com.megacrit.cardcrawl.map.MapRoomNode at=AbstractDungeon.getCurrMapNode();
        AbstractPlayer player=AbstractDungeon.player;
        StringBuilder key=new StringBuilder().append(AbstractDungeon.actNum).append('/')
            .append(AbstractDungeon.floorNum).append('/').append(at.x).append(',').append(at.y).append('/')
            .append(firstX).append('/').append(player.currentHealth).append('/').append(player.maxHealth)
            .append('/').append(player.gold).append('/').append(player.masterDeck.size()).append('/')
            .append(player.relics.size());
        key.append('/').append(Settings.hasEmeraldKey).append('/').append(Settings.hasRubyKey);
        for(AbstractCard card:player.masterDeck.group)key.append('/').append(card.cardID).append('+').append(card.timesUpgraded);
        for(com.megacrit.cardcrawl.relics.AbstractRelic relic:player.relics)key.append('/').append(relic.relicId);
        for(com.megacrit.cardcrawl.potions.AbstractPotion potion:player.potions)key.append('/').append(potion.ID);
        if(key.toString().equals(routeKey))return;
        // Recalculate only when public route inputs change; no simulated combat or future rewards.
        routeKey=key.toString();
        try {
            if(outsideModel==null)outsideModel=Distill2.load();
            routePlan=OutsidePacket.plannedRoute(outsideModel,capture(),DecisionContext.visible(),firstX);
        } catch(Exception failure) {
            routePlan=java.util.Collections.emptyList();
            System.err.println("[STS1AutoPilot] route: "+failure);
        }
    }

    /** Diff the master deck each frame: obtained, removed and upgraded cards flash for one real second. */
    private void updateDeckChanges() {
        AbstractPlayer player=CommandExecutor.isInDungeon()?AbstractDungeon.player:null;
        if(player==null)return;
        boolean fresh=player!=deckPlayer; // a new run or a reload: take a silent snapshot.
        if(fresh) { deckPlayer=player; deckCards.clear(); deckUpgrades.clear(); }
        long now=System.currentTimeMillis();
        if(now>deckShownUntil) { deckShown.clear(); deckVerbs.clear(); }
        Set<UUID> present=new HashSet<>();
        for(AbstractCard card:player.masterDeck.group) {
            present.add(card.uuid);
            Integer before=deckUpgrades.put(card.uuid,card.timesUpgraded);
            deckCards.put(card.uuid,card);
            if(fresh)continue;
            if(before==null)showDeckChange(card,"获得",now);
            else if(card.timesUpgraded>before)showDeckChange(card,"强化",now);
        }
        for(Iterator<Map.Entry<UUID,AbstractCard>> it=deckCards.entrySet().iterator();it.hasNext();) {
            Map.Entry<UUID,AbstractCard> entry=it.next();
            if(present.contains(entry.getKey()))continue;
            if(!fresh)showDeckChange(entry.getValue(),"删除",now);
            deckUpgrades.remove(entry.getKey()); it.remove();
        }
    }
    private void showDeckChange(AbstractCard card, String verb, long now) {
        if(!outsidePilotRun)return;
        AbstractCard copy=card.makeStatEquivalentCopy();
        copy.drawScale=copy.targetDrawScale=.5f;
        deckShown.add(copy); deckVerbs.add(verb);
        deckShownUntil=now+1000;
    }
    private void renderDeckChanges(SpriteBatch sb) {
        if(deckShown.isEmpty() || System.currentTimeMillis()>deckShownUntil || AbstractDungeon.topPanel==null)return;
        float s=Settings.scale, step=165*s;
        float cx=Math.min(AbstractDungeon.topPanel.deckHb.cX,Settings.WIDTH-85*s);
        float cy=AbstractDungeon.topPanel.deckHb.cY-AbstractDungeon.topPanel.deckHb.height/2-130*s;
        for(int i=0;i<deckShown.size();i++) {
            AbstractCard card=deckShown.get(i);
            card.current_x=card.target_x=cx-i*step;
            card.current_y=card.target_y=cy;
            card.render(sb);
            String verb=deckVerbs.get(i);
            mapCaption(sb,I18n.t(verb),card.current_x-24*s,cy+125*s,
                verb.equals("删除")?danger:verb.equals("强化")?accent:Color.WHITE);
        }
        sb.setColor(Color.WHITE);
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
        }
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
        if (!runLog) sendRunLogSetting();
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
            if(AbstractDungeon.player!=null){record.addProperty("seed",Long.toString(Settings.seed));record.addProperty("floor",AbstractDungeon.floorNum);}
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
        if(outsideMode!=0) {
            outsideMode=0; outsidePacket=null; outsideScores=null; outsideAwaiting=false; outsideFailed=true;
            outsideNotice=I18n.t("全自动暂停，请手动接管：")+message;
        }
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

    /** One json for the current seed, written to the desktop so the user can send it as is. */
    private void exportRun() {
        try {
            startBackend();
            JsonObject request = new JsonObject();
            request.addProperty("op", "export_run");
            request.addProperty("directory", desktopDirectory().toString());
            if (AbstractDungeon.player != null) request.addProperty("seed", Long.toString(Settings.seed));
            request.add("metadata", DecisionContext.metadata());
            writer.write(gson.toJson(request)); writer.newLine(); writer.flush();
            debugMessage = I18n.t("正在导出记录…");
        } catch (Exception failure) { debugMessage = I18n.t("导出失败：") + failure.getMessage(); }
    }

    /** F7: this act's map with the planned route, as a PNG on the desktop. */
    private void exportMap() {
        try {
            if (!CommandExecutor.isInDungeon() || AbstractDungeon.player == null) { debugMessage = I18n.t("当前没有地图"); return; }
            Path file = MapExport.write(desktopDirectory(), routePlan);
            debugMessage = I18n.t("地图已导出到桌面：") + file.getFileName();
            showDirectory(file.getParent());
        } catch (Exception failure) { debugMessage = I18n.t("导出失败：") + failure.getMessage(); }
    }

    private Path desktopDirectory() {
        try {
            java.io.File home = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
            if (home != null && home.isDirectory()) return home.toPath();
        } catch (Throwable ignored) { }
        return Paths.get(System.getProperty("user.home"), "Desktop");
    }

    private void toggleRunLog() {
        runLog = !runLog;
        try { if (config != null) { config.setBool("runLog", runLog); config.save(); } }
        catch (IOException failure) { debugMessage = I18n.t("设置保存失败：") + failure.getMessage(); }
        sendRunLogSetting();
        debugMessage = I18n.t(runLog ? "持续记录已开启" : "持续记录已关闭");
    }

    private void sendRunLogSetting() {
        if (writer == null) return;
        try {
            JsonObject request = new JsonObject();
            request.addProperty("op", "run_log");
            request.addProperty("enabled", runLog);
            writer.write(gson.toJson(request)); writer.newLine(); writer.flush();
        } catch (IOException failure) { System.err.println("[STS1Solver] " + failure); }
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

    /** A bug in the solver must never close the game: record the stack and keep the panel alive. */
    private long lastGuardAt;

    private void guard(String where, Throwable failure) {
        long now = System.currentTimeMillis();
        if (now - lastGuardAt < 5000) return;
        lastGuardAt = now;
        System.err.println("[STS1Solver] " + where + " failed: " + failure);
        failure.printStackTrace();
        try {
            Path directory = notesDirectory("crash-reports");
            Files.createDirectories(directory);
            java.io.StringWriter text = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(text));
            Files.write(directory.resolve("mod-error-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt"),
                (where + "\n" + text).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
        error = I18n.t("求解器内部错误，已记录：") + failure;
    }

    @Override public void receivePostUpdate() {
        try { update(); } catch (Throwable failure) { guard("update", failure); }
    }

    private void update() {
        layout();
        if (researchRecording) observeInventory();
        JsonObject debugReply;
        while ((debugReply = debugReplies.poll()) != null) {
            if (debugReply.get("status").getAsString().equals("debug_run_export")) {
                debugMessage = I18n.t("已导出到桌面：") + debugReply.get("file").getAsString();
                showDirectory(Paths.get(debugReply.get("directory").getAsString()));
            } else debugMessage = I18n.t("导出失败：") + debugReply.get("message").getAsString();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F6)) {
            if (researchRecording && !Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) && !Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) {
                if (voiceNotes.active()) voiceNotes.stop(); else startVoiceNote();
            } else exportRun();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F7)) exportMap();
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
            runAuto=runAutoEnabled || outsideMode!=0; // the pilot keeps combat automation across acts.
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
        if(!inCombat && outsideMode!=0)updatePotionRewards();
        updateOutside();
        updateRoute();
        updateDeckChanges();
        if (!inCombat) {
            status = outsidePrompt || outsideMode!=0 || !outsideNotice.isEmpty() ? outsideNotice
                : runAuto ? I18n.t("跨场自动已开 · 等待下场；其他选择由你决定") : I18n.t("选牌与路线由你决定");
            if(outsideMode==0)updatePotionRewards();
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
            if(outsideMode!=0 && offers!=null)for(JsonElement offer:offers) {
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
        com.megacrit.cardcrawl.helpers.FontHelper.renderFontLeftTopAligned(sb,font(),label,
            Settings.WIDTH*.20f,Settings.HEIGHT*.90f,accent);
    }

    private float scale() {
        return PanelSize.fit(uiScale, Settings.scale, Settings.WIDTH, Settings.HEIGHT, PANEL_WIDTH, PANEL_HEIGHT);
    }
    private void changeScale(float value) {
        uiScale = PanelSize.preference(Float.toString(value));
        layout();
        try { if (config != null) { config.setString("uiScale", Float.toString(uiScale)); config.save(); } }
        catch (IOException failure) { error = I18n.t("界面缩放保存失败：") + failure.getMessage(); }
    }
    private void layout() {
        float s = scale();
        if (x < 0) { x = 20*s; y = Settings.HEIGHT - 180*s; }
        x = Math.max(0, Math.min(x, Settings.WIDTH - panelWidth()*s));
        y = Math.max(panelHeight()*s, Math.min(y, Settings.HEIGHT));
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
        boolean over=inside(mx,my,x,y-panelHeight()*s,panelWidth()*s,panelHeight()*s);
        boolean click=InputHelper.justClickedLeft;
        if(pilotCard()) {
            if(dragging) {
                if(Gdx.input.isButtonPressed(Input.Buttons.LEFT)) { x=mx-dx; y=my-dy; layout(); }
                else dragging=false;
            }
            if(over) {
                pilotInput((mx-x)/s,(y-my)/s,click,mx,my);
                InputHelper.justClickedLeft=false; InputHelper.justReleasedClickLeft=false;
                InputHelper.justClickedRight=false; InputHelper.justReleasedClickRight=false;
                InputHelper.scrolledDown=InputHelper.scrolledUp=false;
            }
            hovered=over || dragging; return;
        }
        if (dragging) {
            if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)) { x=mx-dx; y=my-dy; layout(); }
            else dragging=false;
        }
        if (over && click) {
            float lx=(mx-x)/s, ly=(y-my)/s, w=panelWidth();
            boolean body=!collapsed && !settingsOpen, list=body && lx>=ROUTE_LEFT && ly>=BODY_TOP && ly<BODY_TOP+BODY_ROWS*BODY_ROW;
            int row=(int)((ly-BODY_TOP)/BODY_ROW);
            if (ly < 42) {
                if (!collapsed && lx >= w-118 && lx < w-54) settingsOpen=!settingsOpen;
                else if (lx > w-47) { collapsed=!collapsed; autoFolded=false; }
                else { dragging=true; dx=mx-x; dy=my-y; }
            } else if (!collapsed && settingsOpen && ly>=80 && ly<192 && (ly-80)%40<32) {
                float cw=(w-40)/2, off=lx-(24+cw);
                int setting=(int)((ly-80)/40);
                if (setting==0 && off<0) toggleBrightEye();
                else if (setting==0) changeScale(off<cw/3 ? uiScale-.1f : off>=cw*2/3 ? uiScale+.1f : 1);
                else if (setting==1) { if (off<0) bindingKey=true; else toggleLanguage(); }
                else if (off<0) toggleRunLog(); else exportRun();
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
                        clickedBranch=progress.get("id").getAsString();
                        cancel(true);
                    }
                }
            } else if (list && result!=null) {
                if (!auto && !turnOnly) toggleAuto();
            } else if (!collapsed && lx >= 16 && lx < w-16 && ly >= ACTIONS_TOP && ly <= ACTIONS_TOP+36
                    && (lx-16)%ACTION_SPACING < ACTION_SPACING-8) {
                int action=(int)((lx-16)/ACTION_SPACING);
                if (action==0) recalculate();
                else if (action==1) { runAuto=auto=turnOnly=false; executeStep(); }
                else if (action==2) {
                    if (result != null && ready()) { startingTurn=AbstractDungeon.actionManager.turn; turnOnly=true; runAuto=auto=false; }
                } else if (action==3) toggleCombatMode();
                else if (action==4) togglePilotMode();
                else stopAll();
            } else if (!collapsed && lx >= 16 && lx < w-16 && ly >= TOGGLES_TOP && ly <= TOGGLES_TOP+28) {
                float spacing=(w-24)/(canCreditRest()?4:3);
                if ((lx-16)%spacing < spacing-8) {
                    int toggle=(int)((lx-16)/spacing);
                    if (toggle==0) { budgetIndex=(budgetIndex+1)%3; recalculate(); }
                    else if (toggle==1) togglePotionRewards();
                    else if (toggle==2) toggleCombatMode();
                    else { nextRest=!nextRest; recalculate(); }
                }
            }
        }
        hovered = over || dragging;
        if (over || dragging) {
            float lx=(mx-x)/s, ly=(y-my)/s;
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

    private void toggleLanguage() {
        I18n.setLanguage("en".equals(I18n.language()) ? "zh" : "en");
        scroll = 0;
        status = I18n.t(busy ? "正在搜索整场战斗…" : "路线已就绪 · 等待你的操作");
        try {
            if (config != null) { config.setString("language", I18n.language()); config.save(); }
        } catch (IOException failure) { error = "Language save failed: " + failure.getMessage(); }
    }

    private void box(SpriteBatch sb, float left, float top, float width, float height, Color color) {
        if (rounded == null) {
            com.badlogic.gdx.graphics.Pixmap pixmap = new com.badlogic.gdx.graphics.Pixmap(24,24,com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.WHITE);
            pixmap.fillRectangle(6,0,12,24); pixmap.fillRectangle(0,6,24,12);
            for (int cx : new int[]{6,17}) for (int cy : new int[]{6,17}) pixmap.fillCircle(cx,cy,6);
            com.badlogic.gdx.graphics.Texture texture = new com.badlogic.gdx.graphics.Texture(pixmap);
            texture.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear,com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
            pixmap.dispose();
            rounded = new com.badlogic.gdx.graphics.g2d.NinePatch(texture,8,8,8,8);
        }
        float s=scale();
        sb.setColor(Color.WHITE);
        rounded.setColor(color);
        rounded.draw(sb,x+left*s,y-(top+height)*s,width*s,height*s);
    }
    private void line(SpriteBatch sb, float left, float top, float width) {
        sb.setColor(border);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG,x+left*scale(),y-top*scale(),width*scale(),scale());
        sb.setColor(Color.WHITE);
    }
    private void initFont() {
        if (uiFont == null) {
            fontGenerator = new FreeTypeFontGenerator(Gdx.files.internal("font/zhs/NotoSansMonoCJKsc-Regular.otf"));
            FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
            parameter.size = Math.max(12, Math.round(19*Settings.scale));
            fontScale = parameter.size / 19f;
            parameter.incremental = true;
            parameter.minFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
            parameter.magFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
            uiFont = fontGenerator.generateFont(parameter);
        }
    }
    BitmapFont font() { initFont(); return uiFont; }
    private void text(SpriteBatch sb, String text, float left, float top, Color color) {
        label(sb,text,left,top,(left < ROUTE_LEFT ? ROUTE_LEFT-12 : panelWidth()-16)-left,.9f,color);
    }
    private void label(SpriteBatch sb, String value, float left, float top, float width, float size, Color color) {
        initFont();
        float oldX=uiFont.getData().scaleX, oldY=uiFont.getData().scaleY;
        try {
            uiFont.getData().setScale(scale()/fontScale*size);
            String text=I18n.t(value);
            measure.setText(uiFont,text);
            while(text.length()>1 && measure.width>width*scale()) {
                text=text.substring(0,text.length()-2)+"…";
                measure.setText(uiFont,text);
            }
            FontHelper.renderFontLeftTopAligned(sb,uiFont,text,x+left*scale(),y-top*scale(),color);
        } finally { uiFont.getData().setScale(oldX,oldY); }
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
        renderOutsideRoute(sb);
        renderDeckChanges(sb);
        layout();
        if(pilotCard()) { renderPilot(sb); cursorOnTop(sb); return; }
        float w=panelWidth();
        box(sb,0,0,w,panelHeight(),border);
        box(sb,1,1,w-2,panelHeight()-2,background);
        label(sb,I18n.t("战斗求解器"),16,collapsed?7:10,collapsed?240:w-150,collapsed?.95f:1.1f,Color.WHITE);
        label(sb,collapsed?"＋":"－",w-36,collapsed?14:10,26,1,accent);
        if (collapsed) {
            label(sb,error.isEmpty()?status:I18n.backend(error),20,29,240,.65f,error.isEmpty()?muted:danger);
            sb.setColor(Color.WHITE); cursorOnTop(sb); return;
        }
        box(sb,w-118,8,64,28,settingsOpen?accent:surface);
        label(sb,I18n.t(settingsOpen?"返回":"设置"),w-108,12,48,.85f,settingsOpen?background:accent);
        String revivalWarning = result == null ? "" : revivalWarning(result);
        label(sb,revivalWarning.isEmpty()?status:revivalWarning,16,48,ROUTE_LEFT-28,.85f,
            revivalWarning.isEmpty() && error.isEmpty()?muted:danger);
        String note=researchRecording?voiceNotes.status():debugMessage;
        if (settingsOpen) renderSettings(sb,w,note);
        else renderCombatBody(sb,w,note);
        String[] labels={I18n.t("重新计算"),I18n.t("执行一步"),turnOnly?I18n.t("正在执行本回合"):I18n.t("执行本回合"),
            I18n.t(outsideMode==0 && (runAuto || auto)?"战斗自动：开":"战斗自动"),
            I18n.t(outsideMode==2?"AI全自动：开":"AI全自动"),I18n.t("停止")};
        for(int i=0;i<labels.length;i++) {
            box(sb,16+i*ACTION_SPACING,ACTIONS_TOP,ACTION_SPACING-8,36,i==0?accent:surface);
            label(sb,labels[i],26+i*ACTION_SPACING,ACTIONS_TOP+8,ACTION_SPACING-28,.8f,
                i==0?background:i==5?danger:i==4&&outsideMode==2?accent
                :i==3&&outsideMode==0&&(runAuto||auto)?accent:i==2&&result==null?muted:Color.WHITE);
        }
        List<String> toggles=new ArrayList<>(Arrays.asList(
            I18n.t("搜索预算：")+I18n.t(budgetNames[budgetIndex]),
            I18n.t("自动换药：")+I18n.t(autoPotionRewards?"开":"关"),
            I18n.t("跨场自动：")+I18n.t(runAuto?"开":"关")));
        if(canCreditRest())toggles.add(I18n.t("羽毛回血：")+I18n.t(nextRest?"是":"仅必经火堆"));
        float spacing=(w-24)/toggles.size();
        for(int i=0;i<toggles.size();i++) {
            box(sb,16+i*spacing,TOGGLES_TOP,spacing-8,28,surface);
            label(sb,toggles.get(i),24+i*spacing,TOGGLES_TOP+5,spacing-24,.8f,
                i==2?(runAuto?accent:muted):i==1?(autoPotionRewards?accent:muted):accent);
        }
        sb.setColor(Color.WHITE);
        cursorOnTop(sb);
    }

    private void renderSettings(SpriteBatch sb, float w, String note) {
        float cw=(w-40)/2, right=24+cw;
        for(int row=0;row<3;row++) for(int col=0;col<2;col++) box(sb,16+col*(cw+8),80+row*40,cw,32,surface);
        label(sb,I18n.t("辉眼模式：")+I18n.t(Foresight.brightEyeMode?"开":"关"),26,87,cw-20,.85f,accent);
        label(sb,"−",right+12,86,24,1,accent);
        label(sb,Math.round(uiScale*100)+"%",right+cw/2-22,87,60,.85f,Color.WHITE);
        label(sb,"＋",right+cw-30,86,24,1,accent);
        label(sb,I18n.t("自动战斗快捷键：")+Input.Keys.toString(autoKey),26,127,cw-20,.85f,accent);
        label(sb,I18n.t("语言：")+("en".equals(I18n.language())?"English":"简体中文"),right+10,127,cw-20,.85f,accent);
        label(sb,I18n.t("持续记录：")+" "+I18n.t(runLog?"开":"关"),26,167,cw-20,.85f,runLog?accent:muted);
        label(sb,I18n.t("导出到桌面 · F6"),right+10,167,cw-20,.85f,accent);
        label(sb,bindingKey?I18n.t("按 F1-F12 绑定；F6/F8/F9 保留；Esc 取消"):I18n.t("按种子记录牌组、战斗与选择，仅保存到本机"),
            16,204,w-32,.75f,bindingKey?accent:muted);
        boolean incompatibleMod = Loader.isModLoaded("SaveStateMod") || Loader.isModLoaded("undothespire") || Loader.isModLoaded("undobutton");
        String defaultNote = incompatibleMod
            ? I18n.t("检测到不兼容 Mod（SaveStateMod / Undo）：会破坏战斗选牌与状态同步，建议禁用")
            : I18n.t("F6 导出记录到桌面，发这一个文件即可");
        label(sb,note.isEmpty()?defaultNote:note,16,NOTE_TOP,w-32,.75f,
            note.isEmpty() && incompatibleMod?danger:muted);
    }

    private void renderCombatBody(SpriteBatch sb, float w, String note) {
        float inner=ROUTE_LEFT-48;
        box(sb,16,BODY_TOP,ROUTE_LEFT-28,BODY_ROWS*BODY_ROW-4,surface);
        if (busy) {
            long seconds=(System.currentTimeMillis()-sentAt)/1000;
            long now = System.currentTimeMillis();
            label(sb,I18n.backend(backendPhase),26,86,inner,.9f,accent);
            label(sb,I18n.t("总等待 ")+seconds+I18n.t(" 秒 · 本阶段 ")+((now-phaseAt)/1000)+I18n.t(" 秒"),26,116,inner,.8f,muted);
            label(sb,now-lastProgressAt>5000
                ? I18n.t("已 ")+((now-lastProgressAt)/1000)+I18n.t(" 秒无进度 · F9 停止")
                :progressRows==null?I18n.t("请求 #")+activeId+I18n.t(" · F9 停止"):progressSummary(),26,142,inner,.8f,muted);
        } else if (result != null) {
            boolean won=result.get("won").getAsBoolean();
            label(sb,won?I18n.t("预测胜利"):I18n.t("当前路线未找到胜利"),26,84,inner,.9f,won?accent:danger);
            label(sb,I18n.t("战后 HP"),26,108,90,.75f,muted);
            label(sb,I18n.t("等效战损"),126,108,90,.75f,muted);
            label(sb,result.get("hp").getAsString(),26,124,90,1.5f,Color.WHITE);
            label(sb,result.get("loss").getAsString(),126,124,90,1.5f,accent);
            line(sb,26,164,inner);
            label(sb,I18n.t("下一步  ")+commandLabel(),26,172,inner,.85f,Color.WHITE);
            String healing=I18n.t("战后恢复：")+result.get("recovery").getAsInt();
            if(result.getAsJsonArray("recovery_notes").size()>0)
                healing+=" · "+I18n.backend(result.getAsJsonArray("recovery_notes").get(0).getAsString());
            label(sb,healing,26,198,inner,.75f,muted);
        } else {
            label(sb,error.isEmpty()?I18n.t("由你构筑，由它求解战斗"):I18n.backend(error),26,90,inner,.85f,error.isEmpty()?accent:danger);
            label(sb,error.isEmpty()?I18n.t("仅查看建议，点击按钮后才出牌"):I18n.t("详细原因已写入后台日志"),26,124,inner,.8f,muted);
        }
        List<String> foresight=transformPreview!=null?transformPreview.lines():combat()?Collections.<String>emptyList():Foresight.lines();
        String heading=transformPreview!=null?(transformPreview.upgrade?I18n.t("星盘"):I18n.t("变化"))+I18n.t("试选 ") + transformPreview.selected.size() + "/"+transformPreview.count+I18n.t(" · 点击此处关闭")
            :Foresight.previewCount()>0?I18n.t("变化预览 · 点击此处打开试选列表")
            :branchPicker?I18n.t("输入 0～10 后回车：[")+branchInput+"]"
            :busy&&progressRows!=null?I18n.t("搜索过程 · 已完成路线可点击执行")
            :!foresight.isEmpty()?I18n.t("随机结果预测 · 滚轮翻页")
            :result!=null && result.has("manual_choice") && result.get("manual_choice").getAsBoolean()
                ?I18n.t("抢劫怪路线 · 可手动或自动执行")
            :(runAuto || auto) && result!=null && result.has("branch") && !result.get("branch").getAsString().equals("no-potion")
                ?I18n.t("自动战斗已采纳用药路线 · 点击或 F9 可接管")
            :I18n.t("推荐行动顺序 · 点击执行整条路线");
        label(sb,heading,ROUTE_LEFT,48,w-ROUTE_LEFT-16,.85f,Color.WHITE);
        float rowWidth=w-ROUTE_LEFT-16, textWidth=rowWidth-16;
        String hint="";
        Color hintColor=muted;
        if (transformPreview != null) {
            scroll=Math.max(0,Math.min(scroll,Math.max(0,foresight.size()-BODY_ROWS)));
            for (int i=scroll;i<Math.min(foresight.size(),scroll+BODY_ROWS);i++) {
                box(sb,ROUTE_LEFT,BODY_TOP+(i-scroll)*BODY_ROW,rowWidth,BODY_ROW-4,surface);
                label(sb,foresight.get(i),ROUTE_LEFT+8,BODY_TOP+5+(i-scroll)*BODY_ROW,textWidth,.85f,i<transformPreview.selected.size()?accent:muted);
            }
            hint=I18n.t("仅试选 · 滚轮翻页 · 点击已选项撤回"); hintColor=accent;
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
                box(sb,ROUTE_LEFT,BODY_TOP+(i-scroll)*BODY_ROW,rowWidth,BODY_ROW-4,surface);
                label(sb,i+" "+summary+" · "+I18n.backend(branch.get("name").getAsString()),ROUTE_LEFT+8,BODY_TOP+5+(i-scroll)*BODY_ROW,
                    textWidth,.85f,!revival.isEmpty()?danger:available?accent:muted);
            }
            hint=result.has("manual_choice") && result.get("manual_choice").getAsBoolean()
                ?I18n.t("空回车：选推荐路线；选后可开自动"):I18n.t("0 / 空回车：不交药；无输入则等待");
        } else if (result != null) {
            JsonArray rows=result.getAsJsonArray("route");
            scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-BODY_ROWS)));
            for (int i=scroll;i<Math.min(rows.size(),scroll+BODY_ROWS);i++) {
                JsonObject row=rows.get(i).getAsJsonObject();
                String label=row.get("text").getAsString();
                if (row.has("label")) label=row.get("label").getAsString();
                float top=BODY_TOP+(i-scroll)*BODY_ROW;
                box(sb,ROUTE_LEFT,top,rowWidth,BODY_ROW-4,surface);
                label(sb,String.format("%02d",i+1),ROUTE_LEFT+8,top+5,28,.8f,i==0?accent:muted);
                label(sb,I18n.backend(label),ROUTE_LEFT+40,top+4,rowWidth-88,.9f,i==0?Color.WHITE:muted);
                label(sb,"T"+row.get("turn").getAsInt(),w-56,top+5,36,.75f,muted);
            }
        } else if (busy && progressRows != null) {
            for (int i=0;i<Math.min(progressRows.size(),BODY_ROWS);i++) {
                JsonObject row=progressRows.get(i).getAsJsonObject();
                int best=row.get("best_hp").getAsInt();
                String line=(best>0?"HP "+best:I18n.t("未胜"))+" · T"+row.get("turn").getAsInt()+" · "
                    +count(row.get("simulations").getAsLong())+" · "+I18n.backend(row.get("name").getAsString());
                label(sb,line,ROUTE_LEFT+8,BODY_TOP+5+i*BODY_ROW,textWidth,.85f,row.has("ready") && row.get("ready").getAsBoolean()?accent:muted);
            }
        } else if (!foresight.isEmpty()) {
            scroll=Math.max(0,Math.min(scroll,Math.max(0,foresight.size()-BODY_ROWS)));
            for (int i=scroll;i<Math.min(foresight.size(),scroll+BODY_ROWS);i++) {
                String line=foresight.get(i);
                label(sb,line,ROUTE_LEFT+8,BODY_TOP+5+(i-scroll)*BODY_ROW,textWidth,.85f,line.startsWith(" ")?muted:accent);
            }
        } else {
            label(sb,I18n.t("发牌和动画结算后自动生成建议"),ROUTE_LEFT+8,BODY_TOP+10,textWidth,.85f,muted);
            label(sb,I18n.t("奖励牌 / 地图 / 商店始终由你操作"),ROUTE_LEFT+8,BODY_TOP+40,textWidth,.85f,muted);
        }
        if(runAuto && hint.isEmpty() && result!=null && result.has("auto_recommendation")) {
            JsonObject advice=result.getAsJsonObject("auto_recommendation");
            hint=I18n.backend(autoReason)+" · "+I18n.backend(advice.getAsJsonObject("assessment").get("summary").getAsString());
            hintColor=accent;
        }
        if(!note.isEmpty())label(sb,note,16,NOTE_TOP,ROUTE_LEFT-28,.75f,muted);
        if(!hint.isEmpty())label(sb,hint,ROUTE_LEFT,NOTE_TOP,w-ROUTE_LEFT-16,.75f,hintColor);
    }
    private void renderOutsideRoute(SpriteBatch sb) {
        if(routePlan.isEmpty() || AbstractDungeon.screen!=AbstractDungeon.CurrentScreen.MAP
                || AbstractDungeon.map==null)return;
        if(routePixel==null)routePixel=new com.badlogic.gdx.graphics.g2d.TextureRegion(ImageMaster.WHITE_SQUARE_IMG);
        List<com.megacrit.cardcrawl.map.MapRoomNode> nodes=new ArrayList<>();
        com.megacrit.cardcrawl.map.MapRoomNode current=AbstractDungeon.getCurrMapNode();
        if(current!=null && current.y>=0 && current.y<AbstractDungeon.map.size())nodes.add(current);
        int from=nodes.size();
        for(OutsidePacket.Step step:routePlan) {
            if(step.y<0 || step.y>=AbstractDungeon.map.size() || step.x<0
                    || step.x>=AbstractDungeon.map.get(step.y).size())return;
            nodes.add(AbstractDungeon.map.get(step.y).get(step.x));
        }
        float s=Settings.scale;
        for(int i=1;i<nodes.size();i++) {
            com.megacrit.cardcrawl.map.MapRoomNode a=nodes.get(i-1), b=nodes.get(i);
            float angle=(float)Math.toDegrees(Math.atan2(b.hb.cY-a.hb.cY,b.hb.cX-a.hb.cX));
            float length=(float)Math.hypot(b.hb.cX-a.hb.cX,b.hb.cY-a.hb.cY);
            boolean next=i==from;
            sb.setColor(next?1f:.38f,next?.8f:.83f,next?.25f:.72f,.8f);
            sb.draw(routePixel,a.hb.cX,a.hb.cY-4*s,0,4*s,length,8*s,1,1,angle);
        }
        for(int i=from;i<nodes.size();i++) {
            com.megacrit.cardcrawl.map.MapRoomNode node=nodes.get(i);
            boolean next=i==from;
            float share=routePlan.get(i-from).share, size=(next?150:118)*s;
            // Rule routes have deterministic rings; brightness is not a survival probability.
            sb.setColor(next?1f:.38f,next?.8f:.83f,next?.25f:.72f,next?1f:.35f+.6f*share);
            sb.draw(ImageMaster.MAP_CIRCLE_5,node.hb.cX-size/2,node.hb.cY-size/2,size,size);
        }
        com.megacrit.cardcrawl.map.MapRoomNode first=nodes.get(from), last=nodes.get(nodes.size()-1);
        mapCaption(sb,I18n.t("规则推荐下一步"),first.hb.cX+80*s,first.hb.cY+10*s,accent);
        if(last!=first)mapCaption(sb,I18n.t("开幕偏好规划 · 状态变化后可改道"),
            last.hb.cX+70*s,last.hb.cY+10*s,muted);
        sb.setColor(Color.WHITE);
    }
    private void mapCaption(SpriteBatch sb, String value, float left, float baseline, Color color) {
        initFont();
        float oldX=uiFont.getData().scaleX, oldY=uiFont.getData().scaleY;
        try {
            uiFont.getData().setScale(Settings.scale/fontScale*.85f);
            uiFont.setColor(Color.BLACK); uiFont.draw(sb,value,left+2*Settings.scale,baseline-2*Settings.scale);
            uiFont.setColor(color); uiFont.draw(sb,value,left,baseline);
        } finally { uiFont.getData().setScale(oldX,oldY); uiFont.setColor(Color.WHITE); }
    }
    /** Outside combat, a run with the pilot chosen (or the start-of-run question) gets the compact card. */
    private boolean pilotCard() {
        return (outsidePrompt || outsidePilotRun) && CommandExecutor.isInDungeon() && !combat();
    }
    private int pilotListRows() {
        return outsideMode==1 && outsidePacket!=null && outsideScores!=null
            ? Math.min(PILOT_ROWS,outsidePacket.choices.size()) : 0;
    }
    private float pilotButtonsTop() {
        int rows=pilotListRows();
        return PILOT_LIST_TOP+rows*PILOT_ROW+(rows>0?8:0);
    }
    private float pilotHeight() { return pilotButtonsTop()+38+14; }
    /** Buttons as {action, label}; actions: 2 auto, 1 step, 0 take over, 3 confirm, -1 start manual. */
    private String[][] pilotButtons() {
        if(outsidePrompt) return new String[][]{{"2","AI全自动"},{"1","逐步确认"},{"-1","由我操作"}};
        if(outsideMode==2) return new String[][]{{"1","改为逐步"},{"0","接管"}};
        if(outsideMode==1) return new String[][]{{"3","确认这一步"},{"2","AI全自动"},{"0","接管"}};
        return new String[][]{{"2","继续AI全自动"},{"1","逐步"}};
    }
    private void pilotInput(float lx, float ly, boolean click, float mx, float my) {
        int rows=pilotListRows();
        if(rows>0 && ly>=PILOT_LIST_TOP && ly<PILOT_LIST_TOP+rows*PILOT_ROW) {
            int last=Math.max(0,outsidePacket.choices.size()-rows);
            if(InputHelper.scrolledDown)outsideScroll=Math.min(last,outsideScroll+1);
            if(InputHelper.scrolledUp)outsideScroll=Math.max(0,outsideScroll-1);
            if(click) {
                int index=outsideScroll+(int)((ly-PILOT_LIST_TOP)/PILOT_ROW);
                if(index<outsidePacket.choices.size())outsideChosen=index;
            }
            return;
        }
        if(!click)return;
        if(ly<40) {
            if(lx>=PILOT_WIDTH-40 && !outsidePrompt) { // hide the card; the full panel returns.
                if(outsideMode!=0)stopAll();
                outsidePilotRun=false;
            } else { dragging=true; dx=mx-x; dy=my-y; }
            return;
        }
        float top=pilotButtonsTop();
        if(ly<top || ly>top+38)return;
        String[][] buttons=pilotButtons();
        float width=(PILOT_WIDTH-32-(buttons.length-1)*8)/buttons.length;
        int slot=(int)((lx-16)/(width+8));
        if(lx<16 || slot<0 || slot>=buttons.length || lx-16-slot*(width+8)>width)return;
        switch(Integer.parseInt(buttons[slot][0])) {
            case 3: if(outsidePacket!=null)outsideConfirm=true; break;
            case 0: stopAll(); break;
            case -1: chooseOutsideMode(0); break;
            default: chooseOutsideMode(Integer.parseInt(buttons[slot][0]));
        }
    }
    private void renderPilot(SpriteBatch sb) {
        float height=pilotHeight();
        box(sb,0,0,PILOT_WIDTH,height,border);
        box(sb,1,1,PILOT_WIDTH-2,height-2,background);
        label(sb,I18n.t("自动爬塔"),16,10,140,1.05f,Color.WHITE);
        String chip=outsidePrompt?"新一局":outsideMode==2?"AI全自动中":outsideMode==1?"逐步确认"
            :outsideFailed?"已暂停":"已接管";
        Color chipColor=outsideMode==2?accent:outsideFailed?danger:outsideMode==1?Color.WHITE:muted;
        box(sb,PILOT_WIDTH-170,9,120,24,surface);
        label(sb,I18n.t(chip),PILOT_WIDTH-160,13,100,.78f,chipColor);
        if(!outsidePrompt)label(sb,"×",PILOT_WIDTH-32,9,20,1,muted);
        label(sb,outsideNotice,16,46,PILOT_WIDTH-32,.78f,outsideFailed?danger:muted);
        int rows=pilotListRows();
        for(int row=0;row<rows;row++) {
            int i=outsideScroll+row;
            if(i>=outsidePacket.choices.size())break;
            float top=PILOT_LIST_TOP+row*PILOT_ROW;
            boolean chosen=i==outsideChosen;
            box(sb,16,top,PILOT_WIDTH-32,PILOT_ROW-4,chosen?new Color(.17f,.29f,.32f,1):surface);
            label(sb,(chosen?"● ":"  ")+outsidePacket.choices.get(i).label,24,top+5,PILOT_WIDTH-130,.8f,
                chosen?accent:Color.WHITE);
            label(sb,String.format(java.util.Locale.ROOT,"%.3f",outsideScores.scores[i]),
                PILOT_WIDTH-86,top+5,62,.76f,muted);
        }
        String[][] buttons=pilotButtons();
        float top=pilotButtonsTop(), width=(PILOT_WIDTH-32-(buttons.length-1)*8)/buttons.length;
        for(int b=0;b<buttons.length;b++) {
            int action=Integer.parseInt(buttons[b][0]);
            boolean primary=b==0 && !(action==3 && outsidePacket==null);
            float left=16+b*(width+8);
            box(sb,left,top,width,38,primary?accent:surface);
            label(sb,I18n.t(buttons[b][1]),left+12,top+9,width-20,.9f,
                primary?background:action==0?danger:action==3?muted:Color.WHITE);
        }
        sb.setColor(Color.WHITE);
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
