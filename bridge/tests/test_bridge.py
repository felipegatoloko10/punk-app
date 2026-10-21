import asyncio
import hashlib
import importlib.util
import json
import tempfile
import ssl
import unittest
from pathlib import Path
from unittest.mock import patch

from aiohttp import web
import aiohttp
from aiohttp.test_utils import TestClient, TestServer

from server import Bridge, certificate, parse_adb_devices, safe_preview
from storage import Store


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(self.temp.name)
        self.bridge = Bridge({'internalToken': 'test-internal-only', 'hermesToken': 'test-upstream-only',
                              'hermesUrl': 'http://127.0.0.1:1/p/punk-mobile'}, self.store)
        self.client = TestClient(TestServer(self.bridge.public_app()))
        await self.client.start_server()
        self.internal = TestClient(TestServer(self.bridge.internal_app()))
        await self.internal.start_server()
        result = self.store.pair(self.store.pairing_code(), 'Test phone')
        self.device, self.token = result['deviceId'], result['token']
        self.auth = {'Authorization': 'Bearer ' + self.token}
        self.internal_auth = {'Authorization': 'Bearer test-internal-only'}

    async def asyncTearDown(self):
        await self.internal.close(); await self.client.close()
        self.store.db.close(); self.temp.cleanup()

    async def socket(self):
        ws = await self.client.ws_connect('/punk-bridge', headers=self.auth)
        await ws.send_json({'id': 'hello-test', 'type': 'hello', 'payload': {'protocolVersion': 3, 'deviceId': self.device, 'tools': [{'name': 'device_status'}]}})
        ack = await ws.receive_json()
        self.assertTrue(ack['payload']['authenticated'])
        return ws

    async def test_public_and_internal_require_separate_auth(self):
        self.assertEqual((await self.client.get('/api/status')).status, 401)
        self.assertEqual((await self.internal.get('/api/status', headers=self.auth)).status, 401)
        self.assertEqual((await self.internal.get('/api/status', headers=self.internal_auth)).status, 200)

    def test_adb_device_states_are_reported_without_identifiers(self):
        connected = parse_adb_devices('List of devices attached\nR58M123 device product:x model:Phone transport_id:1\n')
        waiting = parse_adb_devices('List of devices attached\nR58M123 unauthorized usb:1-2 transport_id:1\n')
        self.assertEqual(connected, {'adb': True, 'adbState': 'connected', 'adbDevices': 1})
        self.assertEqual(waiting, {'adb': False, 'adbState': 'unauthorized', 'adbDevices': 1})
        self.assertNotIn('R58M123', json.dumps(connected))

    async def test_status_request_refreshes_adb_health(self):
        ws = await self.socket()
        refreshed = {'bridge': True, 'hermes': True, 'policy': True,
                     'adb': True, 'adbState': 'connected', 'adbDevices': 1}
        with patch.object(self.bridge, 'health', return_value=refreshed):
            await ws.send_json({'id': 'status-1', 'type': 'status_request', 'payload': {}})
            message = await ws.receive_json()
        self.assertEqual(message['type'], 'health_update')
        self.assertEqual(message['payload']['health'], refreshed)
        await ws.close()

    async def test_pairing_once_expired_and_hash_only(self):
        code = self.store.pairing_code()
        first = await self.client.post('/api/pair', json={'code': code})
        self.assertEqual(first.status, 200)
        self.assertEqual((await self.client.post('/api/pair', json={'code': code})).status, 401)
        expired = self.store.pairing_code()
        self.store.execute('UPDATE pairing SET expires=0 WHERE used=0')
        self.assertEqual((await self.client.post('/api/pair', json={'code': expired})).status, 401)
        self.assertNotIn(self.token, '\n'.join(str(tuple(r)) for r in self.store.db.execute('SELECT * FROM devices')))

    async def test_wrong_device_hello_rejected(self):
        ws = await self.client.ws_connect('/punk-bridge', headers=self.auth)
        await ws.send_json({'id': 'bad', 'type': 'hello', 'payload': {'protocolVersion': 3, 'deviceId': 'other'}})
        await ws.receive()
        self.assertTrue(ws.closed)

    async def test_https_listener_accepts_only_expected_certificate_pin(self):
        cert, key, pin = certificate(self.temp.name, 'localhost')
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        tls_store = Store(Path(self.temp.name) / 'tls')
        tls_bridge = Bridge(self.bridge.config, tls_store)
        token = tls_store.pair(tls_store.pairing_code(), 'TLS test')['token']
        runner = web.AppRunner(tls_bridge.public_app(), access_log=None)
        await runner.setup()
        site = web.TCPSite(runner, '127.0.0.1', 0, ssl_context=context)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        try:
            async with aiohttp.ClientSession() as client:
                async with client.get(f'https://127.0.0.1:{port}/api/status', headers={'Authorization': 'Bearer ' + token}, ssl=aiohttp.Fingerprint(bytes.fromhex(pin))) as response:
                    self.assertEqual(response.status, 200)
                with self.assertRaises(aiohttp.ServerFingerprintMismatch):
                    await client.get(f'https://127.0.0.1:{port}/api/status', headers={'Authorization': 'Bearer ' + token}, ssl=aiohttp.Fingerprint(b'0' * 32))
        finally:
            await runner.cleanup(); tls_store.db.close()

    async def test_chat_dedupe_and_assistant_identity(self):
        ws = await self.socket()
        p = {'conversationId': 'conversation-1', 'messageId': 'user-1', 'text': 'test'}
        await ws.send_json({'id': 'send-1', 'type': 'chat', 'payload': p})
        frame = await ws.receive_json(timeout=5)
        self.assertEqual(frame['type'], 'chat_chunk')
        self.assertEqual(frame['payload']['messageId'], 'assistant:user-1')
        self.assertEqual(frame['payload']['requestId'], 'user-1')
        self.assertEqual(frame['payload']['status'], 'failed')
        await ws.send_json({'id': 'send-2', 'type': 'chat', 'payload': p})
        replay = await ws.receive_json(timeout=5)
        self.assertEqual(replay['payload']['seq'], frame['payload']['seq'])
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM requests').fetchone()[0], 1)
        history = await (await self.client.get('/api/conversations/conversation-1/messages', headers=self.auth)).json()
        self.assertEqual([x['messageId'] for x in history['messages']], ['user-1', 'assistant:user-1'])

    async def test_tool_result_failure_not_promoted(self):
        ws = await self.socket()
        task = asyncio.create_task(self.internal.post('/api/tool', headers=self.internal_auth, json={'tool': 'device_status', 'params': {}, 'callId': 'test-call'}))
        frame = await ws.receive_json(timeout=5)
        self.assertEqual(frame['type'], 'tool_call')
        await ws.send_json({'id': 'result', 'type': 'tool_result', 'payload': {'callId': 'test-call', 'ok': True, 'result': {'ok': False}}})
        result = await (await task).json()
        self.assertFalse(result['ok'])
        replay = await self.internal.post('/api/tool', headers=self.internal_auth, json={'tool': 'device_status', 'params': {'different': True}, 'callId': 'test-call'})
        self.assertEqual(replay.status, 409)

    async def test_native_hermes_runs_stream_and_server_session_id(self):
        upstream = web.Application()
        seen = {'runs': 0}
        async def capabilities(request):
            return web.json_response({'features': {'run_events_sse': True}})
        async def session(request):
            return web.json_response({'id': 'server-chosen-session'}, status=201)
        async def create_run(request):
            body = await request.json()
            self.assertEqual(body['session_id'], 'server-chosen-session')
            self.assertIn('Idempotency-Key', request.headers)
            seen['runs'] += 1
            return web.json_response({'run_id': 'run_mock'}, status=202)
        async def events(request):
            response = web.StreamResponse(headers={'Content-Type': 'text/event-stream'})
            await response.prepare(request)
            for seq, event in enumerate([{'event': 'message.delta', 'delta': 'Resposta real'}, {'event': 'tool.started', 'tool': 'read_file', 'tool_call_id': 'tool-1', 'arguments': {'path': 'notes.txt', 'token': 'never-show'}}, {'event': 'tool.completed', 'tool': 'read_file', 'tool_call_id': 'tool-1', 'result': {'lines': 4}, 'error': False}, {'event': 'run.completed', 'output': 'Resposta real'}]):
                await response.write(('data: ' + json.dumps({**event, 'seq': seq}) + '\n\n').encode())
            await response.write_eof()
            return response
        prefix = '/p/punk-mobile'
        upstream.router.add_get(prefix + '/v1/capabilities', capabilities)
        upstream.router.add_post(prefix + '/api/sessions', session)
        upstream.router.add_post(prefix + '/v1/runs', create_run)
        upstream.router.add_get(prefix + '/v1/runs/run_mock/events', events)
        mock = TestServer(upstream); await mock.start_server()
        try:
            self.bridge.config['hermesUrl'] = str(mock.make_url(prefix))
            self.bridge.policy_seen = __import__('time').time()
            ws = await self.socket()
            await ws.send_json({'id': 'native', 'type': 'chat', 'payload': {'conversationId': 'c', 'messageId': 'u', 'text': 'test'}})
            frames = [await ws.receive_json(timeout=5) for _ in range(4)]
            self.assertEqual(frames[0]['payload']['text'], 'Resposta real')
            self.assertEqual(frames[1]['payload']['title'], 'read_file')
            self.assertIn('notes.txt', frames[1]['payload']['detail'])
            self.assertNotIn('never-show', frames[1]['payload']['detail'])
            self.assertEqual(frames[1]['payload']['id'], frames[2]['payload']['id'])
            self.assertIn('4', frames[2]['payload']['detail'])
            self.assertTrue(frames[-1]['payload']['done'])
            self.assertEqual(frames[-1]['payload']['status'], 'completed')
            self.assertEqual(seen['runs'], 1)
        finally:
            await mock.close()

    async def test_upload_resume_ownership_hash_and_original(self):
        data = b'original\x00bytes\xff'
        meta = {'name': '../../original.bin', 'mime': 'application/octet-stream', 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        created = await (await self.client.post('/api/uploads', json=meta, headers=self.auth)).json()
        path = '/api/uploads/' + created['uploadId']
        self.assertEqual((await self.client.put(path + '/chunks', data=data[:3], headers={**self.auth, 'Upload-Offset': '0'})).status, 200)
        resumed = await (await self.client.get(path, headers=self.auth)).json()
        self.assertEqual(resumed['offset'], 3)
        self.assertEqual((await self.client.put(path + '/chunks', data=data[3:], headers={**self.auth, 'Upload-Offset': '0'})).status, 409)
        self.assertEqual((await self.client.put(path + '/chunks', data=data[3:], headers={**self.auth, 'Upload-Offset': '3'})).status, 200)
        self.assertEqual((await self.client.post(path + '/complete', headers=self.auth)).status, 200)
        download = await self.client.get('/api/files/' + created['uploadId'], headers=self.auth)
        self.assertEqual(await download.read(), data)
        other = self.store.pair(self.store.pairing_code(), 'Other')
        self.assertEqual((await self.client.get(path, headers={'Authorization': 'Bearer ' + other['token']})).status, 404)

    async def test_publish_pc_file(self):
        ws = await self.socket()
        source = Path(self.temp.name) / 'source.txt'; source.write_bytes(b'original')
        result = await self.internal.post('/api/files/publish', headers=self.internal_auth, json={'path': str(source), 'conversationId': 'conversation-1'})
        self.assertEqual(result.status, 200)
        event = await ws.receive_json(timeout=5)
        self.assertEqual(event['type'], 'file_received')
        self.assertEqual(event['payload']['sha256'], hashlib.sha256(b'original').hexdigest())

    async def test_policy_autonomous_read_supervised_and_exact_decision(self):
        ws = await self.socket()
        self.store.set('binding:session-test', {'device': self.device, 'conversation': 'c'})
        body = {'sessionId': 'session-test', 'tool': 'terminal', 'args': {'command': 'test'}}
        allow = await (await self.internal.post('/api/policy/check', headers=self.internal_auth, json=body)).json()
        self.assertTrue(allow['allow'])
        self.store.set('supervised', True)
        reading = await (await self.internal.post('/api/policy/check', headers=self.internal_auth, json={**body, 'tool': 'read_file'})).json()
        self.assertTrue(reading['allow'])
        task = asyncio.create_task(self.internal.post('/api/policy/check', headers=self.internal_auth, json=body))
        event = await ws.receive_json(timeout=5)
        self.assertEqual(event['type'], 'approval_request')
        await ws.send_json({'id': 'answer', 'type': 'approval_response', 'payload': {'id': event['payload']['id'], 'allow': False}})
        self.assertFalse((await (await task).json())['allow'])
        self.assertFalse(self.bridge.pending_approvals)

    async def test_mode_and_global_cancel(self):
        ws = await self.socket()
        await ws.send_json({'id': 'stop', 'type': 'cancel', 'payload': {}})
        stopped = await ws.receive_json(timeout=5)
        self.assertEqual(stopped['type'], 'task_event')
        self.assertEqual(stopped['payload']['id'], 'cancel:all')
        self.assertEqual(stopped['payload']['status'], 'cancelled')
        await ws.send_json({'id': 'mode', 'type': 'control_mode', 'payload': {'supervised': True}})
        event = await ws.receive_json(timeout=5)
        self.assertEqual(event['type'], 'control_mode')
        self.assertTrue(event['payload']['supervised'])
        self.assertTrue(event['payload']['applied'])


class PureTests(unittest.TestCase):
    def test_certificate_fingerprint_stable(self):
        with tempfile.TemporaryDirectory() as root:
            first = certificate(root, 'localhost')
            second = certificate(root, 'localhost')
            self.assertRegex(first[2], '^[a-f0-9]{64}$')
            self.assertEqual(first[2], second[2])

    def test_preview_redacts_sensitive_keys(self):
        self.assertNotIn('test-sensitive', safe_preview({'pin': 'test-sensitive', 'nested': {'token': 'test-sensitive'}}))

    def test_middleware_denial_and_exception_never_dispatch(self):
        from punk_control import make_middleware
        invoked = []
        middleware = make_middleware({'url': 'http://127.0.0.1:1', 'token': 'test'})
        with patch('punk_control._post', return_value={'allow': False}):
            self.assertIn('error', json.loads(middleware(tool_name='terminal', args={}, next_call=lambda x: invoked.append(x))))
        with patch('punk_control._post', side_effect=RuntimeError('test')):
            self.assertIn('error', json.loads(middleware(tool_name='terminal', args={}, next_call=lambda x: invoked.append(x))))
        self.assertEqual(invoked, [])
        with patch('punk_control._post', return_value={'allow': True}):
            middleware(tool_name='terminal', args={'a': 1}, next_call=lambda x: invoked.append(x))
        self.assertEqual(invoked, [{'a': 1}])


    async def test_accessibility_and_voice_events_are_observable_but_not_agent_prompts(self):
        ws = await self.socket()
        await ws.send_json({'id': 'event-1', 'type': 'accessibility_event', 'payload': {
            'seq': 7, 'timestamp': 1000, 'type': 'WINDOW_STATE_CHANGED',
            'package': 'com.example.app', 'class': 'MainActivity', 'text': 'Tela inicial'
        }})
        await ws.send_json({'id': 'voice-1', 'type': 'voice_partial', 'payload': {
            'sessionId': 'session-1', 'conversationId': 'conversation-1',
            'text': 'abra o mapa', 'wakePhraseArmed': True, 'timestamp': 1001
        }})
        await asyncio.sleep(0.02)
        response = await self.internal.get('/api/device-events', headers=self.internal_auth)
        body = await response.json()
        self.assertEqual(response.status, 200)
        self.assertEqual(body['events'][0]['seq'], 7)
        self.assertEqual(body['events'][0]['package'], 'com.example.app')
        self.assertEqual(body['voice']['partial'], 'abra o mapa')
        self.assertTrue(body['voice']['wakePhraseArmed'])
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM messages').fetchone()[0], 0)
        await ws.close()


if __name__ == '__main__':
    unittest.main()
