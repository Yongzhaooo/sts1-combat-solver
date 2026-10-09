package sts1solver;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.random.Random;
import java.lang.reflect.Field;

/**
 * Hand-written event policy that overrides the student network (docs/event-rules.md).
 * Positions are decided from the number of shown buttons, because the game lists only enabled
 * options; a layout that does not match the expectation returns null and the network decides.
 */
final class EventRules {
    /** Set by the mod at start-up. Offline checks leave it off so no game class is touched. */
    static boolean live;

    static final int UNKNOWN = -1, SAFE_SEARCH = 0, ELITE_SEARCH = 1;
    static final String MANUAL = "人工规则";

    static final class Context {
        String event = "";
        int shown, hp, maxHp, gold, ascension;
        /** Cards the removal order can reach: removable curses, Strikes, Bash, other attacks, then Defends. */
        int removable;
        int freePotionSlots;
        /** Neow's four offers (bonus / drawback names), when the blessing page is up. */
        String[] neowBonus, neowDrawback;
        boolean sozu;
        boolean artifactRelic;
        boolean redMask;
        /** Rooms offered on the next map step; the Cleric may heal only when none of them is a rest site. */
        boolean nextRoomsKnown, nextHasRest;
        int skullGoldCost = 6;
        /** Hidden information, read from the live event: total HP lost until Scrap Ooze gives its relic. */
        int oozeLoss = UNKNOWN;
        /** Dead Adventurer: what the next search does, and which elite it brings (0 sentries, 1 Nob, 2 Lagavulin). */
        int adventurerNext = UNKNOWN, adventurerEnemy = UNKNOWN;
        /** Hidden information: Face Trader's trade gives a good face (1) or not (0); the Joust owner wins (1) or loses (0). */
        int goodFace = UNKNOWN, ownerWins = UNKNOWN;
    }

    static final class Decision {
        /** Chosen button, or -1 to let the network choose among the buttons not listed in {@code avoid}. */
        final int index;
        final String note;
        final int[] avoid;
        /** Above zero the button is only favoured: this much is added to its network score, which may still be outvoted. */
        float boost;
        Decision(int index, String note, int... avoid) { this.index = index; this.note = note; this.avoid = avoid; }
    }

    /** Lower is removed first: curse, Strike, Bash, other attacks. Everything else (Defend...) is 9. */
    static int removalTier(String id, String type) {
        if (type.equals("CURSE")) return unremovable(id) ? 9 : 0;
        if (id.equals("Strike_R") || id.equals("STRIKE_RED")) return 1;
        if (id.equals("Bash") || id.equals("BASH")) return 2;
        if (type.equals("ATTACK")) return 3;
        return 9;
    }

    static boolean unremovable(String id) {
        return id.equals("AscendersBane") || id.equals("CurseOfTheBell") || id.equals("Necronomicurse");
    }

    private static Decision pick(int index, String note) { return new Decision(index, note); }

    /** Neow's lose-HP-remove-two: a strong lean, not a command; the network still weighs the other offers. */
    static final float NEOW_BOOST = 3f;

    private static Decision favor(int index, String note, float boost) {
        Decision d = new Decision(index, note);
        d.boost = boost;
        return d;
    }

    /** Never take the curse: the network picks among the other buttons. */
    private static Decision without(String note, int... avoid) { return new Decision(-1, note, avoid); }

    private static boolean healthy(Context c, double fraction) { return c.hp >= fraction * c.maxHp; }

    /** Null means "no rule: ask the network". */
    /** Ruby Key: smith first, recall only at the second-to-last campfire (or the last one). {@code left} counts the
     * campfires still ahead on the shortest route. Decision index is -1 (network picks) with recall avoided until then. */
    static Decision rest(String[] labels, boolean wantRuby, int left) {
        int recall = java.util.Arrays.asList(labels).indexOf("recall");
        if (recall < 0 || !wantRuby) return null;
        if (left <= 1) return pick(recall, left == 0 ? "红钥匙：最后一个火堆，回忆" : "红钥匙：倒数第二个火堆，回忆");
        return without("红钥匙：先敲牌，倒数第二个火堆再拿", recall);
    }

