// Bounded T1 policy candidates. Java histories authorize entry; actions keep their real effects.
#pragma once
#include <functional>
#include <tuple>

struct SmokeOpeningPlan {
    BattleContext state;
    std::vector<int> actions;
    int prefixes=0;
    bool capped=false;
    explicit SmokeOpeningPlan(const BattleContext &root): state(root) {}
};

static SmokeOpeningPlan smokeOpeningPlan(const BattleContext &root,int smokeSlot,const std::vector<int> &slots) {
    SmokeOpeningPlan best(root);
    search::Action smoke(search::ActionType::POTION,smokeSlot,0);
    if(!smoke.isValidAction(root))throw std::invalid_argument("Smoke Bomb cannot escape this encounter");
    smoke.execute(best.state);
    best.actions.push_back(static_cast<int>(smoke.bits));
    bool reaper=false;
    std::array<int,10> original{};
    for(int i=0;i<root.cards.cardsInHand;++i) {
        original[i]=root.cards.hand[i].uniqueId;
        reaper |= root.cards.hand[i].id==CardId::REAPER;
    }
    if(!reaper)return best;
    auto hp=[](const BattleContext &b) { return b.victoryHpRelics.project(b.player.curHp,b.player.maxHp).curHp; };
    auto rank=[&](const BattleContext &b,const std::vector<int> &actions) {
        return std::make_tuple(hp(b),b.outcome==Outcome::PLAYER_VICTORY,b.potionCount,-static_cast<int>(actions.size()));
    };
    auto offer=[&](const BattleContext &b,const std::vector<int> &actions) {
        // An optional heal before a safe escape must not spend passive revival reserves.
        if(fairyCount(b)<fairyCount(root) || (root.player.hasRelic<RelicId::LIZARD_TAIL>()
                && !b.player.hasRelic<RelicId::LIZARD_TAIL>()))return;
        if(rank(b,actions)>rank(best.state,best.actions)) {
            best.state=b; best.actions=actions;
        }
    };
    std::function<void(const BattleContext &,unsigned,std::vector<int>)> visit;
    visit=[&](const BattleContext &b,unsigned used,std::vector<int> prefix) {
        // shortcut: at most 4096 strength prefixes, expand only if actual hands need more.
        if(best.prefixes>=4096){best.capped=true;return;}
        ++best.prefixes;
        if(overlayInterrupted() || b.turn!=root.turn || b.outcome!=Outcome::UNDECIDED
                || b.player.curHp<=0 || b.inputState!=InputState::PLAYER_NORMAL)return;
        search::BattleScumSearcher2 enumerator(b);
        search::RolloutNode node;
        enumerator.preservePotions=true;
        enumerator.enumerateActionsForNode(node,b);
        for(const auto &edge:node.edges) {
            const auto &action=edge.action;
            bool harvest=false, strength=false;
            unsigned bit=0;
            if(action.getActionType()==search::ActionType::CARD) {
                const auto &card=b.cards.hand[action.getSourceIdx()];
                for(int i=0;i<root.cards.cardsInHand;++i)if(original[i]==card.uniqueId)bit=1u<<i;
                if(!bit || (used&bit))continue;
                harvest=card.id==CardId::REAPER;
                strength=card.id==CardId::FLEX || card.id==CardId::INFLAME
                    || card.id==CardId::SPOT_WEAKNESS || card.id==CardId::LIMIT_BREAK || card.id==CardId::JAX;
            } else if(action.getActionType()==search::ActionType::POTION) {
                int slot=action.getSourceIdx();
                if(std::find(slots.begin(),slots.end(),slot)==slots.end())continue;
                bit=1u<<(10+slot);
                if(used&bit)continue;
                strength=b.potions[slot]==Potion::STRENGTH_POTION || b.potions[slot]==Potion::FLEX_POTION;
            }
            if(!harvest && !strength)continue;
            BattleContext next(b);
            action.execute(next);
            auto actions=prefix;
            actions.push_back(static_cast<int>(action.bits));
            if(harvest) {
                if(next.player.curHp<=0)continue;
                if(next.outcome==Outcome::PLAYER_VICTORY){offer(next,actions);continue;}
                if(next.outcome!=Outcome::UNDECIDED || next.turn!=root.turn || !smoke.isValidAction(next))continue;
                smoke.execute(next);
                actions.push_back(static_cast<int>(smoke.bits));
                if(next.outcome==Outcome::PLAYER_ESCAPE)offer(next,actions);
            } else if(next.player.getStatus<PS::STRENGTH>()>b.player.getStatus<PS::STRENGTH>()) {
                visit(next,used|bit,std::move(actions));
            }
        }
    };
    visit(root,0,{});
    return best;
}
