# Punk 3.7 · Desktop, conexão e MCP

A entrega reúne o fechamento de conexão proposto para 3.6.2 e o primeiro MCP. Não é necessário refazer o login nem o pareamento existente para atualizar.

## Inicialização e conexão

Abra **Punk Desktop** pelo atalho ou `ABRIR-PUNK.vbs`. Entre na mesma conta do celular. A preferência externa fica salva; ao iniciar, o bridge recria o túnel e atualiza seu endereço na conta. O Android tenta Internet antes de LAN/VPN, sem misturar portas ou identidades TLS.

O painel distingue **Conta**, **PC / bridge**, **Celular**, **Hermes pronto** e **ADB no PC**. Prontidão do Hermes exige API e heartbeat da política. ADB do computador desconectado não impede Shizuku no celular nem comunicação pelo 4G.

**Iniciar Punk ao entrar no Windows** é opcional. Registra apenas `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\PunkCompanion`, sem administrador. Um supervisor verifica o bridge a cada 15 segundos e tenta restaurá-lo quando indisponível. Ao desmarcar, remove só a entrada desta instalação; o supervisor termina na próxima verificação, sem derrubar o bridge atual. Outra instalação não é sobrescrita.

O início acontece depois do login Windows, não antes. Não liga/acorda o PC, não configura o gateway Hermes e não funciona durante suspensão. O Hermes precisa estar instalado, provisionado e disponível para conversar. O instalador independente continua pendente.

## Aparelhos

Abra **Aparelhos, integrações MCP e histórico → Aparelhos**:

- **Desconectar agora:** encerra a sessão atual; o Android pode reconectar automaticamente com a credencial válida.
- **Revogar acesso:** invalida a credencial e o vínculo da conta, cancela filas e fecha a sessão. Solicita também a parada dos runs Hermes desse aparelho, sem afetar Telegram ou outros aparelhos. Falta de confirmação fica na auditoria.
- **Permitir novo vínculo:** depois de revogar, permite explicitamente NOVO pareamento pela conta. O bearer antigo continua inválido; nova identidade é criada. No celular, toque Conectar novamente. O histórico antigo não é apagado, mas permanece ligado à identidade anterior no bridge.
- **Solicitar parada de todas as pendências do aparelho:** envia um único pedido para reconciliar chamadas antigas/desconhecidas. Só marca parada depois da confirmação Android; não afirma que efeitos anteriores foram desfeitos. Útil ao migrar chamadas antigas em timeout.

O estado Live exibido é telemetria, não prova acústica de funcionamento do microfone/alto-falante.

## Integração MCP sem copiar tokens

1. Abra a aba **Integrações MCP**.
2. Dê um nome e escolha um aparelho ou todos. **Somente leitura** vem marcado; desmarcar permite alterações dentro das permissões Android, com confirmação explícita.
3. Clique **Criar integração**. A nova linha fica selecionada.
4. Clique **Configuração para o cliente** e copie ou salve o JSON.
5. Importe/mescle o bloco `mcpServers` em um cliente MCP com transporte **stdio**, no mesmo PC e usuário Windows. O método varia por cliente; o Punk não modifica silenciosamente outros programas.
6. Peça ao agente que descubra aparelhos e catálogo antes de agir. Revogue o acesso pelo painel quando não precisar mais.

A configuração contém executável Python, adaptador, runtime e ID da integração, não seu segredo. O adaptador lê o segredo no cofre DPAPI do usuário. Copiar o JSON para outra máquina/usuário não transfere autorização. O ambiente indicado pelo Desktop precisa de MCP Python SDK 2.0.0 e jsonschema, incluídos em `bridge/requirements.txt`.

O token administrativo NÃO é distribuído ao MCP. A API MCP só escuta no loopback; suas credenciais não acessam conta, gestão, publicação de arquivos ou API Hermes. Credenciais Android também não acessam MCP. A consulta/cancelamento de chamadas é limitada ao próprio cliente e ao aparelho autorizado.

## Ferramentas

