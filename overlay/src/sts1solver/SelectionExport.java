package sts1solver;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import communicationmod.GameStateConverter;
import java.lang.reflect.*;
import java.util.*;

/** Observed startup continuation for the existing native selection importer. */
final class SelectionExport {
    private static final Set<String> INPUTS=new HashSet<>(Arrays.asList("amount","energyGain",
        "target","source","powerToApply","this$0","cardToMake","randomSpot","damage","damageType","ID","owner"));
    static Map<String,Object> snapshot() {
        Map<String,Object> out=new HashMap<>();
        out.put("current_action",fields(AbstractDungeon.actionManager.currentAction));
        List<Object> queue=new ArrayList<>();
        for(AbstractGameAction action:AbstractDungeon.actionManager.actions)queue.add(fields(action));
        out.put("pending_actions",queue);
        return out;
    }

    static Map<String,Object> fields(Object object) {
        Map<String,Object> out=new HashMap<>();
        if(object==null)return out;
        out.put("class",object.getClass().getSimpleName());
        out.put("class_full",object.getClass().getName());
        try {
            for(Class<?> type=object.getClass();type!=null && type!=Object.class;type=type.getSuperclass())
                for(Field field:type.getDeclaredFields()) {
                    if(Modifier.isStatic(field.getModifiers()) || !INPUTS.contains(field.getName())
                            || out.containsKey(field.getName()))continue;
                    field.setAccessible(true);
                    Object value=field.get(object);
                    if(value instanceof String || value instanceof Number || value instanceof Boolean
                            || value instanceof int[])out.put(field.getName(),value);
                    else if(value instanceof Enum)out.put(field.getName(),((Enum<?>)value).name());
                    else if(value instanceof AbstractCreature)out.put(field.getName(),creature((AbstractCreature)value));
                    else if(value instanceof AbstractPower)out.put(field.getName(),fields(value));
                    else if(value instanceof AbstractRelic)out.put(field.getName(),((AbstractRelic)value).relicId);
                    else if(value instanceof AbstractCard) {
                        Method convert=GameStateConverter.class.getDeclaredMethod("convertCardToJson",AbstractCard.class);
                        convert.setAccessible(true);
                        out.put(field.getName(),convert.invoke(null,value));
                    }
                }
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("无法导出战斗选牌动作",failure);
        }
        return out;
    }

    private static Map<String,Object> creature(AbstractCreature value) {
        Map<String,Object> out=new HashMap<>();
        if(value==AbstractDungeon.player)out.put("kind","player");
        else {
            int index=AbstractDungeon.getMonsters().monsters.indexOf(value);
            if(index<0)throw new IllegalStateException("选牌队列目标不在当前战斗中");
            out.put("kind","monster");out.put("index",index);out.put("id",((AbstractMonster)value).id);
        }
        return out;
    }
}
