package com.autombot.networkprobe

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class CoreFullProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = CoreProfileStore(applicationContext).loadProfile()
        val engine = CoreAdvisorEngine(applicationContext)

        setContent {
            MaterialTheme {
                CoreFullProbeScreen(
                    profile = profile,
                    engine = engine,
                    onShareJson = { report -> ReportShare.share(this, report.toJson()) },
                    onShareText = { report ->
                        ReportShare.shareText(this, "AutomBot Core — diagnóstico e plano de ajuste", report.toText())
                    }
                )
            }
        }
    }
}

@Composable
private fun CoreFullProbeScreen(
    profile: CoreProfileSnapshot?,
    engine: CoreAdvisorEngine,
    onShareJson: (CoreAdvisorReport) -> Unit,
    onShareText: (CoreAdvisorReport) -> Unit
) {
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CoreAdvisorReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = FullBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                FullCard {
                    Text("AutomBot Core Network Advisor", color = FullText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Testa toda a configuração importada, repete TCP três vezes, verifica todos os IPs resolvidos, avalia portas padrão candidatas e gera um plano de ajuste da VPS.",
                        color = FullDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            if (profile == null) {
                item {
                    FullCard {
                        Text("Nenhum perfil AutomBot Core salvo neste aparelho.", color = FullFail, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Volte e faça o vínculo/sincronização antes do teste completo.", color = FullDim, fontSize = 12.sp)
                    }
                }
            } else {
                val planned = profile.protocols.fold(0) { acc, protocol ->
                    acc + protocol.ports.distinct().size +
                        (if (!protocol.originHost.isNullOrBlank() && protocol.originPort != null) 1 else 0)
                }
                item {
                    FullCard {
                        Text(profile.profileName, color = FullText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Versão: ${profile.profileVersion}", color = FullDim, fontSize = 11.sp)
                        Text("Configurações recebidas do Core: ${profile.protocols.size}", color = FullDim, fontSize = 11.sp)
                        Text("Combinações brutas no perfil: $planned", color = FullAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Duplicatas semânticas são removidas no teste. Portas de protocolos incompatíveis não são misturadas. O catálogo de portas candidatas é limitado e executado somente contra a VPS vinculada.",
                            color = FullDim,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                item {
                    Button(
                        onClick = {
                            running = true
                            error = null
                            report = null
                            scope.launch {
                                runCatching { engine.run(profile) }
                                    .onSuccess { report = it }
                                    .onFailure { error = it.message ?: it.javaClass.simpleName }
                                running = false
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FullAccent)
                    ) {
                        if (running) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.padding(horizontal = 6.dp))
                            Text("Testando protocolos e portas candidatas…")
                        } else {
                            Text("Executar diagnóstico + plano de ajuste", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            error?.let { message ->
                item { Text(message, color = FullFail, fontSize = 12.sp) }
            }

            report?.let { current ->
                item {
                    FullCard {
                        Text("Resumo do diagnóstico", color = FullText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            current.carrier?.let { "${current.networkLabel} · $it" } ?: current.networkLabel,
                            color = FullText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(7.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SummaryValue("OK", current.passed, FullPass)
                            SummaryValue("PARCIAL", current.warnings, FullWarn)
                            SummaryValue("FALHA", current.failed, FullFail)
                            SummaryValue("TOTAL", current.cases.size, FullText)
                        }
                    }
                }

                items(current.cases) { item ->
                    CoreCaseCard(item)
                }

                item {
                    FullCard {
                        Text("Portas TCP padrão candidatas", color = FullText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "Porta recusada rapidamente é tratada como caminho alcançável sem listener identificado. Porta aberta e não declarada é conflito potencial, não oportunidade automática.",
                            color = FullDim,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                items(current.candidateTcpPorts) { item ->
                    CandidatePortCard(item)
                }

                item {
                    FullCard {
                        Text("Plano de ajuste AutomBot Core", color = FullAccent, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            current.optimizationPlan,
                            color = FullText,
                            fontSize = 11.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                item {
                    Button(
                        onClick = { onShareText(current) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FullAccent)
                    ) {
                        Text("Compartilhar diagnóstico + plano", fontWeight = FontWeight.SemiBold)
                    }
                }

                item {
                    Button(
                        onClick = { onShareJson(current) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FullSurfaceAlt)
                    ) {
                        Text("Compartilhar JSON completo", color = FullText)
                    }
                }
            }

            item {
                Text(
                    "UDP continua classificado como PARCIAL quando não há resposta ao payload genérico. Mudanças de Hysteria2, TUIC, WireGuard e OpenVPN UDP só devem ser promovidas depois de um teste de protocolo real.",
                    color = FullDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun CoreCaseCard(result: CoreProbeCaseResult) {
    val color = when (result.status) {
        CoreCaseStatus.PASS -> FullPass
        CoreCaseStatus.WARN -> FullWarn
        CoreCaseStatus.FAIL -> FullFail
    }
    val label = when (result.status) {
        CoreCaseStatus.PASS -> "OK"
        CoreCaseStatus.WARN -> "PARCIAL"
        CoreCaseStatus.FAIL -> "FALHA"
    }

    FullCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.protocolType.uppercase(), color = FullText, fontWeight = FontWeight.Bold)
                Text(
                    "${result.host}:${result.port} · ${result.transport}${if (result.tls) " · TLS" else ""}",
                    color = FullDim,
                    fontSize = 11.sp
                )
                result.path?.let { Text("Path: $it", color = FullDim, fontSize = 11.sp) }
                if (result.role == "origin") {
                    Text("Endpoint de origem/CDN", color = FullAccent, fontSize = 10.sp)
                }
            }
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        result.layers.forEach { layer ->
            val layerColor = when (layer.status) {
                CoreCaseStatus.PASS -> FullPass
                CoreCaseStatus.WARN -> FullWarn
                CoreCaseStatus.FAIL -> FullFail
            }
            Text("• ${layer.name}: ${layer.detail}", color = layerColor, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun CandidatePortCard(result: CoreCandidatePortResult) {
    val color = when (result.state) {
        CandidatePortState.AVAILABLE_REACHABLE -> FullPass
        CandidatePortState.RESERVED -> FullAccent
        CandidatePortState.OPEN_UNKNOWN, CandidatePortState.UNSTABLE -> FullWarn
        CandidatePortState.UNREACHABLE -> FullFail
    }
    val label = when (result.state) {
        CandidatePortState.AVAILABLE_REACHABLE -> "CANDIDATA"
        CandidatePortState.RESERVED -> "RESERVADA"
        CandidatePortState.OPEN_UNKNOWN -> "CONFLITO?"
        CandidatePortState.UNSTABLE -> "INSTÁVEL"
        CandidatePortState.UNREACHABLE -> "SEM ALCANCE"
    }

    FullCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TCP ${result.port}", color = FullText, fontWeight = FontWeight.Bold)
                Text(result.host, color = FullDim, fontSize = 11.sp)
            }
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(result.detail, color = FullDim, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun SummaryValue(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = FullDim, fontSize = 9.sp)
    }
}

@Composable
private fun FullCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FullSurface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = { content() }
    )
}

private val FullBackground = Color(0xFF120E1B)
private val FullSurface = Color(0xFF1C1628)
private val FullSurfaceAlt = Color(0xFF292039)
private val FullAccent = Color(0xFF8B5CF6)
private val FullText = Color(0xFFF5F2FA)
private val FullDim = Color(0xFFAAA1B9)
private val FullPass = Color(0xFF4ADE80)
private val FullWarn = Color(0xFFFBBF24)
private val FullFail = Color(0xFFF87171)
