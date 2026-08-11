package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

internal enum class CoreCaseStatus { PASS, WARN, FAIL }

data class CoreLayerResult(
    val name: String,
    val status: CoreCaseStatus,
    val detail: String
)

data class CoreProbeCaseResult(
    val protocolId: String,
    val protocolType: String,
    val role: String,
    val host: String,
    val port: Int,
    val transport: String,
    val tls: Boolean,
    val path: String?,
    val status: CoreCaseStatus,
    val layers: List<CoreLayerResult>
)

data class CoreFullProbeReport(
    val profileName: String,
    val profileVersion: String,
    val networkLabel: String,
    val carrier: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val cases: List<CoreProbeCaseResult>
) {
    val passed: Int get() = cases.count { it.status == CoreCaseStatus.PASS }
    val warnings: Int get() = cases.count { it.status == CoreCaseStatus.WARN }
    val failed: Int get() = cases.count { it.status == CoreCaseStatus.FAIL }

    fun toJson(): String {
        val root = JSONObject()
            .put("tool", "AutomBot Core Full Network Probe")
            .put("version", "0.6.0")
            .put("profile_name", profileName)
            .put("profile_version", profileVersion)
            .put("network", networkLabel)
            .put("carrier", carrier ?: JSONObject.NULL)
            .put("started_at_ms", startedAtMs)
            .put("finished_at_ms", finishedAtMs)
            .put("summary", JSONObject()
                .put("total", cases.size)
                .put("pass", passed)
                .put("warn", warnings)
                .put("fail", failed)
            )

        val array = JSONArray()
        cases.forEach { item ->
            val layers = JSONArray()
            item.layers.forEach { layer ->
                layers.put(
                    JSONObject()
                        .put("name", layer.name)
                        .put("status", layer.status.name.lowercase())
                        .put("detail", layer.detail)
                )
            }
            array.put(
                JSONObject()
                    .put("protocol_id", item.protocolId)
                    .put("protocol_type", item.protocolType)
                    .put("role", item.role)
                    .put("host", item.host)
                    .put("port", item.port)
                    .put("transport", item.transport)
                    .put("tls", item.tls)
                    .put("path", item.path ?: JSONObject.NULL)
                    .put("status", item.status.name.lowercase())
                    .put("layers", layers)
            )
        }
        root.put("cases", array)
        root.put("manual", toText())
        return root.toString(2)
    }

    fun toText(): String = buildString {
        appendLine("AUTOMBOT CORE — RELATÓRIO COMPLETO DE REDE")
        appendLine("Perfil: $profileName")
        appendLine("Versão: $profileVersion")
        appendLine("Rede: ${carrier?.let { "$networkLabel · $it" } ?: networkLabel}")
        appendLine("Combinações válidas testadas: ${cases.size}")
        appendLine("OK: $passed · PARCIAL: $warnings · FALHA: $failed")
        appendLine()

        cases.forEachIndexed { index, item ->
            val badge = when (item.status) {
                CoreCaseStatus.PASS -> "OK"
                CoreCaseStatus.WARN -> "PARCIAL"
                CoreCaseStatus.FAIL -> "FALHA"
            }
            appendLine("${index + 1}. ${item.protocolType.uppercase()} [${item.role}] — $badge")
            appendLine("   ${item.host}:${item.port} · ${item.transport}${if (item.tls) " · TLS" else ""}${item.path?.let { " · path=$it" } ?: ""}")
            item.layers.forEach { layer ->
                val symbol = when (layer.status) {
                    CoreCaseStatus.PASS -> "✓"
                    CoreCaseStatus.WARN -> "~"
                    CoreCaseStatus.FAIL -> "x"
                }
                appendLine("   $symbol ${layer.name}: ${layer.detail}")
            }
            appendLine()
        }

        appendLine("INTERPRETAÇÃO")
        appendLine("OK = as camadas aplicáveis a essa configuração responderam.")
        appendLine("PARCIAL = o endpoint foi alcançado, mas alguma camada não teve confirmação determinística ou exige autenticação/handshake específico.")
        appendLine("FALHA = a camada básica necessária para a configuração não respondeu.")
        appendLine()
        appendLine("O teste usa apenas endpoints e portas importados do AutomBot Core. Ele não cria combinações aleatórias entre protocolos incompatíveis.")
    }.trimEnd()
}

class CoreFullProbeEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun run(snapshot: CoreProfileSnapshot): CoreFullProbeReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val network = selectPhysicalNetwork()
            ?: return@withContext CoreFullProbeReport(
                profileName = snapshot.profileName,
                profileVersion = snapshot.profileVersion,
                networkLabel = "Sem rede física disponível",
                carrier = null,
                startedAtMs = started,
                finishedAtMs = System.currentTimeMillis(),
                cases = emptyList()
            )

        val caps = connectivity.getNetworkCapabilities(network)
        val networkLabel = describeNetwork(caps)
        val carrier = carrierName(caps)
        val target = selectOwnedTcpTarget(snapshot)
        val cases = buildCases(snapshot)
        val results = mutableListOf<CoreProbeCaseResult>()

        for (case in cases) {
            results += runCase(network, case, target)
        }

        CoreFullProbeReport(
            profileName = snapshot.profileName,
            profileVersion = snapshot.profileVersion,
            networkLabel = networkLabel,
            carrier = carrier,
            startedAtMs = started,
            finishedAtMs = System.currentTimeMillis(),
            cases = results
        )
    }

    private data class ProbeCase(
        val protocol: CoreProtocolProfile,
        val role: String,
        val host: String,
        val port: Int
    )

    private data class OwnedTarget(val host: String, val port: Int)

    private fun buildCases(snapshot: CoreProfileSnapshot): List<ProbeCase> {
        val cases = mutableListOf<ProbeCase>()
        snapshot.protocols.forEach { protocol ->
            protocol.ports.distinct().filter { it in 1..65535 }.forEach { port ->
                cases += ProbeCase(protocol, "public", protocol.host, port)
            }
            val originHost = protocol.originHost
            val originPort = protocol.originPort
            if (!originHost.isNullOrBlank() && originPort != null && originPort in 1..65535) {
                cases += ProbeCase(protocol, "origin", originHost, originPort)
            }
        }
        return cases.distinctBy {
            listOf(it.protocol.id, it.role, it.host.lowercase(), it.port, it.protocol.path ?: "").joinToString("|")
        }
    }

    private fun selectOwnedTcpTarget(snapshot: CoreProfileSnapshot): OwnedTarget? {
        val preferred = snapshot.protocols.firstOrNull {
            it.transport.lowercase() != "udp" && it.type.lowercase() !in setOf("http_proxy", "socks5") && it.ports.isNotEmpty()
        } ?: return null
        return OwnedTarget(preferred.host, preferred.ports.first())
    }

    private fun runCase(network: Network, case: ProbeCase, target: OwnedTarget?): CoreProbeCaseResult {
        val protocol = case.protocol
        val layers = mutableListOf<CoreLayerResult>()
        val addresses = try {
            network.getAllByName(case.host).toList()
        } catch (error: Exception) {
            layers += CoreLayerResult("DNS", CoreCaseStatus.FAIL, error.message ?: error.javaClass.simpleName)
            return finish(case, layers, CoreCaseStatus.FAIL)
        }

        val ipv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull()
        val address = ipv4 ?: addresses.firstOrNull()
        if (address == null) {
            layers += CoreLayerResult("DNS", CoreCaseStatus.FAIL, "Nenhum endereço A/AAAA retornado")
            return finish(case, layers, CoreCaseStatus.FAIL)
        }
        layers += CoreLayerResult("DNS", CoreCaseStatus.PASS, "${case.host} → ${address.hostAddress}")

        if (protocol.transport.equals("udp", ignoreCase = true)) {
            val udp = udpProbe(network, address, case.port)
            layers += udp
            val status = if (udp.status == CoreCaseStatus.PASS) CoreCaseStatus.PASS else CoreCaseStatus.WARN
            return finish(case, layers, status)
        }

        val tcp = tcpProbe(network, address, case.port)
        layers += tcp
        if (tcp.status == CoreCaseStatus.FAIL) {
            return finish(case, layers, CoreCaseStatus.FAIL)
        }

        when (protocol.type.lowercase()) {
            "ssh" -> layers += sshBannerProbe(network, address, case.port)
            "socks5" -> layers += socks5Probe(network, address, case.port, target)
            "http_proxy" -> layers += httpProxyProbe(network, address, case.port, target)
        }

        if (protocol.tls) {
            layers += tlsProbe(network, address, protocol.sni ?: case.host, case.port)
        }

        if (protocol.transport.equals("websocket", ignoreCase = true)) {
            layers += websocketProbe(
                network = network,
                address = address,
                hostHeader = case.host,
                sni = protocol.sni ?: case.host,
                port = case.port,
                path = protocol.path ?: "/",
                tls = protocol.tls
            )
        }

        val required = when {
            protocol.transport.equals("websocket", ignoreCase = true) -> layers.lastOrNull { it.name.startsWith("WebSocket") }
            protocol.type.equals("ssh", ignoreCase = true) -> layers.lastOrNull { it.name == "SSH banner" }
            protocol.type.equals("socks5", ignoreCase = true) -> layers.lastOrNull { it.name == "SOCKS5" }
            protocol.type.equals("http_proxy", ignoreCase = true) -> layers.lastOrNull { it.name == "HTTP CONNECT" }
            protocol.tls -> layers.lastOrNull { it.name == "TLS/SNI" }
            else -> tcp
        }

        val status = when {
            required?.status == CoreCaseStatus.PASS -> CoreCaseStatus.PASS
            required?.status == CoreCaseStatus.FAIL -> CoreCaseStatus.FAIL
            tcp.status == CoreCaseStatus.PASS -> CoreCaseStatus.WARN
            else -> CoreCaseStatus.WARN
        }
        return finish(case, layers, status)
    }

    private fun finish(case: ProbeCase, layers: List<CoreLayerResult>, status: CoreCaseStatus) =
        CoreProbeCaseResult(
            protocolId = case.protocol.id,
            protocolType = case.protocol.type,
            role = case.role,
            host = case.host,
            port = case.port,
            transport = case.protocol.transport,
            tls = case.protocol.tls,
            path = case.protocol.path,
            status = status,
            layers = layers
        )

    private fun tcpProbe(network: Network, address: InetAddress, port: Int): CoreLayerResult {
        val started = System.nanoTime()
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
            }
            CoreLayerResult("TCP", CoreCaseStatus.PASS, "conectou em ${elapsedMs(started)} ms")
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("TCP", CoreCaseStatus.FAIL, "timeout após ${TCP_TIMEOUT_MS} ms")
        } catch (error: ConnectException) {
            val text = error.message.orEmpty()
            if (text.contains("refused", ignoreCase = true) || text.contains("recus", ignoreCase = true)) {
                CoreLayerResult("TCP", CoreCaseStatus.WARN, "host respondeu, mas a porta recusou a conexão")
            } else {
                CoreLayerResult("TCP", CoreCaseStatus.FAIL, text.ifBlank { error.javaClass.simpleName })
            }
        } catch (error: Exception) {
            CoreLayerResult("TCP", CoreCaseStatus.FAIL, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun sshBannerProbe(network: Network, address: InetAddress, port: Int): CoreLayerResult {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val banner = reader.readLine().orEmpty().trim()
                if (banner.startsWith("SSH-")) {
                    CoreLayerResult("SSH banner", CoreCaseStatus.PASS, banner.take(120))
                } else {
                    CoreLayerResult("SSH banner", CoreCaseStatus.WARN, "TCP respondeu, mas não foi recebido banner SSH")
                }
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("SSH banner", CoreCaseStatus.WARN, "sem banner dentro de ${READ_TIMEOUT_MS} ms")
        } catch (error: Exception) {
            CoreLayerResult("SSH banner", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun tlsProbe(network: Network, address: InetAddress, sni: String, port: Int): CoreLayerResult {
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            raw = network.socketFactory.createSocket().apply {
                connect(InetSocketAddress(address, port), TLS_TIMEOUT_MS)
                soTimeout = TLS_TIMEOUT_MS
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
            val sslSocket = context.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
            ssl = sslSocket
            val params = sslSocket.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            sslSocket.sslParameters = params
            sslSocket.startHandshake()
            CoreLayerResult("TLS/SNI", CoreCaseStatus.PASS, "${sslSocket.session.protocol} válido para $sni")
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("TLS/SNI", CoreCaseStatus.WARN, "timeout no handshake TLS")
        } catch (error: SSLException) {
            CoreLayerResult("TLS/SNI", CoreCaseStatus.WARN, "handshake TLS falhou: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: Exception) {
            CoreLayerResult("TLS/SNI", CoreCaseStatus.FAIL, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
        }
    }

    private fun websocketProbe(
        network: Network,
        address: InetAddress,
        hostHeader: String,
        sni: String,
        port: Int,
        path: String,
        tls: Boolean
    ): CoreLayerResult {
        var raw: Socket? = null
        var active: Socket? = null
        return try {
            raw = network.socketFactory.createSocket().apply {
                connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
            active = if (tls) {
                val context = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
                (context.socketFactory.createSocket(raw, sni, port, true) as SSLSocket).apply {
                    val params = sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    sslParameters = params
                    startHandshake()
                    soTimeout = READ_TIMEOUT_MS
                }
            } else {
                raw
            }

            val normalizedPath = if (path.startsWith('/')) path else "/$path"
            val randomKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val wsKey = Base64.encodeToString(randomKey, Base64.NO_WRAP)
            val request = buildString {
                append("GET $normalizedPath HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Key: $wsKey\r\n")
                append("User-Agent: AutomBot-Network-Probe/0.6\r\n\r\n")
            }
            active!!.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            active!!.getOutputStream().flush()
            val statusLine = BufferedReader(InputStreamReader(active!!.getInputStream(), Charsets.US_ASCII)).readLine().orEmpty()
            if (statusLine.contains(" 101 ")) {
                CoreLayerResult(if (tls) "WebSocket WSS" else "WebSocket WS", CoreCaseStatus.PASS, statusLine)
            } else if (statusLine.startsWith("HTTP/")) {
                CoreLayerResult(if (tls) "WebSocket WSS" else "WebSocket WS", CoreCaseStatus.WARN, "servidor HTTP respondeu, mas não fez upgrade: $statusLine")
            } else {
                CoreLayerResult(if (tls) "WebSocket WSS" else "WebSocket WS", CoreCaseStatus.WARN, "sem resposta HTTP de upgrade")
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult(if (tls) "WebSocket WSS" else "WebSocket WS", CoreCaseStatus.WARN, "timeout no handshake WebSocket")
        } catch (error: Exception) {
            CoreLayerResult(if (tls) "WebSocket WSS" else "WebSocket WS", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { active?.close() }
            if (active !== raw) runCatching { raw?.close() }
        }
    }

    private fun httpProxyProbe(network: Network, address: InetAddress, port: Int, target: OwnedTarget?): CoreLayerResult {
        if (target == null) {
            return CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "proxy alcançável; não há outro endpoint TCP próprio no perfil para validar CONNECT")
        }
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val request = "CONNECT ${target.host}:${target.port} HTTP/1.1\r\nHost: ${target.host}:${target.port}\r\nProxy-Connection: Keep-Alive\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                val status = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine().orEmpty()
                when {
                    status.contains(" 200 ") -> CoreLayerResult("HTTP CONNECT", CoreCaseStatus.PASS, "CONNECT confirmado até ${target.host}:${target.port}")
                    status.contains(" 407 ") -> CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "proxy HTTP confirmado; autenticação necessária")
                    status.startsWith("HTTP/") -> CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "proxy respondeu: $status")
                    else -> CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "sem resposta HTTP reconhecível")
                }
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "proxy alcançável, mas CONNECT expirou")
        } catch (error: Exception) {
            CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun socks5Probe(network: Network, address: InetAddress, port: Int, target: OwnedTarget?): CoreLayerResult {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                socket.getOutputStream().flush()
                val response = ByteArray(2)
                val count = socket.getInputStream().read(response)
                if (count != 2 || response[0].toInt() != 0x05) {
                    return@use CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "TCP respondeu, mas não houve saudação SOCKS5 válida")
                }
                when (response[1].toInt() and 0xff) {
                    0x00 -> {
                        if (target == null) {
                            CoreLayerResult("SOCKS5", CoreCaseStatus.PASS, "SOCKS5 confirmado sem autenticação")
                        } else {
                            val hostBytes = target.host.toByteArray(Charsets.UTF_8)
                            if (hostBytes.size > 255) {
                                CoreLayerResult("SOCKS5", CoreCaseStatus.PASS, "SOCKS5 confirmado sem autenticação")
                            } else {
                                val request = ByteArray(7 + hostBytes.size)
                                request[0] = 0x05
                                request[1] = 0x01
                                request[2] = 0x00
                                request[3] = 0x03
                                request[4] = hostBytes.size.toByte()
                                System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
                                request[5 + hostBytes.size] = ((target.port shr 8) and 0xff).toByte()
                                request[6 + hostBytes.size] = (target.port and 0xff).toByte()
                                socket.getOutputStream().write(request)
                                socket.getOutputStream().flush()
                                val reply = ByteArray(4)
                                val got = socket.getInputStream().read(reply)
                                if (got >= 2 && reply[0].toInt() == 0x05 && reply[1].toInt() == 0x00) {
                                    CoreLayerResult("SOCKS5", CoreCaseStatus.PASS, "SOCKS5 CONNECT confirmado até ${target.host}:${target.port}")
                                } else {
                                    CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "SOCKS5 detectado, mas CONNECT ao endpoint próprio não foi confirmado")
                                }
                            }
                        }
                    }
                    0x02 -> CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "SOCKS5 confirmado; exige usuário/senha")
                    0xff -> CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "SOCKS5 respondeu, mas rejeitou método sem autenticação")
                    else -> CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "SOCKS5 respondeu com método 0x${(response[1].toInt() and 0xff).toString(16)}")
                }
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "timeout na negociação SOCKS5")
        } catch (error: Exception) {
            CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun udpProbe(network: Network, address: InetAddress, port: Int): CoreLayerResult {
        val payload = ByteArray(24).also { bytes ->
            val prefix = "AUTOMBOT-PROBE:".toByteArray(Charsets.US_ASCII)
            System.arraycopy(prefix, 0, bytes, 0, minOf(prefix.size, bytes.size))
            val random = ByteArray(bytes.size - minOf(prefix.size, bytes.size))
            SecureRandom().nextBytes(random)
            if (random.isNotEmpty()) System.arraycopy(random, 0, bytes, prefix.size, random.size)
        }
        return try {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = UDP_TIMEOUT_MS
                socket.send(DatagramPacket(payload, payload.size, address, port))
                val responseBuffer = ByteArray(512)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                CoreLayerResult("UDP", CoreCaseStatus.PASS, "resposta recebida de ${response.address.hostAddress}:${response.port}")
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("UDP", CoreCaseStatus.WARN, "datagrama enviado, sem resposta genérica em ${UDP_TIMEOUT_MS} ms; o protocolo pode ignorar payload desconhecido")
        } catch (error: Exception) {
            CoreLayerResult("UDP", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun selectPhysicalNetwork(): Network? {
        val candidates = LinkedHashSet<Network>()
        connectivity.activeNetwork?.let { candidates += it }
        connectivity.allNetworks.forEach { candidates += it }
        return candidates.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            Triple(
                network,
                network == connectivity.activeNetwork,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }.sortedWith(compareByDescending<Triple<Network, Boolean, Boolean>> { it.second }.thenByDescending { it.third })
            .firstOrNull()?.first
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
            val telephony = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephony.networkOperatorName.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private companion object {
        const val TCP_TIMEOUT_MS = 2500
        const val TLS_TIMEOUT_MS = 4000
        const val READ_TIMEOUT_MS = 3000
        const val UDP_TIMEOUT_MS = 1800
    }
}
