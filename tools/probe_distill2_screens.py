"""Development-only: find native simulator decision states for adapter fixtures."""
import argparse
import os
from pathlib import Path
import sys


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--student-dir',required=True,type=Path)
    parser.add_argument('--native-dir',required=True,type=Path)
    parser.add_argument('--seed',type=int,default=1947989092)
    parser.add_argument('--steps',type=int,default=80)
    args=parser.parse_args()
    os.environ['STS_LIGHTSPEED_BUILD']=str(args.native_dir.resolve())
    sys.path[:0]=[str(args.student_dir.resolve()),str(args.student_dir.resolve().parent/'agent')]
    import armG_train as A
    from distill2_model import load_student
    from distill2_features import public_packet
    import slaythespire as sts
    import torch
    student=load_student(args.student_dir/'models/distill2_frozen.pt',A)
    gc=sts.GameContext(sts.CharacterClass.IRONCLAD,args.seed,20)
    agent=sts.Agent();agent.simulation_count_base=20
    agent.pause_on_all_out_of_combat_decisions=True
    for step in range(args.steps):
        agent.playout(gc)
        actions=list(sts.get_legal_game_actions(gc))
        print(step,gc.screen_state.name,'act',gc.act,'floor',gc.floor_num,
              'hp',gc.cur_hp,'actions',len(actions),'event',gc.event_id_string,
              'choicebits',[int(a.bits) for a in actions],flush=True)
        if gc.screen_state==sts.ScreenState.EVENT_SCREEN:
            print(' event_observation',A.obs_vec(gc)[22:27],
                  'event_fields',[(k,getattr(gc,k)) for k in dir(gc)
                    if k.startswith('event_') and not callable(getattr(gc,k))][:25],flush=True)
        if any(action.is_potion_action for action in actions):
            print(' potions',[(int(a.bits),int(a.idx1),bool(a.is_potion_discard))
                for a in actions if a.is_potion_action],flush=True)
        if not actions or gc.outcome!=sts.GameOutcome.UNDECIDED:break
        _,descriptors,execs=A.build_choices(gc)
        packet=public_packet(gc,actions,descriptors,student.schema,A)
        with torch.inference_mode():scores=student(*student.tensors(packet)).tolist()
        selected=max(range(len(scores)),key=scores.__getitem__)
        print(' selected',selected,'score',round(scores[selected],3),flush=True)
        execs[selected](gc)


if __name__=='__main__':main()
