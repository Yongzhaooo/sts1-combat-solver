package sts1solver;

/**
 * The panel's automation modes and the transitions its buttons and keys make between them.
 *
 * MANUAL: the player decides outside combat and steps or starts the solved route (Execute step, Execute turn,
 * Enter, F10 or a click on the route). COMBAT_AUTO: every fight runs automatically, outside decisions stay
 * with the player. PILOT: full autopilot (climb and fight). STEP_CONFIRM: the pilot asks before each outside action.
 */
enum RunMode {
    MANUAL, COMBAT_AUTO, STEP_CONFIRM, PILOT;

    /** The mode implied by the state flags: SolverMod.outsideMode (0 off, 1 confirm, 2 pilot) and runAuto. */
    static RunMode of(int outsideMode, boolean runAuto) {
        return outsideMode == 2 ? PILOT : outsideMode == 1 ? STEP_CONFIRM : runAuto ? COMBAT_AUTO : MANUAL;
    }

    /** The "Combat auto" button. {@code fightAuto}: this fight alone is running automatically (Enter / F10). */
    static RunMode combatButton(RunMode current, boolean fightAuto) {
        if (current == MANUAL && fightAuto) return MANUAL;  // switches the single fight's automation off
        return current == COMBAT_AUTO ? MANUAL : COMBAT_AUTO;  // from the pilot it keeps the fights automatic
    }

    /** The "AI autopilot" button. */
    static RunMode pilotButton(RunMode current) {
        return current == PILOT ? MANUAL : PILOT;
    }

    /** F9 and the Stop button. */
    static RunMode stop(RunMode current) {
        return MANUAL;
    }
}
