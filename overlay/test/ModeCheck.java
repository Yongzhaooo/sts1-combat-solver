package sts1solver;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import java.util.EnumSet;

/** The three automation modes (Standby, Combat auto, AI autopilot), plan approval and the Boss fast-finish switch. */
public final class ModeCheck {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        require(SolverMod.smokeOpening(1,0,0,0), "untouched T1 smoke policy entry");
        require(!SolverMod.smokeOpening(1,1,0,0), "real card history closes smoke policy entry");
        require(!SolverMod.smokeOpening(1,0,1,0), "real damage closes entry even after healing");
        require(!SolverMod.smokeOpening(1,0,0,1), "HP loss closes entry");
        require(!SolverMod.smokeOpening(2,0,0,0), "T2 closes entry");
        // Flags -> mode. outsideMode: 0 no outside suggestions, 1 the agent confirms each outside step, 2 pilot.
        for (boolean run : new boolean[]{false, true})
            require(RunMode.of(2, run) == RunMode.PILOT, "pilot ignores runAuto");
        require(RunMode.of(0, false) == RunMode.STANDBY && RunMode.of(1, false) == RunMode.STANDBY, "fights wait: Standby");
        require(RunMode.of(0, true) == RunMode.COMBAT_AUTO, "combat auto");
        require(RunMode.of(1, true) == RunMode.COMBAT_AUTO, "agent play keeps its fights automatic, never Standby");
        require(RunMode.values().length == 3, "exactly three modes");
        com.google.gson.JsonParser json = new com.google.gson.JsonParser();
        com.google.gson.JsonObject revivalSwap = json.parse("{\"needs_confirmation\":false,\"branches\":["
            + "{\"id\":\"no-potion\",\"available\":true},{\"id\":\"potion-1\",\"available\":true}]}").getAsJsonObject();
        require(SolverMod.routeChoice(revivalSwap, true), "Standby lists the routes whenever two are usable");
        require(!SolverMod.approved(revivalSwap, false, true), "Standby never plays (or steps) a plan the player has not picked");
        require(SolverMod.approved(revivalSwap, true, true), "a picked plan may run");
        require(SolverMod.approved(revivalSwap, false, false), "Combat auto picks by itself");
        require(!SolverMod.routeChoice(revivalSwap, false), "Combat auto keeps choosing by itself");
        com.google.gson.JsonObject single = json.parse("{\"needs_confirmation\":false,\"branches\":["
            + "{\"id\":\"no-potion\",\"available\":true},{\"id\":\"potion-0\",\"available\":false}]}").getAsJsonObject();
        require(!SolverMod.routeChoice(single, true), "one usable route needs no choice");
        require(SolverMod.approved(single, false, true), "with one usable plan, Play is the approval");
        com.google.gson.JsonObject thief = json.parse("{\"needs_confirmation\":true,\"manual_choice\":true,\"branches\":[]}").getAsJsonObject();
        require(!SolverMod.approved(thief, false, false) && SolverMod.approved(thief, true, false), "a backend question waits for its answer in every mode");
        require(SolverMod.routeChoice(json.parse("{\"needs_confirmation\":true}").getAsJsonObject(), false),
            "a backend confirmation is always asked");

        // Agent commands: "<decision> <index>" for the current decision only.
        require(RunMode.agentChoice("7 2", 7, 3) == 2, "agent picks a listed choice");
        require(RunMode.agentChoice(" 7   0\n", 7, 3) == 0, "agent command whitespace");
        require(RunMode.agentChoice("6 2", 7, 3) == -1, "stale decision refused");
        require(RunMode.agentChoice("7 3", 7, 3) == -1, "index past the list refused");
        require(RunMode.agentChoice("7 -1", 7, 3) == -1 && RunMode.agentChoice("7 x", 7, 3) == -1, "malformed index refused");
        require(RunMode.agentChoice("7", 7, 3) == -1 && RunMode.agentChoice("0 0", 0, 3) == -1, "no decision pending");

        // Standby waits for the player in every fight; Combat auto automates fights alone; the pilot does both.
        require(!RunMode.combatAutomatic(RunMode.STANDBY) && !RunMode.outsideAutomatic(RunMode.STANDBY), "standby waits");
        require(RunMode.combatAutomatic(RunMode.COMBAT_AUTO) && !RunMode.outsideAutomatic(RunMode.COMBAT_AUTO), "combat auto: fights only");
        require(RunMode.combatAutomatic(RunMode.PILOT) && RunMode.outsideAutomatic(RunMode.PILOT), "pilot: fights and outside");
        for (RunMode mode : EnumSet.allOf(RunMode.class)) require(RunMode.stop(mode) == RunMode.STANDBY, "F9 from " + mode);

