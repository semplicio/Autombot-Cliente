package com.autombot.networkprobe

import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.text.KeyboardOptions
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

private data class SniInspectionReport(
    val ip: String,
    val host: String,
    val port: Int,
    val tcpOk: Boolean,
    val tlsOk: Boolean,
    val hostnameVerified: Boolean?,
    val tlsProtocol: String?,
    val cipherSuite: String?,
    val alpn: String?,
    val certificateSubject: String?,
    val certificateIssuer: String?,
    val certificateNotBefore: String?,
    val certificateNotAfter: String?,
    val certificateSans: List<String>,
    val httpStatus: Int?,
    val serverHeader: String?,
    val locationHeader: String?,
    val detail: String
) {
    fun toJson(): String = JSONObject()
        .put("tool", "AutomBot Manual SNI Inspector")
        .put("version", "1.4.0")
        .put("ip", ip)
        .put("sni", host)
        .put("port", port)
        .put("tcp_ok", tcpOk)
        .put("tls_ok", tlsOk)
        .put("hostname_verified", hostnameVerified ?: JSONObject.NULL)
        .put("tls_protocol", tlsProtocol ?: JSONObject.NULL)
        .put("cipher_suite", cipherSuite ?: JSONObject.NULL)
        .put("alpn", alpn ?: JSONObject.NULL)
        .put("certificate_subject", certificateSubject ?: JSONObject.NULL)
        .put("certificate_issuer", certificateIssuer ?: JSONObject.NULL)
        .put("certificate_not_before", certificateNotBefore ?: JSONObject.NULL)
        .put("certificate_not_after", certificateNotAfter ?: JSONObject.NULL)
        .put("certificate_sans", JSONArray(certificateSans))
        .put("http_status", httpStatus ?: JSONObject.NULL)
        .put("server", serverHeader ?: JSONObject.NULL)
        .put("location", locationHeader ?: JSONObject.NULL)
        .put("detail", detail)
        .toString(2)

    fun toText(): String = buildString {
        appendLine("AUTOMBOT NETWORK PROBE — TESTE SNI MANUAL")
        appendLine("IP: $ip:$port")
        appendLine("SNI/Host: $host")
        appendLine("TCP: ${if (tcpOk) "OK" else "FALHA"}")
        appendLine("TLS: ${if (tlsOk) "OK" else "FALHA"}")
        hostnameVerified?.let { appendLine("Certificado válido para o hostname: ${if (it) "SIM" else "NÃO"}") }
        tlsProtocol?.let { appendLine("TLS: $it") }
        cipherSuite?.let { appendLine("Cipher: $it") }
        alpn?.let { appendLine("ALPN: $it") }
        certificateSubject?.let { appendLine("Subject/CN: $it") }
        certificateIssuer?.let { appendLine("Issuer: $it") }
        certificateNotBefore?.let { appendLine("Válido desde: $it") }
        certificateNotAfter?.let { appendLine("Válido até: $it") }
        if (certificateSans.isNotEmpty()) appendLine("SANs: ${certificateSans.joinToString()}")
        httpStatus?.let { appendLine("HTTP: $it") }
        serverHeader?.let { appendLine("Server: $it") }
        locationHeader?.let { appendLine("Location: $it") }
        appendLine("Detalhe: $detail")
        appendLine()
        append("Interpretação: TCP+TLS OK confirma que o IP respondeu ao ClientHello enviado com esse SNI. Certificado válido para o hostname confirma identidade TLS. Uma resposta HTTP confirma que houve resposta de aplicação para o Host informado; isso não significa que o endpoint aceite proxy arbitrário.")
    }
}

private class SniInspectorEngine {
    suspend fun inspect(rawIp: String, rawHost: String, port: Int): SniInspectionReport = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Porta inválida." }
        val ip = normalizeIp(rawIp)
        val host = normalizeHost(rawHost)

        var raw: Socket? = null
        try {
            raw = Socket()
            raw.soTimeout = IO_TIMEOUT_MS
            raw.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)

            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = IO_TIMEOUT_MS
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            ssl.sslParameters = params
            ssl.startHandshake()

