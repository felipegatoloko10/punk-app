# Punk · 3.6.0

**Seu agente no computador e no Android, em uma única conversa.**

[Baixar APK](apk/Punk-3.6.0.apk) · [Conta e acesso externo](docs/CONTA-PUNK.md) · [Configurar o bridge](bridge/README.md) · [Protocolo](bridge/PROTOCOL.md) · [Validação da versão](apk/Punk-3.6.0-validation.json) · [Desktop e MCP](docs/PUNK-DESKTOP-MCP.md) · [Roadmap](docs/ROADMAP-HERMES-MOBILE.md)

Aplicativo Android nativo para conversar com o seu Punk e usar as ferramentas do Hermes no computador e no celular. Interface em português, com fundo grafite, destaque ciano e três áreas: **Conversa**, **Dispositivos** e **Atividade**.

## Instalar no celular

O APK está em [`apk/Punk-3.6.0.apk`](apk/Punk-3.6.0.apk), versão 3.6.0, código 32, pacote `com.punk.app`. A compilação de distribuição tem depuração desativada e usa a mesma assinatura das versões 3.x, permitindo atualização direta sem remover o app. A migração Room 1 → 2 mantém conversas e atividades existentes.

Novo: **Conta Punk com e-mail e senha**, sessão salva no cofre do Windows/Android e descoberta do PC. No Desktop, crie a conta e toque **Ativar acesso externo gratuito**. No Android, entre na mesma conta e escolha o PC. Não é necessário comprar domínio nem copiar tokens. [Instruções e limites](docs/CONTA-PUNK.md).

1. Copie o APK para o S23 Ultra e instale.
2. No PC já configurado, abra **ABRIR-PUNK.vbs** com duplo clique: o painel Punk Desktop mostra a conexão e oferece **Conectar ao PC**.
3. No painel, clique **Conectar celular · mostrar QR**: abre uma página local com QR de uso único, válido por cinco minutos. Os scripts `bridge/INICIAR-PUNK.ps1` e `bridge/PAREAR-PUNK.ps1` continuam disponíveis para manutenção.
4. No app, abra **Configurações → Ler QR code**. Também é possível importar o `bridge/runtime/pairing.json` ou colar seu conteúdo.
5. Em **Dispositivos**, ative os recursos desejados. Microfone, câmera, localização, contatos e SMS têm concessões próprias; acessibilidade e Shizuku são apresentados separadamente.

As portas novas são **18765 (TLS)** e **18766 (API privada em loopback)**, preservando o bridge anterior nas portas 8765/8766 durante a transição. O canal do celular exige credencial por dispositivo. Na rede local, confere a identidade criptográfica do PC; no modo Conta Punk remoto, usa HTTPS/WebSocket pela Cloudflare com certificado público válido. O Tailscale manual continua como alternativa, sem alternar a VPN automaticamente.

Em uma instalação nova no computador, siga primeiro a [provisão do bridge](bridge/README.md). O painel e os scripts pressupõem que essa configuração já existe; o repositório não inclui as credenciais nem a identidade pessoal do agente. O instalador autossuficiente e o servidor MCP estão planejados em [Desktop e MCP](docs/PUNK-DESKTOP-MCP.md), ainda não implementados.

## O que foi implementado

