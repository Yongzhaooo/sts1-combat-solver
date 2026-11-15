"""Check the heart_sim backend op against a real backend process, without a game.

Covers the frozen criteria of the heart simulation plan: A2 (unsupported items refused by name, accepted without them), A3 (win /
no_win, a 1 HP starter deck is never a win), A5 (cancel answers within 2 s and a solve follows normally), A6 (a request's seed
is echoed and the same seed gives the same result), A7 (one heart-sim.jsonl line per finished request, cancelled ones too), the
deep-tier time limit (progress says 600 s, a shortened limit yields `timeout`) and the solver's own limit staying 150 s.

    python tools/check_heart_sim.py [--root REPO]

The backend runs with its data directory and LOCALAPPDATA in a temporary folder; nothing of the real solver data is read or written.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
WIN_FIXTURE = 'fixtures/heart-sim/win-2904945097763423898.json'
LONG_FIXTURE = 'fixtures/heart-sim/long-7664671196745433303.json'


def starter(hp=80, relics=None, potions=None, bottles=None, extra_deck=()):
    """An outside-combat game_state for the starting Ironclad deck."""
    deck = [{'id': 'Strike_R', 'upgrades': 0, 'uuid': 'strike-%d' % i} for i in range(5)]
    deck += [{'id': 'Defend_R', 'upgrades': 0, 'uuid': 'defend-%d' % i} for i in range(4)]
    deck += [{'id': 'Bash', 'upgrades': 0, 'uuid': 'bash'}, *extra_deck]
    return {'class': 'IRONCLAD', 'ascension_level': 20, 'floor': 33, 'gold': 99, 'current_hp': hp, 'max_hp': 80,
            'keys': {'ruby': False, 'emerald': False, 'sapphire': False}, 'seed': 123,
            'deck': deck, 'relics': relics or [{'id': 'Burning Blood', 'name': 'Burning Blood', 'counter': -1}],
            'potions': potions or [{'id': 'Potion Slot'}] * 3, 'solver_auto_context': {'feed_mode': False},
            **({'solver_heart_bottles': bottles} if bottles is not None else {})}


def fixture_state(name=None):
    fixture = json.loads((ROOT / (name or WIN_FIXTURE)).read_text(encoding='utf-8'))
    deck = [{'id': c['id'], 'upgrades': c['upgrades'], 'uuid': 'card-%d' % i} for i, c in enumerate(fixture['deck'])]
    return fixture['seed'], {'class': 'IRONCLAD', 'ascension_level': fixture['ascension_level'], 'floor': 56, 'gold': 0,
                             'current_hp': fixture['current_hp'], 'max_hp': fixture['max_hp'], 'keys': {}, 'deck': deck,
                             'relics': fixture['relics'], 'potions': [{'id': p} for p in fixture['potions']],
                             'solver_auto_context': {}}


# Entry point whose heart_sim ignores interrupts for a minute; the spawned worker re-imports this file, so the patch reaches it.
STALLED = '''import sys, time
sys.path.insert(0, {overlay!r})
import backend
_heart = backend.Advisor.heart_sim
def heart_sim(self, *args):
    time.sleep(60)
    return _heart(self, *args)
backend.Advisor.heart_sim = heart_sim
if __name__ == "__main__":
    backend.serve()
'''


class Backend:
    def __init__(self, folder, stalled=False, **env):
        self.folder = Path(folder)
        backend = ROOT / 'overlay/backend.py'
        if stalled:
            backend = Path(folder) / 'stalled_backend.py'
            backend.write_text(STALLED.format(overlay=str(ROOT / 'overlay')), encoding='utf-8')
        full = dict(os.environ, STS_SOLVER_DATA=str(self.folder), LOCALAPPDATA=str(self.folder / 'localappdata'),
                    PYTHONUTF8='1', PYTHONIOENCODING='utf-8', **env)
        full.pop('PYTHONHOME', None)
        full.pop('PYTHONPATH', None)
        (self.folder / 'localappdata').mkdir(exist_ok=True)
        self.log = (self.folder / 'backend.log').open('w', encoding='utf-8')
        self.process = subprocess.Popen([sys.executable, '-X', 'utf8', '-B', '-u', str(backend)], cwd=self.folder, env=full,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, text=True, encoding='utf-8')
        self.replies = queue.Queue()
        threading.Thread(target=lambda: [self.replies.put(json.loads(line)) for line in self.process.stdout], daemon=True).start()

    def send(self, value):
        self.process.stdin.write(json.dumps(value) + '\n')
        self.process.stdin.flush()

    def recv(self, wait=60):
        return self.replies.get(timeout=wait)

    def until_final(self, request_id, wait=300, progress=None):
        """Replies for one id up to its terminal one; progress replies are appended to `progress`."""
        end = time.monotonic() + wait
        while True:
            reply = self.recv(max(.1, end - time.monotonic()))
            if reply.get('id') != request_id:
                raise AssertionError('reply for another id while waiting for %r: %r' % (request_id, reply))
            if reply.get('status') != 'progress':
                return reply
            if progress is not None:
                progress.append(reply)

    def heart(self, request_id, game, seed, wait=300, progress=None, **extra):
        self.send({'id': request_id, 'op': 'heart_sim', 'seed': str(seed), 'progress': True,
                   'state': {'in_game': True, 'game_state': game}, **extra})
        return self.until_final(request_id, wait, progress)

    def silent(self, seconds):
        """No reply at all for this long."""
        try:
            return self.replies.get(timeout=seconds)
        except queue.Empty:
            return None

    def lines(self):
        path = self.folder / 'heart-sim.jsonl'
        return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()] if path.exists() else []

    def close(self):
        try:
            if self.process.poll() is None:
                self.process.kill()
            self.process.wait()
        finally:
            self.process.stdout.close()
            self.process.stdin.close()
            self.log.close()


def check_main(folder):
    seed_win, win_game = fixture_state()
    seed_long, long_game = fixture_state(LONG_FIXTURE)
    live = json.loads((ROOT / 'overlay/test/live-root.json').read_text(encoding='utf-8'))
    backend = Backend(folder)
    try:
        ids = iter(range(-1000, -100000, -1))

        # --- A2: an unimplemented relic is refused by name; without it the same position gives a result.
        progress = []
        gapped = starter(hp=1, relics=[{'id': 'Burning Blood', 'counter': -1}, {'id': 'Tingsha', 'name': 'Tingsha', 'counter': -1}])
        reply = backend.heart(next(ids), gapped, 11, progress=progress)
        assert reply['status'] == 'error' and 'Tingsha' in reply['message'] and reply['message'].startswith('无法模拟'), reply
        assert reply.get('limitation') and 'Tingsha' in reply['limitation'][0], reply
        # The first progress reply carries the time limit: the deep tier, 600 s (a heart_sim has no budget of its own).
        assert progress and progress[0].get('timeout_seconds') == 600, progress[:1]
        gapped_card = starter(hp=1, extra_deck=[{'id': 'Neutralize', 'name': 'Neutralize', 'upgrades': 0, 'uuid': 'n'}])
        reply = backend.heart(next(ids), gapped_card, 11)
        assert reply['status'] == 'error' and 'Neutralize' in reply['message'], reply
        unknown = starter(hp=1, relics=[{'id': 'Burning Blood', 'counter': -1}, {'id': 'NoSuchRelic', 'counter': -1}])
        reply = backend.heart(next(ids), unknown, 11)
        assert reply['status'] == 'error' and 'NoSuchRelic' in reply['message'], reply
        reply = backend.heart(next(ids), starter(hp=1), 11)
        assert reply['status'] == 'ready' and reply['heart_result'] in ('no_win', 'win'), reply

        # --- A3: the 1 HP starter deck is never a win; the recorded winning position is a win.
        reply = backend.heart(next(ids), starter(hp=1), 12)
        assert reply['status'] == 'ready' and reply['heart_result'] == 'no_win' and 'hp' not in reply, reply
        assert reply['seed'] == '12' and reply['simulations'] > 0 and reply['seconds'] >= 0, reply
        win = backend.heart(next(ids), win_game, seed_win)
        assert win['status'] == 'ready' and win['heart_result'] == 'win' and win['hp'] > 0 and win['seed'] == seed_win, win

        # --- A6: the same seed gives the same result, and a different seed is recorded as a different request.
        again = backend.heart(next(ids), win_game, seed_win)
        assert (again['heart_result'], again['simulations'], again['hp']) == (win['heart_result'], win['simulations'], win['hp']), (win, again)
        other = backend.heart(next(ids), win_game, '5')
        assert other['seed'] == '5' and other['status'] == 'ready', other
        # Seeds are unsigned 64-bit decimal strings; anything else is refused before a battle is built.
        for bad in ('-1', '18446744073709551616', 'abc', ''):
            reply = backend.heart(next(ids), starter(hp=1), bad)
            assert reply['status'] == 'error' and '种子' in reply['message'], (bad, reply)
        reply = backend.heart(next(ids), starter(hp=1), '18446744073709551615')
        assert reply['status'] == 'ready' and reply['seed'] == '18446744073709551615', reply

        # --- Other refusals: class, bottled card unknown, Omamori counter unreadable; a bottle with its card is accepted.
        defect = dict(starter(hp=1), **{'class': 'DEFECT'})
        reply = backend.heart(next(ids), defect, 11)
        assert reply['status'] == 'error' and '暂只支持战士' in reply['message'], reply
        flame = [{'id': 'Burning Blood', 'counter': -1}, {'id': 'Bottled Flame', 'counter': -1}]
        reply = backend.heart(next(ids), starter(hp=1, relics=flame), 11)
        assert reply['status'] == 'error' and '瓶装牌' in reply['message'], reply            # no solver_heart_bottles at all
        reply = backend.heart(next(ids), starter(hp=1, relics=flame, bottles=[{'relic': 'Bottled Flame', 'uuid': 'nope'}]), 11)
        assert reply['status'] == 'error' and '瓶装牌' in reply['message'], reply            # a uuid that is not in the deck
        reply = backend.heart(next(ids), starter(hp=1, relics=flame, bottles=[{'relic': 'Bottled Flame', 'uuid': 'bash'}]), 11)
        assert reply['status'] == 'ready', reply                                           # Bash is an Attack: Bottled Flame fits
        lightning = [{'id': 'Burning Blood', 'counter': -1}, {'id': 'Bottled Lightning', 'counter': -1}]
        reply = backend.heart(next(ids), starter(hp=1, relics=lightning, bottles=[{'relic': 'Bottled Lightning', 'uuid': 'bash'}]), 11)
        assert reply['status'] == 'error' and '瓶装牌' in reply['message'], reply            # an Attack cannot sit in Lightning
        omamori = lambda counter: starter(hp=1, relics=[{'id': 'Burning Blood', 'counter': -1}, {'id': 'Omamori', 'counter': counter}])
        for counter in (2, 1, 0, -2):
            reply = backend.heart(next(ids), omamori(counter), 11)
            assert reply['status'] == 'ready', (counter, reply)
        reply = backend.heart(next(ids), omamori(5), 11)
        assert reply['status'] == 'error' and 'Omamori' in reply['message'], reply
        reply = backend.heart(next(ids), omamori(None), 11)
        assert reply['status'] == 'error' and 'Omamori' in reply['message'], reply

        # --- A5: cancel in the middle of a long search answers within 2 s, the original request never gets a final reply,
        # and a normal solve follows.
        long_id, cancel_id = next(ids), next(ids)
        progress = []
        backend.send({'id': long_id, 'op': 'heart_sim', 'seed': seed_long, 'progress': True,
                      'state': {'in_game': True, 'game_state': long_game}})
        seen_rows = False
        started = time.monotonic()
        while time.monotonic() - started < 120 and not seen_rows:       # wait until the search itself reports counters
            reply = backend.recv(30)
            assert reply['id'] == long_id and reply['status'] == 'progress', reply
            seen_rows = bool(reply.get('branches'))
        assert seen_rows, 'no search counters within 120 s'
        row = reply['branches'][0]
        assert row['id'] == 'heart-all' and {'simulations', 'phase', 'done', 'ready'} <= set(row), row
        sent = time.monotonic()
        backend.send({'id': cancel_id, 'op': 'cancel'})
        while True:
            reply = backend.recv(10)
            if reply['status'] != 'progress':
                break
        waited = time.monotonic() - sent
        assert reply == {'id': cancel_id, 'status': 'cancelled'}, reply
        assert waited < 2, 'cancel took %.2f s' % waited
        assert backend.silent(1.0) is None, 'the cancelled request got another reply'
        reply = backend.send({'id': 1, 'op': 'solve', 'progress': True, 'state': live, 'budget': 2000})
        first = backend.recv(60)
        assert first['status'] == 'progress' and first['timeout_seconds'] == 150, first       # the solver's limit is unchanged
        solved = backend.until_final(1, 120)
        assert solved['status'] == 'ready' and solved.get('command') and not solved.get('interrupted'), solved
        cancelled_wait = waited
        # A cancel that is not for the running request does not touch it: a heart cancel with nothing running just answers.
        stray = next(ids)
        backend.send({'id': stray, 'op': 'cancel'})
        assert backend.recv(10) == {'id': stray, 'status': 'cancelled'}

        # --- A7: one line per finished request, cancelled included, with the plan's fields.
        lines = backend.lines()
        statuses = [(line['status'], line['heart_result']) for line in lines]
        assert statuses.count(('cancelled', None)) == 1 and statuses.count(('error', None)) >= 10, statuses
        assert ('ready', 'win') in statuses and ('ready', 'no_win') in statuses, statuses
        want = {'time', 'floor', 'hp', 'max_hp', 'deck', 'relics', 'potions', 'seed', 'status', 'heart_result', 'seconds',
                'simulations', 'message'}
        assert all(want <= set(line) for line in lines), [sorted(want - set(line)) for line in lines if not want <= set(line)]
        wins = [line for line in lines if line['heart_result'] == 'win' and line['seed'] == seed_win]
        assert len(wins) == 2 and wins[0]['hp'] == 72 and wins[0]['floor'] == 56 and wins[0]['win_hp'] > 0, wins
        assert 'Girya' in wins[0]['relics'] and 'Bash' in wins[0]['deck'] and 'Blood for Blood+1' in wins[0]['deck'], wins[0]
        assert wins[0]['potions'] == ['PowerPotion', 'DistilledChaos'], wins[0]
        killed = [line for line in lines if line['status'] == 'cancelled']
        assert killed[0]['seed'] == seed_long and killed[0]['hp'] == 71 and killed[0]['seconds'] > 0, killed
        errors = [line for line in lines if line['status'] == 'error']
        assert all(line['message'] for line in errors) and any('Tingsha' in line['message'] for line in errors), errors
        return len(lines), cancelled_wait
    finally:
        backend.close()


def check_timeout(folder):
    """A shortened limit (the protocol checks' STS_SEARCH_TIMEOUT_SECONDS) makes an unfinished search `timeout`, not `no_win`."""
    backend = Backend(folder, STS_SEARCH_TIMEOUT_SECONDS='12')
    try:
        progress = []
        started = time.monotonic()
        seed, game = fixture_state(LONG_FIXTURE)
        reply = backend.heart(-1000, game, seed, wait=60, progress=progress)
        took = time.monotonic() - started
        assert reply['status'] == 'ready' and reply['heart_result'] == 'timeout' and reply.get('deadline') is True, reply
        assert progress[0]['timeout_seconds'] == 12 and 6 <= took < 20, (progress[0], took)
        lines = backend.lines()
        assert len(lines) == 1 and lines[0]['heart_result'] == 'timeout' and lines[0]['status'] == 'ready', lines
        return took
    finally:
        backend.close()


def check_counters():
    """Relic counters reach the engine in the game's own convention (heart_sim.py docstring): the first turn shows them."""
    sys.path.insert(0, str(ROOT / 'overlay'))
    import heart_sim
    from backend import Advisor
    advisor = Advisor()
    engine = (advisor.search.sts, advisor.search.comparator.bridge)

    def battle(*relics, bottles=None):
        game = starter(relics=[{'id': 'Burning Blood', 'counter': -1}, *relics], bottles=bottles)
        return heart_sim.build_heart_battle(game, 5, engine=engine)

    # Happy Flower: the counter before the fight; the third turn start of a run of three pays one energy.
    assert [battle({'id': 'Happy Flower', 'counter': c}).player.energy for c in (-1, 0, 1, 2)] == [3, 3, 3, 4]
    assert battle().player.energy == 3
    # Girya: its counter is the Strength it brings into the fight.
    assert [battle({'id': 'Girya', 'counter': c}).player.strength for c in (0, 1, 2, 3)] == [0, 1, 2, 3]
    # A bottled card starts in the hand (Bash is the only Attack the starting deck can bottle here).
    plain = [str(card.id) for card in battle().hand]
    bottled = battle({'id': 'Bottled Flame', 'counter': -1}, bottles=[{'relic': 'Bottled Flame', 'uuid': 'bash'}])
    assert str(bottled.hand[0].id) == 'CardId.BASH', [str(card.id) for card in bottled.hand]
    assert [str(card.id) for card in bottled.hand] != plain or plain[0] == 'CardId.BASH'
    # Omamori: a spent one (0 charges, or -2) is left out; one with charges stays; anything else is refused.
    assert heart_sim.unsupported(starter(relics=[{'id': 'Omamori', 'counter': 2}]), engine=engine) == []
    for bad in (3, -1, None, 'x'):
        assert heart_sim.unsupported(starter(relics=[{'id': 'Omamori', 'counter': bad}]), engine=engine), bad
    # Names the engine cannot map are named; the coverage manifest's missing cards and relics are named too.
    items = heart_sim.unsupported(starter(relics=[{'id': 'Tingsha', 'counter': -1}, {'id': 'Nope', 'counter': -1}],
                                          extra_deck=[{'id': 'Accuracy', 'upgrades': 0, 'uuid': 'x'},
                                                      {'id': 'NoSuchCard', 'name': 'Sorry Card', 'upgrades': 0, 'uuid': 'y'}]),
                                  engine=engine)
    assert any('Tingsha' in item for item in items) and any('Nope' in item for item in items), items
    assert any('Accuracy' in item for item in items) and any('Sorry Card' in item and 'NoSuchCard' in item for item in items), items
    assert heart_sim.unsupported(dict(starter(), **{'class': 'DEFECT'}), engine=engine) == ['暂只支持战士']


def check_stalled(folder, live):
    """A worker that does not come back from a cancelled heart_sim is replaced within the 2 s, and a request sent right behind
    the cancel waits for it and is answered normally."""
    backend = Backend(folder, stalled=True)
    try:
        seed, game = fixture_state(WIN_FIXTURE)
        backend.send({'id': -1000, 'op': 'heart_sim', 'seed': seed, 'progress': True, 'state': {'in_game': True, 'game_state': game}})
        assert backend.recv(60)['status'] == 'progress'
        time.sleep(1.0)
        sent = time.monotonic()
        backend.send({'id': -1001, 'op': 'cancel'})
        backend.send({'id': 1, 'op': 'solve', 'progress': True, 'state': live, 'budget': 2000})
        reply = backend.recv(10)
        while reply['status'] == 'progress':
            reply = backend.recv(10)
        waited = time.monotonic() - sent
        assert reply == {'id': -1001, 'status': 'cancelled'} and waited < 2, (reply, waited)
        solved = backend.until_final(1, 120)
        assert solved['status'] == 'ready' and solved.get('command'), solved
        lines = backend.lines()
        assert [(line['status'], line['seed']) for line in lines] == [('cancelled', seed)], lines
        return waited
    finally:
        backend.close()


def check_hard_stop(folder):
    """A worker that ignores even the soft interrupt is killed at the limit; that is a timeout, not an error."""
    backend = Backend(folder, stalled=True, STS_SEARCH_TIMEOUT_SECONDS='4')
    try:
        seed, game = fixture_state(WIN_FIXTURE)
        started = time.monotonic()
        reply = backend.heart(-1000, game, seed, wait=30)
        took = time.monotonic() - started
        assert reply['status'] == 'ready' and reply['heart_result'] == 'timeout' and reply['seed'] == seed, reply
        assert 3.5 < took < 8, took
        lines = backend.lines()
        assert [(line['status'], line['heart_result']) for line in lines] == [('ready', 'timeout')], lines
    finally:
        backend.close()


def main():
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    ROOT = parser.parse_args().root.resolve()
    with tempfile.TemporaryDirectory(prefix='solver heart sim ') as folder:
        count, cancel_seconds = check_main(Path(folder))
    with tempfile.TemporaryDirectory(prefix='solver heart timeout ') as folder:
        timeout_seconds = check_timeout(Path(folder))
    live = json.loads((ROOT / 'overlay/test/live-root.json').read_text(encoding='utf-8'))
    check_counters()
    with tempfile.TemporaryDirectory(prefix='solver heart stalled ') as folder:
        stalled_seconds = check_stalled(Path(folder), live)
    with tempfile.TemporaryDirectory(prefix='solver heart hard ') as folder:
        check_hard_stop(Path(folder))
    print('PASS: heart_sim: refusals by name (A2), win and no_win (A3), cancel in %.2f s then solve (A5; %.2f s when the worker '
          'stalls), seeds (A6), %d log lines (A7), deep tier 600 s, timeout after %.1f s, hard stop is a timeout, relic counters'
          % (cancel_seconds, stalled_seconds, count, timeout_seconds))


if __name__ == '__main__':
    main()
