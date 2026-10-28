package sts1solver;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Spire Ledger turn-replay contract v1. The ledger reflects on these members and relies on these semantics;
 *  changing either needs a contract bump agreed with the ledger first, never a silent rename. */
public class ReplayContractCheck {
    // Instances without constructors (no BaseMod, config or game frame); sun.misc is hidden from javac --release 8.
    static final Object UNSAFE;
    static final Method ALLOCATE;
    static {
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            Field f = type.getDeclaredField("theUnsafe"); f.setAccessible(true); UNSAFE = f.get(null);
            ALLOCATE = type.getMethod("allocateInstance", Class.class);
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    static Object allocate(Class<?> type) throws Exception { return ALLOCATE.invoke(UNSAFE, type); }

    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static Field field(String name) throws Exception { Field f = SolverMod.class.getDeclaredField(name); f.setAccessible(true); return f; }
    static Object get(SolverMod mod, String name) throws Exception { return field(name).get(mod); }
    static void set(SolverMod mod, String name, Object value) throws Exception { field(name).set(mod, value); }
    static void call(SolverMod mod, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = SolverMod.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(mod, args);
    }

    /** A panel mid-fight with a plan, automation on and a search in flight; no backend process, no game frame. */
    static SolverMod running() throws Exception {
        SolverMod mod = (SolverMod) allocate(SolverMod.class);
        set(mod, "backend", allocate(BackendClient.class));
        set(mod, "pilot", new OutsidePilot());
        set(mod, "result", new com.google.gson.JsonObject());
        set(mod, "auto", true);
        set(mod, "runAuto", true);
        set(mod, "busy", true);
        set(mod, "serial", 7L);
        set(mod, "activeId", 7L);
        return mod;
    }

    public static void main(String[] args) throws Exception {
        // 1. Members the ledger reflects on.
        require(Modifier.isStatic(field("instance").getModifiers()), "SolverMod.instance is static");
        field("runAutoPlayer");
        SolverMod.class.getDeclaredMethod("cancel", boolean.class);
        SolverMod.class.getDeclaredMethod("stopAll");
        SolverMod.class.getDeclaredMethod("receivePostUpdate");
        SolverMod.class.getDeclaredMethod("receivePostRender", SpriteBatch.class);

        // 2. Before a rewind the ledger calls cancel(false) then stopAll().
        SolverMod mod = running();
        long before = (Long) get(mod, "activeId");
        call(mod, "cancel", new Class<?>[]{boolean.class}, false);
        call(mod, "stopAll", new Class<?>[0]);
        require(get(mod, "result") == null, "old plan cleared");
        require(!(Boolean) get(mod, "auto") && !(Boolean) get(mod, "runAuto"), "automation stopped");
        require((Boolean) get(mod, "paused"), "paused");
        require((Long) get(mod, "activeId") > before, "late replies to the old request are void");

        // 3. After the rewind the ledger sets runAutoPlayer to the restored player: the next update must not re-arm.
        // followPlayer() applies SolverMod.rearms(); game player objects can't be built outside the game, so any object
        // stands in for the player here.
        Object restored = new Object();
        require(!SolverMod.rearms(true, restored, restored, true), "a restored player the ledger registered does not re-arm automation");
        require(SolverMod.rearms(true, restored, null, true) && SolverMod.rearms(true, restored, new Object(), true),
            "a player the mod has not seen (a new run) re-arms");
        require(!SolverMod.rearms(false, restored, null, true) && !SolverMod.rearms(true, restored, null, false),
            "outside a dungeon or for an unsupported class nothing re-arms");
        System.out.println("PASS: Spire Ledger replay contract v1 (members, stop semantics, restored player)");
    }
}
