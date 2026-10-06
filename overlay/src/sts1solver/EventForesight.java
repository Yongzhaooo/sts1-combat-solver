package sts1solver;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.events.shrines.GremlinMatchGame;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.helpers.EventHelper;
import com.megacrit.cardcrawl.helpers.MonsterHelper;
import com.megacrit.cardcrawl.helpers.PotionHelper;
import com.megacrit.cardcrawl.helpers.RelicLibrary;
import com.megacrit.cardcrawl.random.Random;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Per-event mirrors of random option outcomes. Each mirror replays the event's
 * own RNG calls (same stream, same order) inside a Sandbox and writes rows.
 * To support another event, read its buttonEffect/update and register it here.
 *
 * Conventions: AbstractDungeon.miscRng (and every other stream) inside a mirror is
 * already a Sandbox copy, but options are alternatives, so a mirror that needs an
 * option's draws takes {@code miscRng.copy()} instead of advancing the shared copy.
 */
final class EventForesight {
    interface Mirror { void predict(AbstractEvent event, List<String> out); }

    private static final String E = "com.megacrit.cardcrawl.events.";
    private static final Map<String, Mirror> MIRRORS = new HashMap<>();
    static {
        // Grid transforms call transformCard(card, false, miscRng) once the card is picked.
        // Option slots: LivingWall forget 0 / change 1 / grow 2 (constructor setDialogOption @38,@50,@77|@94);
        // Transmogrifier transform 0 / leave 1 (@28,@40).
        MIRRORS.put(E + "exordium.LivingWall", transform(1));
        MIRRORS.put(E + "shrines.Transmogrifier", transform(0));
        MIRRORS.put(E + "city.DrugDealer", (e, out) -> {
            if (!onPage(e, "0") || AbstractDungeon.player.masterDeck.getPurgeableCards().size() < 2) return;
            out.add(I18n.t("变化两张：点击「变化预览」打开试选"));
            Foresight.note(1, I18n.t("求解器可试选两张，预览完整结果"));
        });
        MIRRORS.put(E + "exordium.BigFish", (e, out) -> {
            if (!onPage(e, "INTRO")) return;
            String relic = relicName();
            out.add(I18n.t("盒子 → ") + relic + I18n.t(" + 遗憾"));
            Foresight.note(2, I18n.t("遗物：") + relic + I18n.t(" + 遗憾"));  // slots: banana 0 (@73), donut 1 (@110), box 2 (@127)
        });
        // exordium
        MIRRORS.put(E + "exordium.ShiningLight", EventForesight::shiningLight);
        MIRRORS.put(E + "exordium.GoldenWing", EventForesight::goldenWing);
        MIRRORS.put(E + "exordium.GoopPuddle", EventForesight::goopPuddle);
        MIRRORS.put(E + "exordium.ScrapOoze", EventForesight::scrapOoze);
        MIRRORS.put(E + "exordium.DeadAdventurer", EventForesight::deadAdventurer);
        MIRRORS.put(E + "exordium.Mushrooms", EventForesight::mushrooms);
        // city
        MIRRORS.put(E + "city.TheMausoleum", EventForesight::mausoleum);
        MIRRORS.put(E + "city.TheJoust", EventForesight::joust);
        MIRRORS.put(E + "city.CursedTome", EventForesight::cursedTome);
        MIRRORS.put(E + "city.Addict", EventForesight::addict);
        MIRRORS.put(E + "city.KnowingSkull", EventForesight::knowingSkull);
        MIRRORS.put(E + "city.MaskedBandits", EventForesight::maskedBandits);
        MIRRORS.put(E + "city.TheLibrary", EventForesight::library);
        // beyond
        MIRRORS.put(E + "beyond.Falling", EventForesight::falling);
        MIRRORS.put(E + "beyond.MysteriousSphere", EventForesight::mysteriousSphere);
        MIRRORS.put(E + "beyond.SensoryStone", EventForesight::sensoryStone);
        MIRRORS.put(E + "beyond.MindBloom", EventForesight::mindBloom);
        // shrines
        MIRRORS.put(E + "shrines.GremlinWheelGame", EventForesight::wheel);
        MIRRORS.put(E + "shrines.Lab", EventForesight::lab);
        MIRRORS.put(E + "shrines.WomanInBlue", EventForesight::womanInBlue);
        MIRRORS.put(E + "shrines.FaceTrader", EventForesight::faceTrader);
        MIRRORS.put(E + "shrines.Designer", EventForesight::designer);
        MIRRORS.put(E + "shrines.WeMeetAgain", EventForesight::weMeetAgain);
        MIRRORS.put(E + "shrines.Nloth", EventForesight::nloth);
        MIRRORS.put(E + "shrines.GremlinMatchGame", EventForesight::matchGame);
        MIRRORS.put(E + "shrines.NoteForYourself", EventForesight::noteForYourself);
    }

