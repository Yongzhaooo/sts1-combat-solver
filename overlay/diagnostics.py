import json
import time
import uuid
import copy
import queue
import threading
import re
from pathlib import Path


from collections import namedtuple
RunItem = namedtuple('RunItem', 'kind payload state seed time_ns')


class AsyncReports:
    """One ordered writer owns disk writes; retention must not delay combat replies."""
    def __init__(self, directory):
        self.directory = Path(directory)
        self.runs = RunLog(self.directory)
        self.inbox = queue.Queue(maxsize=256)
        self.failure = None
        self.last_seed = None
        self.thread = threading.Thread(target=self.write, daemon=True)
        self.thread.start()

    def write(self):
        try:
            while True:
                item = self.inbox.get()
                if item is None:
                    break
                if isinstance(item, threading.Event):
                    item.set()
                    continue
                if isinstance(item, RunItem):
                    self.runs.record(*item)
        except Exception as error:
            self.failure = error

    def check(self):
        if self.failure is not None:
            raise RuntimeError('战斗报告写入失败') from self.failure

    def enqueue(self, item):
        # Bounded backpressure only if storage stalls beyond 256 pending events.
        while True:
            self.check()
            try:
                self.inbox.put(item, timeout=.1)
                return
            except queue.Full:
                continue

    def runs_seed(self):
        return self.last_seed

    def run(self, kind, payload, state=None, seed=None):
        """Continuous per-seed record; written by the same single writer thread."""
        game = (state or {}).get('game_state', {})
        if 'seed' in game:
            self.last_seed = str(game['seed'])
        elif seed is not None:
            self.last_seed = str(seed)
        self.enqueue(RunItem(kind, copy.deepcopy(payload), copy.deepcopy(state), seed, time.time_ns()))

    def close(self):
        self.enqueue(None)
        self.thread.join()
        self.check()

    def flush(self):
        barrier = threading.Event()
        self.enqueue(barrier)
        while not barrier.wait(.1):
            self.check()


PRIVATE_KEYS = {'username', 'user_name', 'player_name', 'steam_id', 'steamid',
                'email', 'password', 'token', 'access_token', 'authorization',
                'home', 'hostname', 'voice_notes', 'decision_session', 'session'}
LOCAL_PATH = re.compile(r'(?:[A-Za-z]:[\\/]|/(?:mnt|home|Users|tmp)/)[^\s\"\'<>]+')
SECRET = re.compile(r'(?i)(?:bearer\s+\S+|(?:token|password|api[_-]?key)\s*[=:]\s*[^\s,;]+)')


def redact(value):
    """Best-effort redaction; users still review the human-readable export."""
    if isinstance(value, dict):
        return {k: redact(v) for k, v in value.items() if k.lower() not in PRIVATE_KEYS}
    if isinstance(value, list):
        return [redact(v) for v in value]
    if isinstance(value, str):
        return SECRET.sub('<redacted>', LOCAL_PATH.sub('<local-path>', value))
    return value


RUN_KEEP_SEEDS = 20
RUN_KEEP_BYTES = 50 * 1024 * 1024


def fault_signature(message='', stderr='', exitcode=None):
    """Stable short label for grouping the same failure across users."""
    text = '%s\n%s' % (message or '', stderr or '')
    card = re.search(r'unimplemented card:\s*([A-Za-z0-9_ ]+)', text)
    if card:
        return 'unimplemented_card-' + card.group(1).strip()
    where = re.search(r'Assertion failed:[^\n]*?,\s*([A-Za-z0-9_.]+):(\d+)', text)
    if where:
        return 'assert-%s-%s' % (where.group(1), where.group(2))
    if '偏离预测' in text or '状态偏离' in text:
        return 'state_divergence'
    for name in ('IdentityDifference', 'BrokenPipeError', 'MemoryError'):
        if name in text:
            return name
    if '超时' in text:
        return 'timeout'
    if '意外退出' in text:
        return 'worker_exit'
    return 'error'


def safe_name(text, limit=40):
    return re.sub(r'[^A-Za-z0-9_.-]+', '-', str(text)).strip('-')[:limit] or 'x'


def battle_snapshot(state):
    """Few fields per step so a replay can notice the first divergence cheaply."""
    game = (state or {}).get('game_state', {})
    combat = game.get('combat_state') or {}
    if not combat:
        return None
    player = combat.get('player', {})
    return {'turn': combat.get('turn'), 'hp': game.get('current_hp'), 'energy': player.get('energy'),
            'block': player.get('block'),
            'monsters': [[m.get('id'), m.get('current_hp'), m.get('is_gone')] for m in combat.get('monsters', [])],
            'hand': [c.get('id') for c in combat.get('hand', [])]}


