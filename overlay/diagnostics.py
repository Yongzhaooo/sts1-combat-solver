"""Local combat debugging: at most 300 events across the latest three runs."""
import json
import sqlite3
import time
import uuid
import copy
import queue
import threading
import re
import zipfile
from contextlib import closing
from pathlib import Path


from collections import namedtuple
RunItem = namedtuple('RunItem', 'kind payload state seed time_ns')


class Reports:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.runs = RunLog(Path(path).parent / 'runs')
        self.session = uuid.uuid4().hex
        self.context = None
        self.db.executescript('''
            PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY, touched INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY, run TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
                floor INTEGER, battle TEXT, time_ns INTEGER NOT NULL, session TEXT NOT NULL,
                kind TEXT NOT NULL, payload TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS battle_events ON events(run,floor,id);
        ''')

    def record(self, kind, payload, state=None, time_ns=None):
        game = (state or {}).get('game_state', {})
        if 'seed' in game:
            # Stable across game/backend restarts and save reloads of this run.
            run = json.dumps([str(game['seed']), game.get('class'), game.get('ascension_level')])
            floor = game.get('floor')
            enemies = [m.get('id') for m in game.get('combat_state', {}).get('monsters', [])]
            self.context = (run, floor, json.dumps(enemies))
        if self.context is None:
            return
        run, floor, battle = self.context
        now = time.time_ns() if time_ns is None else time_ns
        with self.db:
            self.db.execute('INSERT INTO runs VALUES (?,?) ON CONFLICT(id) DO UPDATE SET touched=excluded.touched', (run, now))
            self.db.execute('INSERT INTO events(run,floor,battle,time_ns,session,kind,payload) VALUES (?,?,?,?,?,?,?)',
                            (run, floor, battle, now, self.session, kind,
                             json.dumps(payload, ensure_ascii=False)))
            # Delete only owned database rows, never runtime files or user saves.
            # Wall-clock timestamps can tie (notably Python 3.11 on Windows).
            # Event insertion order also handles revisiting an older run correctly.
            self.db.execute('DELETE FROM runs WHERE id NOT IN (SELECT run FROM events GROUP BY run ORDER BY MAX(id) DESC LIMIT 3)')
            self.db.execute('DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 300)')

    def close(self):
        self.db.close()


class AsyncReports:
    """One ordered writer owns SQLite; retention must not delay combat replies."""
    def __init__(self, path):
        self.inbox = queue.Queue(maxsize=256)
        self.failure = None
        self.last_seed = None
        self.thread = threading.Thread(target=self.write, args=(path,), daemon=True)
        self.thread.start()

    def write(self, path):
        reports = None
        try:
            reports = Reports(path)
            while True:
                item = self.inbox.get()
                if item is None:
                    break
                if isinstance(item, threading.Event):
                    item.set()
                    continue
                if isinstance(item, RunItem):
                    reports.runs.record(*item)
                else:
                    reports.record(*item)
        except Exception as error:
            self.failure = error
        finally:
            if reports is not None:
                reports.close()

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

    def record(self, kind, payload, state=None):
        self.enqueue((kind, copy.deepcopy(payload), copy.deepcopy(state), time.time_ns()))

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


def export_debug(database, directory, metadata=None):
    """Export one recent battle, never the raw database, saves or microphone files."""
    events = []
    if Path(database).exists():
        with closing(sqlite3.connect(Path(database).resolve().as_uri() + '?mode=ro', uri=True)) as db:
            context = db.execute('SELECT run,floor,battle FROM events ORDER BY id DESC LIMIT 1').fetchone()
            if context:
                rows = db.execute('SELECT kind,payload FROM events WHERE run=? AND floor IS ? AND battle IS ? ORDER BY id DESC LIMIT 100', context).fetchall()
                events = [{'kind': kind, 'payload': redact(json.loads(payload))} for kind, payload in reversed(rows)]
    document = {'format': 'sts1-debug-v1', 'purpose': 'bug diagnosis only; no training permission implied',
                'metadata': redact(metadata or {}), 'events': events, 'omitted_events': 0}
    while True:
        payload = json.dumps(document, ensure_ascii=False, indent=2).encode('utf-8')
        if len(payload) <= 2 * 1024 * 1024 or not document['events']:
            break
        document['events'].pop(0)
        document['omitted_events'] += 1
    # No arbitrary file attachments or filesystem paths enter the archive.
    import io
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('debug.json', payload)
        archive.writestr('README.txt', 'Local bug report / 本地错误报告\nReview debug.json before sharing. It may contain seeds, decks, actions and mod IDs.\n分享前检查 debug.json：可能含种子、牌组、动作和模组 ID。\nNo automatic upload. A bug report does not authorize training use.\n不会自动上传；提交错误报告不等于授权用于训练。\n')
    if len(buffer.getvalue()) > 1024 * 1024:
        raise ValueError('Debug export exceeds 1 MiB; report the error text instead')
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / ('debug-' + uuid.uuid4().hex[:12] + '.zip')
    with target.open('xb') as stream:
        stream.write(buffer.getvalue())
    return target


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
                              'enemies': enemies, 'state': state})
            snap = battle_snapshot(state)
            if snap and kind in ('request', 'reply'):
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
