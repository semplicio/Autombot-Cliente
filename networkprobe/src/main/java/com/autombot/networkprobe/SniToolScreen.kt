package com.autombot.networkprobe

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private data class SniToolResult(
    val ip: String,
    val host: String,
    val port: Int,
    val tcpOk: Boolean,
    val tlsOk: Boolean,
    val hostnameVerified: Boolean?,
    val protocol: String?,
    val cipher: String?,
    val alpn: String?,
    val subject: String?,
    val issuer: String?,
    val notBefore: String?,
    val notAfter: String?,
    val sans: List<String>,
    val httpStatus: Int?,
    val server: String?,
    val location: String?,
    val detail: String
) {
    fun json(): String = JSONObject()
        .put("tool", "AutomBot Manual SNI Test")
        .put("version", "1.4.0")
        .put("ip", ip)
        .put("sni", host)
        .put("port", port)
        .put("tcp_ok", tcpOk)
        .put("tls_sni_ok", tlsOk)
        .put("certificate_matches_hostname", hostnameVerified ?: JSONObject.NULL)
        .put("tls_protocol", protocol ?: JSONObject.NULL)
        .put("cipher", cipher ?: JSONObject.NULL)
        .put("alpn", alpn ?: JSONObject.NULL)
        .put("certificate_subject", subject ?: JSONObject.NULL)
        .put("certificate_issuer", issuer ?: JSONObject.NULL)
        .put("certificate_not_before", notBefore ?: JSONObject.NULL)
        .put("certificate_not_after", notAfter ?: JSONObject.NULL)
        .put("certificate_sans", JSONArray(sans))
        .put("http_status", httpStatus ?: JSONObject.NULL)
        .put("server", server ?: JSONObject.NULL)
        .put("location", location ?: JSONObject.NULL)
        .put("detail", detail)
        .toString(2)

    fun text(): String = buildString {
        appendLine("AUTOMBOT NETWORK PROBE — TESTE SNI MANUAL")
        appendLine("IP: $ip:$port")
        appendLine("SNI/Host: $host")
        appendLine("TCP: ${if (tcpOk) "OK" else "FALHA"}")
        appendLine("TLS/SNI: ${if (tlsOk) "OK" else "FALHA"}")
        hostnameVerified?.let { appendLine("Certificado confere com hostname: ${if (it) "SIM" else "NÃO"}") }
        protocol?.let { appendLine("Protocolo: $it") }
        cipher?.let { appendLine("Cipher: $it") }
        alpn?.let { appendLine("ALPN: $it") }
        subject?.let { appendLine("Subject: $it") }
        issuer?.let { appendLine("Issuer: $it") }
        notBefore?.let { appendLine("Válido desde: $it") }
        notAfter?.let { appendLine("Válido até: $it") }
        if (sans.isNotEmpty()) appendLine("SANs: ${sans.joinToString()}")
        httpStatus?.let { appendLine("HTTP: $it") }
        server?.let { appendLine("Server: $it") }
        location?.let { appendLine("Location: $it") }
        appendLine("Detalhe: $detail")
    }.trimEnd()
}

