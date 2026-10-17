#include <tuple>
// Search user-authorized bottles (one or a set), including timing, without changing inventory.
#include <pybind11/pybind11.h>
#include <pybind11/stl.h>
#include <combat_search_reuse.h>
#include <array>
#include <atomic>
#include <map>
namespace py = pybind11;
using namespace sts;
namespace {
// Each potion branch searches on its own thread; per-call policy is thread-local.
thread_local unsigned allowedMask=0;          // bit i: the bottle in slot i may be used once
thread_local std::array<Potion,16> allowedPotion{};
thread_local std::array<Potion,16> initialPotion{};
thread_local bool allowGeneratedPotions=false;
thread_local int futureHeal=0, progressIndex=-1;
thread_local bool requireThiefKill=false;
thread_local bool heartFight=false, fastBoss=false, fastReady=false, initialTail=false;
thread_local int initialHp=0, initialFairies=0;
thread_local int initialTurn=0;
thread_local bool smokeProbe=false;
thread_local int smokeLossThreshold=15;
thread_local Random originalPotionRng;
// Set under the GIL before any search starts; searches only read them.
bool discoveryReroll=true;
unsigned incenseTargets=0;  // bit c: ending the fight with Incense Burner counter c is worth one Intangible
std::map<std::string,std::vector<CardId>> actualPools;
// Live counters per branch index, read by the progress reporter while plans run.
struct Progress { std::atomic<long long> simulations{0}, transitions{0}, transitionLimit{0};
    std::atomic<int> rounds{0}, turn{0}, bestHp{-1}, done{0}, phase{0}, budget{0}; };
std::array<Progress,32> progress;
// cancel() bumps the generation; older searches stop at their next replanning round.
std::atomic<int> generation{0};
std::atomic<bool> stopRequested{false};
thread_local int searchGeneration=0;
thread_local long long finishedRounds=0;  // simulations of completed replanning rounds
}
static Progress *live() {
    return progressIndex>=0 && progressIndex<static_cast<int>(progress.size()) ? &progress[progressIndex] : nullptr;
}
void overlayProgress(int rounds, long long simulations, int bestHp, int turn) {
    finishedRounds=simulations;
    if(auto *p=live()){ p->rounds=rounds; p->simulations=simulations; p->bestHp=bestHp; p->turn=turn; }
}
bool overlaySearchTick(long long done, int roundBestHp) {
    if(auto *p=live()){
        p->simulations=finishedRounds+done;
        if(roundBestHp>p->bestHp.load())p->bestHp=roundBestHp;
    }
    return stopRequested.load() || searchGeneration!=generation.load();
}
bool overlayInterrupted() { return stopRequested.load() || searchGeneration!=generation.load(); }
bool overlayFastReady() { return fastReady; }
bool overlayDiscoveryReroll() { return discoveryReroll; }
const std::vector<CardId> *overlayCardPool(CardType type) {
    const char *name=type==CardType::ATTACK?"ATTACK":type==CardType::SKILL?"SKILL":
        type==CardType::POWER?"POWER":type==CardType::STATUS?"COLORLESS":"ALL";
    auto found=actualPools.find(name);
    return found==actualPools.end()?nullptr:&found->second;
}
bool overlayPotionAllowed(const BattleContext &bc, int slot) {
    if(slot<0 || slot>=16)return false;
    // Smoke is an immediate opening policy branch, never a rollout action.
    if(bc.potions[slot]==Potion::SMOKE_BOMB)return false;
    if(allowGeneratedPotions && bc.potionRng.counter!=originalPotionRng.counter
            && (initialPotion[slot]==Potion::EMPTY_POTION_SLOT || bc.potions[slot]!=initialPotion[slot]))
        return true;
    if(!(allowedMask>>slot & 1u) || bc.potions[slot]!=allowedPotion[slot])return false;
    if(allowGeneratedPotions)return true;
    // A used slot is empty; only a potion generator (Entropic Brew, Alchemize) can refill it,
    // and it advances potionRng. Bottles appearing after that are never authorized, so with
    // several authorized slots any generated potion closes the remaining authorizations.
    bool single=(allowedMask & (allowedMask-1))==0;
    if(single && allowedPotion[slot]!=Potion::ENTROPIC_BREW)return true;
    return bc.potionRng.counter==originalPotionRng.counter;
}
// Run value a victory carries into later fights, in HP-equivalents (score unit = 100).
// ponytail: hand-set weights, not fitted; replace with values estimated from paired runs.
constexpr double EXTRA_PER_MAX_HP=2.0;   // on top of the engine's +1 and the healed current HP
constexpr double ENERGY_HP=4.0, PEN_NIB_HP=4.0, INTANGIBLE_HP=10.0, DRAW_HP=1.5;
thread_local int initialMaxHp=0;
thread_local int initialGoldWealth=0;
static int fairyCount(const BattleContext &bc) {
    int count=0;
    for(int i=0;i<bc.potionCapacity;++i) count+=bc.potions[i]==Potion::FAIRY_POTION;
    return count;
}
static bool relicsHeld(const BattleContext &bc);
bool overlayAcceptTerminal(const BattleContext &bc, const std::vector<search::Action> &actions) {
    if(bc.outcome!=Outcome::PLAYER_VICTORY || bc.player.curHp<=0)return false;
    if(smokeProbe) {
        if(std::max(0,initialHp-bc.player.curHp)<smokeLossThreshold
                && fairyCount(bc)>=initialFairies
                && (!initialTail || bc.player.hasRelic<RelicId::LIZARD_TAIL>()))return fastReady=true;
        return false;
    }
    if(heartFight)return fastReady=true;
    if(!fastBoss || bc.turn-initialTurn>20 || 2*std::max(0,initialHp-bc.player.curHp)>initialMaxHp
            || fairyCount(bc)<initialFairies
            || (initialTail && !bc.player.hasRelic<RelicId::LIZARD_TAIL>())
            || !relicsHeld(bc))return false;  // a fast finish still holds Sundial/Nunchaku/... counters
    for(const auto &action:actions)if((action.bits>>29)==1)return false;
    return fastReady=true;
}
bool overlayHeartFight() { return heartFight; }
static double revivalValue(const BattleContext &bc) {
    bool heart=false;
    for(int i=0;i<bc.monsters.monsterCount;++i) heart |= bc.monsters.arr[i].id==MonsterId::CORRUPT_HEART;
    // A refill (especially Bark + Flower) must not outscore keeping a revival.
    // Fairy already receives 4 HP of base inventory value. Keep total reserve at
    // max HP + 20; finite, so spending it remains preferable to losing the fight.
    // Heart is terminal: no future reserve is needed there.
    return (heart ? 0.0 : bc.player.maxHp+16.0)*fairyCount(bc)
        + (!heart && bc.player.hasRelic<RelicId::LIZARD_TAIL>() ? bc.player.maxHp+20.0 : 0.0);
}
// Use the same base exchange rate as the engine's escaped-thief valuation.
constexpr double GOLD_PER_HP=10.0;
static int goldWealth(const BattleContext &bc) {
    int gold=bc.player.gold;
    // Theft is scored separately. Include held stolen gold even if the thief escaped,
    // so moving coins between player and thief cannot masquerade as generated gold.
    for(int i=0;i<bc.monsters.monsterCount;++i) {
        const auto &m=bc.monsters.arr[i];
        if(m.id==MonsterId::LOOTER || m.id==MonsterId::MUGGER) gold+=std::max(0,m.miscInfo);
    }
    return gold;
}
// Progress toward a cross-fight trigger is inventory: n/period of the trigger's value.
// Firing a trigger on the last turn resets progress and is usually wasted, so the
// search learns to hold Nunchaku/Pen Nib and to time Happy Flower/Incense Burner.
// best=true: the value with every counter held one step short of firing (the most a fight can carry).
static double carriedRelicValue(const BattleContext &bc, bool best=false) {
    const auto &p=bc.player;
    auto part=[best](int counter, int period) { return (best ? period-1 : std::max(0, counter)) / static_cast<double>(period); };
    double value=0;
    if(p.hasRelic<RelicId::NUNCHAKU>()) value+=ENERGY_HP*part(p.nunchakuCounter,10);
    if(p.hasRelic<RelicId::HAPPY_FLOWER>()) value+=ENERGY_HP*part(p.happyFlowerCounter,3);
    if(p.hasRelic<RelicId::SUNDIAL>()) value+=2*ENERGY_HP*part(p.sundialCounter,3);
    // Targets come from recovery.incense_targets (user rule); none means the counter is worthless.
    if(p.hasRelic<RelicId::INCENSE_BURNER>() && p.incenseBurnerCounter>=0)
        value+=INTANGIBLE_HP*(best ? incenseTargets!=0 : incenseTargets>>p.incenseBurnerCounter & 1u);
    if(p.hasRelic<RelicId::PEN_NIB>()) value+=PEN_NIB_HP*part(p.penNibCounter,10);
    if(p.hasRelic<RelicId::INK_BOTTLE>()) value+=DRAW_HP*part(p.inkBottleCounter,10);
    return value;
}
static bool relicsHeld(const BattleContext &bc) { return carriedRelicValue(bc)+1e-9>=carriedRelicValue(bc,true); }
// A permanent curse costs about this much HP over the rest of the run (user estimate).
constexpr double CURSE_HP=30.0, OMAMORI_CHARGE_HP=15.0;
// Writhing Mass's Implant marks miscInfo; exitBattle then adds Parasite to the deck,
// or spends an Omamori charge instead.
static double deckCurseHp(const BattleContext &bc) {
    const auto &m=bc.monsters.arr[0];
    if(m.id!=MonsterId::WRITHING_MASS || !m.miscInfo)return 0;
    return bc.player.hasRelic<RelicId::OMAMORI>() ? OMAMORI_CHARGE_HP : CURSE_HP;
}
static int escapedThiefGold(const BattleContext &bc) {
    if(bc.player.hasRelic<RelicId::ECTOPLASM>())return 0;
    int stolen=0;
    bool thief=false, allEscaped=true;
    for(int i=0;i<bc.monsters.monsterCount;++i) {
        const auto &monster=bc.monsters.arr[i];
        if(monster.id==MonsterId::INVALID)continue;
        allEscaped &= monster.isEscaping();
        if(monster.id!=MonsterId::LOOTER && monster.id!=MonsterId::MUGGER)continue;
        thief=true;
        if(monster.isEscaping() && monster.curHp>0)stolen+=std::max(0,monster.miscInfo);
    }
    return thief ? stolen+(allEscaped?15:0) : 0;
}
static int escapedThieves(const BattleContext &bc) {
    int count=0;
    for(int i=0;i<bc.monsters.monsterCount;++i) {
        const auto &monster=bc.monsters.arr[i];
        if((monster.id==MonsterId::LOOTER || monster.id==MonsterId::MUGGER)
                && monster.isEscaping() && monster.curHp>0)++count;
    }
    return count;
}
double overlayRecoveryBonus(const BattleContext &bc) {
    if(bc.outcome!=Outcome::PLAYER_VICTORY)return 0;
    if(requireThiefKill && escapedThieves(bc))return -100000000.0;
    auto hp=bc.victoryHpRelics.project(bc.player.curHp,bc.player.maxHp);
    return 100.0*(revivalValue(bc) + std::min(futureHeal,hp.maxHp-hp.curHp)
        + EXTRA_PER_MAX_HP*std::max(0,bc.player.maxHp-initialMaxHp)
        + carriedRelicValue(bc) - deckCurseHp(bc)
        + std::max(0,goldWealth(bc)-initialGoldWealth)/GOLD_PER_HP);
}
static bool overlaySameVictory(const BattleContext &a, const BattleContext &b) {
    if (a.outcome != Outcome::PLAYER_VICTORY || b.outcome != Outcome::PLAYER_VICTORY
            || a.player.curHp <= 0 || b.player.curHp <= 0) return false;
    const auto ah = a.victoryHpRelics.project(a.player.curHp,a.player.maxHp);
    const auto bh = b.victoryHpRelics.project(b.player.curHp,b.player.maxHp);
    auto inventory = [](const BattleContext &s) {
        const auto &p=s.player;
        return std::make_tuple(p.curHp,p.maxHp,p.gold,p.relicBits0,p.relicBits1,
            p.happyFlowerCounter,p.incenseBurnerCounter,p.inkBottleCounter,p.inserterCounter,
            p.nunchakuCounter,p.penNibCounter,p.sundialCounter,
            goldWealth(s),escapedThiefGold(s),escapedThieves(s),deckCurseHp(s));
    };
    if (inventory(a)!=inventory(b) || ah.curHp!=bh.curHp || ah.maxHp!=bh.maxHp
            || a.potionCapacity!=b.potionCapacity || a.potionBarkFlip!=b.potionBarkFlip) return false;
    for (int i=0;i<a.potionCapacity;++i) if (a.potions[i]!=b.potions[i]) return false;
    auto growth = [](const BattleContext &s, bool &supported) {
        std::vector<std::pair<int,int>> out;
        auto card = [&](const CardInstance &c) {
            // shortcut: these permanent rewards are not modeled by the native exit path.
            if (c.id==CardId::LESSON_LEARNED || c.id==CardId::GENETIC_ALGORITHM) supported=false;
            if (c.id==CardId::RITUAL_DAGGER) out.emplace_back(c.uniqueId,c.specialData);
        };
        for (int i=0;i<s.cards.cardsInHand;++i) card(s.cards.hand[i]);
        for (auto &c:s.cards.drawPile) card(c);
        for (auto &c:s.cards.discardPile) card(c);
        for (auto &c:s.cards.exhaustPile) card(c);
        for (auto &c:s.cards.stasisCards) card(c);
        std::sort(out.begin(),out.end());
        return out;
    };
    bool supported=true;
    const auto ag=growth(a,supported), bg=growth(b,supported);
    return supported && ag==bg;
}