| Ferramenta | Função |
| --- | --- |
| `punk_devices` | Aparelhos autorizados, conexão e capacidades/permissões recebidas do Android. |
| `punk_tools` | Catálogo nativo, schemas, risco e disponibilidade; somente leitura filtra alterações. |
| `punk_execute` | Admite uma ação com `callId` estável e obrigatório. Admissão não é sucesso. |
| `punk_call_status` | Consulta persistida, sem repetir efeitos. |
| `punk_cancel` | Solicita parada e informa se houve confirmação. |
| `punk_events` | Atividades desde cursor e observações recentes, nunca instruções automáticas. |

Os 84 schemas Android são descobertos por `punk_tools`, não anunciados como 84 ferramentas MCP independentes. Ferramentas nativas de arquivos continuam no catálogo, dentro do escopo. Upload/download/retomada MCP dedicado e publicação arbitrária de caminhos do PC NÃO foram expostos nesta entrega.

## Fila, resultados e cancelamento

Ferramentas Android encaminhadas por Hermes e MCP usam uma fila por aparelho. Só uma é despachada por vez; outros aparelhos têm filas independentes. Isso não controla código arbitrário externo ao bridge, como outro programa usando ADB diretamente.

Estados: `queued`, `running`, `completed`, `failed`, `cancel_requested`, `cancelled`, `unknown`. Sucesso exige resposta real do Android, respeitando erros aninhados. Permissões e supervisão do Android continuam soberanas; uma integração não concede Shizuku/acessibilidade ou acesso a dados automaticamente.

O `callId` vincula cliente, aparelho, ferramenta e hash dos argumentos. Repetir a mesma chamada consulta o mesmo registro; alterar argumentos ou usar ID de outro cliente é recusado. Depois de queda ou timeout, NÃO gere outro ID para repetir uma ação com efeito externo.

Timeout/desconexão deixam resultado `unknown`, bloqueando novas ações até resultado tardio ou parada confirmada. Após reinício, ações em voo ficam desconhecidas e filas antigas são canceladas, nunca reenviadas. Em **Execuções**, o administrador pode solicitar parada de chamadas órfãs. Reconecte o aparelho para confirmar; sem confirmação, o bloqueio continua.

Cancelar uma chamada na fila não envia cancelamento ao telefone. Uma chamada despachada aguarda `cancel_ack` depois da barreira local Android. `cancel_requested` não é `cancelled`. Parar não desfaz mensagens ou alterações já realizadas; resultados concluídos enquanto a parada é solicitada são preservados.

`settled` indica estado terminal. `outcomeKnown` só é verdadeiro quando houve resultado concluído/falha; uma parada confirmada não prova quais efeitos ocorreram antes. `effectsMayHaveOccurred` é conservador e impede interpretar cancelamento como rollback.

**Histórico** mostra responsável, ação, alvo, horário e estado, sem argumentos, resultados, transcrições ou tokens. As abas atualizam periodicamente. Resultados de ferramentas e eventos autorizados podem conter dados pessoais: conceda leitura apenas a clientes confiáveis. DPAPI/MCP não isolam código malicioso executado sob o mesmo usuário Windows; não são um sandbox do sistema operacional.

## Validação e limites

Foram testados dois clientes do SDK e um processo stdio real contra aparelho simulado, sem LLM/custo de inferência. Testes incluem isolamento, fila Hermes/MCP, revogação, timeout, resultado tardio, parada e reinício do bridge, além de registro Windows em chave temporária isolada. Não foram testados modelos comerciais distintos.

O usuário confirmou 4G na 3.6.1. Troca de rede/reinício físico e acústica Live com o APK 3.7 continuam pendentes: o aparelho não estava no ADB e o usuário estava sem cabo. A proteção half-duplex e seus testes continuam presentes: sem escuta durante TTS e por 900 ms depois. Não há interrupção automática por voz durante TTS.

Pendências de produto: instalador autossuficiente/provisão visual, recuperação de senha na interface, verificação de e-mail/MFA, mDNS e MCP remoto com autenticação própria. Próximo foco: 3.8, chat e observabilidade.

Referência: [SDK oficial MCP Python 2.0.0](https://github.com/modelcontextprotocol/python-sdk/tree/v2.0.0). MCP fornece ferramentas; não transfere memória, personalidade ou identidade do Hermes para outro modelo.
