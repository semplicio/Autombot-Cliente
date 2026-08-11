package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import javax.net.ssl.SSLSocket

/**
 * Diagnóstico de capacidade de rede para endpoints controlados pelo usuário.
 *
 * O módulo não procura domínios de terceiros nem tenta descobrir exceções de cobrança
 * ou zero-rating. Todos os testes são realizados exclusivamente contra o host/portas
 * informados na tela.
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
                localAddresses = emptyList(),
                results = listOf(
                    ProbeResult("Rede física", ProbeStatus.FAIL, "Nenhuma rede Wi‑Fi/dados móveis com acesso à Internet foi encontrada")
                ),
                recommendation = "Conecte o aparelho a uma rede e execute novamente.",
                startedAtMs = startedAt,
                finishedAtMs = System.currentTimeMillis()
            )

        val caps = connectivityManager.getNetworkCapabilities(network)
        val label = describeNetwork(caps)
        val carrier = carrierName(caps)
        val localAddresses = linkAddresses(network)
        val results = mutableListOf<ProbeResult>()

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
            return@withContext ProbeReport(
                config = config,
                networkLabel = label,
                carrier = carrier,
                localAddresses = localAddresses,
                results = results,
                recommendation = "O endpoint não foi resolvido pela rede física; corrija DNS/host antes de testar transportes.",
                startedAtMs = startedAt,
                finishedAtMs = System.currentTimeMillis()
            )
        }

        val preferred = preferredAddress(resolved)
        results += tcpProbe(network, preferred, config.tcpPort)
        results += tlsProbe(network, preferred, config.host, config.tcpPort)
        results += httpsProbe(network, config.host, config.tcpPort)
        results += websocketProbe(network, config.host, config.tcpPort, config.webSocketPath)
        results += udpEchoProbe(network, preferred, config.udpPort)

        val recommendation = recommend(results)
        ProbeReport(
            config = config,
            networkLabel = label,
            carrier = carrier,
            localAddresses = localAddresses,
            results = results,
            recommendation = recommendation,
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
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                CandidateNetwork(
                    network = network,
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                )
            }
            .sortedWith(
                compareByDescending<CandidateNetwork> { it.validated }
                    .thenByDescending { it.wifi }
                    .thenByDescending { it.cellular }
            )
            .firstOrNull()
            ?.network
    }

    private fun describeNetwork(caps: NetworkCapabilities?): String = when {
        caps == null -> "Rede desconhecida"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Rede móvel"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Outra rede"
    }

    private fun carrierName(caps: NetworkCapabilities?): String? {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true) return null
        return runCatching {
            val telephony = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephony.networkOperatorName.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun linkAddresses(network: Network): List<String> =
        connectivityManager.getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address.hostAddress.orEmpty().substringBefore('%') }
            .orEmpty()

    private fun preferredAddress(addresses: List<InetAddress>): InetAddress =
        addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull()
            ?: throw IllegalStateException("Nenhum IP resolvido")

    private fun tcpProbe(network: Network, address: InetAddress, port: Int): ProbeResult {
        val started = System.nanoTime()
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
            }
            ProbeResult(
                "TCP $port",
                ProbeStatus.PASS,
                "Conexão estabelecida com ${address.hostAddress}:$port em ${elapsedMs(started)} ms"
            )
        } catch (error: Exception) {
            ProbeResult(
                "TCP $port",
                ProbeStatus.FAIL,
                "${address.hostAddress}:$port — ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun tlsProbe(network: Network, address: InetAddress, host: String, port: Int): ProbeResult {
        val started = System.nanoTime()
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            raw = network.socketFactory.createSocket().apply {
                connect(InetSocketAddress(address, port), TLS_TIMEOUT_MS)
                soTimeout = TLS_TIMEOUT_MS
            }

            val context = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
            ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.startHandshake()

            val peer = ssl.session.peerCertificates
                .firstOrNull() as? X509Certificate
            val subject = peer?.subjectX500Principal?.name ?: "certificado válido"

            ProbeResult(
                "TLS/SNI $port",
                ProbeStatus.PASS,
                "Handshake válido para $host em ${elapsedMs(started)} ms | $subject"
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
                    "Resposta HTTP ${response.code} em ${elapsedMs(started)} ms"
                )
            }
        } catch (error: Exception) {
            ProbeResult(
                "HTTPS",
                ProbeStatus.FAIL,
                error.message ?: error.javaClass.simpleName
            )
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

        return when {
            opened -> ProbeResult(
                "WebSocket TLS",
                ProbeStatus.PASS,
                "Upgrade WSS confirmado em ${elapsedMs(started)} ms"
            )
            responseCode != null -> ProbeResult(
                "WebSocket TLS",
                ProbeStatus.WARN,
                "A rede alcançou o servidor, mas o endpoint WS respondeu HTTP $responseCode. Verifique o path/Host configurado."
            )
            failure != null -> ProbeResult(
                "WebSocket TLS",
                ProbeStatus.FAIL,
                failure.orEmpty()
            )
            else -> ProbeResult(
                "WebSocket TLS",
                ProbeStatus.FAIL,
                "Timeout sem resposta durante o upgrade WSS"
            )
        }
    }

    /**
     * Envia um nonce UDP e aguarda eco. PASS só significa UDP bidirecional confirmado
     * quando o endpoint remoto executa um serviço de echo/probe compatível.
     *
     * Se o datagrama for enviado mas não houver resposta, WARN é intencional: UDP não
     * possui handshake e a ausência de eco não distingue bloqueio de porta de um
     * serviço que simplesmente ignora payloads desconhecidos (Hysteria/TUIC incluídos).
     */
    private fun udpEchoProbe(network: Network, address: InetAddress, port: Int): ProbeResult {
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

                val buffer = ByteArray(256)
                val reply = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(reply)
                    ProbeResult(
                        "UDP $port",
                        ProbeStatus.PASS,
                        "Resposta UDP recebida de ${reply.address.hostAddress}:${reply.port} em ${elapsedMs(started)} ms"
                    )
                } catch (_: SocketTimeoutException) {
                    ProbeResult(
                        "UDP $port",
                        ProbeStatus.WARN,
                        "Datagrama enviado, mas sem eco em ${UDP_TIMEOUT_MS} ms. Para confirmação real use um endpoint AutomBot UDP echo/probe."
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
            when {
                hostname.equals(targetHost, ignoreCase = true) -> network.getAllByName(targetHost).toList()
                else -> network.getAllByName(hostname).toList()
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

    private fun recommend(results: List<ProbeResult>): String {
        fun status(prefix: String): ProbeStatus? =
            results.firstOrNull { it.name.startsWith(prefix) }?.status

        return when {
            status("WebSocket TLS") == ProbeStatus.PASS ->
                "WSS/TLS foi confirmado. VLESS/VMess sobre WebSocket TLS é um candidato forte para esta rede."

            status("TLS/SNI") == ProbeStatus.PASS && status("TCP") == ProbeStatus.PASS ->
                "TCP/TLS está disponível. Priorize transportes TLS sobre TCP e valide o endpoint específico do protocolo."

            status("UDP") == ProbeStatus.PASS ->
                "UDP bidirecional foi confirmado no endpoint de probe. Vale testar Hysteria2/TUIC/WireGuard nesse mesmo caminho."

            status("TCP") == ProbeStatus.FAIL ->
                "O endpoint TCP não está alcançável nesta rede. Teste outro endpoint/porta autorizados da sua própria infraestrutura."

            else ->
                "Há conectividade parcial. Compare os resultados entre Wi‑Fi e rede móvel antes de escolher o transporte."
        }
    }

    private fun elapsedMs(startNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private data class CandidateNetwork(
        val network: Network,
        val validated: Boolean,
        val wifi: Boolean,
        val cellular: Boolean
    )

    private companion object {
        const val TCP_TIMEOUT_MS = 4_000
        const val TLS_TIMEOUT_MS = 5_000
        const val HTTP_TIMEOUT_MS = 6_000
        const val WS_TIMEOUT_MS = 6_000
        const val UDP_TIMEOUT_MS = 2_500
    }
}

data class ProbeConfig(
    val host: String,
    val tcpPort: Int = 443,
    val udpPort: Int = 443,
    val webSocketPath: String = "/"
)

enum class ProbeStatus { PASS, WARN, FAIL }

data class ProbeResult(
    val name: String,
    val status: ProbeStatus,
    val detail: String
)

data class ProbeReport(
    val config: ProbeConfig,
    val networkLabel: String,
    val carrier: String?,
    val localAddresses: List<String>,
    val results: List<ProbeResult>,
    val recommendation: String,
    val startedAtMs: Long,
    val finishedAtMs: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("tool", "AutomBot Network Probe")
        put("version", "0.1.0")
        put("started_at_ms", startedAtMs)
        put("finished_at_ms", finishedAtMs)
        put("network", networkLabel)
        put("carrier", carrier ?: JSONObject.NULL)
        put("local_addresses", JSONArray(localAddresses))
        put("endpoint", JSONObject().apply {
            put("host", config.host)
            put("tcp_port", config.tcpPort)
            put("udp_port", config.udpPort)
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
        put("recommendation", recommendation)
    }.toString(2)
}
