package sts1solver;

import com.badlogic.gdx.Gdx;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.monsters.AbstractMonster;

/** Shorten only the exit animations; the game's own methods still finish them. */
public class MonsterExitAnimationPatch {
    private static final float EXTRA_SPEED = 5.0f; // Six times normal including the game's own tick.

    private static float extraTime() {
        // SuperFastMode restores normal delta inside these two methods. Raw delta
        // keeps our added time independent of its global speed setting.
        return Gdx.graphics.getRawDeltaTime() * EXTRA_SPEED;
    }

    @SpirePatch(clz=AbstractMonster.class, method="updateDeathAnimation")
    public static class Death {
        @SpirePrefixPatch
        public static void before(AbstractMonster monster) {
            if (monster.isDying && !monster.isDead && monster.deathTimer > 0f)
                monster.deathTimer -= extraTime();
        }
    }

    @SpirePatch(clz=AbstractMonster.class, method="updateEscapeAnimation")
    public static class Escape {
        @SpirePrefixPatch
        public static void before(AbstractMonster monster) {
            if (monster.isEscaping && !monster.escaped && monster.escapeTimer > 0f) {
                float extra = extraTime();
                monster.escapeTimer -= extra;
                monster.drawX += 400f * Settings.scale * extra;
            }
        }
    }
}
