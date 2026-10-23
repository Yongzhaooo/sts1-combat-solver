"""Bounded post-search reduction; search ordering is unchanged."""
import time
import slaythespire as S
import overlay_search as N


def replay_single(b, actions):
    states = []; tokens = []; descriptions = []
    for bits in actions:
        a = S.SearchAction.from_bits(int(bits) & 0xffffffff)
        assert b.outcome == S.Outcome.UNDECIDED and a.is_valid(b)
        states.append(b.clone()); descriptions.append(a.desc(b))
        token = dict(bits=int(bits), turn=b.turn, card=a.action_type == S.SearchActionType.CARD)
        if token['card']:
            c = b.hand[a.source_idx]
            token.update(uid=c.unique_id, id=int(c.id), target=a.target_idx)
        tokens.append(token); N.execute(b, a.bits)
    return b, states, tokens, descriptions


def locate(b, t):
    if t['card']:
        indices = [i for i, c in enumerate(b.hand) if c.unique_id == t['uid'] and int(c.id) == t['id']]
        if len(indices) != 1: return None
        a = S.SearchAction(S.SearchActionType.CARD, indices[0], t['target'])
    else:
        a = S.SearchAction.from_bits(t['bits'] & 0xffffffff)
    # Keep the original turn schedule; do not move a command into another turn.
    return a if b.turn == t['turn'] and a.is_valid(b) else None


