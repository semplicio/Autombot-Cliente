package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

internal enum class InjectorLogLevel { INFO, SUCCESS, ERROR }

internal data class InjectorLogEntry(
    val timestampMs: Long,
    val level: InjectorLogLevel,
    val message: String
) {
    fun formatted(): String {
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(timestampMs))
        return "[$timestamp] ${level.name}: $message"
    }
}

internal data class InjectorFlowConfig(
    val targetHost: String,
    val targetPort: Int,
    val entryHost: String,
    val entryPort: Int,
    val entryTls: Boolean,
    val sni: String,
    val httpHost: String,
    val payload: String,
    val forceCellular: Boolean,
    val timeoutSeconds: Int = 15,
    val maxAttempts: Int = 3
)

internal data class InjectorFlowReport(
    val success: Boolean,
    val attempts: Int,
    val localPort: Int,
    val networkLabel: String,
    val logs: List<InjectorLogEntry>
) {
    fun text(): String = buildString {
        appendLine("AUTOMBOT NETWORK PROBE — HTTP PROXY ➔ SSH (CUSTOM PAYLOAD)")
        appendLine("Resultado: ${if (success) "SUCESSO" else "FALHA"}")
        appendLine("Rede: $networkLabel")
        appendLine("Tentativas: $attempts")
        appendLine("Proxy local: 127.0.0.1:$localPort")
        appendLine()
        logs.forEach { appendLine(it.formatted()) }
    }.trimEnd()
}

/**
 * Reproduz, para diagnóstico, a cadeia observável de um injetor HTTP:
 *
 * cliente SSH de prova -> proxy local -> entrada autorizada -> payload -> banner SSH.
 *
 * O teste para no banner SSH-2.0. Ele não recebe nem persiste credenciais e não faz
 * autenticação no servidor. Assim conseguimos separar falha de rede/payload de falha
 * de usuário/senha sem transformar o Network Probe em um cliente SSH completo.
 */
