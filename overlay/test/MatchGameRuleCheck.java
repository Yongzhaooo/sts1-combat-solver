package sts1solver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Match and Keep! on synthetic boards: choice mapping, wanted pairs, curses, deliberate misses, live off. usage: FIXTURE */
public final class MatchGameRuleCheck {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    /** ids[p] sits at board position p; "Injury" and "Decay" are curses. */
    static List<MatchGameRule.Slot> board(String... ids) {
        List<MatchGameRule.Slot> out = new ArrayList<>();
        for (int p = 0; p < ids.length; p++)
            if (ids[p] != null) out.add(new MatchGameRule.Slot(ids[p], ids[p].equals("Injury") || ids[p].equals("Decay"), p, true));
        return out;
    }

    /** The same card turned face up (the first card of an attempt). */
    static MatchGameRule.Slot flip(List<MatchGameRule.Slot> board, int position) {
        for (int i = 0; i < board.size(); i++) {
            MatchGameRule.Slot s = board.get(i);
            if (s.position == position) {
                MatchGameRule.Slot up = new MatchGameRule.Slot(s.id, s.curse, s.position, false);
                board.set(i, up);
                return up;
            }
        }
        throw new AssertionError("no card at " + position);
    }

    /** CommunicationMod's labels: card<position> for unseen cards, the id for cards in seen. */
    static List<String> labels(List<MatchGameRule.Slot> board, Integer... seen) {
        List<String> out = new ArrayList<>();
        for (MatchGameRule.Slot s : MatchGameRule.pickable(board))
            out.add(Arrays.asList(seen).contains(s.position) ? s.id : "card" + s.position);
        return out;
    }

    /** Wants ids in this order of preference, and only these. Records the "added" list it was shown. */
    static MatchGameRule.Preference ranking(List<List<String>> seenAdded, String... wanted) {
        return (offer, added) -> {
            seenAdded.add(new ArrayList<>(added));
            for (String id : wanted) if (offer.contains(id)) return offer.indexOf(id);
            return -1;
        };
    }

    static int position(List<MatchGameRule.Slot> board, MatchGameRule.Pick pick) {
        return MatchGameRule.pickable(board).get(pick.index).position;
    }

    static String idAt(List<MatchGameRule.Slot> board, MatchGameRule.Pick pick) {
        return MatchGameRule.pickable(board).get(pick.index).id;
    }

