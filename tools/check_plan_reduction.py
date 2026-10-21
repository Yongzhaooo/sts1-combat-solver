"""A redundant block must disappear without changing victory resources or the input."""
import argparse
from pathlib import Path
import sys

parser=argparse.ArgumentParser()
parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
root=parser.parse_args().root.resolve()
sys.path.insert(0,str(root/'overlay'))
from backend import Advisor
advisor=Advisor()
S=advisor.search.sts
b=S.BattleContext.from_snapshot(dict(seed=1,floor=1,turn=1,ascension=20,
    player=dict(current_hp=72,max_hp=80,block=0,energy=3),
    hand=[dict(id=S.card_id_from_name(x),upgrades=0) for x in ('STRIKE_RED','DEFEND_RED')],
    draw_pile=[],discard_pile=[],exhaust_pile=[],relics=[],potions=[],
    monsters=[dict(id=S.monster_id_from_name('CULTIST'),current_hp=1,max_hp=48,
                   block=0,move=S.monster_move_id_from_name('CULTIST_INCANTATION'))]),1)
before=advisor.search.comparator.clone_fingerprint(b)
actions=[int(S.SearchAction(S.SearchActionType.CARD,i,0).bits) for i in (1,0)]
result=advisor.finalize_plan(b,dict(actions=actions,turns=0,value=10700))
assert result['action_reduction']['before']==2 and result['action_reduction']['after']==1
assert result['value']==10700 and result['turns']==0
assert advisor.search.comparator.clone_fingerprint(b)==before
end=b.clone()
for bits in result['actions']:advisor.native_search.execute(end,bits&0xffffffff)
assert end.outcome==S.Outcome.PLAYER_VICTORY and end.player.cur_hp==72 and end.player.max_hp==80
print('PASS: actual Advisor removes redundant Defend, keeps victory/HP and preserves root')

# A bottle that changes nothing (Speed Potion, then the killing Strike) leaves a no-potion line; a killing Fire Potion does not.
from concurrent.futures import Future
import time
from plan_reduction import drop_potions
def bottled(potion):
    return S.BattleContext.from_snapshot(dict(seed=1,floor=1,turn=1,ascension=20,
        player=dict(current_hp=72,max_hp=80,block=0,energy=3),
        hand=[dict(id=S.card_id_from_name('STRIKE_RED'),upgrades=0)],draw_pile=[],discard_pile=[],exhaust_pile=[],
        relics=[],potions=[S.potion_id_from_name(potion)],
        monsters=[dict(id=S.monster_id_from_name('CULTIST'),current_hp=1,max_hp=48,
                       block=0,move=S.monster_move_id_from_name('CULTIST_INCANTATION'))]),1)
speed=bottled('SPEED_POTION')
use=int(S.SearchAction(S.SearchActionType.POTION,0,0).bits);strike=int(S.SearchAction(S.SearchActionType.CARD,0,0).bits)
assert drop_potions(speed,[use,strike])==[strike]
assert drop_potions(bottled('FIRE_POTION'),[use]) is None
advisor.root=speed;advisor.selected_branch=None
game={'current_hp':72,'max_hp':80,'act':1,'ascension_level':20,'potions':[{'id':'SpeedPotion','can_use':True}],
      'combat_state':{'turn':1,'monsters':[]},'solver_auto_context':{}}
base=dict(actions=[strike],outcome=1,hp=60,post_hp=66,max_hp=80,value=9000,empty_potion_slots=0)
advisor.plan=base;advisor.selected_branch='no-potion';advisor.branches={'no-potion':base}
advisor.branch_info=[advisor.branch_summary('no-potion','不主动用药',base,{'game':game})]
f=Future();f.set_result(dict(base,actions=[use,strike],hp=72,post_hp=78,value=10700,empty_potion_slots=1))
advisor.potion_branch((0,),{0:'速度药水'},f,time.monotonic(),{'game':game},8000)
assert advisor.branches['no-potion']['actions']==[strike] and advisor.branches['no-potion']['value']==11100
assert advisor.plan is advisor.branches['no-potion'] and 'potion-0' not in advisor.branches
assert [b['id'] for b in advisor.branch_info]==['no-potion','potion-0'] and not advisor.branch_info[1]['available']
assert advisor.branch_info[0]['raw_hp']==72
print('PASS: an idle Speed Potion use merges into the no-potion line; a killing Fire Potion stays a potion line')