    static Decision decide(Context c) {
        int n = c.shown;
        switch (c.event) {
            case "Knowing Skull":
                if (n != 4) return null;
                // potion 0, gold 1 (90), card 2, leave 3; every option and leaving cost HP.
                return healthy(c, .7) && c.skullGoldCost <= 7 ? pick(1, "全知头骨：血量充足，拿金币") : pick(3, "全知头骨：离开");
            case "Colosseum":
                if (n != 2) return null; // the single-button pages are clicked through elsewhere
                return healthy(c, .5) ? pick(1, "竞技场：血量不低于 50%，再战") : pick(0, "竞技场：血量不足，离开");
            case "Golden Idol":
                if (n == 2) return pick(0, "金神像：拿取");
                // outrun 0 (Injury), smash 1 (HP), hide 2 (max HP). Smashing only when a win is certain stays manual.
                return n == 3 ? pick(2, "金神像：扣最大血上限") : null;
            case "Shining Light": {
                if (n != 2) return null;
                int damage = Math.round(c.maxHp * (c.ascension >= 15 ? .3f : .2f));
                return c.hp - damage >= .55 * c.maxHp ? pick(0, "闪耀之光：升级两张牌") : pick(1, "闪耀之光：血量不足，离开");
            }
            case "The Cleric": {
                int cost = c.ascension >= 15 ? 75 : 50;
                boolean heal = c.gold >= 35, purify = c.gold >= cost;
                if (n != 1 + (heal ? 1 : 0) + (purify ? 1 : 0)) return null;
                // Healing only in an emergency: under 15 HP with no rest site on the next step.
                if (heal && c.hp < 15 && c.nextRoomsKnown && !c.nextHasRest) return pick(0, "牧师：血量不足 15 且下一步没有火堆，治疗");
                return purify && c.removable > 0 ? pick(heal ? 1 : 0, "牧师：付钱删牌（不花钱回血）") : pick(n - 1, "牧师：离开");
            }
            case "Beggar": {
                boolean pay = c.gold >= 75;
                if (n != (pay ? 2 : 1)) return null;
                return pay && c.removable > 0 ? pick(0, "乞丐：付钱删牌") : pick(n - 1, "乞丐：离开");
            }
            case "Addict": {
                boolean pay = c.gold >= 85;
                if (n != (pay ? 3 : 2)) return null;
                return pay ? pick(0, "瘾君子：付钱买遗物") : pick(n - 1, "瘾君子：离开"); // never steal
            }
            case "Ghosts":
                return n == 2 ? pick(1, MANUAL + "：AI 大牌组打法不适配，拒绝幽灵") : null;
            case "Scrap Ooze":
                if (n != 2 || c.oozeLoss == UNKNOWN) return null;
                return c.oozeLoss > 0 && c.oozeLoss <= 25 && c.hp - c.oozeLoss >= 10
                    ? pick(0, "废料软泥：预读 " + c.oozeLoss + " 血内得遗物") : pick(1, "废料软泥：预读得不偿失，离开");
            case "Dead Adventurer":
                if (n != 2 || c.adventurerNext == UNKNOWN) return null;
                if (c.adventurerNext == SAFE_SEARCH) return pick(0, "死去的冒险者：预读安全，继续搜");
                if (c.adventurerEnemy == 2) return pick(1, "死去的冒险者：下一次遇乐嘉，离开");
                return healthy(c, c.adventurerEnemy == 1 ? .75 : .6)
                    ? pick(0, "死去的冒险者：下一次遇" + (c.adventurerEnemy == 1 ? "地精大块头" : "三只哨卫") + "，血量够，打")
                    : pick(1, "死去的冒险者：下一次遇精英，血量不够，离开");
            case "Drug Dealer":
                // J.A.X. 0, transform 1 (only listed with two transformable cards), Mutagenic Strength 2.
                if (n == 3) return c.artifactRelic ? pick(2, "药贩：自带人工制品，突变力量") : pick(1, "药贩：变形两张牌");
                return n == 2 ? pick(1, "药贩：不选 J.A.X.") : null;
            case "Living Wall":
                if (n != 2 && n != 3) return null;
                return c.removable > 0 ? pick(0, "活墙：删牌") : pick(n == 3 ? 2 : 1, "活墙：无牌可删");
            case "Purifier":
                return n == 2 ? (c.removable > 0 ? pick(0, "净化祭坛：删牌") : pick(1, "净化祭坛：无牌可删")) : null;
            case "Transmorgrifier":
                return n == 2 ? (c.removable > 0 ? pick(0, "变形祭坛：变形") : pick(1, "变形祭坛：无牌可变")) : null;
            case "Upgrade Shrine":
            case "Duplicator":
                return n == 2 ? pick(0, c.event.equals("Duplicator") ? "复制祭坛：复制" : "升级祭坛：升级") : null;
            case "Golden Shrine":
                return n == 3 ? pick(0, "金色祭坛：祈祷") : null;
            case "Fountain of Cleansing":
                return n == 2 ? pick(0, "净化喷泉：移除诅咒") : null;
            case "Golden Wing":
                if (n != 2 && n != 3) return null;
                return c.removable > 0 && healthy(c, .4) ? pick(0, "金翼：删牌") : pick(n - 1, "金翼：离开");
            case "Big Fish":
                return n == 3 ? (healthy(c, .55) ? pick(1, "大鱼：甜甜圈 +5 最大血") : pick(0, "大鱼：香蕉回血")) : null;
            case "World of Goop":
                return n == 2 ? (healthy(c, .35) ? pick(0, "泥坑：收集金币") : pick(1, "泥坑：血量不足，离开")) : null;
            case "Mushrooms":
                return n == 2 ? pick(0, "蘑菇：战斗（不吃寄生诅咒）") : null;
            // Events that offer a curse never take it (user rule, 2026-10-09).
            case "Liars Game":
                return n == 2 ? pick(1, "说谎者：不拿疑虑诅咒") : null;
            case "The Mausoleum":
                return n == 2 ? pick(1, "陵墓：不冒悔恨诅咒的险，离开") : null;
            case "Forgotten Altar": {
                // [offer the Golden Idol,] sacrifice, desecrate (Decay). No leave button exists.
                if (n != 2 && n != 3) return null;
                int loss = Math.round(c.maxHp * (c.ascension >= 15 ? .35f : .25f));
                if (n == 3) return pick(0, "被遗忘的祭坛：献上金神像");
                return c.hp > loss ? pick(0, "被遗忘的祭坛：牺牲，不拿衰减诅咒") : pick(1, "被遗忘的祭坛：牺牲会致死，只能亵渎");
            }
            case "Accursed Blacksmith":
                // forge 0 (only listed with an upgradable card), rummage 1 (Pain), leave 2
                return n == 3 ? pick(0, "邪恶铁匠：锻造") : n == 2 ? pick(1, "邪恶铁匠：不拿疼痛诅咒，离开") : null;
            case "MindBloom":
                // war 0 (an Act 1 boss, rare relic), awake 1, rich 2 (curse). User rule: always the boss.
                return n == 3 ? pick(0, "心灵绽放：第一幕 Boss") : null;
            // Rules distilled from the community event guides (docs/event-rules.md), minus anything that needs save-and-load.
            case "Nest":
                return n == 2 ? pick(0, "巢穴：拿钱就跑（匕首不适配大牌组）") : null;
            case "Back to Basics":
                // simplicity 0 (remove a card), elegance 1 (upgrade Strikes and Defends)
                return n == 2 ? (c.removable > 0 ? pick(0, "古老文字：删牌") : pick(1, "古老文字：没有可删的牌，升级打防")) : null;
            case "Cursed Tome":
                // read 0, leave 1; on the last page take 0 (10-15 HP, random book) or stop 1: both times the second button
                return n == 2 ? pick(1, "诅咒之书：不读，不赌随机书") : null;
            case "Vampires":
                return n == 2 || n == 3 ? pick(n - 1, "吸血鬼：拒绝（噬咬牌质量低，还要删光打击）") : null;
            case "Masked Bandits":
                // pay 0 (all gold), fight 1 (Red Mask)
                return n == 2 ? pick(1, "蒙面强盗：宁死不给钱，战斗") : null;
            case "SecretPortal":
                return n == 2 ? pick(1, "秘密传送门：离开（跳过三层房间的收益）") : null;
            case "Tomb of Lord Red Mask":
                // with the Red Mask: wear it for 222 gold (0), leave; without: pay all gold for it, leave
                return n == 2 ? (c.redMask ? pick(0, "红面具陵墓：戴上红面具拿 222 金币") : pick(1, "红面具陵墓：不花光金币买面具，离开")) : null;
            case "SensoryStone":
                // recall 1 colorless card, 5 HP for 2, 10 HP for 3
                return n == 3 ? (healthy(c, .6) ? pick(2, "感知石：血量够，拿三张无色牌") : pick(0, "感知石：血量不足，只拿一张")) : null;
            case "FaceTrader":
                // touch (lose 10% HP, gold), trade (a random face, read ahead), leave
                if (n != 3) return null;
                if (c.goodFace == 1) return pick(1, "换脸商：预读到好脸，交易");
                return healthy(c, .4) ? pick(0, "换脸商：" + (c.goodFace == 0 ? "预读到不是好脸，只摸一下拿金币" : "摸一下拿金币"))
                    : pick(2, "换脸商：血量不足，离开");
            case "The Library":
                // read 0 (pick one of twenty cards), sleep 1 (heal)
                return n == 2 ? (healthy(c, .4) ? pick(0, "图书馆：读书选牌") : pick(1, "图书馆：血量低于 40%，睡觉回血")) : null;
            case "The Joust":
                // bet on the murderer 0 (100 gold if he wins), on the owner 1 (250 gold); the fight is decided by a read-ahead draw
                if (n != 2 || c.ownerWins == UNKNOWN) return null;
                return c.ownerWins == 1 ? pick(1, "对决：预读到店主获胜，押店主") : pick(0, "对决：预读到店主落败，押凶手");
            case "The Moai Head":
                // jump in 0 (full heal, lose max HP), give the Golden Idol 1 (333 gold, only listed with the idol), leave
                if (n == 3) return pick(1, "莫艾头：献上金神像换 333 金币");
                return n == 2 ? (c.hp * 3 < c.maxHp ? pick(0, "莫艾头：血量不足三成，跳进去回满") : pick(1, "莫艾头：离开")) : null;
            case "Mysterious Sphere":
                // open it (fight, rare relic) 0, leave 1
                return n == 2 ? (healthy(c, .6) ? pick(0, "神秘球体：打开战斗拿稀有遗物") : pick(1, "神秘球体：血量不足，离开")) : null;
            case "Designer": {
                // adjust (upgrade), clean up (remove or transform), full service (remove + upgrade), punch; unaffordable ones are not listed
                int adjust = c.ascension >= 15 ? 50 : 40, clean = c.ascension >= 15 ? 75 : 60, full = c.ascension >= 15 ? 110 : 90;
                int base = (c.gold >= clean ? 1 : 0) + (c.gold >= full ? 1 : 0) + 1;
                boolean hasAdjust = n == base + 1;
                if (n != base && !hasAdjust) return null;
                if (hasAdjust && c.gold < adjust) return null;
                int first = hasAdjust ? 1 : 0, cleanAt = first, fullAt = first + (c.gold >= clean ? 1 : 0);
                if (c.gold >= full && c.removable > 0) return pick(fullAt, "设计师：全套服务（删牌加升级）");
                if (c.gold >= clean && c.removable > 0) return pick(cleanAt, "设计师：清理（删牌或变形）");
                return hasAdjust ? pick(0, "设计师：调整（升级）") : null;
            }
            case "Neow Event": {
                if (c.neowBonus == null || c.neowDrawback == null || c.neowBonus.length != n) return null;
                // Lose HP, remove two cards: the strongest opening. Otherwise never take the curse drawback.
                for (int i = 0; i < n; i++)
                    if (c.neowBonus[i].equals("REMOVE_TWO") && (c.neowDrawback[i].equals("TEN_PERCENT_HP_LOSS")
                            || c.neowDrawback[i].equals("PERCENT_DAMAGE")))
                        return favor(i, "涅奥：掉血删两张牌（优先）", NEOW_BOOST);
                int[] cursed = new int[n];
                int count = 0;
                for (int i = 0; i < n; i++) if (c.neowDrawback[i].equals("CURSE")) cursed[count++] = i;
                return count == 0 ? null : without("涅奥：不拿诅咒", java.util.Arrays.copyOf(cursed, count));
            }
            case "The Woman in Blue": {
                // 1, 2, 3 potions for 20, 30, 40 gold, then leave (A15+: leaving costs HP). Fill the free slots.
                if (n != 4) return null;
                int count = c.sozu ? 0 : Math.min(3, c.freePotionSlots);
                while (count > 0 && c.gold < 10 * count + 10) count--;
                return count > 0 ? pick(count - 1, "蓝衣女子：买 " + count + " 瓶药") : pick(3, "蓝衣女子：买不了药，离开");
            }
            case "Winding Halls":
                return n == 3 ? without("蜿蜒走廊：不选带诅咒的回溯", 1) : null;
            default:
                return null;
        }
    }