private class SniToolEngine {
    suspend fun run(ipInput: String, hostInput: String, port: Int): SniToolResult = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Porta TLS inválida." }
        val ip = normalizeIp(ipInput)
        val host = normalizeHost(hostInput)
        var raw: Socket? = null
        try {
            raw = Socket().apply {
                soTimeout = IO_TIMEOUT_MS
                connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            }
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = IO_TIMEOUT_MS
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(host))
            }
            ssl.startHandshake()
            ssl.use { socket ->
                val session = socket.session
                val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
                val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
                val reply = requestHttp(socket, host)
                SniToolResult(
                    ip = ip,
                    host = host,
                    port = port,
                    tcpOk = true,
                    tlsOk = true,
                    hostnameVerified = verified,
                    protocol = session.protocol,
                    cipher = session.cipherSuite,
                    alpn = if (Build.VERSION.SDK_INT >= 29) socket.applicationProtocol.takeIf(String::isNotBlank) else null,
                    subject = certificate?.subjectX500Principal?.name,
                    issuer = certificate?.issuerX500Principal?.name,
                    notBefore = certificate?.notBefore?.let(::formatDate),
                    notAfter = certificate?.notAfter?.let(::formatDate),
                    sans = dnsSans(certificate),
                    httpStatus = reply.status,
                    server = reply.server,
                    location = reply.location,
                    detail = when {
                        !verified -> "O endpoint respondeu ao SNI, mas o certificado não é válido para esse hostname."
                        reply.status != null -> "IP respondeu a TCP + TLS/SNI e a aplicação respondeu ao Host informado."
                        else -> "IP respondeu a TCP + TLS/SNI; não houve uma resposta HTTP legível."
                    }
                )
            }
        } catch (error: Exception) {
            SniToolResult(
                ip = ip,
                host = host,
                port = port,
                tcpOk = raw?.isConnected == true,
                tlsOk = false,
                hostnameVerified = null,
                protocol = null,
                cipher = null,
                alpn = null,
                subject = null,
                issuer = null,
                notBefore = null,
                notAfter = null,
                sans = emptyList(),
                httpStatus = null,
                server = null,
                location = null,
                detail = error.message ?: error.javaClass.simpleName
            )
        } finally {
            runCatching { raw?.close() }
        }
    }

    private data class HttpReply(val status: Int?, val server: String?, val location: String?)

    private fun requestHttp(socket: SSLSocket, host: String): HttpReply = runCatching {
        val request = "HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: AutomBot-NetworkProbe/1.4\r\nConnection: close\r\n\r\n"
        socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
        socket.outputStream.flush()
        val input = BufferedInputStream(socket.inputStream)
        val status = readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull()
        var server: String? = null
        var location: String? = null
        var count = 0
        while (count++ < 64) {
            val line = readLine(input) ?: break
            if (line.isBlank()) break
            val key = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            if (key == "server") server = value
            if (key == "location") location = value
        }
        HttpReply(status, server, location)
    }.getOrElse { HttpReply(null, null, null) }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ArrayList<Byte>()
        while (out.size < 8192) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) out += value.toByte()
        }
        if (out.isEmpty()) return null
        return out.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun dnsSans(cert: X509Certificate?): List<String> = runCatching {
        cert?.subjectAlternativeNames.orEmpty()
            .mapNotNull { item -> if ((item.getOrNull(0) as? Int) == 2) item.getOrNull(1)?.toString() else null }
            .distinct()
            .take(100)
    }.getOrDefault(emptyList())

    private fun normalizeHost(value: String): String {
        val host = IDN.toASCII(value.trim().trimEnd('.')).lowercase()
        require(host.contains('.') && host.length <= 253) { "Hostname/SNI inválido." }
        require(host.split('.').all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' }
        }) { "Hostname/SNI inválido." }
        return host
    }

    private fun normalizeIp(value: String): String {
        val text = value.trim().removePrefix("[").removeSuffix("]")
        require(isIpv4(text) || text.contains(':')) { "Informe um IP literal, não um domínio." }
        val parsed = InetAddress.getByName(text)
        if (text.contains(':')) require(parsed is Inet6Address) { "IPv6 inválido." }
        return parsed.hostAddress ?: text
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && (part.toIntOrNull() ?: 999) in 0..255
        }
    }

    private fun formatDate(value: java.util.Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(value)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3500
        const val IO_TIMEOUT_MS = 5000
    }
}

