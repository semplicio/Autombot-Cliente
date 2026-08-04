# Motor de tun2socks nativo — passo a passo pra deixar pronto pra compilar

Essa pasta contém a ponte JNI própria do AutomBot Connect (`autombot_tun2socks_jni.c` +
`CMakeLists.txt`), mas ela sozinha **não compila** — falta baixar e pré-compilar a
biblioteca de terceiros `hev-socks5-tunnel`
(https://github.com/heiher/hev-socks5-tunnel), que é o motor tun2socks de verdade
(o mesmo tipo de biblioteca usado por apps como NekoBox e Matsuri).

Isso só precisa ser feito **uma vez** (o resultado fica na pasta, versionado ou não
como você preferir — se for versionar no git, considere `.gitignore` no `bin/` e
`third-part/` do hev-socks5-tunnel pra não inflar o repositório).

## Passo 1 — baixar o código-fonte

Abra o terminal do Android Studio (ou qualquer terminal) **dentro desta pasta**
(`app/src/main/cpp/`) e rode:

```bash
git clone --recursive https://github.com/heiher/hev-socks5-tunnel
```

Isso cria a pasta `hev-socks5-tunnel/` aqui dentro, com todo o código C e as
dependências dele (lwip, yaml, hev-task-system) como submódulos.

## Passo 2 — compilar como biblioteca estática, usando o toolchain do NDK

Você precisa saber onde o NDK está instalado na sua máquina — normalmente aparece em
**Android Studio → Settings → Languages & Frameworks → Android SDK → SDK Tools → NDK**,
ou no arquivo `local.properties` do projeto (campo `ndk.dir`).

Com isso em mãos, ainda dentro de `hev-socks5-tunnel/`, defina as variáveis do
toolchain (ajuste `NDK_HOME` e `HOST_TAG` pro seu sistema — `linux-x86_64`,
`darwin-x86_64` no Mac Intel, `darwin-arm64` no Mac com Apple Silicon, ou
`windows-x86_64` no Windows):

```bash
export NDK_HOME=/caminho/pro/seu/Android/Sdk/ndk/<versao>
export HOST_TAG=linux-x86_64
export TOOLCHAIN=$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG
export API=26
export CC=$TOOLCHAIN/bin/aarch64-linux-android$API-clang
export AR=$TOOLCHAIN/bin/llvm-ar

make CC="$CC" AR="$AR" CFLAGS="-fPIC" static
```

Isso cobre só o **arm64-v8a** (a arquitetura de praticamente todo celular Android
vendido nos últimos ~6 anos) — suficiente pra testar e usar de verdade. Se um dia
precisar suportar aparelhos mais antigos (armeabi-v7a) ou rodar no emulador x86_64,
repita o passo 2 trocando `aarch64-linux-android` pelo triplo do alvo
(`armv7a-linux-androideabi` / `x86_64-linux-android`) e organizando os `.a`
resultantes em pastas separadas por ABI (o `CMakeLists.txt` precisaria ser ajustado
pra isso — não mexi nele ainda porque não sabemos se você vai precisar).

## Passo 3 — conferir se o resultado bateu com o que o CMakeLists.txt espera

Depois do `make static`, confira se existe o arquivo:

```
app/src/main/cpp/hev-socks5-tunnel/bin/libhev-socks5-tunnel.a
```

Se o `make` gerar **mais de um** `.a` (por exemplo, um pra cada dependência interna,
em `third-part/lwip/bin/`, `third-part/yaml/bin/`, etc.), me avisa — o
`CMakeLists.txt` que já deixei pronto tem um comentário mostrando onde adicionar os
outros.

## Passo 4 — conferir a assinatura de `hev_socks5_tunnel_stats()`

Abre `hev-socks5-tunnel/src/hev-main.h` depois de clonado e procura a declaração
dessa função. O `autombot_tun2socks_jni.c` que escrevi assume a assinatura
`void hev_socks5_tunnel_stats(size_t *tx, size_t *rx)` — é a mais provável pelo
padrão do resto da API, mas não bati o olho no cabeçalho real ainda (não tenho
acesso à internet no meu ambiente). Se for diferente, me manda o que encontrar lá que
eu ajusto a ponte JNI na hora.

## Depois de tudo isso pronto

É só compilar o projeto normal no Android Studio — o Gradle vai chamar o CMake
sozinho (já configurei isso no `build.gradle.kts`). Se der erro de compilação C,
me manda a mensagem completa.