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


class Reports:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
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
            self.db.execute('DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY touched DESC LIMIT 3)')
            self.db.execute('DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 300)')

    def close(self):
        self.db.close()


class AsyncReports:
    """One ordered writer owns SQLite; retention must not delay combat replies."""
    def __init__(self, path):
        self.inbox = queue.Queue(maxsize=256)
        self.failure = None
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
