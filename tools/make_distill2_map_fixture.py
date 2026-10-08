"""Development-only: generate one simulator packet and its original-game-shaped state."""
import argparse
import json
import os
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--student-dir', type=Path, required=True)
    parser.add_argument('--native-dir', type=Path, required=True)
    parser.add_argument('--seed', type=int, default=1947989092)
    parser.add_argument('--stage', choices=('neow', 'map', 'card', 'rest', 'shop', 'chest', 'grid', 'event'), default='map')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'overlay/test/distill2-map-parity.json')
    args = parser.parse_args()
    os.environ['STS_LIGHTSPEED_BUILD'] = str(args.native_dir.resolve())
    sys.path[:0] = [str(args.student_dir.resolve()), str(args.student_dir.resolve().parent / 'agent')]
    import armG_train as A
    from distill2_model import load_student
    from distill2_features import public_packet
    import slaythespire as sts
    import torch

    student = load_student(args.student_dir / 'models/distill2_frozen.pt', A)
    game = sts.GameContext(sts.CharacterClass.IRONCLAD, args.seed, 20)
    options = game.neow_options
    symbols = dict(MONSTER='M', ELITE='E', REST='R', SHOP='$', EVENT='?', TREASURE='T', BOSS='B')
    if args.stage == 'map':
        # This Neow offer gives gold and proceeds directly to the map.
        index = next(i for i, (bonus, drawback) in enumerate(options) if int(bonus) == 10)
        sts.get_legal_game_actions(game)[index].execute(game)
        assert game.screen_state == sts.ScreenState.MAP_SCREEN
    elif args.stage == 'card':
        sts.get_legal_game_actions(game)[0].execute(game)
        assert game.screen_state == sts.ScreenState.REWARDS
    elif args.stage == 'neow':
        assert game.screen_state == sts.ScreenState.EVENT_SCREEN
    else:
        target={'rest':sts.ScreenState.REST_ROOM,'shop':sts.ScreenState.SHOP_ROOM,
                'chest':sts.ScreenState.TREASURE_ROOM,'grid':sts.ScreenState.CARD_SELECT,
                'event':sts.ScreenState.EVENT_SCREEN}[args.stage]
        agent=sts.Agent();agent.simulation_count_base=20
        agent.pause_on_all_out_of_combat_decisions=True
        last_symbol=None;previous_symbol=None
        for step in range(100):
            agent.playout(game)
            if game.screen_state==target and (args.stage!='event' or game.event_id_string!='NEOW'):
                break
            _,descriptors,execs=A.build_choices(game)
            if not descriptors:raise RuntimeError(f'No action before {args.stage}: {game.screen_state}')
            current=list(sts.get_legal_game_actions(game))
            candidate=public_packet(game,current,descriptors,student.schema,A)
            with torch.inference_mode():scores=student(*student.tensors(candidate)).tolist()
            selected=max(range(len(scores)),key=scores.__getitem__)
            if game.screen_state==sts.ScreenState.MAP_SCREEN:
                x=int(current[selected].idx1);y=int(game.cur_map_node_y)+1
                previous_symbol=last_symbol
                last_symbol=symbols.get(game.map_node_room(x,y).name,'-')
            execs[selected](game)
        else:raise RuntimeError(f'Could not reach {args.stage}')
    actions = list(sts.get_legal_game_actions(game))
    _, descriptors, _ = A.build_choices(game)
    packet = public_packet(game, actions, descriptors, student.schema, A)
    with torch.inference_mode():
        scores = student(*student.tensors(packet)).tolist()

    nodes = [dict(x=x, y=y, symbol=symbols.get(game.map_node_room(x, y).name, '-'),
                  children=[dict(x=int(child), y=y+1) for child in game.map_node_children(x, y)])
             for y in range(15) for x in range(7)]
    by_position = {(node['x'], node['y']): node for node in nodes}
    metadata = json.loads((Path(__file__).resolve().parents[1] /
        'overlay/resources/sts1solver/distill2-schema.json').read_text(encoding='utf-8'))
    inverse_potions = {value: name for name, value in metadata['ids']['potions'].items()}
    inverse_relics = {value: name for name, value in metadata['ids']['relics'].items()}
    deck = [dict(id=card.id.name, upgrades=int(card.upgrade_count), misc=int(card.misc),
                 type=card.type.name) for card in game.deck]
    relics = [dict(id=relic.id.name, counter=int(relic.data)) for relic in game.relics]
    potions = [dict(id=inverse_potions[int(potion)]) for potion in game.potions[:game.potion_capacity]]
    if args.stage == 'map':
        screen_state = dict(boss_available=False,
            next_nodes=[by_position[(int(action.idx1), int(game.cur_map_node_y)+1)] for action in actions])
    elif args.stage == 'card':
        screen_state = dict(cards=[dict(id=card.id.name, name=card.id.name,
            upgrades=int(card.upgrade_count), misc=int(card.misc))
            for card in game.rewards['cards'][0]], bowl_available=False, skip_available=True)
    elif args.stage == 'shop':
        screen_state=dict(cards=[dict(id=card.id.name,name=card.id.name,
            upgrades=int(card.upgrade_count),misc=int(card.misc),price=int(price))
            for card,price in game.get_shop_cards()],
            relics=[dict(id=inverse_relics[int(relic)],name=inverse_relics[int(relic)],price=int(price))
                for relic,price in game.get_shop_relics()],
            potions=[dict(id=inverse_potions[int(potion)],name=inverse_potions[int(potion)],price=int(price))
                for potion,price in game.get_shop_potions()],
            purge_available=any(int(action.bits)==(5<<27) for action in actions),
            purge_cost=int(game.shop_remove_cost))
    elif args.stage == 'grid':
        chosen_type=int(game.selection_type)
        screen_state=dict(cards=[dict(id=card.id.name,name=card.id.name,
            type=card.type.name,upgrades=int(card.upgrade_count),misc=int(card.misc))
            for card in game.selection_cards],selected_cards=[],confirm_up=False,
            num_cards=int(game.selection_count),
            for_purge=chosen_type==4,for_upgrade=chosen_type==3,for_transform=chosen_type==1)
    elif args.stage == 'event':
        screen_state=dict(event_id=game.event_id_string)
    else:
        screen_state = dict(event_id='Neow Event') if args.stage=='neow' else {}
    room_classes=dict(MONSTER='MonsterRoom',ELITE='MonsterRoomElite',REST='RestRoom',
        SHOP='ShopRoom',EVENT='EventRoom',TREASURE='TreasureRoom',BOSS='MonsterRoomBoss')
    game_state = dict(current_hp=int(game.cur_hp), max_hp=int(game.max_hp), gold=int(game.gold),
        act=int(game.act), floor=int(game.floor_num), ascension_level=int(game.ascension),
        act_boss=game.boss.name, room_type=room_classes.get(game.cur_room.name,game.cur_room.name), screen_state=screen_state,
        deck=deck, relics=relics, potions=potions, map=nodes, class_='IRONCLAD',
        keys=dict(ruby=bool(game.red_key), emerald=bool(game.green_key), sapphire=bool(game.blue_key)))
    game_state['class'] = game_state.pop('class_')
    visible = dict(screen_type={'map': 'MAP', 'neow': 'EVENT', 'card': 'CARD_REWARD',
        'rest':'REST','shop':'SHOP_SCREEN','chest':'CHEST','grid':'GRID','event':'EVENT'}[args.stage],
        x=int(game.cur_map_node_x), y=int(game.cur_map_node_y),
        purge_base_cost=75+25*int(game.shop_remove_count),
        burning_elites=[dict(x=int(game.burning_elite[0]), y=int(game.burning_elite[1]))],
        path_taken=[previous_symbol] if args.stage not in ('map','neow','card') and previous_symbol else [],
        bottled_deck_indices=[int(i) for i in game.bottle_indices if int(i)>=0])
    if args.stage=='rest':
        names=('rest','smith','recall','lift','toke','dig','unknown')
        visible['choices']=[names[int(action.idx1)] for action in actions]
    elif args.stage=='shop':
        shop=screen_state;gold=int(game.gold)
        visible['choices']=(['purge'] if shop['purge_available'] else [])+[
            item['name'] for category in ('cards','relics','potions')
            for item in shop[category] if item['price']<=gold]
        visible['purge_actual_cost']=shop['purge_cost']
    elif args.stage=='chest':
        visible['choices']=['open']
        visible['chest_size']=int(game.chest_size)
    elif args.stage=='grid':
        visible['grid_from_shop']=True
    elif args.stage=='event':
        visible['choices']=['option '+str(i) for i in range(len(actions))]
        if game.event_id_string=='Shining Light':
            visible['event_hp_loss']=round(float(packet['observation'][22])*200)
            visible['choices'][0]=f"Lose {visible['event_hp_loss']} HP"
    if args.stage == 'neow':
        bonus_names = ('THREE_CARDS', 'ONE_RANDOM_RARE_CARD', 'REMOVE_CARD', 'UPGRADE_CARD',
            'TRANSFORM_CARD', 'RANDOM_COLORLESS', 'THREE_SMALL_POTIONS', 'RANDOM_COMMON_RELIC',
            'TEN_PERCENT_HP_BONUS', 'THREE_ENEMY_KILL', 'HUNDRED_GOLD', 'RANDOM_COLORLESS_2',
            'REMOVE_TWO', 'ONE_RARE_RELIC', 'THREE_RARE_CARDS', 'TWO_FIFTY_GOLD',
            'TRANSFORM_TWO_CARDS', 'TWENTY_PERCENT_HP_BONUS', 'BOSS_RELIC')
        drawback_names = ('INVALID', 'NONE', 'TEN_PERCENT_HP_LOSS', 'NO_GOLD',
            'CURSE', 'PERCENT_DAMAGE', 'LOSE_STARTER_RELIC')
        visible['neow_options'] = [dict(bonus=bonus_names[int(bonus)],
            drawback=drawback_names[int(drawback)]) for bonus, drawback in options]
        visible['choices'] = ['Neow ' + str(i) for i in range(len(actions))]
        visible['neow_screen'] = 3  # NeowEvent.screenNum while the blessings are offered.
    available=[]
    if args.stage=='shop':available.append('leave')
    if args.stage=='grid' and any(int(action.bits)==805306368 for action in actions):available.append('cancel')
    if args.stage=='rest':available.append('choose')
    output = dict(state=dict(in_game=True, ready_for_command=True, game_state=game_state,
                  available_commands=available),
                  visible=visible, expected={k: packet[k].tolist() for k in (
                      'observation', 'extra', 'routes', 'descriptors')},
                  bits=packet['bits'], scores=scores, schema_id=student.schema['schema_id'])
    args.output.write_text(json.dumps(output, separators=(',', ':')), encoding='utf-8')
    print(args.output, 'actions', packet['bits'], 'scores', scores)


if __name__ == '__main__':
    main()
