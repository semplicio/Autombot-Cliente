package com.autombot.networkprobe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = NetworkProbeEngine(applicationContext)

        setContent {
            AutomBotProbeTheme {
                ProbeScreen(
                    engine = engine,
                    onShare = { report -> shareReport(report) }
                )
            }
        }
    }

    private fun shareReport(report: ProbeReport) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "AutomBot Network Probe")
            putExtra(Intent.EXTRA_TEXT, report.toJson())
        }
        startActivity(Intent.createChooser(intent, "Compartilhar diagnóstico"))
    }
}

@Composable
private fun AutomBotProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Accent,
            background = Background,
            surface = SurfaceColor,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        ),
        content = content
    )
}

@Composable
private fun ProbeScreen(
    engine: NetworkProbeEngine,
    onShare: (ProbeReport) -> Unit
) {
    var host by remember { mutableStateOf("core.infinitenet.net") }
    var tcpPort by remember { mutableStateOf("443") }
    var udpPort by remember { mutableStateOf("443") }
    var wsPath by remember { mutableStateOf("/") }
    var extraTcpPorts by remember { mutableStateOf("80,109,2222,8080,8443") }
    var extraUdpPorts by remember { mutableStateOf("36712,44300,51820") }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ProbeReport?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HeaderCard() }

            item {
                EndpointCard(
                    host = host,
                    onHostChange = { host = it; validationError = null },
                    tcpPort = tcpPort,
                    onTcpPortChange = { tcpPort = it.filter(Char::isDigit); validationError = null },
                    udpPort = udpPort,
                    onUdpPortChange = { udpPort = it.filter(Char::isDigit); validationError = null },
                    wsPath = wsPath,
                    onWsPathChange = { wsPath = it; validationError = null },
                    extraTcpPorts = extraTcpPorts,
                    onExtraTcpPortsChange = {
                        extraTcpPorts = sanitizePortList(it)
                        validationError = null
                    },
                    extraUdpPorts = extraUdpPorts,
                    onExtraUdpPortsChange = {
                        extraUdpPorts = sanitizePortList(it)
                        validationError = null
                    }
                )
            }

            item { ToolsCard() }

            validationError?.let { error ->
                item { Text(error, color = Fail, fontSize = 12.sp) }
            }

            item {
                Button(
                    onClick = {
                        val tcp = tcpPort.toIntOrNull()
                        val udp = udpPort.toIntOrNull()
                        val tcpExtras = parsePorts(extraTcpPorts)
                        val udpExtras = parsePorts(extraUdpPorts)

                        when {
                            host.isBlank() -> validationError = "Informe um domínio/host da sua infraestrutura."
                            tcp == null || tcp !in 1..65535 -> validationError = "Porta TCP principal inválida."
                            udp == null || udp !in 1..65535 -> validationError = "Porta UDP principal inválida."
                            tcpExtras == null -> validationError = "Lista de portas TCP extras inválida."
                            udpExtras == null -> validationError = "Lista de portas UDP extras inválida."
                            else -> {
                                validationError = null
                                running = true
                                report = null
                                scope.launch {
                                    report = engine.run(
                                        ProbeConfig(
                                            host = host.trim(),
                                            tcpPort = tcp,
                                            udpPort = udp,
                                            webSocketPath = wsPath.ifBlank { "/" },
                                            extraTcpPorts = tcpExtras,
                                            extraUdpPorts = udpExtras
                                        )
                                    )
                                    running = false
                                }
                            }
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("Executando matriz de diagnóstico…")
                    } else {
                        Text("Executar diagnóstico completo", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            report?.let { current ->
                item { NetworkSummaryCard(current) }
                item { NetworkDetailsCard(current) }

                items(current.results) { result ->
                    ResultCard(result)
                }

                item { TransportHintsCard(current.transportHints) }
                item { RecommendationCard(current.recommendation) }

                item {
                    Button(
                        onClick = { onShare(current) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt)
                    ) {
                        Text("Exportar diagnóstico JSON", color = TextPrimary)
                    }
                }
            }

            item {
                Text(
                    "O Network Probe testa somente o endpoint informado e as portas escolhidas. Ele diferencia timeout, porta recusada, TLS, WSS e resposta UDP; não procura domínios de terceiros, exceções de cobrança ou zero-rating.",
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun HeaderCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text(
            "AutomBot Network Probe",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Diagnóstico em camadas para descobrir onde a conexão quebra e quais transportes da sua infraestrutura respondem naquela rede.",
            color = TextDim,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun EndpointCard(
    host: String,
    onHostChange: (String) -> Unit,
    tcpPort: String,
    onTcpPortChange: (String) -> Unit,
    udpPort: String,
    onUdpPortChange: (String) -> Unit,
    wsPath: String,
    onWsPathChange: (String) -> Unit,
    extraTcpPorts: String,
    onExtraTcpPortsChange: (String) -> Unit,
    extraUdpPorts: String,
    onExtraUdpPortsChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Endpoint autorizado", color = TextPrimary, fontWeight = FontWeight.SemiBold)

        ProbeTextField(
            value = host,
            onValueChange = onHostChange,
            label = "Domínio / host"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProbeTextField(
                value = tcpPort,
                onValueChange = onTcpPortChange,
                label = "TCP principal",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
            ProbeTextField(
                value = udpPort,
                onValueChange = onUdpPortChange,
                label = "UDP principal",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
        }

        ProbeTextField(
            value = wsPath,
            onValueChange = onWsPathChange,
            label = "WebSocket path"
        )

        ProbeTextField(
            value = extraTcpPorts,
            onValueChange = onExtraTcpPortsChange,
            label = "Portas TCP extras (separadas por vírgula)",
            keyboardType = KeyboardType.Number
        )

        ProbeTextField(
            value = extraUdpPorts,
            onValueChange = onExtraUdpPortsChange,
            label = "Portas UDP extras (separadas por vírgula)",
            keyboardType = KeyboardType.Number
        )
    }
}

@Composable
private fun ToolsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("Ferramentas do diagnóstico", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "• Estado/validação da rede, MTU, DNS e detecção de CGNAT\n" +
                "• Resolução A/AAAA e comparação IPv4/IPv6\n" +
                "• Matriz de portas TCP com distinção entre timeout e porta recusada\n" +
                "• TLS/SNI, validade do certificado e ALPN\n" +
                "• HTTPS e WebSocket TLS\n" +
                "• Matriz UDP para Hysteria2, TUIC, WireGuard e portas personalizadas\n" +
                "• Pontuação de capacidade e candidatos de transporte",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun ProbeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Line,
            focusedLabelColor = Accent,
            unfocusedLabelColor = TextDim,
            cursorColor = Accent
        )
    )
}

@Composable
private fun NetworkSummaryCard(report: ProbeReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("Rede detectada", color = TextDim, fontSize = 11.sp)
        Text(
            buildString {
                append(report.networkLabel)
                report.carrier?.let { append(" · ").append(it) }
            },
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Capacidade observada: ${report.score}%",
            color = when {
                report.score >= 70 -> Pass
                report.score >= 40 -> Warn
                else -> Fail
            },
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        if (report.localAddresses.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                report.localAddresses.joinToString(" · "),
                color = TextDim,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun NetworkDetailsCard(report: ProbeReport) {
    val info = report.networkInfo
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("Detalhes da rede", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            buildString {
                append("Validada: ").append(if (info.validated) "sim" else "não")
                append(" · Medida: ").append(if (info.metered) "sim" else "não")
                info.mtu?.let { append(" · MTU: ").append(it) }
                append("\nIPv4: ").append(if (info.hasIpv4) "sim" else "não")
                append(" · IPv6: ").append(if (info.hasIpv6) "sim" else "não")
                info.interfaceName?.let { append(" · Interface: ").append(it) }
                if (info.dnsServers.isNotEmpty()) {
                    append("\nDNS: ").append(info.dnsServers.joinToString())
                }
                info.natHint?.let { append("\nNAT: ").append(it) }
            },
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun ResultCard(result: ProbeResult) {
    val statusColor = when (result.status) {
        ProbeStatus.PASS -> Pass
        ProbeStatus.WARN -> Warn
        ProbeStatus.FAIL -> Fail
    }
    val statusLabel = when (result.status) {
        ProbeStatus.PASS -> "OK"
        ProbeStatus.WARN -> "PARCIAL"
        ProbeStatus.FAIL -> "FALHA"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(16.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .background(statusColor, CircleShape)
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(result.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(result.detail, color = TextDim, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun TransportHintsCard(hints: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("Transportes candidatos", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        hints.forEach { hint ->
            Text("• $hint", color = TextDim, fontSize = 11.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RecommendationCard(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("Diagnóstico", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(text, color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

private fun sanitizePortList(value: String): String =
    value.filter { it.isDigit() || it == ',' || it == ';' || it.isWhitespace() }

private fun parsePorts(value: String): List<Int>? {
    if (value.isBlank()) return emptyList()
    val tokens = value.split(',', ';', ' ', '\n', '\t').filter { it.isNotBlank() }
    val ports = mutableListOf<Int>()
    for (token in tokens) {
        val port = token.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        if (port !in ports) ports += port
    }
    return ports.take(8)
}

private val Background = Color(0xFF120E1B)
private val SurfaceColor = Color(0xFF1C1628)
private val SurfaceAlt = Color(0xFF292039)
private val Accent = Color(0xFF8B5CF6)
private val TextPrimary = Color(0xFFF5F2FA)
private val TextDim = Color(0xFFAAA1B9)
private val Line = Color(0xFF3A3049)
private val Pass = Color(0xFF4ADE80)
private val Warn = Color(0xFFFBBF24)
private val Fail = Color(0xFFF87171)
