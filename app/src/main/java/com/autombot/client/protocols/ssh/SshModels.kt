package com.autombot.client.protocols.ssh

/**
 * Modelo de perfil de conexao SSH.
 *
 * REDESENHO (a pedido do usuario): antes existia um unico seletor de "modo de
 * transporte" (Direto OU Proxy OU Payload+Proxy OU SSL-TLS OU WebSocket) — uma
 * combinacao fixa por vez, definida por mim. O usuario pediu o contrario: comecar so
 * com servidor+porta (a base) e cada camada (Proxy, Payload, SSL/TLS, WebSocket) ser
 * um toggle INDEPENDENTE que ele liga/desliga e combina do jeito dele — inclusive
 * combinacoes que eu nao previ, tipo Proxy + SSL + Payload todos juntos.
 *
 * A ordem de composicao das camadas no momento de conectar (ver SshTunnelManager.kt)
 * e: TCP (direto, proxy ou gateway) -> TLS (se ligado) -> payload cru (se ligado) -> handshake SSH.
 */
data class SshConnectionConfig(
    val connectionName: String,
    val server: String,
    val port: String = "22",
    val username: String,
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val password: String = "",
    // Quem autentica no servidor e a chave PRIVADA (a publica fica no servidor, em
    // authorized_keys) — correcao em relacao a uma referencia de app de terceiro que
    // tinha um campo "Chave publica" que nao funcionaria pra autenticar de verdade.
    val privateKeyPem: String = "",

    val compression: Boolean = false,
    val disableTcpDelay: Boolean = false,
    val connectionTimeoutSeconds: String = "10",

    // --- Camada: Proxy (independente) ---
    val useProxy: Boolean = false,
    val proxyType: ProxyType = ProxyType.SOCKS5,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",

    // --- Camada: Payload customizado (independente) ---
    val usePayload: Boolean = false,
    val payload: String = "",

    // --- Camada: SSL/TLS com SNI de fachada (independente) ---
    val useSslTls: Boolean = false,
    val sni: String = "",

    // --- Camada: WebSocket (independente — AINDA NAO implementada de verdade) ---
    val useWebSocket: Boolean = false,
    val wsHost: String = "",
    val wsPath: String = "/",

    // --- Camada: SlowDNS (independente, mas ESPECIAL — ver comentario em
    // SshTunnelManager.kt sobre a ordem de composicao). Tunela a conexao TCP inteira
    // disfarcada de trafego DNS comum (usando o dnstt, ferramenta do proprio Tor
    // Project) — pensada pra redes onde so DNS passa livre (planos de dados "zero-
    // rated"/sem internet mas com DNS liberado). Precisa que o servidor (VPS) tenha
    // o dnstt-server rodando, configurado com um dominio proprio + chave publica/
    // privada geradas por ele.
    val useSlowDns: Boolean = false,
    val slowDnsDomain: String = "", // dominio raiz reservado pro tunel (ex: t.exemplo.com)
    val slowDnsPubkey: String = "", // chave publica do servidor (hex), gerada por "dnstt-server -gen-key"
    val slowDnsResolverMode: SlowDnsResolverMode = SlowDnsResolverMode.UDP,
    val slowDnsResolver: String = "", // endereço do resolvedor DNS (UDP: host:53: DoH: URL completa; DoT: host:853)

    // DNS
    val dnsForwardingEnabled: Boolean = false,
    val dnsPrimary: String = "8.8.8.8",
    val dnsSecondary: String = "8.8.4.4",

    // UDP forwarding (badvpn/udpgw)
    val udpForwardEnabled: Boolean = false,
    val udpGatewayHost: String = "127.0.0.1",
    val udpGatewayPort: String = "7300"
)

enum class SshAuthMethod(val label: String) {
    PASSWORD("Senha"),
    PRIVATE_KEY("Chave privada")
}

enum class ProxyType(val label: String) {
    SOCKS5("SOCKS5"),
    HTTP("HTTP CONNECT"),
    /**
     * Gateway de entrada que não implementa HTTP CONNECT. O app abre TCP em
     * proxyHost:proxyPort e envia a camada Payload diretamente como os primeiros
     * bytes da sessão. server:port continuam sendo o destino SSH lógico.
     */
    PAYLOAD_GATEWAY("Gateway Payload")
}

enum class SlowDnsResolverMode(val label: String) {
    UDP("DNS puro (UDP)"),
    DOH("DNS sobre HTTPS (DoH)"),
    DOT("DNS sobre TLS (DoT)")
}

