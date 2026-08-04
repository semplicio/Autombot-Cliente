package com.autombot.client.protocols.wireguard

import com.autombot.client.protocols.ConnectionResult
import com.autombot.client.protocols.ProtocolConfig
import com.autombot.client.protocols.ProtocolDriver

/**
 * Adapta o WireGuardManager ao contrato generico ProtocolDriver (ver
 * com.autombot.client.protocols.ProtocolDriver), permitindo que o nucleo trate
 * WireGuard igual a qualquer outro protocolo plugavel no futuro.
 *
 * config.host      = nome do tunel
 * config.extra["configText"] = conteudo completo do arquivo .conf (wg-quick)
 */
class WireGuardDriver(private val manager: WireGuardManager) : ProtocolDriver {

    override val id: String = "wireguard"
    override val displayName: String = "WireGuard"

    override fun supports(config: ProtocolConfig): Boolean = config.protocolId == id

    override suspend fun connect(config: ProtocolConfig): ConnectionResult {
        val configText = config.extra["configText"]
            ?: return ConnectionResult.Failure("Configuracao WireGuard (.conf) ausente")

        val importResult = manager.importConfig(config.host, configText)
        if (importResult.isFailure) {
            return ConnectionResult.Failure(
                importResult.exceptionOrNull()?.message ?: "Erro ao importar configuracao WireGuard"
            )
        }

        val tunnel = manager.tunnels.value.firstOrNull { it.name == config.host }
            ?: return ConnectionResult.Failure("Tunel nao encontrado apos importacao")

        return try {
            manager.toggle(tunnel)
            ConnectionResult.Success
        } catch (e: Exception) {
            ConnectionResult.Failure(e.message ?: "Falha ao conectar ao tunel WireGuard")
        }
    }

    override suspend fun disconnect() {
        manager.tunnels.value.forEach { tunnel ->
            if (tunnel.state.name == "UP") manager.toggle(tunnel)
        }
    }
}
