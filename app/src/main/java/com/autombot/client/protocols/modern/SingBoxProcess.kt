package com.autombot.client.protocols.modern

import android.content.Context
import com.autombot.client.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
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
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            AppLog.log("$logPrefix core: $line", AppLog.Level.INFO)
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
            if (ready) return true
            delay(100)
        }
        return false
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
    }
}
