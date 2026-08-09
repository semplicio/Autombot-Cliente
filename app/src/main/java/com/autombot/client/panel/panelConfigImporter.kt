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

/**
 * Pega a resposta de GET /api/v1/configs.php e importa cada protocolo disponível
 * no manager certo. A mesma rotina também é usada quando o usuário aplica uma
 * atualização de configuração pelo Dashboard.
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
    openVpnManager: OpenVpnTunnelManager
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

    val itemVmess = response.protocols["vmess"]
    if (itemVmess != null && itemVmess.success && itemVmess.uri != null) {
        runCatching { vmessManager.addProfile(parseVmessUri(itemVmess.uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("vmess", it.message ?: "erro ao interpretar a URI") }
    } else {
        if (itemVmess != null && !itemVmess.success) avisar("vmess", itemVmess.error ?: "sem sucesso")
        runCatching { vmessManager.removeProfile(nomeBase) }
    }

    val itemVless = response.protocols["vless"]
    if (itemVless != null && itemVless.success && itemVless.uri != null) {
        runCatching { vlessManager.addProfile(parseVlessUri(itemVless.uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("vless", it.message ?: "erro ao interpretar a URI") }
    } else {
        if (itemVless != null && !itemVless.success) avisar("vless", itemVless.error ?: "sem sucesso")
        runCatching { vlessManager.removeProfile(nomeBase) }
    }

    val itemTrojan = response.protocols["trojan"]
    if (itemTrojan != null && itemTrojan.success && itemTrojan.uri != null) {
        runCatching { trojanManager.addProfile(parseTrojanUri(itemTrojan.uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("trojan", it.message ?: "erro ao interpretar a URI") }
    } else {
        if (itemTrojan != null && !itemTrojan.success) avisar("trojan", itemTrojan.error ?: "sem sucesso")
        runCatching { trojanManager.removeProfile(nomeBase) }
    }

    val itemSs = response.protocols["shadowsocks"]
    if (itemSs != null && itemSs.success && itemSs.uri != null) {
        runCatching { shadowsocksManager.addProfile(parseShadowsocksUri(itemSs.uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("shadowsocks", it.message ?: "erro ao interpretar a URI") }
    } else {
        if (itemSs != null && !itemSs.success) avisar("shadowsocks", itemSs.error ?: "sem sucesso")
        runCatching { shadowsocksManager.removeProfile(nomeBase) }
    }

    // Hysteria2 e TUIC já chegam do AutomBot Core como links completos. O parser
    // compartilhado do manager preserva TLS/SNI, Salamander, UUID, ALPN e controle
    // de congestionamento. Como os dois podem usar o mesmo nome de usuário, a
    // identidade interna é tipo+nome e um nunca sobrescreve o outro.
    fun importModern(protocolKey: String, expectedType: ModernProtocolType) {
        val item = response.protocols[protocolKey]
        val managedNames = setOf(response.usuario, nomeBase).filter { it.isNotBlank() }.toSet()
        if (item != null && item.success && !item.uri.isNullOrBlank()) {
            runCatching { modernManager.importUri(item.uri) }
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
            } else if (item != null && item.success && item.uri.isNullOrBlank()) {
                avisar(protocolKey, "o painel não devolveu a URI")
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
            val porta = if (raw.has("porta")) raw.optString("porta") else "22"
            val usuarioSsh = raw.optString("usuario").ifBlank { raw.optString("username") }.ifBlank { raw.optString("login") }
            val senhaSsh = raw.optString("senha").ifBlank { raw.optString("password") }
            if (host.isBlank() || usuarioSsh.isBlank()) {
                avisar("ssh ($nomePerfil)", "não achei host/usuário nesse perfil")
                continue
            }
            nomesValidos.add(nomeConexao)
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
                        proxyType = if (raw.optString("proxy_type") == "HTTP") ProxyType.HTTP else ProxyType.SOCKS5,
                        proxyHost = raw.optString("proxy_host"),
                        proxyPort = if (raw.isNull("proxy_port")) "" else raw.optString("proxy_port"),
                        proxyUsername = raw.optString("proxy_username"),
                        proxyPassword = raw.optString("proxy_password"),
                        usePayload = raw.optBoolean("use_payload", false),
                        payload = raw.optString("payload"),
                        useSslTls = raw.optBoolean("use_ssl_tls", false),
                        sni = raw.optString("sni"),
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

    return avisos
}
