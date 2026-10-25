// STUB: replaced by executor A's CardWant at integration
package sts1solver;

import com.megacrit.cardcrawl.cards.AbstractCard;
import java.util.List;

/** "The model wants this card" (event-preread spec, owner executor A). This stub never wants anything. */
final class CardWant {
    private CardWant() { }

    /** Index into offer, or -1 = skip. */
    static int pick(List<AbstractCard> offer, List<AbstractCard> addedSoFar) { return -1; }

    static boolean wants(AbstractCard card, List<AbstractCard> addedSoFar) { return false; }
}
