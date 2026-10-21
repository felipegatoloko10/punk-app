# Punk + Hermes Mobile — diagnóstico e roadmap

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
- Adicionar endpoint autenticado de diagnóstico do Hermes e cartão “Modelo e capacidades” no app.
- Reconciliar também atividades remotas após reconexão, com histórico de execução fornecido pelo bridge.
- Criar testes de migração Room em dispositivo/emulador e teste ponta a ponta com um Hermes real de homologação.
- Validar no S23 Ultra o serviço de reconhecimento Samsung/Google e registrar o código retornado pelo Android caso ainda haja recusa.

### P0 — percepção e automação robusta no Android

- **Concluído em 3.2.0:** `ui_dump` estruturado e sanitizado a partir de `AccessibilityNodeInfo`, com texto, id, classe, bounds e ações disponíveis.
- **Concluído em 3.2.0:** `find_and_click` por texto/id com espera, scroll limitado e identificação do alvo. É a melhoria com maior impacto para abandonar coordenadas frágeis.
- **Parcial em 3.2.0:** eventos de acessibilidade ficam em buffer sequencial consultável; o push contínuo, com limitação de frequência e escopo por sessão, ainda será conectado ao bridge.
- **Concluído em 3.2.0:** OCR local do conteúdo visível, sem upload da captura.
- **Concluído em 3.2.0:** leitura, abertura, dispensa e resposta inline de notificações compatíveis.
- Abrir apps, deep links, rotas e folhas de compartilhamento por intents validadas. Intents arbitrárias devem continuar bloqueadas ou exigir confirmação.

### 3.3 — ação e dados pessoais

- Gestos avançados via `dispatchGesture`: toque longo, arrastar e soltar, curvas, pinça/zoom e multitouch, todos com limites de tela e timeout.
- Intents tipadas: pacote, deep link, rota no Maps, compartilhamento, seletor de arquivo e alarmes; sem aceitar um `Intent` arbitrário vindo do modelo.
- Gerenciador de arquivos em armazenamento compartilhado: listar, buscar, ler, copiar, mover, excluir para lixeira e transferir pelo bridge com hash e progresso.
- Contatos e agenda: consultar/criar/editar contatos, eventos, lembretes e alarmes com resultado verificável.
- Caixa SMS completa: ler conversas e responder, mantendo o envio explícito e auditado. O histórico de chamadas continua somente leitura e a chamada direta permanece uma ação sensível.

### 3.4 — administração privilegiada de alto risco

- Controles via Shizuku: Wi-Fi, Bluetooth, dados móveis, modo avião, brilho, volume, não perturbe, rotação e economia, sempre confirmando o estado final.
- Gestão de aplicativos: listar detalhes, instalar/atualizar APK, desinstalar, `force-stop`, limpar cache e conceder/revogar permissões. Limpar dados fica separado de limpar cache porque destrói sessões e conteúdo.
- Energia e bloqueio: acordar, bloquear e navegar na tela bloqueada. PIN opcional somente cifrado pelo Android Keystore, nunca incluído em logs, catálogo ou bridge.
- Política própria para ações destrutivas: confirmação por ação ou sessão, alvo exibido antes da execução, trilha de auditoria e evidência posterior. O modo autônomo geral não elimina essa barreira.

### 3.5 — Live de baixa latência

- Serviço foreground de voz iniciado pelo usuário, transcrição parcial, detecção de fim de fala, redução de ruído e barge-in para interromper o TTS.
- Wake word offline opcional com Vosk ou Porcupine, indicador persistente de microfone, medição de bateria e botão único para desligar.
- Áudio e texto em streaming no mesmo contexto da conversa, com reconexão, cancelamento e fallback para o modo por turnos já existente.

### 4.0 — autonomia orientada por eventos

- Regras como “quando chegar em casa”, “ao receber notificação X” ou “todo dia às 8h”, com condições, janela de execução e histórico.
- Modo missão para objetivos longos: plano, checkpoints, orçamento, recuperação após falha e confirmação antes de passos destrutivos.
- Gatilhos de acessibilidade/notificação enviados ao bridge sem polling, com filtros locais para não transmitir eventos irrelevantes.

### P1 — chat de nível profissional

- Renderização completa de Markdown: blocos de código, tabelas, listas, citações e links.
- Busca em conversas, fixação, renomear, arquivar e excluir.
- Respostas com cartões de ferramenta recolhíveis dentro da conversa, incluindo progresso, duração e resultado.
- Pré-visualização de imagens, documentos e arquivos enviados/recebidos.
- Ações rápidas contextuais: copiar, compartilhar, continuar, refazer, transformar em tarefa e enviar ao PC.
- Indicador explícito de modelo, perfil, memória e modo supervisionado em cada conversa.

### P1 — Live multimodal

- Cancelamento por fala (barge-in) para interromper o TTS e começar uma nova pergunta.
- Detecção de fim de fala mais natural, redução de ruído e escolha de voz/velocidade.
- Transcrição parcial transmitida ao bridge e resposta de áudio com menor latência.
- Modo câmera: apontar o celular e perguntar sobre o que está na tela/câmera.
- Continuidade em segundo plano por sessão explicitamente iniciada, com notificação persistente e controles de privacidade.

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

- `python -m unittest discover -s tests -q`: 16 testes, todos aprovados.
- `:feature-chat:testDebugUnitTest :app:compileDebugKotlin`: aprovado.
- `:app:assembleDebug`: aprovado.
- `git diff --check`: sem erros de whitespace.

APK de distribuição gerado em `apk/Punk-3.2.0.apk`.

Referência usada para a migração e consultas assíncronas do Room: [documentação oficial Android](https://developer.android.com/training/data-storage/room/accessing-data).
