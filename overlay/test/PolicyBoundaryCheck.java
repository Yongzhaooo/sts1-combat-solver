package sts1solver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SL-visible boundary of the decision policy (docs/outside-rules.md). The policy classes (network input, OutsidePacket rules,
 * EventRules, RoutePlanner, ...) must never reach the bright-eye display code, which shows beyond-SL information: map rooms
 * ahead (Foresight.nextRooms / question) and event-fight rewards (EventForesight). The only live reads allowed are the
 * EventRules.SL_PREREAD events. Neow's four offers are on the game's page (policy input); what they roll (cards, relic,
 * potions, transforms) is beyond SL and shown only with Glowing Eye (brightEyeMode) on. usage: PolicyBoundaryCheck SRC_DIR [extra policy .java ...]
 */
public final class PolicyBoundaryCheck {
    /** Decision code. DecisionContext is left out: its only Foresight use is the bright_eye_mode flag in recording metadata. */
    static final String[] POLICY = {"OutsidePacket", "Distill2", "RoutePlanner", "EventRules", "RelicScore", "PotionRewards"};
    /** Display-only classes and members that carry beyond-SL information. */
    static final String[] FORBIDDEN = {"Foresight", "EventForesight", "TransformPreview", "Sandbox", "brightEyeMode", "nextRooms",
        "NeowEvent", "NeowReward", "neowRows"};

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    /** Source without comments and string literals, so that only code references count. */
    static String code(String source) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 1;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                for (i++; i < source.length() && source.charAt(i) != c; i++) if (source.charAt(i) == '\\') i++;
                out.append("\"\"");
            } else out.append(c);
        }
        return out.toString();
    }

    /** Forbidden identifiers this source uses in code. */
    static List<String> violations(String source) {
        List<String> found = new ArrayList<>();
        String code = code(source);
        for (String name : FORBIDDEN)
            if (java.util.regex.Pattern.compile("\\b" + name + "\\b").matcher(code).find()) found.add(name);
        return found;
    }

    /** Glowing Eye off: the offers are listed, nothing is rolled and no option note is set; on: unchanged. */
    static void neowGate() throws Exception {
        String[] bonus = {"THREE_CARDS", "ONE_RARE_RELIC", "TRANSFORM_CARD", "HUNDRED_GOLD"};
        String[] drawback = {"NONE", "NONE", "CURSE", "NO_GOLD"};
        java.lang.reflect.Field notes = Foresight.class.getDeclaredField("optionNotes");
        notes.setAccessible(true);
        java.util.Map<?, ?> optionNotes = (java.util.Map<?, ?>) notes.get(null);
        int[] rolled = {0};
        java.util.function.IntFunction<List<String>> roll = i -> {
            rolled[0]++;
            return Arrays.asList(i == 3 ? "  → none" : "  → Secret Outcome " + i, "  取决于别的");
        };

        optionNotes.clear();
        List<String> off = new ArrayList<>();
        Foresight.neowRows(bonus, drawback, false, roll, off);
        check(rolled[0] == 0, "Glowing Eye off: no Neow outcome is rolled");
        check(optionNotes.isEmpty(), "Glowing Eye off: no Neow option note");
        check(off.size() == 5, "Glowing Eye off: heading and the four offers only: " + off);
        for (String row : off) check(!row.contains("Secret") && !row.contains("→"), "Glowing Eye off: outcome text in " + row);
        check(off.get(3).contains(I18n.t("（代价：")), "Glowing Eye off: the offers keep their drawbacks: " + off.get(3));

        optionNotes.clear();
        List<String> on = new ArrayList<>();
        Foresight.neowRows(bonus, drawback, true, roll, on);
        check(rolled[0] == 4, "Glowing Eye on: every option is rolled");
        check(on.contains("  → Secret Outcome 0") && on.size() == 5 + 8, "Glowing Eye on: outcome rows are listed: " + on);
        check("Secret Outcome 1".equals(optionNotes.get(1)) && optionNotes.size() == 4, "Glowing Eye on: option notes are set: " + optionNotes);
        optionNotes.clear();
    }

    public static void main(String[] args) throws Exception {
        // the violating case: a policy that reads the map preview must fail
        check(violations("int route(List<String> out) { Foresight.nextRooms(out); return out.size(); }")
            .containsAll(Arrays.asList("Foresight", "nextRooms")), "a policy reading Foresight.nextRooms is rejected");
        check(violations("void ev() { if (Foresight.brightEyeMode) score += EventForesight.stateKey(e).length(); }").size() == 3,
            "a policy gated on brightEyeMode or reading event-fight previews is rejected");
        check(violations("/** same order as EventForesight.scrapOoze */ String s = \"Foresight\"; // nextRooms").isEmpty(),
            "comments and strings are not references");

        check(violations("void neow(NeowReward r) { r.getRewardCards(true); }").contains("NeowReward"),
            "a policy reading Neow's live rewards is rejected");
        neowGate();

        Path dir = Paths.get(args[0]);
        // the display callers hand Glowing Eye to the gate: the panel/option rows and the Neow transform labels
        String display = code(new String(Files.readAllBytes(dir.resolve("Foresight.java")), StandardCharsets.UTF_8))
            .replaceAll("\\s+", " ");
        check(display.contains("neowRows(bonus, drawback, brightEyeMode,"), "Neow rows must be gated on brightEyeMode");
        check(display.contains("if (neow && !brightEyeMode) return;"), "Neow transform labels must be gated on brightEyeMode");
        List<Path> files = new ArrayList<>();
        for (String name : POLICY) files.add(dir.resolve(name + ".java"));
        for (int i = 1; i < args.length; i++) files.add(Paths.get(args[i]));
        for (Path file : files) {
            List<String> found = violations(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            check(found.isEmpty(), file.getFileName() + " reaches beyond-SL display code: " + found);
        }

        // pre-reads: exactly the audited within-SL events; event-fight rewards are never pre-read
        Set<String> audited = new HashSet<>(Arrays.asList("Knowing Skull", "Scrap Ooze", "Dead Adventurer", "FaceTrader", "The Joust"));
        check(EventRules.SL_PREREAD.equals(audited), "SL pre-read allowlist changed: " + EventRules.SL_PREREAD);
        for (String beyond : new String[]{"Masked Bandits", "Mushrooms", "Mysterious Sphere"})
            check(!EventRules.SL_PREREAD.contains(beyond), beyond + " fight rewards are beyond SL");
        // offline (live off) nothing is read and nothing is recorded
        EventRules.Context c = new EventRules.Context();
        c.event = "Scrap Ooze";
        EventRules.readLive(c);
        check(c.preread == null && c.oozeLoss == EventRules.UNKNOWN, "readLive is inert while live is off");
        System.out.println("PolicyBoundaryCheck passed: " + files.size() + " policy sources, " + audited.size() + " SL pre-read events");
    }
}
