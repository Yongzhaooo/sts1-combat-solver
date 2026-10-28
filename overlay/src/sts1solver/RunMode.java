package sts1solver;

/**
 * The panel's three automation modes and the transitions its switch and keys make between them.
 *
 * STANDBY: every fight waits for the player. The panel opens when a fight starts, the fight is searched after Solve
 * (or F10), a plan runs only after the player approves it, and outside decisions are the player's. COMBAT_AUTO: every
 * fight runs automatically, outside decisions stay with the player (or the agent). PILOT: full autopilot (climb and
 * fight).
 */
enum RunMode {
    STANDBY, COMBAT_AUTO, PILOT;

    /**
     * The mode implied by the state flags: OutsidePilot.mode (0 no outside suggestions, 1 the agent confirms each
     * outside step, 2 pilot) and runAuto. Agent play (outsideMode 1 with automatic fights) counts as Combat auto.
     */
    static RunMode of(int outsideMode, boolean runAuto) {
        return outsideMode == 2 ? PILOT : runAuto ? COMBAT_AUTO : STANDBY;
    }

    /** Fights start and advance without the player (Combat auto and the pilot). In Standby every fight waits. */
    static boolean combatAutomatic(RunMode mode) { return mode != STANDBY; }

    /** Non-combat decisions are made without the player: only the pilot. */
    static boolean outsideAutomatic(RunMode mode) { return mode == PILOT; }

    /**
     * Agent play (AGENT_PLAY.md): the choice index an agent's command "<decision> <index>" confirms, or -1 when the
     * command names another decision or no listed choice. Agent mode confirms outside steps with automatic fights.
     */
    static int agentChoice(String command, long decision, int choices) {
        String[] parts = command.trim().split("\\s+");
        if (decision <= 0 || parts.length != 2 || !parts[0].equals(Long.toString(decision)) || !parts[1].matches("\\d{1,4}"))
            return -1;
        int index = Integer.parseInt(parts[1]);
        return index < choices ? index : -1;
    }

    /** F9: back to Standby from any mode. */
    static RunMode stop(RunMode current) {
        return STANDBY;
    }
}