            ssl.use { socket ->
                val session = socket.session
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
                val sans = certificateDnsSans(cert)
                val http = requestHttp(socket, host)
                val alpn = if (Build.VERSION.SDK_INT >= 29) socket.applicationProtocol.takeIf { it.isNotBlank() } else null

                SniInspectionReport(
                    ip = ip,
                    host = host,
                    port = port,
                    tcpOk = true,
                    tlsOk = true,
                    hostnameVerified = verified,
                    tlsProtocol = session.protocol,
                    cipherSuite = session.cipherSuite,
                    alpn = alpn,
                    certificateSubject = cert?.subjectX500Principal?.name,
                    certificateIssuer = cert?.issuerX500Principal?.name,
                    certificateNotBefore = cert?.notBefore?.let(::formatDate),
                    certificateNotAfter = cert?.notAfter?.let(::formatDate),
                    certificateSans = sans,
                    httpStatus = http.status,
                    serverHeader = http.server,
                    locationHeader = http.location,
                    detail = when {
                        !verified -> "TLS respondeu ao SNI, porém o certificado apresentado não valida para esse hostname."
                        http.status != null -> "TCP + TLS/SNI + certificado + HTTP responderam para o IP e hostname informados."
                        else -> "TCP + TLS/SNI + certificado responderam; não foi possível ler uma linha HTTP válida."
                    }
                )
            }
        } catch (error: Exception) {
            SniInspectionReport(
                ip = runCatching { normalizeIp(rawIp) }.getOrDefault(rawIp.trim()),
                host = runCatching { normalizeHost(rawHost) }.getOrDefault(rawHost.trim()),
                port = port,
                tcpOk = raw?.isConnected == true,
                tlsOk = false,
                hostnameVerified = null,
                tlsProtocol = null,
                cipherSuite = null,
                alpn = null,
                certificateSubject = null,
                certificateIssuer = null,
                certificateNotBefore = null,
                certificateNotAfter = null,
                certificateSans = emptyList(),
                httpStatus = null,
                serverHeader = null,
                locationHeader = null,
                detail = error.message ?: error.javaClass.simpleName
            )
        } finally {
            runCatching { raw?.close() }
        }
    }

    private data class HttpReply(val status: Int?, val server: String?, val location: String?)

    private fun requestHttp(socket: SSLSocket, host: String): HttpReply {
        return runCatching {
            val request = "HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: AutomBot-NetworkProbe/1.4\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            val input = BufferedInputStream(socket.inputStream)
            val statusLine = readAsciiLine(input)
            val status = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()
            var server: String? = null
            var location: String? = null
            repeat(64) {
                val line = readAsciiLine(input) ?: return@repeat
                if (line.isEmpty()) return@repeat
                val name = line.substringBefore(':', "").trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                when (name) {
                    "server" -> server = value
                    "location" -> location = value
                }
            }
            HttpReply(status, server, location)
        }.getOrElse { HttpReply(null, null, null) }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val data = ArrayList<Byte>()
        while (data.size < 8192) {
            val value = input.read()
            if (value < 0) break
            if (value == '\n'.code) break
            if (value != '\r'.code) data += value.toByte()
        }
        if (data.isEmpty()) return null
        return data.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun certificateDnsSans(cert: X509Certificate?): List<String> {
        if (cert == null) return emptyList()
        return runCatching {
            cert.subjectAlternativeNames.orEmpty()
                .mapNotNull { entry ->
                    if ((entry.getOrNull(0) as? Int) == 2) entry.getOrNull(1)?.toString() else null
                }
                .distinct()
                .take(100)
        }.getOrDefault(emptyList())
    }

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
        require(isIpv4(text) || text.contains(':')) { "Informe um IPv4 ou IPv6 literal, não um domínio." }
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

    private fun formatDate(date: java.util.Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(date)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3500
        const val IO_TIMEOUT_MS = 5000
    }
}

class SniInspectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = SniInspectorEngine()
        setContent {
            MaterialTheme {
                var ip by remember { mutableStateOf(intent.getStringExtra(EXTRA_IP).orEmpty()) }
                var host by remember { mutableStateOf(intent.getStringExtra(EXTRA_SNI).orEmpty()) }
                var port by remember { mutableStateOf(intent.getIntExtra(EXTRA_PORT, 443).toString()) }
                var running by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var report by remember { mutableStateOf<SniInspectionReport?>(null) }
                val scope = rememberCoroutineScope()

                Surface(modifier = Modifier.fillMaxSize(), color = SniBackground) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            SniCard {
                                Text("Testar SNI manual", color = SniText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Informe um IP da sua infraestrutura e o hostname autorizado que deseja enviar como SNI. O Probe conecta diretamente ao IP, envia o hostname no ClientHello TLS e depois faz uma requisição HTTPS com o mesmo Host.",
                                    color = SniDim,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }

                        item {
                            SniCard {
                                SniField(ip, { ip = it; error = null }, "IP (IPv4 ou IPv6)")
                                Spacer(Modifier.height(10.dp))
                                SniField(host, { host = it; error = null }, "Hostname / SNI")
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = port,
                                    onValueChange = { port = it.filter(Char::isDigit); error = null },
                                    label = { Text("Porta TLS") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(13.dp),
                                    colors = sniFieldColors()
                                )
                            }
                        }

                        error?.let { message -> item { Text(message, color = SniFail, fontSize = 12.sp) } }

                        item {
                            Button(
                                onClick = {
                                    val parsedPort = port.toIntOrNull()
                                    if (parsedPort == null || parsedPort !in 1..65535) {
                                        error = "Porta inválida."
                                    } else {
                                        running = true
                                        error = null
                                        report = null
                                        scope.launch {
                                            runCatching { engine.inspect(ip, host, parsedPort) }
                                                .onSuccess { report = it }
                                                .onFailure { error = it.message ?: it.javaClass.simpleName }
                                            running = false
                                        }
                                    }
                                },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SniAccent)
                            ) {
                                if (running) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                                    Spacer(Modifier.padding(horizontal = 6.dp))
                                    Text("Testando SNI…")
                                } else {
                                    Text("Testar IP + SNI", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        report?.let { current ->
                            item {
                                SniCard {
                                    Text("${current.ip}:${current.port}", color = SniText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    Text("SNI: ${current.host}", color = SniAccent, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(8.dp))
                                    Text("TCP: ${if (current.tcpOk) "OK" else "FALHA"}", color = if (current.tcpOk) SniPass else SniFail)
                                    Text("TLS/SNI: ${if (current.tlsOk) "OK" else "FALHA"}", color = if (current.tlsOk) SniPass else SniFail)
                                    current.hostnameVerified?.let {
                                        Text("Certificado para o hostname: ${if (it) "VÁLIDO" else "NÃO CONFERE"}", color = if (it) SniPass else SniWarn)
                                    }
                                    current.httpStatus?.let { Text("HTTP: $it", color = SniText) }
                                    current.serverHeader?.let { Text("Server: $it", color = SniDim) }
                                    current.locationHeader?.let { Text("Location: $it", color = SniDim) }
                                    Spacer(Modifier.height(6.dp))
                                    Text(current.detail, color = SniDim, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                            }

                            if (current.tlsOk) {
                                item {
                                    SniCard {
                                        Text("TLS / certificado", color = SniAccent, fontWeight = FontWeight.Bold)
                                        current.tlsProtocol?.let { Text("Protocolo: $it", color = SniDim, fontSize = 11.sp) }
                                        current.cipherSuite?.let { Text("Cipher: $it", color = SniDim, fontSize = 11.sp) }
                                        current.alpn?.let { Text("ALPN: $it", color = SniDim, fontSize = 11.sp) }
                                        current.certificateSubject?.let { Text("Subject: $it", color = SniDim, fontSize = 11.sp) }
                                        current.certificateIssuer?.let { Text("Issuer: $it", color = SniDim, fontSize = 11.sp) }
                                        current.certificateNotBefore?.let { Text("Desde: $it", color = SniDim, fontSize = 11.sp) }
                                        current.certificateNotAfter?.let { Text("Até: $it", color = SniDim, fontSize = 11.sp) }
                                        if (current.certificateSans.isNotEmpty()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text("SANs (${current.certificateSans.size})", color = SniText, fontWeight = FontWeight.SemiBold)
                                            Text(current.certificateSans.joinToString("\n"), color = SniDim, fontSize = 10.sp, lineHeight = 15.sp)
                                        }
                                    }
                                }
                            }

                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { ReportShare.shareText(this@SniInspectorActivity, "AutomBot — teste SNI", current.toText()) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = SniSurfaceAlt)
                                    ) { Text("TXT", color = SniText) }
                                    Button(
                                        onClick = { ReportShare.share(this@SniInspectorActivity, current.toJson()) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = SniSurfaceAlt)
                                    ) { Text("JSON", color = SniText) }
                                }
                            }
                        }

                        item {
                            Text(
                                "Use somente endpoints e hostnames que você controla ou tem autorização para testar. Um SNI responder no mesmo IP não significa que ele possa encaminhar tráfego arbitrário ou funcionar como proxy.",
                                color = SniDim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(bottom = 18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_IP = "sni_ip"
        const val EXTRA_SNI = "sni_host"
        const val EXTRA_PORT = "sni_port"
    }
}

@Composable
private fun SniField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = sniFieldColors()
    )
}

@Composable
private fun sniFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SniText,
    unfocusedTextColor = SniText,
    focusedBorderColor = SniAccent,
    unfocusedBorderColor = SniLine,
    focusedLabelColor = SniAccent,
    unfocusedLabelColor = SniDim,
    cursorColor = SniAccent
)

@Composable
private fun SniCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SniSurface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = { content() }
    )
}

private val SniBackground = Color(0xFF120E1B)
private val SniSurface = Color(0xFF1C1628)
private val SniSurfaceAlt = Color(0xFF292039)
private val SniAccent = Color(0xFF8B5CF6)
private val SniText = Color(0xFFF5F2FA)
private val SniDim = Color(0xFFAAA1B9)
private val SniLine = Color(0xFF3A3049)
private val SniPass = Color(0xFF4ADE80)
private val SniWarn = Color(0xFFFBBF24)
private val SniFail = Color(0xFFF87171)
