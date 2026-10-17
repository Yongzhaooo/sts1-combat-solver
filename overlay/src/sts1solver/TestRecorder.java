package sts1solver;

import basemod.BaseMod;
import basemod.interfaces.OnStartBattleSubscriber;
import basemod.interfaces.PostBattleSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import communicationmod.GameStateConverter;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.google.gson.*;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Always keep recent battles in memory for F6; test mode additionally writes the current run to a desktop JSONL. */
public class TestRecorder implements OnStartBattleSubscriber, PostBattleSubscriber, PostUpdateSubscriber {
    static volatile boolean enabled;
    private static long fileSeed;
    private static Path file;
    private static final BattleHistory history = new BattleHistory();
    private static String selectionKey;

    static JsonObject recentBattles() { return history.snapshot(String.valueOf(Settings.seed)); }

    static void subscribe() { BaseMod.subscribe(new TestRecorder()); }

    @Override public void receiveOnBattleStart(AbstractRoom room) {
        JsonObject o = facts();
        o.addProperty("room", room.getClass().getSimpleName());
        write("battle_start", o);
    }

    @Override public void receivePostBattle(AbstractRoom room) { write("battle_end", facts()); }

    @Override public void receivePostUpdate() {
        if (AbstractDungeon.player != null && AbstractDungeon.player.currentHealth <= 0
                && !history.needsRoot(Long.toString(Settings.seed))) write("battle_end", facts());
    }

    private static JsonArray identities(CardGroup group) {
        JsonArray out = new JsonArray();
        for (AbstractCard card : group.group) out.add(card.uuid.toString());
        return out;
    }

    private static void selection(String screen, CardGroup selected) {
        if (AbstractDungeon.player == null || AbstractDungeon.getCurrRoom() == null
                || AbstractDungeon.getCurrRoom().phase != AbstractRoom.RoomPhase.COMBAT) return;
        JsonArray hand = identities(AbstractDungeon.player.hand), choices = identities(selected);
        boolean open = AbstractDungeon.screen.toString().equals(screen);
        String key = screen + open + hand + choices;
        if (key.equals(selectionKey)) return;
        selectionKey = key;
        JsonObject event = facts();
        event.addProperty("screen", screen);
        event.addProperty("screen_open", open);
        event.add("selected", choices);
        event.add("hand_uuids", hand);
        write("selection", event);
    }

    private static JsonArray cards(CardGroup group) {
        JsonArray a = new JsonArray();
        for (AbstractCard c : group.group) a.add(c.cardID + (c.timesUpgraded > 0 ? "+" + (c.timesUpgraded > 1 ? c.timesUpgraded : "") : ""));
        return a;
    }

