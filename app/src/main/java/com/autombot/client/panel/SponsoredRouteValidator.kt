package com.autombot.client.panel

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Confirma o upgrade WebSocket real antes de promover uma rota patrocinada. */
internal object SponsoredRouteValidator {
    private const val VALIDATION_TIMEOUT_MS = 7_000L

    suspend fun selectConnectHost(route: ProtocolRoute, endpoint: SponsoredEndpoint?): String? {
        val host = route.host ?: endpoint?.domain ?: return null
        val port = route.port ?: endpoint?.tcpPort ?: 443
        val path = route.path?.takeIf { it.isNotBlank() } ?: "/"
        val bootstrapIps = endpoint?.bootstrapIps.orEmpty()
        return selectConnectHost(host, port, path, route.tls, bootstrapIps)
    }

    suspend fun selectConnectHost(
        host: String,
        port: Int,
        path: String,
        tls: Boolean,
        bootstrapIps: List<String>
    ): String? {
        for (bootstrapIp in bootstrapIps.distinct()) {
            if (isWebSocketReachable(host, port, path, tls, listOf(bootstrapIp))) {
                return bootstrapIp
            }
        }
        return if (isWebSocketReachable(host, port, path, tls, emptyList())) host else null
    }

    suspend fun isWebSocketReachable(
        host: String,
        port: Int,
        path: String,
        tls: Boolean,
        bootstrapIps: List<String>
    ): Boolean = withTimeoutOrNull(VALIDATION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val socketRef = AtomicReference<WebSocket?>()
            val dns = object : Dns {
                override fun lookup(requestedHost: String): List<InetAddress> {
                    return if (requestedHost.equals(host, ignoreCase = true) && bootstrapIps.isNotEmpty()) {
                        bootstrapIps.mapNotNull { raw -> runCatching { InetAddress.getByName(raw) }.getOrNull() }
                            .ifEmpty { Dns.SYSTEM.lookup(requestedHost) }
                    } else {
                        Dns.SYSTEM.lookup(requestedHost)
                    }
                }
            }
            val client = OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val normalizedPath = if (path.startsWith('/')) path else "/$path"
            val scheme = if (tls) "wss" else "ws"
            val request = Request.Builder()
                .url("$scheme://$host:$port$normalizedPath")
                .build()

            fun complete(value: Boolean) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(value)
                }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    complete(true)
                    webSocket.cancel()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    complete(false)
                }
}
            socketRef.set(client.newWebSocket(request, listener))
            continuation.invokeOnCancellation { socketRef.get()?.cancel() }
        }
    } ?: false
}
