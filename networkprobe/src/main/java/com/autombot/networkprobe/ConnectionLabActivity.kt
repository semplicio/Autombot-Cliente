package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private enum class LabState { PASS, WARN, FAIL, SKIP }

private data class ConnectionLabConfig(
    val targetHost: String,
    val targetPort: Int,
    val gatewayHost: String,
    val gatewayPort: Int,
    val gatewayTls: Boolean,
    val sniHost: String,
    val httpHost: String,
    val wsPath: String,
    val proxyUsername: String,
    val proxyPassword: String,
    val payload: String,
    val forceCellular: Boolean
)

private data class LabStep(
    val id: String,
    val title: String,
    val state: LabState,
    val latencyMs: Long? = null,
    val detail: String,
    val response: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("state", state.name.lowercase())
        .put("latency_ms", latencyMs ?: JSONObject.NULL)
        .put("detail", detail)
        .put("response", response ?: JSONObject.NULL)
}

private data class ConnectionLabReport(
    val networkLabel: String,
    val config: ConnectionLabConfig,
    val steps: List<LabStep>,
    val candidates: List<String>
) {
    fun toJson(): String = JSONObject()
        .put("tool", "AutomBot Connection Lab")
        .put("version", "1.5.0")
        .put("network", networkLabel)
        .put("target", JSONObject()
            .put("host", config.targetHost)
            .put("port", config.targetPort))
        .put("gateway", JSONObject()
            .put("host", config.gatewayHost)
            .put("port", config.gatewayPort)
            .put("tls", config.gatewayTls)
            .put("sni", config.sniHost)
            .put("http_host", config.httpHost)
            .put("ws_path", config.wsPath))
        .put("force_cellular", config.forceCellular)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        .put("candidates", JSONArray(candidates))
        .put("note", "Os testes usam somente os endpoints informados pelo operador. Alcance técnico não comprova política de cobrança/zero-rating da operadora.")
        .toString(2)

    fun toText(): String = buildString {
        appendLine("AUTOMBOT NETWORK PROBE — CONNECTION LAB")
        appendLine("Rede: $networkLabel")
        appendLine("Destino: ${config.targetHost}:${config.targetPort}")
        if (config.gatewayHost.isNotBlank()) {
            appendLine("Gateway/proxy: ${config.gatewayHost}:${config.gatewayPort}")
            appendLine("TLS no gateway: ${if (config.gatewayTls) "sim" else "não"}")
            if (config.sniHost.isNotBlank()) appendLine("SNI: ${config.sniHost}")
            if (config.httpHost.isNotBlank()) appendLine("Host HTTP/WS: ${config.httpHost}")
        }
        appendLine()
        steps.forEach { step ->
            val status = when (step.state) {
                LabState.PASS -> "OK"
                LabState.WARN -> "PARCIAL"
                LabState.FAIL -> "FALHA"
                LabState.SKIP -> "NÃO TESTADO"
            }
            appendLine("[$status] ${step.title}${step.latencyMs?.let { " · ${it}ms" } ?: ""}")
            appendLine(step.detail)
            step.response?.takeIf { it.isNotBlank() }?.let {
                appendLine("Resposta:")
                appendLine(it)
            }
            appendLine()
        }
        if (candidates.isNotEmpty()) {
            appendLine("ROTAS / TRANSPORTES CANDIDATOS")
            candidates.forEach { appendLine("- $it") }
            appendLine()
        }
        append("Observação: o Lab valida alcance e comportamento dos seus próprios endpoints; ele não confirma isenção de cobrança da operadora nem testa hostnames de terceiros.")
    }.trimEnd()
}

private data class OpenedSocket(
    val socket: Socket,
    val connectedIp: String,
    val latencyMs: Long
)

private data class TlsMeta(
    val protocol: String,
    val cipher: String,
    val hostnameVerified: Boolean,
    val subject: String?,
    val issuer: String?,
    val sans: List<String>,
    val alpn: String?
)

