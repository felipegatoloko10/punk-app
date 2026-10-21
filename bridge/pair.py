"""Create a private, expiring pairing page and QR without printing its secret."""
import argparse
import base64
import html
import io
import json
import socket
import sys
import time
import re
import urllib.request
import urllib.error
from pathlib import Path

from server import certificate, load_config
from storage import Store


def account_route(config):
    """Obtain the live URL from the private loopback API, not stale saved tunnel logs."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs): return None
    try:
        port=int(config.get('internalPort',18766))
        if not 1 <= port <= 65535: return {}
        request=urllib.request.Request(f'http://127.0.0.1:{port}/api/account',
            headers={'Authorization':'Bearer '+config.get('internalToken','')})
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        with opener.open(request,timeout=3) as response:
            account=json.load(response)
        return account
    except (OSError,ValueError,urllib.error.URLError):
        return {}


def pairing_payload(config,host,pin,code,account,tailscale=''):
    payload={'version':3,'host':host,'port':config['port'],'useTls':True,'certificateFingerprint':pin,
             'pairingCode':code,'localHost':host,'tailscaleHost':tailscale or config.get('tailscaleHost','')}
    endpoint=account.get('remoteEndpoint','')
    if account.get('remote') and re.fullmatch(r'https://[a-z0-9-]+\.trycloudflare\.com',endpoint):
        payload['remoteEndpoint']=endpoint
        payload['computerId']=account.get('computerId','')
    return payload


def local_address():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            # Route lookup only; no packet is sent.
            sock.connect(('192.0.2.1', 9))
            return sock.getsockname()[0]
    except OSError:
        return socket.gethostbyname(socket.gethostname())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', default=str(Path(__file__).parent / 'runtime'))
    parser.add_argument('--host')
    parser.add_argument('--tailscale-host', default='')
    args = parser.parse_args()
    root = Path(args.runtime).resolve()
    config = load_config(root)
    host = args.host or config.get('advertiseHost') or local_address()
    _, _, pin = certificate(root, host)
    store = Store(root)
    try:
        code = store.pairing_code()
    finally:
        store.db.close()
    payload = pairing_payload(config,host,pin,code,account_route(config),args.tailscale_host)
    text = json.dumps(payload, ensure_ascii=False)
    (root / 'pairing.json').write_text(text, encoding='utf-8')
    sys.path.insert(0, str(root / 'vendor'))
    image = ''
    try:
        import qrcode
        buffer = io.BytesIO()
        qrcode.make(text).save(buffer, format='PNG')
        image = '<img alt="QR de pareamento do Punk" src="data:image/png;base64,' + base64.b64encode(buffer.getvalue()).decode() + '">'
    except ImportError:
        image = '<p>Importe o arquivo pairing.json ou copie o código abaixo.</p>'
    expires = int((time.time() + 300) * 1000)
    page = '''<!doctype html><html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Conectar Punk</title><style>body{margin:0;background:#080b10;color:#edf2f7;font:16px system-ui;display:grid;place-items:center;min-height:100vh}main{max-width:520px;padding:36px 24px}h1{font-size:32px;margin-bottom:8px}p{color:#a6b2c2;line-height:1.6}img{width:min(100%,340px);display:block;border-radius:16px;margin:24px auto}textarea{box-sizing:border-box;width:100%;height:130px;background:#141b25;border:1px solid #263646;border-radius:12px;color:#b6dfe5;padding:14px;font:12px ui-monospace}b{color:#91e6ec}small{color:#a6b2c2}</style><main><b>PUNK</b><h1>Seu agente. Seus dispositivos.</h1><p>No app, abra <b>Configurações → Ler QR code</b> e aponte para este código.</p>''' + image + '''<p id="timer">Código válido por cinco minutos, para um único pareamento.</p><details><summary>Copiar ou importar código</summary><p>Você também pode importar o arquivo <b>pairing.json</b> no aplicativo.</p><textarea readonly>''' + html.escape(text) + '''</textarea></details><p><small>Se expirar, execute PAREAR-PUNK.ps1 novamente. A identidade criptográfica do PC permanece a mesma.</small></p></main><script>const deadline=''' + str(expires) + ''';setInterval(()=>{const s=Math.max(0,Math.ceil((deadline-Date.now())/1000));document.getElementById('timer').textContent=s?'Expira em '+Math.floor(s/60)+':'+String(s%60).padStart(2,'0'):'Código expirado. Gere outro pareamento no PC.'},1000)</script></html>'''
    (root / 'pareamento.html').write_text(page, encoding='utf-8')
    print('Página de pareamento criada em runtime/pareamento.html (validade: cinco minutos).')


if __name__ == '__main__':
    main()
