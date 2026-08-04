package com.autombot.client.protocols.slowdns

import android.content.Context
import com.autombot.client.protocols.ssh.SlowDnsResolverMode
import com.autombot.client.util.AppLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Controla o processo `dnstt-client` DE VERDADE (binário nativo Go, ver README.md
 * desta pasta) — a ferramenta de tunelamento por DNS mantida pelo próprio Tor
 * Project (dnstt, domínio público). Bem mais simples de integrar que o OpenVPN: o
 * dnstt-client não precisa de TUN nem de protocolo de gerenciamento — ele só ABRE UM
 * PORTA LOCAL e encaminha qualquer conexão TCP recebida ali através do túnel DNS,
 * igual um `ssh -L` comum. A gente só precisa: iniciar o processo, esperar a porta
 * local começar a aceitar conexão, e then apontar o SshTunnelManager pra conectar
 * nessa porta local em vez do servidor de verdade — o próprio dnstt-server (do lado
 * do VPS) já sabe pra onde encaminhar de verdade (configurado lá, não aqui).
 */
class SlowDnsClient(
    private val context: Context,
    private val domain: String,
    private val pubkey: String,
    private val resolverMode: SlowDnsResolverMode,
    private val resolver: String
) {
    private var process: Process? = null
    private var localPort: Int = 0

    /**
     * Inicia o processo e espera a porta local começar a aceitar conexão (com um
     * prazo máximo). Retorna a porta local pronta pra uso, ou null se falhar.
     */
    fun start(timeoutMs: Long = 8000): Int? {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libdnstt.so")
        if (!binary.exists()) {
            AppLog.log(
                "SlowDNS: binário nativo não encontrado (${binary.absolutePath}) — " +
                    "veja protocols/slowdns/README.md pra compilar e incluir o libdnstt.so no projeto.",
                AppLog.Level.ERROR
            )
            return null
        }

        val port = findFreePort()
        localPort = port
        val localAddr = "127.0.0.1:$port"

        val resolverArgs = when (resolverMode) {
            SlowDnsResolverMode.UDP -> listOf("-udp", resolver)
            SlowDnsResolverMode.DOH -> listOf("-doh", resolver)
            SlowDnsResolverMode.DOT -> listOf("-dot", resolver)
        }

        // ATENCAO: flags conferidas contra a documentacao oficial do dnstt-client
        // (pkg.go.dev/www.bamsoftware.com/git/dnstt.git/dnstt-client) — nao testadas
        // contra o binario de verdade rodando.
        val args = listOf(binary.absolutePath) + resolverArgs + listOf("-pubkey", pubkey, domain, localAddr)

        val proc = try {
            ProcessBuilder(args)
                .redirectErrorStream(true)
                .directory(context.filesDir)
                .start()
        } catch (e: Exception) {
            AppLog.log("SlowDNS: falha ao iniciar o processo dnstt-client: ${e.message}", AppLog.Level.ERROR)
            return null
        }
        process = proc

        Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                    AppLog.log("SlowDNS (processo): $line", AppLog.Level.INFO)
                }
            } catch (e: Exception) {
                // processo encerrado — normal
            }
        }.apply { isDaemon = true; start() }

        // Espera a porta local comecar a aceitar conexao de verdade — o processo
        // pode levar um instante pra subir o listener.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                AppLog.log("SlowDNS: processo dnstt-client encerrou logo no início (config errada?)", AppLog.Level.ERROR)
                return null
            }
            if (isPortListening(port)) {
                AppLog.log("SlowDNS: túnel DNS ativo — encaminhando 127.0.0.1:$port pro servidor via DNS", AppLog.Level.SUCCESS)
                return port
            }
            Thread.sleep(150)
        }

        AppLog.log("SlowDNS: timeout esperando o túnel DNS ficar pronto", AppLog.Level.ERROR)
        stop()
        return null
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
    }

    private fun isPortListening(port: Int): Boolean {
        return try {
            Socket("127.0.0.1", port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}