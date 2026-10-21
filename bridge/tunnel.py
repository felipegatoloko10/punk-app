"""Opt-in free Quick Tunnel. Only the authenticated public TLS bridge is exposed."""
import asyncio
import hashlib
import json
import os
import re
import urllib.request
from pathlib import Path

try:
    from .account import AccountError
except ImportError:
    from account import AccountError


URL = re.compile(r'https://[a-z0-9-]+\.trycloudflare\.com(?![a-z0-9.\-])')


def install_cloudflared(runtime):
    """Download from the official GitHub release, verifying its published SHA-256 digest."""
    if os.name != 'nt':
        raise AccountError('O instalador automático desta versão requer Windows x64.')
    target = Path(runtime) / 'deps' / 'cloudflared.exe'
    receipt = target.with_suffix('.sha256')
    if target.exists() and receipt.exists():
        if hashlib.sha256(target.read_bytes()).hexdigest() == receipt.read_text().strip():
            return target
        raise AccountError('O executável do túnel foi alterado. Reinstale pelo Desktop.')
    request = urllib.request.Request('https://api.github.com/repos/cloudflare/cloudflared/releases/latest',
                                     headers={'User-Agent': 'Punk-Desktop', 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.load(response)
    asset = next(a for a in release['assets'] if a['name'] == 'cloudflared-windows-amd64.exe')
    expected = asset.get('digest', '')
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', expected):
        raise AccountError('A versão oficial do túnel não publicou um checksum verificável.')
    url = asset['browser_download_url']
    if not url.startswith('https://github.com/cloudflare/cloudflared/releases/download/'):
        raise AccountError('Origem do download inválida.')
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix('.download')
    checksum = hashlib.sha256()
    try:
        with urllib.request.urlopen(url, timeout=60) as response, temp.open('wb') as out:
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > 150 * 1024 * 1024:
                    raise AccountError('Download do túnel maior que o esperado.')
                checksum.update(chunk)
                out.write(chunk)
        if checksum.hexdigest() != expected[7:]:
            raise AccountError('O checksum do túnel não confere. Download descartado.')
        os.replace(temp, target)
        receipt.write_text(expected[7:], encoding='ascii')
        return target
    finally:
        temp.unlink(missing_ok=True)


class QuickTunnel:
    def __init__(self, runtime, port):
        self.runtime, self.port = Path(runtime), port
        self.process = None
        self.drain = None
        self.diagnostics = set()
        self.connected = asyncio.Event()

    async def start(self):
        executable = await asyncio.to_thread(install_cloudflared, self.runtime)
        self.process = await asyncio.create_subprocess_exec(
            str(executable), 'tunnel', '--url', f'https://localhost:{self.port}',
            '--origin-ca-pool', str(self.runtime / 'certificate.pem'),
            '--origin-server-name', 'localhost', '--no-autoupdate',
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
            creationflags=getattr(__import__('subprocess'), 'CREATE_NO_WINDOW', 0),
            limit=65536)
        try:
            async with asyncio.timeout(60):
                while line := await self.process.stdout.readline():
                    if b'registered tunnel connection' in line.lower():
                        self.connected.set()
                    match = URL.search(line.decode('utf-8', 'replace'))
                    if match:
                        self.drain = asyncio.create_task(self._drain())
                        await asyncio.wait_for(self.connected.wait(), 30)
                        return match.group()
            raise AccountError('O túnel não forneceu um endereço. Tente novamente.')
        except BaseException:
            await self.stop()
            raise

    async def _drain(self):
        # Do not persist cloudflared diagnostics: origin traffic may include private metadata.
        while line := await self.process.stdout.readline():
            text = line.decode('utf-8', 'replace').lower()
            for category, pattern in [('origin_tls', 'x509'), ('origin_unreachable', 'refused'),
                                      ('edge_timeout', 'timeout'), ('edge_connected', 'registered tunnel connection')]:
                if pattern in text:
                    self.diagnostics.add(category)
                    if category == 'edge_connected':
                        self.connected.set()

    async def stop(self):
        if self.process and self.process.returncode is None:
            self.process.terminate()
            try:
                await asyncio.wait_for(self.process.wait(), 5)
            except asyncio.TimeoutError:
                self.process.kill()
                await self.process.wait()
        if self.drain:
            self.drain.cancel()
            await asyncio.gather(self.drain, return_exceptions=True)
