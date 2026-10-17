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
