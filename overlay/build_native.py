"""Build a small isolated search extension; never change the experiment runtime."""
from pathlib import Path
import subprocess
import sysconfig
import sys
import pybind11

here=Path(__file__).resolve().parent
repo=here.parent/'candidates/sts-ironclad-agent'
engine=repo/'combat_engine/combat4r/engine-source'
out=here/'build/native'
out.mkdir(parents=True,exist_ok=True)
source=(engine/'src/sim/search/BattleScumSearcher2.cpp').read_text(encoding='utf-8')
marker='''void enumeratePotionActionsImpl(NodeLike &node, const BattleContext &bc, bool preservePotions) {
    for (int i = 0; i < bc.potionCapacity; ++i) {'''
assert source.count(marker)==1,'upstream potion enumeration changed'
# Keep potion inventory and passive triggers intact. Only search edges change.
source=source.replace('using namespace sts;', '''using namespace sts;
extern bool overlayPotionAllowed(const BattleContext &, int);
extern bool overlayFastReady();
extern bool overlayHeartFight();
extern bool overlayAcceptTerminal(const BattleContext &, const std::vector<search::Action> &);
extern double overlayRecoveryBonus(const BattleContext &);''',1)
source=source.replace(marker,marker+'\n        if(!overlayPotionAllowed(bc,i))continue;\n',1)
score='return evaluateEndState4q(bc) + 100.0 * (hp.curHp - bc.player.curHp)'
assert source.count(score)==1,'upstream recovery score changed'
source=source.replace(score,'return overlayRecoveryBonus(bc) + evaluateEndState4q(bc) + 100.0 * (hp.curHp - bc.player.curHp)',1)
terminal='    updateFromEvaluation(stack, actionStack, evaluation, terminalHp);'
assert source.count(terminal)==1
source=source.replace(terminal,'''    if (overlayAcceptTerminal(endState, actionStack)) {
        bestActionSequence = actionStack;
        bestActionValue = evaluation;
        outcomePlayerHp = terminalHp;
    }
'''+terminal,1)
victory='    const auto hp = bc.victoryHpRelics.project(bc.player.curHp, bc.player.maxHp);'
assert source.count(victory)==1
source=source.replace(victory,'''    // Terminal fight: survival dominates; among wins prefer fewer turns only.
    if (overlayHeartFight()) return 100000000.0 - 1000.0 * bc.turn;
'''+victory,1)
# Potion branches search in parallel threads; this debug counter is the only shared write.
counter='std::int64_t simulationIdx = 0;'
assert source.count(counter)==1,'upstream debug counter changed'
source=source.replace(counter,'thread_local '+counter,1)
# Tick every 1024 simulations: live counts for the panel and prompt cancellation.
loop='''    for (std::int64_t simCount = 0; simCount < simulations; ++simCount) {
        step();'''
assert source.count(loop)==1,'upstream search loop changed'
source=source.replace(loop,loop+'\n        if (overlayFastReady()) break;\n        if ((simCount & 1023) == 1023 && overlaySearchTick(simCount + 1, outcomePlayerHp)) break;',1)
source=source.replace('extern double overlayRecoveryBonus(const BattleContext &);',
    'extern double overlayRecoveryBonus(const BattleContext &);\nextern bool overlaySearchTick(long long, int);',1)
patched=out/'BattleScumSearcher2.cpp'
patched.write_text(source,encoding='utf-8')
game=(engine/'src/game/Game.cpp').read_text(encoding='utf-8')
game=game.replace('using namespace sts;', 'using namespace sts;\nextern const std::vector<CardId> *overlayCardPool(CardType);',1)
for signature,kind in [
    ('CardId sts::getTrulyRandomColorlessCardInCombat(Random &cardRandomRng) {','STATUS'),
    ('CardId sts::getTrulyRandomCardInCombat(Random &cardRandomRng, CharacterClass cc) {','INVALID'),
    ('CardId sts::getTrulyRandomCardInCombat(Random &cardRandomRng, const CharacterClass cc, const CardType type) {',None)]:
    assert game.count(signature)==1,'upstream combat pool function changed'
    which='type' if kind is None else 'CardType::'+kind
    game=game.replace(signature,signature+f'\n    if(auto pool=overlayCardPool({which}))return (*pool)[cardRandomRng.random(static_cast<int>(pool->size())-1)];',1)
