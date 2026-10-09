package sts1solver;

import com.evacipated.cardcrawl.modthespire.lib.*;
import com.evacipated.cardcrawl.modthespire.Loader;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapRoomNode;
import communicationmod.GameStateConverter;
import communicationmod.ChoiceScreenUtils;
import com.megacrit.cardcrawl.actions.unique.DiscoveryAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import java.util.*;

@SpirePatch(clz=GameStateConverter.class, method="getGameState")
public class RecoveryExportPatch {
    @SpirePostfixPatch public static HashMap<String,Object> after(HashMap<String,Object> __result) {
        ArrayList<String> rooms=new ArrayList<>();
        if(AbstractDungeon.getCurrMapNode()!=null && AbstractDungeon.map!=null)
            for(MapRoomNode node:ChoiceScreenUtils.getMapScreenNodeChoices())rooms.add(node.getRoomSymbol(true));
        __result.put("solver_next_rooms",rooms);
        try {
            // This act's path so far: fights seen (card rewards) and whether an elite is already behind us.
            int seen=0; boolean elite=false;
            for(int i=0;i<AbstractDungeon.pathX.size() && i<AbstractDungeon.pathY.size();i++) {
                int y=AbstractDungeon.pathY.get(i);
                if(y<0 || y>=AbstractDungeon.map.size())continue;
                com.megacrit.cardcrawl.rooms.AbstractRoom room=AbstractDungeon.map.get(y).get(AbstractDungeon.pathX.get(i)).room;
                if(room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomElite)elite=true;
                else if(room instanceof com.megacrit.cardcrawl.rooms.MonsterRoom && !(room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss))seen++;
            }
            HashMap<String,Object> prep=new HashMap<>();
            prep.put("seen",seen); prep.put("elite",elite);
            __result.put("solver_act_prep",prep);
        } catch(RuntimeException ignored) { }
        if(AbstractDungeon.getCurrRoom()!=null && AbstractDungeon.getCurrRoom().event!=null)
            __result.put("solver_event_class",AbstractDungeon.getCurrRoom().event.getClass().getSimpleName());
        __result.put("solver_final_act",com.megacrit.cardcrawl.core.Settings.isFinalActAvailable);
        if(AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.RestRoom && AbstractDungeon.getCurrMapNode()!=null)
            __result.put("solver_rest_left",Foresight.remainingCampfires(AbstractDungeon.map,AbstractDungeon.getCurrMapNode(),new IdentityHashMap<>()));
        HashMap<String,ArrayList<String>> pools=new HashMap<>();
        for(String type:new String[]{"ALL","ATTACK","SKILL","POWER","COLORLESS"})pools.put(type,new ArrayList<>());
        for(CardGroup group:new CardGroup[]{AbstractDungeon.srcCommonCardPool,AbstractDungeon.srcUncommonCardPool,AbstractDungeon.srcRareCardPool})
            for(AbstractCard card:group.group)if(!card.hasTag(AbstractCard.CardTags.HEALING)) {
                pools.get("ALL").add(card.cardID);
                if(pools.containsKey(card.type.name()))pools.get(card.type.name()).add(card.cardID);
            }
        for(AbstractCard card:AbstractDungeon.srcColorlessCardPool.group)
            if(!card.hasTag(AbstractCard.CardTags.HEALING))pools.get("COLORLESS").add(card.cardID);
        __result.put("solver_card_pools",pools);
        // SaveStateMod replaces Discovery.update and only generates the opening choices.
        // Export this before planning so potion branches use the live rule from the root.
        __result.put("solver_discovery_reroll",!Loader.isModLoaded("SaveStateMod"));
        if(AbstractDungeon.isScreenUp && AbstractDungeon.getCurrRoom().phase
                == com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT)
            __result.put("solver_selection",SelectionExport.snapshot());
        if(AbstractDungeon.actionManager.currentAction instanceof DiscoveryAction) {
            HashMap<String,Object> choice=new HashMap<>();
            choice.put("copies",AbstractDungeon.actionManager.currentAction.amount);
            choice.put("queue_empty",AbstractDungeon.actionManager.actions.isEmpty()
                && AbstractDungeon.actionManager.cardQueue.isEmpty());
            __result.put("solver_discovery",choice);
        }
        return __result;
    }
}
