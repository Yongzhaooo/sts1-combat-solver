"""Which comparator differences make a grade fail.

Both grades used to drop every path under `/legacy/`. That is right for the rows the legacy helper
rebuilds from the recording it is grading -- they restate the recording instead of checking it --
but it also hid a monster's power stack, which is the row a new card's own effect lands in.
Ticket #8: a Neutralize that applied the wrong number of WEAK passed every grade.

So the legacy rows are listed here one by one. A row is graded when it is named in REPORTED and
filtered otherwise, which means a row the helper learns to emit later stays filtered until somebody
opens it on purpose and writes down why.
"""
from __future__ import annotations

# The legacy rows the grades look at. Every other `/legacy/` path is filtered.
REPORTED = {
    # The powers and intent each monster carries. A monster's WEAK, VULNERABLE, STRENGTH and the
    # rest live here, so this is the row that catches a card applying the wrong stack to the wrong
    # monster -- or applying none at all. Left filtered, a wrong power stack graded green.
    "/legacy/monster_powers_and_intents",
}

# The legacy rows that stay filtered, and why. Named rather than assumed, so the rule reads as a
# decision instead of an oversight.
FILTERED = {
    # Rebuilt from the recorded intent, which is the CURRENT intent: a monster that already acted
    # this turn still carries the intent it acted on, so the row restates the recording.
    "/legacy/monster_attack_damage_and_count",
    # The player's powers are compared against the live export's own power rows, which need a
    # FightState field the simulator keeps separately (draw reduction, Bombs); the legacy helper's
    # view of them is alias-driven and not an independent check.
    "/legacy/player_powers",
}


def kept(difference) -> bool:
    """True when this difference must fail a grade.

    Only the legacy rows are filtered; every other row is the comparator's own finding and is
    always graded. A legacy row with an index suffix names the same row as its prefix.
    """
    path = difference["path"]
    if not path.startswith("/legacy/"):
        return True
    return any(path == row or path.startswith(row + "/") for row in REPORTED)
