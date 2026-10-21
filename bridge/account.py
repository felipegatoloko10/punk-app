"""Managed identity for the PC. Passwords never persist; session cookies use Windows DPAPI.

Only a one-use PC-scoped ticket reaches the public bridge, never a mobile account JWT.
"""
import asyncio
import base64
import ctypes
import json
import os
import re
import secrets
import time
import uuid
import socket
from http.cookies import SimpleCookie
from pathlib import Path


class AccountError(RuntimeError):
    pass


class WindowsSecret:
    def __init__(self, path):
        self.path = Path(path)

    @staticmethod
    def _crypt(value, decrypt=False):
        if os.name != 'nt':
            raise AccountError('O cofre desta versão requer Windows.')
        from ctypes import wintypes
        class Blob(ctypes.Structure):
            _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]
        buffer = ctypes.create_string_buffer(value)
        source = Blob(len(value), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
        result = Blob()
        crypt = ctypes.windll.crypt32
        fn = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
        fn.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                       ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
        fn.restype = wintypes.BOOL
        if not fn(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(result)):
            raise AccountError('Não foi possível acessar o cofre do Windows.')
        try:
            return ctypes.string_at(result.data, result.size)
        finally:
            ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
            ctypes.windll.kernel32.LocalFree(result.data)

    def load(self):
        if not self.path.exists():
            return {}
        try:
            return json.loads(self._crypt(self.path.read_bytes(), True))
        except Exception:
            raise AccountError('Sessão local indisponível. Entre novamente na conta.') from None

    def save(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix('.tmp')
        temp.write_bytes(self._crypt(json.dumps(data).encode()))
        os.replace(temp, self.path)


class PcAccount:
    def __init__(self, runtime, public_config=None, secret=None):
        self.runtime = Path(runtime)
        path = Path(__file__).resolve().parent.parent / 'core-bridge' / 'src' / 'main' / 'assets' / 'punk-account.json'
        self.public = public_config if public_config is not None else json.loads(path.read_text('utf-8'))
        self.secret = secret or WindowsSecret(self.runtime / 'account.dpapi')
        self.data = self.secret.load()
        self.lock = asyncio.Lock()
        self.error = ''
        self.endpoint = ''
        self.tunnel = None

    @property
    def configured(self):
        return bool(re.fullmatch(r'https://[a-z0-9.-]+\.neon\.tech/[^?#]*auth', self.public.get('authUrl', ''))
                    and re.fullmatch(r'https://[a-z0-9.-]+\.neon\.tech', self.public.get('apiUrl', '')))

    def status(self):
        return {'configured': self.configured, 'signedIn': bool(self.data.get('cookies')),
                'email': self.data.get('email', ''), 'remote': bool(self.endpoint),
                'remoteEnabled': self.data.get('remote_enabled', False),
                'error': self.error, 'computerId': self.data.get('computer_id', '')}

    async def request(self, http, path, payload=None, token=''):
        if not self.configured:
            raise AccountError('O serviço de contas ainda não foi ativado nesta instalação.')
        headers = {}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        async with http.request('POST', self.public['apiUrl'] + path,
                                json=payload, headers=headers, allow_redirects=False) as response:
            if response.status >= 300:
                raise AccountError('A conta não autorizou a operação. Confira o login e tente novamente.')
            return await response.json() if response.status != 204 else None

    async def auth_request(self, http, path, payload=None):
        headers = {'Origin': 'http://localhost:3000'}
        if self.data.get('cookies'):
            headers['Cookie'] = self.data['cookies']
        async with http.request('GET' if payload is None else 'POST', self.public['authUrl'] + path,
                                json=payload, headers=headers, allow_redirects=False) as response:
            if response.status >= 300:
                raise AccountError('Login recusado. Confira e-mail e senha ou entre novamente.')
            cookies = SimpleCookie()
            if self.data.get('cookies'):
                cookies.load(self.data['cookies'])
            for value in response.headers.getall('Set-Cookie', []):
                # Python 3.11 rejects the newer Partitioned attribute; only send name/value back.
                parsed = SimpleCookie(); parsed.load(value.split(';', 1)[0])
                for name, morsel in parsed.items():
                    if re.search(r'(?:^|;)\s*max-age=0(?:;|$)', value, re.I):
                        cookies.pop(name, None)
                    else:
                        cookies[name] = morsel.value
            self.data['cookies'] = '; '.join(f'{k}={v.coded_value}' for k, v in cookies.items())
            return await response.json(), response.headers.get('set-auth-jwt', '')

    async def login(self, http, email, password, signup=False):
        async with self.lock:
            previous = dict(self.data)
            try:
                payload = {'email': email, 'password': password}
                if signup:
                    payload['name'] = 'Punk'
                session, _ = await self.auth_request(http, '/sign-up/email' if signup else '/sign-in/email', payload)
                owner = session['user']['id']
                if self.data.get('owner') and self.data['owner'] != owner:
                    raise AccountError('Este PC já está vinculado a outra conta. Use a conta original.')
                self.data.update(owner=owner, email=session['user'].get('email', email),
                                 computer_id=self.data.get('computer_id') or str(uuid.uuid4()),
                                 device_key=self.data.get('device_key') or secrets.token_hex(32))
                await self._refresh(http)
            except Exception:
                self.data = previous
                raise
        return self.status()

    async def _refresh(self, http):
        session, token = await self.auth_request(http, '/get-session')
        if not session or not session.get('user'):
            raise AccountError('A sessão expirou. Entre novamente.')
        if session['user']['id'] != self.data.get('owner'):
            raise AccountError('Identidade da sessão mudou. Entre novamente.')
        if not token:
            data, _ = await self.auth_request(http, '/token')
            token = data.get('token', '')
        if not token:
            raise AccountError('O serviço de contas não forneceu uma credencial válida.')
        # The API verifies signature, issuer and expiry. This decode only schedules renewal.
        encoded = token.split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        self.data.update(access_token=token, expires_at=claims['exp'])
        self.secret.save(self.data)

    async def access(self, http):
        async with self.lock:
            if not self.data.get('cookies'):
                raise AccountError('Entre na sua conta no Punk Desktop.')
            if self.data.get('expires_at', 0) < time.time() + 90:
                await self._refresh(http)
            return self.data['access_token']

    async def rpc(self, http, name, payload):
        return await self.request(http, '/rpc/' + name, payload, await self.access(http))

    async def register(self, http, config, fingerprint):
        await self.rpc(http, 'punk_register_computer', {
            'p_id': self.data['computer_id'], 'p_name': os.environ.get('COMPUTERNAME', 'Meu PC')[:100],
            'p_endpoint': self.endpoint, 'p_local_host': config.get('advertiseHost') or self.local_address(),
            'p_local_port': config.get('port', 18765), 'p_fingerprint': fingerprint})

    async def consume(self, http, ticket):
        installation = await self.rpc(http, 'punk_consume_connect_grant', {
            'p_computer_id': self.data['computer_id'], 'p_ticket': ticket})
        return str(uuid.UUID(installation))

    async def logout(self, http):
        async with self.lock:
            if self.data.get('access_token'):
                await self.auth_request(http, '/sign-out', {})
            # Keep identity and device-key so logging in again preserves mobile history/revocations.
            for key in ('access_token', 'cookies', 'expires_at', 'email'):
                self.data.pop(key, None)
            self.secret.save(self.data)
            self.endpoint = ''

    @staticmethod
    def local_address():
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                connection.connect(('1.1.1.1', 443))
                return connection.getsockname()[0]
        except OSError:
            return ''
