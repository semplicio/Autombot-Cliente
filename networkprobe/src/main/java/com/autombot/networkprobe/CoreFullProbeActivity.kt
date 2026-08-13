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
        val advisorEngine = CoreAdvisorEngine(applicationContext)
        val sweepEngine = AuthorizedEndpointSweepEngine(applicationContext)
        val sponsoredEngine = SponsoredDomainReachabilityProbe(applicationContext)

        setContent {
            MaterialTheme {
                CoreFullProbeScreen(
                    profile = profile,
                    advisorEngine = advisorEngine,
                    sweepEngine = sweepEngine,
                    sponsoredEngine = sponsoredEngine,
                    onShareBundle = { report -> ReportShare.share(this, report.toJson()) },
                    onShareText = { report ->
                        ReportShare.shareText(this, "AutomBot Core — diagnóstico, varredura e domínio patrocinado", report.toText())
                    }
                )
            }
        }
    }
}

@Composable
private fun CoreFullProbeScreen(
    profile: CoreProfileSnapshot?,
    advisorEngine: CoreAdvisorEngine,
    sweepEngine: AuthorizedEndpointSweepEngine,
    sponsoredEngine: SponsoredDomainReachabilityProbe,
    onShareBundle: (CoreAdvisorBundleReport) -> Unit,
    onShareText: (CoreAdvisorBundleReport) -> Unit
) {
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CoreAdvisorBundleReport?>(null) }
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
                        "Testa a configuração importada, repete TCP, verifica IPs resolvidos, avalia portas padrão e valida o domínio patrocinado configurado pelo Core diretamente pela rede celular.",
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
                        profile.sponsoredManifest?.takeIf { it.enabled }?.active?.let { endpoint ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Domínio patrocinado salvo: ${endpoint.domain}:${endpoint.tcpPort}",
                                color = FullAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "A varredura adicional é restrita aos IPs/domínios existentes neste perfil e ao domínio patrocinado declarado pelo próprio Core. Não enumera domínios da operadora, sub-redes ou hosts de terceiros.",
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
                                runCatching {
                                    val advisor = advisorEngine.run(profile)
                                    val sweep = sweepEngine.run(profile)
                                    val sponsored = sponsoredEngine.run(profile.sponsoredManifest)
                                    CoreAdvisorBundleReport(advisor, sweep, sponsored)
                                }.onSuccess { report = it }
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
                            Text("Testando configuração e rede móvel…")
                        } else {
                            Text("Executar diagnóstico + domínio patrocinado", fontWeight = FontWeight.SemiBold)
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

                current.sponsored?.let { sponsored ->
                    item {
                        FullCard {
                            Text("Domínio patrocinado — rede celular", color = FullAccent, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (sponsored.cellularNetworkFound) {
                                    "O teste foi preso a uma interface celular do Android. A confirmação abaixo significa alcance técnico do endpoint configurado no Core, não confirmação de cobrança zero pela operadora."
                                } else {
                                    "Nenhuma interface celular com Internet foi encontrada. Ative os dados móveis e execute novamente."
                                },
                                color = FullDim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    if (sponsored.items.isEmpty()) {
                        item {
                            FullCard {
                                Text(
                                    "Nenhum manifesto de domínio patrocinado está salvo. Sincronize novamente com o AutomBot Core antes de trocar para 4G/5G.",
                                    color = FullWarn,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    } else {
                        items(sponsored.items) { item -> SponsoredDomainCard(item) }
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
                            "Porta recusada rapidamente indica caminho até a VPS sem listener. Porta aberta e não declarada indica listener desconhecido e deve ser auditada antes de reutilização.",
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
                        Text("Varredura autorizada de IPs/domínios", color = FullAccent, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Mostra somente caminhos TCP acessíveis entre os endpoints já cadastrados no AutomBot Core. Um resultado OPEN confirma o listener; REFUSED confirma que a rede chegou ao host, mas não havia listener.",
                            color = FullDim,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                val reachableSweep = current.sweep.reachable
                    .distinctBy { listOf(it.host.lowercase(), it.address, it.port.toString()).joinToString("|") }
                if (reachableSweep.isEmpty()) {
                    item {
                        FullCard {
                            Text("Nenhum caminho TCP adicional foi confirmado pela varredura.", color = FullWarn, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(reachableSweep) { item ->
                        EndpointSweepCard(item)
                    }
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
                    FullCard {
                        Text("Próximos passos para a VPN", color = FullAccent, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            current.buildVpnNextSteps(),
                            color = FullText,
                            fontSize = 11.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                item {
                    Button(
                        onClick = { onShareBundle(current) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FullAccent)
                    ) {
                        Text("Compartilhar TXT + JSON completos", fontWeight = FontWeight.SemiBold)
                    }
                }

                item {
                    Button(
                        onClick = { onShareText(current) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FullSurfaceAlt)
                    ) {
                        Text("Compartilhar somente texto", color = FullText)
                    }
                }
            }

            item {
                Text(
                    "O teste patrocinado não procura outros domínios, listas privadas da operadora ou zero-rating. Ele valida apenas o FQDN ativo/anterior publicado pelo seu AutomBot Core e informa se esse endpoint responde pela rede celular.",
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
private fun SponsoredDomainCard(result: SponsoredDomainProbeItem) {
    val color = when (result.state) {
        SponsoredDomainProbeState.CONFIRMED -> FullPass
        SponsoredDomainProbeState.REACHABLE, SponsoredDomainProbeState.MANIFEST_MISMATCH -> FullWarn
        SponsoredDomainProbeState.TIMEOUT, SponsoredDomainProbeState.TLS_ERROR, SponsoredDomainProbeState.ERROR -> FullFail
    }
    val label = when (result.state) {
        SponsoredDomainProbeState.CONFIRMED -> "ENDPOINT OK"
        SponsoredDomainProbeState.REACHABLE -> "ALCANÇÁVEL"
        SponsoredDomainProbeState.MANIFEST_MISMATCH -> "DIVERGENTE"
        SponsoredDomainProbeState.TIMEOUT -> "TIMEOUT"
        SponsoredDomainProbeState.TLS_ERROR -> "TLS FALHOU"
        SponsoredDomainProbeState.ERROR -> "ERRO"
    }

    FullCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${if (result.endpoint.active) "ATIVO" else "ANTERIOR"} · ${result.endpoint.domain}:${result.endpoint.tcpPort}",
                    color = FullText,
                    fontWeight = FontWeight.Bold
                )
                if (result.resolvedIps.isNotEmpty()) {
                    Text("DNS 4G/5G: ${result.resolvedIps.joinToString()}", color = FullDim, fontSize = 10.sp)
                }
                if (result.endpoint.bootstrapIps.isNotEmpty()) {
                    Text("Bootstrap: ${result.endpoint.bootstrapIps.joinToString()}", color = FullDim, fontSize = 10.sp)
                }
                result.connectedIp?.let { Text("Conectado em: $it", color = FullAccent, fontSize = 10.sp) }
            }
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(7.dp))
        Text(result.detail, color = color, fontSize = 11.sp, lineHeight = 16.sp)
        result.remoteRevision?.let { Text("Manifesto remoto: $it", color = FullDim, fontSize = 10.sp) }
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
private fun EndpointSweepCard(result: AuthorizedEndpointSweepItem) {
    val color = when (result.state) {
        EndpointSweepState.OPEN -> FullPass
        EndpointSweepState.REFUSED -> FullWarn
        EndpointSweepState.TIMEOUT, EndpointSweepState.ERROR -> FullFail
    }
    val label = when (result.state) {
        EndpointSweepState.OPEN -> "LISTENER OK"
        EndpointSweepState.REFUSED -> "HOST ALCANÇÁVEL"
        EndpointSweepState.TIMEOUT -> "TIMEOUT"
        EndpointSweepState.ERROR -> "ERRO"
    }

    FullCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${result.host}:${result.port}", color = FullText, fontWeight = FontWeight.Bold)
                Text("IP ${result.address}", color = FullDim, fontSize = 11.sp)
                Text(result.sources.joinToString(), color = FullDim, fontSize = 10.sp)
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
