package com.autombot.client.panel

import android.content.Context
import com.autombot.client.protocols.openvpn.OpenVpnTunnelManager
import com.autombot.client.protocols.openvpn.saveOpenVpnConfig
import com.autombot.client.protocols.shadowsocks.ShadowsocksTunnelManager
import com.autombot.client.protocols.shadowsocks.parseShadowsocksUri
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
 * Não lança exceção por causa de UM protocolo que falhar — cada falha vira um aviso
 * (devolvido na lista + logado no AppLog) e os outros continuam sendo importados
 * normalmente. Só lança [PanelException] se NENHUM protocolo foi importado com
 * sucesso (aí sim é um problema real que o usuário precisa saber).
 *
 * Ressalva importante (ver também o comentário no topo de api/v1/configs.php do
 * painel): o formato exato de "ssh" e "openvpn" ainda não foi confirmado contra uma
 * resposta real do automcore — o parsing aqui é uma melhor tentativa (várias
 * variações de nome de campo), não uma certeza. Se vier errado, vai aparecer como
 * aviso em vez de importar campo errado silenciosamente.
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

    response.protocols["vmess"]?.let { item ->
        if (!item.success) { avisar("vmess", item.error ?: "sem sucesso"); return@let }
        val uri = item.uri
        if (uri == null) { avisar("vmess", "resposta sem campo \"uri\""); return@let }
        runCatching { vmessManager.addProfile(parseVmessUri(uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("vmess", it.message ?: "erro ao interpretar a URI") }
    }

    response.protocols["vless"]?.let { item ->
        if (!item.success) { avisar("vless", item.error ?: "sem sucesso"); return@let }
        val uri = item.uri
        if (uri == null) { avisar("vless", "resposta sem campo \"uri\""); return@let }
        runCatching { vlessManager.addProfile(parseVlessUri(uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("vless", it.message ?: "erro ao interpretar a URI") }
    }

    response.protocols["trojan"]?.let { item ->
        if (!item.success) { avisar("trojan", item.error ?: "sem sucesso"); return@let }
        val uri = item.uri
        if (uri == null) { avisar("trojan", "resposta sem campo \"uri\""); return@let }
        runCatching { trojanManager.addProfile(parseTrojanUri(uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("trojan", it.message ?: "erro ao interpretar a URI") }
    }

    response.protocols["shadowsocks"]?.let { item ->
        if (!item.success) { avisar("shadowsocks", item.error ?: "sem sucesso"); return@let }
        val uri = item.uri
        if (uri == null) { avisar("shadowsocks", "resposta sem campo \"uri\""); return@let }
        runCatching { shadowsocksManager.addProfile(parseShadowsocksUri(uri)) }
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("shadowsocks", it.message ?: "erro ao interpretar a URI") }
    }

    response.protocols["wireguard"]?.let { item ->
        if (!item.success) { avisar("wireguard", item.error ?: "sem sucesso"); return@let }
        val conf = item.wireGuardConf ?: item.raw?.optString("conf")?.takeIf { it.isNotBlank() }
        if (conf == null) { avisar("wireguard", "resposta sem campo \"conf\""); return@let }
        wireGuardManager.importConfig(nomeBase, conf)
            .onSuccess { algumaImportacao = true }
            .onFailure { avisar("wireguard", it.message ?: "erro ao interpretar o .conf") }
    }

    response.protocols["openvpn"]?.let { item ->
        if (!item.success) { avisar("openvpn", item.error ?: "sem sucesso"); return@let }
        val conf = item.raw?.optString("conf")?.takeIf { it.isNotBlank() }
        if (conf == null) { avisar("openvpn", "resposta sem campo \"conf\""); return@let }
        runCatching {
            val config = saveOpenVpnConfig(context, nomeBase, conf)
            openVpnManager.addProfile(config)
        }.onSuccess { algumaImportacao = true }
            .onFailure { avisar("openvpn", it.message ?: "erro ao salvar o .ovpn") }
    }

    response.protocols["ssh"]?.let { item ->
        if (!item.success) { avisar("ssh", item.error ?: "sem sucesso"); return@let }
        val raw = item.raw
        if (raw == null) { avisar("ssh", "resposta vazia"); return@let }
        // Tenta reconhecer variações de nome de campo — formato exato ainda não
        // confirmado contra uma resposta real do automcore (ver ressalva no topo).
        val host = raw.optString("host").ifBlank { raw.optString("server") }.ifBlank { raw.optString("ip") }
        val porta = raw.optString("port").ifBlank { raw.optString("porta") }.ifBlank { "22" }
        val usuarioSsh = raw.optString("username").ifBlank { raw.optString("usuario") }.ifBlank { raw.optString("login") }
        val senhaSsh = raw.optString("password").ifBlank { raw.optString("senha") }
        if (host.isBlank() || usuarioSsh.isBlank()) {
            avisar("ssh", "não achei host/usuário nos campos esperados — confirmar formato real com o painel")
            return@let
        }
        runCatching {
            sshManager.saveProfile(
                SshConnectionConfig(
                    connectionName = nomeBase,
                    server = host,
                    port = porta,
                    username = usuarioSsh,
                    authMethod = SshAuthMethod.PASSWORD,
                    password = senhaSsh
                )
            )
        }.onSuccess { algumaImportacao = true }
            .onFailure { avisar("ssh", it.message ?: "erro ao salvar o perfil") }
    }

    if (!algumaImportacao) {
        throw PanelException("Nenhum protocolo pôde ser importado. " + (avisos.firstOrNull() ?: "Verifique a config do painel."))
    }

    return avisos
}