@Composable
fun SniToolScreen(
    onBack: () -> Unit,
    onShareText: (String) -> Unit,
    onShareJson: (String) -> Unit
) {
    val engine = remember { SniToolEngine() }
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<SniToolResult?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = SniToolBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = SniToolAlt)) {
                    Text("Voltar", color = SniToolText)
                }
            }
            item {
                SniToolCard {
                    Text("Testar SNI manual", color = SniToolText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Informe o IP do endpoint e o hostname autorizado. O teste ignora o DNS para a conexão: vai direto ao IP, envia o hostname no SNI do TLS e usa o mesmo valor no Host HTTP.",
                        color = SniToolDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
            item {
                SniToolCard {
                    SniToolField(ip, { ip = it; error = null }, "IP (IPv4 ou IPv6)")
                    Spacer(Modifier.height(10.dp))
                    SniToolField(host, { host = it; error = null }, "Hostname / SNI")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit); error = null },
                        label = { Text("Porta TLS") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = sniToolFieldColors()
                    )
                }
            }
            error?.let { message -> item { Text(message, color = SniToolFail) } }
            item {
                Button(
                    onClick = {
                        val parsedPort = port.toIntOrNull()
                        if (parsedPort == null || parsedPort !in 1..65535) {
                            error = "Porta inválida."
                        } else {
                            running = true
                            error = null
                            result = null
                            scope.launch {
                                runCatching { engine.run(ip, host, parsedPort) }
                                    .onSuccess { result = it }
                                    .onFailure { error = it.message ?: it.javaClass.simpleName }
                                running = false
                            }
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SniToolAccent)
                ) {
                    if (running) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text("Testando…")
                    } else Text("Testar IP + SNI", fontWeight = FontWeight.SemiBold)
                }
            }
            result?.let { current ->
                item {
                    SniToolCard {
                        Text("${current.ip}:${current.port}", color = SniToolText, fontWeight = FontWeight.Bold)
                        Text("SNI: ${current.host}", color = SniToolAccent)
                        Text("TCP: ${if (current.tcpOk) "OK" else "FALHA"}", color = if (current.tcpOk) SniToolPass else SniToolFail)
                        Text("TLS/SNI: ${if (current.tlsOk) "OK" else "FALHA"}", color = if (current.tlsOk) SniToolPass else SniToolFail)
                        current.hostnameVerified?.let {
                            Text("Certificado: ${if (it) "VÁLIDO PARA O HOST" else "HOST NÃO CONFERE"}", color = if (it) SniToolPass else SniToolWarn)
                        }
                        current.httpStatus?.let { Text("HTTP: $it", color = SniToolText) }
                        current.server?.let { Text("Server: $it", color = SniToolDim) }
                        current.location?.let { Text("Location: $it", color = SniToolDim) }
                        Spacer(Modifier.height(6.dp))
                        Text(current.detail, color = SniToolDim, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                if (current.tlsOk) {
                    item {
                        SniToolCard {
                            Text("TLS / certificado", color = SniToolAccent, fontWeight = FontWeight.Bold)
                            current.protocol?.let { Text("Protocolo: $it", color = SniToolDim, fontSize = 11.sp) }
                            current.cipher?.let { Text("Cipher: $it", color = SniToolDim, fontSize = 11.sp) }
                            current.alpn?.let { Text("ALPN: $it", color = SniToolDim, fontSize = 11.sp) }
                            current.subject?.let { Text("Subject: $it", color = SniToolDim, fontSize = 11.sp) }
                            current.issuer?.let { Text("Issuer: $it", color = SniToolDim, fontSize = 11.sp) }
                            current.notBefore?.let { Text("Desde: $it", color = SniToolDim, fontSize = 11.sp) }
                            current.notAfter?.let { Text("Até: $it", color = SniToolDim, fontSize = 11.sp) }
                            if (current.sans.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text("SANs (${current.sans.size})", color = SniToolText, fontWeight = FontWeight.SemiBold)
                                Text(current.sans.joinToString("\n"), color = SniToolDim, fontSize = 10.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onShareText(current.text()) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = SniToolAlt)) {
                            Text("TXT", color = SniToolText)
                        }
                        Button(onClick = { onShareJson(current.json()) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = SniToolAlt)) {
                            Text("JSON", color = SniToolText)
                        }
                    }
                }
            }
            item {
                Text(
                    "Use somente IPs e hostnames que você controla ou tem autorização para testar. HTTP 403/404 ainda pode indicar que a aplicação reconheceu o virtual host; não significa capacidade de proxy.",
                    color = SniToolDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun SniToolField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = sniToolFieldColors()
    )
}

@Composable
private fun sniToolFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SniToolText,
    unfocusedTextColor = SniToolText,
    focusedBorderColor = SniToolAccent,
    unfocusedBorderColor = SniToolLine,
    focusedLabelColor = SniToolAccent,
    unfocusedLabelColor = SniToolDim,
    cursorColor = SniToolAccent
)

@Composable
private fun SniToolCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SniToolSurface, RoundedCornerShape(18.dp)).padding(16.dp),
        content = { content() }
    )
}

private val SniToolBackground = Color(0xFF120E1B)
private val SniToolSurface = Color(0xFF1C1628)
private val SniToolAlt = Color(0xFF292039)
private val SniToolAccent = Color(0xFF8B5CF6)
private val SniToolText = Color(0xFFF5F2FA)
private val SniToolDim = Color(0xFFAAA1B9)
private val SniToolLine = Color(0xFF3A3049)
private val SniToolPass = Color(0xFF4ADE80)
private val SniToolWarn = Color(0xFFFBBF24)
private val SniToolFail = Color(0xFFF87171)
