package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.events.shrines.GremlinMatchGame;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Match and Keep! (GremlinMatchGame), live client only. The board is fixed in the event's constructor and an SL replay shows
 * the same twelve cards, so the rule reads the private {@code cards} group. With five attempts (every ascension; A15 swaps the
 * colorless pair for a second curse pair) it matches only the pairs the outside model wants, best-first with sequential deck
 * additions (CardWant), never a curse, and spends any other attempt on two cards of different pairs.
 * Choice i is the i-th face-down card ordered by board position (communicationmod GremlinMatchGamePatch.getOrderedCards);
 * its label is "card&lt;position&gt;", or the card id once the card was revealed. Every label is checked before a choice is made.
 */
final class MatchGameRule {
    static final String EVENT = "Match and Keep!";

    private MatchGameRule() { }

    /** One card in the event's group. faceDown is the game's isFlipped; position = column + 4 * row (0 top left, 11 bottom right). */
    static final class Slot {
        final String id;
        final boolean curse, faceDown;
        final int position;
        Slot(String id, boolean curse, int position, boolean faceDown) {
            this.id = id; this.curse = curse; this.position = position; this.faceDown = faceDown;
        }
    }

    /** Outside-model preference over card ids: index into offer, or -1 = take none (CardWant.pick on real cards). */
    interface Preference { int pick(List<String> offer, List<String> added); }

    static final class Pick {
        final int index;
        final String note;
        Pick(int index, String note) { this.index = index; this.note = note; }
    }

