package com.autombot.client.protocols.trojan

import android.content.Context
import com.autombot.client.core.tun2socks.NativeTun2Socks
import com.autombot.client.protocols.modern.SingBoxProcess
import com.autombot.client.protocols.vmess.VmessUnderlyingNetworkDns
import com.autombot.client.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.Inet4Address
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

enum class TrojanStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ManagedTrojanConnection(
    val config: TrojanConnectionConfig,
    val status: TrojanStatus = TrojanStatus.DISCONNECTED,
    val localSocksPort: Int? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastError: String? = null
)

/**
 * Gerencia Trojan usando o núcleo sing-box já empacotado no AutomBot.
 *
 * A UI, os perfis trojan:// e o roteamento TUN/HEV continuam iguais:
 *
 *   TUN -> HEV/tun2socks -> mixed SOCKS local do sing-box -> Trojan remoto
 *
 * O transporte Trojan TCP/UDP/TLS próprio permanece no repositório como fallback
 * histórico, mas deixa de participar do caminho de produção.
 */
class TrojanTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("autombot_trojan", Context.MODE_PRIVATE)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configDir = File(appContext.filesDir, "sing-box-trojan-runtime").apply { mkdirs() }
    private val underlyingDns = VmessUnderlyingNetworkDns(appContext)

    private val _connections = MutableStateFlow<List<ManagedTrojanConnection>>(emptyList())
    val connections: StateFlow<List<ManagedTrojanConnection>> = _connections

    private val activeProcesses = ConcurrentHashMap<String, SingBoxProcess>()

    init {
        loadPersisted()

        managerScope.launch {
            while (isActive) {
                delay(2000)

                val dead = activeProcesses.entries
                    .filter { !it.value.isAlive() }
                    .map { it.key }

                dead.forEach { name ->
                    activeProcesses.remove(name)?.stop()
                    runtimeConfigFile(name).delete()
                    _connections.update { current ->
                        current.map { conn ->
                            if (conn.config.connectionName == name && conn.status == TrojanStatus.CONNECTED) {
                                conn.copy(
                                    status = TrojanStatus.ERROR,
                                    localSocksPort = null,
                                    lastError = "O núcleo sing-box do Trojan encerrou inesperadamente"
                                )
                            } else conn
                        }
                    }
                    AppLog.log(
                        "Trojan \"$name\": núcleo sing-box encerrou inesperadamente",
                        AppLog.Level.ERROR
                    )
                }

                if (_connections.value.any { it.status == TrojanStatus.CONNECTED }) {
                    runCatching { NativeTun2Socks.stats() }.getOrNull()?.let { stats ->
                        if (stats.size >= 4) {
                            val tx = stats[1].coerceAtLeast(0L)
                            val rx = stats[3].coerceAtLeast(0L)
                            _connections.update { current ->
                                current.map { conn ->
                                    if (conn.status == TrojanStatus.CONNECTED) {
                                        conn.copy(rxBytes = rx, txBytes = tx)
                                    } else conn
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun addProfile(config: TrojanConnectionConfig) {
        _connections.update { current ->
            if (current.any { it.config.connectionName == config.connectionName }) {
                current.map {
                    if (it.config.connectionName == config.connectionName) {
                        it.copy(config = config, lastError = null)
                    } else it
                }
            } else {
                current + ManagedTrojanConnection(config)
            }
        }
        persist()
    }

    fun removeProfile(connectionName: String) {
        activeProcesses.remove(connectionName)?.stop()
        runtimeConfigFile(connectionName).delete()
        _connections.update { current -> current.filterNot { it.config.connectionName == connectionName } }
        persist()
    }

    suspend fun connect(connectionName: String) {
        val managed = _connections.value.firstOrNull {
            it.config.connectionName == connectionName
        } ?: return
        val config = managed.config

        markStatus(connectionName, TrojanStatus.CONNECTING)
        AppLog.log(
            "Trojan \"$connectionName\": iniciando núcleo sing-box (${config.describeTransport()})",
            AppLog.Level.INFO
        )

        withContext(Dispatchers.IO) {
            try {
                activeProcesses.keys.filter { it != connectionName }.toList().forEach { other ->
                    activeProcesses.remove(other)?.stop()
                    runtimeConfigFile(other).delete()
                    markStatus(
                        other,
                        TrojanStatus.DISCONNECTED,
                        clearPort = true,
                        clearStats = true
                    )
                }

                activeProcesses.remove(connectionName)?.stop()
                runtimeConfigFile(connectionName).delete()

                val runner = SingBoxProcess(appContext, "Trojan \"$connectionName\"")
                if (!runner.isCoreAvailable()) {
                    throw IllegalStateException(
                        "Núcleo sing-box ausente no APK. Gere novamente o aplicativo com o núcleo moderno incluído."
                    )
                }

                val localPort = findFreePort()
                val serverAddress = resolveServerAddress(config.server)
                val runtimeFile = runtimeConfigFile(connectionName)

                runtimeFile.parentFile?.mkdirs()
                runtimeFile.writeText(
                    TrojanSingBoxConfigFactory.build(
                        config = config,
                        localPort = localPort,
                        serverAddress = serverAddress
                    ).toString(2)
                )

                val check = runner.checkConfig(runtimeFile)
                if (check.exitCode != 0) {
                    runtimeFile.delete()
                    throw IllegalArgumentException(
                        check.output.lineSequence().lastOrNull()?.take(500)
                            ?: "Configuração Trojan rejeitada pelo sing-box"
                    )
                }

                runner.start(runtimeFile)
                if (!runner.awaitLocalPort(localPort)) {
                    runner.stop()
                    runtimeFile.delete()
                    throw IllegalStateException("sing-box não abriu o proxy Trojan local em 12s")
                }

                runtimeFile.delete()
                activeProcesses[connectionName] = runner

                _connections.update { current ->
                    current.map { conn ->
                        if (conn.config.connectionName == connectionName) {
                            conn.copy(
                                status = TrojanStatus.CONNECTED,
                                localSocksPort = localPort,
                                rxBytes = 0L,
                                txBytes = 0L,
                                lastError = null
                            )
                        } else conn
                    }
                }

                val version = runner.version()?.substringBefore('\n')
                AppLog.log(
                    "Trojan \"$connectionName\": conectado — sing-box SOCKS5 em 127.0.0.1:$localPort" +
                        " (servidor ${config.server} -> $serverAddress, SNI ${config.sni.ifBlank { config.server }})" +
                        (version?.let { " [$it]" } ?: ""),
                    AppLog.Level.SUCCESS
                )
            } catch (e: Exception) {
                runtimeConfigFile(connectionName).delete()
                activeProcesses.remove(connectionName)?.stop()
                markError(connectionName, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun disconnect(connectionName: String) = withContext(Dispatchers.IO) {
        activeProcesses.remove(connectionName)?.stop()
        runtimeConfigFile(connectionName).delete()
        markStatus(
            connectionName,
            TrojanStatus.DISCONNECTED,
            clearPort = true,
            clearStats = true
        )
        AppLog.log("Trojan \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    fun coreAvailable(): Boolean =
        SingBoxProcess(appContext, "Trojan").isCoreAvailable()

    private fun resolveServerAddress(server: String): String {
        require(server.isNotBlank()) { "Servidor Trojan vazio" }

        if (IPV4_REGEX.matches(server) || server.contains(':')) {
            return server
        }

        val addresses = underlyingDns.lookup(server)
        val selected = addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull()
            ?: throw java.net.UnknownHostException(
                "Nenhum endereço retornado para o servidor Trojan $server"
            )

        return selected.hostAddress
            ?: throw java.net.UnknownHostException(
                "Endereço inválido retornado para o servidor Trojan $server"
            )
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun runtimeConfigFile(connectionName: String): File {
        val safeName = connectionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(configDir, "trojan-$safeName.json")
    }

    private fun markStatus(
        name: String,
        status: TrojanStatus,
        clearPort: Boolean = false,
        clearStats: Boolean = false
    ) {
        _connections.update { current ->
            current.map { conn ->
                if (conn.config.connectionName == name) {
                    conn.copy(
                        status = status,
                        localSocksPort = if (clearPort) null else conn.localSocksPort,
                        rxBytes = if (clearStats) 0L else conn.rxBytes,
                        txBytes = if (clearStats) 0L else conn.txBytes,
                        lastError = if (status == TrojanStatus.ERROR) conn.lastError else null
                    )
                } else conn
            }
        }
    }

    private fun markError(name: String, error: String) {
        AppLog.log("Erro na conexão Trojan \"$name\": $error", AppLog.Level.ERROR)
        _connections.update { current ->
            current.map { conn ->
                if (conn.config.connectionName == name) {
                    conn.copy(
                        status = TrojanStatus.ERROR,
                        localSocksPort = null,
                        lastError = error
                    )
                } else conn
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
                ManagedTrojanConnection(config = trojanConnectionConfigFromJson(array.getJSONObject(i)))
            }
            _connections.value = loaded
        }
    }

    private companion object {
        val IPV4_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
    }
}