- Conta gerenciada pelo Neon, cadastro/criação de conta no Desktop e Android, credenciais de sessão cifradas e renovação automática do JWT. Senhas não são persistidas.
- Descoberta de computadores por proprietário, convites de conexão com 90 segundos e uso único, vínculo estável por instalação e respeito à revogação. A migração de um QR válido para conta conserva o ID do aparelho e o histórico do bridge.
- Acesso externo opcional via Cloudflare Quick Tunnel, sem domínio comprado. Download oficial com SHA-256, processo supervisionado pelo bridge, verificação TLS da origem e atualização automática do endereço na conta.
- Reconexão com descoberta atualizada e fallback de DNS-over-HTTPS somente para `*.trycloudflare.com` quando o DNS do sistema falha. Não muda DNS/VPN do aparelho nem desativa TLS.
- Conversa com streaming, Markdown, código copiável, múltiplas conversas, histórico persistente, busca, exportação e repetição explícita de pedidos interrompidos.
- Modo Live persistente em foreground: continua ativo ao fechar a folha ou apagar a tela, mantém notificação com controles e reabre a escuta após cada resposta.
- Wake phrase opcional “Punk”/“Ei, Punk”, reconhecimento local quando disponível, fallback para o reconhecedor do sistema, transcrição parcial e indicação clara de escuta, processamento e fala.
- Proteção contra eco: o microfone permanece fechado durante o TTS e por 900 ms após o término ou interrupção. O botão **Interromper e falar** corta o TTS; o detector automático baseado em volume foi retirado do fluxo ativo após reproduzir auto-interrupções no S23.
- Envio de voz com ID registrado antes do envio; o botão de voz não cancela o pedido enquanto o Hermes processa. A escuta aguarda o término da resposta do assistente, não apenas a confirmação da mensagem do usuário.
- Telemetria Live sem áudio bruto: sessão, bateria, modo do reconhecedor e texto parcial seguem ao bridge; nenhuma captura de microfone é gravada ou enviada.
- Cartões de dispositivos e permissões com estado real. A aba Atividade agora mostra duração, origem, status técnico, ID, última atualização, entradas e resultados sanitizados das ferramentas.
- Diagnóstico ADB em tempo real: o app separa “Depuração USB ativada” de “PC conectado e autorizado”. O bridge verifica o `adb devices` local a cada dez segundos e informa conectado, aguardando autorização, offline, ausente ou erro sem transmitir o serial do aparelho.
- Diagnóstico local independente: **Shizuku · ADB local** mostra quando o serviço privilegiado está ativo e autorizado no próprio celular, sem confundir esse estado com o ADB do computador.
- Reconciliação de atividades órfãs: execuções que ficaram sem confirmação após reinício ou perda de sessão deixam de aparecer eternamente como “em andamento”.
- Autonomia por padrão. O modo supervisionado pede aprovação antes de ferramentas de alteração ou comportamento indeterminado; vale para o canal mobile, sem alterar a política dos outros canais.
- Comandos explícitos: `/supervisionado`, `/autonomo`, `/parar`; na voz, “Punk, peça confirmação antes de agir”, “Punk, volte ao modo autônomo” e “Punk, para tudo”.
- Parada de execuções locais e solicitação de cancelamento remoto. A confirmação remota espera as tarefas e os recursos encerrarem; falha de parada permanece visível.
- Árvore de interface estruturada em JSON com texto sanitizado, ID, classe, hierarquia, limites, estado e ações de cada nó. Campos de senha nunca têm o conteúdo exposto.
- Busca de elementos por texto, descrição ou ID; `find_and_click` repete a busca, rola a tela de forma limitada e clica no ancestral acionável, reduzindo a dependência de coordenadas fixas.
- Buffer sequencial de eventos de acessibilidade para detectar troca de janela, diálogos e mudanças de conteúdo sem capturar screenshots continuamente.
- Push de eventos de acessibilidade com deduplicação e limite local; o bridge mantém um buffer privado consultável pelas ferramentas sem transformar texto de apps em instruções do agente.
- OCR local da tela com o modelo latino do ML Kit: retorna texto, blocos, linhas, elementos e coordenadas sem enviar a imagem a um serviço externo.
- Notificações acionáveis: além de listar, o Hermes pode abrir, dispensar e responder inline quando o aplicativo de origem oferece `RemoteInput`.
- Gestos avançados por acessibilidade: toque longo, arrastar e soltar, caminho com até 32 pontos e pinça/zoom real com dois dedos.
- Intents tipadas para mapas, navegação, compartilhamento e alarmes, sem aceitar intents arbitrárias produzidas pelo modelo.
- Gerenciador de arquivos confinado ao armazenamento compartilhado: listar, buscar, ler com limite, criar pastas, copiar, mover e enviar para uma lixeira recuperável.
- Contatos e agenda: criação de contatos, consulta/criação de eventos e alarmes, além das ferramentas existentes de SMS e chamadas.
- Administração Shizuku com leitura do estado e confirmação posterior: Wi-Fi, dados móveis, Bluetooth, modo avião, brilho, rotação, Não Perturbe e economia de bateria.
- Administração de aplicativos: detalhes, instalação corrigida por staging em `/data/local/tmp`, desinstalação, limpeza separada de cache/dados e concessão/revogação de permissões runtime.
- Energia e bloqueio: acordar, bloquear e desbloquear. O PIN opcional é cifrado com AES-GCM por uma chave não exportável do Android Keystore e nunca aparece no resultado ou auditoria.
- Catálogo de 84 ferramentas Android com schemas, validação, callbacks de gestos, tratamento de falhas e cancelamento.
- Anexos originais com chunks, progresso, retomada e SHA-256; limite de 512 MB por arquivo e cinco anexos por pedido. Recebimento de arquivos do PC na conversa.
- Credenciais Android protegidas pelo Keystore, autenticação separada no PC e auditoria com dados sensíveis removidos e cadeia de integridade.

