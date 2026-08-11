package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

enum class ProxyKind { AUTO, HTTP, SOCKS5 }

data class ProxyAnalyzerConfig(
    val proxyHost: String,
    val proxyPort: Int,
    val kind: ProxyKind = ProxyKind.AUTO,
    val username: String = "",
    val password: String = "",
    val targetHost: String = "core.infinitenet.net",
    val targetPort: Int = 443,
    val webSocketPath: String = "/"
)

data class ProxyPortCandidate(
    val port: Int,
    val latencyMs: Long
)

data class ProxyAnalyzerReport(
    val config: ProxyAnalyzerConfig,
    val networkLabel: String,
    val proxyAddresses: List<String>,
    val results: List<ProbeResult>,
    val capabilities: List<String>,
    val recommendation: String,
    val manual: String,
    val startedAtMs: Long,
    val finishedAtMs: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("tool", "AutomBot Proxy Analyzer")
        put("version", "0.2.0")
        put("started_at_ms", startedAtMs)
        put("finished_at_ms", finishedAtMs)
        put("network", networkLabel)
        put("proxy", JSONObject().apply {
            put("host", config.proxyHost)
            put("port", config.proxyPort)
            put("requested_kind", config.kind.name.lowercase())
            put("resolved_addresses", JSONArray(proxyAddresses))
        })
        put("target", JSONObject().apply {
            put("host", config.targetHost)
            put("port", config.targetPort)
            put("websocket_path", config.webSocketPath)
        })
        put("results", JSONArray().apply {
            results.forEach { result ->
                put(JSONObject().apply {
                    put("name", result.name)
                    put("status", result.status.name.lowercase())
                    put("detail", result.detail)
                })
            }
        })
        put("capabilities", JSONArray(capabilities))
        put("recommendation", recommendation)
        put("connection_manual", manual)
    }.toString(2)
}

class ProxyAnalyzerEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun detectCommonPorts(proxyHost: String): List<ProxyPortCandidate> = withContext(Dispatchers.IO) {
        if (proxyHost.isBlank()) return@withContext emptyList()
        val network = selectPhysicalNetwork() ?: return@withContext emptyList()
        val addresses = runCatching { network.getAllByName(proxyHost).toList() }.getOrDefault(emptyList())
        if (addresses.isEmpty()) return@withContext emptyList()
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        val found = mutableListOf<ProxyPortCandidate>()

