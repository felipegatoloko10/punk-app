# Punk + Hermes Mobile — diagnóstico e roadmap

## Atualização 3.7.0: fechamento de conexão e MCP

A 3.7 absorve a proposta 3.6.2: inicialização Windows opt-in, estados separados e gestão visual de aparelhos/revogação. MCP stdio com credencial por cliente, escopo por aparelho, catálogo, execução, consulta/cancelamento e eventos usa a mesma fila das ferramentas encaminhadas pelo Hermes. Não repete ações desconhecidas após timeout/reinício. [Guia 3.7](MCP-3.7.md). Instalador autossuficiente, MCP remoto e testes físicos Live/reinício continuam pendentes. Próximo foco: 3.8.

## Atualização 3.5.2: voz e conexão visual

- Corrigida a reabertura precoce da escuta: confirmação da mensagem do usuário e primeiro trecho de streaming não significam resposta final do assistente.
- O Live suspende captura durante o TTS e espera 900 ms após a fala. A interrupção automática por volume saiu do fluxo ativo após auto-interrupções observadas no S23; o botão de interrupção permanece.
- Painel Windows inicial em `ABRIR-PUNK.vbs`: conectar PC, ver estado dos componentes e gerar QR sem terminal, em instalações já provisionadas.
- A nova prioridade proposta é 3.6 conexão/instalador, 3.7 MCP e 3.8 chat/observabilidade. O chat profissional continua no escopo; o detalhamento atualizado está em [Punk Desktop e MCP](PUNK-DESKTOP-MCP.md). O MCP e o instalador autossuficiente ainda não foram implementados.

## O que foi implementado nesta rodada

- Atividades que ficaram em `running`, `pending`, `waiting`, `started` ou `executing` agora são marcadas como `interrupted` quando o app inicia novamente. Isso elimina tarefas fantasmas depois de reinício ou perda de sessão.
- O banco Room foi migrado da versão 1 para a 2 sem apagar o histórico. Cada atividade agora preserva `ts` (início) e `updatedAt` (última atualização).
- A tela Atividade mostra duração, horário da última atualização, origem, status técnico e ID. Registros atualizados recentemente sobem para o topo.
- Eventos de ferramentas do Hermes agora incluem entrada, resultado, mensagem e erro quando disponíveis. Campos que parecem conter senha, token, cookie, credencial ou segredo são ocultados e o tamanho é limitado.
- Eventos `tool.started` e `tool.completed` com o mesmo `tool_call_id` são unidos em um único histórico detalhado, preservando entrada e resultado.
- O modo de voz virou uma conversa contínua por turnos: ouvir → enviar ao Hermes → falar a resposta → voltar a ouvir. Há um controle visível para pausar/retomar o modo Live.
- O botão **Falar** não é mais disputado por uma escuta automática ao abrir a tela. A sessão agora informa o código de erro do Android e não exige foco de áudio exclusivo para reconhecer a fala.
- A tela Dispositivos separa a depuração USB local do vínculo ADB real com o computador. O bridge consulta o Platform Tools periodicamente e não envia o serial do aparelho ao app.

### Entregue na versão 3.2.0

- `screen_dump` agora devolve uma árvore JSON com hierarquia, texto, descrição, ID, classe, limites, estado e ações disponíveis; conteúdo de campos de senha é substituído.
- `find_element` localiza candidatos por texto, descrição ou ID e informa pontuação e limites.
- `find_and_click` tenta novamente, rola a tela de forma limitada e sobe até o ancestral clicável antes de agir.
- `accessibility_events` expõe um buffer sequencial de mudanças de janela e conteúdo, evitando screenshots ou `uiautomator` contínuos.
- `ocr_screen` captura a tela pelo Shizuku e executa reconhecimento local com ML Kit, retornando texto e coordenadas; a imagem temporária é apagada no bloco de finalização.
- As notificações agora têm chave, categoria e capacidades. Quando permitido pelo app de origem, o Hermes pode abrir, dispensar ou responder por `RemoteInput`.
- O cartão do aparelho identifica **Shizuku · ADB local** separadamente do **ADB com o PC**. O aparelho de teste confirmou Shizuku autorizado como UID 2000 (`shell`).
- O catálogo passou de 43 para 50 ferramentas com schemas e validação correspondentes.

