package com.autombot.client.core.tun2socks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ponte pro motor de tun2socks NATIVO (biblioteca C de terceiros hev-socks5-tunnel,
 * https://github.com/heiher/hev-socks5-tunnel), no lugar do Tun2SocksEngine.kt
 * (motor escrito do zero em Kotlin, mantido no projeto só como referência histórica
 * — não é mais usado pela VPN de sistema).
 *
 * Motivo da troca: o motor próprio em Kotlin foi acumulando bug atrás de bug ao
 * longo do desenvolvimento (checksum, opção MSS do TCP, protect() com fd inválido,
 * bytes de endereço IP com sinal errado, UDP genuíno nunca chegando a existir de
 * verdade) — cada um encontrado só depois de testar em aparelho real. A biblioteca
 * nativa usada aqui é a mesma classe de motor usada por apps de produção (NekoBox,
 * Matsuri e outros clientes VLESS/VMess/Shadowsocks populares), já testada por uma
 * comunidade inteira, com TCP e UDP genuíno completos (IPv4/IPv6).
 *
 * IMPORTANTE — pré-requisito antes de compilar: a biblioteca nativa precisa ser
 * baixada e pré-compilada manualmente uma vez. Ver
 * app/src/main/cpp/README.md pros passos exatos.
 */
object NativeTun2Socks {
    init {
        System.loadLibrary("autombot_tun2socks")
    }

    /**
     * Inicia o túnel — roda numa thread nativa separada (não bloqueia quem chama).
     * [tunFd] deve vir de um VpnService.Builder().establish() já estabelecido (o
     * mesmo file descriptor que qualquer motor de VPN precisa).
     * [socksHost]/[socksPort] apontam pro proxy SOCKS5 local que cada protocolo
     * (SSH/VLESS/VMess/Shadowsocks) já expõe — igual ao Tun2SocksEngine antigo usava.
     */
    /**
     * [dns]: servidor DNS a usar quando o motor nativo precisar resolver nomes —
     * passado para não depender do DNS padrão do sistema (que pode estar dentro do
     * próprio túnal, causando loop).
     */
    // CORRECAO: usuario sem acesso a PC/adb pra puxar o Logcat real do sistema (unico
    // jeito de ver os logs INTERNOS da hev-socks5-tunnel — coisas como falha de
    // handshake SOCKS5, timeout de conexao, sessao TCP/UDP descartada — que nunca
    // passavam pelo nosso AppLog porque essa parte e uma biblioteca de terceiros,
    // nao codigo nosso). Documentacao da lib confirma que ela aceita
    // "misc.log-file: <caminho>" apontando pra QUALQUER arquivo, nao so
    // stdout/stderr. Agora aponta pra um arquivo dentro da pasta do proprio app
    // (nao precisa de permissao nenhuma) com log-level "debug" (maximo detalhe) —
    // e o startTailingToAppLog() abaixo fica lendo esse arquivo e repassando linha
    // por linha pro AppLog, que ja aparece na tela "Ver log" que o usuario ja usa.
    // Sem isso, essa metade inteira do pipeline (TUN -> lib nativa -> SOCKS5) era
    // uma caixa preta.
    fun logFilePath(context: android.content.Context): String =
        java.io.File(context.filesDir, "hev_tunnel.log").absolutePath

    fun start(tunFd: Int, socksHost: String, socksPort: Int, dns: String = "8.8.8.8", logFilePath: String? = null): Boolean {
        if (logFilePath != null) runCatching { java.io.File(logFilePath).writeText("") } // limpa do teste anterior
        val config = buildConfig(socksHost, socksPort, dns, logFilePath)
        return nativeStart(config, tunFd)
    }

    private var tailJob: Job? = null

    /** Fica lendo o arquivo de log da lib nativa e repassando linha por linha pro AppLog. */
    fun startTailingToAppLog(logFilePath: String) {
        tailJob?.cancel()
        tailJob = CoroutineScope(Dispatchers.IO).launch {
            val file = java.io.File(logFilePath)
            var lastSize = 0L
            while (isActive) {
                runCatching {
                    if (file.exists() && file.length() > lastSize) {
                        file.inputStream().use { input ->
                            input.skip(lastSize)
                            input.bufferedReader().forEachLine { line ->
                                if (line.isNotBlank()) {
                                    val isError = line.contains("[E]") || line.contains("error", ignoreCase = true)
                                    com.autombot.client.util.AppLog.log(
                                        "hev (motor nativo): $line",
                                        if (isError) com.autombot.client.util.AppLog.Level.ERROR else com.autombot.client.util.AppLog.Level.INFO
                                    )
                                }
                            }
                        }
                        lastSize = file.length()
                    }
                }
                delay(1500)
            }
        }
    }

    fun stopTailing() {
        tailJob?.cancel()
        tailJob = null
    }

    private var statsJob: Job? = null

    /**
     * CORRECAO: usuario com ERR_CONNECTION_RESET consistente no navegador, mas o
     * NOSSO Socks5Server mostra dado real fluindo nos dois sentidos sem erro nenhum
     * — ou seja, o problema so pode estar DEPOIS do nosso codigo, no trecho que
     * pega o que escrevemos e devolve pela interface TUN de verdade (dentro da
     * propria hev-socks5-tunnel). nativeGetStats() existe desde sempre mas nunca
     * foi chamado em lugar nenhum — e a UNICA visao real que temos desse pedaco.
     * Compara com os totais do Socks5Server: se os numeros nao baterem, mostra
     * exatamente onde o dado esta se perdendo (dentro da lib nativa, nao no nosso
     * codigo nem no SSH/VPS).
     */
    fun startStatsLogging() {
        statsJob?.cancel()
        statsJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(5000)
                runCatching {
                    val s = nativeGetStats() // [tx_packets, tx_bytes, rx_packets, rx_bytes]
                    if (s.size >= 4) {
                        com.autombot.client.util.AppLog.log(
                            "hev stats: TUN enviou ${s[0]} pacotes/${s[1]}B, recebeu ${s[2]} pacotes/${s[3]}B (acumulado desde que ligou)",
                            com.autombot.client.util.AppLog.Level.INFO
                        )
                    }
                }
            }
        }
    }

    fun stopStatsLogging() {
        statsJob?.cancel()
        statsJob = null
    }

    /** Para o túnel. Bloqueia até a thread nativa terminar de verdade — chamar fora da main thread. */
    fun stop() = nativeStop()

    /**
     * [0] = bytes transmitidos, [1] = bytes recebidos.
     * ATENCAO: a assinatura real de hev_socks5_tunnel_stats() ainda precisa ser
     * conferida contra o cabeçalho da biblioteca (ver app/src/main/cpp/README.md,
     * passo 4) — se for diferente do suposto, isso pode retornar valores errados
     * até a ponte JNI ser ajustada.
     */
    fun stats(): LongArray = nativeGetStats()

    // NOTAS sobre os campos do config YAML do hev-socks5-tunnel:
    //  - tunnel.ipv4: IP interno que o motor usa pra montar os pacotes que ele
    //    mesmo gera (respostas TCP/UDP de volta pro TUN). NAO e o IP da interface
    //    TUN configurada pelo VpnService (que e 10.0.0.1/24 em AutomBotVpnService).
    //    O valor 198.18.0.1 e um endereco reservado (RFC 2544) que nao conflita com
    //    redes reais — padrao usado pela propria documentacao do hev-socks5-tunnel.
    //  - tunnel.mtu: DEVE bater com o MTU configurado no VpnService.Builder
    //    (Builder().setMtu(1500) em AutomBotVpnService.kt) para que os dois lados
    //    concordem no tamanho maximo de pacote. Valores divergentes causam
    //    fragmentacao ou descarte silencioso de pacotes.
    //  - dns: quando o motor precisar resolver nomes por conta propria (raro, a
    //    maioria do DNS vem como pacote UDP da interface TUN), usa esse servidor
    //    em vez de herdar o DNS do sistema — que pode estar dentro do tunel, causando
    //    loop.
    private fun buildConfig(socksHost: String, socksPort: Int, dns: String = "8.8.8.8", logFilePath: String? = null): String {
        val base = """
        tunnel:
          mtu: 1500
          ipv4: 198.18.0.1
        socks5:
          port: $socksPort
          address: $socksHost
          udp: udp
          pipeline: true
        dns:
          address: $dns
          port: 53
        """.trimIndent()
        if (logFilePath == null) return base
        // Mantem o valor compativel com a configuracao oficial da biblioteca
        // incorporada. O aumento para 256 KiB nao foi validado e pode apenas elevar
        // consumo de memoria sem ampliar a janela TCP real do lwIP.
        return base + "\nmisc:\n  log-file: $logFilePath\n  log-level: warn\n  connect-timeout: 10000\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  tcp-buffer-size: 65536\n"
    }

    private external fun nativeStart(configYaml: String, tunFd: Int): Boolean
    private external fun nativeStop()
    private external fun nativeGetStats(): LongArray
}
