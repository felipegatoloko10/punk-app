# Punk 3.7.0 · conexão, gestão e MCP

APK Android: versão 3.7.0, código 34, pacote `com.punk.app`, atualização direta da série 3.x sem remover dados. A entrega absorve a proposta 3.6.2, sem exigir outro APK intermediário.

## Entregue

- Inicialização Windows opcional por usuário, supervisor do bridge e preferência externa preservada.
- Estados separados de conta, bridge, celular e Hermes pronto; correção da persistência da rota alternativa no failover.
- Painel de aparelhos: desconexão, revogação e autorização explícita de novo vínculo, mantendo credenciais antigas revogadas.
- Integrações MCP locais stdio, credenciais próprias DPAPI, escopo por aparelho e somente leitura/controle completo. Exportação visual de JSON sem tokens.
- Seis ferramentas MCP para aparelhos/catálogo, execução, resultado, cancelamento e eventos. Capacidades Android publicadas no hello e atualizadas durante a conexão.
- Fila por aparelho compartilhada entre chamadas Hermes e MCP, deduplicação vinculada ao cliente, estado persistido, bloqueio de resultado desconhecido e cancelamento com confirmação.
- Auditoria administrativa sem conteúdo sensível, nomes de clientes nas atividades e painel de execuções atualizado periodicamente.

## Uso

Atualize o APK e o checkout do PC, reinicie o bridge quando não houver execução ativa e reabra o Punk Desktop. Não precisa refazer o login existente. No painel, marque o início Windows se desejar. Abra **Aparelhos, integrações MCP e histórico → Integrações MCP**, crie uma integração e copie/salve sua configuração para seu cliente stdio.

Se houver chamadas antigas em `running`/`timeout`, a migração as conserva como resultado desconhecido e bloqueia novas ações naquele aparelho. Com o telefone conectado, selecione-o em **Aparelhos → Solicitar parada de todas as pendências do aparelho**. A confirmação encerra as pendências, mas não afirma que efeitos passados foram desfeitos. Filas anteriores ao reinício nunca são reenviadas.

[Guia e limites](MCP-3.7.md) · [Relatório de validação](../apk/Punk-3.7.0-validation.json).

Validação: **113 testes** (59 Android/JVM, 50 bridge Python, 4 API), zero falhas/erros/skips; lint sem erro bloqueante e com 83 avisos existentes. Assinatura APK v2 e alinhamento de 4 bytes verificados. A rota pública real foi consultada pelo mesmo cliente OkHttp do app e recusou acesso sem autenticação. Mantém a chave de assinatura de desenvolvimento já usada na série 3.x para preservar a atualização pessoal; uma distribuição pública endurecida exige planejamento de assinatura próprio.

## Limites honestos

O painel ainda depende do Hermes/Python provisionados. Inicialização do Punk não liga o PC, não inicia o gateway Hermes nem instala um serviço antes do login. MCP remoto e instalador independente não estão incluídos. MCP não replica o contexto/memória do Hermes.

Testes de MCP usam aparelho simulado e SDK real, não modelos comerciais. O teste acústico Live, reinício Windows real e troca Wi-Fi/4G no APK 3.7 permanecem pendentes, sem celular disponível no ADB. O usuário confirmou acesso 4G na 3.6.1. A proteção de voz por turnos continua presente; não há barge-in automático durante a fala.
