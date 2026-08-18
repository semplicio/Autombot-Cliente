package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

internal enum class CdnRouteState {
    PASS,
    REDIRECT,
    PARTIAL,
    TIMEOUT,
    FAIL
}

internal data class CdnRouteConfig(
    val host: String,
    val paths: List<String>,
    val forceCellular: Boolean
)

internal data class CdnIpAttempt(
    val ip: String,
    val state: CdnRouteState,
    val statusLine: String?,
    val statusCode: Int?,
    val connectMs: Long?,
    val totalMs: Long,
    val tlsProtocol: String?,
    val headers: Map<String, String>,
    val detail: String
)

internal data class CdnRouteResult(
    val label: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val path: String,
    val state: CdnRouteState,
    val attempts: List<CdnIpAttempt>
)

internal data class CdnRouteReport(
    val networkLabel: String,
    val carrier: String?,
    val host: String,
    val resolvedIps: List<String>,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val routes: List<CdnRouteResult>
) {
    val passed: Int get() = routes.count { it.state == CdnRouteState.PASS }
    val redirected: Int get() = routes.count { it.state == CdnRouteState.REDIRECT }
    val failed: Int get() = routes.size - passed - redirected

    fun toJson(): String = JSONObject()
        .put("tool", "AutomBot CDN Route Probe")
        .put("version", "1.6.0")
        .put("network", networkLabel)
        .put("carrier", carrier ?: JSONObject.NULL)
        .put("host", host)
        .put("resolved_ips", JSONArray(resolvedIps))
        .put("started_at_ms", startedAtMs)
        .put("finished_at_ms", finishedAtMs)
        .put(
            "summary",
            JSONObject()
                .put("total", routes.size)
                .put("pass", passed)
                .put("redirect", redirected)
                .put("other_failures", failed)
        )
        .put(
            "routes",
            JSONArray().apply {
                routes.forEach { route ->
                    put(
                        JSONObject()
                            .put("label", route.label)
                            .put("host", route.host)
                            .put("port", route.port)
                            .put("tls", route.tls)
                            .put("path", route.path)
                            .put("state", route.state.name.lowercase())
                            .put(
                                "attempts",
                                JSONArray().apply {
                                    route.attempts.forEach { attempt ->
                                        put(
                                            JSONObject()
                                                .put("ip", attempt.ip)
                                                .put("state", attempt.state.name.lowercase())
                                                .put("status_line", attempt.statusLine ?: JSONObject.NULL)
                                                .put("status_code", attempt.statusCode ?: JSONObject.NULL)
                                                .put("connect_ms", attempt.connectMs ?: JSONObject.NULL)
                                                .put("total_ms", attempt.totalMs)
                                                .put("tls_protocol", attempt.tlsProtocol ?: JSONObject.NULL)
                                                .put("headers", JSONObject(attempt.headers))
                                                .put("detail", attempt.detail)
                                        )
                                    }
                                }
                            )
                    )
                }
            }
        )
        .put(
            "note",
            "O teste confirma alcance e handshake WebSocket somente para o FQDN informado. Ele não confirma política de cobrança, patrocínio ou zero-rating da operadora."
        )
        .put("manual", toText())
        .toString(2)

    fun toText(): String = buildString {
        appendLine("AUTOMBOT NETWORK PROBE — CDN HTTP/80 + TLS/443")
        appendLine("Rede: ${carrier?.let { "$networkLabel · $it" } ?: networkLabel}")
        appendLine("FQDN: $host")
        appendLine("DNS: ${resolvedIps.joinToString().ifBlank { "sem resposta" }}")
        appendLine("Rotas: ${routes.size} · OK: $passed · REDIRECT: $redirected · OUTRAS FALHAS: $failed")
        appendLine()

        routes.forEachIndexed { index, route ->
            appendLine("${index + 1}. ${route.label} ${route.host}:${route.port}${route.path} — ${stateLabel(route.state)}")
            route.attempts.forEach { attempt ->
                appendLine("   ${attempt.ip} — ${stateLabel(attempt.state)}${attempt.totalMs.let { " · ${it}ms" }}")
                attempt.connectMs?.let { appendLine("   TCP: ${it}ms") }
                attempt.statusLine?.let { appendLine("   HTTP: $it") }
                attempt.tlsProtocol?.let { appendLine("   TLS: $it") }
                SELECTED_HEADERS.forEach { header ->
                    attempt.headers[header]?.let { value ->
                        appendLine("   ${displayHeader(header)}: $value")
                    }
                }
                appendLine("   ${attempt.detail}")
            }
            appendLine()
        }

        appendLine("INTERPRETAÇÃO")
        appendLine("• HTTP 101 com Sec-WebSocket-Accept válido confirma a rota WebSocket.")
        appendLine("• Location apontando para HTTPS no mesmo FQDN indica redirecionamento HTTP→HTTPS na borda/CDN.")
        appendLine("• Location apontando para portal da operadora indica resposta de um intermediário da rede antes do WebSocket.")
        appendLine("• Timeout em 443 indica falta de resposta TCP/TLS nessa rede; não significa erro do núcleo local do AutomBot Connect.")
        appendLine()
        append("O resultado mede conectividade técnica e não comprova política comercial de acesso patrocinado ou zero-rating.")
    }.trimEnd()

    private fun stateLabel(state: CdnRouteState): String = when (state) {
        CdnRouteState.PASS -> "OK"
        CdnRouteState.REDIRECT -> "REDIRECT"
        CdnRouteState.PARTIAL -> "PARCIAL"
        CdnRouteState.TIMEOUT -> "TIMEOUT"
        CdnRouteState.FAIL -> "FALHA"
    }

    private fun displayHeader(name: String): String = when (name) {
        "location" -> "Location"
        "server" -> "Server"
        "via" -> "Via"
        "x-cache" -> "X-Cache"
        "connection" -> "Connection"
        "upgrade" -> "Upgrade"
        "sec-websocket-accept" -> "Sec-WebSocket-Accept"
        else -> name
    }

    private companion object {
        val SELECTED_HEADERS = listOf(
            "location",
            "server",
            "via",
            "x-cache",
            "connection",
            "upgrade",
            "sec-websocket-accept"
        )
    }
}

