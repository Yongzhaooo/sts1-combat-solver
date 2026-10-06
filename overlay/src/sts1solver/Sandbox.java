package sts1solver;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.EventHelper;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.neow.NeowEvent;
import com.megacrit.cardcrawl.random.Random;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * Runs the game's own random helpers against copies of every run RNG and
 * consumable pool, then puts the original objects back. Predictions never
 * advance the real seed state; mirrors only read what a save/load could reveal.
 */
final class Sandbox implements AutoCloseable {
    private final Random monster = AbstractDungeon.monsterRng, map = AbstractDungeon.mapRng,
        event = AbstractDungeon.eventRng, merchant = AbstractDungeon.merchantRng, card = AbstractDungeon.cardRng,
        treasure = AbstractDungeon.treasureRng, relic = AbstractDungeon.relicRng, potion = AbstractDungeon.potionRng,
        monsterHp = AbstractDungeon.monsterHpRng, ai = AbstractDungeon.aiRng, shuffle = AbstractDungeon.shuffleRng,
        cardRandom = AbstractDungeon.cardRandomRng, misc = AbstractDungeon.miscRng, neow = NeowEvent.rng;
    private final ArrayList<String> common = AbstractDungeon.commonRelicPool, uncommon = AbstractDungeon.uncommonRelicPool,
        rare = AbstractDungeon.rareRelicPool, shop = AbstractDungeon.shopRelicPool, boss = AbstractDungeon.bossRelicPool,
        events = AbstractDungeon.eventList, shrines = AbstractDungeon.shrineList,
        oneTime = AbstractDungeon.specialOneTimeEventList, monsters = AbstractDungeon.monsterList,
        elites = AbstractDungeon.eliteMonsterList;
    private final ArrayList<Float> chances = EventHelper.getChances();
    private final Object previousChances = previousChances();
    private final AbstractCard transformed = AbstractDungeon.transformedCard;
    private final int blizzard = AbstractDungeon.cardBlizzRandomizer, floor = AbstractDungeon.floorNum;
    private final MapRoomNode node = AbstractDungeon.currMapNode;
    private final ArrayList<AbstractRelic> relics = AbstractDungeon.player == null ? null : AbstractDungeon.player.relics;
    private final int[] counters;

    Sandbox() {
        AbstractDungeon.monsterRng = copy(monster); AbstractDungeon.mapRng = copy(map);
        AbstractDungeon.eventRng = copy(event); AbstractDungeon.merchantRng = copy(merchant);
        AbstractDungeon.cardRng = copy(card); AbstractDungeon.treasureRng = copy(treasure);
        AbstractDungeon.relicRng = copy(relic); AbstractDungeon.potionRng = copy(potion);
        AbstractDungeon.monsterHpRng = copy(monsterHp); AbstractDungeon.aiRng = copy(ai);
        AbstractDungeon.shuffleRng = copy(shuffle); AbstractDungeon.cardRandomRng = copy(cardRandom);
        AbstractDungeon.miscRng = copy(misc); NeowEvent.rng = copy(neow);
        AbstractDungeon.commonRelicPool = copy(common); AbstractDungeon.uncommonRelicPool = copy(uncommon);
        AbstractDungeon.rareRelicPool = copy(rare); AbstractDungeon.shopRelicPool = copy(shop);
        AbstractDungeon.bossRelicPool = copy(boss); AbstractDungeon.eventList = copy(events);
        AbstractDungeon.shrineList = copy(shrines); AbstractDungeon.specialOneTimeEventList = copy(oneTime);
        AbstractDungeon.monsterList = copy(monsters); AbstractDungeon.eliteMonsterList = copy(elites);
        // Relic counters are mutated by room rolls (Tiny Chest); the list itself may be edited by mirrors.
        counters = new int[relics == null ? 0 : relics.size()];
        for (int i = 0; i < counters.length; i++) counters[i] = relics.get(i).counter;
        if (relics != null) AbstractDungeon.player.relics = new ArrayList<>(relics);
    }

    @Override public void close() {
        AbstractDungeon.monsterRng = monster; AbstractDungeon.mapRng = map; AbstractDungeon.eventRng = event;
        AbstractDungeon.merchantRng = merchant; AbstractDungeon.cardRng = card; AbstractDungeon.treasureRng = treasure;
        AbstractDungeon.relicRng = relic; AbstractDungeon.potionRng = potion; AbstractDungeon.monsterHpRng = monsterHp;
        AbstractDungeon.aiRng = ai; AbstractDungeon.shuffleRng = shuffle; AbstractDungeon.cardRandomRng = cardRandom;
        AbstractDungeon.miscRng = misc; NeowEvent.rng = neow;
        AbstractDungeon.commonRelicPool = common; AbstractDungeon.uncommonRelicPool = uncommon;
        AbstractDungeon.rareRelicPool = rare; AbstractDungeon.shopRelicPool = shop; AbstractDungeon.bossRelicPool = boss;
        AbstractDungeon.eventList = events; AbstractDungeon.shrineList = shrines;
        AbstractDungeon.specialOneTimeEventList = oneTime; AbstractDungeon.monsterList = monsters;
        AbstractDungeon.eliteMonsterList = elites;
        if (chances != null && chances.size() == 4) EventHelper.setChances(chances);
        setPreviousChances(previousChances);
        AbstractDungeon.transformedCard = transformed;
        AbstractDungeon.cardBlizzRandomizer = blizzard;
        AbstractDungeon.floorNum = floor;
        AbstractDungeon.currMapNode = node;
        if (relics != null) {
            AbstractDungeon.player.relics = relics;
            for (int i = 0; i < counters.length; i++) relics.get(i).counter = counters[i];
        }
    }

    private static Random copy(Random rng) { return rng == null ? null : rng.copy(); }
    private static <T> ArrayList<T> copy(ArrayList<T> list) { return list == null ? null : new ArrayList<>(list); }

    private static Field previousField;
    private static Field previousField() throws ReflectiveOperationException {
        if (previousField == null) {
            previousField = EventHelper.class.getDeclaredField("saveFilePreviousChances");
            previousField.setAccessible(true);
        }
        return previousField;
    }
    private static Object previousChances() {
        try { return previousField().get(null); } catch (ReflectiveOperationException e) { return null; }
    }
    private static void setPreviousChances(Object value) {
        try { previousField().set(null, value); } catch (ReflectiveOperationException ignored) { }
    }
}
