package com.autombot.networkprobe

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.ColumnScope
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
import java.io.OutputStream
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

private data class LabConfig(
    val targetHost: String,
    val targetPort: Int,
    val entryHost: String,
    val entryPort: Int,
    val entryTls: Boolean,
    val sni: String,
    val httpHost: String,
    val wsPath: String,
    val proxyUser: String,
    val proxyPassword: String,
    val payload: String,
    val forceCellular: Boolean
)

private data class LabResult(
    val id: String,
    val name: String,
    val state: LabState,
    val detail: String,
    val latencyMs: Long? = null,
    val response: String? = null
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("state", state.name.lowercase())
        .put("detail", detail)
        .put("latency_ms", latencyMs ?: JSONObject.NULL)
        .put("response", response ?: JSONObject.NULL)
}

private data class LabReport(
    val network: String,
    val config: LabConfig,
    val results: List<LabResult>,
    val candidates: List<String>
) {
    fun json(): String = JSONObject()
        .put("tool", "AutomBot Connection Lab")
        .put("version", "1.6.0")
        .put("network", network)
        .put("target", "${config.targetHost}:${config.targetPort}")
        .put("entry", "${config.entryHost}:${config.entryPort}")
        .put("entry_tls", config.entryTls)
        .put("sni", config.sni)
        .put("http_host", config.httpHost)
        .put("ws_path", config.wsPath)
        .put("force_cellular", config.forceCellular)
        .put("results", JSONArray().apply { results.forEach { put(it.json()) } })
        .put("candidates", JSONArray(candidates))
        .put("note", "Somente endpoints informados pelo operador são testados. Alcance técnico não comprova política de cobrança ou zero-rating.")
        .toString(2)

    fun text(): String = buildString {
        appendLine("AUTOMBOT NETWORK PROBE — CONNECTION LAB")
        appendLine("Rede: $network")
        appendLine("Servidor final: ${config.targetHost}:${config.targetPort}")
        if (config.entryHost.isNotBlank()) appendLine("Entrada/gateway: ${config.entryHost}:${config.entryPort}")
        if (config.entryTls) appendLine("TLS/SNI: ${config.sni.ifBlank { config.entryHost }}")
        appendLine()
        results.forEach { result ->
            val status = when (result.state) {
                LabState.PASS -> "OK"
                LabState.WARN -> "PARCIAL"
                LabState.FAIL -> "FALHA"
                LabState.SKIP -> "NÃO TESTADO"
            }
            appendLine("[$status] ${result.name}${result.latencyMs?.let { " · ${it}ms" } ?: ""}")
            appendLine(result.detail)
            result.response?.takeIf { it.isNotBlank() }?.let { appendLine(it.take(6000)) }
            appendLine()
        }
        appendLine("ROTAS / TRANSPORTES CANDIDATOS")
        candidates.forEach { appendLine("- $it") }
        appendLine()
        append("Use os resultados apenas para configurar serviços e domínios que você controla ou está autorizado a operar.")
    }.trimEnd()
}

private data class Opened(val socket: Socket, val ip: String, val latencyMs: Long)