    // ---- live reads (game thread only; every failure leaves the field unknown) ----------------

    static void readLive(Context c) {
        if (!live) return;
        try {
            AbstractEvent event = AbstractDungeon.getCurrRoom().event;
            if (event == null) return;
            switch (c.event) {
                case "Knowing Skull": c.skullGoldCost = intField(event, "goldCost"); break;
                case "Scrap Ooze": c.oozeLoss = oozeLoss(event); break;
                case "Dead Adventurer": adventurer(event, c); break;
                case "FaceTrader": c.goodFace = goodFace(); break;
                case "The Joust": c.ownerWins = AbstractDungeon.miscRng.copy().randomBoolean(0.3f) ? 1 : 0; break;
                default: break;
            }
        } catch (Throwable ignored) {
            // Stay UNKNOWN: the caller falls back to the network.
        }
    }

    /** Replays the event's own draws on a copy of miscRng (same order as EventForesight.scrapOoze). */
    static int oozeLoss(AbstractEvent event) throws ReflectiveOperationException {
        int chance = intField(event, "relicObtainChance"), dmg = intField(event, "dmg"), total = 0;
        Random rng = AbstractDungeon.miscRng.copy();
        for (int n = 0; n < 20; n++) {
            total += dmg;
            if (rng.random(0, 99) >= 99 - chance) return total;
            chance += 10;
            dmg++;
        }
        return 0;
    }

    /** Replays FaceTrader.getRandomFace on a copy of miscRng: the first shuffled id among the faces not yet owned. */
    private static int goodFace() {
        java.util.ArrayList<String> ids = new java.util.ArrayList<String>();
        for (String id : new String[]{"CultistMask", "FaceOfCleric", "GremlinMask", "NlothsMask", "SsserpentHead"})
            if (!AbstractDungeon.player.hasRelic(id)) ids.add(id);
        if (ids.isEmpty()) return UNKNOWN;
        java.util.Collections.shuffle(ids, new java.util.Random(AbstractDungeon.miscRng.copy().randomLong()));
        return ids.get(0).equals("FaceOfCleric") || ids.get(0).equals("SsserpentHead") ? 1 : 0;
    }

    private static void adventurer(AbstractEvent event, Context c) throws ReflectiveOperationException {
        int chance = intField(event, "encounterChance");
        c.adventurerEnemy = intField(event, "enemy");
        Random rng = AbstractDungeon.miscRng.copy();
        c.adventurerNext = rng.random(0, 99) < chance ? ELITE_SEARCH : SAFE_SEARCH;
    }

    private static int intField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return (Integer) field.get(target);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
