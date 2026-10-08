package sts1solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Compare the Java bridge against an independently generated simulator packet. */
public final class OutsidePacketParityCheck {
    private static void compare(String name, float[] actual, JsonArray expected) {
        if (actual.length != expected.size()) throw new AssertionError(name + " width");
        for (int i = 0; i < actual.length; i++) {
            float value = expected.get(i).getAsFloat();
            if (Float.floatToIntBits(actual[i]) != Float.floatToIntBits(value))
                throw new AssertionError(name + '[' + i + "] " + actual[i] + " != " + value);
        }
    }

    public static void main(String[] args) throws Exception {
        JsonObject fixture;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(args[0]), StandardCharsets.UTF_8)) {
            fixture = new JsonParser().parse(reader).getAsJsonObject();
        }
        OutsidePacket packet = OutsidePacket.build(Distill2.load(),
            fixture.getAsJsonObject("state"), fixture.getAsJsonObject("visible"));
        JsonObject expected = fixture.getAsJsonObject("expected");
        compare("observation", packet.observation, expected.getAsJsonArray("observation"));
        compare("extra", packet.extra, expected.getAsJsonArray("extra"));
        JsonArray descriptors = expected.getAsJsonArray("descriptors");
        JsonArray routes = expected.getAsJsonArray("routes");
        JsonArray bits = fixture.getAsJsonArray("bits");
        if(args.length>1 && args[1].equals("restricted-removal")) {
            if(packet.choices.size()>=bits.size())throw new AssertionError("Shop removal was not restricted");
            JsonArray cards=fixture.getAsJsonObject("state").getAsJsonObject("game_state")
                .getAsJsonObject("screen_state").getAsJsonArray("cards");
            for(OutsidePacket.Choice choice:packet.choices) {
                int original=-1;
                for(int i=0;i<bits.size();i++)if(bits.get(i).getAsLong()==choice.bits)original=i;
                if(original<0)throw new AssertionError("Unknown restricted action");
                if(choice.bits<cards.size()) {
                    String id=cards.get((int)choice.bits).getAsJsonObject().get("id").getAsString();
                    if(!id.equals("STRIKE_RED") && !id.equals("DEFEND_RED") && !id.equals("BASH")
                            && !cards.get((int)choice.bits).getAsJsonObject().get("type").getAsString().equals("CURSE"))
                        throw new AssertionError("Disallowed shop removal: "+id);
                }
                compare("restricted descriptor",choice.descriptor,descriptors.get(original).getAsJsonArray());
                compare("restricted route",choice.route,routes.get(original).getAsJsonArray());
            }
            packet.score();
            System.out.println("PASS: shop removal permits only curse, Strike, Defend and Bash");
            return;
        }
        if (packet.choices.size() != descriptors.size()) throw new AssertionError("Candidate count");
        for (int i = 0; i < packet.choices.size(); i++) {
            OutsidePacket.Choice choice = packet.choices.get(i);
            if (choice.bits != bits.get(i).getAsLong()) throw new AssertionError("Action bits " + i);
            compare("descriptor " + i, choice.descriptor, descriptors.get(i).getAsJsonArray());
            compare("route " + i, choice.route, routes.get(i).getAsJsonArray());
        }
        Distill2.Result result = packet.score();
        for (int i = 0; i < result.scores.length; i++)
            if (Math.abs(result.scores[i] - fixture.getAsJsonArray("scores").get(i).getAsFloat()) > .002f)
                throw new AssertionError("Student score " + i);
        System.out.println("PASS: simulator/Java public packet and score parity");
    }
}
