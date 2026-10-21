"""Opt-in live tunnel test against an isolated dummy origin, never the owner's Hermes."""
import asyncio
import ssl
import sys
import tempfile
import socket
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import aiohttp
from aiohttp import web
from server import certificate
from tunnel import QuickTunnel, install_cloudflared


class PublicResolver(aiohttp.abc.AbstractResolver):
    async def resolve(self, host, port=0, family=socket.AF_INET):
        try:
            answer = await aiohttp.resolver.ThreadedResolver().resolve(host, port, family)
            if not answer: raise OSError('DNS returned no addresses')
            return answer
        except OSError:
            if not host.endswith('.trycloudflare.com'): raise
            async with aiohttp.ClientSession() as dns:
                async with dns.get('https://cloudflare-dns.com/dns-query',params={'name':host,'type':'A'},headers={'Accept':'application/dns-json'}) as r:
                    data=await r.json(content_type=None)
            addresses = [dict(hostname=host,host=a['data'],port=port,family=socket.AF_INET,proto=0,flags=socket.AI_NUMERICHOST)
                    for a in data.get('Answer',[]) if a['type']==1]
            if not addresses: raise OSError('Public DNS not ready')
            return addresses
    async def close(self): pass


async def main():
    runtime = Path(__file__).resolve().parent.parent / 'runtime'
    executable = await asyncio.to_thread(install_cloudflared, runtime)
    with tempfile.TemporaryDirectory() as temp:
        cert, key, _ = certificate(temp, 'localhost')
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(cert,key)
        async def status(request): return web.json_response({'ok':False},status=401)
        async def socket(request):
            if request.headers.get('Authorization') != 'Bearer isolated-tunnel-test': raise web.HTTPUnauthorized()
            ws=web.WebSocketResponse();await ws.prepare(request);await ws.send_json({'ok':True});await ws.close();return ws
        app=web.Application();app.router.add_get('/api/status',status);app.router.add_get('/punk-bridge',socket)
        runner=web.AppRunner(app,access_log=None);await runner.setup()
        site=web.TCPSite(runner,'127.0.0.1',0,ssl_context=tls);await site.start()
        port=site._server.sockets[0].getsockname()[1]
        tunnel=QuickTunnel(temp,port)
        try:
            with patch('tunnel.install_cloudflared',return_value=executable):
                url=await tunnel.start()
            print('Isolated test hostname:',url.split('://')[1],flush=True)
            async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(resolver=PublicResolver()),timeout=aiohttp.ClientTimeout(total=8)) as http:
                last = 'not attempted'
                for attempt in range(20):
                    try:
                        async with http.get(url+'/api/status') as r:
                            last = str(r.status)
                            if r.status==401: break
                    except (aiohttp.ClientError,asyncio.TimeoutError) as error: last = type(error).__name__
                    if attempt == 2 and last == 'ClientConnectorDNSError':
                        async with http.get('https://cloudflare-dns.com/dns-query',params={'name':url.split('://')[1],'type':'A'},headers={'Accept':'application/dns-json'}) as dns:
                            data=await dns.json(content_type=None)
                            print('Public DNS status:',data.get('Status'),'answers:',len(data.get('Answer',[])),flush=True)
                    await asyncio.sleep(2)
                else: raise AssertionError('HTTPS origin unavailable: status=' + last + ', diagnostics=' + str(tunnel.diagnostics))
                async with http.ws_connect(url.replace('https://','wss://')+'/punk-bridge',headers={'Authorization':'Bearer isolated-tunnel-test'}) as ws:
                    assert (await ws.receive_json())['ok'] is True
            print('PASS: public WebPKI HTTPS, pinned origin CA, unauthenticated 401 and authenticated WebSocket')
        finally:
            await tunnel.stop();await runner.cleanup()


if __name__=='__main__': asyncio.run(main())
