package com.autombot.client.panel

import android.content.Context
import com.autombot.client.protocols.trojan.TrojanTunnelManager
import com.autombot.client.protocols.vless.VlessTunnelManager
import com.autombot.client.protocols.vmess.VmessTunnelManager
import com.autombot.client.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Sincroniza o FQDN patrocinado pelo próprio endpoint patrocinado.
 *
 * O manifesto é público e não contém credenciais. A consulta usa os IPs de
 * bootstrap armazenados no último pacote, mas mantém o domínio na URL para que
 * TLS/SNI continuem corretos mesmo quando o DNS da operadora está indisponível.
 */
internal object SponsoredDomainSync {
    private const val PREFS = "autombot_sponsored_endpoint"
    private const val KEY_MANIFEST = "manifest"
    private const val MANIFEST_PATH = "/v1/dominio-patrocinado/manifesto"

    fun storeManifest(context: Context, manifest: SponsoredEndpointManifest) {
        if (manifest.revision.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANIFEST, manifest.toJson().toString())
            .apply()
    }

    fun loadManifest(context: Context): SponsoredEndpointManifest? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANIFEST, null)
            ?: return null
        return runCatching { JSONObject(raw).toSponsoredEndpointManifest() }.getOrNull()
    }

    suspend fun refresh(
        context: Context,
        vlessManager: VlessTunnelManager,
        vmessManager: VmessTunnelManager,
        trojanManager: TrojanTunnelManager
    ): Boolean {
        val cached = loadManifest(context) ?: return false
        if (!cached.enabled) return false

        var latest: SponsoredEndpointManifest? = null
        for (endpoint in cached.allEndpoints()) {
            latest = fetchManifest(endpoint)
            if (latest != null) break
        }
        val latestManifest = latest ?: return false
        if (!latestManifest.enabled || latestManifest.revision.isBlank()) return false
        val active = latestManifest.active ?: return false
        val activeDomain = active.domain.lowercase()
        val knownDomains = (cached.allEndpoints() + latestManifest.previous)
            .map { it.domain.lowercase() }
            .toSet()
        val activeConnectTargets = (active.bootstrapIps + activeDomain).map { it.lowercase() }.toSet()
        val currentEndpoints =
            vmessManager.connections.value.map {
                Triple(it.config.server.lowercase(), it.config.wsHost.lowercase(), it.config.sni.lowercase())
            } +
                vlessManager.connections.value.map {
                    Triple(it.config.server.lowercase(), it.config.wsHost.lowercase(), it.config.sni.lowercase())
                } +
                trojanManager.connections.value
                    .filter { it.config.transportType.equals("ws", ignoreCase = true) }
                    .map {
                        Triple(it.config.server.lowercase(), it.config.wsHost.lowercase(), it.config.sni.lowercase())
                    }
        val needsEndpointPromotion = currentEndpoints.any { (server, wsHost, sni) ->
            (wsHost in knownDomains || sni in knownDomains) &&
                (wsHost != activeDomain || sni != activeDomain || server !in activeConnectTargets)
        }
        if (latestManifest.revision == cached.revision && !needsEndpointPromotion) return false

        if (latestManifest.xrayEnabled) {
            val oldDomains = knownDomains
            val paths = buildSet {
                vmessManager.connections.value.forEach { conn ->
                    if (conn.config.wsHost.lowercase() in oldDomains || conn.config.sni.lowercase() in oldDomains) {
                        add(conn.config.wsPath)
                    }
                }
                vlessManager.connections.value.forEach { conn ->
                    if (conn.config.wsHost.lowercase() in oldDomains || conn.config.sni.lowercase() in oldDomains) {
                        add(conn.config.wsPath)
                    }
                }
                trojanManager.connections.value.forEach { conn ->
                    if (
                        conn.config.transportType.equals("ws", ignoreCase = true) &&
                        (conn.config.wsHost.lowercase() in oldDomains || conn.config.sni.lowercase() in oldDomains)
                    ) {
                        add(conn.config.wsPath)
                    }
                }
            }

            var validatedConnectHost: String? = null
            if (paths.isNotEmpty()) {
                for (path in paths) {
                    validatedConnectHost = SponsoredRouteValidator.selectConnectHost(
                        host = active.domain,
                        port = active.tcpPort,
                        path = path,
                        tls = true,
                        bootstrapIps = active.bootstrapIps
                    )
                    if (validatedConnectHost != null) break
                }
                if (validatedConnectHost == null) {
                    AppLog.log(
                        "Domínio patrocinado ${active.domain} recebeu nova revisão, mas não concluiu upgrade WebSocket; mantendo a configuração anterior",
                        AppLog.Level.ERROR
                    )
                    return false
                }
            }

            val connectHost = validatedConnectHost ?: active.bootstrapIps.firstOrNull() ?: active.domain
            vmessManager.connections.value
                .map { it.config }
                .filter { it.wsHost.lowercase() in oldDomains || it.sni.lowercase() in oldDomains }
                .forEach { config ->
                    vmessManager.addProfile(
                        config.copy(
                            server = connectHost,
                            port = active.tcpPort,
                            wsHost = active.domain,
                            useTls = true,
                            sni = active.domain
                        )
                    )
                }
            vlessManager.connections.value
                .map { it.config }
                .filter { it.wsHost.lowercase() in oldDomains || it.sni.lowercase() in oldDomains }
                .forEach { config ->
                    vlessManager.addProfile(
                        config.copy(
                            server = connectHost,
                            port = active.tcpPort,
                            wsHost = active.domain,
                            useTls = true,
                            sni = active.domain
                        )
                    )
                }
            trojanManager.connections.value
                .map { it.config }
                .filter {
                    it.transportType.equals("ws", ignoreCase = true) &&
                        (it.wsHost.lowercase() in oldDomains || it.sni.lowercase() in oldDomains)
                }
                .forEach { config ->
                    trojanManager.addProfile(
                        config.copy(
                            server = connectHost,
                            port = active.tcpPort,
                            sni = active.domain,
                            transportType = "ws",
                            wsHost = active.domain
                        )
                    )
                }
        }

        storeManifest(context, latestManifest)
        AppLog.log(
            "Domínio patrocinado atualizado automaticamente para ${active.domain} (${latestManifest.revision})",
            AppLog.Level.SUCCESS
        )
        return true
    }

    private suspend fun fetchManifest(endpoint: SponsoredEndpoint): SponsoredEndpointManifest? {
        val dns = object : Dns {
            override fun lookup(requestedHost: String): List<InetAddress> {
                return if (requestedHost.equals(endpoint.domain, ignoreCase = true) && endpoint.bootstrapIps.isNotEmpty()) {
                    endpoint.bootstrapIps
                        .mapNotNull { raw -> runCatching { InetAddress.getByName(raw) }.getOrNull() }
                        .ifEmpty { Dns.SYSTEM.lookup(requestedHost) }
                } else {
                    Dns.SYSTEM.lookup(requestedHost)
                }
            }
        }
        val client = OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url("https://${endpoint.domain}:${endpoint.tcpPort}$MANIFEST_PATH")
            .get()
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val parsed = if (it.isSuccessful) {
                            runCatching {
                                JSONObject(it.body?.string().orEmpty()).toSponsoredEndpointManifest()
                            }.getOrNull()
                        } else null
                        if (continuation.isActive) continuation.resume(parsed)
                    }
                }
            })
        }
    }
}
