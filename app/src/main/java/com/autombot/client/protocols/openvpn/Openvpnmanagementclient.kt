package com.autombot.client.protocols.openvpn

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import com.autombot.client.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

/** Uma rota que o OpenVPN pediu para instalar na interface VPN do Android. */
data class OpenVpnTunRoute(
    val address: String,
    val prefixLength: Int
)

/**
 * Configuração de TUN coletada das callbacks Android do OpenVPN antes do OPENTUN.
 * O VpnService usa exatamente estes valores, em vez de criar uma interface fixa.
 */
data class OpenVpnTunConfig(
    val ipv4Address: String?,
    val ipv4PrefixLength: Int?,
    val ipv6Address: String?,
    val ipv6PrefixLength: Int?,
    val routes: List<OpenVpnTunRoute>,
    val dnsServers: List<String>,
    val searchDomain: String?,
    val mtu: Int
)

/**
 * Controla o binário OpenVPN compilado com TARGET_ANDROID pela Management Interface.
 *
 * O fluxo segue o mesmo desenho do OpenVPN for Android (ics-openvpn): o APP cria
 * primeiro o LocalServerSocket UNIX e o processo OpenVPN é iniciado com
 * --management-client para se conectar nele. Isso é importante no Android porque
 * PROTECTFD e OPENTUN trafegam descritores reais via SCM_RIGHTS.
 */