## Diagnóstico da paridade com o Telegram

Na instalação atual, o perfil padrão do Hermes e o perfil `punk-mobile` já apontam para:

- provedor: `nine_router`;
- modelo: `cx/gpt-5.6-sol`;
- mesmo endpoint local de inferência;
- `toolsets.enabled: ['*']` nos dois perfis.

Portanto, a diferença percebida não está vindo do provedor/modelo neste momento. Os fatores que ainda diferenciam a experiência são:

1. O celular usa outra sessão e um perfil móvel separado. A personalidade e memórias locais copiadas são snapshots; só a memória durável do Hindsight é compartilhada.
2. O bridge atualmente ativo carrega `C:\Users\Usuario\punk-android-v2\bridge\server.py`, enquanto as melhorias desta rodada estão neste checkout novo. É necessário atualizar o serviço ativo e reconstruir/instalar o APK para elas aparecerem no aparelho.
3. Telegram pode aplicar instruções de canal, contexto anterior e formatação próprios. Paridade real exige uma camada comum de identidade e políticas, não apenas o mesmo modelo.

## Próxima etapa recomendada: uma identidade, vários canais

Criar no Hermes uma configuração compartilhada, consumida por Telegram e `punk-mobile`, contendo:

- prompt/identidade base única;
- mesmas skills e ferramentas autorizadas;
- mesma memória Hindsight e regras de recuperação;
- políticas específicas apenas na borda de cada canal (Telegram ou Android);
- diagnóstico exposto pelo bridge com perfil, modelo efetivo, provedor efetivo, conjunto de ferramentas e versão carregada.

O app deve mostrar esse diagnóstico em Dispositivos. Assim será possível provar, e não apenas presumir, que Telegram e celular estão usando a mesma configuração efetiva.

## Roadmap sugerido

### P0 — confiabilidade e paridade

- Atualizar o bridge ativo, manter uma cópia privada de `runtime/` e reiniciar de forma coordenada.
- Instalar o novo APK e validar migração do histórico no aparelho.
- **Concluído em 3.5.0:** diagnóstico autenticado e cartão com perfil, modelo, provedor, sincronização de identidade e paridade do toolset com o Telegram.
- Reconciliar também atividades remotas após reconexão, com histórico de execução fornecido pelo bridge.
- Criar testes de migração Room em dispositivo/emulador e teste ponta a ponta com um Hermes real de homologação.
- Validar no S23 Ultra o serviço de reconhecimento Samsung/Google e registrar o código retornado pelo Android caso ainda haja recusa.

### P0 — percepção e automação robusta no Android

- **Concluído em 3.2.0:** `ui_dump` estruturado e sanitizado a partir de `AccessibilityNodeInfo`, com texto, id, classe, bounds e ações disponíveis.
- **Concluído em 3.2.0:** `find_and_click` por texto/id com espera, scroll limitado e identificação do alvo. É a melhoria com maior impacto para abandonar coordenadas frágeis.
- **Concluído em 3.5.0:** eventos de acessibilidade são enviados ao bridge com deduplicação, limitação de frequência, contador de descartes e buffer privado; observações nunca entram automaticamente como prompt.
- **Concluído em 3.2.0:** OCR local do conteúdo visível, sem upload da captura.
- **Concluído em 3.2.0:** leitura, abertura, dispensa e resposta inline de notificações compatíveis.
- Abrir apps, deep links, rotas e folhas de compartilhamento por intents validadas. Intents arbitrárias devem continuar bloqueadas ou exigir confirmação.

### Entregue na versão 3.4.0 — ação e dados pessoais

- Gestos avançados via `dispatchGesture`: toque longo, arrastar e soltar, caminho com até 32 pontos e pinça/zoom multitouch, todos com limites de tela e timeout.
- Intents tipadas para rota/mapa, compartilhamento e alarmes; nenhum `Intent` arbitrário vindo do modelo é aceito.
- Gerenciador de arquivos confinado ao armazenamento compartilhado: listar, buscar, ler com limite, criar pasta, copiar, mover e excluir para lixeira recuperável. A transferência com hash e progresso continua pelo bridge existente.
- Contatos e agenda: consultar/criar contatos, consultar/criar eventos e criar alarmes com resultado estruturado.
- A caixa SMS já combina leitura e resposta por `read_sms`/`send_sms`; o histórico de chamadas continua somente leitura e a chamada direta permanece sensível.