    private EventForesight() { }

    static int transformCount(AbstractEvent event) {
        if (event == null) return 0;
        switch (event.getClass().getName()) {
            case E + "city.DrugDealer": return onPage(event, "0") ? 2 : 0;
            case E + "shrines.Designer":
                return onPage(event, "INTRO", "MAIN") && !flag(event, "cleanUpRemovesCards") ? 2 : 0;
            case E + "exordium.LivingWall":
            case E + "shrines.Transmogrifier": return onPage(event, "INTRO") ? 1 : 0;
            default: return 0;
        }
    }

    static CardGroup transformCards(AbstractEvent event) {
        CardGroup cards = AbstractDungeon.player.masterDeck.getPurgeableCards();
        // DrugDealer includes bottled cards, unlike the other event pickers.
        return event != null && event.getClass().getName().equals(E + "city.DrugDealer")
            ? cards : CardGroup.getGroupWithoutBottledCards(cards);
    }

    static boolean transformSelecting(AbstractEvent event) {
        if (event == null) return false;
        if (event.getClass().getName().equals(E + "city.DrugDealer")) return !flag(event, "cardsSelected");
        return event.getClass().getName().equals(E + "shrines.Designer") && "TRANSFORM".equals(String.valueOf(field(event, "option")));
    }

    private static Mirror transform(int slot) {
        return (e, out) -> {
            if (!onPage(e, "INTRO")) return;
            out.add(I18n.t("变化结果（按所选卡牌）"));
            Foresight.transforms(AbstractDungeon.miscRng, out);
            Foresight.note(slot, I18n.t("选牌时每张牌下方标出结果"));
        };
    }

    static void predict(AbstractEvent event, List<String> out) {
        String name = name(event);
        Mirror mirror = MIRRORS.get(event.getClass().getName());
        if (mirror == null) {
            out.add(name + I18n.t(" · 暂未适配预测"));
            return;
        }
        out.add(name + I18n.t(" · 选项的确定结果"));
        try (Sandbox ignored = new Sandbox()) {
            mirror.predict(event, out);
        } catch (RuntimeException | LinkageError error) {
            System.err.println("[STS1Solver] event mirror " + event.getClass().getSimpleName() + ": " + error);
            out.add(I18n.t("  预测失败：") + error.getClass().getSimpleName());
        }
        if (out.size() == 1) out.add(I18n.t("  当前页没有可预测的随机项"));
    }

