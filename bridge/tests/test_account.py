import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

from account import PcAccount, AccountError, WindowsSecret
from storage import Store
from tunnel import URL


class MemorySecret:
    def __init__(self): self.data = {}
    def load(self): return dict(self.data)
    def save(self, data): self.data = dict(data)


class Accounts(unittest.IsolatedAsyncioTestCase):
    async def test_parallel_refresh_is_serialized_and_password_not_persisted(self):
        secret = MemorySecret()
        account = PcAccount('.', public_config={}, secret=secret)
        account.data = {'cookies': 'session=private', 'expires_at': 0}
        calls = []
        async def renew(http):
            calls.append(True)
            await asyncio.sleep(.01)
            account.data.update(access_token='renewed', expires_at=9999999999)
            secret.save(account.data)
        account._refresh = renew
        result = await asyncio.gather(*(account.access(None) for _ in range(8)))
        self.assertEqual(result, ['renewed'] * 8)
        self.assertEqual(len(calls), 1)
        self.assertNotIn('password', secret.data)

    def test_idempotent_pairing_preserves_device_and_revocation(self):
        with tempfile.TemporaryDirectory() as temp:
            store = Store(temp)
            try:
                one = store.account_pair('owner', 'install', 'ab' * 32)
                two = store.account_pair('owner', 'install', 'ab' * 32)
                self.assertEqual(one, two)
                self.assertEqual(store.authenticate(one['token']), one['deviceId'])
                store.execute('UPDATE devices SET revoked=1 WHERE id=?', (one['deviceId'],))
                self.assertIsNone(store.account_pair('owner', 'install', 'ab' * 32))
                self.assertIsNone(store.authenticate(one['token']))
                self.assertNotIn(one['token'], str(list(store.db.execute('SELECT * FROM devices'))))
            finally: store.db.close()

    def test_tunnel_hostname_has_no_suffix_confusion(self):
        self.assertEqual(URL.search('Started https://one-two.trycloudflare.com now').group(), 'https://one-two.trycloudflare.com')
        self.assertIsNone(URL.search('https://one-two.trycloudflare.com.evil.example'))

    def test_existing_qr_device_keeps_history_when_linked_to_account(self):
        with tempfile.TemporaryDirectory() as temp:
            store=Store(temp)
            try:
                old=store.pair(store.pairing_code(),'Phone')
                store.message(old['deviceId'],'main','message1','user','hello','completed')
                new=store.account_pair('owner','install','ab'*32,existing_device=store.authenticate(old['token']))
                self.assertEqual(new['deviceId'],old['deviceId'])
                self.assertEqual(len(store.history(new['deviceId'],'main')),1)
                self.assertIsNone(store.authenticate(old['token']))
                self.assertEqual(store.authenticate(new['token']),new['deviceId'])
            finally: store.db.close()

    async def test_missing_account_is_explicit(self):
        account = PcAccount('.', public_config={}, secret=MemorySecret())
        self.assertFalse(account.configured)
        with self.assertRaises(AccountError): await account.access(None)

    @unittest.skipUnless(__import__('os').name == 'nt', 'Windows DPAPI')
    def test_dpapi_roundtrip_never_stores_plaintext(self):
        with tempfile.TemporaryDirectory() as temp:
            secret = WindowsSecret(Path(temp) / 'session.dpapi')
            secret.save({'cookies': 'test-session-value'})
            self.assertNotIn(b'test-session-value', secret.path.read_bytes())
            self.assertEqual(secret.load(), {'cookies': 'test-session-value'})