internal class InjectorFlowProbe(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var activeServer: ServerSocket? = null

    fun cancel() {
        activeServer?.let { runCatching { it.close() } }
        activeSockets.toList().forEach { socket -> runCatching { socket.close() } }
    }

    suspend fun run(
        config: InjectorFlowConfig,
        onLog: suspend (InjectorLogEntry) -> Unit = {}
    ): InjectorFlowReport = withContext(Dispatchers.IO) {
        require(config.targetHost.isNotBlank()) { "Informe o servidor SSH." }
        require(config.targetPort in 1..65535) { "Porta SSH inválida." }
        require(config.entryHost.isNotBlank()) { "Informe a entrada/proxy que receberá o payload." }
        require(config.entryPort in 1..65535) { "Porta da entrada inválida." }
        require(config.payload.isNotBlank()) { "Informe o payload personalizado." }

        val logs = mutableListOf<InjectorLogEntry>()
        suspend fun emit(message: String, level: InjectorLogLevel = InjectorLogLevel.INFO) {
            val entry = InjectorLogEntry(System.currentTimeMillis(), level, message)
            logs += entry
            onLog(entry)
        }

        val network = selectPhysicalNetwork(config.forceCellular)
            ?: error("Nenhuma rede física compatível foi encontrada.")
        val snapshot = networkSnapshot(network)
        val timeoutMs = config.timeoutSeconds.coerceIn(5, 60) * 1000
        val attemptsLimit = config.maxAttempts.coerceIn(1, 5)

        emit("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android API ${Build.VERSION.SDK_INT} (${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()})")
        emit("App version: ${BuildConfig.VERSION_NAME} Build ${BuildConfig.VERSION_CODE}")
        emit("IP local: ${snapshot.localIp}")
        emit("Modo de túneis HTTP Proxy ➔ SSH (Custom Payload)")
        emit("[INICIAR] serviço solicitado")
        emit("Status da Conexão: CONNECTED ${snapshot.type}${snapshot.operatorSuffix}")
        emit("Local IP: ${snapshot.localIp}")
        emit("Network available: [type: ${snapshot.type}, state: CONNECTED, interface: ${snapshot.interfaceName}, validated: ${snapshot.validated}]")

        var attemptsUsed = 0
        var success = false
        val server = ServerSocket()
        activeServer = server
        try {
            server.use { localServer ->
            localServer.reuseAddress = true
            localServer.bind(InetSocketAddress("127.0.0.1", 0))
            localServer.soTimeout = timeoutMs

            emit("Serviço de Injeção Iniciado")
            emit("Iniciando porta local listada: ${localServer.localPort}")
            emit("Iniciando Serviço de Rede")
            emit("Aguardando uma conexão de entrada")
            emit("Type: ${snapshot.type} | State: CONNECTED${snapshot.operatorSuffix}")

            for (attempt in 1..attemptsLimit) {
                attemptsUsed = attempt
                if (attempt > 1) emit("Reconectando... (tentativa $attempt/$attemptsLimit)")
                emit("Iniciar o serviço de túnel")
                emit("Compressão SSH: não negociada neste probe; validação limitada ao banner SSH-2.0")
                emit("Tamanho do Buffer: Enviar: $SEND_BUFFER_BYTES | Receber: $RECEIVE_BUFFER_BYTES")

                val result = runCatching {
                    runAttempt(network, localServer, config, timeoutMs, ::emit)
                }.getOrElse { error ->
                    val detail = when (error) {
                        is SocketTimeoutException -> "The connect timeout expired (${error.message ?: "etapa sem resposta"})"
                        else -> error.message ?: error.javaClass.simpleName
                    }
                    AttemptResult(false, detail)
                }

                if (result.success) {
                    emit(result.detail, InjectorLogLevel.SUCCESS)
                    success = true
                    break
                }

                emit("Conexão perdida ${result.detail}", InjectorLogLevel.ERROR)
                if (attempt < attemptsLimit) {
                    val waitSeconds = attempt.coerceAtMost(MAX_RECONNECT_DELAY_SECONDS)
                    emit("Waiting for ${waitSeconds}s before reconnecting")
                    delay(waitSeconds * 1000L)
                }
            }

            if (!success) {
                emit("Fluxo encerrado após $attemptsUsed tentativa(s), sem banner SSH confirmado", InjectorLogLevel.ERROR)
            }

                InjectorFlowReport(
                    success = success,
                    attempts = attemptsUsed,
                    localPort = localServer.localPort,
                    networkLabel = snapshot.label,
                    logs = logs.toList()
                )
            }
        } finally {
            if (activeServer === server) activeServer = null
            activeSockets.clear()
        }
    }

    private suspend fun runAttempt(
        network: Network,
        localServer: ServerSocket,
        config: InjectorFlowConfig,
        timeoutMs: Int,
        emit: suspend (String, InjectorLogLevel) -> Unit
    ): AttemptResult = coroutineScope {
        val injector = async(Dispatchers.IO) {
            var stage = "aguardando cliente local"
            try {
                tracked(localServer.accept()) { local ->
                    tune(local, timeoutMs)
                    stage = "recebendo HTTP CONNECT do cliente local"
                    val localInput = BufferedInputStream(local.getInputStream())
                    val connectLine = readLine(localInput)
                        ?: throw IOException("cliente local não enviou HTTP CONNECT")
                    val expectedAuthority = "${config.targetHost}:${config.targetPort}"
                    val requestedAuthority = connectLine
                        .split(Regex("\\s+"))
                        .takeIf { it.size >= 3 && it[0].equals("CONNECT", ignoreCase = true) }
                        ?.get(1)
                        ?: throw IOException("requisição do proxy local inválida: $connectLine")
                    while (true) {
                        val header = readLine(localInput) ?: throw IOException("cabeçalho HTTP CONNECT local incompleto")
                        if (header.isEmpty()) break
                    }
                    if (!requestedAuthority.equals(expectedAuthority, ignoreCase = true)) {
                        throw IOException("destino local não autorizado: $requestedAuthority")
                    }

                    stage = "abrindo entrada ${config.entryHost}:${config.entryPort}"
                    tracked(openEntry(network, config, timeoutMs)) { upstream ->
                        tune(upstream, timeoutMs)
                        emit("Proxy em Execução", InjectorLogLevel.INFO)

                        stage = "enviando payload"
                        val expandedPayload = expandPayload(config)
                        emit("Enviando payload", InjectorLogLevel.INFO)
                        upstream.getOutputStream().apply {
                            write(expandedPayload.toByteArray(Charsets.UTF_8))
                            flush()
                        }
                        local.getOutputStream().apply {
                            write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: AutomBot-NetworkProbe/1.7\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }

                        stage = "encaminhando identificação SSH do cliente"
                        val clientBanner = readLine(localInput)
                            ?: throw IOException("cliente SSH local não enviou identificação")
                        upstream.getOutputStream().apply {
                            write((clientBanner + "\r\n").toByteArray(Charsets.US_ASCII))
                            flush()
                        }

                        stage = "aguardando resposta do gateway/banner SSH"
                        val upstreamInput = BufferedInputStream(upstream.getInputStream())
                        val response = readGatewayResponse(upstreamInput)
                        local.getOutputStream().apply {
                            response.rawLines.forEach { line ->
                                write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                            }
                            flush()
                        }
                        if (response.banner != null) {
                            AttemptResult(
                                true,
                                "Conectado — ${response.banner} recebido através do proxy local 127.0.0.1:${localServer.localPort}"
                            )
                        } else {
                            AttemptResult(false, response.failureDetail)
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is SocketTimeoutException) {
                    throw SocketTimeoutException("timeout em $stage")
                }
                throw IOException("falha em $stage: ${error.message ?: error.javaClass.simpleName}", error)
            }
        }

        val localSshProbe = async(Dispatchers.IO) {
            tracked(Socket()) { client ->
                tune(client, timeoutMs)
                client.connect(InetSocketAddress("127.0.0.1", localServer.localPort), timeoutMs)
                client.getOutputStream().apply {
                    val authority = "${config.targetHost}:${config.targetPort}"
                    write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\nProxy-Connection: Keep-Alive\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val input = BufferedInputStream(client.getInputStream())
                val statusLine = readLine(input)
                    ?: throw IOException("proxy local não respondeu ao HTTP CONNECT")
                val status = statusLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                while (true) {
                    val header = readLine(input) ?: throw IOException("resposta HTTP CONNECT local incompleta")
                    if (header.isEmpty()) break
                }
                if (status == null || status !in 200..299) {
                    throw IOException("proxy local recusou HTTP CONNECT: $statusLine")
                }
                client.getOutputStream().apply {
                    write((CLIENT_SSH_BANNER + "\r\n").toByteArray(Charsets.US_ASCII))
                    flush()
                }
                // Drena a resposta encaminhada. A classificação é feita pelo lado
                // injetor, que enxerga também o cabeçalho HTTP completo.
                runCatching {
                    while (readLine(input) != null) Unit
                }
            }
        }

        val result = injector.await()
        localSshProbe.await()
        result
    }

    private fun openEntry(network: Network, config: InjectorFlowConfig, timeoutMs: Int): Socket {
        val addresses = network.getAllByName(config.entryHost)
            .sortedBy { if (it is Inet4Address) 0 else 1 }
        var lastError: Exception? = null
        for (address in addresses) {
            var candidate: Socket? = null
            try {
                val raw = network.socketFactory.createSocket()
                candidate = raw
                raw.connect(InetSocketAddress(address, config.entryPort), timeoutMs)
                if (!config.entryTls) return raw

                val context = SSLContext.getInstance("TLS")
                context.init(null, null, null)
                val ssl = context.socketFactory.createSocket(
                    raw,
                    config.entryHost,
                    config.entryPort,
                    true
                ) as SSLSocket
                candidate = ssl
                val serverName = config.sni.ifBlank { config.entryHost }
                val parameters: SSLParameters = ssl.sslParameters
                parameters.serverNames = listOf(SNIHostName(serverName))
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = parameters
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                return ssl
            } catch (error: Exception) {
                runCatching { candidate?.close() }
                lastError = error
            }
        }
        throw lastError ?: IOException("Não foi possível resolver/conectar em ${config.entryHost}:${config.entryPort}")
    }

    private fun readGatewayResponse(input: BufferedInputStream): GatewayResponse {
        val first = readLine(input) ?: throw SocketTimeoutException("nenhuma resposta após o payload")
        if (first.startsWith("SSH-2.0-")) {
            return GatewayResponse(first, listOf(first), "")
        }
        if (!first.startsWith("HTTP/", ignoreCase = true)) {
            return GatewayResponse(null, listOf(first), "resposta inesperada: $first")
        }

        val raw = mutableListOf(first)
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("cabeçalho HTTP incompleto")
            raw += line
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        val status = first.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
        if (status == null || (status != 101 && status !in 200..299)) {
            val location = headers["location"]?.let { "; Location=$it" }.orEmpty()
            return GatewayResponse(null, raw, "unexpected HTTP response status: ${status ?: "inválido"}$location")
        }

        val banner = readLine(input) ?: throw SocketTimeoutException("HTTP $status aceito, mas o banner SSH não chegou")
        raw += banner
        return if (banner.startsWith("SSH-2.0-")) {
            GatewayResponse(banner, raw, "")
        } else {
            GatewayResponse(null, raw, "HTTP $status aceito, porém a próxima resposta não foi SSH: $banner")
        }
    }

    private fun expandPayload(config: InjectorFlowConfig): String = config.payload
        .replace("[crlf]", "\r\n", ignoreCase = true)
        .replace("[host]", config.targetHost, ignoreCase = true)
        .replace("[port]", config.targetPort.toString(), ignoreCase = true)
        .replace("[entry_host]", config.entryHost, ignoreCase = true)
        .replace("[entry_port]", config.entryPort.toString(), ignoreCase = true)
        .replace("[proxy_host]", config.entryHost, ignoreCase = true)
        .replace("[proxy_port]", config.entryPort.toString(), ignoreCase = true)
        .replace("[sni]", config.sni.ifBlank { config.entryHost }, ignoreCase = true)
        .replace("[http_host]", config.httpHost.ifBlank { config.entryHost }, ignoreCase = true)

    private fun readLine(input: BufferedInputStream): String? {
        val output = ByteArrayOutputStream()
        while (output.size() < MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (output.size() == 0) null else output.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (value == '\n'.code) return output.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            output.write(value)
        }
        throw IOException("linha de resposta excedeu $MAX_LINE_BYTES bytes")
    }

    private fun tune(socket: Socket, timeoutMs: Int) {
        socket.soTimeout = timeoutMs
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.keepAlive = true }
        runCatching { socket.sendBufferSize = SEND_BUFFER_BYTES }
        runCatching { socket.receiveBufferSize = RECEIVE_BUFFER_BYTES }
    }

    private inline fun <T> tracked(socket: Socket, block: (Socket) -> T): T {
        activeSockets += socket
        return try {
            socket.use(block)
        } finally {
            activeSockets -= socket
        }
    }

    private fun selectPhysicalNetwork(forceCellular: Boolean): Network? {
        fun isPhysical(network: Network): Boolean {
            val caps = connectivity.getNetworkCapabilities(network) ?: return false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            return !forceCellular || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }

        connectivity.activeNetwork?.takeIf(::isPhysical)?.let { return it }
        return connectivity.allNetworks
            .filter(::isPhysical)
            .sortedWith(
                compareByDescending<Network> {
                    connectivity.getNetworkCapabilities(it)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                }.thenByDescending {
                    connectivity.getNetworkCapabilities(it)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                }
            )
            .firstOrNull()
    }

    private fun networkSnapshot(network: Network): NetworkSnapshot {
        val caps = connectivity.getNetworkCapabilities(network)
        val links = connectivity.getLinkProperties(network)
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "MOBILE${mobileGeneration()}"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
            else -> "OTHER"
        }
        val operator = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            runCatching { telephony.networkOperatorName.orEmpty().trim() }.getOrDefault("")
        } else ""
        val localIp = links?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
            ?: links?.linkAddresses?.firstOrNull()?.address?.hostAddress?.substringBefore('%')
            ?: "indisponível"
        return NetworkSnapshot(
            type = type,
            localIp = localIp,
            interfaceName = links?.interfaceName ?: "indisponível",
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            operatorSuffix = operator.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        )
    }

    @Suppress("DEPRECATION")
    private fun mobileGeneration(): String {
        val networkType = runCatching { telephony.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val generation = when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> ""
        }
        return generation.takeIf(String::isNotBlank)?.let { "[$it]" }.orEmpty()
    }

    private data class AttemptResult(val success: Boolean, val detail: String)

    private data class GatewayResponse(
        val banner: String?,
        val rawLines: List<String>,
        val failureDetail: String
    )

    private data class NetworkSnapshot(
        val type: String,
        val localIp: String,
        val interfaceName: String,
        val validated: Boolean,
        val operatorSuffix: String
    ) {
        val label: String
            get() = "$type$operatorSuffix · $interfaceName · $localIp · validated=$validated"
    }

    private companion object {
        const val SEND_BUFFER_BYTES = 16 * 1024
        const val RECEIVE_BUFFER_BYTES = 32 * 1024
        const val MAX_RECONNECT_DELAY_SECONDS = 5
        const val MAX_LINE_BYTES = 32 * 1024
        const val CLIENT_SSH_BANNER = "SSH-2.0-AutomBotNetworkProbe_1.7"
    }
}