    static String name(AbstractEvent event) {
        try {
            Field id = event.getClass().getField("ID");
            return EventHelper.getEventName((String) id.get(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return event.getClass().getSimpleName();
        }
    }

    /** Events keep their page in screen/screenNum/curScreen; a page change can change the prediction. */
    static String stateKey(AbstractEvent event) {
        StringBuilder key = new StringBuilder();
        for (String field : PAGE_FIELDS) {
            Object value = fieldOrNull(event, field);
            if (value != null) key.append(field).append('=').append(value).append(';');
        }
        // Matched pairs leave the board without changing the page.
        if (event instanceof GremlinMatchGame) {
            Object cards = fieldOrNull(event, "cards");
            if (cards != null) key.append(((CardGroup) cards).size());
        }
        return key.toString();
    }

    // ---- reflection / page helpers ------------------------------------------------

    private static final String[] PAGE_FIELDS = {"screen", "curScreen", "screenNum"};

    /** Declared field of the event class or any superclass (screenNum lives in AbstractEvent). */
    private static Object fieldOrNull(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(I18n.t("游戏字段无法读取：") + name, e);
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        Object value = fieldOrNull(target, name);
        if (value == null) throw new IllegalStateException(I18n.t("游戏字段已变化：") + target.getClass().getSimpleName() + "." + name);
        return value;
    }

    private static int intField(Object target, String name) { return (Integer) field(target, name); }
    private static boolean flag(Object target, String name) { return (Boolean) field(target, name); }

    /** Current page: screen / curScreen enum name, else the integer screenNum. */
    private static String page(AbstractEvent event) {
        for (String field : PAGE_FIELDS) {
            Object value = fieldOrNull(event, field);
            if (value != null) return String.valueOf(value);
        }
        return "";
    }

    private static boolean onPage(AbstractEvent event, String... pages) {
        String now = page(event);
        for (String p : pages) if (p.equals(now)) return true;
        return false;
    }

    // ---- output helpers -----------------------------------------------------------

    private static AbstractRelic.RelicTier tier() { return AbstractDungeon.returnRandomRelicTier(); }

    /** returnRandomRelicTier + returnRandomScreenlessRelic, the pair most events use. */
    private static String relicName() { return AbstractDungeon.returnRandomScreenlessRelic(tier()).name; }

    private static String upgraded(AbstractCard c) { return c.name + "+"; }

    private static Random misc() { return AbstractDungeon.miscRng.copy(); }

    /** Shuffles like the events do: Collections.shuffle(list, new java.util.Random(rng.randomLong())). */
    private static <T> void shuffle(List<T> list, Random rng) {
        Collections.shuffle(list, new java.util.Random(rng.randomLong()));
    }

    /** "head a / b / c / d" over several rows, perRow names each. */
    private static void rows(List<String> out, String head, List<String> names, int perRow) {
        for (int i = 0; i < names.size(); i += perRow) {
            String line = String.join(" / ", names.subList(i, Math.min(names.size(), i + perRow)));
            out.add(i == 0 ? head + line : "  " + line);
        }
    }

    private static String encounter(String key) { return MonsterHelper.getEncounterName(key); }

    private static String slashed(List<AbstractCard> cards) {
        List<String> names = new ArrayList<>();
        for (AbstractCard c : cards) names.add(c.name);
        return String.join("/", names);
    }

    // ---- exordium -------------------------------------------------------------------

    // upgradeCards(): upgradable cards in deck order, shuffled with Random(miscRng.randomLong()), first two.
    private static void shiningLight(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO") || !AbstractDungeon.player.masterDeck.hasUpgradableCards()) return;
        List<AbstractCard> pool = new ArrayList<>();
        for (AbstractCard c : AbstractDungeon.player.masterDeck.group) if (c.canUpgrade()) pool.add(c);
        shuffle(pool, misc());
        List<String> names = new ArrayList<>();
        for (AbstractCard c : pool.subList(0, Math.min(2, pool.size()))) names.add(upgraded(c));
        out.add(I18n.t("进入光芒（失 ") + intField(e, "damage") + I18n.t(" 血）→ 升级"));
        out.add("  " + String.join("、", names));
        Foresight.note(0, I18n.t("升级 ") + String.join("、", names));  // slots: enter 0 (@121|@137), leave 1 (@149)
    }

    // buttonEffect INTRO option 1: goldAmount = miscRng.random(50, 80) (needs canAttack).
    private static void goldenWing(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO") || !flag(e, "canAttack")) return;
        int gold = misc().random(50, 80);
        out.add(I18n.t("拔金翅（≥10伤牌）→ ") + gold + I18n.t(" 金币"));
        Foresight.note(1, I18n.t("金币 +") + gold);  // slots: purge 0 (@76), attack 1 (@134|@188), leave 2 (@201)
    }

    // goldLoss was rolled in the constructor.
    private static void goopPuddle(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        out.add(I18n.t("留下 → 失去 ") + intField(e, "goldLoss") + I18n.t(" 金币"));
    }

    // Each attempt: damage, roll = miscRng.random(0,99), success when roll >= 99 - chance (chance +10, dmg +1 per miss);
    // success: returnRandomRelicTier() -> returnRandomScreenlessRelic(tier). relicRng is only used on success.
    private static void scrapOoze(AbstractEvent e, List<String> out) {
        if (!onPage(e, "0")) return;
        int chance = intField(e, "relicObtainChance"), dmg = intField(e, "dmg"), total = 0;
        Random rng = misc();
        StringBuilder seq = new StringBuilder();
        for (int n = 0; n < 20; n++) {
            total += dmg;
            boolean success = rng.random(0, 99) >= 99 - chance;
            seq.append(success ? I18n.t("成功") : I18n.t("落空"));
            if (success) {
                out.add(I18n.t("连续伸手：") + seq + I18n.t("（共失 ") + total + I18n.t(" 血）"));
                String relic = relicName();
                out.add(I18n.t("  成功得 → ") + relic);
                Foresight.note(0, I18n.t("第") + (n + 1) + I18n.t("次成功：") + relic);  // slots: reach in 0 (@98), leave 1 (@110)
                return;
            }
            seq.append('→');
            chance += 10;
            dmg++;
        }
    }

    // Search = miscRng.random(0,99) < chance (A15: 35 else 25) -> fight (+30/25-35 gold) else randomReward()
    // (pop rewards[0], chance += 25; RELIC = tier + screenless relic). A fight also hands out the
    // unfound rewards (GOLD 30 / RELIC addRelicToRewards(returnRandomRelicTier())) at the next click.
    @SuppressWarnings("unchecked")
    private static void deadAdventurer(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        List<String> rewards = new ArrayList<>((List<String>) field(e, "rewards"));
        int chance = intField(e, "encounterChance"), found = intField(e, "numRewards");
        int enemy = intField(e, "enemy");
        String fight = encounter(enemy == 0 ? "3 Sentries" : enemy == 1 ? "Gremlin Nob" : "Lagavulin Event");
        Random rng = misc();
        List<String> seq = new ArrayList<>();
        for (int search = 1; found < 3 && !rewards.isEmpty(); search++) {
            if (rng.random(0, 99) < chance) {
                int gold = Settings.isDailyRun ? rng.random(30) : rng.random(25, 35);
                out.add(I18n.t("搜第 ") + search + I18n.t(" 次：遇敌 ") + fight + "（+" + gold + I18n.t(" 金币）"));
                seq.add(I18n.t("遇敌"));
                List<String> left = new ArrayList<>();
                for (String r : rewards) {
                    if ("GOLD".equals(r)) left.add(I18n.t("金币 30"));
                    else if ("RELIC".equals(r)) left.add(Foresight.relic(tier()));
                }
                if (!left.isEmpty()) out.add(I18n.t("  战后补发：") + String.join(" / ", left));
                break;
            }
            found++;
            chance += 25;
            String reward = rewards.remove(0);
            String token = "GOLD".equals(reward) ? I18n.t("金30") : "RELIC".equals(reward) ? relicName() : I18n.t("空");
            out.add(I18n.t("搜第 ") + search + I18n.t(" 次：") + ("GOLD".equals(reward) ? I18n.t("金币 30")
                : "RELIC".equals(reward) ? I18n.t("遗物 ") + token : I18n.t("什么都没有")));
            seq.add(token);
        }
        // slots: search 0 (addDialogOption @356), escape 1 (@368)
        if (!seq.isEmpty()) Foresight.note(0, I18n.t("依次搜：") + String.join("→", seq));
    }

    // screenNum 0 click -> screenNum 2; the second click rolls the fight gold (daily: random(25) else random(20,30)).
    private static void mushrooms(AbstractEvent e, List<String> out) {
        if (!onPage(e, "0", "2")) return;
        int gold = Settings.isDailyRun ? misc().random(25) : misc().random(20, 30);
        out.add(I18n.t("战斗奖励 → ") + gold + I18n.t(" 金币"));
        // slots: fight 0 (addDialogOption @45; the second click, still slot 0, rolls the gold), eat 1 (@99)
        Foresight.note(0, I18n.t("战后金币 +") + gold);
        AbstractRelic odd = AbstractDungeon.player.hasRelic("Odd Mushroom")
            ? RelicLibrary.getRelic("Circlet") : RelicLibrary.getRelic("Odd Mushroom");
        out.add(I18n.t("  另得遗物 ") + odd.name);
    }

    // ---- city -----------------------------------------------------------------------

    // option 0: miscRng.randomBoolean() (curse if true or A15+), then returnRandomRelicTier()/Screenless.
    private static void mausoleum(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        boolean curse = misc().randomBoolean() || AbstractDungeon.ascensionLevel >= 15;
        String result = (curse ? CardLibrary.getCopy("Writhe").name : I18n.t("无诅咒")) + " + " + relicName();
        out.add(I18n.t("打开棺材 → ") + result);
        Foresight.note(0, result);  // slots: open 0 (@84), leave 1 (@96)
    }

    // ownerWins = miscRng.randomBoolean(0.3f), rolled when the PRE_JOUST page is confirmed.
    private static void joust(AbstractEvent e, List<String> out) {
        boolean owner;
        if (onPage(e, "HALT", "EXPLANATION", "PRE_JOUST")) owner = misc().randomBoolean(0.3f);
        else if (onPage(e, "JOUST")) owner = flag(e, "ownerWins");
        else return;
        out.add(owner ? I18n.t("胜者：主人 → 押主人赢 250，押杀手输 50") : I18n.t("胜者：杀手 → 押杀手赢 100，押主人输 50"));
        // EXPLANATION page: slot 0 = bet on the murderer (betFor=false), slot 1 = bet on the owner (setDialogOption @155)
        if (onPage(e, "EXPLANATION")) {
            Foresight.note(0, owner ? I18n.t("主人赢：输 50") : I18n.t("杀手赢：赢 100"));
            Foresight.note(1, owner ? I18n.t("主人赢：赢 250") : I18n.t("杀手赢：输 50"));
        }
    }

    // randomBook(): unowned of Necronomicon/Enchiridion/Nilry's Codex (Circlet if none), pick miscRng.random(size-1).
    private static void cursedTome(AbstractEvent e, List<String> out) {
        if (onPage(e, "END")) return;
        List<String> books = new ArrayList<>();
        for (String id : new String[]{"Necronomicon", "Enchiridion", "Nilry's Codex"})
            if (!AbstractDungeon.player.hasRelic(id)) books.add(id);
        String id = books.isEmpty() ? "Circlet" : books.get(misc().random(books.size() - 1));
        String book = RelicLibrary.getRelic(id).name;
        out.add(I18n.t("读完全部 → ") + book);
        // Slot 0 reads on: INTRO @59, PAGE_1..3 @82/@119/@198; on LAST_PAGE slot 0 takes the book (@377, @387 = stop).
        Foresight.note(0, onPage(e, "LAST_PAGE") ? I18n.t("遗物：") + book : I18n.t("读完得：") + book);
    }

    // Both options end in returnRandomRelicTier()/returnRandomScreenlessRelic.
    private static void addict(AbstractEvent e, List<String> out) {
        if (!onPage(e, "0")) return;
        String relic = relicName();
        out.add(I18n.t("付 85 金币或偷窃 → ") + relic);
        // slots: pay 0 (@81|@138, disabled below 85 gold), steal 1 (@155), leave 2 (@167)
        if (AbstractDungeon.player.gold >= 85) Foresight.note(0, I18n.t("遗物：") + relic);
        Foresight.note(1, I18n.t("遗物：") + relic);
    }

    // Every pick consumes potionRng (getRandomPotion) / shuffleRng (returnColorlessCard(UNCOMMON), which also
    // reshuffles the shared pool in place, so the pool order is put back afterwards).
    private static void knowingSkull(AbstractEvent e, List<String> out) {
        if (onPage(e, "COMPLETE")) return;
        List<String> potions = new ArrayList<>();
        for (int i = 0; i < 3; i++) potions.add(PotionHelper.getRandomPotion().name);
        List<String> cards = new ArrayList<>();
        List<AbstractCard> pool = AbstractDungeon.colorlessCardPool.group;
        List<AbstractCard> saved = new ArrayList<>(pool);
        try {
            for (int i = 0; i < 3; i++) cards.add(AbstractDungeon.returnColorlessCard(AbstractCard.CardRarity.UNCOMMON).name);
        } finally {
            pool.clear();
            pool.addAll(saved);
        }
        out.add(I18n.t("药水（第1/2/3次）：") + String.join(" / ", potions));
        out.add(I18n.t("无色牌（第1/2/3次）：") + String.join(" / ", cards));
        // ASK page slots (buttonEffect @248, obtainReward): potion 0, gold 1 (fixed 90), card 2, leave 3
        if (onPage(e, "ASK")) {
            Foresight.note(0, I18n.t("药水：") + potions.get(0));
            Foresight.note(2, I18n.t("无色牌：") + cards.get(0));
        }
    }

    // Fight option: gold = daily ? random(30) : random(25,35); Red Mask is fixed.
    private static void maskedBandits(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        int gold = Settings.isDailyRun ? misc().random(30) : misc().random(25, 35);
        out.add(I18n.t("战斗奖励 → ") + gold + I18n.t(" 金币"));
        Foresight.note(1, I18n.t("战利品 +") + gold + I18n.t(" 金币"));  // slots: pay 0 (addDialogOption @29), fight 1 (@41)
        out.add(I18n.t("  另得遗物 ") + RelicLibrary.getRelic(AbstractDungeon.player.hasRelic("Red Mask") ? "Circlet" : "Red Mask").name);
    }

    // Read: 20 times rollRarity() + getCard(rarity) on cardRng, re-rolling duplicates of cards already in the list.
    private static void library(AbstractEvent e, List<String> out) {
        if (!onPage(e, "0")) return;
        List<AbstractCard> picked = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            AbstractCard card = AbstractDungeon.getCard(AbstractDungeon.rollRarity()).makeCopy();
            boolean dupe = true;
            while (dupe) {
                dupe = false;
                for (AbstractCard have : picked) {
                    if (have.cardID.equals(card.cardID)) {
                        dupe = true;
                        card = AbstractDungeon.getCard(AbstractDungeon.rollRarity()).makeCopy();
                        break;
                    }
                }
            }
            for (AbstractRelic r : AbstractDungeon.player.relics) r.onPreviewObtainCard(card);
            picked.add(card);
        }
        List<String> names = new ArrayList<>();
        for (AbstractCard c : picked) names.add(c.name);
        rows(out, I18n.t("阅读 → 20 张："), names, 4);
        Foresight.note(0, I18n.t("见面板：20 张牌"));  // slots: read 0 (@76), sleep 1 (@116)
    }

    // ---- beyond ---------------------------------------------------------------------

    // The three cards were drawn in the constructor (setCards); only the chosen type is lost.
    private static void falling(AbstractEvent e, List<String> out) {
        if (onPage(e, "RESULT")) return;
        Object skill = fieldOrNull(e, "skillCard"), power = fieldOrNull(e, "powerCard"), attack = fieldOrNull(e, "attackCard");
        out.add(I18n.t("失去的牌（已定）"));
        out.add(I18n.t("  技能 ") + (skill == null ? I18n.t("无") : ((AbstractCard) skill).name)
            + I18n.t(" · 能力 ") + (power == null ? I18n.t("无") : ((AbstractCard) power).name));
        out.add(I18n.t("  攻击 ") + (attack == null ? I18n.t("无") : ((AbstractCard) attack).name));
    }

    // Fight rewards: gold (daily also random(50)) = random(45,55), then returnRandomScreenlessRelic(RARE).
    private static void mysteriousSphere(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO", "PRE_COMBAT")) return;
        Random rng = misc();
        int gold = (Settings.isDailyRun ? rng.random(50) : 0) + rng.random(45, 55);
        String reward = gold + I18n.t(" 金币 + ") + AbstractDungeon.returnRandomScreenlessRelic(AbstractRelic.RelicTier.RARE).name;
        out.add(I18n.t("战斗奖励 → ") + reward);
        // slots: INTRO open 0 (addDialogOption @49), leave 1 (@61); PRE_COMBAT keeps the fight in slot 0
        Foresight.note(0, I18n.t("战后：") + reward);
    }

    // reward(n): n x RewardItem(COLORLESS) = getColorlessRewardCards() on cardRng; n = 1/2/3 for the three memories,
    // so every option takes a prefix of the same sequence.
    private static void sensoryStone(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO", "INTRO_2")) return;
        for (int group = 1; group <= 3; group++) {
            List<AbstractCard> cards = AbstractDungeon.getColorlessRewardCards();
            for (AbstractCard c : cards)
                for (AbstractRelic r : AbstractDungeon.player.relics) r.onPreviewObtainCard(c);
            out.add(I18n.t("第 ") + group + I18n.t(" 组：") + Foresight.cards(cards));
            if (group == 1 && onPage(e, "INTRO_2")) Foresight.note(0, I18n.t("第1组：") + slashed(cards));
        }
        out.add(I18n.t("记忆1得第1组，2得1-2组，3得1-3组"));
        // INTRO_2 slots (buttonEffect @149): memory 1 -> choice 1, memory 2 -> 2, memory 3 -> 3
        if (onPage(e, "INTRO_2")) {
            Foresight.note(1, I18n.t("第1+2组，见面板"));
            Foresight.note(2, I18n.t("第1+2+3组，见面板"));
        }
    }

    // Fight option: boss = first of [Guardian, Hexaghost, Slime Boss] after Collections.shuffle(Random(miscRng.randomLong())).
    private static void mindBloom(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        List<String> bosses = new ArrayList<>(Arrays.asList("The Guardian", "Hexaghost", "Slime Boss"));
        shuffle(bosses, misc());
        out.add(I18n.t("我是战士 → Boss：") + encounter(bosses.get(0)));
        Foresight.note(0, "Boss：" + encounter(bosses.get(0)));  // slots: fight 0 (@28), upgrade 1 (@40), gold/heal 2 (@68|@88)
        out.add(I18n.t("其余两项无随机（升级/金币或疑虑）"));
    }

    // ---- shrines --------------------------------------------------------------------

    // Spin: result = miscRng.random(0,5): 0 gold, 1 relic (tier + screenless), 2 full heal, 3 Decay, 4 purge, 5 HP loss.
    private static void wheel(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        String text;
        switch (misc().random(0, 5)) {
            case 0: text = I18n.t("金币 +") + intField(e, "goldAmount"); break;
            case 1: text = I18n.t("遗物 ") + relicName(); break;
            case 2: text = I18n.t("回满生命"); break;
            case 3: text = I18n.t("获得诅咒 ") + CardLibrary.getCopy("Decay").name; break;
            case 4: text = I18n.t("删除一张牌（自选）"); break;
            default:
                text = I18n.t("受到 ") + (int) (AbstractDungeon.player.maxHealth * (Float) field(e, "hpLossPercent")) + I18n.t(" 点伤害");
        }
        out.add(I18n.t("转轮结果 → ") + text);
        Foresight.note(0, I18n.t("结果：") + text);  // slots: spin 0 (setDialogOption @187); buttonEffect INTRO `ifne` -> only index 0 spins
    }

    // Option: 2 potions on A15+, else 3, each PotionHelper.getRandomPotion() on potionRng.
    private static void lab(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        List<String> names = new ArrayList<>();
        for (int i = AbstractDungeon.ascensionLevel >= 15 ? 2 : 3; i > 0; i--) names.add(PotionHelper.getRandomPotion().name);
        out.add(I18n.t("取走 → ") + String.join(" / ", names));
        Foresight.note(0, I18n.t("药水：") + String.join("/", names));  // slots: take 0 (setDialogOption @33)
    }

    // Buying 1/2/3 potions draws the first 1/2/3 of one potionRng sequence.
    private static void womanInBlue(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 3; i++) names.add(PotionHelper.getRandomPotion().name);
        out.add(I18n.t("买 1/2/3 瓶 → 依次得到："));
        out.add("  " + String.join(" / ", names));
        // slots: 1 potion 0 (@59), 2 potions 1 (@97), 3 potions 2 (@135), leave 3 (@193|@208)
        for (int i = 0; i < 3; i++) Foresight.note(i, String.join("/", names.subList(0, i + 1)));
    }