        // Boss fast finish: off by default means no fast finish in any Boss fight; on keeps the earlier rule.
        for (int act = 1; act <= 4; act++) for (int ascension : new int[]{0, 20}) for (int bosses = 0; bosses <= 2; bosses++)
            for (boolean noHealing : new boolean[]{false, true}) for (boolean bossRoom : new boolean[]{false, true}) {
                require(!SolverMod.bossFastFinish(false, bossRoom, act, ascension, bosses, noHealing), "switch off never fast-finishes");
                require(SolverMod.bossFastFinish(true, bossRoom, act, ascension, bosses, noHealing)
                    == SolverMod.bossFastFinish(bossRoom, act, ascension, bosses, noHealing), "switch on keeps the rule");
            }
        require(SolverMod.bossFastFinish(true, true, 3, 20, 1, false), "A20 act 3 second Boss (one candidate left) fast-finishes when on");
        require(!SolverMod.bossFastFinish(true, true, 3, 20, 2, false), "A20 act 3 first Boss never");
        require(!SolverMod.bossFastFinish(true, true, 4, 0, 0, false), "the Heart never");

        com.google.gson.JsonObject unavailable = new com.google.gson.JsonObject();
        String noUse = "本轮搜索未找到使用路线";
        String merged = "药水不改变结果，已并入不用药路线";
        unavailable.addProperty("message", noUse);
        require(SolverMod.unavailableRouteReason(unavailable).equals(noUse), "show the actual route search reason");
        unavailable.addProperty("message", merged);
        require(SolverMod.unavailableRouteReason(unavailable).equals(merged), "explain a route merged into no-potion");
        String longError = "Failed to validate potion route at turn 12: " + String.join(" ", java.util.Collections.nCopies(30, "original-error-detail"));
        unavailable.addProperty("message", longError);
        require(SolverMod.unavailableRouteReason(unavailable).equals(longError), "unknown errors retain every detail");
        unavailable.remove("message");
        require(SolverMod.unavailableRouteReason(unavailable).equals("本轮未提供原因，请重新计算或导出日志"), "missing cause is explicit");

        // Panel folding: a fight opens the panel, other screens fold it, and autoFold never folds a panel the
        // player held open by hand until the fight ends.
        require(!SolverMod.foldsAway(AbstractDungeon.CurrentScreen.NONE, true, false, false), "a combat screen keeps the panel open");
        require(SolverMod.foldsAway(AbstractDungeon.CurrentScreen.GRID, true, false, false), "a card-select screen folds it");
        require(!SolverMod.foldsAway(AbstractDungeon.CurrentScreen.NONE, false, true, false), "an event screen keeps it open");
        require(SolverMod.foldsAway(AbstractDungeon.CurrentScreen.NONE, false, false, false), "outside a fight it folds");
        require(SolverMod.foldsAway(AbstractDungeon.CurrentScreen.NONE, false, false, true), "the pilot folds it outside");
        require(SolverMod.autoFolds(true, false, true, false, false, true), "a new screen folds it");
        require(!SolverMod.autoFolds(true, true, false, false, false, true), "the same screen twice does not fold again");
        require(!SolverMod.autoFolds(true, false, true, true, false, true), "an already folded panel stays folded");
        require(!SolverMod.autoFolds(true, false, true, false, true, true), "held open in a fight: no auto fold");
        require(SolverMod.autoFolds(true, false, true, false, true, false), "held open last fight: folds outside a fight");
        require(!SolverMod.autoUnfolds(true, true, true, false, true), "no unfold while folding");
        require(SolverMod.autoUnfolds(false, true, true, false, true), "Combat auto reopens it");
        require(SolverMod.autoUnfolds(false, true, true, true, true), "Standby reopens it during the fight");
        require(!SolverMod.autoUnfolds(false, true, true, true, false), "Standby keeps it folded outside a fight");
        require(!SolverMod.autoUnfolds(false, true, false, false, true), "never unfold what the player folded");

        // New and renamed panel text is translated.
        I18n.setLanguage("en");
        for (String key : new String[]{" · 滚轮查看完整路线与原因", "本轮无可选路线", "本轮未提供原因，请重新计算或导出日志", "Boss 快速收尾：", "未安装", "战斗全自动", "执行整场", "暂停自动", "计算", "执行", "暂停", "待命", "待命 · 点【计算】开始搜索", "待命：点【重新计算】才开始搜索", "确认（回车）",
                "战斗全自动已开 · 等待下场；其他选择由你决定", "按 F1-F12 绑定；F6/F7/F8/F9 保留；Esc 取消",
                "Boss 快速收尾：找到无药胜线就停止搜索，省时间但可能多掉血；关闭时与普通战斗相同",
                "已停止 · 待命：局外由你决定，战斗点【计算】才搜索", "Agent 确认每一步局外选择",
                "新一局：选【待命】每场点了才算，【战斗全自动】只自动打战斗，或【AI全自动】完全托管"})
            require(!I18n.t(key).equals(key), "English text for " + key);
        System.out.println("PASS: three modes (Standby, Combat auto, AI autopilot), Standby plays only picked plans, Boss fast-finish switch, English text");
    }
}
