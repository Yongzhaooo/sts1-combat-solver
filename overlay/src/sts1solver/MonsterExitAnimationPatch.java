package sts1solver;

import com.badlogic.gdx.Gdx;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;

/** Shorten only the exit animations; the game's own methods still finish them. */
public class MonsterExitAnimationPatch {
    private static final float EXTRA_SPEED = 5.0f; // Six times normal including the game's own tick.

    private static float extraTime() {
        // SuperFastMode restores normal delta inside these two methods. Raw delta
        // keeps our added time independent of its global speed setting.
        return Gdx.graphics.getRawDeltaTime() * EXTRA_SPEED;
    }

    private static boolean actionsPending() {
        // The last exit ends the battle and endBattle() clears the action queue, so a
        // lethal Reaper's queued HealAction must run first (issue #75).
        GameActionManager actions = AbstractDungeon.actionManager;
        return actions.currentAction != null || !actions.actions.isEmpty();
    }

    @SpirePatch(clz=AbstractMonster.class, method="updateDeathAnimation")
    public static class Death {
        @SpirePrefixPatch
        public static void before(AbstractMonster monster) {
            if (monster.isDying && !monster.isDead && monster.deathTimer > 0f && !actionsPending())
                monster.deathTimer -= extraTime();
        }
    }

    @SpirePatch(clz=AbstractMonster.class, method="updateEscapeAnimation")
    public static class Escape {
        @SpirePrefixPatch
        public static void before(AbstractMonster monster) {
            if (monster.isEscaping && !monster.escaped && monster.escapeTimer > 0f && !actionsPending()) {
                float extra = extraTime();
                monster.escapeTimer -= extra;
                monster.drawX += 400f * Settings.scale * extra;
            }
        }
    }
}
