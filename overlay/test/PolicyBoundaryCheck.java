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
 * EventRules.SL_PREREAD events. usage: PolicyBoundaryCheck SRC_DIR [extra policy .java ...]
 */
public final class PolicyBoundaryCheck {
    /** Decision code. DecisionContext is left out: its only Foresight use is the bright_eye_mode flag in recording metadata. */
    static final String[] POLICY = {"OutsidePacket", "Distill2", "RoutePlanner", "EventRules", "RelicScore", "PotionRewards"};
    /** Display-only classes and members that carry beyond-SL information. */
    static final String[] FORBIDDEN = {"Foresight", "EventForesight", "TransformPreview", "Sandbox", "brightEyeMode", "nextRooms"};

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

    public static void main(String[] args) throws Exception {
        // the violating case: a policy that reads the map preview must fail
        check(violations("int route(List<String> out) { Foresight.nextRooms(out); return out.size(); }")
            .containsAll(Arrays.asList("Foresight", "nextRooms")), "a policy reading Foresight.nextRooms is rejected");
        check(violations("void ev() { if (Foresight.brightEyeMode) score += EventForesight.stateKey(e).length(); }").size() == 3,
            "a policy gated on brightEyeMode or reading event-fight previews is rejected");
        check(violations("/** same order as EventForesight.scrapOoze */ String s = \"Foresight\"; // nextRooms").isEmpty(),
            "comments and strings are not references");

        Path dir = Paths.get(args[0]);
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
