package com.autombot.client.panel

import android.content.Context
import com.autombot.client.protocols.modern.ModernProtocolManagerProvider
import com.autombot.client.protocols.modern.ModernProtocolType
import com.autombot.client.protocols.openvpn.OpenVpnTunnelManager
import com.autombot.client.protocols.openvpn.saveOpenVpnConfig
import com.autombot.client.protocols.shadowsocks.ShadowsocksTunnelManager
import com.autombot.client.protocols.shadowsocks.parseShadowsocksUri
import com.autombot.client.protocols.ssh.ProxyType
import com.autombot.client.protocols.ssh.SlowDnsResolverMode
import com.autombot.client.protocols.ssh.SshAuthMethod
import com.autombot.client.protocols.ssh.SshConnectionConfig
import com.autombot.client.protocols.ssh.SshTunnelManager
import com.autombot.client.protocols.trojan.TrojanTunnelManager
import com.autombot.client.protocols.trojan.parseTrojanUri
import com.autombot.client.protocols.vless.VlessTunnelManager
import com.autombot.client.protocols.vless.parseVlessUri
import com.autombot.client.protocols.vmess.VmessTunnelManager
import com.autombot.client.protocols.vmess.parseVmessUri
import com.autombot.client.protocols.wireguard.WireGuardManager
import com.autombot.client.util.AppLog

private data class RouteSelection(
    val uri: String?,
    val route: ProtocolRoute?,
    val connectHost: String? = null,
    val preserveExisting: Boolean = false
)

/**
 * Importa as configurações do painel e respeita a rota preferida indicada pelo Core.
 *
 * O contrato novo pode trazer várias rotas para a mesma credencial. VMess/VLESS
 * materializam cada rota pública como uma conexão separada (por exemplo, WS/80 e
 * WSS/443), permitindo escolha manual sem associar uma porta a uma operadora fixa.
 * Os demais protocolos continuam escolhendo a melhor rota validada. Painéis antigos
 * que entregam somente um campo ``uri`` permanecem compatíveis.
 */
