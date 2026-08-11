# AutomBot Network Probe — vínculo com AutomBot Core

## Fluxo

1. No Network Probe, abra **Vincular / Perfil AutomBot Core**.
2. Informe a URL da plataforma/API e o token administrativo da VPS.
3. O app usa o token administrativo somente para `POST /v1/probe/token`.
4. O Core devolve um token limitado a `probe:read` e o app baixa `GET /v1/probe/profile`.
5. O token administrativo é descartado e nunca é persistido.
6. O token `probe:read` fica cifrado pelo Android Keystore.
7. O perfil sanitizado (hosts, portas, transportes, TLS/SNI próprio e paths) fica salvo localmente.
8. Depois disso é possível desligar o Wi-Fi, usar 4G/5G e tocar em **Testar configuração salva nesta rede**.

## O que fica salvo

- URL da plataforma/API;
- nome e versão do perfil;
- IP público informado pelo Core;
- protocolos configurados;
- hosts/endpoints;
- portas TCP/UDP;
- transporte (TCP/UDP/WebSocket);
- TLS, SNI do próprio endpoint e WebSocket path quando aplicável;
- token `probe:read` cifrado.

Não são salvos token administrativo, UUID/senha de clientes, chaves privadas WireGuard, senha Hysteria/TUIC ou outras credenciais de usuários.

## Perfis com mais de um endpoint

Uma infraestrutura pode usar um domínio/CDN para Xray e outro endpoint direto para SSH/UDP. O armazenamento local mantém todos os protocolos. A tela principal preenche somente as portas pertencentes ao endpoint selecionado para evitar testar, por exemplo, uma porta SSH do IP da VPS contra o domínio da CDN.

## API esperada

```text
POST /v1/probe/token
Authorization: Bearer <token-administrativo>

GET /v1/probe/profile
X-AutomBot-Probe-Token: <token-probe-read>
```

O token de diagnóstico expira e pode ser renovado fazendo o vínculo novamente. O perfil já salvo continua disponível para testes locais mesmo quando a plataforma não estiver acessível.