    public static void main(String[] args) throws Exception {
        // Below A15: rare, uncommon, common, colorless, curse, starter, each twice.
        String[] full = {"Rare", "Unc", "Injury", "Com", "Strike_R", "Col", "Rare", "Com", "Unc", "Injury", "Col", "Strike_R"};
        List<List<String>> added = new ArrayList<>();

        // Board mapping: pair Com (3, 7) matched, first card at 1 face up -> choices skip 1, 3, 7 and keep board order.
        String[] partial = full.clone();
        partial[3] = null; partial[7] = null;
        List<MatchGameRule.Slot> b = board(partial);
        MatchGameRule.Slot up = flip(b, 1);
        List<String> shown = labels(b, 9);
        check(shown.equals(Arrays.asList("card0", "card2", "card4", "card5", "card6", "card8", "Injury", "card10", "card11")),
            "choice labels follow board order without matched or face-up cards: " + shown);
        MatchGameRule.Pick pick = MatchGameRule.choose(b, up, 4, Arrays.asList("Unc"), shown);
        check(pick != null && pick.index == 5 && position(b, pick) == 8, "partner of Unc at position 8 is choice 5");
        List<String> stale = new ArrayList<>(shown);
        stale.set(6, "Decay");
        check(MatchGameRule.choose(b, up, 4, Arrays.asList("Unc"), stale) == null, "a label that contradicts the board falls back");
        check(MatchGameRule.choose(b, up, 4, Arrays.asList("Unc"), shown.subList(1, shown.size())) == null,
            "a choice count that contradicts the board falls back");

        // Wanted pairs best-first, sequential: the second offer sees the first pick as already added.
        b = board(full);
        List<String> plan = MatchGameRule.plan(MatchGameRule.candidates(b), ranking(added, "Col", "Rare", "Injury"));
        check(plan.equals(Arrays.asList("Col", "Rare")), "plan is best-first and never offers the curse: " + plan);
        check(!MatchGameRule.candidates(b).contains("Injury"), "curse pair is not a candidate");
        check(added.get(0).isEmpty() && added.get(1).equals(Arrays.asList("Col")), "sequential deck additions: " + added);
        pick = MatchGameRule.choose(b, null, 5, plan, labels(b));
        check(pick != null && idAt(b, pick).equals("Col") && position(b, pick) == 5, "first click opens the best wanted pair");
        up = flip(b, 5);
        pick = MatchGameRule.choose(b, up, 5, plan, labels(b));
        check(pick != null && position(b, pick) == 10, "second click completes the wanted pair");
        b = board(full); b.removeIf(s -> s.id.equals("Col"));
        pick = MatchGameRule.choose(b, null, 4, plan, labels(b));
        check(pick != null && idAt(b, pick).equals("Rare"), "then the next wanted pair");

        // Curse never matched: even with a face-up curse and a preference list naming it.
        b = board(full);
        up = flip(b, 2);
        pick = MatchGameRule.choose(b, up, 5, Arrays.asList("Injury", "Rare"), labels(b));
        check(pick != null && !idAt(b, pick).equals("Injury"), "a face-up curse is answered with a different card");

        // Nothing wanted: two cards of different pairs every attempt.
        b = board(full);
        pick = MatchGameRule.choose(b, null, 5, new ArrayList<String>(), labels(b));
        check(pick != null, "nothing wanted still flips a card");
        up = flip(b, position(b, pick));
        MatchGameRule.Pick second = MatchGameRule.choose(b, up, 5, new ArrayList<String>(), labels(b));
        check(second != null && !idAt(b, second).equals(up.id), "nothing wanted: the second card belongs to another pair");

        // A15 with two equal curses: four wanted pairs still fit five attempts, the miss comes before the last pair.
        String[] a15 = {"Rare", "Unc", "Decay", "Com", "Strike_R", "Decay", "Rare", "Com", "Unc", "Decay", "Decay", "Strike_R"};
        b = board(a15);
        plan = MatchGameRule.plan(MatchGameRule.candidates(b), ranking(added, "Rare", "Unc", "Com", "Strike_R"));
        check(plan.size() == 4, "A15: four non-curse pairs wanted: " + plan);
        b.removeIf(s -> !s.id.equals("Strike_R") && !s.id.equals("Decay")); // three pairs matched, two attempts left
        pick = MatchGameRule.choose(b, null, 2, plan, labels(b));
        check(pick != null && pick.note.contains("留一次翻错"), "A15: with two attempts the miss comes first: " + pick.note);
        up = flip(b, position(b, pick));
        second = MatchGameRule.choose(b, up, 2, plan, labels(b));
        check(second != null && !idAt(b, second).equals(up.id), "A15: the reserved attempt is a real miss");
        b = board(a15); b.removeIf(s -> !s.id.equals("Strike_R") && !s.id.equals("Decay"));
        pick = MatchGameRule.choose(b, null, 1, plan, labels(b));
        check(pick != null && idAt(b, pick).equals("Strike_R"), "A15: the last attempt takes the last wanted pair");

        // Self-check: revealed labels and matched pairs against the board read at first sight.
        b = board(full);
        MatchGameRule.State state = new MatchGameRule.State(new Object(), b, 0, plan);
        b.removeIf(s -> s.id.equals("Col"));
        List<String> log = state.check(b, null, labels(b, 0), Arrays.asList("Col"));
        check(log.size() == 2 && log.get(0).startsWith("preread ok") && log.get(1).equals("preread ok Match and Keep! matched Col"),
            "self-check passes a revealed card and a matched pair: " + log);
        List<String> wrong = labels(b);
        wrong.set(1, "Bash");
        log = state.check(b, null, wrong, Arrays.asList("Col"));
        check(log.size() == 1 && log.get(0).startsWith("preread MISMATCH") && log.get(0).contains("actual=Bash"),
            "self-check flags a label that differs from the preread: " + log);

        // live=false (training): no rule, the network scores this screen exactly as before.
        EventRules.live = false;
        EventRules.Context c = new EventRules.Context();
        c.event = MatchGameRule.EVENT;
        check(MatchGameRule.decide(c, new com.google.gson.JsonArray()) == null && c.preread == null, "live off: the rule is inert");
        JsonObject fixture;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8)) {
            fixture = new JsonParser().parse(reader).getAsJsonObject();
        }
        OutsidePacket packet = EventRulesCheck.event(Distill2.load(), fixture, MatchGameRule.EVENT, 70, 80, 12);
        Distill2.Result result = packet.policyScore();
        check(result.best == packet.score().best && packet.ruleNote == null && packet.prereadNote == null,
            "live off: the network decides the board");
        System.out.println("PASS: Match and Keep! mapping, wanted pairs, curses, deliberate misses, self-check, live off");
    }
}
