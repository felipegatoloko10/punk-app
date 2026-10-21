"""Private durable state. Only credential digests, never bearer tokens, enter SQLite."""
import hashlib
import hmac
import json
import secrets
import sqlite3
import time
import uuid
from pathlib import Path


def uid():
    return str(uuid.uuid4())


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


class Store:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.files = self.root / 'files'
        self.files.mkdir(exist_ok=True)
        self.db = sqlite3.connect(self.root / 'bridge.sqlite3')
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,name TEXT,token_hash TEXT UNIQUE,revoked INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS pairing(code_hash TEXT PRIMARY KEY,expires REAL,used INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS account_links(owner TEXT,installation TEXT,device TEXT UNIQUE,PRIMARY KEY(owner,installation));
        CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT,device TEXT,type TEXT,payload TEXT);
        CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT);
        CREATE TABLE IF NOT EXISTS sessions(device TEXT,conversation TEXT,hermes_id TEXT,PRIMARY KEY(device,conversation));
        CREATE TABLE IF NOT EXISTS messages(device TEXT,conversation TEXT,id TEXT,role TEXT,text TEXT,status TEXT,created REAL,PRIMARY KEY(device,id));
        CREATE TABLE IF NOT EXISTS requests(device TEXT,id TEXT,conversation TEXT,run_id TEXT,status TEXT,last_seq INTEGER DEFAULT -1,PRIMARY KEY(device,id));
        CREATE TABLE IF NOT EXISTS calls(id TEXT PRIMARY KEY,device TEXT,tool TEXT,status TEXT,result TEXT,params_hash TEXT);
        CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY,device TEXT,name TEXT,mime TEXT,size INTEGER,sha256 TEXT,offset INTEGER DEFAULT 0,complete INTEGER DEFAULT 0);
        CREATE INDEX IF NOT EXISTS events_device_seq ON events(device,seq);
        CREATE INDEX IF NOT EXISTS messages_conversation ON messages(device,conversation,created);
        ''')
        self.db.commit()
        if 'params_hash' not in {row[1] for row in self.db.execute('PRAGMA table_info(calls)')}:
            self.execute('ALTER TABLE calls ADD COLUMN params_hash TEXT')

    def execute(self, sql, args=()):
        cur = self.db.execute(sql, args)
        self.db.commit()
        return cur

    def get(self, key, default=None):
        row = self.db.execute('SELECT value FROM settings WHERE key=?', (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def set(self, key, value):
        self.execute('INSERT OR REPLACE INTO settings VALUES (?,?)', (key, json.dumps(value)))

    def pairing_code(self):
        code = secrets.token_urlsafe(18)
        self.execute('INSERT INTO pairing VALUES (?,?,0)', (digest(code), time.time() + 300))
        return code

    def pair(self, code, name):
        with self.db:
            row = self.db.execute('SELECT expires,used FROM pairing WHERE code_hash=?', (digest(code),)).fetchone()
            if not row or row['used'] or row['expires'] < time.time():
                return None
            self.db.execute('UPDATE pairing SET used=1 WHERE code_hash=?', (digest(code),))
            device, token = uid(), secrets.token_urlsafe(48)
            self.db.execute('INSERT INTO devices(id,name,token_hash) VALUES (?,?,?)', (device, name[:100], digest(token)))
        return {'deviceId': device, 'token': token, 'protocolVersion': 3}

    def authenticate(self, token):
        row = self.db.execute('SELECT id FROM devices WHERE token_hash=? AND revoked=0', (digest(token),)).fetchone()
        return row[0] if row else None

    def account_pair(self, owner, installation, key, existing_device=None):
        """Idempotent device identity. Revoked installations never silently re-enroll."""
        with self.db:
            row = self.db.execute('SELECT device FROM account_links WHERE owner=? AND installation=?',
                                  (owner, installation)).fetchone()
            adopt = existing_device and self.db.execute(
                'SELECT id FROM devices WHERE id=? AND revoked=0 AND id NOT IN (SELECT device FROM account_links)',
                (existing_device,)).fetchone()
            device = row[0] if row else existing_device if adopt else uid()
            token = hmac.new(bytes.fromhex(key), ('punk-account-v1:' + device).encode(), hashlib.sha256).hexdigest()
            if row:
                existing = self.db.execute('SELECT revoked,token_hash FROM devices WHERE id=?', (device,)).fetchone()
                if not existing or existing['revoked'] or not hmac.compare_digest(existing['token_hash'], digest(token)):
                    return None
            else:
                if adopt:
                    self.db.execute('UPDATE devices SET token_hash=? WHERE id=?', (digest(token), device))
                else:
                    self.db.execute('INSERT INTO devices(id,name,token_hash) VALUES (?,?,?)',
                                    (device, 'Conta Punk', digest(token)))
                self.db.execute('INSERT INTO account_links VALUES (?,?,?)', (owner, installation, device))
        return {'deviceId': device, 'token': token, 'protocolVersion': 3}

    def event(self, device, kind, payload):
        seq = self.execute('INSERT INTO events(device,type,payload) VALUES (?,?,?)', (device, kind, json.dumps(payload))).lastrowid
        return {'id': f'event:{seq}', 'type': kind, 'payload': {**payload, 'seq': seq}}

    def events(self, device, after=0):
        rows = self.db.execute('SELECT * FROM events WHERE device=? AND seq>? ORDER BY seq LIMIT 1000', (device, after))
        return [{'id': f"event:{r['seq']}", 'type': r['type'], 'payload': {**json.loads(r['payload']), 'seq': r['seq']}} for r in rows]

    def last_seq(self, device):
        return self.db.execute('SELECT COALESCE(MAX(seq),0) FROM events WHERE device=?', (device,)).fetchone()[0]

    def message(self, device, conversation, mid, role, text, status):
        self.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?) ON CONFLICT(device,id) DO UPDATE SET text=excluded.text,status=excluded.status', (device, conversation, mid, role, text, status, time.time()))

    def request(self, device, mid, conversation):
        return self.execute('INSERT OR IGNORE INTO requests(device,id,conversation,status) VALUES (?,?,?,?)', (device, mid, conversation, 'queued')).rowcount == 1

    def history(self, device, conversation):
        return [{'messageId': r['id'], 'role': r['role'], 'text': r['text'], 'status': r['status'], 'createdAt': r['created']} for r in self.db.execute('SELECT * FROM messages WHERE device=? AND conversation=? ORDER BY created', (device, conversation))]
