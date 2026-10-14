package sts1solver;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Golden routes of all 30 reviewed openings (Python goldens until W19b; since the expected-gold shop simulation they are
 * the Java planner's, with 6 paths and 15 scores changed), plus suffix, key, gold and burning-elite cases. */
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
        keyChecks(fixtures.get(0).getAsJsonObject());
        goldChecks();
        wingChecks();
        System.out.printf("PASS: %d golden openings, final-act keys, expected gold, burning-elite lean, Wing Boots ties, all legal starts, low-HP suffix replans, unchanged input (%.0f ms)%n",checks,(System.nanoTime()-start)/1e6);
    }

    static JsonObject copy(JsonObject o) {return new JsonParser().parse(o.toString()).getAsJsonObject();}

    static boolean passes(RoutePlanner.Plan p, int x, int y) {
        for(OutsidePacket.Step s:p.path)if(s.x==x&&s.y==y)return true;
        return false;
    }

    /** Act 3 keys only with the final act on; acts 1-2 lean to the burning elite only when strong. */
    static void keyChecks(JsonObject fixture) {
        JsonObject g0=copy(fixture.getAsJsonObject("game")), v0=copy(fixture.getAsJsonObject("visible"));
        int found=0;
        for(JsonElement e:g0.getAsJsonArray("map")) {
            JsonObject n=e.getAsJsonObject();
            if(!n.get("symbol").getAsString().equals("E"))continue;
            int x=n.get("x").getAsInt(), y=n.get("y").getAsInt();
            JsonArray burning=new JsonArray(); JsonObject pos=new JsonObject(); pos.addProperty("x",x); pos.addProperty("y",y); burning.add(pos);
            JsonObject g=copy(g0), v=copy(v0); v.add("burning_elites",burning);
            g.addProperty("act",3); g.addProperty("solver_final_act",false);
            for(RoutePlanner.Plan p:new RoutePlanner(g,v).byStart.values())
                if(p.missingKeys!=0)throw new AssertionError("No act-3 key requirement without the final act");
            g.addProperty("solver_final_act",true);
            RoutePlanner.Plan keyed=new RoutePlanner(g,v).best(-1);
            if(keyed.missingKeys==0 && !passes(keyed,x,y))throw new AssertionError("Act 3 with the final act passes the burning elite");
            // Acts 1-2: strong (act 2, HP >= 70%) gets one extra elite weight on the burning-elite path; weak states never.
            g.addProperty("act",2); g.addProperty("current_hp",g.get("max_hp").getAsInt());
            RoutePlanner.Plan strong=new RoutePlanner(g,v).best(-1);
            g.addProperty("solver_final_act",false);
            RoutePlanner.Plan off=new RoutePlanner(g,v).best(-1);
            g.addProperty("solver_final_act",true); g.addProperty("current_hp",g.get("max_hp").getAsInt()*7/10-1);
            RoutePlanner.Plan hurt=new RoutePlanner(g,v).best(-1);
            g.addProperty("solver_final_act",false);
            RoutePlanner.Plan hurtOff=new RoutePlanner(g,v).best(-1);
            g.addProperty("act",1); g.addProperty("solver_final_act",true); g.addProperty("current_hp",g.get("max_hp").getAsInt());
            RoutePlanner.Plan act1=new RoutePlanner(g,v).best(-1);
            g.addProperty("solver_final_act",false);
            RoutePlanner.Plan act1Off=new RoutePlanner(g,v).best(-1);
            if(hurt.score!=hurtOff.score || act1.score!=act1Off.score)
                throw new AssertionError("Weak state (HP < 70%, or act 1 without a beaten elite) gets no burning-elite bonus");
            double bonus=RULES_ELITE_ACT2;
            if(passes(off,x,y) && Math.abs(strong.score-off.score-bonus)>1e-6)
                throw new AssertionError("Strong act-2 bonus is one elite weight");
            if(passes(strong,x,y) && !passes(off,x,y))found++;
            if(!passes(strong,x,y) && passes(off,x,y))throw new AssertionError("The bonus never steers away from the burning elite");
        }
        if(found==0)throw new AssertionError("Fixture 0 must have an elite the act-2 bonus pulls the route to");
    }
    static final double RULES_ELITE_ACT2=1.380691;

    /** One-column map: shops see the gold of the fights before them; unaffordable shops are neither shops nor "too close". */
    static void goldChecks() {
        String[] rooms={"M","$","T","$","M","M","M","M","M","M","M","M","M","M","M"};
        double shop60=line(rooms,60), shop59=line(rooms,59), none=line(new String[]{"M","T","T","T","M","M","M","M","M","M","M","M","M","M","M"},0);
        if(!(shop60>shop59))throw new AssertionError("60 gold + 15 from the first fight affords the row-1 shop");
        if(line(rooms,0)!=none)throw new AssertionError("Two unaffordable shops add nothing, not even a close-shops penalty");
    }
    /** M1 seed 5534104192084612327 step 471 (act 2, at the x=4 elite of row 13, Wing Boots): rest sites at x=1, 5, 6 all
     * scored 2.62 and the leftmost flight (x=1) spent a charge. The edge child x=5 must win any tie; a flight start wins
     * only by more than FLIGHT_MARGIN; with no edge child, flight is free. */
    static void wingChecks() {
        String[] row14={"R","R","R"};
        if(wing(row14,new int[]{1,5,6})!=5)throw new AssertionError("Tie: take the edge child, keep the Wing Boots charge");
        if(wing(new String[]{"R","M","R"},new int[]{1,5,6})==5)throw new AssertionError("A flight start more than 0.5 better wins");
        if(wing(row14,new int[]{1,6})<0)throw new AssertionError("No edge child offered: flight is allowed as before");
    }
    static int wing(String[] symbols,int[] offered) {
        int[] xs={1,5,6};
        JsonObject g=new JsonObject(), v=new JsonObject(), screen=new JsonObject();
        g.addProperty("act",2); g.addProperty("screen_type","MAP"); g.addProperty("current_hp",97); g.addProperty("max_hp",99);
        g.addProperty("gold",252); g.add("deck",new JsonArray());
        JsonObject keys=new JsonObject(); keys.addProperty("emerald",true); g.add("keys",keys);
        JsonArray relics=new JsonArray(); JsonObject boots=new JsonObject(); boots.addProperty("id","WingedGreaves"); relics.add(boots);
        g.add("relics",relics);
        JsonArray map=new JsonArray(), next=new JsonArray();
        JsonObject current=new JsonObject(); current.addProperty("x",4); current.addProperty("y",13); current.addProperty("symbol","E");
        JsonArray edge=new JsonArray(); JsonObject child=new JsonObject(); child.addProperty("x",5); child.addProperty("y",14); edge.add(child);
        current.add("children",edge); map.add(current);
        for(int i=0;i<3;i++) {
            JsonObject n=new JsonObject(); n.addProperty("x",xs[i]); n.addProperty("y",14); n.addProperty("symbol",symbols[i]);
            n.add("children",new JsonArray()); map.add(n);
        }
        for(int x:offered){JsonObject n=new JsonObject(); n.addProperty("x",x); n.addProperty("y",14); next.add(n);}
        screen.add("next_nodes",next); g.add("screen_state",screen); g.add("map",map);
        v.addProperty("x",4); v.addProperty("y",13); v.addProperty("route_upgrades",3); v.addProperty("purge_base_cost",75);
        RoutePlanner.Plan p=new RoutePlanner(g,v).best(-1);
        return p==null?-1:p.path.get(0).x;
    }

    static double line(String[] rooms,int gold) {
        JsonObject g=new JsonObject(), v=new JsonObject();
        g.addProperty("act",1); g.addProperty("screen_type","NONE"); g.addProperty("current_hp",70); g.addProperty("max_hp",70);
        g.addProperty("gold",gold); g.add("keys",new JsonObject()); g.add("deck",new JsonArray()); g.add("relics",new JsonArray());
        g.add("screen_state",new JsonObject());
        JsonArray map=new JsonArray();
        for(int y=0;y<15;y++) {
            JsonObject n=new JsonObject(); n.addProperty("x",0); n.addProperty("y",y); n.addProperty("symbol",rooms[y]);
            JsonArray children=new JsonArray();
            if(y<14){JsonObject c=new JsonObject(); c.addProperty("x",0); c.addProperty("y",y+1); children.add(c);}
            n.add("children",children); map.add(n);
        }
        g.add("map",map);
        v.addProperty("x",-1); v.addProperty("y",-1); v.addProperty("route_upgrades",0); v.addProperty("purge_base_cost",75);
        return new RoutePlanner(g,v).best(-1).score;
    }
}
