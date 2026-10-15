package sts1solver;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatches;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.random.Random;
import java.io.InputStream;

public class RngModsCheck {
    @SpirePatch(clz=Random.class, method="random", paramtypez={int.class}) static class PatchesClz { }
    @SpirePatches({@SpirePatch(cls="com.megacrit.cardcrawl.random.Random", method="randomBoolean")}) static class PatchesCls { }
    static class Draws { static int f() { return AbstractDungeon.cardRandomRng.random(3); } }
    static class ReadsCounter { static int f() { return AbstractDungeon.cardRandomRng.counter; } }
    static class Copies { static Random f() { return AbstractDungeon.cardRandomRng.copy(); } }

    static boolean touches(Class<?> type) throws Exception {
        try (InputStream in = RngModsCheck.class.getResourceAsStream(type.getName().substring(type.getName().lastIndexOf('.') + 1) + ".class")) {
            return RngMods.classTouchesRng(in);
        }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        require(touches(PatchesClz.class), "patch with clz=Random");
        require(touches(PatchesCls.class), "patch with cls string inside @SpirePatches");
        require(touches(Draws.class), "draws from a game stream");
        require(!touches(ReadsCounter.class), "reading counter is harmless");
        require(!touches(Copies.class), "copy() is harmless");
        System.out.println("PASS: RNG-affecting mod detection");
    }
}
