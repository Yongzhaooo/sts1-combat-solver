package sts1solver;

import com.google.gson.*;
import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.EventHelper;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.neow.NeowEvent;
import com.megacrit.cardcrawl.neow.NeowReward;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.shop.ShopScreen;
import communicationmod.ChoiceScreenUtils;
import communicationmod.GameStateConverter;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.InputStream;
import java.util.Properties;

/** Read-only outside-decision observations. Never serialize the game's UI object graph. */
final class DecisionContext {
    private static final Gson JSON = new Gson();
    private static Method screen, map;
    private static JsonObject provenance;

    private static void initialize() throws Exception {
        if (provenance != null) return;
        screen = GameStateConverter.class.getDeclaredMethod("getScreenState");
        map = GameStateConverter.class.getDeclaredMethod("convertMapToJson");
        screen.setAccessible(true);
        map.setAccessible(true);
        JsonObject source = new JsonObject();
        source.addProperty("game_version", CardCrawlGame.VERSION_NUM);
        Properties properties = new Properties();
        try (InputStream stream = DecisionContext.class.getResourceAsStream("/solver.properties")) {
            if (stream != null) properties.load(stream);
        }
        source.addProperty("recorder_build", properties.getProperty("recorder_build", "unknown"));
        JsonArray mods = new JsonArray();
        if (Loader.MODINFOS != null) for (ModInfo mod : Loader.MODINFOS) {
            JsonObject item = new JsonObject();
            item.addProperty("id", mod.ID);
            item.addProperty("version", String.valueOf(mod.ModVersion));
            mods.add(item);
        }
        source.add("mods", mods);
        provenance = source;
    }

