package sts1solver;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.ShopRoom;

/**
 * The grid screen outlives save and quit: cards picked on a two-card confirm (Empty Cage,
 * Augmenter, Designer) and abandoned by a reload stay selected, and ShopRoom.updatePurge then
 * charges a removal for them on the next shop's first frame. Only cards in the current deck
 * can be a removal the player chose here.
 */
@SpirePatch(clz = ShopRoom.class, method = "updatePurge")
public class StalePurgeSelectionPatch {
    @SpirePrefixPatch
    public static void before(ShopRoom room) {
        if (AbstractDungeon.player == null) return;
        AbstractDungeon.gridSelectScreen.selectedCards.removeIf(
            c -> !AbstractDungeon.player.masterDeck.group.contains(c));
    }
}