    /** The choice list: face-down cards in board order. */
    static List<Slot> pickable(List<Slot> board) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : board) if (s.faceDown) out.add(s);
        Collections.sort(out, (a, b) -> Integer.compare(a.position, b.position));
        return out;
    }

    /** Non-curse ids with both cards still on the board, in board order. */
    static List<String> candidates(List<Slot> board) {
        List<String> out = new ArrayList<>();
        List<Slot> sorted = new ArrayList<>(board); // includes a face-up first card
        Collections.sort(sorted, (a, b) -> Integer.compare(a.position, b.position));
        for (Slot s : sorted)
            if (!s.curse && !out.contains(s.id) && count(board, s.id) >= 2) out.add(s.id);
        return out;
    }

    /** Wanted pairs best-first: each pick joins "added" before the next offer is scored. */
    static List<String> plan(List<String> candidates, Preference preference) {
        List<String> offer = new ArrayList<>(candidates), added = new ArrayList<>();
        while (!offer.isEmpty()) {
            int k = preference.pick(Collections.unmodifiableList(offer), Collections.unmodifiableList(added));
            if (k < 0 || k >= offer.size()) break;
            added.add(offer.remove(k));
        }
        return added;
    }

    /**
     * Next card to click. chosen is the face-up first card of the attempt in progress, or null; attempts counts the attempt in
     * progress. Returns null (network decides) when the labels do not match the board or no safe click exists.
     */
    static Pick choose(List<Slot> board, Slot chosen, int attempts, List<String> plan, List<String> shown) {
        List<Slot> pickable = pickable(board);
        if (attempts <= 0 || pickable.size() != shown.size()) return null;
        for (int i = 0; i < shown.size(); i++) {
            Slot s = pickable.get(i);
            if (!shown.get(i).equals("card" + s.position) && !shown.get(i).equals(s.id)) return null;
        }
        List<String> wanted = new ArrayList<>();
        for (String id : plan) if (count(board, id) >= 2 && !curse(board, id)) wanted.add(id);
        // Keep a mismatch possible for every attempt not spent on a match: never leave a single id (e.g. four equal curses).
        int m = Math.min(wanted.size(), attempts);
        while (m > 0 && m < attempts && distinctWithout(board, wanted.subList(0, m)) < 2) m--;
        List<String> targets = wanted.subList(0, m);
        if (chosen != null) {
            if (targets.contains(chosen.id)) {
                int partner = first(pickable, chosen.id, true, null);
                return partner < 0 ? null : new Pick(partner, "对对碰：配对想要的 " + chosen.id);
            }
            int other = first(pickable, chosen.id, false, targets);
            if (other < 0) other = first(pickable, chosen.id, false, null);
            return other < 0 ? null : new Pick(other, "对对碰：故意翻错，不拿 " + chosen.id);
        }
        if (!targets.isEmpty()) {
            int card = first(pickable, targets.get(0), true, null);
            return card < 0 ? null : new Pick(card, "对对碰：翻开想要的 " + targets.get(0) + "（预读牌面）");
        }
        int best = -1;
        for (int i = 0; i < pickable.size(); i++) {
            Slot s = pickable.get(i);
            if (first(pickable, s.id, false, null) < 0) continue; // the second card must be able to differ
            if (!wanted.contains(s.id)) { best = i; break; }
            if (best < 0) best = i;
        }
        return best < 0 ? null : new Pick(best, wanted.isEmpty() ? "对对碰：没有想要的牌，故意翻错" : "对对碰：留一次翻错，避免被迫配对诅咒");
    }

    /** First pickable index whose id equals (or differs from) id; when differing, ids in skip are passed over. */
    private static int first(List<Slot> pickable, String id, boolean same, List<String> skip) {
        for (int i = 0; i < pickable.size(); i++) {
            String other = pickable.get(i).id;
            if (other.equals(id) == same && (skip == null || !skip.contains(other))) return i;
        }
        return -1;
    }

    private static int count(List<Slot> board, String id) {
        int n = 0;
        for (Slot s : board) if (s.id.equals(id)) n++;
        return n;
    }

    private static boolean curse(List<Slot> board, String id) {
        for (Slot s : board) if (s.id.equals(id) && s.curse) return true;
        return false;
    }

    private static int distinctWithout(List<Slot> board, List<String> removed) {
        Set<String> ids = new HashSet<>();
        for (Slot s : board) if (!removed.contains(s.id)) ids.add(s.id);
        return ids.size();
    }

    // ---- runtime self-check: the board read at first sight vs what the game later shows -------------------------------

    /** Per event instance: the wanted plan and the board snapshot (position -> id) taken on the first PLAY decision. */
    static final class State {
        final Object event;
        final List<String> plan;
        final Map<Integer, String> snapshot = new HashMap<>();
        private final Set<Integer> checked = new HashSet<>();
        private final int matchBase;
        private int matchesChecked;

        State(Object event, List<Slot> board, int matchedSoFar, List<String> plan) {
            this.event = event; this.plan = plan; this.matchBase = this.matchesChecked = matchedSoFar;
            for (Slot s : board) snapshot.put(s.position, s.id);
        }

        /** "preread ok" / "preread MISMATCH" lines for cards revealed (labels, face-up card) and pairs matched since last call. */
        List<String> check(List<Slot> board, Slot chosen, List<String> shown, List<String> matched) {
            List<String> out = new ArrayList<>();
            List<Slot> pickable = pickable(board);
            if (pickable.size() == shown.size())
                for (int i = 0; i < shown.size(); i++) {
                    int p = pickable.get(i).position;
                    if (!shown.get(i).equals("card" + p)) compare(out, p, shown.get(i));
                }
            if (chosen != null) compare(out, chosen.position, chosen.id);
            if (matched.size() > matchesChecked) {
                List<String> vanished = new ArrayList<>(), expected = new ArrayList<>();
                Set<Integer> onBoard = new HashSet<>();
                for (Slot s : board) onBoard.add(s.position);
                for (Map.Entry<Integer, String> e : snapshot.entrySet()) if (!onBoard.contains(e.getKey())) vanished.add(e.getValue());
                for (String id : matched.subList(matchBase, matched.size())) { expected.add(id); expected.add(id); }
                Collections.sort(vanished); Collections.sort(expected);
                String last = matched.get(matched.size() - 1);
                out.add(vanished.equals(expected) ? "preread ok " + EVENT + " matched " + last
                    : "preread MISMATCH " + EVENT + " matched " + last + " but the preread board lost " + vanished);
                matchesChecked = matched.size();
            }
            return out;
        }

        private void compare(List<String> out, int position, String actual) {
            if (!checked.add(position)) return;
            String read = snapshot.get(position);
            out.add(actual.equals(read) ? "preread ok " + EVENT + " card" + position + "=" + actual
                : "preread MISMATCH " + EVENT + " card" + position + " preread=" + read + " actual=" + actual);
        }
    }

    private static State state;

    // ---- live entry (game thread) ---------------------------------------------------------------------------------------

    /** Null leaves the screen to the network: training (live=false), another page, or any failure. */
    static EventRules.Decision decide(EventRules.Context c, JsonArray shownJson) {
        if (!EventRules.live || !EventRules.SL_PREREAD.contains(EVENT) || !EVENT.equals(c.event)) return null;
        AbstractEvent event;
        try { event = AbstractDungeon.getCurrRoom().event; } catch (Throwable failure) { return null; }
        if (!(event instanceof GremlinMatchGame)) return null;
        try {
            if (!"PLAY".equals(String.valueOf(field(event, "screen")))) return null;
            // Decisions come only after CommunicationMod's flip patches settle; a turning board is left to the current behaviour.
            if ((Float) field(event, "waitTimer") != 0f) return null;
            List<AbstractCard> group = ((CardGroup) field(event, "cards")).group;
            AbstractCard chosenCard = (Boolean) field(event, "cardFlipped") ? (AbstractCard) field(event, "chosenCard") : null;
            int attempts = (Integer) field(event, "attemptCount");
            @SuppressWarnings("unchecked") List<String> matched = (List<String>) field(event, "matchedCards");
            List<Slot> board = new ArrayList<>();
            Map<String, AbstractCard> cards = new HashMap<>();
            Slot chosen = null;
            for (AbstractCard card : group) {
                Slot s = new Slot(card.cardID, card.type == AbstractCard.CardType.CURSE, position(card), card.isFlipped);
                board.add(s);
                if (!cards.containsKey(card.cardID)) cards.put(card.cardID, card);
                if (card == chosenCard) chosen = s;
                else if (!card.isFlipped) return null;
            }
            if (chosenCard != null && chosen == null) return null;
            if (state == null || state.event != event) {
                Preference model = (offer, added) -> CardWant.pick(real(offer, cards), real(added, cards));
                state = new State(event, board, matched.size(), plan(candidates(board), model));
            }
            List<String> shown = new ArrayList<>();
            for (JsonElement label : shownJson) shown.add(label.getAsString());
            StringBuilder note = new StringBuilder(EVENT + ": plan=" + state.plan + " attempts=" + attempts);
            for (String line : state.check(board, chosen, shown, matched)) {
                System.err.println("[STS1Solver] " + line);
                note.append(" | ").append(line);
            }
            c.preread = note.toString();
            Pick pick = choose(board, chosen, attempts, state.plan, shown);
            return pick == null ? null : new EventRules.Decision(pick.index, pick.note);
        } catch (Throwable failure) {
            System.err.println("[STS1Solver] " + EVENT + " rule: " + failure);
            return null;
        }
    }

    private static List<AbstractCard> real(List<String> ids, Map<String, AbstractCard> cards) {
        List<AbstractCard> out = new ArrayList<>();
        for (String id : ids) out.add(cards.get(id));
        return out;
    }

    /** placeCards: column (i % 4) at x = 640 + 210 * column, row (i % 3) at y = 750 - 230 * row (scaled); the target stays put. */
    private static int position(AbstractCard c) {
        int col = Math.round((c.target_x - 640f * Settings.xScale) / (210f * Settings.xScale));
        int row = Math.round((750f * Settings.yScale - c.target_y) / (230f * Settings.yScale));
        if (col < 0 || col > 3 || row < 0 || row > 2) throw new IllegalStateException("card off the board: " + c.cardID);
        return col + 4 * row;
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
