package sts1solver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * "Danger ahead" for event rules that spend HP (docs/event-rules.md): the rooms the planned route (RoutePlanner, public map
 * only) meets before the next rest site, priced in expected HP loss and compared with the HP left after the event.
 * Row 14 is always a rest site, so the boss only counts when rest sites cannot heal (Coffee Dripper).
 */
final class DangerAhead {
    // Uncalibrated hand-set prices: expected HP lost per room, indexed by act 1..3; not fitted to run data.
    static final int[] MONSTER_LOSS = {0, 6, 10, 12}, ELITE_LOSS = {0, 18, 25, 30}, BOSS_LOSS = {0, 30, 40, 45};
    // Uncalibrated: an unknown room is sometimes a fight.
    static final int UNKNOWN_LOSS = 3;
    // Uncalibrated: danger is high when the HP left after the event is under this multiple of the expected loss.
    static final double MARGIN = 1.5;

    final int monsters, elites, unknowns, expectedLoss;
    final boolean boss;

    DangerAhead(int act, int monsters, int elites, int unknowns, boolean boss) {
        int a = Math.max(1, Math.min(3, act));
        this.monsters = monsters; this.elites = elites; this.unknowns = unknowns; this.boss = boss;
        expectedLoss = monsters * MONSTER_LOSS[a] + elites * ELITE_LOSS[a] + unknowns * UNKNOWN_LOSS + (boss ? BOSS_LOSS[a] : 0);
    }

    /** The planned route from the current room up to the next rest site that heals; null when no route is known. */
    static DangerAhead of(JsonObject game, JsonObject visible) {
        try {
            RoutePlanner.Plan plan = new RoutePlanner(game, visible).best(-1);
            if (plan == null || plan.path.isEmpty()) return null;
            Map<Integer, String> symbols = new HashMap<>();
            for (JsonElement item : game.getAsJsonArray("map")) {
                JsonObject node = item.getAsJsonObject();
                symbols.put(node.get("y").getAsInt() * 7 + node.get("x").getAsInt(), node.get("symbol").getAsString());
            }
            boolean heals = true;
            for (JsonElement item : game.getAsJsonArray("relics"))
                heals &= !item.getAsJsonObject().get("id").getAsString().equals("Coffee Dripper");
            int monsters = 0, elites = 0, unknowns = 0;
            for (OutsidePacket.Step step : plan.path) {
                String room = symbols.getOrDefault(step.y * 7 + step.x, "");
                if (room.equals("R") && heals) return new DangerAhead(game.get("act").getAsInt(), monsters, elites, unknowns, false);
                if (room.equals("M")) monsters++;
                else if (room.equals("E")) elites++;
                else if (room.equals("?")) unknowns++;
            }
            return new DangerAhead(game.get("act").getAsInt(), monsters, elites, unknowns, true);
        } catch (RuntimeException failure) {
            return null; // malformed or partial public state: the caller keeps its non-danger rule
        }
    }

    boolean high(int hpAfter) { return hpAfter <= 0 || hpAfter < MARGIN * expectedLoss; }

    /** Short Chinese summary for the decision note. */
    String describe(int hpAfter) {
        return (high(hpAfter) ? "前路危险高" : "前路危险低") + "（" + (boss ? "到 Boss" : "到火堆") + "前 " + monsters + " 普通 "
            + elites + " 精英 " + unknowns + " 问号，预计失血 " + expectedLoss + "，事件后剩 " + hpAfter + "）";
    }
}
