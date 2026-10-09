package sts1solver;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reviewed public-state route preferences. Recomputed, never a locked itinerary.
 * No predicted encounter, seed, future reward or fixed-HP student rollout.
 * A combat-budget failure cannot suppress a legal opening plan.
 */
final class RoutePlanner {
    private static final JsonObject RULES = loadRules();
    final Map<Integer, Plan> byStart = new LinkedHashMap<>();
    private final Map<Integer, JsonObject> nodes = new HashMap<>();
    private final JsonObject game, visible, weights;
    private final Set<String> relics = new HashSet<>();
    private final int act, row, flame, upgrades;
    private final boolean wantGreen, wantRed, canSmith, canRest;

    static final class Plan {
        final List<OutsidePacket.Step> path;
        final double score;
        final int missingKeys;
        Plan(List<OutsidePacket.Step> path, double score, int missingKeys) {
            this.path = new ArrayList<>(path); this.score = score; this.missingKeys = missingKeys;
        }
        boolean beats(Plan other) {
            return other == null || missingKeys < other.missingKeys
                || (missingKeys == other.missingKeys && score > other.score + 1e-9);
        }
    }

    RoutePlanner(JsonObject game, JsonObject visible) {
        this.game=game; this.visible=visible;
        act=number(game,"act",1); row=number(visible,"y",-1)+1;
        weights=RULES.getAsJsonObject("weights").getAsJsonObject(act+":balanced");
        for(JsonElement e:array(game,"map")) {
            JsonObject n=e.getAsJsonObject(); nodes.put(key(number(n,"x",-1),number(n,"y",-1)),n);
        }
        for(JsonElement e:array(game,"relics")) relics.add(text(e.getAsJsonObject(),"id"));
        canSmith=!relics.contains("Fusion Hammer"); canRest=!relics.contains("Coffee Dripper");
        int count=0;
        for(JsonElement e:array(game,"deck")) {
            JsonObject c=e.getAsJsonObject(); String type=text(c,"type");
            if((number(c,"upgrades",0)==0 || text(c,"id").equals("Searing Blow"))
                    && (type.equals("ATTACK")||type.equals("SKILL")||type.equals("POWER")))count++;
        }
        upgrades=number(visible,"route_upgrades",count);
        JsonObject keys=object(game,"keys");
        wantGreen=act==3&&!flag(keys,"emerald"); wantRed=act==3&&!flag(keys,"ruby");
        JsonArray burning=array(visible,"burning_elites");
        flame=burning.size()==0?-1:key(number(burning.get(0).getAsJsonObject(),"x",-1),number(burning.get(0).getAsJsonObject(),"y",-1));
        if(weights==null || row<0 || row>14)return;
        List<Integer> starts=new ArrayList<>();
        JsonObject screen=object(game,"screen_state");
        if(flag(screen,"boss_available"))return;
        if(text(game,"screen_type").equals("MAP") && screen.has("next_nodes")) {
            for(JsonElement e:array(screen,"next_nodes")) starts.add(number(e.getAsJsonObject(),"x",-1));
        } else if(row==0) {
            for(int x=0;x<7;x++)if(nodes.containsKey(key(x,0))&&array(nodes.get(key(x,0)),"children").size()>0)starts.add(x);
        } else for(JsonElement e:array(nodes.get(key(number(visible,"x",-1),row-1)),"children"))
            starts.add(number(e.getAsJsonObject(),"x",-1));
        for(int x:starts)walk(x,row,new ArrayList<OutsidePacket.Step>(),x);
    }

    private void walk(int x,int y,List<OutsidePacket.Step> path,int start) {
        JsonObject n=nodes.get(key(x,y)); if(n==null)return;
        path.add(new OutsidePacket.Step(x,y,1)); // deterministic rule, not a probability
        if(y==14) {
            boolean green=false,red=false;
            for(OutsidePacket.Step p:path) {green|=key(p.x,p.y)==flame; red|=room(p).equals("R");}
            Plan plan=new Plan(path,score(path),(wantGreen&&!green?1:0)+(wantRed&&!red?1:0));
            if(plan.beats(byStart.get(start)))byStart.put(start,plan);
        } else for(JsonElement e:array(n,"children")) {
            JsonObject child=e.getAsJsonObject();
            if(number(child,"y",-1)==y+1)walk(number(child,"x",-1),y+1,path,start);
        }
        path.remove(path.size()-1);
    }