### Entregue na versão 3.4.0 — administração privilegiada de alto risco

- Controles via Shizuku: Wi-Fi, Bluetooth, dados móveis, modo avião, brilho, volume, Não Perturbe, rotação e economia. As novas alterações consultam o estado final e não relatam sucesso sem confirmação.
- Gestão de aplicativos: detalhes, instalar/atualizar APK com staging SELinux compatível, desinstalar, `force-stop`, limpar cache, limpar dados e conceder/revogar permissões runtime. Cache e dados são ferramentas separadas.
- Energia e bloqueio: acordar, bloquear e navegar na tela bloqueada. O PIN opcional é cifrado com AES-GCM por uma chave Android Keystore não exportável e é removível por ferramenta própria.
- Ações irreversíveis permanecem classificadas como `destructive`, passam pelo modo supervisionado quando ele está ativo e entram na auditoria com parâmetros sensíveis ocultos.

### Entregue na versão 3.5.0 — Live de baixa latência e paridade

- Serviço foreground de voz iniciado pelo usuário, notificação persistente, transcrição parcial, detecção de fim de fala e continuidade fora da tela do chat.
- Reconhecimento on-device do Android quando disponível, fallback para o reconhecedor do sistema e wake phrase opcional “Punk”/“Ei, Punk”. Não foi incorporado um SDK comercial de wake word.
- Interrupção manual disponível. O detector por voz introduzido em 3.5.0 foi retirado do fluxo ativo em 3.5.2 por confundir o TTS do alto-falante com voz do usuário.
- Texto parcial e estado da sessão seguem ao bridge no mesmo contexto da conversa. Áudio bruto não é transmitido porque o Hermes 0.22 instalado não anuncia uma API Realtime de áudio.
- Sincronização automática do perfil padrão para `punk-mobile`: identidade, modelo, provedor, skills, memória configurada e o mesmo toolset do Telegram, com histórico/canais/segredos isolados.

### Chat e observabilidade — remanejado para 3.8 na proposta Desktop/MCP

- Cartões de ferramenta dentro de cada resposta, com argumentos sanitizados, progresso, resultado, duração e relação pai/filho.
- Linha do tempo recuperável do bridge para reconciliar atividades remotas após reconexão e reinício do app.
- Pré-visualização de imagens/documentos, fixação e arquivamento de conversas e busca global mais rápida.
- Diagnóstico de voz por aparelho: latência STT/Hermes/TTS, motivo de fallback e consumo estimado de bateria, sem armazenar áudio.

### 4.0 — autonomia orientada por eventos

- Regras como “quando chegar em casa”, “ao receber notificação X” ou “todo dia às 8h”, com condições, janela de execução e histórico.
- Modo missão para objetivos longos: plano, checkpoints, orçamento, recuperação após falha e confirmação antes de passos destrutivos.
- Transformar os eventos já enviados ao bridge em regras explícitas e consentidas, com filtros locais e orçamento de execução.

### P1 — chat de nível profissional

- Renderização completa de Markdown: blocos de código, tabelas, listas, citações e links.
- Busca em conversas, fixação, renomear, arquivar e excluir.
- Respostas com cartões de ferramenta recolhíveis dentro da conversa, incluindo progresso, duração e resultado.
- Pré-visualização de imagens, documentos e arquivos enviados/recebidos.
- Ações rápidas contextuais: copiar, compartilhar, continuar, refazer, transformar em tarefa e enviar ao PC.
- Indicador explícito de modelo, perfil, memória e modo supervisionado em cada conversa.

### P1 — expansão Live multimodal

- **Concluído:** cancelamento manual, transcrição parcial e continuidade em foreground. Em 3.5.2, captura bloqueada durante o TTS e retorno após a cauda de eco; interrupção automática exige futura validação acústica, não somente detecção de volume.
- Tornar o fim de fala ainda mais natural e adicionar escolha de voz/velocidade.
- Adotar áudio Realtime full-duplex quando o Hermes expuser um transporte compatível; hoje o fallback por turnos é intencional.
- Modo câmera: apontar o celular e perguntar sobre o que está na tela/câmera.
- Adicionar controles de privacidade e consumo ainda mais granulares à sessão persistente já entregue.

