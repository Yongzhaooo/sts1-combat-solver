package sts1solver;

import static sts1solver.CombatReadiness.combat;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import communicationmod.CommandExecutor;
import communicationmod.GameStateListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Outside-combat decisions: scores each choice with the solver rules and the network, runs it after 600 ms
 * in AI auto mode (2) or after the player confirms in step mode (1), plans the route drawn on the map,
 * and draws the compact pilot card that replaces the panel outside combat.
 */
final class OutsidePilot {
    /** What the pilot needs from the panel that owns the combat automation. */
    interface Host {
        JsonObject capture();
        /** Adds an executed outside action to the backend diagnostics. */
        void record(String message);
        /** A new run (or a reload) was detected. */
        void runStarted();
        /** An automatic outside action failed; combat automation stops as well. */
        void failed();
    }

    static final float PILOT_WIDTH = 440, PILOT_LIST_TOP = 72, PILOT_ROW = 30, NOTICE_ROW = 20;
    static final float EXPORT_LEFT = PILOT_WIDTH-240, EXPORT_WIDTH = 64;  // header button between the title and the mode chip
    static final int PILOT_ROWS = 5;
    static final int NO_BUTTON = Integer.MIN_VALUE;

    int mode; // 0: off, 1: confirm each outside action, 2: auto pilot.
    boolean prompt, confirm;
    boolean pilotRun, failed; // the compact pilot card replaces the panel outside combat.
    String notice = "";
    private int noticeRows = 1; // set while drawing; the list and buttons sit below the wrapped notice
    OutsidePacket packet;
    Distill2.Result scores;
    int chosen;
    List<OutsidePacket.Step> routePlan = java.util.Collections.emptyList();

    private Distill2 model;
    private OutsidePacket statePacket;
    private AbstractPlayer player;
    private boolean awaiting;
    private int scroll;
    private long scoredAt, submittedAt, waitSince;
    private int retries;
    private String routeKey = "";
    private int skippedCardFloor = -1, skippedCards;
    private boolean shopLeft;
    private com.badlogic.gdx.graphics.g2d.TextureRegion routePixel;

    /** F9: drop the pending decision and hand outside decisions to the player (Standby has no card). */
    void stop() {
        prompt=false; packet=null; statePacket=null; scores=null;
        confirm=false; awaiting=false; failed=false;
        mode=0; pilotRun=false;
        notice=I18n.t("已停止 · 待命：局外由你决定，战斗点【计算】才搜索");
    }

    void select(int newMode) {
        prompt=false; mode=newMode; packet=null; statePacket=null; scores=null;
        confirm=false; awaiting=false; failed=false;
        if(newMode==0) { pilotRun=false; notice=I18n.t("局外决策由你操作"); return; }
        pilotRun=true;
        notice=newMode==1?I18n.t("Agent 确认每一步局外选择")
            :I18n.t("全自动爬塔已开启 · F9 随时接管");
    }

    /** Stops after a failure and tells the player to take over. */
    void pause(String message) {
        mode=0; packet=null; scores=null; awaiting=false; failed=true;
        notice=I18n.t("全自动暂停，请手动接管：")+message;
    }

    /** The card's × button: back to the full panel, without suggestions. */
    void close() {
        mode=0; packet=null; scores=null; confirm=false;
        pilotRun=false;
    }

    /** A step-mode choice is listed and waits for the player (or the agent) to confirm it. */
    boolean pending() {
        return mode==1 && packet!=null && scores!=null && !confirm && !combat();
    }

    /**
     * The original game keeps a skipped card reward listed and lets the merchant be reopened;
     * remember both per floor so the pilot moves on instead of looping.
     */
    private JsonObject visible() throws Exception {
        if(skippedCardFloor!=AbstractDungeon.floorNum) {
            skippedCardFloor=AbstractDungeon.floorNum; skippedCards=0; shopLeft=false;
        }
        JsonObject visible=DecisionContext.visible();
        visible.addProperty("skipped_card_rewards",skippedCards);
        visible.addProperty("shop_left",shopLeft);
        return visible;
    }

