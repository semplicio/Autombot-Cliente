package com.autombot.client.protocols.shadowsocks

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

enum class ShadowsocksStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ManagedShadowsocksConnection(
    val config: ShadowsocksConnectionConfig,
    val status: ShadowsocksStatus = ShadowsocksStatus.DISCONNECTED,
    val localSocksPort: Int? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastError: String? = null
)

/**
 * Nucleo real da conexao Shadowsocks (TCP direto + AEAD proprio + servidor SOCKS5
 * local) — mesma estrutura do VlessTunnelManager.kt/VmessTunnelManager.kt.
 */
class ShadowsocksTunnelManager(context: Context) {
    private val prefs = context.getSharedPreferences("autombot_shadowsocks", Context.MODE_PRIVATE)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connections = MutableStateFlow<List<ManagedShadowsocksConnection>>(emptyList())
    val connections: StateFlow<List<ManagedShadowsocksConnection>> = _connections

    private val activeSocksServers = mutableMapOf<String, Socks5Server>()
    // CORRECAO: UDP do Shadowsocks usa UM socket compartilhado por conexao (nao um
    // por destino, ver ShadowsocksUdpTransport.kt) — criado sob demanda na primeira
    // vez que algum UDP aparecer, guardado aqui pra ser reaproveitado e fechado
    // junto com a conexao.
    private val activeUdpTransports = mutableMapOf<String, ShadowsocksUdpTransport>()

    init {
        loadPersisted()
        managerScope.launch {
            while (isActive) {
                delay(2000)
                _connections.update { current ->
                    current.map { conn ->
                        val server = activeSocksServers[conn.config.connectionName]
                        if (server != null && conn.status == ShadowsocksStatus.CONNECTED) {
                            conn.copy(rxBytes = server.totalRx.get(), txBytes = server.totalTx.get())
                        } else conn
                    }
                }
            }
        }
    }

    fun addProfile(config: ShadowsocksConnectionConfig) {
        _connections.update { current ->
            if (current.any { it.config.connectionName == config.connectionName }) {
                current.map { if (it.config.connectionName == config.connectionName) it.copy(config = config) else it }
            } else {
                current + ManagedShadowsocksConnection(config)
            }
        }
        persist()
    }

    fun removeProfile(connectionName: String) {
        if (_connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == ShadowsocksStatus.CONNECTED) {
            managerScope.launch { disconnect(connectionName) }
        }
        _connections.update { current -> current.filterNot { it.config.connectionName == connectionName } }
        persist()
    }

    suspend fun connect(connectionName: String) {
        val managed = _connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return
        val config = managed.config

        markStatus(connectionName, ShadowsocksStatus.CONNECTING)
        AppLog.log("Shadowsocks \"$connectionName\": iniciando conexão (${config.describeTransport()})", AppLog.Level.INFO)

        withContext(Dispatchers.IO) {
            try {
                val socksPort = findFreePort()
                val socksServer = Socks5Server(
                    socksPort,
                    onConnectRequest = { destHost, destPort ->
                        openShadowsocksChannel(config, connectionName, destHost, destPort)
                    },
                    onUdpAssociateRequest = { destHost, destPort, onIncoming ->
                        openShadowsocksUdpSession(config, connectionName, destHost, destPort, onIncoming)
                    },
                    protectDatagramSocket = { socket -> AutomBotVpnService.protectDatagramSocket(socket) }
                )
                socksServer.start()
                activeSocksServers[connectionName] = socksServer

                _connections.update { current ->
                    current.map {
                        if (it.config.connectionName == connectionName)
                            it.copy(status = ShadowsocksStatus.CONNECTED, localSocksPort = socksPort, lastError = null)
                        else it
                    }
                }
                AppLog.log("Shadowsocks \"$connectionName\": conectado — proxy SOCKS5 em 127.0.0.1:$socksPort", AppLog.Level.SUCCESS)
            } catch (e: Exception) {
                markError(connectionName, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun disconnect(connectionName: String) {
        markStatus(connectionName, ShadowsocksStatus.DISCONNECTED)
        activeSocksServers.remove(connectionName)?.stop()
        activeUdpTransports.remove(connectionName)?.close()
        _connections.update { current ->
            current.map {
                if (it.config.connectionName == connectionName) it.copy(localSocksPort = null, rxBytes = 0L, txBytes = 0L)
                else it
            }
        }
        AppLog.log("Shadowsocks \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    private fun openShadowsocksChannel(
        config: ShadowsocksConnectionConfig,
        connectionName: String,
        destHost: String,
        destPort: Int
    ): Pair<InputStream, OutputStream>? {
        return try {
            ShadowsocksTransport.connect(
                config = config,
                destHost = destHost,
                destPort = destPort,
                protectSocket = { socket -> AutomBotVpnService.protectSocket(socket) }
            )
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.log("Shadowsocks \"$connectionName\": falha ao abrir canal para $destHost:$destPort — $detail", AppLog.Level.ERROR)
            android.util.Log.w("ShadowsocksTunnelManager", "Falha ao abrir canal Shadowsocks para $destHost:$destPort: $detail", e)
            null
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Abre (ou reaproveita, se já tiver uma pra essa conexão) o transporte UDP
     * compartilhado do Shadowsocks — ver ShadowsocksUdpTransport.kt pro porquê de
     * ser compartilhado em vez de um socket por destino.
     */
    @Synchronized
    private fun openShadowsocksUdpSession(
        config: ShadowsocksConnectionConfig,
        connectionName: String,
        destHost: String,
        destPort: Int,
        onIncoming: (ByteArray) -> Unit
    ): com.autombot.client.protocols.ssh.UdpBackendSession? {
        return try {
            val transport = activeUdpTransports.getOrPut(connectionName) {
                ShadowsocksUdpTransport(config) { socket -> AutomBotVpnService.protectDatagramSocket(socket) }
            }
            transport.openSession(destHost, destPort, onIncoming)
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.log("Shadowsocks \"$connectionName\": falha ao abrir UDP para $destHost:$destPort — $detail", AppLog.Level.ERROR)
            null
        }
    }

    private fun markStatus(name: String, status: ShadowsocksStatus) {
        _connections.update { current ->
            current.map { if (it.config.connectionName == name) it.copy(status = status) else it }
        }
    }

    private fun markError(name: String, error: String) {
        AppLog.log("Erro na conexão Shadowsocks \"$name\": $error", AppLog.Level.ERROR)
        _connections.update { current ->
            current.map { if (it.config.connectionName == name) it.copy(status = ShadowsocksStatus.ERROR, lastError = error) else it }
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
                ManagedShadowsocksConnection(config = shadowsocksConnectionConfigFromJson(array.getJSONObject(i)))
            }
            _connections.value = loaded
        }
    }
}