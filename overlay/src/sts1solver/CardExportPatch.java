package sts1solver;

import com.evacipated.cardcrawl.modthespire.lib.*;
import com.megacrit.cardcrawl.cards.AbstractCard;
import communicationmod.GameStateConverter;
import java.util.HashMap;

/** The native importer needs rule values that stock CommunicationMod omits. */
@SpirePatch(clz=GameStateConverter.class, method="convertCardToJson")
public class CardExportPatch {
    @SpirePostfixPatch
    public static HashMap<String,Object> after(HashMap<String,Object> __result, AbstractCard card) {
        __result.put("base_cost",card.cost);
        __result.put("free_to_play_once",card.freeToPlayOnce);
        __result.put("base_damage",card.baseDamage);
        __result.put("base_block",card.baseBlock);
        __result.put("base_magic_number",card.baseMagicNumber);
        __result.put("damage",card.damage);
        __result.put("block",card.block);
        __result.put("magic_number",card.magicNumber);
        __result.put("cost_modified_for_turn",card.isCostModifiedForTurn);
        __result.put("target",card.target.name());
        __result.put("misc",card.misc);
        return __result;
    }
}
