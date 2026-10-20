# Punk · 3.0.0

**Seu agente no computador e no Android, em uma única conversa.**

[Baixar APK](apk/Punk-3.0.0.apk) · [Configurar o bridge](bridge/README.md) · [Protocolo](bridge/PROTOCOL.md) · [Validação da versão](apk/Punk-3.0.0-validation.json)

Aplicativo Android nativo para conversar com o seu Punk e usar as ferramentas do Hermes no computador e no celular. Interface em português, com fundo grafite, destaque ciano e três áreas: **Conversa**, **Dispositivos** e **Atividade**.

## Instalar no celular

O APK está em [`apk/Punk-3.0.0.apk`](apk/Punk-3.0.0.apk), versão 3.0.0, código 24, pacote `com.punk.app`. A compilação de distribuição tem depuração desativada e mantém a chave existente para permitir atualização dos APKs fornecidos. Antes de atualizar outro APK, confira se a assinatura é compatível. Instalar por atualização preserva os dados; a assinatura efetiva no S23 ainda precisa ser conferida.

1. Copie o APK para o S23 Ultra e instale.
2. No PC, execute `bridge/INICIAR-PUNK.ps1` para iniciar o novo bridge.
3. Execute `bridge/PAREAR-PUNK.ps1`: abre uma página local com QR de uso único, válido por cinco minutos.
4. No app, abra **Configurações → Ler QR code**. Também é possível importar o `bridge/runtime/pairing.json` ou colar seu conteúdo.
5. Em **Dispositivos**, ative os recursos desejados. Microfone, câmera, localização, contatos e SMS têm concessões próprias; acessibilidade e Shizuku são apresentados separadamente.

As portas novas são **18765 (TLS)** e **18766 (API privada em loopback)**, preservando o bridge anterior nas portas 8765/8766 durante a transição. O canal do celular exige token por dispositivo e confere a identidade criptográfica do PC. O modo remoto usa seu Tailscale já conectado; o aplicativo não alterna a VPN automaticamente.

Em uma instalação nova no computador, siga primeiro a [provisão do bridge](bridge/README.md). Os scripts de início e pareamento pressupõem que essa configuração já existe; o repositório não inclui as credenciais nem a identidade pessoal do agente.

## O que foi implementado

- Conversa com streaming, Markdown, código copiável, múltiplas conversas, histórico persistente, busca, exportação e repetição explícita de pedidos interrompidos.
- Voz na mesma conversa, com transcrição e STT/TTS Android em pt-BR. Respostas antigas não são reproduzidas ao reabrir a tela.
- Cartões de dispositivos e permissões com estado real, histórico de ações e distinção entre comando aceito e resultado confirmado.
- Autonomia por padrão. O modo supervisionado pede aprovação antes de ferramentas de alteração ou comportamento indeterminado; vale para o canal mobile, sem alterar a política dos outros canais.
- Comandos explícitos: `/supervisionado`, `/autonomo`, `/parar`; na voz, “Punk, peça confirmação antes de agir”, “Punk, volte ao modo autônomo” e “Punk, para tudo”.
- Parada de execuções locais e solicitação de cancelamento remoto. A confirmação remota espera as tarefas e os recursos encerrarem; falha de parada permanece visível.
- Catálogo de 43 ferramentas Android com schemas, validação, callbacks de gestos, tratamento de falhas e cancelamento.
- Anexos originais com chunks, progresso, retomada e SHA-256; limite de 512 MB por arquivo e cinco anexos por pedido. Recebimento de arquivos do PC na conversa.
- Credenciais Android protegidas pelo Keystore, autenticação separada no PC e auditoria com dados sensíveis removidos e cadeia de integridade.

## Integração com o agente

O bridge usa a API nativa do Hermes (`/v1/runs` + SSE retomável), no perfil `punk-mobile`. A provisão inicializa esse perfil a partir da identidade, configuração, skills e plugins selecionados do agente existente, com histórico próprio e os mesmos serviços externos de memória configurados. Mudanças futuras nos arquivos de personalidade do perfil original precisam ser sincronizadas explicitamente.

O plugin `punk_control` aplica a política de execução independentemente do modo YOLO do Hermes. O bridge rejeita novos pedidos quando o componente de política não está saudável. Provisão e detalhes do protocolo: [`bridge/README.md`](bridge/README.md) e [`bridge/PROTOCOL.md`](bridge/PROTOCOL.md).

`bridge/runtime/` contém chaves, arquivos e histórico privados e está excluído do Git. Gere o pareamento localmente; não publique essa pasta. O código novo não usa nem replica a credencial do roteador embutida no servidor antigo.

## Limites e validação

O controle é limitado às permissões concedidas pelo Android, acessibilidade e Shizuku sem root, e aos acessos da sessão Windows do Hermes. Telas protegidas, dados privados de outros aplicativos, janelas elevadas e operações em segundo plano podem ser restritos. Captura de câmera/microfone exige o Punk visível nesta versão.

A voz inicial usa os mecanismos instalados no Android. Wake word permanente, voz full-duplex, overlay e modos avançados de câmera ficam para evolução posterior. Entrega de SMS/WhatsApp não é apresentada como confirmada sem verificação do aplicativo de destino. A parada não desfaz ações já concluídas; um gesto despachado pode levar até dois segundos para terminar.

Validação desta versão: **23 testes Android e 14 testes Python passaram**, release assinada e lint sem erros bloqueantes. Foram exercitados política, cancelamento, replay, contratos de ferramentas, privacidade e auditoria. Os testes do bridge usam TLS real e Hermes simulado. Também passou um teste separado com o Hermes local real: autenticação, envio de pedido e recebimento de `PUNK_OK` por streaming. As bibliotecas nativas do APK foram verificadas quanto ao alinhamento de 16 KB.

**O S23 Ultra não esteve conectado por ADB nesta execução.** A aparência em execução, permissões Samsung, Bluetooth, comportamento em segundo plano e atualização sobre a instalação efetiva precisam de validação no aparelho.

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

O histórico Git mantém as versões anteriores. Os APKs antigos na pasta `apk/` são históricos; use **Punk-3.0.0.apk** para esta versão.
