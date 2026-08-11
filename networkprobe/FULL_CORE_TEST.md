# Teste completo do AutomBot Core

A partir da v0.6.0 o Network Probe possui dois modos para um perfil sincronizado do AutomBot Core:

1. **Abrir teste manual com perfil carregado** — mantém a tela tradicional de um endpoint por vez.
2. **Executar teste completo do Core** — percorre todas as combinações válidas que vieram do perfil salvo.

O teste completo não faz produto cartesiano entre portas e protocolos incompatíveis. Ele preserva a relação que veio do Core:

- protocolo;
- endpoint público;
- porta ou portas configuradas;
- TCP/UDP;
- TLS/SNI quando aplicável;
- WebSocket + path quando aplicável;
- endpoint de origem quando o Xray está atrás de CDN e o Core informou a origem.

Exemplos de combinações distintas:

```text
VMess   + CDN público + 443   + WSS + /vmess
VLESS   + CDN público + 443   + WSS + /vless
Trojan  + CDN público + 443   + WSS + /trojan
SSH     + IP da VPS   + 109   + TCP
SSH     + IP da VPS   + 2222  + TCP
TUIC    + domínio     + 44300 + UDP
Hysteria2 + domínio   + 36712 + UDP
WireGuard + endpoint  + 51820 + UDP
HTTP Proxy + VPS      + porta configurada + HTTP CONNECT
SOCKS5 + VPS          + porta configurada + negociação SOCKS5
```

## Camadas verificadas

- DNS do endpoint pela rede física Android;
- TCP para transportes TCP;
- banner SSH nas portas SSH;
- TLS/SNI quando a configuração declara TLS;
- handshake WS/WSS no path importado do Core;
- saudação/CONNECT SOCKS5 quando possível;
- HTTP CONNECT do proxy até outro endpoint pertencente ao próprio perfil AutomBot Core;
- envio UDP e detecção de resposta para transportes UDP.

Ausência de resposta a um datagrama genérico continua sendo classificada como **PARCIAL**, e não como falha definitiva de Hysteria2/TUIC/WireGuard/OpenVPN UDP, pois esses serviços podem ignorar payloads desconhecidos.

## Relatórios

A tela de teste completo gera:

- resumo por rede/operadora;
- contagem OK / PARCIAL / FALHA;
- resultado separado para cada combinação configurada;
- detalhes de cada camada;
- relatório de texto para compartilhar;
- JSON completo.

O teste usa somente endpoints sincronizados do AutomBot Core e não procura hosts ou portas de terceiros.
