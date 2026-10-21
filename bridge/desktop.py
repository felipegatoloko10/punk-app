"""Local connection dashboard. Launched with pythonw: no console and no token copying."""
import concurrent.futures
import json
import queue
import subprocess
import sys
import time
import tkinter as tk
import urllib.error
import urllib.request
import webbrowser
from pathlib import Path
from tkinter import ttk


ROOT = Path(__file__).resolve().parent


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Connection:
    def __init__(self, root=ROOT):
        self.root = Path(root)
        self.runtime = self.root / 'runtime'

    def account(self, action=None, data=None):
        self.connect()
        config = json.loads((self.runtime / 'config.json').read_text('utf-8'))
        request = urllib.request.Request(
            f"http://127.0.0.1:{int(config.get('internalPort', 18766))}/api/account" + ('/' + action if action else ''),
            data=json.dumps(data or {}).encode() if action else None,
            headers={'Authorization': 'Bearer ' + config['internalToken'], 'Content-Type': 'application/json'})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            with opener.open(request, timeout=90) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            # Never reflect arbitrary upstream HTTP bodies (could contain credentials).
            if error.code == 404:
                raise RuntimeError('Atualize e reinicie o bridge para habilitar a Conta Punk.') from None
            raise RuntimeError('Não foi possível acessar a conta. Confira e-mail, senha e a ativação do serviço.') from None

    def status(self):
        path = self.runtime / 'config.json'
        if not path.is_file():
            return {'configured': False, 'online': False}
        try:
            config = json.loads(path.read_text(encoding='utf-8'))
            port = int(config.get('internalPort', 18766))
            if not 1 <= port <= 65535 or not config.get('internalToken'):
                return {'configured': False, 'online': False}
            request = urllib.request.Request(
                f'http://127.0.0.1:{port}/api/status',
                headers={'Authorization': 'Bearer ' + config['internalToken']})
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
            with opener.open(request, timeout=4) as response:
                data = json.load(response)
            account = {}
            try:
                info = urllib.request.Request(f'http://127.0.0.1:{port}/api/account',headers=request.headers)
                with opener.open(info, timeout=4) as response:
                    account = json.load(response)
            except (OSError, ValueError, urllib.error.URLError):
                pass
            return {'configured': True, 'online': data.get('ok') is True,
                    'phone': data.get('connected') is True, 'health': data.get('health', {}), 'account': account}
        except (OSError, ValueError, urllib.error.URLError):
            return {'configured': True, 'online': False}

    def connect(self, pair=False):
        state = self.status()
        if not state['configured']:
            raise RuntimeError('A instalação do Punk neste PC precisa ser concluída antes do pareamento.')
        flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
        if not state['online']:
            result = subprocess.run(
                ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                 str(self.root / 'INICIAR-PUNK.ps1')],
                cwd=self.root, capture_output=True, timeout=120, creationflags=flags)
            if result.returncode:
                raise RuntimeError('Não foi possível iniciar o Punk. Confira a instalação do Hermes neste PC.')
            for _ in range(20):
                state = self.status()
                if state['online']:
                    break
                time.sleep(1)
            if not state['online']:
                raise RuntimeError('O Punk ainda não está disponível. Tente conectar novamente.')
        if pair:
            result = subprocess.run(
                [sys.executable, str(self.root / 'pair.py'), '--runtime', str(self.runtime)],
                cwd=self.root, capture_output=True, timeout=30, creationflags=flags)
            if result.returncode:
                raise RuntimeError('Não foi possível gerar o QR. Confira a instalação do pareamento no PC.')
            return {'pairingPage': (self.runtime / 'pareamento.html').as_uri()}
        return state


