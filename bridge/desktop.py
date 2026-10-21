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
from tkinter import ttk, messagebox, filedialog
from windows_startup import startup_enabled, set_startup


ROOT = Path(__file__).resolve().parent


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Connection:
    def __init__(self, root=ROOT):
        self.root = Path(root)
        self.runtime = self.root / 'runtime'

    def manage(self, action=None, data=None):
        self.connect()
        config = json.loads((self.runtime / 'config.json').read_text('utf-8'))
        req = urllib.request.Request(
            f"http://127.0.0.1:{int(config.get('internalPort',18766))}/api/manage" + ('/' + action if action else ''),
            data=json.dumps(data or {}).encode() if action else None,
            headers={'Authorization':'Bearer ' + config['internalToken'], 'Content-Type':'application/json'})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            with opener.open(req, timeout=40) as response:
                return json.load(response)
        except urllib.error.HTTPError:
            raise RuntimeError('Gestão indisponível. Atualize o bridge ou confira a seleção e as permissões.') from None

    def mcp_config(self, client):
        import re
        if not re.fullmatch(r'[a-f0-9-]{36}', client):
            raise RuntimeError('Selecione uma integração válida.')
        # A client ID is not a secret. DPAPI credentials are loaded only by the local adapter.
        return {'mcpServers': {'punk-' + client[:8]: {'command': str(Path(sys.executable).with_name('python.exe')),
            'args': [str(self.root.resolve() / 'mcp_server.py'), '--runtime', str(self.runtime.resolve()), '--client', client]}}}

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
        root.geometry('580x920')
        root.minsize(480, 650)
        root.configure(bg='#0b1018')
        style = ttk.Style(root)
        style.theme_use('clam')
        style.configure('TFrame', background='#0b1018')
        style.configure('TLabel', background='#0b1018', foreground='#e6edf5', font=('Segoe UI', 11))
        style.configure('Title.TLabel', font=('Segoe UI', 26, 'bold'), foreground='#91e6ec')
        style.configure('TButton', font=('Segoe UI', 11), padding=12)
        canvas = tk.Canvas(root, bg='#0b1018', highlightthickness=0)
        scroll = ttk.Scrollbar(root, orient='vertical', command=canvas.yview)
        canvas.configure(yscrollcommand=scroll.set)
        scroll.pack(side='right', fill='y'); canvas.pack(side='left', fill='both', expand=True)
        frame = ttk.Frame(canvas, padding=28)
        window = canvas.create_window((0,0),window=frame,anchor='nw')
        frame.bind('<Configure>',lambda event: canvas.configure(scrollregion=canvas.bbox('all')))
        canvas.bind('<Configure>',lambda event: canvas.itemconfigure(window,width=event.width))
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
        self.manager = None
        self.management_pending = False
        self.new_client = None
        self.autostart = tk.BooleanVar(value=startup_enabled(getattr(self.connection,'root',ROOT)))
        ttk.Checkbutton(frame, text='Iniciar Punk ao entrar no Windows', variable=self.autostart,
                        command=self.toggle_startup).pack(anchor='w',pady=8)
        ttk.Button(frame,text='Aparelhos, integrações MCP e histórico',command=self.open_management).pack(fill='x',pady=5)
        ttk.Label(frame,text='Inicialização automática é opcional. O PC precisa estar ligado e acordado.',
                  wraplength=460).pack(anchor='w',pady=6)
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

    def toggle_startup(self):
        try:
            enabled = set_startup(getattr(self.connection,'root',ROOT), self.autostart.get())
            self.autostart.set(enabled)
            self.notice.set('Inicialização automática ativada para seu usuário.' if enabled else 'Inicialização automática desativada.')
        except (OSError, ValueError, RuntimeError) as error:
            self.autostart.set(startup_enabled(getattr(self.connection,'root',ROOT)))
            self.notice.set(str(error) if isinstance(error,RuntimeError) else 'Não foi possível alterar a inicialização automática deste usuário.')

    def open_management(self):
        if self.manager and self.manager.winfo_exists():
            self.manager.lift(); return
        self.manager = tk.Toplevel(self.root)
        self.manager.title('Punk · aparelhos e integrações MCP')
        self.manager.geometry('960x690')
        tabs = ttk.Notebook(self.manager); tabs.pack(fill='both',expand=True,padx=12,pady=12)
        self.management_trees = {}
        specs = [('devices','Aparelhos',('name','connected','revoked','voice','id')),
                 ('integrations','Integrações MCP',('name','read_only','revoked','id')),
                 ('calls','Execuções',('tool','status','client','id')),
                 ('audit','Histórico',('ts','actor','action','target','status'))]
        frames = {}
        for key,title,columns in specs:
            page = ttk.Frame(tabs,padding=10); tabs.add(page,text=title); frames[key]=page
            tree = ttk.Treeview(page,columns=columns,show='headings',height=13)
            for column in columns:
                tree.heading(column,text={'name':'Nome','connected':'Conectado','revoked':'Revogado','read_only':'Só leitura',
                    'client':'Cliente','actor':'Responsável','tool':'Ferramenta','status':'Estado','ts':'Horário','target':'Alvo','action':'Ação','voice':'Live'}.get(column,column))
                tree.column(column,width=160,minwidth=90)
            tree.pack(fill='both',expand=True)
            bar = ttk.Scrollbar(page,orient='horizontal',command=tree.xview);bar.pack(fill='x');tree.configure(xscrollcommand=bar.set)
            self.management_trees[key]=tree
        for text,action in [('Desconectar agora','disconnect-device'),('Revogar acesso','revoke-device'),('Permitir novo vínculo','allow-repair')]:
            ttk.Button(frames['devices'],text=text,command=lambda a=action:self.management_action(a,'devices')).pack(side='left',padx=4,pady=10)
        ttk.Label(frames['devices'],text='Desconectar permite reconexão automática. Revogar bloqueia a credencial e exige novo pareamento.').pack(anchor='w')
        ttk.Button(frames['devices'],text='Solicitar parada de todas as pendências do aparelho',
            command=lambda:self.management_action('cancel-device-calls','devices')).pack(anchor='w',pady=5)
        entry = ttk.Frame(frames['integrations']);entry.pack(fill='x',pady=8)
        self.client_name=tk.StringVar(value='Meu agente')
        ttk.Entry(entry,textvariable=self.client_name).pack(side='left',fill='x',expand=True)
        self.client_readonly=tk.BooleanVar(value=True)
        ttk.Checkbutton(entry,text='Somente leitura',variable=self.client_readonly).pack(side='left',padx=8)
        self.client_device=tk.StringVar(value='Todos os aparelhos')
        self.client_device_choice=ttk.Combobox(frames['integrations'],textvariable=self.client_device,state='readonly')
        self.client_device_choice.pack(fill='x')
        actions = ttk.Frame(frames['integrations']);actions.pack(fill='x',pady=8)
        ttk.Button(actions,text='Criar integração',command=self.create_client).pack(side='left')
        ttk.Button(actions,text='Configuração para o cliente',command=self.show_mcp_config).pack(side='left',padx=5)
        ttk.Button(actions,text='Revogar',command=lambda:self.management_action('revoke-client','integrations')).pack(side='left')
        ttk.Label(frames['integrations'],text='Compatível com clientes MCP stdio. Configuração sem tokens; cofre vinculado ao usuário Windows.\n'
            'Controle completo permite ações sensíveis. A supervisão do celular continua válida.',wraplength=820).pack(anchor='w')
        ttk.Button(frames['calls'],text='Solicitar parada da execução selecionada',command=lambda:self.management_action('cancel-call','calls')).pack(anchor='w',pady=8)
        self.management_notice=tk.StringVar(value='Carregando…')
        ttk.Label(self.manager,textvariable=self.management_notice,wraplength=880).pack(anchor='w',padx=14)
        ttk.Button(self.manager,text='Atualizar',command=self.refresh_management).pack(anchor='e',padx=14,pady=8)
        self.refresh_management()
        self.manager.after(4_000,self.poll_management)

    def refresh_management(self):
        if self.management_pending:return
        self.management_pending=True
        self.submit('management',self.connection.manage)

    def poll_management(self):
        if self.closed or not self.manager or not self.manager.winfo_exists():return
        self.refresh_management()
        self.manager.after(4_000,self.poll_management)

    def selected_management(self, key):
        tree=self.management_trees[key];selected=tree.selection()
        return selected[0] if selected else None

    def management_action(self, action, key):
        target=self.selected_management(key)
        if not target:
            self.management_notice.set('Selecione um item.');return
        if not messagebox.askyesno('Confirmar alteração','Aplicar esta alteração ao item selecionado?\nExecuções podem ser interrompidas.',parent=self.manager):
            return
        self.management_notice.set('Aplicando…')
        self.submit('management_action',lambda:self.connection.manage(action,{'id':target}))

    def create_client(self):
        name=self.client_name.get().strip()
        if not name:
            self.management_notice.set('Dê um nome à integração.');return
        devices=[] if self.client_device.get()=='Todos os aparelhos' else [self.client_device.get()]
        read_only=self.client_readonly.get()
        if not read_only and not messagebox.askyesno('Controle completo',
                'Este cliente poderá executar ações, enviar dados e alterar o celular dentro das permissões concedidas. Autorizar?',parent=self.manager):return
        self.submit('management_action',lambda:self.connection.manage('create-client',{'name':name,'devices':devices,'readOnly':read_only}))

    def show_mcp_config(self):
        client=self.selected_management('integrations')
        if not client:
            self.management_notice.set('Selecione uma integração.');return
        config=json.dumps(self.connection.mcp_config(client),ensure_ascii=False,indent=2)
        window=tk.Toplevel(self.manager);window.title('Punk MCP · configuração sem tokens');window.geometry('760x430')
        text=tk.Text(window,wrap='word');text.insert('1.0',config);text.configure(state='disabled');text.pack(fill='both',expand=True)
        def copy():
            self.root.clipboard_clear();self.root.clipboard_append(config)
        def save():
            path=filedialog.asksaveasfilename(parent=window,defaultextension='.json',initialfile='punk-mcp.json',filetypes=[('JSON','*.json')])
            if path:Path(path).write_text(config,encoding='utf-8')
        ttk.Button(window,text='Copiar configuração',command=copy).pack(side='left',padx=10,pady=10)
        ttk.Button(window,text='Salvar JSON…',command=save).pack(side='left')

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
                            'Conta · ' + ('conectada' if account.get('signedIn') else 'não conectada'),
                            'PC / bridge · ' + ('disponível' if data['online'] else 'indisponível'),
                            'Celular · ' + ('conectado' if data.get('phone') else 'aguardando conexão'),
                            'Hermes · ' + ('pronto' if health.get('hermes') and health.get('policy') else 'API disponível; política pendente' if health.get('hermes') else 'indisponível'),
                            'ADB no PC · ' + ('conectado' if health.get('adb') else 'não conectado')]))
            elif kind in ('management','management_action'):
                if kind=='management':self.management_pending=False
                if not self.manager or not self.manager.winfo_exists():continue
                if error:
                    self.management_notice.set(error)
                elif kind=='management_action':
                    if data.get('id'):self.new_client=data['id']
                    status=data.get('status','')
                    self.management_notice.set('Parada solicitada, ainda sem confirmação.' if status=='cancel_requested' else 'Alteração aplicada.' + (' Integração criada; selecione-a para copiar a configuração.' if data.get('id') else ''))
                    self.refresh_management()
                else:
                    labels={c['id']:c['name'] for c in data.get('integrations',[])}
                    labels.update({'hermes':'Hermes','desktop':'Punk Desktop'})
                    for key,tree in self.management_trees.items():
                        selected=tree.selection()
                        position=tree.yview()
                        tree.delete(*tree.get_children())
                        for row in data.get(key,[]):
                            item=str(row.get('id',row.get('seq')))
                            values=[]
                            for col in tree['columns']:
                                value=row.get(col,'')
                                if col in ('connected','revoked','read_only'):value='Sim' if value else 'Não'
                                elif col=='ts':value=time.strftime('%d/%m %H:%M:%S',time.localtime(value))
                                elif col in ('actor','client'):value=labels.get(value,value)
                                elif col=='voice':value=value.get('phase','Ativo') if value and value.get('active') else 'Inativo'
                                values.append(value)
                            tree.insert('', 'end',iid=item,values=values)
                        if selected and tree.exists(selected[0]):tree.selection_set(selected[0])
                        if position:tree.yview_moveto(position[0])
                    if self.new_client and self.management_trees['integrations'].exists(self.new_client):
                        self.management_trees['integrations'].selection_set(self.new_client)
                        self.new_client=None
                    choices=['Todos os aparelhos']+[d['id'] for d in data.get('devices',[]) if not d['revoked']]
                    self.client_device_choice.configure(values=choices)
                    if self.client_device.get() not in choices:self.client_device.set(choices[0])
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