### P2 — mais “poderes” no celular

- Rotinas pessoais: “quando eu chegar em casa”, “todo dia às 8h”, “se receber notificação X”.
- Contexto do aparelho com consentimento: bateria, rede, localização aproximada, calendário, mídia e notificações.
- Ações compostas verificáveis: abrir app, navegar, preencher, capturar tela e retornar evidência visual.
- Modo missão: objetivo longo com plano, checkpoints, orçamento de tempo e aprovação antes de ações sensíveis.
- Central de permissões por ferramenta, com autorização única, por sessão ou permanente.
- Widget, atalho de tela bloqueada, Quick Settings e comando de voz sempre disponíveis.
- “Enviar para o Punk” no menu Compartilhar do Android para páginas, imagens, PDFs e texto selecionado.
- Painel de saúde do ecossistema: PC, bridge, Hermes, memória, modelo, latência e ferramentas.

### Avaliação das sugestões adicionais

- **Alta prioridade:** árvore de UI, busca/clique sem coordenadas, eventos de acessibilidade, OCR local, intents e transferência de arquivos. Esses recursos aumentam muito a confiabilidade e podem retornar evidências verificáveis.
- **Úteis com consentimento explícito:** ler/responder notificações, contatos, agenda, chamadas e SMS. Devem ter autorização por ferramenta, campos sensíveis ocultos e histórico auditável.
- **Úteis, mas dependentes do aparelho:** controles de Wi-Fi, Bluetooth, brilho, volume, rotação e economia via Shizuku. Cada comando precisa detectar suporte real e nunca prometer sucesso só porque o shell não gerou erro.
- **Alto risco:** instalar/desinstalar apps, limpar dados, revogar permissões e desbloquear com PIN. Devem ficar fora do modo autônomo por padrão. O PIN não deve ser armazenado nem automatizado antes de um modelo de ameaça e consentimento específico.
- **Custo de bateria/privacidade:** wake word permanente e escuta com a tela apagada precisam de sessão visível, notificação persistente, opção de desligar e medição em aparelho real. Vosk/Porcupine não devem ser adicionados só para simular “Live”.

### P2 — atividades como observabilidade real

- Linha do tempo hierárquica: pedido → execução Hermes → chamadas de ferramenta → resultado.
- Filtros por conversa, ferramenta, dispositivo, status e intervalo de tempo.
- Métricas locais: duração, taxa de sucesso, cancelamentos e falhas mais frequentes.
- Evidências anexas: screenshot, arquivo gerado, saída resumida e link para a mensagem que iniciou a ação.
- Estado `unknown` para resultados que podem ter ocorrido, mas cuja confirmação foi perdida.

## Banco de dados

Não é necessário adicionar Vercel/Neon para corrigir os bugs atuais: Room no celular e SQLite no bridge bastam e preservam a privacidade local.

Um Postgres gerenciado passa a fazer sentido quando houver:

- sincronização entre vários celulares/computadores;
- histórico disponível fora da rede local;
- push notifications e filas duráveis;
- contas de usuários, equipes ou compartilhamento;
- métricas agregadas e recuperação de desastre.

Nesse estágio, a recomendação é manter conteúdo sensível e credenciais fora da nuvem por padrão, sincronizar apenas metadados/eventos necessários e usar criptografia ponta a ponta para conversas ou arquivos privados.

## Verificação executada

- `python -m unittest discover -s tests -v`: 18 testes, todos aprovados.
- Suítes Android/JVM: 28 testes, sem falhas ou erros.
- `:app:assembleRelease :app:lintDebug`: aprovado.
- `git diff --check`: sem erros de whitespace.
- S23 Ultra: instalação por ADB wireless, versão 3.5.0/código 29, reconexão automática e interface Live verificadas.

APK de distribuição gerado em `apk/Punk-3.5.0.apk`.

Referência usada para a migração e consultas assíncronas do Room: [documentação oficial Android](https://developer.android.com/training/data-storage/room/accessing-data).
