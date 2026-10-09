package sts1solver;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.io.*;

public class ExperienceCheck {
    static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Paths.get(args[0]), "experience-check-");
        ExperienceNotes notes = new ExperienceNotes(1024);
        JsonObject state = new JsonObject();
        state.addProperty("player_name", "private-name");
        state.addProperty("path", "C:\\Users\\private-name\\file");
        state.addProperty("seed", "-9223372036854775808");
        JsonArray cards = new JsonArray();
        for (int i=0;i<100;i++) { JsonObject card=new JsonObject(); card.addProperty("id","Strike_R"); card.addProperty("uuid","card-"+i); card.addProperty("upgrades",i); cards.add(card); }
        state.add("deck",cards);
        notes.record("observation",state,null,null);
        check(notes.directory()==null && notes.count()==0,"default off writes nothing");
        notes.start(root,new JsonObject());
        notes.record("observation",state,null,null);
        notes.record("observation",state,null,null);
        check(notes.count()==1,"identical observations deduplicated");
        for (int i=0;i<4;i++) notes.record("solver_action_submitted",state,"play 1 0",new JsonObject());
        notes.stop();
        notes.record("observation",state,null,null);
        check(notes.count()==5,"off stops collection");
        int seen=0,parts=0;
        try (DirectoryStream<Path> files=Files.newDirectoryStream(notes.directory(),"*.zip")) {
            for(Path path:files) {
                parts++;
                try(ZipFile zip=new ZipFile(path.toFile()); BufferedReader input=new BufferedReader(new InputStreamReader(zip.getInputStream(zip.getEntry("experience.jsonl")),StandardCharsets.UTF_8))) {
                    JsonObject header=new JsonParser().parse(input.readLine()).getAsJsonObject();
                    check(header.get("format").getAsString().equals("sts1-experience-v1"),"schema header per part");
                    String line;
                    while((line=input.readLine())!=null) {
                        seen++;
                        check(!line.contains("private-name"),"direct identity and paths removed");
                        JsonObject event=new JsonParser().parse(line).getAsJsonObject();
                        JsonObject restored=event.getAsJsonObject("state");
                        check(restored.get("seed").getAsString().equals("-9223372036854775808"),"64-bit seed preserved");
                        check(restored.getAsJsonArray("deck").size()==100,"full deck preserved across compression");
                        check(restored.getAsJsonArray("deck").get(99).getAsJsonObject().get("uuid").getAsString().equals("card-99"),"card identity preserved");
                    }
                }
            }
        }
        check(parts==5 && seen==5,"rotation preserves every event without truncation");
        ExperienceNotes idle = new ExperienceNotes();
        idle.start(root,new JsonObject());
        for (int i=0;i<3;i++) {
            JsonObject raw=new JsonParser().parse("{\"raw_state\":{\"game_state\":{\"combat_state\":{\"turn\":3},\"full_rng_state\":{\"streams\":{\"aiRng\":{\"counter\":"+(i/2)+"}}}}}}").getAsJsonObject();
            JsonObject game=raw.getAsJsonObject("raw_state").getAsJsonObject("game_state");
            game.getAsJsonObject("combat_state").addProperty("frame_delta_seconds",0.016+i);
            JsonObject cosmetic=new JsonObject(); cosmetic.addProperty("counter",100+i);
            game.getAsJsonObject("full_rng_state").getAsJsonObject("streams").add("MathUtils.random",cosmetic);
            idle.record("observation",raw,null,null);
        }
        idle.stop();
        check(idle.count()==2,"idle frame timer and MathUtils ignored; game RNG change still recorded");
        System.out.println("PASS: opt-in, lossless large-deck/seed/action capture, deduplication, privacy filtering and chunk rotation");
    }
}