/** Resumo curto das camadas ativas, pra mostrar na lista de conexões e nos logs. */
fun SshConnectionConfig.describeLayers(): String {
    if (!useProxy && !usePayload && !useSslTls && !useWebSocket && !useSlowDns) return "Direto"
    val parts = mutableListOf<String>()
    if (useSlowDns) parts.add("SlowDNS")
    if (useProxy) parts.add("Proxy ${proxyType.label}")
    if (usePayload) parts.add("Payload")
    if (useSslTls) parts.add("SSL/TLS")
    if (useWebSocket) parts.add("WebSocket")
    return parts.joinToString(" + ")
}

/** Serialização simples pra persistência local (SharedPreferences) — ver SshTunnelManager.kt. */
fun SshConnectionConfig.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("connectionName", connectionName)
    put("server", server)
    put("port", port)
    put("username", username)
    put("authMethod", authMethod.name)
    put("password", password)
    put("privateKeyPem", privateKeyPem)
    put("compression", compression)
    put("disableTcpDelay", disableTcpDelay)
    put("connectionTimeoutSeconds", connectionTimeoutSeconds)
    put("useProxy", useProxy)
    put("proxyType", proxyType.name)
    put("proxyHost", proxyHost)
    put("proxyPort", proxyPort)
    put("proxyUsername", proxyUsername)
    put("proxyPassword", proxyPassword)
    put("usePayload", usePayload)
    put("payload", payload)
    put("useSslTls", useSslTls)
    put("sni", sni)
    put("useWebSocket", useWebSocket)
    put("wsHost", wsHost)
    put("wsPath", wsPath)
    put("useSlowDns", useSlowDns)
    put("slowDnsDomain", slowDnsDomain)
    put("slowDnsPubkey", slowDnsPubkey)
    put("slowDnsResolverMode", slowDnsResolverMode.name)
    put("slowDnsResolver", slowDnsResolver)
    put("dnsForwardingEnabled", dnsForwardingEnabled)
    put("dnsPrimary", dnsPrimary)
    put("dnsSecondary", dnsSecondary)
    put("udpForwardEnabled", udpForwardEnabled)
    put("udpGatewayHost", udpGatewayHost)
    put("udpGatewayPort", udpGatewayPort)
}

fun sshConnectionConfigFromJson(json: org.json.JSONObject): SshConnectionConfig = SshConnectionConfig(
    connectionName = json.optString("connectionName"),
    server = json.optString("server"),
    port = json.optString("port", "22"),
    username = json.optString("username"),
    authMethod = runCatching { SshAuthMethod.valueOf(json.optString("authMethod")) }.getOrDefault(SshAuthMethod.PASSWORD),
    password = json.optString("password"),
    privateKeyPem = json.optString("privateKeyPem"),
    compression = json.optBoolean("compression"),
    disableTcpDelay = json.optBoolean("disableTcpDelay"),
    connectionTimeoutSeconds = json.optString("connectionTimeoutSeconds", "10"),
    useProxy = json.optBoolean("useProxy"),
    proxyType = runCatching { ProxyType.valueOf(json.optString("proxyType")) }.getOrDefault(ProxyType.SOCKS5),
    proxyHost = json.optString("proxyHost"),
    proxyPort = json.optString("proxyPort"),
    proxyUsername = json.optString("proxyUsername"),
    proxyPassword = json.optString("proxyPassword"),
    usePayload = json.optBoolean("usePayload"),
    payload = json.optString("payload"),
    useSslTls = json.optBoolean("useSslTls"),
    sni = json.optString("sni"),
    useWebSocket = json.optBoolean("useWebSocket"),
    wsHost = json.optString("wsHost"),
    wsPath = json.optString("wsPath", "/"),
    useSlowDns = json.optBoolean("useSlowDns"),
    slowDnsDomain = json.optString("slowDnsDomain"),
    slowDnsPubkey = json.optString("slowDnsPubkey"),
    slowDnsResolverMode = runCatching { SlowDnsResolverMode.valueOf(json.optString("slowDnsResolverMode")) }.getOrDefault(SlowDnsResolverMode.UDP),
    slowDnsResolver = json.optString("slowDnsResolver"),
    dnsForwardingEnabled = json.optBoolean("dnsForwardingEnabled"),
    dnsPrimary = json.optString("dnsPrimary", "8.8.8.8"),
    dnsSecondary = json.optString("dnsSecondary", "8.8.4.4"),
    udpForwardEnabled = json.optBoolean("udpForwardEnabled"),
    udpGatewayHost = json.optString("udpGatewayHost", "127.0.0.1"),
    udpGatewayPort = json.optString("udpGatewayPort", "7300")
)