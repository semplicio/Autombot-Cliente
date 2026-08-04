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
 * Gerenciador de perfis OpenVPN — guarda a lista de perfis (cada um é só um arquivo
 * .ovpn salvo, ver OpenVpnModels.kt) e o status de cada um.
 *
 * DIFERENTE dos outros 4 protocolos (SSH/VLESS/VMess/Shadowsocks/Trojan): esses todos
 * rodam sua conexão real AQUI, no próprio Manager (escopo do Activity/Application),
 * expondo um proxy SOCKS5 local que o motor de VPN consome. O OpenVPN NÃO — ele
 * controla a interface TUN inteira sozinho (é assim que o processo `openvpn` de
 * verdade funciona), e só o AutomBotVpnService (rodando de fato como VpnService) tem
 * permissão de estabelecer essa TUN ou proteger sockets. Por isso, a conexão real
 * (OpenVpnManagementClient) roda DENTRO do AutomBotVpnService, não aqui — esse
 * Manager só guarda o estado (status/tráfego) e recebe atualizações dele através do
 * padrão de "instância ativa" (ver companion object), o mesmo já usado em outros
 * lugares do projeto pra esse tipo de ponte entre Service e Activity.
 */
class OpenVpnTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("autombot_openvpn", Context.MODE_PRIVATE)

    private val _connections = MutableStateFlow<List<ManagedOpenVpnConnection>>(emptyList())
    val connections: StateFlow<List<ManagedOpenVpnConnection>> = _connections

    companion object {
        @Volatile private var activeInstance: OpenVpnTunnelManager? = null

        /** Chamado pelo AutomBotVpnService quando o estado de uma conexão OpenVPN muda de verdade. */
        fun reportStateChange(connectionName: String, connected: Boolean, error: String?) {
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

        /** Chamado pelo AutomBotVpnService a cada atualização de BYTECOUNT do processo openvpn. */
        fun reportBytes(connectionName: String, rx: Long, tx: Long) {
            val instance = activeInstance ?: return
            instance._connections.update { current ->
                current.map {
                    if (it.config.connectionName == connectionName) it.copy(rxBytes = rx, txBytes = tx) else it
                }
            }
        }
    }

    init {
        activeInstance = this
        loadPersisted()
    }

    fun addProfile(config: OpenVpnConnectionConfig) {
        _connections.update { current ->
            if (current.any { it.config.connectionName == config.connectionName }) {
                current.map { if (it.config.connectionName == config.connectionName) it.copy(config = config) else it }
            } else {
                current + ManagedOpenVpnConnection(config)
            }
        }
        persist()
    }

    fun removeProfile(connectionName: String) {
        _connections.update { current -> current.filterNot { it.config.connectionName == connectionName } }
        persist()
        val file = _connections.value.firstOrNull { it.config.connectionName == connectionName }?.config?.configFile(appContext)
        runCatching { file?.delete() }
    }

    /** Só marca "conectando" — a conexão real é disparada pelo AutomBotVpnService (ver MainActivity.kt). */
    fun markConnecting(connectionName: String) {
        _connections.update { current ->
            current.map { if (it.config.connectionName == connectionName) it.copy(status = OpenVpnStatus.CONNECTING, lastError = null) else it }
        }
    }

    fun markDisconnected(connectionName: String) {
        _connections.update { current ->
            current.map {
                if (it.config.connectionName == connectionName) it.copy(status = OpenVpnStatus.DISCONNECTED, rxBytes = 0L, txBytes = 0L)
                else it
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