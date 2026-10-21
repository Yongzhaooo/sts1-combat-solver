package sts1solver;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import communicationmod.CommandExecutor;
import communicationmod.GameStateListener;

/** When the running game is in a fight and settled enough to read its state or accept a command. */
final class CombatReadiness {
    private CombatReadiness() { }

    static boolean combat() {
        return CommandExecutor.isInDungeon() && AbstractDungeon.player != null
            && AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT
            && !AbstractDungeon.getCurrRoom().isBattleOver && !AbstractDungeon.player.isDead;
    }

    static boolean ready() {
        if (!combat() || !GameStateListener.isWaitingForCommand()) return false;
        // Piles already contain cards while Soul is still moving them. Completion
        // calls clearPowers/resetAttributes, including temporary cost reset.
        if (com.megacrit.cardcrawl.cards.SoulGroup.isActive()) return false;
        // AbstractMonster.updateDeathAnimation clears powers when isDead becomes
        // true. Action queues can already be idle before this final state change.
        for (AbstractMonster monster: AbstractDungeon.getMonsters().monsters)
            if (waitForMonsterExit(AbstractDungeon.screen, monster.isDying, monster.isDead,
                    monster.halfDead, monster.isEscaping, monster.escaped)) return false;
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.CARD_REWARD
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.HAND_SELECT
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) return false;
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.NONE) {
            for(com.megacrit.cardcrawl.vfx.AbstractGameEffect effect:AbstractDungeon.effectList)
                if(pendingCardEffect(effect))return false;
            for(com.megacrit.cardcrawl.vfx.AbstractGameEffect effect:AbstractDungeon.effectsQueue)
                if(pendingCardEffect(effect))return false;
            for(com.megacrit.cardcrawl.vfx.AbstractGameEffect effect:AbstractDungeon.topLevelEffects)
                if(pendingCardEffect(effect))return false;
            for (AbstractMonster monster: AbstractDungeon.getMonsters().monsters)
                if (!monster.isDeadOrEscaped() && monster.intent == AbstractMonster.Intent.DEBUG) return false;
            return AbstractDungeon.actionManager.actions.isEmpty()
                && AbstractDungeon.actionManager.phase == GameActionManager.Phase.WAITING_ON_USER
                && AbstractDungeon.actionManager.currentAction == null
                && !AbstractDungeon.actionManager.hasControl
                && AbstractDungeon.actionManager.cardQueue.isEmpty()
                && AbstractDungeon.actionManager.preTurnActions.isEmpty()
                && !AbstractDungeon.player.endTurnQueued;
        }
        return true;
    }

    static boolean actionPausedByScreen(AbstractDungeon.CurrentScreen screen) {
        return screen != AbstractDungeon.CurrentScreen.NONE
            && screen != AbstractDungeon.CurrentScreen.HAND_SELECT
            && screen != AbstractDungeon.CurrentScreen.GRID
            && screen != AbstractDungeon.CurrentScreen.CARD_REWARD;
    }

    static boolean waitForMonsterExit(AbstractDungeon.CurrentScreen screen, boolean dying,
                                      boolean dead, boolean halfDead, boolean escaping, boolean escaped) {
        // AbstractDungeon skips room.update on these screens unless peeking.
        // A killing Headbutt must choose a card before death animations can resume.
        if (screen == AbstractDungeon.CurrentScreen.GRID
                || screen == AbstractDungeon.CurrentScreen.CARD_REWARD) return false;
        return pendingMonsterDeath(dying, dead, halfDead) || (escaping && !escaped);
    }

    static boolean pendingMonsterDeath(boolean dying, boolean dead, boolean halfDead) {
        return dying && !dead && !halfDead;
    }

    // The startup action identifies Gambling Chip more precisely than the relic alone:
    // other hand selections can occur while the player owns the same relic.
    static boolean gamblingSelection(JsonObject state) {
        if (state == null || !state.has("game_state")) return false;
        JsonObject game = state.getAsJsonObject("game_state");
        if (!game.has("screen_type") || !"HAND_SELECT".equals(game.get("screen_type").getAsString())
                || !game.has("screen_state") || !game.has("solver_selection")) return false;
        JsonObject screen = game.getAsJsonObject("screen_state");
        JsonObject action = game.getAsJsonObject("solver_selection").getAsJsonObject("current_action");
        return screen != null && screen.has("max_cards") && screen.get("max_cards").getAsInt() == 99
            && action != null && action.has("class")
            && "GamblingChipAction".equals(action.get("class").getAsString());
    }

    static boolean toolboxSelection(JsonObject state) {
        if (state == null || !state.has("game_state")) return false;
        JsonObject game = state.getAsJsonObject("game_state");
        if (!game.has("screen_type") || !"CARD_REWARD".equals(game.get("screen_type").getAsString())
                || !game.has("solver_selection")) return false;
        JsonObject action = game.getAsJsonObject("solver_selection").getAsJsonObject("current_action");
        return action != null && action.has("class") && "ChooseOneColorless".equals(action.get("class").getAsString());
    }

    /** Gambling Chip and Toolbox open the fight with a selection the solver plans and picks itself. */
    static boolean openingSelection(JsonObject state) {
        return gamblingSelection(state) || toolboxSelection(state);
    }

    private static boolean pendingCardEffect(com.megacrit.cardcrawl.vfx.AbstractGameEffect effect) {
        String name=effect.getClass().getSimpleName();
        return !effect.isDone && (name.equals("ShowCardAndAddToHandEffect")
            || name.equals("ShowCardAndAddToDiscardEffect") || name.equals("ShowCardAndAddToDrawPileEffect")
            || name.equals("ExhaustCardEffect")); // Completion resets temporary card attributes.
    }
}
