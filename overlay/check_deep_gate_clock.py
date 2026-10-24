"""Elite deep gate clock: with work_clock the gate's seconds input is a pure function of plan counters, without it unchanged."""
import time
from pathlib import Path

from deep_gate import DeepGate

MODEL = str(Path(__file__).parent / 'elite_deep_gate.json')
game = dict(act=2, current_hp=60, combat_state=dict(monsters=[dict(id='GremlinNob')]))


def plan(sims, beam, hp=45):
    return dict(outcome=1, hp=hp, value=9000.0, turns=6, simulations=sims, used_tail=False, used_fairy=0,
                loop_beam_transitions=beam)


def seen_seconds(gate, p, slots, seconds, base_seconds):
    """The s_sec the gate scored for one skip call."""
    seen = []
    score = gate.score
    gate.score = lambda f: (seen.append(f['s_sec']), score(f))[1]
    try:
        gate.bind(game, plan(8000, 0), base_seconds, 1)(p, slots, seconds)
    finally:
        gate.score = score
    return seen[0]


work, wall = DeepGate(MODEL, work_clock=True), DeepGate(MODEL)
w = work.work
real_clock = time.monotonic
for sims, beam in ((8000, 0), (8000, 640000), (24000, 12345)):
    p = plan(sims, beam)
    expect = max(1e-3, w['sims'] * sims + w['beam'] * beam + w['const'])
    time.monotonic = lambda: (_ for _ in ()).throw(AssertionError('clock read'))
    try:
        # Measured seconds (from any machine or load) must not matter: base-plan (None) and branch calls alike.
        for slots, seconds, base_seconds in (((), None, 0.01), ((), None, 500.0), ((0,), 0.02, 0.01), ((0,), 900.0, 0.01)):
            assert seen_seconds(work, p, slots, seconds, base_seconds) == expect, (sims, beam, slots, seconds)
    finally:
        time.monotonic = real_clock
    # Default: the measured value reaches the gate untouched (base plan uses base_seconds, a branch its own seconds).
    assert seen_seconds(wall, p, (), None, 3.5) == 3.5
    assert seen_seconds(wall, p, (0,), 7.25, 3.5) == 7.25

assert w['sims'] > 0 and w['beam'] > 0, 'work-seconds coefficients must be positive'
print('PASS: work clock is a pure function of plan counters; default gate keeps measured seconds')