#include "growth_gate.h"
#include "loop_beam.h"
#include "smoke_opening.h"
static bool overlayBeamTick() {
    if (auto *p=live()) {
        if (loop_beam::stats.transitions-p->transitions.load(std::memory_order_relaxed)>=256)
            p->transitions.store(loop_beam::stats.transitions,std::memory_order_relaxed);
    }
    return overlayInterrupted();
}
static bool macroFinish(const BattleContext &root, const BattleContext &end) {
    if (end.outcome != Outcome::PLAYER_VICTORY || end.player.curHp <= 0) return false;
    if (heartFight) return true;
    auto hp = end.victoryHpRelics.project(end.player.curHp, end.player.maxHp);
    if (hp.curHp < hp.maxHp || end.player.maxHp < root.player.maxHp || !relicsHeld(end)
            || escapedThieves(end) || deckCurseHp(end) || goldWealth(end) < goldWealth(root)
            || fairyCount(end) < fairyCount(root)
            || (initialTail && !end.player.hasRelic<RelicId::LIZARD_TAIL>())) return false;
    for (int i = 0; i < root.potionCapacity; ++i) if (root.potions[i] != end.potions[i]) return false;
    // Full HP is not an upper bound on permanent card rewards.
    auto reward = [](const CardInstance &c) { return c.id == CardId::FEED || c.id == CardId::HAND_OF_GREED
        || c.id == CardId::RITUAL_DAGGER || c.id == CardId::WISH || c.id == CardId::LESSON_LEARNED; };
    for (int i = 0; i < root.cards.cardsInHand; ++i) if (reward(root.cards.hand[i])) return false;
    for (auto &c : root.cards.drawPile) if (reward(c)) return false;
    for (auto &c : root.cards.discardPile) if (reward(c)) return false;
    for (auto &c : root.cards.exhaustPile) if (reward(c)) return false;
    return true;
}
PYBIND11_MODULE(overlay_search, m) {
    m.def("_same_victory", &overlaySameVictory);
    m.def("revival_inventory", [](const BattleContext &bc) {
        return std::make_pair(bc.player.hasRelic<RelicId::LIZARD_TAIL>() ? 1 : 0, fairyCount(bc));
    });
    m.def("revival_value", &revivalValue);
    m.def("carried_relic_value", [](const BattleContext &bc) { return carriedRelicValue(bc); });
    m.def("set_discovery_reroll", [](bool value) { discoveryReroll=value; });
    m.def("set_max_hp_gain_weight", [](double weight) { search::BattleScumSearcher2::maxHpGainWeight=weight; });
    m.def("set_incense_targets", [](const std::vector<int> &counters) {
        unsigned mask=0;
        for(int counter:counters) {
            if(counter<0 || counter>5)throw std::invalid_argument("incense counter outside 0-5");
            mask|=1u<<counter;
        }
        incenseTargets=mask;
    });
    m.def("set_card_pools", [](const std::map<std::string,std::vector<CardId>> &pools) {
        for(auto &entry:pools)if(entry.second.size()<3)
            throw std::invalid_argument("card pool has fewer than three candidates");
        actualPools=pools;
    });
    m.def("execute", [](BattleContext &battle, std::uint32_t bits) {
        search::Action action(bits);
        if(!action.isValidAction(battle))throw std::invalid_argument("invalid predicted action");
        action.execute(battle);
    });
    m.def("plan", [](const BattleContext &root, int simulations, const std::vector<int> &slots, int recovery, int index, bool killThieves, bool generatedPotions, bool bossFast, bool smokeOpening, bool bounded, int lossThreshold) {
        if(root.outcome != Outcome::UNDECIDED || root.player.curHp <= 0)
            throw std::invalid_argument("search requires a living combat decision");
        if(recovery<0 || index<0 || index>=static_cast<int>(progress.size()))
            throw std::invalid_argument("invalid potion/recovery policy");
        if(lossThreshold<0)throw std::invalid_argument("negative Smoke Bomb loss threshold");
        smokeProbe=bounded; smokeLossThreshold=lossThreshold;
        if(bounded && (!slots.empty() || simulations<=0))
            throw std::invalid_argument("bounded probe requires no active potions and a positive budget");
        bool smoke=false;
        for(int slot:slots)if(slot>=0 && slot<root.potionCapacity && root.potions[slot]==Potion::SMOKE_BOMB)smoke=true;
        if(smoke && (!smokeOpening || root.turn!=0 || root.player.cardsPlayedThisTurn!=0 || root.player.timesDamagedThisCombat!=0))
            throw std::invalid_argument("Smoke Bomb requires an untouched turn-one opening");
        allowedMask=0;
        heartFight=false;
        for(int i=0;i<root.monsters.monsterCount;++i)heartFight |= root.monsters.arr[i].id==MonsterId::CORRUPT_HEART;
        fastBoss=bossFast; fastReady=false;
        initialHp=root.player.curHp; initialFairies=fairyCount(root);
        initialTurn=root.turn;
        initialTail=root.player.hasRelic<RelicId::LIZARD_TAIL>();
        for(int slot=0;slot<root.potionCapacity;++slot)initialPotion[slot]=root.potions[slot];
        allowGeneratedPotions=generatedPotions;
        for(int slot:slots) {
            if(slot<0 || slot>=root.potionCapacity || slot>=16 || (allowedMask>>slot & 1u))
                throw std::invalid_argument("invalid potion/recovery policy");
            allowedMask|=1u<<slot;
            allowedPotion[slot]=root.potions[slot];
        }
        progressIndex=index;
        originalPotionRng=root.potionRng;
        futureHeal=recovery;
        requireThiefKill=killThieves;
        initialMaxHp=root.player.maxHp;
        initialGoldWealth=goldWealth(root);
        searchGeneration=generation.load();
        finishedRounds=0;
        auto &live=progress[index];
        live.simulations=0; live.rounds=0; live.turn=root.turn; live.bestHp=-1; live.done=0;
        live.phase=1; live.budget=simulations; live.transitions=0; live.transitionLimit=0;
        BattleContext predicted(root);
        combat_search::ReuseResult result;
        bool beamRan=false, beamUsed=false;
        bool macroUsed=false;
        int smokePrefixes=0;
        bool smokeCapped=false;
        double mctsValue=0;
        {
            // The copy above was taken under the GIL; the search touches no Python objects.
            py::gil_scoped_release release;
            loop_beam::stats = {};
            if(smoke) {
                live.phase=5;
                int smokeSlot=-1;
                for(int slot:slots) {
                    auto p=root.potions[slot];
                    if(p==Potion::SMOKE_BOMB)smokeSlot=slot;
                    else if(p!=Potion::STRENGTH_POTION && p!=Potion::FLEX_POTION)
                        throw std::invalid_argument("Smoke prefix only authorizes immediate strength potions");
                }
                auto opening=smokeOpeningPlan(root,smokeSlot,slots);
                predicted=opening.state; result.actions=opening.actions;
                smokePrefixes=opening.prefixes; smokeCapped=opening.capped;
                result.bestHp=predicted.player.curHp;
            } else if(bounded) {
                live.phase=4;
                search::BattleScumSearcher2 probe(root);
                probe.preservePotions=true;
                probe.combat4qEnabled=probe.combat4rEnabled=true;
                probe.combatInitialMaxHp=initialMaxHp;
                probe.search(simulations);
                result.simulations=probe.root.simulationCount;
                // Only a complete terminal witness qualifies; an unfinished probe does not act.
                BattleContext replay(root);
                for(const auto &action:probe.bestActionSequence) {
                    if(!action.isValidAction(replay))throw std::runtime_error("invalid bounded probe action");
                    action.execute(replay);
                }
                if(replay.outcome==Outcome::PLAYER_VICTORY) {
                    predicted=replay;
                    for(const auto &action:probe.bestActionSequence)result.actions.push_back(static_cast<int>(action.bits));
                    result.bestHp=predicted.player.curHp;
                }
            } else {
                loop_beam::Best quick;
            const long long beamCap = 160LL * simulations;
            auto value = [](const BattleContext &b){return search::BattleScumSearcher2::evaluateEndState4r(b,initialMaxHp);};
            auto cancelled = [](){return overlayBeamTick();};
            if (loop_beam::cyclePotential(root)) {
                beamRan=true;
                live.phase=2; live.transitionLimit=std::min(beamCap/32,40000LL);
                loop_beam::pass(root,8,std::min(beamCap/32,40000LL),value,cancelled,quick,true);
                ++loop_beam::stats.passes;
            }
            if (quick.found && loop_beam::stats.macroActions > 0 && macroFinish(root,*quick.state)) {
                predicted=*quick.state; result.actions=quick.actions; result.bestHp=predicted.player.curHp;
                macroUsed=beamUsed=true;
                fastReady=heartFight;
            } else {
                live.transitions=loop_beam::stats.transitions; live.phase=1;
                result=combat_search::playoutReusing(predicted,simulations,4.0,false,false,true,true,true);
            }
            // Small-loop rescue: only when the MCTS line loses or costs a lot of HP (formed simple loops are found faster by MCTS).
            // The beam gets LOOP_BEAM_PER_SIM state transitions per MCTS simulation and wins only on the planner's own end-state value.
            constexpr long long LOOP_BEAM_PER_SIM=160;
            const bool mctsWin=predicted.outcome==Outcome::PLAYER_VICTORY;
            const int cost=root.player.curHp-predicted.player.curHp;
            mctsValue=search::BattleScumSearcher2::evaluateEndState4r(predicted,initialMaxHp);
            // Deep (refinement) budgets: any HP loss is worth a beam; standard budgets: only a loss or a costly line.
            // Heart victory ends the run; further HP or turn improvements have no continuation value.
            if(!macroUsed && !(heartFight && mctsWin) && (!mctsWin || cost>(simulations>=128000 ? 0 : std::max(6,root.player.maxHp*15/100)))) {
                beamRan=true;
                live.phase=3; live.transitionLimit=LOOP_BEAM_PER_SIM*simulations;
                const int growth = simulations == 128000 ? growth_gate::remaining(root,allowedMask,allowGeneratedPotions) : -1;
                auto best=loop_beam::portfolio(root,LOOP_BEAM_PER_SIM*simulations,
                    [](const BattleContext &b){return search::BattleScumSearcher2::evaluateEndState4r(b,initialMaxHp);},
                    [](){return overlayBeamTick();},std::move(quick),loop_beam::stats,
                    simulations == 128000 ? &predicted : nullptr, growth < 0 ? -1 : root.player.maxHp+growth);
                const int mctsHp=predicted.player.curHp;
                if(best.found && (!mctsWin || best.value>mctsValue)) {
                    predicted=*best.state; result.actions=best.actions; result.bestHp=predicted.player.curHp; beamUsed=true;
                }
                if(const char *log=std::getenv("LOOP_BEAM_LOG")) {      // diagnostic only
                    if(FILE *f=std::fopen(log,"a")) {
                        std::fprintf(f,"{\"t\":%.3f,\"turn\":%d,\"hp0\":%d,\"sims\":%d,\"mcts_win\":%d,\"mcts_hp\":%d,\"mcts_value\":%.3f,\"beam_found\":%d,\"beam_hp\":%d,\"beam_value\":%.3f,\"used\":%d,\"transitions\":%lld,\"layers\":%d,\"passes\":%d}\n",
                            std::chrono::duration<double>(std::chrono::system_clock::now().time_since_epoch()).count(),root.turn,root.player.curHp,simulations,int(mctsWin),mctsHp,mctsValue,int(best.found),best.found?best.state->player.curHp:-1,
                            best.found?best.value:0.0,int(beamUsed),loop_beam::stats.transitions,loop_beam::stats.layers,loop_beam::stats.passes);
                        std::fclose(f);
                    }
                }
            }
            }
        }
        if(macroUsed){ live.bestHp=predicted.player.curHp; live.turn=predicted.turn; }
        live.transitions=loop_beam::stats.transitions;
        if(bounded)live.simulations=result.simulations;
        live.done=1;
        // Smoke Bomb still invokes AbstractRoom.endBattle and victory healing.
        auto hp=(predicted.outcome==Outcome::PLAYER_VICTORY || predicted.outcome==Outcome::PLAYER_ESCAPE)
            ? predicted.victoryHpRelics.project(predicted.player.curHp,predicted.player.maxHp)
            : VictoryHp{predicted.player.curHp,predicted.player.maxHp};
        py::dict out;
        int emptySlots=0;
        for(int slot=0;slot<predicted.potionCapacity;++slot)emptySlots+=predicted.potions[slot]==Potion::EMPTY_POTION_SLOT;
        out["empty_potion_slots"]=emptySlots;
        out["actions"]=result.actions;
        out["smoke_prefixes"]=smokePrefixes;
        out["smoke_prefix_capped"]=smokeCapped;
        out["turns"]=predicted.turn-root.turn;
        out["fast_ready"]=fastReady;
        out["simulations"]=result.simulations;
        out["hp"]=predicted.player.curHp;
        out["post_hp"]=hp.curHp;
        out["max_hp"]=hp.maxHp;
        out["used_tail"]=root.player.hasRelic<RelicId::LIZARD_TAIL>() && !predicted.player.hasRelic<RelicId::LIZARD_TAIL>();
        out["relics_held"]=relicsHeld(predicted);
        out["used_fairy"]=std::max(0,fairyCount(root)-fairyCount(predicted));
        out["gained_gold"]=predicted.outcome==Outcome::PLAYER_VICTORY ? std::max(0,goldWealth(predicted)-initialGoldWealth) : 0;
        out["outcome"]=static_cast<int>(predicted.outcome);
        out["curse_hp"]=predicted.outcome==Outcome::PLAYER_VICTORY ? static_cast<int>(deckCurseHp(predicted)) : 0;
        out["lost_gold"]=predicted.outcome==Outcome::PLAYER_VICTORY ? escapedThiefGold(predicted) : 0;
        out["escaped_thieves"]=escapedThieves(predicted);
        out["value"]=search::BattleScumSearcher2::evaluateEndState4r(predicted,initialMaxHp);
        out["loop_beam_ran"]=beamRan;
        out["loop_beam_used"]=beamUsed;
        out["loop_beam_transitions"]=beamRan ? loop_beam::stats.transitions : 0;
        out["loop_beam_mcts_value"]=mctsValue;
        out["loop_macro_used"]=macroUsed;
        out["loop_macro_actions"]=loop_beam::stats.macroActions;
        return out;
    },py::arg("root"),py::arg("simulations"),py::arg("slots"),py::arg("recovery"),py::arg("index"),py::arg("kill_thieves")=false,py::arg("generated_potions")=false,py::arg("boss_fast")=false,py::arg("smoke_opening")=false,py::arg("bounded")=false,py::arg("smoke_loss_threshold")=15);
    m.def("cancel", []() { stopRequested=true; ++generation; });
    m.def("resume", []() { stopRequested=false; });
    m.def("progress", [](int index) {
        if(index<0 || index>=static_cast<int>(progress.size()))throw std::invalid_argument("invalid branch");
        auto &p=progress[index];
        py::dict out;
        const char *phases[]={"waiting","mcts","cycle","beam","smoke_probe","smoke_opening"};
        out["phase"]=phases[p.phase.load()]; out["budget"]=p.budget.load();
        out["transitions"]=p.transitions.load(); out["transition_limit"]=p.transitionLimit.load();
        out["simulations"]=p.simulations.load(); out["rounds"]=p.rounds.load();
        out["turn"]=p.turn.load()+1; out["best_hp"]=p.bestHp.load(); out["done"]=p.done.load()!=0;
        return out;
    });
}
