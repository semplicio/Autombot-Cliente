package com.autombot.client.protocols.shadowsocks

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
 * Gerencia Shadowsocks usando o núcleo sing-box já empacotado no AutomBot.
 *
 * A UI, o formato ss://, os perfis e o roteamento do aplicativo continuam iguais:
 *
 *   TUN -> HEV/tun2socks -> mixed SOCKS local do sing-box -> Shadowsocks remoto
 *
 * O codec AEAD TCP/UDP próprio e o Socks5Server antigo permanecem no repositório
 * como fallback histórico, mas não fazem mais parte do caminho de produção.
 */
class ShadowsocksTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("autombot_shadowsocks", Context.MODE_PRIVATE)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configDir = File(appContext.filesDir, "sing-box-shadowsocks-runtime").apply { mkdirs() }
    private val underlyingDns = VmessUnderlyingNetworkDns(appContext)

    private val _connections = MutableStateFlow<List<ManagedShadowsocksConnection>>(emptyList())
    val connections: StateFlow<List<ManagedShadowsocksConnection>> = _connections

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
                            if (conn.config.connectionName == name && conn.status == ShadowsocksStatus.CONNECTED) {
                                conn.copy(
                                    status = ShadowsocksStatus.ERROR,
                                    localSocksPort = null,
                                    lastError = "O núcleo sing-box do Shadowsocks encerrou inesperadamente"
                                )
                            } else conn
                        }
                    }
                    AppLog.log(
                        "Shadowsocks \"$name\": núcleo sing-box encerrou inesperadamente",
                        AppLog.Level.ERROR
                    )
                }

                if (_connections.value.any { it.status == ShadowsocksStatus.CONNECTED }) {
                    runCatching { NativeTun2Socks.stats() }.getOrNull()?.let { stats ->
                        if (stats.size >= 4) {
                            val tx = stats[1].coerceAtLeast(0L)
                            val rx = stats[3].coerceAtLeast(0L)
                            _connections.update { current ->
                                current.map { conn ->
                                    if (conn.status == ShadowsocksStatus.CONNECTED) {
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

    fun addProfile(config: ShadowsocksConnectionConfig) {
        _connections.update { current ->
            if (current.any { it.config.connectionName == config.connectionName }) {
                current.map {
                    if (it.config.connectionName == config.connectionName) {
                        it.copy(config = config, lastError = null)
                    } else it
                }
            } else {
                current + ManagedShadowsocksConnection(config)
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

        markStatus(connectionName, ShadowsocksStatus.CONNECTING)
        AppLog.log(
            "Shadowsocks \"$connectionName\": iniciando núcleo sing-box (${config.describeTransport()})",
            AppLog.Level.INFO
        )

        withContext(Dispatchers.IO) {
            try {
                activeProcesses.keys.filter { it != connectionName }.toList().forEach { other ->
                    activeProcesses.remove(other)?.stop()
                    runtimeConfigFile(other).delete()
                    markStatus(
                        other,
                        ShadowsocksStatus.DISCONNECTED,
                        clearPort = true,
                        clearStats = true
                    )
                }

                activeProcesses.remove(connectionName)?.stop()
                runtimeConfigFile(connectionName).delete()

                val runner = SingBoxProcess(appContext, "Shadowsocks \"$connectionName\"")
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
                    ShadowsocksSingBoxConfigFactory.build(
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
                            ?: "Configuração Shadowsocks rejeitada pelo sing-box"
                    )
                }

                runner.start(runtimeFile)
                if (!runner.awaitLocalPort(localPort)) {
                    runner.stop()
                    runtimeFile.delete()
                    throw IllegalStateException("sing-box não abriu o proxy Shadowsocks local em 12s")
                }

                runtimeFile.delete()
                activeProcesses[connectionName] = runner

                _connections.update { current ->
                    current.map { conn ->
                        if (conn.config.connectionName == connectionName) {
                            conn.copy(
                                status = ShadowsocksStatus.CONNECTED,
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
                    "Shadowsocks \"$connectionName\": conectado — sing-box SOCKS5 em 127.0.0.1:$localPort" +
                        " (servidor ${config.server} -> $serverAddress)" +
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
            ShadowsocksStatus.DISCONNECTED,
            clearPort = true,
            clearStats = true
        )
        AppLog.log("Shadowsocks \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    fun coreAvailable(): Boolean =
        SingBoxProcess(appContext, "Shadowsocks").isCoreAvailable()

    private fun resolveServerAddress(server: String): String {
        require(server.isNotBlank()) { "Servidor Shadowsocks vazio" }

        if (IPV4_REGEX.matches(server) || server.contains(':')) {
            return server
        }

        val addresses = underlyingDns.lookup(server)
        val selected = addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull()
            ?: throw java.net.UnknownHostException(
                "Nenhum endereço retornado para o servidor Shadowsocks $server"
            )

        return selected.hostAddress
            ?: throw java.net.UnknownHostException(
                "Endereço inválido retornado para o servidor Shadowsocks $server"
            )
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun runtimeConfigFile(connectionName: String): File {
        val safeName = connectionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(configDir, "shadowsocks-$safeName.json")
    }

    private fun markStatus(
        name: String,
        status: ShadowsocksStatus,
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
                        lastError = if (status == ShadowsocksStatus.ERROR) conn.lastError else null
                    )
                } else conn
            }
        }
    }

    private fun markError(name: String, error: String) {
        AppLog.log("Erro na conexão Shadowsocks \"$name\": $error", AppLog.Level.ERROR)
        _connections.update { current ->
            current.map { conn ->
                if (conn.config.connectionName == name) {
                    conn.copy(
                        status = ShadowsocksStatus.ERROR,
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
                ManagedShadowsocksConnection(config = shadowsocksConnectionConfigFromJson(array.getJSONObject(i)))
            }
            _connections.value = loaded
        }
    }

    private companion object {
        val IPV4_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
    }
}
