# AutomBot Core Network Advisor — v0.7

O teste completo do perfil vinculado agora funciona também como consultor de ajuste do AutomBot Core.

## O que mudou

- remove duplicatas semânticas do plano de teste, mesmo quando o mesmo serviço veio do módulo específico e do fallback do dashboard;
- `websocket` só recebe `OK` depois de handshake HTTP Upgrade `101`; TCP aberto sozinho não confirma WebSocket;
- para TCP configurado, repete o teste 3 vezes por IP resolvido;
- testa até 4 endereços A/AAAA retornados para o mesmo host, útil para CDN/edge;
- mantém UDP como parcial quando o protocolo ignora o payload genérico;
- testa um catálogo limitado de portas TCP padrão somente contra a VPS vinculada;
- detecta porta reservada pelo perfil, porta com listener desconhecido, caminho alcançável sem listener, instabilidade e timeout;
- gera `automcore_optimization_plan` no JSON e um plano legível no compartilhamento.

## Catálogo TCP padrão

`22, 80, 109, 443, 1080, 1194, 2222, 3128, 8000, 8080, 8081, 8118, 8443, 8888, 9443`

Esse catálogo não é uma varredura ampla. Ele é fixo e usado somente contra o endpoint da VPS presente no perfil AutomBot Core salvo no aparelho.

## Interpretação das portas candidatas

- `RESERVED`: o perfil já declara um serviço TCP naquela porta; não reutilizar para outro daemon sem arquitetura explícita de reverse proxy.
- `AVAILABLE_REACHABLE`: a tentativa recebeu `connection refused` de forma consistente. Isso demonstra que o caminho TCP chegou ao host e não há listener identificado pelo perfil; é uma boa candidata para adicionar um listener alternativo e retestar.
- `OPEN_UNKNOWN`: existe um listener que o perfil não atribuiu; tratar como conflito potencial.
- `UNSTABLE`: resultado misto entre as três tentativas.
- `UNREACHABLE`: timeouts consistentes; não recomendar naquela rede.

## Política de alteração sugerida

O Advisor nunca recomenda remover primeiro a porta atual. A sequência é:

1. adicionar a porta alternativa;
2. verificar conflito de listener;
3. repetir o teste na mesma operadora;
4. exigir o handshake aplicável (`SSH banner`, `WS/WSS 101`, `TLS`, `HTTP CONNECT`, `SOCKS5`);
5. somente depois promover a nova porta e, se desejado, retirar a antiga.

Para UDP, a versão 0.7 ainda não recomenda troca automática de porta de Hysteria2, TUIC, WireGuard ou OpenVPN UDP sem handshake determinístico do protocolo.
