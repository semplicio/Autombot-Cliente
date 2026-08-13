package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private enum class ManualDomainState {
    REACHABLE,
    TCP_ONLY,
    DNS_ERROR,
    TIMEOUT,
    TLS_ERROR,
    ERROR
}

private data class ManualDomainTarget(
    val domain: String,
    val port: Int,
    val tls: Boolean
)

private data class ManualDomainResult(
    val target: ManualDomainTarget,
    val coreDeclared: Boolean,
    val resolvedIps: List<String>,
    val connectedIp: String?,
    val httpStatus: Int?,
    val state: ManualDomainState,
    val detail: String
)

private data class ManualDomainReport(
    val cellularNetworkFound: Boolean,
    val results: List<ManualDomainResult>
) {
    fun toJson(): String = JSONObject()
        .put("tool", "AutomBot Manual Domain Probe")
        .put("version", "1.1.0")
        .put("cellular_network_found", cellularNetworkFound)
        .put("results", JSONArray().apply {
            results.forEach { item ->
                put(JSONObject()
                    .put("domain", item.target.domain)
                    .put("port", item.target.port)
                    .put("tls", item.target.tls)
                    .put("declared_by_core", item.coreDeclared)
                    .put("resolved_ips", JSONArray(item.resolvedIps))
                    .put("connected_ip", item.connectedIp ?: JSONObject.NULL)
                    .put("http_status", item.httpStatus ?: JSONObject.NULL)
                    .put("state", item.state.name.lowercase())
                    .put("detail", item.detail)
                )
            }
        })
        .put(
            "note",
            "A lista é informada manualmente. O teste mede somente alcance técnico na rede móvel e comparação com o manifesto salvo do AutomBot Core; não descobre a lista interna da operadora e não prova isenção de cobrança."
        )
        .toString(2)

    fun toText(): String = buildString {
        appendLine("AUTOMBOT NETWORK PROBE — LISTA MANUAL DE DOMÍNIOS")
        appendLine("Rede: dados móveis / 4G / 5G")
        appendLine()
        if (!cellularNetworkFound) {
            appendLine("Nenhuma interface celular com capacidade de Internet foi encontrada.")
            return@buildString
        }
        results.forEach { item ->
            val state = when (item.state) {
                ManualDomainState.REACHABLE -> "ALCANÇÁVEL"
                ManualDomainState.TCP_ONLY -> "TCP ALCANÇÁVEL"
                ManualDomainState.DNS_ERROR -> "DNS FALHOU"
                ManualDomainState.TIMEOUT -> "TIMEOUT"
                ManualDomainState.TLS_ERROR -> "TLS/SNI FALHOU"
                ManualDomainState.ERROR -> "ERRO"
            }
            appendLine("${item.target.domain}:${item.target.port} — $state")
            appendLine("  Core: ${if (item.coreDeclared) "declarado no manifesto patrocinado salvo" else "não declarado no manifesto patrocinado salvo"}")
            if (item.resolvedIps.isNotEmpty()) appendLine("  DNS móvel: ${item.resolvedIps.joinToString()}")
            item.connectedIp?.let { appendLine("  IP conectado: $it") }
            item.httpStatus?.let { appendLine("  HTTP: $it") }
            appendLine("  ${item.detail}")
            appendLine()
        }
        appendLine("Observação: resposta de DNS/TCP/TLS/HTTP não comprova que a operadora classifica o domínio como patrocinado nem que o tráfego será isento de cobrança.")
    }.trimEnd()
}

