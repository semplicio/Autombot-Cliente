package com.autombot.client.panel

import android.content.Context
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
 * Pega a resposta de GET /api/v1/configs.php (já parseada em [PanelConfigsResponse])
 * e importa cada protocolo disponível no manager certo — reaproveitando os MESMOS
 * parsers que a importação manual já usa (parseVmessUri, parseVlessUri, etc), então
 * qualquer melhoria feita ali vale pra cá também sem duplicar código.
 *
 * CORRECAO: essa função também é chamada de novo toda vez que o usuário toca em
 * "Buscar" no banner de atualização do Dashboard (ver MainShell.applyConfigUpdate())
 * — não só na criação da conta. Antes, um protocolo que sumia da resposta do painel
 * (ex: admin excluiu a config SSH em "SSH pro App") ficava esquecido no app pra
 * sempre, porque a função só sabia ADICIONAR/ATUALIZAR, nunca remover. Agora, pra
 * cada protocolo: se veio com sucesso, importa/atualiza; se não veio ou veio sem
 * sucesso, REMOVE qualquer perfil existente com esse nome — o app fica sempre
 * batendo com o que o painel diz que existe agora, pra mais ou pra menos.
 *
 * Não lança exceção por causa de UM protocolo que falhar — cada falha vira um aviso
 * (devolvido na lista + logado no AppLog) e os outros continuam sendo importados
 * normalmente. Só lança [PanelException] se NENHUM protocolo foi importado com
 * sucesso (aí sim é um problema real que o usuário precisa saber).
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

    fun avisar(protocolo: String, motivo: String) {
        val msg = "Painel: não importei \"$protocolo\" ($motivo)"
        avisos.add(msg)
        AppLog.log(msg, AppLog.Level.ERROR)
    }

    // vmess / vless / trojan / shadowsocks — mesmo padrão (URI única), cada um
    // explícito abaixo (import/atualiza se success+uri, remove caso contrário).

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
    if (itemSsh != null && itemSsh.success && itemSsh.raw != null) {
        val raw = itemSsh.raw
        // CORRECAO: formato confirmado contra resposta real do painel — não é
        // mais chute. host/porta/usuario/senha continuam os mesmos nomes de
        // antes (já bateram certo); adicionado o resto das camadas
        // (Proxy/Payload/SSL-TLS/WebSocket/SlowDNS/Gateway UDP), que o painel
        // agora também devolve quando configuradas em "SSH pro App".
        val host = raw.optString("host").ifBlank { raw.optString("server") }
        val porta = if (raw.has("porta")) raw.optString("porta") else "22"
        val usuarioSsh = raw.optString("usuario").ifBlank { raw.optString("username") }.ifBlank { raw.optString("login") }
        val senhaSsh = raw.optString("senha").ifBlank { raw.optString("password") }
        if (host.isBlank() || usuarioSsh.isBlank()) {
            avisar("ssh", "não achei host/usuário na resposta do painel")
            runCatching { sshManager.removeProfile(nomeBase) }
        } else {
            runCatching {
                sshManager.saveProfile(
                    SshConnectionConfig(
                        connectionName = nomeBase,
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
                .onFailure { avisar("ssh", it.message ?: "erro ao salvar o perfil") }
        }
    } else {
        if (itemSsh != null && !itemSsh.success) avisar("ssh", itemSsh.error ?: "sem sucesso")
        runCatching { sshManager.removeProfile(nomeBase) }
    }

    if (!algumaImportacao) {
        throw PanelException("Nenhum protocolo pôde ser importado. " + (avisos.firstOrNull() ?: "Verifique a config do painel."))
    }

    return avisos
}
