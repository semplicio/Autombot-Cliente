# AutomBot Network Probe

Aplicativo Android separado para medir a capacidade real da rede física antes de escolher um transporte no AutomBot Connect e para transformar o diagnóstico em recomendações operacionais para o AutomBot Core.

## Objetivo

O probe testa **somente endpoints informados pelo operador**. Ele não procura domínios de terceiros, exceções de cobrança, zero-rating ou formas de obter acesso não autorizado.

## Testes da v0.4

### Teste dedicado CDN HTTP/80 + TLS/443

- Executa WS sem TLS na porta 80 e WSS com TLS/SNI na porta 443 para o mesmo FQDN.
- Aceita vários paths na mesma execução, por exemplo `/vmess,/vless,/trojan`.
- Resolve o domínio pela rede física escolhida e testa cada IP retornado separadamente.
- Registra a etapa exata do timeout e os headers `HTTP`, `Location`, `Server`, `Via`, `X-Cache`, `Connection`, `Upgrade` e `Sec-WebSocket-Accept`.
- Distingue upgrade `101`, redirect HTTP→HTTPS no mesmo domínio, redirect para portal da operadora, timeout e resposta HTTP sem upgrade.
- Permite prender os sockets diretamente à interface celular para repetir o comportamento observado no AutomBot Connect sem passar por Wi-Fi ou VPN.

- Detecta Wi‑Fi / rede móvel e evita usar uma VPN já ativa como rede de teste.
- Exibe validação da rede, interface, MTU, DNS, IPv4/IPv6 e indício de CGNAT/NAT privado.
- Resolve A/AAAA explicitamente pela rede física Android.
- Faz matriz de portas TCP configuráveis e diferencia `timeout` de `connection refused`.
- Quando há A e AAAA, testa também o TCP principal pelo IPv6 para comparar as famílias.
- Faz handshake TLS/SNI com validação do certificado.
- Faz requisição HTTPS e tenta upgrade WebSocket TLS no path configurado.
- Faz matriz de portas UDP configuráveis.
- Gera pontuação de capacidade, candidatos de transporte e diagnóstico contextual.
- Ao exportar o relatório, gera também um **Plano de Infraestrutura AutomCore**.
- Gera um **Manual de Conexão AutomBot Connect** com perfis confirmados/candidatos.
- O manual inclui orientação de payload somente para o próprio endpoint testado e separa payload direto, WebSocket nativo e HTTP CONNECT via Proxy Analyzer.
- Quando o caminho direto principal falha, mas portas HTTP respondem, o plano marca CDN/edge como **teste adicional** para HTTP(S)/WS/WSS, sem tratar CDN como solução automática para SSH bruto ou UDP.

## Plano de Infraestrutura AutomCore

O relatório exportado passa a incluir `automcore_plan`. Essa seção transforma a matriz observada em um plano de configuração para a VPS, por exemplo:

- portas HTTP/WS que responderam ficam como **candidatas** para front door WebSocket;
- WSS só aparece como **confirmado** quando o handshake WebSocket TLS realmente passou;
- portas SSH abertas ficam como candidatas até o handshake SSH real ser validado;
- UDP só é promovido quando existe resposta determinística;
- portas típicas de proxy só são sugeridas para Proxy Analyzer, nunca assumidas como proxy apenas porque o TCP abriu;
- CDN/edge é sugerida apenas quando existe motivo de rota/alcance e somente para transportes HTTP(S)/WS/WSS compatíveis com o provedor escolhido.

## Manual AutomBot Connect

O relatório exportado também inclui `autombot_connect_manual` com os campos operacionais do cliente:

- servidor;
- porta;
- TLS;
- SNI/Server Name;
- WebSocket;
- WS Host;
- WS Path;
- proxy/payload quando realmente aplicável;
- classificação `CONFIRMADO`, `CANDIDATO` ou `NÃO RECOMENDADO`.

### Payload

O Network Probe não gera domínio de fachada de terceiros. Quando existe uma porta HTTP candidata, o manual pode gerar um modelo dirigido ao **mesmo host do operador** para validar um front door HTTP/WebSocket configurado no AutomCore.

