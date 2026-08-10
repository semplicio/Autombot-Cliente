package com.autombot.client.protocols.vmess

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

enum class VmessStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ManagedVmessConnection(
    val config: VmessConnectionConfig,
    val status: VmessStatus = VmessStatus.DISCONNECTED,
    val localSocksPort: Int? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastError: String? = null
)

/**
 * Nucleo real da conexao VMess (WebSocket + protocolo AEAD proprio + servidor SOCKS5
 * local) — mesma estrutura do VlessTunnelManager.kt/SshTunnelManager.kt.
 */
class VmessTunnelManager(context: Context) {
    private val prefs = context.getSharedPreferences("autombot_vmess", Context.MODE_PRIVATE)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connections = MutableStateFlow<List<ManagedVmessConnection>>(emptyList())
    val connections: StateFlow<List<ManagedVmessConnection>> = _connections

    private val activeSocksServers = mutableMapOf<String, Socks5Server>()

    // O navegador pode abrir dezenas de conexoes TCP ao mesmo tempo. Criar um
    // OkHttpClient por canal VMess criava um pool/dispatcher/task runner completo para
    // cada uma delas. Uma instancia compartilhada e o modelo recomendado pelo OkHttp
    // e reduz bastante o custo de navegacao com muitos recursos paralelos.
    private val vmessSocketProtector: (java.net.Socket) -> Boolean =
        { socket -> AutomBotVpnService.protectSocket(socket) }
    private val sharedVmessHttpClient = VmessTransport.createClient(vmessSocketProtector)

    init {
        loadPersisted()
        managerScope.launch {
            while (isActive) {
                delay(2000)
                _connections.update { current ->
                    current.map { conn ->
                        val server = activeSocksServers[conn.config.connectionName]
                        if (server != null && conn.status == VmessStatus.CONNECTED) {
                            conn.copy(rxBytes = server.totalRx.get(), txBytes = server.totalTx.get())
                        } else conn
                    }
                }
            }
        }
    }

    fun addProfile(config: VmessConnectionConfig) {
        _connections.update { current ->
            if (current.any { it.config.connectionName == config.connectionName }) {
                current.map { if (it.config.connectionName == config.connectionName) it.copy(config = config) else it }
            } else {
                current + ManagedVmessConnection(config)
            }
        }
        persist()
    }

    fun removeProfile(connectionName: String) {
        if (_connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == VmessStatus.CONNECTED) {
            managerScope.launch { disconnect(connectionName) }
        }
        _connections.update { current -> current.filterNot { it.config.connectionName == connectionName } }
        persist()
    }

    suspend fun connect(connectionName: String) {
        val managed = _connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return
        val config = managed.config

        markStatus(connectionName, VmessStatus.CONNECTING)
        AppLog.log("VMess \"$connectionName\": iniciando conexao (${config.describeTransport()})", AppLog.Level.INFO)

        withContext(Dispatchers.IO) {
            try {
                val socksPort = findFreePort()
                val socksServer = Socks5Server(
                    socksPort,
                    onConnectRequest = { destHost, destPort ->
                        openVmessChannel(config, connectionName, destHost, destPort)
                    },
                    onUdpAssociateRequest = { destHost, destPort, onIncoming ->
                        openVmessUdpSession(config, connectionName, destHost, destPort, onIncoming)
                    },
                    protectDatagramSocket = { socket -> AutomBotVpnService.protectDatagramSocket(socket) },
                    logPrefix = "VMess \"$connectionName\""
                )
                socksServer.start()
                activeSocksServers[connectionName] = socksServer

                _connections.update { current ->
                    current.map {
                        if (it.config.connectionName == connectionName)
                            it.copy(status = VmessStatus.CONNECTED, localSocksPort = socksPort, lastError = null)
                        else it
                    }
                }
                AppLog.log("VMess \"$connectionName\": conectado — proxy SOCKS5 em 127.0.0.1:$socksPort", AppLog.Level.SUCCESS)
            } catch (e: Exception) {
                markError(connectionName, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun disconnect(connectionName: String) {
        markStatus(connectionName, VmessStatus.DISCONNECTED)
        activeSocksServers.remove(connectionName)?.stop()
        _connections.update { current ->
            current.map {
                if (it.config.connectionName == connectionName) it.copy(localSocksPort = null, rxBytes = 0L, txBytes = 0L)
                else it
            }
        }
        AppLog.log("VMess \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    private fun openVmessChannel(
        config: VmessConnectionConfig,
        connectionName: String,
        destHost: String,
        destPort: Int
    ): Pair<InputStream, OutputStream>? {
        return try {
            VmessTransport.connect(
                config = config,
                destHost = destHost,
                destPort = destPort,
                client = sharedVmessHttpClient
            )
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.log("VMess \"$connectionName\": falha ao abrir canal para $destHost:$destPort — $detail", AppLog.Level.ERROR)
            android.util.Log.w("VmessTunnelManager", "Falha ao abrir canal VMess para $destHost:$destPort: $detail", e)
            null
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun openVmessUdpSession(
        config: VmessConnectionConfig,
        connectionName: String,
        destHost: String,
        destPort: Int,
        onIncoming: (ByteArray) -> Unit
    ): com.autombot.client.protocols.ssh.UdpBackendSession? {
        return try {
            VmessUdpTransport.openSession(
                config = config,
                destHost = destHost,
                destPort = destPort,
                protectSocket = { socket -> AutomBotVpnService.protectSocket(socket) },
                onIncoming = onIncoming
            )
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.log("VMess \"$connectionName\": falha ao abrir UDP para $destHost:$destPort — $detail", AppLog.Level.ERROR)
            null
        }
    }

    private fun markStatus(name: String, status: VmessStatus) {
        _connections.update { current ->
            current.map { if (it.config.connectionName == name) it.copy(status = status) else it }
        }
    }

    private fun markError(name: String, error: String) {
        AppLog.log("Erro na conexao VMess \"$name\": $error", AppLog.Level.ERROR)
        _connections.update { current ->
            current.map { if (it.config.connectionName == name) it.copy(status = VmessStatus.ERROR, lastError = error) else it }
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
                ManagedVmessConnection(config = vmessConnectionConfigFromJson(array.getJSONObject(i)))
            }
            _connections.value = loaded
        }
    }
}
