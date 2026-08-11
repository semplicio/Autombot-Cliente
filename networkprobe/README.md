# AutomBot Network Probe

Aplicativo Android separado para medir a capacidade real da rede física antes de escolher um transporte no AutomBot Connect.

## Objetivo

O probe testa **somente endpoints informados pelo operador**. Ele não procura domínios de terceiros, exceções de cobrança, zero-rating ou formas de obter acesso não autorizado.

## Testes da v0.2

- Detecta Wi‑Fi / rede móvel e evita usar uma VPN já ativa como rede de teste.
- Exibe validação da rede, interface, MTU, DNS, IPv4/IPv6 e indício de CGNAT/NAT privado.
- Resolve A/AAAA explicitamente pela rede física Android.
- Faz matriz de portas TCP configuráveis e diferencia `timeout` de `connection refused`.
  - `connection refused`: o host respondeu, mas a porta TCP está fechada/sem serviço.
  - `timeout`: pode indicar filtragem, rota indisponível ou ausência de resposta.
- Quando há A e AAAA, testa também o TCP principal pelo IPv6 para comparar as famílias.
- Faz handshake TLS/SNI com validação do certificado, versão TLS, validade e ALPN quando disponível.
- Faz requisição HTTPS e registra código/protocolo HTTP.
- Tenta upgrade WebSocket TLS no path configurado.
- Faz matriz de portas UDP configuráveis; qualquer resposta recebida confirma caminho UDP bidirecional naquela porta.
- Vem com portas úteis da infraestrutura AutomBot como referência: TCP 80/109/2222/8080/8443 e UDP 36712/44300/51820.
- Gera pontuação de capacidade, candidatos de transporte, diagnóstico contextual e relatório JSON.

## Interpretação do UDP

UDP não possui handshake genérico. Se o probe recebe uma resposta, o caminho bidirecional naquela porta está confirmado. Se não recebe resposta, o resultado permanece `PARCIAL`, porque um serviço como Hysteria2, TUIC ou WireGuard pode simplesmente ignorar um payload que não pertence ao protocolo.

Para confirmação determinística de uma porta UDP de diagnóstico, pode ser criado posteriormente um pequeno endpoint AutomBot UDP probe/echo controlado pela própria infraestrutura.

## Como usar

Use como TCP principal a porta do serviço que realmente fala TCP/TLS/WSS. Para Hysteria2/TUIC/WireGuard, coloque as portas correspondentes na matriz UDP em vez de interpretar uma porta UDP como TCP.

Exemplo:

```text
Host: core.infinitenet.net
TCP principal: 443
UDP principal: 443
TCP extras: 80,109,2222,8080,8443
UDP extras: 36712,44300,51820
WebSocket path: /
```

Compare o mesmo conjunto de testes no Wi‑Fi e na rede móvel.

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
