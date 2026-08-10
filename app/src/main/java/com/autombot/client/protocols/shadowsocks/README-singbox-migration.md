# Shadowsocks runtime via sing-box

O Shadowsocks do AutomBot continua usando o mesmo perfil `ss://`, tela e fluxo do painel.
O motor interno passa a usar o sing-box empacotado no APK:

`TUN -> HEV/tun2socks -> mixed local -> sing-box -> Shadowsocks remoto`

Motivação: o transporte Kotlin anterior acumulava relays do `Socks5Server` por 30s após EOF em navegação concorrente/Speedtest. O sing-box assume TCP, UDP e AEAD do protocolo.

A configuração gerada mantém os métodos já aceitos pelo app (`chacha20-ietf-poly1305`, `aes-256-gcm`, `aes-128-gcm`) e resolve previamente o host do servidor pela rede Android subjacente quando necessário.
