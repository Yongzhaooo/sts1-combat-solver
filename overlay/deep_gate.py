"""Headless acts 1-3 elite gate for the standard search's deep refinement.

The model is a JSON file fitted offline on recorded standard/deep call pairs (ridge estimate of the HP
the chosen route would lose without this deep call, divided by its estimated seconds). A deep call is
skipped when that rate is at or below the fitted cutoff. Only standard routes that already win are
gated; a losing standard route always gets its refinement.

A model with a "work" key counts cost in native work (simulations and rescue-beam transitions of the plan) instead of
wall seconds, so the same root decides the same on any machine or load; the headless base selects such a model through
STS_ELITE_DEEP_GATE. A model without it keeps the measured seconds (the mod's player waits real time).
"""
import json
import math


# Encounter keys were recorded with display names; the view carries monster ids.
NAMES = dict(GremlinNob='Gremlin Nob', SlaverBlue='Slaver', SlaverRed='Slaver', SlaverBoss='Taskmaster',
             BookOfStabbing='Book of Stabbing', GiantHead='Giant Head', GremlinLeader='Gremlin Leader',
             GremlinWizard='Gremlin Wizard', GremlinWarrior='Mad Gremlin', GremlinThief='Sneaky Gremlin',
             GremlinFat='Fat Gremlin', GremlinTsundere='Shield Gremlin')


class DeepGate:
    def __init__(self, path):
        m = json.loads(open(path, encoding='utf-8').read())
        self.names, self.encs, self.mu, self.sd = m['names'], m['encs'], m['mu'], m['sd']
        self.wg, self.ws, self.tau = m['wg'], m['ws'], m['tau']
        self.work = m.get('work')

    def cost(self, plan, measured_seconds):
        """Cost input of a standard plan: its measured seconds, or (work model) a pure function of its work counters."""
        if self.work is None:
            return measured_seconds
        return self.work['sims'] * plan['simulations'] + self.work['beam'] * (plan.get('loop_beam_transitions') or 0)

    def score(self, f):
        """f: act, hp0, s_loss, s_value, s_turns, s_tail, s_fairy, s_sims, s_sec, n_slots, n_br, own_sc, pgap_b, enc."""
        v = dict(act2=f['act'] == 2, act3=f['act'] == 3, hp0=f['hp0'], s_loss=f['s_loss'],
                 loss_frac=f['s_loss'] / max(1, f['hp0']), s_value=f['s_value'], s_turns=f['s_turns'],
                 s_tail=bool(f['s_tail']), s_fairy=f['s_fairy'], log_sims=math.log(max(1, f['s_sims'])),
                 log_ssec=math.log(max(1e-3, f['s_sec'])), log_swork=math.log(max(1e-3, f['s_sec'])), n_slots=f['n_slots'], n_br=f['n_br'],
                 is_base=f['n_slots'] == 0, own_score=f['own_sc'], pgap_b=f['pgap_b'])
        x = [1.0] + [(float(v[n] if not n.startswith('enc:') else f['enc'] == n[4:]) - mu) / sd
                     for n, mu, sd in zip(self.names, self.mu, self.sd)]
        dot = lambda w: sum(a * b for a, b in zip(w, x))
        return max(0.05, dot(self.wg)) / math.exp(min(8, dot(self.ws)))

    def bind(self, game, base, base_seconds, n_pot):
        """Gate for one elite root: base is the no-potion standard plan (or None), n_pot the single-bottle branches."""
        hp0 = game['current_hp']
        enc = '+'.join(sorted({NAMES.get(m['id'], m['id']) for m in game['combat_state']['monsters']}))
        base_sc = None if base is None else (hp0 - base['hp'] if base['outcome'] == 1 else hp0 + 50)

        def skip(plan, slots, seconds):
            if plan['outcome'] != 1:
                return False
            own = hp0 - plan['hp'] + 10 * len(slots)
            return self.score(dict(
                act=game['act'], hp0=hp0, s_loss=hp0 - plan['hp'], s_value=plan['value'], s_turns=plan['turns'],
                s_tail=plan.get('used_tail'), s_fairy=plan.get('used_fairy', 0), s_sims=plan['simulations'],
                s_sec=self.cost(plan, base_seconds if seconds is None else seconds), n_slots=len(slots), n_br=n_pot, own_sc=own,
                pgap_b=own - base_sc if slots and base_sc is not None else 0.0, enc=enc)) <= self.tau
        return skip


if __name__ == '__main__':
    # Self-check against the reference scores the fitting script stored with the model.
    import sys
    gate = DeepGate(sys.argv[1])
    checks = json.loads(open(sys.argv[1], encoding='utf-8').read())['checks']
    for c in checks:
        got = gate.score(c['row'])
        assert abs(got - c['score']) <= 1e-9 * max(1.0, abs(c['score'])), (c, got)
    print('deep gate self-check ok:', len(checks))
