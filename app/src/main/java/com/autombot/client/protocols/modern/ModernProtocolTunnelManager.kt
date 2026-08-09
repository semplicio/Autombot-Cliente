package com.autombot.client.protocols.modern

import android.content.Context
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
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

enum class ModernProtocolStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ManagedModernConnection(
    val config: ModernProtocolConfig,
    val status: ModernProtocolStatus = ModernProtocolStatus.DISCONNECTED,
    val localSocksPort: Int? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastError: String? = null
)

/**
 * Gerencia Hysteria2 e TUIC através de um único núcleo sing-box.
 *
 * O sing-box expõe um inbound Mixed/SOCKS local. O AutomBotVpnService/HEV já
 * sabe encaminhar o TUN para uma porta SOCKS5 local, portanto os protocolos
 * modernos entram no pipeline existente sem um segundo VpnService.
 */
class ModernProtocolTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("autombot_modern_protocols", Context.MODE_PRIVATE)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configDir = File(appContext.filesDir, "sing-box-runtime").apply { mkdirs() }

    private val _connections = MutableStateFlow<List<ManagedModernConnection>>(emptyList())
    val connections: StateFlow<List<ManagedModernConnection>> = _connections

    private val activeProcesses = ConcurrentHashMap<String, SingBoxProcess>()

    init {
        loadPersisted()
        managerScope.launch {
            while (isActive) {
                delay(2000)
                val dead = activeProcesses.entries.filter { !it.value.isAlive() }.map { it.key }
                dead.forEach { name ->
                    activeProcesses.remove(name)?.stop()
                    _connections.update { current ->
                        current.map { conn ->
                            if (conn.config.connectionName == name && conn.status == ModernProtocolStatus.CONNECTED) {
                                conn.copy(
                                    status = ModernProtocolStatus.ERROR,
                                    localSocksPort = null,
                                    lastError = "O núcleo sing-box encerrou inesperadamente"
                                )
                            } else conn
                        }
                    }
                    AppLog.log("Protocolo moderno \"$name\": núcleo sing-box encerrou inesperadamente", AppLog.Level.ERROR)
                }
            }
        }
    }

    fun addProfile(config: ModernProtocolConfig) {
        _connections.update { current ->
            val existing = current.indexOfFirst { it.config.connectionName == config.connectionName }
            if (existing >= 0) {
                current.mapIndexed { index, old ->
                    if (index == existing) old.copy(config = config, lastError = null) else old
                }
            } else {
                current + ManagedModernConnection(config)
            }
        }
        persist()
    }

    fun importUri(uri: String): ModernProtocolConfig {
        val parsed = parseModernProtocolUri(uri)
        addProfile(parsed)
        AppLog.log("${parsed.type.displayName} \"${parsed.connectionName}\": perfil importado", AppLog.Level.SUCCESS)
        return parsed
    }

    fun removeProfile(connectionName: String) {
        activeProcesses.remove(connectionName)?.stop()
        runtimeConfigFile(connectionName).delete()
        _connections.update { current -> current.filterNot { it.config.connectionName == connectionName } }
        persist()
    }

    suspend fun connect(connectionName: String) {
        val managed = _connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return
        val config = managed.config
        markStatus(connectionName, ModernProtocolStatus.CONNECTING, null)
        AppLog.log("${config.type.displayName} \"$connectionName\": iniciando núcleo moderno", AppLog.Level.INFO)

        withContext(Dispatchers.IO) {
            try {
                // O roteador do app trabalha com uma única porta SOCKS ativa. Evita
                // deixar dois processos modernos vivos competindo pela seleção.
                activeProcesses.keys.filter { it != connectionName }.toList().forEach { other ->
                    stopProcessOnly(other)
                    markStatus(other, ModernProtocolStatus.DISCONNECTED, null, clearPort = true)
                }

                activeProcesses.remove(connectionName)?.stop()
                val runner = SingBoxProcess(appContext, "${config.type.displayName} \"$connectionName\"")
                if (!runner.isCoreAvailable()) {
                    throw IllegalStateException(
                        "Núcleo sing-box ausente no APK. Execute scripts/fetch_singbox_android_core.sh e gere o APK novamente."
                    )
                }

                val localPort = findFreePort()
                val runtimeFile = runtimeConfigFile(connectionName)
                runtimeFile.parentFile?.mkdirs()
                runtimeFile.writeText(SingBoxConfigFactory.build(config, localPort).toString(2))

                val check = runner.checkConfig(runtimeFile)
                if (check.exitCode != 0) {
                    runtimeFile.delete()
                    throw IllegalArgumentException(
                        check.output.lineSequence().lastOrNull()?.take(300)
                            ?: "Configuração rejeitada pelo sing-box"
                    )
                }

                runner.start(runtimeFile)
                if (!runner.awaitLocalPort(localPort)) {
                    runner.stop()
                    runtimeFile.delete()
                    throw IllegalStateException("sing-box não abriu o proxy local em 12s")
                }

                // O core já leu o JSON; remove o arquivo temporário para não manter
                // credenciais duplicadas no armazenamento além do perfil persistido.
                runtimeFile.delete()
                activeProcesses[connectionName] = runner
                _connections.update { current ->
                    current.map { conn ->
                        if (conn.config.connectionName == connectionName) {
                            conn.copy(
                                status = ModernProtocolStatus.CONNECTED,
                                localSocksPort = localPort,
                                lastError = null
                            )
                        } else conn
                    }
                }
                val version = runner.version()?.substringBefore('\n')
                AppLog.log(
                    "${config.type.displayName} \"$connectionName\": conectado — proxy local 127.0.0.1:$localPort" +
                        (version?.let { " ($it)" } ?: ""),
                    AppLog.Level.SUCCESS
                )
            } catch (e: Exception) {
                runtimeConfigFile(connectionName).delete()
                activeProcesses.remove(connectionName)?.stop()
                val detail = e.message ?: e.javaClass.simpleName
                markStatus(connectionName, ModernProtocolStatus.ERROR, detail, clearPort = true)
                AppLog.log("Erro na conexão ${config.type.displayName} \"$connectionName\": $detail", AppLog.Level.ERROR)
            }
        }
    }

    suspend fun disconnect(connectionName: String) = withContext(Dispatchers.IO) {
        stopProcessOnly(connectionName)
        markStatus(connectionName, ModernProtocolStatus.DISCONNECTED, null, clearPort = true)
        AppLog.log("Protocolo moderno \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    fun coreAvailable(): Boolean = SingBoxProcess(appContext, "sing-box").isCoreAvailable()

    suspend fun coreVersion(): String? = SingBoxProcess(appContext, "sing-box").version()

    private fun stopProcessOnly(name: String) {
        activeProcesses.remove(name)?.stop()
        runtimeConfigFile(name).delete()
    }

    private fun markStatus(
        name: String,
        status: ModernProtocolStatus,
        error: String?,
        clearPort: Boolean = false
    ) {
        _connections.update { current ->
            current.map { conn ->
                if (conn.config.connectionName == name) {
                    conn.copy(
                        status = status,
                        localSocksPort = if (clearPort) null else conn.localSocksPort,
                        lastError = error
                    )
                } else conn
            }
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun runtimeConfigFile(name: String): File =
        File(configDir, "profile-${name.hashCode().toUInt().toString(16)}.json")

    private fun persist() {
        val array = JSONArray()
        _connections.value.forEach { array.put(it.config.toJson()) }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    private fun loadPersisted() {
        val raw = prefs.getString("profiles", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            _connections.value = (0 until array.length()).map { index ->
                ManagedModernConnection(modernProtocolConfigFromJson(array.getJSONObject(index)))
            }
        }.onFailure {
            AppLog.log("Protocolos modernos: falha ao carregar perfis salvos (${it.message})", AppLog.Level.ERROR)
        }
    }
}
