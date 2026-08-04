# SlowDNS (dnstt) — binário nativo, passo a passo

O SlowDNS que os apps de bypass usam é o **dnstt**, ferramenta mantida pelo próprio
Tor Project (David Fifield, domínio público — sem restrição de licença, diferente do
OpenVPN). É bem mais simples de integrar: não precisa de TUN nem de protocolo de
gerenciamento — o `dnstt-client` só abre uma porta TCP local e encaminha qualquer
conexão pra lá através do túnel DNS (o SSH conecta nessa porta local em vez do
servidor direto).

**Vantagem grande**: é escrito em Go puro (sem C/C++), então compilar pra Android
**não precisa do NDK** — só do compilador Go normal, com as variáveis de ambiente
certas.

## Passo 1 — instalar o Go (se ainda não tiver)

https://go.dev/dl/ — qualquer versão recente serve.

## Passo 2 — baixar e compilar

```bash
git clone https://github.com/gh4rib/dnstt
# (mirror do repositório oficial, que fica em www.bamsoftware.com/git/dnstt.git —
# esse mirror no GitHub é só mais fácil de clonar)

cd dnstt/dnstt-client
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -o libdnstt.so
```

Isso gera o binário já com o nome certo (`libdnstt.so`) — só arm64-v8a por enquanto
(cobre a esmagadora maioria dos aparelhos reais).

## Passo 3 — copiar pro projeto

```
app/src/main/jniLibs/arm64-v8a/libdnstt.so
```

(mesma pasta onde o `libopenvpn.so` também fica, se você já tiver feito aquela
etapa — cada binário na sua própria linha, sem conflito.)

## Passo 4 — dados que você precisa ter em mãos, do lado do servidor

O SlowDNS depende de configuração feita **no VPS** (fora do escopo do app):
1. Um domínio (ou subdomínio) com um registro NS apontando pro IP do seu VPS —
   é esse domínio que vira o "DOMAIN" no app.
2. Rodar `dnstt-server -gen-key` no VPS pra gerar a chave privada (fica no
   servidor) e a chave pública (essa você cola no app).
3. Rodar o `dnstt-server` de verdade, apontando pro serviço real que ele deve
   encaminhar (tipicamente a porta do SSH local do VPS).

Sem isso configurado do lado do servidor, o app não tem como conectar — o SlowDNS
não é algo que só o app resolve sozinho.

## Se algo não bater

Assim como no OpenVPN, as flags exatas de linha de comando (`-udp`/`-doh`/`-dot`,
`-pubkey`, etc.) foram conferidas contra a documentação oficial, mas nunca testadas
contra o binário rodando de verdade. Se o processo morrer logo ao iniciar, o log dele
(repassado pro `AppLog` do app) geralmente é bem claro sobre o motivo — confere ali
primeiro.