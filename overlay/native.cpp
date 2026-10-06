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
thread_local Random originalPotionRng;
// Set under the GIL before any search starts; searches only read them.
bool discoveryReroll=true;
std::map<std::string,std::vector<CardId>> actualPools;
// Live counters per branch index, read by the progress reporter while plans run.
struct Progress { std::atomic<long long> simulations{0}; std::atomic<int> rounds{0}, turn{0}, bestHp{-1}, done{0}; };
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
bool overlayAcceptTerminal(const BattleContext &bc, const std::vector<search::Action> &actions) {
    if(bc.outcome!=Outcome::PLAYER_VICTORY || bc.player.curHp<=0)return false;
    if(heartFight)return fastReady=true;
    if(!fastBoss || bc.turn-initialTurn>20 || 2*std::max(0,initialHp-bc.player.curHp)>initialMaxHp
            || fairyCount(bc)<initialFairies
            || (initialTail && !bc.player.hasRelic<RelicId::LIZARD_TAIL>()))return false;
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
static double carriedRelicValue(const BattleContext &bc) {
    const auto &p=bc.player;
    auto part=[](int counter, int period) { return std::max(0, counter) / static_cast<double>(period); };
    double value=0;
    if(p.hasRelic<RelicId::NUNCHAKU>()) value+=ENERGY_HP*part(p.nunchakuCounter,10);
    if(p.hasRelic<RelicId::HAPPY_FLOWER>()) value+=ENERGY_HP*part(p.happyFlowerCounter,3);
    if(p.hasRelic<RelicId::SUNDIAL>()) value+=2*ENERGY_HP*part(p.sundialCounter,3);
    if(p.hasRelic<RelicId::INCENSE_BURNER>()) {
        bool beforeHeart=false, heart=false;
        for(int i=0;i<bc.monsters.monsterCount;++i) {
            auto id=bc.monsters.arr[i].id;
            beforeHeart |= id==MonsterId::SPIRE_SHIELD || id==MonsterId::SPIRE_SPEAR;
            heart |= id==MonsterId::CORRUPT_HEART;
        }
        // Shield/Spear leads directly to Heart: 4 covers turn 2, 3 covers
        // turn 3; 5 fires on Heart's opening debuff turn. Do not reward that.
        if(beforeHeart) value+=INTANGIBLE_HP*(p.incenseBurnerCounter==3 || p.incenseBurnerCounter==4);
        else if(!heart) value+=INTANGIBLE_HP*part(p.incenseBurnerCounter,6);
    }
    if(p.hasRelic<RelicId::PEN_NIB>()) value+=PEN_NIB_HP*part(p.penNibCounter,10);
    if(p.hasRelic<RelicId::INK_BOTTLE>()) value+=DRAW_HP*part(p.inkBottleCounter,10);
    return value;
}
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
PYBIND11_MODULE(overlay_search, m) {
    m.def("revival_inventory", [](const BattleContext &bc) {
        return std::make_pair(bc.player.hasRelic<RelicId::LIZARD_TAIL>() ? 1 : 0, fairyCount(bc));
    });
    m.def("revival_value", &revivalValue);
    m.def("carried_relic_value", &carriedRelicValue);
    m.def("set_discovery_reroll", [](bool value) { discoveryReroll=value; });
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
    m.def("plan", [](const BattleContext &root, int simulations, const std::vector<int> &slots, int recovery, int index, bool killThieves, bool generatedPotions, bool bossFast) {
        if(root.outcome != Outcome::UNDECIDED || root.player.curHp <= 0)
            throw std::invalid_argument("search requires a living combat decision");
        if(recovery<0 || index<0 || index>=static_cast<int>(progress.size()))
            throw std::invalid_argument("invalid potion/recovery policy");
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
        BattleContext predicted(root);
        combat_search::ReuseResult result;
        {
            // The copy above was taken under the GIL; the search touches no Python objects.
            py::gil_scoped_release release;
            result=combat_search::playoutReusing(predicted,simulations,4.0,false,false,true,true,true);
        }
        live.done=1;
        auto hp=predicted.outcome==Outcome::PLAYER_VICTORY
            ? predicted.victoryHpRelics.project(predicted.player.curHp,predicted.player.maxHp)
            : VictoryHp{predicted.player.curHp,predicted.player.maxHp};
        py::dict out;
        out["actions"]=result.actions;
        out["turns"]=predicted.turn-root.turn;
        out["fast_ready"]=fastReady;
        out["simulations"]=result.simulations;
        out["hp"]=predicted.player.curHp;
        out["post_hp"]=hp.curHp;
        out["max_hp"]=hp.maxHp;
        out["used_tail"]=root.player.hasRelic<RelicId::LIZARD_TAIL>() && !predicted.player.hasRelic<RelicId::LIZARD_TAIL>();
        out["used_fairy"]=std::max(0,fairyCount(root)-fairyCount(predicted));
        out["gained_gold"]=predicted.outcome==Outcome::PLAYER_VICTORY ? std::max(0,goldWealth(predicted)-initialGoldWealth) : 0;
        out["outcome"]=static_cast<int>(predicted.outcome);
        out["curse_hp"]=predicted.outcome==Outcome::PLAYER_VICTORY ? static_cast<int>(deckCurseHp(predicted)) : 0;
        out["lost_gold"]=predicted.outcome==Outcome::PLAYER_VICTORY ? escapedThiefGold(predicted) : 0;
        out["escaped_thieves"]=escapedThieves(predicted);
        out["value"]=search::BattleScumSearcher2::evaluateEndState4r(predicted,initialMaxHp);
        return out;
    },py::arg("root"),py::arg("simulations"),py::arg("slots"),py::arg("recovery"),py::arg("index"),py::arg("kill_thieves")=false,py::arg("generated_potions")=false,py::arg("boss_fast")=false);
    m.def("cancel", []() { stopRequested=true; ++generation; });
    m.def("resume", []() { stopRequested=false; });
    m.def("progress", [](int index) {
        if(index<0 || index>=static_cast<int>(progress.size()))throw std::invalid_argument("invalid branch");
        auto &p=progress[index];
        py::dict out;
        out["simulations"]=p.simulations.load(); out["rounds"]=p.rounds.load();
        out["turn"]=p.turn.load()+1; out["best_hp"]=p.bestHp.load(); out["done"]=p.done.load()!=0;
        return out;
    });
}