suspend fun importPanelConfigs(
    context: Context,
    response: PanelConfigsResponse,
    wireGuardManager: WireGuardManager,
    sshManager: SshTunnelManager,
    vlessManager: VlessTunnelManager,
    vmessManager: VmessTunnelManager,
    shadowsocksManager: ShadowsocksTunnelManager,
    trojanManager: TrojanTunnelManager,
    openVpnManager: OpenVpnTunnelManager,
    managedUsername: String? = null,
    managedPassword: String? = null
): List<String> {
    val avisos = mutableListOf<String>()
    var algumaImportacao = false
    val nomeBase = response.servidor.ifBlank { response.usuario }
    val modernManager = ModernProtocolManagerProvider.get(context)

    fun avisar(protocolo: String, motivo: String) {
        val msg = "Painel: não importei \"$protocolo\" ($motivo)"
        avisos.add(msg)
        AppLog.log(msg, AppLog.Level.ERROR)
    }

    fun registrarRota(protocolo: String, route: ProtocolRoute?) {
        route?.let {
            AppLog.log(
                "Painel: $protocolo usando rota ${it.id} — ${it.host ?: "?"}:${it.port ?: "?"} " +
                    "${it.transport ?: ""}${if (it.tls) " + TLS" else ""}${it.path?.let { path -> " path=$path" } ?: ""}",
                AppLog.Level.INFO
            )
        }
    }

    fun rotasPublicasWebSocket(item: ProtocolPackage?): List<ProtocolRoute> {
        if (item == null) return emptyList()
        val rotasWebSocket = item.orderedRoutes()
            .filter { route ->
                val websocket = route.transport.equals("websocket", ignoreCase = true) ||
                    route.transport.equals("ws", ignoreCase = true)
                websocket &&
                    !route.uri.isNullOrBlank() &&
                    !route.host.isNullOrBlank() &&
                    route.port != null
            }

        // Quando o Core identifica explicitamente as rotas públicas, exibe somente
        // essas entradas. Isso mantém exatamente os cartões CDN HTTP/80 e CDN
        // TLS/443 e evita mostrar a rota técnica direta/origem como terceira opção.
        // Contratos antigos sem role=public continuam usando toda rota que não
        // seja marcada como origem.
        val publicas = rotasWebSocket.filter { route ->
            route.role.equals("public", ignoreCase = true)
        }
        val selecionadas = publicas.ifEmpty {
            rotasWebSocket.filterNot { route ->
                route.role.equals("origin", ignoreCase = true)
            }
        }

        return selecionadas
            // O mesmo endpoint pode aparecer mais de uma vez. Uma conexão por
            // combinação real evita cartões duplicados na interface.
            .distinctBy { route ->
                listOf(route.host?.lowercase(), route.port, route.tls, route.path).joinToString("|")
            }
    }

    fun nomeDaRota(nomeOriginal: String, route: ProtocolRoute): String {
        val detalhe = route.label.takeIf { it.isNotBlank() }
            ?: "${if (route.tls) "WSS" else "WS"}/${route.port ?: "?"}"
        return "$nomeOriginal · $detalhe"
    }

    fun hostDeConexao(item: ProtocolPackage, route: ProtocolRoute): String? {
        val endpoint = if (route.role.equals("sponsored", ignoreCase = true)) {
            item.sponsoredEndpoint?.endpointForDomain(route.host)
        } else {
            null
        }
        return endpoint?.bootstrapIps?.firstOrNull() ?: route.host
    }

    suspend fun selecionarRota(item: ProtocolPackage?): RouteSelection {
        if (item == null) return RouteSelection(null, null)
        val routes = item.orderedRoutes()
        if (routes.isEmpty()) return RouteSelection(item.uri, null)

        for (route in routes) {
            val websocket = route.transport.equals("websocket", ignoreCase = true) ||
                route.transport.equals("ws", ignoreCase = true)

            // Rotas TCP/UDP (por exemplo VLESS Reality) não usam upgrade WS e
            // continuam seguindo a ordem indicada pelo Core. Rotas antigas sem
            // metadados completos também preservam a compatibilidade anterior.
            if (!websocket || route.host.isNullOrBlank() || route.port == null) {
                return RouteSelection(route.uri, route)
            }

            val endpoint = if (route.role.equals("sponsored", ignoreCase = true)) {
                item.sponsoredEndpoint?.endpointForDomain(route.host)
            } else {
                null
            }
            val connectHost = SponsoredRouteValidator.selectConnectHost(route, endpoint)
            if (connectHost != null) {
                return RouteSelection(route.uri, route, connectHost = connectHost)
            }

            AppLog.log(
                "Painel: rota ${route.id} (${route.host}:${route.port}) não concluiu upgrade WebSocket; tentando próxima rota",
                AppLog.Level.ERROR
            )
        }

        // Não troca um perfil persistido potencialmente funcional quando nenhuma
        // das novas rotas anunciadas respondeu na rede atual.
        return RouteSelection(null, null, preserveExisting = true)
    }

    val itemVmess = response.protocols["vmess"]
    val rotasVmess = rotasPublicasWebSocket(itemVmess)
    if (itemVmess?.success == true) {
        AppLog.log(
            "Painel: vmess recebeu ${itemVmess.routes.size} rota(s); " +
                "${rotasVmess.size} rota(s) pública(s) WebSocket selecionada(s): " +
                rotasVmess.joinToString { "${it.id}:${it.port}" },
            AppLog.Level.INFO
        )
    }
    if (itemVmess != null && itemVmess.success && rotasVmess.isNotEmpty()) {
        val nomesBase = mutableSetOf<String>()
        val nomesImportados = mutableSetOf<String>()
        rotasVmess.forEach { rota ->
            runCatching {
                val parsed = parseVmessUri(rota.uri!!)
                val routeHost = rota.host?.takeIf { it.isNotBlank() }
                val routePath = rota.path
                    ?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith('/')) it else "/$it" }
                val connectionName = nomeDaRota(parsed.connectionName, rota)
                nomesBase += parsed.connectionName
                nomesImportados += connectionName
                vmessManager.addProfile(
                    parsed.copy(
                        connectionName = connectionName,
                        server = hostDeConexao(itemVmess, rota) ?: parsed.server,
                        port = rota.port ?: parsed.port,
                        wsPath = routePath ?: parsed.wsPath,
                        wsHost = routeHost ?: parsed.wsHost,
                        useTls = rota.tls,
                        sni = if (rota.tls) routeHost ?: parsed.sni else parsed.sni
                    )
                )
                registrarRota("vmess ($connectionName)", rota)
            }.onSuccess {
                algumaImportacao = true
            }.onFailure {
                avisar("vmess (${rota.label})", it.message ?: "erro ao interpretar a URI")
            }
        }
        vmessManager.connections.value
            .map { it.config.connectionName }
            .filter { nome ->
                nome !in nomesImportados &&
                    nomesBase.any { base -> nome == base || nome.startsWith("$base · ") }
            }
            .forEach(vmessManager::removeProfile)
    } else if (itemVmess != null && itemVmess.success && !itemVmess.uri.isNullOrBlank()) {
        runCatching { vmessManager.addProfile(parseVmessUri(itemVmess.uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("vmess", it.message ?: "erro ao interpretar a URI") }
    } else {
        if (itemVmess != null && !itemVmess.success) avisar("vmess", itemVmess.error ?: "sem sucesso")
        runCatching { vmessManager.removeProfile(nomeBase) }
    }

    val itemVless = response.protocols["vless"]
    val rotasVless = rotasPublicasWebSocket(itemVless)
    if (itemVless?.success == true) {
        AppLog.log(
            "Painel: vless recebeu ${itemVless.routes.size} rota(s); " +
                "${rotasVless.size} rota(s) pública(s) WebSocket selecionada(s): " +
                rotasVless.joinToString { "${it.id}:${it.port}" },
            AppLog.Level.INFO
        )
    }
    if (itemVless != null && itemVless.success && rotasVless.isNotEmpty()) {
        val nomesBase = mutableSetOf<String>()
        val nomesImportados = mutableSetOf<String>()
        rotasVless.forEach { rota ->
            runCatching {
                val parsed = parseVlessUri(rota.uri!!)
                val routeHost = rota.host?.takeIf { it.isNotBlank() }
                val routePath = rota.path
                    ?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith('/')) it else "/$it" }
                val connectionName = nomeDaRota(parsed.connectionName, rota)
                nomesBase += parsed.connectionName
                nomesImportados += connectionName
                vlessManager.addProfile(
                    parsed.copy(
                        connectionName = connectionName,
                        server = hostDeConexao(itemVless, rota) ?: parsed.server,
                        port = rota.port ?: parsed.port,
                        wsPath = routePath ?: parsed.wsPath,
                        wsHost = routeHost ?: parsed.wsHost,
                        useTls = rota.tls,
                        sni = if (rota.tls) {
                            routeHost ?: parsed.sni.ifBlank { parsed.wsHost }
                        } else {
                            parsed.sni
                        }
                    )
                )
                registrarRota("vless ($connectionName)", rota)
            }.onSuccess {
                algumaImportacao = true
            }.onFailure {
                avisar("vless (${rota.label})", it.message ?: "erro ao interpretar a URI")
            }
        }
        vlessManager.connections.value
            .map { it.config.connectionName }
            .filter { nome ->
                nome !in nomesImportados &&
                    nomesBase.any { base -> nome == base || nome.startsWith("$base · ") }
            }
            .forEach(vlessManager::removeProfile)
    } else if (itemVless != null && itemVless.success && !itemVless.uri.isNullOrBlank()) {
        runCatching { vlessManager.addProfile(parseVlessUri(itemVless.uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("vless", it.message ?: "erro ao interpretar a URI") }
    } else {
        if (itemVless != null && !itemVless.success) avisar("vless", itemVless.error ?: "sem sucesso")
        runCatching { vlessManager.removeProfile(nomeBase) }
    }

    val itemTrojan = response.protocols["trojan"]
    val selecaoTrojan = selecionarRota(itemTrojan)
    val uriTrojan = selecaoTrojan.uri
    val rotaTrojan = selecaoTrojan.route
    if (selecaoTrojan.preserveExisting) {
        avisar("trojan", "nenhuma rota nova validou; mantive o perfil anterior")
    } else if (itemTrojan != null && itemTrojan.success && !uriTrojan.isNullOrBlank()) {
        registrarRota("trojan", rotaTrojan)
        runCatching {
            val parsed = parseTrojanUri(uriTrojan)
            trojanManager.addProfile(
                parsed.copy(server = selecaoTrojan.connectHost ?: parsed.server)
            )
        }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("trojan", it.message ?: "erro ao interpretar a URI") }
    } else {
        if (itemTrojan != null && !itemTrojan.success) avisar("trojan", itemTrojan.error ?: "sem sucesso")
        runCatching { trojanManager.removeProfile(nomeBase) }
    }

    val itemSs = response.protocols["shadowsocks"]
    val selecaoSs = selecionarRota(itemSs)
    val uriSs = selecaoSs.uri
    val rotaSs = selecaoSs.route
    if (selecaoSs.preserveExisting) {
        avisar("shadowsocks", "nenhuma rota nova validou; mantive o perfil anterior")
    } else if (itemSs != null && itemSs.success && !uriSs.isNullOrBlank()) {
        registrarRota("shadowsocks", rotaSs)
        runCatching { shadowsocksManager.addProfile(parseShadowsocksUri(uriSs)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("shadowsocks", it.message ?: "erro ao interpretar a URI") }
    } else {
        if (itemSs != null && !itemSs.success) avisar("shadowsocks", itemSs.error ?: "sem sucesso")
        runCatching { shadowsocksManager.removeProfile(nomeBase) }
    }

    fun importModern(protocolKey: String, expectedType: ModernProtocolType) {
        val item = response.protocols[protocolKey]
        val uri = item?.effectiveUri()
        val managedNames = setOf(response.usuario, nomeBase).filter { it.isNotBlank() }.toSet()
        if (item != null && item.success && !uri.isNullOrBlank()) {
            registrarRota(protocolKey, item.selectedRoute())
            runCatching { modernManager.importUri(uri) }
                .onSuccess { parsed ->
                    if (parsed.type != expectedType) {
                        modernManager.removeProfile(parsed.type, parsed.connectionName)
                        avisar(protocolKey, "o link recebido é ${parsed.type.displayName}")
                    } else {
                        algumaImportacao = true
                    }
                }
                .onFailure { avisar(protocolKey, it.message ?: "erro ao interpretar a URI") }
        } else {
            if (item != null && !item.success) {
                avisar(protocolKey, item.error ?: "sem sucesso")
            } else if (item != null && item.success && uri.isNullOrBlank()) {
                avisar(protocolKey, "o painel não devolveu uma URI utilizável")
            }
            modernManager.removeManagedProfiles(expectedType, managedNames)
        }
    }

    importModern("hysteria2", ModernProtocolType.HYSTERIA2)
    importModern("tuic", ModernProtocolType.TUIC)

    val itemWg = response.protocols["wireguard"]
    val confWg = itemWg?.wireGuardConf ?: itemWg?.raw?.optString("config")?.takeIf { it.isNotBlank() }
    if (itemWg != null && itemWg.success && confWg != null) {
        wireGuardManager.importConfig(nomeBase, confWg)
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("wireguard", it.message ?: "erro ao interpretar o .conf") }
    } else {
        if (itemWg != null && !itemWg.success) avisar("wireguard", itemWg.error ?: "sem sucesso")
        runCatching { wireGuardManager.removeTunnel(nomeBase) }
    }

    val itemOvpn = response.protocols["openvpn"]
    val confOvpn = itemOvpn?.raw?.optString("config")?.takeIf { it.isNotBlank() }
        ?: itemOvpn?.wireGuardConf
    if (itemOvpn != null && itemOvpn.success && confOvpn != null) {
        runCatching {
            val config = saveOpenVpnConfig(context, nomeBase, confOvpn)
            openVpnManager.addProfile(config)
        }.onSuccess { algumaImportacao = true }
            .onFailure { avisar("openvpn", it.message ?: "erro ao salvar o .ovpn") }
    } else {
        if (itemOvpn != null && !itemOvpn.success) avisar("openvpn", itemOvpn.error ?: "sem sucesso")
        runCatching { openVpnManager.removeProfile(nomeBase) }
    }

    val itemSsh = response.protocols["ssh"]
    val perfisSsh = itemSsh?.raw?.optJSONArray("perfis")
    if (itemSsh != null && itemSsh.success && perfisSsh != null && perfisSsh.length() > 0) {
        val nomesValidos = mutableSetOf<String>()
        for (i in 0 until perfisSsh.length()) {
            val raw = perfisSsh.optJSONObject(i) ?: continue
            val nomePerfil = raw.optString("nome").ifBlank { "Padrão" }
            val nomeConexao = "$nomeBase - $nomePerfil"
            val host = raw.optString("host").ifBlank { raw.optString("server") }
            val porta = if (raw.has("porta")) raw.optString("porta") else "2222"
            val usuarioSsh = managedUsername.orEmpty().trim()
                .ifBlank { raw.optString("usuario") }
                .ifBlank { raw.optString("username") }
                .ifBlank { raw.optString("login") }
            val senhaSsh = managedPassword.orEmpty()
                .ifBlank { raw.optString("senha") }
                .ifBlank { raw.optString("password") }
            if (host.isBlank() || usuarioSsh.isBlank() || senhaSsh.isBlank()) {
                val ausentes = listOfNotNull(
                    "host".takeIf { host.isBlank() },
                    "usuário".takeIf { usuarioSsh.isBlank() },
                    "senha".takeIf { senhaSsh.isBlank() }
                ).joinToString("/")
                avisar("ssh ($nomePerfil)", "não achei $ausentes para a conta vinculada ao aparelho")
                continue
            }
            nomesValidos.add(nomeConexao)
            AppLog.log(
                "Painel: SSH \"$nomeConexao\" usando a conta gerenciada \"$usuarioSsh\"",
                AppLog.Level.INFO
            )
            runCatching {
                sshManager.saveProfile(
                    SshConnectionConfig(
                        connectionName = nomeConexao,
                        server = host,
                        port = porta,
                        username = usuarioSsh,
                        authMethod = SshAuthMethod.PASSWORD,
                        password = senhaSsh,
                        useProxy = raw.optBoolean("use_proxy", false),
                        proxyType = when (raw.optString("proxy_type").trim().uppercase()) {
                            "HTTP", "HTTP_CONNECT", "CONNECT" -> ProxyType.HTTP
                            "PAYLOAD_GATEWAY", "GATEWAY", "RAW_HTTP", "HTTP_PAYLOAD" -> ProxyType.PAYLOAD_GATEWAY
                            else -> ProxyType.SOCKS5
                        },
                        proxyHost = raw.optString("proxy_host"),
                        proxyPort = if (raw.isNull("proxy_port")) "" else raw.optString("proxy_port"),
                        proxyUsername = raw.optString("proxy_username"),
                        proxyPassword = raw.optString("proxy_password"),
                        usePayload = raw.optBoolean("use_payload", false),
                        payload = raw.optString("payload"),
                        useSslTls = raw.optBoolean("use_ssl_tls", false),
                        sni = raw.optString("sni"),
                        tlsCertificateSha256 = raw.optString("tls_certificate_sha256")
                            .ifBlank { raw.optString("certificate_sha256") }
                            .ifBlank { raw.optString("tls_cert_sha256") },
                        useWebSocket = raw.optBoolean("use_websocket", false),
                        wsHost = raw.optString("ws_host"),
                        wsPath = raw.optString("ws_path").ifBlank { "/" },
                        useSlowDns = raw.optBoolean("use_slow_dns", false),
                        slowDnsDomain = raw.optString("slow_dns_domain"),
                        slowDnsPubkey = raw.optString("slow_dns_pubkey"),
                        slowDnsResolverMode = when (raw.optString("slow_dns_resolver_mode")) {
                            "DOH" -> SlowDnsResolverMode.DOH
                            "DOT" -> SlowDnsResolverMode.DOT
                            else -> SlowDnsResolverMode.UDP
                        },
                        slowDnsResolver = raw.optString("slow_dns_resolver"),
                        dnsForwardingEnabled = raw.optBoolean("dns_forwarding_enabled", false),
                        dnsPrimary = raw.optString("dns_primary").ifBlank { "8.8.8.8" },
                        dnsSecondary = raw.optString("dns_secondary").ifBlank { "8.8.4.4" },
                        udpForwardEnabled = raw.optBoolean("udp_forward_enabled", false),
                        udpGatewayHost = raw.optString("udp_gateway_host").ifBlank { "127.0.0.1" },
                        udpGatewayPort = if (raw.isNull("udp_gateway_port")) "7300" else raw.optString("udp_gateway_port")
                    )
                )
            }.onSuccess { algumaImportacao = true }
                .onFailure { avisar("ssh ($nomePerfil)", it.message ?: "erro ao salvar o perfil") }
        }
        val prefixoGerenciado = "$nomeBase - "
        sshManager.connections.value
            .map { it.config.connectionName }
            .filter { it.startsWith(prefixoGerenciado) && it !in nomesValidos }
            .forEach { runCatching { sshManager.removeProfile(it) } }
    } else {
        if (itemSsh != null && !itemSsh.success) avisar("ssh", itemSsh.error ?: "sem sucesso")
        runCatching { sshManager.removeProfile(nomeBase) }
        val prefixoGerenciado = "$nomeBase - "
        sshManager.connections.value
            .map { it.config.connectionName }
            .filter { it.startsWith(prefixoGerenciado) }
            .forEach { runCatching { sshManager.removeProfile(it) } }
    }

    if (!algumaImportacao) {
        throw PanelException("Nenhum protocolo pôde ser importado. " + (avisos.firstOrNull() ?: "Verifique a config do painel."))
    }

    response.sponsoredEndpoint?.let { SponsoredDomainSync.storeManifest(context, it) }

    return avisos
}
