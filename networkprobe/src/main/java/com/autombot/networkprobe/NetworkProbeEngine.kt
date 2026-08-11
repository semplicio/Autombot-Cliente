package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Diagnóstico de capacidade da rede para endpoints controlados pelo operador.
 *
 * O módulo mede disponibilidade de transporte e ajuda a separar DNS, caminho IP,
 * porta, TLS, HTTP, WebSocket e UDP. Ele não procura domínios de terceiros,
 * zero-rating ou exceções de cobrança.
 */
class NetworkProbeEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun run(config: ProbeConfig): ProbeReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val network = selectPhysicalNetwork()
            ?: return@withContext ProbeReport(
                config = config,
                networkLabel = "Sem rede física disponível",
                carrier = null,
                networkInfo = NetworkInfo(),
                localAddresses = emptyList(),
                results = listOf(
                    ProbeResult(
                        "Rede física",
                        ProbeStatus.FAIL,
                        "Nenhuma rede Wi-Fi/dados móveis com acesso à Internet foi encontrada"
                    )
                ),
                score = 0,
                transportHints = emptyList(),
                recommendation = "Conecte o aparelho a uma rede e execute novamente.",
                startedAtMs = startedAt,
                finishedAtMs = System.currentTimeMillis()
            )

        val caps = connectivityManager.getNetworkCapabilities(network)
        val link = connectivityManager.getLinkProperties(network)
        val label = describeNetwork(caps)
        val carrier = carrierName(caps)
        val localAddresses = link?.linkAddresses
            ?.map { it.address.hostAddress.orEmpty().substringBefore('%') }
            .orEmpty()

        val networkInfo = NetworkInfo(
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
            interfaceName = link?.interfaceName,
            mtu = link?.mtu?.takeIf { it > 0 },
            dnsServers = link?.dnsServers
                ?.mapNotNull { it.hostAddress?.substringBefore('%') }
                .orEmpty(),
            hasIpv4 = link?.linkAddresses?.any { it.address is Inet4Address } == true,
            hasIpv6 = link?.linkAddresses?.any { it.address is Inet6Address } == true,
            natHint = detectNatHint(link?.linkAddresses?.map { it.address }.orEmpty())
        )

        val results = mutableListOf<ProbeResult>()
        results += networkStateResult(networkInfo)

        val resolved = runCatching { network.getAllByName(config.host).toList() }
            .onSuccess { addresses ->
                val a = addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                val aaaa = addresses.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress }
                val detail = buildString {
                    append("A=")
                    append(if (a.isEmpty()) "—" else a.joinToString())
                    append(" | AAAA=")
                    append(if (aaaa.isEmpty()) "—" else aaaa.joinToString())
                }
                results += ProbeResult("DNS pela rede física", ProbeStatus.PASS, detail)
            }
            .onFailure { error ->
                results += ProbeResult(
                    "DNS pela rede física",
                    ProbeStatus.FAIL,
                    error.message ?: error.javaClass.simpleName
                )
            }
            .getOrDefault(emptyList())

        if (resolved.isEmpty()) {
            return@withContext buildReport(
                config = config,
                label = label,
                carrier = carrier,
                networkInfo = networkInfo,
                localAddresses = localAddresses,
                results = results,
                startedAt = startedAt
            )
        }

        val ipv4 = resolved.filterIsInstance<Inet4Address>().firstOrNull()
        val ipv6 = resolved.filterIsInstance<Inet6Address>().firstOrNull()
        val preferred = ipv4 ?: ipv6 ?: resolved.first()

        val tcpPorts = (listOf(config.tcpPort) + config.extraTcpPorts)
            .filter { it in 1..65535 }
            .distinct()
            .take(MAX_PORTS_PER_FAMILY)

        tcpPorts.forEach { port ->
            results += tcpProbe(network, preferred, port)
        }

        if (ipv4 != null && ipv6 != null) {
            results += tcpProbe(
                network = network,
                address = ipv6,
                port = config.tcpPort,
                suffix = " IPv6"
            )
        }

        results += tlsProbe(network, preferred, config.host, config.tcpPort)
        results += httpsProbe(network, config.host, config.tcpPort)
        results += websocketProbe(network, config.host, config.tcpPort, config.webSocketPath)

        val udpPorts = (listOf(config.udpPort) + config.extraUdpPorts)
            .filter { it in 1..65535 }
            .distinct()
            .take(MAX_PORTS_PER_FAMILY)

        udpPorts.forEach { port ->
            results += udpResponseProbe(network, preferred, port)
        }

        buildReport(
            config = config,
            label = label,
            carrier = carrier,
            networkInfo = networkInfo,
            localAddresses = localAddresses,
            results = results,
            startedAt = startedAt
        )
    }

    private fun buildReport(
        config: ProbeConfig,
        label: String,
        carrier: String?,
        networkInfo: NetworkInfo,
        localAddresses: List<String>,
        results: List<ProbeResult>,
        startedAt: Long
    ): ProbeReport {
        val score = calculateScore(results)
        val transportHints = transportHints(config, results)
        return ProbeReport(
            config = config,
            networkLabel = label,
            carrier = carrier,
            networkInfo = networkInfo,
            localAddresses = localAddresses,
            results = results,
            score = score,
            transportHints = transportHints,
            recommendation = recommend(config, results, networkInfo),
            startedAtMs = startedAt,
            finishedAtMs = System.currentTimeMillis()
        )
    }

    private fun selectPhysicalNetwork(): Network? {
        val candidates = LinkedHashSet<Network>()
        connectivityManager.activeNetwork?.let { candidates += it }
        connectivityManager.allNetworks.forEach { candidates += it }

        return candidates
            .mapNotNull { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return@mapNotNull null
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return@mapNotNull null
                }

                CandidateNetwork(
                    network = network,
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    active = network == connectivityManager.activeNetwork,
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                )
            }
            .sortedWith(
                compareByDescending<CandidateNetwork> { it.active }
                    .thenByDescending { it.validated }
                    .thenByDescending { it.cellular || it.wifi }
            )
            .firstOrNull()
            ?.network
    }

    private fun describeNetwork(caps: NetworkCapabilities?): String = when {
        caps == null -> "Rede desconhecida"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Rede móvel"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Outra rede"
    }

    private fun carrierName(caps: NetworkCapabilities?): String? {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true) return null
        return runCatching {
            val telephony =
                appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephony.networkOperatorName.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun networkStateResult(info: NetworkInfo): ProbeResult {
        val status = if (info.validated) ProbeStatus.PASS else ProbeStatus.WARN
        val detail = buildString {
            append(if (info.validated) "Internet validada" else "Internet não validada pelo Android")
            append(" | ")
            append(if (info.metered) "rede medida" else "rede não medida")
            info.mtu?.let { append(" | MTU=").append(it) }
            if (info.dnsServers.isNotEmpty()) {
                append(" | DNS=").append(info.dnsServers.joinToString())
            }
            info.natHint?.let { append(" | ").append(it) }
        }
        return ProbeResult("Estado da rede", status, detail)
    }

    private fun tcpProbe(
        network: Network,
        address: InetAddress,
        port: Int,
        suffix: String = ""
    ): ProbeResult {
        val started = System.nanoTime()
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
            }
            ProbeResult(
                "TCP $port$suffix",
                ProbeStatus.PASS,
                "Conexão estabelecida com ${displayAddress(address)}:$port em ${elapsedMs(started)} ms"
            )
        } catch (error: ConnectException) {
            val detail = error.message.orEmpty()
            if (isConnectionRefused(detail)) {
                ProbeResult(
                    "TCP $port$suffix",
                    ProbeStatus.WARN,
                    "${displayAddress(address)}:$port recusou a conexão. O caminho até o host respondeu, mas não há serviço TCP aceitando nessa porta."
                )
            } else {
                ProbeResult(
                    "TCP $port$suffix",
                    ProbeStatus.FAIL,
                    "${displayAddress(address)}:$port — ${detail.ifBlank { error.javaClass.simpleName }}"
                )
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult(
                "TCP $port$suffix",
                ProbeStatus.FAIL,
                "${displayAddress(address)}:$port — timeout após ${TCP_TIMEOUT_MS} ms; possível filtragem, rota indisponível ou servidor sem resposta."
            )
        } catch (error: Exception) {
            ProbeResult(
                "TCP $port$suffix",
                ProbeStatus.FAIL,
                "${displayAddress(address)}:$port — ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun tlsProbe(
        network: Network,
        address: InetAddress,
        host: String,
        port: Int
    ): ProbeResult {
        val started = System.nanoTime()
        var raw: Socket? = null
        var ssl: SSLSocket? = null

        return try {
            raw = network.socketFactory.createSocket().apply {
                connect(InetSocketAddress(address, port), TLS_TIMEOUT_MS)
                soTimeout = TLS_TIMEOUT_MS
            }

            val context = SSLContext.getInstance("TLS").apply {
                init(null, null, SecureRandom())
            }
            ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.startHandshake()

            val certificate = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
            val certDetail = certificate?.let {
                val days = TimeUnit.MILLISECONDS.toDays(it.notAfter.time - System.currentTimeMillis())
                "cert expira em ${days}d"
            } ?: "certificado válido"
            val alpn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ssl.applicationProtocol.takeIf { it.isNotBlank() }
            } else {
                null
            }

            ProbeResult(
                "TLS/SNI $port",
                ProbeStatus.PASS,
                buildString {
                    append("TLS ")
                    append(ssl.session.protocol)
                    append(" válido para ")
                    append(host)
                    append(" em ")
                    append(elapsedMs(started))
                    append(" ms | ")
                    append(certDetail)
                    alpn?.let { append(" | ALPN=").append(it) }
                }
            )
        } catch (_: SocketTimeoutException) {
            ProbeResult(
                "TLS/SNI $port",
                ProbeStatus.WARN,
                "TCP iniciou, mas não houve resposta TLS em ${TLS_TIMEOUT_MS} ms. A porta pode usar outro protocolo ou o handshake pode estar sendo filtrado."
            )
        } catch (error: SSLException) {
            ProbeResult(
                "TLS/SNI $port",
                ProbeStatus.WARN,
                "O servidor foi alcançado, mas o handshake TLS falhou: ${error.message ?: error.javaClass.simpleName}"
            )
        } catch (error: ConnectException) {
            val detail = error.message.orEmpty()
            ProbeResult(
                "TLS/SNI $port",
                if (isConnectionRefused(detail)) ProbeStatus.WARN else ProbeStatus.FAIL,
                detail.ifBlank { error.javaClass.simpleName }
            )
        } catch (error: Exception) {
            ProbeResult(
                "TLS/SNI $port",
                ProbeStatus.FAIL,
                error.message ?: error.javaClass.simpleName
            )
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
        }
    }

    private fun httpsProbe(network: Network, host: String, port: Int): ProbeResult {
        val client = baseHttpClient(network, host)
        val request = Request.Builder()
            .url("https://$host:$port/")
            .head()
            .build()

        val started = System.nanoTime()
        return try {
            client.newCall(request).execute().use { response ->
                ProbeResult(
                    "HTTPS",
                    ProbeStatus.PASS,
                    "HTTP ${response.code} via ${response.protocol} em ${elapsedMs(started)} ms"
                )
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult(
                "HTTPS",
                ProbeStatus.WARN,
                "Timeout HTTPS. A porta pode não oferecer HTTP/TLS mesmo que o TCP esteja alcançável."
            )
        } catch (error: Exception) {
            ProbeResult(
                "HTTPS",
                ProbeStatus.WARN,
                "HTTPS não confirmado: ${error.message ?: error.javaClass.simpleName}"
            )
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun websocketProbe(
        network: Network,
        host: String,
        port: Int,
        path: String
    ): ProbeResult {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        val client = baseHttpClient(network, host)
        val request = Request.Builder()
            .url("wss://$host:$port$normalizedPath")
            .build()

        val latch = CountDownLatch(1)
        var opened = false
        var failure: String? = null
        var responseCode: Int? = null
        val started = System.nanoTime()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                responseCode = response.code
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                responseCode = response?.code
                failure = t.message ?: t.javaClass.simpleName
                latch.countDown()
            }
        })

        latch.await(WS_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        socket.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()

        return when {
            opened -> ProbeResult(
                "WebSocket TLS",
                ProbeStatus.PASS,
                "Upgrade WSS confirmado em ${elapsedMs(started)} ms no path $normalizedPath"
            )
            responseCode != null -> ProbeResult(
                "WebSocket TLS",
                ProbeStatus.WARN,
                "O servidor respondeu HTTP $responseCode. O caminho TCP/TLS existe, mas o path/Host pode não ser um endpoint WebSocket."
            )
            failure != null -> ProbeResult(
                "WebSocket TLS",
                ProbeStatus.WARN,
                "WSS não confirmado: ${failure.orEmpty()}"
            )
            else -> ProbeResult(
                "WebSocket TLS",
                ProbeStatus.WARN,
                "Timeout no upgrade WSS; isso não prova bloqueio se a porta não for WebSocket."
            )
        }
    }

    /**
     * Envia um pequeno datagrama de probe e aguarda qualquer resposta UDP do destino.
     * Receber resposta confirma caminho bidirecional naquela porta. A ausência de
     * resposta continua sendo WARN: serviços UDP podem ignorar payloads desconhecidos.
     */
    private fun udpResponseProbe(
        network: Network,
        address: InetAddress,
        port: Int
    ): ProbeResult {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payload = "AUTOMBOT-PROBE:".toByteArray() + nonce
        val started = System.nanoTime()

        return try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                network.bindSocket(socket)
                socket.soTimeout = UDP_TIMEOUT_MS
                socket.send(DatagramPacket(payload, payload.size, address, port))

                val buffer = ByteArray(512)
                val reply = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(reply)
                    ProbeResult(
                        "UDP $port",
                        ProbeStatus.PASS,
                        "Resposta UDP de ${displayAddress(reply.address)}:${reply.port} em ${elapsedMs(started)} ms. Caminho bidirecional confirmado."
                    )
                } catch (_: SocketTimeoutException) {
                    ProbeResult(
                        "UDP $port",
                        ProbeStatus.WARN,
                        "Datagrama enviado, mas sem resposta em ${UDP_TIMEOUT_MS} ms. Pode ser filtragem ou apenas um serviço que ignora probes genéricos."
                    )
                }
            }
        } catch (error: Exception) {
            ProbeResult(
                "UDP $port",
                ProbeStatus.FAIL,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun baseHttpClient(network: Network, targetHost: String): OkHttpClient {
        val dns = Dns { hostname ->
            if (hostname.equals(targetHost, ignoreCase = true)) {
                network.getAllByName(targetHost).toList()
            } else {
                network.getAllByName(hostname).toList()
            }
        }

        return OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(dns)
            .connectTimeout(HTTP_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(HTTP_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    private fun calculateScore(results: List<ProbeResult>): Int {
        if (results.isEmpty()) return 0
        val points = results.sumOf {
            when (it.status) {
                ProbeStatus.PASS -> 100
                ProbeStatus.WARN -> 50
                ProbeStatus.FAIL -> 0
            }
        }
        return points / results.size
    }

    private fun transportHints(
        config: ProbeConfig,
        results: List<ProbeResult>
    ): List<String> {
        fun pass(name: String): Boolean =
            results.any { it.name == name && it.status == ProbeStatus.PASS }

        val hints = mutableListOf<String>()

        if (pass("WebSocket TLS")) {
            hints += "VLESS/VMess WSS: WebSocket TLS confirmado no endpoint principal."
        }
        if (pass("TLS/SNI ${config.tcpPort}")) {
            hints += "TCP/TLS: handshake válido; transportes baseados em TLS podem usar este endpoint."
        }
        if (pass("TCP 22") || pass("TCP 109") || pass("TCP 2222")) {
            hints += "SSH: pelo menos uma porta SSH comum da sua infraestrutura respondeu em TCP."
        }
        if (pass("UDP 36712")) {
            hints += "Hysteria2: UDP 36712 respondeu; vale validar o handshake Hysteria2 real."
        }
        if (pass("UDP 44300")) {
            hints += "TUIC: UDP 44300 respondeu; vale validar o handshake TUIC real."
        }
        if (pass("UDP 51820")) {
            hints += "WireGuard: UDP 51820 respondeu ao probe; valide o handshake WireGuard real."
        }
        if (
            pass("UDP ${config.udpPort}") &&
            hints.none {
                it.startsWith("Hysteria2") ||
                    it.startsWith("TUIC") ||
                    it.startsWith("WireGuard")
            }
        ) {
            hints += "UDP: o endpoint principal respondeu; existe caminho UDP bidirecional confirmado."
        }

        if (hints.isEmpty()) {
            hints += "Nenhum transporte foi confirmado completamente. Use a matriz de portas para separar timeout de porta recusada."
        }
        return hints
    }

    private fun recommend(
        config: ProbeConfig,
        results: List<ProbeResult>,
        info: NetworkInfo
    ): String {
        fun status(name: String): ProbeStatus? =
            results.firstOrNull { it.name == name }?.status

        val tcpPrimary = status("TCP ${config.tcpPort}")
        val tls = status("TLS/SNI ${config.tcpPort}")
        val wss = status("WebSocket TLS")
        val responsiveUdp = results.filter {
            it.name.startsWith("UDP ") && it.status == ProbeStatus.PASS
        }.map { it.name.removePrefix("UDP ") }

        return buildString {
            when {
                wss == ProbeStatus.PASS -> {
                    append("WSS/TLS está operacional. É o melhor candidato atual para VMess/VLESS sobre WebSocket.")
                }
                tls == ProbeStatus.PASS && tcpPrimary == ProbeStatus.PASS -> {
                    append("TCP e TLS estão operacionais no endpoint principal. Teste o protocolo final usando esse mesmo caminho.")
                }
                responsiveUdp.isNotEmpty() -> {
                    append("UDP bidirecional respondeu nas portas ")
                    append(responsiveUdp.joinToString())
                    append(". Priorize os protocolos configurados nessas portas.")
                }
                tcpPrimary == ProbeStatus.PASS -> {
                    append("O TCP principal está alcançável, mas a camada TLS/HTTP/WSS não foi confirmada. Verifique se a porta realmente serve esse protocolo.")
                }
                else -> {
                    append("O endpoint principal não foi confirmado. Portas com 'recusada' provam que o host foi alcançado; timeouts sugerem filtragem, rota ou ausência de resposta.")
                }
            }

            if (info.natHint != null) {
                append(" ")
                append(info.natHint)
                append(" foi detectado; isso é comum em redes móveis e deve ser considerado em testes de retorno/NAT.")
            }
        }
    }

    private fun detectNatHint(addresses: List<InetAddress>): String? {
        val ipv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull() ?: return null
        val octets = ipv4.address.map { it.toInt() and 0xFF }

        return when {
            octets[0] == 100 && octets[1] in 64..127 -> "CGNAT 100.64.0.0/10"
            octets[0] == 10 -> "IPv4 privado 10.0.0.0/8"
            octets[0] == 172 && octets[1] in 16..31 -> "IPv4 privado 172.16.0.0/12"
            octets[0] == 192 && octets[1] == 168 -> "IPv4 privado 192.168.0.0/16"
            else -> null
        }
    }

    private fun isConnectionRefused(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("refused") || normalized.contains("econnrefused")
    }

    private fun displayAddress(address: InetAddress): String =
        address.hostAddress.orEmpty().substringBefore('%')

    private fun elapsedMs(startNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private data class CandidateNetwork(
        val network: Network,
        val validated: Boolean,
        val active: Boolean,
        val wifi: Boolean,
        val cellular: Boolean
    )

    private companion object {
        const val TCP_TIMEOUT_MS = 2_500
        const val TLS_TIMEOUT_MS = 4_000
        const val HTTP_TIMEOUT_MS = 4_500
        const val WS_TIMEOUT_MS = 4_500
        const val UDP_TIMEOUT_MS = 1_800
        const val MAX_PORTS_PER_FAMILY = 8
    }
}

data class ProbeConfig(
    val host: String,
    val tcpPort: Int = 443,
    val udpPort: Int = 443,
    val webSocketPath: String = "/",
    val extraTcpPorts: List<Int> = listOf(80, 109, 2222, 8080, 8443),
    val extraUdpPorts: List<Int> = listOf(36712, 44300, 51820)
)

enum class ProbeStatus { PASS, WARN, FAIL }

data class ProbeResult(
    val name: String,
    val status: ProbeStatus,
    val detail: String
)

data class NetworkInfo(
    val validated: Boolean = false,
    val metered: Boolean = false,
    val interfaceName: String? = null,
    val mtu: Int? = null,
    val dnsServers: List<String> = emptyList(),
    val hasIpv4: Boolean = false,
    val hasIpv6: Boolean = false,
    val natHint: String? = null
)

data class ProbeReport(
    val config: ProbeConfig,
    val networkLabel: String,
    val carrier: String?,
    val networkInfo: NetworkInfo,
    val localAddresses: List<String>,
    val results: List<ProbeResult>,
    val score: Int,
    val transportHints: List<String>,
    val recommendation: String,
    val startedAtMs: Long,
    val finishedAtMs: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("tool", "AutomBot Network Probe")
        put("version", "0.2.0")
        put("started_at_ms", startedAtMs)
        put("finished_at_ms", finishedAtMs)
        put("score", score)
        put("network", networkLabel)
        put("carrier", carrier ?: JSONObject.NULL)
        put("local_addresses", JSONArray(localAddresses))
        put("network_info", JSONObject().apply {
            put("validated", networkInfo.validated)
            put("metered", networkInfo.metered)
            put("interface", networkInfo.interfaceName ?: JSONObject.NULL)
            put("mtu", networkInfo.mtu ?: JSONObject.NULL)
            put("dns_servers", JSONArray(networkInfo.dnsServers))
            put("has_ipv4", networkInfo.hasIpv4)
            put("has_ipv6", networkInfo.hasIpv6)
            put("nat_hint", networkInfo.natHint ?: JSONObject.NULL)
        })
        put("endpoint", JSONObject().apply {
            put("host", config.host)
            put("tcp_port", config.tcpPort)
            put("udp_port", config.udpPort)
            put("websocket_path", config.webSocketPath)
            put("extra_tcp_ports", JSONArray(config.extraTcpPorts))
            put("extra_udp_ports", JSONArray(config.extraUdpPorts))
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
        put("transport_hints", JSONArray(transportHints))
        put("recommendation", recommendation)
    }.toString(2)
}