    /** One frame: notice a new run, score a new decision, and execute it when the mode allows. */
    void update(Host host, boolean rewardPending) {
        if(!CommandExecutor.isInDungeon() || AbstractDungeon.player==null || !SolverMod.supported()) {
            // Act transitions leave the dungeon for a few frames; keep the chosen mode. A new run
            // (or a reload) brings a new player object, which asks again below.
            packet=null; statePacket=null; scores=null; awaiting=false; return;
        }
        if(player!=AbstractDungeon.player) {
            player=AbstractDungeon.player; prompt=true; mode=0;
            packet=null; statePacket=null; scores=null; awaiting=false;
            pilotRun=false; failed=false;
            notice=I18n.t("新一局：选【待命】每场点了才算，【战斗全自动】只自动打战斗，或【AI全自动】完全托管");
            host.runStarted();
        }
        if(prompt) return;
        if(mode==0 || combat() || AbstractDungeon.player.isDead || rewardPending) return;
        if(!GameStateListener.isWaitingForCommand()) return;
        try {
            JsonObject state=host.capture();
            if(!state.get("ready_for_command").getAsBoolean())return;
            JsonObject visible=visible();
            if(model==null)model=Distill2.load();
            OutsidePacket latest=OutsidePacket.build(model,state,visible);
            waitSince=0;
            if(awaiting) {
                if(latest.sameDecision(statePacket)) {
                    long waited=System.currentTimeMillis()-submittedAt;
                    // A click on a button that is still fading in is ignored by the game: try again.
                    if(mode==2 && waited>3000 && retries<3) {
                        retries++; awaiting=false; return;
                    }
                    if(waited>10000)
                        throw new IllegalStateException("局外动作未完成；请手动接管");
                    return;
                }
                awaiting=false; retries=0;
            }
            if(packet==null || !latest.sameDecision(packet)) {
                packet=latest;
                scores=packet.policyScore();
                statePacket=packet; scoredAt=System.currentTimeMillis();
                confirm=false; chosen=scores.best; scroll=0;
            }
            if((mode==2 && System.currentTimeMillis()-scoredAt>=600)
                    || (mode==1 && confirm)) {
                OutsidePacket.Choice choice=packet.choices.get(chosen);
                if(choice.label!=null && (choice.label.contains("Prismatic") || choice.label.contains("棱镜") || choice.label.contains("PrismaticShard")))
                    throw new IllegalStateException("检测到棱镜（Prismatic Shard），策略禁止自动获取，请手动确认！");
                String command=choice.command, verb=command.split(" ")[0];
                if(!CommandExecutor.isCommandAvailable(verb))
                    throw new IllegalStateException("局外命令已不可用："+command);
                if(verb.equals("choose")) {
                    int index=Integer.parseInt(command.substring(7));
                    if(index<0 || index>=communicationmod.ChoiceScreenUtils.getCurrentChoiceList().size())
                        throw new IllegalStateException("局外候选已变化："+command);
                }
                JsonObject again=host.capture();
                if(!again.get("ready_for_command").getAsBoolean()
                        || !OutsidePacket.build(model,again,visible())
                            .sameDecision(packet)) {
                    packet=null; scores=null; return;
                }
                if(!CommandExecutor.executeCommand(command))
                    throw new IllegalStateException("局外动作未提交："+command);
                GameStateListener.registerCommandExecution();
                String screenType=visible.get("screen_type").getAsString();
                if(verb.equals("skip") && screenType.equals("CARD_REWARD"))skippedCards++;
                if(verb.equals("leave") && screenType.equals("SHOP_SCREEN"))shopLeft=true;
                host.record(screenType+" | "+choice.label+" | "+command
                    +" | "+packet.detail()+(packet.ruleNote==null?" | 网络":" | 规则："+packet.ruleNote)
                    +(packet.prereadNote==null?"":" | 预读："+packet.prereadNote));
                notice=I18n.t("已执行：")+choice.label
                    +(packet.ruleNote==null?"":" · "+I18n.t(packet.ruleNote));
                packet=null; scores=null; confirm=false;
                awaiting=true; submittedAt=System.currentTimeMillis();
            }
        } catch(Exception failure) {
            long now=System.currentTimeMillis();
            if(failure instanceof OutsidePacket.NotReady) {
                if(waitSince==0)waitSince=now;
                if(now-waitSince<10000)return; // UI transition frames settle within a few hundred ms.
            }
            waitSince=0;
            pause(failure.getMessage());
            System.err.println("[STS1AutoPilot] "+failure);
            host.failed();
        }
    }

