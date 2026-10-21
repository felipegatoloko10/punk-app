import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from aiohttp.test_utils import TestClient, TestServer
from account import WindowsSecret
from control import Control
from mcp_server import BridgeTransport, build_server
from server import Bridge
from storage import Store, digest


class ControlTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(self.temp.name)
        self.bridge = Bridge({'internalToken': 'test-admin', 'hermesUrl': 'http://127.0.0.1:1',
                              '_runtimePath': self.temp.name}, self.store)
        self.bridge.account = None
        self.public = TestClient(TestServer(self.bridge.public_app()))
        self.local = TestClient(TestServer(self.bridge.internal_app()))
        await self.public.start_server(); await self.local.start_server()
        self.admin = {'Authorization': 'Bearer test-admin'}
        self.phone = self.store.pair(self.store.pairing_code(), 'Synthetic phone')
        self.device = self.phone['deviceId']
        self.socket = await self.public.ws_connect('/punk-bridge', headers={'Authorization': 'Bearer ' + self.phone['token']})
        await self.socket.send_json({'type':'hello', 'payload':{'protocolVersion':3,'deviceId':self.device,
            'tools':[{'name':'device_status','risk':'reading','parameters':{'type':'object','additionalProperties':False}},
                     {'name':'input_tap','risk':'reversible_write','parameters':{'type':'object','properties':{'x':{'type':'number'}},'required':['x'],'additionalProperties':False}}],
            'capabilities':{'accessibility':True,'tools':[{'name':'input_tap','available':True}]}}})
        await self.socket.receive_json()
        for name, token, read_only in [('client-a','test-client-a',False),('client-b','test-client-b',False),('reader','test-reader',True)]:
            self.store.execute('INSERT INTO integrations VALUES (?,?,?,?,?,0,?)', (name,name,digest(token),json.dumps([self.device]),int(read_only),1))
        self.a={'Authorization':'Bearer test-client-a'};self.b={'Authorization':'Bearer test-client-b'}
        self.reader={'Authorization':'Bearer test-reader'}

    async def asyncTearDown(self):
        await self.socket.close();await self.local.close();await self.public.close()
        self.store.db.close();self.temp.cleanup()

    async def frame(self, kind):
        for _ in range(20):
            frame=await self.socket.receive_json(timeout=3)
            if frame['type']==kind:return frame
        self.fail('Expected '+kind)

    async def execute(self, call, auth=None, **extra):
        return await self.local.post('/api/mcp/execute',headers=auth or self.a,
            json={'deviceId':self.device,'tool':'device_status','params':{},'callId':call,**extra})

    async def result(self, call, ok=True):
        await self.socket.send_json({'type':'tool_result','payload':{'callId':call,'ok':ok,'result':{'ok':ok}}})
        for _ in range(100):
            if self.bridge.call_view(call)['outcomeKnown']:break
            await asyncio.sleep(.01)

    async def test_credentials_cannot_manage_or_use_phone_or_admin_api(self):
        for path in ('/api/manage','/api/account','/api/status','/api/device-events'):
            self.assertEqual((await self.local.get(path,headers=self.a)).status,401)
        self.assertEqual((await self.public.get('/api/status',headers=self.a)).status,401)
        self.assertEqual((await self.local.get('/api/mcp/devices',headers={'Authorization':'Bearer '+self.phone['token']})).status,401)
        self.assertEqual((await self.local.post('/api/tool',headers=self.a,json={})).status,401)

    async def test_real_capabilities_schemas_and_read_only_scope(self):
        body=await (await self.local.get('/api/mcp/devices',headers=self.reader)).json()
        self.assertTrue(body['devices'][0]['capabilities']['accessibility'])
        catalog=await (await self.local.get('/api/mcp/tools',headers=self.reader)).json()
        self.assertEqual([t['name'] for t in catalog['tools']],['device_status'])
        self.assertEqual((await self.execute('write-denied',self.reader,tool='input_tap',params={'x':2})).status,403)
        self.assertEqual((await self.execute('bad-schema',tool='input_tap',params={'x':'bad'})).status,400)
        self.assertEqual((await self.execute('missing-id',callId=None)).status,400)

    async def test_two_clients_and_hermes_share_one_device_queue_and_ownership(self):
        self.assertEqual((await self.execute('a-1')).status,202)
        frame=await self.frame('tool_call');self.assertEqual(frame['payload']['clientId'],'client-a')
        self.assertEqual((await self.execute('b-1',self.b)).status,202)
        # A second client sees only its own call. Hermes cannot steal another actor's callId.
        self.assertEqual((await self.local.get('/api/mcp/calls/a-1',headers=self.b)).status,404)
        self.assertEqual((await self.execute('a-1',self.b)).status,409)
        self.assertEqual(self.bridge.call_view('b-1')['status'],'queued')
        await self.result('a-1')
        second=await self.frame('tool_call');self.assertEqual(second['payload']['callId'],'b-1')
        await self.result('b-1')
        task=asyncio.create_task(self.local.post('/api/tool',headers=self.admin,json={'tool':'device_status','params':{},'callId':'hermes-1'}))
        third=await self.frame('tool_call');self.assertEqual(third['payload']['clientId'],'hermes')
        await self.result('hermes-1');self.assertTrue((await (await task).json())['ok'])
        self.assertTrue(self.bridge.call_view('b-1')['ok'])

    async def test_idempotent_retries_even_after_disconnect_and_result_replay(self):
        await self.execute('stable');await self.frame('tool_call')
        replay=await self.execute('stable');self.assertEqual(replay.status,202)
        self.assertEqual(len(self.bridge.pending_calls),1)
        self.assertEqual((await self.execute('stable',params={'different':True})).status,409)
        await self.result('stable')
        await self.socket.close()
        await asyncio.sleep(.02)
        replay=await self.execute('stable');self.assertEqual(replay.status,202)
        self.assertTrue((await replay.json())['ok'])
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM calls').fetchone()[0],1)

    async def test_timeout_does_not_replay_and_late_result_releases_device(self):
        await self.execute('late',timeout=1);await self.frame('tool_call')
        await asyncio.sleep(1.1)
        self.assertEqual(self.bridge.call_view('late')['status'],'unknown')
        self.assertEqual((await self.execute('blocked')).status,409)
        self.assertEqual((await self.execute('late')).status,202)
        await self.result('late')
        self.assertTrue(self.bridge.call_view('late')['ok'])
        self.assertEqual((await self.execute('after-late')).status,202)
        await self.frame('tool_call');await self.result('after-late')

    async def test_disconnect_is_unknown_not_failure_and_reconnect_allows_confirmed_stop(self):
        await self.execute('disconnected');await self.frame('tool_call')
        await self.socket.close()
        for _ in range(100):
            if self.bridge.call_view('disconnected')['status']=='unknown':break
            await asyncio.sleep(.01)
        self.assertEqual(self.bridge.call_view('disconnected')['status'],'unknown')
        # A device can report its late result when it reconnects; the durable call was never resubmitted.
        await self.bridge.handle_frame(self.device,{'type':'tool_result','payload':{'callId':'disconnected','ok':True,'result':{'ok':True}}})
        self.assertEqual(self.bridge.call_view('disconnected')['status'],'completed')

    async def test_stop_without_ack_is_not_claimed_as_cancelled(self):
        await self.execute('uncertain-stop');await self.frame('tool_call')
        task=asyncio.create_task(self.local.post('/api/mcp/calls/uncertain-stop/cancel',headers=self.a,json={}))
        await self.frame('cancel_tools')
        response=await task
        self.assertEqual((await response.json())['status'],'cancel_requested')
        self.assertEqual((await self.execute('not-yet-safe')).status,409)
        await self.result('uncertain-stop')

    async def test_completed_result_wins_race_with_stop_ack(self):
        await self.execute('finished-before-stop');await self.frame('tool_call')
        task=asyncio.create_task(self.local.post('/api/mcp/calls/finished-before-stop/cancel',headers=self.a,json={}))
        await self.frame('cancel_tools')
        await self.result('finished-before-stop')
        await self.socket.send_json({'type':'cancel_ack','payload':{'callIds':['finished-before-stop']}})
        self.assertEqual((await (await task).json())['status'],'completed')

    async def test_queued_cancel_has_no_phone_side_effect_and_running_cancel_needs_ack(self):
        await self.execute('first');await self.frame('tool_call')
        await self.execute('queued',self.b)
        stopped=await self.local.post('/api/mcp/calls/queued/cancel',headers=self.b,json={})
        self.assertEqual((await stopped.json())['status'],'cancelled')
        self.assertEqual(self.bridge.call_view('first')['status'],'running')
        task=asyncio.create_task(self.local.post('/api/mcp/calls/first/cancel',headers=self.a,json={}))
        frame=await self.frame('cancel_tools')
        self.assertEqual(frame['payload']['callIds'],['first'])
        self.assertEqual(self.bridge.call_view('first')['status'],'cancel_requested')
        await self.socket.send_json({'type':'cancel_ack','payload':{'callIds':['first']}})
        self.assertEqual((await (await task).json())['status'],'cancelled')

    async def test_revocation_blocks_existing_client_and_queued_work(self):
        await self.execute('running');await self.frame('tool_call')
        await self.execute('revoked-queued',self.b)
        revoke=await self.local.post('/api/manage/revoke-client',headers=self.admin,json={'id':'client-b'})
        self.assertEqual(revoke.status,200)
        self.assertEqual(self.bridge.call_view('revoked-queued')['status'],'cancelled')
        self.assertEqual((await self.execute('revoked-new',self.b)).status,401)
        await self.result('running')

    async def test_bulk_stop_legacy_calls_sends_one_frame_and_preserves_unknown_effects(self):
        for call_id in ('legacy-1','legacy-2'):
            self.store.execute("INSERT INTO calls VALUES (?,?,'device_status','unknown',NULL,'legacy')",(call_id,self.device))
        task=asyncio.create_task(self.local.post('/api/manage/cancel-device-calls',headers=self.admin,json={'id':self.device}))
        frame=await self.frame('cancel_tools')
        self.assertEqual(set(frame['payload']['callIds']),{'legacy-1','legacy-2'})
        self.assertEqual(self.bridge.call_view('legacy-1')['status'],'cancel_requested')
        await self.socket.send_json({'type':'cancel_ack','payload':{'callIds':frame['payload']['callIds']}})
        body=await (await task).json()
        self.assertTrue(body['ok']);self.assertEqual(body['calls'],2)
        self.assertEqual(self.bridge.call_view('legacy-1')['status'],'cancelled')
        self.assertFalse(self.bridge.call_view('legacy-1')['outcomeKnown'])
        self.assertTrue(self.bridge.call_view('legacy-1')['effectsMayHaveOccurred'])

    async def test_new_pairing_requires_admin_and_does_not_restore_revoked_token(self):
        self.store.execute('INSERT INTO account_links VALUES (?,?,?)',('owner','installation',self.device))
        self.assertEqual((await self.local.post('/api/manage/allow-repair',headers=self.a,json={'id':self.device})).status,401)
        self.assertEqual((await self.local.post('/api/manage/allow-repair',headers=self.admin,json={'id':self.device})).status,409)
        self.store.execute('UPDATE devices SET revoked=1 WHERE id=?',(self.device,))
        response=await self.local.post('/api/manage/allow-repair',headers=self.admin,json={'id':self.device})
        self.assertEqual(response.status,200)
        self.assertIsNone(self.store.authenticate(self.phone['token']))
        self.assertIsNone(self.store.db.execute('SELECT 1 FROM account_links WHERE device=?',(self.device,)).fetchone())

    async def test_device_revoke_closes_socket_and_invalidates_http_token(self):
        reply=await self.local.post('/api/manage/revoke-device',headers=self.admin,json={'id':self.device})
        self.assertEqual(reply.status,200)
        self.assertIsNone(self.store.authenticate(self.phone['token']))
        self.assertEqual((await self.execute('revoked-device')).status,404)

    async def test_other_device_and_other_client_results_are_not_visible(self):
        other=self.store.pair(self.store.pairing_code(),'Not shared')['deviceId']
        self.assertEqual((await self.execute('other-device',deviceId=other)).status,403)
        body=await (await self.local.get('/api/mcp/devices',headers=self.a)).json()
        self.assertEqual([d['id'] for d in body['devices']],[self.device])

    async def test_management_audit_has_no_tokens_params_or_results(self):
        await self.execute('audit');await self.frame('tool_call');await self.result('audit')
        body=await (await self.local.get('/api/manage',headers=self.admin)).json()
        text=json.dumps(body)
        for secret in ('test-admin','test-client-a',self.phone['token'],'params_hash','token_hash'):
            self.assertNotIn(secret,text)
        self.assertTrue(any(x['actor']=='client-a' for x in body['audit']))

    @unittest.skipUnless(os.name=='nt','DPAPI credential test requires Windows')
    async def test_create_client_stores_hash_and_dpapi_not_exported_bearer(self):
        self.bridge.config['internalPort']=self.local.server.port
        response=await self.local.post('/api/manage/create-client',headers=self.admin,
            json={'name':'SDK test','devices':[self.device],'readOnly':True})
        self.assertEqual(response.status,200)
        client=(await response.json())['id']
        data=WindowsSecret(Path(self.temp.name)/'mcp'/(client+'.dpapi')).load()
        self.assertEqual(data['client'],client)
        self.assertNotIn(data['token'],(Path(self.temp.name)/'mcp'/(client+'.dpapi')).read_bytes().decode('latin1'))
        self.assertEqual(self.bridge.integration_auth(data['token'])['id'],client)
        self.assertNotIn(data['token'],json.dumps(await (await self.local.get('/api/manage',headers=self.admin)).json()))

    async def test_sdk_protocol_with_two_isolated_clients(self):
        # Real MCP SDK initialization/list/call over two independent transports; fake phone, no LLM or provider charges.
        import anyio
        from mcp.client.session import ClientSession
        from mcp.shared.memory import create_client_server_memory_streams
        for token in ('test-client-a','test-client-b'):
            server=build_server(BridgeTransport({'port':self.local.server.port,'token':token}))
            with anyio.fail_after(15):
                async with create_client_server_memory_streams() as (client_streams,server_streams):
                    async with anyio.create_task_group() as group:
                        group.start_soon(lambda:server.run(*server_streams,server.create_initialization_options()))
                        async with ClientSession(*client_streams) as session:
                            result=await session.initialize()
                            self.assertEqual(result.server_info.version,'3.7.0')
                            catalog=await session.list_tools()
                            self.assertEqual(len(catalog.tools),6)
                            devices=await session.call_tool('punk_devices',{})
                            self.assertFalse(devices.is_error)
                            self.assertEqual(devices.structured_content['devices'][0]['id'],self.device)
                            invalid=await session.call_tool('punk_execute',{'tool':'device_status','params':{}})
                            self.assertTrue(invalid.is_error)
                            group.cancel_scope.cancel()

    @unittest.skipUnless(os.name=='nt','DPAPI stdio test requires Windows')
    async def test_real_stdio_process_uses_only_own_credential(self):
        import anyio
        import sys
        import uuid
        from mcp.client.session import ClientSession
        from mcp.client.stdio import StdioServerParameters, stdio_client
        client=str(uuid.uuid4())
        self.store.execute('INSERT INTO integrations VALUES (?,?,?,?,?,0,?)',
            (client,'stdio-test',digest('stdio-test-token'),json.dumps([self.device]),1,1))
        WindowsSecret(Path(self.temp.name)/'mcp'/(client+'.dpapi')).save(
            {'client':client,'port':self.local.server.port,'token':'stdio-test-token'})
        params=StdioServerParameters(command=sys.executable,args=[str(Path(__file__).resolve().parents[1]/'mcp_server.py'),
            '--runtime',self.temp.name,'--client',client])
        with anyio.fail_after(20):
            async with stdio_client(params) as streams:
                async with ClientSession(*streams) as session:
                    await session.initialize()
                    tools=await session.list_tools()
                    self.assertEqual(len(tools.tools),6)
                    devices=await session.call_tool('punk_devices',{})
                    self.assertFalse(devices.is_error)
                    self.assertEqual(devices.structured_content['devices'][0]['id'],self.device)

    def test_restart_never_replays_inflight_or_queued_requests(self):
        self.store.execute("INSERT INTO calls VALUES ('before',?,'device_status','running',NULL,'hash')",(self.device,))
        self.store.execute("INSERT INTO calls VALUES ('queue',?,'device_status','queued',NULL,'hash')",(self.device,))
        restarted=Bridge(self.bridge.config,self.store)
        self.assertEqual(restarted.call_view('before')['status'],'unknown')
        self.assertEqual(restarted.call_view('queue')['status'],'cancelled')
        self.assertFalse(restarted.call_tasks)