class Desktop:
    def __init__(self, root, connection=None):
        self.root = root
        self.connection = connection or Connection()
        self.executor = concurrent.futures.ThreadPoolExecutor(max_workers=2)
        self.results = queue.Queue()
        self.busy = False
        self.polling = False
        self.closed = False
        root.title('Punk Desktop')
        root.geometry('540x760')
        root.minsize(480, 720)
        root.configure(bg='#0b1018')
        style = ttk.Style(root)
        style.theme_use('clam')
        style.configure('TFrame', background='#0b1018')
        style.configure('TLabel', background='#0b1018', foreground='#e6edf5', font=('Segoe UI', 11))
        style.configure('Title.TLabel', font=('Segoe UI', 26, 'bold'), foreground='#91e6ec')
        style.configure('TButton', font=('Segoe UI', 11), padding=12)
        frame = ttk.Frame(root, padding=28)
        frame.pack(fill='both', expand=True)
        ttk.Label(frame, text='Punk Desktop', style='Title.TLabel').pack(anchor='w')
        ttk.Label(frame, text='Seu celular conectado ao seu assistente.').pack(anchor='w', pady=(4, 24))
        self.summary = tk.StringVar(value='Verificando conexão…')
        ttk.Label(frame, textvariable=self.summary, wraplength=440, justify='left').pack(anchor='w', pady=(0, 20))
        self.start = ttk.Button(frame, text='Conectar ao PC', command=lambda: self.run(False))
        self.start.pack(fill='x', pady=5)
        self.pair = ttk.Button(frame, text='Conectar celular · mostrar QR', command=lambda: self.run(True))
        self.pair.pack(fill='x', pady=5)
        self.notice = tk.StringVar(value='No celular, abra Configurações → Ler QR code.\nO pareamento fica salvo para as próximas conexões.')
        ttk.Label(frame, textvariable=self.notice, wraplength=440, justify='left').pack(anchor='w', pady=(20, 0))
        ttk.Separator(frame).pack(fill='x', pady=16)
        ttk.Label(frame, text='Conta Punk · conexão automática').pack(anchor='w')
        self.email = tk.StringVar()
        self.password = tk.StringVar()
        ttk.Label(frame, text='E-mail').pack(anchor='w', pady=(8, 0))
        ttk.Entry(frame, textvariable=self.email).pack(fill='x')
        ttk.Label(frame, text='Senha').pack(anchor='w', pady=(6, 0))
        ttk.Entry(frame, textvariable=self.password, show='●').pack(fill='x')
        buttons = ttk.Frame(frame); buttons.pack(fill='x', pady=6)
        self.account_buttons = []
        for title, action in [('Entrar', 'login'), ('Criar conta', 'signup'), ('Sair', 'logout')]:
            button = ttk.Button(buttons, text=title, command=lambda a=action: self.account_action(a))
            button.pack(side='left', expand=True, fill='x'); self.account_buttons.append(button)
        self.remote = ttk.Button(frame, text='Ativar acesso externo gratuito', command=lambda: self.account_action('remote'))
        self.remote.pack(fill='x'); self.account_buttons.append(self.remote)
        self.account_notice = tk.StringVar(value='Entre na mesma conta no PC e no celular. Não é necessário comprar domínio.')
        ttk.Label(frame, textvariable=self.account_notice, wraplength=440).pack(anchor='w', pady=8)
        self.remote_enabled = False
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.after(100, self.pump)
        self.refresh()
        # Opening the Desktop restores the provisioned PC/remembered tunnel without another button press.
        runtime=getattr(self.connection,'runtime',None)
        if runtime and (runtime / 'config.json').is_file():
            root.after(250,lambda:self.run(False))

    def account_action(self, action):
        if self.busy:
            return
        email, password = self.email.get().strip(), self.password.get()
        if action in ('login', 'signup') and (not email or not password):
            self.account_notice.set('Preencha e-mail e senha.'); return
        self.password.set('')
        self.busy = True
        for button in self.account_buttons:
            button.state(['disabled'])
        self.account_notice.set('Conectando sua conta…')
        data = {'email': email, 'password': password} if action in ('login', 'signup') else {'enabled': not self.remote_enabled}
        self.submit('account', lambda: self.connection.account(action, data))

    def submit(self, kind, function):
        future = self.executor.submit(function)
        def complete(done):
            try:
                self.results.put((kind, done.result(), None))
            except Exception as error:
                # Display actionable messages only; never raw HTTP bodies or credentials.
                message = str(error) if isinstance(error, RuntimeError) else 'Não foi possível concluir. Tente novamente.'
                self.results.put((kind, None, message))
        future.add_done_callback(complete)

    def run(self, pair):
        if self.busy:
            return
        self.busy = True
        self.start.state(['disabled'])
        self.pair.state(['disabled'])
        self.notice.set('Preparando seu QR…' if pair else 'Conectando…')
        self.submit('connect', lambda: self.connection.connect(pair=pair))

    def refresh(self):
        if self.closed:
            return
        if not self.polling and not self.busy:
            self.polling = True
            self.submit('status', self.connection.status)
        self.root.after(5_000, self.refresh)

    def pump(self):
        if self.closed:
            return
        while True:
            try:
                kind, data, error = self.results.get_nowait()
            except queue.Empty:
                break
            if kind == 'status':
                self.polling = False
                if data:
                    health = data.get('health', {})
                    account = data.get('account', {})
                    if account and not self.busy:
                        self.remote_enabled = account.get('remoteEnabled', False)
                        self.remote.configure(text='Desativar acesso externo' if self.remote_enabled else 'Ativar acesso externo gratuito')
                        if account.get('signedIn'):
                            self.account_notice.set('Conta conectada · ' + account.get('email', '') + '\n' +
                                (account.get('error') or ('Acesso externo conectado.' if account.get('remote') else 'Preparando o túnel…' if self.remote_enabled else 'Acesso externo desativado.')))
                    if not data['configured']:
                        self.summary.set('A instalação deste PC ainda precisa ser concluída.')
                    else:
                        self.summary.set('\n'.join([
                            'PC · ' + ('conectado' if data['online'] else 'desconectado'),
                            'Celular · ' + ('conectado' if data.get('phone') else 'aguardando conexão'),
                            'Hermes · ' + ('disponível' if health.get('hermes') else 'aguardando'),
                            'ADB no PC · ' + ('conectado' if health.get('adb') else 'não conectado')]))
            elif kind == 'account':
                self.busy = False
                for button in self.account_buttons:
                    button.state(['!disabled'])
                if error:
                    self.account_notice.set(error)
                else:
                    self.remote_enabled = data.get('remoteEnabled', False)
                    self.remote.configure(text='Desativar acesso externo' if self.remote_enabled else 'Ativar acesso externo gratuito')
                    self.account_notice.set(('Conta conectada · ' + data.get('email', '') if data.get('signedIn') else 'Você saiu da conta.') +
                        ('\nTúnel sendo preparado. O endereço será atualizado automaticamente.' if self.remote_enabled else ''))
            else:
                self.busy = False
                self.start.state(['!disabled'])
                self.pair.state(['!disabled'])
                if error:
                    self.notice.set(error)
                elif data and data.get('pairingPage'):
                    webbrowser.open(data['pairingPage'])
                    self.notice.set('QR aberto no navegador deste PC.\nLeia pelo Punk no celular. Expira em cinco minutos.\nPara renovar, toque em Conectar celular novamente.')
                else:
                    self.notice.set('PC conectado. O celular pareado reconecta automaticamente.')
                if not self.polling:
                    self.polling = True
                    self.submit('status', self.connection.status)
        self.root.after(100, self.pump)

    def close(self):
        self.closed = True
        self.executor.shutdown(wait=False, cancel_futures=True)
        # Closing the dashboard leaves the independently running bridge and opt-in tunnel available.
        self.root.destroy()


def main():
    root = tk.Tk()
    Desktop(root)
    root.mainloop()


if __name__ == '__main__':
    main()