private class ConnectionLabEngine(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    suspend fun run(config: ConnectionLabConfig): ConnectionLabReport = withContext(Dispatchers.IO) {
        val network = selectNetwork(config.forceCellular)
        val networkLabel = describeNetwork(network, config.forceCellular)
        val steps = mutableListOf<LabStep>()

        steps += testDirectSsh(network, config)

        if (config.gatewayHost.isBlank()) {
            steps += LabStep("gateway_tcp", "Gateway TCP", LabState.SKIP, detail = "Gateway/proxy não informado.")
            steps += LabStep("tls_sni", "TLS / SNI", LabState.SKIP, detail = "Gateway/proxy não informado.")
            steps += LabStep("websocket", "WebSocket", LabState.SKIP, detail = "Gateway/proxy não informado.")
            steps += LabStep("payload", "Payload HTTP bruto", LabState.SKIP, detail = "Gateway/proxy não informado.")
            steps += LabStep("http_connect", "Proxy HTTP CONNECT → SSH", LabState.SKIP, detail = "Gateway/proxy não informado.")
            steps += LabStep("socks5", "Proxy SOCKS5 → SSH", LabState.SKIP, detail = "Gateway/proxy não informado.")
        } else {
            steps += testGatewayTcp(network, config)
            steps += testTlsSni(network, config)
            steps += testWebSocket(network, config)
            steps += testPayload(network, config)
            steps += testHttpConnect(network, config)
            steps += testSocks5(network, config)
        }

        val candidates = buildCandidates(steps)
        ConnectionLabReport(networkLabel, config, steps, candidates)
    }

    private fun selectNetwork(forceCellular: Boolean): Network? {
        if (!forceCellular) return connectivity.activeNetwork
        val active = connectivity.activeNetwork
        fun acceptable(network: Network): Boolean {
            val caps = connectivity.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        if (active != null && acceptable(active)) return active
        val candidates = connectivity.allNetworks.filter(::acceptable)
        val validated = candidates.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        return validated ?: candidates.firstOrNull()
            ?: throw IllegalStateException("Nenhuma rede celular física com capacidade de Internet foi encontrada. Desligue o Wi‑Fi/VPN, mantenha os dados móveis ativos e tente novamente.")
    }

    private fun describeNetwork(network: Network?, forcedCellular: Boolean): String {
        if (network == null) return if (forcedCellular) "Celular não encontrada" else "Rede padrão do Android"
        val caps = connectivity.getNetworkCapabilities(network)
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Rede celular física${if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " · validada" else " · sem validação geral"}"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi‑Fi"
            else -> "Rede padrão do Android"
        }
    }

    private fun testDirectSsh(network: Network?, config: ConnectionLabConfig): LabStep {
        if (config.targetHost.isBlank()) return LabStep("ssh_direct", "SSH direto / banner", LabState.SKIP, detail = "Servidor SSH não informado.")
        return runCatching {
            val opened = openTcp(network, config.targetHost, config.targetPort)
            opened.socket.use { socket ->
                val banner = readSshIdentification(socket)
                if (banner != null) {
                    LabStep(
                        "ssh_direct",
                        "SSH direto / banner",
                        LabState.PASS,
                        opened.latencyMs,
                        "Chegou ao servidor SSH em ${opened.connectedIp}. ${describeSshBanner(banner)}",
                        banner
                    )
                } else {
                    LabStep(
                        "ssh_direct",
                        "SSH direto / banner",
                        LabState.WARN,
                        opened.latencyMs,
                        "TCP chegou a ${opened.connectedIp}, mas nenhum banner SSH-2.0 foi recebido dentro do tempo de leitura."
                    )
                }
            }
        }.getOrElse { error -> failedStep("ssh_direct", "SSH direto / banner", error) }
    }

    private fun testGatewayTcp(network: Network?, config: ConnectionLabConfig): LabStep = runCatching {
        val opened = openTcp(network, config.gatewayHost, config.gatewayPort)
        opened.socket.close()
        LabStep(
            "gateway_tcp",
            "Gateway TCP",
            LabState.PASS,
            opened.latencyMs,
            "Conexão TCP estabelecida com ${config.gatewayHost}:${config.gatewayPort} via ${opened.connectedIp}."
        )
    }.getOrElse { failedStep("gateway_tcp", "Gateway TCP", it) }

    private fun testTlsSni(network: Network?, config: ConnectionLabConfig): LabStep {
        if (!config.gatewayTls) return LabStep("tls_sni", "TLS / SNI", LabState.SKIP, detail = "TLS do gateway está desligado para esta execução.")
        val sni = config.sniHost.ifBlank { config.gatewayHost }
        return runCatching {
            val opened = openTcp(network, config.gatewayHost, config.gatewayPort)
            val (ssl, meta) = wrapTls(opened.socket, config.gatewayHost, config.gatewayPort, sni)
            ssl.close()
            LabStep(
                "tls_sni",
                "TLS / SNI",
                if (meta.hostnameVerified) LabState.PASS else LabState.WARN,
                opened.latencyMs,
                buildString {
                    append("TLS respondeu em ${opened.connectedIp}; ${meta.protocol}; ${meta.cipher}. ")
                    append(if (meta.hostnameVerified) "Certificado válido para $sni." else "O certificado apresentado não valida para $sni.")
                    meta.alpn?.let { append(" ALPN: $it.") }
                    meta.issuer?.let { append(" Emissor: $it.") }
                },
                meta.sans.take(30).joinToString("\n").takeIf { it.isNotBlank() }
            )
        }.getOrElse { failedStep("tls_sni", "TLS / SNI", it) }
    }

    private fun testWebSocket(network: Network?, config: ConnectionLabConfig): LabStep {
        val host = config.httpHost.ifBlank { config.sniHost.ifBlank { config.gatewayHost } }
        if (host.isBlank()) return LabStep("websocket", "WebSocket", LabState.SKIP, detail = "Host HTTP/WebSocket não informado.")
        return runCatching {
            val opened = openGatewaySocket(network, config)
            opened.socket.use { socket ->
                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                val path = config.wsPath.trim().let { if (it.isBlank()) "/" else if (it.startsWith('/')) it else "/$it" }
                val request = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: $host\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("User-Agent: AutomBot-NetworkProbe/1.5\r\n\r\n")
                }
                socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                val input = BufferedInputStream(socket.inputStream)
                val statusLine = readAsciiLine(input)
                val headers = readHeaders(input)
                val status = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()
                val response = buildString {
                    statusLine?.let { appendLine(it) }
                    headers.take(20).forEach { appendLine(it) }
                }.trimEnd()
                when (status) {
                    101 -> LabStep("websocket", "WebSocket", LabState.PASS, opened.latencyMs, "Upgrade WebSocket confirmado para Host $host e path $path.", response)
                    null -> LabStep("websocket", "WebSocket", LabState.FAIL, opened.latencyMs, "O endpoint abriu a conexão, mas não devolveu uma resposta HTTP válida ao handshake WebSocket.", response.takeIf { it.isNotBlank() })
                    else -> LabStep("websocket", "WebSocket", LabState.WARN, opened.latencyMs, "Endpoint respondeu HTTP $status, mas não confirmou Upgrade 101 para $path.", response)
                }
            }
        }.getOrElse { failedStep("websocket", "WebSocket", it) }
    }

    private fun testPayload(network: Network?, config: ConnectionLabConfig): LabStep {
        if (config.payload.isBlank()) return LabStep("payload", "Payload HTTP bruto", LabState.SKIP, detail = "Nenhum payload informado.")
        return runCatching {
            val opened = openGatewaySocket(network, config)
            opened.socket.use { socket ->
                val expanded = expandPayload(config)
                socket.outputStream.write(expanded.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                val response = readAvailableText(socket, 12_000)
                val sshBanner = response.lineSequence().firstOrNull { it.startsWith("SSH-") }
                val status = response.lineSequence().firstOrNull { it.startsWith("HTTP/") }
                    ?.split(' ')?.getOrNull(1)?.toIntOrNull()
                when {
                    sshBanner != null -> LabStep("payload", "Payload HTTP bruto", LabState.PASS, opened.latencyMs, "Depois do payload o endpoint devolveu uma identificação SSH. ${describeSshBanner(sshBanner)}", response.take(6000))
                    status == 101 -> LabStep("payload", "Payload HTTP bruto", LabState.PASS, opened.latencyMs, "O payload recebeu HTTP 101; o gateway aceitou a solicitação de upgrade.", response.take(6000))
                    status != null && status in 200..299 -> LabStep("payload", "Payload HTTP bruto", LabState.WARN, opened.latencyMs, "O gateway respondeu HTTP $status ao payload. Há resposta de aplicação, mas ainda não foi confirmado um túnel SSH.", response.take(6000))
                    response.isNotBlank() -> LabStep("payload", "Payload HTTP bruto", LabState.WARN, opened.latencyMs, "O gateway respondeu ao payload; analise a resposta bruta para saber se a rota esperada foi aceita.", response.take(6000))
                    else -> LabStep("payload", "Payload HTTP bruto", LabState.WARN, opened.latencyMs, "Payload enviado, mas não houve resposta legível antes do timeout. Isso não confirma encaminhamento ao SSH.")
                }
            }
        }.getOrElse { failedStep("payload", "Payload HTTP bruto", it) }
    }

    private fun testHttpConnect(network: Network?, config: ConnectionLabConfig): LabStep {
        if (config.targetHost.isBlank()) return LabStep("http_connect", "Proxy HTTP CONNECT → SSH", LabState.SKIP, detail = "Servidor de destino não informado.")
        return runCatching {
            val opened = openGatewaySocket(network, config)
            opened.socket.use { socket ->
                val authority = "${config.targetHost}:${config.targetPort}"
                val auth = proxyAuthorization(config)
                val request = buildString {
                    append("CONNECT $authority HTTP/1.1\r\n")
                    append("Host: $authority\r\n")
                    append("Proxy-Connection: Keep-Alive\r\n")
                    append("User-Agent: AutomBot-NetworkProbe/1.5\r\n")
                    if (auth != null) append("Proxy-Authorization: Basic $auth\r\n")
                    append("\r\n")
                }
                socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                val input = BufferedInputStream(socket.inputStream)
                val statusLine = readAsciiLine(input)
                val headers = readHeaders(input)
                val status = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()
                val responseHead = buildString {
                    statusLine?.let { appendLine(it) }
                    headers.take(20).forEach { appendLine(it) }
                }.trimEnd()
                if (status != null && status in 200..299) {
                    val banner = readSshIdentification(input)
                    if (banner != null) {
                        LabStep("http_connect", "Proxy HTTP CONNECT → SSH", LabState.PASS, opened.latencyMs, "CONNECT foi aceito e o servidor final respondeu SSH. ${describeSshBanner(banner)}", "$responseHead\n$banner".trim())
                    } else {
                        LabStep("http_connect", "Proxy HTTP CONNECT → SSH", LabState.WARN, opened.latencyMs, "CONNECT foi aceito (HTTP $status), mas nenhum banner SSH chegou depois da abertura do túnel.", responseHead)
                    }
                } else {
                    LabStep("http_connect", "Proxy HTTP CONNECT → SSH", LabState.FAIL, opened.latencyMs, "O gateway não abriu um túnel HTTP CONNECT para $authority${status?.let { "; HTTP $it" } ?: ""}.", responseHead.takeIf { it.isNotBlank() })
                }
            }
        }.getOrElse { failedStep("http_connect", "Proxy HTTP CONNECT → SSH", it) }
    }

    private fun testSocks5(network: Network?, config: ConnectionLabConfig): LabStep {
        if (config.targetHost.isBlank()) return LabStep("socks5", "Proxy SOCKS5 → SSH", LabState.SKIP, detail = "Servidor de destino não informado.")
        return runCatching {
            val opened = openGatewaySocket(network, config)
            opened.socket.use { socket ->
                val input = BufferedInputStream(socket.inputStream)
                val output = socket.outputStream
                val hasAuth = config.proxyUsername.isNotBlank()
                if (hasAuth) output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)) else output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                val version = input.read()
                val method = input.read()
                if (version != 5 || method < 0 || method == 0xFF) {
                    return@use LabStep("socks5", "Proxy SOCKS5 → SSH", LabState.FAIL, opened.latencyMs, "O gateway não respondeu como SOCKS5 compatível (versão=$version, método=$method).")
                }
                if (method == 0x02) {
                    val user = config.proxyUsername.toByteArray(Charsets.UTF_8)
                    val pass = config.proxyPassword.toByteArray(Charsets.UTF_8)
                    require(user.size in 1..255 && pass.size <= 255) { "Credenciais SOCKS5 longas demais." }
                    output.write(byteArrayOf(0x01, user.size.toByte()))
                    output.write(user)
                    output.write(byteArrayOf(pass.size.toByte()))
                    output.write(pass)
                    output.flush()
                    val authVersion = input.read()
                    val authStatus = input.read()
                    if (authVersion != 1 || authStatus != 0) {
                        return@use LabStep("socks5", "Proxy SOCKS5 → SSH", LabState.FAIL, opened.latencyMs, "Autenticação SOCKS5 recusada.")
                    }
                }

                sendSocksConnect(output, config.targetHost, config.targetPort)
                val replyVersion = input.read()
                val replyCode = input.read()
                input.read() // RSV
                val atyp = input.read()
                if (replyVersion != 5 || replyCode != 0) {
                    return@use LabStep("socks5", "Proxy SOCKS5 → SSH", LabState.FAIL, opened.latencyMs, "SOCKS5 recusou a conexão ao destino (código=$replyCode).")
                }
                consumeSocksAddress(input, atyp)
                val banner = readSshIdentification(input)
                if (banner != null) {
                    LabStep("socks5", "Proxy SOCKS5 → SSH", LabState.PASS, opened.latencyMs, "SOCKS5 abriu o destino e o servidor respondeu SSH. ${describeSshBanner(banner)}", banner)
                } else {
                    LabStep("socks5", "Proxy SOCKS5 → SSH", LabState.WARN, opened.latencyMs, "SOCKS5 confirmou a abertura do destino, mas nenhum banner SSH chegou dentro do timeout.")
                }
            }
        }.getOrElse { failedStep("socks5", "Proxy SOCKS5 → SSH", it) }
    }

    private fun openGatewaySocket(network: Network?, config: ConnectionLabConfig): OpenedSocket {
        val opened = openTcp(network, config.gatewayHost, config.gatewayPort)
        if (!config.gatewayTls) return opened
        val sni = config.sniHost.ifBlank { config.gatewayHost }
        val (ssl, _) = wrapTls(opened.socket, config.gatewayHost, config.gatewayPort, sni)
        return opened.copy(socket = ssl)
    }

    private fun openTcp(network: Network?, host: String, port: Int): OpenedSocket {
        require(host.isNotBlank()) { "Host vazio." }
        require(port in 1..65535) { "Porta inválida: $port" }
        val addresses = resolve(network, host)
        var lastError: Exception? = null
        addresses.forEach { address ->
            val socket = if (network != null) network.socketFactory.createSocket() else Socket()
            try {
                socket.soTimeout = IO_TIMEOUT_MS
                val start = android.os.SystemClock.elapsedRealtime()
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                return OpenedSocket(socket, address.hostAddress ?: address.toString(), elapsed)
            } catch (error: Exception) {
                lastError = error
                runCatching { socket.close() }
            }
        }
        throw lastError ?: IllegalStateException("Não foi possível conectar a $host:$port")
    }

    private fun resolve(network: Network?, host: String): List<InetAddress> {
        numericAddress(host)?.let { return listOf(it) }
        val addresses = if (network != null) network.getAllByName(host) else InetAddress.getAllByName(host)
        if (addresses.isEmpty()) throw IllegalStateException("DNS não retornou endereço para $host")
        return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    private fun numericAddress(host: String): InetAddress? {
        val value = host.trim().removePrefix("[").removeSuffix("]")
        val ipv4 = value.split('.').let { parts ->
            parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() ?: 999) in 0..255 }
        }
        val ipv6 = ':' in value
        if (!ipv4 && !ipv6) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private fun wrapTls(raw: Socket, connectHost: String, port: Int, sni: String): Pair<SSLSocket, TlsMeta> {
        val ssl = SSLSocketFactory.getDefault().createSocket(raw, connectHost, port, true) as SSLSocket
        ssl.soTimeout = IO_TIMEOUT_MS
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(sni))
        ssl.sslParameters = params
        ssl.startHandshake()
        val session = ssl.session
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate
        val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, session)
        val sans = runCatching {
            cert?.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
                if ((entry.getOrNull(0) as? Int) == 2) entry.getOrNull(1)?.toString() else null
            }.distinct()
        }.getOrDefault(emptyList())
        val alpn = if (android.os.Build.VERSION.SDK_INT >= 29) ssl.applicationProtocol.takeIf { it.isNotBlank() } else null
        return ssl to TlsMeta(
            protocol = session.protocol,
            cipher = session.cipherSuite,
            hostnameVerified = verified,
            subject = cert?.subjectX500Principal?.name,
            issuer = cert?.issuerX500Principal?.name,
            sans = sans,
            alpn = alpn
        )
    }

    private fun readSshIdentification(socket: Socket): String? = readSshIdentification(BufferedInputStream(socket.inputStream))

    private fun readSshIdentification(input: InputStream): String? {
        repeat(8) {
            val line = readAsciiLine(input) ?: return null
            if (line.startsWith("SSH-")) return line.take(500)
        }
        return null
    }

    private fun describeSshBanner(banner: String): String = when {
        banner.contains("dropbear", ignoreCase = true) -> "Implementação detectada: Dropbear."
        banner.contains("openssh", ignoreCase = true) -> "Implementação detectada: OpenSSH."
        banner.startsWith("SSH-2.0-") -> "Servidor compatível com SSH 2.0; implementação customizada/não identificada."
        else -> "Identificação SSH recebida."
    }

    private fun expandPayload(config: ConnectionLabConfig): String = config.payload
        .replace("[crlf]", "\r\n", ignoreCase = true)
        .replace("[host]", config.targetHost, ignoreCase = true)
        .replace("[port]", config.targetPort.toString(), ignoreCase = true)
        .replace("[entry_host]", config.gatewayHost, ignoreCase = true)
        .replace("[entry_port]", config.gatewayPort.toString(), ignoreCase = true)
        .replace("[proxy_host]", config.gatewayHost, ignoreCase = true)
        .replace("[proxy_port]", config.gatewayPort.toString(), ignoreCase = true)
        .replace("[sni]", config.sniHost.ifBlank { config.gatewayHost }, ignoreCase = true)
        .replace("[http_host]", config.httpHost.ifBlank { config.sniHost.ifBlank { config.gatewayHost } }, ignoreCase = true)

    private fun proxyAuthorization(config: ConnectionLabConfig): String? {
        if (config.proxyUsername.isBlank()) return null
        val raw = "${config.proxyUsername}:${config.proxyPassword}"
        return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun sendSocksConnect(output: java.io.OutputStream, host: String, port: Int) {
        val literal = numericAddress(host)
        val head = byteArrayOf(0x05, 0x01, 0x00)
        output.write(head)
        when (literal) {
            is Inet4Address -> {
                output.write(0x01)
                output.write(literal.address)
            }
            is Inet6Address -> {
                output.write(0x04)
                output.write(literal.address)
            }
            else -> {
                val bytes = host.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..255) { "Hostname de destino longo demais para SOCKS5." }
                output.write(0x03)
                output.write(bytes.size)
                output.write(bytes)
            }
        }
        output.write((port shr 8) and 0xFF)
        output.write(port and 0xFF)
        output.flush()
    }

    private fun consumeSocksAddress(input: InputStream, atyp: Int) {
        when (atyp) {
            0x01 -> readExact(input, 4)
            0x04 -> readExact(input, 16)
            0x03 -> {
                val len = input.read()
                if (len < 0) error("Resposta SOCKS5 incompleta")
                readExact(input, len)
            }
            else -> error("ATYP SOCKS5 inválido: $atyp")
        }
        readExact(input, 2)
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) error("Resposta terminou antes do esperado")
            offset += read
        }
        return out
    }

    private fun readHeaders(input: InputStream): List<String> {
        val result = mutableListOf<String>()
        repeat(64) {
            val line = readAsciiLine(input) ?: return result
            if (line.isEmpty()) return result
            result += line
        }
        return result
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val value = input.read()
            if (value < 0) break
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        if (bytes.isEmpty()) return null
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun readAvailableText(socket: Socket, maxBytes: Int): String {
        socket.soTimeout = SHORT_READ_TIMEOUT_MS
        val input = socket.inputStream
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (output.size() < maxBytes) {
            try {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (read <= 0) break
                output.write(buffer, 0, read)
                if (input.available() == 0 && output.size() > 0) break
            } catch (_: SocketTimeoutException) {
                break
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun failedStep(id: String, title: String, error: Throwable): LabStep = LabStep(
        id = id,
        title = title,
        state = LabState.FAIL,
        detail = error.message ?: error.javaClass.simpleName
    )

    private fun buildCandidates(steps: List<LabStep>): List<String> {
        val byId = steps.associateBy { it.id }
        val candidates = mutableListOf<String>()
        if (byId["ssh_direct"]?.state == LabState.PASS) candidates += "SSH direto: o destino devolveu banner SSH válido."
        if (byId["http_connect"]?.state == LabState.PASS) candidates += "SSH via proxy HTTP CONNECT: o túnel chegou até o servidor SSH."
        if (byId["socks5"]?.state == LabState.PASS) candidates += "SSH via SOCKS5: o proxy abriu o destino e recebeu banner SSH."
        if (byId["payload"]?.state == LabState.PASS) candidates += "Gateway + payload: houve resposta positiva de aplicação/upgrade ou banner SSH após o payload."
        if (byId["tls_sni"]?.state == LabState.PASS) candidates += "TLS/SNI: o hostname foi aceito e o certificado confere."
        if (byId["websocket"]?.state == LabState.PASS) candidates += "WebSocket: Upgrade 101 confirmado; rota candidata para transportes WS autorizados (por exemplo VLESS/VMess/Trojan WS)."
        if (candidates.isEmpty()) candidates += "Nenhuma rota completa foi confirmada nesta execução; use os detalhes de cada etapa para localizar onde a cadeia quebra."
        return candidates
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val IO_TIMEOUT_MS = 5_000
        const val SHORT_READ_TIMEOUT_MS = 2_500
    }
}

class ConnectionLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val snapshot = CoreProfileStore(applicationContext).loadProfile()
        val sponsored = snapshot?.sponsoredManifest?.active
        val origin = snapshot?.protocols?.firstOrNull { !it.originHost.isNullOrBlank() }
        val sshProfile = snapshot?.protocols?.firstOrNull { it.type.equals("ssh", ignoreCase = true) }

        val initialGateway = sponsored?.domain.orEmpty()
        val initialGatewayPort = sponsored?.tcpPort ?: 443
        val initialTarget = origin?.originHost ?: sshProfile?.host ?: snapshot?.publicIp.orEmpty()
        val initialTargetPort = origin?.originPort ?: sshProfile?.ports?.firstOrNull() ?: 22

        setContent {
            MaterialTheme {
                ConnectionLabScreen(
                    engine = remember { ConnectionLabEngine(applicationContext) },
                    initialGateway = initialGateway,
                    initialGatewayPort = initialGatewayPort,
                    initialTarget = initialTarget,
                    initialTargetPort = initialTargetPort,
                    onShareText = { ReportShare.shareText(this, "AutomBot — Connection Lab", it.toText()) },
                    onShareJson = { ReportShare.share(this, it.toJson()) }
                )
            }
        }
    }
}

@Composable
private fun ConnectionLabScreen(
    engine: ConnectionLabEngine,
    initialGateway: String,
    initialGatewayPort: Int,
    initialTarget: String,
    initialTargetPort: Int,
    onShareText: (ConnectionLabReport) -> Unit,
    onShareJson: (ConnectionLabReport) -> Unit
) {
    var targetHost by remember { mutableStateOf(initialTarget) }
    var targetPort by remember { mutableStateOf(initialTargetPort.toString()) }
    var gatewayHost by remember { mutableStateOf(initialGateway) }
    var gatewayPort by remember { mutableStateOf(initialGatewayPort.toString()) }
    var gatewayTls by remember { mutableStateOf(initialGatewayPort == 443) }
    var sniHost by remember { mutableStateOf(initialGateway) }
    var httpHost by remember { mutableStateOf(initialGateway) }
    var wsPath by remember { mutableStateOf("/") }
    var proxyUsername by remember { mutableStateOf("") }
    var proxyPassword by remember { mutableStateOf("") }
    var payload by remember {
        mutableStateOf("GET / HTTP/1.1[crlf]Host: [http_host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf][crlf]")
    }
    var forceCellular by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ConnectionLabReport?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = LabBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LabCard {
                    Text("Connection Lab", color = LabText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Valida, em etapas, servidor SSH/Dropbear, gateway/proxy, TLS/SNI, WebSocket, payload HTTP, HTTP CONNECT e SOCKS5 usando somente endpoints da sua própria infraestrutura.",
                        color = LabDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            item {
                LabCard {
                    Text("Servidor final", color = LabAccent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LabField(targetHost, { targetHost = it; error = null }, "Host/IP do SSH")
                    Spacer(Modifier.height(8.dp))
                    LabField(targetPort, { targetPort = it.filter(Char::isDigit); error = null }, "Porta SSH", KeyboardType.Number)
                }
            }

            item {
                LabCard {
                    Text("Gateway / proxy / domínio patrocinado", color = LabAccent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LabField(gatewayHost, { gatewayHost = it; error = null }, "Host/IP de entrada")
                    Spacer(Modifier.height(8.dp))
                    LabField(gatewayPort, { gatewayPort = it.filter(Char::isDigit); error = null }, "Porta de entrada", KeyboardType.Number)
                    Spacer(Modifier.height(8.dp))
                    ToggleLabRow("TLS no gateway", "Liga TLS antes de WebSocket/payload/proxy; normalmente usado em 443.", gatewayTls) { gatewayTls = it }
                    Spacer(Modifier.height(8.dp))
                    LabField(sniHost, { sniHost = it; error = null }, "SNI autorizado")
                    Spacer(Modifier.height(8.dp))
                    LabField(httpHost, { httpHost = it; error = null }, "Host HTTP / WebSocket")
                    Spacer(Modifier.height(8.dp))
                    LabField(wsPath, { wsPath = it; error = null }, "WebSocket path")
                }
            }

            item {
                LabCard {
                    Text("Proxy (opcional)", color = LabAccent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LabField(proxyUsername, { proxyUsername = it; error = null }, "Usuário do proxy")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = proxyPassword,
                        onValueChange = { proxyPassword = it; error = null },
                        label = { Text("Senha do proxy") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = labFieldColors()
                    )
                }
            }

            item {
                LabCard {
                    Text("Payload bruto", color = LabAccent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Placeholders: [host] [port] [entry_host] [entry_port] [proxy_host] [proxy_port] [sni] [http_host] [crlf]",
                        color = LabDim,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it; error = null },
                        label = { Text("Payload") },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = labFieldColors()
                    )
                }
            }

            item {
                LabCard {
                    ToggleLabRow(
                        "Forçar rede celular física",
                        "Quando ligado, os sockets são criados diretamente na interface 4G/5G e VPN/Wi‑Fi não são usados para os testes.",
                        forceCellular
                    ) { forceCellular = it }
                }
            }

            error?.let { item { Text(it, color = LabFail, fontSize = 12.sp) } }

            item {
                Button(
                    onClick = {
                        val tPort = targetPort.toIntOrNull()
                        val gPort = gatewayPort.toIntOrNull()
                        when {
                            targetHost.isBlank() -> error = "Informe o servidor final SSH."
                            tPort == null || tPort !in 1..65535 -> error = "Porta SSH inválida."
                            gatewayHost.isNotBlank() && (gPort == null || gPort !in 1..65535) -> error = "Porta do gateway inválida."
                            gatewayTls && sniHost.isBlank() -> error = "Com TLS ligado, informe o SNI autorizado."
                            else -> {
                                running = true
                                error = null
                                report = null
                                scope.launch {
                                    runCatching {
                                        engine.run(
                                            ConnectionLabConfig(
                                                targetHost = targetHost.trim(),
                                                targetPort = tPort,
                                                gatewayHost = gatewayHost.trim(),
                                                gatewayPort = gPort ?: 443,
                                                gatewayTls = gatewayTls,
                                                sniHost = sniHost.trim(),
                                                httpHost = httpHost.trim(),
                                                wsPath = wsPath.trim(),
                                                proxyUsername = proxyUsername,
                                                proxyPassword = proxyPassword,
                                                payload = payload,
                                                forceCellular = forceCellular
                                            )
                                        )
                                    }.onSuccess { report = it }
                                        .onFailure { error = it.message ?: it.javaClass.simpleName }
                                    running = false
                                }
                            }
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LabAccent)
                ) {
                    if (running) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Executando cadeia…")
                    } else {
                        Text("Executar Connection Lab", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            report?.let { current ->
                item {
                    LabCard {
                        Text("Resultado", color = LabText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(current.networkLabel, color = LabDim, fontSize = 11.sp)
                    }
                }
                items(current.steps, key = { it.id }) { step -> LabStepCard(step) }
                item {
                    LabCard {
                        Text("Rotas / transportes candidatos", color = LabAccent, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        current.candidates.forEach { Text("• $it", color = LabDim, fontSize = 11.sp, lineHeight = 16.sp) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onShareText(current) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = LabSurfaceAlt)
                        ) { Text("TXT", color = LabText) }
                        Button(
                            onClick = { onShareJson(current) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = LabSurfaceAlt)
                        ) { Text("JSON", color = LabText) }
                    }
                }
            }

            item {
                Text(
                    "O Connection Lab não autentica no SSH e não tenta descobrir infraestrutura de terceiros. Ele apenas verifica se os endpoints informados aceitam cada camada e se a resposta volta ao aparelho.",
                    color = LabDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun LabStepCard(step: LabStep) {
    val color = when (step.state) {
        LabState.PASS -> LabPass
        LabState.WARN -> LabWarn
        LabState.FAIL -> LabFail
        LabState.SKIP -> LabDim
    }
    val label = when (step.state) {
        LabState.PASS -> "OK"
        LabState.WARN -> "PARCIAL"
        LabState.FAIL -> "FALHA"
        LabState.SKIP -> "NÃO TESTADO"
    }
    LabCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(step.title, color = LabText, fontWeight = FontWeight.Bold)
                Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            step.latencyMs?.let { Text("${it}ms", color = LabDim, fontSize = 11.sp) }
        }
        Spacer(Modifier.height(6.dp))
        Text(step.detail, color = LabDim, fontSize = 11.sp, lineHeight = 16.sp)
        step.response?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.take(6000), color = LabText, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun ToggleLabRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = LabText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = LabDim, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = LabAccent, checkedTrackColor = LabAccent.copy(alpha = 0.35f))
        )
    }
}

@Composable
private fun LabField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = labFieldColors()
    )
}

@Composable
private fun labFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LabText,
    unfocusedTextColor = LabText,
    focusedBorderColor = LabAccent,
    unfocusedBorderColor = LabLine,
    focusedLabelColor = LabAccent,
    unfocusedLabelColor = LabDim,
    cursorColor = LabAccent
)

@Composable
private fun LabCard(content: @Composable Column.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LabSurface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
}

private val LabBackground = Color(0xFF120E1B)
private val LabSurface = Color(0xFF1C1628)
private val LabSurfaceAlt = Color(0xFF292039)
private val LabAccent = Color(0xFF8B5CF6)
private val LabText = Color(0xFFF5F2FA)
private val LabDim = Color(0xFFAAA1B9)
private val LabLine = Color(0xFF3A3049)
private val LabPass = Color(0xFF4ADE80)
private val LabWarn = Color(0xFFFBBF24)
private val LabFail = Color(0xFFF87171)
