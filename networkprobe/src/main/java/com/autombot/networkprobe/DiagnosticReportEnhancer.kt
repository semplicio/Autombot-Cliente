package com.autombot.networkprobe

import org.json.JSONArray
import org.json.JSONObject

/**
 * Enriquece os relatórios exportados pelo Network Probe com dois textos operacionais:
 * 1) plano de infraestrutura para o AutomBot Core;
 * 2) manual de configuração para o AutomBot Connect.
 *
 * As recomendações são derivadas somente do endpoint e das portas testadas. Elas não
 * inventam domínios de terceiros, não procuram zero-rating e não tratam uma porta TCP
 * aberta como confirmação de um protocolo que não teve handshake real.
 */
internal object DiagnosticReportEnhancer {
    fun enrich(rawJson: String): String {
        return try {
            val root = JSONObject(rawJson)
            if (root.optString("tool") == "AutomBot Network Probe") {
                root.put("version", "0.4.0")
                root.put("automcore_plan", buildAutomCorePlan(root))
                root.put("autombot_connect_manual", buildConnectManual(root))
            }
            root.toString(2)
        } catch (_: Exception) {
            rawJson
        }
    }

    fun readableShareText(enrichedJson: String): String {
        return try {
            val root = JSONObject(enrichedJson)
            val tool = root.optString("tool", "AutomBot Network Probe")
            val parts = mutableListOf<String>()
            parts += tool

            val corePlan = root.optString("automcore_plan")
            if (corePlan.isNotBlank()) parts += corePlan

            val connectManual = root.optString("autombot_connect_manual")
            if (connectManual.isNotBlank()) parts += connectManual

            val proxyManual = root.optString("connection_manual")
            if (proxyManual.isNotBlank()) parts += proxyManual

            if (parts.size == 1) {
                parts += "Diagnóstico exportado pelo AutomBot Network Probe."
            }
            parts.joinToString("\n\n")
        } catch (_: Exception) {
            "Diagnóstico exportado pelo AutomBot Network Probe."
        }
    }