    /** Re-plan the drawn route whenever the map is open and the run state or chosen next node moved. */
    void updateRoute(Host host) {
        if(!CommandExecutor.isInDungeon() || !SolverMod.supported() || AbstractDungeon.map==null
                || AbstractDungeon.screen!=AbstractDungeon.CurrentScreen.MAP)return;
        int firstX=packet==null?-1:packet.mapTargetX(chosen);
        com.megacrit.cardcrawl.map.MapRoomNode at=AbstractDungeon.getCurrMapNode();
        int atX=at==null?0:at.x, atY=at==null?-1:at.y;
        AbstractPlayer current=AbstractDungeon.player;
        if(current==null)return;
        StringBuilder key=new StringBuilder().append(AbstractDungeon.actNum).append('/')
            .append(AbstractDungeon.floorNum).append('/').append(atX).append(',').append(atY).append('/')
            .append(firstX).append('/').append(current.currentHealth).append('/').append(current.maxHealth)
            .append('/').append(current.gold).append('/').append(current.masterDeck.size()).append('/')
            .append(current.relics.size());
        key.append('/').append(Settings.hasEmeraldKey).append('/').append(Settings.hasRubyKey);
        for(AbstractCard card:current.masterDeck.group)key.append('/').append(card.cardID).append('+').append(card.timesUpgraded);
        for(com.megacrit.cardcrawl.relics.AbstractRelic relic:current.relics)key.append('/').append(relic.relicId);
        for(com.megacrit.cardcrawl.potions.AbstractPotion potion:current.potions)key.append('/').append(potion.ID);
        if(key.toString().equals(routeKey))return;
        // Recalculate only when public route inputs change; no simulated combat or future rewards.
        routeKey=key.toString();
        try {
            if(model==null)model=Distill2.load();
            routePlan=OutsidePacket.plannedRoute(model,host.capture(),DecisionContext.visible(),firstX);
            if(routePlan.isEmpty())routeKey=""; // Allow retry once map state or nodes become available
        } catch(Exception failure) {
            routePlan=java.util.Collections.emptyList();
            routeKey=""; // Clear key so next frame retries
            System.err.println("[STS1AutoPilot] route: "+failure);
        }
    }