private class ManualDomainProbeEngine(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun run(targets: List<ManualDomainTarget>, manifest: SponsoredDomainManifest?): ManualDomainReport =
        withContext(Dispatchers.IO) {
            val network = selectCellularNetwork()
                ?: return@withContext ManualDomainReport(false, emptyList())
            val declared = manifest?.endpoints
                ?.map { it.domain.lowercase() }
                ?.toSet()
                .orEmpty()
            ManualDomainReport(
                cellularNetworkFound = true,
                results = targets.map { target -> test(network, target, target.domain.lowercase() in declared) }
            )
        }

    private fun test(network: Network, target: ManualDomainTarget, coreDeclared: Boolean): ManualDomainResult {
        val addresses = try {
            network.getAllByName(target.domain)
                .distinctBy { it.hostAddress }
                .take(MAX_ADDRESSES)
        } catch (error: Exception) {
            return ManualDomainResult(
                target, coreDeclared, emptyList(), null, null,
                ManualDomainState.DNS_ERROR,
                error.message ?: "falha ao resolver pela rede móvel"
            )
        }
        if (addresses.isEmpty()) {
            return ManualDomainResult(
                target, coreDeclared, emptyList(), null, null,
                ManualDomainState.DNS_ERROR,
                "DNS móvel não retornou endereço"
            )
        }

        var lastState = ManualDomainState.ERROR
        var lastDetail = "nenhuma tentativa concluída"
        val resolved = addresses.mapNotNull { it.hostAddress }
        for (address in addresses) {
            try {
                network.socketFactory.createSocket().use { raw ->
                    raw.soTimeout = IO_TIMEOUT_MS
                    raw.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                    if (!target.tls) {
                        val status = requestHttp(raw, target.domain)
                        return ManualDomainResult(
                            target, coreDeclared, resolved, address.hostAddress, status,
                            if (status != null) ManualDomainState.REACHABLE else ManualDomainState.TCP_ONLY,
                            status?.let { "DNS + TCP + Host HTTP responderam pela rede móvel" }
                                ?: "conexão TCP estabelecida pela rede móvel"
                        )
                    }

                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, target.domain, target.port, true) as SSLSocket
                    ssl.soTimeout = IO_TIMEOUT_MS
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(target.domain))
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    ssl.sslParameters = params
                    ssl.startHandshake()
                    ssl.use { socket ->
                        val status = requestHttp(socket, target.domain)
                        return ManualDomainResult(
                            target, coreDeclared, resolved, address.hostAddress, status,
                            ManualDomainState.REACHABLE,
                            if (status != null) {
                                "DNS + TCP + TLS/SNI + HTTP responderam pela rede móvel"
                            } else {
                                "DNS + TCP + TLS/SNI concluíram pela rede móvel"
                            }
                        )
                    }
                }
            } catch (_: SocketTimeoutException) {
                lastState = ManualDomainState.TIMEOUT
                lastDetail = "timeout em ${address.hostAddress}:${target.port}"
            } catch (error: javax.net.ssl.SSLException) {
                lastState = ManualDomainState.TLS_ERROR
                lastDetail = error.message ?: "falha TLS/SNI"
            } catch (error: Exception) {
                lastState = ManualDomainState.ERROR
                lastDetail = error.message ?: error.javaClass.simpleName
            }
        }
        return ManualDomainResult(target, coreDeclared, resolved, null, null, lastState, lastDetail)
    }

    private fun requestHttp(socket: java.net.Socket, host: String): Int? {
        return runCatching {
            val request = "HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: AutomBot-NetworkProbe/1.1\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            val line = readAsciiLine(BufferedInputStream(socket.inputStream)) ?: return@runCatching null
            line.split(' ').getOrNull(1)?.toIntOrNull()
        }.getOrNull()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (bytes.size < 4096) {
            val value = input.read()
            if (value < 0) break
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        if (bytes.isEmpty()) return null
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun selectCellularNetwork(): Network? {
        val active = connectivity.activeNetwork
        return connectivity.allNetworks
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                Triple(
                    network,
                    network == active,
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }
            .sortedWith(
                compareByDescending<Triple<Network, Boolean, Boolean>> { it.second }
                    .thenByDescending { it.third }
            )
            .firstOrNull()?.first
    }

    private companion object {
        const val MAX_ADDRESSES = 4
        const val CONNECT_TIMEOUT_MS = 2500
        const val IO_TIMEOUT_MS = 3500
    }
}

class ManualDomainProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("manual_domain_probe", Context.MODE_PRIVATE)
        val profileStore = CoreProfileStore(applicationContext)
        val engine = ManualDomainProbeEngine(applicationContext)

        setContent {
            MaterialTheme {
                var input by remember { mutableStateOf(prefs.getString("domains", "").orEmpty()) }
                var running by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var report by remember { mutableStateOf<ManualDomainReport?>(null) }
                val scope = rememberCoroutineScope()

                Surface(modifier = Modifier.fillMaxSize(), color = ManualBackground) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            ManualCard {
                                Text("Verificar lista manual de domínios", color = ManualText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Informe um domínio por linha, vírgula ou ponto e vírgula. Sem porta, o teste usa HTTPS/443. Você também pode usar domínio:80, http://domínio ou https://domínio:porta.",
                                    color = ManualDim,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it; error = null },
                                label = { Text("Domínios para testar") },
                                placeholder = { Text("exemplo.com\noutro.exemplo.com:443") },
                                minLines = 6,
                                maxLines = 12,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = ManualText,
                                    unfocusedTextColor = ManualText,
                                    focusedBorderColor = ManualAccent,
                                    unfocusedBorderColor = ManualLine,
                                    focusedLabelColor = ManualAccent,
                                    unfocusedLabelColor = ManualDim,
                                    cursorColor = ManualAccent
                                )
                            )
                        }

                        item {
                            Text(
                                "O app não procura domínios automaticamente. Ele testa apenas os nomes que você informar e mostra separadamente se cada domínio também está declarado no manifesto patrocinado salvo do seu AutomBot Core.",
                                color = ManualDim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }

                        error?.let { message -> item { Text(message, color = ManualFail, fontSize = 12.sp) } }

                        item {
                            Button(
                                onClick = {
                                    val parsed = parseManualTargets(input)
                                    if (parsed.isFailure) {
                                        error = parsed.exceptionOrNull()?.message ?: "Lista inválida."
                                    } else {
                                        val targets = parsed.getOrThrow()
                                        prefs.edit().putString("domains", input).apply()
                                        running = true
                                        report = null
                                        error = null
                                        scope.launch {
                                            report = engine.run(targets, profileStore.loadSponsoredManifest())
                                            running = false
                                        }
                                    }
                                },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ManualAccent)
                            ) {
                                if (running) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                                    Spacer(Modifier.padding(horizontal = 6.dp))
                                    Text("Testando na rede móvel…")
                                } else {
                                    Text("Testar lista no 4G/5G", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        report?.let { current ->
                            if (!current.cellularNetworkFound) {
                                item {
                                    ManualCard { Text("Nenhuma rede celular disponível. Desligue o Wi-Fi se necessário e mantenha os dados móveis ativos.", color = ManualFail) }
                                }
                            } else {
                                items(current.results) { item -> ManualDomainResultCard(item) }
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = { ReportShare.shareText(this@ManualDomainProbeActivity, "AutomBot — domínios manuais", current.toText()) },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = ManualSurfaceAlt)
                                        ) { Text("TXT", color = ManualText) }
                                        Button(
                                            onClick = { ReportShare.share(this@ManualDomainProbeActivity, current.toJson()) },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = ManualSurfaceAlt)
                                        ) { Text("JSON", color = ManualText) }
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "Alcançável significa que o domínio respondeu tecnicamente pela rede móvel. Isso não comprova patrocínio/zero-rating, franquia gratuita ou política de cobrança da operadora.",
                                color = ManualDim,
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
}

@Composable
private fun ManualDomainResultCard(result: ManualDomainResult) {
    val color = when (result.state) {
        ManualDomainState.REACHABLE -> ManualPass
        ManualDomainState.TCP_ONLY -> ManualWarn
        else -> ManualFail
    }
    val label = when (result.state) {
        ManualDomainState.REACHABLE -> "ALCANÇÁVEL"
        ManualDomainState.TCP_ONLY -> "TCP OK"
        ManualDomainState.DNS_ERROR -> "DNS FALHOU"
        ManualDomainState.TIMEOUT -> "TIMEOUT"
        ManualDomainState.TLS_ERROR -> "TLS FALHOU"
        ManualDomainState.ERROR -> "ERRO"
    }
    ManualCard {
        Text("${result.target.domain}:${result.target.port}", color = ManualText, fontWeight = FontWeight.Bold)
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            if (result.coreDeclared) "Também declarado no Core" else "Lista manual; não declarado no Core salvo",
            color = if (result.coreDeclared) ManualAccent else ManualDim,
            fontSize = 11.sp
        )
        if (result.resolvedIps.isNotEmpty()) Text("DNS móvel: ${result.resolvedIps.joinToString()}", color = ManualDim, fontSize = 10.sp)
        result.connectedIp?.let { Text("Conectado: $it", color = ManualDim, fontSize = 10.sp) }
        result.httpStatus?.let { Text("HTTP: $it", color = ManualDim, fontSize = 10.sp) }
        Spacer(Modifier.height(5.dp))
        Text(result.detail, color = ManualDim, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private fun parseManualTargets(raw: String): Result<List<ManualDomainTarget>> = runCatching {
    val tokens = raw.split('\n', ',', ';').map { it.trim() }.filter { it.isNotBlank() }
    require(tokens.isNotEmpty()) { "Informe pelo menos um domínio." }
    require(tokens.size <= 20) { "Use no máximo 20 domínios por execução." }

    tokens.map { original ->
        val authority = original.substringAfter("://", original)
        require(!authority.contains('*') && !authority.contains('/')) { "Wildcards, paths e CIDR não são aceitos: $original" }
        val hasScheme = original.contains("://")
        val uri = URI(if (hasScheme) original else "probe://$original")
        val rawHost = uri.host?.trim().orEmpty()
        require(rawHost.isNotBlank()) { "Domínio inválido: $original" }
        val host = IDN.toASCII(rawHost).lowercase()
        require(!host.all { it.isDigit() || it == '.' } && !host.contains(':')) { "Informe nomes de domínio, não IPs: $original" }
        require(host.contains('.')) { "Domínio incompleto: $original" }
        require(host.length <= 253 && host.split('.').all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }) { "Domínio inválido: $original" }

        val scheme = uri.scheme?.lowercase().orEmpty()
        val explicitPort = uri.port.takeIf { it in 1..65535 }
        val tls = when (scheme) {
            "http" -> false
            "https" -> true
            "probe" -> explicitPort != 80
            else -> error("Esquema não suportado em $original")
        }
        val port = explicitPort ?: if (tls) 443 else 80
        ManualDomainTarget(host, port, tls)
    }.distinctBy { "${it.domain}:${it.port}:${it.tls}" }
}

@Composable
private fun ManualCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ManualSurface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = { content() }
    )
}

private val ManualBackground = Color(0xFF120E1B)
private val ManualSurface = Color(0xFF1C1628)
private val ManualSurfaceAlt = Color(0xFF292039)
private val ManualAccent = Color(0xFF8B5CF6)
private val ManualText = Color(0xFFF5F2FA)
private val ManualDim = Color(0xFFAAA1B9)
private val ManualLine = Color(0xFF3A3049)
private val ManualPass = Color(0xFF4ADE80)
private val ManualWarn = Color(0xFFFBBF24)
private val ManualFail = Color(0xFFF87171)