    // getRandomFace(): unowned of the five masks (Circlet if none), Collections.shuffle(Random(miscRng.randomLong())), first.
    private static void faceTrader(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO", "MAIN")) return;
        List<String> faces = new ArrayList<>();
        for (String id : new String[]{"CultistMask", "FaceOfCleric", "GremlinMask", "NlothsMask", "SsserpentHead"})
            if (!AbstractDungeon.player.hasRelic(id)) faces.add(id);
        if (faces.isEmpty()) faces.add("Circlet");
        shuffle(faces, misc());
        String face = RelicLibrary.getRelic(faces.get(0)).name;
        out.add(I18n.t("交易 → ") + face);
        // MAIN page slots (buttonEffect @157): touch 0 (updateDialogOption @119), trade 1 (@131), leave 2 (@143)
        if (onPage(e, "MAIN")) Foresight.note(1, I18n.t("面具：") + face);
    }

    // adjustmentUpgradesOne/cleanUpRemovesCards were rolled in the constructor. Random parts: upgradeTwoRandomCards
    // (shuffle Random(miscRng.randomLong()) of upgradable cards) and transformCard(.., miscRng) per picked card.
    private static void designer(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO", "MAIN")) return;
        if (flag(e, "adjustmentUpgradesOne")) out.add(I18n.t("调整 → 自选一张升级（无随机）"));
        else {
            List<AbstractCard> pool = new ArrayList<>();
            for (AbstractCard c : AbstractDungeon.player.masterDeck.group) if (c.canUpgrade()) pool.add(c);
            shuffle(pool, misc());
            List<String> names = new ArrayList<>();
            for (AbstractCard c : pool.subList(0, Math.min(2, pool.size()))) names.add(upgraded(c));
            out.add(I18n.t("调整 → 随机升级 ") + (names.isEmpty() ? I18n.t("无") : String.join("、", names)));
            // MAIN page slots (buttonEffect @571): adjust 0, clean up 1, full service 2, punch 3
            if (onPage(e, "MAIN") && !names.isEmpty()) Foresight.note(0, I18n.t("升级 ") + String.join("、", names));
        }
        if (flag(e, "cleanUpRemovesCards")) out.add(I18n.t("清理 → 自选删一张（无随机）"));
        else {
            out.add(I18n.t("清理 → 变化两张，点击「变化预览」试选"));
            if (onPage(e, "MAIN")) Foresight.note(1, I18n.t("求解器可试选两张，预览完整结果"));
        }
        out.add(I18n.t("全套 → 各删牌选择的随机升级（滚轮查看）"));
        List<AbstractCard> eligible = new ArrayList<>();
        for (AbstractCard card : AbstractDungeon.player.masterDeck.group)
            if (card.canUpgrade()) eligible.add(card);
        long seed = misc().randomLong();
        int index = 0;
        for (AbstractCard removed : AbstractDungeon.player.masterDeck.getPurgeableCards().group) {
            AbstractCard upgradedCard = upgradeAfterRemoval(eligible, removed, seed);
            out.add("  " + (++index) + I18n.t(" 删 ") + removed.name + " → "
                + (upgradedCard == null ? I18n.t("无牌可升级") : upgraded(upgradedCard)));
        }
        if (onPage(e, "MAIN")) Foresight.note(2, I18n.t("删牌后升级结果：展开面板查看"));
    }

