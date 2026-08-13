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
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLException

data class SponsoredDomainEndpoint(
    val domain: String,
    val tcpPort: Int,
    val udpPort: Int,
    val bootstrapIps: List<String>,
    val validUntil: String? = null,
    val active: Boolean = false
)

data class SponsoredDomainManifest(
    val revision: String,
    val enabled: Boolean,
    val xrayEnabled: Boolean,
    val active: SponsoredDomainEndpoint?,
    val previous: List<SponsoredDomainEndpoint>,
    val rawJson: String
) {
    val endpoints: List<SponsoredDomainEndpoint>
        get() = listOfNotNull(active) + previous

    companion object {
        fun parse(raw: String): SponsoredDomainManifest? = runCatching {
            val root = JSONObject(raw)

            fun parseEndpoint(item: JSONObject?, active: Boolean): SponsoredDomainEndpoint? {
                if (item == null) return null
                val domain = item.optString("domain").trim()
                if (domain.isBlank()) return null
                val tcpPort = item.optInt("tcp_port", 443).takeIf { it in 1..65535 } ?: 443
                val udpPort = item.optInt("udp_port", 443).takeIf { it in 1..65535 } ?: 443
                val ips = mutableListOf<String>()
                val array = item.optJSONArray("bootstrap_ips") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotBlank() && value !in ips) ips += value
                }
                return SponsoredDomainEndpoint(
                    domain = domain,
                    tcpPort = tcpPort,
                    udpPort = udpPort,
                    bootstrapIps = ips,
                    validUntil = item.optString("valid_until").takeIf { it.isNotBlank() && it != "null" },
                    active = active
                )
            }

            val previous = mutableListOf<SponsoredDomainEndpoint>()
            val previousJson = root.optJSONArray("previous") ?: JSONArray()
            for (index in 0 until previousJson.length()) {
                parseEndpoint(previousJson.optJSONObject(index), active = false)?.let { previous += it }
            }

            SponsoredDomainManifest(
                revision = root.optString("revision"),
                enabled = root.optBoolean("enabled", false),
                xrayEnabled = root.optJSONObject("services")?.optBoolean("xray", false) ?: false,
                active = parseEndpoint(root.optJSONObject("active"), active = true),
                previous = previous,
                rawJson = raw
            )
        }.getOrNull()
    }
}

enum class SponsoredDomainProbeState {
    CONFIRMED,
    REACHABLE,
    TIMEOUT,
    TLS_ERROR,
    MANIFEST_MISMATCH,
    ERROR
}

data class SponsoredDomainProbeItem(
    val endpoint: SponsoredDomainEndpoint,
    val resolvedIps: List<String>,
    val attemptedIps: List<String>,
    val connectedIp: String?,
    val state: SponsoredDomainProbeState,
    val httpStatus: Int?,
    val remoteRevision: String?,
    val detail: String
)

data class SponsoredDomainProbeReport(
    val cellularNetworkFound: Boolean,
    val items: List<SponsoredDomainProbeItem>,
    val note: String
) {
    val confirmed: List<SponsoredDomainProbeItem>
        get() = items.filter { it.state == SponsoredDomainProbeState.CONFIRMED }

    fun toJson(): JSONObject = JSONObject()
        .put("cellular_network_found", cellularNetworkFound)
        .put("note", note)
        .put("items", JSONArray().apply {
            items.forEach { item ->
                put(JSONObject()
                    .put("role", if (item.endpoint.active) "active" else "previous")
                    .put("domain", item.endpoint.domain)
                    .put("tcp_port", item.endpoint.tcpPort)
                    .put("udp_port", item.endpoint.udpPort)
                    .put("bootstrap_ips", JSONArray(item.endpoint.bootstrapIps))
                    .put("resolved_ips", JSONArray(item.resolvedIps))
                    .put("attempted_ips", JSONArray(item.attemptedIps))
                    .put("connected_ip", item.connectedIp ?: JSONObject.NULL)
                    .put("state", item.state.name.lowercase())
                    .put("http_status", item.httpStatus ?: JSONObject.NULL)
                    .put("remote_revision", item.remoteRevision ?: JSONObject.NULL)
                    .put("detail", item.detail)
                )
            }
        })

    fun toText(): String = buildString {
        appendLine("DOMÍNIO PATROCINADO — TESTE NA REDE MÓVEL")
        appendLine(note)
        appendLine()
        if (!cellularNetworkFound) {
            appendLine("Nenhuma rede celular com capacidade de Internet foi encontrada. Ative os dados móveis e execute novamente.")
            return@buildString
        }
        if (items.isEmpty()) {
            appendLine("O perfil salvo não possui manifesto de domínio patrocinado.")
            return@buildString
        }
        items.forEach { item ->
            val role = if (item.endpoint.active) "ATIVO" else "ANTERIOR"
            val status = when (item.state) {
                SponsoredDomainProbeState.CONFIRMED -> "ENDPOINT CONFIRMADO"
                SponsoredDomainProbeState.REACHABLE -> "ALCANÇÁVEL"
                SponsoredDomainProbeState.TIMEOUT -> "TIMEOUT"
                SponsoredDomainProbeState.TLS_ERROR -> "TLS FALHOU"
                SponsoredDomainProbeState.MANIFEST_MISMATCH -> "MANIFESTO DIVERGENTE"
                SponsoredDomainProbeState.ERROR -> "ERRO"
            }
            appendLine("$role ${item.endpoint.domain}:${item.endpoint.tcpPort} — $status")
            if (item.resolvedIps.isNotEmpty()) appendLine("  DNS móvel: ${item.resolvedIps.joinToString()}")
            if (item.endpoint.bootstrapIps.isNotEmpty()) appendLine("  bootstrap Core: ${item.endpoint.bootstrapIps.joinToString()}")
            item.connectedIp?.let { appendLine("  IP conectado: $it") }
            appendLine("  ${item.detail}")
            item.endpoint.validUntil?.let { appendLine("  válido até: $it") }
            appendLine()
        }
    }.trimEnd()
}

