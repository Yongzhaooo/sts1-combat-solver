package sts1solver;

import java.util.EnumSet;

/** The three automation modes (plus the pilot's confirm-each-step variant) and the Boss fast-finish switch. */
public final class ModeCheck {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // Flags -> mode: outsideMode 2 is the pilot, 1 confirms each outside step, otherwise runAuto decides.
        for (boolean run : new boolean[]{false, true}) {
            require(RunMode.of(2, run) == RunMode.PILOT, "pilot ignores runAuto");
            require(RunMode.of(1, run) == RunMode.STEP_CONFIRM, "confirm mode ignores runAuto");
        }
        require(RunMode.of(0, false) == RunMode.MANUAL, "manual");
        require(RunMode.of(0, true) == RunMode.COMBAT_AUTO, "combat auto");

        // Combat auto button: every mode lands on one of the two combat results.
        require(RunMode.combatButton(RunMode.MANUAL, false) == RunMode.COMBAT_AUTO, "manual -> combat auto");
        require(RunMode.combatButton(RunMode.MANUAL, true) == RunMode.MANUAL, "this fight's own automation is switched off first");
        require(RunMode.combatButton(RunMode.COMBAT_AUTO, false) == RunMode.MANUAL, "combat auto -> manual");
        require(RunMode.combatButton(RunMode.COMBAT_AUTO, true) == RunMode.MANUAL, "combat auto with a running fight -> manual");
        require(RunMode.combatButton(RunMode.PILOT, true) == RunMode.COMBAT_AUTO, "pilot -> combat auto keeps fights automatic");
        require(RunMode.combatButton(RunMode.STEP_CONFIRM, true) == RunMode.COMBAT_AUTO, "confirm mode -> combat auto");

        // AI autopilot button and Stop.
        for (RunMode mode : EnumSet.allOf(RunMode.class)) {
            require(RunMode.pilotButton(mode) == (mode == RunMode.PILOT ? RunMode.MANUAL : RunMode.PILOT), "pilot button from " + mode);
            require(RunMode.stop(mode) == RunMode.MANUAL, "stop from " + mode);
        }

        // Closure under the three buttons from manual: the three modes, never the confirm variant.
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
        require(reach.equals(EnumSet.of(RunMode.MANUAL, RunMode.COMBAT_AUTO, RunMode.PILOT)), "buttons reach exactly three modes: " + reach);

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

        // New settings text is translated.
        I18n.setLanguage("en");
        for (String key : new String[]{"Boss 快速收尾：", "未安装", "战斗全自动", "战斗全自动：开", "战斗全自动（自己构筑）",
                "战斗全自动已开 · 等待下场；其他选择由你决定", "按 F1-F12 绑定；F6/F7/F8/F9 保留；Esc 取消",
                "Boss 快速收尾：找到无药胜线就停止搜索，省时间但可能多掉血；关闭时与普通战斗相同"})
            require(!I18n.t(key).equals(key), "English text for " + key);
        System.out.println("PASS: automation modes, Boss fast-finish switch and its English text");
    }
}
