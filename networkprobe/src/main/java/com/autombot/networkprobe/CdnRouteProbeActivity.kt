package com.autombot.networkprobe

import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.net.IDN

class CdnRouteProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = CoreProfileStore(applicationContext).loadProfile()
        val wsProfiles = profile?.protocols.orEmpty().filter {
            it.transport.equals("websocket", ignoreCase = true) && it.host.isNotBlank()
        }
        val preferred = wsProfiles.firstOrNull { 443 in it.ports && it.tls }
            ?: wsProfiles.firstOrNull()
        val initialHost = preferred?.host ?: DEFAULT_CDN_HOST
        val initialPaths = wsProfiles
            .filter { it.host.equals(initialHost, ignoreCase = true) }
            .mapNotNull { it.path }
            .distinct()
            .ifEmpty { DEFAULT_PATHS }

        setContent {
            MaterialTheme {
                CdnRouteProbeScreen(
                    engine = remember { CdnRouteProbeEngine(applicationContext) },
                    initialHost = initialHost,
                    initialPaths = initialPaths.joinToString(","),
                    onShareText = { report ->
                        ReportShare.shareText(this, "AutomBot — CDN 80/443", report.toText())
                    },
                    onShareJson = { report -> ReportShare.share(this, report.toJson()) }
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_CDN_HOST = "mobile.autombot.com.br"
        val DEFAULT_PATHS = listOf("/vmess", "/vless", "/trojan")
    }
}

@Composable
private fun CdnRouteProbeScreen(
    engine: CdnRouteProbeEngine,
    initialHost: String,
    initialPaths: String,
    onShareText: (CdnRouteReport) -> Unit,
    onShareJson: (CdnRouteReport) -> Unit
) {
    var host by remember { mutableStateOf(initialHost) }
    var paths by remember { mutableStateOf(initialPaths) }
    var forceCellular by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<CdnRouteReport?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = CdnBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                CdnCard {
                    Text(
                        "Teste CDN — portas 80 e 443",
                        color = CdnText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Compara WS/80 sem TLS e WSS/443 com TLS/SNI no mesmo FQDN. Cada IP retornado pelo DNS é testado separadamente.",
                        color = CdnDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            item {
                CdnCard {
                    Text("Endpoint autorizado", color = CdnAccent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    CdnField(
                        value = host,
                        onChange = { host = it; error = null },
                        label = "FQDN da CDN"
                    )
                    Spacer(Modifier.height(8.dp))
                    CdnField(
                        value = paths,
                        onChange = { paths = it; error = null },
                        label = "WebSocket paths separados por vírgula"
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Forçar rede celular física", color = CdnText, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Ignora Wi-Fi e VPN para reproduzir o caminho real da operadora.",
                                color = CdnDim,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                        Switch(
                            checked = forceCellular,
                            onCheckedChange = { forceCellular = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CdnAccent,
                                checkedTrackColor = CdnAccent.copy(alpha = 0.35f)
                            )
                        )
                    }
                }
            }

            item {
                CdnCard {
                    Text("O relatório registra", color = CdnText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• DNS e todos os IPs testados\n" +
                            "• Tempo do TCP e etapa exata do timeout\n" +
                            "• TLS/SNI na porta 443\n" +
                            "• HTTP, Location, Server, Via e X-Cache\n" +
                            "• Connection, Upgrade e Sec-WebSocket-Accept\n" +
                            "• Identificação de redirect para HTTPS ou portal da operadora",
                        color = CdnDim,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            error?.let { message ->
                item { Text(message, color = CdnFail, fontSize = 12.sp) }
            }

            item {
                Button(
                    onClick = {
                        val normalizedHost = normalizeHost(host)
                        val normalizedPaths = paths.split(',', '\n')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()

                        when {
                            normalizedHost == null ->
                                error = "Informe somente o FQDN, sem http://, porta ou path."
                            normalizedPaths.isEmpty() ->
                                error = "Informe ao menos um WebSocket path, por exemplo /vmess."
                            normalizedPaths.size > 6 ->
                                error = "Use no máximo seis paths por execução."
                            else -> {
                                error = null
                                report = null
                                running = true
                                scope.launch {
                                    runCatching {
                                        engine.run(
                                            CdnRouteConfig(
                                                host = normalizedHost,
                                                paths = normalizedPaths,
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
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CdnAccent)
                ) {
                    if (running) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Testando todos os IPs…")
                    } else {
                        Text("Testar CDN HTTP/80 + TLS/443", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            report?.let { current ->
                item { CdnSummaryCard(current) }
                items(
                    items = current.routes,
                    key = { "${it.port}:${it.path}" }
                ) { route ->
                    CdnRouteCard(route)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onShareText(current) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CdnSurfaceAlt)
                        ) {
                            Text("Compartilhar TXT", color = CdnText)
                        }
                        Button(
                            onClick = { onShareJson(current) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CdnSurfaceAlt)
                        ) {
                            Text("JSON", color = CdnText)
                        }
                    }
                }
            }

            item {
                Text(
                    "Use somente FQDNs da sua infraestrutura. O teste mede conectividade técnica e não confirma política de cobrança ou acesso patrocinado da operadora.",
                    color = CdnDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun CdnSummaryCard(report: CdnRouteReport) {
    CdnCard {
        Text("Resultado", color = CdnText, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(
            report.carrier?.let { "${report.networkLabel} · $it" } ?: report.networkLabel,
            color = CdnText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(report.host, color = CdnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("DNS: ${report.resolvedIps.joinToString()}", color = CdnDim, fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CdnSummaryValue("OK", report.passed, CdnPass)
            CdnSummaryValue("REDIRECT", report.redirected, CdnWarn)
            CdnSummaryValue("OUTRAS", report.failed, CdnFail)
            CdnSummaryValue("TOTAL", report.routes.size, CdnText)
        }
    }
}

@Composable
private fun CdnSummaryValue(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = CdnDim, fontSize = 9.sp)
    }
}

@Composable
private fun CdnRouteCard(route: CdnRouteResult) {
    val stateColor = cdnStateColor(route.state)
    CdnCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${route.label} · ${route.path}", color = CdnText, fontWeight = FontWeight.Bold)
                Text(cdnStateLabel(route.state), color = stateColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("${route.host}:${route.port}", color = CdnDim, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        route.attempts.forEachIndexed { index, attempt ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Text(
                "${attempt.ip} · ${cdnStateLabel(attempt.state)} · ${attempt.totalMs}ms",
                color = cdnStateColor(attempt.state),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            attempt.connectMs?.let { Text("TCP: ${it}ms", color = CdnDim, fontSize = 10.sp) }
            attempt.statusLine?.let { Text(it, color = CdnText, fontSize = 10.sp) }
            attempt.tlsProtocol?.let { Text("TLS: $it", color = CdnDim, fontSize = 10.sp) }
            listOf(
                "location" to "Location",
                "server" to "Server",
                "via" to "Via",
                "x-cache" to "X-Cache",
                "connection" to "Connection",
                "upgrade" to "Upgrade",
                "sec-websocket-accept" to "Sec-WebSocket-Accept"
            ).forEach { (key, label) ->
                attempt.headers[key]?.let { value ->
                    Text("$label: $value", color = CdnDim, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            Text(attempt.detail, color = CdnDim, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun CdnField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = CdnText,
            unfocusedTextColor = CdnText,
            focusedBorderColor = CdnAccent,
            unfocusedBorderColor = CdnLine,
            focusedLabelColor = CdnAccent,
            unfocusedLabelColor = CdnDim,
            cursorColor = CdnAccent
        )
    )
}

@Composable
private fun CdnCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CdnSurface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
}

private fun normalizeHost(value: String): String? {
    val trimmed = value.trim().trimEnd('.')
    if (trimmed.isBlank() || trimmed.contains("://") || trimmed.contains('/') || trimmed.contains(':')) return null
    val ascii = runCatching { IDN.toASCII(trimmed).lowercase() }.getOrNull() ?: return null
    if (ascii.length > 253 || !ascii.contains('.') || ascii.split('.').any { label ->
            label.isBlank() || label.length > 63 || label.startsWith('-') || label.endsWith('-') ||
                label.any { !it.isLetterOrDigit() && it != '-' }
        }
    ) return null
    return ascii
}

private fun cdnStateColor(state: CdnRouteState): Color = when (state) {
    CdnRouteState.PASS -> CdnPass
    CdnRouteState.REDIRECT, CdnRouteState.PARTIAL -> CdnWarn
    CdnRouteState.TIMEOUT, CdnRouteState.FAIL -> CdnFail
}

private fun cdnStateLabel(state: CdnRouteState): String = when (state) {
    CdnRouteState.PASS -> "OK"
    CdnRouteState.REDIRECT -> "REDIRECT"
    CdnRouteState.PARTIAL -> "PARCIAL"
    CdnRouteState.TIMEOUT -> "TIMEOUT"
    CdnRouteState.FAIL -> "FALHA"
}

private val CdnBackground = Color(0xFF120E1B)
private val CdnSurface = Color(0xFF1C1628)
private val CdnSurfaceAlt = Color(0xFF292039)
private val CdnAccent = Color(0xFF8B5CF6)
private val CdnText = Color(0xFFF5F2FA)
private val CdnDim = Color(0xFFAAA1B9)
private val CdnLine = Color(0xFF3A3049)
private val CdnPass = Color(0xFF4ADE80)
private val CdnWarn = Color(0xFFFBBF24)
private val CdnFail = Color(0xFFF87171)
