package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * CardWant scores a hypothetical offer from an event page exactly as the real CARD_REWARD screen (recorded simulator
 * parity fixture), grids as the real GRID decision, and PrereadCheck turns later states into one self-check line.
 * usage: CardWantCheck distill2-card-parity.json distill2-event-parity.json
 */
public final class CardWantCheck {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static JsonObject read(String path) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(reader).getAsJsonObject();
        }
    }

    static JsonObject copy(JsonObject value) { return new JsonParser().parse(value.toString()).getAsJsonObject(); }

    static void same(String name, float[] actual, JsonArray expected) {
        check(actual.length == expected.size(), name + " width");
        for (int i = 0; i < actual.length; i++)
            check(Float.floatToIntBits(actual[i]) == Float.floatToIntBits(expected.get(i).getAsFloat()),
                name + "[" + i + "] " + actual[i] + " != " + expected.get(i));
    }

    static int uuids;

    static JsonObject card(String id, String type) {
        JsonObject card = new JsonObject();
        card.addProperty("id", id); card.addProperty("name", id); card.addProperty("type", type);
        card.addProperty("upgrades", 0); card.addProperty("misc", 0);
        card.addProperty("uuid", "test-" + (++uuids));  // the grid tells selected cards apart by uuid
        return card;
    }

    /** The recorded state turned into an event page (what readLive sees): same run state, an event screen instead. */
    static JsonObject[] eventPage(JsonObject fixture, String event, int buttons) {
        JsonObject state = copy(fixture.getAsJsonObject("state")), visible = copy(fixture.getAsJsonObject("visible"));
        JsonObject game = state.getAsJsonObject("game_state");
        game.addProperty("screen_type", "EVENT");
        JsonObject screen = new JsonObject();
        screen.addProperty("event_id", event);
        game.add("screen_state", screen);
        JsonArray choices = new JsonArray();
        for (int i = 0; i < buttons; i++) choices.add("option " + i);
        visible.add("choices", choices);
        visible.addProperty("screen_type", "EVENT");
        return new JsonObject[]{game, visible};
    }

    /** The real CARD_REWARD state for these cards, built directly the way CommunicationMod reports it. */
    static int realPick(Distill2 model, JsonObject fixture, List<JsonObject> offer) {
        JsonObject state = copy(fixture.getAsJsonObject("state")), visible = copy(fixture.getAsJsonObject("visible"));
        JsonArray cards = new JsonArray();
        for (JsonObject c : offer) cards.add(c);
        state.getAsJsonObject("game_state").getAsJsonObject("screen_state").add("cards", cards);
        OutsidePacket packet = OutsidePacket.build(model, state, visible);
        Distill2.Result result = packet.policyScore();
        String command = packet.choices.get(result.best).command;
        int index = command.startsWith("choose ") ? Integer.parseInt(command.substring(7)) : -1;
        return index < offer.size() ? index : -1;
    }

    public static void main(String[] args) throws Exception {
        Distill2 model = Distill2.load();
        JsonObject fixture = read(args[0]);
        JsonObject[] page = eventPage(fixture, "SensoryStone", 3);
        List<JsonObject> offer = new ArrayList<>();
        for (JsonElement c : fixture.getAsJsonObject("state").getAsJsonObject("game_state").getAsJsonObject("screen_state")
                .getAsJsonArray("cards")) offer.add(c.getAsJsonObject());

        // Parity: from the event page, the hypothetical screen is bit for bit the recorded CARD_REWARD packet.
        OutsidePacket packet = CardWant.rewardPacket(model, page[0], page[1], offer, Collections.<JsonObject>emptyList());
        JsonObject expected = fixture.getAsJsonObject("expected");
        same("observation", packet.observation, expected.getAsJsonArray("observation"));
        same("extra", packet.extra, expected.getAsJsonArray("extra"));
        JsonArray bits = fixture.getAsJsonArray("bits");
        check(packet.choices.size() == bits.size(), "candidate count");
        for (int i = 0; i < bits.size(); i++) {
            check(packet.choices.get(i).bits == bits.get(i).getAsLong(), "action bits " + i);
            same("descriptor " + i, packet.choices.get(i).descriptor, expected.getAsJsonArray("descriptors").get(i).getAsJsonArray());
            same("route " + i, packet.choices.get(i).route, expected.getAsJsonArray("routes").get(i).getAsJsonArray());
        }
        float[] scores = packet.score().scores;
        for (int i = 0; i < scores.length; i++)
            check(Math.abs(scores[i] - fixture.getAsJsonArray("scores").get(i).getAsFloat()) <= .002f, "student score " + i);
        check(CardWant.pick(model, page[0], page[1], offer, Collections.<JsonObject>emptyList()) == -1,
            "the recorded screen skips its three colorless cards");

        // Verdicts agree with the real CARD_REWARD packet for other offers, and some offer is wanted.
        String[][] offers = {{"Offering", "SKILL"}, {"Demon Form", "POWER"}, {"Feed", "ATTACK"}, {"Clumsy", "CURSE"},
            {"Panacea", "SKILL"}, {"Apotheosis", "SKILL"}, {"Hand of Greed", "ATTACK"}, {"Shrug It Off", "SKILL"}};
        int wantedSingles = 0;
        for (String[] c : offers) {
            List<JsonObject> one = Collections.singletonList(card(c[0], c[1]));
            int pick = CardWant.pick(model, page[0], page[1], one, Collections.<JsonObject>emptyList());
            check(pick == realPick(model, fixture, one), c[0] + ": same verdict as the real reward screen");
            if (pick == 0) wantedSingles++;
        }
        check(wantedSingles > 0 && wantedSingles < offers.length, "offers both wanted and skipped: " + wantedSingles);
        List<JsonObject> three = Arrays.asList(card("Clumsy", "CURSE"), card("Offering", "SKILL"), card("Shrug It Off", "SKILL"));
        check(CardWant.pick(model, page[0], page[1], three, Collections.<JsonObject>emptyList()) == realPick(model, fixture, three),
            "three-card offer: same verdict as the real reward screen");

        // Sequential groups: the earlier pick is in the deck of the later screen.
        JsonObject picked = card("Offering", "SKILL");
        OutsidePacket later = CardWant.rewardPacket(model, page[0], page[1], offer, Collections.singletonList(picked));
        int deck = page[0].getAsJsonArray("deck").size();
        check(later.observation[8] == (deck + 1) / 100f && packet.observation[8] == deck / 100f, "the picked card joins the deck");
        check(!Arrays.equals(later.observation, packet.observation), "a deck addition changes the later screen");

        // Singing Bowl owned: the bowl is offered, and choosing it is not a card.
        JsonObject bowlGame = copy(page[0]);
        JsonObject bowl = new JsonObject();
        bowl.addProperty("id", "Singing Bowl"); bowl.addProperty("counter", -1);
        bowlGame.getAsJsonArray("relics").add(bowl);
        OutsidePacket bowled = CardWant.rewardPacket(model, bowlGame, page[1], offer, Collections.<JsonObject>emptyList());
        boolean hasBowl = false;
        for (OutsidePacket.Choice choice : bowled.choices) hasBowl |= choice.label.equals("Singing Bowl");
        check(hasBowl, "Singing Bowl is offered when owned");

        // Unbound (no outside decision being ruled) or failing: -1, and choose throws so callers keep their old rule.
        check(CardWant.pick(Collections.emptyList(), Collections.emptyList()) == -1, "unbound pick is -1");
        boolean threw = false;
        try { CardWant.choose(Collections.emptyList(), Collections.emptyList()); } catch (IllegalStateException e) { threw = true; }
        check(threw, "unbound choose throws");

        gridChecks(model, read(args[1]));
        selfChecks();
        System.out.println("PASS: CardWant reward/grid parity and pre-read self-checks");
    }

    static void gridChecks(Distill2 model, JsonObject fixture) {
        JsonObject[] page = eventPage(fixture, "Transmorgrifier", 2);
        page[1].addProperty("event_hp_loss", 0);
        List<JsonObject> grid = Arrays.asList(card("Defend_R", "SKILL"), card("Hemokinesis", "ATTACK"), card("Bash", "ATTACK"),
            card("Strike_R", "ATTACK"), card("Injury", "CURSE"));
        List<JsonObject> none = Collections.emptyList();
        // Transform grid: the real GRID rule (removal order) skips the curse (it only becomes another curse): Strike, then Bash.
        check(CardWant.gridPick(model, page[0], page[1], grid, none, 1, true) == 3, "transform grid skips the curse for the Strike");
        check(CardWant.gridPick(model, page[0], page[1], Arrays.asList(grid.get(0), grid.get(1), grid.get(2), grid.get(4)), none, 1, true) == 2,
            "then the Bash, still not the curse");
        // Drug Dealer grid (for_transform off): the network's choice over the whole grid, and never the card already selected.
        int first = CardWant.gridPick(model, page[0], page[1], grid, none, 2, false);
        int second = CardWant.gridPick(model, page[0], page[1], grid, Collections.singletonList(grid.get(first)), 2, false);
        check(first >= 0 && second >= 0 && second != first, "two different selections: " + first + ", " + second);
    }

    static JsonObject state(int floor, String screen, JsonArray cards, JsonArray deck) {
        JsonObject game = new JsonObject();
        game.addProperty("floor", floor);
        JsonObject s = new JsonObject();
        s.add("cards", cards);
        game.add("screen_state", s);
        game.add("deck", deck);
        return game;
    }

    static JsonObject visible(String screen) {
        JsonObject v = new JsonObject();
        v.addProperty("screen_type", screen);
        return v;
    }

    static JsonArray cards(String... faces) {
        JsonArray out = new JsonArray();
        for (String face : faces) {
            String[] part = face.split("\\+");
            JsonObject card = card(part[0], "SKILL");
            card.addProperty("upgrades", Integer.parseInt(part[1]));
            if (part.length > 2) card.addProperty("uuid", part[2]);
            out.add(card);
        }
        return out;
    }

    static void selfChecks() {
        JsonArray empty = new JsonArray();
        List<List<String>> groups = Arrays.asList(Arrays.asList("Trip+0", "Panacea+0", "Blind+0"),
            Arrays.asList("Apotheosis+0", "Madness+0", "Finesse+0"));
        PrereadCheck.expect("SensoryStone", 30, PrereadCheck.rewardGroups("SensoryStone", groups));
        check(PrereadCheck.observe(state(30, "COMBAT_REWARD", empty, empty), visible("COMBAT_REWARD")) == null, "rewards page: wait");
        String ok = PrereadCheck.observe(state(30, "CARD_REWARD", cards("Madness+0", "Apotheosis+0", "Finesse+0"), empty), visible("CARD_REWARD"));
        check(ok != null && ok.startsWith("preread ok: SensoryStone group 2"), "group 2 shown in any order: " + ok);
        check(PrereadCheck.observe(state(30, "CARD_REWARD", cards("Trip+0"), empty), visible("CARD_REWARD")) == null, "one line per pre-read");

        PrereadCheck.expect("SensoryStone", 30, PrereadCheck.rewardGroups("SensoryStone", groups));
        String bad = PrereadCheck.observe(state(30, "CARD_REWARD", cards("Trip+0", "Panacea+0", "Blind+1"), empty), visible("CARD_REWARD"));
        check(bad != null && bad.startsWith("preread MISMATCH: SensoryStone"), "an upgrade difference is a mismatch: " + bad);

        PrereadCheck.expect("The Library", 20, PrereadCheck.grid("The Library", Arrays.asList("Feed+0", "Inflame+0")));
        String lib = PrereadCheck.observe(state(20, "GRID", cards("Inflame+0", "Feed+0"), empty), visible("GRID"));
        check(lib != null && lib.startsWith("preread ok: The Library"), "library grid in reverse order: " + lib);
        PrereadCheck.expect("The Library", 20, PrereadCheck.grid("The Library", Arrays.asList("Feed+0", "Inflame+0")));
        String moved = PrereadCheck.observe(state(21, "EVENT", empty, empty), visible("EVENT"));
        check(moved != null && moved.startsWith("preread unchecked"), "another floor ends the wait: " + moved);

        List<String> before = Arrays.asList("Strike_R+0", "Strike_R+0", "Defend_R+0");
        PrereadCheck.expect("Transmorgrifier", 12, PrereadCheck.transform("Transmorgrifier", new HashSet<>(Collections.singletonList("u1")),
            Collections.singletonList("Feed+0"), before));
        check(PrereadCheck.observe(state(12, "EVENT", empty, cards("Strike_R+0+u2", "Defend_R+0+u3", "Feed+0+u9")), visible("EVENT")) == null,
            "transform: wait for the map");
        String trans = PrereadCheck.observe(state(12, "MAP", empty, cards("Strike_R+0+u2", "Defend_R+0+u3", "Feed+0+u9")), visible("MAP"));
        check(trans != null && trans.startsWith("preread ok: Transmorgrifier"), "transform result joined, target left: " + trans);
        PrereadCheck.expect("Transmorgrifier", 12, PrereadCheck.transform("Transmorgrifier", new HashSet<>(Collections.singletonList("u1")),
            Collections.singletonList("Feed+0"), before));
        String wrong = PrereadCheck.observe(state(12, "MAP", empty, cards("Strike_R+0+u2", "Defend_R+0+u3", "Inflame+0+u9")), visible("MAP"));
        check(wrong != null && wrong.startsWith("preread MISMATCH: Transmorgrifier"), "another result is a mismatch: " + wrong);
        PrereadCheck.expect("Transmorgrifier", 12, PrereadCheck.transform("Transmorgrifier", new HashSet<>(Collections.singletonList("u1")),
            Collections.singletonList("Feed+0"), before));
        String kept = PrereadCheck.observe(state(12, "MAP", empty, cards("Strike_R+0+u1", "Strike_R+0+u2", "Defend_R+0+u3")), visible("MAP"));
        check(kept != null && kept.startsWith("preread unchecked"), "transform not taken: " + kept);
        check(PrereadCheck.observe(state(12, "MAP", empty, empty), visible("MAP")) == null, "nothing pending");
    }
}
