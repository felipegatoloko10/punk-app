"""Shared, durable execution path for Hermes and local MCP integrations.

Only loopback administration can enroll/revoke clients. MCP credentials cannot use
the administrative API. A timeout or disconnect never authorizes replay.
"""
import asyncio
import hashlib
import json
import re
import secrets
import time
from pathlib import Path

from aiohttp import web
from jsonschema import Draft202012Validator, ValidationError, SchemaError

try:
    from .account import WindowsSecret
    from .storage import digest, uid
except ImportError:
    from account import WindowsSecret
    from storage import digest, uid

FINAL = {'completed', 'failed', 'cancelled'}
UNCERTAIN = {'unknown', 'cancel_requested'}
ID = re.compile(r'^[A-Za-z0-9_.:-]{1,180}$')


def identifier(value):
    if not isinstance(value, str) or not ID.fullmatch(value):
        raise web.HTTPBadRequest(reason='Invalid identifier')
    return value


class Control:
    def init_control(self):
        self.call_tasks, self.device_locks, self.tool_schemas = {}, {}, {}
        self.capabilities = {}
        # Queued actions are not replayed after restart. In-flight effects are unknown.
        self.store.execute("UPDATE calls SET status='cancelled' WHERE status='queued'")
        self.store.execute("UPDATE calls SET status='unknown' WHERE status IN ('running','timeout','cancel_requested')")

    def integration_auth(self, token):
        row = self.store.db.execute('SELECT * FROM integrations WHERE token_hash=? AND revoked=0',
                                    (digest(token),)).fetchone()
        return dict(row) if row else None

    def client_active(self, client):
        return client == 'hermes' or self.store.db.execute(
            'SELECT 1 FROM integrations WHERE id=? AND revoked=0', (client,)).fetchone() is not None

    def actor_label(self, actor):
        row = self.store.db.execute('SELECT name FROM integrations WHERE id=?', (actor,)).fetchone()
        return row['name'] if row else 'Hermes' if actor == 'hermes' else actor

    def allowed_device(self, client, device):
        return client is None or not json.loads(client['devices']) or device in json.loads(client['devices'])

    def control_routes(self, app):
        app.router.add_get('/api/manage', self.management)
        app.router.add_post('/api/manage/{action}', self.manage_action)
        for prefix in ('/api/mcp',):
            app.router.add_get(prefix + '/devices', self.mcp_devices)
            app.router.add_get(prefix + '/tools', self.mcp_tools)
            app.router.add_get(prefix + '/events', self.mcp_events)
            app.router.add_post(prefix + '/execute', self.mcp_execute)
            app.router.add_get(prefix + '/calls/{call_id}', self.mcp_call_status)
            app.router.add_post(prefix + '/calls/{call_id}/cancel', self.mcp_cancel)

    def device_view(self, client=None):
        rows = self.store.db.execute('SELECT id,name,revoked FROM devices ORDER BY name,id').fetchall()
        return [{**dict(r), 'connected': r['id'] in self.sockets,
                 'capabilities': self.capabilities.get(r['id']),
                 'voice': {k:v for k,v in self.voice_sessions.get(r['id'], {}).items()
                           if k in ('active','phase','event','updatedAt','onDeviceRecognition','bargeInSupported')},
                 'capabilitiesFresh': r['id'] in self.sockets and r['id'] in self.capabilities,
                 'toolCount': len(self.catalogs.get(r['id'], []))}
                for r in rows if self.allowed_device(client, r['id']) and (client is None or not r['revoked'])]

    async def management(self, request):
        clients = [dict(r) for r in self.store.db.execute(
            'SELECT id,name,devices,read_only,revoked,created FROM integrations ORDER BY created DESC')]
        for row in clients:
            row['devices'] = json.loads(row['devices'])
        calls = [dict(r) for r in self.store.db.execute('''SELECT c.id,c.device,c.tool,c.status,
            m.client,m.created,m.updated FROM calls c LEFT JOIN call_meta m ON c.id=m.id
            ORDER BY COALESCE(m.created,0) DESC,c.rowid DESC LIMIT 100''')]
        audit = [dict(r) for r in self.store.db.execute('SELECT * FROM audit ORDER BY seq DESC LIMIT 100')]
        return web.json_response({'devices': self.device_view(), 'integrations': clients, 'calls': calls, 'audit': audit})

    async def manage_action(self, request):
        body = await request.json()
        action = request.match_info['action']
        if action == 'create-client':
            name = body.get('name', '')
            devices = body.get('devices', [])
            read_only = body.get('readOnly', True)
            if not isinstance(name, str) or not name.strip() or len(name) > 100 or not isinstance(read_only, bool):
                raise web.HTTPBadRequest()
            if not isinstance(devices, list) or len(devices) > 100:
                raise web.HTTPBadRequest()
            for device in devices:
                identifier(device)
                if not self.store.db.execute('SELECT 1 FROM devices WHERE id=? AND revoked=0', (device,)).fetchone():
                    raise web.HTTPBadRequest(reason='Unknown device')
            client, token = uid(), secrets.token_urlsafe(48)
            runtime = Path(self.config.get('_runtimePath', self.store.root)).resolve()
            port = int(self.config.get('internalPort', 18766))
            if not 1 <= port <= 65535:
                raise web.HTTPBadRequest()
            # Secret stays under the current Windows user's DPAPI; exported config has no bearer.
            WindowsSecret(runtime / 'mcp' / (client + '.dpapi')).save({'client': client, 'token': token, 'port': port})
            self.store.execute('INSERT INTO integrations VALUES (?,?,?,?,?,0,?)',
                               (client, name.strip(), digest(token), json.dumps(devices), int(read_only), time.time()))
            self.store.audit('desktop', 'create-client', client, 'read_only' if read_only else 'full')
            return web.json_response({'ok': True, 'id': client})
        target = identifier(body.get('id'))
        if action == 'cancel-device-calls':
            rows = self.store.db.execute("SELECT id FROM calls WHERE device=? AND status NOT IN ('completed','failed','cancelled')", (target,)).fetchall()
            await self.cancel_many(target, [r['id'] for r in rows])
            pending = self.store.db.execute("SELECT COUNT(*) FROM calls WHERE device=? AND status NOT IN ('completed','failed','cancelled')", (target,)).fetchone()[0]
            self.store.audit('desktop', action, target, 'unconfirmed' if pending else 'stopped')
            return web.json_response({'ok': pending == 0, 'status': 'cancel_requested' if pending else 'cancelled', 'calls': len(rows)})
        if action == 'allow-repair':
            if not self.store.db.execute('SELECT 1 FROM devices WHERE id=? AND revoked=1', (target,)).fetchone():
                raise web.HTTPConflict(reason='Revoke device first')
            # Explicit administrator consent permits a NEW account enrollment; the old bearer stays revoked.
            self.store.execute('DELETE FROM account_links WHERE device=?', (target,))
        elif action in ('disconnect-device', 'revoke-device'):
            if not self.store.db.execute('SELECT 1 FROM devices WHERE id=?', (target,)).fetchone():
                raise web.HTTPNotFound()
            if action == 'revoke-device':
                self.store.execute('UPDATE devices SET revoked=1 WHERE id=?', (target,))
            # No new effects after revocation, including queued requests from Hermes.
            for row in self.store.db.execute("SELECT id FROM calls WHERE device=? AND status='queued'", (target,)).fetchall():
                self.call_state(row['id'], 'cancelled')
            ws = self.sockets.get(target)
            if action == 'revoke-device':
                try:
                    # Stop this phone's bound Hermes runs as well; do not affect Telegram or other devices.
                    await self.cancel(target)
                except Exception:
                    self.store.audit('desktop', 'revoke-device-stop', target, 'unconfirmed')
            if ws:
                await ws.close(code=4003 if action == 'revoke-device' else 4000,
                               message=b'Access revoked' if action == 'revoke-device' else b'Disconnected by desktop')
        elif action == 'revoke-client':
            if not self.store.execute('UPDATE integrations SET revoked=1 WHERE id=?', (target,)).rowcount:
                raise web.HTTPNotFound()
            rows = self.store.db.execute('''SELECT c.id FROM calls c JOIN call_meta m ON c.id=m.id
                    WHERE m.client=? AND c.status NOT IN ('completed','failed','cancelled')''', (target,)).fetchall()
            await asyncio.gather(*(self.cancel_call(row['id']) for row in rows))
        elif action == 'cancel-call':
            result = await self.cancel_call(target)
            self.store.audit('desktop', action, target, result['status'])
            return web.json_response(result)
        else:
            raise web.HTTPNotFound()
        self.store.audit('desktop', action, target, 'applied')
        return web.json_response({'ok': True})

    def selected_device(self, request, value):
        client = request.get('integration')
        if value is None:
            candidates = [d for d in self.sockets if self.allowed_device(client, d)]
            if len(candidates) != 1:
                raise web.HTTPConflict(reason='Select one authorized deviceId')
            value = candidates[0]
        identifier(value)
        if not self.allowed_device(client, value):
            raise web.HTTPForbidden()
        if not self.store.db.execute('SELECT 1 FROM devices WHERE id=? AND revoked=0', (value,)).fetchone():
            raise web.HTTPNotFound()
        return value

    async def mcp_devices(self, request):
        return web.json_response({'ok': True, 'devices': self.device_view(request.get('integration'))})

    async def mcp_tools(self, request):
        device = self.selected_device(request, request.query.get('deviceId'))
        client = request.get('integration')
        tools = self.tool_schemas.get(device, {})
        caps = self.capabilities.get(device, {})
        availability = {t['name']: t for t in caps.get('tools', []) if isinstance(t, dict) and 'name' in t}
        return web.json_response({'ok': True, 'deviceId': device, 'connected': device in self.sockets,
            'tools': [{**t, 'availability': availability.get(n, {'available': None, 'reason': 'Refresh device capabilities'})}
                      for n, t in tools.items() if client is None or not client['read_only'] or t.get('risk') == 'reading']})

    async def mcp_events(self, request):
        device = self.selected_device(request, request.query.get('deviceId'))
        after = max(0, int(request.query.get('after', 0)))
        # Events are observations, never agent instructions. Existing Android event cursor is reused.
        events = self.store.events(device, after)
        return web.json_response({'ok': True, 'events': events,
            'cursor': events[-1]['payload']['seq'] if events else after,
            'observations': list(self.device_events.get(device, [])),
            'voice': self.voice_sessions.get(device, {})})

    def call_view(self, call_id, client=None):
        row = self.store.db.execute('''SELECT c.*,m.client,m.conversation FROM calls c
            LEFT JOIN call_meta m ON m.id=c.id WHERE c.id=?''', (identifier(call_id),)).fetchone()
        if not row or (client and (row['client'] != client['id'] or not self.allowed_device(client, row['device']))):
            raise web.HTTPNotFound()
        return {'ok': row['status'] == 'completed', 'callId': row['id'], 'deviceId': row['device'],
                'tool': row['tool'], 'clientId': row['client'], 'status': row['status'],
                'outcomeKnown': row['status'] in {'completed', 'failed'}, 'settled': row['status'] in FINAL,
                'effectsMayHaveOccurred': row['status'] != 'queued',
                'data': json.loads(row['result']) if row['result'] else None}

    async def mcp_call_status(self, request):
        return web.json_response(self.call_view(request.match_info['call_id'], request.get('integration')))

    async def mcp_execute(self, request):
        body = await request.json()
        device = self.selected_device(request, body.get('deviceId'))
        client = request.get('integration')
        # An explicit id is required so a retry of the HTTP/MCP request cannot duplicate an action.
        identifier(body.get('callId'))
        call_id = self.admit_call(device, body, client['id'] if client else 'hermes', client)
        return web.json_response(self.call_view(call_id, client), status=202)

    async def mcp_cancel(self, request):
        call_id = request.match_info['call_id']
        self.call_view(call_id, request.get('integration'))
        return web.json_response(await self.cancel_call(call_id))

    def call_state(self, call_id, status, result=None):
        self.store.execute('UPDATE calls SET status=?,result=COALESCE(?,result) WHERE id=?',
                           (status, json.dumps(result) if result is not None else None, call_id))
        self.store.execute('UPDATE call_meta SET updated=? WHERE id=?', (time.time(), call_id))
        row = self.store.db.execute('SELECT client FROM call_meta WHERE id=?', (call_id,)).fetchone()
        self.store.audit(row['client'] if row else 'hermes', 'call', call_id, status)

    def admit_call(self, device, body, actor='hermes', client=None):
        tool, params = body.get('tool'), body.get('params', {})
        call_id = identifier(body.get('callId') or uid())
        if not isinstance(tool, str) or not isinstance(params, dict):
            raise web.HTTPBadRequest()
        params_hash = hashlib.sha256(json.dumps(params, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        existing = self.store.db.execute('''SELECT c.*,m.client FROM calls c LEFT JOIN call_meta m ON m.id=c.id
                                            WHERE c.id=?''', (call_id,)).fetchone()
        if existing:
            if (existing['device'], existing['tool'], existing['params_hash'], existing['client'] or 'hermes') != (device, tool, params_hash, actor):
                raise web.HTTPConflict(reason='callId already bound to another request')
            return call_id
        if device not in self.sockets:
            raise web.HTTPServiceUnavailable(reason='Phone disconnected')
        if tool not in self.catalogs.get(device, set()):
            raise web.HTTPBadRequest(reason='Tool not advertised by device')
        definition = self.tool_schemas.get(device, {}).get(tool, {})
        if client:
            if client['read_only'] and definition.get('risk') != 'reading':
                raise web.HTTPForbidden(reason='Read-only integration')
            schema = definition.get('parameters')
            if not isinstance(schema, dict):
                raise web.HTTPBadRequest(reason='Device schema unavailable; update Android app')
            try:
                Draft202012Validator.check_schema(schema)
                Draft202012Validator(schema).validate(params)
            except (ValidationError, SchemaError):
                raise web.HTTPBadRequest(reason='Arguments do not match device schema') from None
        if self.switching:
            raise web.HTTPConflict(reason='Control mode transition in progress')
        unknown = self.store.db.execute("SELECT 1 FROM calls WHERE device=? AND status IN ('unknown','cancel_requested')", (device,)).fetchone()
        if unknown:
            raise web.HTTPConflict(reason='Resolve or cancel unknown calls before another action')
        count = self.store.db.execute("SELECT COUNT(*) FROM calls WHERE device=? AND status IN ('queued','running')", (device,)).fetchone()[0]
        if count >= 32:
            raise web.HTTPTooManyRequests()
        timeout = min(300, max(1, float(body.get('timeout', 30))))
        conversation = identifier(body.get('conversationId') or 'default')
        self.store.execute('INSERT INTO calls VALUES (?,?,?,?,NULL,?)', (call_id, device, tool, 'queued', params_hash))
        self.store.execute('INSERT INTO call_meta VALUES (?,?,?,?,?)', (call_id, actor, conversation, time.time(), time.time()))
        self.store.audit(actor, 'call', call_id, 'queued')
        task = asyncio.create_task(self.dispatch_call(call_id, device, tool, params, conversation, actor, timeout))
        self.call_tasks[call_id] = task
        task.add_done_callback(lambda done: self.call_tasks.pop(call_id, None))
        return call_id

    async def dispatch_call(self, call_id, device, tool, params, conversation, actor, timeout):
        future = None
        try:
            async with self.device_locks.setdefault(device, asyncio.Lock()):
                state = self.call_view(call_id)['status']
                if state != 'queued':
                    return
                if not self.client_active(actor) or self.switching or not self.store.db.execute(
                        'SELECT 1 FROM devices WHERE id=? AND revoked=0', (device,)).fetchone():
                    self.call_state(call_id, 'cancelled')
                    return
                if device not in self.sockets or self.store.db.execute(
                        "SELECT 1 FROM calls WHERE device=? AND id!=? AND status IN ('unknown','cancel_requested')", (device, call_id)).fetchone():
                    self.call_state(call_id, 'failed', {'ok': False, 'error': 'Not dispatched: phone unavailable or prior outcome unknown'})
                    return
                future = asyncio.get_running_loop().create_future()
                self.pending_calls[call_id] = {'device': device, 'future': future, 'conversation': conversation}
                self.call_state(call_id, 'running')
                await self.send(device, 'tool_call', {'callId': call_id, 'tool': tool, 'params': params,
                    'supervised': self.supervised, 'conversationId': conversation, 'clientId': actor})
                await self.emit(device, 'task_event', {'id': call_id, 'title': tool, 'detail': 'Cliente: ' + self.actor_label(actor),
                    'device': 'Celular', 'status': 'running', 'ts': time.time(), 'conversationId': conversation})
                try:
                    result = await asyncio.wait_for(asyncio.shield(future), timeout)
                except asyncio.TimeoutError:
                    self.call_state(call_id, 'unknown')
                    await self.emit(device, 'task_event', {'id': call_id, 'title': tool, 'detail': 'Prazo excedido. Resultado desconhecido; não repetir.',
                        'device': 'Celular', 'status': 'unverified', 'ts': time.time(), 'conversationId': conversation})
                    result = await future  # Keep the device lease until a late result or confirmed stop.
                if not result.get('outcomeUnknown'):
                    status = 'cancelled' if result.get('cancelled') else 'completed' if result.get('ok') is True else 'failed'
                    self.call_state(call_id, status, result)
                    await self.emit(device, 'task_event', {'id': call_id, 'title': tool, 'detail': 'Cliente: ' + self.actor_label(actor),
                        'device': 'Celular', 'status': status, 'ts': time.time(), 'conversationId': conversation})
                else:
                    self.call_state(call_id, 'unknown')
        except asyncio.CancelledError:
            if self.call_view(call_id)['status'] not in FINAL:
                self.call_state(call_id, 'unknown')
            raise
        except Exception:
            # Sending can fail after bytes reached the phone: do not claim not executed.
            self.call_state(call_id, 'unknown')
        finally:
            self.pending_calls.pop(call_id, None)

    async def cancel_call(self, call_id):
        view = self.call_view(call_id)
        if view['status'] in FINAL:
            return view
        await self.cancel_many(view['deviceId'], [call_id])
        return self.call_view(call_id)

    async def cancel_many(self, device, call_ids):
        running = []
        for call_id in call_ids:
            view = self.call_view(call_id)
            if view['deviceId'] != device or view['status'] in FINAL:
                continue
            if view['status'] == 'queued':
                self.call_state(call_id, 'cancelled')
                task = self.call_tasks.get(call_id)
                if task:
                    task.cancel()
            else:
                running.append(call_id)
        if not running:
            return
        for call_id in running:
            self.call_state(call_id, 'cancel_requested')
            self.cancel_waiters[(device, call_id)] = asyncio.get_running_loop().create_future()
        try:
            await self.send(device, 'cancel_tools', {'callIds': running})
            await asyncio.wait_for(asyncio.gather(*(self.cancel_waiters[(device, c)] for c in running)), 5)
            # ACK is emitted by Android only after the local cancellation barrier settles.
            for call_id in running:
                if self.call_view(call_id)['status'] not in FINAL:
                    self.call_state(call_id, 'cancelled')
                entry = self.pending_calls.get(call_id)
                if entry and not entry['future'].done():
                    entry['future'].set_result({'ok': False, 'cancelled': True})
        except (asyncio.TimeoutError, web.HTTPException, ConnectionError):
            pass  # A request is not a confirmation. Keep the lease/unknown outcome.
        finally:
            for call_id in running:
                self.cancel_waiters.pop((device, call_id), None)