patched_game=out/'Game.cpp'
patched_game.write_text(game,encoding='utf-8')
battle=(engine/'src/combat/BattleContext.cpp').read_text(encoding='utf-8')
intangible='''    if (monster.hasStatus<MS::INTANGIBLE>()) {
        damage = std::max(damage, 1.0f);
    }'''
assert battle.count(intangible)==1,'upstream intangible calculation changed'
battle=battle.replace(intangible,intangible.replace('std::max','std::min'),1)
battle=battle.replace('using namespace sts;', 'using namespace sts;\nextern bool overlayDiscoveryReroll();',1)
begin=battle.index('    if (cardSelectInfo.discoveryFrameRng) {',battle.index('void BattleContext::chooseDiscoveryCard'))
end=battle.index('    CardInstance c = createGeneratedCard(id);',begin)
battle=battle[:begin]+'''    // Match original game 1/60 frame-advance behavior for DiscoveryAction
    if (cardSelectInfo.discoveryFrameRng && overlayDiscoveryReroll()) {
        if (!(actionFrameDelta >= 0.000125f && actionFrameDelta <= 1.0f))
            throw std::invalid_argument("Discovery replay requires a finite frame interval in [0.000125, 1] seconds");
        // In the original game (DiscoveryAction.java), update() unconditionally generates
        // card choices on line 1 before checking duration == Settings.ACTION_DUR_FAST.
        // At fixed headless 60 FPS (dt = 1/60s), duration decreases from 0.25 - 1/60 down to < 0,
        // resulting in exactly 15 post-selection choice generations.
        for (float duration = 0.25f - actionFrameDelta; duration >= 0.0f; duration -= actionFrameDelta)
            generateDiscoveryCards(cardRandomRng, player.cc, cardSelectInfo.discoveryType);
    }
'''+battle[end:]
patched_battle=out/'BattleContext.cpp'
marker='        case CardId::DARK_SHACKLES:'
assert battle.count(marker)==1,'upstream skill switch changed'
battle=battle.replace(marker,'''        case CardId::PIERCING_WAIL:
            // Match the two original enqueue loops. Artifact is checked before
            // the queued Strength debuff consumes it, not after execution.
            for (int i = 0; i < monsters.monsterCount; ++i) {
                if (monsters.arr[i].isTargetable())
                    addToBot(Actions::DebuffEnemy<MS::STRENGTH>(i, up ? -8 : -6, false));
            }
            for (int i = 0; i < monsters.monsterCount; ++i) {
                if (monsters.arr[i].isTargetable() && !monsters.arr[i].hasStatus<MS::ARTIFACT>())
                    addToBot(Actions::BuffEnemy<MS::SHACKLED>(i, up ? 8 : 6));
            }
            break;

'''+marker,1)
early_end='''    addToTopCard(CardQueueItem::endTurnItem());
    endTurnQueued = true;
}'''
assert battle.count(early_end)==1,'upstream early-turn-end layout changed'
battle=battle.replace(early_end,'''    // Time Warp queues turn end while Unceasing Top can still refresh an
    // empty hand. Resolve its draw before the end-turn card discards the hand.
    addToBot([](BattleContext &bc) {
        if (bc.player.hasRelic<R::UNCEASING_TOP>() && bc.cards.cardsInHand == 0
                && !bc.turnHasEnded && !bc.player.hasStatus<PS::NO_DRAW>()) {
            bc.addToBot(Actions::DrawCards(1));
        }
    });
'''+early_end,1)
# Dark Embrace uses areMonstersBasicallyDead, which keeps the rebirth interval
# alive. A failed Havoc autoplay fades its card below; that is not an exhaust.
unplayable='''    } else if (!canUseCard && item.autoplay) {'''
assert battle.count(unplayable)==1,'upstream autoplay failure handling changed'
battle=battle.replace(unplayable,unplayable+'''
        // With no living target, GameActionManager fades the rejected limbo card.
        // That visual exhaust neither adds it to exhaustPile nor triggers exhaust powers.
        if (c.requiresTarget() && monsters.getTargetableCount() == 0) return;
''',1)
patched_battle.write_text(battle,encoding='utf-8')
# FlightPower queues ReducePowerAction at the bottom; all already queued hits
# still see Flight. Removing it immediately overestimates Sword Boomerang damage.
monster=(engine/'src/combat/Monster.cpp').read_text(encoding='utf-8')
flight='''    } else if (hasStatus<MS::FLIGHT>() && damage > 0) {
        auto flight = getStatus<MS::FLIGHT>();
        if (flight == 1) {
            removeStatus<MS::FLIGHT>();
            setMove(MMID::BYRD_STUNNED);
        } else {
            setStatus<MS::FLIGHT>(flight-1);
        }
'''
assert monster.count(flight)==1,'upstream flight damage handling changed'
monster=monster.replace(flight,'''    } else if (hasStatus<MS::FLIGHT>() && damage > 0) {
        if (damage * .5f < curHp) {
            const int target = idx;
            bc.addToBot(Action{[target](BattleContext &state) {
                auto &enemy = state.monsters.arr[target];
                if (!enemy.hasStatus<MS::FLIGHT>()) return;
                const auto stacks = enemy.getStatus<MS::FLIGHT>();
                if (stacks > 1) enemy.setStatus<MS::FLIGHT>(stacks - 1);
                else {
                    enemy.removeStatus<MS::FLIGHT>();
                    state.addToBot(Action{[target](BattleContext &next) {
                        next.monsters.arr[target].setMove(MMID::BYRD_STUNNED);
                    }});
                }
            }});
        }
''',1)
patched_monster=out/'Monster.cpp'
patched_monster.write_text(monster,encoding='utf-8')
# Metamorphosis/Chrysalis reduce positive costs only; generated X-cost cards
# retain -1, so their damage still uses the player's available energy.
actions=(engine/'src/combat/Actions.cpp').read_text(encoding='utf-8')
generated_cost='''            CardInstance card(ids[i], false);
            card.cost = 0;
            card.costForTurn = 0;'''
