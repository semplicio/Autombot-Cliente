package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

enum class CandidatePortState {
    RESERVED,
    AVAILABLE_REACHABLE,
    OPEN_UNKNOWN,
    UNSTABLE,
    UNREACHABLE
}

data class CoreCandidatePortResult(
    val host: String,
    val port: Int,
    val state: CandidatePortState,
    val attempts: Int,
    val opened: Int,
    val refused: Int,
    val timedOut: Int,
    val configuredBy: List<String>,
    val detail: String
)

data class CoreAdvisorReport(
    val profileName: String,
    val profileVersion: String,
    val networkLabel: String,
    val carrier: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val cases: List<CoreProbeCaseResult>,
    val candidateTcpPorts: List<CoreCandidatePortResult>,
    val optimizationPlan: String
) {
    val passed: Int get() = cases.count { it.status == CoreCaseStatus.PASS }
    val warnings: Int get() = cases.count { it.status == CoreCaseStatus.WARN }
    val failed: Int get() = cases.count { it.status == CoreCaseStatus.FAIL }

    fun toJson(): String {
        val root = JSONObject()
            .put("tool", "AutomBot Core Network Advisor")
            .put("version", "0.9.0")
            .put("profile_name", profileName)
            .put("profile_version", profileVersion)
            .put("network", networkLabel)
            .put("carrier", carrier ?: JSONObject.NULL)
            .put("started_at_ms", startedAtMs)
            .put("finished_at_ms", finishedAtMs)
            .put(
                "summary",
                JSONObject()
                    .put("total", cases.size)
                    .put("pass", passed)
                    .put("warn", warnings)
                    .put("fail", failed)
            )

        val casesJson = JSONArray()
        cases.forEach { item ->
            val layersJson = JSONArray()
            item.layers.forEach { layer ->
                layersJson.put(
                    JSONObject()
                        .put("name", layer.name)
                        .put("status", layer.status.name.lowercase())
                        .put("detail", layer.detail)
                )
            }
            casesJson.put(
                JSONObject()
                    .put("protocol_id", item.protocolId)
                    .put("protocol_type", item.protocolType)
                    .put("role", item.role)
                    .put("host", item.host)
                    .put("port", item.port)
                    .put("transport", item.transport)
                    .put("tls", item.tls)
                    .put("path", item.path ?: JSONObject.NULL)
                    .put("status", item.status.name.lowercase())
                    .put("layers", layersJson)
            )
        }
        root.put("cases", casesJson)

        val candidateJson = JSONArray()
        candidateTcpPorts.forEach { item ->
            candidateJson.put(
                JSONObject()
                    .put("host", item.host)
                    .put("port", item.port)
                    .put("state", item.state.name.lowercase())
                    .put("attempts", item.attempts)
                    .put("opened", item.opened)
                    .put("refused", item.refused)
                    .put("timeouts", item.timedOut)
                    .put("configured_by", JSONArray(item.configuredBy))
                    .put("detail", item.detail)
            )
        }
        root.put("candidate_tcp_ports", candidateJson)
        root.put("automcore_optimization_plan", optimizationPlan)
        root.put("manual", toText())
        return root.toString(2)
    }

    fun toText(): String = buildString {
        appendLine("AUTOMBOT CORE — RELATÓRIO COMPLETO + PLANO DE AJUSTE")
        appendLine("Perfil: $profileName")
        appendLine("Versão: $profileVersion")
        appendLine("Rede: ${carrier?.let { "$networkLabel · $it" } ?: networkLabel}")
        appendLine("Combinações válidas testadas: ${cases.size}")
        appendLine("OK: $passed · PARCIAL: $warnings · FALHA: $failed")
        appendLine()

        cases.forEachIndexed { index, item ->
            val badge = when (item.status) {
                CoreCaseStatus.PASS -> "OK"
                CoreCaseStatus.WARN -> "PARCIAL"
                CoreCaseStatus.FAIL -> "FALHA"
            }
            appendLine("${index + 1}. ${item.protocolType.uppercase()} [${item.role}] — $badge")
            appendLine("   ${item.host}:${item.port} · ${item.transport}${if (item.tls) " · TLS" else ""}${item.path?.let { " · path=$it" } ?: ""}")
            item.layers.forEach { layer ->
                val symbol = when (layer.status) {
                    CoreCaseStatus.PASS -> "✓"
                    CoreCaseStatus.WARN -> "~"
                    CoreCaseStatus.FAIL -> "x"
                }
                appendLine("   $symbol ${layer.name}: ${layer.detail}")
            }
            appendLine()
        }

        appendLine("PORTAS TCP PADRÃO — CANDIDATAS")
        candidateTcpPorts.forEach { item ->
            val state = when (item.state) {
                CandidatePortState.RESERVED -> "EM USO/RESERVADA"
                CandidatePortState.AVAILABLE_REACHABLE -> "CAMINHO ACESSÍVEL / SEM LISTENER"
                CandidatePortState.OPEN_UNKNOWN -> "LISTENER NÃO IDENTIFICADO"
                CandidatePortState.UNSTABLE -> "INSTÁVEL"
                CandidatePortState.UNREACHABLE -> "SEM ALCANCE CONFIRMADO"
            }
            appendLine("TCP ${item.port}: $state — ${item.detail}")
        }
        appendLine()
        appendLine(optimizationPlan)
    }.trimEnd()
}

class CoreAdvisorEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun run(snapshot: CoreProfileSnapshot): CoreAdvisorReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val network = selectPhysicalNetwork()
            ?: return@withContext CoreAdvisorReport(
                profileName = snapshot.profileName,
                profileVersion = snapshot.profileVersion,
                networkLabel = "Sem rede física disponível",
                carrier = null,
                startedAtMs = started,
                finishedAtMs = System.currentTimeMillis(),
                cases = emptyList(),
                candidateTcpPorts = emptyList(),
                optimizationPlan = "Não foi possível analisar a configuração porque não há rede física disponível."
            )

        val caps = connectivity.getNetworkCapabilities(network)
        val networkLabel = describeNetwork(caps)
        val carrier = carrierName(caps)
        val target = selectOwnedTcpTarget(snapshot)
        val cases = buildCases(snapshot)
        val caseResults = mutableListOf<CoreProbeCaseResult>()

        for (case in cases) {
            caseResults += runCase(network, case, target)
        }

        val candidates = testCandidatePorts(network, snapshot)
        val plan = buildOptimizationPlan(snapshot, caseResults, candidates, networkLabel, carrier)

        CoreAdvisorReport(
            profileName = snapshot.profileName,
            profileVersion = snapshot.profileVersion,
            networkLabel = networkLabel,
            carrier = carrier,
            startedAtMs = started,
            finishedAtMs = System.currentTimeMillis(),
            cases = caseResults,
            candidateTcpPorts = candidates,
            optimizationPlan = plan
        )
    }

    private data class ProbeCase(
        val protocol: CoreProtocolProfile,
        val role: String,
        val host: String,
        val port: Int
    )

    private data class OwnedTarget(val host: String, val port: Int)

    private enum class TcpAttempt { OPEN, REFUSED, TIMEOUT, ERROR }

    private data class AddressTcpStats(
        val address: InetAddress,
        val opened: Int,
        val refused: Int,
        val timedOut: Int,
        val errors: Int
    )

    private data class TcpSelection(
        val layer: CoreLayerResult,
        val address: InetAddress?
    )

    private fun buildCases(snapshot: CoreProfileSnapshot): List<ProbeCase> {
        val cases = mutableListOf<ProbeCase>()
        snapshot.protocols.forEach { protocol ->
            protocol.ports.distinct().filter { it in 1..65535 }.forEach { port ->
                cases += ProbeCase(protocol, "public", protocol.host, port)
            }
            val originHost = protocol.originHost
            val originPort = protocol.originPort
            if (!originHost.isNullOrBlank() && originPort != null && originPort in 1..65535) {
                cases += ProbeCase(protocol, "origin", originHost, originPort)
            }
        }

        // Remove duplicatas semânticas, inclusive quando o Core informou o mesmo
        // HTTP proxy pelo módulo específico e pelo fallback do dashboard.
        return cases.distinctBy {
            listOf(
                it.protocol.type.lowercase(),
                it.role,
                it.host.lowercase(),
                it.port.toString(),
                it.protocol.transport.lowercase(),
                it.protocol.tls.toString(),
                it.protocol.path.orEmpty()
            ).joinToString("|")
        }
    }

    private fun selectOwnedTcpTarget(snapshot: CoreProfileSnapshot): OwnedTarget? {
        val preferred = snapshot.protocols.firstOrNull {
            it.transport.lowercase() != "udp" &&
                it.type.lowercase() !in setOf("http_proxy", "socks5") &&
                it.ports.isNotEmpty()
        } ?: return null
        return OwnedTarget(preferred.host, preferred.ports.first())
    }

    private fun runCase(network: Network, case: ProbeCase, target: OwnedTarget?): CoreProbeCaseResult {
        val protocol = case.protocol
        val layers = mutableListOf<CoreLayerResult>()
        val addresses = try {
            network.getAllByName(case.host)
                .distinctBy { it.hostAddress }
                .take(MAX_ADDRESSES_PER_HOST)
        } catch (error: Exception) {
            layers += CoreLayerResult("DNS", CoreCaseStatus.FAIL, error.message ?: error.javaClass.simpleName)
            return finish(case, layers, CoreCaseStatus.FAIL)
        }

        if (addresses.isEmpty()) {
            layers += CoreLayerResult("DNS", CoreCaseStatus.FAIL, "Nenhum endereço A/AAAA retornado")
            return finish(case, layers, CoreCaseStatus.FAIL)
        }

        layers += CoreLayerResult(
            "DNS",
            CoreCaseStatus.PASS,
            "$caseHostLabel → ${addresses.joinToString { it.hostAddress ?: "?" }}".replace("$caseHostLabel", case.host)
        )

        if (protocol.transport.equals("udp", ignoreCase = true)) {
            val udp = udpProbe(network, addresses.first(), case.port)
            layers += udp
            val status = if (udp.status == CoreCaseStatus.PASS) CoreCaseStatus.PASS else CoreCaseStatus.WARN
            return finish(case, layers, status)
        }

        val tcpSelection = repeatedTcpProbe(network, addresses, case.port)
        layers += tcpSelection.layer
        val address = tcpSelection.address
        if (address == null) {
            return finish(case, layers, CoreCaseStatus.FAIL)
        }

        when (protocol.type.lowercase()) {
            "ssh" -> layers += sshBannerProbe(network, address, case.port)
            "socks5" -> layers += socks5Probe(network, address, case.port, target)
            "http_proxy" -> layers += httpProxyProbe(network, address, case.port, target)
        }

        if (protocol.tls) {
            layers += tlsProbe(network, address, protocol.sni ?: case.host, case.port)
        }

        val isWebSocket = protocol.transport.equals("websocket", ignoreCase = true) ||
            protocol.type.equals("websocket", ignoreCase = true)
        if (isWebSocket) {
            layers += websocketProbe(
                network = network,
                address = address,
                hostHeader = case.host,
                sni = protocol.sni ?: case.host,
                port = case.port,
                path = protocol.path ?: "/",
                tls = protocol.tls
            )
        }

        val required = when {
            isWebSocket -> layers.lastOrNull { it.name.startsWith("WebSocket") }
            protocol.type.equals("ssh", ignoreCase = true) -> layers.lastOrNull { it.name == "SSH banner" }
            protocol.type.equals("socks5", ignoreCase = true) -> layers.lastOrNull { it.name == "SOCKS5" }
            protocol.type.equals("http_proxy", ignoreCase = true) -> layers.lastOrNull { it.name == "HTTP CONNECT" }
            protocol.tls -> layers.lastOrNull { it.name == "TLS/SNI" }
            else -> tcpSelection.layer
        }

        val status = when {
            required?.status == CoreCaseStatus.PASS -> CoreCaseStatus.PASS
            required?.status == CoreCaseStatus.FAIL -> CoreCaseStatus.FAIL
            required?.status == CoreCaseStatus.WARN -> CoreCaseStatus.WARN
            else -> CoreCaseStatus.WARN
        }
        return finish(case, layers, status)
    }

    private val caseHostLabel: String get() = "__HOST__"

    private fun finish(case: ProbeCase, layers: List<CoreLayerResult>, status: CoreCaseStatus) =
        CoreProbeCaseResult(
            protocolId = case.protocol.id,
            protocolType = case.protocol.type,
            role = case.role,
            host = case.host,
            port = case.port,
            transport = case.protocol.transport,
            tls = case.protocol.tls,
            path = case.protocol.path,
            status = status,
            layers = layers
        )

    private fun repeatedTcpProbe(network: Network, addresses: List<InetAddress>, port: Int): TcpSelection {
        val stats = addresses.map { address ->
            var opened = 0
            var refused = 0
            var timedOut = 0
            var errors = 0
            repeat(TCP_REPETITIONS) {
                when (singleTcpAttempt(network, address, port, TCP_TIMEOUT_MS)) {
                    TcpAttempt.OPEN -> opened++
                    TcpAttempt.REFUSED -> refused++
                    TcpAttempt.TIMEOUT -> timedOut++
                    TcpAttempt.ERROR -> errors++
                }
            }
            AddressTcpStats(address, opened, refused, timedOut, errors)
        }

        val bestOpen = stats.maxByOrNull { it.opened }
        val chosen = bestOpen?.takeIf { it.opened > 0 }?.address
        val totalRefused = stats.sumOf { it.refused }
        val totalTimeouts = stats.sumOf { it.timedOut }
        val detail = stats.joinToString(" | ") { item ->
            "${item.address.hostAddress}:${item.opened}/$TCP_REPETITIONS ok, ${item.refused} recusada, ${item.timedOut} timeout"
        }

        val status = when {
            chosen != null && bestOpen != null && bestOpen.opened >= 2 -> CoreCaseStatus.PASS
            chosen != null -> CoreCaseStatus.WARN
            totalRefused > 0 -> CoreCaseStatus.WARN
            else -> CoreCaseStatus.FAIL
        }
        val prefix = when {
            chosen != null && bestOpen != null && bestOpen.opened == TCP_REPETITIONS -> "estável"
            chosen != null -> "instável"
            totalRefused > 0 -> "caminho alcançou o host, mas o serviço recusou"
            totalTimeouts > 0 -> "sem resposta TCP"
            else -> "falha TCP"
        }
        return TcpSelection(
            CoreLayerResult("TCP 3x / todos os IPs", status, "$prefix — $detail"),
            chosen
        )
    }

    private fun singleTcpAttempt(network: Network, address: InetAddress, port: Int, timeoutMs: Int): TcpAttempt {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), timeoutMs)
            }
            TcpAttempt.OPEN
        } catch (_: SocketTimeoutException) {
            TcpAttempt.TIMEOUT
        } catch (error: ConnectException) {
            val text = error.message.orEmpty()
            if (text.contains("refused", ignoreCase = true) || text.contains("recus", ignoreCase = true)) {
                TcpAttempt.REFUSED
            } else {
                TcpAttempt.ERROR
            }
        } catch (_: Exception) {
            TcpAttempt.ERROR
        }
    }

    private fun testCandidatePorts(network: Network, snapshot: CoreProfileSnapshot): List<CoreCandidatePortResult> {
        val host = snapshot.publicIp?.takeIf { it.isNotBlank() }
            ?: snapshot.protocols.firstOrNull { it.host.isNotBlank() }?.host
            ?: return emptyList()
        val address = runCatching {
            network.getAllByName(host).toList().firstOrNull { it is Inet4Address }
                ?: network.getAllByName(host).firstOrNull()
        }.getOrNull() ?: return emptyList()

        val reserved = mutableMapOf<Int, MutableSet<String>>()
        snapshot.protocols.forEach { protocol ->
            if (!protocol.transport.equals("udp", ignoreCase = true)) {
                protocol.ports.filter { it in 1..65535 }.forEach { port ->
                    reserved.getOrPut(port) { linkedSetOf() } += protocol.type.uppercase()
                }
                protocol.originPort?.takeIf { it in 1..65535 }?.let { port ->
                    reserved.getOrPut(port) { linkedSetOf() } += "${protocol.type.uppercase()} ORIGIN"
                }
            }
        }

        return STANDARD_TCP_PORTS.map { port ->
            val configuredBy = reserved[port]?.toList().orEmpty()
            if (configuredBy.isNotEmpty()) {
                CoreCandidatePortResult(
                    host = host,
                    port = port,
                    state = CandidatePortState.RESERVED,
                    attempts = 0,
                    opened = 0,
                    refused = 0,
                    timedOut = 0,
                    configuredBy = configuredBy,
                    detail = "reservada pelo perfil: ${configuredBy.joinToString()}"
                )
            } else {
                var opened = 0
                var refused = 0
                var timedOut = 0
                var errors = 0
                repeat(CANDIDATE_REPETITIONS) {
                    when (singleTcpAttempt(network, address, port, CANDIDATE_TIMEOUT_MS)) {
                        TcpAttempt.OPEN -> opened++
                        TcpAttempt.REFUSED -> refused++
                        TcpAttempt.TIMEOUT -> timedOut++
                        TcpAttempt.ERROR -> errors++
                    }
                }

                val state = when {
                    opened >= 2 -> CandidatePortState.OPEN_UNKNOWN
                    refused >= 2 -> CandidatePortState.AVAILABLE_REACHABLE
                    timedOut >= 2 && opened == 0 && refused == 0 -> CandidatePortState.UNREACHABLE
                    else -> CandidatePortState.UNSTABLE
                }
                val detail = when (state) {
                    CandidatePortState.OPEN_UNKNOWN -> "$opened/$CANDIDATE_REPETITIONS conexões abriram; existe um listener não atribuído pelo perfil, então não reutilizar sem conferir a VPS"
                    CandidatePortState.AVAILABLE_REACHABLE -> "$refused/$CANDIDATE_REPETITIONS recusadas rapidamente; a operadora alcança a porta e não foi identificado listener pelo perfil"
                    CandidatePortState.UNREACHABLE -> "$timedOut/$CANDIDATE_REPETITIONS timeouts; não recomendar nesta rede"
                    CandidatePortState.UNSTABLE -> "resultado misto: $opened abertas, $refused recusadas, $timedOut timeouts, $errors outros erros"
                    CandidatePortState.RESERVED -> "reservada"
                }
                CoreCandidatePortResult(
                    host = host,
                    port = port,
                    state = state,
                    attempts = CANDIDATE_REPETITIONS,
                    opened = opened,
                    refused = refused,
                    timedOut = timedOut,
                    configuredBy = emptyList(),
                    detail = detail
                )
            }
        }
    }

    private fun buildOptimizationPlan(
        snapshot: CoreProfileSnapshot,
        cases: List<CoreProbeCaseResult>,
        candidates: List<CoreCandidatePortResult>,
        networkLabel: String,
        carrier: String?
    ): String = buildString {
        val networkName = carrier?.let { "$networkLabel · $it" } ?: networkLabel
        val available = candidates.filter { it.state == CandidatePortState.AVAILABLE_REACHABLE }
        val openUnknown = candidates.filter { it.state == CandidatePortState.OPEN_UNKNOWN }
        val passCases = cases.filter { it.status == CoreCaseStatus.PASS }
        val failedPublic = cases.filter { it.role == "public" && it.status == CoreCaseStatus.FAIL }
        val partialUdp = cases.filter {
            it.transport.equals("udp", ignoreCase = true) && it.status == CoreCaseStatus.WARN
        }

        appendLine("AUTOMBOT CORE — PLANO DE AJUSTE PARA $networkName")
        appendLine("Objetivo: manter a configuração atual como fallback, adicionar alternativas em portas comprovadamente alcançáveis e só remover a porta antiga depois de um novo teste completo.")
        appendLine()

        appendLine("1. CAMINHOS CONFIRMADOS")
        if (passCases.isEmpty()) {
            appendLine("Nenhum protocolo completo foi confirmado nesta execução.")
        } else {
            passCases.forEach { item ->
                appendLine("✓ ${item.protocolType.uppercase()} ${item.host}:${item.port}${item.path?.let { " path=$it" } ?: ""}")
            }
        }
        appendLine()

        appendLine("2. PORTAS PADRÃO CANDIDATAS NA VPS")
        if (available.isEmpty()) {
            appendLine("Nenhuma porta livre do catálogo AutomBot teve alcance determinístico por connection-refused nesta rede.")
        } else {
            available.forEach { item ->
                appendLine("✓ TCP ${item.port}: caminho alcançável e sem listener identificado pelo perfil (${item.refused}/${item.attempts} recusadas).")
            }
        }
        if (openUnknown.isNotEmpty()) {
            appendLine("Portas com listener não identificado e que NÃO devem ser reutilizadas sem auditoria: ${openUnknown.joinToString { it.port.toString() }}.")
        }
        appendLine()

        fun pick(preferred: List<Int>): CoreCandidatePortResult? =
            preferred.firstNotNullOfOrNull { port -> available.firstOrNull { it.port == port } }
                ?: available.firstOrNull()

        appendLine("3. MODELO DE ALTERAÇÃO NO AUTOMCORE")
        val sshFailed = failedPublic.filter { it.protocolType.equals("ssh", ignoreCase = true) }
        if (sshFailed.isNotEmpty()) {
            val alt = pick(listOf(80, 22, 2222, 8080, 443, 8443, 9443))
            val current = sshFailed.joinToString { it.port.toString() }
            if (alt != null) {
                appendLine("DROPBEAR/SSH: porta atual $current falhou. Adicionar um listener alternativo em TCP ${alt.port}; manter $current; testar banner SSH em ${alt.port}; somente depois de PASS considerar retirar a porta antiga.")
            } else {
                appendLine("DROPBEAR/SSH: porta atual $current falhou, mas ainda não existe porta alternativa livre e alcançável confirmada. Não trocar às cegas.")
            }
        }

        val wsCases = cases.filter {
            it.protocolType.equals("websocket", ignoreCase = true) ||
                it.transport.equals("websocket", ignoreCase = true)
        }
        val wsTcpButNoHandshake = wsCases.filter { item ->
            item.layers.any { it.name.startsWith("TCP") && it.status != CoreCaseStatus.FAIL } &&
                item.layers.any { it.name.startsWith("WebSocket") && it.status != CoreCaseStatus.PASS }
        }
        if (wsTcpButNoHandshake.isNotEmpty()) {
            wsTcpButNoHandshake.forEach { item ->
                appendLine("WEBSOCKET ${item.host}:${item.port}: a porta TCP responde, mas o handshake WS/WSS não foi confirmado. Antes de trocar a porta, revisar serviço, path '${item.path ?: "/"}', Host/SNI e reverse proxy.")
            }
        }

        val wsFailedAtTcp = wsCases.filter { item ->
            item.status == CoreCaseStatus.FAIL && item.layers.any { it.name.startsWith("TCP") && it.status == CoreCaseStatus.FAIL }
        }
        if (wsFailedAtTcp.isNotEmpty()) {
            val alt = pick(listOf(80, 8080, 8000, 8888, 8443, 443))
            if (alt != null) {
                appendLine("VMESS/VLESS/TROJAN/WS: existem caminhos atuais com falha TCP. Criar uma entrada/front door adicional em TCP ${alt.port}, preservando os paths configurados e sem remover os listeners atuais; depois validar WS/WSS real.")
            } else {
                appendLine("VMESS/VLESS/TROJAN/WS: há falha TCP, mas nenhuma porta livre padronizada foi confirmada para migração nesta rede.")
            }
        }

        val cdnFailed = failedPublic.filter { item ->
            item.host.contains("cloudfront", ignoreCase = true) || item.host.contains("cloudflare", ignoreCase = true)
        }
        if (cdnFailed.isNotEmpty()) {
            appendLine("CDN/EDGE: os endpoints públicos de CDN com falha foram testados contra até $MAX_ADDRESSES_PER_HOST IPs resolvidos e com $TCP_REPETITIONS tentativas TCP por IP. Não alterar somente a origem supondo que isso corrige a rota; comparar também um front door direto em porta candidata e repetir o teste.")
        }

        val proxyFailed = failedPublic.filter { it.protocolType.equals("http_proxy", ignoreCase = true) }
        if (proxyFailed.isNotEmpty()) {
            val alt = pick(listOf(3128, 8080, 80, 8888))
            if (alt != null) {
                appendLine("HTTP PROXY: adicionar listener alternativo em TCP ${alt.port} e validar HTTP CONNECT até outro endpoint próprio antes de recomendar o perfil ao AutomBot Connect.")
            }
        }

        val socksFailed = failedPublic.filter { it.protocolType.equals("socks5", ignoreCase = true) }
        if (socksFailed.isNotEmpty()) {
            val alt = pick(listOf(1080, 8080, 3128, 8888))
            if (alt != null) {
                appendLine("SOCKS5: adicionar listener alternativo em TCP ${alt.port} e repetir saudação SOCKS5 + CONNECT; não compartilhar a porta com outro daemon bruto.")
            }
        }

        val sshTlsFailed = failedPublic.filter { it.protocolType.equals("ssh_tls", ignoreCase = true) }
        if (sshTlsFailed.isNotEmpty()) {
            val alt = pick(listOf(443, 8443, 9443))
            if (alt != null) {
                appendLine("SSH-TLS: TCP ${alt.port} é candidata para um listener alternativo, mas só promover depois de handshake TLS e encaminhamento SSH confirmados.")
            }
        }

        val realityFailed = failedPublic.filter { it.protocolType.equals("vless-reality", ignoreCase = true) }
        if (realityFailed.isNotEmpty()) {
            appendLine("VLESS Reality: o teste de porta candidata mede somente alcance TCP. Qualquer mudança de porta exige novo teste do protocolo real; não reutilizar automaticamente uma recomendação de WS/HTTP.")
        }

        if (partialUdp.isNotEmpty()) {
            appendLine("UDP: ${partialUdp.joinToString { "${it.protocolType.uppercase()}:${it.port}" }} continuam inconclusivos porque o probe genérico não executa o handshake desses protocolos. Não trocar as portas UDP apenas com este resultado.")
        }
        appendLine()

        appendLine("4. REGRA DE IMPLANTAÇÃO")
        appendLine("A) adicionar a porta alternativa sem remover a atual; B) garantir que não exista conflito de listener; C) repetir este teste na mesma operadora; D) promover somente quando o handshake aplicável der PASS; E) manter ao menos um caminho de administração conhecido durante a mudança.")
        appendLine()
        appendLine("Catálogo TCP AutomBot avaliado: ${STANDARD_TCP_PORTS.joinToString()}.")
        appendLine("O relatório usa somente a VPS/endpoints vinculados ao AutomBot Core e não faz varredura ampla de portas.")
    }.trimEnd()

    private fun sshBannerProbe(network: Network, address: InetAddress, port: Int): CoreLayerResult {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val banner = reader.readLine().orEmpty().trim()
                if (banner.startsWith("SSH-")) {
                    CoreLayerResult("SSH banner", CoreCaseStatus.PASS, banner.take(120))
                } else {
                    CoreLayerResult("SSH banner", CoreCaseStatus.WARN, "TCP respondeu, mas não foi recebido banner SSH")
                }
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("SSH banner", CoreCaseStatus.WARN, "sem banner dentro de ${READ_TIMEOUT_MS} ms")
        } catch (error: Exception) {
            CoreLayerResult("SSH banner", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun tlsProbe(network: Network, address: InetAddress, sni: String, port: Int): CoreLayerResult {
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            raw = network.socketFactory.createSocket().apply {
                connect(InetSocketAddress(address, port), TLS_TIMEOUT_MS)
                soTimeout = TLS_TIMEOUT_MS
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
            val sslSocket = context.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
            ssl = sslSocket
            val params = sslSocket.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            sslSocket.sslParameters = params
            sslSocket.startHandshake()
            CoreLayerResult("TLS/SNI", CoreCaseStatus.PASS, "${sslSocket.session.protocol} válido para $sni")
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("TLS/SNI", CoreCaseStatus.WARN, "timeout no handshake TLS")
        } catch (error: SSLException) {
            CoreLayerResult("TLS/SNI", CoreCaseStatus.WARN, "handshake TLS falhou: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: Exception) {
            CoreLayerResult("TLS/SNI", CoreCaseStatus.FAIL, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
        }
    }

    private fun websocketProbe(
        network: Network,
        address: InetAddress,
        hostHeader: String,
        sni: String,
        port: Int,
        path: String,
        tls: Boolean
    ): CoreLayerResult {
        var raw: Socket? = null
        var active: Socket? = null
        return try {
            raw = network.socketFactory.createSocket().apply {
                connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
            active = if (tls) {
                val context = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
                (context.socketFactory.createSocket(raw, sni, port, true) as SSLSocket).apply {
                    val params = sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    sslParameters = params
                    startHandshake()
                    soTimeout = READ_TIMEOUT_MS
                }
            } else {
                raw
            }

            val normalizedPath = if (path.startsWith('/')) path else "/$path"
            val randomKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val wsKey = Base64.encodeToString(randomKey, Base64.NO_WRAP)
            val request = buildString {
                append("GET $normalizedPath HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Key: $wsKey\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Pragma: no-cache\r\n")
                append("User-Agent: AutomBot-Network-Probe/0.9\r\n\r\n")
            }
            active!!.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            active!!.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(active!!.getInputStream(), Charsets.US_ASCII))
            val statusLine = reader.readLine().orEmpty()
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    headers[name] = headers[name]?.let { "$it, $value" } ?: value
                }
            }

            val responseMeta = buildList {
                add("endpoint=${address.hostAddress}:$port")
                add("Host=$hostHeader")
                add("path=$normalizedPath")
                headers["server"]?.let { add("Server=$it") }
                headers["location"]?.let { add("Location=$it") }
                headers["via"]?.let { add("Via=$it") }
                headers["connection"]?.let { add("Connection=$it") }
                headers["upgrade"]?.let { add("Upgrade=$it") }
                headers["x-cache"]?.let { add("X-Cache=$it") }
            }.joinToString("; ")

            when {
                statusLine.contains(" 101 ") -> CoreLayerResult(
                    if (tls) "WebSocket WSS" else "WebSocket WS",
                    CoreCaseStatus.PASS,
                    "$statusLine; $responseMeta"
                )
                statusLine.startsWith("HTTP/") -> CoreLayerResult(
                    if (tls) "WebSocket WSS" else "WebSocket WS",
                    CoreCaseStatus.WARN,
                    "servidor HTTP respondeu, mas não fez upgrade: $statusLine; $responseMeta"
                )
                else -> CoreLayerResult(
                    if (tls) "WebSocket WSS" else "WebSocket WS",
                    CoreCaseStatus.WARN,
                    "sem resposta HTTP de upgrade; $responseMeta"
                )
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult(if (tls) "WebSocket WSS" else "WebSocket WS", CoreCaseStatus.WARN, "timeout no handshake WebSocket")
        } catch (error: Exception) {
            CoreLayerResult(if (tls) "WebSocket WSS" else "WebSocket WS", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { active?.close() }
            if (active !== raw) runCatching { raw?.close() }
        }
    }

    private fun httpProxyProbe(network: Network, address: InetAddress, port: Int, target: OwnedTarget?): CoreLayerResult {
        if (target == null) {
            return CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "proxy alcançável; não há outro endpoint TCP próprio no perfil para validar CONNECT")
        }
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val request = "CONNECT ${target.host}:${target.port} HTTP/1.1\r\nHost: ${target.host}:${target.port}\r\nProxy-Connection: Keep-Alive\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                val status = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine().orEmpty()
                when {
                    status.contains(" 200 ") -> CoreLayerResult("HTTP CONNECT", CoreCaseStatus.PASS, "CONNECT confirmado até ${target.host}:${target.port}")
                    status.contains(" 407 ") -> CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "proxy HTTP confirmado; autenticação necessária")
                    status.startsWith("HTTP/") -> CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "proxy respondeu: $status")
                    else -> CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "sem resposta HTTP reconhecível")
                }
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, "proxy alcançável, mas CONNECT expirou")
        } catch (error: Exception) {
            CoreLayerResult("HTTP CONNECT", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun socks5Probe(network: Network, address: InetAddress, port: Int, target: OwnedTarget?): CoreLayerResult {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                socket.getOutputStream().flush()
                val response = ByteArray(2)
                val count = socket.getInputStream().read(response)
                if (count != 2 || response[0].toInt() != 0x05) {
                    return@use CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "TCP respondeu, mas não houve saudação SOCKS5 válida")
                }
                when (response[1].toInt() and 0xff) {
                    0x00 -> CoreLayerResult("SOCKS5", CoreCaseStatus.PASS, "SOCKS5 confirmado sem autenticação")
                    0x02 -> CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "SOCKS5 confirmado; exige usuário/senha")
                    0xff -> CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "SOCKS5 respondeu, mas rejeitou método sem autenticação")
                    else -> CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "SOCKS5 respondeu com método 0x${(response[1].toInt() and 0xff).toString(16)}")
                }
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, "timeout na negociação SOCKS5")
        } catch (error: Exception) {
            CoreLayerResult("SOCKS5", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun udpProbe(network: Network, address: InetAddress, port: Int): CoreLayerResult {
        val payload = ByteArray(24).also { bytes ->
            val prefix = "AUTOMBOT-PROBE:".toByteArray(Charsets.US_ASCII)
            System.arraycopy(prefix, 0, bytes, 0, minOf(prefix.size, bytes.size))
            val random = ByteArray(bytes.size - minOf(prefix.size, bytes.size))
            SecureRandom().nextBytes(random)
            if (random.isNotEmpty()) System.arraycopy(random, 0, bytes, prefix.size, random.size)
        }
        return try {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = UDP_TIMEOUT_MS
                socket.send(DatagramPacket(payload, payload.size, address, port))
                val responseBuffer = ByteArray(512)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                CoreLayerResult("UDP", CoreCaseStatus.PASS, "resposta recebida de ${response.address.hostAddress}:${response.port}")
            }
        } catch (_: SocketTimeoutException) {
            CoreLayerResult("UDP", CoreCaseStatus.WARN, "datagrama enviado, sem resposta genérica em ${UDP_TIMEOUT_MS} ms; o protocolo pode ignorar payload desconhecido")
        } catch (error: Exception) {
            CoreLayerResult("UDP", CoreCaseStatus.WARN, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun selectPhysicalNetwork(): Network? {
        val candidates = LinkedHashSet<Network>()
        connectivity.activeNetwork?.let { candidates += it }
        connectivity.allNetworks.forEach { candidates += it }
        return candidates.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            Triple(
                network,
                network == connectivity.activeNetwork,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }.sortedWith(compareByDescending<Triple<Network, Boolean, Boolean>> { it.second }.thenByDescending { it.third })
            .firstOrNull()?.first
    }

    private fun describeNetwork(caps: NetworkCapabilities?): String = when {
        caps == null -> "Rede desconhecida"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Rede móvel"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Outra rede"
    }

    private fun carrierName(caps: NetworkCapabilities?): String? {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true) return null
        return runCatching {
            val telephony = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephony.networkOperatorName.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private companion object {
        const val TCP_TIMEOUT_MS = 2200
        const val TLS_TIMEOUT_MS = 4000
        const val READ_TIMEOUT_MS = 3000
        const val UDP_TIMEOUT_MS = 1800
        const val CANDIDATE_TIMEOUT_MS = 900
        const val TCP_REPETITIONS = 3
        const val CANDIDATE_REPETITIONS = 3
        const val MAX_ADDRESSES_PER_HOST = 4

        val STANDARD_TCP_PORTS = listOf(
            22, 80, 109, 443, 1080, 1194, 2222, 3128,
            8000, 8080, 8081, 8118, 8443, 8888, 9443
        )
    }
}