class OpenVpnManagementClient(
    private val context: Context,
    private val config: OpenVpnConnectionConfig,
    private val connectionName: String,
    /** Recebe toda a configuração acumulada e chama VpnService.Builder().establish(). */
    private val establishTun: (OpenVpnTunConfig) -> ParcelFileDescriptor?,
    /** Chama VpnService.protect(fd) no socket recebido via SCM_RIGHTS. */
    private val protectFd: (FileDescriptor) -> Boolean,
    private val onStateChange: (connected: Boolean, error: String?) -> Unit,
    private val onBytesUpdate: (rx: Long, tx: Long) -> Unit
) {
    private var process: Process? = null
    private var managementSocket: LocalSocket? = null
    private var managementServerSocket: LocalServerSocket? = null
    private var managementServerBackingSocket: LocalSocket? = null
    private var managementSocketPath: File? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var lastFatalError: String? = null

    // Estado que o OpenVPN envia por NEED-OK antes de pedir OPENTUN.
    private var ipv4Address: String? = null
    private var ipv4PrefixLength: Int? = null
    private var ipv6Address: String? = null
    private var ipv6PrefixLength: Int? = null
    private var tunMtu: Int = 1500
    private var searchDomain: String? = null
    private val routes = linkedMapOf<String, OpenVpnTunRoute>()
    private val dnsServers = linkedSetOf<String>()

    fun start() {
        if (running) return
        running = true
        scope.launch { runManagement() }
    }

    fun stop() {
        running = false
        runCatching { sendCommandRaw("signal SIGTERM\n") }
        scope.launch {
            delay(1500)
            runCatching { process?.destroy() }
            cleanup()
        }
    }

    private fun cleanup() {
        runCatching { managementSocket?.close() }
        runCatching { managementServerSocket?.close() }
        runCatching { managementServerBackingSocket?.close() }
        runCatching { tunPfd?.close() }
        managementSocket = null
        managementServerSocket = null
        managementServerBackingSocket = null
        tunPfd = null
        managementSocketPath?.let { runCatching { it.delete() } }
        managementSocketPath = null
    }

    private fun openManagementServer(socketFile: File): LocalServerSocket? {
        runCatching { socketFile.delete() }

        val backing = LocalSocket(LocalSocket.SOCKET_STREAM)
        return try {
            backing.bind(
                LocalSocketAddress(
                    socketFile.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM
                )
            )
            val server = LocalServerSocket(backing.fileDescriptor)
            managementServerBackingSocket = backing
            managementServerSocket = server
            AppLog.log(
                "OpenVPN \"$connectionName\": Management UNIX preparado pelo app em ${socketFile.absolutePath}",
                AppLog.Level.INFO
            )
            server
        } catch (e: Exception) {
            runCatching { backing.close() }
            AppLog.log(
                "OpenVPN \"$connectionName\": falha ao criar Management UNIX local — ${e.message}",
                AppLog.Level.ERROR
            )
            null
        }
    }

    private suspend fun runManagement() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libopenvpn.so")
        AppLog.log("OpenVPN \"$connectionName\": usando binário em ${binary.absolutePath}", AppLog.Level.INFO)
        if (!binary.exists()) {
            onStateChange(false, "Binário openvpn não encontrado no app")
            running = false
            return
        }

        val configFile = config.configFile(context)
        if (!configFile.isFile) {
            onStateChange(false, "Arquivo .ovpn não encontrado: ${configFile.absolutePath}")
            running = false
            return
        }

        // Mantém o pathname curto: sockaddr_un no Linux tem limite pequeno.
        val socketFile = File(
            context.cacheDir,
            "ovpn_${Integer.toHexString(connectionName.hashCode())}_${android.os.Process.myPid()}.sock"
        )
        managementSocketPath = socketFile

        // O app precisa estar ouvindo ANTES do OpenVPN começar. Este é o padrão usado
        // pelo OpenVPN for Android: LocalServerSocket no app + --management-client.
        val server = openManagementServer(socketFile)
        if (server == null) {
            val error = "Não foi possível preparar a Management Interface UNIX"
            onStateChange(false, error)
            running = false
            cleanup()
            return
        }

        val processArgs = listOf(
            binary.absolutePath,
            "--config", configFile.absolutePath,
            "--management", socketFile.absolutePath, "unix",
            "--management-client",
            "--management-query-passwords",
            "--management-hold",
            "--verb", "3"
        )

        AppLog.log(
            "OpenVPN \"$connectionName\": iniciando; processo vai conectar ao Management UNIX do app",
            AppLog.Level.INFO
        )

        val proc = try {
            ProcessBuilder(processArgs)
                .redirectErrorStream(true)
                .directory(context.filesDir)
                .start()
        } catch (e: Exception) {
            onStateChange(false, "Falha ao iniciar OpenVPN: ${e.message}")
            running = false
            cleanup()
            return
        }
        process = proc

        // Mantém as últimas linhas para que uma saída muito rápida do binário nunca
        // resulte apenas em "encerrou antes de abrir management" sem a causa real.
        val recentProcessOutput = ArrayDeque<String>()
        val outputJob = scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                    synchronized(recentProcessOutput) {
                        if (recentProcessOutput.size >= 20) recentProcessOutput.removeFirst()
                        recentProcessOutput.addLast(line)
                    }
                    AppLog.log("OpenVPN \"$connectionName\" (processo): $line", AppLog.Level.INFO)
                }
            } catch (e: Exception) {
                if (running) {
                    AppLog.log(
                        "OpenVPN \"$connectionName\" (processo): saída encerrada — ${e.message}",
                        AppLog.Level.INFO
                    )
                }
            }
        }

        val socket = acceptManagementSocket(server, proc)
        if (socket == null) {
            if (!proc.isAlive) {
                try {
                    outputJob.join()
                } catch (_: Exception) {
                }
            }

            val exitCode = if (!proc.isAlive) runCatching { proc.exitValue() }.getOrNull() else null
            val tail = synchronized(recentProcessOutput) {
                recentProcessOutput.joinToString(" | ").take(1200)
            }
            val error = when {
                !proc.isAlive && tail.isNotBlank() ->
                    "OpenVPN encerrou antes da Management Interface (exit=${exitCode ?: "?"}): $tail"
                !proc.isAlive ->
                    "Processo OpenVPN encerrou antes de abrir a Management Interface (exit=${exitCode ?: "?"})"
                else ->
                    "OpenVPN não conectou à Management Interface UNIX do app em 30s"
            }
            AppLog.log("OpenVPN \"$connectionName\": $error", AppLog.Level.ERROR)
            onStateChange(false, error)
            running = false
            runCatching { proc.destroy() }
            cleanup()
            return
        }

        managementSocket = socket
        AppLog.log(
            "OpenVPN \"$connectionName\": Management Interface UNIX conectada com suporte a passagem de FDs",
            AppLog.Level.SUCCESS
        )

        try {
            // Ativa notificações antes de soltar o hold para não perder os primeiros estados.
            sendCommandRaw("state on\n")
            sendCommandRaw("bytecount 2\n")
            sendCommandRaw("hold release\n")

            val input = socket.inputStream
            while (running && coroutineContext.isActive) {
                val message = readManagementLine(socket, input) ?: break
                handleLine(message.line, message.fileDescriptors)
            }
        } catch (e: Exception) {
            if (running) {
                AppLog.log(
                    "OpenVPN \"$connectionName\": Management Interface encerrou — ${e.message}",
                    AppLog.Level.ERROR
                )
            }
        } finally {
            val wasConnected = connected
            running = false
            connected = false
            if (wasConnected) {
                onStateChange(false, null)
            } else if (lastFatalError == null && !proc.isAlive) {
                val exitCode = runCatching { proc.exitValue() }.getOrNull()
                onStateChange(false, "Processo OpenVPN encerrou antes de conectar (exit=${exitCode ?: "?"})")
            }
            cleanup()
        }
    }

    /**
     * O LocalServerSocket não expõe timeout. Aceitamos em uma coroutine IO e, em
     * paralelo, observamos o processo. Se ele morrer ou exceder 30s, fechar o server
     * desbloqueia accept() imediatamente.
     */
    private suspend fun acceptManagementSocket(server: LocalServerSocket, proc: Process): LocalSocket? {
        val acceptDeferred = scope.async(Dispatchers.IO) {
            try {
                server.accept()
            } catch (_: Exception) {
                null
            }
        }

        val deadline = System.currentTimeMillis() + 30_000L
        while (
            running &&
            proc.isAlive &&
            !acceptDeferred.isCompleted &&
            System.currentTimeMillis() < deadline
        ) {
            delay(50)
        }

        if (!acceptDeferred.isCompleted) {
            runCatching { server.close() }
        }

        return try {
            acceptDeferred.await()
        } catch (_: Exception) {
            null
        }
    }

    private data class ManagementLine(
        val line: String,
        val fileDescriptors: List<FileDescriptor>
    )

    /**
     * Lê byte a byte porque o SCM_RIGHTS pode chegar junto de qualquer read().
     * O volume da Management Interface é mínimo; priorizamos associar o FD à linha
     * correta em vez de usar BufferedReader, que pode consumir várias linhas de uma vez.
     */
    private fun readManagementLine(socket: LocalSocket, input: InputStream): ManagementLine? {
        val bytes = ByteArrayOutputStream()
        val receivedFds = mutableListOf<FileDescriptor>()

        while (true) {
            val value = input.read()
            runCatching { socket.ancillaryFileDescriptors }
                .getOrNull()
                ?.let { receivedFds.addAll(it) }

            if (value == -1) {
                return if (bytes.size() == 0) null else ManagementLine(
                    line = String(bytes.toByteArray(), Charsets.UTF_8).trimEnd('\r'),
                    fileDescriptors = receivedFds
                )
            }

            if (value == '\n'.code) {
                return ManagementLine(
                    line = String(bytes.toByteArray(), Charsets.UTF_8).trimEnd('\r'),
                    fileDescriptors = receivedFds
                )
            }

            bytes.write(value)
        }
    }

    private fun handleLine(line: String, ancillaryFds: List<FileDescriptor>) {
        when {
            line.startsWith(">NEED-OK:") -> handleNeedOk(line, ancillaryFds)
            line.startsWith(">STATE:") -> {
                closeAncillaryFds(ancillaryFds)
                handleState(line)
            }
            line.startsWith(">BYTECOUNT:") -> {
                closeAncillaryFds(ancillaryFds)
                handleByteCount(line)
            }
            line.startsWith(">LOG:") -> {
                closeAncillaryFds(ancillaryFds)
                AppLog.log(
                    "OpenVPN \"$connectionName\": ${line.substringAfter(">LOG:", "")}",
                    AppLog.Level.INFO
                )
            }
            line.startsWith(">FATAL:") -> {
                closeAncillaryFds(ancillaryFds)
                val msg = line.substringAfter(">FATAL:", "").trim()
                lastFatalError = msg
                AppLog.log("OpenVPN \"$connectionName\": erro fatal — $msg", AppLog.Level.ERROR)
                onStateChange(false, msg)
            }
            line.startsWith(">PASSWORD:") -> {
                closeAncillaryFds(ancillaryFds)
                val msg = "Perfil OpenVPN pediu usuário/senha via management; este perfil precisa de credenciais explícitas"
                lastFatalError = msg
                AppLog.log("OpenVPN \"$connectionName\": $msg", AppLog.Level.ERROR)
                onStateChange(false, msg)
            }
            else -> closeAncillaryFds(ancillaryFds)
        }
    }

    private fun handleNeedOk(line: String, ancillaryFds: List<FileDescriptor>) {
        val rest = line.removePrefix(">NEED-OK:")
        val type = rest.substringBefore(':').trim().uppercase()
        val message = rest.substringAfter("MSG:", rest.substringAfter(':', "")).trim()

        when (type) {
            "IFCONFIG" -> {
                closeAncillaryFds(ancillaryFds)
                runCatching { parseIfConfig(message) }
                    .onSuccess { sendNeedOk(type, "ok") }
                    .onFailure { failNeedOk(type, it.message ?: "IFCONFIG inválido") }
            }

            "IFCONFIG6" -> {
                closeAncillaryFds(ancillaryFds)
                runCatching { parseIfConfig6(message) }
                    .onSuccess { sendNeedOk(type, "ok") }
                    .onFailure { failNeedOk(type, it.message ?: "IFCONFIG6 inválido") }
            }

            "ROUTE" -> {
                closeAncillaryFds(ancillaryFds)
                runCatching { parseRoute4(message) }
                    .onSuccess { sendNeedOk(type, "ok") }
                    .onFailure { failNeedOk(type, it.message ?: "ROUTE inválida") }
            }

            "ROUTE6" -> {
                closeAncillaryFds(ancillaryFds)
                runCatching { parseRoute6(message) }
                    .onSuccess { sendNeedOk(type, "ok") }
                    .onFailure { failNeedOk(type, it.message ?: "ROUTE6 inválida") }
            }

            "DNSSERVER", "DNS6SERVER" -> {
                closeAncillaryFds(ancillaryFds)
                val dns = tokenize(message).firstOrNull()
                if (dns.isNullOrBlank()) {
                    failNeedOk(type, "DNS vazio")
                } else {
                    dnsServers.add(dns)
                    AppLog.log("OpenVPN \"$connectionName\": DNS recebido: $dns", AppLog.Level.INFO)
                    sendNeedOk(type, "ok")
                }
            }

            "DNSDOMAIN" -> {
                closeAncillaryFds(ancillaryFds)
                searchDomain = tokenize(message).firstOrNull()?.takeIf { it.isNotBlank() }
                sendNeedOk(type, "ok")
            }

            "PERSIST_TUN_ACTION" -> {
                closeAncillaryFds(ancillaryFds)
                sendNeedOk(type, "OPEN_BEFORE_CLOSE")
            }

            "PROTECTFD" -> handleProtectFd(ancillaryFds)

            "OPENTUN" -> {
                closeAncillaryFds(ancillaryFds)
                handleOpenTun()
            }

            else -> {
                closeAncillaryFds(ancillaryFds)
                AppLog.log(
                    "OpenVPN \"$connectionName\": NEED-OK $type não específico; confirmando",
                    AppLog.Level.INFO
                )
                sendNeedOk(type, "ok")
            }
        }
    }

    private fun parseIfConfig(message: String) {
        val args = tokenize(message)
        require(args.size >= 4) { "IFCONFIG incompleto: $message" }

        val local = args[0]
        val remoteOrNetmask = args[1]
        val mtu = args[2].toIntOrNull()?.coerceIn(576, 9000)
        val topology = args[3].lowercase()

        val prefix = when (topology) {
            "subnet" -> netmaskToPrefix(remoteOrNetmask)
            "net30" -> 30
            "p2p" -> 32
            else -> runCatching { netmaskToPrefix(remoteOrNetmask) }.getOrDefault(32)
        }

        ipv4Address = local
        ipv4PrefixLength = prefix
        if (mtu != null) tunMtu = mtu

        AppLog.log(
            "OpenVPN \"$connectionName\": IFCONFIG $local/$prefix, mtu=$tunMtu, topology=$topology",
            AppLog.Level.INFO
        )
    }

    private fun parseIfConfig6(message: String) {
        val args = tokenize(message)
        require(args.isNotEmpty()) { "IFCONFIG6 vazio" }

        val localToken = args[0]
        if ('/' in localToken) {
            ipv6Address = localToken.substringBefore('/')
            ipv6PrefixLength = localToken.substringAfter('/').toIntOrNull()
                ?: throw IllegalArgumentException("Prefixo IPv6 inválido: $localToken")
        } else {
            ipv6Address = localToken
            ipv6PrefixLength = args.getOrNull(1)
                ?.substringAfterLast('/')
                ?.toIntOrNull()
                ?: 64
        }

        args.getOrNull(2)?.toIntOrNull()?.coerceIn(1280, 9000)?.let { tunMtu = it }
        AppLog.log(
            "OpenVPN \"$connectionName\": IFCONFIG6 ${ipv6Address}/${ipv6PrefixLength}",
            AppLog.Level.INFO
        )
    }

    private fun parseRoute4(message: String) {
        val args = tokenize(message)
        require(args.isNotEmpty()) { "ROUTE vazia" }

        val route = if ('/' in args[0]) {
            val address = args[0].substringBefore('/')
            val prefix = args[0].substringAfter('/').toIntOrNull()
                ?: throw IllegalArgumentException("Prefixo IPv4 inválido: ${args[0]}")
            OpenVpnTunRoute(address, prefix)
        } else {
            require(args.size >= 2) { "ROUTE sem netmask: $message" }
            OpenVpnTunRoute(args[0], netmaskToPrefix(args[1]))
        }

        require(route.prefixLength in 0..32) { "Prefixo IPv4 fora do intervalo" }
        routes["4:${route.address}/${route.prefixLength}"] = route
        AppLog.log(
            "OpenVPN \"$connectionName\": rota IPv4 ${route.address}/${route.prefixLength}",
            AppLog.Level.INFO
        )
    }

    private fun parseRoute6(message: String) {
        val token = tokenize(message).firstOrNull()
            ?: throw IllegalArgumentException("ROUTE6 vazia")
        require('/' in token) { "ROUTE6 sem prefixo: $token" }

        val route = OpenVpnTunRoute(
            address = token.substringBefore('/'),
            prefixLength = token.substringAfter('/').toIntOrNull()
                ?: throw IllegalArgumentException("Prefixo IPv6 inválido: $token")
        )
        require(route.prefixLength in 0..128) { "Prefixo IPv6 fora do intervalo" }
        routes["6:${route.address}/${route.prefixLength}"] = route
        AppLog.log(
            "OpenVPN \"$connectionName\": rota IPv6 ${route.address}/${route.prefixLength}",
            AppLog.Level.INFO
        )
    }

    private fun handleProtectFd(ancillaryFds: List<FileDescriptor>) {
        val fd = ancillaryFds.firstOrNull()
        if (fd == null) {
            AppLog.log(
                "OpenVPN \"$connectionName\": PROTECTFD chegou sem descritor SCM_RIGHTS",
                AppLog.Level.ERROR
            )
            sendNeedOk("PROTECTFD", "cancel")
            return
        }

        val protected = runCatching { protectFd(fd) }.getOrDefault(false)
        closeAncillaryFds(ancillaryFds)

        if (protected) {
            AppLog.log(
                "OpenVPN \"$connectionName\": socket remoto protegido fora do túnel",
                AppLog.Level.INFO
            )
            sendNeedOk("PROTECTFD", "ok")
        } else {
            AppLog.log(
                "OpenVPN \"$connectionName\": VpnService.protect() falhou no socket remoto",
                AppLog.Level.ERROR
            )
            sendNeedOk("PROTECTFD", "cancel")
        }
    }

    private fun handleOpenTun() {
        val snapshot = OpenVpnTunConfig(
            ipv4Address = ipv4Address,
            ipv4PrefixLength = ipv4PrefixLength,
            ipv6Address = ipv6Address,
            ipv6PrefixLength = ipv6PrefixLength,
            routes = routes.values.toList(),
            dnsServers = dnsServers.toList(),
            searchDomain = searchDomain,
            mtu = tunMtu
        )

        if (snapshot.ipv4Address == null && snapshot.ipv6Address == null) {
            failNeedOk("OPENTUN", "OpenVPN pediu OPENTUN antes de fornecer IFCONFIG/IFCONFIG6")
            return
        }

        val pfd = establishTun(snapshot)
        if (pfd == null) {
            failNeedOk("OPENTUN", "VpnService.Builder.establish() retornou null")
            return
        }

        try {
            val socket = managementSocket
                ?: throw IllegalStateException("Management Interface não está conectada")

            socket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
            if (!sendCommandRaw("needok 'OPENTUN' ok\n")) {
                throw IllegalStateException("Falha ao enviar resposta OPENTUN")
            }

            runCatching { tunPfd?.close() }
            tunPfd = pfd

            AppLog.log(
                "OpenVPN \"$connectionName\": TUN entregue ao processo via SCM_RIGHTS " +
                    "(rotas=${snapshot.routes.size}, dns=${snapshot.dnsServers.joinToString().ifBlank { "nenhum" }})",
                AppLog.Level.SUCCESS
            )
        } catch (e: Exception) {
            runCatching { pfd.close() }
            failNeedOk("OPENTUN", e.message ?: "falha ao transmitir fd da TUN")
        }
    }

    private fun snapshotError(message: String) {
        lastFatalError = message
        onStateChange(false, message)
    }

    private fun failNeedOk(type: String, reason: String) {
        AppLog.log("OpenVPN \"$connectionName\": $type recusado — $reason", AppLog.Level.ERROR)
        snapshotError(reason)
        sendNeedOk(type, "cancel")
    }

    private fun sendNeedOk(type: String, result: String) {
        sendCommandRaw("needok '$type' $result\n")
    }

    private fun handleState(line: String) {
        val parts = line.removePrefix(">STATE:").split(",")
        val stateName = parts.getOrNull(1)?.trim() ?: return
        val detail = parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }

        when (stateName) {
            "CONNECTED" -> {
                connected = true
                lastFatalError = null
                onStateChange(true, null)
                AppLog.log(
                    "OpenVPN \"$connectionName\": CONNECTED — túnel Android estabelecido",
                    AppLog.Level.SUCCESS
                )
            }
            "RECONNECTING" -> {
                connected = false
                AppLog.log(
                    "OpenVPN \"$connectionName\": reconectando${detail?.let { " ($it)" } ?: ""}",
                    AppLog.Level.INFO
                )
            }
            "EXITING" -> {
                connected = false
                onStateChange(false, detail)
            }
            else -> AppLog.log(
                "OpenVPN \"$connectionName\": estado $stateName${detail?.let { " ($it)" } ?: ""}",
                AppLog.Level.INFO
            )
        }
    }

    private fun handleByteCount(line: String) {
        val parts = line.removePrefix(">BYTECOUNT:").split(",")
        val rx = parts.getOrNull(0)?.toLongOrNull() ?: return
        val tx = parts.getOrNull(1)?.toLongOrNull() ?: return
        onBytesUpdate(rx, tx)
    }

    private fun sendCommandRaw(command: String): Boolean {
        val socket = managementSocket ?: return false
        return runCatching {
            val out = socket.outputStream
            out.write(command.toByteArray(Charsets.UTF_8))
            out.flush()
        }.onFailure {
            AppLog.log(
                "OpenVPN \"$connectionName\": falha ao enviar management command: ${it.message}",
                AppLog.Level.ERROR
            )
        }.isSuccess
    }

    private fun closeAncillaryFds(fds: List<FileDescriptor>) {
        fds.distinctBy { System.identityHashCode(it) }.forEach { fd ->
            runCatching { Os.close(fd) }
        }
    }

    private fun tokenize(message: String): List<String> =
        message.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun netmaskToPrefix(mask: String): Int {
        val parts = mask.split('.')
        require(parts.size == 4) { "Netmask IPv4 inválida: $mask" }

        var prefix = 0
        var zeroSeen = false
        parts.forEach { part ->
            val value = part.toIntOrNull()
                ?: throw IllegalArgumentException("Netmask IPv4 inválida: $mask")
            require(value in 0..255) { "Netmask IPv4 inválida: $mask" }

            for (bit in 7 downTo 0) {
                val one = (value and (1 shl bit)) != 0
                if (one) {
                    require(!zeroSeen) { "Netmask IPv4 não contígua: $mask" }
                    prefix++
                } else {
                    zeroSeen = true
                }
            }
        }
        return prefix
    }
}