    static JsonObject visible() throws Exception {
        initialize();
        JsonObject out = new JsonObject();
        out.addProperty("hp", AbstractDungeon.player.currentHealth);
        out.addProperty("max_hp", AbstractDungeon.player.maxHealth);
        out.addProperty("act", AbstractDungeon.actNum);
        out.addProperty("class", AbstractDungeon.player.chosenClass.name());
        out.addProperty("ascension", AbstractDungeon.ascensionLevel);
        out.addProperty("speedrun_under_800_seconds", CardCrawlGame.playtime < 800);
        out.addProperty("boss", AbstractDungeon.bossKey);
        out.addProperty("room", AbstractDungeon.getCurrRoom().getClass().getSimpleName());
        out.addProperty("room_phase", AbstractDungeon.getCurrRoom().phase.name());
        com.megacrit.cardcrawl.rewards.chests.AbstractChest chest=null;
        if(AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.TreasureRoom)
            chest=((com.megacrit.cardcrawl.rooms.TreasureRoom)AbstractDungeon.getCurrRoom()).chest;
        else if(AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.TreasureRoomBoss)
            chest=((com.megacrit.cardcrawl.rooms.TreasureRoomBoss)AbstractDungeon.getCurrRoom()).chest;
        if(chest!=null) {
            String kind=chest.getClass().getSimpleName();
            out.addProperty("chest_size",kind.contains("Small")?0:kind.contains("Medium")?1
                :kind.contains("Large")?2:3);
        }
        out.addProperty("screen_type", String.valueOf(ChoiceScreenUtils.getCurrentChoiceType()));
        out.addProperty("grid_from_shop", AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID
            && AbstractDungeon.previousScreen == AbstractDungeon.CurrentScreen.SHOP);
        out.addProperty("is_screen_up", AbstractDungeon.isScreenUp);
        out.add("screen_state", JSON.toJsonTree(screen.invoke(null)));
        out.add("choices", JSON.toJsonTree(ChoiceScreenUtils.getCurrentChoiceList()));
        if(AbstractDungeon.getCurrRoom().event!=null
                && AbstractDungeon.getCurrRoom().event.getClass().getSimpleName().equals("ShiningLight")) {
            JsonArray options=out.getAsJsonObject("screen_state").getAsJsonArray("options");
            if(options!=null)for(com.google.gson.JsonElement item:options) {
                String shown=item.getAsJsonObject().get("text").getAsString();
                java.util.regex.Matcher number=java.util.regex.Pattern.compile(
                    "(?i)(\\d+)\\D{0,20}(?:HP|生命)").matcher(shown);
                if(number.find()) {out.addProperty("event_hp_loss",Integer.parseInt(number.group(1)));break;}
            }
        }
        if (AbstractDungeon.getCurrRoom().event instanceof NeowEvent) {
            Field field = NeowEvent.class.getDeclaredField("rewards");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.ArrayList<NeowReward> rewards = (java.util.ArrayList<NeowReward>)
                field.get(AbstractDungeon.getCurrRoom().event);
            JsonArray options = new JsonArray();
            if (rewards != null) for (NeowReward reward : rewards) {
                JsonObject option = new JsonObject();
                option.addProperty("bonus", reward.type.name());
                option.addProperty("drawback", reward.drawback == null ? "NONE" : reward.drawback.name());
                options.add(option);
            }
            out.add("neow_options", options);
            // rewards outlive the pick; only screenNum 3 offers them, other stages are one button.
            Field stage = NeowEvent.class.getDeclaredField("screenNum");
            stage.setAccessible(true);
            out.addProperty("neow_screen", stage.getInt(AbstractDungeon.getCurrRoom().event));
        }
        out.addProperty("ruby", Settings.hasRubyKey);
        out.addProperty("emerald", Settings.hasEmeraldKey);
        out.addProperty("sapphire", Settings.hasSapphireKey);
        MapRoomNode current = AbstractDungeon.getCurrMapNode();
        if (current != null) { out.addProperty("x", current.x); out.addProperty("y", current.y); }
        out.add("map", JSON.toJsonTree(map.invoke(null)));
        JsonArray burning = new JsonArray();
        if (AbstractDungeon.map != null) for (java.util.ArrayList<MapRoomNode> layer : AbstractDungeon.map)
            for (MapRoomNode node : layer) if (node.hasEmeraldKey) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", node.x); pos.addProperty("y", node.y); burning.add(pos);
            }
        out.add("burning_elites", burning);
        if (CardCrawlGame.metricData != null) {
            out.add("path_taken", JSON.toJsonTree(CardCrawlGame.metricData.path_taken));
            out.add("path_per_floor", JSON.toJsonTree(CardCrawlGame.metricData.path_per_floor));
        }
        int routeUpgrades=0;
        for(com.megacrit.cardcrawl.cards.AbstractCard card:AbstractDungeon.player.masterDeck.group)
            if(card.canUpgrade())routeUpgrades++;
        out.addProperty("route_upgrades",routeUpgrades);
        out.addProperty("purge_base_cost", ShopScreen.purgeCost);
        out.addProperty("purge_actual_cost", ShopScreen.actualPurgeCost);
        JsonArray relics = new JsonArray();
        for (AbstractRelic relic : AbstractDungeon.player.relics) {
            JsonObject item = new JsonObject();
            item.addProperty("id", relic.relicId); item.addProperty("counter", relic.counter);
            relics.add(item);
        }
        out.add("relics", relics);
        JsonArray slots = new JsonArray();
        for (AbstractPotion potion : AbstractDungeon.player.potions) {
            JsonObject item = new JsonObject();
            item.addProperty("slot", potion.slot); item.addProperty("id", potion.ID); slots.add(item);
        }
        out.add("potion_slots", slots);
        JsonArray bottles = new JsonArray();
        for (int i = 0; i < AbstractDungeon.player.masterDeck.group.size(); i++) {
            AbstractCard card = AbstractDungeon.player.masterDeck.group.get(i);
            if (card.inBottleFlame || card.inBottleLightning || card.inBottleTornado) bottles.add(i);
        }
        out.add("bottled_deck_indices", bottles);
        return out;
    }

    static void cardDetails(JsonObject out, AbstractCard card) {
        out.addProperty("misc", card.misc);
        out.addProperty("bottle_flame", card.inBottleFlame);
        out.addProperty("bottle_lightning", card.inBottleLightning);
        out.addProperty("bottle_tornado", card.inBottleTornado);
    }

    static JsonObject recovery() {
        JsonObject out = new JsonObject();
        out.addProperty("replay_verified", false);
        out.addProperty("coverage", "raw_state_and_run_pools; event_private_fields_and_mod_state_not_complete");
        out.addProperty("card_rarity_factor", AbstractDungeon.cardBlizzRandomizer);
        out.addProperty("potion_chance", AbstractRoom.blizzardPotionMod);
        out.add("event_chances", JSON.toJsonTree(EventHelper.getChances()));
        out.add("monster_list", JSON.toJsonTree(AbstractDungeon.monsterList));
        out.add("elite_list", JSON.toJsonTree(AbstractDungeon.eliteMonsterList));
        out.add("boss_list", JSON.toJsonTree(AbstractDungeon.bossList));
        out.add("event_list", JSON.toJsonTree(AbstractDungeon.eventList));
        out.add("shrine_list", JSON.toJsonTree(AbstractDungeon.shrineList));
        out.add("one_time_events", JSON.toJsonTree(AbstractDungeon.specialOneTimeEventList));
        JsonObject pools = new JsonObject();
        pools.add("common", JSON.toJsonTree(AbstractDungeon.commonRelicPool));
        pools.add("uncommon", JSON.toJsonTree(AbstractDungeon.uncommonRelicPool));
        pools.add("rare", JSON.toJsonTree(AbstractDungeon.rareRelicPool));
        pools.add("shop", JSON.toJsonTree(AbstractDungeon.shopRelicPool));
        pools.add("boss", JSON.toJsonTree(AbstractDungeon.bossRelicPool));
        out.add("relic_pools", pools);
        return out;
    }

    static JsonObject metadata() throws Exception {
        initialize();
        JsonObject out = new JsonParser().parse(provenance.toString()).getAsJsonObject();
        out.addProperty("bright_eye_mode", Foresight.brightEyeMode);
        out.addProperty("reload_detection", "dungeon_exit_player_replacement_seed_or_floor_regression_only");
        return out;
    }
}