def combat_open_summary(state):
    """Extract key summary without dumping the raw state tree (kept under 1 KB)."""
    game = (state or {}).get('game_state', {})
    combat = game.get('combat_state') or {}
    monsters = [{'id': m.get('id'), 'hp': m.get('current_hp'), 'max_hp': m.get('max_hp')}
                for m in combat.get('monsters', [])]
    deck = [c.get('id') + ('+' if c.get('upgrades', 0) > 0 else '')
            for c in game.get('deck', []) if c.get('id')]
    relics = [r.get('id') for r in game.get('relics', []) if r.get('id')]
    potions = [p.get('id') for p in game.get('potions', []) if p.get('id')]
    keys = {
        'ruby': bool(game.get('has_ruby_key')),
        'emerald': bool(game.get('has_emerald_key')),
        'sapphire': bool(game.get('has_sapphire_key')),
    }
    return {
        'hp': game.get('current_hp'),
        'max_hp': game.get('max_hp'),
        'monsters': monsters,
        'deck': deck,
        'relics': relics,
        'potions': potions,
        'keys': keys,
    }


class RunLog:
    """One append-only file per seed. The exported json is assembled from it."""
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.session = uuid.uuid4().hex[:12]
        self.seed = None
        self.battle = {}
        self.opened = set()

    def path(self, seed):
        return self.directory / (safe_name(seed, 64) + '.jsonl')

    def record(self, kind, payload, state=None, seed=None, time_ns=None):
        game = (state or {}).get('game_state', {})
        if 'seed' in game:
            seed = str(game['seed'])
        seed = seed if seed is not None else self.seed
        if seed is None:
            return
        seed = str(seed)
        self.seed = seed
        row = {'k': kind, 't': time.time_ns() if time_ns is None else time_ns, 's': self.session}
        if game.get('floor') is not None:
            row['f'] = game['floor']
        row.update(payload)
        lines = []
        if seed not in self.opened:
            self.opened.add(seed)
            self.prune(keep=seed)
            self.terminate_line(self.path(seed))
            lines.append({'k': 'session', 't': row['t'], 's': self.session,
                          'class': game.get('class'), 'ascension': game.get('ascension_level')})
        if state is not None and game.get('combat_state'):
            enemies = [m.get('id') for m in game['combat_state'].get('monsters', [])]
            battle = (game.get('floor'), tuple(enemies))
            if self.battle.get(seed) != battle:
                self.battle[seed] = battle
                lines.append({'k': 'combat_open', 't': row['t'], 's': self.session, 'f': game.get('floor'),
                              'enemies': enemies, 'summary': combat_open_summary(state)})
            snap = battle_snapshot(state)
            if snap and kind == 'request':
                row['snap'] = snap
        lines.append(row)
        with self.path(seed).open('a', encoding='utf-8') as stream:
            stream.write(''.join(json.dumps(line, ensure_ascii=False, separators=(',', ':')) + '\n' for line in lines))

    @staticmethod
    def terminate_line(path):
        """A crash can leave a torn last line; never glue the next record onto it."""
        if path.exists() and path.stat().st_size:
            with path.open('rb+') as stream:
                stream.seek(-1, 2)
                if stream.read(1) != b'\n':
                    stream.write(b'\n')

    def prune(self, keep):
        files = sorted((f for f in self.directory.glob('*.jsonl') if f.stem != safe_name(keep, 64)),
                       key=lambda f: f.stat().st_mtime, reverse=True)
        total = 0
        for index, file in enumerate(files):
            total += file.stat().st_size
            if index >= RUN_KEEP_SEEDS - 1 or total > RUN_KEEP_BYTES:
                try:
                    file.unlink()  # Only files this class created in its own directory.
                except OSError:
                    pass


def export_run(directory, seed, target_dir, metadata=None):
    """Write the seed's whole record as ONE json file for the user to send."""
    path = Path(directory) / (safe_name(seed, 64) + '.jsonl')
    if not path.exists():
        raise FileNotFoundError('还没有这个种子的记录')
    events = []
    with path.open('r', encoding='utf-8') as stream:
        for line in stream:
            try:
                events.append(json.loads(line))
            except ValueError:
                pass  # A torn last line after a crash must not block the export.
    fault = next((e for e in reversed(events) if e.get('k') == 'fault'), None)
    signature = safe_name(fault.get('sig', 'error')) if fault else 'run'
    floor = max([e['f'] for e in events if isinstance(e.get('f'), int)] or [0])
    meta = metadata or {}
    own = next((m.get('version') for m in meta.get('mods', []) if 'solver' in str(m.get('id', '')).lower()), None)
    version = safe_name(meta.get('mod_version') or own or 'x', 12)
    name = 'sts1-%s-%s-F%d-v%s-%s.json' % (signature, safe_name(seed, 20), floor, version,
                                           time.strftime('%m%d-%H%M'))
    document = {'format': 'sts1-run-v1', 'purpose': 'bug diagnosis; no training permission implied',
                'seed': str(seed), 'metadata': redact(meta), 'events': redact(events)}
    target = Path(target_dir)
    target.mkdir(parents=True, exist_ok=True)
    out = target / name
    with out.open('w', encoding='utf-8') as stream:
        json.dump(document, stream, ensure_ascii=False, separators=(',', ':'))
    return out