    // Designer.update removes the chosen instance, then shuffles the remaining
    // upgradeable cards with one miscRng.randomLong(). Alternatives reuse that draw.
    static <T> T upgradeAfterRemoval(List<T> eligible, T removed, long seed) {
        List<T> remaining = new ArrayList<>(eligible);
        remaining.remove(removed);
        Collections.shuffle(remaining, new java.util.Random(seed));
        return remaining.isEmpty() ? null : remaining.get(0);
    }

    // The potion/gold/card costs were rolled in the constructor; every trade ends in tier + screenless relic.
    private static void weMeetAgain(AbstractEvent e, List<String> out) {
        if (!onPage(e, "INTRO")) return;
        String relic = relicName();
        out.add(I18n.t("任一交易 → ") + relic);
        // slots: potion 0 (setDialogOption @82|@98), gold 1 (@163|@179), card 2 (@245|@261), attack 3 (@274); buttonEffect @44
        if (fieldOrNull(e, "potionOption") != null) Foresight.note(0, I18n.t("遗物：") + relic);
        if (intField(e, "goldAmount") > 0) Foresight.note(1, I18n.t("遗物：") + relic);
        if (fieldOrNull(e, "cardOption") != null) Foresight.note(2, I18n.t("遗物：") + relic);
    }

