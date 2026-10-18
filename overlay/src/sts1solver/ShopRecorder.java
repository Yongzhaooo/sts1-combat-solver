package sts1solver;

import basemod.BaseMod;
import basemod.abstracts.CustomSavable;
import basemod.interfaces.PostUpdateSubscriber;
import communicationmod.CommandExecutor;
import com.google.gson.*;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.ShopRoom;
import com.megacrit.cardcrawl.shop.ShopScreen;

/** Observe game-owned state; never click, confirm or change a purchase. */
public final class ShopRecorder implements PostUpdateSubscriber, CustomSavable<JsonObject> {
    private static final ShopHistory history = new ShopHistory();
    private static AbstractPlayer owner;
    private static JsonObject pendingSave;

    static void subscribe() {
        ShopRecorder recorder = new ShopRecorder();
        BaseMod.subscribe(recorder);
        BaseMod.addSaveField("sts1solver:shops", recorder);
    }

    static JsonObject snapshot() { return history.snapshot(String.valueOf(Settings.seed)); }

    @Override public JsonObject onSave() { return snapshot(); }
    @Override public void onLoad(JsonObject value) { pendingSave = value; owner = null; }

    @Override public void receivePostUpdate() {
        if (!CommandExecutor.isInDungeon() || AbstractDungeon.player == null || AbstractDungeon.getCurrMapNode() == null) return;
        try {
            String seed = String.valueOf(Settings.seed);
            AbstractPlayer player = AbstractDungeon.player;
            if (owner != player) {
                owner = player;
                history.restore(seed, pendingSave);
                pendingSave = null;
            }
            if (!(AbstractDungeon.getCurrRoom() instanceof ShopRoom)) { history.close(); return; }
            JsonObject state = new JsonObject();
            state.addProperty("floor", AbstractDungeon.floorNum);
            state.addProperty("act", AbstractDungeon.actNum);
            state.addProperty("gold", player.gold);
            state.addProperty("hp", player.currentHealth);
            state.addProperty("max_hp", player.maxHealth);
            state.addProperty("screen", String.valueOf(AbstractDungeon.screen));
            state.addProperty("previous_screen", String.valueOf(AbstractDungeon.previousScreen));
            state.addProperty("purge_cost", ShopScreen.actualPurgeCost);
            state.addProperty("purge_available", AbstractDungeon.shopScreen.purgeAvailable);
            state.addProperty("confirm", AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID
                    && AbstractDungeon.gridSelectScreen.confirmScreenUp);
            JsonArray selected = new JsonArray();
            for (AbstractCard card : AbstractDungeon.gridSelectScreen.selectedCards) selected.add(card.uuid.toString());
            state.add("selected_uuids", selected);
            JsonArray deck = new JsonArray(), relics = new JsonArray(), potions = new JsonArray();
            for (AbstractCard card : player.masterDeck.group) {
                JsonObject item = new JsonObject();
                item.addProperty("id", card.cardID);
                item.addProperty("uuid", card.uuid.toString());
                item.addProperty("upgrades", card.timesUpgraded);
                item.addProperty("misc", card.misc);
                deck.add(item);
            }
            player.relics.forEach(relic -> {
                JsonObject item = new JsonObject(); item.addProperty("id", relic.relicId);
                item.addProperty("counter", relic.counter); relics.add(item);
            });
            player.potions.forEach(potion -> potions.add(potion.ID));
            state.add("deck", deck); state.add("relics", relics); state.add("potions", potions);
            history.observe(seed, AbstractDungeon.actNum + ":" + AbstractDungeon.floorNum, state);
        } catch (Exception failure) { System.err.println("[STS1ShopHistory] " + failure); }
    }
}
