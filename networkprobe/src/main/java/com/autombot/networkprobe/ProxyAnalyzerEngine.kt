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
import java.util.Base64
import java.util.concurrent.TimeUnit

enum class ProxyKind { AUTO, HTTP, SOCKS5 }

data class ProxyAnalyzerConfig(
    val proxyHost: String,
    val proxyPort: Int,
    val kind: ProxyKind = ProxyKind.AUTO,
    val username: String = "",
    val password: String = "",
    val targetHost: String = "core.infinitenet.net",
    val targetPort: Int = 443
)

data class ProxyAnalyzerReport(
    val config: ProxyAnalyzerConfig,
    val networkLabel: String,
    val proxyAddresses: List<String>,
    val results: List<ProbeResult>,
    val capabilities: List<String>,
    val recommendation: String,
    val startedAtMs: Long,
    val finishedAtMs: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("tool", "AutomBot Proxy Analyzer")
        put("version", "0.1.0")
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
    }.toString(2)
}

class ProxyAnalyzerEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
            results += httpConnectProbe(network, address, config)
        }
        if (config.kind == ProxyKind.AUTO || config.kind == ProxyKind.SOCKS5) {
            results += socks5ConnectProbe(network, address, config)
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
        val httpConnect = results.firstOrNull { it.name == "HTTP CONNECT" }?.status == ProbeStatus.PASS
        val socksConnect = results.firstOrNull { it.name == "SOCKS5 CONNECT" }?.status == ProbeStatus.PASS
        val socksUdp = results.firstOrNull { it.name == "SOCKS5 UDP ASSOCIATE" }?.status == ProbeStatus.PASS
        val tcp = results.firstOrNull { it.name.startsWith("TCP do proxy") }?.status == ProbeStatus.PASS

        val capabilities = buildList {
            if (httpConnect) {
                add("HTTP CONNECT: túnel TCP genérico confirmado até ${config.targetHost}:${config.targetPort}")
                add("Candidato para SSH via HTTP CONNECT e OpenVPN TCP quando o cliente suporta proxy HTTP")
                add("Candidato para TLS/WebSocket/VMess/VLESS somente quando o cliente suporta encadeamento por HTTP CONNECT")
            }
            if (socksConnect) {
                add("SOCKS5 CONNECT: túnel TCP genérico confirmado até ${config.targetHost}:${config.targetPort}")
                add("Candidato para SSH/TCP, OpenVPN TCP e transportes TLS/WS quando o cliente suporta upstream SOCKS5")
            }
            if (socksUdp) {
                add("SOCKS5 UDP ASSOCIATE anunciado pelo proxy; tráfego UDP real exige suporte explícito no cliente e teste de dados")
            }
            if (tcp && !httpConnect && !socksConnect) {
                add("A porta do proxy está acessível, mas nenhum método HTTP CONNECT/SOCKS5 testado foi confirmado")
            }
        }

        val recommendation = when {
            httpConnect && socksConnect ->
                "O endpoint aceita HTTP CONNECT e SOCKS5. Para maior compatibilidade com o AutomBot, priorize o método que o protocolo/cliente suporta nativamente e valide o transporte real antes de usar em produção."
            httpConnect ->
                "Proxy HTTP CONNECT funcional. Use-o apenas em transportes TCP que tenham suporte explícito a proxy HTTP/CONNECT no cliente."
            socksConnect && socksUdp ->
                "SOCKS5 funcional para TCP e com UDP ASSOCIATE disponível. O uso com protocolos UDP depende de suporte explícito ao relay SOCKS5 UDP no cliente."
            socksConnect ->
                "SOCKS5 CONNECT funcional para TCP. Transportes TCP podem ser encadeados quando o cliente oferece suporte a upstream SOCKS5."
            tcp ->
                "A rede alcança o proxy, mas o tipo de proxy não foi confirmado. Verifique autenticação, tipo e política de CONNECT do servidor."
            else ->
                "O proxy não está alcançável nesta rede. Compare o mesmo proxy no Wi-Fi e na rede móvel para separar bloqueio de rota/porta de configuração do proxy."
        }

        return ProxyAnalyzerReport(
            config = config,
            networkLabel = networkLabel,
            proxyAddresses = addresses,
            results = results,
            capabilities = capabilities,
            recommendation = recommendation,
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
        return try {
            openProxySocket(network, address, config.proxyPort).use { socket ->
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
                when {
                    code in 200..299 -> ProbeResult(
                        "HTTP CONNECT",
                        ProbeStatus.PASS,
                        "$statusLine · túnel até ${config.targetHost}:${config.targetPort} em ${elapsedMs(started)} ms"
                    )
                    code == 407 -> ProbeResult(
                        "HTTP CONNECT",
                        ProbeStatus.WARN,
                        "$statusLine · autenticação do proxy necessária ou credencial rejeitada"
                    )
                    code != null -> ProbeResult(
                        "HTTP CONNECT",
                        ProbeStatus.FAIL,
                        "$statusLine · CONNECT não autorizado para o destino informado"
                    )
                    else -> ProbeResult(
                        "HTTP CONNECT",
                        ProbeStatus.FAIL,
                        "Resposta não reconhecida: ${statusLine.take(120)}"
                    )
                }
            }
        } catch (error: Exception) {
            ProbeResult("HTTP CONNECT", ProbeStatus.FAIL, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun socks5ConnectProbe(
        network: Network,
        address: InetAddress,
        config: ProxyAnalyzerConfig
    ): ProbeResult {
        val started = System.nanoTime()
        return try {
            openProxySocket(network, address, config.proxyPort).use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                socks5Negotiate(input, output, config)
                val reply = socks5Command(input, output, 0x01, config.targetHost, config.targetPort)
                if (reply == 0x00) {
                    ProbeResult(
                        "SOCKS5 CONNECT",
                        ProbeStatus.PASS,
                        "SOCKS5 abriu ${config.targetHost}:${config.targetPort} em ${elapsedMs(started)} ms"
                    )
                } else {
                    ProbeResult("SOCKS5 CONNECT", ProbeStatus.FAIL, "SOCKS5 reply=0x${reply.toString(16)}")
                }
            }
        } catch (error: Exception) {
            ProbeResult("SOCKS5 CONNECT", ProbeStatus.FAIL, error.message ?: error.javaClass.simpleName)
        }
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
        while (bytes.size < 1024) {
            val value = input.read()
            if (value < 0) break
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
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

    private fun elapsedMs(startNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
