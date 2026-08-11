# AutomBot Network Probe

Aplicativo Android separado para medir a capacidade real da rede física antes de escolher um transporte no AutomBot Connect.

## Objetivo

O probe testa **somente endpoints informados pelo operador**. Ele não procura domínios de terceiros, exceções de cobrança, zero-rating ou formas de obter acesso não autorizado.

## Testes da v0.3

- Detecta Wi‑Fi / rede móvel e evita usar uma VPN já ativa como rede de teste.
- Exibe validação da rede, interface, MTU, DNS, IPv4/IPv6 e indício de CGNAT/NAT privado.
- Resolve A/AAAA explicitamente pela rede física Android.
- Faz matriz de portas TCP configuráveis e diferencia `timeout` de `connection refused`.
- Quando há A e AAAA, testa também o TCP principal pelo IPv6 para comparar as famílias.
- Faz handshake TLS/SNI com validação do certificado.
- Faz requisição HTTPS e tenta upgrade WebSocket TLS no path configurado.
- Faz matriz de portas UDP configuráveis.
- Gera pontuação de capacidade, candidatos de transporte, diagnóstico contextual e relatório JSON.

## Proxy Analyzer

A v0.3 inclui o **AutomBot Proxy Analyzer**:

- Testa um proxy HTTP CONNECT ou SOCKS5 informado pelo operador.
- Valida DNS e TCP do proxy pela rede física.
- Possui detecção opcional de uma lista curta de portas comuns de proxy: `80, 443, 1080, 3128, 8080, 8000, 8118, 8888, 8889, 9090`.
- Testa HTTP CONNECT até um endpoint da própria infraestrutura.
- Testa SOCKS5 CONNECT e SOCKS5 UDP ASSOCIATE.
- Quando o túnel TCP é confirmado, testa TLS real através do proxy.
- Também tenta WebSocket Seguro (WSS) através do proxy usando o path informado.
- Gera automaticamente um **manual de conexão** com servidor, porta, proxy, porta do proxy, opções TLS/WSS e um modelo HTTP CONNECT dirigido somente ao endpoint testado.
- O manual pode ser compartilhado diretamente como texto e o relatório técnico continua disponível em JSON.

O manual não inventa domínios de fachada nem sugere terceiros para contornar políticas de rede. Ele só usa o proxy e o destino explicitamente informados no teste e diferencia capacidade confirmada de simples compatibilidade de transporte.

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
- Exportação de um perfil de transporte recomendado para o AutomBot Connect.
