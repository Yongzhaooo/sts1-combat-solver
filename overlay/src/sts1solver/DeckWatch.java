package sts1solver;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import communicationmod.CommandExecutor;
import java.util.*;

/** Diffs the master deck each frame: obtained, removed and upgraded cards flash under the deck icon for one real second. */
final class DeckWatch {
    // uuid -> card and its upgrade count
    private final Map<UUID,AbstractCard> cards = new HashMap<>();
    private final Map<UUID,Integer> upgrades = new HashMap<>();
    private AbstractPlayer player;
    private final List<AbstractCard> shown = new ArrayList<>();
    private final List<String> verbs = new ArrayList<>();
    private long shownUntil;

    /** Changes are flashed only while {@code show} holds (the pilot is playing the run). */
    void update(boolean show) {
        AbstractPlayer current=CommandExecutor.isInDungeon()?AbstractDungeon.player:null;
        if(current==null)return;
        boolean fresh=current!=player; // a new run or a reload: take a silent snapshot.
        if(fresh) { player=current; cards.clear(); upgrades.clear(); }
        long now=System.currentTimeMillis();
        if(now>shownUntil) { shown.clear(); verbs.clear(); }
        Set<UUID> present=new HashSet<>();
        for(AbstractCard card:current.masterDeck.group) {
            present.add(card.uuid);
            Integer before=upgrades.put(card.uuid,card.timesUpgraded);
            cards.put(card.uuid,card);
            if(fresh)continue;
            if(before==null)show(card,"获得",now,show);
            else if(card.timesUpgraded>before)show(card,"强化",now,show);
        }
        for(Iterator<Map.Entry<UUID,AbstractCard>> it=cards.entrySet().iterator();it.hasNext();) {
            Map.Entry<UUID,AbstractCard> entry=it.next();
            if(present.contains(entry.getKey()))continue;
            if(!fresh)show(entry.getValue(),"删除",now,show);
            upgrades.remove(entry.getKey()); it.remove();
        }
    }

    private void show(AbstractCard card, String verb, long now, boolean show) {
        if(!show)return;
        AbstractCard copy=card.makeStatEquivalentCopy();
        copy.drawScale=copy.targetDrawScale=.5f;
        shown.add(copy); verbs.add(verb);
        shownUntil=now+1000;
    }

    void render(SpriteBatch sb, PanelCanvas canvas) {
        if(shown.isEmpty() || System.currentTimeMillis()>shownUntil || AbstractDungeon.topPanel==null)return;
        float s=Settings.scale, step=165*s;
        float cx=Math.min(AbstractDungeon.topPanel.deckHb.cX,Settings.WIDTH-85*s);
        float cy=AbstractDungeon.topPanel.deckHb.cY-AbstractDungeon.topPanel.deckHb.height/2-130*s;
        for(int i=0;i<shown.size();i++) {
            AbstractCard card=shown.get(i);
            card.current_x=card.target_x=cx-i*step;
            card.current_y=card.target_y=cy;
            card.render(sb);
            String verb=verbs.get(i);
            canvas.caption(sb,I18n.t(verb),card.current_x-24*s,cy+125*s,
                verb.equals("删除")?PanelCanvas.DANGER:verb.equals("强化")?PanelCanvas.ACCENT:Color.WHITE);
        }
        sb.setColor(Color.WHITE);
    }
}