assert actions.count(generated_cost)==1,'upstream generated draw-pile costs changed'
actions=actions.replace(generated_cost,'''            CardInstance card(ids[i], false);
            if (card.cost > 0) {
                card.cost = 0;
                card.costForTurn = 0;
            }''',1)
patched_actions=out/'Actions.cpp'
patched_actions.write_text(actions,encoding='utf-8')
# Use the engine's settled pile costs. Soul.update clears temporary attributes
# when a discard/draw transfer completes; the Java bridge waits for SoulGroup.
# Report each replanning round so the panel can show the search as it runs.
reuse=(repo/'combat_engine/combat4r/agent/combat_search_reuse.h').read_text(encoding='utf-8')
rounds='        result.simulations += s.root.simulationCount - oldVisits;\n'
assert reuse.count(rounds)==1,'upstream reuse loop changed'
reuse=reuse.replace('namespace combat_search {','void overlayProgress(int rounds, long long simulations, int bestHp, int turn);\nbool overlayInterrupted();\nbool overlayFastReady();\n\nnamespace combat_search {',1)
reuse=reuse.replace(rounds,rounds+'        overlayProgress(result.rounds + 1, result.simulations, bestHp, battle.turn);\n',1)
second='if (combat4Enabled && result.rounds == 0 && s.outcomePlayerHp <= 0) {'
assert reuse.count(second)==1,'upstream adaptive search layout changed'
reuse=reuse.replace(second,'if (!overlayInterrupted() && combat4Enabled && result.rounds == 0 && s.outcomePlayerHp <= 0) {',1)
cancel_point='        const auto start = agent.gameActionHistory.size();\n'
assert reuse.count(cancel_point)==1,'upstream replanning layout changed'
reuse=reuse.replace(cancel_point,'''        if (overlayInterrupted() || overlayFastReady()) {
            auto actions = !overlayFastReady() && bestHp > 0 ? bestActions :
                std::vector(s.bestActionSequence.rbegin(), s.bestActionSequence.rend());
            if (actions.empty()) throw std::runtime_error("搜索中断前尚无可执行路线");
            while (!actions.empty() && battle.outcome == Outcome::UNDECIDED) {
                agent.takeAction(battle, actions.back());
                actions.pop_back();
            }
            if (battle.outcome == Outcome::UNDECIDED)
                throw std::runtime_error("中断时尚无完整路线");
            result.actions = std::move(agent.gameActionHistory);
            result.bestHp = bestHp;
            return result;
        }
'''+cancel_point,1)
(out/'combat_search_reuse.h').write_text(reuse,encoding='utf-8')
binary=out/('overlay_search'+sysconfig.get_config_var('EXT_SUFFIX'))
pending=binary.with_name(binary.name+'.pending')
if sys.platform == 'win32':
    # Reuse CMake's installed MSVC toolchain; no developer shell required.
    def cmake_path(path):
        return '"' + str(path).replace('\\', '/') + '"'
    sources=[here/'native.cpp',patched,patched_game,patched_battle,patched_monster,patched_actions]
    includes=[out,engine/'include',repo/'combat_engine/combat4r/agent',repo/'combat_engine/third_party']
    (out/'CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.19)
project(overlay_search LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
find_package(pybind11 CONFIG REQUIRED)
pybind11_add_module(overlay_search '''+' '.join(map(cmake_path,sources))+''')
target_compile_options(overlay_search PRIVATE /O2 /UNDEBUG /utf-8 /EHsc /bigobj)
target_compile_definitions(overlay_search PRIVATE COMBAT3_TARGET_POLICY=1 NOMINMAX)
target_include_directories(overlay_search PRIVATE '''+' '.join(map(cmake_path,includes))+''')
target_link_libraries(overlay_search PRIVATE '''+cmake_path(repo/'build/engine/Release/sts_core.lib')+''')
set_target_properties(overlay_search PROPERTIES LIBRARY_OUTPUT_DIRECTORY_RELEASE '''+cmake_path(out)+''')
''',encoding='utf-8')
    subprocess.run(['cmake','-S',str(out),'-B',str(out/'cmake'),'-A','x64',
        '-Dpybind11_DIR='+pybind11.get_cmake_dir(),'-DPython_EXECUTABLE='+sys.executable,
        '-DPYTHON_EXECUTABLE='+sys.executable],check=True)
    subprocess.run(['cmake','--build',str(out/'cmake'),'--config','Release','-j','4'],check=True)
else:
    subprocess.run(['c++','-std=c++17','-O3','-UNDEBUG','-DCOMBAT3_TARGET_POLICY=1',
    '-fPIC','-fvisibility=hidden','-shared','-pthread',
    # Python supplies its C API at import time; Darwin's linker needs this explicitly.
    *(['-undefined','dynamic_lookup'] if sys.platform == 'darwin' else []),
    # The patched reuse header in out/ must shadow the agent copy.
    '-I'+str(out),'-I'+str(engine/'include'),'-I'+str(repo/'combat_engine/combat4r/agent'),
    '-I'+str(repo/'combat_engine/third_party'),'-I'+pybind11.get_include(),
    '-I'+sysconfig.get_paths()['include'],str(here/'native.cpp'),str(patched),str(patched_game),str(patched_battle),str(patched_monster),str(patched_actions),
        str(repo/'build/engine/libsts_core.a'),'-o',str(pending)],check=True)
    pending.replace(binary)  # Existing workers retain their loaded library until stopped.
assert binary.stat().st_size>1000
print(binary)
