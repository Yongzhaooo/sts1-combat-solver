package sts1solver;

import java.util.EnumSet;

/** The three automation modes (AI autopilot, Combat auto, Manual step by step) and the Boss fast-finish switch. */
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
        // Flags -> mode. outsideMode: 0 no outside suggestions, 1 suggest and confirm each step, 2 pilot.
        for (boolean run : new boolean[]{false, true})
            require(RunMode.of(2, run) == RunMode.PILOT, "pilot ignores runAuto");
        require(RunMode.of(1, false) == RunMode.MANUAL, "manual with suggestions");
        require(RunMode.of(0, false) == RunMode.MANUAL, "manual with the suggestions card hidden");
        require(RunMode.of(0, true) == RunMode.COMBAT_AUTO, "combat auto");
        require(RunMode.values().length == 3, "exactly three modes");
        com.google.gson.JsonParser json = new com.google.gson.JsonParser();
        com.google.gson.JsonObject revivalSwap = json.parse("{\"needs_confirmation\":false,\"branches\":["
            + "{\"id\":\"no-potion\",\"available\":true},{\"id\":\"potion-1\",\"available\":true}]}").getAsJsonObject();
        require(SolverMod.routeChoice(revivalSwap, true), "Manual lists the routes whenever two are usable");
        require(!SolverMod.routeChoice(revivalSwap, false), "Combat auto keeps choosing by itself");
        com.google.gson.JsonObject single = json.parse("{\"needs_confirmation\":false,\"branches\":["
            + "{\"id\":\"no-potion\",\"available\":true},{\"id\":\"potion-0\",\"available\":false}]}").getAsJsonObject();
        require(!SolverMod.routeChoice(single, true), "one usable route needs no choice");
        require(SolverMod.routeChoice(json.parse("{\"needs_confirmation\":true}").getAsJsonObject(), false),
            "a backend confirmation is always asked");

        // Agent commands: "<decision> <index>" for the current decision only.
        require(RunMode.agentChoice("7 2", 7, 3) == 2, "agent picks a listed choice");
        require(RunMode.agentChoice(" 7   0\n", 7, 3) == 0, "agent command whitespace");
        require(RunMode.agentChoice("6 2", 7, 3) == -1, "stale decision refused");
        require(RunMode.agentChoice("7 3", 7, 3) == -1, "index past the list refused");
        require(RunMode.agentChoice("7 -1", 7, 3) == -1 && RunMode.agentChoice("7 x", 7, 3) == -1, "malformed index refused");
        require(RunMode.agentChoice("7", 7, 3) == -1 && RunMode.agentChoice("0 0", 0, 3) == -1, "no decision pending");

        // Manual waits for the player in combat and outside; Combat auto only fights alone; the pilot does both.
        require(!RunMode.combatAutomatic(RunMode.MANUAL) && !RunMode.outsideAutomatic(RunMode.MANUAL), "manual waits for every step");
        require(RunMode.combatAutomatic(RunMode.COMBAT_AUTO) && !RunMode.outsideAutomatic(RunMode.COMBAT_AUTO), "combat auto: fights only");
        require(RunMode.combatAutomatic(RunMode.PILOT) && RunMode.outsideAutomatic(RunMode.PILOT), "pilot: fights and outside");

        // Combat auto button.
        require(RunMode.combatButton(RunMode.MANUAL, false) == RunMode.COMBAT_AUTO, "manual -> combat auto");
        require(RunMode.combatButton(RunMode.MANUAL, true) == RunMode.MANUAL, "this fight's own automation is switched off first");
        require(RunMode.combatButton(RunMode.COMBAT_AUTO, false) == RunMode.MANUAL, "combat auto -> manual");
        require(RunMode.combatButton(RunMode.COMBAT_AUTO, true) == RunMode.MANUAL, "combat auto with a running fight -> manual");
        require(RunMode.combatButton(RunMode.PILOT, true) == RunMode.COMBAT_AUTO, "pilot -> combat auto keeps fights automatic");

        // AI autopilot button and Stop.
        for (RunMode mode : EnumSet.allOf(RunMode.class)) {
            require(RunMode.pilotButton(mode) == (mode == RunMode.PILOT ? RunMode.MANUAL : RunMode.PILOT), "pilot button from " + mode);
            require(RunMode.stop(mode) == RunMode.MANUAL, "stop from " + mode);
        }

        // Closure under the three buttons from manual reaches exactly the three modes, and Stop always comes back.
        EnumSet<RunMode> reach = EnumSet.of(RunMode.MANUAL);
        for (boolean grew = true; grew; ) {
            EnumSet<RunMode> next = EnumSet.copyOf(reach);
            for (RunMode m : reach) {
                next.add(RunMode.combatButton(m, false));
                next.add(RunMode.combatButton(m, true));
                next.add(RunMode.pilotButton(m));
                next.add(RunMode.stop(m));
            }
            grew = next.size() > reach.size();
            reach = next;
        }
        require(reach.equals(EnumSet.allOf(RunMode.class)), "buttons reach the three modes: " + reach);

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

        // New and renamed panel text is translated.
        I18n.setLanguage("en");
        for (String key : new String[]{"Boss 快速收尾：", "未安装", "战斗全自动", "执行整场", "暂停自动", "计算", "执行", "暂停", "待命模式：", "待命 · 点【计算】开始搜索", "待命：点【重新计算】才开始搜索", "人工逐步", "确认这一步（回车）",
                "战斗全自动已开 · 等待下场；其他选择由你决定", "按 F1-F12 绑定；F6/F7/F8/F9 保留；Esc 取消",
                "Boss 快速收尾：找到无药胜线就停止搜索，省时间但可能多掉血；关闭时与普通战斗相同",
                "已停止 · 人工逐步：每一步回车或点击确认", "人工逐步：求解器和 AI 给出建议，每一步回车或点击确认",
                "新一局：选【人工逐步】每步确认，【战斗全自动】只自动打战斗，或【AI全自动】完全托管"})
            require(!I18n.t(key).equals(key), "English text for " + key);
        System.out.println("PASS: three automation modes, Manual waits in combat and outside, Boss fast-finish switch, English text");
    }
}
