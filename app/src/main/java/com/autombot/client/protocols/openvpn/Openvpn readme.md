# OpenVPN — binário nativo real, passo a passo

Diferente dos outros 4 protocolos (que são conexões que o próprio app faz), o OpenVPN é
grande e complexo demais pra reimplementar em Kotlin com segurança — em vez disso, o app
controla o binário `openvpn` DE VERDADE (compilado do código-fonte oficial do projeto
OpenVPN, através do trabalho de portabilidade do projeto **ics-openvpn**), rodando como
um processo separado, conversado por um socket local (a "Interface de Gerenciamento" do
próprio OpenVPN).

**Aviso de licença**: o binário `openvpn` é GPL-2.0. Distribuir o app com ele embutido
pode te obrigar a publicar o código-fonte do app inteiro sob a mesma licença, dependendo
de como você distribuir. Vale uma conversa com alguém que entenda de licenciamento antes
de publicar isso pra terceiros.

## Passo 1 — baixar e compilar

```bash
git clone --recursive https://github.com/schwabe/ics-openvpn
cd ics-openvpn    teste.infinitenet.net
```

Esse projeto já tem os scripts prontos pra compilar o `openvpn` (e as libs que ele
precisa: OpenSSL, lzo) pra Android, pra várias ABIs de uma vez, via NDK. Normalmente
basta abrir o projeto no Android Studio e deixar o Gradle rodar as tasks nativas dele
(`main/build-native.sh` ou os targets `externalNativeBuild` do próprio `main/build.gradle`
deles) — o resultado fica em algo como:

```
main/build/intermediates/cmake/release/obj/arm64-v8a/libopenvpn.so
```

(o caminho exato pode variar um pouco conforme a versão do AGP/Gradle deles na hora que
você clonar — se não achar exatamente esse caminho, procura por `libopenvpn.so` dentro da
pasta `build/` inteira depois de compilar.)

## Passo 2 — copiar o binário pro nosso projeto

Copia o `libopenvpn.so` (da ABI **arm64-v8a**, a que cobre a esmagadora maioria dos
aparelhos reais) pra:

```
app/src/main/jniLibs/arm64-v8a/libopenvpn.so
```

O nome do arquivo **precisa** começar com `lib` e terminar com `.so` — é assim que o
Android permite empacotar um executável de verdade dentro do APK (tecnicamente ele entra
como se fosse uma biblioteca nativa, mas na prática é o binário `openvpn` comum, chamado
depois via `ProcessBuilder`, não carregado como lib de código).

## Passo 3 — conferir se o binário roda

Depois de compilado o app com esse arquivo no lugar, o caminho real dele no aparelho (uma
vez instalado) fica em `context.applicationInfo.nativeLibraryDir + "/libopenvpn.so"` — é
esse caminho que o `OpenVpnManagementClient.kt` usa pra chamar o processo. Não precisa
mexer em nada disso manualmente, só garantir que o arquivo está no lugar certo antes de
compilar.

## Se algo não bater

O protocolo de comunicação entre o app e o processo `openvpn` (a "Interface de
Gerenciamento", com os comandos `needok`, `>STATE:`, `>BYTECOUNT:`, passagem do
descritor da TUN via socket Unix) foi implementado com base na documentação oficial
(`doc/android.txt` do próprio código-fonte do OpenVPN) e em relatos de comportamento
real — mas **nunca foi testado contra o binário de verdade rodando**. Se a conexão
falhar logo no início, o primeiro lugar a olhar é o Logcat/log do processo `openvpn`
em si (o `OpenVpnManagementClient` repassa a saída dele pro nosso `AppLog`) — geralmente
a mensagem de erro do próprio OpenVPN é bem clara sobre o que esperava e não recebeu.