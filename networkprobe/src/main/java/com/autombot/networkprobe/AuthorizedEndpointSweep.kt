package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

enum class EndpointSweepState {
    OPEN,
    REFUSED,
    TIMEOUT,
    ERROR
}

data class AuthorizedEndpointSweepItem(
    val sources: List<String>,
    val host: String,
    val address: String,
    val port: Int,
    val configured: Boolean,
    val state: EndpointSweepState,
    val detail: String
)

data class AuthorizedEndpointSweepReport(
    val items: List<AuthorizedEndpointSweepItem>
) {
    val reachable: List<AuthorizedEndpointSweepItem>
        get() = items.filter { it.state == EndpointSweepState.OPEN || it.state == EndpointSweepState.REFUSED }

    val openListeners: List<AuthorizedEndpointSweepItem>
        get() = items.filter { it.state == EndpointSweepState.OPEN }

    fun toJsonArray(): JSONArray {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("sources", JSONArray(item.sources))
                    .put("host", item.host)
                    .put("address", item.address)
                    .put("port", item.port)
                    .put("configured", item.configured)
                    .put("state", item.state.name.lowercase())
                    .put("detail", item.detail)
            )
        }
        return array
    }

    fun toText(): String = buildString {
        appendLine("VARREDURA AUTORIZADA — ENDPOINTS DO AUTOMBOT CORE")
        appendLine("A varredura usa somente IPs e domínios presentes no perfil vinculado e um catálogo TCP limitado; não enumera a Internet, sub-redes ou domínios de terceiros.")
        appendLine()

        if (items.isEmpty()) {
            appendLine("Nenhum endpoint TCP pôde ser avaliado.")
            return@buildString
        }

        val grouped = items.groupBy { it.host }
        grouped.forEach { (host, hostItems) ->
            val sources = hostItems.flatMap { it.sources }.distinct()
            appendLine("$host — ${sources.joinToString()}")
            hostItems
                .filter { it.state == EndpointSweepState.OPEN || it.state == EndpointSweepState.REFUSED }
                .sortedWith(compareBy<AuthorizedEndpointSweepItem> { it.port }.thenBy { it.address })
                .forEach { item ->
                    val status = if (item.state == EndpointSweepState.OPEN) "LISTENER ACESSÍVEL" else "CAMINHO ACESSÍVEL / SEM LISTENER"
                    appendLine("  ${item.address}:${item.port} — $status${if (item.configured) " — porta do perfil" else ""}")
                }
            val reachableCount = hostItems.count { it.state == EndpointSweepState.OPEN || it.state == EndpointSweepState.REFUSED }
            if (reachableCount == 0) appendLine("  nenhum caminho TCP confirmado no catálogo desta execução")
            appendLine()
        }
    }.trimEnd()
}