## Integração com o agente

O bridge usa a API nativa do Hermes (`/v1/runs` + SSE retomável), no perfil `punk-mobile`. Ao iniciar, ele sincroniza novamente identidade, modelo, provedor, skills, plugins e o toolset do Telegram a partir do perfil padrão. Histórico, canais e segredos continuam isolados; a memória externa configurada é compartilhada. O app mostra perfil, modelo, provedor e paridade no cartão do computador, e `identity-sync.json` registra hashes sem expor conteúdo pessoal.

O plugin `punk_control` aplica a política de execução independentemente do modo YOLO do Hermes. O bridge rejeita novos pedidos quando o componente de política não está saudável. Provisão e detalhes do protocolo: [`bridge/README.md`](bridge/README.md) e [`bridge/PROTOCOL.md`](bridge/PROTOCOL.md).

`bridge/runtime/` contém chaves, arquivos e histórico privados e está excluído do Git. Gere o pareamento localmente; não publique essa pasta. O código novo não usa nem replica a credencial do roteador embutida no servidor antigo.

## Limites e validação

O controle é limitado às permissões concedidas pelo Android, acessibilidade e Shizuku sem root, e aos acessos da sessão Windows do Hermes. Telas protegidas, dados privados de outros aplicativos, janelas elevadas e operações em segundo plano podem ser restritos. O Live exige uma sessão iniciada pelo usuário e exibe notificação persistente enquanto usa o microfone. Resposta de notificação só funciona quando a própria notificação expõe uma ação de resposta direta. Operações de administração são marcadas individualmente como destrutivas no catálogo e permanecem na trilha de auditoria sanitizada.

O modo Live usa STT/TTS do Android e continua sendo conversação por turnos: a instalação atual do Hermes não expõe transporte Realtime de áudio. Durante a fala, use o botão para interromper; a interrupção automática por voz não está ativa nesta versão. A wake phrase é reconhecida por software durante a escuta Live, não durante o TTS nem por DSP de baixíssimo consumo. Entrega de SMS/WhatsApp não é apresentada como confirmada sem verificação do aplicativo de destino. A parada não desfaz ações já concluídas; um gesto despachado pode levar até dois segundos para terminar.

Validação 3.6.0: **47 testes Android/JVM**, testes Python e testes da API de contas; consulte o [relatório da versão](apk/Punk-3.6.0-validation.json) para contagens e verificações. Login, restauração DPAPI, renovação, isolamento entre dois usuários e consumo único foram exercitados contra o Neon real. Um servidor isolado confirmou HTTPS e WebSocket autenticado pelo túnel gratuito, com TLS da origem verificado. Não foram usados a conta pessoal ou o Hermes real nesses testes de nuvem. O APK 3.6.0 ainda requer validação de login/rede no telefone físico; nenhum aparelho apareceu no ADB ao finalizar a compilação. O teste acústico Live também permanece pendente. O painel Desktop requer o PC previamente provisionado.

O Shizuku foi verificado no aparelho em execução via ADB wireless, autorizado e operando como UID 2000 (`shell`). Isso não significa que o Windows tenha enumerado o telefone: o cartão **ADB com o PC** continua sendo um diagnóstico separado e pode permanecer desconectado sem impedir as ferramentas locais via Shizuku.

## Compilar e testar

Use JDK 17 ou superior (esta execução usou JDK 21):

```powershell
.\gradlew.bat :app:assembleRelease :app:lintDebug :core-bridge:testDebugUnitTest :feature-chat:testDebugUnitTest :core-tools:testDebugUnitTest :core-security:testDebugUnitTest
```

```powershell
cd bridge
python -m unittest discover -s tests -v
```

## Organização do código

| Área | Responsabilidade |
| --- | --- |
| `app` | Navegação, visual, configurações e composição do aplicativo |
| `feature-chat` | Conversas, persistência Room, streaming e anexos |
| `core-bridge` | Pareamento, TLS, reconexão, supervisão e cancelamento |
| `core-tools` | Catálogo e execução das ferramentas Android |
| `core-accessibility` | Leitura de tela e gestos acessíveis |
| `core-axmanager` | Operações de shell com Shizuku |
| `core-voice` | Reconhecimento e síntese de voz |
| `core-security` | Auditoria e integridade |
| `bridge` | Servidor Python e plugin de integração com o Hermes |

O histórico Git mantém as versões anteriores. Os APKs antigos na pasta `apk/` são históricos; use **Punk-3.5.0.apk** para esta versão.
