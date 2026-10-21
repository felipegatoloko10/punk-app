# Punk 3.6.1 · Internet, QR e reconexão

- O acesso externo é ativado por padrão ao entrar na conta e restaurado entre reinícios do bridge. Uma desativação explícita continua respeitada. Abrir o Desktop já provisionado inicia o bridge automaticamente, sem configurar startup do Windows.
- A conexão pela conta tenta primeiro a rota de Internet publicada, seguida da rede local. Login com um único PC inicia o vínculo automaticamente; a tela diferencia conta autenticada de PC conectado.
- O novo QR contém o túnel externo ativo e mantém alternativas local/VPN. Cada rota conserva sua própria porta e identidade TLS. Gere outro QR depois de atualizar o Desktop: o QR antigo só com IP local não funciona pelo 4G.
- QRs locais antigos podem migrar para a conta durante reconexão/troca de rede, somente quando o certificado corresponde ao PC da conta; isso conserva o histórico e não escolhe outro PC arbitrariamente.
- Falhas informam a rota e distinguem DNS, timeout, TLS e recusa HTTP, sem reproduzir tokens ou dados de erro do servidor. DNS-over-HTTPS aceita IPv4 e IPv6.

Diagnóstico desta instalação: o túnel público estava acessível, mas não havia aparelho vinculado à conta; a conexão no Wi-Fi era um pareamento local. A correção cobre esse fluxo e a ausência da rota externa no QR anterior.

Validação: 59 testes Android/JVM, 29 Python e 4 da API, sem falhas. O teste JVM executou uma chamada real ao endereço público usando o mesmo cliente OkHttp do aplicativo; o servidor recusou corretamente acesso sem autenticação. APK código 33, assinatura compatível e lint sem erros bloqueantes. A confirmação em 4G no S23 ainda depende de reteste com este APK; não havia dispositivo conectado ao ADB.

Depois de atualizar, abra **Configurações → Conta Punk → Conectar · seu PC** uma vez, confirme **PC conectado · Internet**, desligue o Wi-Fi e repita o teste. O acesso externo continua gratuito para testes, sem garantia de disponibilidade; requer PC ligado e bridge em execução.