private class ConnectionLabEngine(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    suspend fun run(config: LabConfig): LabReport = withContext(Dispatchers.IO) {
        val network = pickNetwork(config.forceCellular)
        val results = mutableListOf<LabResult>()

        results += directSsh(network, config)
        if (config.entryHost.isBlank()) {
            listOf(
                "entry_tcp" to "Entrada TCP",
                "tls_sni" to "TLS / SNI",
                "websocket" to "WebSocket",
                "payload" to "Payload HTTP",
                "http_connect" to "HTTP CONNECT → SSH",
                "socks5" to "SOCKS5 → SSH"
            ).forEach { (id, name) -> results += LabResult(id, name, LabState.SKIP, "Entrada/gateway não informado.") }
        } else {
            results += entryTcp(network, config)
            results += tlsSni(network, config)
            results += websocket(network, config)
            results += payload(network, config)
            results += httpConnect(network, config)
            results += socks5(network, config)
        }

        LabReport(networkLabel(network, config.forceCellular), config, results, candidates(results))
    }

    private fun pickNetwork(forceCellular: Boolean): Network? {
        if (!forceCellular) return connectivity.activeNetwork
        fun cellular(network: Network): Boolean {
            val caps = connectivity.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        connectivity.activeNetwork?.takeIf(::cellular)?.let { return it }
        return connectivity.allNetworks
            .filter(::cellular)
            .sortedByDescending {
                connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            .firstOrNull()
            ?: error("Nenhuma rede celular física foi encontrada. Desligue Wi‑Fi/VPN, mantenha os dados móveis ativos e tente novamente.")
    }

    private fun networkLabel(network: Network?, forceCellular: Boolean): String {
        if (network == null) return if (forceCellular) "Celular indisponível" else "Rede padrão"
        val caps = connectivity.getNetworkCapabilities(network)
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                "Celular física${if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " · Internet geral validada" else " · sem validação de Internet geral"}"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi‑Fi"
            else -> "Rede padrão"
        }
    }

    private fun directSsh(network: Network?, c: LabConfig): LabResult = runCatching {
        val opened = open(network, c.targetHost, c.targetPort)
        opened.socket.use { socket ->
            val banner = sshBanner(BufferedInputStream(socket.inputStream))
            if (banner != null) LabResult("ssh_direct", "SSH direto / banner", LabState.PASS, "Servidor respondeu em ${opened.ip}. ${sshType(banner)}", opened.latencyMs, banner)
            else LabResult("ssh_direct", "SSH direto / banner", LabState.WARN, "TCP chegou em ${opened.ip}, mas nenhum banner SSH-2.0 foi recebido.", opened.latencyMs)
        }
    }.getOrElse { fail("ssh_direct", "SSH direto / banner", it) }

    private fun entryTcp(network: Network?, c: LabConfig): LabResult = runCatching {
        val opened = open(network, c.entryHost, c.entryPort)
        opened.socket.close()
        LabResult("entry_tcp", "Entrada TCP", LabState.PASS, "TCP respondeu em ${opened.ip}:${c.entryPort}.", opened.latencyMs)
    }.getOrElse { fail("entry_tcp", "Entrada TCP", it) }

    private fun tlsSni(network: Network?, c: LabConfig): LabResult {
        if (!c.entryTls) return LabResult("tls_sni", "TLS / SNI", LabState.SKIP, "TLS está desligado para esta rota.")
        val sni = c.sni.ifBlank { c.entryHost }
        return runCatching {
            val opened = open(network, c.entryHost, c.entryPort)
            val ssl = tls(opened.socket, c.entryHost, c.entryPort, sni)
            ssl.use {
                val session = it.session
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, session)
                val sans = cert?.subjectAlternativeNames.orEmpty().mapNotNull { item ->
                    if ((item.getOrNull(0) as? Int) == 2) item.getOrNull(1)?.toString() else null
                }.distinct().take(40)
                val alpn = if (android.os.Build.VERSION.SDK_INT >= 29) it.applicationProtocol.takeIf(String::isNotBlank) else null
                LabResult(
                    "tls_sni",
                    "TLS / SNI",
                    if (verified) LabState.PASS else LabState.WARN,
                    "${session.protocol} · ${session.cipherSuite}. Certificado para $sni: ${if (verified) "válido" else "não confere"}.${alpn?.let { value -> " ALPN: $value." } ?: ""}",
                    opened.latencyMs,
                    buildString {
                        cert?.subjectX500Principal?.name?.let { appendLine("Subject: $it") }
                        cert?.issuerX500Principal?.name?.let { appendLine("Issuer: $it") }
                        if (sans.isNotEmpty()) append("SANs: ${sans.joinToString()}")
                    }.trim()
                )
            }
        }.getOrElse { fail("tls_sni", "TLS / SNI", it) }
    }

    private fun websocket(network: Network?, c: LabConfig): LabResult = runCatching {
        val opened = gatewaySocket(network, c)
        opened.socket.use { socket ->
            val host = c.httpHost.ifBlank { c.sni.ifBlank { c.entryHost } }
            val path = c.wsPath.ifBlank { "/" }.let { if (it.startsWith('/')) it else "/$it" }
            val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = Base64.encodeToString(random, Base64.NO_WRAP)
            val request = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: $key\r\nUser-Agent: AutomBot-NetworkProbe/1.5\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            val input = BufferedInputStream(socket.inputStream)
            val first = line(input)
            val headers = headers(input)
            val status = first?.split(' ')?.getOrNull(1)?.toIntOrNull()
            val response = (listOfNotNull(first) + headers).joinToString("\n")
            if (status == 101) LabResult("websocket", "WebSocket", LabState.PASS, "Upgrade 101 confirmado para $host$path.", opened.latencyMs, response)
            else LabResult("websocket", "WebSocket", if (status != null) LabState.WARN else LabState.FAIL, "WebSocket não recebeu 101${status?.let { "; HTTP $it" } ?: ""}.", opened.latencyMs, response.takeIf(String::isNotBlank))
        }
    }.getOrElse { fail("websocket", "WebSocket", it) }

    private fun payload(network: Network?, c: LabConfig): LabResult {
        if (c.payload.isBlank()) return LabResult("payload", "Payload HTTP", LabState.SKIP, "Payload vazio.")
        return runCatching {
            val opened = gatewaySocket(network, c)
            opened.socket.use { socket ->
                val text = expand(c)
                socket.outputStream.write(text.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                val response = readText(socket)
                val ssh = response.lineSequence().firstOrNull { it.startsWith("SSH-") }
                val status = response.lineSequence().firstOrNull { it.startsWith("HTTP/") }?.split(' ')?.getOrNull(1)?.toIntOrNull()
                when {
                    ssh != null -> LabResult("payload", "Payload HTTP", LabState.PASS, "Após o payload houve banner SSH. ${sshType(ssh)}", opened.latencyMs, response)
                    status == 101 -> LabResult("payload", "Payload HTTP", LabState.PASS, "Payload recebeu HTTP 101.", opened.latencyMs, response)
                    status != null -> LabResult("payload", "Payload HTTP", LabState.WARN, "Gateway respondeu HTTP $status; houve resposta, mas não foi confirmado SSH após o payload.", opened.latencyMs, response)
                    response.isNotBlank() -> LabResult("payload", "Payload HTTP", LabState.WARN, "Gateway devolveu bytes; analise a resposta bruta.", opened.latencyMs, response)
                    else -> LabResult("payload", "Payload HTTP", LabState.WARN, "Payload enviado, sem resposta legível no intervalo de teste.", opened.latencyMs)
                }
            }
        }.getOrElse { fail("payload", "Payload HTTP", it) }
    }

    private fun httpConnect(network: Network?, c: LabConfig): LabResult = runCatching {
        val opened = gatewaySocket(network, c)
        opened.socket.use { socket ->
            val authority = "${c.targetHost}:${c.targetPort}"
            val auth = if (c.proxyUser.isBlank()) "" else {
                val value = Base64.encodeToString("${c.proxyUser}:${c.proxyPassword}".toByteArray(), Base64.NO_WRAP)
                "Proxy-Authorization: Basic $value\r\n"
            }
            val request = "CONNECT $authority HTTP/1.1\r\nHost: $authority\r\nProxy-Connection: Keep-Alive\r\n$auth\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            val input = BufferedInputStream(socket.inputStream)
            val first = line(input)
            val head = (listOfNotNull(first) + headers(input)).joinToString("\n")
            val status = first?.split(' ')?.getOrNull(1)?.toIntOrNull()
            if (status != null && status in 200..299) {
                val banner = sshBanner(input)
                if (banner != null) LabResult("http_connect", "HTTP CONNECT → SSH", LabState.PASS, "CONNECT abriu o destino e o banner SSH voltou. ${sshType(banner)}", opened.latencyMs, "$head\n$banner")
                else LabResult("http_connect", "HTTP CONNECT → SSH", LabState.WARN, "CONNECT foi aceito, porém nenhum banner SSH foi recebido.", opened.latencyMs, head)
            } else LabResult("http_connect", "HTTP CONNECT → SSH", LabState.FAIL, "CONNECT não abriu o destino${status?.let { "; HTTP $it" } ?: ""}.", opened.latencyMs, head.takeIf(String::isNotBlank))
        }
    }.getOrElse { fail("http_connect", "HTTP CONNECT → SSH", it) }

    private fun socks5(network: Network?, c: LabConfig): LabResult = runCatching {
        val opened = gatewaySocket(network, c)
        opened.socket.use { socket ->
            val input = BufferedInputStream(socket.inputStream)
            val output = socket.outputStream
            val wantAuth = c.proxyUser.isNotBlank()
            output.write(if (wantAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); output.flush()
            val version = input.read(); val method = input.read()
            if (version != 5 || method < 0 || method == 255) return@use LabResult("socks5", "SOCKS5 → SSH", LabState.FAIL, "Entrada não aceitou negociação SOCKS5.", opened.latencyMs)
            if (method == 2) {
                val user = c.proxyUser.toByteArray(); val pass = c.proxyPassword.toByteArray()
                require(user.size in 1..255 && pass.size <= 255) { "Credenciais SOCKS5 inválidas." }
                output.write(byteArrayOf(1, user.size.toByte())); output.write(user); output.write(byteArrayOf(pass.size.toByte())); output.write(pass); output.flush()
                if (input.read() != 1 || input.read() != 0) return@use LabResult("socks5", "SOCKS5 → SSH", LabState.FAIL, "Autenticação SOCKS5 recusada.", opened.latencyMs)
            }
            socksConnect(output, c.targetHost, c.targetPort)
            val replyVersion = input.read(); val reply = input.read(); input.read(); val atyp = input.read()
            if (replyVersion != 5 || reply != 0) return@use LabResult("socks5", "SOCKS5 → SSH", LabState.FAIL, "SOCKS5 recusou o destino (código $reply).", opened.latencyMs)
            consumeSocksAddress(input, atyp)
            val banner = sshBanner(input)
            if (banner != null) LabResult("socks5", "SOCKS5 → SSH", LabState.PASS, "SOCKS5 abriu o destino e recebeu SSH. ${sshType(banner)}", opened.latencyMs, banner)
            else LabResult("socks5", "SOCKS5 → SSH", LabState.WARN, "SOCKS5 abriu o destino, mas não chegou banner SSH.", opened.latencyMs)
        }
    }.getOrElse { fail("socks5", "SOCKS5 → SSH", it) }

    private fun gatewaySocket(network: Network?, c: LabConfig): Opened {
        val opened = open(network, c.entryHost, c.entryPort)
        if (!c.entryTls) return opened
        return opened.copy(socket = tls(opened.socket, c.entryHost, c.entryPort, c.sni.ifBlank { c.entryHost }))
    }

    private fun open(network: Network?, host: String, port: Int): Opened {
        require(host.isNotBlank()) { "Host vazio." }
        require(port in 1..65535) { "Porta inválida." }
        val addresses = resolve(network, host)
        var last: Exception? = null
        for (address in addresses) {
            val socket = if (network != null) network.socketFactory.createSocket() else Socket()
            try {
                socket.soTimeout = IO_TIMEOUT
                val start = android.os.SystemClock.elapsedRealtime()
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT)
                return Opened(socket, address.hostAddress ?: host, android.os.SystemClock.elapsedRealtime() - start)
            } catch (error: Exception) {
                last = error
                runCatching { socket.close() }
            }
        }
        throw last ?: error("Falha ao conectar")
    }

    private fun resolve(network: Network?, host: String): List<InetAddress> {
        literal(host)?.let { return listOf(it) }
        val found = if (network != null) network.getAllByName(host) else InetAddress.getAllByName(host)
        return found.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    private fun literal(host: String): InetAddress? {
        val value = host.trim().removePrefix("[").removeSuffix("]")
        val v4 = value.split('.').let { p -> p.size == 4 && p.all { it.all(Char::isDigit) && (it.toIntOrNull() ?: 999) in 0..255 } }
        if (!v4 && ':' !in value) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private fun tls(raw: Socket, peerHost: String, peerPort: Int, sni: String): SSLSocket {
        val ssl = SSLSocketFactory.getDefault().createSocket(raw, peerHost, peerPort, true) as SSLSocket
        ssl.soTimeout = IO_TIMEOUT
        ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
        ssl.startHandshake()
        return ssl
    }

    private fun sshBanner(input: InputStream): String? {
        repeat(8) {
            val value = line(input) ?: return null
            if (value.startsWith("SSH-")) return value.take(500)
        }
        return null
    }

    private fun sshType(banner: String): String = when {
        banner.contains("dropbear", true) -> "Dropbear detectado."
        banner.contains("openssh", true) -> "OpenSSH detectado."
        banner.startsWith("SSH-2.0-") -> "SSH 2.0 compatível; implementação customizada/não identificada."
        else -> "Identificação SSH recebida."
    }

    private fun expand(c: LabConfig): String = c.payload
        .replace("[crlf]", "\r\n", true)
        .replace("[host]", c.targetHost, true)
        .replace("[port]", c.targetPort.toString(), true)
        .replace("[entry_host]", c.entryHost, true)
        .replace("[entry_port]", c.entryPort.toString(), true)
        .replace("[proxy_host]", c.entryHost, true)
        .replace("[proxy_port]", c.entryPort.toString(), true)
        .replace("[sni]", c.sni.ifBlank { c.entryHost }, true)
        .replace("[http_host]", c.httpHost.ifBlank { c.sni.ifBlank { c.entryHost } }, true)

    private fun readText(socket: Socket): String {
        socket.soTimeout = SHORT_TIMEOUT
        val input = socket.inputStream
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (out.size() < 12000) {
            try {
                val count = input.read(buffer, 0, minOf(buffer.size, 12000 - out.size()))
                if (count <= 0) break
                out.write(buffer, 0, count)
                if (input.available() == 0) break
            } catch (_: SocketTimeoutException) { break }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun line(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < 8192) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) out.write(value)
        }
        if (out.size() == 0) return null
        return out.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun headers(input: InputStream): List<String> {
        val out = mutableListOf<String>()
        repeat(64) {
            val value = line(input) ?: return out
            if (value.isBlank()) return out
            out += value
        }
        return out
    }

    private fun socksConnect(output: OutputStream, host: String, port: Int) {
        output.write(byteArrayOf(5, 1, 0))
        when (val address = literal(host)) {
            is Inet4Address -> { output.write(1); output.write(address.address) }
            is Inet6Address -> { output.write(4); output.write(address.address) }
            else -> {
                val bytes = host.toByteArray()
                require(bytes.size in 1..255) { "Hostname SOCKS5 inválido." }
                output.write(3); output.write(bytes.size); output.write(bytes)
            }
        }
        output.write((port shr 8) and 255); output.write(port and 255); output.flush()
    }

    private fun consumeSocksAddress(input: InputStream, atyp: Int) {
        when (atyp) {
            1 -> exact(input, 4)
            4 -> exact(input, 16)
            3 -> exact(input, input.read().also { require(it >= 0) })
            else -> error("Resposta SOCKS5 inválida.")
        }
        exact(input, 2)
    }

    private fun exact(input: InputStream, amount: Int) {
        var left = amount
        val buffer = ByteArray(32)
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(left, buffer.size))
            if (read < 0) error("Resposta terminou antes do esperado.")
            left -= read
        }
    }

    private fun fail(id: String, name: String, error: Throwable) = LabResult(id, name, LabState.FAIL, error.message ?: error.javaClass.simpleName)

    private fun candidates(results: List<LabResult>): List<String> {
        val map = results.associateBy { it.id }
        val out = mutableListOf<String>()
        if (map["ssh_direct"]?.state == LabState.PASS) out += "SSH direto confirmado pelo banner do servidor."
        if (map["http_connect"]?.state == LabState.PASS) out += "SSH via HTTP CONNECT confirmado até o banner do servidor."
        if (map["socks5"]?.state == LabState.PASS) out += "SSH via SOCKS5 confirmado até o banner do servidor."
        if (map["payload"]?.state == LabState.PASS) out += "Gateway + payload respondeu positivamente; valide esta cadeia no Connect com seu endpoint autorizado."
        if (map["tls_sni"]?.state == LabState.PASS) out += "TLS/SNI válido para o hostname informado."
        if (map["websocket"]?.state == LabState.PASS) out += "WebSocket 101 confirmado; rota compatível com transportes WS autorizados."
        if (out.isEmpty()) out += "Nenhuma cadeia completa foi confirmada; revise as etapas que falharam."
        return out
    }

    private companion object {
        const val CONNECT_TIMEOUT = 4000
        const val IO_TIMEOUT = 5000
        const val SHORT_TIMEOUT = 2500
    }
}

class ConnectionLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val snapshot = CoreProfileStore(applicationContext).loadProfile()
        val sponsored = snapshot?.sponsoredManifest?.active
        val origin = snapshot?.protocols?.firstOrNull { !it.originHost.isNullOrBlank() }
        val ssh = snapshot?.protocols?.firstOrNull { it.type.equals("ssh", true) }

        setContent {
            MaterialTheme {
                LabScreen(
                    engine = remember { ConnectionLabEngine(applicationContext) },
                    initialEntry = sponsored?.domain.orEmpty(),
                    initialEntryPort = sponsored?.tcpPort ?: 443,
                    initialTarget = origin?.originHost ?: ssh?.host ?: snapshot?.publicIp.orEmpty(),
                    initialTargetPort = origin?.originPort ?: ssh?.ports?.firstOrNull() ?: 22,
                    onOpenCdnRouteProbe = {
                        startActivity(Intent(this, CdnRouteProbeActivity::class.java))
                    },
                    onTxt = { ReportShare.shareText(this, "AutomBot — Connection Lab", it.text()) },
                    onJson = { ReportShare.share(this, it.json()) }
                )
            }
        }
    }
}

