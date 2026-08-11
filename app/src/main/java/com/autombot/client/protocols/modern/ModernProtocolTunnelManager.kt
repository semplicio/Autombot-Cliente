package com.autombot.client.protocols.modern

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.autombot.client.core.tun2socks.NativeTun2Socks
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
import java.net.Inet6Address
import java.net.InetAddress
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
 * Gerencia Hysteria2 e TUIC por um único núcleo sing-box.
 *
 * A identidade interna do perfil é (tipo + nome). Isso é obrigatório porque o
 * AutomBot Core pode gerar Hysteria2 e TUIC para o mesmo usuário, portanto os dois
 * links normalmente carregam o mesmo fragmento/nome sem serem o mesmo perfil.
 */
class ModernProtocolTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("autombot_modern_protocols", Context.MODE_PRIVATE)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configDir = File(appContext.filesDir, "sing-box-runtime").apply { mkdirs() }

    private val _connections = MutableStateFlow<List<ManagedModernConnection>>(emptyList())
    val connections: StateFlow<List<ManagedModernConnection>> = _connections

    /** Processos são indexados por tipo+nome, nunca somente pelo nome visível. */
    private val activeProcesses = ConcurrentHashMap<String, SingBoxProcess>()

    init {
        loadPersisted()
        managerScope.launch {
            while (isActive) {
                delay(2000)

                val deadKeys = activeProcesses.entries
                    .filter { !it.value.isAlive() }
                    .map { it.key }

                deadKeys.forEach { key ->
                    activeProcesses.remove(key)?.stop()
                    _connections.update { current ->
                        current.map { conn ->
                            if (profileKey(conn.config) == key && conn.status == ModernProtocolStatus.CONNECTED) {
                                conn.copy(
                                    status = ModernProtocolStatus.ERROR,
                                    localSocksPort = null,
                                    lastError = "O núcleo sing-box encerrou inesperadamente"
                                )
                            } else conn
                        }
                    }
                    AppLog.log("Protocolo moderno: núcleo sing-box encerrou inesperadamente ($key)", AppLog.Level.ERROR)
                }

                // O HEV é o ponto TUN comum do app. Enquanto Hysteria2/TUIC estiver
                // conectado, os bytes desse TUN pertencem à sessão moderna ativa.
                // A ponte JNI usada pelo log retorna:
                // [tx_packets, tx_bytes, rx_packets, rx_bytes].
                if (_connections.value.any { it.status == ModernProtocolStatus.CONNECTED }) {
                    runCatching { NativeTun2Socks.stats() }.getOrNull()?.let { stats ->
                        if (stats.size >= 4) {
                            val tx = stats[1].coerceAtLeast(0L)
                            val rx = stats[3].coerceAtLeast(0L)
                            _connections.update { current ->
                                current.map { conn ->
                                    if (conn.status == ModernProtocolStatus.CONNECTED) {
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

    fun addProfile(config: ModernProtocolConfig) {
        _connections.update { current ->
            val existing = current.indexOfFirst {
                it.config.type == config.type && it.config.connectionName == config.connectionName
            }
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

    fun removeProfile(type: ModernProtocolType, connectionName: String) {
        val key = profileKey(type, connectionName)
        activeProcesses.remove(key)?.stop()
        runtimeConfigFile(key).delete()
        _connections.update { current ->
            current.filterNot { it.config.type == type && it.config.connectionName == connectionName }
        }
        persist()
    }

    /** Remove somente perfis gerenciados conhecidos, sem apagar perfis manuais. */
    fun removeManagedProfiles(type: ModernProtocolType, candidateNames: Set<String>) {
        _connections.value
            .filter { it.config.type == type && it.config.connectionName in candidateNames }
            .map { it.config.connectionName }
            .distinct()
            .forEach { removeProfile(type, it) }
    }

    suspend fun connect(type: ModernProtocolType, connectionName: String) {
        val managed = _connections.value.firstOrNull {
            it.config.type == type && it.config.connectionName == connectionName
        } ?: return
        val config = managed.config
        val key = profileKey(config)

        markStatus(type, connectionName, ModernProtocolStatus.CONNECTING, null)
        AppLog.log("${config.type.displayName} \"$connectionName\": iniciando núcleo moderno", AppLog.Level.INFO)

        withContext(Dispatchers.IO) {
            try {
                // O roteador do app usa uma única porta SOCKS ativa. Só um processo
                // moderno pode ficar vivo de cada vez, independentemente do protocolo.
                activeProcesses.keys.filter { it != key }.toList().forEach { otherKey ->
                    stopProcessOnly(otherKey)
                    markStatusByKey(otherKey, ModernProtocolStatus.DISCONNECTED, null, clearPort = true)
                }

                activeProcesses.remove(key)?.stop()
                val runner = SingBoxProcess(appContext, "${config.type.displayName} \"$connectionName\"")
                if (!runner.isCoreAvailable()) {
                    throw IllegalStateException(
                        "Núcleo sing-box ausente no APK. Execute scripts/fetch_singbox_android_core.sh e gere o APK novamente."
                    )
                }

                // Solução A: resolve o endpoint do servidor ANTES de iniciar o core e
                // antes de a sessão ser anunciada como conectada ao VpnService. O
                // sing-box recebe um IP literal como server, portanto não depende de
                // DNS depois que o TUN/HEV captura o tráfego. O hostname original fica
                // preservado exclusivamente no TLS/SNI para validar o certificado.
                val runtimeConfig = resolveEndpointBeforeTunnel(config)

                val localPort = findFreePort()
                val runtimeFile = runtimeConfigFile(key)
                runtimeFile.parentFile?.mkdirs()
                runtimeFile.writeText(SingBoxConfigFactory.build(runtimeConfig, localPort).toString(2))

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

                runtimeFile.delete()
                activeProcesses[key] = runner
                _connections.update { current ->
                    current.map { conn ->
                        if (conn.config.type == type && conn.config.connectionName == connectionName) {
                            conn.copy(
                                status = ModernProtocolStatus.CONNECTED,
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
                    "${config.type.displayName} \"$connectionName\": conectado — proxy local 127.0.0.1:$localPort" +
                        (version?.let { " ($it)" } ?: ""),
                    AppLog.Level.SUCCESS
                )
            } catch (e: Exception) {
                runtimeConfigFile(key).delete()
                activeProcesses.remove(key)?.stop()
                val detail = e.message ?: e.javaClass.simpleName
                markStatus(type, connectionName, ModernProtocolStatus.ERROR, detail, clearPort = true)
                AppLog.log("Erro na conexão ${config.type.displayName} \"$connectionName\": $detail", AppLog.Level.ERROR)
            }
        }
    }

    suspend fun disconnect(type: ModernProtocolType, connectionName: String) = withContext(Dispatchers.IO) {
        val key = profileKey(type, connectionName)
        stopProcessOnly(key)
        markStatus(type, connectionName, ModernProtocolStatus.DISCONNECTED, null, clearPort = true, clearStats = true)
        AppLog.log("${type.displayName} \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    fun coreAvailable(): Boolean = SingBoxProcess(appContext, "sing-box").isCoreAvailable()

    suspend fun coreVersion(): String? = SingBoxProcess(appContext, "sing-box").version()

    fun hasActiveConnection(): Boolean =
        _connections.value.any { it.status == ModernProtocolStatus.CONNECTED && it.localSocksPort != null }

    fun activeConnection(): ManagedModernConnection? =
        _connections.value.firstOrNull { it.status == ModernProtocolStatus.CONNECTED && it.localSocksPort != null }

    /**
     * Resolve o hostname na rede física, preferindo IPv4, e cria uma cópia SOMENTE
     * para execução. O perfil persistido continua guardando o domínio e será
     * resolvido novamente a cada conexão, então mudança de IP da VPS não exige
     * republicar perfis pelo painel.
     */
    private fun resolveEndpointBeforeTunnel(config: ModernProtocolConfig): ModernProtocolConfig {
        val originalHost = config.server.trim().removePrefix("[").removeSuffix("]")
        numericIpOrNull(originalHost)?.let { literal ->
            return if (literal == config.server) config else config.copy(server = literal)
        }

        AppLog.log(
            "${config.type.displayName} \"${config.connectionName}\": resolvendo $originalHost pela rede física antes de subir a VPN",
            AppLog.Level.INFO
        )

        val resolvedIp = resolveOnPhysicalNetwork(originalHost)
            ?: throw IllegalStateException(
                "Não foi possível resolver $originalHost pela rede física antes de iniciar a VPN"
            )

        val tlsName = config.tlsServerName.ifBlank { originalHost }
        AppLog.log(
            "${config.type.displayName} \"${config.connectionName}\": endpoint pré-resolvido $originalHost -> $resolvedIp; TLS/SNI mantido em $tlsName",
            AppLog.Level.SUCCESS
        )

        return config.copy(
            server = resolvedIp,
            tlsServerName = tlsName
        )
    }

    /**
     * Tenta explicitamente redes não-VPN com capacidade de Internet. Isso evita que
     * uma TUN já existente (por troca/reconexão) seja usada para resolver justamente
     * o endpoint necessário para reconstruir o túnel.
     */
    private fun resolveOnPhysicalNetwork(host: String): String? {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val candidates = LinkedHashSet<Network>()
        connectivityManager.activeNetwork?.let { candidates += it }
        connectivityManager.allNetworks.forEach { candidates += it }

        var fallbackAddress: InetAddress? = null

        for (network in candidates) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

            val addresses = runCatching { network.getAllByName(host) }.getOrNull() ?: continue
            val preferred = preferredAddress(addresses) ?: continue

            // Rede validada tem prioridade. Se o Android ainda não marcou VALIDATED
            // (troca recente Wi-Fi/4G), guardamos a resposta como fallback.
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return normalizedHostAddress(preferred)
            }
            if (fallbackAddress == null) fallbackAddress = preferred
        }

        fallbackAddress?.let { return normalizedHostAddress(it) }

        // Último recurso para aparelhos/ROMs que não expõem a rede subjacente em
        // allNetworks. No fluxo normal esta chamada ainda ocorre antes de subir o TUN.
        return runCatching { InetAddress.getAllByName(host) }
            .getOrNull()
            ?.let(::preferredAddress)
            ?.let(::normalizedHostAddress)
    }

    private fun preferredAddress(addresses: Array<InetAddress>): InetAddress? =
        addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull { it is Inet6Address }
            ?: addresses.firstOrNull()

    private fun normalizedHostAddress(address: InetAddress): String =
        address.hostAddress.orEmpty().substringBefore('%')

    /** Reconhece IP literal sem disparar uma consulta DNS. */
    private fun numericIpOrNull(host: String): String? {
        if (host.contains(':')) {
            // O campo server já chega sem porta; ':' aqui representa IPv6 literal.
            return host.takeIf { it.matches(Regex("^[0-9A-Fa-f:.%]+$")) }?.substringBefore('%')
        }

        val parts = host.split('.')
        if (parts.size != 4) return null
        if (parts.any { part ->
                part.isEmpty() ||
                    part.length > 3 ||
                    part.any { !it.isDigit() } ||
                    part.toIntOrNull()?.let { it in 0..255 } != true
            }
        ) return null
        return host
    }

    private fun stopProcessOnly(key: String) {
        activeProcesses.remove(key)?.stop()
        runtimeConfigFile(key).delete()
    }

    private fun markStatus(
        type: ModernProtocolType,
        name: String,
        status: ModernProtocolStatus,
        error: String?,
        clearPort: Boolean = false,
        clearStats: Boolean = false
    ) {
        _connections.update { current ->
            current.map { conn ->
                if (conn.config.type == type && conn.config.connectionName == name) {
                    conn.copy(
                        status = status,
                        localSocksPort = if (clearPort) null else conn.localSocksPort,
                        rxBytes = if (clearStats) 0L else conn.rxBytes,
                        txBytes = if (clearStats) 0L else conn.txBytes,
                        lastError = error
                    )
                } else conn
            }
        }
    }

    private fun markStatusByKey(
        key: String,
        status: ModernProtocolStatus,
        error: String?,
        clearPort: Boolean = false
    ) {
        _connections.update { current ->
            current.map { conn ->
                if (profileKey(conn.config) == key) {
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

    private fun profileKey(config: ModernProtocolConfig): String =
        profileKey(config.type, config.connectionName)

    private fun profileKey(type: ModernProtocolType, name: String): String =
        "${type.id}:${name}"

    private fun runtimeConfigFile(key: String): File =
        File(configDir, "profile-${key.hashCode().toUInt().toString(16)}.json")

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
