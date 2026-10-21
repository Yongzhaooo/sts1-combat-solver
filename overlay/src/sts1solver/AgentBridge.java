package sts1solver;

import static sts1solver.CombatReadiness.combat;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Agent play (AGENT_PLAY.md): outside choices come from a file an external agent writes; fights run automatically.
 * Writes agent/decision.json whenever the outside choice or the run status changes, and applies
 * agent/command.txt: "<decision> <index>" confirms one listed choice, "resume" re-enters agent mode.
 * The confirmed choice goes through the same checks as a click on the panel.
 */
final class AgentBridge {
    private final Gson gson = new Gson();
    private long decision;
    private OutsidePacket packet;
    private String error = "", written = "";
    private Path directory;

    /** Returns true when decision.json was rewritten. Failures are retried on the next call. */
    boolean sync(OutsidePilot pilot, boolean runAuto, String rngWarning, Runnable startAgentMode) throws IOException {
        if(directory==null)directory=Files.createDirectories(RunExports.dataDirectory("agent"));
        Path command=directory.resolve("command.txt");
        if(Files.exists(command)) {
            String text=new String(Files.readAllBytes(command),StandardCharsets.UTF_8).trim();
            Files.delete(command);
            int index=pilot.pending()?RunMode.agentChoice(text,decision,pilot.packet.choices.size()):-1;
            if(text.equals("resume") && SolverMod.inSupportedRun()) { startAgentMode.run(); error=""; }
            else if(index>=0) { pilot.chosen=index; pilot.confirm=true; error=""; }
            else error="ignored command (stale decision or invalid index): "+text;
        }
        boolean pending=pilot.pending();
        if(pending && pilot.packet!=packet) { packet=pilot.packet; decision++; }
        String phase=!SolverMod.inSupportedRun()?"no_run":AbstractDungeon.player.isDead?"dead":combat()?"combat":pending?"choose":"waiting";
        boolean agentMode=pilot.mode==1 && runAuto;
        String key=(pending?decision:0)+"|"+phase+"|"+agentMode+"|"+pilot.notice+"|"+error+"|"+rngWarning;
        if(key.equals(written))return false; // the full packet is rebuilt only when this summary changes
        JsonObject out=new JsonObject();
        out.addProperty("decision",pending?decision:0);
        out.addProperty("agent_mode",agentMode);
        out.addProperty("phase",phase);
        out.addProperty("notice",pilot.notice);
        out.addProperty("error",error);
        JsonArray warnings=new JsonArray();  // what the settings page shows, for an agent that cannot see the panel
        if(Loader.isModLoaded("SaveStateMod") || Loader.isModLoaded("undothespire") || Loader.isModLoaded("undobutton"))
            warnings.add(I18n.t("检测到不兼容 Mod（SaveStateMod / Undo）：会破坏战斗选牌与状态同步，建议禁用"));
        if(!rngWarning.isEmpty())warnings.add(rngWarning);
        out.add("mod_warnings",warnings);
        if(pending) {
            JsonObject game=new JsonObject();
            for(String field:new String[]{"class","ascension_level","act","floor","current_hp","max_hp","gold","act_boss",
                    "room_type","screen_type","screen_state","deck","relics","potions","keys","map"})
                if(pilot.packet.game.has(field))game.add(field,pilot.packet.game.get(field));
            out.add("game",game);
            out.add("visible",pilot.packet.visible);
            JsonArray choices=new JsonArray();
            for(int i=0;i<pilot.packet.choices.size();i++) {
                JsonObject choice=new JsonObject();
                choice.addProperty("index",i);
                choice.addProperty("label",pilot.packet.choices.get(i).label);
                choice.addProperty("solver_score",pilot.scores.scores[i]);
                choices.add(choice);
            }
            out.add("choices",choices);
            out.addProperty("solver_pick",pilot.scores.best);
            if(pilot.packet.ruleNote!=null)out.addProperty("solver_rule",pilot.packet.ruleNote);
        }
        Path temp=directory.resolve("decision.json.tmp");
        Files.write(temp,gson.toJson(out).getBytes(StandardCharsets.UTF_8));
        Files.move(temp,directory.resolve("decision.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        written=key;
        return true;
    }
}