        for (port in COMMON_PROXY_PORTS) {
            val started = System.nanoTime()
            val opened = runCatching {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), PORT_DETECT_TIMEOUT_MS)
                }
            }.isSuccess
            if (opened) {
                found += ProxyPortCandidate(port, elapsedMs(started))
            }
        }
        found.sortedBy { it.latencyMs }
    }

    suspend fun analyze(config: ProxyAnalyzerConfig): ProxyAnalyzerReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val network = selectPhysicalNetwork()
            ?: return@withContext ProxyAnalyzerReport(
                config = config,
                networkLabel = "Sem rede física",
                proxyAddresses = emptyList(),
                results = listOf(ProbeResult("Rede física", ProbeStatus.FAIL, "Nenhuma rede física disponível")),
                capabilities = emptyList(),
                recommendation = "Conecte o aparelho a uma rede antes de testar o proxy.",
                manual = "Nenhum manual foi gerado porque não existe uma rede física disponível.",
                startedAtMs = startedAt,
                finishedAtMs = System.currentTimeMillis()
            )

        val caps = connectivityManager.getNetworkCapabilities(network)
        val networkLabel = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Rede móvel"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Outra rede"
        }

        val results = mutableListOf<ProbeResult>()
        val addresses = runCatching { network.getAllByName(config.proxyHost).toList() }
            .onSuccess {
                results += ProbeResult(
                    "DNS do proxy",
                    ProbeStatus.PASS,
                    it.joinToString { addr -> addr.hostAddress.orEmpty().substringBefore('%') }
                )
            }
            .onFailure {
                results += ProbeResult("DNS do proxy", ProbeStatus.FAIL, it.message ?: it.javaClass.simpleName)
            }
            .getOrDefault(emptyList())

        if (addresses.isEmpty()) {
            return@withContext finish(config, networkLabel, emptyList(), results, startedAt)
        }

        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        results += tcpProxyProbe(network, address, config.proxyPort)

        if (config.kind == ProxyKind.AUTO || config.kind == ProxyKind.HTTP) {
            val connect = httpConnectProbe(network, address, config)
            results += connect
            if (connect.status == ProbeStatus.PASS) {
                results += tlsViaHttpConnectProbe(network, address, config)
                results += wssViaHttpConnectProbe(network, address, config)
            }
        }

        if (config.kind == ProxyKind.AUTO || config.kind == ProxyKind.SOCKS5) {
            val connect = socks5ConnectProbe(network, address, config)
            results += connect
            if (connect.status == ProbeStatus.PASS) {
                results += tlsViaSocks5Probe(network, address, config)
                results += wssViaSocks5Probe(network, address, config)
            }
            results += socks5UdpAssociateProbe(network, address, config)
        }

        finish(
            config,
            networkLabel,
            addresses.mapNotNull { it.hostAddress?.substringBefore('%') },
            results,
            startedAt
        )
    }

    private fun finish(
        config: ProxyAnalyzerConfig,
        networkLabel: String,
        addresses: List<String>,
        results: List<ProbeResult>,
        startedAt: Long
    ): ProxyAnalyzerReport {
        val httpConnect = passed(results, "HTTP CONNECT")
        val httpTls = passed(results, "TLS via HTTP CONNECT")
        val httpWss = passed(results, "WSS via HTTP CONNECT")
        val socksConnect = passed(results, "SOCKS5 CONNECT")
        val socksTls = passed(results, "TLS via SOCKS5")
        val socksWss = passed(results, "WSS via SOCKS5")
        val socksUdp = passed(results, "SOCKS5 UDP ASSOCIATE")
        val tcp = results.firstOrNull { it.name.startsWith("TCP do proxy") }?.status == ProbeStatus.PASS

        val capabilities = mutableListOf<String>()
        if (httpConnect) {
            capabilities += "HTTP CONNECT: túnel TCP confirmado até ${config.targetHost}:${config.targetPort}"
            capabilities += "SSH/OpenVPN TCP são candidatos quando a porta final corresponde ao serviço e o cliente suporta proxy HTTP"
        }
        if (httpTls) {
            capabilities += "TLS válido através do HTTP CONNECT: HTTPS e transportes TLS podem usar esse caminho"
        }
        if (httpWss) {
            capabilities += "WebSocket Seguro (WSS) confirmado através do HTTP CONNECT no path ${normalizedPath(config.webSocketPath)}"
            capabilities += "VMess/VLESS sobre WSS são candidatos quando configurados nesse endpoint e o cliente suporta upstream HTTP CONNECT"
        }
        if (socksConnect) {
            capabilities += "SOCKS5 CONNECT: túnel TCP confirmado até ${config.targetHost}:${config.targetPort}"
            capabilities += "SSH/TCP e OpenVPN TCP são candidatos quando o cliente suporta upstream SOCKS5"
        }
        if (socksTls) {
            capabilities += "TLS válido através do SOCKS5: transportes TLS podem usar esse upstream"
        }
        if (socksWss) {
            capabilities += "WSS confirmado através do SOCKS5 no path ${normalizedPath(config.webSocketPath)}"
        }
        if (socksUdp) {
            capabilities += "SOCKS5 UDP ASSOCIATE aceito; relay UDP real ainda precisa de teste fim a fim e suporte explícito do cliente"
        }
        if (tcp && !httpConnect && !socksConnect) {
            capabilities += "A porta do proxy está acessível, mas HTTP CONNECT e SOCKS5 não foram confirmados"
        }

        val recommendation = when {
            httpWss ->
                "HTTP CONNECT + TLS + WSS foram confirmados. Este é o caminho mais completo observado e o manual abaixo mostra como reproduzir a configuração com o endpoint testado."
            socksWss ->
                "SOCKS5 + TLS + WSS foram confirmados. Use upstream SOCKS5 apenas nos protocolos do AutomBot que suportem esse encadeamento."
            httpTls ->
                "HTTP CONNECT e TLS foram confirmados. Priorize transportes TCP/TLS compatíveis com proxy HTTP."
            socksTls ->
                "SOCKS5 CONNECT e TLS foram confirmados. Priorize transportes TCP/TLS com suporte a upstream SOCKS5."
            httpConnect && socksConnect ->
                "O endpoint aceita HTTP CONNECT e SOCKS5. Compare o suporte de cada protocolo do AutomBot antes de escolher o upstream."
            httpConnect ->
                "Proxy HTTP CONNECT funcional. SSH/OpenVPN TCP e outros transportes TCP são candidatos quando a porta final corresponde ao serviço."
            socksConnect && socksUdp ->
                "SOCKS5 funcional para TCP e com UDP ASSOCIATE disponível. UDP fim a fim ainda precisa ser validado."
            socksConnect ->
                "SOCKS5 CONNECT funcional para TCP. Use somente em protocolos que suportem upstream SOCKS5."
            tcp ->
                "A rede alcança a porta do proxy, mas o tipo de proxy não foi confirmado. Verifique autenticação, tipo do servidor ou outra porta comum."
            else ->
                "O proxy não está alcançável nesta rede. Use a detecção de portas comuns ou compare o mesmo proxy no Wi-Fi e na rede móvel."
        }

        val manual = buildProxyConnectionManual(config, networkLabel, results)

        return ProxyAnalyzerReport(
            config = config,
            networkLabel = networkLabel,
            proxyAddresses = addresses,
            results = results,
            capabilities = capabilities,
            recommendation = recommendation,
            manual = manual,
            startedAtMs = startedAt,
            finishedAtMs = System.currentTimeMillis()
        )
    }

    private fun tcpProxyProbe(network: Network, address: InetAddress, port: Int): ProbeResult {
        val started = System.nanoTime()
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            }
            ProbeResult(
                "TCP do proxy $port",
                ProbeStatus.PASS,
                "Conexão com ${address.hostAddress}:$port em ${elapsedMs(started)} ms"
            )
        } catch (error: Exception) {
            ProbeResult(
                "TCP do proxy $port",
                ProbeStatus.FAIL,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun httpConnectProbe(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): ProbeResult {
        val started = System.nanoTime()
        var tunnel: HttpTunnel? = null
        return try {
            tunnel = establishHttpTunnel(network, address, config)
            when {
                tunnel.code in 200..299 -> ProbeResult(
                    "HTTP CONNECT",
                    ProbeStatus.PASS,
                    "${tunnel.statusLine} · túnel até ${config.targetHost}:${config.targetPort} em ${elapsedMs(started)} ms"
                )
                tunnel.code == 407 -> ProbeResult(
                    "HTTP CONNECT",
                    ProbeStatus.WARN,
                    "${tunnel.statusLine} · autenticação do proxy necessária ou credencial rejeitada"
                )
                tunnel.code != null -> ProbeResult(
                    "HTTP CONNECT",
                    ProbeStatus.FAIL,
                    "${tunnel.statusLine} · CONNECT não autorizado para o destino informado"
                )
                else -> ProbeResult(
                    "HTTP CONNECT",
                    ProbeStatus.FAIL,
                    "Resposta não reconhecida: ${tunnel.statusLine.take(120)}"
                )
            }
        } catch (error: Exception) {
            ProbeResult("HTTP CONNECT", ProbeStatus.FAIL, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { tunnel?.socket?.close() }
        }
    }

    private fun tlsViaHttpConnectProbe(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): ProbeResult = tlsThroughTunnel("TLS via HTTP CONNECT", config) {
        val tunnel = establishHttpTunnel(network, address, config)
        if (tunnel.code !in 200..299) {
            tunnel.socket.close()
            error("HTTP CONNECT respondeu ${tunnel.statusLine}")
        }
        tunnel.socket
    }

    private fun wssViaHttpConnectProbe(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): ProbeResult = wssThroughTunnel("WSS via HTTP CONNECT", config) {
        val tunnel = establishHttpTunnel(network, address, config)
        if (tunnel.code !in 200..299) {
            tunnel.socket.close()
            error("HTTP CONNECT respondeu ${tunnel.statusLine}")
        }
        tunnel.socket
    }

    private fun socks5ConnectProbe(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): ProbeResult {
        val started = System.nanoTime()
        return try {
            establishSocks5Tunnel(network, address, config).use {
                ProbeResult(
                    "SOCKS5 CONNECT",
                    ProbeStatus.PASS,
                    "SOCKS5 abriu ${config.targetHost}:${config.targetPort} em ${elapsedMs(started)} ms"
                )
            }
        } catch (error: Exception) {
            ProbeResult("SOCKS5 CONNECT", ProbeStatus.FAIL, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun tlsViaSocks5Probe(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): ProbeResult = tlsThroughTunnel("TLS via SOCKS5", config) {
        establishSocks5Tunnel(network, address, config)
    }

    private fun wssViaSocks5Probe(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): ProbeResult = wssThroughTunnel("WSS via SOCKS5", config) {
        establishSocks5Tunnel(network, address, config)
    }

    private fun socks5UdpAssociateProbe(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): ProbeResult {
        return try {
            openProxySocket(network, address, config.proxyPort).use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                socks5Negotiate(input, output, config)
                val reply = socks5Command(input, output, 0x03, "0.0.0.0", 0)
                if (reply == 0x00) {
                    ProbeResult(
                        "SOCKS5 UDP ASSOCIATE",
                        ProbeStatus.PASS,
                        "O proxy aceitou UDP ASSOCIATE. Isso confirma a capacidade do método, não o tráfego UDP fim a fim."
                    )
                } else {
                    ProbeResult(
                        "SOCKS5 UDP ASSOCIATE",
                        ProbeStatus.WARN,
                        "SOCKS5 reply=0x${reply.toString(16)}; relay UDP não disponível"
                    )
                }
            }
        } catch (error: Exception) {
            ProbeResult("SOCKS5 UDP ASSOCIATE", ProbeStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun tlsThroughTunnel(
        label: String,
        config: ProxyAnalyzerConfig,
        tunnelFactory: () -> Socket
    ): ProbeResult {
        val started = System.nanoTime()
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            raw = tunnelFactory()
            val context = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
            ssl = context.socketFactory.createSocket(raw, config.targetHost, config.targetPort, true) as SSLSocket
            ssl.soTimeout = TIMEOUT_MS
            val parameters = ssl.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = parameters
            ssl.startHandshake()
            ProbeResult(
                label,
                ProbeStatus.PASS,
                "${ssl.session.protocol} válido para ${config.targetHost} em ${elapsedMs(started)} ms"
            )
        } catch (error: Exception) {
            ProbeResult(label, ProbeStatus.FAIL, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
        }
    }

    private fun wssThroughTunnel(
        label: String,
        config: ProxyAnalyzerConfig,
        tunnelFactory: () -> Socket
    ): ProbeResult {
        val started = System.nanoTime()
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            raw = tunnelFactory()
            val context = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
            ssl = context.socketFactory.createSocket(raw, config.targetHost, config.targetPort, true) as SSLSocket
            ssl.soTimeout = TIMEOUT_MS
            val parameters = ssl.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = parameters
            ssl.startHandshake()

            val output = BufferedOutputStream(ssl.getOutputStream())
            val input = BufferedInputStream(ssl.getInputStream())
            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val path = normalizedPath(config.webSocketPath)
            val hostHeader = if (config.targetPort == 443) config.targetHost else "${config.targetHost}:${config.targetPort}"
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            output.flush()

            val statusLine = readHttpLine(input)
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            consumeHttpHeaders(input)
            when (code) {
                101 -> ProbeResult(
                    label,
                    ProbeStatus.PASS,
                    "Upgrade WebSocket 101 confirmado em $path em ${elapsedMs(started)} ms"
                )
                null -> ProbeResult(label, ProbeStatus.FAIL, "Resposta WSS inválida: ${statusLine.take(120)}")
                else -> ProbeResult(
                    label,
                    ProbeStatus.WARN,
                    "TLS chegou ao servidor, mas o path $path respondeu HTTP $code em vez de 101"
                )
            }
        } catch (error: Exception) {
            ProbeResult(label, ProbeStatus.FAIL, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
        }
    }

    private fun establishHttpTunnel(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): HttpTunnel {
        val socket = openProxySocket(network, address, config.proxyPort)
        try {
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            val auth = if (config.username.isNotBlank()) {
                val token = Base64.getEncoder().encodeToString(
                    "${config.username}:${config.password}".toByteArray(Charsets.ISO_8859_1)
                )
                "Proxy-Authorization: Basic $token\r\n"
            } else ""

            val request = buildString {
                append("CONNECT ${config.targetHost}:${config.targetPort} HTTP/1.1\r\n")
                append("Host: ${config.targetHost}:${config.targetPort}\r\n")
                append("Proxy-Connection: keep-alive\r\n")
                append(auth)
                append("\r\n")
            }
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            output.flush()

            val statusLine = readHttpLine(input)
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            consumeHttpHeaders(input)
            return HttpTunnel(socket, statusLine, code)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun establishSocks5Tunnel(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): Socket {
        val socket = openProxySocket(network, address, config.proxyPort)
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            socks5Negotiate(input, output, config)
            val reply = socks5Command(input, output, 0x01, config.targetHost, config.targetPort)
            if (reply != 0x00) error("SOCKS5 reply=0x${reply.toString(16)}")
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun socks5Negotiate(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        config: ProxyAnalyzerConfig
    ) {
        val hasCredentials = config.username.isNotBlank()
        val methods = if (hasCredentials) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)
        output.write(methods)
        output.flush()

        val response = readExact(input, 2)
        if (response[0].toInt() and 0xff != 0x05) error("Resposta SOCKS5 inválida")
        when (response[1].toInt() and 0xff) {
            0x00 -> Unit
            0x02 -> {
                if (!hasCredentials) error("SOCKS5 exige usuário/senha")
                val user = config.username.toByteArray(Charsets.UTF_8)
                val pass = config.password.toByteArray(Charsets.UTF_8)
                require(user.size <= 255 && pass.size <= 255) { "Credencial SOCKS5 muito longa" }
                output.write(byteArrayOf(0x01, user.size.toByte()))
                output.write(user)
                output.write(byteArrayOf(pass.size.toByte()))
                output.write(pass)
                output.flush()
                val authReply = readExact(input, 2)
                if (authReply[1].toInt() and 0xff != 0x00) error("Autenticação SOCKS5 rejeitada")
            }
            0xff -> error("SOCKS5 não aceitou os métodos de autenticação oferecidos")
            else -> error("Método SOCKS5 não suportado pelo analisador")
        }
    }

    private fun socks5Command(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        command: Int,
        targetHost: String,
        targetPort: Int
    ): Int {
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        require(hostBytes.size <= 255) { "Host de destino muito longo" }
        output.write(byteArrayOf(0x05, command.toByte(), 0x00, 0x03, hostBytes.size.toByte()))
        output.write(hostBytes)
        output.write(byteArrayOf(((targetPort ushr 8) and 0xff).toByte(), (targetPort and 0xff).toByte()))
        output.flush()

        val header = readExact(input, 4)
        if (header[0].toInt() and 0xff != 0x05) error("Resposta SOCKS5 inválida")
        val reply = header[1].toInt() and 0xff
        when (header[3].toInt() and 0xff) {
            0x01 -> readExact(input, 4)
            0x03 -> readExact(input, readExact(input, 1)[0].toInt() and 0xff)
            0x04 -> readExact(input, 16)
            else -> error("ATYP SOCKS5 inválido")
        }
        readExact(input, 2)
        return reply
    }

    private fun openProxySocket(network: Network, address: InetAddress, port: Int): Socket =
        network.socketFactory.createSocket().apply {
            connect(InetSocketAddress(address, port), TIMEOUT_MS)
            soTimeout = TIMEOUT_MS
        }

    private fun readHttpLine(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 4096) {
            val value = input.read()
            if (value < 0) break
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun consumeHttpHeaders(input: BufferedInputStream) {
        repeat(100) {
            if (readHttpLine(input).isBlank()) return
        }
    }

    private fun readExact(input: BufferedInputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) error("Conexão encerrada pelo proxy")
            offset += read
        }
        return buffer
    }

    private fun selectPhysicalNetwork(): Network? {
        val candidates = LinkedHashSet<Network>()
        connectivityManager.activeNetwork?.let { candidates += it }
        connectivityManager.allNetworks.forEach { candidates += it }
        return candidates.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            Triple(
                network,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            )
        }.sortedWith(
            compareByDescending<Triple<Network, Boolean, Boolean>> { it.second }
                .thenByDescending { it.third }
        ).firstOrNull()?.first
    }

    private fun passed(results: List<ProbeResult>, name: String): Boolean =
        results.firstOrNull { it.name == name }?.status == ProbeStatus.PASS

    private fun normalizedPath(path: String): String = when {
        path.isBlank() -> "/"
        path.startsWith('/') -> path
        else -> "/$path"
    }

    private fun elapsedMs(startNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private data class HttpTunnel(
        val socket: Socket,
        val statusLine: String,
        val code: Int?
    )

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val PORT_DETECT_TIMEOUT_MS = 1_200
        val COMMON_PROXY_PORTS = intArrayOf(80, 443, 1080, 3128, 8080, 8000, 8118, 8888, 8889, 9090)
    }
}