    private fun buildAutomCorePlan(root: JSONObject): String {
        val endpoint = root.optJSONObject("endpoint") ?: JSONObject()
        val networkInfo = root.optJSONObject("network_info") ?: JSONObject()
        val host = endpoint.optString("host")
        val mainTcp = endpoint.optInt("tcp_port", 443)
        val mainUdp = endpoint.optInt("udp_port", 443)
        val wsPath = normalizePath(endpoint.optString("websocket_path", "/"))
        val carrier = if (root.isNull("carrier")) null else root.optString("carrier")
        val network = root.optString("network", "rede desconhecida")
        val score = root.optInt("score", 0)
        val tcpPorts = collectPorts(endpoint, "tcp_port", "extra_tcp_ports")
        val udpPorts = collectPorts(endpoint, "udp_port", "extra_udp_ports")
        val reachableTcp = tcpPorts.filter { resultStatus(root, "TCP $it") == "pass" }
        val confirmedUdp = udpPorts.filter { resultStatus(root, "UDP $it") == "pass" }
        val tlsStatus = resultStatus(root, "TLS/SNI $mainTcp")
        val wssStatus = resultStatus(root, "WebSocket TLS")
        val httpPorts = reachableTcp.filter { it == 80 || it == 8080 || it == 8000 || it == 8888 }
        val sshPorts = reachableTcp.filter { it == 22 || it == 109 || it == 2222 }
        val proxyPorts = reachableTcp.filter { it == 1080 || it == 3128 || it == 8080 || it == 8118 || it == 8888 }

        val out = StringBuilder()
        out.appendLine("AUTOMBOT — PLANO DE INFRAESTRUTURA AUTOMCORE")
        out.appendLine("Rede testada: ${carrier?.let { "$network · $it" } ?: network}")
        out.appendLine("Endpoint: $host")
        out.appendLine("Pontuação observada: $score%")
        networkInfo.optString("nat_hint").takeIf { it.isNotBlank() && it != "null" }?.let {
            out.appendLine("NAT: $it")
        }
        out.appendLine()
        out.appendLine("MATRIZ OBSERVADA")
        for (port in tcpPorts) {
            out.appendLine("TCP $port: ${statusLabel(resultStatus(root, "TCP $port"))}")
        }
        for (port in udpPorts) {
            out.appendLine("UDP $port: ${statusLabel(resultStatus(root, "UDP $port"))}")
        }

        out.appendLine()
        out.appendLine("CONFIGURAÇÃO RECOMENDADA NO AUTOMCORE")

        if (wssStatus == "pass") {
            out.appendLine("✅ WSS/TLS $mainTcp — CONFIRMADO")
            out.appendLine("• Manter um front door TLS/WebSocket no host $host, porta $mainTcp, path $wsPath.")
            out.appendLine("• É adequado para VLESS/VMess sobre WebSocket quando o serviço final estiver configurado para esse path.")
        } else if (resultStatus(root, "TCP $mainTcp") == "pass" && tlsStatus == "pass") {
            out.appendLine("🟡 TLS $mainTcp — CONFIRMADO; WEBSOCKET NÃO CONFIRMADO")
            out.appendLine("• A camada TLS responde. Configure/valide um path WebSocket específico antes de publicar VLESS/VMess WSS.")
        } else {
            out.appendLine("⚠ Porta TCP principal $mainTcp não confirmou WSS/TLS completo nesta rede.")
        }

        if (httpPorts.isNotEmpty()) {
            for (port in httpPorts) {
                out.appendLine("🟡 WebSocket/HTTP :$port — CANDIDATO")
                out.appendLine("• TCP $port respondeu. Você pode configurar no AutomCore um front door HTTP/WebSocket nessa porta e repetir o teste com o path real.")
            }
        }

        if (sshPorts.isNotEmpty()) {
            out.appendLine("🟡 SSH direto — CANDIDATO nas portas ${sshPorts.joinToString()}")
            out.appendLine("• A abertura TCP foi confirmada; valide banner/handshake SSH antes de considerar o protocolo operacional.")
        } else if (tcpPorts.any { it == 109 || it == 2222 }) {
            out.appendLine("⚠ SSH direto 109/2222 não foi confirmado nesta rede.")
        }

        if (proxyPorts.isNotEmpty()) {
            out.appendLine("🟡 Serviço de proxy — portas alcançáveis: ${proxyPorts.joinToString()}")
            out.appendLine("• Só use 3128/8080/1080 como proxy se houver Tinyproxy/Dante/serviço equivalente realmente configurado nessa porta.")
            out.appendLine("• Rode o Proxy Analyzer para confirmar HTTP CONNECT ou SOCKS5 antes de criar perfis de cliente.")
        }

        if (confirmedUdp.isNotEmpty()) {
            out.appendLine("✅ UDP bidirecional confirmado nas portas ${confirmedUdp.joinToString()}.")
            if (36712 in confirmedUdp) out.appendLine("• Hysteria2 36712: candidato forte; validar handshake Hysteria2 real.")
            if (44300 in confirmedUdp) out.appendLine("• TUIC 44300: candidato forte; validar handshake TUIC real.")
            if (51820 in confirmedUdp) out.appendLine("• WireGuard 51820: candidato forte; validar handshake WireGuard real.")
        } else {
            out.appendLine("⚠ UDP não teve resposta determinística. Não alterar Hysteria2/TUIC/WireGuard apenas com base em probes genéricos.")
        }

        val tcp443 = resultStatus(root, "TCP 443")
        if (tcp443 != "pass" && httpPorts.isNotEmpty()) {
            out.appendLine()
            out.appendLine("CDN / EDGE — TESTE ADICIONAL RECOMENDADO")
            out.appendLine("• O caminho direto em 443 não foi confirmado, enquanto ${httpPorts.joinToString()} respondeu em TCP.")
            out.appendLine("• Vale testar uma CDN/edge somente para HTTP(S)/WS/WSS usando domínio e origem sob seu controle.")
            out.appendLine("• A porta pública precisa ser suportada pelo provedor escolhido; a porta de origem pode ser diferente quando o provedor permitir.")
            out.appendLine("• CDN não transforma automaticamente SSH bruto ou UDP em tráfego compatível.")
        } else {
            out.appendLine()
            out.appendLine("CDN / EDGE")
            out.appendLine("• Não é obrigatória apenas por este teste. Use-a quando um teste separado mostrar melhora de rota/alcance para HTTP(S)/WS/WSS.")
        }

        out.appendLine()
        out.appendLine("ORDEM DE IMPLEMENTAÇÃO")
        out.appendLine("1. Configurar no AutomCore somente as portas candidatas que realmente serão usadas.")
        out.appendLine("2. Para WebSocket, definir um path próprio (ex.: $wsPath) e testar o handshake completo.")
        out.appendLine("3. Reexecutar o Network Probe na mesma operadora e depois no Wi-Fi.")
        out.appendLine("4. Só marcar um protocolo como CONFIRMADO depois do handshake real, não apenas porque a porta TCP abriu.")

        return out.toString().trimEnd()
    }