    void renderRoute(SpriteBatch sb, PanelCanvas canvas) {
        if(routePlan.isEmpty() || AbstractDungeon.screen!=AbstractDungeon.CurrentScreen.MAP
                || AbstractDungeon.map==null)return;
        if(routePixel==null)routePixel=new com.badlogic.gdx.graphics.g2d.TextureRegion(ImageMaster.WHITE_SQUARE_IMG);
        List<com.megacrit.cardcrawl.map.MapRoomNode> nodes=new ArrayList<>();
        com.megacrit.cardcrawl.map.MapRoomNode current=AbstractDungeon.getCurrMapNode();
        if(current!=null && current.y>=0 && current.y<AbstractDungeon.map.size())nodes.add(current);
        int from=nodes.size();
        for(OutsidePacket.Step step:routePlan) {
            if(step.y<0 || step.y>=AbstractDungeon.map.size() || step.x<0
                    || step.x>=AbstractDungeon.map.get(step.y).size())return;
            nodes.add(AbstractDungeon.map.get(step.y).get(step.x));
        }
        float s=Settings.scale;
        for(int i=1;i<nodes.size();i++) {
            com.megacrit.cardcrawl.map.MapRoomNode a=nodes.get(i-1), b=nodes.get(i);
            float angle=(float)Math.toDegrees(Math.atan2(b.hb.cY-a.hb.cY,b.hb.cX-a.hb.cX));
            float length=(float)Math.hypot(b.hb.cX-a.hb.cX,b.hb.cY-a.hb.cY);
            boolean next=i==from;
            sb.setColor(next?1f:.38f,next?.8f:.83f,next?.25f:.72f,.8f);
            sb.draw(routePixel,a.hb.cX,a.hb.cY-4*s,0,4*s,length,8*s,1,1,angle);
        }
        for(int i=from;i<nodes.size();i++) {
            com.megacrit.cardcrawl.map.MapRoomNode node=nodes.get(i);
            boolean next=i==from;
            float share=routePlan.get(i-from).share, size=(next?150:118)*s;
            // Rule routes have deterministic rings; brightness is not a survival probability.
            sb.setColor(next?1f:.38f,next?.8f:.83f,next?.25f:.72f,next?1f:.35f+.6f*share);
            sb.draw(ImageMaster.MAP_CIRCLE_5,node.hb.cX-size/2,node.hb.cY-size/2,size,size);
        }
        com.megacrit.cardcrawl.map.MapRoomNode first=nodes.get(from), last=nodes.get(nodes.size()-1);
        canvas.caption(sb,I18n.t("规则推荐下一步"),first.hb.cX+80*s,first.hb.cY+10*s,PanelCanvas.ACCENT);
        if(last!=first)canvas.caption(sb,I18n.t("开幕偏好规划 · 状态变化后可改道"),
            last.hb.cX+70*s,last.hb.cY+10*s,PanelCanvas.MUTED);
        sb.setColor(Color.WHITE);
    }

    /** Outside combat, a run with the pilot chosen (or the start-of-run question) gets the compact card. */
    boolean cardShown() {
        return (prompt || pilotRun) && CommandExecutor.isInDungeon() && !combat();
    }

    private int listRows() {
        return mode==1 && packet!=null && scores!=null
            ? Math.min(PILOT_ROWS,packet.choices.size()) : 0;
    }

    private float listTop() { return PILOT_LIST_TOP+(noticeRows-1)*NOTICE_ROW; }

    private float buttonsTop() {
        int rows=listRows();
        return listTop()+rows*PILOT_ROW+(rows>0?8:0);
    }

    float cardHeight() { return buttonsTop()+38+14; }

    /** Buttons as {action, label}; actions: 2 pilot, 0 take over, 3 confirm (agent play), -1 Standby, -2 Combat auto. */
    private String[][] buttons() {
        if(prompt) return new String[][]{{"-1","待命"},{"-2","战斗全自动"},{"2","AI全自动"}};
        if(mode==2) return new String[][]{{"0","待命"}};
        if(mode==1) return new String[][]{{"3","确认（回车）"},{"-2","战斗全自动"},{"2","AI全自动"}};
        return new String[][]{{"2","继续AI全自动"},{"-1","待命"}};
    }

    /** Scrolls or picks in the step-mode list; true when the pointer is over the list. */
    boolean listInput(float ly, boolean click) {
        int rows=listRows();
        if(rows<=0 || ly<listTop() || ly>=listTop()+rows*PILOT_ROW)return false;
        int last=Math.max(0,packet.choices.size()-rows);
        if(InputHelper.scrolledDown)scroll=Math.min(last,scroll+1);
        if(InputHelper.scrolledUp)scroll=Math.max(0,scroll-1);
        if(click) {
            int index=scroll+(int)((ly-listTop())/PILOT_ROW);
            if(index<packet.choices.size())chosen=index;
        }
        return true;
    }