internal class CdnRouteProbeEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun run(config: CdnRouteConfig): CdnRouteReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val network = selectNetwork(config.forceCellular)
        val host = config.host.trim().lowercase()
        val addresses = network.getAllByName(host)
            .distinctBy { it.hostAddress }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .take(MAX_ADDRESSES)

        require(addresses.isNotEmpty()) { "O DNS da rede não retornou IP para $host." }

        val paths = config.paths
            .map(::normalizePath)
            .distinct()
            .take(MAX_PATHS)
        require(paths.isNotEmpty()) { "Informe ao menos um WebSocket path." }

        val results = mutableListOf<CdnRouteResult>()
        paths.forEach { path ->
            val pathRoutes = listOf(
                RouteSpec("CDN HTTP/80", 80, false, path),
                RouteSpec("CDN TLS/443", 443, true, path)
            )
            results += coroutineScope {
                pathRoutes.map { route ->
                    async(Dispatchers.IO) { runRoute(network, host, addresses, route) }
                }.awaitAll()
            }
        }

        CdnRouteReport(
            networkLabel = networkLabel(network),
            carrier = carrierName(network),
            host = host,
            resolvedIps = addresses.mapNotNull { it.hostAddress },
            startedAtMs = started,
            finishedAtMs = System.currentTimeMillis(),
            routes = results
        )
    }

    private data class RouteSpec(
        val label: String,
        val port: Int,
        val tls: Boolean,
        val path: String
    )

    private suspend fun runRoute(
        network: Network,
        host: String,
        addresses: List<InetAddress>,
        route: RouteSpec
    ): CdnRouteResult = coroutineScope {
        val attempts = addresses.map { address ->
            async(Dispatchers.IO) { attempt(network, host, address, route) }
        }.awaitAll()

        val state = when {
            attempts.any { it.state == CdnRouteState.PASS } -> CdnRouteState.PASS
            attempts.any { it.state == CdnRouteState.REDIRECT } -> CdnRouteState.REDIRECT
            attempts.any { it.state == CdnRouteState.PARTIAL } -> CdnRouteState.PARTIAL
            attempts.all { it.state == CdnRouteState.TIMEOUT } -> CdnRouteState.TIMEOUT
            else -> CdnRouteState.FAIL
        }

        CdnRouteResult(
            label = route.label,
            host = host,
            port = route.port,
            tls = route.tls,
            path = route.path,
            state = state,
            attempts = attempts
        )
    }

    private fun attempt(
        network: Network,
        host: String,
        address: InetAddress,
        route: RouteSpec
    ): CdnIpAttempt {
        val started = System.nanoTime()
        var stage = "TCP"
        var raw: Socket? = null
        var active: Socket? = null
        var connectedMs: Long? = null
        var tlsProtocol: String? = null
        val ip = address.hostAddress ?: address.toString()

        return try {
            raw = network.socketFactory.createSocket().apply {
                connect(InetSocketAddress(address, route.port), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
            connectedMs = elapsedMs(started)

            active = if (route.tls) {
                stage = "TLS"
                val context = SSLContext.getInstance("TLS").apply {
                    init(null, null, SecureRandom())
                }
                (context.socketFactory.createSocket(raw, host, route.port, true) as SSLSocket).apply {
                    val params = sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    sslParameters = params
                    soTimeout = READ_TIMEOUT_MS
                    startHandshake()
                    tlsProtocol = session.protocol
                }
            } else {
                raw
            }

            stage = "WebSocket"
            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val webSocketKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val request = buildString {
                append("GET ${route.path} HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Connection: Upgrade\r\n")
                append("Upgrade: websocket\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Key: $webSocketKey\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Pragma: no-cache\r\n")
                append("User-Agent: AutomBot-Network-Probe/1.6\r\n\r\n")
            }
            active!!.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            active!!.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(active!!.getInputStream(), Charsets.US_ASCII))
            val statusLine = reader.readLine().orEmpty().take(MAX_STATUS_LINE)
            val headers = readHeaders(reader)
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            val expectedAccept = expectedWebSocketAccept(webSocketKey)
            val actualAccept = headers["sec-websocket-accept"]
            val acceptMatches = actualAccept == expectedAccept
            val classified = classify(host, route, statusCode, headers, acceptMatches)

            CdnIpAttempt(
                ip = ip,
                state = classified.first,
                statusLine = statusLine.ifBlank { null },
                statusCode = statusCode,
                connectMs = connectedMs,
                totalMs = elapsedMs(started),
                tlsProtocol = tlsProtocol,
                headers = headers,
                detail = classified.second
            )
        } catch (_: SocketTimeoutException) {
            CdnIpAttempt(
                ip = ip,
                state = CdnRouteState.TIMEOUT,
                statusLine = null,
                statusCode = null,
                connectMs = connectedMs,
                totalMs = elapsedMs(started),
                tlsProtocol = tlsProtocol,
                headers = emptyMap(),
                detail = "Timeout na etapa $stage para $ip:${route.port}."
            )
        } catch (error: SSLException) {
            failureAttempt(ip, connectedMs, started, tlsProtocol, "Falha TLS: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: ConnectException) {
            failureAttempt(ip, connectedMs, started, tlsProtocol, "Conexão TCP recusada/falhou: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: Exception) {
            failureAttempt(ip, connectedMs, started, tlsProtocol, "Falha na etapa $stage: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { active?.close() }
            if (active !== raw) runCatching { raw?.close() }
        }
    }

    private fun failureAttempt(
        ip: String,
        connectMs: Long?,
        started: Long,
        tlsProtocol: String?,
        detail: String
    ) = CdnIpAttempt(
        ip = ip,
        state = CdnRouteState.FAIL,
        statusLine = null,
        statusCode = null,
        connectMs = connectMs,
        totalMs = elapsedMs(started),
        tlsProtocol = tlsProtocol,
        headers = emptyMap(),
        detail = detail
    )

    private fun classify(
        host: String,
        route: RouteSpec,
        statusCode: Int?,
        headers: Map<String, String>,
        acceptMatches: Boolean
    ): Pair<CdnRouteState, String> {
        if (statusCode == 101) {
            return if (acceptMatches) {
                CdnRouteState.PASS to "Upgrade WebSocket 101 confirmado, incluindo Sec-WebSocket-Accept válido."
            } else {
                CdnRouteState.PARTIAL to "O servidor respondeu 101, mas o Sec-WebSocket-Accept não corresponde à chave enviada."
            }
        }

        val location = headers["location"].orEmpty()
        if (statusCode != null && statusCode in REDIRECT_CODES) {
            val detail = when {
                location.contains("portalrecarga.vivo.com.br", ignoreCase = true) ->
                    "Redirecionado para o portal da operadora antes de concluir o WebSocket."
                route.port == 80 && location.startsWith("https://$host", ignoreCase = true) ->
                    "A rota HTTP/80 foi redirecionada para HTTPS no mesmo FQDN; revise a Viewer Protocol Policy da CDN."
                location.isNotBlank() -> "Recebeu redirecionamento HTTP $statusCode para $location."
                else -> "Recebeu redirecionamento HTTP $statusCode sem header Location."
            }
            return CdnRouteState.REDIRECT to detail
        }

        return when {
            statusCode != null -> CdnRouteState.PARTIAL to
                "O servidor HTTP respondeu $statusCode, mas não realizou o upgrade WebSocket."
            else -> CdnRouteState.FAIL to "A resposta não continha uma linha de status HTTP válida."
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        repeat(MAX_HEADER_LINES) {
            val line = reader.readLine() ?: return headers
            if (line.isBlank()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) return@repeat
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim().take(MAX_HEADER_VALUE)
            if (name.isNotBlank() && value.isNotBlank()) {
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }
        }
        return headers
    }

    private fun expectedWebSocketAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + WEB_SOCKET_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun normalizePath(value: String): String {
        val trimmed = value.trim().substringBefore('#')
        return when {
            trimmed.isBlank() -> "/"
            trimmed.startsWith('/') -> trimmed
            else -> "/$trimmed"
        }.take(MAX_PATH_LENGTH)
    }

    private fun selectNetwork(forceCellular: Boolean): Network {
        if (!forceCellular) {
            return connectivity.activeNetwork ?: error("Nenhuma rede ativa foi encontrada.")
        }

        fun isPhysicalCellular(network: Network): Boolean {
            val caps = connectivity.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        connectivity.activeNetwork?.takeIf(::isPhysicalCellular)?.let { return it }
        return connectivity.allNetworks
            .filter(::isPhysicalCellular)
            .sortedByDescending { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            .firstOrNull()
            ?: error("Nenhuma rede celular física foi encontrada. Desligue Wi-Fi/VPN e mantenha os dados móveis ativos.")
    }

    private fun networkLabel(network: Network): String {
        val caps = connectivity.getNetworkCapabilities(network)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                "Rede móvel${if (validated) " · Internet validada" else " · sem validação de Internet geral"}"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                "Wi-Fi${if (validated) " · Internet validada" else " · sem validação de Internet geral"}"
            else -> "Rede física${if (validated) " · Internet validada" else ""}"
        }
    }

    private fun carrierName(network: Network): String? {
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null
        val telephony = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephony?.networkOperatorName?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_ADDRESSES = 8
        const val MAX_PATHS = 6
        const val MAX_PATH_LENGTH = 512
        const val MAX_STATUS_LINE = 512
        const val MAX_HEADER_LINES = 64
        const val MAX_HEADER_VALUE = 2_048
        const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