    private fun buildConnectManual(root: JSONObject): String {
        val endpoint = root.optJSONObject("endpoint") ?: JSONObject()
        val host = endpoint.optString("host")
        val mainTcp = endpoint.optInt("tcp_port", 443)
        val wsPath = normalizePath(endpoint.optString("websocket_path", "/"))
        val tcpPorts = collectPorts(endpoint, "tcp_port", "extra_tcp_ports")
        val reachableTcp = tcpPorts.filter { resultStatus(root, "TCP $it") == "pass" }
        val wssStatus = resultStatus(root, "WebSocket TLS")
        val tlsStatus = resultStatus(root, "TLS/SNI $mainTcp")
        val sshPorts = reachableTcp.filter { it == 22 || it == 109 || it == 2222 }
        val httpPorts = reachableTcp.filter { it == 80 || it == 8080 || it == 8000 || it == 8888 }

        val out = StringBuilder()
        out.appendLine("AUTOMBOT — MANUAL DE CONEXÃO PARA AUTOMBOT CONNECT")
        out.appendLine("Servidor testado: $host")
        out.appendLine("Path informado: $wsPath")
        out.appendLine()
        out.appendLine("LEGENDA")
        out.appendLine("✅ CONFIRMADO = handshake correspondente respondeu")
        out.appendLine("🟡 CANDIDATO = a porta respondeu, mas o protocolo final ainda precisa ser configurado/testado")
        out.appendLine("❌ NÃO RECOMENDADO = o caminho testado falhou ou expirou")

        if (wssStatus == "pass") {
            out.appendLine()
            out.appendLine("PERFIL 1 — VLESS/VMESS + WSS — ✅ CONFIRMADO")
            out.appendLine("• Servidor: $host")
            out.appendLine("• Porta: $mainTcp")
            out.appendLine("• TLS: ATIVADO")
            out.appendLine("• SNI/Server Name: $host")
            out.appendLine("• Verificação de certificado: ATIVADA")
            out.appendLine("• WebSocket: ATIVADO")
            out.appendLine("• WS Host: $host")
            out.appendLine("• WS Path: $wsPath")
            out.appendLine("• Payload personalizado: DESATIVADO quando o WebSocket nativo estiver disponível")
        } else if (resultStatus(root, "TCP $mainTcp") == "pass" && tlsStatus == "pass") {
            out.appendLine()
            out.appendLine("PERFIL TLS — 🟡 CANDIDATO")
            out.appendLine("• Servidor: $host")
            out.appendLine("• Porta: $mainTcp")
            out.appendLine("• TLS: ATIVADO")
            out.appendLine("• SNI: $host")
            out.appendLine("• WebSocket: somente depois que o AutomCore confirmar um path WS nessa porta")
        }

        var profile = 2
        for (port in httpPorts) {
            out.appendLine()
            out.appendLine("PERFIL $profile — VLESS/VMESS + WEBSOCKET :$port — 🟡 CANDIDATO")
            out.appendLine("• Servidor: $host")
            out.appendLine("• Porta: $port")
            out.appendLine("• WebSocket: ATIVAR somente após configurar o front door WS no AutomCore")
            out.appendLine("• WS Host: $host")
            out.appendLine("• WS Path: $wsPath")
            out.appendLine("• TLS: ${if (port == 443) "ATIVADO" else "DESATIVADO, salvo se você configurar TLS especificamente nessa porta"}")
            out.appendLine("• Payload personalizado: preferir DESATIVADO quando o transporte WS nativo funcionar")
            profile++
        }

        if (sshPorts.isNotEmpty()) {
            out.appendLine()
            out.appendLine("SSH DIRETO — 🟡 CANDIDATO")
            out.appendLine("• Servidor SSH: $host")
            out.appendLine("• Portas TCP alcançáveis: ${sshPorts.joinToString()}")
            out.appendLine("• Proxy: DESATIVADO")
            out.appendLine("• Payload: DESATIVADO no modo direto")
            out.appendLine("• Para SSH + HTTP Proxy, rode o Proxy Analyzer; ele só gera o CONNECT depois de confirmar o proxy.")
        }

        out.appendLine()
        out.appendLine("USO COM PAYLOAD — SOMENTE NO SEU PRÓPRIO ENDPOINT")
        if (httpPorts.isNotEmpty()) {
            val payloadPort = httpPorts.first()
            out.appendLine("Há porta HTTP candidata ($payloadPort), mas TCP aberto sozinho NÃO confirma que o servidor aceita payload HTTP/WS.")
            out.appendLine("Depois de configurar um front door HTTP/WebSocket no AutomCore, você pode validar este modelo:")
            out.appendLine()
            out.appendLine("Payload HTTP simples:")
            out.appendLine("GET $wsPath HTTP/1.1[crlf]Host: $host[crlf]Connection: Keep-Alive[crlf][crlf]")
            out.appendLine()
            out.appendLine("Modelo de upgrade WebSocket:")
            out.appendLine("GET $wsPath HTTP/1.1[crlf]Host: $host[crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]Sec-WebSocket-Version: 13[crlf]Sec-WebSocket-Key: <GERADO-DINAMICAMENTE>[crlf][crlf]")
            out.appendLine("• Se o campo de payload do cliente não consegue gerar Sec-WebSocket-Key dinamicamente, use o WebSocket nativo do app e deixe o payload personalizado desligado.")
            out.appendLine("• Host/SNI devem continuar sendo o domínio do seu próprio serviço; este relatório não gera host de fachada de terceiros.")
        } else {
            out.appendLine("Nenhuma porta HTTP candidata respondeu. Não foi gerado payload direto.")
        }

        out.appendLine()
        out.appendLine("PAYLOAD COM PROXY")
        out.appendLine("• HTTP CONNECT/SSH+Proxy não é inferido deste teste principal.")
        out.appendLine("• Abra o Proxy Analyzer, informe o proxy autorizado e o destino da sua VPS. Se HTTP CONNECT for confirmado, o manual do Proxy Analyzer gera o payload CONNECT exato para essa combinação.")

        return out.toString().trimEnd()
    }

    private fun collectPorts(endpoint: JSONObject, primaryName: String, extraName: String): List<Int> {
        val ports = LinkedHashSet<Int>()
        val primary = endpoint.optInt(primaryName, -1)
        if (primary in 1..65535) ports += primary

        val extras = endpoint.optJSONArray(extraName) ?: JSONArray()
        for (index in 0 until extras.length()) {
            val port = extras.optInt(index)
            if (port in 1..65535) ports += port
        }
        return ports.toList()
    }

    private fun resultStatus(root: JSONObject, name: String): String? {
        val results = root.optJSONArray("results") ?: return null
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            if (item.optString("name") == name) {
                return item.optString("status").lowercase()
            }
        }
        return null
    }

    private fun statusLabel(status: String?): String = when (status) {
        "pass" -> "✅ OK"
        "warn" -> "🟡 PARCIAL/INCONCLUSIVO"
        "fail" -> "❌ FALHA"
        else -> "— não testado"
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        return if (path.startsWith('/')) path else "/$path"
    }
}
