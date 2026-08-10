package com.autombot.client.protocols.modern

import android.content.Context
import com.autombot.client.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Executa o binário oficial do sing-box empacotado como libsingbox.so.
 *
 * O nome .so é intencional: arquivos em jniLibs são extraídos pelo Android em
 * nativeLibraryDir com permissão de execução. O artefato continua sendo o CLI
 * oficial do sing-box, não uma biblioteca carregada por System.loadLibrary().
 */
class SingBoxProcess(
    context: Context,
    private val logPrefix: String
) {
    private val appContext = context.applicationContext
    private val binaryFile = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)

    @Volatile
    private var process: Process? = null

    fun isCoreAvailable(): Boolean = binaryFile.isFile && binaryFile.canExecute()

    fun binaryPath(): String = binaryFile.absolutePath

    fun isAlive(): Boolean = process?.isAlive == true

    suspend fun version(): String? = withContext(Dispatchers.IO) {
        if (!isCoreAvailable()) return@withContext null
        runCommand(listOf("version"), timeoutSeconds = 5).takeIf { it.exitCode == 0 }?.output?.lineSequence()?.firstOrNull()
    }

    suspend fun checkConfig(configFile: File): CommandResult = withContext(Dispatchers.IO) {
        if (!isCoreAvailable()) {
            return@withContext CommandResult(-1, "Núcleo sing-box ausente em ${binaryFile.absolutePath}")
        }
        runCommand(listOf("check", "-c", configFile.absolutePath), timeoutSeconds = 10)
    }

    suspend fun start(configFile: File) = withContext(Dispatchers.IO) {
        check(isCoreAvailable()) { "Núcleo sing-box ausente. Execute scripts/fetch_singbox_android_core.sh antes de gerar o APK." }
        stop()

        val builder = ProcessBuilder(binaryFile.absolutePath, "run", "-c", configFile.absolutePath)
            .redirectErrorStream(true)
        builder.environment()["HOME"] = appContext.filesDir.absolutePath
        builder.environment()["TMPDIR"] = appContext.cacheDir.absolutePath

        val started = builder.start()
        process = started
        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = ANSI_ESCAPE.replace(rawLine, "").trim()
                        if (line.isNotBlank()) {
                            val level = if (line.contains("ERROR", ignoreCase = true)) {
                                AppLog.Level.ERROR
                            } else {
                                AppLog.Level.INFO
                            }
                            AppLog.log("$logPrefix core: $line", level)
                        }
                    }
                }
            }
        }, "sing-box-log-${logPrefix.hashCode()}").apply {
            isDaemon = true
            start()
        }
    }

    suspend fun awaitLocalPort(port: Int, timeoutMs: Long = 12_000L): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isAlive()) return false
            val ready = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    }
                }.isSuccess
            }
            if (ready) {
                // O mixed inbound abrir a porta só prova que o processo iniciou. TUIC
                // e Hysteria2 criam a sessão QUIC de forma preguiçosa no primeiro
                // CONNECT real. Sem aquecimento, a primeira rajada do navegador faz
                // dezenas de conexões disputarem justamente o primeiro handshake e
                // algumas podem expirar/fechar antes dele terminar.
                //
                // Fazemos um CONNECT SOCKS5 mínimo por IP literal antes de anunciar a
                // conexão como pronta. É best-effort: se o destino de teste estiver
                // bloqueado na rede, não derrubamos um túnel cujo proxy local está OK.
                val warmed = withContext(Dispatchers.IO) {
                    warmUpSocksSession(port, timeoutMs = 5_000)
                }
                if (warmed) {
                    AppLog.log(
                        "$logPrefix: sessão QUIC aquecida antes de liberar o tráfego da VPN",
                        AppLog.Level.SUCCESS
                    )
                } else {
                    AppLog.log(
                        "$logPrefix: proxy local pronto; warm-up QUIC não confirmou em 5s, seguindo normalmente",
                        AppLog.Level.INFO
                    )
                }
                return true
            }
            delay(100)
        }
        return false
    }

    /**
     * Faz somente a negociação SOCKS5 + CONNECT para um endpoint IP estável. Receber
     * REP=0 significa que o sing-box já atravessou o outbound TUIC/Hysteria2 e abriu
     * a conexão remota; ao fechar o socket, a sessão QUIC multiplexada permanece
     * disponível para as conexões reais que chegam logo depois.
     */
    private fun warmUpSocksSession(port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 500)
            socket.soTimeout = timeoutMs

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // SOCKS5: versão 5, um método, sem autenticação.
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()

            val methodReply = ByteArray(2)
            if (!readFully(input, methodReply)) return@runCatching false
            if ((methodReply[0].toInt() and 0xFF) != 0x05 ||
                (methodReply[1].toInt() and 0xFF) != 0x00
            ) {
                return@runCatching false
            }

            // CONNECT 1.1.1.1:443. IP literal evita introduzir outra consulta DNS no
            // próprio warm-up. O destino é usado só para completar o handshake.
            output.write(
                byteArrayOf(
                    0x05, 0x01, 0x00, 0x01,
                    0x01, 0x01, 0x01, 0x01,
                    0x01, 0xBB.toByte()
                )
            )
            output.flush()

            val replyHeader = ByteArray(4)
            if (!readFully(input, replyHeader)) return@runCatching false
            if ((replyHeader[0].toInt() and 0xFF) != 0x05 ||
                (replyHeader[1].toInt() and 0xFF) != 0x00
            ) {
                return@runCatching false
            }

            val boundAddressLength = when (replyHeader[3].toInt() and 0xFF) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> {
                    val length = input.read()
                    if (length < 0) return@runCatching false
                    length
                }
                else -> return@runCatching false
            }

            val tail = ByteArray(boundAddressLength + 2)
            readFully(input, tail)
        }
    }.getOrDefault(false)

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    fun stop() {
        val active = process ?: return
        process = null
        runCatching { active.destroy() }
        runCatching {
            if (!active.waitFor(1500, TimeUnit.MILLISECONDS)) {
                active.destroyForcibly()
                active.waitFor(1000, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun runCommand(args: List<String>, timeoutSeconds: Long): CommandResult {
        val builder = ProcessBuilder(listOf(binaryFile.absolutePath) + args).redirectErrorStream(true)
        builder.environment()["HOME"] = appContext.filesDir.absolutePath
        builder.environment()["TMPDIR"] = appContext.cacheDir.absolutePath
        val cmd = builder.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                cmd.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.appendLine(it) }
                }
            }
        }.apply { start() }

        val finished = cmd.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            cmd.destroyForcibly()
            reader.join(500)
            return CommandResult(-1, "Comando sing-box excedeu ${timeoutSeconds}s")
        }
        reader.join(500)
        return CommandResult(cmd.exitValue(), output.toString().trim())
    }

    data class CommandResult(val exitCode: Int, val output: String)

    companion object {
        const val BINARY_NAME = "libsingbox.so"
        private val ANSI_ESCAPE = Regex("\u001B\\[[;\\d]*m")
    }
}