    Plan best(int firstX) {
        if(firstX>=0)return byStart.get(firstX);
        Plan best=null; for(Plan p:byStart.values())if(p.beats(best))best=p;
        return best;
    }

    private String room(OutsidePacket.Step p) {return text(nodes.get(key(p.x,p.y)),"symbol");}
    private double score(List<OutsidePacket.Step> path) {
        int elite=0,fire=0,event=0,monster=0,prepare=0,preFire=0,shop=0,close=0,lastShop=-99;
        double early=0,late=0;
        int money=number(game,"gold",0),base=number(visible,"purge_base_cost",75);
        int cost=relics.contains("Smiling Mask")?50:(int)Math.floor(base*(relics.contains("Membership Card")?.5:relics.contains("The Courier")?.8:1)+.5);
        for(OutsidePacket.Step p:path) {
            String room=room(p);
            if(room.equals("E"))elite++;
            if(room.equals("R")) {fire++; if(elite==0)preFire=1;}
            if(room.equals("?"))event++;
            if(room.equals("M")) {monster++; if(elite==0)prepare++;}
            if(room.equals("$")) {
                if(p.y-lastShop<=3)close++; lastShop=p.y;
                int spend=money>=150?150:cost;
                if(money>=spend) {money-=spend;shop++;early+=(14-p.y)/14.;late+=p.y/14.;}
            }
        }
        int available=Math.max(0,fire-(wantRed?1:0));
        int fires=Math.max(canSmith?Math.min(available,upgrades):0,
            canRest&&number(game,"current_hp",0)<number(game,"max_hp",0)?Math.min(available,1):0);
        // Card sightings (fights) before the first elite count at every row, minus those already seen this act.
        JsonObject history=object(game,"solver_act_prep");
        int seen=row==0?0:number(history,"seen",game.has("solver_act_prep")?0:99);
        if(elite==0||flag(history,"elite")) prepare=0; else prepare=Math.min(prepare,Math.max(0,(act==1?3:2)-seen));
        if(row!=0||elite==0) preFire=0;
        return w("elite")*elite+w("fire")*fires+w("event")*event+w("monster")*monster
            +w("prepare")*prepare+w("pre_fire")*preFire+w("shop")*shop
            +w("early_shop")*early+w("late_shop")*late+w("close_shops")*close
            +w("low_hp_elites")*elite*Math.max(0.,1.-2.*number(game,"current_hp",0)/Math.max(1,number(game,"max_hp",1)));
    }

    private double w(String name) {return weights.get(name).getAsDouble();}
    private static JsonObject loadRules() {
        try(InputStream input=RoutePlanner.class.getResourceAsStream("/route-preferences.json")) {
            if(input==null)throw new IOException("Missing route preferences");
            return new JsonParser().parse(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(IOException e) {throw new IllegalStateException(e);}
    }
    private static int key(int x,int y) {return y*7+x;}
    private static int number(JsonObject o,String k,int fallback) {return o!=null&&o.has(k)?o.get(k).getAsInt():fallback;}
    private static String text(JsonObject o,String k) {return o!=null&&o.has(k)?o.get(k).getAsString():"";}
    private static boolean flag(JsonObject o,String k) {return o!=null&&o.has(k)&&o.get(k).getAsBoolean();}
    private static JsonObject object(JsonObject o,String k) {return o!=null&&o.has(k)?o.getAsJsonObject(k):new JsonObject();}
    private static JsonArray array(JsonObject o,String k) {return o!=null&&o.has(k)?o.getAsJsonArray(k):new JsonArray();}
}
