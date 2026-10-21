# Conta Punk · 3.6.0

## Usar sem terminal e sem comprar domínio

1. Atualize este repositório no PC já provisionado e abra `ABRIR-PUNK.vbs`.
2. Em **Conta Punk**, informe seu e-mail e uma senha própria e escolha **Criar conta** uma única vez. Depois, use **Entrar**. Não envie sua senha ao agente de programação.
3. Toque **Ativar acesso externo gratuito**. Na primeira vez, o Desktop baixa o `cloudflared` oficial, confere o SHA-256 publicado e prepara o túnel. Aguarde a indicação de conexão.
4. Instale o APK 3.6.0 no Android, sem remover a versão anterior. Abra **Configurações → Conta Punk** e entre na mesma conta.
5. Toque **Conectar · nome do PC**. O app tenta a rota local com certificado fixado e, se necessário, a rota externa com certificado público. O PC deve estar ligado, com o bridge e o Hermes disponíveis.

O QR continua funcionando sem depender da conta. Se o celular já estava pareado por QR, use o mesmo PC na conta para conservar a identidade do dispositivo no bridge.

## O que acontece automaticamente

- O Windows guarda a sessão no DPAPI, vinculado ao usuário do Windows. O Android usa uma chave não exportável do Keystore. A senha não é salva.
- O app renova o JWT usando a sessão gerenciada enquanto ela for válida. Sair, revogação, expiração definitiva ou perda do cofre podem exigir novo login, mas não copiar códigos no terminal.
- A conta lista apenas os computadores do proprietário autenticado. A API usa identidade verificada, não um `owner_id` enviado pelo cliente.
- O celular pede um convite aleatório de uso único, restrito ao PC/conta/instalação, válido por 90 segundos. O JWT da conta nunca é enviado ao túnel do PC.
- O PC troca o convite por uma credencial de aparelho estável. Repetir a conexão não cria outro histórico. Revogação existente não é revertida automaticamente.
- O bridge monitora o processo do túnel e publica mudanças do endereço/IP. O app consulta novamente a conta durante reconexão ou mudança de rede.
- Não há heartbeat SQL de 30 em 30 segundos: publicação só quando a rota muda ou a cada 30 minutos. A disponibilidade é confirmada pelo bridge real, não pelo horário de cadastro. Isso permite suspensão do banco gratuito.
- Fechar a janela Desktop não encerra o bridge nem um túnel ativado. **Desativar acesso externo** encerra o túnel. **Sair** encerra a sessão local e o túnel, mas não é uma revogação de todos os aparelhos já pareados.

## Limites do gratuito

O Neon da instalação está em uma organização Free. Não foi ativado plano pago. O acesso remoto usa [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/): não exige domínio ou conta Cloudflare, mas é destinado a testes, não tem garantia de disponibilidade, muda de hostname a cada criação e tem limites de concorrência. As cotas gratuitas dos serviços continuam valendo; não é serviço ilimitado.

Quick Tunnel não suporta SSE. Por isso, apenas o WebSocket do Android atravessa o túnel; o SSE entre bridge e Hermes permanece no PC. A Cloudflare termina o TLS externo e volta a usar HTTPS com a origem verificada. Não é criptografia de ponta a ponta que oculte o conteúdo da Cloudflare.

Se o DNS do sistema falhar para `*.trycloudflare.com`, o cliente Android consulta o DNS-over-HTTPS da Cloudflare somente para esse hostname. Isso não altera o DNS global ou a VPN. A validação do certificado público continua obrigatória.

A versão não inclui instalador autossuficiente, inicialização automática com o Windows, recuperação de senha com interface, MFA, gestão visual de revogações ou MCP. O Desktop ainda usa a instalação pessoal do Hermes já provisionada. O login/reconexão no Android físico e a troca Wi-Fi/4G precisam de teste no aparelho.

Este é um piloto pessoal: o cadastro atual usa e-mail/senha e ainda não exige confirmação do e-mail. Não disponibilize para terceiros antes de adicionar confirmação, recuperação de conta e gestão de aparelhos.

## Manutenção e privacidade

- Configuração pública única: `core-bridge/src/main/assets/punk-account.json`. Contém somente URLs públicas, sem chave de banco/administrador.
- API: `cloud/src/index.js`, função Neon `punkaccounts`; migrações SQL versionadas em `cloud/migrations/`. Não expõe acesso SQL direto ao app.
- Sessão privada do PC: `bridge/runtime/account.dpapi`. Não copie para o Git. Chaves, SQLite e arquivos do bridge também ficam nessa pasta ignorada.
- O túnel aponta somente para o listener público TLS do bridge. A porta interna 18766 e a API Hermes não são publicadas.
- O primeiro vínculo fixa o proprietário do PC. Trocar de proprietário requer manutenção local explícita; o painel não toma posse de outro vínculo.
- Em manutenção, feche a janela Desktop e reinicie somente o processo deste bridge, não o gateway Hermes inteiro nem serviços de outros projetos.

## Verificações reproduzíveis

Os testes normais em `bridge/tests` são locais. Os scripts abaixo são opt-in e acessam serviços externos:

```powershell
# No ambiente Python do bridge, a partir de bridge/
python -m unittest discover -s tests -v
python tests/check_cloud_accounts.py
python tests/check_cloud_tunnel.py

# A partir de cloud/
npm ci
npm test
npm run build
```

O teste de contas cria identidades descartáveis `@example.invalid` e salva seus IDs em `bridge/runtime/cloud-test-accounts.json` para remoção administrativa posterior. Não imprime senhas/sessões. O teste do túnel usa origem e certificado temporários, sem expor o Hermes pessoal, e encerra o processo ao terminar.
