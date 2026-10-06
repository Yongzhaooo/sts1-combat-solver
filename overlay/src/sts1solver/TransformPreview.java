package sts1solver;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.random.Random;
import java.util.*;

/** A local trial selection: never touches the game's selection or live RNG. */
final class TransformPreview {
    final List<AbstractCard> cards;
    final List<AbstractCard> selected = new ArrayList<>();
    private final Random stream;
    private List<String> cachedLines;
    final int count;
    final boolean upgrade;
    private final boolean automatic;

    TransformPreview(List<AbstractCard> cards, Random stream) {
        this(cards, stream, 3, true);
    }

    TransformPreview(List<AbstractCard> cards, Random stream, int count, boolean upgrade) {
        this.cards = new ArrayList<>(cards);
        this.stream = stream.copy();
        this.count = count;
        this.upgrade = upgrade;
        automatic = upgrade && cards.size() <= count;
        // Astrolabe uses addToTop and immediately transforms the whole group at <= 3 cards.
        if (automatic) {
            Collections.reverse(this.cards);
            selected.addAll(this.cards);
        }
    }

    List<AbstractCard> choices() {
        List<AbstractCard> rows = new ArrayList<>(selected);
        if (selected.size() < Math.min(count, cards.size()))
            for (AbstractCard card : cards) if (!selected.contains(card)) rows.add(card);
        return rows;
    }

    void pick(int index) {
        if (automatic) return;
        List<AbstractCard> rows = choices();
        if (index < 0 || index >= rows.size()) return;
        if (index < selected.size()) selected.subList(index, selected.size()).clear();
        else selected.add(rows.get(index));
        cachedLines = null;
    }

    List<String> lines() {
        if (cachedLines != null) return cachedLines;
        List<String> rows = new ArrayList<>();
        Random rng = stream.copy();
        for (int i = 0; i < selected.size(); i++) {
            AbstractCard card = selected.get(i);
            rows.add((i + 1) + " " + card.name + " → " + Foresight.result(card, rng, upgrade)
                + (automatic ? I18n.t(" [自动变化]") : I18n.t(" [撤回]")));
        }
        if (selected.size() < Math.min(count, cards.size()))
            for (AbstractCard card : cards) if (!selected.contains(card))
                rows.add(I18n.t("选 ") + card.name + " → " + Foresight.result(card, rng.copy(), upgrade));
        cachedLines = rows;
        return rows;
    }
}
