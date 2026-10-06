package sts1solver;

import com.evacipated.cardcrawl.modthespire.lib.*;
import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.actions.unique.DiscoveryAction;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/** A fast frame must not finish the action that just opened a card picker. */
@SpirePatch(clz=AbstractGameAction.class, method="tickDuration")
public class SelectionTimingPatch {
    @SpireInstrumentPatch public static ExprEditor edit() {
        return new ExprEditor() {
            @Override public void edit(MethodCall call) throws CannotCompileException {
                if(call.getMethodName().equals("getDeltaTime"))
                    call.replace("{ $_ = sts1solver.SelectionTimingPatch.delta(this.duration, $proceed($$)); }");
            }
        };
    }
    public static float delta(float duration,float delta) {
        if(AbstractDungeon.isScreenUp && (AbstractDungeon.screen==AbstractDungeon.CurrentScreen.HAND_SELECT
                || AbstractDungeon.screen==AbstractDungeon.CurrentScreen.GRID
                || AbstractDungeon.screen==AbstractDungeon.CurrentScreen.CARD_REWARD)) {
            return duration-remaining(duration,delta);
        }
        return delta;
    }
    static float remaining(float duration,float delta) {
        return 0.000001f;
    }
    @SpirePatch(clz=DiscoveryAction.class, method="update")
    public static class DiscoveryRetrieved {
        @SpirePostfixPatch public static void after(DiscoveryAction __instance, boolean ___retrieveCard) {
            // Preserve the original opening/retrieval calls, including their RNG;
            // subsequent animation frames must not generate discarded choices.
            if(___retrieveCard)__instance.isDone=true;
        }
    }
}
