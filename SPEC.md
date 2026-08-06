# AutomBot Connect — Especificação do App (Android)

App cliente de tunelamento/VPN para Android, sucessor espiritual do HTTP Injector/NapsternetV/HA Tunnel Plus,
integrado nativamente ao painel **AutomBot Core**.

> Rebrand: o app passou a se chamar **AutomBot Connect** (antes "AutomBot Client"), seguindo o mockup de
> design completo enviado pelo usuário (25 telas — ver seção 11). Identidade visual: roxo/violeta escuro.

---

## 1. Visão geral

- **Plataforma:** Android (nativo, Kotlin + Jetpack Compose)
- **Motor de túnel:** `VpnService` do Android, com núcleos de protocolo plugáveis (JNI para libs em C/Go quando aplicável)
- **Diferencial central:** autoconfiguração total a partir de um link de painel (AutomBot Core), sem exigir que o
  usuário monte configs manualmente

---

## 2. Protocolos suportados (v1)

### Já usados atualmente pelo usuário / obrigatórios
- SSH (com suporte a payload customizado)
- HTTP Injector-style (payload/headers customizados sobre proxy)
- WebSocket (WS/WSS) — como camada de transporte para envolver outros protocolos (SSH-over-WS, V2Ray-over-WS),
  útil para atravessar proxies que só liberam tráfego HTTP
- V2Ray (VMess / VLESS)
- OpenVPN
- BadVPN (UDP gateway, tipicamente companion do SSH tunnel)

### Modernos, incluídos desde o início
- Shadowsocks
- Trojan
- WireGuard
- VLESS + Reality / Vision
- (Avaliar para v2: Hysteria2, TUIC, ShadowTLS — usados pelo Hiddify Next para evasão de DPI mais recente)

### Núcleo técnico
- Arquitetura de "driver plugável": cada protocolo implementa uma interface comum (`ProtocolDriver`), permitindo
  adicionar novos protocolos sem reescrever o núcleo
- Considerar uso de núcleos open-source já maduros via JNI (ex: sing-box, Xray-core, wireguard-go) em vez de
  reimplementar protocolo por protocolo do zero

---

## 3. Fluxo de primeiro acesso (diferencial principal)

1. App abre pela primeira vez → pergunta se o usuário tem um **link de painel**
2. **Com link:**
   - App consulta o painel via webhook
   - Painel devolve toda a configuração (todos os meios de conexão disponíveis para aquele usuário)
   - App fica **sempre sincronizado** com o painel (atualizações futuras chegam automaticamente)
3. **Sem link:**
   - App abre em **modo livre**
   - Usuário configura manualmente: servidor próprio, painel próprio, ou arquivo de conexão privado (import)

## 4. Provisionamento automático de usuário

- Ao vincular um link de painel pela primeira vez:
  - App gera um **ID de dispositivo persistente** (UUID gerado localmente e salvo — não usar Android ID/IMEI,
    que têm restrições de privacidade e podem mudar)
  - App gera uma **senha aleatória** localmente
  - Envia (`login = device UUID`, `senha aleatória`) para o painel via POST/webhook autenticado (token do painel
    + assinatura, para evitar criação de usuários falsos)
  - Painel valida, registra e passa a reconhecer esse dispositivo como usuário único → **evita duplicidade de usuário**

---

## 5. Melhorias sobre HTTP Injector / concorrentes (feature matrix)

| Categoria | Inspirado em | Funcionalidade |
|---|---|---|
| Autoconfig | — (diferencial próprio) | Setup 100% automático via link de painel |
| Núcleo de protocolos | Hiddify/sing-box | VLESS+Reality, Trojan, Hysteria2, TUIC, ShadowTLS num core só |
| Evasão de firewall pesado | SlowDNS, WebTunnel | Modo de "última tentativa" via DNS tunneling / HTTP puro |
| Import de config | Hiddify | Deeplink de 1 clique, subscription link com auto-update |
| Roteamento | Surfshark / Hiddify | Split tunneling por app, multihop opcional |
| Payload/SSH | HA Tunnel, NapsternetV | Editor de payload customizado + subprotocolos (dnstt etc.) |
| Gestão de IP | HA Tunnel, Surfshark | Rotação de IP sem cair a conexão |
| Perfis | NapsternetV | Múltiplos perfis com troca rápida sem reconfiguração |
| Reconexão | — | Reconexão automática inteligente (detecta queda, troca de protocolo/servidor sozinho) |
| Estatísticas | — | Gráfico de consumo, velocidade e latência por servidor em tempo real |
| Segurança | — | Kill switch, configs criptografadas localmente |

---

## 6. UX

- Dashboard único de status (moderno, substitui a interface datada do HTTP Injector)
- Modo simples (usuário leigo) e modo avançado (editor de config completo)
- QR code / deeplink para importar perfil vindo do painel

## 7. Integração com AutomBot Core

- Consome API do painel/agente para: importar servidores e configs, autenticar usuário, checar validade de conta,
  baixar configs assinadas
- Webhook de provisionamento de usuário (ver seção 4)
- Suporte a domínio e IP puro no lado do agente (compatibilidade dupla)

---

## 8. Fora de escopo / não incluído

- Qualquer lógica de payload/porta específica voltada a burlar cobrança de dados móveis de operadoras
  (zero-rating fraud). Suporte a payload customizado é uma funcionalidade genérica de tunelamento — não uma
  lista de combinações que enganam operadoras específicas.

---

## 9. Próximos passos

1. Fechar lista de funcionalidades da v1 (este documento)
2. Desenhar arquitetura técnica detalhada (módulos, como cada protocolo se pluga no núcleo)
3. Especificar contrato de API entre app ↔ AutomBot Core (endpoints de webhook, autenticação, formato de config)
4. Prototipar UI (dashboard, fluxo de onboarding)
5. ~~Implementar núcleo + 1 protocolo (WireGuard) como prova de conceito~~ ✅ em andamento
6. Expandir para os demais protocolos (SSH, WebSocket, V2Ray, Trojan, Shadowsocks, VLESS+Reality, OpenVPN, BadVPN)

## 10. Status: WireGuard (primeiro protocolo implementado)

- Integração via lib oficial `com.wireguard.android:tunnel` (backend Go)
- `protocols/wireguard/WireGuardManager.kt` — envolve o `GoBackend`, expõe estado via `StateFlow`
- `protocols/wireguard/WireGuardDriver.kt` — adapta ao contrato `ProtocolDriver` genérico do núcleo
- `ui/wireguard/WireGuardScreen.kt` — tela própria: import de config (colar texto ou selecionar `.conf`),
  lista de túneis, toggle conectar/desconectar, estatísticas de tráfego em tempo real
- Fluxo de permissão de VPN do Android integrado (`VpnService.prepare`)
- Ainda não integrado ao painel — import é manual (colar/selecionar arquivo) até a autoconfig via
  webhook (seção 3) ser implementada

## 11. Mockup de referência (AutomBot Connect — 25 telas)

Usuário enviou um mockup de design completo com 25 telas, cobrindo:
- Onboarding (splash, escolha domínio/manual, conectando, criação automática de conta)
- Dashboard e gerência de conexões (múltiplos protocolos simultâneos, detalhes por conexão)
- Planos e pagamento (teste grátis 2h, mensal R$29,90, anual R$299,90, PIX com QR code) — **fora do escopo
  imediato**, entra depois de fechar a conectividade
- Modo manual/profissional (seleção de protocolo, configuração, teste de conexão com % de progresso)
- Configurações, logs, estatísticas, dispositivos, suporte, menu lateral