    /** The action of the button under card point (lx, ly), or NO_BUTTON. */
    int buttonAt(float lx, float ly) {
        float top=buttonsTop();
        if(ly<top || ly>top+38)return NO_BUTTON;
        String[][] buttons=buttons();
        float width=(PILOT_WIDTH-32-(buttons.length-1)*8)/buttons.length;
        int slot=(int)((lx-16)/(width+8));
        if(lx<16 || slot<0 || slot>=buttons.length || lx-16-slot*(width+8)>width)return NO_BUTTON;
        return Integer.parseInt(buttons[slot][0]);
    }

    void renderCard(SpriteBatch sb, PanelCanvas canvas) {
        float height=cardHeight();
        canvas.box(sb,0,0,PILOT_WIDTH,height,PanelCanvas.BORDER);
        canvas.box(sb,1,1,PILOT_WIDTH-2,height-2,PanelCanvas.BACKGROUND);
        canvas.label(sb,I18n.t("自动爬塔"),16,10,140,1.05f,Color.WHITE);
        canvas.button(sb,EXPORT_LEFT,9,EXPORT_WIDTH,24,PanelCanvas.SURFACE);
        canvas.label(sb,I18n.t("导出"),EXPORT_LEFT+8,13,EXPORT_WIDTH-12,.78f,PanelCanvas.ACCENT);
        String chip=prompt?"新一局":mode==2?"AI全自动中":mode==1?"Agent"
            :failed?"已暂停":"已接管";
        Color chipColor=mode==2?PanelCanvas.ACCENT:failed?PanelCanvas.DANGER:mode==1?Color.WHITE:PanelCanvas.MUTED;
        canvas.box(sb,PILOT_WIDTH-170,9,120,24,PanelCanvas.SURFACE);
        canvas.label(sb,I18n.t(chip),PILOT_WIDTH-160,13,100,.78f,chipColor);
        if(!prompt)canvas.label(sb,"×",PILOT_WIDTH-32,9,20,1,PanelCanvas.MUTED);
        java.util.List<String> lines=canvas.wrap(notice,PILOT_WIDTH-32,.78f);
        noticeRows=Math.max(1,lines.size());
        for(int i=0;i<lines.size();i++)
            canvas.label(sb,lines.get(i),16,46+i*NOTICE_ROW,PILOT_WIDTH-32,.78f,failed?PanelCanvas.DANGER:PanelCanvas.MUTED);
        int rows=listRows();
        for(int row=0;row<rows;row++) {
            int i=scroll+row;
            if(i>=packet.choices.size())break;
            float top=listTop()+row*PILOT_ROW;
            boolean selected=i==chosen;
            canvas.button(sb,16,top,PILOT_WIDTH-32,PILOT_ROW-4,selected?PanelCanvas.ACCENT_SOFT:PanelCanvas.SURFACE);
            canvas.label(sb,(selected?"● ":"  ")+packet.choices.get(i).label,24,top+5,PILOT_WIDTH-130,.8f,
                selected?PanelCanvas.ACCENT:Color.WHITE);
            canvas.label(sb,String.format(java.util.Locale.ROOT,"%.3f",scores.scores[i]),
                PILOT_WIDTH-86,top+5,62,.76f,PanelCanvas.MUTED);
        }
        String[][] buttons=buttons();
        float top=buttonsTop(), width=(PILOT_WIDTH-32-(buttons.length-1)*8)/buttons.length;
        for(int b=0;b<buttons.length;b++) {
            int action=Integer.parseInt(buttons[b][0]);
            boolean primary=b==0 && !(action==3 && packet==null);
            float left=16+b*(width+8);
            canvas.button(sb,left,top,width,38,primary?PanelCanvas.ACCENT:PanelCanvas.SURFACE);
            canvas.label(sb,I18n.t(buttons[b][1]),left+12,top+9,width-20,.9f,
                primary?PanelCanvas.BACKGROUND:action==0?PanelCanvas.DANGER:action==3?PanelCanvas.MUTED:Color.WHITE);
        }
        sb.setColor(Color.WHITE);
    }
}
