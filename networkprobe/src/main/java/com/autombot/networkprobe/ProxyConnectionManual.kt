package com.autombot.networkprobe

/**
 * Gera um manual de configuração somente a partir das capacidades realmente
 * observadas pelo Proxy Analyzer. Os exemplos de CONNECT usam exclusivamente
 * o destino informado pelo operador; não são gerados hosts de fachada ou
 * domínios de terceiros para contornar políticas de rede.
 */
internal fun buildProxyConnectionManual(
    config: ProxyAnalyzerConfig,
    networkLabel: String,
    results: List<ProbeResult>
): String {
    fun passed(name: String): Boolean =
        results.firstOrNull { it.name == name }?.status == ProbeStatus.PASS

    val httpConnect = passed("HTTP CONNECT")
    val httpTls = passed("TLS via HTTP CONNECT")
    val httpWss = passed("WSS via HTTP CONNECT")
    val socksConnect = passed("SOCKS5 CONNECT")
    val socksTls = passed("TLS via SOCKS5")
    val socksWss = passed("WSS via SOCKS5")
    val socksUdp = passed("SOCKS5 UDP ASSOCIATE")
    val path = if (config.webSocketPath.startsWith('/')) {
        config.webSocketPath
    } else {
        "/${config.webSocketPath}"
    }

    val out = StringBuilder()
    out.appendLine("AUTOMBOT — MANUAL DE CONEXÃO GERADO PELO TESTE")
    out.appendLine("Rede testada: $networkLabel")
    out.appendLine("Proxy: ${config.proxyHost}:${config.proxyPort}")
    out.appendLine("Destino: ${config.targetHost}:${config.targetPort}")
    out.appendLine("WebSocket path: $path")
    out.appendLine()
    out.appendLine("IMPORTANTE")
    out.appendLine("Este manual usa somente o proxy e o destino informados no teste. Métodos aparecem como confirmados apenas quando a camada correspondente respondeu.")

    if (!httpConnect && !socksConnect) {
        out.appendLine()
        out.appendLine("Nenhum túnel HTTP CONNECT ou SOCKS5 CONNECT foi confirmado. Não há uma receita de conexão por proxy segura para recomendar com este resultado.")
        return out.toString().trimEnd()
    }

    if (httpConnect) {
        out.appendLine()
        out.appendLine("1) SSH + HTTP PROXY — TRANSPORTE TCP CONFIRMADO")
        out.appendLine("Use esta receita se ${config.targetHost}:${config.targetPort} for uma porta SSH/Dropbear da sua VPS.")
        out.appendLine("No AutomBot Connect:")
        out.appendLine("• Servidor SSH: ${config.targetHost}")
        out.appendLine("• Porta SSH: ${config.targetPort}")
        out.appendLine("• Proxy HTTP: ${config.proxyHost}")
        out.appendLine("• Porta do proxy: ${config.proxyPort}")
        out.appendLine("• Usar proxy HTTP/CONNECT: ATIVADO")
        out.appendLine("• Usuário do proxy: ${config.username.ifBlank { "não usar" }}")
        out.appendLine("• Senha do proxy: ${if (config.username.isBlank()) "não usar" else "usar a senha informada no teste"}")
        out.appendLine("• TLS do SSH: DESATIVADO, a menos que a porta de destino seja especificamente SSH-over-TLS")
        out.appendLine("Payload HTTP CONNECT padrão para o seu próprio endpoint, se o módulo SSH exigir payload manual:")
        out.appendLine("CONNECT ${config.targetHost}:${config.targetPort} HTTP/1.1")
        out.appendLine("Host: ${config.targetHost}:${config.targetPort}")
        out.appendLine("Proxy-Connection: Keep-Alive")
        out.appendLine()
        out.appendLine("Se o cliente já possui suporte nativo a HTTP CONNECT, prefira o modo nativo e deixe payload personalizado desativado.")
    }

    if (httpTls) {
        out.appendLine()
        out.appendLine("2) HTTPS/TLS + HTTP PROXY — CONFIRMADO")
        out.appendLine("No AutomBot Connect:")
        out.appendLine("• Servidor: ${config.targetHost}")
        out.appendLine("• Porta: ${config.targetPort}")
        out.appendLine("• Proxy HTTP: ${config.proxyHost}:${config.proxyPort}")
        out.appendLine("• HTTP CONNECT: ATIVADO")
        out.appendLine("• TLS: ATIVADO")
        out.appendLine("• SNI/Server Name: ${config.targetHost}")
        out.appendLine("• Verificação do certificado: ATIVADA")
        out.appendLine("• Host TLS: ${config.targetHost}")
    }

    if (httpWss) {
        out.appendLine()
        out.appendLine("3) WEBSOCKET SEGURO (WSS) + HTTP PROXY — CONFIRMADO")
        out.appendLine("No AutomBot Connect:")
        out.appendLine("• Servidor: ${config.targetHost}")
        out.appendLine("• Porta: ${config.targetPort}")
        out.appendLine("• Proxy HTTP: ${config.proxyHost}:${config.proxyPort}")
        out.appendLine("• HTTP CONNECT: ATIVADO")
        out.appendLine("• TLS: ATIVADO")
        out.appendLine("• WebSocket: ATIVADO")
        out.appendLine("• WS Path: $path")
        out.appendLine("• WS Host: ${config.targetHost}")
        out.appendLine("• SNI: ${config.targetHost}")
        out.appendLine("• Payload personalizado: DESATIVADO quando o transporte WebSocket nativo estiver disponível")
        out.appendLine("• O cliente deve gerar o handshake WebSocket e Sec-WebSocket-Key dinamicamente")
    }

    if (socksConnect) {
        out.appendLine()
        out.appendLine("4) TCP/SSH VIA SOCKS5 — TRANSPORTE CONFIRMADO")
        out.appendLine("Use esta receita quando o protocolo escolhido no AutomBot aceitar upstream SOCKS5.")
        out.appendLine("• Servidor final: ${config.targetHost}")
        out.appendLine("• Porta final: ${config.targetPort}")
        out.appendLine("• Proxy SOCKS5: ${config.proxyHost}")
        out.appendLine("• Porta SOCKS5: ${config.proxyPort}")
        out.appendLine("• Usuário: ${config.username.ifBlank { "não usar" }}")
        out.appendLine("• Senha: ${if (config.username.isBlank()) "não usar" else "usar a senha informada no teste"}")
    }

    if (socksTls) {
        out.appendLine()
        out.appendLine("5) TLS/HTTPS VIA SOCKS5 — CONFIRMADO")
        out.appendLine("• Upstream SOCKS5: ${config.proxyHost}:${config.proxyPort}")
        out.appendLine("• Destino TLS: ${config.targetHost}:${config.targetPort}")
        out.appendLine("• TLS: ATIVADO")
        out.appendLine("• SNI: ${config.targetHost}")
        out.appendLine("• Verificação do certificado: ATIVADA")
    }

    if (socksWss) {
        out.appendLine()
        out.appendLine("6) WSS VIA SOCKS5 — CONFIRMADO")
        out.appendLine("• Upstream SOCKS5: ${config.proxyHost}:${config.proxyPort}")
        out.appendLine("• Destino: ${config.targetHost}:${config.targetPort}")
        out.appendLine("• TLS: ATIVADO")
        out.appendLine("• WebSocket: ATIVADO")
        out.appendLine("• WS Path: $path")
        out.appendLine("• WS Host/SNI: ${config.targetHost}")
    }

    if (socksUdp) {
        out.appendLine()
        out.appendLine("7) SOCKS5 UDP ASSOCIATE — CAPACIDADE ANUNCIADA")
        out.appendLine("O proxy aceitou UDP ASSOCIATE. Isso não significa automaticamente que Hysteria2, TUIC ou WireGuard funcionarão: o cliente precisa suportar relay SOCKS5 UDP e o tráfego fim a fim deve ser validado.")
    }

    out.appendLine()
    out.appendLine("Resumo de ativação")
    out.appendLine("• HTTP CONNECT: ${if (httpConnect) "SIM" else "NÃO"}")
    out.appendLine("• TLS pelo proxy HTTP: ${if (httpTls) "SIM" else "NÃO"}")
    out.appendLine("• WSS pelo proxy HTTP: ${if (httpWss) "SIM" else "NÃO"}")
    out.appendLine("• SOCKS5 CONNECT: ${if (socksConnect) "SIM" else "NÃO"}")
    out.appendLine("• TLS pelo SOCKS5: ${if (socksTls) "SIM" else "NÃO"}")
    out.appendLine("• WSS pelo SOCKS5: ${if (socksWss) "SIM" else "NÃO"}")
    out.appendLine("• SOCKS5 UDP ASSOCIATE: ${if (socksUdp) "SIM" else "NÃO"}")

    return out.toString().trimEnd()
}