class AuthorizedEndpointSweepEngine(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun run(snapshot: CoreProfileSnapshot): AuthorizedEndpointSweepReport = withContext(Dispatchers.IO) {
        val network = selectPhysicalNetwork() ?: return@withContext AuthorizedEndpointSweepReport(emptyList())
        val targets = collectTargets(snapshot).take(MAX_HOSTS)
        val output = mutableListOf<AuthorizedEndpointSweepItem>()

        targets.forEach { target ->
            val addresses = runCatching {
                network.getAllByName(target.host)
                    .distinctBy { it.hostAddress }
                    .take(MAX_ADDRESSES_PER_HOST)
            }.getOrDefault(emptyList())

            if (addresses.isEmpty()) return@forEach

            val ports = buildPorts(target).take(MAX_PORTS_PER_HOST)
            ports.forEach { port ->
                val testAllAddresses = port in ALL_ADDRESS_PORTS || port in target.configuredPorts
                val selectedAddresses = if (testAllAddresses) addresses else addresses.take(1)

                selectedAddresses.forEach { address ->
                    val attempt = tcpAttempt(network, address, port)
                    output += AuthorizedEndpointSweepItem(
                        sources = target.sources.toList(),
                        host = target.host,
                        address = address.hostAddress ?: target.host,
                        port = port,
                        configured = port in target.configuredPorts,
                        state = attempt.first,
                        detail = attempt.second
                    )
                }
            }
        }

        AuthorizedEndpointSweepReport(
            output.distinctBy { listOf(it.host.lowercase(), it.address, it.port.toString()).joinToString("|") }
        )
    }

    private data class Target(
        val host: String,
        val sources: LinkedHashSet<String> = linkedSetOf(),
        val configuredPorts: LinkedHashSet<Int> = linkedSetOf()
    )

    private fun collectTargets(snapshot: CoreProfileSnapshot): List<Target> {
        val targets = linkedMapOf<String, Target>()

        fun add(hostValue: String?, source: String, ports: List<Int> = emptyList()) {
            val host = hostValue?.trim().orEmpty()
            if (host.isBlank()) return
            val key = host.lowercase()
            val target = targets.getOrPut(key) { Target(host = host) }
            target.sources += source
            ports.filter { it in 1..65535 }.forEach { target.configuredPorts += it }
        }

        add(snapshot.publicIp, "IP público")

        snapshot.protocols.forEach { protocol ->
            val tcpPorts = if (protocol.transport.equals("udp", ignoreCase = true)) emptyList() else protocol.ports
            add(protocol.host, "${protocol.type.uppercase()} public", tcpPorts)
            if (!protocol.originHost.isNullOrBlank()) {
                add(
                    protocol.originHost,
                    "${protocol.type.uppercase()} origin",
                    protocol.originPort?.let { listOf(it) }.orEmpty()
                )
            }
        }

        return targets.values.toList()
    }

    private fun buildPorts(target: Target): List<Int> {
        val ports = linkedSetOf<Int>()
        target.configuredPorts.forEach { ports += it }
        DISCOVERY_PORTS.forEach { ports += it }
        if (isIpLiteral(target.host)) DIRECT_SERVICE_PORTS.forEach { ports += it }
        return ports.filter { it in 1..65535 }
    }

    private fun tcpAttempt(network: Network, address: InetAddress, port: Int): Pair<EndpointSweepState, String> {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), SWEEP_TIMEOUT_MS)
            }
            EndpointSweepState.OPEN to "conexão TCP estabelecida"
        } catch (_: SocketTimeoutException) {
            EndpointSweepState.TIMEOUT to "timeout após ${SWEEP_TIMEOUT_MS} ms"
        } catch (error: ConnectException) {
            val text = error.message.orEmpty()
            if (text.contains("refused", ignoreCase = true) || text.contains("recus", ignoreCase = true)) {
                EndpointSweepState.REFUSED to "host alcançado; não há listener TCP aceitando"
            } else {
                EndpointSweepState.ERROR to text.ifBlank { error.javaClass.simpleName }
            }
        } catch (error: Exception) {
            EndpointSweepState.ERROR to (error.message ?: error.javaClass.simpleName)
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.all { it.isDigit() || it == '.' } || host.contains(':')

    private fun selectPhysicalNetwork(): Network? {
        val candidates = LinkedHashSet<Network>()
        connectivity.activeNetwork?.let { candidates += it }
        connectivity.allNetworks.forEach { candidates += it }

        return candidates.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            Triple(
                network,
                network == connectivity.activeNetwork,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }.sortedWith(
            compareByDescending<Triple<Network, Boolean, Boolean>> { it.second }
                .thenByDescending { it.third }
        ).firstOrNull()?.first
    }

    private companion object {
        const val SWEEP_TIMEOUT_MS = 900
        const val MAX_HOSTS = 8
        const val MAX_ADDRESSES_PER_HOST = 4
        const val MAX_PORTS_PER_HOST = 14

        val DISCOVERY_PORTS = listOf(80, 443, 8080, 8081, 8443)
        val ALL_ADDRESS_PORTS = setOf(80, 443, 8080, 8443)
        val DIRECT_SERVICE_PORTS = listOf(22, 109, 1080, 2222, 3128, 9443)
    }
}
