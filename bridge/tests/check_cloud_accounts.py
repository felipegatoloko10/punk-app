"""Explicit opt-in integration check. Creates isolated test identities, never uses the owner's credentials.

Run from bridge/: python tests/check_cloud_accounts.py
Test account IDs are saved to ignored runtime/ for cleanup; no password/session is printed.
"""
import asyncio
import json
import secrets
import sys
import tempfile
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import aiohttp
from account import PcAccount, AccountError


async def main():
    owners = []
    with tempfile.TemporaryDirectory() as temp:
        async with aiohttp.ClientSession(cookie_jar=aiohttp.DummyCookieJar()) as http:
            accounts = []
            for index in range(2):
                account = PcAccount(Path(temp) / str(index))
                email = 'punk-check-' + uuid.uuid4().hex + '@example.invalid'
                await account.login(http, email, secrets.token_urlsafe(24), signup=True)
                owners.append({'id': account.data['owner'], 'email': email})
                accounts.append(account)
            a, b = accounts
            await a.register(http, {'advertiseHost': '127.0.0.1', 'port': 18765}, 'ab' * 32)
            computer = a.data['computer_id']
            installation = str(uuid.uuid4())
            args = {'p_computer_id': computer, 'p_installation_id': installation}
            try:
                await b.rpc(http, 'punk_create_connect_grant', args)
                raise AssertionError('Cross-account enrollment was accepted')
            except AccountError: pass
            ticket = await a.rpc(http, 'punk_create_connect_grant', args)
            assert await a.consume(http, ticket) == installation
            try:
                await a.consume(http, ticket)
                raise AssertionError('A ticket was reused')
            except AccountError: pass
            a.data['expires_at'] = 0
            assert await a.access(http)
            # Restore encrypted sessions, not passwords.
            restored = PcAccount(Path(temp) / '0')
            assert restored.data['owner'] == a.data['owner']
            for account in accounts:
                await account.logout(http)
    target = Path(__file__).resolve().parent.parent / 'runtime' / 'cloud-test-accounts.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(owners), encoding='utf-8')
    print('PASS: signup, login session, DPAPI restoration, refresh, cross-account denial, one-use grant and logout')
    print('Test identity IDs for cleanup: ' + ', '.join(item['id'] for item in owners))


if __name__ == '__main__': asyncio.run(main())
