package com.autombot.client.protocols.vless

import android.content.Context
import com.autombot.client.core.AutomBotVpnService
import com.autombot.client.protocols.ssh.Socks5Server
import com.autombot.client.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket

enum class VlessStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ManagedVlessConnection(
    val config: VlessConnectionConfig,
    val status: VlessStatus = VlessStatus.DISCONNECTED,
    val localSocksPort: Int? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastError: String? = null
)

/**
 * Nucleo real da conexao VLESS (WebSocket + protocolo proprio + servidor SOCKS5
 * local) — mesma estrutura do SshTunnelManager.kt, adaptada. Ver avisos de risco em
 * VlessProtocol.kt e VlessTransport.kt: protocolo implementado a partir da
 * especificacao, nao testado contra servidor real ainda.
 */
class VlessTunnelManager(context: Context) {
    private val prefs = context.getSharedPreferences("autombot_vless", Context.MODE_PRIVATE)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connections = MutableStateFlow<List<ManagedVlessConnection>>(emptyList())
    val connections: StateFlow<List<ManagedVlessConnection>> = _connections

    private val activeSocksServers = mutableMapOf<String, Socks5Server>()

    init {
        loadPersisted()
        managerScope.launch {
            while (isActive) {
                delay(2000)
                _connections.update { current ->
                    current.map { conn ->
                        val server = activeSocksServers[conn.config.connectionName]
                        if (server != null && conn.status == VlessStatus.CONNECTED) {
                            conn.copy(rxBytes = server.totalRx.get(), txBytes = server.totalTx.get())
                        } else conn
                    }
                }
            }
        }
    }

    fun addProfile(config: VlessConnectionConfig) {
        _connections.update { current ->
            if (current.any { it.config.connectionName == config.connectionName }) {
                current.map { if (it.config.connectionName == config.connectionName) it.copy(config = config) else it }
            } else {
                current + ManagedVlessConnection(config)
            }
        }
        persist()
    }

    fun removeProfile(connectionName: String) {
        if (_connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == VlessStatus.CONNECTED) {
            managerScope.launch { disconnect(connectionName) }
        }
        _connections.update { current -> current.filterNot { it.config.connectionName == connectionName } }
        persist()
    }

    suspend fun connect(connectionName: String) {
        val managed = _connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return
        val config = managed.config

        markStatus(connectionName, VlessStatus.CONNECTING)
        AppLog.log("VLESS \"$connectionName\": iniciando conexão (${config.describeTransport()})", AppLog.Level.INFO)

        withContext(Dispatchers.IO) {
            try {
                val socksPort = findFreePort()
                val socksServer = Socks5Server(
                    socksPort,
                    onConnectRequest = { destHost, destPort ->
                        openVlessChannel(config, connectionName, destHost, destPort)
                    },
                    onUdpAssociateRequest = { destHost, destPort, onIncoming ->
                        openVlessUdpSession(config, connectionName, destHost, destPort, onIncoming)
                    },
                    protectDatagramSocket = { socket -> AutomBotVpnService.protectDatagramSocket(socket) },
                    logPrefix = "VLESS \"$connectionName\""
                )
                socksServer.start()
                activeSocksServers[connectionName] = socksServer

                _connections.update { current ->
                    current.map {
                        if (it.config.connectionName == connectionName)
                            it.copy(status = VlessStatus.CONNECTED, localSocksPort = socksPort, lastError = null)
                        else it
                    }
                }
                AppLog.log("VLESS \"$connectionName\": conectado — proxy SOCKS5 em 127.0.0.1:$socksPort", AppLog.Level.SUCCESS)
            } catch (e: Exception) {
                markError(connectionName, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun disconnect(connectionName: String) {
        markStatus(connectionName, VlessStatus.DISCONNECTED)
        activeSocksServers.remove(connectionName)?.stop()
        _connections.update { current ->
            current.map {
                if (it.config.connectionName == connectionName) it.copy(localSocksPort = null, rxBytes = 0L, txBytes = 0L)
                else it
            }
        }
        AppLog.log("VLESS \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    private fun openVlessChannel(
        config: VlessConnectionConfig,
        connectionName: String,
        destHost: String,
        destPort: Int
    ): Pair<InputStream, OutputStream>? {
        return try {
            VlessTransport.connect(
                config = config,
                destHost = destHost,
                destPort = destPort,
                protectSocket = { socket -> AutomBotVpnService.protectSocket(socket) }
            )
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.log("VLESS \"$connectionName\": falha ao abrir canal para $destHost:$destPort — $detail", AppLog.Level.ERROR)
            android.util.Log.w("VlessTunnelManager", "Falha ao abrir canal VLESS para $destHost:$destPort: $detail", e)
            null
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun openVlessUdpSession(
        config: VlessConnectionConfig,
        connectionName: String,
        destHost: String,
        destPort: Int,
        onIncoming: (ByteArray) -> Unit
    ): com.autombot.client.protocols.ssh.UdpBackendSession? {
        return try {
            VlessUdpTransport.openSession(
                config = config,
                destHost = destHost,
                destPort = destPort,
                protectSocket = { socket -> AutomBotVpnService.protectSocket(socket) },
                onIncoming = onIncoming
            )
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.log("VLESS \"$connectionName\": falha ao abrir UDP para $destHost:$destPort — $detail", AppLog.Level.ERROR)
            null
        }
    }

    private fun markStatus(name: String, status: VlessStatus) {
        _connections.update { current ->
            current.map { if (it.config.connectionName == name) it.copy(status = status) else it }
        }
    }

    private fun markError(name: String, error: String) {
        AppLog.log("Erro na conexão VLESS \"$name\": $error", AppLog.Level.ERROR)
        _connections.update { current ->
            current.map { if (it.config.connectionName == name) it.copy(status = VlessStatus.ERROR, lastError = error) else it }
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
                ManagedVlessConnection(config = vlessConnectionConfigFromJson(array.getJSONObject(i)))
            }
            _connections.value = loaded
        }
    }
}