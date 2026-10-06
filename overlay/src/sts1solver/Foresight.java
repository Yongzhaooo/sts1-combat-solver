package sts1solver;

import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.Color;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.events.RoomEventDialog;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.helpers.EventHelper;
import com.megacrit.cardcrawl.helpers.MonsterHelper;
import com.megacrit.cardcrawl.helpers.PotionHelper;
import com.megacrit.cardcrawl.helpers.RelicLibrary;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.neow.NeowEvent;
import com.megacrit.cardcrawl.neow.NeowReward;
import com.megacrit.cardcrawl.random.Random;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import communicationmod.ChoiceScreenUtils;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Out-of-combat foresight: Neow blessings, the next map rooms and the current
 * event. Every prediction runs inside {@link Sandbox}, so the real RNG and pools
 * are untouched. Event mirrors live in {@link EventForesight}; add a class there
 * to cover another event (any character, since mirrors use the game's helpers).
 */
public final class Foresight {
    private static String signature = "";
    static boolean brightEyeMode = true;
    private static List<String> cached = Collections.emptyList();
    private static String failure = "";
    private static String digNote;
    static boolean capturing;

    /** Thrown from the event factory to read the chosen key without building the event. */
    static final class Captured extends RuntimeException {
        final String key;
        Captured(String key) { super(null, null, false, false); this.key = key; }
    }

    @SpirePatch(clz = EventHelper.class, method = "getEvent", paramtypez = {String.class})
    public static class CaptureEvent {
        @SpirePrefixPatch public static void before(String key) { if (capturing) throw new Captured(key); }
    }

    private Foresight() { }

    @SpirePatch(clz = com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption.class, method = "render")
    public static class DigNote {
        @SpirePostfixPatch public static void after(com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption option, SpriteBatch sb) {
            if (!(option instanceof com.megacrit.cardcrawl.ui.campfire.DigOption) || !option.usable) return;
            lines();
            if (digNote != null) draw(sb, digNote, option.hb.x, option.hb.y - 8 * Settings.scale,
                420 * Settings.scale, false);
        }
    }

    static String digResult() {
        try (Sandbox ignored = new Sandbox()) {
            return AbstractDungeon.returnRandomRelic(AbstractDungeon.returnRandomRelicTier()).name;
        }
    }

    /** Count future campfires on the shortest campfire route, excluding this node. */
    static int remainingCampfires(List<? extends List<MapRoomNode>> map, MapRoomNode node,
                                  Map<MapRoomNode,Integer> memo) {
        if (memo.containsKey(node)) return memo.get(node);
        int minimum = Integer.MAX_VALUE;
        for (com.megacrit.cardcrawl.map.MapEdge edge : node.getEdges()) {
            int count = 0;
            if (edge.dstY >= 0 && edge.dstY < map.size()) {
                MapRoomNode next = map.get(edge.dstY).get(edge.dstX);
                count = (next.room instanceof com.megacrit.cardcrawl.rooms.RestRoom ? 1 : 0)
                    + remainingCampfires(map, next, memo);
            }
            minimum = Math.min(minimum, count);
        }
        int count = minimum == Integer.MAX_VALUE ? 0 : minimum;
        memo.put(node, count);
        return count;
    }

    static boolean remindRuby(int act, boolean enabled, boolean owned, boolean atRest, int remaining) {
        return act == 3 && enabled && !owned && atRest && remaining <= 1;
    }

    static void renderRubyReminder(SpriteBatch sb) {
        if (AbstractDungeon.player == null || AbstractDungeon.getCurrMapNode() == null
                || AbstractDungeon.actNum != 3 || !Settings.isFinalActAvailable || Settings.hasRubyKey
                || !(AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.RestRoom)) return;
        int remaining = remainingCampfires(AbstractDungeon.map, AbstractDungeon.getCurrMapNode(), new IdentityHashMap<>());
        if (!remindRuby(AbstractDungeon.actNum, Settings.isFinalActAvailable, Settings.hasRubyKey, true, remaining)) return;
        String note = remaining == 0 ? I18n.t("还没有红钥匙！这是最后一个火堆，请选择「回忆」。")
            : I18n.t("还没有红钥匙！部分路线后面仅剩一个火堆，请预留「回忆」。");
        draw(sb, note, 40 * Settings.scale, Settings.HEIGHT - 110 * Settings.scale,
             Settings.WIDTH - 80 * Settings.scale, false);
    }

    // Short results drawn beside the matching event option / map node (see OptionNote, NodeNote).
    private static final Map<Integer, String> optionNotes = new HashMap<>();
    private static final Map<MapRoomNode, String> nodeNotes = new IdentityHashMap<>();
    private static final GlyphLayout layout = new GlyphLayout();

    /** Mirrors call this to put a short result beside event option {@code slot}. */
    static void note(int slot, String text) { optionNotes.merge(slot, text, (a, b) -> a + "；" + b); }

    @SpirePatch(clz = LargeDialogOptionButton.class, method = "render")
    public static class OptionNote {
        @SpirePostfixPatch public static void after(LargeDialogOptionButton button, SpriteBatch sb) {
            lines();
            String note = optionNotes.get(button.slot);
            // Beside the button, vertically centred; long option texts fill the button itself.
            if (note != null) draw(sb, note, button.hb.x + button.hb.width + 16 * Settings.scale,
                button.hb.cY + 12 * Settings.scale, 520 * Settings.scale, false);
        }
    }

    @SpirePatch(clz = MapRoomNode.class, method = "render")
    public static class NodeNote {
        @SpirePostfixPatch public static void after(MapRoomNode node, SpriteBatch sb) {
            if (!brightEyeMode) return;
            if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP) return;
            lines();
            String note = nodeNotes.get(node);
            if (note != null) draw(sb, note, node.hb.cX + 36 * Settings.scale, node.hb.cY + 12 * Settings.scale,
                360 * Settings.scale, false);
        }
    }

    // Transform grids label every card with its result. Picks resolve in selection order on
    // one stream, so cards still unpicked show what they become after the picks so far.
    private static String gridKey = "";
    private static final Map<AbstractCard, String> gridNotes = new IdentityHashMap<>();

    @SpirePatch(clz = GridCardSelectScreen.class, method = "render")
    public static class GridNote {
        @SpirePostfixPatch public static void after(GridCardSelectScreen screen, SpriteBatch sb) {
            boolean astrolabe = astrolabeSelecting();
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            boolean eventTransform = room != null && EventForesight.transformSelecting(room.event);
            if ((!screen.forTransform && !astrolabe && !eventTransform) || screen.targetGroup == null || AbstractDungeon.player == null) return;
            boolean neow = room != null && room.event instanceof NeowEvent;
            Random stream = neow ? NeowEvent.rng : AbstractDungeon.miscRng;
            if (stream == null) return;
            boolean upgrade = astrolabe;
            StringBuilder key = new StringBuilder().append(System.identityHashCode(screen.targetGroup))
                .append('|').append(stream.counter).append('|').append(neow).append('|').append(upgrade);
            for (AbstractCard c : screen.selectedCards) key.append('|').append(System.identityHashCode(c));
            if (!key.toString().equals(gridKey)) {
                gridKey = key.toString();
                gridNotes.clear();
                Random rng = stream.copy();
                for (AbstractCard c : screen.selectedCards) gridNotes.put(c, I18n.t("第") + (gridNotes.size() + 1) + I18n.t("张 → ") + result(c, rng, upgrade));
                for (AbstractCard c : screen.targetGroup.group)
                    if (!gridNotes.containsKey(c)) gridNotes.put(c, "→ " + result(c, rng.copy(), upgrade));
            }
            for (AbstractCard c : screen.targetGroup.group) {
                String note = gridNotes.get(c);
                if (note == null) continue;
                layout.setText(SolverMod.instance.font(), note);
                draw(sb, note, c.current_x - layout.width / 2,
                    c.current_y - AbstractCard.IMG_HEIGHT * c.drawScale / 2 - 6 * Settings.scale, 300 * Settings.scale, false);
            }
        }
    }

    static String result(AbstractCard card, Random rng, boolean upgrade) {
        AbstractCard c = transform(card, rng).makeCopy();
        if (upgrade && c.canUpgrade()) c.upgrade();
        return c.name;
    }

    static boolean astrolabeSelecting() {
        if (AbstractDungeon.player == null || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) return false;
        AbstractRelic relic = AbstractDungeon.player.getRelic("Astrolabe");
        return relic != null && Boolean.FALSE.equals(read(com.megacrit.cardcrawl.relics.Astrolabe.class, "cardsSelected", relic));
    }

    static boolean astrolabeOffered() {
        if (AbstractDungeon.player == null || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD
                || AbstractDungeon.bossRelicScreen == null) return false;
        for (AbstractRelic relic : AbstractDungeon.bossRelicScreen.relics)
            if ("Astrolabe".equals(relic.relicId)) return true;
        return false;
    }

    static int previewCount() {
        if (astrolabeOffered()) return 3;
        if (AbstractDungeon.player == null || AbstractDungeon.currMapNode == null
                || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE) return 0;
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.EVENT) return 0;
        int count = EventForesight.transformCount(room.event);
        return count > 0 && EventForesight.transformCards(room.event).size() >= count ? count : 0;
    }

    private static void draw(SpriteBatch sb, String text, float x, float top, float maxWidth, boolean rightAligned) {
        if (SolverMod.instance == null) return;
        BitmapFont font = SolverMod.instance.font();
        layout.setText(font, text);
        while (text.length() > 2 && layout.width > maxWidth) {
            text = text.substring(0, text.length() - 2) + "…";
            layout.setText(font, text);
        }
        float left = rightAligned ? x - layout.width : x, pad = 6 * Settings.scale;
        sb.setColor(0, 0, 0, .72f);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, left - pad, top - layout.height - pad, layout.width + 2 * pad, layout.height + 2 * pad);
        sb.setColor(Color.WHITE);
        FontHelper.renderFontLeftTopAligned(sb, font, text, left, top, SolverMod.ACCENT);
    }

    /** Prediction rows for the panel; empty when nothing predictable is on screen. */
    static List<String> lines() {
        if (AbstractDungeon.player == null || AbstractDungeon.currMapNode == null) return Collections.emptyList();
        String key = signature();
        if (!key.equals(signature)) {
            signature = key;
            try {
                cached = compute();
                failure = "";
            } catch (RuntimeException | LinkageError error) {
                cached = Collections.emptyList();
                failure = error.toString();
                System.err.println("[STS1Solver] foresight: " + failure);
            }
        }
        return cached;
    }
    static String failure() { return failure; }

    // Anything a mirror reads; the cache refreshes when one of them changes.
    private static String signature() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        StringBuilder s = new StringBuilder();
        s.append(I18n.language()).append('|').append(brightEyeMode).append('|');
        s.append(AbstractDungeon.screen).append('|').append(AbstractDungeon.floorNum).append('|')
         .append(room == null ? "" : room.getClass().getName() + room.phase).append('|')
         .append(room == null || room.event == null ? 0 : System.identityHashCode(room.event)).append('|')
         .append(System.identityHashCode(AbstractDungeon.currMapNode)).append('|')
         .append(AbstractDungeon.player.masterDeck.size()).append('|').append(AbstractDungeon.player.gold).append('|')
         .append(AbstractDungeon.player.currentHealth).append('|').append(AbstractDungeon.player.relics.size())
         .append('|').append(RoomEventDialog.optionList.size());
        for (Random rng : new Random[]{AbstractDungeon.eventRng, AbstractDungeon.miscRng, AbstractDungeon.cardRng,
                AbstractDungeon.relicRng, AbstractDungeon.potionRng, AbstractDungeon.merchantRng, NeowEvent.rng})
            s.append('|').append(rng == null ? -1 : rng.counter);
        s.append('|').append(AbstractDungeon.monsterList == null ? 0 : AbstractDungeon.monsterList.size())
         .append('|').append(AbstractDungeon.eventList == null ? 0 : AbstractDungeon.eventList.size());
        if (room != null && room.event != null) s.append('|').append(EventForesight.stateKey(room.event));
        return s.toString();
    }

    private static List<String> compute() {
        List<String> out = new ArrayList<>();
        optionNotes.clear();
        nodeNotes.clear();
        digNote = null;
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        boolean onMap = AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP;
        if (!onMap && room instanceof com.megacrit.cardcrawl.rooms.RestRoom
                && room.phase != AbstractRoom.RoomPhase.COMPLETE && AbstractDungeon.player.hasRelic("Shovel")) {
            digNote = I18n.t("挖掘 → ") + digResult();
            out.add(digNote);
        }
        if (!onMap && room != null && room.event instanceof NeowEvent) neow((NeowEvent) room.event, out);
        else if (!onMap && room != null && room.event != null && room.phase == AbstractRoom.RoomPhase.EVENT)
            EventForesight.predict(room.event, out);
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP
                || (room != null && room.phase == AbstractRoom.RoomPhase.COMPLETE && out.isEmpty()))
            nextRooms(out);
        return out;
    }

    // ---- Neow ---------------------------------------------------------------

    private static final Map<String, String> NEOW = new HashMap<>();
    static {
        String[][] names = {{"THREE_CARDS", I18n.t("三选一牌")}, {"ONE_RANDOM_RARE_CARD", I18n.t("随机稀有牌")}, {"REMOVE_CARD", I18n.t("删除一张")},
            {"UPGRADE_CARD", I18n.t("升级一张")}, {"RANDOM_COLORLESS", I18n.t("无色牌三选一")}, {"TRANSFORM_CARD", I18n.t("变化一张")},
            {"THREE_SMALL_POTIONS", I18n.t("三瓶药水")}, {"RANDOM_COMMON_RELIC", I18n.t("随机普通遗物")}, {"TEN_PERCENT_HP_BONUS", I18n.t("最大生命+10%")},
            {"HUNDRED_GOLD", I18n.t("100 金币")}, {"THREE_ENEMY_KILL", I18n.t("涅奥的悲恸")}, {"REMOVE_TWO", I18n.t("删除两张")},
            {"TRANSFORM_TWO_CARDS", I18n.t("变化两张")}, {"ONE_RARE_RELIC", I18n.t("随机稀有遗物")}, {"THREE_RARE_CARDS", I18n.t("稀有牌三选一")},
            {"TWO_FIFTY_GOLD", I18n.t("250 金币")}, {"TWENTY_PERCENT_HP_BONUS", I18n.t("最大生命+20%")}, {"BOSS_RELIC", I18n.t("换 Boss 遗物")},
            {"RANDOM_COLORLESS_2", I18n.t("稀有无色牌三选一")}, {"TEN_PERCENT_HP_LOSS", I18n.t("失去 10% 最大生命")}, {"NO_GOLD", I18n.t("失去全部金币")},
            {"CURSE", I18n.t("获得诅咒（游戏用非种子随机，无法预测）")}, {"PERCENT_DAMAGE", I18n.t("受到 30% 当前生命伤害")}};
        for (String[] n : names) NEOW.put(n[0], n[1]);
    }

    @SuppressWarnings("unchecked")
    private static void neow(NeowEvent event, List<String> out) {
        List<NeowReward> rewards = (List<NeowReward>) read(NeowEvent.class, "rewards", event);
        // Only while the blessing buttons are on screen; after a pick the list is stale.
        if (rewards == null || rewards.isEmpty() || NeowEvent.rng == null
                || RoomEventDialog.optionList.size() != rewards.size()) return;
        out.add(I18n.t("涅奥祝福 · 各选项的确定结果"));
        for (int i = 0; i < rewards.size(); i++) {
            NeowReward r = rewards.get(i);
            String head = (i + 1) + " " + NEOW.getOrDefault(r.type.name(), r.type.name());
            if (r.drawback != null && r.drawback != NeowReward.NeowRewardDrawback.NONE)
                head += I18n.t("（代价：") + NEOW.getOrDefault(r.drawback.name(), r.drawback.name()) + "）";
            out.add(head);
            int start = out.size();
            try (Sandbox ignored = new Sandbox()) {
                neowResult(r, out);
            }
            List<String> rows = new ArrayList<>();
            for (String row : out.subList(start, out.size()))
                if (!row.contains(I18n.t("取决于"))) rows.add(row.replace("  → ", "").trim());
            if (!rows.isEmpty()) note(i, String.join("；", rows));
        }
    }

    private static void neowResult(NeowReward r, List<String> out) {
        switch (r.type) {
            case THREE_CARDS: out.add("  → " + cards(r.getRewardCards(false))); break;
            case THREE_RARE_CARDS: out.add("  → " + cards(r.getRewardCards(true))); break;
            case RANDOM_COLORLESS: out.add("  → " + cards(r.getColorlessRewardCards(false))); break;
            case RANDOM_COLORLESS_2: out.add("  → " + cards(r.getColorlessRewardCards(true))); break;
            case ONE_RANDOM_RARE_CARD:
                out.add("  → " + AbstractDungeon.getCard(AbstractCard.CardRarity.RARE, NeowEvent.rng).name); break;
            case RANDOM_COMMON_RELIC: out.add("  → " + relic(AbstractRelic.RelicTier.COMMON)); break;
            case ONE_RARE_RELIC: out.add("  → " + relic(AbstractRelic.RelicTier.RARE)); break;
            case BOSS_RELIC:
                // activate() drops the starter relic before rolling, so canSpawn sees it gone.
                if (!AbstractDungeon.player.relics.isEmpty()) AbstractDungeon.player.relics.remove(0);
                out.add("  → " + relic(AbstractRelic.RelicTier.BOSS)); break;
            case THREE_SMALL_POTIONS: {
                List<String> names = new ArrayList<>();
                for (int k = 0; k < 3; k++) names.add(PotionHelper.getRandomPotion().name);
                out.add("  → " + String.join(" / ", names)); break;
            }
            case TRANSFORM_CARD: transforms(NeowEvent.rng, out); break;
            case TRANSFORM_TWO_CARDS:
                transformPairs(CardGroup.getGroupWithoutBottledCards(AbstractDungeon.player.masterDeck.getPurgeableCards()).group,
                    NeowEvent.rng, out); break;
            default: break;
        }
    }

    // ---- shared mirrors -----------------------------------------------------

    static String cards(List<AbstractCard> cards) {
        List<String> names = new ArrayList<>();
        for (AbstractCard c : cards) names.add(c.name);
        return String.join(" / ", names);
    }

    /** Name of the relic the next returnRandomRelic(tier) yields. Caller holds a Sandbox. */
    static String relic(AbstractRelic.RelicTier tier) {
        return RelicLibrary.getRelic(AbstractDungeon.returnRandomRelicKey(tier)).name;
    }

    /** AbstractDungeon.transformCard without its seen-card bookkeeping. */
    static AbstractCard transform(AbstractCard card, Random rng) {
        if (card.color == AbstractCard.CardColor.COLORLESS)
            return AbstractDungeon.returnTrulyRandomColorlessCardFromAvailable(card, rng);
        if (card.color == AbstractCard.CardColor.CURSE) return CardLibrary.getCurse(card, rng);
        return AbstractDungeon.returnTrulyRandomCardFromAvailable(card, rng);
    }

    /** One row per distinct transformable card: the result if it is the card chosen. */
    static void transforms(Random rng, List<String> out) {
        CardGroup group = CardGroup.getGroupWithoutBottledCards(AbstractDungeon.player.masterDeck.getPurgeableCards());
        Set<String> seen = new HashSet<>();
        for (AbstractCard c : group.group) {
            if (!seen.add(c.cardID + "+" + c.timesUpgraded)) continue;
            out.add("  " + c.name + " → " + transform(c, rng.copy()).name);
        }
    }

    /** Ordered pairs, keeping duplicate physical cards available but collapsing identical rows. */
    static void transformPairs(List<AbstractCard> cards, Random stream, List<String> out) {
        Set<String> seen = new HashSet<>();
        for (AbstractCard first : cards) for (AbstractCard second : cards) {
            if (first == second || !seen.add(first.cardID + "+" + first.timesUpgraded + "|" + second.cardID + "+" + second.timesUpgraded)) continue;
            Random rng = stream.copy();
            out.add("  " + first.name + " / " + second.name + " → "
                + transform(first, rng).name + " / " + transform(second, rng).name);
        }
    }

    static Object read(Class<?> type, String name, Object target) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(I18n.t("游戏字段已变化：") + type.getSimpleName() + "." + name, e);
        }
    }

    // ---- map ----------------------------------------------------------------

    private static void nextRooms(List<String> out) {
        if (!brightEyeMode) return;
        List<MapRoomNode> choices = ChoiceScreenUtils.getMapScreenNodeChoices();
        if (choices.isEmpty()) return;
        out.add(I18n.t("下一层可选房间 · 确定结果"));
        Set<String> shown = new LinkedHashSet<>();
        for (MapRoomNode node : choices) {
            String symbol = node.getRoomSymbol(true);
            String text;
            if ("?".equals(symbol)) text = I18n.t("? 房间 → ") + question(node);
            else if ("M".equals(symbol)) text = I18n.t("普通战 → ") + encounter(AbstractDungeon.monsterList);
            else if ("E".equals(symbol)) text = I18n.t("精英 → ") + encounter(AbstractDungeon.eliteMonsterList);
            else {
                // ponytail: shop stock and chest contents are not mirrored yet.
                shown.add(I18n.t("  第 ") + (node.x + 1) + I18n.t(" 列 ") + ("R".equals(symbol) ? I18n.t("火堆") : "$".equals(symbol) ? I18n.t("商店")
                    : "T".equals(symbol) ? I18n.t("宝箱") : symbol) + I18n.t("（暂无预测）"));
                continue;
            }
            shown.add(I18n.t("  第 ") + (node.x + 1) + I18n.t(" 列 ") + text);
            nodeNotes.put(node, text.substring(text.indexOf("→ ") + 2));
        }
        out.addAll(shown);
    }

    private static String encounter(List<String> list) {
        return list == null || list.isEmpty() ? I18n.t("列表将重新生成") : MonsterHelper.getEncounterName(list.get(0));
    }

    /** Mirrors nextRoomTransition: roll on a fresh eventRng copy, then EventRoom.onPlayerEntry. */
    private static String question(MapRoomNode node) {
        try (Sandbox ignored = new Sandbox()) {
            AbstractDungeon.floorNum++;
            Random roll = new Random(Settings.seed, AbstractDungeon.eventRng.counter);
            EventHelper.RoomResult result = EventHelper.roll(roll);  // reads the room we leave
            AbstractDungeon.eventRng = roll;
            switch (result) {
                case MONSTER: return I18n.t("普通战：") + encounter(AbstractDungeon.monsterList);
                case ELITE: return I18n.t("精英：") + encounter(AbstractDungeon.eliteMonsterList);
                case SHOP: return I18n.t("商店");
                case TREASURE: return I18n.t("宝箱");
                default: break;
            }
            AbstractDungeon.currMapNode = node;  // event conditions read the entered node
            capturing = true;
            try {
                AbstractEvent ignoredEvent = AbstractDungeon.generateEvent(new Random(Settings.seed, roll.counter));
                return ignoredEvent == null ? I18n.t("事件：没有剩余事件") : I18n.t("事件");
            } catch (Captured captured) {
                return I18n.t("事件：") + EventHelper.getEventName(captured.key);
            } finally {
                capturing = false;
            }
        }
    }
}
