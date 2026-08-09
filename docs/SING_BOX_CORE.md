# Núcleo sing-box — Hysteria2 / TUIC

Esta etapa adiciona ao AutomBot Connect um núcleo moderno compartilhado para protocolos baseados em QUIC. O APK não reimplementa Hysteria2 nem TUIC em Kotlin: ele executa o CLI oficial do sing-box localmente e usa um inbound `mixed` em `127.0.0.1`.

Fluxo:

```text
Android VpnService/TUN
        |
        v
hev-socks5-tunnel
        |
        v
127.0.0.1:<porta mixed do sing-box>
        |
        +--> Hysteria2 (QUIC/UDP)
        |
        `--> TUIC v5 (QUIC/UDP)
```

O `AutomBotVpnService` já exclui o próprio pacote Android do roteamento do TUN. Assim os sockets externos abertos pelo processo sing-box saem pela rede real em vez de voltarem para a própria VPN.

## Versão fixada

Primeira integração: sing-box `1.13.18`, versão estável do upstream usada durante o desenvolvimento desta branch.

O binário não é salvo no Git. Antes do build:

```bash
bash scripts/fetch_singbox_android_core.sh
```

O script baixa o artefato Android arm64 da release oficial e o instala em:

```text
app/src/main/jniLibs/arm64-v8a/libsingbox.so
```

O nome `.so` é usado para que o Android empacote/extrai o executável no `nativeLibraryDir`; o código não chama `System.loadLibrary()` para esse arquivo.

## Build

```bash
./gradlew clean :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libsingbox
```

## Formatos aceitos

### Hysteria2

O app importa `hysteria2://` (ou `hy2://`). Quando o servidor oficial usa autenticação `userpass`, o sing-box recebe a combinação `usuario:senha` no campo `password` do outbound. Salamander obfs é aplicado quando o link contém `obfs=salamander&obfs-password=...`.

### TUIC

O app importa `tuic://uuid:senha@host:porta?...`, mantendo SNI, ALPN e `congestion_control`; `udp_relay_mode` é `native` e 0-RTT permanece desativado.

## Segurança

- TLS é validado por padrão (`insecure=false`).
- Não registrar URI/config completa no AppLog, pois contém credenciais.
- O JSON temporário do sing-box é removido depois que o proxy local sobe.
- Os perfis seguem, por enquanto, o mesmo padrão de persistência usado pelos outros managers do app; migração para armazenamento criptografado pode ser feita separadamente.

## Licença / distribuição

O sing-box upstream é distribuído sob GPLv3. Antes de publicar um APK que inclua o binário, revisar as obrigações de licença e distribuição do produto. Esta branch mantém o binário fora do histórico Git de propósito e fornece apenas o script de obtenção do artefato oficial.
