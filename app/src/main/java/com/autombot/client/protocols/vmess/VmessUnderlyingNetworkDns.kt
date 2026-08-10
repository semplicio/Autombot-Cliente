package com.autombot.client.protocols.vmess

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolve o host externo do servidor VMess pela rede fisica subjacente, nunca pela
 * VPN criada pelo proprio AutomBot.
 *
 * Sem isso, depois que o TUN sobe o OkHttp pode tentar resolver o dominio do servidor
 * VMess usando o DNS roteado pela propria VPN. Isso cria um bootstrap circular:
 * para abrir o VMess precisa resolver o servidor, mas para resolver pelo tunel precisa
 * primeiro abrir o VMess.
 *
 * Network.getAllByName() amarra explicitamente a consulta DNS a uma Network Android
 * nao-VPN (Wi-Fi/dados moveis), preservando no OkHttp o hostname original para TLS/SNI
 * e Host. Os IPs sao mantidos em cache curto para evitar repetir DNS em cada WebSocket.
 */
class VmessUnderlyingNetworkDns(context: Context) : Dns {
    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("hostname vazio")

        val now = android.os.SystemClock.elapsedRealtime()
        cache[hostname]?.let { cached ->
            if (cached.expiresAtMs > now && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
            cache.remove(hostname, cached)
        }

        var lastError: Throwable? = null
        for (network in underlyingNetworks()) {
            try {
                val addresses = network.getAllByName(hostname).toList()
                if (addresses.isNotEmpty()) {
                    cache[hostname] = CacheEntry(
                        addresses = addresses,
                        expiresAtMs = now + CACHE_TTL_MS
                    )
                    return addresses
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }

        // Ultimo recurso. Em condicoes normais nao chegamos aqui; mantemos o fallback
        // para aparelhos onde o fabricante nao expoe corretamente a rede subjacente.
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (systemError: Throwable) {
            val error = UnknownHostException(
                "Nao foi possivel resolver $hostname pela rede fisica fora da VPN"
            )
            if (lastError != null) error.addSuppressed(lastError)
            error.addSuppressed(systemError)
            throw error
        }
    }

    private fun underlyingNetworks(): List<Network> {
        data class Candidate(
            val network: Network,
            val validated: Boolean,
            val wifi: Boolean,
            val cellular: Boolean
        )

        return connectivityManager.allNetworks
            .mapNotNull { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return@mapNotNull null
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return@mapNotNull null
                }

                Candidate(
                    network = network,
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { it.validated }
                    .thenByDescending { it.wifi }
                    .thenByDescending { it.cellular }
            )
            .map { it.network }
    }

    private companion object {
        const val CACHE_TTL_MS = 60_000L
    }
}
