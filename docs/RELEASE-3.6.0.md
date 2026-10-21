# Punk 3.6.0 · Conta e conexão automática

- Conta Punk no Desktop e Android: criar conta, entrar, restaurar sessão e renovar JWT sem copiar tokens. Login gerenciado pelo Neon; senha não persistida.
- Computadores visíveis somente ao proprietário autenticado. Convite de conexão restrito ao PC, de uso único e com 90 segundos.
- Preserva a identidade/histórico do aparelho ao migrar um QR válido e não reativa automaticamente dispositivos revogados.
- Acesso externo opcional via Cloudflare Quick Tunnel gratuito, sem domínio comprado. Download oficial verificado por SHA-256 e origem HTTPS com certificado conferido.
- Descoberta atualizada nas reconexões, rotas local/externa e alternativa de DNS-over-HTTPS limitada aos hostnames do túnel.
- README, instruções de conta e roadmap atualizados. QR/Tailscale continuam disponíveis.

Validação: 47 testes Android/JVM, 26 Python e 4 da API; zero falhas e zero erros bloqueantes de lint (83 avisos). API real de contas e túnel HTTPS/WebSocket testados com usuários/origem isolados. Contas de teste removidas. APK assinado e alinhado, código 32.

No PC já provisionado, abra **ABRIR-PUNK.vbs**, crie sua conta e ative **Acesso externo gratuito**. No APK atualizado, entre na mesma conta em **Configurações → Conta Punk** e escolha o PC. Não envie senha ou tokens no chat.

Limites: Quick Tunnel é para testes, sem SLA, com endereço temporário atualizado pelo Desktop. Serviços gratuitos têm cotas. Requer PC ligado e Hermes provisionado; ainda não há instalador autossuficiente, MCP ou gestão visual de revogações. Não foi possível testar login/troca Wi-Fi/4G no telefone físico porque não apareceu no ADB nesta sessão. O acesso externo pessoal não foi ativado automaticamente.

SHA-256 do APK: `6d455dd5960dbd0d46d8d351e1f50c8652595dfc66ec4be758e7b4f9e59fed74`.
