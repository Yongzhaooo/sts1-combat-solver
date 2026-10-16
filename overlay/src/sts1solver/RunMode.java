package sts1solver;

/**
 * The panel's three automation modes and the transitions its buttons and keys make between them.
 *
 * MANUAL (step by step): the solver and the network suggest, and every step waits for the player, in combat
 * (Enter or a click runs one step; F10 or Execute turn run more) and outside it (Enter or Confirm runs the
 * suggested action). COMBAT_AUTO: every fight runs automatically, outside decisions stay with the player.
 * PILOT: full autopilot (climb and fight).
 */
enum RunMode {
    MANUAL, COMBAT_AUTO, PILOT;

    /**
     * The mode implied by the state flags: SolverMod.outsideMode (0 no outside suggestions, 1 suggest and confirm
     * each step, 2 pilot) and runAuto. Manual is outsideMode 1; outsideMode 0 without runAuto is Manual with the
     * suggestions card hidden.
     */
    static RunMode of(int outsideMode, boolean runAuto) {
        return outsideMode == 2 ? PILOT : outsideMode == 0 && runAuto ? COMBAT_AUTO : MANUAL;
    }

    /** The "Combat auto" button. {@code fightAuto}: this fight alone is running automatically (F10). */
    static RunMode combatButton(RunMode current, boolean fightAuto) {
        if (current == MANUAL && fightAuto) return MANUAL;  // switches the single fight's automation off
        return current == COMBAT_AUTO ? MANUAL : COMBAT_AUTO;  // from the pilot it keeps the fights automatic
    }

    /** The "AI autopilot" button. */
    static RunMode pilotButton(RunMode current) {
        return current == PILOT ? MANUAL : PILOT;
    }

    /** Fights start and advance without the player (Combat auto and the pilot). In Manual every combat step waits. */
    static boolean combatAutomatic(RunMode mode) { return mode != MANUAL; }

    /** Non-combat decisions are made without the player: only the pilot. */
    static boolean outsideAutomatic(RunMode mode) { return mode == PILOT; }

    /** F9 and the Stop button. */
    static RunMode stop(RunMode current) {
        return MANUAL;
    }
}
