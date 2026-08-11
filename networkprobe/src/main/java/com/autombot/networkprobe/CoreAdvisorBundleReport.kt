package com.autombot.networkprobe

import org.json.JSONObject

data class CoreAdvisorBundleReport(
    val advisor: CoreAdvisorReport,
    val sweep: AuthorizedEndpointSweepReport
) {
    val passed: Int get() = advisor.passed
    val warnings: Int get() = advisor.warnings
    val failed: Int get() = advisor.failed
    val cases: List<CoreProbeCaseResult> get() = advisor.cases
    val candidateTcpPorts: List<CoreCandidatePortResult> get() = advisor.candidateTcpPorts
    val optimizationPlan: String get() = advisor.optimizationPlan
    val networkLabel: String get() = advisor.networkLabel
    val carrier: String? get() = advisor.carrier

    fun toJson(): String {
        val root = JSONObject(advisor.toJson())
        val nextSteps = buildVpnNextSteps()
        root.put("version", "0.8.0")
        root.put("authorized_endpoint_sweep", sweep.toJsonArray())
        root.put("vpn_connection_next_steps", nextSteps)
        root.put("manual", toText())
        return root.toString(2)
    }

    fun toText(): String = buildString {
        append(advisor.toText())
        appendLine()
        appendLine()
        append(sweep.toText())
        appendLine()
        appendLine()
        append(buildVpnNextSteps())
    }.trimEnd()

    fun buildVpnNextSteps(): String = buildString {
        val networkName = carrier?.let { "$networkLabel · $it" } ?: networkLabel
        val fullPass = cases.filter { it.status == CoreCaseStatus.PASS }
        val open = sweep.openListeners
            .distinctBy { listOf(it.host.lowercase(), it.address, it.port.toString()).joinToString("|") }
        val refused = sweep.reachable
            .filter { it.state == EndpointSweepState.REFUSED }
            .distinctBy { listOf(it.host.lowercase(), it.address, it.port.toString()).joinToString("|") }
        val wsPartial = cases.filter { item ->
            item.layers.any { it.name.startsWith("WebSocket") && it.status == CoreCaseStatus.WARN }
        }

        appendLine("PRÓXIMOS PASSOS PARA CONSEGUIR UMA CONEXÃO — $networkName")
        if (fullPass.isNotEmpty()) {
            appendLine("Já existe protocolo completo confirmado. Priorizar: ${fullPass.joinToString { "${it.protocolType.uppercase()} ${it.host}:${it.port}" }}.")
        } else {
            appendLine("Nenhum protocolo completo foi confirmado ainda; portanto o relatório não garante uma configuração VPN funcional neste momento.")
        }

        if (open.isNotEmpty()) {
            appendLine("Caminhos com listener TCP acessível: ${open.take(12).joinToString { "${it.host}/${it.address}:${it.port}" }}.")
        }
        if (refused.isNotEmpty()) {
            appendLine("Caminhos que chegam ao host, mas sem listener: ${refused.take(12).joinToString { "${it.host}/${it.address}:${it.port}" }}.")
        }

        if (wsPartial.isNotEmpty()) {
            wsPartial.forEach { item ->
                val wsLayer = item.layers.firstOrNull { it.name.startsWith("WebSocket") }
                appendLine("WebSocket ${item.host}:${item.port}${item.path?.let { " $it" } ?: ""}: TCP chega ao servidor, mas o upgrade não foi confirmado${wsLayer?.detail?.let { " ($it)" } ?: ""}.")
            }
            appendLine("Nessa situação, o próximo teste útil é corrigir/confirmar o front door HTTP/WS da própria VPS e repetir o diagnóstico até obter HTTP 101 no path configurado.")
        }

        val port80 = open.filter { it.port == 80 }
        val port8080 = open.filter { it.port == 8080 }
        if (port80.isNotEmpty() || port8080.isNotEmpty()) {
            appendLine("Como 80/8080 têm caminho TCP nesta rede, vale auditar qual processo está ouvindo nessas portas antes de alterar serviços. Em Linux, use 'ss -ltnp' na VPS e confira especificamente 80 e 8080.")
            appendLine("Se um reverse proxy seu estiver em 80 ou 8080, uma opção segura é adicionar nele uma rota WebSocket para o serviço Xray interno, manter as portas antigas como fallback e testar VMess/VLESS/Trojan novamente. Não reutilizar uma porta ocupada sem saber qual daemon a possui.")
        }

        appendLine("A varredura desta versão só testa hosts/IPs presentes no perfil AutomBot Core vinculado. Ela não procura domínios de terceiros, ranges da operadora, zero-rating ou fronting externo.")
    }.trimEnd()
}
