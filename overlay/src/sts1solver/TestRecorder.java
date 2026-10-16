package sts1solver;

import basemod.BaseMod;
import basemod.interfaces.OnStartBattleSubscriber;
import basemod.interfaces.PostBattleSubscriber;
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

/** Test mode (mod setting): every card played, potion used and turn ended, with the state before it, appended one JSON line at a time to
 *  sts1-test-SEED.jsonl on the desktop. A new run deletes the previous test file, so only the latest run is kept. */
public class TestRecorder implements OnStartBattleSubscriber, PostBattleSubscriber {
    static volatile boolean enabled;
    private static long fileSeed;
    private static Path file;

    static void subscribe() { BaseMod.subscribe(new TestRecorder()); }

    @Override public void receiveOnBattleStart(AbstractRoom room) {
        JsonObject o = facts();
        o.addProperty("room", room.getClass().getSimpleName());
        write("battle_start", o);
    }

    @Override public void receivePostBattle(AbstractRoom room) { write("battle_end", facts()); }

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
        if (!enabled || AbstractDungeon.player == null) return;
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
            o.addProperty("kind", kind);
            o.addProperty("time", System.currentTimeMillis());
            Files.write(file, (o + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) {   // recording must never break the game
            System.err.println("[STS1TestMode] " + failure);
        }
    }

    @SpirePatch(clz = AbstractPlayer.class, method = "useCard")
    public static class Played {
        @SpirePrefixPatch public static void before(AbstractPlayer __instance, AbstractCard c, AbstractMonster m, int energyOnUse) {
            if (!enabled) return;
            JsonObject o = facts();
            o.addProperty("card", c.cardID + (c.timesUpgraded > 0 ? "+" : ""));
            o.addProperty("cost", c.costForTurn);
            o.addProperty("target", m == null ? -1 : AbstractDungeon.getMonsters().monsters.indexOf(m));
            o.addProperty("autoplay", c.isInAutoplay);
            write("card_play", o);
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.ui.buttons.EndTurnButton.class, method = "disable", paramtypez = {boolean.class})
    public static class Ended {
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.ui.buttons.EndTurnButton __instance, boolean isEnemyTurn) {
            if (enabled && isEnemyTurn) write("end_turn", facts());
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.ui.panels.TopPanel.class, method = "destroyPotion")
    public static class Potion {
        @SpirePrefixPatch public static void before(com.megacrit.cardcrawl.ui.panels.TopPanel __instance, int slot) {
            if (!enabled || AbstractDungeon.player == null || slot < 0 || slot >= AbstractDungeon.player.potions.size()) return;
            JsonObject o = facts();
            o.addProperty("slot", slot);
            o.addProperty("potion", AbstractDungeon.player.potions.get(slot).ID);
            write("potion", o);
        }
    }
}
