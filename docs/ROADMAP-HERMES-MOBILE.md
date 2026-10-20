# Punk + Hermes Mobile — diagnóstico e roadmap

## O que foi implementado nesta rodada

- Atividades que ficaram em `running`, `pending`, `waiting`, `started` ou `executing` agora são marcadas como `interrupted` quando o app inicia novamente. Isso elimina tarefas fantasmas depois de reinício ou perda de sessão.
- O banco Room foi migrado da versão 1 para a 2 sem apagar o histórico. Cada atividade agora preserva `ts` (início) e `updatedAt` (última atualização).
- A tela Atividade mostra duração, horário da última atualização, origem, status técnico e ID. Registros atualizados recentemente sobem para o topo.
- Eventos de ferramentas do Hermes agora incluem entrada, resultado, mensagem e erro quando disponíveis. Campos que parecem conter senha, token, cookie, credencial ou segredo são ocultados e o tamanho é limitado.
- Eventos `tool.started` e `tool.completed` com o mesmo `tool_call_id` são unidos em um único histórico detalhado, preservando entrada e resultado.
- O modo de voz virou uma conversa contínua por turnos: ouvir → enviar ao Hermes → falar a resposta → voltar a ouvir. Há um controle visível para pausar/retomar o modo Live.

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

- `python -m unittest discover -s tests -q`: 14 testes, todos aprovados.
- `:feature-chat:testDebugUnitTest :app:compileDebugKotlin`: aprovado.
- `:app:assembleDebug`: aprovado.
- `git diff --check`: sem erros de whitespace.

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.

Referência usada para a migração e consultas assíncronas do Room: [documentação oficial Android](https://developer.android.com/training/data-storage/room/accessing-data).