Para WebSocket padrão, o manual lembra que `Sec-WebSocket-Key` precisa ser gerado dinamicamente. Se o campo de payload do cliente não faz isso, o modo recomendado é usar o transporte WebSocket nativo do AutomBot Connect e deixar payload personalizado desligado.

Para **SSH + HTTP Proxy / HTTP CONNECT**, o relatório principal não inventa um proxy. Esse payload só é gerado no **Proxy Analyzer** depois que HTTP CONNECT é realmente confirmado contra o proxy e o destino informados pelo operador.

## Proxy Analyzer

A v0.4 mantém o **AutomBot Proxy Analyzer**:

- Testa um proxy HTTP CONNECT ou SOCKS5 informado pelo operador.
- Valida DNS e TCP do proxy pela rede física.
- Possui detecção opcional de uma lista curta de portas comuns de proxy: `80, 443, 1080, 3128, 8080, 8000, 8118, 8888, 8889, 9090`.
- Testa HTTP CONNECT até um endpoint da própria infraestrutura.
- Testa SOCKS5 CONNECT e SOCKS5 UDP ASSOCIATE.
- Quando o túnel TCP é confirmado, testa TLS real através do proxy.
- Também tenta WebSocket Seguro (WSS) através do proxy usando o path informado.
- Gera automaticamente um **manual de conexão** com servidor, porta, proxy, porta do proxy, opções TLS/WSS e um modelo HTTP CONNECT dirigido somente ao endpoint testado.
- O manual pode ser compartilhado diretamente como texto e o relatório técnico continua disponível em JSON.

## Compartilhamento

O botão de compartilhar continua anexando o arquivo JSON via `FileProvider`, mas a mensagem enviada junto ao arquivo agora contém também, em texto legível:

- Plano AutomCore;
- Manual AutomBot Connect;
- Manual do Proxy Analyzer, quando aplicável.

Isso facilita o envio pelo WhatsApp sem depender de abrir o JSON manualmente.

## Interpretação do UDP

UDP não possui handshake genérico. Se o probe recebe uma resposta, o caminho bidirecional naquela porta está confirmado. Se não recebe resposta, o resultado permanece `PARCIAL`, porque um serviço como Hysteria2, TUIC ou WireGuard pode simplesmente ignorar um payload que não pertence ao protocolo.

Para confirmação determinística de uma porta UDP de diagnóstico, pode ser criado posteriormente um pequeno endpoint AutomBot UDP probe/echo controlado pela própria infraestrutura.

## Como usar

Para o Network Probe principal, use como TCP principal a porta do serviço que realmente fala TCP/TLS/WSS. Para Hysteria2/TUIC/WireGuard, coloque as portas correspondentes na matriz UDP.

Exemplo:

```text
Host: core.infinitenet.net
TCP principal: 443
UDP principal: 443
TCP extras: 80,109,2222,8080,8443
UDP extras: 36712,44300,51820
WebSocket path: /
```

No Proxy Analyzer, informe o proxy a testar e um endpoint da sua VPS como destino. Se a porta do proxy for desconhecida, use **Detectar porta comum do proxy** e depois execute a análise completa.

## Compilar

Na raiz do repositório:

```bash
./gradlew :networkprobe:assembleDebug
```

APK esperado:

```text
networkprobe/build/outputs/apk/debug/networkprobe-debug.apk
```

## Próximas evoluções

- Cadastro de `probe endpoints` no AutomBot Core e download automático dessa lista pelo app.
- Histórico local por rede/operadora para comparar Wi‑Fi x 4G/5G.
- Testes reais de handshake dos protocolos AutomBot (Hysteria2, TUIC, WireGuard, VMess/VLESS, SSH/OpenVPN) sem ativar a VPN de sistema.
- Endpoint AutomBot Probe Server próprio para TCP/UDP com respostas determinísticas e baixa amplificação.
- Exportação/importação direta do perfil recomendado para o AutomBot Connect.