    // choice1/choice2 were shuffled in the constructor; the gift is fixed.
    private static void nloth(AbstractEvent e, List<String> out) {
        if (!onPage(e, "0")) return;
        AbstractRelic gift = AbstractDungeon.player.hasRelic("Nloth's Gift")
            ? RelicLibrary.getRelic("Circlet") : RelicLibrary.getRelic("Nloth's Gift");
        out.add(I18n.t("换 ") + ((AbstractRelic) field(e, "choice1")).name + I18n.t(" 或 ") + ((AbstractRelic) field(e, "choice2")).name);
        out.add(I18n.t("  → 得 ") + gift.name + I18n.t("（无随机）"));
    }

    // The board was built and shuffled in the constructor; card i sits at column i%4, row i%3 (placeCards).
    // Once PLAY starts, matched cards leave the group, so read the on-screen target positions instead.
    private static void matchGame(AbstractEvent e, List<String> out) {
        if (onPage(e, "COMPLETE", "CLEAN_UP")) return;
        List<AbstractCard> group = ((CardGroup) field(e, "cards")).group;
        String[][] board = new String[3][4];
        for (String[] row : board) Arrays.fill(row, "·");
        boolean placed = onPage(e, "PLAY");
        for (int i = 0; i < group.size(); i++) {
            AbstractCard c = group.get(i);
            int col = i % 4, row = i % 3;
            if (placed) {
                col = Math.round((c.target_x - 640f * Settings.xScale) / (210f * Settings.xScale));
                row = Math.round((750f * Settings.yScale - c.target_y) / (230f * Settings.yScale));
            }
            if (row >= 0 && row < 3 && col >= 0 && col < 4) board[row][col] = c.name;
        }
        out.add(I18n.t("牌面布局（已定，3 行 × 4 列）"));
        for (int r = 0; r < 3; r++) out.add("  " + String.join(" / ", board[r]));
    }

    // The card comes from the previous run's saved note (Prefs), not from the seed.
    private static void noteForYourself(AbstractEvent e, List<String> out) {
        if (onPage(e, "COMPLETE")) return;
        Object card = fieldOrNull(e, "obtainCard");
        if (card == null) return;
        AbstractCard c = (AbstractCard) card;
        out.add(I18n.t("取走 → ") + (c.timesUpgraded > 0 ? upgraded(c) : c.name) + I18n.t("（来自存档，无随机）"));
    }
}
