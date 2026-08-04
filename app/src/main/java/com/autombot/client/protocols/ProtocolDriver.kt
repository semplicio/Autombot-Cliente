package com.autombot.client.protocols

/**
 * Contrato comum para qualquer protocolo de tunelamento suportado pelo app.
 * Cada protocolo (SSH, WebSocket, V2Ray, Trojan, Shadowsocks, WireGuard, VLESS+Reality,
 * OpenVPN, BadVPN...) implementa esta interface, permitindo que o nucleo (AutomBotVpnService)
 * trate todos de forma uniforme e que novos protocolos sejam plugados sem alterar o core.
 *
 * Ver SPEC.md secao 2 para a lista completa de protocolos planejados.
 */
interface ProtocolDriver {
    val id: String
    val displayName: String

    /** Valida se a configuracao fornecida (vinda do painel ou de import manual) e compativel. */
    fun supports(config: ProtocolConfig): Boolean

    /** Estabelece a conexao. Implementacao real ira interagir com o VpnService/tun fd. */
    suspend fun connect(config: ProtocolConfig): ConnectionResult

    suspend fun disconnect()
}

/** Configuracao generica de um perfil de conexao (payload, host, porta, credenciais, etc). */
data class ProtocolConfig(
    val protocolId: String,
    val host: String,
    val port: Int,
    val payload: String? = null,
    val extra: Map<String, String> = emptyMap()
)

sealed class ConnectionResult {
    data object Success : ConnectionResult()
    data class Failure(val reason: String) : ConnectionResult()
}
