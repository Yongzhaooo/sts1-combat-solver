package sts1solver;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Cross-language golden routes from all 30 reviewed openings, plus suffix/key cases. */
public final class RoutePlannerCheck {
    public static void main(String[] args)throws Exception {
        JsonArray fixtures;
        try(Reader r=new InputStreamReader(new FileInputStream(args[0]),StandardCharsets.UTF_8)) {
            fixtures=new JsonParser().parse(r).getAsJsonArray();
        }
        long start=System.nanoTime();
        int checks=0;
        for(JsonElement item:fixtures) {
            JsonObject fixture=item.getAsJsonObject(),g=fixture.getAsJsonObject("game"),v=fixture.getAsJsonObject("visible");
            String before=g.toString()+v.toString();
            RoutePlanner.Plan p=new RoutePlanner(g,v).best(-1);
            if(p==null || p.path.size()!=15)throw new AssertionError("Missing opening");
            JsonArray expected=fixture.getAsJsonArray("path");
            for(int i=0;i<15;i++)if(p.path.get(i).x!=expected.get(i).getAsJsonArray().get(0).getAsInt())
                throw new AssertionError("Python/Java route mismatch: map "+checks+" row "+i);
            if(Math.abs(p.score-fixture.get("score").getAsDouble())>.001)
                throw new AssertionError("Score mismatch "+checks+" "+p.score);
            if(!before.equals(g.toString()+v.toString()))throw new AssertionError("Planner mutated state");
            // Use actual next_nodes for each possible first choice, including manual choices.
            for(JsonElement e:g.getAsJsonObject("screen_state").getAsJsonArray("next_nodes")) {
                int x=e.getAsJsonObject().get("x").getAsInt();
                RoutePlanner.Plan forced=new RoutePlanner(g,v).best(x);
                if(forced==null||forced.path.size()!=15||forced.path.get(0).x!=x)throw new AssertionError("Missing forced route");
            }
            OutsidePacket.Step step=p.path.get(0);
            v.addProperty("x",step.x);v.addProperty("y",step.y);
            for(JsonElement e:g.getAsJsonArray("map")) {
                JsonObject n=e.getAsJsonObject();
                if(n.get("x").getAsInt()==step.x && n.get("y").getAsInt()==step.y)
                    g.getAsJsonObject("screen_state").add("next_nodes",n.get("children"));
            }
            g.addProperty("current_hp",1);g.addProperty("gold",0);
            RoutePlanner.Plan suffix=new RoutePlanner(g,v).best(-1);
            if(suffix==null||suffix.path.size()!=14||suffix.path.get(0).y!=1)throw new AssertionError("Low HP suppressed plan");
            checks++;
        }
        System.out.printf("PASS: %d Python/Java openings, all legal starts, low-HP suffix replans, unchanged input (%.0f ms)%n",checks,(System.nanoTime()-start)/1e6);
    }
}