Identidade visual: fundo roxo-escuro profundo (#120E1B), acento violeta vibrante (#8B5CF6), acento azul
secundário (#4F8CFF) — aplicada em `ui/theme/AutomBotColors.kt`.

Ordem de implementação decidida: (1) rebrand visual ✅, (2) onboarding completo ✅, (3) dashboard/gerência de
conexões ✅, (4) modo manual/profissional ✅, (5) planos/pagamento por último.

## 12. Status: Onboarding (telas 01-06 implementadas)

- `ui/onboarding/SplashScreen.kt` — marca + tagline, avança sozinha
- `ui/onboarding/ChoiceScreen.kt` — escolha "já tenho domínio" / "não tenho domínio"
- `ui/onboarding/DomainInputScreen.kt` — input do domínio do provedor
- `ui/onboarding/ProgressStepsScreen.kt` — componente reutilizado nas telas "Conectando" e "Criando conta"
  (checklist de etapas com animação); hoje simula os passos com delay, ainda **não chama a API real**
- `ui/onboarding/AccountCreatedScreen.kt` — sucesso com contador regressivo do teste grátis (2h fixas por ora)
- Fluxo "não tenho domínio" cai direto no modo manual (Shell com WireGuard já disponível)
- Pendente: plugar `PanelWebhookClient` de verdade nas telas de progresso (hoje é só simulação visual)

## 13. Status: Dashboard e gerência de conexões (telas 07-10)

- `ui/dashboard/BottomNavBar.kt` — barra inferior (Início / Conexões / Planos / Mais)
- `ui/dashboard/DashboardScreen.kt` — plano atual + countdown do teste, resumo de conexões ativas e tráfego
- `ui/dashboard/ConnectionsScreen.kt` — lista de protocolos; **só WireGuard tem dado real** (vem do
  `WireGuardManager`), os demais aparecem como "Em breve" até os drivers existirem
- `ui/dashboard/PlanScreen.kt` — "Meu Plano", mostra o teste grátis e recursos inclusos
- `ui/dashboard/MoreScreen.kt` — placeholder para configurações/logs/estatísticas/dispositivos/suporte
  (telas 20-25 do mockup, ainda não implementadas)
- Countdown do teste grátis elevado para o `AppRoot` (fonte única), continua contando entre Dashboard,
  Conexões, Meu Plano e a tela de conta criada
- Tela 09 ("Detalhes da Conexão") não foi construída separada — o `WireGuardScreen` já cobre lista +
  detalhe + estatísticas para esse protocolo; ao adicionar SSH/V2Ray/etc. reavaliar se cada um precisa
  de tela de detalhe própria seguindo o mockup (com servidor/porta/usuário) ou se cabe no mesmo padrão
- Botão "Nova Conexão" ainda vai direto para o WireGuard (único protocolo disponível); quando outros
  protocolos existirem, precisa virar seletor (tela 17 do mockup)

- `ui/onboarding/SplashScreen.kt` — marca + tagline, avança sozinha
- `ui/onboarding/ChoiceScreen.kt` — escolha "já tenho domínio" / "não tenho domínio"
- `ui/onboarding/DomainInputScreen.kt` — input do domínio do provedor
- `ui/onboarding/ProgressStepsScreen.kt` — componente reutilizado nas telas "Conectando" e "Criando conta"
  (checklist de etapas com animação); hoje simula os passos com delay, ainda **não chama a API real**
- `ui/onboarding/AccountCreatedScreen.kt` — sucesso com contador regressivo do teste grátis (2h fixas por ora)
- Fluxo "não tenho domínio" cai direto no modo manual (Home com WireGuard já disponível)
- Pendente: plugar `PanelWebhookClient` de verdade nas telas de progresso (hoje é só simulação visual)

- Integração via lib oficial `com.wireguard.android:tunnel` (backend Go)
- `protocols/wireguard/WireGuardManager.kt` — envolve o `GoBackend`, expõe estado via `StateFlow`
- `protocols/wireguard/WireGuardDriver.kt` — adapta ao contrato `ProtocolDriver` genérico do núcleo
- `ui/wireguard/WireGuardScreen.kt` — tela própria: import de config (colar texto ou selecionar `.conf`),
  lista de túneis, toggle conectar/desconectar, estatísticas de tráfego em tempo real
- Fluxo de permissão de VPN do Android integrado (`VpnService.prepare`)
- Ainda não integrado ao painel — import é manual (colar/selecionar arquivo) até a autoconfig via
  webhook (seção 3) ser implementada

## 14. Status: Modo manual/profissional (telas 16-19)

- `ui/manual/NoDomainScreen.kt` — introdução ao modo manual
- `ui/manual/ProtocolSelectScreen.kt` — lista de protocolos (WireGuard marcado como implementado, os
  demais como "driver em desenvolvimento")
- `ui/manual/ManualConfigScreen.kt` — formulário genérico (nome/servidor/porta/usuário/senha), reutilizável
  para qualquer protocolo baseado nesses campos
- `ui/manual/ConnectionTestScreen.kt` — tela de teste de conexão

**Decisão importante de honestidade:** como só o WireGuard tem driver real, a tela de teste de conexão
(19) NÃO inventa latência/download/upload falsos para protocolos sem suporte — ela simula o progresso
visual (%) e depois deixa claro que aquele protocolo ainda não conecta de verdade, e que a configuração
foi salva localmente (em memória, não persiste entre sessões ainda) para quando o driver existir. Isso
evita que o app pareça estar conectando de verdade quando não está.

- Selecionar WireGuard no seletor de protocolo pula a tela de config genérica e vai direto para o
  `WireGuardScreen` (que já tem fluxo próprio de import de `.conf` e é realmente funcional)
- Conexões manuais configuradas aparecem na aba "Conexões" do Shell como "Configurado (pendente)"
- Pendente: persistência real (hoje as configs manuais somem ao fechar o app) e drivers de fato para
  SSH, V2Ray, Shadowsocks, VLESS, Trojan, SOCKS5

## 15. Status: Telas 20-25 (Configurações, Logs, Estatísticas, Dispositivos, Suporte, Menu Lateral)

- `util/AppLog.kt` — log em memória compartilhado pelo app (base real, não fake) para a tela de Logs
- `protocols/wireguard/WireGuardManager.kt` — atualizado para gravar eventos reais no AppLog (import,
  conectar, desconectar, erro)
- `ui/more/LogsScreen.kt` — mostra os eventos reais do AppLog (lista vazia se nada aconteceu ainda)
- `ui/more/StatisticsScreen.kt` — tráfego real da sessão atual (soma dos túneis WireGuard). **Não** mostra
  o gráfico histórico "hoje/7 dias/30 dias" do mockup original — isso precisa de armazenamento persistente
  que ainda não existe
- `ui/more/DevicesScreen.kt` — mostra só o dispositivo atual (nome do aparelho via `Build.MODEL`). **Não**
  lista múltiplos dispositivos como no mockup — isso depende do provisionamento multi-dispositivo do
  AutomBot Core, que ainda não está plugado
- `ui/more/SupportScreen.kt` — itens de navegação (FAQ, Tutoriais, Contato, Reportar Problema, Sobre);
  sem backend de suporte ainda, cada item é só estrutural
- `ui/more/SettingsScreen.kt` — toggles de tema/notificações/iniciar com sistema (só estado local, não
  persiste ainda); "Limpar Cache" é uma ação real (limpa o AppLog); "Sair" reseta a sessão (volta pra
  tela de escolha inicial, limpa teste grátis e conexões manuais)
- `ui/dashboard/SideMenuContent.kt` — menu lateral (drawer), acessível pelo ícone de menu no topo do Shell
- `ui/dashboard/MoreScreen.kt` — atualizado: itens agora navegam de verdade para as telas acima, em vez
  de mostrar "Em breve"

**Decisão de honestidade repetida aqui:** Estatísticas e Dispositivos foram deliberadamente simplificadas
em vez de replicar os dados de exemplo do mockup — mostrar histórico de tráfego ou múltiplos dispositivos
fake criaria uma sensação de funcionalidade que o app ainda não tem.

Com isso fecha a implementação de UI das 25 telas do mockup. Pendências que continuam abertas: persistência
(configs manuais, preferências, device ID, logs), drivers reais de SSH/V2Ray/Shadowsocks/VLESS/Trojan/SOCKS5,
integração real com o AutomBot Core (hoje as telas de onboarding/teste simulam), e as telas de planos/pagamento
PIX (fora do escopo combinado com o usuário por enquanto).

## 16. Ajuste: "Planos" só aparece no modo gerenciado

Correção pedida pelo usuário: a aba/menu "Planos" só deve aparecer para quem entrou pelo fluxo de domínio
(modo gerenciado) — usuários em modo manual não têm painel/API de pagamento por trás, então não faz
sentido mostrar planos pra eles. Planos e renovação são dados e API que só existem quando vêm do painel
(AutomBot Core), nunca gerados dentro do app.

- `AppRoot` agora guarda `isManagedMode: Boolean` (true só quando o fluxo completa via domínio/criação de
  conta; false por padrão e no modo manual)
- `BottomNavBar` e `SideMenuContent` (menu lateral) recebem `showPlan` e escondem a aba/item "Planos"
  quando `isManagedMode == false`
- Salvaguarda: se o usuário estiver na aba Planos e o modo mudar para manual (ex: logout/reset), a tab
  volta sozinha pra Dashboard

## 17. Correção: import de config aceita o JSON do painel (AutomBot Core)

Problema relatado: o painel do usuário devolve a config embrulhada em JSON —
`{"cliente": "...", "protocolo": "wireguard", "config": "[Interface]\n..."}` — mas a tela de import só
sabia interpretar um `.conf` puro (texto cru do WireGuard), então colar o JSON direto não funcionava.

- `panel/PanelConfigParser.kt` (novo) — parser genérico do envelope JSON do painel. Genérico de propósito:
  o mesmo formato deve valer pra outros protocolos (SSH, V2Ray, etc.) quando os drivers existirem, não é
  específico do WireGuard.
- `protocols/wireguard/WireGuardManager.importConfig()` — agora tenta interpretar a entrada como o JSON do
  painel primeiro; se não for JSON, trata como `.conf` cru (compatibilidade mantida). Se o nome do túnel
  não for informado, usa o campo `"cliente"` do JSON.
- `ui/wireguard/WireGuardScreen.kt` — campo de nome agora é opcional (o manager decide o nome final), texto
  de ajuda deixa claro que aceita colar o JSON do painel direto, sem precisar gerar arquivo `.conf`.

Não foi necessário mudar nada no painel — colar a resposta JSON da API direto no campo de texto já funciona.
QR code (scanner de câmera) ainda não foi implementado — ver seção 18 se decidirmos priorizar isso depois.

## 18. Correção: tráfego sempre mostrava "0 B"

Confirmado pelo usuário: a conexão WireGuard funcionou (conectou de verdade, navegação normal), mas
Download/Upload ficavam sempre em 0 B.

Causa: `refreshStatistics()` só era chamado uma vez, no exato instante em que o túnel ficava "Conectado"
(quando ainda não tinha passado nenhum byte). Depois disso nada disparava uma nova leitura.

Correção em `WireGuardManager.kt`: o manager agora tem um loop próprio (`managerScope`, roda a cada 2s)
que atualiza as estatísticas de qualquer túnel conectado, independente de qual tela está aberta — então
tanto a tela do WireGuard quanto o Dashboard (que soma o tráfego de todos os túneis) ficam corretos.

## 19. Correção: conexão instável (flapping) + falta de feedback visual

Usuário reportou, com prints dos Logs: cada "conectado" aparecia **duas vezes** no mesmo segundo, seguido
de desconexões e reconexões repetidas, terminando em "Erro" sem mensagem visível.

**Causa raiz:** `WireGuardManager.setState()` aplicava o novo estado da conexão duas vezes por toque —
uma vez através do callback `onStateChange` que o próprio `backend.setState(...)` já dispara internamente,
e outra vez manualmente usando o valor de retorno da chamada. Isso causava updates de estado duplicados
(daí os dois "conectado" no mesmo segundo) e instabilidade real na conexão.

**Correção 1** (`WireGuardManager.kt`): removida a chamada duplicada — agora só o callback interno atualiza
o estado. Adicionada uma segunda camada de proteção em `markStatus()`: se o novo status for idêntico ao
atual, não faz nada (nem loga, nem atualiza), então mesmo que algo dispare o callback mais de uma vez no
futuro, não duplica.

**Correção 2** (`WireGuardScreen.kt`): a falta de qualquer feedback visual de "conectando" fazia o usuário
clicar de novo achando que não tinha funcionado — o que disparava toggles sobrepostos e piorava a
instabilidade. Agora o `Switch` fica **desabilitado** enquanto o status é `CONNECTING`/`DISCONNECTING`, e
aparece um spinner ao lado do nome do túnel nesse período.

**Correção 3**: mensagem de erro agora sempre tem texto (fallback pro nome da exceção quando a exceção não
tem mensagem), então "Erro" nunca aparece em branco — o texto já era exibido embaixo do card, só não tinha
conteúdo pra mostrar quando `e.message` vinha nulo.

## 20. Investigando: tráfego continua em 0 B

Usuário confirmou que a instabilidade (seção 19) foi resolvida — log limpo agora, só um evento por ação.
Mas o tráfego (Recebido/Enviado, tanto na tela do WireGuard quanto no Dashboard) continua sempre em 0 B.

Causa encontrada: `WireGuardManager.refreshStatistics()` engolia qualquer erro em silêncio
(`runCatching { ... }` sem `onFailure`) — então se a leitura de estatísticas estivesse falhando por
qualquer motivo (API da lib, threading, etc.), não sobrava nenhuma pista visível.

**Ainda não é uma correção definitiva** — é instrumentação. Agora qualquer falha ao ler as estatísticas
aparece nos Logs como "Falha ao ler estatísticas de ...". Próximo passo depende do que aparecer no log do
próximo teste:
- Se aparecer um erro → o problema é na chamada à API do backend (ex: metodo errado, tunel nao
  reconhecido) e dá pra corrigir direto
- Se **não** aparecer erro nenhum e o tráfego continuar 0 → o problema é que `stats.totalRx()`/`totalTx()`
  estão retornando 0 de verdade (ex: precisa ler por peer em vez de total, ou os testes de navegação não
  passaram tráfego suficiente pela mesma sessão de handle que o app está consultando)

Também observado: na primeira tentativa de conexão apareceu `BackendException`, a segunda tentativa
conectou normalmente. Ainda não confirmado se isso está relacionado ao bug de duplicidade corrigido na
seção 19 (plausível) ou se é uma questão separada — acompanhar nos próximos testes.

## 21. SSH — tela de configuração avançada (baseada em referência de terceiro)

Usuário mandou print de referência do editor de perfil SSH de um app de terceiro (estilo HTTP Injector):
campos de servidor/porta/usuário/senha/chave pública, toggles de compressão e TCP delay, "Tipo de proxy"
(dropdown) + toggles separados "Proxy Http"/"Proxy SSL/TLS", encaminhamento de DNS (pdnsd) com DNS
customizado, e encaminhamento de UDP (badvpn/udpgw) com gateway configurável.

**Melhoria proposital em relação à referência:** lá existia um dropdown "Tipo de proxy" E dois toggles
separados de proxy que podiam ficar ligados ao mesmo tempo de forma ambígua (não fica claro o que
prevalece). Unifiquei tudo em um único seletor de **modo de transporte** (`TransportMode`): Direto / Proxy /
Payload + Proxy / SSL-TLS / WebSocket — só um ativo por vez, sem ambiguidade, e cobre exatamente as
combinações que o usuário descreveu (SSH=proxy, SSH=payload=proxy, SSH=SSL-TLS, SSH+WebSocket).

- `protocols/ssh/SshModels.kt` — `SshConnectionConfig` (modelo completo) + enums `SshAuthMethod`,
  `TransportMode`, `ProxyType`
- `ui/manual/SshConfigScreen.kt` — tela de configuração dedicada, substitui o formulário genérico
  (`ManualConfigScreen`) quando o protocolo selecionado é SSH. Campos condicionais por modo de transporte
  (proxy/payload só aparecem quando fazem sentido), seção de configurações avançadas colapsável
  (compressão, TCP delay, DNS customizado, UDP forwarding/badvpn)
- `MainActivity.kt` — roteamento: selecionar "SSH" no seletor de protocolo agora vai para `SshConfigScreen`
  em vez do formulário genérico; ao salvar, cai no mesmo `ConnectionTestScreen` honesto de sempre (ainda
  sem driver real)

**Importante — isso é só a camada de configuração, não uma conexão SSH real ainda.** Diferente do
WireGuard (que tem uma biblioteca Android oficial pronta), implementar SSH de verdade exige montar isso do
zero: uma lib de cliente SSH (ex: sshj ou JSch), um servidor proxy local (SOCKS5/HTTP) rodando dentro do
app, roteamento de todo o tráfego do dispositivo até esse proxy (estilo tun2socks) via `VpnService`, além
da lógica de payload customizado e encapsulamento em TLS/WebSocket. É bem mais trabalho que o WireGuard.
Ainda não comecei essa parte — combinar com o usuário se seguimos para o driver real de SSH agora ou para
a configuração de outros protocolos primeiro.

## 22. SSH — camada funcional real (primeira parte)

Implementação real da conexão SSH (não é mais só a tela de configuração da seção 21).

- `protocols/ssh/Socks5Server.kt` — servidor SOCKS5 próprio (RFC 1928), sem dependência externa,
  rodando local (127.0.0.1). Só suporta comando CONNECT (suficiente pra navegação normal)
- `protocols/ssh/SshTunnelManager.kt` — núcleo real: conecta via `sshj` (lib SSH pura Java/Kotlin),
  autentica (senha ou chave privada), e liga o SOCKS5 local a canais `direct-tcpip` do SSH — ou seja,
  cada conexão que passa pelo SOCKS5 realmente atravessa o túnel SSH
- `ui/ssh/SshScreen.kt` — tela real: conectar/desconectar de verdade, mostra o endereço do proxy SOCKS5
  local quando conectado (ex: `127.0.0.1:53214`)
- Dependência adicionada: `com.hierynomus:sshj:0.38.0`

**Modos de transporte implementados de verdade:**
- Direto ✅
- Proxy (SOCKS5/HTTP) ✅ — usa o suporte a `Proxy` nativo do `java.net.Socket`
- Payload + Proxy ✅ — manda o payload cru (com `[host]`/`[port]`/`[crlf]` substituídos) antes do
  handshake SSH
- SSL/TLS ✅ — envolve o socket em `SSLSocket` com SNI de fachada configurável
- WebSocket ❌ — ainda não implementado; a tela mostra erro claro em vez de fingir. Precisa de uma lib de
  WebSocket client + uma ponte Socket-sobre-WebSocket, fica pra depois

**Correção de segurança/UX em relação à referência:** o campo que na referência era "Chave pública" virou
"Chave privada (PEM)" — é a chave privada que autentica no cliente; a pública fica no servidor. O campo
antigo não funcionaria pra autenticar de verdade.

**Avisos de segurança / pendências conhecidas:**
1. Verificação de host key está com `PromiscuousVerifier` (aceita qualquer chave do servidor, sem checar)
   — inseguro contra man-in-the-middle. Precisa de verificação real (known_hosts ou pin da chave) antes de
   uso em produção.
2. "Desativar TCP delay" (toggle da tela de config) ainda não está ligado a nada — ficou como TODO em vez
   de inventar uma chamada de API que eu não tinha certeza que existia.
3. A abertura do canal `direct-tcpip` (`SshTunnelManager.openDirectChannel`, usando `DirectTCPIPChannel` +
   `connection.attach`) é o trecho de MAIOR incerteza de API neste arquivo — mais que qualquer coisa no
   WireGuard. Se o Android Studio acusar erro ali, mandar a mensagem exata pra eu corrigir na hora.

**O que NÃO está incluído ainda (importante):** conectar aqui sobe um proxy SOCKS5 local real, mas **não
substitui a VPN do sistema** — outros apps do celular não passam por ele automaticamente. Pra isso
funcionar tipo o WireGuard (todo o tráfego do aparelho passando pelo túnel), falta um componente de
roteamento de pacotes (`tun2socks`), que normalmente é um binário nativo (compilado de Go, com toolchain
NDK) que este ambiente não consegue baixar/compilar. Isso fica como próximo passo a combinar: (a) buscar um
binário tun2socks pronto pra integrar via JNI, ou (b) escrever uma versão simplificada em Kotlin (mais
arriscado, exigiria bastante iteração de testes).

## 23. Correções pós-teste do usuário: thread de rede, persistência e edição

Usuário testou o SSH real (seção 22) e reportou 3 problemas, além de ter aplicado ele mesmo uma correção
no Android Studio (trocou `openDirectChannel` de `DirectTCPIPChannel`+`connection.attach` pra
`client.newDirectConnection(destHost, destPort)` — mais simples e correto; adotado como base daqui pra
frente).

**Correção 1 — `NetworkOnMainThreadException`** (causa raiz de a conexão SSH nunca completar): o corpo de
`SshTunnelManager.connect()`/`disconnect()` fazia I/O de rede bloqueante (sockets, handshake SSH) herdando
o dispatcher de quem chamava (`SshScreen` usa `rememberCoroutineScope()`, que roda na thread principal por
padrão). Envolvido em `withContext(Dispatchers.IO)`.

**Correção 2 — persistência**: perfis SSH e túneis WireGuard agora são salvos em `SharedPreferences`
(JSON), carregados no `init` de cada manager. Sobrevivem a fechar/reabrir o app.
- `protocols/ssh/SshModels.kt` — funções `toJson()`/`sshConnectionConfigFromJson()`
- `protocols/ssh/SshTunnelManager.kt` — agora recebe `Context`, persiste em `persistProfiles()`/carrega em
  `loadPersistedProfiles()`
- `protocols/wireguard/WireGuardManager.kt` — `ManagedTunnel` ganhou campo `configText` (precisa do texto
  cru pra poder re-parsear ao carregar); persiste em `persistTunnels()`/carrega em `loadPersistedTunnels()`
- **Pendente**: `manualConnections` (perfis de protocolos ainda sem driver, tipo V2Ray/Shadowsocks) e o
  estado de trial/onboarding continuam só em memória — ainda não persistem. Fica pro próximo ajuste geral
  de persistência se fizer sentido.

**Correção 3 — editar conexão existente (SSH)**: `SshConfigScreen` agora aceita um `initialConfig`
opcional e pré-preenche todos os campos; título muda pra "Editar SSH". `SshScreen` ganhou um ícone de lápis
em cada perfil, que abre a edição. Como `saveProfile()` já substitui por nome, salvar edita no lugar.
- Editar WireGuard/outros protocolos ainda não tem essa opção — só SSH por enquanto, já que foi o pedido
  direto do usuário; posso replicar o mesmo padrão pros outros quando fizer sentido.

**Instanciação**: `SshTunnelManager` deixou de ter construtor vazio — agora precisa de `Context`
(`SshTunnelManager(applicationContext)`), igual ao `WireGuardManager`. Ajustado em `MainActivity.kt`.

## 24. Correção: "no such algorithm: X25519 for provider BC"

Usuário testou os 3 modos (Direto, Proxy, Payload+Proxy) e mandou os logs. Dois problemas distintos:

**Bug real (corrigido)** — modo Direto chegou a negociar com o servidor SSH (prova que a thread/conexão
TCP já estavam ok, ver seção 23), mas falhou com `no such algorithm: X25519 for provider BC`. Causa: o
Android vem com um provedor de segurança "BC" (BouncyCastle) próprio e **capado** — sem vários algoritmos
modernos, incluindo X25519, que o `sshj` precisa pra negociar com servidores SSH atuais. Nunca tínhamos
registrado o BouncyCastle de verdade no sistema.

- `app/build.gradle.kts` — adicionada dependência explícita `org.bouncycastle:bcprov-jdk18on:1.78.1`
- `protocols/ssh/SshTunnelManager.kt` — `companion object` com bloco `init` que remove o "BC" capado do
  Android e registra o BouncyCastle completo no lugar (`Security.removeProvider("BC")` +
  `Security.insertProviderAt(BouncyCastleProvider(), 1)`), executado uma vez quando a classe é carregada

**Não é bug do app** — modos Proxy e Payload+Proxy falharam com `ECONNREFUSED` ao tentar conectar em
`147.15.57.44:8081` (o proxy configurado). O log mostra claramente que a tentativa de conexão foi recusada
pelo próprio host/porta — ou seja, não há nenhum serviço de proxy escutando ali. Isso é do lado do servidor
(AutomBotCore) ou da configuração do perfil (porta errada), não do código do app. Fica como item pro
usuário conferir no painel/servidor.

## 25. Correções: onboarding não persistia, tráfego SSH, logs mais detalhados

Usuário reportou vários pontos após testar mais a fundo. Resumo do que foi corrigido:

**"App sempre volta pra tela inicial"** — confirmado: o app nunca lembrava que o usuário já tinha
concluído o onboarding. Agora persiste em `SharedPreferences` (`autombot_app`, chaves `onboarded` e
`managed_mode`). Splash decide pra onde ir com base nisso. Além disso, o fluxo "Não tenho domínio" agora
leva **direto pro Shell** (antes obrigava passar por configurar um protocolo pra sair da tela de introdução
— esse era o "obrigando a criar uma nova configuração" que o usuário reportou). Logout ("Sair") limpa essa
flag, forçando onboarding de novo no próximo acesso — comportamento esperado.

**Tráfego do SSH sempre em 0 B** — diferente do WireGuard, o SSH nunca teve contagem de bytes implementada
(só mostrava o endereço do proxy). Agora:
- `protocols/ssh/Socks5Server.kt` — conta bytes reais de cada direção (`totalRx`/`totalTx`, `AtomicLong`)
  durante o relay
- `protocols/ssh/SshTunnelManager.kt` — loop periódico (a cada 2s, mesmo padrão do WireGuardManager) lê
  esses contadores e atualiza `ManagedSshConnection`
- `ui/ssh/SshScreen.kt` — mostra chips de Recebido/Enviado quando conectado
- Dashboard agora soma tráfego de WireGuard **e** SSH no total exibido

**Tráfego do WireGuard continua em 0 B** — ainda não resolvido, e preciso do log específico ("Falha ao ler
estatísticas...") pra continuar investigando; a instrumentação da seção 20 já deveria estar capturando o
motivo real.

**Log mais detalhado na conexão SSH** — `SshTunnelManager.connect()` agora loga cada etapa
(`[1/4]` conectando, `[2/4]` handshake concluído/autenticando, `[3/4]` autenticado/subindo proxy, `[4/4]`
conectado). Se der erro, a última etapa registrada no log mostra exatamente onde travou.

**Sobre o teste do usuário com outro app usando a mesma config de proxy e funcionando** — ainda não
identifiquei uma causa de código pra isso; `ECONNREFUSED` é um erro de nível TCP (a tentativa de conexão
foi recusada antes de qualquer dado ser trocado), o que normalmente independe da lógica da nossa aplicação.
Pendente: comparar host/porta/tipo de proxy exatos usados no app que funcionou, pra achar alguma diferença
literal de configuração.

**Sobre rodar múltiplos protocolos ao mesmo tempo** — explicado ao usuário: hoje "funciona" só porque o SSH
ainda não é uma VPN de verdade (não usa `VpnService`), por isso não aparece o ícone de chave na barra e não
conflita com o WireGuard. O painel mostrar os dois "online" é status de servidor (cada lado autenticou),
não status de VPN do aparelho. Quando o SSH também virar VPN de sistema, só um dos dois vai poder ser a VPN
ativa por vez — restrição do próprio Android.

## 26. Correção importante: VPN do WireGuard não desligava de verdade no sistema

Usuário reportou: desconecta o WireGuard no app, mas a VPN continua ativa no Android (ícone de chave
permanece, sistema continua roteando pela VPN) mesmo com o app mostrando "desconectado".

**Causa raiz encontrada — e provavelmente também explica o tráfego zerado do WireGuard (seção 20/25):**
`WireGuardManager` criava um objeto `SimpleTunnel` **novo a cada chamada** (`toggle()`/`setState()` pra
conectar, outro pra desconectar, outro em `refreshStatistics()` pra ler tráfego). O `GoBackend` parece
reconhecer qual túnel está rodando pela **identidade do objeto**, não só pelo nome — então desconectar com
um objeto diferente do que foi usado pra conectar não desligava a VPN de verdade no sistema, e ler
estatísticas com ainda outro objeto diferente também não encontrava o túnel certo (por isso sempre 0,
sem erro nenhum — o backend simplesmente não reconhecia aquele objeto como um túnel ativo).

**Correção**: `WireGuardManager.getOrCreateHandle(name)` agora guarda um `SimpleTunnel` por nome de túnel
num mapa (`tunnelHandles`) e sempre reaproveita o mesmo objeto — pra conectar, desconectar e ler
estatísticas do mesmo túnel. Ainda precisa ser confirmado no teste real, mas essa é a explicação mais
provável para os dois problemas de uma vez.

## 27. Proxy HTTP com ECONNREFUSED — ainda sem causa confirmada

Usuário confirmou: mesmo servidor/porta, tipo HTTP, funcionou num app de terceiro mas não no nosso.
Revisão do código: a implementação usa `java.net.Socket(Proxy)` com `Proxy.Type.HTTP`, que é o mecanismo
padrão do Java/Android pra túnel HTTP CONNECT em sockets crus — não achei bug na nossa lógica. O erro
(`ECONNREFUSED`) acontece a nível de TCP, antes de qualquer byte de protocolo ser trocado, o que descarta
diferença de como HTTP vs SOCKS é negociado.

**Hipótese mais provável**: o bug de duplicidade de conexão (seção 19, já corrigido) pode ter gerado várias
tentativas de conexão em sequência rápida, e é comum VPS terem proteção tipo fail2ban que bloqueia IPs após
várias falhas — o bloqueio pode já ter expirado. Pendente: usuário testar de novo com as correções atuais.
Se persistir, próximo passo é comparar se o app de referência estava testando pela mesma rede/IP.

## 28. SSH redesenhado: camadas independentes e combináveis (a pedido do usuário)

Usuário pediu explicitamente pra desfazer a decisão de design da seção 21 (seletor único de "modo de
transporte" com combinações fixas). Queria o contrário: começar só com servidor+porta, e cada camada
(Proxy, Payload, SSL/TLS, WebSocket) ser um **toggle independente**, combinável livremente — inclusive
combinações não previstas por mim, tipo Proxy + Payload + SSL/TLS todos ativos ao mesmo tempo.

- `protocols/ssh/SshModels.kt` — `TransportMode` (enum) removido. `SshConnectionConfig` agora tem
  `useProxy`, `usePayload`, `useSslTls`, `useWebSocket` como booleanos independentes, cada um com seus
  próprios campos. Adicionado `connectionTimeoutSeconds` (pedido do usuário). Nova extensão
  `describeLayers()` gera um resumo tipo "Proxy SOCKS5 + Payload + SSL/TLS" pra mostrar na lista/logs.
- `ui/manual/SshConfigScreen.kt` — reescrita: seção "Básico" (nome/servidor/porta/usuário/auth/timeout)
  sempre visível, depois cada camada é um card expansível (`ExpandableLayer`) com switch — só mostra os
  campos daquela camada quando ligada.
- `protocols/ssh/SshTunnelManager.kt` — `proxySocketFactory`/`tlsSocketFactory` (uma função por combinação
  fixa) viraram uma única `composedSocketFactory()` que aplica as camadas na ordem: TCP (direto ou via
  proxy) → payload cru (se ligado) → TLS com SNI (se ligado) → daí sim o sshj continua o handshake SSH.
- `ui/ssh/SshScreen.kt` — mostra `describeLayers()` no lugar do antigo rótulo fixo de modo.

**Nota de compatibilidade**: perfis SSH salvos antes dessa mudança (formato antigo com `transportMode`)
não são migrados automaticamente — ao carregar, os campos novos (`useProxy` etc.) vêm todos `false`
(equivalente a "Direto"). Como o app ainda está em fase de testes, não implementei migração; se isso virar
problema real, dá pra adicionar depois.

**Pendência combinada com o usuário**: próximo passo é começar a implementação do SSH como VPN de sistema
de verdade (VpnService + tun2socks) — ainda não iniciado, fica pro próximo ciclo de trabalho.

## 29. Correção crítica no SSH + log por conexão + excluir conexão

Usuário confirmou: **WireGuard está 100% funcional** (conecta, desconecta de verdade, tráfego contando
certo no Dashboard e na tela da conexão). SSH continuava sem conectar, com erro "precisa de host/porta"
mesmo com servidor e porta preenchidos.

**Bug crítico do SSH — corrigido**: a mensagem "precisa de host/porta" era um texto que eu mesmo tinha
escrito no código, num `UnsupportedOperationException` dentro do overload sem argumentos de
`SocketFactory.createSocket()` — que eu assumia que o `sshj` nunca chamaria. O erro real provou o
contrário: o sshj chama esse overload sem argumentos primeiro, e só depois chama `.connect(endereco,
timeout)` no socket retornado (diferente do que eu tinha assumido antes). Correção: `ComposedSocket`, um
`Socket` "decorador" que faz toda a composição das camadas (proxy → payload → TLS) dentro do próprio
`connect()`, funcionando independente de qual dos dois jeitos o sshj usa.

**Novo: botão de log por conexão** — antes só dava pra ver logs indo em Mais → Logs (misturado com tudo).
Agora cada card de túnel WireGuard e cada card de conexão SSH tem um link "Ver log" que abre a tela de Logs
já filtrada só pros eventos daquela conexão específica (filtro por nome entre aspas na mensagem).

**Novo: excluir conexão** — cada card (WireGuard e SSH) agora tem um link "Excluir", com diálogo de
confirmação antes de remover de verdade (usa `removeTunnel()`/`removeProfile()`, que já existiam e já
persistem a remoção).

**Limitação conhecida**: o botão "voltar" da tela de log filtrada sempre volta pro Dashboard (Shell), não
pra tela de onde o usuário veio (WireGuard ou SSH) — simplificação por ora, dá pra ajustar depois se
incomodar no uso.

## 30. Rodada grande de correções: ordem TLS/Payload, WebSocket real, navegação e status

Usuário testou bastante e mandou vários pontos de uma vez. Resumo:

**1. SSH "Connection reset" (Payload + SSL/TLS via CloudFront) — bug real corrigido.** A ordem em que as
camadas eram aplicadas estava errada: payload cru era enviado ANTES do handshake TLS. Servidores/CDNs que
recebem TLS numa porta (como CloudFront na 443) esperam um ClientHello TLS como os PRIMEIROS bytes da
conexão — mandar texto cru antes disso faz o servidor derrubar a conexão na hora. Corrigido em
`ComposedSocket.connect()` (`SshTunnelManager.kt`): agora é TCP → TLS (se ligado) → payload (se ligado,
agora dentro do túnel TLS, criptografado) → SSH. Usuário confirmou que a mesma config funciona noutro app,
o que já sinalizava que o problema era nosso, não do servidor — e essa é a explicação concreta.

**2. WebSocket implementado de verdade.** Novo arquivo `protocols/ssh/WebSocketBridgeSocket.kt` — usa o
OkHttp (já era dependência do projeto) pra abrir uma conexão WebSocket real (`ws://` ou `wss://` se
SSL/TLS também estiver ligado) e faz a ponte entre os frames binários WS e o `InputStream`/`OutputStream`
que o sshj espera de um Socket comum, via `PipedInputStream`/`PipedOutputStream`. Esse é o trecho de MAIOR
incerteza de todo o módulo SSH — não consegui compilar/testar aqui; se travar ou o Android Studio acusar
erro, preciso do relato exato.

**3. Status errado em "Minhas Conexões".** A linha resumida de SSH só olhava o PRIMEIRO perfil da lista —
se o usuário tivesse 2 perfis e o segundo estivesse conectado, a lista ainda mostrava o status do primeiro
(ex: "Erro"). Corrigido: agora prioriza conectado > erro > desconectado > não configurado, olhando todos
os perfis.

**4. Botão "voltar" da tela de log filtrada.** Antes sempre ia pro Dashboard. Agora `Screen.Logs` guarda a
tela de origem (`origin`) e volta pra ela — se abriu o log de dentro do WireGuard, volta pro WireGuard; se
abriu de dentro do SSH, volta pro SSH.

**5. WireGuard: log de erro melhorado.** O erro intermitente da primeira tentativa (`BackendException` sem
detalhe nenhum) ainda não tem causa identificada — só instrumentado melhor agora (tenta extrair
`.reason` da exceção e a causa, não só o nome da classe), pra próxima falha aparecer mais informação.

**6. Esclarecimento (não é bug): tráfego do SSH em 0 B.** Confirmado que não tem relação com VpnService —
é porque nada está de fato usando o proxy SOCKS5 local ainda (nenhum app do aparelho está configurado pra
passar por ele). Vai continuar em 0 B até: (a) o usuário testar manualmente apontando outro app pro
SOCKS5 local, ou (b) a integração VpnService + tun2socks existir (item já combinado como próximo passo
grande).

## 31. Correção: timeout de conexão SSH por IPv6 quebrado + botão de copiar log

**Novo erro reportado**: `failed to connect to d9jxp37bwpf6q.cloudfront.net/2600:9000:... (port 443) ...
after 10000ms` — reparar que o endereço é IPv6. Causa: ao resolver o hostname, conectávamos direto no
primeiro endereço que o DNS devolvesse, sem preferência — em redes onde o IPv6 está quebrado ou incompleto
(comum em rede móvel), isso gera um timeout completo tentando um IPv6 que nunca responde, mesmo o IPv4
funcionando normalmente. Isso também explica por que a mesma config funciona "no outro app" — provavelmente
esse outro app já tenta IPv4 primeiro.

**Correção** (`SshTunnelManager.kt`, `ComposedSocket.connectPreferringIPv4()`): resolve todos os endereços
do host, ordena IPv4 antes de IPv6, e tenta conectar em cada um até um funcionar. Só se aplica na conexão
direta (sem proxy) — com proxy, quem resolve o host de destino é o próprio proxy.

**Novo: botão de copiar log** (`LogsScreen.kt`) — ícone ao lado do título copia o relatório completo (ou
filtrado, se aberto de uma conexão específica) como texto puro pra área de transferência, com timestamp e
nível de cada evento. Usuário pode colar e enviar direto no chat em vez de print de tela.

**Reforçando (não é bug)**: a chave de VPN do sistema não acende ao conectar via SSH porque o SSH ainda não
usa `VpnService` — é exatamente a peça que falta e que está combinada como próximo passo grande do projeto.

## 32. Iniciando a VPN de sistema (VpnService) — Etapa 1 concluída, Etapa 2 precisa de decisão

Usuário confirmou início da maior etapa do projeto: fazer o SSH (e futuramente outros protocolos) virarem
VPN de sistema de verdade, com a chave ativa na barra do Android.

**Isso se divide em duas etapas bem diferentes em dificuldade:**

### Etapa 1 — Estabelecer a interface TUN (✅ feito agora)

`core/AutomBotVpnService.kt` — VpnService real:
- Pede a permissão do sistema (já integrado via `MainActivity`)
- Estabelece a interface TUN (`Builder.establish()`)
- Sobe como foreground service com notificação persistente (exigência do Android pra manter
  rodando em background)
- Isso sozinho já faz a **chave de VPN acender na barra** quando ativado

`AndroidManifest.xml` — permissão `POST_NOTIFICATIONS` (Android 13+) e `foregroundServiceType="specialUse"`
adicionados pro nosso serviço.

### ⚠️ Etapa 1 ainda NÃO está ligada a nenhum botão do app — motivo de segurança

A interface é configurada com `addRoute("0.0.0.0", 0)` — ou seja, pede pro Android mandar **todo o
tráfego de internet do aparelho** pra essa interface. Sem a Etapa 2 (abaixo), **ninguém lê o outro lado
do arquivo (fd) do TUN** — então se isso for ativado de verdade agora, o aparelho perde acesso à internet
completamente até desativar a VPN manualmente. Por segurança, não conectei isso a nenhum "Conectar" da UI
ainda. Só existe pronto, esperando a Etapa 2.

### Etapa 2 — Motor de roteamento de pacotes (tipo "tun2socks") — PRECISA DE DECISÃO DO USUÁRIO

É a parte que de fato pega os pacotes IP que chegam no TUN e entrega pro proxy SOCKS5 local do SSH. Isso
exige implementar (ou reaproveitar) uma pilha TCP/IP mínima — bem mais complexo que tudo que fizemos até
aqui. Duas opções reais:

**(a) Motor próprio em Kotlin (dentro do app, sem dependência externa)**
- Vantagem: tudo dentro do projeto, sem binário externo
- Desvantagem: implementar um parser de IP/TCP + gerenciamento de conexões do zero é a parte de MAIOR risco
  técnico do projeto inteiro — é fácil ter bugs sutis de rede que eu não consigo validar sem compilar/testar
  de verdade. Vou precisar iterar bastante com testes reais do usuário pra chegar em algo estável.

**(b) Binário nativo pronto (tun2socks), integrado via JNI ou subprocesso**
- É o caminho que apps de produção (Shadowsocks Android, v2rayNG, etc.) usam de verdade
- Vantagem: motor já testado e maduro, bem mais confiável
- Desvantagem: precisa de um binário `.so` compilado pra cada arquitetura (arm64-v8a, armeabi-v7a, x86_64)
  — normalmente feito com toolchain Go + NDK, algo que **eu não consigo compilar aqui no sandbox** (sem
  acesso a rede/toolchain de compilação nativa). O usuário precisaria obter esses binários prontos (de um
  projeto open-source como `hev-socks5-tunnel` ou `go-tun2socks`) e colocar em
  `app/src/main/jniLibs/<abi>/`, e eu escrevo a parte Kotlin que liga tudo.

**Pendente**: decidir com o usuário qual caminho seguir antes de continuar a Etapa 2.

## 33. WireGuard: retry automático pro erro "UNABLE_TO_START_VPN"

Usuário mandou o relatório de log (usando o novo botão de copiar) confirmando: primeira tentativa de
conexão WireGuard falha com `UNABLE_TO_START_VPN`, segunda tentativa sempre funciona.

**Causa**: corrida clássica entre o Android terminar de processar a permissão de VPN concedida e o app
tentar estabelecer a interface logo em seguida — a primeira tentativa acontece rápido demais.

**Correção** (`WireGuardManager.setState()`): quando o erro for especificamente `UNABLE_TO_START_VPN` numa
tentativa de conectar (não desconectar), o app agora espera 600ms e tenta de novo automaticamente, uma
única vez, antes de mostrar erro pro usuário — não precisa mais tocar duas vezes manualmente.

**Ainda pendente investigar**: perfil SSH "ssh, Proxy, payload" com erro `Server closed connection during
identification exchange` — esse perfil está com Payload ligado mas Proxy desligado, mandando o payload cru
direto pro `core.infinitenet.net:8444`. Se essa porta for um `sshd` puro, o payload sem proxy nunca vai
funcionar (o servidor recebe lixo antes do handshake SSH e derruba a conexão) — não é bug do app, é
configuração. Perguntado ao usuário o que roda de fato nessa porta pra confirmar.

## 34. Etapa 2 da VPN de sistema: motor de roteamento de pacotes (tun2socks) — PRIMEIRA VERSÃO EXPERIMENTAL

Usuário pediu pra seguir com a implementação completa, mesmo sabendo que eu não consigo compilar um
binário nativo aqui. Decisão tomada: implementar um motor próprio em Kotlin (opção "a" da seção 32),
aceitando que é a parte de maior risco técnico do projeto inteiro — nenhuma linha deste motor foi
compilada nem testada em dispositivo real.

**Novos arquivos** (`core/tun2socks/`):
- `Checksums.kt` — checksum IP/TCP (RFC 1071)
- `Socks5Client.kt` — cliente SOCKS5 (lado cliente, o inverso do `Socks5Server` do SSH) — usado pelo motor
  pra abrir cada fluxo TCP através do proxy SOCKS5 local já existente
- `PacketBuilder.kt` — monta pacotes IPv4+TCP crus (sem opções) pra responder de volta pro app
- `TcpFlow.kt` — estado de uma conexão TCP individual: abre socket real via `Socks5Client`, faz o papel de
  "servidor" do ponto de vista do app (SYN-ACK, ACKs, dados, FIN/RST), com uma thread dedicada relayando
  dados do socket real de volta pro TUN
- `Tun2SocksEngine.kt` — motor principal: lê pacotes do fd do TUN num loop, identifica fluxos TCP por
  origem/destino, cria/atualiza/fecha `TcpFlow` conforme os pacotes chegam

**`core/AutomBotVpnService.kt`** — agora liga a Etapa 1 (TUN) com a Etapa 2 (motor): recebe a porta do
proxy SOCKS5 via `Intent` (`EXTRA_SOCKS_PORT`) e inicia o `Tun2SocksEngine` assim que a interface é
estabelecida.

**`ui/ssh/SshScreen.kt` / `MainActivity.kt`** — a VPN de sistema agora ativa/desativa **automaticamente**:
quando qualquer conexão SSH fica `CONNECTED` com proxy SOCKS5 disponível, o app pede a permissão de VPN (se
ainda não concedida) e inicia o `AutomBotVpnService` apontando pra aquela porta — é isso que deve fazer a
chave de VPN acender na barra do Android ao conectar via SSH. Ao desconectar, a VPN de sistema desliga
junto.

### Limitações conhecidas desta primeira versão (importante)

- **Só TCP.** UDP (necessário pra DNS resolver corretamente em muitos casos, jogos, chamadas de voz/vídeo)
  ainda não é tratado — pacotes UDP chegando no TUN são simplesmente ignorados nesta versão. Isso significa
  que MESMO com a VPN "ativa", uma boa parte do tráfego pode não funcionar ainda.
- **Só IPv4.** Pacotes IPv6 também são ignorados por enquanto.
- Sem retransmissão robusta, sem escalonamento de janela TCP, sem SACK — conexões simples devem funcionar,
  mas cenários de rede ruim/alta perda de pacote podem se comportar mal.
- **Não testado em dispositivo real.** Erros de baixo nível (checksum errado, sequência de bytes trocada,
  etc.) são esperados numa primeira tentativa — vou precisar do relato de teste real (idealmente com o
  relatório de log, que já tem o botão de copiar) pra corrigir.

### Rede de segurança pro usuário

Não importa o que aconteça (trava, para de responder, etc.), a VPN sempre pode ser desligada direto em
**Configurações do Android → Rede → VPN**, independente do app estar respondendo ou não.

### Pendências pra próxima etapa

- Implementar UDP (essencial pro DNS funcionar direito com a VPN ativa)
- Testar em dispositivo real e corrigir os bugs que aparecerem
- Considerar IPv6 se necessário

## 35. PLANEJADO (próxima atualização): Monitor de Consumo de Dados

Usuário propôs um novo módulo enquanto testa a build atual — registrado aqui pra não perder o escopo,
**ainda não implementado**.

**Objetivo**: mostrar ao usuário quanto de dados está sendo economizado pela compressão via Shadowsocks,
comparando tráfego bruto (antes da compressão) vs tráfego comprimido (o que efetivamente chega no celular).

**Dependência crítica de ordem**: só faz sentido depois do **Shadowsocks estar implementado** no app —
ainda não existe (só WireGuard e SSH até agora). Confirmado com o usuário que é específico do Shadowsocks
(único protocolo com compressão real), não genérico pra qualquer protocolo.

**Módulos previstos:**
1. Medidor de tráfego comprimido (downstream) — `android.net.TrafficStats`, lado do celular. Sem
   dependência externa, direto de fazer.
2. Medidor de tráfego bruto (upstream) — **depende de uma API nova no AutomBotCore (VPS)**, que ainda não
   existe nem foi planejada. Rascunho de contrato proposto (a refinar junto com o usuário quando chegar a
   hora):
   ```
   GET /api/traffic/raw?device_id=<uuid>&since=<timestamp>
   { "device_id": "...", "raw_bytes_total": N, "period_start": "...", "period_end": "..." }
   ```
   Isso é trabalho no projeto do AutomBotCore (VPS), fora do escopo deste app — precisa ser coordenado lá.
3. Sessão de baseline (consumo "normal" antes de conectar, manual ou por período)
4. Sessão pós-conexão — histórico local (Room/SQLite — dependência nova, ainda não adicionada ao projeto)
5. Tela de relatório — gráfico em tempo real (MPAndroidChart — dependência nova via JitPack, precisa
   adicionar repositório extra no `settings.gradle.kts`) + resumo comparativo + histórico
6. Foreground service dedicado, notificação persistente de consumo (dá pra reaproveitar o padrão já criado
   em `AutomBotVpnService.kt`)

**Ordem de entrada na fila**: Shadowsocks (driver + config) → API de tráfego bruto no VPS (projeto separado)
→ este módulo de monitor.

## 36. Correção crítica: loop de roteamento (internet travava com a VPN ativa) + DNS via UDP

Usuário testou: chave de VPN acendeu, SSH conectou normal, mas **nenhum site carregava** — internet
totalmente travada com a VPN "ligada". Log do SSH não mostrava erro nenhum (a conexão SSH em si estava OK).

**Causa raiz — bug crítico e clássico de apps de VPN**: com a rota `0.0.0.0/0` ativa, o Android intercepta
**todo** o tráfego do aparelho, incluindo o da própria conexão que o app usa pra falar com o servidor SSH
de verdade. Sem chamar `VpnService.protect()` nesse socket, a conexão real ficava presa dentro do próprio
túnel que deveria alimentar — um loop. Isso trava a internet inteira, não só o SSH, exatamente como
reportado.

**Correção**:
- `core/AutomBotVpnService.kt` — guarda a instância ativa num companion object com métodos estáticos
  `protectSocket()`/`protectDatagramSocket()`, acessíveis de qualquer parte do app
- `protocols/ssh/SshTunnelManager.kt` — protege o socket real (direto ou via proxy) antes de conectar
- `protocols/ssh/WebSocketBridgeSocket.kt` — protege via `SocketFactory` customizada passada pro OkHttp

**Segunda correção, no mesmo pacote**: mesmo corrigindo o loop, o navegador ainda não teria como resolver
nomes de site, porque o motor só tratava TCP — consultas DNS (UDP, porta 53) eram descartadas em silêncio.
Implementado um relay mínimo de DNS: `Tun2SocksEngine` agora trata pacotes UDP destinados à porta 53,
repassando a consulta por um `DatagramSocket` protegido até o servidor DNS real e escrevendo a resposta de
volta no TUN. Outros usos de UDP (jogos, chamadas) continuam não tratados por enquanto.
- `core/tun2socks/Checksums.kt` — checksum generalizado (`computeL4`) pra servir tanto TCP quanto UDP
- `core/tun2socks/PacketBuilder.kt` — novo `buildUdpPacket()`

**Terceira melhoria**: eventos do motor de VPN (fluxo aberto, erro de conexão, falha de DNS) agora também
vão pro `AppLog` — aparecem na tela de Logs do app. Antes só existiam no Logcat do Android Studio, que o
usuário não tem acesso fácil — por isso "não aparecia nada de erro" no teste anterior, mesmo com o loop
acontecendo de verdade.

## 37. Ainda travado: instrumentação completa do motor de VPN (sem novo fix ainda)

Usuário testou de novo após a correção do loop + DNS: agora dá "connect timeout" no navegador (antes era
travamento total sem nenhuma mensagem) — indício de que a correção anterior teve algum efeito, mas o motor
TCP ainda não está completando conexões de verdade. Como não consigo reproduzir/testar esse código, não dá
pra "adivinhar" mais uma correção às cegas com segurança — em vez disso, instrumentei bem mais o motor:

`core/tun2socks/TcpFlow.kt` agora loga (visível na tela de Logs do app, todos com o prefixo do
host:porta de destino):
- Início da tentativa de conexão via SOCKS5 local
- Sucesso da conexão + confirmação de que o SYN-ACK foi enviado de volta pro app
- Primeira leva de dados recebida do app (repassada pro túnel)
- Primeira leva de dados recebida do túnel (repassada de volta pro app)
- Encerramento do fluxo (e o motivo, quando é erro)

`core/tun2socks/Tun2SocksEngine.kt` — DNS agora também loga sucesso (antes só logava falha).

**Próximo teste precisa do relatório de log completo** (usar o botão de copiar) — com isso dá pra saber
exatamente até onde o fluxo chega: se nem tenta conectar (bug antes do SOCKS5), se conecta mas o app nunca
recebe o SYN-ACK (bug na construção/checksum do pacote de resposta), ou se os dados chegam mas nunca saem
(bug na extração do payload). Sem esse log específico da tentativa de VPN, continuar tentando corrigir às
cegas tem alta chance de ser tempo perdido.

**Confirmado ao usuário**: o contador de Recebido/Enviado do card SSH já reflete tráfego real passando pelo
proxy SOCKS5/túnel SSH — se ele subir durante o teste, é sinal de que pelo menos a parte SSH↔proxy está
funcionando, ajudando a isolar se o problema está aí ou na parte de devolver os pacotes pro TUN/app.

## 38. Bug real encontrado: VPN travava na porta antiga do proxy SOCKS5

Usuário mandou prints: SSH "Conectado", mas navegador dava `ERR_CONNECTION_REFUSED` e o contador
Recebido/Enviado continuava em **0 B**. Isso apontou pra um lugar bem específico.

**Causa raiz**: `SshTunnelManager` sobe um novo `Socks5Server` numa **porta aleatória diferente** a cada
conexão (`findFreePort()`). Mas `AutomBotVpnService.startVpn()` tinha um guard `if (tunInterface != null)
return` — ou seja, uma vez que a VPN de sistema já estivesse rodando, qualquer nova porta recebida
(de uma reconexão do SSH) era **completamente ignorada**. O motor continuava mandando tráfego pra uma
porta antiga que não existe mais — dando "conexão recusada" pra tudo (nosso próprio código manda RST de
volta quando o SOCKS5 falha, e é isso que o Chrome mostra como "conexão recusada") e 0 bytes reais
passando.

**Correção**:
- `core/tun2socks/Tun2SocksEngine.kt` — `socksPort` agora é `var` (atualizável), não mais fixo desde a
  criação do motor
- `core/AutomBotVpnService.kt` — se a VPN já estiver rodando, agora **atualiza a porta do motor** em vez de
  ignorar a chamada; interface TUN não precisa ser recriada, só o destino do proxy muda
- Adicionados logs (`AppLog`) em `AutomBotVpnService` mostrando quando a VPN inicia/atualiza porta/desliga
  — informação que não existia antes nessa camada

Isso deve resolver o cenário de reconectar o SSH depois de já ter ativado a VPN antes. Ainda vale testar
do zero (desligar a VPN de sistema, reconectar o SSH, ver se ativa e navega) pra confirmar.

## 39. Correção: logs sumiam ao voltar do navegador (persistência do AppLog)

Usuário reportou: abriu o log antes de testar o navegador (tinha eventos de conexão), testou a navegação,
voltou pro log — não só não tinha nada novo, o log anterior também tinha sumido.

**Causa provável**: `AppLog` vivia só em memória (`MutableStateFlow`, nunca salvo em disco). Se o Android
mata o processo do app enquanto está em segundo plano (comum em aparelhos com pouca memória — o dele
parece ser um desses, tela trincada, possivelmente mais antigo/limitado), todo o estado em memória some,
incluindo o log. Diferente dos túneis WireGuard e perfis SSH (que já persistem em `SharedPreferences`), o
log nunca tinha esse cuidado.

**Correção** (`util/AppLog.kt`): agora persiste em `SharedPreferences` a cada evento novo, e recarrega ao
iniciar (`AppLog.init()`, chamado bem cedo no `MainActivity.onCreate()`, antes de qualquer outro manager).
Sobrevive a reinícios de processo daqui pra frente.

**Correção extra, no mesmo lugar**: o botão "Limpar Cache" das Configurações dizia "cache limpo" mas não
limpava nada de verdade (só registrava uma mensagem dizendo que tinha limpado). Agora tem uma função
`AppLog.clear()` de verdade, chamada por esse botão.

## 40. Bug provável encontrado: chave acende mas motor nunca inicia (Android 14+)

Usuário confirmou: chave de VPN ativa, **mas sem a notificação "VPN ativa"**, e nenhum log de tráfego
apareceu (nem tentativa de conexão). Essa combinação é bem específica e aponta pra um lugar exato do
código.

**Hipótese forte**: no Android 14+ (API 34), `foregroundServiceType="specialUse"` (o que usamos pro nosso
`VpnService`) passou a exigir uma declaração extra no Manifest (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) — sem
ela, a chamada que mostra a notificação (`startForeground()`) lança uma exceção. Como essa chamada
acontecia **sem try-catch** e **antes** de criar o motor de roteamento, uma falha ali travava a função
inteira: a interface TUN já tinha sido estabelecida (por isso a chave acende), mas nada do que vem depois
(a notificação, e principalmente o motor que processa os pacotes) chegava a rodar.

**Correções**:
- `AndroidManifest.xml` — adicionada a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn" />` dentro da declaração do serviço
- `core/AutomBotVpnService.kt` — `startForeground()` agora está em `try-catch`: mesmo que a notificação
  falhe por qualquer motivo (permissão, versão do Android, etc.), o motor de roteamento **sempre** inicia
  depois. Isso deixa o app resistente a esse tipo de falha silenciosa se acontecer de novo por outro motivo.

Ainda não confirmado — depende do próximo teste do usuário mostrar a notificação aparecendo e (mais
importante) logs de tráfego aparecendo ao tentar navegar.

## 41. Diagnóstico real via Logcat: canal SSH recusado para 100% dos destinos

Usuário conseguiu capturar o Logcat de verdade (via Android Studio + USB) — primeira vez com dado
confiável, não dependente do AppLog (que estava sumindo, seção 39). **Sem nenhum FATAL EXCEPTION** — o app
não trava. Confirma que a correção da seção 40 (foreground service) funcionou nesse aspecto.

**Achado real**: toda tentativa de abrir canal `direct-tcpip` pelo SSH falha com `Proxy SOCKS5 local recusou
CONNECT ... (código 5)` — para TODOS os destinos testados (porta 443, 853, 5228, 993), sem exceção. Isso é
`SshTunnelManager.openDirectChannel()` recebendo uma exceção do `client.newDirectConnection(...)` e
devolvendo `null`, que o `Socks5Server` traduz pro código SOCKS5 "falha geral" (0x05).

**Duas hipóteses**:
1. Bug no código do canal SSH
2. O servidor SSH tem encaminhamento de porta desabilitado (`AllowTcpForwarding no` no `sshd_config` da
   VPS) — explicaria a recusa uniforme, independente do destino

**Faltava o motivo real**: o Logcat só mostrava o código genérico (5), não a mensagem de exceção de dentro
do `openDirectChannel`. Corrigido: `SshTunnelManager.kt` agora manda esse detalhe também pro Logcat (via
`android.util.Log.w`, com stack trace completo), não só pro AppLog — próxima captura deve mostrar a causa
real (ex: "administratively prohibited", erro de rede, etc.).

**Pendências pro usuário verificar em paralelo**: conferir `AllowTcpForwarding` no `sshd_config` do
servidor SSH usado (`core.infinitenet.net`).

## 42. CONFIRMADO: problema é 100% do servidor, não do app

Usuário testou com `ssh` puro do terminal (sem nosso app no meio) usando a mesma conta/porta
(`android@core.infinitenet.net:109`) com `-N -D <porta>` (dynamic port forward puro). Resultado: a sessão
SSH foi derrubada pelo servidor logo após autenticar — `-N` deveria manter o terminal travado
indefinidamente, mas voltou ao prompt na hora. Consequência: nenhum proxy subiu, `curl` deu "Connection
refused".

**Isso reproduz o mesmo sintoma do app, sem nenhuma linha do nosso código envolvida** — prova definitiva de
que a causa é o servidor SSH recusando abrir canais de encaminhamento de porta pra essa conta/porta,
independente de qual cliente (app ou terminal) faz o pedido.

**Ação necessária agora é no servidor, não no app**: checar `/etc/ssh/sshd_config` da VPS
(`core.infinitenet.net`), tanto a configuração global quanto qualquer bloco `Match User android`:
- `AllowTcpForwarding` deve ser `yes` (não `no`)
- `PermitOpen` não deve estar restringindo destinos (ou deve permitir `any`)
- Conferir se a conta tem `ForceCommand` configurado (mataria qualquer tentativa de túnel)

Nenhuma mudança de código feita nesta rodada — aguardando o usuário verificar/ajustar a configuração do
servidor.

## 43. Correção: log ainda sumia mesmo com persistência (apply() assíncrono)

Usuário reportou padrão consistente: ao sair do app pra testar navegação e voltar, o log de conexão
desaparecia — mesmo depois da persistência implementada (seção 39). Logcat mostrou a causa: **o processo
do app realmente foi encerrado** (`PROCESS ENDED` no Logcat) enquanto em segundo plano.

**Causa raiz real**: `AppLog.persist()` usava `SharedPreferences.edit().putString(...).apply()` —
`apply()` grava em disco de forma **assíncrona** (agenda a escrita, não espera terminar). Se o processo
morre logo depois de um evento ser logado (como aconteceu), a escrita pode não ter concluído ainda, e o
dado se perde mesmo com a persistência já implementada.

**Correção** (`util/AppLog.kt`): trocado `apply()` por `commit()`, que escreve de forma síncrona — mais
lento (bloqueia a thread até terminar), mas garante que o evento está gravado em disco antes de continuar,
sobrevivendo mesmo se o processo for encerrado logo em seguida.

## 44. Reabertura da investigação: bug de concorrência encontrado (não é mais só o servidor)

Usuário contestou a conclusão anterior com uma observação certeira: no teste do terminal, o servidor
derrubou a **sessão inteira** na hora (recusa rápida). No app, a sessão SSH continua ativa, só as
tentativas de abrir canal por site é que travam os **30 segundos inteiros**, sempre, sem exceção. Esses
são comportamentos diferentes — se fosse recusa por política do servidor, o esperado seria uma resposta
rápida (como no terminal), não um travamento completo esperando resposta que nunca chega.

**Hipótese revisada e mais provável**: bug de concorrência no app. Quando o navegador carrega uma página,
ele abre várias conexões **ao mesmo tempo** — cada uma chegava em `openDirectChannel()` e chamava
`client.newDirectConnection(...)` na **mesma conexão SSH compartilhada**, simultaneamente, de threads
diferentes. Chamadas concorrentes não sincronizadas na mesma conexão SSH é uma causa plausível e concreta
pra exatamente esse sintoma (trava tudo, sempre, sem nunca ter sucesso nem uma vez).

**Correção** (`SshTunnelManager.kt`, `openDirectChannel()`): a chamada `client.newDirectConnection(...)`
agora está dentro de `synchronized(client) { ... }` — garante que só um pedido de abertura de canal por vez
seja enviado numa mesma conexão SSH. Outros perfis/conexões SSH não são afetados (cada um tem seu próprio
`client`, sua própria trava).

**Ainda não confirmado**: se essa era de fato a causa raiz, ou se existe também alguma configuração do
servidor envolvida — o usuário está testando em paralelo se o mesmo servidor (porta 109, sem camadas
extras) também falha na navegação usando o app HTTP Custom, o que ajudaria a isolar de vez.

## 45. Novo protocolo: VLESS + WebSocket (primeira versão, não testada)

Início do terceiro protocolo do app (depois de WireGuard e SSH), pausando a investigação do SSH pra depois.
Escolhida a variante **VLESS + WebSocket** primeiro (não REALITY) — mais simples de implementar e reaproveita
o mesmo padrão de ponte WebSocket já validado no SSH.

**Descoberta útil no código-fonte do AutomBot Core** (`modules/pacote.py`): o painel já gera o link
`vless://uuid@host:porta?type=ws&path=...&security=tls&host=...&sni=...#nome` pronto — por isso a
experiência de adicionar conexão é só "colar o link", sem preencher campo por campo como no SSH.

**Novos arquivos**:
- `protocols/vless/VlessModels.kt` — `VlessConnectionConfig` + `parseVlessUri()` (parser do link do
  painel, rejeita explicitamente REALITY e outros transportes que não sejam WebSocket, com mensagem clara)
- `protocols/vless/VlessProtocol.kt` — codec do protocolo VLESS: monta o cabeçalho de requisição
  (versão + UUID + comando + porta + endereço) e `VlessResponseInputStream`, que descarta o cabeçalho de
  resposta na primeira leitura
- `protocols/vless/VlessTransport.kt` — abre a conexão WebSocket (com proteção via
  `AutomBotVpnService.protectSocket()`, mesma correção crítica do SSH) e já manda o cabeçalho VLESS
- `protocols/vless/VlessTunnelManager.kt` — mesma estrutura do `SshTunnelManager.kt`: persistência,
  conectar/desconectar, proxy SOCKS5 local (reaproveita `Socks5Server.kt` do SSH, que já era genérico)
- `ui/vless/VlessScreen.kt` — lista de conexões, mesmo padrão visual do SSH (log, excluir, tráfego)
- `ui/vless/VlessAddScreen.kt` — tela simples de colar o link `vless://`

**Integração**: `MainActivity.kt` ganhou `Screen.Vless`/`Screen.VlessAdd`, `vlessManager`, entrada na lista
"Minhas Conexões" do dashboard (prioriza conectado > erro > desconectado > não configurado, mesma lógica já
corrigida pro SSH).

### Avisos importantes desta primeira versão

- **Protocolo implementado a partir da especificação, nunca testado contra um servidor VLESS real.** É o
  mesmo nível de risco que tivemos com o SSH no início — erros de byte a mais/a menos no cabeçalho, ordem
  errada de campos, etc. são esperados numa primeira tentativa.
- **Só WebSocket, com ou sem TLS.** REALITY (o outro modo que o AutomBot Core já suporta no servidor) fica
  pra uma próxima etapa — é significativamente mais complexo (handshake TLS "falso" com troca de chaves
  X25519).
- **Sem VPN de sistema ainda** — igual ao SSH antes da Etapa 2, só sobe proxy SOCKS5 local por enquanto.
- **Sem suporte a UDP** — só TCP, mesma limitação atual do SSH.

## 46. Novo protocolo: VMess (AEAD) + WebSocket — implementação criptográfica completa

Painel do usuário gera VMess, não VLESS — mudança de plano em cima da hora. Perguntado se preferia trocar o
cliente pra VLESS no painel (zero trabalho novo, já que a Etapa 45 estava pronta) ou implementar VMess de
verdade no app, escolheu implementar VMess mesmo sabendo do risco maior.

**Por que é mais arriscado que VLESS**: VLESS manda a requisição praticamente em texto puro (confia no TLS
por fora). VMess tem criptografia própria dentro do protocolo — cada campo (chave, nonce, cabeçalho) é
derivado do UUID do usuário através de uma cadeia de HMAC-SHA256 aninhados, e qualquer bit errado nessa
cadeia faz a autenticação falhar por inteiro, sem sintoma parcial.

**Verificação ativa contra fontes oficiais** (diferente do VLESS, que foi direto da memória): antes de
escrever qualquer código, consultei a documentação oficial (v2fly.org/en_US/developer/protocols/vmess.html)
e trechos do código-fonte real do v2ray-core (`proxy/vmess/aead/encrypt.go`) via busca na internet. Isso já
existia parcialmente implementado de uma tentativa anterior nesta mesma sessão (`VmessKdf.kt`,
`VmessCrypto.kt`, `VmessModels.kt`) — ao comparar contra as fontes confirmadas, **3 bugs reais foram
encontrados e corrigidos**:

1. **KDF (função de derivação de chaves) errado** (`VmessKdf.kt`): a versão anterior encadeava só a CHAVE
   de um HMAC-SHA256 fixo a cada segmento do "caminho" de derivação. A fórmula real do VMess encadeia a
   própria FUNÇÃO DE HASH — cada segmento cria um HMAC novo que usa o HMAC anterior como base, não só como
   chave. Isso exigiu implementar HMAC na mão (RFC 2104), porque a API padrão do Java só aceita um
   algoritmo fixo como "HmacSHA256", não aceita usar outro HMAC como se fosse a função de hash de base.
2. **Campo de comprimento errado** (`VmessCrypto.kt`, `buildRequest`): media o tamanho do cabeçalho
   **depois** de criptografado, mas o correto é o tamanho **antes** (confirmado direto no código-fonte real
   do v2ray-core — a variável usada pra montar esse campo vem de antes de chamar `Seal()`/criptografar).
3. **Chave/IV de resposta com fórmula errada** (`VmessCrypto.kt`): usava MD5 — que é a fórmula do modo
   antigo/depreciado (aid≠0). Como o painel gera `aid=0` (modo AEAD), a fórmula certa é SHA-256.

**Arquivos** (`protocols/vmess/`):
- `VmessModels.kt` — config + parser do link `vmess://` (JSON em base64, campos v/ps/add/port/id/aid/net/
  type/host/path/tls/sni — confirmado contra `modules/pacote.py` do AutomBot Core)
- `VmessKdf.kt` — KDF corrigido (HMAC aninhado de verdade)
- `VmessCrypto.kt` — monta a requisição AEAD completa (AuthID cifrado, comprimento cifrado, cabeçalho
  cifrado) e decodifica/valida a resposta do servidor
- `VmessStreams.kt` — streams de entrada/saída que dividem os dados em "pedaços" (chunks) cifrados com
  AES-128-GCM, conforme o "Standard Format" da especificação
- `VmessTransport.kt` — abre WebSocket, manda a requisição, valida a resposta ANTES de liberar dados (falha
  rápido com causa clara se a autenticação não bater, em vez de falhar silenciosamente depois)
- `VmessTunnelManager.kt` — mesma estrutura do `VlessTunnelManager.kt`

**UI** (`ui/vmess/`): `VmessScreen.kt` + `VmessAddScreen.kt`, mesmo padrão do VLESS (colar link, sem tela de
configuração campo por campo).

**Integração**: `MainActivity.kt` ganhou `Screen.Vmess`/`Screen.VmessAdd`, `vmessManager`, entrada na lista
"Minhas Conexões".

### Avisos importantes desta primeira versão

- **Simplificações conscientes**: só implementado o essencial pra uma conexão TCP funcionar — sem
  mascaramento de metadados (Opt M), sem padding global (Opt P), sem modo UDP, sem instrução de porta
  dinâmica. Encriptação de dados fixada em AES-128-GCM (a opção moderna recomendada).
- **Mesmo com a verificação contra fontes oficiais, ainda é a parte mais arriscada de todo o projeto.** A
  cadeia de derivação de chaves tem muitos passos — um valor errado que não foi pego nessa revisão ainda é
  bem possível.
- **Sem VPN de sistema ainda, sem UDP** — mesmas limitações do VLESS/SSH nesta fase.

## 47. Novo protocolo: Shadowsocks (AEAD)

Quarto protocolo do app. Mais simples que VMess: conexão TCP **direta** (sem WebSocket por cima), sem
cadeia de HMAC aninhado — "só" a especificação AEAD padrão (SIP004/SIP007), confirmada contra a
documentação oficial (shadowsocks.org/doc/aead.html) antes de implementar.

Parte do código já existia de uma tentativa anterior nesta sessão (`ShadowsocksModels.kt`,
`ShadowsocksCrypto.kt`) — revisado contra a especificação oficial e **sem bugs encontrados** (diferente do
VMess): EVP_BytesToKey (derivação da chave mestra a partir da senha), HKDF-SHA1 (subchave por sessão),
nonce little-endian incrementado 2x por pedaço, endereço de destino no formato SOCKS5 (ATYP 0x01/0x03/0x04)
— tudo conferido e correto.

**Como funciona**: cada direção (envio/recebimento) manda seu próprio salt aleatório uma vez no início,
deriva sua própria subchave a partir dele, e depois disso cada "pedaço" de dados é
`[2 bytes tamanho cifrado+tag AEAD][dados cifrados+tag AEAD]`. O endereço de destino (formato SOCKS5) é
mandado como o primeiro payload cifrado — não existe um cabeçalho separado como no VLESS/VMess.

**Arquivos** (`protocols/shadowsocks/`):
- `ShadowsocksModels.kt` — config + parser do link `ss://` (SIP002: base64(método:senha)@host:porta#nome)
- `ShadowsocksCrypto.kt` — EVP_BytesToKey, HKDF-SHA1, nonce incremental, AEAD encrypt/decrypt (suporta
  chacha20-ietf-poly1305 — o método padrão do AutomBot Core — e aes-256-gcm/aes-128-gcm)
- `ShadowsocksStreams.kt` — streams que geram o salt e dividem os dados em pedaços cifrados
- `ShadowsocksTransport.kt` — abre o socket TCP direto (protegido contra loop de VPN) e manda o endereço
  de destino como primeiro payload
- `ShadowsocksTunnelManager.kt` — mesma estrutura dos outros protocolos

**UI** (`ui/shadowsocks/`): `ShadowsocksScreen.kt` + `ShadowsocksAddScreen.kt`, mesmo padrão dos outros
(colar link).

**Integração**: `MainActivity.kt` ganhou `Screen.Shadowsocks`/`Screen.ShadowsocksAdd`,
`shadowsocksManager`, entrada na lista "Minhas Conexões".

### Avisos desta primeira versão

- Verificado contra a especificação oficial, mas ainda nunca testado contra um servidor real.
- Só TCP — sem suporte a UDP (Shadowsocks também é usado bastante pra UDP/jogos, não implementado ainda).
- Sem VPN de sistema ainda — mesma limitação dos outros protocolos nesta fase.

## 48. Pendência combinada: voltar no VMess (e nos outros) pra virar VPN de sistema

Usuário confirmou que quer voltar no VMess depois pra finalizar e fazer ele substituir a VPN do aparelho
(mesmo objetivo que já existe combinado pro SSH — Etapa 2, VpnService + tun2socks). Provavelmente vale
generalizar esse motor de VPN de sistema pra funcionar com qualquer protocolo (SSH/VLESS/VMess/Shadowsocks)
em vez de reimplementar pra cada um, já que a peça que falta é sempre a mesma (rotear o tráfego do
Tun2SocksEngine através do proxy SOCKS5 local de cada protocolo, que já existe pra todos).

## 49. VMess agora vira VPN de sistema (mesma engine já existente)

Pedido: "aplicar o V2Ray" acabou sendo, depois de esclarecido, o pendente da Etapa 48 — fazer o VMess
substituir a VPN do aparelho, igual já combinado pro SSH.

**Boa notícia**: não precisou nada novo na engine de VPN. O `AutomBotVpnService`/`Tun2SocksEngine` (Etapa
34) já é 100% genérico — só recebe um `socksPort` e roteia todo o tráfego do aparelho através dele, sem
saber nem se importar qual protocolo criou aquele proxy SOCKS5. A "ligação" do SSH com a VPN de sistema
também já era simples: `SshScreen.kt` tinha um `LaunchedEffect(connections)` que observa qual conexão está
`CONECTADA` e liga a VPN de sistema automaticamente apontando pro proxy dela (sem toggle manual separado).

**Mudança**: replicado o mesmo `LaunchedEffect` em `VmessScreen.kt` (parâmetro `onVpnRouting` novo,
ligado no `MainActivity.kt` do mesmo jeito que o SSH). Resultado: conectar um perfil VMess agora ativa a
VPN de sistema de verdade (ícone de chave na barra de status), igual ao WireGuard/SSH.

**Limitação conhecida (herdada do SSH, não é nova)**: só a "primeira conexão conectada da tela que está
aberta" controla a VPN de sistema — se você tiver, por exemplo, SSH e VMess conectados ao mesmo tempo, quem
manda no roteamento é o último protocolo cuja tela você visitou. Não tem um coordenador central ainda entre
os protocolos. Vale generalizar isso melhor mais pra frente, quando todos os protocolos tiverem essa opção
(VLESS e Shadowsocks ainda não ganharam essa ligação, só SSH e VMess por enquanto).

## 50. Investigação: só WireGuard gera dados reais, todos os outros não

Usuário reportou algo importante que muda a prioridade de tudo: WireGuard é o **único** protocolo que
funciona como VPN de verdade (gera dados no navegador). SSH, VLESS, VMess, Shadowsocks conectam, alguns
mostram até a chave de VPN ativa, mas nenhum gera tráfego real.

**Diagnóstico**: WireGuard não passa pelo motor próprio (`Tun2SocksEngine`) — usa a biblioteca nativa do
próprio WireGuard (`GoBackend`), já pronta e testada em produção por terceiros. **Todos os outros
protocolos** passam pelo `Tun2SocksEngine`, motor de TCP/IP escrito do zero nesta sessão, nunca testado em
dispositivo real. Como o padrão de falha é o mesmo em protocolos bem diferentes entre si (SSH, VLESS, VMess,
Shadowsocks), o mais provável é o bug estar no motor compartilhado, não em cada protocolo individualmente.

**Revisão feita**: reli checksums (`Checksums.kt`), montagem de pacotes IP/TCP (`PacketBuilder.kt`),
configuração da interface TUN (`AutomBotVpnService.kt`) e a máquina de estados de cada conexão
(`TcpFlow.kt`) — nenhum bug óbvio e definitivo encontrado que explique "zero dados sempre" (checksums batem
com RFC 1071, campo é zerado antes de calcular, endereços/rotas da interface TUN parecem corretos).

**Duas correções concretas aplicadas mesmo assim**:
1. **Opção MSS ausente no SYN-ACK** (`PacketBuilder.kt`, `TcpFlow.kt`): sem essa opção, o Android pode
   assumir o tamanho mínimo de pacote (536 bytes) em vez do real (1460, baseado na MTU 1500 da interface) —
   não impede a conexão de abrir, mas pode inviabilizar navegação real. Adicionada a opção MSS
   especificamente no pacote SYN-ACK.
2. **VLESS e Shadowsocks não tinham a ligação com a VPN de sistema** — só SSH e VMess tinham o
   `LaunchedEffect` que liga a VPN automaticamente ao conectar (Etapa 49). Replicado o mesmo padrão nos
   dois (`VlessScreen.kt`, `ShadowsocksScreen.kt`) — explica por que "alguns mostra a chave, outros nem
   isso" no relato do usuário.

**Ainda não resolvido / próximo passo real**: a causa exata de "conecta mas não gera dado" continua sem
confirmação — a revisão estática não encontrou o bug definitivo. O `TcpFlow.kt` já tem logging detalhado por
etapa (`AppLog`, tag `[destino:porta]`): "nova conexão TCP", "abrindo via SOCKS5 local", "conectado pelo
túnel, enviando SYN-ACK", "recebeu N bytes do app", "chegou N bytes do túnel". Testar de novo (SSH ou VMess
como VPN de sistema) e mandar o log copiado vai mostrar exatamente até onde cada conexão chega — se nem
"nova conexão TCP" aparece, o problema é antes (pacotes não chegando no motor); se para em "enviando
SYN-ACK" sem nunca aparecer "recebeu N bytes do app", o problema é o Android rejeitando nossos pacotes
(volta pra suspeita de checksum/formato, precisa de captura de pacote real pra confirmar); se aparece
"recebeu" mas nunca "chegou do túnel", o problema é na leitura de volta do socket real.

## 51. Evidência direta encontrada: conexões reais entrando em loop pela própria VPN

Usuário mandou prints com logs reais de VLESS e Shadowsocks tentando ser VPN de sistema — e o log do
Shadowsocks tinha uma prova direta, não uma suspeita: `failed to connect to /163.176.5.96 (port 20003)
from /10.90.0.2 (port 42000) after 10000ms`. **10.90.0.2 é exatamente o IP que a própria VPN atribui à
interface TUN** (`AutomBotVpnService.kt`). Isso prova que a conexão real com o servidor Shadowsocks estava
sendo capturada pela própria VPN em vez de sair direto pela rede — um loop clássico. O VLESS trava num
ponto anterior (timeout abrindo o WebSocket pro servidor real), mesma causa provável.

**Causa raiz encontrada**: `AutomBotVpnService.protectSocket()`/`protectDatagramSocket()` (as funções que
isentam uma conexão real de ser capturada pela própria VPN) sempre foram chamadas, mas **o resultado nunca
era conferido** em nenhum lugar do código (SSH, VLESS, VMess, Shadowsocks) — se `protect()` falhasse de
verdade, ninguém saberia, e o sintoma seria exatamente esse: conecta (interface TUN sobe), mas a conexão
real nunca sai de fato, travando em loop até estourar timeout.

**Correção** (`AutomBotVpnService.kt` + todos os transportes — SSH, VLESS, VMess, Shadowsocks): agora o
retorno de `protect()` é conferido em todo lugar. Se falhar, a conexão falha IMEDIATAMENTE com mensagem
clara ("Não consegui isentar esta conexão da VPN — protect() falhou"), em vez de travar 10 segundos num
loop silencioso sem pista nenhuma.

**Possível causa externa a considerar** (fora do nosso código): o Android tem uma opção "Bloquear conexões
sem VPN" / VPN sempre ativa, que pode ser ligada por app específico nas configurações do sistema — se
estiver ligada pro AutomBot Connect, ela **sobrepõe** qualquer `protect()` feito pelo próprio app,
recriando o mesmo loop mesmo com o código 100% correto. Vale o usuário conferir isso nas configurações do
Android (Configurações > Rede > VPN > engrenagem ao lado do AutomBot Connect) como parte do próximo teste.

**Próximo teste**: agora, se `protect()` estiver realmente falhando, o log vai mostrar isso explicitamente
("protect() FALHOU") em vez do timeout genérico anterior — confirma ou descarta essa causa de vez.

## 52. Usuário derrubou a teoria da configuração do Android com um argumento certeiro

Testou de novo com "Bloquear conexões sem VPN" desligado (confirmado por print) — erro idêntico continuou.
E fez uma observação decisiva: **se fosse uma restrição geral do Android, o WireGuard teria o mesmo
problema, já que usa a mesma API de VPN do sistema. Mas o WireGuard funciona perfeitamente** (print
mostrando tráfego real: 14,4 KB recebido / 8,1 KB enviado). Isso descarta de vez a teoria da configuração
do aparelho — a causa é especifica de como o nosso proprio codigo Kotlin chama `protect()`, nao uma
restricao do sistema.

**Diferença estrutural real**: o WireGuard não passa pela nossa função `protect(Socket)` — a biblioteca
nativa dele (Go) protege o próprio socket UDP por dentro, num caminho de código completamente diferente
(nível nativo/JNI, não `java.net.Socket`). Por isso os dois podem se comportar diferente mesmo usando a
mesma API do sistema por baixo.

**Correção aplicada** (`AutomBotVpnService.kt`, `startVpn()`): adicionado `builder.addDisallowedApplication
(packageName)` na configuração da interface — em vez de depender só de proteger cada socket individualmente
na hora certa (que por algum motivo não está bastando), isso exclui o **próprio app inteiro** do roteamento
da VPN direto na configuração da interface. É uma segunda camada, mais robusta, complementar ao `protect()`
que já existia (mantido).

**Ainda não confirmado por teste real** — essa é a correção mais provável de resolver, mas não descarto
que a causa exata de `protect()` falhar continue sem explicação 100% certa. Próximo teste real vai
confirmar.

## 53. Bug real confirmado por log: VPN presa numa porta de proxy morta — decisão centralizada

Usuário mandou um log real e longo (múltiplos reinícios do app, ~20:32-20:38) com achado novo: uma sequência
grande de `Falha ao conectar fluxo ... failed to connect to /127.0.0.1 (port 42267) ... ECONNREFUSED`.

**Causa raiz**: cada tela (SSH/VLESS/VMess/Shadowsocks) decidia SOZINHA, no seu próprio `LaunchedEffect`, se
ela era quem mandava no roteamento da VPN de sistema — só funcionava enquanto o usuário estava exatamente
naquela tela. Ao sair da tela ou reiniciar o app, a VPN ficava presa apontando pra uma porta de proxy que
não existe mais (o `Socks5Server` daquela sessão já foi encerrado), e todo tráfego subsequente falha com
ECONNREFUSED — mesmo que outro protocolo esteja perfeitamente funcional. Essa era a limitação já registrada
na Etapa 49/52 ("não tem um coordenador central"), que virou bug real de verdade neste teste.

**Correção** (arquitetural, não só um patch pontual): a decisão de qual porta a VPN de sistema deve rotear
foi movida pra um lugar ÚNICO — `MainShell` em `MainActivity.kt`, que já observa o estado de TODOS os
protocolos ao mesmo tempo (independente de qual tela o usuário está vendo). Prioridade fixa quando mais de
um protocolo está conectado simultaneamente: SSH > VLESS > VMess > Shadowsocks (WireGuard fica de fora,
gerencia seu próprio túnel nativo). Removido o parâmetro `onVpnRouting` e o `LaunchedEffect` individual de
cada uma das 4 telas (`SshScreen.kt`, `VlessScreen.kt`, `VmessScreen.kt`, `ShadowsocksScreen.kt`).

### Status por protocolo neste teste (relato do usuário)

- **SSH**: conecta e funciona, mas está **lento** no navegador — causa ainda não identificada; o log
  enviado não continha entradas específicas do SSH (só VLESS/VMess), então não deu pra diagnosticar a
  lentidão a partir dele. Precisa de um teste novo com "Ver log" filtrado no SSH.
- **VLESS/VMess**: continuam com `protect() falhou` em toda tentativa, sem exceção — **ainda não confirmado
  se este teste já rodou com o `addDisallowedApplication()` da Etapa 52** (o texto do erro não muda com essa
  correção, então não dá pra saber pelo log se ela já foi testada).
- **"Erro de proxy pontual mesmo com configurações corretas"**: mencionado pelo usuário em outras conexões
  que ele está testando, mas sem log específico anexado ainda — meio de investigação sem confirmação nesta
  etapa.

## 54. Correção real do bug de roteamento — estava no lugar errado (MainShell, não AppRoot)

Usuário reportou o sintoma exato que eu tinha acabado de suspeitar antes de terminar a correção: conectar
uma tela de protocolo (SSH/VLESS/VMess/Shadowsocks) NÃO acendia a chave da VPN enquanto estava naquela tela
— só acendia ao voltar pro Dashboard.

**Causa raiz confirmada**: a Etapa 53 moveu a decisão de roteamento pro `MainShell` — mas `MainShell` só é
composto (só "existe" na tela) quando `screen == Screen.Shell`. Ao navegar pra dentro de `Screen.Ssh`,
`Screen.Vmess` etc., o `when(screen)` no `AppRoot` troca de branch inteiro — `MainShell` para de existir
temporariamente, e o `LaunchedEffect` dentro dele para de rodar junto. Resultado: a decisão só era
reavaliada quando o usuário voltava pro Dashboard (recompondo o `MainShell` de novo).

**Correção definitiva**: o `LaunchedEffect` de roteamento foi movido pra dentro do `AppRoot` — o composable
mais externo, que faz o próprio `when(screen)` e por isso está SEMPRE ativo, não importa qual tela está
sendo mostrada. Removido o `LaunchedEffect` duplicado que tinha ficado dentro do `MainShell`.

### Ainda em aberto neste log

- **Log confirma que `protect() falhou` continua acontecendo em VMess e Shadowsocks**, sem exceção — a
  causa raiz de fundo desse problema (por que `protect()` retorna `false`) segue sem explicação definitiva.
  Vale reavaliar depois que esse bug de roteamento estiver realmente corrigido e testado, pra ter um
  cenário limpo.
- **Log não contém nenhuma entrada de SSH** — não deu pra investigar a lentidão relatada a partir dele.
- **Usuário relatou precisar corrigir erros de compilação manualmente no Android Studio** antes de conseguir
  instalar — os erros específicos e as correções que ele (ou o autofix do Android Studio) aplicou ainda não
  foram compartilhados; podem ter introduzido divergências que valem conferir.
- **Usuário relatou travamento/crash do app ao desconectar SSH** (dropbear) — descrição de voz ficou
  ambígua ("erro 404 443"), sem log ou print anexado ainda; precisa de mais detalhe pra investigar.

## 55. Descoberta real: SSH gerou tráfego de verdade (1,4 MB) — pista sobre o protect()

Usuário mandou print mostrando o perfil "SSH-Direct" **conectado com dados reais**: 1,4 MB recebido, 452,2
KB enviado. **Primeira vez em toda a investigação que um protocolo além do WireGuard mostra tráfego real
passando pelo motor próprio (Tun2SocksEngine)** — confirma que checksums, MTU/MSS, configuração da
interface TUN etc. estão fundamentalmente corretos.

Só que a conexão ficou tão lenta que travou a ponto de o próprio navegador (teste em speedtest.net) dar erro
de socket na função de download — e voltando pro app, ainda apareceram (só que bem menos) os mesmos erros
de `protect() falhou` em VLESS/VMess/Shadowsocks, mesmo já com a correção da Etapa 54.

**Diferença estrutural que chamou atenção**: o SSH abre **uma única conexão real, protegida uma vez**, e
depois multiplica canais lógicos por dentro dela (usando a mesma conexão para vários destinos). VLESS,
VMess e Shadowsocks, do jeito que estão implementados, abrem uma conexão real **nova (WebSocket ou TCP)
para CADA destino diferente** — cada uma dessas exige um `protect()` novo. Como o navegador abre dezenas
de conexões simultâneas ao carregar uma única página, e não havia limite nenhum no
`Socks5Server`, isso gera uma rajada de dezenas de chamadas de `protect()` ao mesmo tempo.

**Hipótese de trabalho**: `protect()` do Android pode começar a falhar sob rajada de chamadas concorrentes
(sem limite), mesmo funcionando perfeitamente para uma única chamada isolada (como no SSH). Isso também
bateria com a lentidão geral — dezenas de sockets sendo abertos ao mesmo tempo, sem controle, competindo
por recursos.

**Correção aplicada** (`Socks5Server.kt`): adicionado um limite de 12 conexões reais sendo abertas ao mesmo
tempo (`Semaphore`), aplicado a todos os protocolos que usam esse servidor SOCKS5 (SSH/VLESS/VMess/
Shadowsocks). Se a hipótese estiver certa, isso deve reduzir bastante (ou eliminar) as falhas de `protect()`
e ajudar com a lentidão/travamento — ainda não confirmado por teste real.

### Pendências continuando em aberto
- Travamento ao desconectar o SSH — usuário se ofereceu para mandar o log/teste gerado no Android Studio;
  aceito a oferta, vai ajudar a investigar direito.
- Detalhes dos erros de compilação corrigidos manualmente no Android Studio — não chegaram a ser
  compartilhados, mas como o app está funcionando (gerando dados reais), não é prioridade re-perguntar por
  enquanto.

## 56. Teoria da rajada DERRUBADA — nova causa raiz encontrada e testável: bind() antes de protect()

O limite de 12 conexões simultâneas (Etapa 55) **não mudou nada** — log novo mostra `protect() falhou`
continuando em 100% das tentativas de VLESS/VMess/Shadowsocks, mesmo throttled. A hipótese da rajada está
descartada.

Reexaminando o log com calma, percebi uma falha no meu raciocínio anterior: a comparação "SSH funciona,
os outros não" não é justa, porque **nunca vimos de fato o `protect()` do SSH sendo genuinamente exercido
contra uma VPN já ativa**. O `connect()` do SSH faz o handshake real ANTES de marcar a conexão como
"CONECTADO" — e é só depois disso que a VPN de sistema liga. Ou seja, o `protect()` do SSH roda num momento
em que ainda não existe VPN nenhuma (`activeInstance` nulo), e a função simplesmente pula a proteção
("sem VPN ativa, nada a proteger") — não é que ele funcionou, é que ele nunca foi realmente testado.

Já VLESS/VMess/Shadowsocks marcam "CONECTADO" IMEDIATAMENTE ao só iniciar o proxy SOCKS5 local, sem
nenhuma conexão real com o servidor ainda — a VPN de sistema liga instantaneamente, e só DEPOIS disso a
primeira conexão real é tentada (na primeira requisição vinda do navegador). Ou seja: é a PRIMEIRA VEZ que
qualquer `protect()` genuíno (com VPN já rodando de verdade) é exercitado neste projeto todo — e falha
sempre.

**Nova causa raiz investigada**: um `java.net.Socket()` recém-criado, sem `bind()` nem `connect()` ainda,
pode não ter o descritor de arquivo (file descriptor) nativo alocado de verdade — dependendo da
implementação, isso só acontece na primeira operação de rede real. `VpnService.protect()` do Android
precisa desse fd nativo pra funcionar; chamado cedo demais, sem fd válido, ele pode falhar mesmo com tudo
mais certo.

**Correção aplicada**: adicionado `socket.bind(InetSocketAddress(0))` (bind numa porta qualquer escolhida
pelo sistema) logo após criar o socket e ANTES de chamar `protect()`, forçando a alocação do fd nativo. Isso
não afeta o `connect()` que vem em seguida. Aplicado nos 4 lugares onde protect() é chamado:
`VlessTransport.kt`, `VmessTransport.kt`, `ShadowsocksTransport.kt`, `SshTunnelManager.kt` (caminho
direto), `WebSocketBridgeSocket.kt`.

Ainda não confirmado por teste real — próximo teste deve mostrar se `protect() FALHOU` some do log.

## 57. Travamento silencioso do SSH identificado: channel-open sem timeout dentro do lock

Usuário esclareceu o sintoma: SSH-Direct conecta, gera um pouco de dado real, **para de funcionar mas
continua mostrando "Conectado"**, e **não gera nenhuma linha nova no log** quando trava.

Isso não é um erro — é um travamento silencioso. Causa encontrada: `client.newDirectConnection(...)`
(dentro do `synchronized(client)` adicionado numa correção anterior pra evitar concorrência) não tem
NENHUM timeout. Se o servidor SSH não responder ao pedido de abrir um canal novo por qualquer motivo (rede
instável, servidor sobrecarregado), essa chamada trava para sempre — e como está dentro do lock, **toda
conexão nova daquele mesmo túnel fica esperando atrás dela, também para sempre**. Como nada lança exceção,
nada cai no bloco que gera log — silêncio total, exatamente como relatado.

**Correção aplicada** (`SshTunnelManager.kt`): a chamada `client.newDirectConnection(...)` agora roda numa
thread separada com prazo máximo de 15 segundos (`Future.get(15, TimeUnit.SECONDS)`). Se estourar, solta o
lock e lança um erro de verdade — que aí sim gera log, e libera as conexões seguintes pra tentar de novo em
vez de ficarem paradas atrás de uma que nunca vai responder.

Limitação conhecida: se a chamada travada de verdade não responder à interrupção (`future.cancel(true)`),
a thread de fundo pode ficar presa indefinidamente nos bastidores do sshj — mas isso é estritamente melhor
que travar o túnel inteiro, já que agora é só uma thread perdida, não mais um deadlock total.

Usuário se ofereceu a mandar o projeto completo compilado no Android Studio pra análise mais profunda —
aceito a oferta; pode ajudar bastante se esta correção não resolver por completo.

## 58. Análise do projeto completo enviado pelo usuário + log "sumindo"

Usuário enviou o projeto inteiro (zip) pra análise mais profunda. Conferindo os arquivos reais:
**a correção do timeout do SSH (Etapa 57) não estava presente** — as correções anteriores (bind() antes de
protect(), Semaphore, roteamento no AppRoot) sim. Ou seja, o teste "continua travando" foi feito SEM a
correção do timeout ainda aplicada — não é evidência de que ela não funcionou, só que ainda não foi testada
de verdade.

Novo sintoma relatado: ao abrir outro app (testar internet) e voltar, **o log "sumiu" mesmo com a conexão
ainda mostrando ativa**. Hipótese mais provável: o processo do app foi encerrado pelo Android por falta de
memória (comum ao rodar testes pesados tipo speedtest em outro app) e reiniciado — nesse caso TUDO que
estava só em memória se perde (o log deveria recarregar do disco, mas as conexões/gerenciadores em si não
sobrevivem a um reinício de processo). O ícone de VPN ativo na barra de status pode persistir por um tempo
mesmo após o processo cair, dando a impressão de "ainda conectado".

Duas correções aplicadas em `AppLog.kt`:
1. O carregamento do log salvo em disco (`loadPersisted()`) estava dentro de um `runCatching` que engolia
   qualquer erro em silêncio — se o JSON salvo estivesse corrompido/incompleto, o log ficava vazio sem
   nenhuma pista do motivo. Agora, se falhar, pelo menos vai pro Logcat.
2. `init()` agora loga explicitamente se está rodando pela primeira vez nesse processo (recarrega do disco)
   ou se é uma reentrada no mesmo processo (não faz nada) — isso ajuda a confirmar, olhando o Logcat, se o
   processo do app está mesmo sendo reiniciado pelo sistema entre um teste e outro.

Próximo passo: pedir pro usuário testar de novo COM a correção do timeout (Etapa 57) incluída dessa vez, e
observar o Logcat em volta do momento em que o log "some", procurando por linhas como "Process: 
com.autombot.client" seguidas de encerramento/reinício — isso confirmaria ou descartaria a teoria da morte
de processo por falta de memória.

## 59. Causa real da lentidão/"não tunela apps": UDP não-DNS era descartado em silêncio

Usuário identificou (com o app já mais estável graças às correções anteriores + do agente do Studio) o
verdadeiro problema por trás da lentidão e apps não funcionando: **`Tun2SocksEngine` só reconhecia dois
tipos de tráfego: TCP, e UDP quando era DNS (porta 53)**. Todo o resto do UDP — que é a maior parte do que
apps modernos usam (QUIC/HTTP3, chamadas de voz/vídeo, notificações push, jogos) — era descartado sem
nenhum log, sem erro, o pacote simplesmente desaparecia. Isso explica por que o WireGuard (que opera no
kernel do Android e já suporta TCP+UDP nativamente) é o único protocolo 100% funcional até agora.

Usuário pediu pra atacar isso pelo caminho mais difícil primeiro (SSH), sabendo de antemão — expliquei
isso claramente — que **SSH puro não tem como tunelar UDP genuíno**: o protocolo só sabe abrir canais
`direct-tcpip` (TCP) no servidor; se o destino final só fala UDP, não há solução do lado do cliente,
período. Isso não é uma limitação da implementação, é do próprio desenho do protocolo SSH.

### O que foi feito

**Arquitetura genérica (`Tun2SocksEngine.kt`)**: generalizado de "só porta 53" pra um sistema plugável —
`onOpenUdpFlow`, um "opener" que cada protocolo pode implementar. Se nenhum protocolo souber tunelar aquele
destino específico, o motor agora **loga isso claramente** (uma vez por destino, pra não inundar) em vez de
descartar em silêncio — essa parte é 100% reaproveitável pros outros 3 protocolos.

**Caminho específico do SSH (`SshTunnelManager.kt`)**: implementado um "jeitinho" pra porta 443
especificamente — a maioria dos serviços que oferecem QUIC/HTTP3 também aceita a conexão tradicional
TLS-sobre-TCP na mesma porta 443 como alternativa. Então, ao ver um UDP:443, o SSH abre um canal
`direct-tcpip` NORMAL pro mesmo host:443 e relay os bytes brutos nos dois sentidos. **Isso não é UDP de
verdade** — não preserva os limites originais de pacote, e só funciona se o servidor de destino realmente
tiver esse fallback TCP (a maioria tem). UDP fora da porta 443 continua sem solução possível via SSH.

**Conectado no `AutomBotVpnService.kt`**: o motor agora tenta `SshTunnelManager.tryOpenUdpOver443(...)`
como opener de UDP.

### Próximos passos (não implementado ainda)
- VLESS e VMess têm suporte a UDP nativo no desenho do protocolo — dá pra implementar de verdade (sem o
  "jeitinho" limitado a porta 443).
- Shadowsocks também tem suporte nativo a UDP no protocolo — provavelmente o mais simples de implementar
  dos três.
- Ainda não testado em dispositivo real.

## 60. Bug meu: bytes com sinal gerando "IPs negativos" no UDP:443 do SSH

Log do teste mostrou erros tipo `Error resolving '-84.-39.-94.-86' port '443'` — endereço IP com números
negativos, o que não existe de verdade. Causa: em Kotlin, `Byte` é COM SINAL (-128 a 127). Na Etapa 59,
escrevi `dstAddr.joinToString(".")` sem converter cada byte pra unsigned antes — qualquer octeto do IP
maior que 127 (ex: 172 vira -84, 217 vira -39) virava negativo, gerando um "host" que não existe, que o
SSH então falhava ao resolver.

O resto do projeto já fazia essa conversão certinha (`(it.toInt() and 0xFF).toString()` em `TcpFlow.kt` e
`Socks5Server.kt`) — só o código novo do UDP:443 (Etapa 59) tinha o bug. Corrigido em `Tun2SocksEngine.kt`
(no `dstHost` usado pelo UDP:443, e também nas duas linhas do `flowKey`, por consistência, embora essas não
afetassem a funcionalidade diretamente).

## 61. Início da migração pro motor de tun2socks nativo (hev-socks5-tunnel)

Usuário perguntou como HTTP Injector/HTTP Custom funcionam por baixo dos panos — a resposta revelou que
esses apps NÃO escrevem o motor de roteamento de pacotes do zero: usam uma biblioteca nativa C madura
(tipicamente `badvpn-tun2socks` ou `hev-socks5-tunnel`), já testada em produção por anos, que resolve TCP e
UDP genuínos (IPv4/IPv6) de fábrica. Praticamente todo bug caçado nesta sessão inteira (protect() com fd
inválido, bytes de IP com sinal errado, MSS do TCP, UDP não suportado) é exatamente a categoria de problema
que essas bibliotecas já resolveram. Decidido migrar pra essa abordagem em vez de continuar corrigindo o
motor próprio em Kotlin peça por peça.

**Biblioteca escolhida**: `hev-socks5-tunnel` (https://github.com/heiher/hev-socks5-tunnel) — usada por
apps de produção reais (NekoBox, Matsuri). Aceita um file descriptor de TUN já estabelecido como parâmetro
(perfeito pro nosso `VpnService.Builder().establish()` existente), suporta TCP+UDP genuínos completos.

**Limitação minha**: não tenho acesso à internet no ambiente de código deste chat — não consigo baixar o
código-fonte da biblioteca nem compilar C nativo aqui. Divisão de trabalho: eu escrevo toda a "cola"
(ponte JNI, wrapper Kotlin, configuração do Gradle/CMake), o usuário roda os comandos de terminal (que
têm internet) pra buscar e pré-compilar a biblioteca.

### Arquivos criados
- `app/src/main/cpp/autombot_tun2socks_jni.c` — ponte JNI: chama `hev_socks5_tunnel_main_from_str()` numa
  pthread separada (a função bloqueia até `hev_socks5_tunnel_quit()`), expõe start/stop/stats pro Kotlin.
- `app/src/main/cpp/CMakeLists.txt` — compila a ponte e linka com a lib nativa pré-compilada.
- `app/src/main/cpp/README.md` — passo a passo pro usuário: clonar o repositório, compilar com o
  toolchain do NDK (`make static`), conferir se bateu com o que o CMake espera.
- `app/src/main/java/com/autombot/client/core/tun2socks/NativeTun2Socks.kt` — wrapper Kotlin
  (`System.loadLibrary` + `external fun`).
- `build.gradle.kts` — configuração do NDK (só `arm64-v8a` por enquanto, cobre a esmagadora maioria dos
  aparelhos reais) + `externalNativeBuild` apontando pro CMakeLists.txt novo.

### Pendências / pontos a confirmar
- Assinatura exata de `hev_socks5_tunnel_stats()` ainda não confirmada contra o `hev-main.h` real (só
  documentação indireta) — suposta como `void hev_socks5_tunnel_stats(size_t *tx, size_t *rx)`, pode
  precisar de ajuste.
- `Tun2SocksEngine.kt` (motor antigo em Kotlin) continua no projeto por enquanto, ainda em uso — a troca
  de verdade no `AutomBotVpnService.kt` (usar `NativeTun2Socks` no lugar) fica pro próximo passo, depois
  que o usuário confirmar que a biblioteca nativa compila com sucesso.
- Só `arm64-v8a` configurado por ora — outras ABIs (armeabi-v7a, x86_64 pra emulador) exigem repetir a
  compilação da lib nativa pra cada uma.

## 62. Motor nativo compilou com sucesso — e agora de fato ligado no serviço

Usuário confirmou (via print do Android Studio) que a biblioteca nativa `hev-socks5-tunnel` compilou com
sucesso pela primeira vez — o agente do Studio inclusive corrigiu a assinatura de
`hev_socks5_tunnel_stats()` que eu tinha deixado marcada como "não confirmada" (a real retorna 4 valores:
pacotes e bytes de TX/RX, não só 2).

Só que o `AutomBotVpnService.kt` ainda estava usando o motor antigo (`Tun2SocksEngine`, Kotlin) — a
biblioteca nova só tinha sido deixada pronta pra compilar, nunca conectada de verdade. Corrigido agora:

- `startVpn()`: troca `Tun2SocksEngine(...).start()` por `NativeTun2Socks.start(tun.fd, "127.0.0.1", socksPort)`.
- Reconexão com VPN já ativa (ex: SSH reconectou, porta SOCKS5 local mudou): como o motor nativo recebe a
  porta uma única vez, embutida no config YAML na hora de iniciar (diferente do antigo, que permitia trocar
  a porta de um motor já rodando), a lógica agora para e reinicia o motor nativo, reaproveitando o MESMO
  file descriptor de TUN já estabelecido (não precisa reconstruir a interface toda).
- `stopVpn()`: troca `engine?.stop()` por `NativeTun2Socks.stop()`.
- Removido o wiring do `onOpenUdpFlow` (o jeitinho de UDP:443 do SSH, Etapa 59) — fica obsoleto com o motor
  nativo, que faz UDP de verdade via SOCKS5 UDP ASSOCIATE.

### Bloqueio real encontrado pro UDP funcionar de ponta a ponta

O motor nativo tenta negociar UDP com o proxy SOCKS5 LOCAL (o nosso `Socks5Server.kt`, usado por
SSH/VLESS/VMess/Shadowsocks) através do comando `UDP ASSOCIATE` do protocolo SOCKS5 — **mas nosso
`Socks5Server.kt` só implementa o comando `CONNECT`** (confirmado no código: qualquer outro comando cai no
"não suportado"). Ou seja: mesmo com o motor nativo rodando certinho, UDP genuíno ainda não vai fluir até
implementarmos suporte a `UDP ASSOCIATE` no nosso SOCKS5 local — essa é a próxima peça que falta pra
completar a migração de verdade.

## 63. UDP ASSOCIATE implementado em todos os protocolos (menos WireGuard, que já tinha)

Usuário pediu pra fechar o suporte a UDP em todos os protocolos de uma vez (SSH/VLESS/VMess/Shadowsocks),
começando pela peça mais difícil, pra deixar tudo pronto pra compilar e testar junto no final.

### Infraestrutura compartilhada
- **`UdpBackend.kt`** (novo): interface comum — `UdpAssociateOpener` (o "abridor" que cada protocolo
  implementa) e `UdpBackendSession` (send/close).
- **`Socks5Server.kt`**: reescrito pra suportar o comando `UDP ASSOCIATE` (0x03) do SOCKS5 além do
  `CONNECT` (0x01) já existente — implementação própria da RFC 1928: abre um socket UDP local ("relay"),
  devolve o endereço/porta pro cliente, mantém a conexão TCP de controle aberta enquanto a associação dura,
  decodifica cada datagrama SOCKS5-UDP recebido (RSV+FRAG+ATYP+ADDR+PORT+DATA), abre uma sessão de backend
  por destino sob demanda, e reembrulha qualquer resposta no mesmo formato. Se o protocolo não passar um
  `onUdpAssociateRequest` (== null), o pedido é recusado educadamente, como antes.

### Por protocolo
- **Shadowsocks** (`ShadowsocksUdpTransport.kt`, novo): UDP nativo de verdade, conforme a spec oficial —
  UM socket UDP compartilhado por conexão (não um por destino), cada pacote se autodescreve com o endereço
  de destino (mesmo formato do primeiro payload do TCP), salt aleatório novo a cada pacote, nonce sempre
  zero (seguro porque a chave/subkey muda junto com o salt a cada pacote).
- **VLESS** (`VlessProtocol.kt` + `VlessUdpTransport.kt`, novo): uma conexão WebSocket NOVA por destino
  (igual ao TCP), com o byte de comando trocado pra `0x02` (UDP) e cada pacote prefixado com 2 bytes de
  comprimento (framing explícito, não depende de limites de mensagem do WebSocket).
- **VMess** (`VmessCrypto.kt` + `VmessUdpTransport.kt`, novo): mesma ideia — comando `0x02` no cabeçalho —
  mas reaproveitando o framing por chunks que o TCP já usa (`VmessOutputStream`/`VmessInputStream`) sem
  mudança nenhuma, já que um datagrama real sempre cabe num chunk só.
- **SSH**: mantido o "jeitinho" de UDP:443 (Etapa 59/60) — mas agora ligado através do mecanismo oficial
  de `UDP ASSOCIATE`, no lugar do hack específico que existia antes dentro do motor Kotlin antigo (que já
  não existe mais). Continua só funcionando pra porta 443 — limitação real do protocolo SSH, sem solução
  possível.

### Limpeza
Removido o código morto que só existia pra alimentar o motor antigo: `SshTunnelManager.tryOpenUdpOver443`
(companion function) e o campo `activeInstance` que só ela usava — a conexão SSH agora passa seu handler de
UDP direto pro próprio `Socks5Server` dela, mesmo padrão dos outros três protocolos.

### Ainda não testado
Nenhuma dessa implementação de UDP foi testada em dispositivo real ainda — assim como o motor nativo em si
(Etapa 61/62), essas são as partes de maior risco/incerteza do projeto até aqui. Se algum protocolo
específico falhar no teste, o framing/comando desse protocolo é o primeiro lugar a revisar.

## 64. Protocolo Trojan — implementação já existia (perdida na compactação), agora ligada de verdade

Ao começar a implementar o Trojan do zero, percebi que o backend completo (`TrojanModels.kt`,
`TrojanProtocol.kt`, `TrojanTransport.kt`, `TrojanUdpTransport.kt`, `TrojanTunnelManager.kt`) e as telas
(`TrojanScreen.kt`, `TrojanAddScreen.kt`) **já existiam no projeto** — trabalho de uma parte anterior desta
mesma conversa que não apareceu no resumo quando o histórico foi compactado. Revisei tudo com calma: a
implementação está correta e já segue a arquitetura mais recente (usa `onUdpAssociateRequest`, `bind()`
antes de `protect()`, etc.) — sem indício de estar desatualizada.

O que faltava de verdade era a **ligação no `MainActivity.kt`**: o `TrojanTunnelManager` já era criado e
passado como parâmetro, mas o `AppRoot`/`MainShell` ainda não tinham `trojanManager` nas suas assinaturas
(o projeto provavelmente nem compilava nesse estado — parâmetro nomeado sem correspondente na função).
Completado agora:
- `trojanManager` adicionado nas assinaturas do `AppRoot` e do `MainShell`
- `Screen.Trojan` / `Screen.TrojanAdd` adicionados ao sealed class de navegação, com os cases no `when`
- Trojan incluído no `LaunchedEffect` de roteamento da VPN (prioridade: depois do Shadowsocks)
- Trojan incluído nas contagens do Dashboard (conexões ativas, tráfego total) e como linha própria na
  lista de conexões, com clique levando pra tela dedicada
- Removido do `ManualProtocolOptions` (lista antiga de protocolos "genéricos ainda não implementados") —
  Trojan tem fluxo próprio (colar link `trojan://`), não deve cair no fluxo manual placeholder genérico

### Protocolo em si (resumo, pra quem for revisar)
Trojan é o mais simples dos 4: TCP + TLS puro, sem criptografia própria — a segurança inteira vem do TLS.
Cabeçalho inicial: SHA-224 da senha (hex, 56 chars) + CRLF + comando (0x01 Connect/0x03 UDP Associate) +
endereço+porta + CRLF, depois os dados fluem crus dentro do túnel TLS. UDP reaproveita o mesmo modelo do
Shadowsocks (uma conexão só, compartilhada, cada pacote se autodescreve) — só que aqui é uma conexão TLS
"fingindo" ser UDP, em vez de um socket UDP de verdade.

Ainda não testado em servidor real, como os outros três (VLESS/VMess/Shadowsocks em modo UDP).

## 65. Protocolo OpenVPN — arquitetura diferente dos outros 4 (processo real + Interface de Gerenciamento)

Diferente de SSH/VLESS/VMess/Shadowsocks/Trojan (implementações próprias em Kotlin), decidimos junto com o
usuário que OpenVPN **não deveria ser reimplementado do zero** — é um protocolo grande demais (handshake TLS
próprio, troca de chaves, múltiplas cifras) pra reescrever com segurança. Duas opções reais foram
discutidas, ambas copyleft (aviso de licença dado ao usuário): OpenVPN3 Core (AGPL-3.0, JNI) ou ics-openvpn
(GPL-2.0, controla o binário `openvpn` real como subprocesso). Usuário escolheu **ics-openvpn** pela
integração mais simples.

### Arquitetura (bem diferente dos outros 4)
O app não fala o protocolo OpenVPN — ele **controla o processo `openvpn` de verdade** (binário nativo, ver
`protocols/openvpn/README.md` pra compilar e incluir), rodando como subprocesso, conversando pela
**Interface de Gerenciamento** oficial do OpenVPN: um protocolo de texto por um socket Unix local. Duas
coisas exigem tratamento especial (só o app, dentro do VpnService, tem essas permissões): quando o processo
precisa abrir a TUN ou proteger seus próprios sockets, ele PARA e pergunta (`>NEED-OK:OPENTUN`/
`>NEED-OK:PROTECTFD`) — a resposta inclui o descritor de arquivo certo como dado auxiliar (ancillary data)
usando o suporte nativo do `LocalSocket` do Android (o mesmo mecanismo usado por qualquer app OpenVPN real,
incluindo o oficial).

Por depender do processo/subprocesso e de permissões só disponíveis dentro do `VpnService`, a conexão real
roda **dentro do `AutomBotVpnService`**, não no Manager (diferente dos outros 4, cuja conexão real roda no
próprio `*TunnelManager`, em escopo de Activity). Isso também significa que o OpenVPN **não participa do
roteamento automático** (o `LaunchedEffect` de prioridade entre protocolos) — conectar/desconectar é um
gesto explícito na própria tela, igual ao WireGuard, e substitui qualquer outra VPN ativa.

### Arquivos criados
- `protocols/openvpn/README.md` — passo a passo pra baixar/compilar o `ics-openvpn` e incluir o
  `libopenvpn.so` no projeto (só arm64-v8a por ora).
- `protocols/openvpn/OpenVpnModels.kt` — não faz parser do .ovpn (formato complexo demais) — salva o
  arquivo inteiro em disco e passa direto pro binário via `--config`.
- `protocols/openvpn/OpenVpnManagementClient.kt` — a peça mais complexa: inicia o processo, negocia a
  Interface de Gerenciamento, trata as queries NEED-OK (incluindo passagem de fd via `LocalSocket`), parseia
  `>STATE:`/`>BYTECOUNT:`.
- `protocols/openvpn/OpenVpnTunnelManager.kt` — só guarda perfis/status; recebe atualizações do Service via
  padrão de "instância ativa" (companion object).
- `ui/openvpn/OpenVpnScreen.kt` + `OpenVpnAddScreen.kt`.

### Arquivos alterados
- `core/AutomBotVpnService.kt`: extraída a criação da TUN+notificação numa função reaproveitável
  (`establishTunAndForeground()`), usada tanto pelo fluxo normal quanto pelo OpenVPN. Novo
  `ACTION_START_OPENVPN` + `startOpenVpn()`.
- `ui/MainActivity.kt`: `Screen.OpenVpn`/`Screen.OpenVpnAdd`, ligado no `AppRoot`/`MainShell`, dashboard,
  callback dedicado de conectar (`onStartOpenVpn`, não passa pelo roteamento automático).

### Bug real encontrado e corrigido nesta revisão
Durante a checagem final, encontrei um bug que eu mesmo tinha introduzido na Etapa 64 (Trojan): tinha
apagado sem querer o parâmetro `onRequestVpnPermission` da assinatura do `AppRoot` (substituí ele pelo
`trojanManager` em vez de adicionar os dois) — o projeto provavelmente não compilava desde aquela etapa.
Corrigido agora. Lição: ao adicionar um parâmetro novo no meio de uma assinatura longa, conferir a lista
INTEIRA depois, não só o trecho editado.

### Ainda não testado
Nunca testado contra o binário `openvpn` de verdade rodando — nem os comandos exatos de linha de comando
(flags do `ProcessBuilder`), nem a query NEED-OK, nem a passagem de fd via `LocalSocket`. Essa é a
implementação de maior risco entre todos os protocolos do projeto até agora, precisamente por depender de
mecanismos de baixo nível do Android (sockets Unix, passagem de descritor de arquivo) que não têm como ser
verificados sem rodar em aparelho real.

## 66. SlowDNS — nova camada independente do SSH (não é um protocolo separado)

Confirmado: "SlowDNS", no contexto de apps tipo HTTP Injector, é o nome popular do **dnstt** — ferramenta
de tunelamento por DNS mantida pelo próprio **Tor Project** (David Fifield, **domínio público** — licença
bem mais simples que o OpenVPN). Túnel de dados disfarçados como consultas DNS comuns, útil em redes onde
só DNS passa livre (planos "zero-rated").

### Por que virou uma camada do SSH, não um protocolo novo
O `dnstt-client` não tuneliza um protocolo específico — ele é um encaminhador de porta TCP puro: abre uma
porta LOCAL e encaminha qualquer conexão que chegar ali através do túnel DNS (tipo um `ssh -L`). Quem decide
pra onde a conexão vai de verdade é o `dnstt-server`, configurado no VPS. Isso bate exatamente com o
conceito de "camadas independentes" que o usuário pediu pro SSH lá no início da sessão (Proxy/Payload/
SSL-TLS) — SlowDNS entrou como mais uma camada, com uma particularidade: quando ligada, ela **substitui**
completamente a etapa de conexão direta/proxy (não faz sentido combinar com Proxy, já que o próprio dnstt já
"é" o transporte).

### Arquivos criados
- `protocols/slowdns/README.md` — passo a passo pra compilar o `dnstt-client` (Go puro, **não precisa do
  NDK** — só o compilador Go normal com `GOOS=android GOARCH=arm64 CGO_ENABLED=0`).
- `protocols/slowdns/SlowDnsClient.kt` — inicia o processo, espera a porta local começar a aceitar conexão,
  devolve a porta pronta pro SSH usar.

### Arquivos alterados
- `protocols/ssh/SshModels.kt`: novos campos (`useSlowDns`, `slowDnsDomain`, `slowDnsPubkey`,
  `slowDnsResolverMode` — UDP/DoH/DoT, `slowDnsResolver`).
- `protocols/ssh/SshTunnelManager.kt`: se `useSlowDns` ligado, sobe o `SlowDnsClient` ANTES do handshake SSH
  (precisa da porta local pronta primeiro), e o `ComposedSocket` conecta em `127.0.0.1:<porta local>` em vez
  do servidor real/proxy configurado — ignorando por completo a etapa de TCP direto/proxy quando SlowDNS
  está ativo.
- `ui/manual/SshConfigScreen.kt`: novo `ExpandableLayer` "SlowDNS", mesmo padrão visual das outras camadas.

### Dependência externa (fora do escopo do app)
SlowDNS **exige configuração do lado do servidor** (domínio com NS apontando pro VPS, `dnstt-server`
rodando com chave gerada) — sem isso, não tem como conectar, não importa o que o app faça. Documentado no
README.

### Ainda não testado
Mesma ressalva do OpenVPN: nunca testado contra o binário `dnstt-client` de verdade rodando — as flags de
linha de comando foram conferidas contra a documentação oficial, mas não contra o comportamento real.

## 67. Bug crítico real: motor nativo reiniciando sozinho a cada ~2 segundos

Usuário mandou Logcat real — revelou um padrão claro: `hev_socks5_tunnel_main_from_str` sendo iniciado e
parado repetidamente, a cada ~2 segundos, por dezenas de segundos seguidas, mesmo sem nenhuma ação do
usuário.

**Causa raiz**: em `AutomBotVpnService.startVpn()`, quando a VPN já estava ativa (`tunInterface != null`),
o código **sempre** parava e reiniciava o motor nativo, mesmo que a porta recebida fosse EXATAMENTE a mesma
de antes (introduzido na Etapa 62, ao trocar pro motor nativo). E essa função é chamada de novo toda vez
que QUALQUER `*TunnelManager` atualiza o contador de tráfego — o que acontece a cada ~2 segundos, sempre
que alguma conexão está ativa (todos os 5 gerenciadores têm esse loop de atualização). Isso muda o
`StateFlow` de conexões, que reaciona o `LaunchedEffect` de roteamento automático no `AppRoot`, que chama
`onStartSystemVpn(socksPort)` de novo — com a MESMA porta de sempre.

Resultado prático: **o túnel nunca ficava de pé por mais de ~2 segundos**, o tempo todo, enquanto qualquer
protocolo estivesse conectado. Isso explica de uma vez só: SSH "conecta mas não gera dado" (a conexão TCP
sendo carregada pelo túnel nunca tinha tempo de se estabilizar antes do próximo restart) e a instabilidade
geral de VLESS/VMess/Shadowsocks/Trojan.

**Correção**: novo campo `activeSocksPort` rastreando qual porta está realmente ativa no motor — o reinício
só acontece de verdade agora se a porta nova for DIFERENTE da que já está rodando. Chamadas repetidas com a
mesma porta (o caso comum, disparado pela atualização de tráfego) agora são ignoradas sem fazer nada.

### Ainda em aberto
Usuário relatou que VLESS/VMess estão marcando status "em breve" no app (parecia já estar funcionando antes)
— não deu pra confirmar a causa a partir deste log (não tem nenhuma tentativa de conexão VLESS/VMess nele).
Suspeita: mesma categoria de regressão introduzida por correções automáticas do Android Studio (como já
aconteceu na Etapa 65/66 com o `onRequestVpnPermission` apagado sem querer) — pode ser algo na integração
do dashboard/status. Pedido o projeto completo pra confirmar.

## 68. Bug crítico real #2: DNS nunca resolvia (dependia do UDP do protocolo ativo)

Usuário confirmou que a correção da Etapa 67 (loop de reinício) já estava no projeto que mandou, e mesmo
assim a VPN continuava "conectada mas sem internet". Analisando o projeto mais recente, achei a causa: na
migração pro motor nativo (Etapa 61), perdemos sem perceber a resolução de DNS direta e protegida que o
motor antigo (`Tun2SocksEngine.kt`) tinha embutida — DNS (porta 53) passou a ser tratado como qualquer outro
UDP, dependendo do `onUdpAssociateRequest` de cada protocolo pra funcionar. Isso é um problema sério porque
**SSH só cobre UDP na porta 443** (limitação real do protocolo, Etapa 59) — toda consulta DNS pra qualquer
outro destino falhava silenciosamente. Resultado: com SSH (o mais testado pelo usuário), o túnel podia até
estar de pé, mas **nenhum nome nunca era resolvido** — navegador/app mostra "sem internet" mesmo com a VPN
"conectada".

**Correção**: `Socks5Server.kt` agora resolve DNS (porta 53) **direto**, através de um socket UDP protegido
de verdade (`protect()`), ignorando completamente qual protocolo está ativo — antes de sequer consultar o
backend do protocolo (`onUdpAssociateRequest`). Isso é infraestrutura compartilhada (mesma classe usada
pelos 5 protocolos), então basta UM lugar corrigido pra resolver pra todos.

### Arquivos alterados
- `protocols/ssh/Socks5Server.kt`: novo parâmetro opcional `protectDatagramSocket`; porta 53 desvia pra
  `resolveDnsDirectly()` antes de qualquer outra lógica.
- `SshTunnelManager.kt`, `VmessTunnelManager.kt`, `ShadowsocksTunnelManager.kt`, `VlessTunnelManager.kt`,
  `TrojanTunnelManager.kt`: todos passam `protectDatagramSocket` na construção do `Socks5Server`.

### Nota lateral
Percebi que o `SshTunnelManager.kt` ganhou um recurso novo que não fui eu que escrevi — um "gateway de UDP"
configurável (`config.udpForwardEnabled`/`udpGatewayHost`/`udpGatewayPort`), alternativa mais geral ao
jeitinho de porta 443. Não mexi nisso, só me certifiquei de que minha correção de DNS não conflita (ela
intercepta ANTES de chegar nessa lógica, então é complementar).

Também notei que o arquivo do Trojan está com o nome `Trojantunnelmanager.kt` (minúsculo) no projeto do
usuário — renomeei pra `TrojanTunnelManager.kt` na cópia canônica local, mas não sei se isso causa problema
de verdade no Android Studio dele (Kotlin geralmente não exige que o nome do arquivo bata com o da classe,
mas vale conferir se não é sintoma de outra coisa).

## 69. Divergência de MTU entre a TUN real e o motor nativo (achado, não confirmado como causa)

Usuário confirmou: já atualizou o Android Studio, compilou com as correções da Etapa 67 (loop de reinício) e
68 (DNS) aplicadas, testou — e o app **continua travando do mesmo jeito**. Ou seja, essas duas correções
eram reais e válidas, mas não são (ou não são as únicas) explicações completas do problema.

Enquanto aguardo um log novo desse teste mais recente (ainda não recebido), revisei a config do motor nativo
e achei uma inconsistência real: `NativeTun2Socks.kt` configurava `mtu: 8500` pro motor (hev-socks5-tunnel),
enquanto a interface TUN de verdade (`AutomBotVpnService.kt`, `Builder().setMtu(1500)`) usa 1500 — o valor
8500 foi escolhido de forma arbitrária lá na Etapa 61 (migração pro motor nativo), sem confirmação contra
documentação real, e ficou assim sem revisão desde então. Corrigido pra 1500 em ambos os lugares,
consistente.

**Importante**: não tenho confirmação de que essa divergência é a causa do travamento atual — é uma
inconsistência real que encontrei por revisão de código, não por evidência de log. Pode ajudar, pode ser
irrelevante. Preciso de um log fresco do teste mais recente (pós Etapa 67+68) pra continuar investigando
com evidência de verdade, em vez de mais suposição.
## 70. BadVPN/UDPGW implementado corretamente — substitui protocolo inventado

Manual de conexões do AutomBot Core (finalizado pelo usuário) revelou que o backend já expõe um serviço
BadVPN/UDPGW dedicado especificamente pra resolver o problema de UDP em túneis TCP — a mesma categoria de
problema que vínhamos tentando resolver com o "jeitinho de porta 443" do SSH.

Ao investigar, descobri que **já existia uma tentativa de implementação** (`openUdpOverGateway`,
provavelmente do Agent do Studio) — mas ela citava um arquivo `PROTOCOL` que **não existe de verdade** no
repositório oficial do badvpn, e usava um formato de pacote (`[tipo][tamanho_host][host][porta]`,
baseado em nome de host) que não bate com o protocolo real (baseado em ID de conexão numérico + endereço
IP binário).

### Verificação contra a fonte real
Em vez de arriscar outro palpite, buscamos o código-fonte oficial DIRETO (`protocol/udpgw_proto.h` e
`protocol/packetproto.h` do github.com/ambrop72/badvpn, BSD-3-clause) via `curl`/`git clone` no ambiente
na nuvem do usuário. Confirmou:
- Framing externo (PacketProto): 2 bytes de tamanho **little-endian** + payload
- Payload: 1 byte flags + 2 bytes conid (LE) + (só na primeira mensagem de cada conid) endereço IPv4 (4
  bytes IP + 2 bytes porta, **ambos little-endian** — pegadinha real: é o oposto da convenção usual de
  rede/sockaddr, que é big-endian. Confirmar isso evitou um bug real.
- UMA conexão persistente multiplexa vários destinos diferentes por conid — não é "uma conexão nova por
  destino" como VLESS/VMess.

### Implementado
- `UdpGwClient.kt` (novo) — cliente completo do protocolo, com keepalive periódico, alocação de conid,
  multiplexação de sessões.
- `SshTunnelManager.kt`: `openUdpOverGateway` reescrita do zero pra usar o `UdpGwClient` compartilhado (um
  por conexão SSH, reaproveitado entre destinos diferentes) em vez do protocolo inventado anterior.
- UI (`SshConfigScreen.kt`) já existia (campos "Gateway UDP host/porta") — nenhuma mudança necessária ali.

### Lição sobre o SPEC.md
Durante essa correção, achei que minha própria cópia local do `SPEC.md` (usada internamente pra ir
documentando) tinha perdido o histórico acumulado (só 33 linhas) — a cópia real e completa estava no
projeto que o usuário mandou (1799 linhas, até a Etapa 69). Restaurei a partir dali antes de continuar.
Isso é um lembrete: o `SPEC.md` que vale sempre é o que está no projeto do usuário — minha cópia de
trabalho é só um cache, que pode ficar desatualizado ou se perder ao longo de uma sessão tão longa quanto
essa.

Ainda não testado contra um `badvpn-udpgw` de verdade rodando no VPS — mas agora com base em especificação
oficial confirmada, não em suposição.

## 71. Correção rápida: erro de compilação real no UdpGwClient (suspend fun)

Usuário compilou no ambiente na nuvem e pegou um erro real que eu introduzi na Etapa 70: fiz
`openUdpOverGateway` virar uma função comum com `@Synchronized` (pra evitar criar dois `UdpGwClient` ao
mesmo tempo por engano), mas ela chama `openDirectChannel`, que é `suspend fun` — `@Synchronized` é da
JVM/threads, não funciona (nem devia ser usado) dentro de uma função suspensa em Kotlin.

Corrigido: `openUdpOverGateway` voltou a ser `suspend fun`, e a proteção contra criação duplicada agora usa
`Mutex` (de `kotlinx.coroutines.sync`, o jeito certo de fazer exclusão mútua em código suspenso) em vez de
`@Synchronized`.

## 72. Bloqueio de orientação + pedido de isenção de otimização de bateria

Usuário reportou dois problemas novos:

1. **Rotação da tela desconecta tudo e limpa o tráfego/ping** — a Activity é destruída e recriada por
   padrão em qualquer rotação, e como o `screen` de navegação (e por extensão, os *TunnelManager,
   instanciados no `onCreate()`) usa estado em memória comum, isso perde as conexões ativas. Corrigido no
   `AndroidManifest.xml`: `android:screenOrientation="portrait"` (trava a orientação, exatamente o pedido)
   + `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode"` como proteção
   extra (mesmo que a orientação mude no futuro por algum motivo, a Activity não é mais recriada por causa
   dessas mudanças específicas — ela só recebe um callback, sem perder estado).

2. **Conexão trava assim que o usuário sai do app** (funciona enquanto o app está em primeiro plano,
   trava ao trocar pra outro app/navegador) — investigado: nada no nosso próprio código explica isso
   (sem `onPause`/`onStop` desconectando nada), e a declaração do serviço em primeiro plano no manifesto já
   está correta (`foregroundServiceType="specialUse"` + a property exigida pelo Android 14+). Explicação
   mais provável: gerenciador de bateria PRÓPRIO do fabricante (comum em Motorola) matando o processo em
   segundo plano mesmo com tudo declarado certo. Adicionado pedido automático de isenção da otimização de
   bateria PADRÃO do Android (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, disparado no `onCreate` se
   ainda não isento) — resolve a camada padrão, mas o Motorola pode ter uma tela PRÓPRIA e separada de
   gerenciamento de bateria que precisa de ajuste manual adicional, fora do alcance do código sozinho.