class SponsoredDomainReachabilityProbe(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun run(manifest: SponsoredDomainManifest?): SponsoredDomainProbeReport = withContext(Dispatchers.IO) {
        val note = "O teste usa somente os domínios ativo/anterior declarados pelo AutomBot Core. Ele confirma alcance técnico pela rede móvel, mas não descobre a lista interna da operadora nem prova isenção de cobrança/zero-rating."
        if (manifest == null || !manifest.enabled || manifest.endpoints.isEmpty()) {
            return@withContext SponsoredDomainProbeReport(
                cellularNetworkFound = selectCellularNetwork() != null,
                items = emptyList(),
                note = note
            )
        }

        val network = selectCellularNetwork()
            ?: return@withContext SponsoredDomainProbeReport(false, emptyList(), note)

        val items = manifest.endpoints
            .distinctBy { "${it.domain.lowercase()}:${it.tcpPort}" }
            .map { endpoint -> testEndpoint(network, endpoint) }

        SponsoredDomainProbeReport(true, items, note)
    }

    private fun testEndpoint(network: Network, endpoint: SponsoredDomainEndpoint): SponsoredDomainProbeItem {
        val resolved = runCatching {
            network.getAllByName(endpoint.domain)
                .mapNotNull { it.hostAddress }
                .distinct()
                .take(MAX_ADDRESSES)
        }.getOrDefault(emptyList())

        val candidates = LinkedHashSet<String>()
        resolved.forEach { candidates += it }
        endpoint.bootstrapIps.forEach { candidates += it }
        val attempted = mutableListOf<String>()
        var lastState = SponsoredDomainProbeState.ERROR
        var lastDetail = "nenhum IP disponível para teste"
        var lastHttp: Int? = null
        var lastRevision: String? = null

        for (ip in candidates.take(MAX_ADDRESSES)) {
            attempted += ip
            val result = attemptTlsManifest(network, endpoint, ip)
            lastState = result.state
            lastDetail = result.detail
            lastHttp = result.httpStatus
            lastRevision = result.remoteRevision
            if (result.state == SponsoredDomainProbeState.CONFIRMED) {
                return SponsoredDomainProbeItem(
                    endpoint = endpoint,
                    resolvedIps = resolved,
                    attemptedIps = attempted,
                    connectedIp = ip,
                    state = result.state,
                    httpStatus = result.httpStatus,
                    remoteRevision = result.remoteRevision,
                    detail = result.detail
                )
            }
        }

        return SponsoredDomainProbeItem(
            endpoint = endpoint,
            resolvedIps = resolved,
            attemptedIps = attempted,
            connectedIp = null,
            state = lastState,
            httpStatus = lastHttp,
            remoteRevision = lastRevision,
            detail = lastDetail
        )
    }

    private data class AttemptResult(
        val state: SponsoredDomainProbeState,
        val httpStatus: Int?,
        val remoteRevision: String?,
        val detail: String
    )

    private fun attemptTlsManifest(
        network: Network,
        endpoint: SponsoredDomainEndpoint,
        ip: String
    ): AttemptResult {
        return try {
            val raw = network.socketFactory.createSocket()
            raw.soTimeout = IO_TIMEOUT_MS
            raw.connect(InetSocketAddress(InetAddress.getByName(ip), endpoint.tcpPort), CONNECT_TIMEOUT_MS)

            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, endpoint.domain, endpoint.tcpPort, true) as SSLSocket
            ssl.soTimeout = IO_TIMEOUT_MS
            val parameters = ssl.sslParameters
            parameters.serverNames = listOf(SNIHostName(endpoint.domain))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = parameters
            ssl.startHandshake()

            ssl.use { socket ->
                val request = buildString {
                    append("GET $MANIFEST_PATH HTTP/1.1\r\n")
                    append("Host: ${endpoint.domain}\r\n")
                    append("User-Agent: AutomBot-NetworkProbe/1.0\r\n")
                    append("Accept: application/json\r\n")
                    append("Connection: close\r\n\r\n")
                }
                socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()

                val response = readHttpResponse(socket)
                val remote = runCatching { JSONObject(response.body) }.getOrNull()
                val remoteRevision = remote?.optString("revision")?.takeIf { it.isNotBlank() }
                val listed = manifestContainsDomain(remote, endpoint.domain)
                when {
                    response.statusCode == 200 && remote?.optBoolean("enabled", false) == true && listed ->
                        AttemptResult(
                            SponsoredDomainProbeState.CONFIRMED,
                            response.statusCode,
                            remoteRevision,
                            "TCP + TLS/SNI + manifesto HTTPS responderam pela rede celular; o próprio Core lista este domínio como endpoint patrocinado configurado."
                        )
                    response.statusCode == 200 ->
                        AttemptResult(
                            SponsoredDomainProbeState.MANIFEST_MISMATCH,
                            response.statusCode,
                            remoteRevision,
                            "o endpoint respondeu HTTPS, mas o manifesto retornado não lista ${endpoint.domain} como ativo/anterior"
                        )
                    else ->
                        AttemptResult(
                            SponsoredDomainProbeState.REACHABLE,
                            response.statusCode,
                            remoteRevision,
                            "TCP/TLS chegaram ao endpoint, mas o manifesto respondeu HTTP ${response.statusCode}"
                        )
                }
            }
        } catch (_: SocketTimeoutException) {
            AttemptResult(SponsoredDomainProbeState.TIMEOUT, null, null, "timeout na conexão/handshake pela rede celular")
        } catch (error: SSLException) {
            AttemptResult(SponsoredDomainProbeState.TLS_ERROR, null, null, error.message ?: "falha TLS/SNI")
        } catch (error: ConnectException) {
            AttemptResult(SponsoredDomainProbeState.ERROR, null, null, error.message ?: "conexão recusada")
        } catch (error: Exception) {
            AttemptResult(SponsoredDomainProbeState.ERROR, null, null, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun manifestContainsDomain(root: JSONObject?, domain: String): Boolean {
        if (root == null) return false
        val active = root.optJSONObject("active")?.optString("domain")
        if (active.equals(domain, ignoreCase = true)) return true
        val previous = root.optJSONArray("previous") ?: return false
        for (index in 0 until previous.length()) {
            if (previous.optJSONObject(index)?.optString("domain").equals(domain, ignoreCase = true)) return true
        }
        return false
    }

    private data class HttpResponse(val statusCode: Int, val body: String)

    private fun readHttpResponse(socket: SSLSocket): HttpResponse {
        val input = BufferedInputStream(socket.inputStream)
        val statusLine = readAsciiLine(input) ?: error("resposta HTTP vazia")
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        var contentLength: Int? = null
        var chunked = false
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.equals("Content-Length", ignoreCase = true)) contentLength = value.toIntOrNull()
            if (name.equals("Transfer-Encoding", ignoreCase = true) && value.contains("chunked", ignoreCase = true)) chunked = true
        }

        val bodyBytes = when {
            chunked -> readChunkedBody(input)
            contentLength != null -> readExact(input, contentLength.coerceAtMost(MAX_BODY_BYTES))
            else -> readUntilEof(input, MAX_BODY_BYTES)
        }
        return HttpResponse(statusCode, bodyBytes.toString(Charsets.UTF_8))
    }

    private fun readChunkedBody(input: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < MAX_BODY_BYTES) {
            val sizeLine = readAsciiLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) break
            val part = readExact(input, size.coerceAtMost(MAX_BODY_BYTES - out.size()))
            out.write(part)
            readAsciiLine(input)
            if (part.size < size) break
        }
        return out.toByteArray()
    }

    private fun readExact(input: BufferedInputStream, amount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        var remaining = amount
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun readUntilEof(input: BufferedInputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var seenAny = false
        while (out.size() < 8192) {
            val value = input.read()
            if (value < 0) return if (seenAny) out.toString(Charsets.US_ASCII.name()).trimEnd('\r') else null
            seenAny = true
            if (value == '\n'.code) break
            out.write(value)
        }
        return out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
    }

    private fun selectCellularNetwork(): Network? {
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            Triple(
                network,
                network == connectivity.activeNetwork,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }
        return candidates.sortedWith(
            compareByDescending<Triple<Network, Boolean, Boolean>> { it.third }
                .thenByDescending { it.second }
        ).firstOrNull()?.first
    }

    private companion object {
        const val MANIFEST_PATH = "/v1/dominio-patrocinado/manifesto"
        const val CONNECT_TIMEOUT_MS = 2200
        const val IO_TIMEOUT_MS = 3500
        const val MAX_ADDRESSES = 6
        const val MAX_BODY_BYTES = 64 * 1024
    }
}