    private static JsonObject facts() {
        JsonObject o = new JsonObject();
        AbstractPlayer p = AbstractDungeon.player;
        o.addProperty("floor", AbstractDungeon.floorNum);
        o.addProperty("turn", GameActionManager.turn);
        o.addProperty("hp", p.currentHealth);
        o.addProperty("max_hp", p.maxHealth);
        o.addProperty("block", p.currentBlock);
        o.addProperty("energy", EnergyPanel.totalCount);
        o.add("hand", cards(p.hand));
        o.add("draw", cards(p.drawPile));
        o.add("discard", cards(p.discardPile));
        o.add("exhaust", cards(p.exhaustPile));
        JsonArray ms = new JsonArray();
        if (AbstractDungeon.getMonsters() != null) for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
            JsonObject v = new JsonObject();
            v.addProperty("id", m.id);
            v.addProperty("hp", m.currentHealth);
            v.addProperty("block", m.currentBlock);
            v.addProperty("intent", String.valueOf(m.intent));
            ms.add(v);
        }
        o.add("monsters", ms);
        o.addProperty("solver_driving", SolverMod.solverDriving());
        return o;
    }

    static synchronized void write(String kind, JsonObject o) {
        if (AbstractDungeon.player == null) return;
        o.addProperty("kind", kind);
        o.addProperty("time", System.currentTimeMillis());
        if (kind.equals("battle_start")) selectionKey = null;
        try {
            JsonObject root = null;
            if (!kind.equals("battle_start") && history.needsRoot(Long.toString(Settings.seed)))
                root = new JsonParser().parse(GameStateConverter.getCommunicationState()).getAsJsonObject();
            history.record(Long.toString(Settings.seed), o, root);
        } catch (Exception failure) {
            o.addProperty("root_error", failure.toString());
            history.record(Long.toString(Settings.seed), o, null);
        }
        if (!enabled) return;
        try {
            if (file == null || fileSeed != Settings.seed) {
                Path dir = SolverMod.desktop();
                try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "sts1-test-*.jsonl")) {
                    for (Path p : old) Files.deleteIfExists(p);   // only the latest run is kept
                }
                fileSeed = Settings.seed;
                file = dir.resolve("sts1-test-" + Long.toUnsignedString(Settings.seed) + ".jsonl");
                JsonObject start = new JsonObject();
                start.addProperty("kind", "run");
                start.addProperty("seed", Long.toUnsignedString(Settings.seed));
                start.addProperty("character", String.valueOf(AbstractDungeon.player.chosenClass));
                start.addProperty("ascension", AbstractDungeon.ascensionLevel);
                start.add("metadata", DecisionContext.metadata());
                Files.write(file, (start + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.write(file, (o + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) {   // recording must never break the game
            System.err.println("[STS1TestMode] " + failure);
        }
    }

    @SpirePatch(clz = AbstractPlayer.class, method = "useCard")
    public static class Played {
        @SpirePrefixPatch public static void before(AbstractPlayer __instance, AbstractCard c, AbstractMonster m, int energyOnUse) {
            JsonObject o = facts();
            o.addProperty("card", c.cardID + (c.timesUpgraded > 0 ? "+" : ""));
            o.addProperty("card_uuid", c.uuid.toString());
            o.addProperty("cost", c.costForTurn);
            o.addProperty("target", m == null ? -1 : AbstractDungeon.getMonsters().monsters.indexOf(m));
            o.addProperty("autoplay", c.isInAutoplay);
            write("card_play", o);
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.ui.buttons.EndTurnButton.class, method = "disable", paramtypez = {boolean.class})
    public static class Ended {
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.ui.buttons.EndTurnButton __instance, boolean isEnemyTurn) {
            if (AbstractDungeon.player != null && isEnemyTurn) write("end_turn", facts());
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.ui.panels.TopPanel.class, method = "destroyPotion")
    public static class Potion {
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.ui.panels.TopPanel __instance, int slot) {
            if (AbstractDungeon.player == null || slot < 0 || slot >= AbstractDungeon.player.potions.size()) return;
            JsonObject o = facts();
            o.addProperty("slot", slot);
            o.addProperty("potion", AbstractDungeon.player.potions.get(slot).ID);
            o.addProperty("removal_only", true); // The game also calls destroyPotion when discarding a bottle.
            write("potion", o);
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.screens.select.HandCardSelectScreen.class, method = "update")
    public static class HandSelected {
        @SpirePostfixPatch public static void after() {
            selection("HAND_SELECT", AbstractDungeon.handCardSelectScreen.selectedCards);
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.screens.select.GridCardSelectScreen.class, method = "update")
    public static class GridSelected {
        @SpirePostfixPatch public static void after() {
            if (AbstractDungeon.player == null || AbstractDungeon.getCurrRoom() == null
                    || AbstractDungeon.getCurrRoom().phase != AbstractRoom.RoomPhase.COMBAT) return;
            CardGroup selected = new CardGroup(CardGroup.CardGroupType.UNSPECIFIED);
            selected.group.addAll(AbstractDungeon.gridSelectScreen.selectedCards);
            selection("GRID", selected);
        }
    }
}
