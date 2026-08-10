package com.autombot.client.protocols.openvpn

import android.content.Context
import com.autombot.client.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

enum class OpenVpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ManagedOpenVpnConnection(
    val config: OpenVpnConnectionConfig,
    val status: OpenVpnStatus = OpenVpnStatus.DISCONNECTED,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastError: String? = null
)

/**
 * Gerenciador de perfis OpenVPN. A conexão real vive no AutomBotVpnService porque
 * OpenVPN assume a TUN do Android inteira; este manager mantém perfis/estado de UI
 * e faz a pequena ponte de intenção necessária para diferenciar uma parada automática
 * do roteador de protocolos de um desligamento solicitado pelo usuário.
 */
class OpenVpnTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("autombot_openvpn", Context.MODE_PRIVATE)

    private val _connections = MutableStateFlow<List<ManagedOpenVpnConnection>>(emptyList())
    val connections: StateFlow<List<ManagedOpenVpnConnection>> = _connections

    companion object {
        @Volatile private var activeInstance: OpenVpnTunnelManager? = null
        @Volatile private var explicitDisconnectRequested: Boolean = false

        /** Chamado pelo AutomBotVpnService quando o estado real do OpenVPN muda. */
        fun reportStateChange(connectionName: String, connected: Boolean, error: String?) {
            if (connected) explicitDisconnectRequested = false
            val instance = activeInstance ?: return
            instance._connections.update { current ->
                current.map {
                    if (it.config.connectionName == connectionName) {
                        it.copy(
                            status = when {
                                connected -> OpenVpnStatus.CONNECTED
                                error != null -> OpenVpnStatus.ERROR
                                else -> OpenVpnStatus.DISCONNECTED
                            },
                            lastError = error,
                            rxBytes = if (connected) it.rxBytes else 0L,
                            txBytes = if (connected) it.txBytes else 0L
                        )
                    } else it
                }
            }
            if (error != null) {
                AppLog.log("Erro na conexão OpenVPN \"$connectionName\": $error", AppLog.Level.ERROR)
            }
        }

        /** Chamado pelo AutomBotVpnService a cada atualização de BYTECOUNT. */
        fun reportBytes(connectionName: String, rx: Long, tx: Long) {
            val instance = activeInstance ?: return
            instance._connections.update { current ->
                current.map {
                    if (it.config.connectionName == connectionName) it.copy(rxBytes = rx, txBytes = tx) else it
                }
            }
        }

        /**
         * ACTION_STOP também é emitido automaticamente pelo roteador dos protocolos
         * SOCKS da MainActivity. OpenVPN não possui porta SOCKS, então o Service só
         * deve aceitar esse STOP se a tela OpenVPN marcou antes uma intenção explícita.
         */
        fun consumeExplicitDisconnectRequest(): Boolean {
            val requested = explicitDisconnectRequested
            explicitDisconnectRequested = false
            return requested
        }

        fun clearExplicitDisconnectRequest() {
            explicitDisconnectRequested = false
        }
    }

    init {
        activeInstance = this
        loadPersisted()
    }

    fun addProfile(config: OpenVpnConnectionConfig) {
        _connections.update { current ->
            if (current.any { it.config.connectionName == config.connectionName }) {
                current.map {
                    if (it.config.connectionName == config.connectionName) {
                        it.copy(config = config, lastError = null)
                    } else it
                }
            } else {
                current + ManagedOpenVpnConnection(config)
            }
        }
        persist()
    }

    fun removeProfile(connectionName: String) {
        val file = _connections.value
            .firstOrNull { it.config.connectionName == connectionName }
            ?.config
            ?.configFile(appContext)
        _connections.update { current -> current.filterNot { it.config.connectionName == connectionName } }
        persist()
        runCatching { file?.delete() }
    }

    /** Só marca conectando; a conexão real é disparada pelo AutomBotVpnService. */
    fun markConnecting(connectionName: String) {
        explicitDisconnectRequested = false
        _connections.update { current ->
            current.map {
                if (it.config.connectionName == connectionName) {
                    it.copy(status = OpenVpnStatus.CONNECTING, lastError = null)
                } else it
            }
        }
    }

    /**
     * Deve ser chamado pela tela imediatamente antes de enviar ACTION_STOP.
     * Assim o Service distingue o clique do usuário de um STOP automático.
     */
    fun requestDisconnect(connectionName: String) {
        explicitDisconnectRequested = true
        _connections.update { current ->
            current.map {
                if (it.config.connectionName == connectionName) {
                    it.copy(
                        status = OpenVpnStatus.DISCONNECTED,
                        rxBytes = 0L,
                        txBytes = 0L,
                        lastError = null
                    )
                } else it
            }
        }
    }

    fun markDisconnected(connectionName: String) {
        _connections.update { current ->
            current.map {
                if (it.config.connectionName == connectionName) {
                    it.copy(
                        status = OpenVpnStatus.DISCONNECTED,
                        rxBytes = 0L,
                        txBytes = 0L,
                        lastError = null
                    )
                } else it
            }
        }
    }

    private fun persist() {
        val array = JSONArray()
        _connections.value.forEach { array.put(it.config.toJson()) }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    private fun loadPersisted() {
        val raw = prefs.getString("profiles", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val loaded = (0 until array.length()).map { i ->
                ManagedOpenVpnConnection(config = openVpnConnectionConfigFromJson(array.getJSONObject(i)))
            }
            _connections.value = loaded
        }
    }
}