@Composable
private fun LabScreen(
    engine: ConnectionLabEngine,
    initialEntry: String,
    initialEntryPort: Int,
    initialTarget: String,
    initialTargetPort: Int,
    onOpenCdnRouteProbe: () -> Unit,
    onTxt: (LabReport) -> Unit,
    onJson: (LabReport) -> Unit
) {
    var target by remember { mutableStateOf(initialTarget) }
    var targetPort by remember { mutableStateOf(initialTargetPort.toString()) }
    var entry by remember { mutableStateOf(initialEntry) }
    var entryPort by remember { mutableStateOf(initialEntryPort.toString()) }
    var tls by remember { mutableStateOf(initialEntryPort == 443) }
    var sni by remember { mutableStateOf(initialEntry) }
    var httpHost by remember { mutableStateOf(initialEntry) }
    var path by remember { mutableStateOf("/") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("GET / HTTP/1.1[crlf]Host: [http_host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf][crlf]") }
    var cellular by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<LabReport?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = LabBg) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { LabCard { Text("Connection Lab", color = LabText, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Testa SSH/Dropbear, gateway, proxy, TLS/SNI, WebSocket e payload somente contra endpoints informados por você.", color = LabDim, fontSize = 12.sp, lineHeight = 17.sp) } }
            item {
                Button(
                    onClick = onOpenCdnRouteProbe,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LabAccent)
                ) {
                    Text("Testar CDN HTTP/80 + TLS/443", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            item { LabCard { Text("Servidor final", color = LabAccent, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Field(target, { target = it }, "Host/IP SSH"); Spacer(Modifier.height(8.dp)); Field(targetPort, { targetPort = it.filter(Char::isDigit) }, "Porta SSH", KeyboardType.Number) } }
            item { LabCard { Text("Entrada / proxy / domínio patrocinado", color = LabAccent, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Field(entry, { entry = it }, "Host/IP de entrada"); Spacer(Modifier.height(8.dp)); Field(entryPort, { entryPort = it.filter(Char::isDigit) }, "Porta de entrada", KeyboardType.Number); Spacer(Modifier.height(8.dp)); Toggle("Usar TLS nesta entrada", "Quando ligado, SNI/TLS é aplicado também aos testes de WS, payload e proxy.", tls) { tls = it }; Spacer(Modifier.height(8.dp)); Field(sni, { sni = it }, "SNI autorizado"); Spacer(Modifier.height(8.dp)); Field(httpHost, { httpHost = it }, "Host HTTP / WebSocket"); Spacer(Modifier.height(8.dp)); Field(path, { path = it }, "WebSocket path") } }
            item { LabCard { Text("Credenciais de proxy (opcional)", color = LabAccent, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Field(user, { user = it }, "Usuário"); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Senha") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), colors = fieldColors()) } }
            item { LabCard { Text("Payload bruto", color = LabAccent, fontWeight = FontWeight.Bold); Text("[host] [port] [entry_host] [entry_port] [proxy_host] [proxy_port] [sni] [http_host] [crlf]", color = LabDim, fontSize = 10.sp); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = payload, onValueChange = { payload = it }, label = { Text("Payload") }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), colors = fieldColors()) } }
            item { LabCard { Toggle("Forçar rede celular física", "Cria os sockets diretamente na interface 4G/5G, sem usar VPN/Wi‑Fi.", cellular) { cellular = it } } }
            error?.let { item { Text(it, color = LabFail, fontSize = 12.sp) } }
            item {
                Button(
                    onClick = {
                        val tp = targetPort.toIntOrNull(); val ep = entryPort.toIntOrNull()
                        when {
                            target.isBlank() -> error = "Informe o servidor SSH."
                            tp == null || tp !in 1..65535 -> error = "Porta SSH inválida."
                            entry.isNotBlank() && (ep == null || ep !in 1..65535) -> error = "Porta de entrada inválida."
                            tls && sni.isBlank() -> error = "Informe o SNI autorizado quando TLS estiver ligado."
                            else -> {
                                error = null; report = null; running = true
                                scope.launch {
                                    runCatching { engine.run(LabConfig(target.trim(), tp, entry.trim(), ep ?: 443, tls, sni.trim(), httpHost.trim(), path.trim(), user, password, payload, cellular)) }
                                        .onSuccess { report = it }
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
                ) { if (running) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Testando…") } else Text("Executar Connection Lab", fontWeight = FontWeight.SemiBold) }
            }
            report?.let { current ->
                item { LabCard { Text(current.network, color = LabText, fontWeight = FontWeight.Bold) } }
                items(current.results, key = { it.id }) { ResultCard(it) }
                item { LabCard { Text("Rotas / transportes candidatos", color = LabAccent, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); current.candidates.forEach { Text("• $it", color = LabDim, fontSize = 11.sp, lineHeight = 16.sp) } } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { onTxt(current) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = LabAlt)) { Text("TXT", color = LabText) }; Button(onClick = { onJson(current) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = LabAlt)) { Text("JSON", color = LabText) } } }
            }
            item { Text("A ferramenta não autentica no SSH e não descobre hostnames de terceiros. Ela confirma apenas se a cadeia informada responde e se algo volta ao aparelho.", color = LabDim, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 18.dp)) }
        }
    }
}

@Composable
private fun ResultCard(result: LabResult) {
    val color = when (result.state) { LabState.PASS -> LabPass; LabState.WARN -> LabWarn; LabState.FAIL -> LabFail; LabState.SKIP -> LabDim }
    val label = when (result.state) { LabState.PASS -> "OK"; LabState.WARN -> "PARCIAL"; LabState.FAIL -> "FALHA"; LabState.SKIP -> "NÃO TESTADO" }
    LabCard { Row(verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(result.name, color = LabText, fontWeight = FontWeight.Bold); Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold) }; result.latencyMs?.let { Text("${it}ms", color = LabDim, fontSize = 11.sp) } }; Spacer(Modifier.height(6.dp)); Text(result.detail, color = LabDim, fontSize = 11.sp, lineHeight = 16.sp); result.response?.takeIf(String::isNotBlank)?.let { Spacer(Modifier.height(8.dp)); Text(it.take(6000), color = LabText, fontSize = 10.sp, lineHeight = 14.sp) } }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(title, color = LabText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(subtitle, color = LabDim, fontSize = 10.sp, lineHeight = 14.sp) }; Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = LabAccent, checkedTrackColor = LabAccent.copy(alpha = 0.35f))) }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), colors = fieldColors())
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = LabText, unfocusedTextColor = LabText, focusedBorderColor = LabAccent, unfocusedBorderColor = LabLine, focusedLabelColor = LabAccent, unfocusedLabelColor = LabDim, cursorColor = LabAccent)

@Composable
private fun LabCard(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(LabSurface, RoundedCornerShape(18.dp)).padding(16.dp), content = content)
}

private val LabBg = Color(0xFF120E1B)
private val LabSurface = Color(0xFF1C1628)
private val LabAlt = Color(0xFF292039)
private val LabAccent = Color(0xFF8B5CF6)
private val LabText = Color(0xFFF5F2FA)
private val LabDim = Color(0xFFAAA1B9)
private val LabLine = Color(0xFF3A3049)
private val LabPass = Color(0xFF4ADE80)
private val LabWarn = Color(0xFFFBBF24)
private val LabFail = Color(0xFFF87171)
