# AutomBot Network Probe

Aplicativo Android separado para medir a capacidade real da rede física antes de escolher um transporte no AutomBot Connect.

## Objetivo

O probe testa **somente endpoints informados pelo operador**. Ele não procura domínios de terceiros, exceções de cobrança, zero-rating ou formas de obter acesso não autorizado.

## Testes da v0.1

- Detecta Wi‑Fi / rede móvel e evita usar uma VPN já ativa como rede de teste.
- Resolve A/AAAA usando explicitamente a rede física Android.
- Testa abertura TCP na porta configurada.
- Faz handshake TLS com validação de certificado e SNI do domínio original.
- Faz requisição HTTPS.
- Tenta upgrade WebSocket TLS no path configurado.
- Envia probe UDP e confirma tráfego bidirecional quando existe um endpoint echo/probe compatível.
- Gera recomendação de transporte e exporta relatório JSON.

## Limitação importante do UDP

UDP não possui handshake. Enviar um datagrama sem receber resposta não prova que a operadora bloqueou UDP: Hysteria2, TUIC e outros serviços ignoram payloads que não pertencem ao protocolo. Para transformar o teste UDP em confirmação real, use um pequeno endpoint UDP echo/probe controlado pela infraestrutura AutomBot.

## Compilar

Na raiz do repositório:

```bash
./gradlew :networkprobe:assembleDebug
```

APK esperado:

```text
networkprobe/build/outputs/apk/debug/networkprobe-debug.apk
```

## Próxima etapa sugerida

Adicionar ao AutomBot Core um cadastro de `probe endpoints` contendo host, porta TCP/TLS, path WSS e porta UDP echo. O Network Probe poderá baixar essa lista e comparar automaticamente os endpoints autorizados antes de sugerir o transporte.