def caps(length, attempts):
    """Limits for one reduction pass. They grow with the plan, so a long looping line (a Heart turn of 500 plays)
    still gets one full pass instead of stopping after a few dozen attempts; plans up to 50 actions keep the old limits."""
    return dict(attempts=max(attempts, attempts * length // 64), executions=max(50000, 1000 * length),
                seconds=max(2, length / 50))


def reduce_plan(initial, actions):
    start = time.monotonic(); cap = caps(len(actions), 128)
    end, states, tokens, before_desc = replay_single(initial.clone(), actions)
    original_end = end.clone(); original = list(actions)
    if end.outcome != S.Outcome.PLAYER_VICTORY:
        return dict(actions=actions, seconds=time.monotonic()-start, attempts=0, executions=len(actions), accepted=[], reason='not_victory')
    attempts = 0; executions = len(actions); changes = []; i = 0
    while i < len(actions) and attempts < cap['attempts'] and executions < cap['executions'] and time.monotonic()-start < cap['seconds']:
        if not tokens[i]['card']:
            i += 1; continue
        attempts += 1; b = states[i].clone(); candidate = list(actions[:i]); removed = []
        for j in range(i+1, len(tokens)):
            if b.outcome != S.Outcome.UNDECIDED: break
            if executions >= cap['executions'] or time.monotonic()-start >= cap['seconds']: break
            a = locate(b, tokens[j])
            if a is None:
                removed.append(j); continue
            N.execute(b, a.bits); executions += 1; candidate.append(int(a.bits))
        if (len(candidate) < len(actions) and b.turn <= original_end.turn
                and N._same_victory(b, original_end)):
            changes.append(dict(index=i, deleted=before_desc[i], additionally_skipped=removed,
                                before=len(actions), after=len(candidate)))
            actions = candidate
            end, states, tokens, before_desc = replay_single(initial.clone(), actions)
            executions += len(actions)
            assert N._same_victory(end, original_end)
        else:
            i += 1
    assert len(actions) <= len(original)
    return dict(actions=actions, seconds=time.monotonic()-start, attempts=attempts, executions=executions,
                accepted=changes, rng_changed=[k for k in end.rng_states if end.rng_states[k] != original_end.rng_states[k]],
                cap_reached=attempts>=cap['attempts'] or executions>=cap['executions'] or time.monotonic()-start>=cap['seconds'])


def replay(root,actions):
    b=root.clone();tokens=[];states=[];desc=[];groups=[]
    for bits in actions:
        a=S.SearchAction.from_bits(bits&0xffffffff)
        assert b.outcome==S.Outcome.UNDECIDED and a.is_valid(b)
        states.append(b.clone());desc.append(a.desc(b))
        t=dict(bits=bits,turn=b.turn)
        if a.action_type==S.SearchActionType.CARD:
            c=b.hand[a.source_idx];t.update(uid=c.unique_id,id=int(c.id),target=a.target_idx)
            groups.append([len(tokens)])
        elif a.action_type in (S.SearchActionType.SINGLE_CARD_SELECT,S.SearchActionType.MULTI_CARD_SELECT):
            if groups and groups[-1][-1]==len(tokens)-1:groups[-1].append(len(tokens))
        tokens.append(t);N.execute(b,a.bits)
    return b,states,tokens,groups,desc


def resources(b):
    cards=[c for pile in (b.hand,b.draw_pile,b.discard_pile,b.exhaust_pile) for c in pile]
    keys=('happy_flower','incense_burner','ink_bottle','inserter','nunchaku','pen_nib','sundial')
    return dict(outcome=int(b.outcome),hp=b.player.cur_hp,max_hp=b.player.max_hp,gold=b.player.gold,
                potions=list(b.potions),revival=list(N.revival_inventory(b)),
                carried_relic_value=N.carried_relic_value(b),potion_bark_flip=b.potion_bark_flip,
                counters={k:b.snapshot_counters[k] for k in keys},
                dagger=sorted((int(c.id),c.unique_id,c.special_data) for c in cards if c.id==S.CardId.RITUAL_DAGGER))


def drop_potions(root, actions):
    """The line without its potion uses, when it replays to the same victory apart from the bottles kept; else None.

    A potion branch's own search can find a better line than the no-potion search and pass through a use that changes
    nothing (Speed Potion right before ending a turn with no Block card); that line needs no bottle at all.
    """
    end, _, tokens, _, _ = replay(root, actions)
    if end.outcome != S.Outcome.PLAYER_VICTORY:
        return None
    b = root.clone(); kept = []
    for t in tokens:
        a = S.SearchAction.from_bits(t['bits'] & 0xffffffff)
        if a.action_type == S.SearchActionType.POTION:
            continue
        if b.outcome != S.Outcome.UNDECIDED:
            return None
        if 'uid' in t:
            matches = [n for n, c in enumerate(b.hand) if c.unique_id == t['uid'] and int(c.id) == t['id']]
            if len(matches) != 1:
                return None
            a = S.SearchAction(S.SearchActionType.CARD, matches[0], t['target'])
        if b.turn != t['turn'] or not a.is_valid(b):
            return None
        N.execute(b, a.bits); kept.append(int(a.bits))
    rest = lambda state: {k: v for k, v in resources(state).items() if k != 'potions'}
    if len(kept) == len(actions) or b.outcome != S.Outcome.PLAYER_VICTORY or b.turn > end.turn or rest(b) != rest(end):
        return None
    return kept


def reduce_blocks(root,actions):
    start=time.monotonic();original=end=replay(root,actions)[0];cap=caps(len(actions),512)
    attempts=0;executions=len(actions);changes=[];i=0
    if end.outcome!=S.Outcome.PLAYER_VICTORY:
        return dict(actions=actions,seconds=time.monotonic()-start,attempts=0,executions=executions,accepted=[])
    end,states,tokens,groups,desc=replay(root,actions);executions+=len(actions)
    while i<len(groups) and attempts<cap['attempts'] and executions<cap['executions'] and time.monotonic()-start<cap['seconds']:
        accepted=False
        for j in range(i+1,min(i+6,len(groups))):
            first=groups[i][0]
            if tokens[first]['turn']!=tokens[groups[j][0]]['turn']:break
            if attempts>=cap['attempts'] or executions>=cap['executions'] or time.monotonic()-start>=cap['seconds']:break
            attempts+=1;drop=set(groups[i]+groups[j]);b=states[first].clone();candidate=list(actions[:first]);valid=True
            for k in range(first,len(tokens)):
                if k in drop:continue
                if b.outcome!=S.Outcome.UNDECIDED:break
                if executions>=cap['executions'] or time.monotonic()-start>=cap['seconds']:valid=False;break
                t=tokens[k];a=S.SearchAction.from_bits(t['bits']&0xffffffff)
                if 'uid' in t:
                    matches=[n for n,c in enumerate(b.hand) if c.unique_id==t['uid'] and int(c.id)==t['id']]
                    if len(matches)!=1:valid=False;break
                    a=S.SearchAction(S.SearchActionType.CARD,matches[0],t['target'])
                if b.turn!=t['turn'] or not a.is_valid(b):valid=False;break
                N.execute(b,a.bits);executions+=1;candidate.append(int(a.bits))
            if valid and len(candidate)<len(actions) and b.turn<=original.turn and N._same_victory(b,original):
                changes.append(dict(deleted=[desc[k] for k in sorted(drop)],indices=sorted(drop),
                                    before=len(actions),after=len(candidate)))
                actions=candidate;end,states,tokens,groups,desc=replay(root,actions);executions+=len(actions)
                assert N._same_victory(end,original)
                accepted=True;break
        if not accepted:i+=1
    return dict(actions=actions,seconds=time.monotonic()-start,attempts=attempts,executions=executions,accepted=changes,
                cap_reached=attempts>=cap['attempts'] or executions>=cap['executions'] or time.monotonic()-start>=cap['seconds'])


def postprocess(root, actions):
    start = time.monotonic()
    before = replay(root, actions)[0]
    single = reduce_plan(root, actions)
    blocks = reduce_blocks(root, single['actions'])
    after = replay(root, blocks['actions'])[0]
    changed = blocks['actions'] != actions
    if changed and (after.turn > before.turn or not N._same_victory(after, before)):
        raise RuntimeError('post-search reduction failed terminal validation')
    return dict(actions=blocks['actions'], seconds=time.monotonic()-start,
                single=single, blocks=blocks)
